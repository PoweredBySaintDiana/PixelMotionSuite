package dev.pixelmotion.a10.recents;

import dev.pixelsuite.FeatureConfig;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Transfers an unfinished reveal from the gesture to its existing native clock. */
final class DockRevealTail {
    private static final WeakHashMap<View, Clock> ATTACHMENTS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Clock> HELPERS = new WeakHashMap<>();
    private static final WeakHashMap<ValueAnimator, Clock> CLOCKS = new WeakHashMap<>();
    private static final WeakHashMap<ValueAnimator, Spring> SPRINGS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Session> SESSIONS = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Session>> CURRENT = new WeakHashMap<>();
    private static final WeakHashMap<View, Tail> TAILS = new WeakHashMap<>();
    private static final ThreadLocal<Session> CAPTURING = new ThreadLocal<>();
    private static boolean installed;
    private static long nextClock;

    private DockRevealTail() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        final Class<?> recentsClass = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
        final Object adjacentProperty = Reflect.field(recentsClass, "ADJACENT_PAGE_HORIZONTAL_OFFSET").get(null);
        Class<?> builder = Class.forName("com.android.launcher3.anim.SpringAnimationBuilder", false, loader);
        Class<?> floatProperty = Class.forName("android.util.FloatProperty", false, loader);
        XposedBridge.hookMethod(Reflect.method(builder, "build", Object.class, floatProperty),
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || !recentsClass.isInstance(p.args[0])
                        || p.args[1] != adjacentProperty || !(p.getResult() instanceof ValueAnimator)) return;
                try {
                    Spring spring = new Spring(p.thisObject);
                    if (spring.usable()) {
                        SPRINGS.put((ValueAnimator) p.getResult(), spring);
                        log("capture spring start=" + spring.start + " end=" + spring.end
                                + " physicalDuration=" + spring.physicalDuration);
                    }
                } catch (Throwable error) { log("attachment spring unavailable: " + error); }
            }
        });
        Class<?> manager = Class.forName("com.android.launcher3.statemanager.StateManager", false, loader);
        XposedBridge.hookMethod(Reflect.method(manager, "createStateElementAnimation", int.class, float[].class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (((Integer) p.args[0]).intValue() != 1) return;
                try {
                    Object container = Reflect.get(p.thisObject, "mContainer");
                    Object view = Reflect.call(container, "getOverviewPanel");
                    if (!(view instanceof View)) return;
                    View recents = (View) view;
                    p.setObjectExtra("pixelDockRecents", recents);
                    float[] values = (float[]) p.args[1];
                    log("create card clock values=" + java.util.Arrays.toString(values));
                    Session session = CAPTURING.get();
                    Object owner = session == null ? null : session.owner.get();
                    if (values != null && values.length > 0
                            && Math.abs(values[values.length - 1]) <= .001f
                            && session != null && !session.closed && session.matches(recents)
                            && hasRecentsIntent(owner)) {
                        // Release the card property before build() samples its
                        // start, so Dock and cards share this exact native spring.
                        if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true))
                            TaskShelf.handToEntry(owner);
                    }
                    Clock previous = ATTACHMENTS.remove(recents);
                    if (previous != null) previous.superseded = true;
                    // StateManager cancels the previous index-1 animator inside
                    // this call. Keep its displayed position until the exact
                    // replacement is available, rather than replaying old time.
                    for (Tail tail : new ArrayList<Tail>(TAILS.values())) {
                        if (tail.recents.get() == recents && tail.attached != null) tail.waitForReplacement();
                    }
                } catch (Throwable error) { log("attachment replacement unavailable: " + error); }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (((Integer) p.args[0]).intValue() != 1) return;
                View recents = (View) p.getObjectExtra("pixelDockRecents");
                float[] values = (float[]) p.args[1];
                // Attachment (cards slide into overview) ends at offset zero.
                // Detachment ends at one and must never reveal a new dock.
                boolean entering = values != null && values.length > 0
                        && Math.abs(values[values.length - 1]) <= .001f;
                Clock replacement = null;
                try {
                    if (recents != null && entering && !p.hasThrowable()
                            && p.getResult() instanceof ValueAnimator) {
                        Session session = CAPTURING.get();
                        if (session != null && (session.closed || !session.matches(recents))) session = null;
                        replacement = new Clock(recents, (ValueAnimator) p.getResult(), "attachment", session);
                        ATTACHMENTS.put(recents, replacement);
                    }
                } catch (Throwable error) { log("attachment clock unavailable: " + error); }
                if (recents != null) for (Tail tail : new ArrayList<Tail>(TAILS.values())) {
                    if (tail.recents.get() == recents && tail.waitingReplacement) {
                        if (replacement != null && tail.accepts(replacement)) tail.followReplacement(replacement);
                        else tail.cancel();
                    }
                }
            }
        });
        Class<?> handler = Class.forName("com.android.quickstep.AbsSwipeUpHandler", false, loader);
        XposedBridge.hookMethod(Reflect.method(handler, "maybeUpdateRecentsAttachedState",
                boolean.class, boolean.class, boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("pixelDockPreviousSession", CAPTURING.get());
                CAPTURING.set(observe(p.thisObject));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                CAPTURING.set((Session) p.getObjectExtra("pixelDockPreviousSession"));
            }
        });
        XposedBridge.hookMethod(Reflect.method(handler, "onCurrentShiftUpdated"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { observe(p.thisObject); }
        });
        XposedBridge.hookMethod(Reflect.method(handler, "lambda$maybeUpdateRecentsAttachedState$11",
                ValueAnimator.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    ValueAnimator animator = (ValueAnimator) p.args[0];
                    Session session = observe(p.thisObject);
                    if (session == null || !hasRecentsIntent(p.thisObject)) return;
                    Clock previous = HELPERS.get(p.thisObject);
                    if (previous != null && previous.animator.get() == animator) return;
                    Clock known = CLOCKS.get(animator);
                    if (known != null) {
                        // Several native helper clocks may finish concurrently.
                        // An older clock's next callback must not replace the
                        // newest one or register another listener every frame.
                        if (known.session == session && !known.ended && !known.superseded
                                && (previous == null || known.serial > previous.serial)) {
                            HELPERS.put(p.thisObject, known);
                        }
                        return;
                    }
                    Object recents = Reflect.get(p.thisObject, "mRecentsView");
                    if (recents instanceof View) HELPERS.put(p.thisObject,
                            new Clock((View) recents, animator, "gesture attachment", session));
                } catch (Throwable error) { log("gesture clock unavailable: " + error); }
            }
        });
        for (String name : new String[]{"reset", "onGestureCancelled", "onRecentsAnimationStartTimedOut"}) {
            XposedBridge.hookMethod(Reflect.method(handler, name), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { close(p.thisObject); }
            });
        }
        installed = true;
        log("native reveal tail ready");
    }

    /**
     * Call before a RECENTS endpoint writes setProgress(1). True transfers the
     * panel until completion; callers must not settle or write its position.
     * The completion is called only after the tail reaches its native endpoint.
     */
    static boolean continueReveal(View recents, RecentsDockHost.Panel panel, float shelf, Object owner, Runnable completion) {
        return startReveal(recents, panel, shelf, owner, completion, true);
    }

    /** Take over as soon as Recents intent and its card clock are available. */
    static boolean beginReveal(View recents, RecentsDockHost.Panel panel, float shelf, Object owner, Runnable completion) {
        return startReveal(recents, panel, shelf, owner, completion, false);
    }

    private static boolean startReveal(View recents, RecentsDockHost.Panel panel, float shelf, Object owner,
            Runnable completion, boolean committed) {
        if (recents == null || panel == null || shelf <= 0f || panel.getWindowToken() == null) return false;
        Tail current = TAILS.get(panel);
        if (current != null && current.valid() && current.sameOwner(owner)) return true;
        cancel(panel);
        float y = panel.revealOffset;
        if (!Float.isFinite(y) || Math.abs(y) <= .5f) return false;
        Clock clock = selectClock(recents, owner);
        if (clock == null) {
            if (!committed && owner != null) return false;
            if (owner != null && !endsInRecents(owner)) return false;
            Session session = owner == null ? null : SESSIONS.get(owner);
            if (owner != null && (session == null || !session.matches(recents))) return false;
            ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
            animation.setDuration(300L);
            animation.setInterpolator(new LinearInterpolator());
            clock = new Clock(recents, animation, "fallback", session, true);
        }
        Tail tail = new Tail(recents, panel, clock, y, completion);
        TAILS.put(panel, tail);
        clock.consumed = true;
        tail.start();
        return true;
    }

    static boolean hasAvailableClock(View recents, Object owner) {
        return selectClock(recents, owner) != null;
    }

    /** Include in Host's active-transition guard and gesture-write guard. */
    static boolean isActive(View panel) {
        Tail tail = panel == null ? null : TAILS.get(panel);
        return tail != null && tail.valid();
    }

    /** Call before HOME/app launch/new gesture/detach; never calls completion. */
    static void cancel(View panel) {
        Tail tail = panel == null ? null : TAILS.remove(panel);
        if (tail != null) tail.cancel();
    }

    private static Clock selectClock(View recents, Object owner) {
        Clock attachment = ATTACHMENTS.get(recents);
        if (attachment != null && attachment.available(recents, owner)) return attachment;
        Clock helper = owner == null ? null : HELPERS.get(owner);
        return helper != null && helper.available(recents, owner) ? helper : null;
    }

    private static Session observe(Object owner) {
        try {
            Object view = Reflect.get(owner, "mRecentsView");
            if (!(view instanceof View)) return null;
            View recents = (View) view;
            Session session = SESSIONS.get(owner);
            if (session != null) return session.matches(recents) && !session.closed ? session : null;
            session = new Session(owner, recents);
            SESSIONS.put(owner, session);
            WeakReference<Session> previous = CURRENT.put(recents, new WeakReference<>(session));
            Session old = previous == null ? null : previous.get();
            if (old != null && old != session) old.superseded = true;
            for (Tail tail : new ArrayList<Tail>(TAILS.values())) {
                if (tail.recents.get() == recents) tail.cancel();
            }
            return session;
        } catch (Throwable ignored) { return null; }
    }

    private static void close(Object owner) {
        Session session = SESSIONS.get(owner);
        if (session != null) session.closed = true;
        HELPERS.remove(owner);
        if (session != null) {
            View recents = session.recents.get();
            Clock clock = recents == null ? null : ATTACHMENTS.get(recents);
            if (clock != null && clock.session == session) ATTACHMENTS.remove(recents);
            if (!endsInRecents(owner)) for (Tail tail : new ArrayList<Tail>(TAILS.values())) {
                if (tail.clock.session == session) tail.cancel();
            }
        }
    }

    private static boolean hasRecentsIntent(Object owner) {
        if (owner == null) return false;
        try {
            if (Boolean.TRUE.equals(Reflect.get(owner, "mIsLikelyToStartNewTask"))) return false;
            Object target = Reflect.call(Reflect.get(owner, "mGestureState"), "getEndTarget");
            return target == null ? Boolean.TRUE.equals(Reflect.get(owner, "mHasMotionEverBeenPaused"))
                    : "RECENTS".equals(target.toString());
        } catch (Throwable ignored) { return false; }
    }

    private static boolean endsInRecents(Object owner) {
        try { return "RECENTS".equals(String.valueOf(
                Reflect.call(Reflect.get(owner, "mGestureState"), "getEndTarget"))); }
        catch (Throwable ignored) { return false; }
    }

    private static final class Session {
        final WeakReference<Object> owner;
        final WeakReference<View> recents;
        boolean closed, superseded;
        Session(Object owner, View recents) {
            this.owner = new WeakReference<>(owner);
            this.recents = new WeakReference<>(recents);
        }
        boolean matches(View view) {
            WeakReference<Session> reference = CURRENT.get(view);
            return !superseded && recents.get() == view && reference != null && reference.get() == this;
        }
    }

    /** Immutable physical coefficients; animator duration is a separate clock. */
    private static final class Spring {
        final double a, b, beta, gamma, physicalDuration, start, end;
        Spring(Object builder) throws Throwable {
            a = ((Number) Reflect.get(builder, "a")).doubleValue();
            b = ((Number) Reflect.get(builder, "b")).doubleValue();
            beta = ((Number) Reflect.get(builder, "beta")).doubleValue();
            gamma = ((Number) Reflect.get(builder, "gamma")).doubleValue();
            physicalDuration = ((Number) Reflect.get(builder, "mDuration")).doubleValue();
            start = ((Number) Reflect.get(builder, "mStartValue")).doubleValue();
            end = ((Number) Reflect.get(builder, "mEndValue")).doubleValue();
        }
        boolean usable() {
            return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(beta)
                    && Double.isFinite(gamma) && Double.isFinite(physicalDuration)
                    && Double.isFinite(start) && Double.isFinite(end)
                    && gamma > 0 && physicalDuration > 0 && Math.abs(start - end) > .0001;
        }
        float remaining(float raw) {
            if (raw >= 1f) return 0f; // native success listener writes mEndValue
            double time = physicalDuration * clamp(raw);
            double angle = gamma * time;
            return (float) (Math.exp(-beta * time / 2)
                    * (a * Math.cos(angle) + b * Math.sin(angle)) / (start - end));
        }
    }

    private static final class Clock extends AnimatorListenerAdapter implements ValueAnimator.AnimatorUpdateListener {
        final WeakReference<View> recents;
        final WeakReference<ValueAnimator> animator;
        final String name;
        final Session session;
        final Spring spring;
        final boolean synthetic;
        final long serial;
        long duration, playTime;
        float raw;
        boolean ended, cancelled, consumed, superseded, cancelledForRecents;
        Clock(View view, ValueAnimator animation, String name, Session session) {
            this(view, animation, name, session, false);
        }
        Clock(View view, ValueAnimator animation, String name, Session session, boolean synthetic) {
            recents = new WeakReference<>(view);
            animator = new WeakReference<>(animation);
            this.name = name;
            this.session = session;
            this.synthetic = synthetic;
            spring = SPRINGS.get(animation);
            serial = ++nextClock;
            CLOCKS.put(animation, this);
            sample(animation);
            animation.addUpdateListener(this);
            animation.addListener(this);
        }
        void sample(ValueAnimator animation) {
            duration = animation.getDuration();
            playTime = Math.max(0L, Math.min(duration, animation.getCurrentPlayTime()));
            raw = duration <= 0L ? 1f : clamp(playTime / (float) duration);
        }
        boolean available(View view, Object owner) {
            ValueAnimator animation = animator.get();
            if (consumed || superseded || ended && !cancelled || recents.get() != view || animation == null) return false;
            if (session != null) {
                if (owner == null || session.owner.get() != owner || session.closed
                        || !session.matches(view) || !hasRecentsIntent(owner)) return false;
            } else if (owner != null || cancelled) return false;
            if (!ended && !cancelled) sample(animation);
            // StateManager returns this animator before its caller sets 300ms
            // and starts it. Listening now preserves the complete entry curve.
            return duration > 0L && playTime < duration && (!cancelled || cancelledForRecents);
        }
        float remaining(float raw) {
            return spring == null ? 1f - DockEntryCurve.overview(clamp(raw)) : spring.remaining(raw);
        }
        @Override public void onAnimationStart(Animator animation) { sample((ValueAnimator) animation); }
        @Override public void onAnimationUpdate(ValueAnimator animation) { sample(animation); }
        @Override public void onAnimationCancel(Animator animation) {
            // cancel() does not preserve fraction on every Android build. The
            // last actual update is the authoritative last displayed frame.
            cancelled = true;
            cancelledForRecents = session != null && !session.closed && !superseded
                    && session.matches(recents.get()) && endsInRecents(session.owner.get());
        }
        @Override public void onAnimationEnd(Animator animation) { ended = true; }
    }

    private static final class Tail extends AnimatorListenerAdapter implements ValueAnimator.AnimatorUpdateListener {
        final WeakReference<View> recents;
        final WeakReference<RecentsDockHost.Panel> panel;
        final WeakReference<ViewParent> parent;
        Clock clock;
        final Runnable completion;
        ValueAnimator attached, continuation;
        float fromY, fromRaw, fromRemaining, minY;
        long lastPlayTime;
        boolean finished, handedOff, waitingReplacement;

        Tail(View recents, RecentsDockHost.Panel panel, Clock clock, float y, Runnable completion) {
            this.recents = new WeakReference<>(recents);
            this.panel = new WeakReference<>(panel);
            parent = new WeakReference<ViewParent>(panel.getParent());
            this.clock = clock;
            this.completion = completion;
            fromY = y;
            minY = y;
        }

        boolean valid() {
            RecentsDockHost.Panel view = panel.get();
            View overview = recents.get();
            return !finished && view != null && overview != null && TAILS.get(view) == this
                    && view.getWindowToken() != null && overview.getWindowToken() != null
                    && view.getParent() == parent.get()
                    && (clock.session == null || clock.session.matches(overview));
        }

        boolean sameOwner(Object owner) {
            return clock.session == null ? owner == null : owner == null || clock.session.owner.get() == owner;
        }

        boolean accepts(Clock replacement) {
            return replacement.session == clock.session && !replacement.superseded
                    && !replacement.ended && !replacement.cancelled
                    && (replacement.session == null || !replacement.session.closed
                            && hasRecentsIntent(replacement.session.owner.get()));
        }

        void waitForReplacement() {
            waitingReplacement = true;
            if (attached != null) {
                attached.removeUpdateListener(this);
                attached.removeListener(this);
            }
            if (continuation != null) {
                continuation.removeAllUpdateListeners();
                continuation.removeAllListeners();
                continuation.cancel();
                continuation = null;
            }
        }

        void followReplacement(Clock replacement) {
            if (!valid()) { cancel(); return; }
            clock = replacement;
            replacement.consumed = true;
            fromY = panel.get().revealOffset;
            handedOff = false;
            waitingReplacement = false;
            start();
        }

        void start() {
            attached = clock.animator.get();
            if (attached == null) { cancel(); return; }
            if (!clock.cancelled && !clock.ended) clock.sample(attached);
            fromRaw = clock.raw;
            fromRemaining = clock.remaining(fromRaw);
            lastPlayTime = clock.playTime;
            RecentsDockHost.Panel view = panel.get();
            if (view != null) { view.setAlpha(1f); view.setVisibility(View.VISIBLE); }
            log("begin " + clock.name + " fromY=" + fromY + " raw=" + fromRaw
                    + " remaining=" + (clock.duration - lastPlayTime) + " spring=" + (clock.spring != null));
            // A late capture near a spring's zero crossing cannot be divided
            // safely. Continue from actual Y with one complete, scaled entry.
            if (fromRaw > .8f || clock.duration - lastPlayTime < 80L
                    || !Float.isFinite(fromRemaining) || Math.abs(fromRemaining) < .08f
                    || fromY * fromRemaining < 0f) startFallback();
            else if (clock.cancelled) continueCancelledClock();
            else {
                attached.addUpdateListener(this);
                attached.addListener(this);
                if (clock.synthetic) attached.start();
            }
        }

        @Override public void onAnimationStart(Animator animation) {
            if (animation == attached && !handedOff && !waitingReplacement) clock.sample(attached);
        }

        @Override public void onAnimationUpdate(ValueAnimator animation) {
            if (!valid()) { cancel(); return; }
            if (animation != attached || handedOff || waitingReplacement) return;
            clock.sample(animation);
            lastPlayTime = clock.playTime;
            apply(clock.raw);
        }

        void apply(float raw) {
            if (!valid()) { cancel(); return; }
            float y = raw <= fromRaw ? fromY : fromY * clock.remaining(raw) / fromRemaining;
            write(y);
        }

        void write(float y) {
            if (!Float.isFinite(y)) { cancel(); return; }
            minY = Math.min(minY, y);
            panel.get().setRevealOffset(y);
        }

        @Override public void onAnimationCancel(Animator animation) {
            if (animation != attached || finished || handedOff || waitingReplacement) return;
            if (clock.superseded || clock.session == null
                    || !endsInRecents(clock.session.owner.get())) { cancel(); return; }
            continueCancelledClock();
        }

        @Override public void onAnimationEnd(Animator animation) {
            if (animation != attached || finished || handedOff || waitingReplacement) return;
            finish();
        }

        void continueCancelledClock() {
            if (!valid()) { cancel(); return; }
            handedOff = true;
            final ValueAnimator original = attached;
            original.removeUpdateListener(this);
            original.removeListener(this);
            final long fullDuration = clock.duration;
            final long remaining = Math.max(0L, fullDuration - lastPlayTime);
            if (remaining == 0L) { finish(); return; }
            final float startRaw = clamp(lastPlayTime / (float) fullDuration);
            // Continue only the unfinished native interval. Android applies
            // the current developer animation-duration scale exactly once.
            continuation = ValueAnimator.ofFloat(startRaw, 1f);
            continuation.setDuration(remaining);
            continuation.setInterpolator(new LinearInterpolator());
            continuation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    if (!valid()) { cancel(); return; }
                    float raw = ((Number) animation.getAnimatedValue()).floatValue();
                    apply(raw);
                }
            });
            continuation.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) { if (!cancelled) finish(); }
            });
            log("native reveal cancelled; continue remaining=" + remaining + " raw=" + startRaw);
            continuation.start();
        }

        void startFallback() {
            handedOff = true;
            attached.removeUpdateListener(this);
            attached.removeListener(this);
            fromY = panel.get().revealOffset;
            continuation = ValueAnimator.ofFloat(0f, 1f);
            continuation.setDuration(300L);
            continuation.setInterpolator(new LinearInterpolator());
            continuation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    if (!valid()) { cancel(); return; }
                    float raw = ((Number) animation.getAnimatedValue()).floatValue();
                    write(fromY * (1f - DockEntryCurve.overview(raw)));
                }
            });
            continuation.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) { if (!cancelled) finish(); }
            });
            log("late clock fallback fromY=" + fromY + " raw=" + fromRaw + " duration=300");
            continuation.start();
        }

        void finish() {
            if (!valid()) { cancel(); return; }
            RecentsDockHost.Panel view = panel.get();
            view.setRevealOffset(0f);
            log("finish " + clock.name + " rawStart=" + fromRaw + " minY=" + minY + " endY=0");
            release();
            if (completion != null) completion.run();
        }

        void cancel() { release(); }

        void release() {
            if (finished) return;
            finished = true;
            RecentsDockHost.Panel view = panel.get();
            if (view != null && TAILS.get(view) == this) TAILS.remove(view);
            if (attached != null) {
                attached.removeUpdateListener(this);
                attached.removeListener(this);
            }
            if (continuation != null) {
                continuation.removeAllUpdateListeners();
                continuation.removeAllListeners();
                continuation.cancel();
            }
        }
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static void log(String text) { android.util.Log.i("PixelRecentsA10", "dock reveal tail " + text); }
}
