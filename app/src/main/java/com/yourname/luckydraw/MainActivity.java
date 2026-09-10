package com.yourname.luckydraw;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String ALLOWED_HOST = "fishing.gysssi.com";
    private static final String API_HOST = "https://api.cdtx.top";

    private WebView webView;
    private ProgressBar progressBar;
    private MediaPlayer mediaPlayer;
    private SharedPreferences securePrefs;
    private OkHttpClient httpClient;
    private Handler handler = new Handler(Looper.getMainLooper());

    // 全局状态
    private boolean isRunning = false;
    private boolean isLocking = false;
    private boolean isHitting = false;
    private int attemptCount = 0;
    private String orderId = "";
    private String token = "";
    private String uuid = "";
    private List<Integer> targetSeats = new ArrayList<>();
    private List<Integer> lastSeats = new ArrayList<>();
    private List<Integer> lockTargetSeats = new ArrayList<>();
    private String lockTargetVenue = "";
    private String currentOrderId = "";

    // 轮询定时器
    private Runnable autoLoopRunnable;
    private Runnable lockLoopRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        // 初始化 OkHttp
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        // 初始化 WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file://") || url.startsWith("https://" + ALLOWED_HOST)) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(ProgressBar.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? ProgressBar.VISIBLE : ProgressBar.GONE);
            }
        });

        // 注册 JS 接口
        webView.addJavascriptInterface(new JSInterface(), "AndroidBridge");

        // 加载本地页面
        webView.loadUrl("file:///android_asset/lucky.html");
    }

    // ============================================================
    // JS 接口
    // ============================================================
    public class JSInterface {
        @JavascriptInterface
        public void playHitSound() {
            runOnUiThread(() -> playHitSoundNative());
        }

        @JavascriptInterface
        public void saveSecure(String key, String value) {
            getSecurePrefs().edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String loadSecure(String key) {
            String v = getSecurePrefs().getString(key, "");
            return v == null ? "" : v;
        }

        @JavascriptInterface
        public void vibrate(int duration) {
            runOnUiThread(() -> {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(duration);
            });
        }

        // 通用 API 请求
        @JavascriptInterface
        public void apiRequest(String url, String method, String body, int callbackId) {
            new Thread(() -> {
                try {
                    String fullUrl = API_HOST + url;
                    Request.Builder builder = new Request.Builder().url(fullUrl);

                    // 添加请求头
                    if (token != null && !token.isEmpty()) {
                        builder.addHeader("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
                    }
                    if (uuid != null && !uuid.isEmpty()) {
                        builder.addHeader("uuid", uuid);
                    }
                    builder.addHeader("Form-type", "routine");
                    builder.addHeader("content-type", "application/json");
                    builder.addHeader("charset", "utf-8");
                    builder.addHeader("Referer", "https://servicewechat.com/wx8d8550109318b8f8/114/page-frame.html");
                    builder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; MI 8) AppleWebKit/537.36");
                    builder.addHeader("Accept", "application/json");

                    if ("POST".equalsIgnoreCase(method) && body != null) {
                        RequestBody requestBody = RequestBody.create(
                                MediaType.parse("application/json"), body);
                        builder.post(requestBody);
                    }

                    Request request = builder.build();
                    Response response = httpClient.newCall(request).execute();
                    String result = response.body().string();

                    final String js = "window._apiCallback(" + callbackId + ", " + escapeJson(result) + ")";
                    handler.post(() -> webView.evaluateJavascript(js, null));

                } catch (Exception e) {
                    final String js = "window._apiCallback(" + callbackId + ", '{\"error\":\"" + escapeJson(e.getMessage()) + "\"}')";
                    handler.post(() -> webView.evaluateJavascript(js, null));
                }
            }).start();
        }

        // 保存 Token/UUID
        @JavascriptInterface
        public void setAuth(String t, String u) {
            token = t;
            uuid = u;
        }

        // 读取本地 seat_map.json
        @JavascriptInterface
        public String loadSeatMap() {
            try {
                File file = new File(getFilesDir(), "seat_map.json");
                if (!file.exists()) {
                    // 从 assets 复制
                    InputStream is = getAssets().open("seat_map.json");
                    FileOutputStream fos = new FileOutputStream(file);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    is.close();
                    fos.close();
                }
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();
                return new String(data, "UTF-8");
            } catch (Exception e) {
                return "{}";
            }
        }

        // 保存 seat_map.json
        @JavascriptInterface
        public void saveSeatMap(String json) {
            try {
                File file = new File(getFilesDir(), "seat_map.json");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(json.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 启动自动刷号
        @JavascriptInterface
        public void startAutoLoop(String oid, String t, String u, String seatsJson) {
            orderId = oid;
            token = t;
            uuid = u;
            targetSeats = parseSeats(seatsJson);
            isRunning = true;
            isHitting = false;
            attemptCount = 0;
            startAutoLoopInternal();
        }

        // 停止自动刷号
        @JavascriptInterface
        public void stopAutoLoop() {
            isRunning = false;
            if (autoLoopRunnable != null) {
                handler.removeCallbacks(autoLoopRunnable);
            }
        }

        // 启动自动锁定
        @JavascriptInterface
        public void startLockLoop(String t, String u, String venue, String seatsJson) {
            token = t;
            uuid = u;
            lockTargetVenue = venue;
            lockTargetSeats = parseSeats(seatsJson);
            isLocking = true;
            startLockLoopInternal();
        }

        // 停止自动锁定
        @JavascriptInterface
        public void stopLockLoop() {
            isLocking = false;
            if (lockLoopRunnable != null) {
                handler.removeCallbacks(lockLoopRunnable);
            }
        }

        // 获取状态
        @JavascriptInterface
        public String getStatus() {
            try {
                JSONObject obj = new JSONObject();
                JSONObject stateObj = new JSONObject();
                stateObj.put("isRunning", isRunning);
                stateObj.put("isHitting", isHitting);
                stateObj.put("isLocking", isLocking);
                stateObj.put("attemptCount", attemptCount);
                stateObj.put("orderId", currentOrderId);
                stateObj.put("lastSeats", new JSONArray(lastSeats));
                obj.put("success", true);
                obj.put("state", stateObj);
                return obj.toString();
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }

    // ============================================================
    // 自动刷号轮询
    // ============================================================
    private void startAutoLoopInternal() {
        autoLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                new Thread(() -> {
                    try {
                        // 调用官方 selectSeat 接口
                        String result = callSelectSeat(orderId, token, uuid);
                        JSONObject json = new JSONObject(result);
                        if ("000".equals(json.optString("code"))) {
                            JSONArray dataArr = json.optJSONArray("data");
                            List<Integer> seats = new ArrayList<>();
                            if (dataArr != null) {
                                for (int i = 0; i < dataArr.length(); i++) {
                                    JSONObject item = dataArr.getJSONObject(i);
                                    seats.add(item.optInt("seat_number"));
                                }
                            }
                            lastSeats = seats;
                            attemptCount++;
                            final boolean hit = checkHit(seats, targetSeats);
                            if (hit) {
                                isHitting = true;
                                isRunning = false;
                                handler.post(() -> {
                                    webView.evaluateJavascript("window._onHit(" + toJsonArray(seats) + ")", null);
                                });
                                return;
                            }
                            handler.post(() -> {
                                webView.evaluateJavascript("window._onProgress(" + attemptCount + ", " + toJsonArray(seats) + ")", null);
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (isRunning) {
                        handler.postDelayed(autoLoopRunnable, 1500);
                    }
                }).start();
            }
        };
        handler.post(autoLoopRunnable);
    }

    // ============================================================
    // 自动锁定轮询
    // ============================================================
    private void startLockLoopInternal() {
        lockLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLocking) return;
                new Thread(() -> {
                    try {
                        // 查询订单列表
                        String ordersJson = callGetOrders(token, uuid);
                        JSONObject json = new JSONObject(ordersJson);
                        if ("000".equals(json.optString("code"))) {
                            JSONArray list = json.optJSONObject("data").optJSONArray("list");
                            // 筛选 status=30 且 seat_id="0"
                            JSONObject targetOrder = null;
                            for (int i = 0; i < list.length(); i++) {
                                JSONObject o = list.getJSONObject(i);
                                if (o.optInt("status") == 30
                                        && o.optJSONObject("order_ticket_item") != null
                                        && "0".equals(o.optJSONObject("order_ticket_item").optString("seat_id"))) {
                                    targetOrder = o;
                                    break;
                                }
                            }
                            if (targetOrder != null) {
                                String orderId = targetOrder.optString("order_id");
                                currentOrderId = orderId;
                                // 读取本地 seat_map.json
                                String seatMapJson = loadSeatMap();
                                JSONObject seatMap = new JSONObject(seatMapJson);
                                JSONObject venueData = seatMap.optJSONObject(lockTargetVenue);
                                if (venueData != null) {
                                    for (int seatNum : lockTargetSeats) {
                                        String seatId = venueData.optString(String.valueOf(seatNum));
                                        if (seatId == null || seatId.isEmpty()) continue;
                                        // 调用 confirmSeat
                                        String confirmResult = callConfirmSeat(orderId, seatId, token, uuid);
                                        JSONObject confirmJson = new JSONObject(confirmResult);
                                        if ("000".equals(confirmJson.optString("code"))) {
                                            isLocking = false;
                                            final int finalSeat = seatNum;
                                            final String finalOrderId = orderId;
                                            handler.post(() -> {
                                                webView.evaluateJavascript("window._onLocked(" + finalSeat + ", '" + finalOrderId + "')", null);
                                            });
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (isLocking) {
                        handler.postDelayed(lockLoopRunnable, 1500);
                    }
                }).start();
            }
        };
        handler.post(lockLoopRunnable);
    }

    // ============================================================
    // 官方 API 调用
    // ============================================================
    private String callSelectSeat(String orderId, String token, String uuid) {
        try {
            String url = API_HOST + "/v2/userApi/ticketSeat/selectSeat";
            JSONObject body = new JSONObject();
            body.put("order_id", orderId);
            body.put("is_lottery", 20);
            return httpPost(url, body.toString(), token, uuid);
        } catch (Exception e) {
            return "{\"code\":\"500\",\"msg\":\"" + e.getMessage() + "\"}";
        }
    }

    private String callConfirmSeat(String orderId, String seatId, String token, String uuid) {
        try {
            String url = API_HOST + "/v2/userApi/ticketSeat/confirmSeat";
            JSONObject body = new JSONObject();
            body.put("order_id", orderId);
            body.put("seat_id", seatId);
            return httpPost(url, body.toString(), token, uuid);
        } catch (Exception e) {
            return "{\"code\":\"500\",\"msg\":\"" + e.getMessage() + "\"}";
        }
    }

    private String callGetOrders(String token, String uuid) {
        try {
            String url = API_HOST + "/v2/userApi/order/getMyTicketOrderList?tab=10&page=1&limit=20";
            return httpGet(url, token, uuid);
        } catch (Exception e) {
            return "{\"code\":\"500\",\"msg\":\"" + e.getMessage() + "\"}";
        }
    }

    private String httpPost(String url, String body, String token, String uuid) throws IOException {
        Request.Builder builder = new Request.Builder().url(url)
                .addHeader("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token)
                .addHeader("uuid", uuid)
                .addHeader("Form-type", "routine")
                .addHeader("content-type", "application/json")
                .addHeader("charset", "utf-8")
                .addHeader("Referer", "https://servicewechat.com/wx8d8550109318b8f8/114/page-frame.html")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; MI 8) AppleWebKit/537.36")
                .post(RequestBody.create(MediaType.parse("application/json"), body));
        Response response = httpClient.newCall(builder.build()).execute();
        return response.body().string();
    }

    private String httpGet(String url, String token, String uuid) throws IOException {
        Request.Builder builder = new Request.Builder().url(url)
                .addHeader("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token)
                .addHeader("uuid", uuid)
                .addHeader("Form-type", "routine")
                .addHeader("content-type", "application/json")
                .addHeader("charset", "utf-8")
                .addHeader("Referer", "https://servicewechat.com/wx8d8550109318b8f8/114/page-frame.html")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; MI 8) AppleWebKit/537.36");
        Response response = httpClient.newCall(builder.build()).execute();
        return response.body().string();
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private List<Integer> parseSeats(String json) {
        List<Integer> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getInt(i));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private boolean checkHit(List<Integer> seats, List<Integer> targets) {
        for (int s : seats) {
            if (targets.contains(s)) return true;
        }
        return false;
    }

    private String toJsonArray(List<Integer> list) {
        JSONArray arr = new JSONArray();
        for (int i : list) arr.put(i);
        return arr.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private SharedPreferences getSecurePrefs() {
        if (securePrefs != null) return securePrefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            securePrefs = EncryptedSharedPreferences.create(
                    this, "lucky_secure_prefs", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            securePrefs = getSharedPreferences("lucky_prefs_fallback", MODE_PRIVATE);
        }
        return securePrefs;
    }

    private void playHitSoundNative() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            mediaPlayer = MediaPlayer.create(MainActivity.this, R.raw.hit_sound);
            if (mediaPlayer != null) {
                final MediaPlayer current = mediaPlayer;
                current.setOnCompletionListener(mp -> {
                    mp.release();
                    if (mediaPlayer == mp) mediaPlayer = null;
                });
                current.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        stopAutoLoop();
        stopLockLoop();
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
