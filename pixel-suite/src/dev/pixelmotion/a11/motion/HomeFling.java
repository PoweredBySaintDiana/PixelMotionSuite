package dev.pixelmotion.a11.motion;

import java.util.WeakHashMap;

final class HomeFling {
    private static final float QUICK_SWITCH_SLOPE = (float) Math.tan(Math.toRadians(15));
    private final WeakHashMap<Object, Boolean> gestures = new WeakHashMap<>();
    private final WeakHashMap<Object, Boolean> springs = new WeakHashMap<>();

    void decide(Object gesture, Object target, boolean fling, boolean cancelled) {
        if (!cancelled && fling && target instanceof Enum && "HOME".equals(((Enum<?>) target).name()))
            gestures.put(gesture, true);
        else gestures.remove(gesture);
    }

    void bind(Object gesture, Object[] created) {
        if (gestures.remove(gesture) == null || created == null) return;
        for (Object spring : created) if (spring != null) springs.put(spring, true);
    }

    boolean take(Object spring) { return springs.remove(spring) != null; }

    static boolean diagonalSwitch(Object nativeTarget, boolean fling, boolean cancelled,
            float horizontal, float vertical, float endVelocity) {
        return nativeTarget instanceof Enum && "NEW_TASK".equals(((Enum<?>) nativeTarget).name())
                && fling && !cancelled && Float.isFinite(horizontal) && Float.isFinite(vertical)
                && endVelocity == vertical && vertical < 0f && Math.abs(horizontal) > -vertical
                && -vertical > Math.abs(horizontal) * QUICK_SWITCH_SLOPE;
    }

    static float[] redirect(float releaseX, float releaseY, float stockX, float stockY) {
        if (releaseX == 0f || releaseY == 0f || !Float.isFinite(releaseX) || !Float.isFinite(releaseY)
                || !Float.isFinite(stockX) || !Float.isFinite(stockY)) return null;
        double speed = Math.hypot(stockX, stockY);
        if (speed == 0.0) return null;
        double scale = speed / Math.hypot(releaseX, releaseY);
        float horizontal = (float) (releaseX * scale);
        float vertical = (float) (releaseY * scale);
        return Float.isFinite(horizontal) && Float.isFinite(vertical) ? new float[]{horizontal, vertical} : null;
    }
}
