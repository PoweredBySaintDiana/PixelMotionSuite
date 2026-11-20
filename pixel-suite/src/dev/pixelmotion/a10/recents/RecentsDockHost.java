package dev.pixelmotion.a10.recents;

import dev.pixelsuite.FeatureConfig;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Property;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** A top-level overview dock, with new views and original ItemInfo. */
final class RecentsDockHost {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, WeakReference<Panel>> PANELS = new WeakHashMap<>();
    private static final WeakHashMap<View, Long> CANCELLED_TOUCHES = new WeakHashMap<>();
    private static final WeakHashMap<Object, RecentsReveal> REVEALS = new WeakHashMap<>();
    private static Class<?> itemClass;
    private static Class<?> workspaceItemClass;
    private static Class<?> activityContextClass;
    private static Class<?> launcherClass;
    private static Class<?> recentsStateClass;
    private static Class<?> animatorListenerClass;
    private static Class<?> springUpdateListenerClass;
    private static Class<?> consumerClass;
    private static Object overviewState, hintState, normalState, allAppsState;
    private static Method launchMethod;
    private static Method displayInfoMethod;
    private static Method disabledClickMethod;
    private static Constructor<?> hostLayoutConstructor;
    private static Constructor<?> qsbConstructor;
    private static boolean loggedError;

    private RecentsDockHost() { }

    static void install(ClassLoader loader) throws Throwable {
        itemClass = Class.forName("com.android.launcher3.model.data.ItemInfo", false, loader);
        workspaceItemClass = Class.forName("com.android.launcher3.model.data.WorkspaceItemInfo", false, loader);
        activityContextClass = Class.forName("com.android.launcher3.views.ActivityContext", false, loader);
        launcherClass = Class.forName("com.android.launcher3.Launcher", false, loader);
        recentsStateClass = Class.forName("com.android.quickstep.fallback.RecentsState", false, loader);
        animatorListenerClass = Class.forName("android.animation.Animator$AnimatorListener", false, loader);
        springUpdateListenerClass = Class.forName(
                "com.android.quickstep.util.RectFSpringAnim$OnUpdateListener", false, loader);
        consumerClass = Class.forName("java.util.function.Consumer", false, loader);
        Class<?> launcherState = Class.forName("com.android.launcher3.LauncherState", false, loader);
        overviewState = get(launcherState, "OVERVIEW");
        hintState = get(launcherState, "HINT_STATE");
        normalState = get(launcherState, "NORMAL");
        allAppsState = get(launcherState, "ALL_APPS");
        Class<?> clickClass = Class.forName("com.android.launcher3.touch.ItemClickHandler", false, loader);
        disabledClickMethod = clickClass.getDeclaredMethod("handleDisabledItemClicked",
                workspaceItemClass, Context.class);
        disabledClickMethod.setAccessible(true);
        launchMethod = activityContextClass.getDeclaredMethod(
                "startActivitySafely", View.class, Intent.class, itemClass);
        launchMethod.setAccessible(true);
        displayInfoMethod = Class.forName("com.android.launcher3.display.DisplayController", false, loader)
                .getDeclaredMethod("getInfo", Context.class);
        displayInfoMethod.setAccessible(true);
        Class<?> paramsClass = Class.forName(
                "com.android.launcher3.views.BaseDragLayer$LayoutParams", false, loader);
        hostLayoutConstructor = paramsClass.getDeclaredConstructor(int.class, int.class);
        hostLayoutConstructor.setAccessible(true);
        qsbConstructor = Class.forName("com.android.launcher3.qsb.OseWidgetView", false, loader)
                .getDeclaredConstructor(Context.class);
        qsbConstructor.setAccessible(true);
        DockSuggestions.install(loader);
        PredictionIcons.install(loader);
        if (FeatureConfig.enabled(FeatureConfig.RECENTS_FLOAT, true))
            DockAppBubble.install(loader);
        Class<?> dragLayerClass = Class.forName("com.android.launcher3.views.BaseDragLayer", false, loader);
        XC_MethodHook routeDockTouches = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof ViewGroup) || !(p.args[0] instanceof MotionEvent)) return;
                if (routeTouch((ViewGroup) p.thisObject, (MotionEvent) p.args[0])) p.setResult(Boolean.TRUE);
            }
        };
        XposedBridge.hookMethod(dragLayerClass.getDeclaredMethod(
                "dispatchTouchEvent", MotionEvent.class), routeDockTouches);
        // LauncherInputConsumer can enter here without calling dispatchTouchEvent.
        // The original method calls FrameLayout.super, so both entry points need hooks.
        XposedBridge.hookMethod(dragLayerClass.getDeclaredMethod(
                "proxyTouchEvent", MotionEvent.class, boolean.class), routeDockTouches);
        // OseWidgetView already supplies native search/voice/Lens click listeners.
        // Close the window after its native launch as we do for dock application clicks.
        XposedBridge.hookMethod(launchMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.getResult() == null || !(p.args[0] instanceof View)) return;
                Panel panel = panelAncestor((View) p.args[0]);
                if (panel != null) panel.afterLaunch((View) p.args[0], p.getResult());
            }
        });
        log("independent suggestions dock ready; native icons/QSB, direct shift, dispatch/proxy touch routing");
    }

    static void onProgress(Object handler, View recents, float progress, boolean endingHome) {
        try {
            RecentsReveal existingReveal = REVEALS.get(handler);
            if (existingReveal != null && existingReveal.stopped) return;
            Panel existingPanel = current(recents);
            if (existingPanel != null && existingPanel.launchBusy()) {
                if (handler == null || existingPanel.owner.get() == handler
                        || existingPanel.committedOwner.get() == handler) return;
                // This is another gesture, not a late frame of the launch's
                // original gesture. Abandon its old source before taking over.
                stopReveal(existingPanel.owner.get());
                stopReveal(existingPanel.committedOwner.get());
                log("new gesture abandons previous dock launch");
                removeIfCurrent(recents, existingPanel);
                existingPanel = null;
            }
            if (existingPanel != null && DockRevealTail.isActive(existingPanel)) {
                if (!endingHome && (existingPanel.owner.get() == handler
                        || existingPanel.committedOwner.get() == handler)) return;
                DockRevealTail.cancel(existingPanel);
            }
            if (endingHome) {
                Panel ending = current(recents);
                if (ending == null || ending.committedOwner.get() == handler) return;
                Object target = call(get(handler, "mGestureState"), "getEndTarget");
                if (target == null || !"HOME".equals(target.toString())) {
                    // Cancel-to-app uses the original shift returning toward 0.
                    // It does not create a home spring and must not be retained.
                    if (ending.owner.get() == handler && !ending.taskLaunchAnimating) {
                        if (Float.isNaN(ending.cancelShift)) {
                            ending.cancelShift = Math.max(.001f, progress);
                            ending.cancelVisible = clamp(1f - ending.revealOffset / ending.shelf);
                        }
                        ending.setProgress(ending.cancelVisible * clamp(progress / ending.cancelShift));
                    }
                    return;
                }
                if (ending.owner.get() == handler) {
                    if (!ending.awaitingHomeSpring) {
                        ending.awaitingHomeSpring = true;
                        ending.settled = false;
                        ending.stopSelectionPoll();
                        ending.setInteractive(false);
                        log("home release holding y=" + ending.revealOffset
                                + " progress=" + progress);
                    }
                }
                return;
            }
            RecentsReveal reveal = REVEALS.get(handler);
            if (reveal == null) {
                if (!hasRecentsIntent(handler)) return;
                reveal = new RecentsReveal();
                REVEALS.put(handler, reveal);
                log("recents intent accepted; handoff from finger to card entry spring");
            }
            Panel panel = obtain(recents);
            if (panel == null) return;
            if (panel.committedOwner.get() == handler) return;
            if (panel.owner.get() != handler) {
                if (panel.windowHandoff != null) {
                    panel.windowHandoff = null;
                    DockAppBubble.cancel();
                }
                panel.clearStateAnimation();
                panel.owner = new WeakReference<>(handler);
                panel.committedOwner = new WeakReference<>(null);
                panel.generation++;
                panel.entryCommitted = panel.entryMotionFinished = false;
                panel.entryHoldForFinger = !panel.entryLandscape();
                panel.homeFingerY = Float.NaN;
                panel.cancelShift = Float.NaN;
                panel.settled = false;
                panel.stopSelectionPoll();
                panel.refresh();
            }
            if (panel.entryMotionFinished) return;
            // Before the card clock starts, only new finger displacement after
            // intent is accepted can move an app-entry dock. No late fraction
            // is applied in one frame when the panel is first attached.
            if (Float.isNaN(reveal.intentShift)) reveal.intentShift = progress;
            if (!panel.entryLandscape() && !reveal.fingerReleased) {
                float dragLength = panel.shelf;
                try { dragLength = ((Number) get(handler, "mTransitionDragLength")).floatValue(); }
                catch (Throwable ignored) { }
                float follow = Math.max(0f, progress - reveal.intentShift) * dragLength / panel.shelf;
                panel.setProgress(clamp(follow));
                int bucket = Math.round(progress * 10f);
                if (bucket != reveal.followBucket) {
                    reveal.followBucket = bucket;
                    log("portrait app dock follows finger shift=" + progress
                            + " y=" + panel.revealOffset);
                }
            }
            final Panel entryPanel = panel;
            final Object entryOwner = handler;
            final int entryGeneration = panel.generation;
            Runnable completion = new Runnable() {
                @Override public void run() {
                    if (entryPanel.generation != entryGeneration || entryPanel.launchBusy()
                            || entryPanel.returningHome || current(entryPanel.recents.get()) != entryPanel) return;
                    entryPanel.entryMotionFinished = true;
                    log("app dock entry spring complete g=" + entryGeneration);
                    if (entryPanel.entryCommitted) finishOverviewEntry(entryPanel, entryOwner);
                }
            };
            reveal.completion = completion;
            if (panel.entryLandscape() || reveal.fingerReleased)
                DockRevealTail.beginReveal(recents, panel, panel.shelf, handler, completion);
        } catch (Throwable failure) { error("gesture", failure); }
    }

    /** The helper wakes the host; DockRevealTail selects the real card clock. */
    static void onRecentsReveal(Object handler, ValueAnimator nativeClock) {
        if (handler == null || nativeClock == null) return;
        try {
            if (!hasRecentsIntent(handler)) return;
            Object recents = get(handler, "mRecentsView");
            if (recents instanceof View) onProgress(handler, (View) recents,
                    ((Number) call(handler, "getCurrentShiftValue")).floatValue(), false);
        } catch (Throwable failure) { error("recents reveal", failure); }
    }

    static void onAppFingerReleased(Object handler) {
        if (handler == null) return;
        try {
            Object view = get(handler, "mRecentsView");
            if (!(view instanceof View)) return;
            View recents = (View) view;
            Panel panel = current(recents);
            RecentsReveal reveal = REVEALS.get(handler);
            if (panel == null || reveal == null || (panel.owner.get() != handler
                    && panel.committedOwner.get() != handler)
                    || !panel.entryHoldForFinger) return;
            reveal.fingerReleased = true;
            panel.entryHoldForFinger = false;
            boolean started = reveal.completion != null && DockRevealTail.beginReveal(recents, panel,
                    panel.shelf, handler, reveal.completion);
            if (!started && panel.revealOffset <= .5f) {
                panel.entryMotionFinished = true;
                if (panel.entryCommitted) finishOverviewEntry(panel, handler);
            }
            log("portrait app finger released; start dock settle y=" + panel.revealOffset);
        } catch (Throwable failure) { error("app finger release", failure); }
    }

    static void onHomeFingerProgress(Object controller, MotionEvent event) {
        if (controller == null || event == null) return;
        try {
            Object view = get(controller, "mRecentsView");
            if (!(view instanceof View)) return;
            View recents = (View) view;
            Panel panel = current(recents);
            if (panel == null || !panel.entryHoldForFinger || panel.entryLandscape()
                    || panel.stateTrack == null || !"OVERVIEW".equals(panel.stateTrack.name)) return;
            float fingerY = event.getRawY();
            if (Float.isNaN(panel.homeFingerY)) {
                panel.homeFingerY = fingerY;
                panel.homeFingerStartDockY = panel.revealOffset;
            }
            panel.setRevealOffset(Math.max(0f, Math.min(panel.shelf,
                    panel.homeFingerStartDockY + fingerY - panel.homeFingerY)));
            int bucket = Math.round(panel.revealOffset / 48f);
            if (bucket != panel.homeFollowBucket) {
                panel.homeFollowBucket = bucket;
                log("portrait home dock follows finger rawY=" + fingerY
                        + " y=" + panel.revealOffset);
            }
        } catch (Throwable failure) { error("home finger progress", failure); }
    }

    static void onHomeFingerReleased(Object controller) {
        if (controller == null) return;
        try {
            Object view = get(controller, "mRecentsView");
            if (!(view instanceof View)) return;
            View recents = (View) view;
            final Panel panel = current(recents);
            if (panel == null || !panel.entryHoldForFinger || panel.entryLandscape()
                    || panel.stateTrack == null || !"OVERVIEW".equals(panel.stateTrack.name)) return;
            panel.entryHoldForFinger = false;
            boolean started = DockRevealTail.continueReveal(recents, panel, panel.shelf, null, new Runnable() {
                @Override public void run() {
                    panel.entryMotionFinished = true;
                    if (panel.entryCommitted) finishOverviewEntry(panel, null);
                }
            });
            if (!started && panel.revealOffset <= .5f) {
                panel.entryMotionFinished = true;
                if (panel.entryCommitted) finishOverviewEntry(panel, null);
            }
            log("portrait home finger released; start dock settle y=" + panel.revealOffset);
        } catch (Throwable failure) { error("home finger release", failure); }
    }

    private static boolean hasRecentsIntent(Object handler) throws Throwable {
        if (handler == null || Boolean.TRUE.equals(get(handler, "mIsLikelyToStartNewTask"))) return false;
        Object target = call(get(handler, "mGestureState"), "getEndTarget");
        if (target != null) return "RECENTS".equals(target.toString());
        return Boolean.TRUE.equals(get(handler, "mHasMotionEverBeenPaused"));
    }

    /** HOME uses a seekable HINT_STATE animation before committing OVERVIEW. */
    static void prepareStateAnimation(Object launcher, Object endState, Object pendingAnimation) {
        if (launcher == null || pendingAnimation == null || !launcherClass.isInstance(launcher)) return;
        boolean entering = endState == overviewState || endState == hintState;
        boolean exiting = endState == normalState || endState == allAppsState;
        if (!entering && !exiting) return;
        if (exiting && isWorkspaceRevealPreparation()) {
            // WorkspaceRevealAnim creates a preparation state animation and
            // immediately ends it at duration 0 before the visible transition.
            // Attaching here would teleport the independent dock to the bottom.
            log("ignore reveal preparation dock track");
            return;
        }
        try {
            Object view = call(launcher, "getOverviewPanel");
            if (!(view instanceof View)) return;
            View recents = (View) view;
            WeakReference<Panel> reference = PANELS.get(recents);
            Panel panel = reference == null ? null : reference.get();
            if (panel != null && (panel.launchBusy() || panel.taskLaunchAnimating || panel.returningHome)) return;
            if (panel != null && endState == hintState) {
                stopReveal(panel.owner.get());
                stopReveal(panel.committedOwner.get());
                panel.clearStateAnimation();
                panel.owner = new WeakReference<>(null);
                panel.committedOwner = new WeakReference<>(null);
            } else if (panel != null && DockRevealTail.isActive(panel)) {
                if (entering) return;
                DockRevealTail.cancel(panel);
            }
            // App entry already owns the attachment clock. HOME's new HINT
            // above explicitly replaces stale app ownership before this guard.
            if (panel != null && panel.owner.get() != null && !panel.settled) return;
            if (panel == null) {
                if (!entering) return;
                panel = obtain(recents);
            }
            if (panel == null || panel.statePending.get() == pendingAnimation) return;
            if (entering && panel.windowHandoff != null) {
                panel.windowHandoff = null;
                DockAppBubble.cancel();
            }
            float start = Math.max(0f, Math.min(panel.shelf, panel.revealOffset));
            float target = entering ? 0f : panel.shelf;
            float hintGain = endState == hintState
                    ? panel.metrics.screenHeight / Math.max(1f, start - target) : 1f;
            final StateTrack track = new StateTrack(panel.generation + 1,
                    endState == hintState ? (panel.entryLandscape() ? "HINT_LANDSCAPE" : "HINT")
                            : entering ? "OVERVIEW" : "STATE_HOME",
                    start, target, hintGain);
            ObjectAnimator animator = ObjectAnimator.ofFloat(panel, track,
                    start, target);
            track.nativeAnimator = new WeakReference<Animator>(animator);
            animator.setInterpolator(new LinearInterpolator());
            final Panel animatedPanel = panel;
            final boolean exit = exiting;
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) { track.cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) {
                    if (!track.owns(animatedPanel)) return;
                    track.finishLog(animatedPanel);
                    if (exit && !track.cancelled) track.end(animatedPanel, true);
                    else if (exit) animatedPanel.reconcileCancelledTrack(track);
                    else if (!track.cancelled && "OVERVIEW".equals(track.name)
                            && !animatedPanel.entryHoldForFinger) {
                        animatedPanel.entryMotionFinished = true;
                        if (animatedPanel.entryCommitted) finishOverviewEntry(animatedPanel, null);
                    }
                }
            });
            // add() assigns the native duration and registers playback holders.
            // Only the native playback controller may start, seek or reverse this.
            OverviewDock.method(pendingAnimation.getClass(), "add", Animator.class)
                    .invoke(pendingAnimation, animator);
            panel.cancelRoutedTouch();
            panel.generation++;
            panel.stateTrack = track;
            panel.statePending = new WeakReference<>(pendingAnimation);
            panel.committedOwner = new WeakReference<>(null);
            panel.settled = false;
            panel.stopSelectionPoll();
            panel.returningHome = exiting;
            if (entering) {
                panel.entryCommitted = panel.entryMotionFinished = false;
                panel.entryHoldForFinger = endState == overviewState && !panel.entryLandscape();
                panel.homeFingerY = Float.NaN;
            }
            panel.setInteractive(false);
            panel.setAlpha(1f);
            panel.setVisibility(View.VISIBLE);
            panel.raiseAboveRecents();
            if (exiting) ExitTaskbarDeferral.holdUntil(animator);
            track.start();
        } catch (Throwable failure) { error("state animation", failure); }
    }

    /** Follow the real app-to-home spring, including the system animation scale. */
    static void prepareHomeSpring(Object handler, Object spring) {
        if (handler == null || spring == null) return;
        try {
            Object view = get(handler, "mRecentsView");
            if (!(view instanceof View)) return;
            final View recents = (View) view;
            final Panel panel = current(recents);
            if (panel == null || panel.launchBusy() || panel.taskLaunchAnimating
                    || panel.owner.get() != handler || panel.statePending.get() == spring) return;
            DockRevealTail.cancel(panel);
            final StateTrack track = new StateTrack(panel.generation + 1, "HOME_SPRING",
                    panel.revealOffset, panel.shelf, 1f);
            Object updates = Proxy.newProxyInstance(springUpdateListenerClass.getClassLoader(),
                    new Class<?>[]{springUpdateListenerClass}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("onUpdate".equals(name) && args != null && args.length == 2
                            && args[1] instanceof Number) {
                        track.setFraction(panel, ((Number) args[1]).floatValue());
                    } else if ("onCancel".equals(name)) {
                        track.cancelled = true;
                        track.end(panel, true);
                    } else if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    else if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                    else if ("toString".equals(name)) return "PixelRecentsDockHomeSpring";
                    return null;
                }
            });
            OverviewDock.method(spring.getClass(), "addOnUpdateListener", springUpdateListenerClass)
                    .invoke(spring, updates);
            OverviewDock.method(spring.getClass(), "addAnimatorListener", Animator.AnimatorListener.class)
                    .invoke(spring, new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) { track.cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) {
                    track.end(panel, true);
                    ExitTaskbarDeferral.release(spring);
                }
            });
            panel.cancelRoutedTouch();
            panel.generation++;
            panel.stateTrack = track;
            panel.statePending = new WeakReference<>(spring);
            panel.committedOwner = new WeakReference<>(handler);
            panel.owner = new WeakReference<>(null);
            panel.awaitingHomeSpring = false;
            panel.returningHome = true;
            panel.settled = false;
            panel.stopSelectionPoll();
            panel.setInteractive(false);
            panel.setVisibility(View.VISIBLE);
            ExitTaskbarDeferral.hold(spring);
            track.start();
        } catch (Throwable failure) { error("home spring", failure); }
    }

    private static Panel current(View recents) {
        WeakReference<Panel> reference = PANELS.get(recents);
        return reference == null ? null : reference.get();
    }

    private static boolean isWorkspaceRevealPreparation() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String type = frame.getClassName();
            if (("com.android.quickstep.util.WorkspaceRevealAnim".equals(type)
                    || "com.android.quickstep.util.ScalingWorkspaceRevealAnim".equals(type))
                    && "<init>".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    /** Follows the same PendingAnimation that expands a selected task to full screen. */
    static void prepareTaskLaunchAnimation(Object recents, Object pendingAnimation) {
        if (!(recents instanceof View) || pendingAnimation == null) return;
        try {
            final View recentsView = (View) recents;
            final Panel panel = current(recentsView);
            if (panel == null || panel.launchBusy() || panel.owner.get() != null
                    || panel.statePending.get() == pendingAnimation) return;
            DockRevealTail.cancel(panel);
            final StateTrack track = new StateTrack(panel.generation + 1, "TASK_EXIT",
                    panel.revealOffset, panel.shelf, 1f);
            ObjectAnimator animator = ObjectAnimator.ofFloat(panel, track,
                    panel.revealOffset, panel.shelf);
            track.nativeAnimator = new WeakReference<Animator>(animator);
            animator.setInterpolator(new LinearInterpolator());
            OverviewDock.method(pendingAnimation.getClass(), "add", Animator.class)
                    .invoke(pendingAnimation, animator);
            // The card controller dispatches END even when reversing to the
            // overview endpoint. Only PendingAnimation knows whether its final
            // progress committed the task; a child Animator's END cannot tell.
            Object completion = Proxy.newProxyInstance(consumerClass.getClassLoader(),
                    new Class<?>[]{consumerClass}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("accept".equals(name) && args != null && args.length == 1) {
                        if (current(recentsView) != panel || !track.owns(panel)) return null;
                        if (Boolean.TRUE.equals(args[0])) track.end(panel, true);
                        else {
                            track.cancelled = true;
                            track.finishLog(panel);
                            panel.reconcileCancelledTrack(track);
                        }
                    } else if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    else if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                    else if ("toString".equals(name)) return "PixelRecentsDockTaskCompletion";
                    return null;
                }
            });
            OverviewDock.method(pendingAnimation.getClass(), "addEndListener", consumerClass)
                    .invoke(pendingAnimation, completion);
            panel.cancelRoutedTouch();
            panel.generation++;
            panel.stateTrack = track;
            panel.statePending = new WeakReference<>(pendingAnimation);
            panel.committedOwner = new WeakReference<>(null);
            panel.taskLaunchAnimating = true;
            panel.settled = false;
            panel.stopSelectionPoll();
            panel.setInteractive(false);
            panel.setVisibility(View.VISIBLE);
            panel.raiseAboveRecents();
            track.start();
        } catch (Throwable failure) { error("task launch dock track", failure); }
    }

    static boolean isDockView(View view) { return view != null && panelAncestor(view) != null; }

    static void onDisplayChanged() {
        List<View> views = new ArrayList<>(PANELS.keySet());
        for (View view : views) if (view != null) onOrientationChanged(view);
    }

    static void onContainerStopped(Object container) {
        List<Panel> stale = new ArrayList<>();
        for (WeakReference<Panel> ref : PANELS.values()) {
            Panel panel = ref.get();
            if (panel != null && panel.container == container && panel.launched) stale.add(panel);
        }
        for (Panel panel : stale) {
            log("release dock launch at stopped window");
            View view = panel.recents.get();
            if (view != null) removeIfCurrent(view, panel);
            else panel.detach();
        }
    }

    /** A HOME intent supersedes an old launch; an internal NORMAL state does not. */
    static void onHomeIntent(Object container) {
        DockAppBubble.cancel();
        List<Panel> stale = new ArrayList<>();
        for (WeakReference<Panel> ref : PANELS.values()) {
            Panel panel = ref.get();
            if (panel != null && panel.container == container && panel.launchBusy()) stale.add(panel);
        }
        for (Panel panel : stale) {
            stopReveal(panel.owner.get());
            stopReveal(panel.committedOwner.get());
            log("HOME abandons previous dock launch");
            View view = panel.recents.get();
            if (view != null) removeIfCurrent(view, panel);
            else panel.detach();
        }
    }

    /** Recents rotates independently of the Launcher Activity's DeviceProfile. */
    static void onOrientationChanged(final View recents) {
        final Panel panel = current(recents);
        if (panel == null) return;
        try { panel.applyOrientation(); }
        catch (Throwable failure) { error("orientation", failure); }
        // Native orientation updates can cancel an old controller and publish
        // its final state later in the same call. Reconcile after that call,
        // using the real state rather than an old completion's target state.
        recents.post(new Runnable() {
            @Override public void run() {
                if (current(recents) != panel) return;
                try {
                    // DeviceProfile/display notifications can precede the
                    // native touch rotation commit within the same call.
                    panel.applyOrientation();
                    Object manager = call(panel.container, "getStateManager");
                    Object state = get(manager, "mState");
                    if (Boolean.TRUE.equals(call(manager, "isInTransition"))
                            || Boolean.TRUE.equals(call(recents, "isGestureActive"))) return;
                    if (state == normalState || state == allAppsState) {
                        if (!panel.holdForNativeTransition()) {
                            log("remove inactive dock after orientation state=" + state);
                            removeIfCurrent(recents, panel);
                        }
                    } else if (state == overviewState && !panel.settled) {
                        onOverview(recents, true);
                    }
                } catch (Throwable failure) { error("orientation state", failure); }
            }
        });
    }

    /** A detached Recents window cannot still own an animation source. */
    static void onRecentsDetached(View recents) {
        if (recents == null) return;
        Panel panel = current(recents);
        if (panel != null) {
            stopReveal(panel.owner.get());
            stopReveal(panel.committedOwner.get());
            detachAfterRecentsWindow(panel);
            return;
        }
        remove(recents);
    }

    private static void detachAfterRecentsWindow(Panel panel) {
        panel.deferDetachRemoval = true;
        panel.dispose();
        panel.detach();
    }

    static void onOverview(View recents, boolean overview) {
        try {
            Panel active = current(recents);
            if (!overview && active != null && DockRevealTail.isActive(active)) DockRevealTail.cancel(active);
            // Native state can settle before a task/card or icon transition
            // finishes.  Keep its host (and FloatingIconView source) attached.
            if (overview && active != null && (DockRevealTail.isActive(active)
                    || (active.stateTrack != null && "OVERVIEW".equals(active.stateTrack.name)
                    && active.stateTrack.nativeActive(active)))) {
                finishOverviewEntry(active, active.owner.get());
                return;
            }
            if (active != null && active.holdForNativeTransition()) return;
            if (!overview) { remove(recents); return; }
            Panel panel = obtain(recents);
            if (panel == null) return;
            finishOverviewEntry(panel, panel.owner.get());
        } catch (Throwable failure) { error("settle", failure); }
    }

    private static void finishOverviewEntry(final Panel panel, Object owner) {
        panel.entryCommitted = true;
        if (owner != null) panel.committedOwner = new WeakReference<>(owner);
        if (panel.entryHoldForFinger) {
            if (owner != null) {
                stopReveal(owner);
                panel.owner = new WeakReference<>(null);
            }
            return;
        }
        if (DockRevealTail.isActive(panel)) {
            stopReveal(owner);
            panel.owner = new WeakReference<>(null);
            return;
        }
        if (panel.stateTrack != null && "OVERVIEW".equals(panel.stateTrack.name)
                && panel.stateTrack.nativeActive(panel)) return;
        panel.clearStateAnimation();
        stopReveal(owner);
        if (owner != null) panel.committedOwner = new WeakReference<>(owner);
        panel.owner = new WeakReference<>(null);
        panel.settled = false;
        panel.stopSelectionPoll();
        panel.setInteractive(false);
        final int entryGeneration = panel.generation;
        Runnable completion = new Runnable() {
            @Override public void run() {
                View view = panel.recents.get();
                if (view == null || current(view) != panel || panel.generation != entryGeneration
                        || panel.launchBusy() || panel.returningHome) return;
                panel.settled = true;
                panel.setProgress(1f);
                log("overview dock entry complete");
            }
        };
        View view = panel.recents.get();
        if (panel.entryMotionFinished
                || !DockRevealTail.continueReveal(view, panel, panel.shelf, owner, completion)) completion.run();
    }

    static void reset(Object handler) {
        if (handler == null) return;
        stopReveal(handler);
        List<Panel> owned = new ArrayList<>();
        for (WeakReference<Panel> reference : PANELS.values()) {
            Panel panel = reference.get();
            if (panel != null && !panel.launchBusy() && panel.owner.get() == handler
                    && !panel.awaitingHomeSpring && !panel.returningHome) owned.add(panel);
        }
        for (Panel panel : owned) {
            boolean toOverview = panel.settled;
            try {
                Object gesture = get(handler, "mGestureState");
                Object end = call(gesture, "getEndTarget");
                toOverview |= end != null && "RECENTS".equals(end.toString());
            } catch (Throwable ignored) { }
            if (toOverview) {
                finishOverviewEntry(panel, handler);
            } else {
                View recents = panel.recents.get();
                if (recents != null) remove(recents);
                else panel.detach();
            }
        }
    }

    /** Cancellation and timeouts must never pin a stale RECENTS end target. */
    static void abort(Object handler) {
        if (handler == null) return;
        stopReveal(handler);
        List<Panel> owned = new ArrayList<>();
        for (WeakReference<Panel> reference : PANELS.values()) {
            Panel panel = reference.get();
            if (panel != null && !panel.launchBusy()
                    && (panel.owner.get() == handler || panel.committedOwner.get() == handler)) owned.add(panel);
        }
        for (Panel panel : owned) {
            View recents = panel.recents.get();
            if (recents != null) removeIfCurrent(recents, panel);
            else panel.detach();
        }
    }

    private static void stopReveal(Object handler) {
        if (handler == null) return;
        RecentsReveal reveal = REVEALS.get(handler);
        if (reveal == null) {
            reveal = new RecentsReveal();
            REVEALS.put(handler, reveal);
        }
        reveal.stopped = true;
    }

    private static Panel obtain(View recents) throws Throwable {
        if (recents == null || recents.getWindowToken() == null) return null;
        Object container = get(recents, "mContainer");
        if (!(container instanceof Context) || !activityContextClass.isInstance(container)) return null;
        Object profile = OverviewDock.deviceProfile(container);
        if (!Boolean.TRUE.equals(get(get(profile, "mDeviceProperties"), "isPhone"))) return null;
        Object split = null;
        try { split = call(container, "getSplitSelectStateController"); }
        catch (NoSuchMethodException ignored) { }
        if (split != null) {
            try { if (Boolean.TRUE.equals(call(split, "isSplitSelectActive"))) return null; }
            catch (NoSuchMethodException ignored) { }
        }
        Object layer = call(container, "getDragLayer");
        if (!(layer instanceof ViewGroup)) return null;
        ViewGroup dragLayer = (ViewGroup) layer;
        if (!OverviewDock.sameWindow(dragLayer, recents)) return null;
        WeakReference<Panel> ref = PANELS.get(recents);
        Panel panel = ref == null ? null : ref.get();
        if (panel != null && panel.getParent() == dragLayer) {
            panel.applyOrientation();
            return panel;
        }
        Metrics metrics = new Metrics((Context) container, profile);
        int height = metrics.height;
        panel = new Panel((Context) container, recents, container, metrics);
        panel.refresh();
        ViewGroup.LayoutParams generic = (ViewGroup.LayoutParams) hostLayoutConstructor.newInstance(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) generic;
        panel.configureHost(params);
        setField(params, "ignoreInsets", Boolean.TRUE);
        panel.setRevealOffset(panel.shelf);
        dragLayer.addView(panel, params);
        try {
            panel.raiseAboveRecents();
        } catch (Throwable failure) {
            panel.detach();
            throw failure;
        }
        PANELS.put(recents, new WeakReference<>(panel));
        log("top-layer dock attached: " + container.getClass().getSimpleName()
                + " icons=" + panel.iconCount + " qsb=" + panel.hasQsb
                + " slots=" + metrics.slots + " sameWindow=" + panel.sameWindow
                + " height=" + height + " tracking=" + panel.shelf);
        return panel;
    }

    /** Shared with OverviewLayout: reserve this independent panel plus the A10 40dp gap. */
    static int dockHeight(Context context, Object profile) throws Throwable {
        return new Metrics(context, profile).height;
    }

    static int dockIconRowTop(Context context, Object profile) throws Throwable {
        return new Metrics(context, profile).top;
    }

    private static final class Metrics {
        final int height, slots, iconSize, rowHeight, qsbHeight, edge, top, gap, bottom, screenHeight;
        Metrics(Context context, Object profile) throws Throwable {
            float density = context.getResources().getDisplayMetrics().density;
            Object workspace = get(profile, "mWorkspaceProfile");
            Object hotseat = get(profile, "mHotseatProfile");
            Object properties = get(profile, "mDeviceProperties");
            Rect insets = (Rect) get(properties, "insets");
            screenHeight = ((Number) get(properties, "heightPx")).intValue();
            // Read profile metrics, never a desktop view, its coordinates, or its contents.
            iconSize = ((Number) get(workspace, "iconSizePx")).intValue();
            slots = Math.max(3, Math.min(6, ((Number) get(hotseat, "numShownIcons")).intValue()));
            qsbHeight = Math.max(Math.round(44f * density), ((Number) get(hotseat, "qsbHeight")).intValue());
            edge = Math.round(16f * density);
            top = Math.round(8f * density);
            gap = Math.round(12f * density);
            bottom = Math.round(12f * density) + (insets == null ? 0 : insets.bottom);
            rowHeight = iconSize + Math.round(20f * density);
            height = top + rowHeight + gap + qsbHeight + bottom;
        }
    }

    private static boolean routeTouch(ViewGroup layer, MotionEvent event) {
        Long cancelled = CANCELLED_TOUCHES.get(layer);
        if (cancelled != null) {
            if (event.getDownTime() == cancelled.longValue()) {
                // Keep the tombstone through duplicate proxy/dispatch terminal events.
                // A new stream clears it below.
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) CANCELLED_TOUCHES.remove(layer);
        }
        for (WeakReference<Panel> ref : PANELS.values()) {
            Panel panel = ref.get();
            if (panel == null || panel.getParent() != layer) continue;
            if (panel.isDuplicateTouch(event)) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (!panel.hitInteractive(event.getX(), event.getY())) {
                    continue;
                }
                panel.cancelRoutedTouch();
                panel.routedTouch = true;
                log("touch routed down x=" + event.getX() + " y=" + event.getY()
                        + " sameWindow=" + panel.sameWindow);
            }
            if (!panel.routedTouch) continue;
            panel.route(event);
            return true;
        }
        return false;
    }

    private static void remove(View recents) {
        WeakReference<Panel> reference = PANELS.remove(recents);
        Panel panel = reference == null ? null : reference.get();
        if (panel != null) panel.detach();
    }

    private static void removeIfCurrent(View recents, Panel expected) {
        WeakReference<Panel> reference = PANELS.get(recents);
        if (reference != null && reference.get() == expected) PANELS.remove(recents);
        expected.detach();
    }

    private static Panel panelAncestor(View source) {
        View current = source;
        while (current != null) {
            if (current instanceof Panel) return (Panel) current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    static final class Panel extends FrameLayout {
        final WeakReference<View> recents;
        final Object container;
        final float shelf;
        final Metrics metrics;
        final FrameLayout predictionRow;
        final boolean sameWindow;
        final Runnable suggestionsChanged;
        View qsb;
        float iconRotation;
        DockEdge dockEdge = DockEdge.BOTTOM;
        float revealOffset;
        boolean landscapeLayout, orientationPending;
        WeakReference<Object> owner = new WeakReference<>(null);
        WeakReference<Object> committedOwner = new WeakReference<>(null);
        WeakReference<Object> statePending = new WeakReference<>(null);
        WeakReference<Object> launchCompletion = new WeakReference<>(null);
        WeakReference<View> launchSource = new WeakReference<>(null);
        Runnable pendingLaunch;
        Object windowHandoff;
        StateTrack stateTrack;
        boolean settled, hasQsb, launched, routedTouch, pendingRefresh, taskLaunchAnimating;
        boolean deferDetachRemoval, deferredRemovalPosted;
        int detachRemovalToken;
        boolean launchPending, launchStarting, disposed;
        boolean awaitingHomeSpring, returningHome;
        boolean interactiveKnown, interactiveEnabled;
        boolean layerEndpointHandled;
        boolean entryCommitted, entryMotionFinished;
        boolean entryHoldForFinger;
        float cancelShift = Float.NaN, cancelVisible;
        float homeFingerY = Float.NaN, homeFingerStartDockY;
        int homeFollowBucket = -1;
        ViewGroup layeredParent;
        int layeredChildCount = -1, layeredChildIndex = -1;
        int iconCount, generation;
        boolean selectionPollScheduled, selectionPolled;
        MotionEvent lastRoutedEvent;
        long lastTouchDown = -1, lastTouchTime = -1;
        int lastTouchAction = -1;

        Panel(Context context, View recents, Object container, Metrics metrics) throws Throwable {
            super(context);
            this.recents = new WeakReference<>(recents);
            this.container = container;
            this.metrics = metrics;
            shelf = metrics.height;
            sameWindow = launcherClass.isInstance(container);
            setClipChildren(false);
            setClipToPadding(false);
            setFocusable(false);
            setClickable(false);
            predictionRow = new FrameLayout(context);
            predictionRow.setClipChildren(false);
            predictionRow.setClipToPadding(false);
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, metrics.rowHeight);
            rowParams.gravity = Gravity.TOP | Gravity.LEFT;
            rowParams.leftMargin = metrics.edge;
            rowParams.rightMargin = metrics.edge;
            rowParams.topMargin = metrics.top;
            addView(predictionRow, rowParams);
            try {
                qsb = (View) qsbConstructor.newInstance(context);
                qsb.setPadding(0, 0, 0, 0);
                qsb.setId(getResources().getIdentifier("search_container_hotseat", "id", context.getPackageName()));
                FrameLayout.LayoutParams qsbParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, metrics.qsbHeight);
                qsbParams.gravity = Gravity.TOP | Gravity.LEFT;
                qsbParams.leftMargin = metrics.edge;
                qsbParams.rightMargin = metrics.edge;
                qsbParams.topMargin = metrics.top + metrics.rowHeight + metrics.gap;
                addView(qsb, qsbParams);
                hasQsb = true;
            } catch (Throwable failure) { log("native QSB unavailable: " + failure); }
            applyOrientation();
            suggestionsChanged = new Runnable() {
                @Override public void run() {
                    if (getWindowToken() == null) return;
                    if (launchBusy()) { pendingRefresh = true; return; }
                    if (routedTouch) { pendingRefresh = true; return; }
                    try { refresh(); }
                    catch (Throwable failure) { error("suggestions update", failure); }
                }
            };
        }

        void refresh() throws Throwable {
            refresh(DockSuggestions.snapshot(container, metrics.slots));
        }

        void refresh(List<Object> suggestions) throws Throwable {
            if (routedTouch || launchBusy()) { pendingRefresh = true; return; }
            iconCount = 0;
            launched = false;
            pendingRefresh = false;
            int animatedIndex = 0;
            for (Object info : suggestions) {
                if (!PredictionIcons.canBind(info) || iconCount >= metrics.slots) continue;
                View icon = iconCount < predictionRow.getChildCount()
                        ? predictionRow.getChildAt(iconCount) : null;
                if (icon == null) {
                    icon = newIcon(info);
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, metrics.rowHeight);
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    predictionRow.addView(icon, params);
                } else if (PredictionIcons.bind(icon, info, animatedIndex)) animatedIndex++;
                formatIcon(icon);
                icon.setEnabled(settled);
                iconCount++;
            }
            while (predictionRow.getChildCount() > iconCount) {
                predictionRow.removeViewAt(predictionRow.getChildCount() - 1);
            }
            interactiveKnown = false;
            setInteractive(settled && !launchBusy());
            requestLayout();
            log("suggestions bound count=" + iconCount + " slots=" + metrics.slots
                    + " animated=" + animatedIndex);
        }

        boolean canPollSelection() {
            return !disposed && getWindowToken() != null && settled && !launchBusy()
                    && !routedTouch && getVisibility() == VISIBLE && getAlpha() >= .05f;
        }

        void scheduleSelectionPoll() {
            if (!canPollSelection() || selectionPollScheduled) return;
            selectionPollScheduled = true;
            postDelayed(selectionPoll, selectionPolled ? 2000L : 0L);
        }

        void stopSelectionPoll() {
            if (!selectionPollScheduled) return;
            selectionPollScheduled = false;
            removeCallbacks(selectionPoll);
        }

        final Runnable selectionPoll = new Runnable() {
            @Override public void run() {
                selectionPollScheduled = false;
                if (!canPollSelection()) return;
                selectionPolled = true;
                DockSuggestions.requestSelectedSnapshot(getContext(), container, metrics.slots,
                        new DockSuggestions.SnapshotCallback() {
                    @Override public void onSnapshot(List<Object> items) {
                        if (!canPollSelection()) {
                            pendingRefresh = true;
                            return;
                        }
                        try { refresh(items); }
                        catch (Throwable failure) { error("selected suggestions update", failure); }
                        scheduleSelectionPoll();
                    }
                });
            }
        };

        View newIcon(Object info) throws Throwable {
            final View icon = PredictionIcons.create(getContext(), predictionRow, info);
            icon.setLongClickable(false);
            formatIcon(icon);
            icon.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View clicked) { launch(clicked, clicked.getTag()); }
            });
            return icon;
        }

        void formatIcon(View icon) {
            icon.setRotation(iconRotation);
            if (icon instanceof TextView) {
                TextView text = (TextView) icon;
                text.setTextColor(Color.TRANSPARENT);
                text.setPadding(0, (metrics.rowHeight - metrics.iconSize) / 2, 0, 0);
                text.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                text.setCompoundDrawablePadding(0);
            }
        }

        void launch(final View icon, final Object info) {
            if (!settled || launchBusy()) return;
            try {
                if (workspaceItemClass.isInstance(info) && Boolean.TRUE.equals(call(info, "isDisabled"))) {
                    Object launcher = OverviewDock.launcher();
                    if (launcher instanceof Context && Boolean.TRUE.equals(
                            disabledClickMethod.invoke(null, info, launcher))) return;
                }
                Object intent = call(info, "getIntent");
                if (!(intent instanceof Intent)) return;
                final Intent launchIntent = new Intent((Intent) intent);
                if (!FeatureConfig.enabled(FeatureConfig.RECENTS_FLOAT, true)) {
                    Object completion = launchMethod.invoke(container, icon, launchIntent, info);
                    if (completion == null) throw new IllegalStateException("Launcher rejected app launch");
                    afterLaunch(icon, completion);
                    return;
                }
                // Both orientations use NativeComplements. Keep the host
                // attached so the native card/HOME animation owns Dock exit.
                {
                    final Object handoff = new Object();
                    windowHandoff = handoff;
                    settled = false;
                    stopSelectionPoll();
                    setInteractive(false);
                    Runnable handedOff = new Runnable() {
                        @Override public void run() {
                            View overview = recents.get();
                            if (windowHandoff == handoff && overview != null
                                    && current(overview) == Panel.this)
                                removeIfCurrent(overview, Panel.this);
                        }
                    };
                    if (!DockAppBubble.launch(container, icon, info, launchIntent, handedOff,
                            new Runnable() {
                        @Override public void run() {
                            if (windowHandoff == handoff && !disposed
                                    && current(recents.get()) == Panel.this) {
                                windowHandoff = null;
                                recoverLaunch();
                            }
                        }
                    })) throw new IllegalStateException("Small windows unavailable");
                }
            } catch (Throwable failure) {
                windowHandoff = null;
                recoverLaunch();
                error("icon launch", failure);
                Toast.makeText(getContext(), "无法打开应用", Toast.LENGTH_SHORT).show();
            }
        }

        boolean launchBusy() { return launchPending || launchStarting || launched; }

        boolean holdForNativeTransition() {
            if (windowHandoff != null) return true;
            if (DockRevealTail.isActive(this)) return true;
            if (launchStarting) return true;
            if (launchPending) {
                View source = launchSource.get();
                if (source == null || source.getWindowToken() == null || panelAncestor(source) != this) return false;
                // DockLaunch owns the native handoff barrier. A null recents
                // controller alone does not mean the handoff has completed.
                return true;
            }
            if (launched) {
                Object callbacks = launchCompletion.get();
                try { return callbacks != null && !Boolean.TRUE.equals(get(callbacks, "mDestroyed")); }
                catch (Throwable ignored) { return callbacks != null; }
            }
            if (taskLaunchAnimating || returningHome) {
                return stateTrack != null && stateTrack.nativeActive(this);
            }
            return awaitingHomeSpring && handlerActive(owner.get());
        }

        boolean handlerActive(Object handler) {
            if (handler == null) return false;
            try {
                if (Boolean.TRUE.equals(get(handler, "mAnimationCanceled"))) return false;
                Object callback = get(handler, "mStateCallback");
                int invalid = ((Number) get(handler.getClass(), "STATE_HANDLER_INVALIDATED")).intValue();
                if (Boolean.TRUE.equals(OverviewDock.method(callback.getClass(), "hasStates", int.class)
                        .invoke(callback, invalid))) return false;
                return Boolean.TRUE.equals(call(get(handler, "mGestureState"), "isRunningAnimationToLauncher"));
            } catch (Throwable ignored) { return false; }
        }

        boolean entryLandscape() {
            return landscapeLayout || getResources().getConfiguration().orientation == 2;
        }

        void applyOrientation() throws Throwable {
            // Native reset restores desktop rotation before the visible exit
            // has finished. Keep this exiting Dock's geometry until it leaves;
            // a cancelled exit will apply the pending direction when restored.
            if (launchBusy() || returningHome || taskLaunchAnimating) {
                orientationPending = true;
                return;
            }
            View overview = recents.get();
            if (overview == null) return;
            Object profile = OverviewDock.deviceProfile(container);
            Object properties = get(profile, "mDeviceProperties");
            Object displayInfo = displayInfoMethod.invoke(null, getContext());
            int displayRotation = ((Number) get(displayInfo, "rotation")).intValue();
            int bufferRotation = ((Number) get(properties, "rotationHint")).intValue();
            Object orientedState = get(overview, "mOrientationState");
            int touchRotation = ((Number) call(orientedState, "getTouchRotation")).intValue();
            // Cards can rotate in place while DisplayController still reports
            // portrait. Match their logical direction in the host's buffer.
            float degrees = ((touchRotation - bufferRotation + 4) % 4) * 90f;
            boolean landscape = (touchRotation & 1) != 0;
            DockEdge nextEdge = DockEdge.forOverview(touchRotation, bufferRotation);
            boolean changed = orientationPending || dockEdge != nextEdge || iconRotation != degrees || landscapeLayout != landscape;
            if (changed) cancelRoutedTouch();
            dockEdge = nextEdge;
            iconRotation = degrees;
            landscapeLayout = landscape;
            orientationPending = false;
            if (qsb != null) qsb.setVisibility(landscape ? GONE : VISIBLE);
            if (changed) {
                FrameLayout.LayoutParams host = (FrameLayout.LayoutParams) getLayoutParams();
                if (host != null) { configureHost(host); setLayoutParams(host); }
                setRevealOffset(revealOffset);
            }
            FrameLayout.LayoutParams row = (FrameLayout.LayoutParams) predictionRow.getLayoutParams();
            int match = ViewGroup.LayoutParams.MATCH_PARENT;
            int rowWidth = dockEdge.vertical() ? metrics.rowHeight : match;
            int rowHeight = dockEdge.vertical() ? match : metrics.rowHeight;
            Rect insets = (Rect) get(properties, "insets");
            int safeLeft = insets == null ? 0 : insets.left, safeTop = insets == null ? 0 : insets.top;
            int safeRight = insets == null ? 0 : insets.right, safeBottom = insets == null ? 0 : insets.bottom;
            int pad = Math.min(metrics.height - metrics.rowHeight, metrics.top + dockEdge.inset(safeLeft, safeTop, safeRight, safeBottom));
            int left = metrics.edge, right = metrics.edge, top = metrics.top, bottom = 0;
            if (landscape) {
                left = dockEdge.vertical() ? (dockEdge == DockEdge.LEFT ? pad : 0) : metrics.edge + safeLeft;
                right = dockEdge.vertical() ? (dockEdge == DockEdge.RIGHT ? pad : 0) : metrics.edge + safeRight;
                top = dockEdge.vertical() ? metrics.edge + safeTop : (dockEdge == DockEdge.TOP ? pad : 0);
                bottom = dockEdge.vertical() ? metrics.edge + safeBottom : (dockEdge == DockEdge.BOTTOM ? pad : 0);
            }
            int gravity = landscape ? edgeGravity() : Gravity.TOP | Gravity.LEFT;
            if (row.width != rowWidth || row.height != rowHeight || row.gravity != gravity
                    || row.leftMargin != left || row.rightMargin != right || row.topMargin != top || row.bottomMargin != bottom) {
                row.width = rowWidth; row.height = rowHeight; row.gravity = gravity;
                row.setMargins(left, top, right, bottom);
                predictionRow.setLayoutParams(row);
            }
            for (int index = 0; index < predictionRow.getChildCount(); index++) {
                predictionRow.getChildAt(index).setRotation(degrees);
            }
            if (changed) {
                layerEndpointHandled = false;
                requestLayout();
                log("orientation degrees=" + degrees + " landscape=" + landscape + " hostEdge=" + dockEdge
                        + " touch=" + touchRotation + " display=" + displayRotation + " buffer=" + bufferRotation);
            }
        }

        int edgeGravity() {
            if (dockEdge == DockEdge.RIGHT) return Gravity.RIGHT | Gravity.TOP;
            if (dockEdge == DockEdge.BOTTOM) return Gravity.BOTTOM | Gravity.LEFT;
            return Gravity.TOP | Gravity.LEFT;
        }

        void configureHost(FrameLayout.LayoutParams params) {
            params.width = dockEdge.vertical() ? metrics.height : ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = dockEdge.vertical() ? ViewGroup.LayoutParams.MATCH_PARENT : metrics.height;
            params.gravity = edgeGravity();
        }

        void setRevealOffset(float offset) {
            revealOffset = offset;
            setTranslationX(dockEdge.outwardX * offset);
            setTranslationY(dockEdge.outwardY * offset);
        }

        void recoverLaunch() {
            if (launched || disposed) return;
            generation++;
            clearLaunch();
            settled = true;
            try { applyOrientation(); }
            catch (Throwable failure) { error("restore launch orientation", failure); }
            try { raiseAboveRecents(); }
            catch (Throwable failure) { error("restore launch source layer", failure); }
            setInteractive(true);
            if (pendingRefresh) post(suggestionsChanged);
            scheduleSelectionPoll();
        }

        void afterLaunch(final View source, Object completionCallbacks) {
            if (launched || disposed) return;
            launchPending = false;
            launchStarting = false;
            launched = true;
            launchSource = new WeakReference<>(source);
            launchCompletion = new WeakReference<>(completionCallbacks);
            final int launchGeneration = generation;
            setInteractive(false);
            log("native icon/QSB launch accepted sameWindow=" + sameWindow);
            Runnable cleanup = new Runnable() {
                @Override public void run() {
                    View panel = recents.get();
                    WeakReference<Panel> current = panel == null ? null : PANELS.get(panel);
                    if (current == null || current.get() != Panel.this || generation != launchGeneration
                            || !launched || launchSource.get() != source) return;
                    try {
                        if (!sameWindow) {
                            Object hidden = get(recentsStateClass, "HIDDEN");
                            OverviewDock.method(container.getClass(), "goToRecentsState",
                                    recentsStateClass, boolean.class, animatorListenerClass)
                                    .invoke(container, hidden, false, null);
                        }
                    } catch (Throwable failure) { error("native window dismissal", failure); }
                    if (panel != null) removeIfCurrent(panel, Panel.this);
                    else detach();
                }
            };
            try {
                OverviewDock.method(completionCallbacks.getClass(), "add", Runnable.class)
                        .invoke(completionCallbacks, cleanup);
            } catch (Throwable failure) {
                // If a nonstandard launcher returns no completion list, avoid
                // retaining a dead overlay. The normal 907 path always has it.
                error("launch completion", failure);
                post(cleanup);
            }
        }

        void setProgress(float progress) {
            if (launchBusy()) return;
            progress = Math.max(0f, Math.min(1f, progress));
            if (!settled || progress < .999f) stopSelectionPoll();
            setRevealOffset(shelf * (1f - progress));
            setAlpha(1f);
            setVisibility(progress <= 0f ? View.INVISIBLE : View.VISIBLE);
            try {
                if (settled && progress >= .999f) {
                    if (!layerEndpointHandled) {
                        raiseAboveRecents();
                        layerEndpointHandled = true;
                    }
                } else {
                    layerEndpointHandled = false;
                    ensureAboveRecents();
                }
            }
            catch (Throwable failure) { error("layer order", failure); }
            // The shelf becomes interactive once the system has committed the overview.
            setInteractive(settled && !launchBusy());
            if (settled && progress >= .999f) scheduleSelectionPoll();
        }

        void setInteractive(boolean enabled) {
            enabled &= !launchBusy();
            if (interactiveKnown && interactiveEnabled == enabled) return;
            interactiveKnown = true;
            interactiveEnabled = enabled;
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).setEnabled(enabled);
            for (int i = 0; i < predictionRow.getChildCount(); i++) predictionRow.getChildAt(i).setEnabled(enabled);
        }

        void clearStateAnimation() {
            DockRevealTail.cancel(this);
            stateTrack = null;
            statePending = new WeakReference<>(null);
            taskLaunchAnimating = false;
            awaitingHomeSpring = false;
            returningHome = false;
        }

        void reconcileCancelledTrack(StateTrack track) {
            if (!track.owns(this)) return;
            clearStateAnimation();
            final int cancelledGeneration = generation;
            // Cancellation can be part of rotation or a replacement transition.
            // Let native state publication finish before deciding whether this
            // now-unowned host should return to overview or be removed.
            post(new Runnable() {
                @Override public void run() {
                    View overview = recents.get();
                    if (overview == null || current(overview) != Panel.this
                            || generation != cancelledGeneration || stateTrack != null || launchBusy()) return;
                    try {
                        Object manager = call(container, "getStateManager");
                        if (Boolean.TRUE.equals(call(manager, "isInTransition"))
                                || Boolean.TRUE.equals(call(overview, "isGestureActive"))) return;
                        Object state = get(manager, "mState");
                        if (state == overviewState) {
                            applyOrientation();
                            settled = true;
                            setProgress(1f);
                        } else if (state == normalState || state == allAppsState) {
                            log("remove cancelled dock track in state=" + state);
                            removeIfCurrent(overview, Panel.this);
                        }
                    } catch (Throwable failure) { error("cancelled track state", failure); }
                }
            });
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            FrameLayout.LayoutParams row = (FrameLayout.LayoutParams) predictionRow.getLayoutParams();
            int available = Math.max(1, dockEdge.vertical()
                    ? View.MeasureSpec.getSize(heightSpec) - row.topMargin - row.bottomMargin
                    : View.MeasureSpec.getSize(widthSpec) - row.leftMargin - row.rightMargin);
            float cell = available / (float) metrics.slots;
            float leading = (available - cell * iconCount) / 2f;
            for (int i = 0; i < predictionRow.getChildCount(); i++) {
                View child = predictionRow.getChildAt(i);
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
                int left = Math.round(leading + i * cell);
                int width = Math.round(leading + (i + 1) * cell) - left;
                int height = metrics.rowHeight, top = 0;
                if (landscapeLayout) {
                    width = height = Math.min(metrics.rowHeight, Math.max(1, (int) cell));
                    int along = dockEdge.slotCenter(i, iconCount, metrics.slots, available) - width / 2;
                    int across = (metrics.rowHeight - width) / 2;
                    left = dockEdge.vertical() ? across : along;
                    top = dockEdge.vertical() ? along : across;
                }
                if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top) {
                    params.width = width;
                    params.height = height;
                    params.leftMargin = left;
                    params.topMargin = top;
                    child.setLayoutParams(params);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
            boolean handled = super.dispatchTouchEvent(event);
            if ((event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(false);
            return handled;
        }

        void raiseAboveRecents() throws Throwable {
            ViewParent parent = getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup layer = (ViewGroup) parent;
            if (layer.indexOfChild(this) != layer.getChildCount() - 1) bringToFront();
            float highest = 0f;
            Method getZ = View.class.getMethod("getZ");
            for (int index = 0; index < layer.getChildCount(); index++) {
                View sibling = layer.getChildAt(index);
                if (sibling != this) highest = Math.max(highest, ((Float) getZ.invoke(sibling)).floatValue());
            }
            View.class.getMethod("setElevation", float.class).invoke(this, highest + dp(8));
            rememberLayerOrder(layer);
        }

        void ensureAboveRecents() throws Throwable {
            ViewParent parent = getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup layer = (ViewGroup) parent;
            int childCount = layer.getChildCount();
            int childIndex = layer.indexOfChild(this);
            if (layeredParent == layer && layeredChildCount == childCount
                    && layeredChildIndex == childIndex) return;
            raiseAboveRecents();
        }

        void rememberLayerOrder(ViewGroup layer) {
            layeredParent = layer;
            layeredChildCount = layer.getChildCount();
            layeredChildIndex = layer.indexOfChild(this);
        }

        boolean hitInteractive(float layerX, float layerY) {
            if (!settled || launchBusy() || getVisibility() != VISIBLE || getAlpha() < .05f) return false;
            float x = layerX - getLeft() - getTranslationX();
            float y = layerY - getTop() - getTranslationY();
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child.getVisibility() != VISIBLE || !child.isEnabled()) continue;
                if (child == predictionRow) {
                    for (int slot = 0; slot < predictionRow.getChildCount(); slot++) {
                        View icon = predictionRow.getChildAt(slot);
                        if (!icon.isEnabled() || icon.getVisibility() != VISIBLE) continue;
                        float[] point = { x - predictionRow.getLeft() - icon.getLeft(),
                                y - predictionRow.getTop() - icon.getTop() };
                        Matrix inverse = new Matrix();
                        if (!icon.getMatrix().invert(inverse)) continue;
                        inverse.mapPoints(point);
                        if (point[0] >= 0f && point[0] < icon.getWidth()
                                && point[1] >= 0f && point[1] < icon.getHeight()) return true;
                    }
                    continue;
                }
                if (x >= child.getLeft() && x < child.getRight()
                        && y >= child.getTop() && y < child.getBottom()) return true;
            }
            return false;
        }

        boolean isDuplicateTouch(MotionEvent event) {
            return event.getDownTime() == lastTouchDown && event.getEventTime() == lastTouchTime
                    && event.getAction() == lastTouchAction;
        }

        void route(MotionEvent event) {
            lastTouchDown = event.getDownTime();
            lastTouchTime = event.getEventTime();
            lastTouchAction = event.getAction();
            if (lastRoutedEvent != null) lastRoutedEvent.recycle();
            lastRoutedEvent = null;
            MotionEvent local = MotionEvent.obtain(event);
            local.offsetLocation(-getLeft() - getTranslationX(), -getTop() - getTranslationY());
            boolean terminal = event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            if (terminal) routedTouch = false;
            else lastRoutedEvent = MotionEvent.obtain(local);
            // A native launch can detach this panel synchronously during ACTION_UP.
            // Keep the dispatched copy independent of the cancellation sample.
            try { dispatchTouchEvent(local); }
            finally { local.recycle(); }
            if (terminal && pendingRefresh && !launchBusy()) post(suggestionsChanged);
            if (terminal) scheduleSelectionPoll();
        }

        void cancelRoutedTouch() {
            MotionEvent previous = lastRoutedEvent;
            lastRoutedEvent = null;
            if (routedTouch && previous != null) {
                routedTouch = false;
                ViewParent parent = getParent();
                if (parent instanceof View) CANCELLED_TOUCHES.put((View) parent, previous.getDownTime());
                previous.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(previous);
            }
            if (previous != null) previous.recycle();
        }

        /** Clear identity before invoking native callbacks, which may reenter detach(). */
        void clearLaunch() {
            final View source = launchSource.get();
            final Object callbacks = launchCompletion.get();
            final Runnable pending = pendingLaunch;
            launchPending = launchStarting = launched = false;
            pendingLaunch = null;
            launchCompletion = new WeakReference<>(null);
            launchSource = new WeakReference<>(null);
            if (source != null && pending != null) source.removeCallbacks(pending);
            if (source != null) DockLaunch.cancelSource(source);
            try { if (callbacks != null) call(callbacks, "executeAllAndDestroy"); }
            catch (Throwable failure) { error("launch disposal", failure); }
        }

        void dispose() {
            if (disposed) return;
            disposed = true;
            stopSelectionPoll();
            windowHandoff = null;
            generation++;
            // Unpublish before draining callbacks: native cancellation can
            // synchronously publish a new state or request another host.
            View overview = recents.get();
            if (overview != null && current(overview) == this) PANELS.remove(overview);
            stopReveal(owner.get());
            stopReveal(committedOwner.get());
            clearLaunch();
            clearStateAnimation();
            cancelRoutedTouch();
            removeCallbacks(suggestionsChanged);
            DockSuggestions.removeListener(suggestionsChanged);
            owner = new WeakReference<>(null);
            committedOwner = new WeakReference<>(null);
        }

        void detach() {
            dispose();
            ViewParent parent = getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) parent;
            if (!deferDetachRemoval) {
                group.removeView(this);
                return;
            }
            if (deferredRemovalPosted) return;
            deferredRemovalPosted = true;
            final int token = ++detachRemovalToken;
            MAIN_HANDLER.post(new Runnable() {
                @Override public void run() {
                    if (token != detachRemovalToken) return;
                    deferredRemovalPosted = false;
                    if (deferDetachRemoval && getParent() == group) group.removeView(Panel.this);
                }
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            disposed = false;
            deferDetachRemoval = false;
            deferredRemovalPosted = false;
            detachRemovalToken++;
            DockSuggestions.addListener(suggestionsChanged);
            post(suggestionsChanged);
        }

        @Override protected void onDetachedFromWindow() {
            dispose();
            super.onDetachedFromWindow();
        }

        int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    }

    /** A superseded native controller must not write over a newer gesture. */
    private static final class RecentsReveal {
        float intentShift = Float.NaN;
        Runnable completion;
        int followBucket = -1;
        boolean fingerReleased;
        boolean stopped;
    }

    private static final class StateTrack extends Property<Panel, Float> {
        final int generation;
        final String name;
        final float startY, endY, hintGain;
        int bucket = -1;
        boolean cancelled, finished;
        WeakReference<Animator> nativeAnimator = new WeakReference<>(null);
        StateTrack(int generation, String name, float startY, float endY, float hintGain) {
            super(Float.class, "pixelRecentsDockY");
            this.generation = generation;
            this.name = name;
            this.startY = startY;
            this.endY = endY;
            this.hintGain = Math.max(1f, hintGain);
        }
        @Override public Float get(Panel panel) { return panel.revealOffset; }
        @Override public void set(Panel panel, Float value) {
            if (!owns(panel)) return;
            if ("OVERVIEW".equals(name) && (panel.entryHoldForFinger
                    || panel.entryMotionFinished || DockRevealTail.isActive(panel))) return;
            float distance = endY - startY;
            float fraction = Math.abs(distance) < .01f ? 1f
                    : clamp((value.floatValue() - startY) / distance);
            float mapped = fraction;
            if ("HINT_LANDSCAPE".equals(name)) {
                // Landscape has no finger phase: wait for OVERVIEW intent.
                mapped = 0f;
            } else if ("HINT".equals(name)) {
                // Native HINT is normalized by screen height. Restore physical
                // finger travel, with resistance only near the dock's endpoint.
                mapped = fraction * hintGain;
                if (mapped > .8f) mapped = .8f + .12f
                        * (1f - (float) Math.exp(-(mapped - .8f) / .12f));
            } else if ("OVERVIEW".equals(name)) {
                // Same raw PendingAnimation clock and tension as the native
                // card entry, allowing its small overshoot instead of clipping.
                mapped = DockEntryCurve.overview(fraction);
            } else if ("STATE_HOME".equals(name)) {
                mapped = fraction * fraction * (3f - 2f * fraction);
            }
            panel.setRevealOffset(startY + distance * mapped);
            int next = Math.round(fraction * 4f);
            if (next != bucket) {
                bucket = next;
                log("track " + name + " g=" + generation + " progress=" + fraction
                        + " mapped=" + mapped + " y=" + panel.revealOffset);
            }
        }
        boolean owns(Panel panel) {
            return panel.stateTrack == this && panel.generation == generation
                    && panel.owner.get() == null && panel.getParent() != null;
        }
        boolean nativeActive(Panel panel) {
            if (!owns(panel) || cancelled || finished) return false;
            Animator animator = nativeAnimator.get();
            if (animator != null && (animator.isStarted() || animator.isRunning())) return true;
            Object nativeTrack = panel.statePending.get();
            if ("HOME_SPRING".equals(name) && nativeTrack != null) {
                try { if (Boolean.TRUE.equals(RecentsDockHost.get(nativeTrack, "mAnimsStarted"))) return true; }
                catch (Throwable ignored) { }
                return panel.handlerActive(panel.committedOwner.get());
            }
            try {
                return Boolean.TRUE.equals(call(call(panel.container, "getStateManager"), "isInTransition"));
            } catch (Throwable ignored) { return false; }
        }
        void setFraction(Panel panel, float fraction) {
            set(panel, startY + (endY - startY) * clamp(fraction));
        }
        void start() {
            log("track " + name + " g=" + generation + " from=" + startY + " to=" + endY
                    + " direction=" + (endY >= startY ? "down" : "up") + " gain=" + hintGain);
        }
        void finishLog(Panel panel) {
            if (finished) return;
            finished = true;
            log("track " + name + " g=" + generation + " " + (cancelled ? "cancelled" : "ended")
                    + " y=" + panel.revealOffset);
        }
        void end(Panel panel, boolean remove) {
            if (!owns(panel)) return;
            finishLog(panel);
            if (remove) {
                View recents = panel.recents.get();
                if (recents != null && current(recents) == panel) removeIfCurrent(recents, panel);
                else panel.detach();
            }
        }
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static Object get(Object object, String name) throws Throwable { return OverviewDock.field(object, name); }
    private static Object call(Object object, String name) throws Throwable {
        return OverviewDock.method(object.getClass(), name).invoke(object);
    }
    private static void setField(Object object, String name, Object value) throws Throwable {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void log(String message) {
        android.util.Log.i("PixelRecentsA10", "dock host " + message);
        XposedBridge.log("PixelRecentsA10: dock host " + message);
    }
    private static void error(String operation, Throwable failure) {
        if (loggedError) return;
        loggedError = true;
        log(operation + " failed: " + failure);
    }
}
