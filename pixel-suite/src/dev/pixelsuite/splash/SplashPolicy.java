package dev.pixelsuite.splash;

final class SplashPolicy {
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static final String SHELL = "com.android.systemui";
    static final String ACTION = "dev.pixelmotion.a10.recents.SPLASH_LAYERS_V1";
    static final String COOKIE = ACTION + ".WINDOW";
    static final String STAMP = ACTION + ".TIME";
    static final int SIZE = 144;
    static final int LIFETIME = 1200;
    private static final int DESKTOP = -100, HOTSEAT = -101, DRAWER_PREDICTION = -102, ALL_APPS = -104;

    static boolean fresh(long stamp, long now) { return stamp > 0 && now >= stamp && now - stamp < LIFETIME; }
    static boolean launcherApp(int container, int itemType) {
        return itemType == 0 && (container == DESKTOP || container == HOTSEAT || container == ALL_APPS || container == DRAWER_PREDICTION);
    }
    static boolean freshWindow(int type, int flags) { return type == 1 && (flags & (0x10 | 0x40)) == 0; }

    static int background(int[] pixels) {
        int[] weight = new int[512], reds = new int[512], greens = new int[512], blues = new int[512];
        int best = 0;
        for (int color : pixels) {
            int alpha = color >>> 24;
            if (alpha < 128) continue;
            int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
            int slot = (red >> 5) * 64 + (green >> 5) * 8 + (blue >> 5);
            weight[slot] += alpha;
            reds[slot] += red * alpha;
            greens[slot] += green * alpha;
            blues[slot] += blue * alpha;
            if (weight[slot] > weight[best]) best = slot;
        }
        int total = weight[best];
        return total == 0 ? 0 : 0xff000000 | (reds[best] / total << 16) | (greens[best] / total << 8) | blues[best] / total;
    }

    static boolean visibleForeground(int[] pixels) {
        int visible = 0, clear = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) > 32) visible++;
            if ((pixel >>> 24) < 224) clear++;
        }
        return visible > pixels.length / 1000 && clear > pixels.length / 100;
    }
}
