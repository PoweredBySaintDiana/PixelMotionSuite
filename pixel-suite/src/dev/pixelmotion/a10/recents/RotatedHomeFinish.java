package dev.pixelmotion.a10.recents;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Retain WM's Recents rotation until the rotated overview has finished leaving. */
final class RotatedHomeFinish {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Finish> FINISHES = new IdentityHashMap<>();
    private static Method nativeFinish;
    private static Class<?> surfaceTransactionClass;
    private static Class<?> surfaceControlClass;
    private static Constructor<?> surfaceCopy;
    private static Executor helper;

    static void install(ClassLoader loader) throws Throwable {
        Class<?> controller = Class.forName("com.android.quickstep.RecentsAnimationController", false, loader);
        nativeFinish = Reflect.method(controller, "lambda$finishController$2", boolean.class, boolean.class);
        helper = (Executor) OverviewDock.field(Class.forName("com.android.launcher3.util.Executors", false, loader),
                "UI_HELPER_EXECUTOR");
        Class<?> factory = Class.forName("com.android.quickstep.util.RecentsAtomicAnimationFactory", false, loader);
        Class<?> state = Class.forName("com.android.launcher3.statemanager.BaseState", false, loader);
        Class<?> config = Class.forName("com.android.launcher3.states.StateAnimationConfig", false, loader);
        Class<?> recents = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
        surfaceTransactionClass = Class.forName("com.android.quickstep.util.SurfaceTransaction", false, loader);
        surfaceControlClass = Class.forName("android.view.SurfaceControl", false, loader);
        surfaceCopy = surfaceControlClass.getDeclaredConstructor(surfaceControlClass, String.class);
        surfaceCopy.setAccessible(true);
        XposedBridge.hookMethod(Reflect.method(recents, "finishRecentsAnimation", boolean.class,
                boolean.class, Runnable.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                if (!Boolean.TRUE.equals(p.args[0])) return;
                try {
                    Object controller = Reflect.get(p.thisObject, "mRecentsAnimationController");
                    Finish finish;
                    synchronized (FINISHES) { finish = FINISHES.get(controller); }
                    if (finish != null && finish.snapshotReady && !finish.surfacesHidden) {
                        hideScreenshotBackedApps(finish);
                    }
                } catch (Throwable failure) {
                    RecentsMotion.log("Rotated screenshot surface cleanup unavailable: " + failure);
                }
            }
        });
        Class<?> screenshotReady = Class.forName(
                "com.android.quickstep.util.RecentsAtomicAnimationFactory$applyOverviewToHomeAnimConfig$1",
                false, loader);
        XposedBridge.hookMethod(Reflect.method(screenshotReady, "run"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object overview = Reflect.get(p.thisObject, "$overview");
                    Object controller = Reflect.get(overview, "mRecentsAnimationController");
                    synchronized (FINISHES) {
                        Finish finish = FINISHES.get(controller);
                        if (finish != null) finish.snapshotReady = true;
                    }
                } catch (Throwable failure) {
                    RecentsMotion.log("Rotated screenshot readiness unavailable: " + failure);
                }
            }
        });
        XposedBridge.hookMethod(Reflect.method(factory, "applyOverviewToHomeAnimConfig", state, config,
                recents, boolean.class, boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                try {
                    Object oriented = Reflect.get(p.args[2], "mOrientationState");
                    int display = ((Number) Reflect.get(oriented, "mDisplayRotation")).intValue();
                    int touch = ((Number) Reflect.call(oriented, "getTouchRotation")).intValue();
                    if ((display & 1) == 0 && (touch & 1) == 0) return;
                    Object controller = Reflect.get(p.args[2], "mRecentsAnimationController");
                    if (controller == null || Boolean.TRUE.equals(Reflect.get(controller, "mFinishRequested"))) return;
                    synchronized (FINISHES) {
                        if (!FINISHES.containsKey(controller)) {
                            Finish finish = new Finish(controller);
                            captureAppSurfaces(finish, p.args[2]);
                            FINISHES.put(controller, finish);
                        }
                    }
                    // Cover screenshot preparation as well as the animator that
                    // Workspace adds later in this same state-building call.
                    ExitTaskbarDeferral.hold(p.args[1]);
                } catch (Throwable failure) {
                    RecentsMotion.log("Rotated home preparation unavailable: " + failure);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Looper.myLooper() == Looper.getMainLooper()) ExitTaskbarDeferral.release(p.args[1]);
            }
        });
        XposedBridge.hookMethod(nativeFinish, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                final Finish finish;
                synchronized (FINISHES) {
                    finish = FINISHES.get(p.thisObject);
                    if (finish == null) return;
                    if (!Boolean.TRUE.equals(p.args[0])) {
                        finish.released = true;
                        FINISHES.remove(p.thisObject);
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                ExitTaskbarDeferral.cancelAfterHomeFrame(finish);
                                ExitTaskbarDeferral.releaseConfiguration(finish);
                                try { if (finish.surfacesHidden) applyAppAlpha(finish, 1f); }
                                catch (Throwable failure) { RecentsMotion.log("Restore app surface failed: " + failure); }
                                releaseSurfaces(finish);
                            }
                        });
                        return;
                    }
                    if (finish.args != null) {
                        p.setResult(null);
                        return;
                    }
                    finish.args = p.args.clone();
                }
                // The original controller has already recorded its finish
                // target and retained all completion callbacks. Only its WM
                // transaction is delayed; keep that exact captured controller.
                p.setResult(null);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (finish.released) return;
                        ExitTaskbarDeferral.holdConfiguration(finish);
                        try {
                            Object callbacks = Reflect.get(finish.controller, "mPendingFinishCallbacks");
                            Reflect.method(callbacks.getClass(), "add", Runnable.class).invoke(callbacks,
                                    new Runnable() {
                                @Override public void run() {
                                    // Keep both orientation notifications coalesced
                                    // through the real WM result and next frame.
                                    MAIN.post(new Runnable() {
                                        @Override public void run() {
                                            ExitTaskbarDeferral.afterHomeFrame(finish, new Runnable() {
                                                @Override public void run() {
                                                    ExitTaskbarDeferral.releaseConfiguration(finish);
                                                    releaseSurfaces(finish);
                                                }
                                            });
                                        }
                                    });
                                }
                            });
                        } catch (Throwable failure) {
                            ExitTaskbarDeferral.releaseConfiguration(finish);
                            RecentsMotion.log("Rotated finish callback unavailable: " + failure);
                        }
                        android.util.Log.i("PixelRecentsA10", "hold rotated WM finish until home frame");
                        ExitTaskbarDeferral.afterHomeFrame(finish, new Runnable() {
                            @Override public void run() { release(finish); }
                        });
                    }
                });
            }
        });
    }

    static void beforeNewGesture() {
        ArrayList<Finish> finishes;
        synchronized (FINISHES) { finishes = new ArrayList<>(FINISHES.values()); }
        for (Finish finish : finishes) release(finish);
    }

    private static void captureAppSurfaces(Finish finish, Object overview) throws Throwable {
        Object[] handles = (Object[]) Reflect.get(overview, "mRemoteTargetHandles");
        if (handles == null) return;
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object handle : handles) {
            Object params = Reflect.call(handle, "getTransformParams");
            Object targets = Reflect.call(params, "getTargetSet");
            if (targets == null) continue;
            for (Object app : (Object[]) Reflect.get(targets, "apps")) {
                Object configuration = Reflect.get(app, "windowConfiguration");
                int type = ((Number) Reflect.call(configuration, "getActivityType")).intValue();
                // Never hide HOME, Recents, wallpaper or navigation surfaces.
                if (type == 2 || type == 3 || ((Number) Reflect.get(app, "mode")).intValue() != 1) continue;
                Object leash = Reflect.get(app, "leash");
                if (leash == null || seen.put(leash, Boolean.TRUE) != null
                        || !Boolean.TRUE.equals(Reflect.call(leash, "isValid"))) continue;
                Object copy = surfaceCopy.newInstance(leash, "PixelRecents rotated screenshot handoff");
                finish.surfaces.add(new AppSurface(copy, params));
            }
        }
    }

    private static int applyAppAlpha(Finish finish, float alpha) throws Throwable {
        Object transaction = surfaceTransactionClass.getDeclaredConstructor().newInstance();
        Object applier = null;
        int count = 0;
        for (AppSurface surface : finish.surfaces) {
            if (!Boolean.TRUE.equals(Reflect.call(surface.leash, "isValid"))) continue;
            Object properties = Reflect.method(surfaceTransactionClass, "forSurface", surfaceControlClass)
                    .invoke(transaction, surface.leash);
            Reflect.method(properties.getClass(), "setAlpha", float.class).invoke(properties, alpha);
            applier = surface.params;
            count++;
        }
        if (applier != null) Reflect.method(applier.getClass(), "applySurfaceParams", surfaceTransactionClass)
                .invoke(applier, transaction);
        return count;
    }

    private static void hideScreenshotBackedApps(Finish finish) throws Throwable {
        // The screenshot callback runs only after native postFrameDrawn.
        // Queue through the same renderer applier as the live task, so this
        // hides its stale surface after prior task transforms, with a frame.
        int hidden = applyAppAlpha(finish, 0f);
        if (hidden == 0) return;
        finish.surfacesHidden = true;
        android.util.Log.i("PixelRecentsA10", "rotated live surfaces hidden after screenshot count=" + hidden);
    }

    private static void releaseSurfaces(Finish finish) {
        for (AppSurface surface : finish.surfaces) {
            try { Reflect.call(surface.leash, "release"); }
            catch (Throwable ignored) { }
        }
        finish.surfaces.clear();
    }

    private static void release(final Finish finish) {
        final Object[] args;
        synchronized (FINISHES) {
            if (finish.released) return;
            finish.released = true;
            if (FINISHES.get(finish.controller) == finish) FINISHES.remove(finish.controller);
            args = finish.args;
        }
        ExitTaskbarDeferral.cancelAfterHomeFrame(finish);
        if (args == null) { releaseSurfaces(finish); return; }
        helper.execute(new Runnable() {
            @Override public void run() {
                try {
                    // Re-enter the original executor and method. Its native
                    // result receiver still runs the real completion chain.
                    nativeFinish.invoke(finish.controller, args);
                    android.util.Log.i("PixelRecentsA10", "rotated WM finish submitted after home frame");
                } catch (Throwable failure) {
                    MAIN.post(new Runnable() {
                        @Override public void run() { ExitTaskbarDeferral.releaseConfiguration(finish); }
                    });
                    RecentsMotion.log("Deferred rotated WM finish failed: " + failure);
                }
            }
        });
    }

    private static final class Finish {
        final Object controller;
        Object[] args;
        final ArrayList<AppSurface> surfaces = new ArrayList<>();
        boolean snapshotReady, surfacesHidden;
        volatile boolean released;
        Finish(Object controller) { this.controller = controller; }
    }

    private static final class AppSurface {
        final Object leash, params;
        AppSurface(Object leash, Object params) { this.leash = leash; this.params = params; }
    }
}
