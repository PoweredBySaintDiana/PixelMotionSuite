package dev.pixelmotion.a10.recents;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline settings UI for selecting the overview dock's application list. */
public final class AppPickerActivity extends Activity {
    private static final String ASSET_ROOT = "file:///android_asset/";
    private static final String PAGE_ROOT = ASSET_ROOT + "settings/apps/";
    private static final int MAX_SELECTION = 512;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> launchablePackages = new HashSet<>();
    private WebView webView;
    private volatile boolean pageReady;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int barColor = night == Configuration.UI_MODE_NIGHT_YES ? 0xff171a19 : 0xfff4f6f5;
            setBarColor("setStatusBarColor", barColor);
            setBarColor("setNavigationBarColor", barColor);
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            flags &= ~(0x00002000 | 0x00000010);
            if (night != Configuration.UI_MODE_NIGHT_YES)
                flags |= 0x00002000 | 0x00000010;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setBackgroundColor(0x00000000);
        webView.addJavascriptInterface(new PickerBridge(), "PixelPicker");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !allowedLocalUrl(url);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (allowedLocalUrl(url)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", null);
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = allowedSettingsPage(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageReady = allowedSettingsPage(url);
            }
        });
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(PAGE_ROOT + "index.html");
    }

    private static boolean allowedLocalUrl(String value) {
        if (value == null || !value.startsWith(ASSET_ROOT)) return false;
        String path = value.substring(ASSET_ROOT.length());
        return !path.startsWith("/") && !path.contains("..") && !path.contains(":");
    }

    private static boolean allowedSettingsPage(String value) {
        if (!allowedLocalUrl(value) || !value.startsWith(PAGE_ROOT)) return false;
        return value.indexOf('#') < 0 && value.indexOf('?') < 0;
    }

    private void setBarColor(String method, int color) {
        try { getWindow().getClass().getMethod(method, int.class).invoke(getWindow(), color); }
        catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        pageReady = false;
        if (webView != null) {
            webView.removeJavascriptInterface("PixelPicker");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadApps() {
        if (isFinishing()) return;
        worker.execute(() -> {
            try {
                String apps = queryApps(this);
                DockSelectionStore.Selection selection = DockSelectionStore.read(this);
                String result = "{\"apps\":" + apps + ",\"selection\":" + selectionJson(selection) + "}";
                evaluate("window.onPickerLoad && window.onPickerLoad(" + result + ")");
            } catch (Throwable failure) {
                evaluate("window.onPickerLoadError && window.onPickerLoadError(" + jsonQuote(message(failure)) + ")");
            }
        });
    }

    private static String queryApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(launcherIntent, 0);
        List<AppEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null) continue;
            String packageName = activity.activityInfo.packageName;
            if (packageName == null || !seen.add(packageName)) continue;
            String label;
            try { label = String.valueOf(activity.loadLabel(packageManager)); }
            catch (RuntimeException ignored) { label = packageName; }
            String icon = "";
            try { icon = iconData(activity.loadIcon(packageManager)); }
            catch (RuntimeException ignored) { }
            entries.add(new AppEntry(packageName, label, icon));
        }
        Collections.sort(entries, new Comparator<AppEntry>() {
            private final java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
            @Override public int compare(AppEntry left, AppEntry right) {
                int byLabel = collator.compare(left.label, right.label);
                return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
            }
        });
        StringBuilder result = new StringBuilder("[");
        for (AppEntry entry : entries) {
            if (result.length() > 1) result.append(',');
            result.append("{\"packageName\":").append(jsonQuote(entry.packageName))
                    .append(",\"label\":").append(jsonQuote(entry.label))
                    .append(",\"icon\":").append(jsonQuote(entry.icon)).append('}');
        }
        return result.append(']').toString();
    }

    private static String iconData(Drawable drawable) {
        if (drawable == null) return "";
        final int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min((float) size / width, (float) size / height);
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        int left = (size - w) / 2, top = (size - h) / 2;
        drawable.setBounds(left, top, left + w, top + h);
        drawable.draw(canvas);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private void evaluate(String script) {
        runOnUiThread(() -> {
            WebView target = webView;
            if (target != null && pageReady && !isFinishing()) {
                try {
                    target.getClass().getMethod("evaluateJavascript", String.class,
                            Class.forName("android.webkit.ValueCallback")).invoke(target, script, null);
                } catch (Throwable failure) {
                    target.loadUrl("javascript:" + script);
                }
            }
        });
    }

    private final class PickerBridge {
        @JavascriptInterface public void loadApps() { AppPickerActivity.this.loadApps(); }

        @JavascriptInterface public void saveSelection(String json) {
            worker.execute(() -> {
                try {
                    String encoded = json == null ? "" : json;
                    if (encoded.length() > MAX_SELECTION * 256) throw new IllegalArgumentException("应用列表过长");
                    List<String> packages = new ArrayList<>();
                    Set<String> unique = new LinkedHashSet<>();
                    PackageManager packageManager = getPackageManager();
                    launchablePackages.clear();
                    Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    for (ResolveInfo info : packageManager.queryIntentActivities(launcherIntent, 0)) {
                        if (info.activityInfo != null) launchablePackages.add(info.activityInfo.packageName);
                    }
                    for (String name : encoded.split("\\n")) {
                        if (packages.size() >= MAX_SELECTION) break;
                        if (launchablePackages.contains(name) && unique.add(name)) packages.add(name);
                    }
                    DockSelectionStore.Selection saved = DockSelectionStore.save(AppPickerActivity.this, packages);
                    String result = "{\"ok\":true,\"selection\":" + selectionJson(saved) + "}";
                    evaluate("window.onPickerSaved && window.onPickerSaved(" + result + ")");
                } catch (Throwable failure) {
                    String result = "{\"ok\":false,\"error\":" + jsonQuote(message(failure)) + "}";
                    evaluate("window.onPickerSaved && window.onPickerSaved(" + result + ")");
                }
            });
        }

        @JavascriptInterface public void useNative() {
            worker.execute(() -> {
                try {
                    DockSelectionStore.Selection state = DockSelectionStore.useNative(AppPickerActivity.this);
                    String result = "{\"ok\":true,\"selection\":" + selectionJson(state) + "}";
                    evaluate("window.onPickerNative && window.onPickerNative(" + result + ")");
                } catch (Throwable failure) {
                    evaluate("window.onPickerNative && window.onPickerNative({ok:false,error:"
                            + jsonQuote(message(failure)) + "})");
                }
            });
        }
    }

    private static String selectionJson(DockSelectionStore.Selection selection) {
        StringBuilder result = new StringBuilder("{\"enabled\":").append(selection.enabled)
                .append(",\"revision\":").append(selection.revision).append(",\"packages\":[");
        for (int i = 0; i < selection.packages.size(); i++) {
            if (i > 0) result.append(',');
            result.append(jsonQuote(selection.packages.get(i)));
        }
        return result.append("]}").toString();
    }

    private static String jsonQuote(String value) {
        if (value == null) value = "";
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        result.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) result.append('0');
                        result.append(hex);
                    } else result.append(c);
            }
        }
        return result.append('"').toString();
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String text = cause.getMessage();
        return text == null || text.isEmpty() ? cause.getClass().getSimpleName() : text;
    }

    private static final class AppEntry {
        final String packageName, label, icon;
        AppEntry(String packageName, String label, String icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }
}
