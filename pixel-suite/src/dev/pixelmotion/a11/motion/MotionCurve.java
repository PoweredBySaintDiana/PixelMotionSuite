package dev.pixelmotion.a11.motion;

import android.view.animation.Interpolator;

final class MotionCurve {
    private MotionCurve() {}

    private static final Interpolator A11_AGGRESSIVE =
            new CubicInterpolator(.2f, 0f, 0f, 1f);
    private static final Interpolator A11_EXAGGERATED = createA11Exaggerated();
    private static final float A10_SPLIT_X = .166666f;
    // Absolute control points from Launcher 701, normalized into the
    // two segments expected by PiecewiseInterpolator.
    private static final Interpolator A10_EXAGGERATED = new PiecewiseInterpolator(
            new CubicInterpolator(.05f / A10_SPLIT_X, 0f, .133333f / A10_SPLIT_X, .08f / .4f),
            new CubicInterpolator((.225f - A10_SPLIT_X) / (1f - A10_SPLIT_X), (.94f - .4f) / .6f,
                    (.5f - A10_SPLIT_X) / (1f - A10_SPLIT_X), 1f),
            A10_SPLIT_X, .4f);

    static Interpolator a10Exaggerated(int durationMs, int totalMs) {
        return new DurationInterpolator(A10_EXAGGERATED, durationMs, totalMs);
    }

    /** Android 11's fast position response used by the icon's X/Y path. */
    static Interpolator a11Aggressive() { return A11_AGGRESSIVE; }

    /**
     * Android 11 gives each opening property its own effective duration even
     * though they are driven by one 500 ms animator.  Keep the same curve but
     * clamp its local clock at the property duration.
     */
    static Interpolator a11Aggressive(int durationMs, int totalMs) {
        return new DurationInterpolator(A11_AGGRESSIVE, durationMs, totalMs);
    }

    /** Android 11's two-stage scale/crop response used by the app window reveal. */
    static Interpolator a11Exaggerated() { return A11_EXAGGERATED; }

    static Interpolator a11Exaggerated(int durationMs, int totalMs) {
        return new DurationInterpolator(A11_EXAGGERATED, durationMs, totalMs);
    }

    private static Interpolator createA11Exaggerated() {
        return new PiecewiseInterpolator(
                new CubicInterpolator(.30f, .0f, .80f, .15f),
                // M 0,0 C .05,0 .133333,.06 .166666,.4
                // C .208333,.82 .25,1 1,1 (Android 11 source path).
                new CubicInterpolator(.05f, .70f, .10f, 1f),
                .166666f, .4f);
    }

    private static final class DurationInterpolator implements Interpolator {
        private final Interpolator curve;
        private final float duration, total;

        DurationInterpolator(Interpolator curve, int durationMs, int totalMs) {
            this.curve = curve;
            this.duration = Math.max(1, durationMs);
            this.total = Math.max(this.duration, totalMs);
        }

        @Override public float getInterpolation(float input) {
            if (!Float.isFinite(input)) return 0f;
            float local = Math.max(0f, Math.min(1f, input)) * total / duration;
            return curve.getInterpolation(Math.min(1f, local));
        }
    }

    /** Small framework-free equivalent of PathInterpolator for the module's API stubs. */
    private static final class CubicInterpolator implements Interpolator {
        private final float x1, y1, x2, y2;
        CubicInterpolator(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
        @Override public float getInterpolation(float x) {
            if (!Float.isFinite(x)) return 0f;
            x = Math.max(0f, Math.min(1f, x));
            float lo = 0f, hi = 1f;
            for (int i = 0; i < 16; i++) {
                float u = (lo + hi) * .5f;
                if (bezier(u, x1, x2) < x) lo = u; else hi = u;
            }
            return bezier((lo + hi) * .5f, y1, y2);
        }
        private static float bezier(float u, float a, float b) {
            float v = 1f - u;
            return 3f * v * v * u * a + 3f * v * u * u * b + u * u * u;
        }
    }

    private static final class PiecewiseInterpolator implements Interpolator {
        private final CubicInterpolator first, second;
        private final float splitX, splitY;
        PiecewiseInterpolator(CubicInterpolator first, CubicInterpolator second,
                float splitX, float splitY) {
            this.first = first; this.second = second;
            this.splitX = splitX; this.splitY = splitY;
        }
        @Override public float getInterpolation(float x) {
            if (!Float.isFinite(x)) return 0f;
            x = Math.max(0f, Math.min(1f, x));
            if (x <= splitX) return splitY * first.getInterpolation(x / splitX);
            return splitY + (1f - splitY) * second.getInterpolation((x - splitX) / (1f - splitX));
        }
    }

    static float positionProgress(float startX, float startY, float endX, float endY,
            float x, float y, float scale, float density) {
        if (Math.abs(endY - startY) >= 12f * density) return (y - startY) / (endY - startY);
        if (Math.abs(endX - startX) >= 12f * density) return (x - startX) / (endX - startX);
        return scale;
    }

    static float springDip(float progress) {
        if (Float.isNaN(progress) || Float.isInfinite(progress)) return 0f;
        // Settle once with the position spring; never append a second bounce.
        return 8.5f * (1f - ease(progress));
    }

    /** Set an absolute overshoot in dp. The remaining distance is not gesture energy. */
    static float landingTarget(float speedDpMs, float dragFraction) {
        float speed = Float.isFinite(speedDpMs) ? Math.abs(speedDpMs) : 0f;
        float drag = Float.isFinite(dragFraction) ? dragFraction : 0f;
        // A quick flick can be much faster than 2 dp/ms, and the drag can
        // continue well past the recents threshold (drag fraction 1).
        return 3.5f + 4f * ease((speed - .22f) / (2.3f - .22f))
                + 4f * ease((speed - 2f) / (12f - 2f))
                + 5f * ease((drag - .08f) / (2.8f - .08f));
    }

    /** Choose damping for the original Y spring, using its actual, already-clamped velocity.
     * A smaller release-to-target distance may need less damping for the same recoil. */
    static float landingDamping(double travel, double towardVelocity, double frequency, double target) {
        if (!Double.isFinite(travel) || !Double.isFinite(towardVelocity)
                || !Double.isFinite(frequency) || !Double.isFinite(target)
                || travel < 0 || frequency <= 0 || target <= 0) return Float.NaN;
        double low = .48, high = 3.5;
        for (int i = 0; i < 18; i++) {
            double mid = (low + high) / 2;
            if (firstPeak(travel, towardVelocity, frequency, mid) > target) low = mid;
            else high = mid;
        }
        return (float) high;
    }

    private static double firstPeak(double d, double v, double w, double z) {
        // Solve x'(t)=0 at the first positive maximum for x(0)=-d and x'(0)=v.
        if (z < 1) {
            double decay = z * w, ring = w * Math.sqrt(1 - z * z);
            double b = (v - decay * d) / ring;
            double phase = Math.atan2(d * ring - decay * b, v) + Math.PI / 2;
            if (phase < 0) phase += 2 * Math.PI;
            return Math.max(0, Math.exp(-decay * phase / ring)
                    * (-d * Math.cos(phase) + b * Math.sin(phase)));
        }
        if (z == 1) {
            double b = v - w * d;
            return b <= 0 ? 0 : Math.exp(-v / b) * b / w;
        }
        double rate = z + Math.sqrt(z * z - 1);
        double slow = -w / rate, fast = -w * rate;
        double a = (v + d * fast) / (slow - fast);
        if (a <= 0) return 0;
        double b = -d - a;
        double time = Math.log(-b * fast / (a * slow)) / (slow - fast);
        return Math.max(0, a * Math.exp(slow * time) + b * Math.exp(fast * time));
    }

    /** Only the adaptive background fades in; its foreground keeps the icon's own alpha. */
    static float backgroundCoverage(float springProgress) {
        if (!Float.isFinite(springProgress)) return 0f;
        return ease((springProgress - .04f) / (.80f - .04f));
    }

    /** Opening keeps the icon's background over the app briefly, then reveals the window. */
    static float openingBackgroundCoverage(float springProgress) {
        if (!Float.isFinite(springProgress)) return 1f;
        // Keep the v7.22 layered-icon timing; the app surface uses Launcher鈥檚
        // own opening alpha instead of a second hand-off curve.
        return 1f - ease((springProgress - .14f) / (.96f - .14f));
    }

    static final class Dip {
        private float furthestProgress;

        float update(float progress) {
            // A position spring can cross the target repeatedly. The desktop
            // settles once, so those crossings must not restart its movement.
            if (!Float.isNaN(progress) && !Float.isInfinite(progress)) {
                furthestProgress = Math.max(furthestProgress, Math.min(1f, progress));
            }
            return springDip(furthestProgress);
        }
    }

    static boolean iconGeometry(float startWidth, float startHeight, float endWidth, float endHeight) {
        return startWidth > 0f && startHeight > startWidth && endWidth > 0f && endHeight > 0f
                && endWidth < startWidth * .48f && endHeight < startHeight * .42f;
    }

    static boolean hasBounds(float width, float height) {
        return Float.isFinite(width) && Float.isFinite(height) && width > 0f && height > 0f;
    }

    static float workspaceRow(float rowFromBottom, int gridRows) {
        return Math.max(0f, Math.min(1f, rowFromBottom / Math.max(1, gridRows)));
    }

    static float rowDistanceScale(float height) { return 1f + .35f * height; }
    static float rowSpeedScale(float height) { return 1f + .15f * height; }

    static int pageOvershoot(int width, int spaceBeyond, float releasePxPerSecond) {
        if (width <= 0) return 0;
        float speed = Float.isFinite(releasePxPerSecond)
                ? Math.max(0f, releasePxPerSecond) : 0f;
        // Keep the edge peek subtle, but make the mapping unambiguously monotonic:
        // a threshold release has a short peek and a fast fling carries farther.
        // The previous curve only changed by about 10%, which was too small to
        // distinguish from frame rounding and made the mapping feel reversed.
        float energy = ease((speed / width - .25f) / 4.2f);
        float minimum = spaceBeyond > 0 ? .032f : .012f;
        float range = spaceBeyond > 0 ? .065f : .022f;
        float amount = minimum + range * energy;
        int peak = Math.round(amount * width);
        return spaceBeyond > 0 ? Math.min(spaceBeyond, peak) : peak;
    }

    static final class PageSpring {
        final int durationMs;
        final double distance, releaseVelocity, crossingAt, peakAt, peak;
        private final double omega, decay, ring, b;
        private static final double SETTLE_RATE = 24.5, SETTLE_BLEND_SECONDS = .075;

        PageSpring(int distance, int overshoot, float releasePxPerSecond, int width) {
            this.distance = distance;
            float speed = Float.isFinite(releasePxPerSecond) ? Math.max(0f, releasePxPerSecond) : 0f;
            // Release speed must affect acceleration as well as initial velocity.
            // A fixed, stiff spring immediately catches up even from a dead stop.
            float pace = ease((speed / Math.max(1, width) - .35f) / 7f);
            omega = 10.5 + 14 * pace;
            releaseVelocity = Math.min(speed, .88 * omega * distance);
            double z = Math.min(.995, landingDamping(distance, releaseVelocity, omega, overshoot));
            decay = z * omega;
            ring = omega * Math.sqrt(1 - z * z);
            b = (releaseVelocity - decay * distance) / ring;
            crossingAt = Math.atan2(distance, b) / ring;
            peakAt = (Math.atan2(distance * ring - decay * b, releaseVelocity) + Math.PI / 2) / ring;
            peak = Math.max(0, approach(peakAt));
            // End once the remainder is below half a physical pixel. The slow
            // approach must not stretch out the whole return at the same rate.
            double lo = 0, hi = .8;
            for (int i = 0; i < 14; i++) {
                double mid = (lo + hi) * .5;
                if (settle(mid) > .45) lo = mid;
                else hi = mid;
            }
            durationMs = (int) Math.ceil((peakAt + hi) * 1000);
        }

        float fraction(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return (float) (1 + offset(t * durationMs / 1000.0) / distance);
        }

        double offset(double seconds) {
            if (seconds <= peakAt) return approach(seconds);
            return settle(seconds - peakAt);
        }

        private double settle(double seconds) {
            // Start the return at the approach rate for continuous acceleration
            // at the crest, then ease its clock toward a common settle rate.
            double u = SETTLE_RATE * seconds + (omega - SETTLE_RATE) * SETTLE_BLEND_SECONDS
                    * -Math.expm1(-seconds / SETTLE_BLEND_SECONDS);
            return peak * (1 + u) * Math.exp(-u);
        }

        private double approach(double seconds) {
            double phase = ring * seconds;
            return Math.exp(-decay * seconds) * (-distance * Math.cos(phase) + b * Math.sin(phase));
        }
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
