package dev.pixelmotion.a10.recents;

import android.app.Application;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import dev.pixelsuite.FeatureConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Independent A10 overview shelf; the desktop animation module owns home animations. */
public final class RecentsMotion implements IXposedHookLoadPackage {
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final WeakHashMap<Object, Session> SESSIONS = new WeakHashMap<>();
    private static boolean installed;

    @Override public void handleLoadPackage(LoadPackageParam pkg) throws Throwable {
        if ("com.android.systemui".equals(pkg.packageName)
                && "com.android.systemui".equals(pkg.processName) && Build.VERSION.SDK_INT == 37) {
            if (!FeatureConfig.enabled(FeatureConfig.RECENTS_DOCK, true)
                    || !FeatureConfig.enabled(FeatureConfig.RECENTS_FLOAT, true)) return;
            try { NativeWindowBridge.install(pkg.classLoader); }
            catch (Throwable failure) { log("NativeComplements bridge installation failed: " + failure); }
            return;
        }
        if (!LAUNCHER.equals(pkg.packageName) || !LAUNCHER.equals(pkg.processName)
                || Build.VERSION.SDK_INT != 37) return;
        Class<?> app = Class.forName("com.android.launcher3.LauncherApplication", false, pkg.classLoader);
        XposedBridge.hookMethod(Reflect.method(app, "onCreate"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (installed) return;
                installed = true;
                try {
                    int version = ((Application) p.thisObject).getPackageManager()
                            .getPackageInfo(LAUNCHER, 0).versionCode;
                    if (version != 907) { log("Unsupported launcher " + version); return; }
                    install(pkg.classLoader);
                    log("v1.24 dock entry and recents layout active; launcher=907, system=37");
                } catch (Throwable error) { log("Overview installation failed: " + error); }
            }
        });
    }

    private static void install(ClassLoader loader) throws Throwable {
        OverviewDock.install(loader);
        DockLaunch.install(loader);
        DockRevealTail.install(loader);
        if (FeatureConfig.enabled(FeatureConfig.RECENTS_LAYOUT, true))
            OverviewLayout.install(loader);
        if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true))
            TaskShelf.install(loader);
        try { ExitTaskbarDeferral.install(loader); }
        catch (Throwable error) { log("Taskbar deferral hooks unavailable: " + error); }
        try { RotatedHomeFinish.install(loader); }
        catch (Throwable error) { log("Rotated home finish hooks unavailable: " + error); }
        Class<?> workspace = Class.forName("com.android.launcher3.Workspace", false, loader);
        Class<?> stateConfig = Class.forName("com.android.launcher3.states.StateAnimationConfig", false, loader);
        Class<?> pending = Class.forName("com.android.launcher3.anim.PendingAnimation", false, loader);
        XposedBridge.hookMethod(Reflect.method(workspace, "setStateWithAnimation", Object.class,
                stateConfig, pending), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try {
                    RecentsDockHost.prepareStateAnimation(Reflect.get(p.thisObject, "mLauncher"),
                            p.args[0], p.args[2]);
                } catch (Throwable error) { log("State shelf track unavailable: " + error); }
            }
        });
        try {
            Class<?> taskUtils = Class.forName("com.android.quickstep.TaskViewUtils", false, loader);
            Class<?> recentsView = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
            Class<?> animatedSurface = Class.forName(
                    "com.android.wm.shell.shared.compat.AnimatedSurface", false, loader);
            Class<?> animatedSurfaceArray = java.lang.reflect.Array.newInstance(
                    animatedSurface, 0).getClass();
            XposedBridge.hookMethod(Reflect.method(taskUtils, "findTaskViewToLaunch", recentsView,
                    View.class, animatedSurfaceArray), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 1 && p.args[1] instanceof View
                            && RecentsDockHost.isDockView((View) p.args[1])) {
                        p.setResult(null);
                        log("dock launch uses icon transition path");
                    }
                }
            });
            Class<?> taskView = Class.forName("com.android.quickstep.views.TaskView", false, loader);
            Class<?> interpolator = Class.forName("android.view.animation.Interpolator", false, loader);
            XposedBridge.hookMethod(Reflect.method(recentsView, "createTaskLaunchAnimation", taskView,
                    long.class, interpolator), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && p.getResult() != null) {
                        RecentsDockHost.prepareTaskLaunchAnimation(p.thisObject, p.getResult());
                    }
                }
            });
            for (Method method : taskUtils.getDeclaredMethods()) {
                if (!"createRecentsWindowAnimator".equals(method.getName())
                        || method.getParameterTypes().length != 10) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable()) {
                            RecentsDockHost.prepareTaskLaunchAnimation(p.args[0], p.args[9]);
                        }
                    }
                });
            }
        } catch (Throwable error) { log("Task launch dock hooks unavailable: " + error); }
        Class<?> handler = Class.forName("com.android.quickstep.AbsSwipeUpHandler", false, loader);
        XposedBridge.hookMethod(Reflect.method(handler, "onGestureStarted", boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // TASK_EXIT can already have disposed the old dock while its
                // request is waiting for WM. Cancel before any new dock exists.
                RotatedHomeFinish.beforeNewGesture();
                DockAppBubble.cancel();
            }
        });
        XposedBridge.hookMethod(Reflect.method(handler,
                "lambda$maybeUpdateRecentsAttachedState$11", ValueAnimator.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) {
                    RecentsDockHost.onRecentsReveal(p.thisObject, (ValueAnimator) p.args[0]);
                }
            }
        });
        Class<?> homeFactory = Class.forName(
                "com.android.quickstep.SwipeUpAnimationLogic$HomeAnimationFactory", false, loader);
        XposedBridge.hookMethod(Reflect.method(handler, "createWindowAnimationToHome",
                float.class, homeFactory), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.getResult() == null) return;
                Object[] springs = (Object[]) p.getResult();
                if (springs.length > 0 && springs[0] != null) {
                    RecentsDockHost.prepareHomeSpring(p.thisObject, springs[0]);
                }
            }
        });
        XposedBridge.hookMethod(Reflect.method(handler, "onCurrentShiftUpdated"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                update(p.thisObject);
            }
        });
        XposedBridge.hookMethod(Reflect.method(handler, "onGestureEnded",
                float.class, android.graphics.PointF.class, boolean.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RecentsDockHost.onAppFingerReleased(p.thisObject);
            }
        });
        Class<?> homeController = Class.forName(
                "com.android.launcher3.uioverrides.touchcontrollers.NoButtonNavbarToOverviewTouchController",
                false, loader);
        XposedBridge.hookMethod(Reflect.method(homeController, "onDrag",
                float.class, float.class, android.view.MotionEvent.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && p.args[2] instanceof android.view.MotionEvent)
                    RecentsDockHost.onHomeFingerProgress(p.thisObject,
                            (android.view.MotionEvent) p.args[2]);
            }
        });
        XposedBridge.hookMethod(Reflect.method(homeController, "onDragEnd", float.class),
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RecentsDockHost.onHomeFingerReleased(p.thisObject);
            }
        });
        for (String name : new String[]{"onLauncherPresentAndGestureStarted", "onLauncherStart"}) {
            XposedBridge.hookMethod(Reflect.method(handler, name), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { update(p.thisObject); }
            });
        }
        for (String name : new String[]{"reset", "onConsumerAboutToBeSwitched"}) {
            XposedBridge.hookMethod(Reflect.method(handler, name), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { finish(p.thisObject, false); }
            });
        }
        for (String name : new String[]{"onRecentsAnimationStartTimedOut", "onGestureCancelled"}) {
            XposedBridge.hookMethod(Reflect.method(handler, name), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { finish(p.thisObject, true); }
            });
        }
        XposedBridge.hookMethod(Reflect.method(handler, "cleanUpOnFailedRecentsAnimation", String.class),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { finish(p.thisObject, true); }
                });
        Class<?> panel = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
        Class<?> launcherActivity = Class.forName("com.android.launcher3.Launcher", false, loader);
        XposedBridge.hookMethod(Reflect.method(launcherActivity, "onStop"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RecentsDockHost.onContainerStopped(p.thisObject);
            }
        });
        XposedBridge.hookMethod(Reflect.method(launcherActivity, "onNewIntent", Intent.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof Intent)) return;
                Intent intent = (Intent) p.args[0];
                if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME))
                    RecentsDockHost.onHomeIntent(p.thisObject);
            }
        });
        XposedBridge.hookMethod(Reflect.method(panel, "updateOrientationHandler", boolean.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable()) RecentsDockHost.onOrientationChanged((View) p.thisObject);
                    }
                });
        XposedBridge.hookMethod(Reflect.method(panel, "updateRecentsRotation"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) RecentsDockHost.onOrientationChanged((View) p.thisObject);
            }
        });
        // In-place Recents rotation updates touch rotation without changing the
        // Launcher's display or DeviceProfile. Hook its public commit point.
        XposedBridge.hookMethod(Reflect.method(panel, "setLayoutRotation", int.class, int.class),
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) RecentsDockHost.onOrientationChanged((View) p.thisObject);
            }
        });
        XposedBridge.hookMethod(panel.getDeclaredMethod("onConfigurationChanged",
                android.content.res.Configuration.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) RecentsDockHost.onOrientationChanged((View) p.thisObject);
            }
        });
        Class<?> displayInfo = Class.forName("com.android.launcher3.display.DisplayInfoContainer", false, loader);
        XposedBridge.hookMethod(Reflect.method(displayInfo, "notifyConfigChange"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) RecentsDockHost.onDisplayChanged();
            }
        });
        XposedBridge.hookMethod(Reflect.method(panel, "onDetachedFromWindow"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                RecentsDockHost.onRecentsDetached((View) p.thisObject);
                if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true))
                    TaskShelf.onOverview((View) p.thisObject, false);
            }
        });
        for (String name : new String[]{"com.android.launcher3.uioverrides.QuickstepLauncher",
                "com.android.quickstep.RecentsActivity", "com.android.quickstep.window.RecentsWindowManager"}) {
            Class<?> container;
            try { container = Class.forName(name, false, loader); }
            catch (ClassNotFoundException ignored) { continue; }
            for (Method method : container.getDeclaredMethods()) {
                if (!"onStateSetEnd".equals(method.getName()) || method.getParameterTypes().length != 1
                        || method.isBridge()) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            // A superseded transition can finish after a newer
                            // state is current, especially during rotation.
                            Object currentState = Reflect.get(
                                    Reflect.call(p.thisObject, "getStateManager"), "mState");
                            if (currentState != p.args[0]) {
                                log("Ignore stale state completion: " + p.args[0]
                                        + ", current=" + currentState);
                                return;
                            }
                            Object panel = Reflect.call(p.thisObject, "getOverviewPanel");
                            if (!(panel instanceof View)) return;
                            boolean overview = isOverview(p.args[0]);
                            // Home gestures pass through HINT before committing
                            // overview; this endpoint is not a shelf dismissal.
                            if ("HintState".equals(p.args[0].getClass().getSimpleName())) return;
                            // BACKGROUND_APP is established while the finger is
                            // already dragging. It is a start endpoint, not an exit.
                            if (!overview && ("BackgroundAppState".equals(
                                    p.args[0].getClass().getSimpleName())
                                    || (Boolean) Reflect.call(panel, "isGestureActive"))) return;
                            OverviewDock.onOverview((View) panel, overview);
                            if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true))
                                TaskShelf.onOverview((View) panel, overview);
                            log("State settled: " + p.thisObject.getClass().getSimpleName()
                                    + ", state=" + p.args[0] + ", overview=" + overview);
                        } catch (Throwable error) { log("State settle skipped: " + error); }
                    }
                });
            }
        }
    }

    private static void update(Object handler) {
        Session session = SESSIONS.get(handler);
        if (session == null) { session = new Session(); SESSIONS.put(handler, session); }
        if (session.failed || session.stopped) return;
        try {
            Object panel = Reflect.get(handler, "mRecentsView");
            if (!(panel instanceof View) || ((View) panel).getWindowToken() == null) return;
            View recents = (View) panel;
            float shift = (Float) Reflect.call(handler, "getCurrentShiftValue");
            if (!Float.isFinite(shift)) return;
            shift = Math.max(0f, Math.min(1f, shift));
            Object gesture = Reflect.get(handler, "mGestureState");
            Object end = Reflect.call(gesture, "getEndTarget");
            String target = end == null ? "DRAG" : end.toString();
            boolean handBack = end != null && !"RECENTS".equals(target);
            if ((Boolean) Reflect.get(handler, "mIsLikelyToStartNewTask")) handBack = true;
            if (!session.logged) {
                session.logged = true;
                log("Gesture attached: handler=" + handler.getClass().getName()
                        + ", panel=" + recents.getClass().getName() + ", container="
                        + Reflect.get(handler, "mContainer") + ", root=" + recents.getRootView().getClass().getName());
            }
            OverviewDock.onProgress(handler, recents, shift, handBack);
            if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true))
                TaskShelf.onProgress(handler, recents, shift, handBack);
            int bucket = (int) (shift * 10);
            if (bucket != session.bucket || !target.equals(session.target)) {
                session.bucket = bucket;
                session.target = target;
                log("Gesture progress=" + shift + ", target=" + target + ", handedBack=" + handBack);
            }
        } catch (Throwable error) {
            session.failed = true;
            OverviewDock.abort(handler);
            if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true)) TaskShelf.reset(handler);
            log("Gesture restore after error: " + error);
        }
    }

    private static void finish(Object handler, boolean aborted) {
        Session session = SESSIONS.get(handler);
        if (session == null) { session = new Session(); SESSIONS.put(handler, session); }
        // Cancelling first calls updateDisplacement(0). Keep a weak tombstone so
        // that callback cannot acquire the panel again before endTarget is set.
        session.stopped = true;
        if (aborted) OverviewDock.abort(handler);
        else OverviewDock.reset(handler);
        if (FeatureConfig.enabled(FeatureConfig.RECENTS_SHELF, true)) TaskShelf.reset(handler);
    }

    private static boolean isOverview(Object state) throws Throwable {
        try { return (Boolean) Reflect.call(state, "isInOverview"); }
        catch (NoSuchMethodException ignored) { }
        String name = state.getClass().getSimpleName();
        return "OverviewState".equals(name) || "DEFAULT".equals(state.toString());
    }

    static void log(String message) {
        android.util.Log.i("PixelRecentsA10", message);
        XposedBridge.log("PixelRecentsA10: " + message);
    }

    private static final class Session {
        boolean logged, failed, stopped;
        int bucket = -1;
        String target = "";
    }
}

