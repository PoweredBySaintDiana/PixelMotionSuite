package dev.pixelmotion.a11.motion;

import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

/** Reproduces Android 10's independent adaptive-icon foreground throw spring. */
final class A10ForegroundSpring {
    private static final float STIFFNESS = 200f;
    private static final float DAMPING = 21.213203f;
    private static final float STEP_SECONDS = 1f / 60f;
    private static final float X_RANGE_PX = 60f;
    private static final float Y_RANGE_PX = 75f;

    private boolean initialized;
    private float x, y, velocityX, velocityY, targetX, targetY;

    void onUpdate(RectF position, View view) {
        if (position == null || view == null) return;
        DisplayMetrics metrics = view.getResources().getDisplayMetrics();
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return;

        int leftMargin = 0;
        int topMargin = 0;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            leftMargin = margins.leftMargin;
            topMargin = margins.topMargin;
        }

        if (!initialized) {
            initialized = true;
            x = y = velocityX = velocityY = 0f;
        }

        // Match A10's normalized window position, scaled to a 60 x 75 px throw.
        targetX = (position.left - leftMargin) / metrics.widthPixels * X_RANGE_PX;
        targetY = (position.top - topMargin) / metrics.heightPixels * Y_RANGE_PX;
    }

    boolean advance() {
        if (!initialized) return false;

        float accelerationX = STIFFNESS * (targetX - x) - DAMPING * velocityX;
        velocityX += accelerationX * STEP_SECONDS;
        x += velocityX * STEP_SECONDS;

        float accelerationY = STIFFNESS * (targetY - y) - DAMPING * velocityY;
        velocityY += accelerationY * STEP_SECONDS;
        y += velocityY * STEP_SECONDS;

        return Math.abs(velocityX) + Math.abs(velocityY)
                + Math.abs(targetX - x) + Math.abs(targetY - y) > .001f;
    }

    boolean isInitialized() { return initialized; }
    float x() { return x; }
    float y() { return y; }
}
