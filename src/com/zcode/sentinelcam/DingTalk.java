package com.zcode.sentinelcam;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

/** 钉钉群机器人 webhook 推送，支持加签。 */
final class DingTalk {
    private static final String TAG = SentinelService.TAG;

    static void send(String webhook, String secret, String text, Executor executor) {
        executor.execute(new Runnable() {
            @Override public void run() {
                post(webhook, secret, text);
            }
        });
    }

    private static void post(String webhook, String secret, String text) {
        HttpURLConnection conn = null;
            try {
                String url = webhook;
                if (secret != null && !secret.isEmpty()) {
                    long ts = System.currentTimeMillis();
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                    byte[] sign = mac.doFinal((ts + "\n" + secret).getBytes(StandardCharsets.UTF_8));
                    url += (webhook.contains("?") ? "&" : "?")
                            + "timestamp=" + ts
                            + "&sign=" + URLEncoder.encode(
                                    Base64.getEncoder().encodeToString(sign), "UTF-8");
                }
                JSONObject body = new JSONObject();
                body.put("msgtype", "text");
                JSONObject t = new JSONObject();
                t.put("content", text);
                body.put("text", t);

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                String resp = slurp(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                Log.i(TAG, "dingtalk resp " + code + " " + resp);
            } catch (Exception e) {
                Log.e(TAG, "dingtalk send failed", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
    }

    private static String slurp(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private DingTalk() {}
}
