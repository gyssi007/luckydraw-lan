package com.yourname.luckydraw;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String ALLOWED_HOST = "fishing.gysssi.com";
    private static final String API_HOST = "https://api.cdtx.top";
    private static final int REQUEST_CODE_PICK_FILE = 1001;

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

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

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
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

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

        webView.addJavascriptInterface(new JSInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/pages/lucky.html");
    }

    // ============================================================
    // 安全调用 WebView JS（统一守卫，防止 NPE）
    // ============================================================
    private void safeEvaluateJavascript(final String js) {
        handler.post(() -> {
            if (webView != null && !isFinishing() && !isDestroyed()) {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            }
        });
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
                try {
                    android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(duration);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void apiRequest(String url, String method, String body, int callbackId) {
            new Thread(() -> {
                try {
                    String fullUrl = API_HOST + url;
                    Request.Builder builder = new Request.Builder().url(fullUrl);

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

                    if ("POST".equalsIgnoreCase(method) && body != null && !body.isEmpty()) {
                        RequestBody requestBody = RequestBody.create(
                                MediaType.parse("application/json"), body);
                        builder.post(requestBody);
                    }

                    Request request = builder.build();
                    Response response = httpClient.newCall(request).execute();
                    String result = response.body().string();

                    final String js = "window._apiCallback(" + callbackId + ", " + JSONObject.quote(result) + ")";
                    safeEvaluateJavascript(js);

                } catch (Exception e) {
                    String errMsg = e.getMessage() == null ? "unknown" : e.getMessage();
                    try {
                        JSONObject errObj = new JSONObject();
                        errObj.put("error", errMsg);
                        final String errorJson = errObj.toString();
                        final String js = "window._apiCallback(" + callbackId + ", " + JSONObject.quote(errorJson) + ")";
                        safeEvaluateJavascript(js);
                    } catch (Exception ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setAuth(String t, String u) {
            token = t;
            uuid = u;
        }

        @JavascriptInterface
        public String loadSeatMap() {
            return readLocalJson("seat_map.json", "{}");
        }

        @JavascriptInterface
        public void saveSeatMap(String json) {
            writeLocalJson("seat_map.json", json);
        }

        @JavascriptInterface
        public String loadVenues() {
            return readLocalJson("venues.json", "[]");
        }

        @JavascriptInterface
        public void saveVenues(String json) {
            writeLocalJson("venues.json", json);
        }

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

        @JavascriptInterface
        public void stopAutoLoop() {
            isRunning = false;
            if (autoLoopRunnable != null) {
                handler.removeCallbacks(autoLoopRunnable);
            }
        }

        @JavascriptInterface
        public void startLockLoop(String t, String u, String venue, String seatsJson) {
            token = t;
            uuid = u;
            lockTargetVenue = venue;
            lockTargetSeats = parseSeats(seatsJson);
            isLocking = true;
            startLockLoopInternal();
        }

        @JavascriptInterface
        public void stopLockLoop() {
            isLocking = false;
            if (lockLoopRunnable != null) {
                handler.removeCallbacks(lockLoopRunnable);
            }
        }

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
                try {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", e.getMessage());
                    return err.toString();
                } catch (Exception ignored) {
                    return "{\"success\":false}";
                }
            }
        }

        /**
         * 导出配置到手机 Download 目录
         */
        @JavascriptInterface
        public String exportConfig(String json, String filename) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "ERROR: 无法创建文件";

                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "ERROR: 无法打开输出流";
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);

                    return "手机「下载」目录 / " + filename;
                } else {
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File file = new File(downloadsDir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(json.getBytes("UTF-8"));
                    fos.close();
                    return file.getAbsolutePath();
                }
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        /**
         * 弹出系统文件选择器
         */
        @JavascriptInterface
        public void pickFileForImport() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/json",
                            "text/plain",
                            "application/octet-stream"
                    });
                    startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
                } catch (Exception e) {
                    e.printStackTrace();
                    safeEvaluateJavascript("window._onImportFileSelected('')");
                }
            });
        }
    }

    // ============================================================
    // 处理文件选择结果
    // ============================================================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        is.close();
                        String content = sb.toString();
                        safeEvaluateJavascript("window._onImportFileSelected(" + JSONObject.quote(content) + ")");
                    } catch (Exception e) {
                        e.printStackTrace();
                        safeEvaluateJavascript("window._onImportFileSelected('')");
                    }
                }
            } else {
                safeEvaluateJavascript("window._onImportFileSelected('')");
            }
        }
    }

    // ============================================================
    // 本地文件读写
    // ============================================================
    private String readLocalJson(String filename, String defaultVal) {
        try {
            File file = new File(getFilesDir(), filename);
            if (!file.exists()) {
                InputStream is = getAssets().open("pages/" + filename);
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
            return defaultVal;
        }
    }

    private void writeLocalJson(String filename, String json) {
        try {
            File file = new File(getFilesDir(), filename);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // 自动刷号轮询
    // ============================================================
    private void startAutoLoopInternal() {
        if (autoLoopRunnable != null) {
            handler.removeCallbacks(autoLoopRunnable);
        }
        autoLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                new Thread(() -> {
                    try {
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
                            final int finalAttempt = attemptCount;
                            if (hit) {
                                isHitting = true;
                                isRunning = false;
                                safeEvaluateJavascript("window._onHit(" + toJsonArray(seats) + ")");
                                return;
                            }
                            safeEvaluateJavascript("window._onProgress(" + finalAttempt + ", " + toJsonArray(seats) + ")");
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
        if (lockLoopRunnable != null) {
            handler.removeCallbacks(lockLoopRunnable);
        }
        lockLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLocking) return;
                new Thread(() -> {
                    try {
                        String ordersJson = callGetOrders(token, uuid);
                        JSONObject json = new JSONObject(ordersJson);
                        if ("000".equals(json.optString("code"))) {
                            JSONObject dataObj = json.optJSONObject("data");
                            if (dataObj != null) {
                                JSONArray list = dataObj.optJSONArray("list");
                                if (list != null) {
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
                                        String oid = targetOrder.optString("order_id");
                                        currentOrderId = oid;

                                        boolean isAutoMode = (lockTargetVenue == null || lockTargetVenue.isEmpty());
                                        String effectiveVenue = lockTargetVenue;

                                        if (isAutoMode) {
                                            effectiveVenue = extractVenueName(targetOrder);
                                            if (effectiveVenue == null || effectiveVenue.isEmpty()) {
                                                if (isLocking) handler.postDelayed(lockLoopRunnable, 1500);
                                                return;
                                            }
                                            safeEvaluateJavascript("window._onVenueDetected(" + JSONObject.quote(effectiveVenue) + ")");
                                        }

                                        String seatMapJson = readLocalJson("seat_map.json", "{}");
                                        JSONObject seatMap = new JSONObject(seatMapJson);
                                        JSONObject venueData = seatMap.optJSONObject(effectiveVenue);

                                        if (venueData == null) {
                                            safeEvaluateJavascript("window._onVenueGenerating(" + JSONObject.quote(effectiveVenue) + ")");

                                            String seatListJson = callQuerySeatList(oid, token, uuid);
                                            JSONObject seatListResult = new JSONObject(seatListJson);
                                            if ("000".equals(seatListResult.optString("code"))) {
                                                JSONObject seatData = seatListResult.optJSONObject("data");
                                                if (seatData != null) {
                                                    JSONArray seatList = seatData.optJSONArray("seat_list");
                                                    if (seatList != null) {
                                                        JSONObject newMap = new JSONObject();
                                                        for (int i = 0; i < seatList.length(); i++) {
                                                            JSONObject seat = seatList.getJSONObject(i);
                                                            newMap.put(seat.optString("seat_number"), seat.optString("product_ticket_seat_id"));
                                                        }
                                                        seatMap.put(effectiveVenue, newMap);
                                                        writeLocalJson("seat_map.json", seatMap.toString());
                                                        venueData = newMap;

                                                        final int seatCount = seatList.length();
                                                        safeEvaluateJavascript("window._onVenueGenerated("
                                                                + JSONObject.quote(effectiveVenue) + ", "
                                                                + seatCount + ")");
                                                    }
                                                }
                                            }
                                        }

                                        if (venueData == null) {
                                            if (isLocking) handler.postDelayed(lockLoopRunnable, 1500);
                                            return;
                                        }

                                        for (int seatNum : lockTargetSeats) {
                                            if (!isLocking) break;
                                            String seatId = venueData.optString(String.valueOf(seatNum));
                                            if (seatId == null || seatId.isEmpty()) continue;

                                            String confirmResult = callConfirmSeat(oid, seatId, token, uuid);
                                            JSONObject confirmJson = new JSONObject(confirmResult);
                                            if ("000".equals(confirmJson.optString("code"))) {
                                                isLocking = false;
                                                final int finalSeat = seatNum;
                                                safeEvaluateJavascript("window._onLocked("
                                                        + finalSeat + ", "
                                                        + JSONObject.quote(oid) + ", "
                                                        + JSONObject.quote(effectiveVenue) + ")");
                                                return;
                                            } else {
                                                // 【新增】锁定失败，把错误回调到前端
                                                String failReason = confirmJson.optString("msg", "未知错误");
                                                safeEvaluateJavascript("window._onLockFailed("
                                                        + JSONObject.quote(failReason) + ", "
                                                        + JSONObject.quote(oid) + ", "
                                                        + JSONObject.quote(effectiveVenue) + ", "
                                                        + seatNum + ")");
                                            }
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

    private String extractVenueName(JSONObject order) {
        try {
            if (order.has("merchant_model")) {
                JSONObject merchant = order.optJSONObject("merchant_model");
                if (merchant != null) {
                    String name = merchant.optString("mer_name");
                    if (name != null && !name.isEmpty()) return name;
                }
            }
            JSONObject ticketItem = order.optJSONObject("order_ticket_item");
            if (ticketItem != null) {
                JSONObject productInfo = ticketItem.optJSONObject("product_info");
                if (productInfo != null) {
                    String name = productInfo.optString("title");
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void stopAutoLoop() {
        isRunning = false;
        if (autoLoopRunnable != null) {
            handler.removeCallbacks(autoLoopRunnable);
        }
    }

    private void stopLockLoop() {
        isLocking = false;
        if (lockLoopRunnable != null) {
            handler.removeCallbacks(lockLoopRunnable);
        }
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
            try {
                JSONObject err = new JSONObject();
                err.put("code", "500");
                err.put("msg", e.getMessage());
                return err.toString();
            } catch (Exception ignored) {
                return "{\"code\":\"500\"}";
            }
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
            try {
                JSONObject err = new JSONObject();
                err.put("code", "500");
                err.put("msg", e.getMessage());
                return err.toString();
            } catch (Exception ignored) {
                return "{\"code\":\"500\"}";
            }
        }
    }

    private String callGetOrders(String token, String uuid) {
        try {
            String url = API_HOST + "/v2/userApi/order/getMyTicketOrderList?tab=10&page=1&limit=20";
            return httpGet(url, token, uuid);
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("code", "500");
                err.put("msg", e.getMessage());
                return err.toString();
            } catch (Exception ignored) {
                return "{\"code\":\"500\"}";
            }
        }
    }

    private String callQuerySeatList(String orderId, String token, String uuid) {
        try {
            String url = API_HOST + "/v2/userApi/ticketSeat/querySeatList?order_id=" + orderId;
            return httpGet(url, token, uuid);
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("code", "500");
                err.put("msg", e.getMessage());
                return err.toString();
            } catch (Exception ignored) {
                return "{\"code\":\"500\"}";
            }
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
        if (targets == null || targets.isEmpty()) return false;
        java.util.Set<Integer> targetSet = new java.util.HashSet<>(targets);
        for (int s : seats) {
            if (targetSet.contains(s)) return true;
        }
        return false;
    }

    private String toJsonArray(List<Integer> list) {
        JSONArray arr = new JSONArray();
        for (int i : list) arr.put(i);
        return arr.toString();
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
            try {
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.setWebViewClient(null);
                webView.setWebChromeClient(null);
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
