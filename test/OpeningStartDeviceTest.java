package dev.pixelmotion.a11.motion;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.os.Looper;
import android.view.animation.LinearInterpolator;
import java.util.IdentityHashMap;

public final class OpeningStartDeviceTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void run(long delay) {
        ValueAnimator icon = ValueAnimator.ofFloat(0f, 450f);
        icon.setDuration(450);
        icon.setInterpolator(new LinearInterpolator());
        AnimatorSet group = new AnimatorSet();
        group.playTogether(icon, new AnimatorSet());
        group.setStartDelay(delay);
        AnimatorSet root = new AnimatorSet();
        root.play(group);
        IdentityHashMap<Object, Boolean> marked = new IdentityHashMap<>();
        marked.put(icon, true);
        check(OpeningStart.advance(root, marked) == -1, "before start");
        root.start();
        marked.put(icon, false);
        check(OpeningStart.advance(root, marked) == -1, "other runner");
        marked.put(icon, true);
        check(OpeningStart.advance(root, marked) == delay, "launch seek");
        check(root.getCurrentPlayTime() == delay + 16, "parent play time");
        check(Math.abs((Float) icon.getAnimatedValue() - 16f) < .02f, "icon time " + icon.getAnimatedValue());
        check(OpeningStart.advance(root, marked) == -1, "second seek");
        root.setCurrentPlayTime(delay + 90);
        check(OpeningStart.advance(root, marked) == -1, "do not rewind");
        root.end();
        check(OpeningStart.advance(root, marked) == -1, "ended");
    }

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        for (long delay : new long[]{0, 8, 11, 16}) run(delay);
        System.out.println("PASS: Android AnimatorSet delivers icon time 16 ms across all wrapper delays");
    }
}
