package dev.pixelmotion.a11.motion;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import com.android.launcher3.*;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;

public final class OpeningContentTest {
    private static void check(boolean actual) { if (!actual) throw new AssertionError(); }

    static void checkSource(OpeningIconSource matcher, View host, View icon, Rect target, boolean expected) {
        View container = new View();
        container.parent = host;
        icon.parent = container;
        RectF source = new RectF();
        int left = target.left, top = target.top, right = target.right, bottom = target.bottom;
        check(matcher.matches(icon, target, source) == expected);
        check(target.left == left && target.top == top && target.right == right && target.bottom == bottom);
    }

    static double cubic(double t, double start, double first, double second, double end) {
        double u = 1 - t;
        return u*u*u*start + 3*u*u*t*first + 3*u*t*t*second + t*t*t*end;
    }

    static void near(float actual, double expected) {
        if (Math.abs(actual - expected) > .0001) throw new AssertionError(actual + " vs " + expected);
    }

    static void checkTiming() {
        OpeningTiming lower = OpeningTiming.LOWER_OR_NEAR_CENTER;
        OpeningTiming upper = OpeningTiming.UPPER;
        check(OpeningTiming.select(1500, 1548, 1200, 180) == lower);
        check(OpeningTiming.select(400, 448, 1200, 180) == upper);
        check(OpeningTiming.select(1000, 1048, 1200, 180) == lower);
        check(OpeningTiming.select(972, 1020, 1200, 180) == upper);
        check(OpeningTiming.select(973, 1021, 1200, 180) == lower);
        for (int i = 0; i <= 1000; i++) {
            double t = i / 1000.0;
            // Evaluate the original 701 path in absolute coordinates. The
            // implementation independently inverts each normalized segment.
            for (boolean first : new boolean[]{true, false}) {
                double x = first ? cubic(t, 0, .05, .133333, .166666)
                        : cubic(t, .166666, .225, .5, 1);
                double y = first ? cubic(t, 0, 0, .08, .4) : cubic(t, .4, .94, 1, 1);
                near(OpeningTiming.SCALE.getInterpolation((float)x), y);
                near(OpeningTiming.CROP_AND_RADIUS.getInterpolation((float)(x * 375 / 450)), y);
            }
            double x = cubic(t, 0, .2, 0, 1);
            double y = cubic(t, 0, 0, 1, 1);
            near(lower.x.getInterpolation((float)(x * 250 / 450)), y);
            near(lower.y.getInterpolation((float)x), y);
            near(upper.x.getInterpolation((float)(x * 360 / 450)), y);
            near(upper.y.getInterpolation((float)(x * 200 / 450)), y);
        }
        for (int ms = 0; ms <= 450; ms++) {
            near(lower.fade.getInterpolation(ms / 450f), Math.max(0, Math.min(1, (ms-25) / 50.0)));
            near(upper.fade.getInterpolation(ms / 450f), Math.max(0, Math.min(1, (ms-25) / 40.0)));
        }
        near(OpeningTiming.CROP_AND_RADIUS.getInterpolation(1f), 1);
        near(upper.y.getInterpolation(1f), 1);
        near(lower.x.getInterpolation(1f), 1);
    }

    public static void main(String[] args) throws Throwable {
        checkTiming();
        OpeningIconSource matcher = OpeningIconSource.load(OpeningContentTest.class.getClassLoader());
        for (View host : new View[]{new Workspace(), new ActivityAllAppsContainerView(), new Hotseat()}) {
            checkSource(matcher, host, new BubbleTextView(), new Rect(1080, 2400), true);
            checkSource(matcher, host, new BubbleTextView(), new Rect(1080, 2320), true);
            checkSource(matcher, host, new BubbleTextView(), new Rect(1080, 2319), false);
            checkSource(matcher, host, new BubbleTextView(), new Rect(540, 2000), false);
            checkSource(matcher, host, new View(), new Rect(1080, 2400), false);
            Rect shifted = new Rect(1080, 2400);
            shifted.left = 32;
            shifted.right += 32;
            checkSource(matcher, host, new BubbleTextView(), shifted, false);
            host.width = 2400;
            host.height = 1080;
            checkSource(matcher, host, new BubbleTextView(), new Rect(2400, 1080), true);
        }
        checkSource(matcher, new View(), new BubbleTextView(), new Rect(1080, 2400), false);
        System.out.println("PASS: Launcher 701 timing and icon sources across full-screen, inset, landscape and split bounds");
    }
}
