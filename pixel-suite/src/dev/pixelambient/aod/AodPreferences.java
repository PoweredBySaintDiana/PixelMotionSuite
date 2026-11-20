package dev.pixelambient.aod;

final class AodPreferences {
    interface Store {
        String read(String name);
        boolean write(String name, String value);
    }

    static boolean enabled(Store store) {
        return "1".equals(store.read(AmbientSettings.WALLPAPER_ENABLED)) && "1".equals(store.read(AmbientSettings.ALWAYS_ON));
    }

    static boolean dimmed(Store store) { return !"0".equals(store.read(AmbientSettings.DIM_ENABLED)); }
    static boolean gainEnabled(Store store) { return !"0".equals(store.read(AmbientSettings.GAIN_ENABLED)); }
    static void gain(Store store, boolean enabled) { set(store, AmbientSettings.GAIN_ENABLED, enabled ? "1" : "0"); }

    static void wallpaper(Store store, boolean enabled) {
        if (!enabled) { set(store, AmbientSettings.WALLPAPER_ENABLED, "0"); return; }
        String old = store.read(AmbientSettings.WALLPAPER_ENABLED);
        set(store, AmbientSettings.WALLPAPER_ENABLED, "1");
        try { set(store, AmbientSettings.ALWAYS_ON, "1"); }
        catch (RuntimeException error) {
            if (!"1".equals(old)) store.write(AmbientSettings.WALLPAPER_ENABLED, old);
            throw error;
        }
    }

    static void shade(Store store, boolean enabled) { set(store, AmbientSettings.DIM_ENABLED, enabled ? "1" : "0"); }

    private static void set(Store store, String key, String value) {
        String old = store.read(key);
        if (value.equals(old)) return;
        try {
            if (!store.write(key, value) || !value.equals(store.read(key))) throw new IllegalStateException("Setting did not persist: " + key);
        } catch (RuntimeException error) {
            if (value.equals(store.read(key))) store.write(key, old);
            throw error;
        }
    }
}
