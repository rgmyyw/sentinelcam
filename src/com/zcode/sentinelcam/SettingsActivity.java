package com.zcode.sentinelcam;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** 二级页面：推送与提示音、认识名单设置。 */
public class SettingsActivity extends Activity {
    private EditText webhookInput, secretInput;
    private TextView testResult, soundDrop, soundVoice;
    private android.widget.LinearLayout knownList;
    private SharedPreferences prefs;
    private DbHelper db;
    private MediaPlayer previewPlayer;
    private final Executor bg = Executors.newSingleThreadExecutor();

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyEdgeToEdge();
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        db = new DbHelper(this);
        webhookInput = findViewById(R.id.webhook);
        secretInput = findViewById(R.id.secret);
        testResult = findViewById(R.id.testResult);
        soundDrop = findViewById(R.id.soundDrop);
        soundVoice = findViewById(R.id.soundVoice);
        knownList = findViewById(R.id.knownList);
        webhookInput.setText(prefs.getString("webhook", ""));
        secretInput.setText(prefs.getString("secret", ""));

        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit()
                        .putString("webhook", webhookInput.getText().toString().trim())
                        .putString("secret", secretInput.getText().toString().trim())
                        .apply();
                testResult.setText("已保存 ✓");
            }
        });

        Button test = findViewById(R.id.btnTest);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = webhookInput.getText().toString().trim();
                if (url.isEmpty()) {
                    testResult.setText("请先填写 webhook 地址");
                    return;
                }
                prefs.edit()
                        .putString("webhook", url)
                        .putString("secret", secretInput.getText().toString().trim())
                        .apply();
                DingTalk.send(url, prefs.getString("secret", ""),
                        "哨兵相机测试推送 ✓", bg);
                testResult.setText("测试指令已发送，若配置正确钉钉群会收到消息（错误详情见日志 SentinelCam）");
            }
        });

        soundDrop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSound("drop"); }
        });
        soundVoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSound("voice"); }
        });
        refreshSoundUi();
    }

    private void chooseSound(String which) {
        prefs.edit().putString("alert_sound", which).apply();
        refreshSoundUi();
        preview(which);
    }

    private void refreshSoundUi() {
        boolean voice = "voice".equals(prefs.getString("alert_sound", "drop"));
        style(soundDrop, !voice);
        style(soundVoice, voice);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshKnownList();
    }

    private void refreshKnownList() {
        knownList.removeAllViews();
        java.util.ArrayList<DbHelper.FaceRec> faces = db.allFaces();
        if (faces.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("暂无。在主界面点某条事件 → \"标记为认识的人\" 即可登记");
            empty.setTextColor(getColor(R.color.textSec));
            empty.setTextSize(12);
            knownList.addView(empty);
            return;
        }
        int pad = (int) (getResources().getDisplayMetrics().density * 8);
        for (final DbHelper.FaceRec f : faces) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad, 0, pad);
            android.widget.TextView name = new android.widget.TextView(this);
            name.setText(f.name);
            name.setTextColor(getColor(R.color.textPri));
            name.setTextSize(14);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, lp);
            android.widget.TextView del = new android.widget.TextView(this);
            del.setText("删除");
            del.setTextColor(getColor(R.color.danger));
            del.setTextSize(12);
            del.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    db.deleteFace(f.id);
                    refreshKnownList();
                }
            });
            row.addView(del);
            knownList.addView(row);
        }
    }

    private void style(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected
                ? R.drawable.btn_primary : R.drawable.btn_dark);
        tv.setTextColor(selected ? 0xFFFFFFFF : getColor(R.color.textPri));
    }

    private void preview(String which) {
        try {
            if (previewPlayer != null) {
                previewPlayer.release();
                previewPlayer = null;
            }
            final MediaPlayer p = new MediaPlayer();
            android.content.res.AssetFileDescriptor afd = getResources().openRawResourceFd(
                    "voice".equals(which) ? R.raw.alert : R.raw.drop);
            p.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) {}
                    if (mp == previewPlayer) previewPlayer = null;
                }
            });
            previewPlayer = p;
            p.prepare();
            p.start();
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (previewPlayer != null) {
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (db != null) try { db.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
