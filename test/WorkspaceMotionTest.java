package dev.pixelmotion.a11.motion;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import java.util.Arrays;

public final class WorkspaceMotionTest {
    private static int checks;

    private static void near(float actual, float expected) {
        checks++;
        if (Math.abs(actual - expected) > .002f)
            throw new AssertionError("check " + checks + ": " + actual + " vs " + expected);
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    private static ValueAnimator first() { return (ValueAnimator) AnimatorSet.latest.children.get(0); }

    public static void main(String[] args) throws Exception {
        WorkspaceMotion.install();
        Context context = new Context();
        WorkspaceMotion motion = new WorkspaceMotion(WorkspaceMotionTest.class.getClassLoader());
        View view = new View();
        view.setTranslationY(23f);
        int[] clips = {0};
        Runnable acquire = () -> clips[0]++;
        Runnable release = () -> clips[0]--;

        motion.animate(context, Arrays.asList(view), Arrays.asList(90f), Arrays.asList(15L), true, acquire, release);
        near(view.y, 23f);
        check(first().delay == 15);
        first().sample(85);
        float before = view.y;
        ValueAnimator old = first();
        motion.animate(context, Arrays.asList(view), Arrays.asList(90f), Arrays.asList(15L), false, acquire, release);
        near(view.y, before);
        check(first().velocity < -1);
        check(first().delay == 0 && clips[0] == 1);
        for (android.animation.AnimatorListenerAdapter callback : old.listeners) callback.onAnimationEnd(old);
        near(view.y, before);

        // A native state/layout update while a module spring runs must survive its completion.
        view.setTranslationY(47f);
        near(view.y - 47f, before - 23f);
        first().sample(60);
        AnimatorSet.latest.end();
        near(view.y, 47f);
        check(!motion.active() && !motion.raised() && clips[0] == 0);

        // Revealing the desktop without a prior launch may seed its covered start.
        motion.animate(context, Arrays.asList(view), Arrays.asList(80f), Arrays.asList(0L), false, acquire, release);
        near(view.y, -33f);
        first().sample(50);
        before = view.y;
        motion.animate(context, Arrays.asList(view), Arrays.asList(80f), Arrays.asList(0L), true, acquire, release);
        near(view.y, before);
        check(first().velocity > 1);
        AnimatorSet.latest.end();
        near(view.y, -33f);

        // A page switch releases the old page and retains the dock's current offset.
        View next = new View();
        next.setTranslationY(11);
        motion.animate(context, Arrays.asList(next), Arrays.asList(80f), Arrays.asList(0L), true, acquire, release);
        near(view.y, 47f);
        near(next.y, 11f);
        first().sample(70);
        before = next.y;
        motion.settle(context);
        near(next.y, before);
        check(clips[0] == 1);
        AnimatorSet.latest.end();
        near(next.y, 11f);
        check(clips[0] == 0);

        // Rapid successive reversals must never accumulate offsets or leave child rows shifted.
        for (int i = 0; i < 60; i++) {
            motion.animate(context, Arrays.asList(view, next), Arrays.asList(90f, 20f),
                    Arrays.asList(0L, 15L), (i & 1) == 0, acquire, release);
            for (android.animation.Animator animator : AnimatorSet.latest.children)
                ((ValueAnimator) animator).sample(30);
            check(clips[0] == 1);
        }
        motion.clear();
        near(view.y, 47f);
        near(next.y, 11f);
        check(clips[0] == 0);
        view.setTranslationY(71f);
        near(view.y, 71f);
        System.out.println("PASS: " + checks + " workspace interruption, native layout, page replacement and cleanup assertions");
    }
}
