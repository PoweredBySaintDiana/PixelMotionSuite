package dev.pixelmotion.a10.recents;

/** Fits a task above the shelf without stretching the thumbnail or live surface. */
final class ShelfGeometry {
    static int[] fit(int left, int top, int right, int bottom, int minTop, int bottomLimit) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || bottomLimit <= minTop || bottom <= bottomLimit) {
            return new int[]{left, top, right, bottom};
        }
        int overflow = bottom - bottomLimit;
        if (top - overflow >= minTop) {
            return new int[]{left, top - overflow, right, bottomLimit};
        }
        int fittedHeight = bottomLimit - minTop;
        int fittedWidth = Math.max(1, Math.round(width * ((float) fittedHeight / height)));
        int fittedLeft = Math.round((left + right - fittedWidth) * .5f);
        return new int[]{fittedLeft, minTop, fittedLeft + fittedWidth, bottomLimit};
    }
}
