package dev.pixelambient.aod;

import android.os.Build;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class AmbientAodModule implements IXposedHookLoadPackage {
    private static final String TAG = "PixelAmbientAOD";

    @Override public void handleLoadPackage(LoadPackageParam pkg) {
        if (Build.VERSION.SDK_INT != 37) return;
        if ("android".equals(pkg.packageName)) {
            AodWallpaperServicePort.install(pkg.classLoader);
        } else if ("com.android.systemui".equals(pkg.packageName)
                && "com.android.systemui".equals(pkg.processName)) {
            AodTimeout.install(pkg.classLoader);
            AodSystemUiPort.install(pkg.classLoader);
            AodPanelBrightness.install(pkg.classLoader);
            AodControlsHost.install();
        }
    }

    static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
