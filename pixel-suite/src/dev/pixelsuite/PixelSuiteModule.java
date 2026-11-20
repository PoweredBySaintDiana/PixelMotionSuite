package dev.pixelsuite;

import android.os.Build;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import dev.pixelambient.aod.AmbientAodModule;
import dev.pixelmotion.a10.recents.RecentsMotion;
import dev.pixelmotion.a11.motion.Motion;
import dev.pixelsuite.splash.HomeSplash;
import dev.pixelsuite.splash.LaunchOrigin;
import dev.pixelsuite.splash.ShellSplash;

public final class PixelSuiteModule implements IXposedHookLoadPackage {
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";

    @Override public void handleLoadPackage(LoadPackageParam pkg) throws Throwable {
        if (Build.VERSION.SDK_INT != 37) return;

        if (LAUNCHER.equals(pkg.packageName) && LAUNCHER.equals(pkg.processName)) {
            if (FeatureConfig.enabled(FeatureConfig.SPLASH_LAYERS, true)) HomeSplash.install(pkg.classLoader);
            if (FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)
                    || FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true)
                    || FeatureConfig.enabled(FeatureConfig.MOTION_PAGE, true)) {
                new Motion().handleLoadPackage(pkg);
            }
            if (FeatureConfig.enabled(FeatureConfig.RECENTS_DOCK, true)) {
                new RecentsMotion().handleLoadPackage(pkg);
            }
            return;
        }

        if ("com.android.systemui".equals(pkg.packageName)
                && "com.android.systemui".equals(pkg.processName)) {
            ShellSplash.install(pkg.classLoader);
            if (FeatureConfig.enabled(FeatureConfig.RECENTS_DOCK, true)
                    && FeatureConfig.enabled(FeatureConfig.RECENTS_FLOAT, true)) {
                new RecentsMotion().handleLoadPackage(pkg);
            }
            new AmbientAodModule().handleLoadPackage(pkg);
            return;
        }

        if ("android".equals(pkg.packageName)) {
            LaunchOrigin.install(pkg.classLoader);
            new AmbientAodModule().handleLoadPackage(pkg);
        }
    }
}
