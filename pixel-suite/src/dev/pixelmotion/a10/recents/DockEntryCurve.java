package dev.pixelmotion.a10.recents;

/** The uncut native OVERVIEW overshoot, sampled from a linear state clock. */
final class DockEntryCurve {
    private DockEntryCurve() { }

    static float overview(float raw) {
        float t = Math.max(0f, Math.min(1f, raw)) - 1f;
        return t * t * (2.2f * t + 1.2f) + 1f;
    }
}
