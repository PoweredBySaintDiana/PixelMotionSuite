package dev.pixelmotion.a10.recents;

final class ShelfDrag {
    private static final float HORIZONTAL_SLOPE = (float) Math.tan(Math.toRadians(15));

    static boolean leftUp(float dx, float dy, float slop) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(slop) || slop <= 0f) return false;
        float left = -dx, up = -dy;
        return left > slop && up > slop && up > left * HORIZONTAL_SLOPE && left > up * HORIZONTAL_SLOPE;
    }
}
