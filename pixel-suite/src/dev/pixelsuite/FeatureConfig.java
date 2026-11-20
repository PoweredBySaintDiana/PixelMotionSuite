package dev.pixelsuite;

import de.robv.android.xposed.XSharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Loads one immutable preference snapshot per injected process. */
public final class FeatureConfig {
    public static final String PACKAGE = "dev.pixelmotion.a10.recents";
    public static final String PREFS = "motion_switches";
    public static final String MOTION_ICON = "motion_icon_transition";
    public static final String SPLASH_LAYERS = "motion_splash_layers";
    public static final String MOTION_WORKSPACE = "motion_workspace_dip";
    public static final String MOTION_PAGE = "motion_page_recoil";
    public static final String RECENTS_DOCK = "recents_overview_dock";
    public static final String RECENTS_LAYOUT = "recents_card_layout";
    public static final String RECENTS_SHELF = "recents_task_shelf";
    public static final String RECENTS_FLOAT = "recents_float_launch";
    public static final String AOD_RESTORE = "aod_restore_earth";
    public static final String AOD_VISIBILITY = "aod_visibility";
    public static final String AOD_GAIN = "aod_wallpaper_gain";

    private static volatile Map<String, Boolean> snapshot;

    private FeatureConfig() { }

    public static boolean enabled(String key, boolean fallback) {
        Map<String, Boolean> values = snapshot;
        if (values == null) values = load();
        Boolean value = values.get(key);
        return value == null ? fallback : value.booleanValue();
    }

    static boolean isMotionKey(String key) {
        return SPLASH_LAYERS.equals(key) || MOTION_ICON.equals(key) || MOTION_WORKSPACE.equals(key) || MOTION_PAGE.equals(key)
                || RECENTS_DOCK.equals(key) || RECENTS_LAYOUT.equals(key) || RECENTS_SHELF.equals(key)
                || RECENTS_FLOAT.equals(key);
    }

    private static synchronized Map<String, Boolean> load() {
        if (snapshot != null) return snapshot;
        Map<String, Boolean> values = new HashMap<>();
        try {
            XSharedPreferences preferences = new XSharedPreferences(PACKAGE, PREFS);
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (isMotionKey(entry.getKey()) && entry.getValue() instanceof Boolean)
                    values.put(entry.getKey(), (Boolean) entry.getValue());
            }
            android.util.Log.i("PixelSuite", "Loaded " + values.size() + " saved motion switches");
        } catch (Throwable error) {
            android.util.Log.w("PixelSuite", "Feature preferences unavailable; using defaults", error);
        }
        snapshot = Collections.unmodifiableMap(values);
        return snapshot;
    }

}
