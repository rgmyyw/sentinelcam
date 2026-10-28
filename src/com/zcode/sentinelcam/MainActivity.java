package com.zcode.sentinelcam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1;

    /** targetSdk 35+ 强制边到边：内容避让系统栏 + 深色背景配浅色图标。 */
    private void applyEdgeToEdge() {
        getWindow().setStatusBarColor(0xFF0E1420);
        getWindow().setNavigationBarColor(0xFF0E1420);
        android.view.WindowInsetsController c =
                getWindow().getDecorView().getWindowInsetsController();
        if (c != null) {
            c.setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        final View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(
                    View v, android.view.WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            }
        });
    }

    private TextView statusText, eventCount, previewHint, btnClear;
    private Button btnToggle;
    private android.view.TextureView preview;
    private Surface previewSurfaceLocal;
    private ListView eventList;

    private SentinelService svc;
    private boolean bound;
    private boolean clearArmed;
    private long clearArmedAt;
    private final EventAdapter adapter = new EventAdapter();
    private long lastFrames;
    private long lastTickAt;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long frames = SentinelService.frameCount;
            long fps = lastTickAt == 0 ? 0
                    : (frames - lastFrames) * 1000 / Math.max(1, now - lastTickAt);
            lastFrames = frames;
            lastTickAt = now;

            String state = SentinelService.lastState;
            if (SentinelService.streaming) {
                boolean hasPreview = svc != null && preview != null && preview.isAvailable();
                statusText.setText("● " + state + (hasPreview ? " · 预览中" : " · 纯采集")
                        + "\n帧率 " + fps + " fps    已采集 " + frames + " 帧    告警 "
                        + SentinelService.alertCount + " 次"
                        + "\n熄屏后帧数继续增长 = 熄屏采集正常");
            } else {
                statusText.setText("○ 哨兵未运行\n点击下方按钮启动（启动后熄屏仍持续监控）");
            }
            btnToggle.setText(SentinelService.streaming ? "停止哨兵" : "启动哨兵");

            if (svc != null) {
                eventCount.setText("共 " + svc.eventTotal() + " 条");
                adapter.replace(svc.recentEvents(30));
            }
            handler.postDelayed(this, 500);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            svc = ((SentinelService.LocalBinder) service).service();
            bound = true;
            attachPreviewIfReady();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyEdgeToEdge();
        statusText = findViewById(R.id.statusText);
        eventCount = findViewById(R.id.eventCount);
        previewHint = findViewById(R.id.previewHint);
        preview = findViewById(R.id.preview);
        eventList = findViewById(R.id.events);
        btnToggle = findViewById(R.id.btnToggle);
        btnClear = findViewById(R.id.btnClear);

        eventList.setAdapter(adapter);
        eventList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent,
                    View view, int position, long id) {
                DbHelper.EventRow r = adapter.get(position);
                if (r != null) showEventActions(r);
            }
        });
        fitPreviewAspect();
        preview.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(
                    android.graphics.SurfaceTexture st, int w, int h) {
                previewHint.setText("");
                st.setDefaultBufferSize(640, 480);
                previewSurfaceLocal = new Surface(st);
                attachPreviewIfReady();
                applyPreviewTransform();
            }
            @Override public void onSurfaceTextureSizeChanged(
                    android.graphics.SurfaceTexture st, int w, int h) {
                applyPreviewTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                if (svc != null && previewSurfaceLocal != null) {
                    svc.clearPreviewSurface(previewSurfaceLocal);
                }
                previewSurfaceLocal = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.btnLens).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc != null) svc.switchLens();
                updatePreviewMirror();
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (clearArmed && now - clearArmedAt < 3000) {
                    clearArmed = false;
                    btnClear.setText("清空");
                    btnClear.setTextColor(getColor(R.color.danger));
                    if (svc != null) svc.clearEvents();
                    adapter.replace(new DbHelper.EventRow[0]);
                } else {
                    clearArmed = true;
                    clearArmedAt = now;
                    btnClear.setText("再点确认");
                }
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (SentinelService.streaming) stopSentinel();
                else startSentinel();
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (!bound) {
            bindService(new Intent(this, SentinelService.class), conn, Context.BIND_AUTO_CREATE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(updater);
        updatePreviewMirror();
        // 只在用户上次未主动停止时自动开启；停止后保持停止
        if (!SentinelService.streaming
                && getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("armed", true)) {
            startSentinel();
        }
    }

    private void startSentinel() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("armed", true).apply();
        startIfPermitted();
        if (!bound) {
            bindService(new Intent(this, SentinelService.class), conn, Context.BIND_AUTO_CREATE);
        }
    }

    /** 有绑定时 stopService 不会真正停止服务，必须先解绑。 */
    private void stopSentinel() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("armed", false).apply();
        if (svc != null && previewSurfaceLocal != null) {
            svc.clearPreviewSurface(previewSurfaceLocal);
        }
        if (bound) {
            unbindService(conn);
            bound = false;
            svc = null;
        }
        stopService(new Intent(this, SentinelService.class));
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updater);
    }

    @Override protected void onStop() {
        super.onStop();
        if (bound) {
            unbindService(conn);
            bound = false;
            svc = null;
        }
    }

    private void attachPreviewIfReady() {
        if (svc != null && previewSurfaceLocal != null
                && previewSurfaceLocal.isValid()) {
            svc.setPreviewSurface(previewSurfaceLocal);
        }
    }

    /** 相机流 640x480（竖屏显示为 3:4），视图等比适配避免拉伸；横屏时按高度封顶。 */
    private void fitPreviewAspect() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int contentW = dm.widthPixels - (int) (dm.density * 32); // 根布局左右各 16dp
        int maxH = (int) (dm.heightPixels * 0.62);
        android.view.ViewGroup.LayoutParams lp = preview.getLayoutParams();
        lp.height = Math.min(contentW * 4 / 3, maxH);
        preview.setLayoutParams(lp);
    }

    /** 前摄预览水平镜像，符合自拍直觉。 */
    private void updatePreviewMirror() {
        applyPreviewTransform();
    }

    /**
     * 预览完整显示：缓冲 640x480 → 旋转 90° 竖显 → 等比缩放取 min（letterbox，
     * 比例不合留黑边）→ 前摄水平镜像。绝不裁剪、绝不变形。
     */
    private void applyPreviewTransform() {
        if (preview.getSurfaceTexture() == null) return;
        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return;
        boolean front = getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("lens", android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
                == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
        float s = Math.min(vw / 480f, vh / 640f);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(90, 320, 240);
        m.postScale(s, s);
        // 旋转后内容中心 = 缩放后的缓冲中心 (320s, 240s)，平移到视口中心
        m.postTranslate(vw / 2f - 320f * s, vh / 2f - 240f * s);
        if (front) m.postScale(-1f, 1f, vw / 2f, vh / 2f);
        preview.setTransform(m);
    }

    private void showEventActions(final DbHelper.EventRow r) {
        String[] items;
        if (r.marked == 1) {
            items = new String[]{"恢复告警（从此人不再豁免）", "从快照登记为认识的人", "删除此事件"};
        } else {
            items = new String[]{"标记为认识的人（命中不告警）", "删除此事件"};
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(fmt.format(new Date(r.ts)))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (r.marked == 1) {
                            if (which == 0) {
                                if (svc != null) svc.setEventMarked(r.id, 0);
                            } else if (which == 1) {
                                enrollKnown(r);
                            } else {
                                deleteEvent(r);
                            }
                        } else {
                            if (which == 0) {
                                enrollKnown(r);
                            } else {
                                deleteEvent(r);
                            }
                        }
                    }
                })
                .show();
    }

    /** 从事件快照裁人脸、算哈希、登记到认识名单。 */
    private void enrollKnown(final DbHelper.EventRow r) {
        if (r.path == null || r.path.isEmpty()) {
            android.widget.Toast.makeText(this, "该事件没有快照，无法登记",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("名字，例如：爸爸");
        new android.app.AlertDialog.Builder(this)
                .setTitle("登记认识的人")
                .setMessage("将从此快照提取人脸。命中名单的人出现时不再告警，仅记录。")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        final String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            android.widget.Toast.makeText(MainActivity.this, "名字不能为空",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        final String path = r.path;
                        final String rect = r.rect == null ? "" : r.rect;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    android.graphics.Bitmap full =
                                            android.graphics.BitmapFactory.decodeFile(path);
                                    if (full == null) throw new IllegalStateException("快照解码失败");
                                    android.graphics.RectF n = FaceUtil.parseRect(rect);
                                    android.graphics.Bitmap face;
                                    if (n != null) {
                                        float mx = (n.right - n.left) * 0.3f + 0.02f;
                                        float my = (n.bottom - n.top) * 0.3f + 0.02f;
                                        int l = Math.max(0,
                                                (int) ((n.left - mx) * full.getWidth()));
                                        int t = Math.max(0,
                                                (int) ((n.top - my) * full.getHeight()));
                                        int ri = Math.min(full.getWidth(),
                                                (int) ((n.right + mx) * full.getWidth()));
                                        int b = Math.min(full.getHeight(),
                                                (int) ((n.bottom + my) * full.getHeight()));
                                        face = (ri - l < 24 || b - t < 24) ? full
                                                : android.graphics.Bitmap.createBitmap(
                                                        full, l, t, ri - l, b - t);
                                    } else {
                                        face = full;
                                    }
                                    long[] h = FaceUtil.hashes(face);
                                    DbHelper db = new DbHelper(MainActivity.this);
                                    db.addFace(name, h);
                                    db.updateMarked(r.id, 1);
                                    db.close();
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            android.widget.Toast.makeText(MainActivity.this,
                                                    "已登记：" + name,
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                } catch (final Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            android.widget.Toast.makeText(MainActivity.this,
                                                    "登记失败：" + e.getMessage(),
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteEvent(final DbHelper.EventRow r) {
        if (svc != null) svc.deleteEvent(r);
    }

    private void startIfPermitted() {        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, SentinelService.class));
        } else {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, SentinelService.class));
        }
    }

    private class EventAdapter extends BaseAdapter {
        private final List<DbHelper.EventRow> rows = new ArrayList<>();
        private final HashMap<String, Bitmap> thumbs = new HashMap<>();

        void replace(DbHelper.EventRow[] items) {
            rows.clear();
            rows.addAll(Arrays.asList(items));
            notifyDataSetChanged();
        }

        DbHelper.EventRow get(int position) {
            return rows.get(position);
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_event, parent, false);
            }
            DbHelper.EventRow r = rows.get(position);
            ImageView thumb = v.findViewById(R.id.thumb);
            TextView time = v.findViewById(R.id.time);
            TextView detail = v.findViewById(R.id.detail);
            TextView badge = v.findViewById(R.id.badge);

            time.setText(fmt.format(new Date(r.ts)));
            detail.setText((r.face ? "人脸✓ " : "人脸－ ")
                    + (r.motion ? "运动✓ " : "运动－ ")
                    + (r.path == null || r.path.isEmpty() ? "· 无快照" : "· 有快照"));
            badge.setText(r.marked == 0 ? "陌生人·告警" : "已豁免");
            badge.setTextColor(r.marked == 0
                    ? getColor(R.color.danger) : getColor(R.color.ok));

            Bitmap bmp = null;
            if (r.path != null && !r.path.isEmpty()) {
                bmp = thumbs.get(r.path);
                if (bmp == null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 8;
                    bmp = BitmapFactory.decodeFile(r.path, o);
                    if (bmp != null) thumbs.put(r.path, bmp);
                }
            }
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
            } else {
                thumb.setBackgroundColor(0xFF26314D);
            }
            return v;
        }
    }
}
