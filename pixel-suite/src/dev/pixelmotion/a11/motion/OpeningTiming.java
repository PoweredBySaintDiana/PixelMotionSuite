package dev.pixelmotion.a11.motion;

import android.view.animation.Interpolator;

/** Per-property clocks of the Pixel Launcher 701 icon opening animation. */
enum OpeningTiming {
    LOWER_OR_NEAR_CENTER(250, 450, 50),
    UPPER(360, 200, 40);

    static final int TOTAL_MS = 450;
    static final Interpolator SCALE = MotionCurve.a10Exaggerated(450, TOTAL_MS);
    static final Interpolator CROP_AND_RADIUS = MotionCurve.a10Exaggerated(375, TOTAL_MS);
    final Interpolator x, y, fade;

    OpeningTiming(int xMs, int yMs, int fadeMs) {
        x = MotionCurve.a11Aggressive(xMs, TOTAL_MS);
        y = MotionCurve.a11Aggressive(yMs, TOTAL_MS);
        fade = fraction -> Math.max(0f, Math.min(1f, (fraction * TOTAL_MS - 25f) / fadeMs));
    }

    static OpeningTiming select(float iconTop, float iconCenterY, float targetCenterY, int cellHeight) {
        return iconTop > targetCenterY || Math.abs(targetCenterY - iconCenterY) < cellHeight
                ? LOWER_OR_NEAR_CENTER : UPPER;
    }
}
