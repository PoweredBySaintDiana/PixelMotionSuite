package dev.pixelmotion.a11.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.FloatProperty;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.WeakHashMap;

/** Keeps the module's spring offset separate from Launcher's layout translation. */
final class WorkspaceMotion {
    private static final WeakHashMap<View, Offset> OFFSETS = new WeakHashMap<>();
    private static final FloatProperty<Offset> OFFSET = new FloatProperty<Offset>("pixelWorkspaceOffset") {
        @Override public Float get(Offset target) { return target.value; }
        @Override public void setValue(Offset target, float value) { target.write(value); }
    };
    private static final float STIFFNESS = 150f;
    private static final float DAMPING = .7f;
    private final IdentityHashMap<View, Offset> owned = new IdentityHashMap<>();
    private final Constructor<?> constructor;
    private final Field start, end, velocity;
    private final Method stiffness, damping, minimum, build;
    private AnimatorSet running;
    private boolean rising;
    private Runnable prepare;
    private Runnable cleanup;

    static void install() throws NoSuchMethodException {
        XposedBridge.hookMethod(View.class.getDeclaredMethod("setTranslationY", float.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!Motion.onUiThread()) return;
                Offset offset = OFFSETS.get(p.thisObject);
                if (offset == null || offset.writing) return;
                float value = (Float) p.args[0];
                if (!Float.isFinite(value)) return;
                offset.base = value;
                p.args[0] = value + offset.value;
            }
        });
    }

    WorkspaceMotion(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Motion.load("com.android.launcher3.anim.SpringAnimationBuilder", loader);
        constructor = type.getDeclaredConstructor(Context.class);
        start = Motion.field(type, "mStartValue");
        end = Motion.field(type, "mEndValue");
        velocity = Motion.field(type, "mVelocity");
        stiffness = Motion.method(type, "setStiffness", float.class);
        damping = Motion.method(type, "setDampingRatio", float.class);
        minimum = Motion.method(type, "setMinimumVisibleChange", float.class);
        build = Motion.method(type, "build", Object.class, FloatProperty.class);
    }

    boolean active() { return running != null && running.isStarted(); }
    boolean rising() { return rising; }

    boolean raised() {
        for (Offset offset : owned.values()) if (Math.abs(offset.value) > .025f) return true;
        return false;
    }

    void animate(Context context, List<View> views, List<Float> destinations, List<Long> delays,
            boolean nextRising, Runnable started, Runnable finished) throws ReflectiveOperationException {
        animate(context, views, destinations, delays, nextRising, false, started, finished);
    }

    void returnToHome(Context context, List<View> views, List<Float> distances, List<Long> delays,
            Runnable started, Runnable finished) throws ReflectiveOperationException {
        animate(context, views, distances, delays, false, true, started, finished);
    }

    private void animate(Context context, List<View> views, List<Float> destinations, List<Long> delays,
            boolean nextRising, boolean committedReturn, Runnable started, Runnable finished)
            throws ReflectiveOperationException {
        boolean inFlight = active();
        // A held launch pose is not a running spring. A fresh close must use
        // this gesture's distance instead of the fixed launch-rise distance.
        boolean seedReturn = committedReturn && !inFlight && rising;
        boolean continuing = inFlight || (raised() && !seedReturn);
        boolean wasRising = rising;
        for (Offset offset : owned.values()) offset.captureVelocity();
        cancel();
        started.run();
        prepare = started;
        cleanup = finished;
        rising = nextRising;
        IdentityHashMap<View, Boolean> next = new IdentityHashMap<>();
        for (View view : views) next.put(view, true);
        for (View view : new ArrayList<>(owned.keySet())) {
            if (!next.containsKey(view)) release(view);
        }
        AnimatorSet set = new AnimatorSet();
        try {
            for (int i = 0; i < views.size(); i++) {
                View view = views.get(i);
                Offset offset = owned.get(view);
                boolean reused = offset != null;
                if (offset == null) {
                    offset = new Offset(view);
                    owned.put(view, offset);
                    OFFSETS.put(view, offset);
                }
                if (!nextRising && (seedReturn || (!reused && !continuing)))
                    offset.write(-destinations.get(i));
                float target = nextRising ? -destinations.get(i) : 0f;
                float initialVelocity = reused ? offset.speed : 0f;
                if (Math.abs(target - offset.value) < .025f && Math.abs(initialVelocity) < 1f) {
                    offset.write(target);
                    continue;
                }
                Object builder = constructor.newInstance(context);
                start.setFloat(builder, offset.value);
                end.setFloat(builder, target);
                velocity.setFloat(builder, initialVelocity);
                stiffness.invoke(builder, STIFFNESS);
                damping.invoke(builder, DAMPING);
                minimum.invoke(builder, 1f);
                ValueAnimator animator = (ValueAnimator) build.invoke(builder, offset, OFFSET);
                offset.animator = animator;
                offset.springStart = offset.value;
                offset.springEnd = target;
                offset.springVelocity = initialVelocity;
                offset.speed = 0f;
                // Preserve the native row delay on a fresh run. A takeover starts on this frame.
                animator.setStartDelay(continuing ? 0L : delays.get(i));
                set.play(animator);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            clear();
            throw error;
        }
        if (set.getChildAnimations().isEmpty()) {
            if (!nextRising) clear();
            finish();
            return;
        }
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (running != set) return;
                running = null;
                for (Offset offset : owned.values()) {
                    offset.animator = null;
                    offset.speed = 0f;
                }
                if (!rising) clear();
                finish();
            }
            @Override public void onAnimationCancel(Animator animation) {
                if (running != set) return;
                running = null;
                clear();
                finish();
            }
        });
        running = set;
        set.start();
        Motion.log("workspace " + (nextRising ? "rise" : "sink") + " views=" + views.size()
                + " takeover=" + continuing + " reverse=" + (continuing && nextRising != wasRising));
    }

    private void cancel() {
        AnimatorSet previous = running;
        running = null;
        if (previous != null) previous.cancel();
        for (Offset offset : owned.values()) offset.animator = null;
        finish();
    }

    private void finish() {
        Runnable previous = cleanup;
        cleanup = null;
        if (previous != null) previous.run();
    }

    void clear() {
        cancel();
        for (View view : new ArrayList<>(owned.keySet())) release(view);
        prepare = null;
    }

    void settle(Context context) throws ReflectiveOperationException {
        if (owned.isEmpty() || (active() && !rising)) return;
        List<View> views = new ArrayList<>(owned.keySet());
        List<Float> destinations = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        for (View view : views) {
            destinations.add(0f);
            delays.add(0L);
        }
        Runnable started = prepare == null ? () -> { } : prepare;
        Runnable finished = cleanup == null ? () -> { } : cleanup;
        animate(context, views, destinations, delays, false, started, finished);
    }

    private void release(View view) {
        Offset offset = owned.remove(view);
        if (offset == null) return;
        offset.write(0f);
        if (OFFSETS.get(view) == offset) OFFSETS.remove(view);
    }

    private static final class Offset {
        final java.lang.ref.WeakReference<View> view;
        float base, value, speed, springStart, springEnd, springVelocity;
        boolean writing;
        ValueAnimator animator;

        Offset(View view) {
            this.view = new java.lang.ref.WeakReference<>(view);
            base = view.getTranslationY();
        }

        void write(float next) {
            View target = view.get();
            if (target == null || !Float.isFinite(next)) return;
            value = next;
            writing = true;
            try { target.setTranslationY(base + next); }
            finally { writing = false; }
        }

        void captureVelocity() {
            speed = 0f;
            if (animator == null || !animator.isRunning()) return;
            double time = animator.getCurrentPlayTime() / 1000.0;
            double decay = DAMPING * Math.sqrt(STIFFNESS);
            double frequency = Math.sqrt(STIFFNESS * (1 - DAMPING * DAMPING));
            double a = springStart - springEnd;
            double b = (springVelocity + decay * a) / frequency;
            double phase = frequency * time;
            speed = (float) (Math.exp(-decay * time) * ((b * frequency - decay * a) * Math.cos(phase)
                    - (a * frequency + decay * b) * Math.sin(phase)));
        }
    }
}
