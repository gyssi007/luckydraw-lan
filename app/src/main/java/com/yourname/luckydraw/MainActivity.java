package com.yourname.luckydraw;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String ALLOWED_HOST = "fishing.gysssi.com";
    private static final String APP_BASE_URL = "https://" + ALLOWED_HOST + "/app/";

    private WebView webView;
    private ProgressBar progressBar;
    private MediaPlayer mediaPlayer;
    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        configureWebView();
        loadPage();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isAllowedAppUrl(url) || url.startsWith("about:blank")) {
                    return false;
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loadLocalAsset(request.getUrl().toString());
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

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
    }

    private boolean isAllowedAppUrl(String url) {
        return url.startsWith(APP_BASE_URL);
    }

    /**
     * 页面通过 https://fishing.gysssi.com/app/... 运行，
     * 因此 fetch('/venues') 等 API 请求仍然保持原来的同源环境。
     * 页面文件本身则从 APK assets 中提供，不依赖服务器部署这些 HTML。
     */
    @Nullable
    private WebResourceResponse loadLocalAsset(String url) {
        if (!url.startsWith(APP_BASE_URL)) {
            return null;
        }

        String assetPath = url.substring(APP_BASE_URL.length());
        if (assetPath.isEmpty() || assetPath.contains("..")) {
            return null;
        }

        try {
            InputStream input = getAssets().open(assetPath);
            String mime = getMimeType(assetPath);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");

            return new WebResourceResponse(
                    mime,
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    input
            );
        } catch (IOException ignored) {
            return null;
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void loadPage() {
        webView.loadUrl(APP_BASE_URL + "pages/lucky.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private SharedPreferences getSecurePrefs() {
        if (securePrefs != null) return securePrefs;

        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePrefs = EncryptedSharedPreferences.create(
                    this,
                    "lucky_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            e.printStackTrace();
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
            if (mediaPlayer == null) return;

            final MediaPlayer current = mediaPlayer;
            current.setOnCompletionListener(mp -> {
                mp.release();
                if (mediaPlayer == mp) {
                    mediaPlayer = null;
                }
            });
            current.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private class AndroidBridge {
        @android.webkit.JavascriptInterface
        public void playHitSound() {
            runOnUiThread(() -> playHitSoundNative());
        }

        @android.webkit.JavascriptInterface
        public void saveSecure(String key, String value) {
            getSecurePrefs().edit().putString(key, value).apply();
        }

        @android.webkit.JavascriptInterface
        public String loadSecure(String key) {
            String value = getSecurePrefs().getString(key, "");
            return value == null ? "" : value;
        }
    }
}
