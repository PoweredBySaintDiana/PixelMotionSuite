package dev.pixelsuite;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.pixelmotion.a10.recents.AppPickerActivity;
import dev.pixelmotion.a10.recents.DockSelectionStore;
import dev.pixelambient.aod.AodControls;

public final class SettingsActivity extends Activity {
    private static final String ASSET_ROOT = "file:///android_asset/";
    private WebView webView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (BuildCompat.isAtLeastM()) {
            int color = night == Configuration.UI_MODE_NIGHT_YES ? 0xff151a18 : 0xfff4f7f5;
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
            int flags = getWindow().getDecorView().getSystemUiVisibility() & ~(0x2000 | 0x10);
            if (night != Configuration.UI_MODE_NIGHT_YES) flags |= 0x2000 | 0x10;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        webView.setBackgroundColor(0xfff4f7f5);
        webView.addJavascriptInterface(new Bridge(), "PixelSuite");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !allowed(url);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return allowed(url) ? null : new WebResourceResponse("text/plain", "UTF-8", null);
            }

        });
        setContentView(webView, new ViewGroup.LayoutParams(-1, -1));
        webView.loadUrl(ASSET_ROOT + "suite/index.html");
    }

    private static boolean allowed(String url) {
        if (url == null || !url.startsWith(ASSET_ROOT)) return false;
        String path = url.substring(ASSET_ROOT.length());
        return !path.startsWith("/") && !path.contains("..") && !path.contains(":");
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("PixelSuite");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface public String readSettings() {
            SharedPreferences preferences = ModulePreferences.features(SettingsActivity.this, false);
            Bundle ambient = AodControls.request(SettingsActivity.this, AodControls.READ, false);
            return "{"
                    + pair(FeatureConfig.MOTION_ICON, preferences.getBoolean(FeatureConfig.MOTION_ICON, true)) + ","
                    + pair(FeatureConfig.SPLASH_LAYERS, preferences.getBoolean(FeatureConfig.SPLASH_LAYERS, true)) + ","
                    + pair(FeatureConfig.MOTION_WORKSPACE, preferences.getBoolean(FeatureConfig.MOTION_WORKSPACE, true)) + ","
                    + pair(FeatureConfig.MOTION_PAGE, preferences.getBoolean(FeatureConfig.MOTION_PAGE, true)) + ","
                    + pair(FeatureConfig.RECENTS_DOCK, preferences.getBoolean(FeatureConfig.RECENTS_DOCK, true)) + ","
                    + pair(FeatureConfig.RECENTS_FLOAT, preferences.getBoolean(FeatureConfig.RECENTS_FLOAT, true)) + ","
                    + pair(FeatureConfig.AOD_RESTORE, ambient.getBoolean(AodControls.WALLPAPER)) + ","
                    + pair(FeatureConfig.AOD_VISIBILITY, ambient.getBoolean(AodControls.SHADE, true)) + ","
                    + pair(FeatureConfig.AOD_GAIN, ambient.getBoolean(AodControls.GAIN, true)) + ","
                    + "\"recents_custom_apps\":" + DockSelectionStore.isEnabled(SettingsActivity.this) + ","
                    + "\"ambient_status\":\"" + ambient.getString("status", "unavailable") + "\"}";
        }

        @JavascriptInterface public String setFeature(String key, boolean enabled) {
            if (FeatureConfig.AOD_RESTORE.equals(key) || FeatureConfig.AOD_VISIBILITY.equals(key) || FeatureConfig.AOD_GAIN.equals(key)) {
                return AodControls.request(SettingsActivity.this, FeatureConfig.AOD_RESTORE.equals(key)
                        ? AodControls.WALLPAPER : FeatureConfig.AOD_GAIN.equals(key) ? AodControls.GAIN : AodControls.SHADE, enabled).getString("status", "unavailable");
            }
            if (!FeatureConfig.isMotionKey(key)) return "invalid";
            try {
                return ModulePreferences.features(SettingsActivity.this, true).edit()
                        .putBoolean(key, enabled).commit() ? "saved" : "failed";
            } catch (SecurityException unavailable) {
                return "sharing_unavailable";
            } catch (RuntimeException failure) {
                return "failed";
            }
        }

        @JavascriptInterface public boolean setCustomAppsEnabled(boolean enabled) {
            try {
                DockSelectionStore.setEnabled(SettingsActivity.this, enabled);
                return true;
            } catch (Throwable failure) { return false; }
        }

        @JavascriptInterface public void openAppPicker() {
            runOnUiThread(() -> startActivity(new Intent(SettingsActivity.this, AppPickerActivity.class)));
        }

        private String pair(String key, boolean value) {
            return "\"" + key + "\":" + value;
        }
    }

    private static final class BuildCompat {
        static boolean isAtLeastM() { return android.os.Build.VERSION.SDK_INT >= 23; }
    }
}
