package dev.pixelambient.aod;

import android.content.Context;
import android.os.UserManager;
import android.provider.Settings;

/** Shared AOD settings use the platform store, readable by the injected processes. */
public final class AmbientSettings {
    public static final String WALLPAPER_ENABLED = "doze_always_on_wallpaper_enabled";
    public static final String ALWAYS_ON = "doze_always_on";
    public static final String DIM_ENABLED = "pixel_suite_aod_dim";
    public static final String GAIN_ENABLED = "pixel_suite_aod_panel_boost";

    private AmbientSettings() { }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            UserManager users = context.getSystemService(UserManager.class);
            return users != null && users.isUserForeground()
                    && Settings.Secure.getInt(context.getContentResolver(), WALLPAPER_ENABLED, 0) == 1
                    && Settings.Secure.getInt(context.getContentResolver(), ALWAYS_ON, 0) == 1;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static boolean isDimmed(Context context) {
        if (context == null) return false;
        try {
            return Settings.Secure.getInt(context.getContentResolver(), DIM_ENABLED, 1) == 1;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
