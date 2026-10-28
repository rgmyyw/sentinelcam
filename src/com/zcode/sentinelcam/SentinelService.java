package com.zcode.sentinelcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SentinelService extends Service {
    static final String TAG = "SentinelCam";
    static final String CHANNEL = "sentinel";

    static volatile long frameCount = 0;
    static volatile long alertCount = 0;
    static volatile String lastState = "idle";
    static volatile boolean streaming = false;

    // 检测参数：运动像素占比/差值、去抖、告警冷却、启动预热
    private static final double MOTION_RATIO = 0.02;
    private static final int MOTION_DIFF = 25;
    private static final int DEBOUNCE_FRAMES = 2;
    private static final long COOLDOWN_MS = 30_000;
    private static final int WARMUP_FRAMES = 15;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder pendingBuilder;
    private volatile Surface previewSurface;
    private ImageReader detReader;
    private ImageReader snapReader;
    private int detW, detH;
    private byte[] prevLuma;
    private volatile byte[] latestJpeg;

    private PowerManager.WakeLock wakeLock;
    private MediaPlayer voicePlayer;
    private android.media.AudioFocusRequest focusRequest;
    private final Executor bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler camHandler; // 相机图像回调线程
    private int faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;

    private volatile long motionSeenUntil;
    private volatile long faceSeenUntil;
    private final android.graphics.RectF lastFaceRect = new android.graphics.RectF();
    private volatile boolean hasFaceRect;
    private int activeW, activeH;
    private int consecutivePerson;
    private long lastAlertAt;
    private DbHelper db;

    /** Activity 通过 Binder 拿到服务实例（同进程）。 */
    public class LocalBinder extends Binder {
        public SentinelService service() {
            return SentinelService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Log.i(TAG, ">>> " + intent.getAction() + " frameCount=" + frameCount);
            lastState = intent.getAction() + " @" + frameCount;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        android.os.HandlerThread ht = new android.os.HandlerThread("sentinel-cam");
        ht.start();
        camHandler = new Handler(ht.getLooper());
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "哨兵",
                NotificationManager.IMPORTANCE_LOW));
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
        db = new DbHelper(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("哨兵相机")
                .setContentText("活体探测运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(1, n);
        }
        if (wakeLock == null) {
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentinelCam:guard");
            wakeLock.acquire(60 * 60 * 1000L);
        }
        if (detReader == null) {
            openCamera();
        } else {
            Log.i(TAG, "already streaming, frameCount=" + frameCount);
        }
        return START_STICKY;
    }

    private void openCamera() {
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            // 默认前置摄像头，可通过预览卡上的"切换镜头"按钮修改
            int want = getSharedPreferences("cfg", MODE_PRIVATE)
                    .getInt("lens", CameraCharacteristics.LENS_FACING_FRONT);
            String id = null;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(cid);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) {
                    id = cid;
                    int[] modes = cc.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                    if (modes != null && modes.length > 1) {
                        for (int m : modes) {
                            if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                                faceDetectMode = m;
                                break;
                            }
                            if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) {
                                faceDetectMode = m;
                            }
                        }
                    }
                    break;
                }
            }
            if (id == null) {
                id = cm.getCameraIdList()[0];
                faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
            }
            try {
                android.graphics.Rect act = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (act != null) {
                    activeW = act.width();
                    activeH = act.height();
                }
            } catch (Exception ignored) {}
            Log.i(TAG, "camera=" + id + " faceDetectMode=" + faceDetectMode);

            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 检测流：接近 320x240 的最小 YUV
            Size det = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 320, 240);
            detW = det.getWidth();
            detH = det.getHeight();
            prevLuma = new byte[(detW / 8) * (detH / 8)];
            detReader = ImageReader.newInstance(detW, detH, ImageFormat.YUV_420_888, 2);
            detReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    onFrame(r);
                }
            }, camHandler);

            // 快照流：接近 640x480 的 JPEG（供留档 / 后续人工标记）
            try {
                Size snap = pickSize(map.getOutputSizes(ImageFormat.JPEG), 640, 480);
                snapReader = ImageReader.newInstance(
                        snap.getWidth(), snap.getHeight(), ImageFormat.JPEG, 2);
                snapReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override public void onImageAvailable(ImageReader r) {
                        grabJpeg(r);
                    }
                }, camHandler);
                Log.i(TAG, "snapshot stream " + snap.getWidth() + "x" + snap.getHeight());
            } catch (Exception e) {
                snapReader = null;
                Log.w(TAG, "JPEG stream unavailable, 快照留档降级", e);
            }
            Log.i(TAG, "detect stream " + detW + "x" + detH);

            lastState = "opening camera";
            cm.openCamera(id, bg, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    camera = c;
                    lastState = "camera opened";
                    Log.i(TAG, "camera opened");
                    rebuildSession();
                }
                @Override public void onDisconnected(CameraDevice c) {
                    streaming = false;
                    lastState = "DISCONNECTED @" + frameCount;
                    Log.e(TAG, "camera onDisconnected frameCount=" + frameCount);
                    c.close();
                }
                @Override public void onError(CameraDevice c, int error) {
                    streaming = false;
                    lastState = "ERROR " + error + " @" + frameCount;
                    Log.e(TAG, "camera onError=" + error + " frameCount=" + frameCount);
                    c.close();
                }
            });
        } catch (Exception e) {
            lastState = "open failed: " + e;
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private static Size pickSize(Size[] sizes, int tw, int th) {
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - (long) tw * th);
            if (d < bestDiff) {
                bestDiff = d;
                best = s;
            }
        }
        return best;
    }

    /** 目标面 = 检测流 + 快照流 + (可选)预览面；预览开关、息屏都会触发重建。 */
    private synchronized void rebuildSession() {
        if (camera == null) return;
        try {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
                session = null;
            }
            List<Surface> targets = new ArrayList<>();
            targets.add(detReader.getSurface());
            if (snapReader != null) targets.add(snapReader.getSurface());
            Surface pv = previewSurface;
            boolean hasPreview = pv != null && pv.isValid();
            if (hasPreview) targets.add(pv);

            pendingBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : targets) pendingBuilder.addTarget(s);
            if (faceDetectMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                pendingBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
            }

            List<OutputConfiguration> outs = new ArrayList<>();
            for (Surface s : targets) outs.add(new OutputConfiguration(s));
            SessionConfiguration cfg = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outs,
                    bg,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(pendingBuilder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override public void onCaptureCompleted(
                                                    CameraCaptureSession cs, CaptureRequest request,
                                                    TotalCaptureResult result) {
                                                try {
                                                    Face[] faces =
                                                            result.get(CaptureResult.STATISTICS_FACES);
                                                    if (faces != null && faces.length > 0) {
                                                        faceSeenUntil =
                                                                SystemClock.elapsedRealtime() + 1500;
                                                        android.graphics.Rect fb =
                                                                faces[0].getBounds();
                                                        if (activeW > 0 && activeH > 0
                                                                && fb.width() > 0) {
                                                            synchronized (lastFaceRect) {
                                                                lastFaceRect.set(
                                                                        (float) fb.left / activeW,
                                                                        (float) fb.top / activeH,
                                                                        (float) fb.right / activeW,
                                                                        (float) fb.bottom / activeH);
                                                            }
                                                            hasFaceRect = true;
                                                        }
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        },
                                        main);
                                streaming = true;
                                lastState = "streaming";
                                Log.i(TAG, "session rebuilt, targets=" + targets.size()
                                        + (previewSurface != null ? " (含预览)" : " (纯采集)"));
                            } catch (Exception e) {
                                lastState = "repeat failed: " + e;
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            lastState = "session config FAILED";
                            Log.e(TAG, "onConfigureFailed");
                        }
                    });
            camera.createCaptureSession(cfg);
        } catch (Exception e) {
            lastState = "session failed: " + e;
            Log.e(TAG, "rebuildSession failed", e);
        }
    }

    public void setPreviewSurface(Surface s) {
        previewSurface = s;
        main.post(new Runnable() {
            @Override public void run() { rebuildSession(); }
        });
    }

    public void clearPreviewSurface(Surface s) {
        if (previewSurface == s) {
            previewSurface = null;
            main.post(new Runnable() {
                @Override public void run() { rebuildSession(); }
            });
        }
    }

    /** 前/后摄切换：写偏好后整条采集链路重开。未运行时下次启动生效。 */
    public void switchLens() {
        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        int cur = p.getInt("lens", CameraCharacteristics.LENS_FACING_FRONT);
        int next = cur == CameraCharacteristics.LENS_FACING_FRONT
                ? CameraCharacteristics.LENS_FACING_BACK
                : CameraCharacteristics.LENS_FACING_FRONT;
        p.edit().putInt("lens", next).apply();
        Log.i(TAG, "switch lens -> " + (next == CameraCharacteristics.LENS_FACING_FRONT
                ? "FRONT" : "BACK"));
        main.post(new Runnable() {
            @Override public void run() { reopenCamera(); }
        });
    }

    private synchronized void reopenCamera() {
        if (camera == null) {
            if (detReader == null) openCamera(); // 未运行：下次启动生效
            return;
        }
        streaming = false;
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) {}
        try { camera.close(); } catch (Exception ignored) {}
        camera = null;
        if (detReader != null) { try { detReader.close(); } catch (Exception ignored) {} detReader = null; }
        if (snapReader != null) { try { snapReader.close(); } catch (Exception ignored) {} snapReader = null; }
        openCamera();
    }

    private void onFrame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        frameCount++;
        if (frameCount % 300 == 0) {
            Log.i(TAG, "frame #" + frameCount);
        }
        boolean motion = detectMotion(img);
        img.close();
        if (motion) {
            motionSeenUntil = SystemClock.elapsedRealtime() + 1500;
        }
        evaluate();
    }

    private void grabJpeg(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            latestJpeg = data;
        } catch (Exception e) {
            Log.e(TAG, "grab jpeg failed", e);
        } finally {
            img.close();
        }
    }

    /** Y 平面 8 步降采样帧差；前 WARMUP_FRAMES 帧只建基准。 */
    private boolean detectMotion(Image img) {
        Image.Plane p = img.getPlanes()[0];
        int rowStride = p.getRowStride();
        ByteBuffer buf = p.getBuffer();
        int step = 8;
        int cols = detW / step, rows = detH / step;
        if (prevLuma.length < cols * rows) return false;
        boolean warmup = frameCount <= WARMUP_FRAMES;
        int changed = 0, idx = 0;
        for (int y = 0; y < rows; y++) {
            int rowOff = (y * step) * rowStride;
            for (int x = 0; x < cols; x++) {
                int v = buf.get(rowOff + x * step) & 0xFF;
                if (!warmup) {
                    int d = v - (prevLuma[idx] & 0xFF);
                    if (d < 0) d = -d;
                    if (d > MOTION_DIFF) changed++;
                }
                prevLuma[idx++] = (byte) v;
            }
        }
        return !warmup && changed > idx * MOTION_RATIO;
    }

    private void evaluate() {
        long now = SystemClock.elapsedRealtime();
        // 活体判定只认人脸；运动仅作辅助信号记录，避免噪声/曝光漂移误报
        boolean person = now < faceSeenUntil;
        consecutivePerson = person ? consecutivePerson + 1 : 0;
        if (consecutivePerson >= DEBOUNCE_FRAMES && now - lastAlertAt > COOLDOWN_MS) {
            lastAlertAt = now;
            consecutivePerson = 0;
            alert(now);
        }
    }

    private void alert(final long now) {
        final boolean faceNow = now < faceSeenUntil;
        final boolean motionNow = now < motionSeenUntil;
        final byte[] snap = latestJpeg;
        final String rectStr = rectString();
        bg.execute(new Runnable() {
            @Override public void run() {
                // 认识名单匹配：命中即豁免，仅记录不告警
                if (snap != null && db != null) {
                    long[] probe = faceHashOf(snap, rectStr);
                    if (probe != null) {
                        String known = matchKnown(probe);
                        if (known != null) {
                            Log.i(TAG, ">>> 认识的人: " + known + " → 不告警，仅记录");
                            persistEventRow(now, faceNow, motionNow, snap, rectStr, 1);
                            return;
                        }
                    }
                }
                alertCount++;
                Log.w(TAG, ">>> PERSON DETECTED alert#" + alertCount
                        + " face=" + faceNow + " motion=" + motionNow + " frames=" + frameCount);
                persistEventRow(now, faceNow, motionNow, snap, rectStr, 0);

                AudioManager am = getSystemService(AudioManager.class);
                @SuppressWarnings("deprecation")
                boolean bt = am.isBluetoothA2dpOn() || am.isBluetoothScoOn();
                String text = "警告：检测到陌生人进入画面";
                if (bt) {
                    int res = alertSoundRes();
                    Log.i(TAG, "alert via 蓝牙（媒体流）sound="
                            + (res == R.raw.drop ? "drop" : "voice"));
                    playAlert(res);
                } else {
                    SharedPreferences prefs = getSharedPreferences("cfg", MODE_PRIVATE);
                    String webhook = prefs.getString("webhook", "");
                    if (webhook.isEmpty()) {
                        Log.w(TAG, "alert via 钉钉 but webhook 未配置");
                    } else {
                        Log.i(TAG, "alert via 钉钉推送");
                        DingTalk.send(webhook, prefs.getString("secret", ""),
                                text + "（哨兵相机 #" + alertCount + "）", bg);
                    }
                }
            }
        });
    }

    /** 当前人脸框（归一化 "l,t,r,b"），无人脸时返回空串。 */
    private String rectString() {
        if (!hasFaceRect) return "";
        synchronized (lastFaceRect) {
            return lastFaceRect.left + "," + lastFaceRect.top + ","
                    + lastFaceRect.right + "," + lastFaceRect.bottom;
        }
    }

    /** 从快照裁人脸区域并计算感知哈希；无人脸框时用整帧（弱匹配）。 */
    private long[] faceHashOf(byte[] jpeg, String rectStr) {
        try {
            android.graphics.Bitmap full = android.graphics.BitmapFactory
                    .decodeByteArray(jpeg, 0, jpeg.length);
            if (full == null) return null;
            android.graphics.RectF n = FaceUtil.parseRect(rectStr);
            android.graphics.Bitmap face;
            if (n != null) {
                float mx = (n.right - n.left) * 0.3f + 0.02f;
                float my = (n.bottom - n.top) * 0.3f + 0.02f;
                int l = Math.max(0, (int) ((n.left - mx) * full.getWidth()));
                int t = Math.max(0, (int) ((n.top - my) * full.getHeight()));
                int r = Math.min(full.getWidth(), (int) ((n.right + mx) * full.getWidth()));
                int b = Math.min(full.getHeight(), (int) ((n.bottom + my) * full.getHeight()));
                face = (r - l < 24 || b - t < 24) ? full
                        : android.graphics.Bitmap.createBitmap(full, l, t, r - l, b - t);
            } else {
                face = full;
            }
            return FaceUtil.hashes(face);
        } catch (Exception e) {
            Log.e(TAG, "faceHashOf failed", e);
            return null;
        }
    }

    private String matchKnown(long[] probe) {
        try {
            for (DbHelper.FaceRec f : db.allFaces()) {
                if (FaceUtil.distance(probe, f.hash) <= FaceUtil.MATCH_THRESHOLD) {
                    return f.name;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "matchKnown failed", e);
        }
        return null;
    }

    /** 已选提示音：drop=水滴（默认），voice=语音播报。 */
    private int alertSoundRes() {
        boolean voice = "voice".equals(getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("alert_sound", "drop"));
        return voice ? R.raw.alert : R.raw.drop;
    }

    /** 媒体通道播放提示音：先抢音频焦点（压过其他媒体），播完释放。 */
    private void playAlert(int rawResId) {
        try {
            final AudioManager am = getSystemService(AudioManager.class);
            if (focusRequest == null) {
                focusRequest = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
            }
            am.requestAudioFocus(focusRequest);
            if (voicePlayer != null) {
                try { voicePlayer.release(); } catch (Exception ignored) {}
                voicePlayer = null;
            }
            final MediaPlayer p = new MediaPlayer();
            android.content.res.AssetFileDescriptor afd =
                    getResources().openRawResourceFd(rawResId);
            p.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) {}
                    if (mp == voicePlayer) voicePlayer = null;
                    try { am.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) {}
                }
            });
            voicePlayer = p;
            p.prepare();
            p.start();
        } catch (Exception e) {
            Log.e(TAG, "playAlert failed", e);
        }
    }

    private void persistEventRow(final long ts, final boolean faceNow, final boolean motionNow,
            final byte[] snap, final String rectStr, final int marked) {
        final File dir = eventsDir();
        bg.execute(new Runnable() {
            @Override public void run() {
                String path = "";
                if (snap != null) {
                    try {
                        dir.mkdirs();
                        File f = new File(dir, "e" + ts + ".jpg");
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(snap);
                        fo.close();
                        path = f.getAbsolutePath();
                    } catch (Exception e) {
                        Log.e(TAG, "save snapshot failed", e);
                    }
                }
                if (db != null) db.insert(ts, path, rectStr, faceNow, motionNow, marked);
                Log.i(TAG, "event saved marked=" + marked
                        + " path=" + (path.isEmpty() ? "(无快照)" : path));
            }
        });
    }

    private File eventsDir() {
        File base = getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir();
        return new File(base, "events");
    }

    /** 供 Activity 拉取最近事件。 */
    public DbHelper.EventRow[] recentEvents(int n) {
        return db != null ? db.recent(n) : new DbHelper.EventRow[0];
    }

    public void setEventMarked(long id, int marked) {
        if (db != null) db.updateMarked(id, marked);
    }

    public void deleteEvent(final DbHelper.EventRow r) {
        if (db != null) db.delete(r.id);
        if (r.path != null && !r.path.isEmpty()) {
            bg.execute(new Runnable() {
                @Override public void run() {
                    try { new File(r.path).delete(); } catch (Exception ignored) {}
                }
            });
        }
    }

    public long eventTotal() {
        return db != null ? db.count() : 0;
    }

    /** 清空事件记录并删除快照文件。 */
    public void clearEvents() {
        if (db != null) db.clear();
        alertCount = 0;
        final File dir = eventsDir();
        bg.execute(new Runnable() {
            @Override public void run() {
                File[] fs = dir.listFiles();
                int n = 0;
                if (fs != null) {
                    for (File f : fs) if (f.delete()) n++;
                }
                Log.i(TAG, "events cleared, removed " + n + " snapshots");
            }
        });
    }

    /** TTS 引擎缺失时兜底发声：A2DP 耳机走媒体流，SCO 单耳走通话通道，都没有才外放。 */
    @SuppressWarnings("deprecation")
    @Override public void onDestroy() {
        Log.i(TAG, "service onDestroy, frames=" + frameCount + " alerts=" + alertCount);
        streaming = false;
        lastState = "destroyed, frames=" + frameCount;
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (session != null) try { session.close(); } catch (Exception ignored) {}
        if (camera != null) try { camera.close(); } catch (Exception ignored) {}
        if (detReader != null) try { detReader.close(); } catch (Exception ignored) {}
        if (snapReader != null) try { snapReader.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (voicePlayer != null) {
            try { voicePlayer.release(); } catch (Exception ignored) {}
            voicePlayer = null;
        }
        if (focusRequest != null) {
            try {
                ((AudioManager) getSystemService(AUDIO_SERVICE))
                        .abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {}
        }
        if (db != null) try { db.close(); } catch (Exception ignored) {}
        if (camHandler != null) {
            ((android.os.HandlerThread) camHandler.getLooper().getThread()).quitSafely();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
