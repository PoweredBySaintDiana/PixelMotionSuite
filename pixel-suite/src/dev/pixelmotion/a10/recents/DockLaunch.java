package dev.pixelmotion.a10.recents;

import android.view.View;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;

/** Hands a prediction launch from live overview back to the native icon runner. */
final class DockLaunch {
    private static final WeakHashMap<View, Boolean> SOURCES = new WeakHashMap<>();
    private static Class<?> launcherClass;
    private static Method snapshot;
    private static Method finishRecents;
    private static Method fetchIcon;
    private static boolean installed;
    private static ClassLoader classLoader;
    private static final Set<Object> ROTATING_TRANSITIONS = new HashSet<>();
    private static final WeakHashMap<Object, Object> ROTATING_FACTORIES = new WeakHashMap<>();
    private static final WeakHashMap<View, Object[]> DIRECT_SOURCES = new WeakHashMap<>();

    private DockLaunch() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        classLoader = loader;
        launcherClass = load(loader, "com.android.launcher3.Launcher");
        Class<?> item = load(loader, "com.android.launcher3.model.data.ItemInfo");
        Class<?> recents = load(loader, "com.android.quickstep.views.RecentsView");
        Class<?> floating = load(loader, "com.android.launcher3.views.FloatingIconView");
        snapshot = Reflect.method(recents, "switchToScreenshot", Runnable.class);
        finishRecents = Reflect.method(recents, "finishRecentsAnimation",
                boolean.class, boolean.class, Runnable.class);
        fetchIcon = Reflect.method(floating, "fetchIcon", launcherClass, View.class, item, boolean.class);

        Class<?> manager = load(loader, "com.android.launcher3.QuickstepTransitionManager");
        XposedBridge.hookMethod(Reflect.method(manager, "getActivityLaunchOptions", View.class, item),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] instanceof View && isPredictionSource((View) p.args[0])) {
                    View source = (View) p.args[0];
                    SOURCES.put(source, Boolean.TRUE);
                    try {
                        Object launcher = Reflect.get(p.thisObject, "mLauncher");
                        prefetch(launcher, source, p.args[1]);
                    }
                    catch (Throwable failure) { log("dock launch prefetch skipped: " + failure); }
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!(p.args[0] instanceof View) || !known((View) p.args[0]) || p.hasThrowable()) return;
                Object runner = Reflect.get(p.thisObject, "mAppLaunchRunner");
                log("dock launch factory=" + (runner == null ? "null" : runner.getClass().getSimpleName())
                        + " animationType=" + (p.args[1] == null ? "null" : Reflect.get(p.args[1], "animationType")));
                if (Math.abs(((View) p.args[0]).getRotation()) > 1f && runner != null)
                    directRotatingTransition(p.thisObject, (View) p.args[0], runner, p.getResult());
            }
        });

        // A prediction is an icon even if its backing suggestion requests a
        // container background animation. Keep the original profile/shortcut tag.
        Class<?> container = load(loader, "com.android.launcher3.remoteanimations.ContainerAnimationRunner");
        for (Method method : container.getDeclaredMethods()) {
            if (!"fromView".equals(method.getName()) || method.getParameterTypes().length != 6) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args[0] instanceof View && known((View) p.args[0])) {
                        p.setResult(null);
                        log("dock container factory bypassed for prediction icon");
                    }
                }
            });
        }

        Class<?> surface = load(loader, "com.android.wm.shell.shared.compat.AnimatedSurface");
        Class<?> taskUtils = load(loader, "com.android.quickstep.TaskViewUtils");
        XposedBridge.hookMethod(Reflect.method(taskUtils, "findTaskViewToLaunch", recents,
                View.class, Array.newInstance(surface, 0).getClass()), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[1] instanceof View && known((View) p.args[1])) p.setResult(null);
            }
        });

        Class<?> runner = load(loader, "com.android.launcher3.QuickstepTransitionManager$AppLaunchAnimationRunner");
        for (Method method : runner.getDeclaredMethods()) {
            if (!"onAnimationStart".equals(method.getName())) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object view = Reflect.get(p.thisObject, "mV");
                    if (view instanceof View && known((View) view)) {
                        log("dock native icon runner starts apps=" + Array.getLength(p.args[1])
                                + " source attached=" + (((View) view).getWindowToken() != null));
                    }
                }
            });
        }
        Class<?> nativeRunner = load(loader, "com.android.launcher3.LauncherAnimationRunner");
        for (Method method : nativeRunner.getDeclaredMethods()) {
            if (!"onAnimationStart".equals(method.getName()) && !"onAnimationCancelled".equals(method.getName())) continue;
            final String event = method.getName();
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object factory = ROTATING_FACTORIES.get(p.thisObject);
                    if (factory != null) log("dock rotating native dispatch=" + event);
                }
            });
        }
        Class<?> bridgeClass = load(loader, "com.android.systemui.animation.RemoteAnimationRunnerCompat$1");
        for (Method method : bridgeClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!"startAnimation".equals(name) && !"mergeAnimation".equals(name)
                    && !"onTransitionConsumed".equals(name)) continue;
            final String event = name;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (ROTATING_TRANSITIONS.contains(p.thisObject)) log("dock rotating bridge=" + event);
                }
            });
        }
        installed = true;
        log("dock native icon transition handoff ready");
    }

    /** The picker can consume a rotation before delegating cancellation to its factory. */
    private static void directRotatingTransition(Object manager, final View source, Object factory, Object wrapper) {
        try {
            Class<?> runnerType = load(classLoader, "com.android.launcher3.LauncherAnimationRunner");
            Class<?> factoryType = load(classLoader, "com.android.launcher3.LauncherAnimationRunner$RemoteAnimationFactory");
            final Object runner = runnerType.getDeclaredConstructor(Handler.class, factoryType, boolean.class)
                    .newInstance(new Handler(Looper.getMainLooper()), factory, true);
            Class<?> bridgeType = load(classLoader, "com.android.systemui.animation.RemoteAnimationRunnerCompat$1");
            final Object bridge = bridgeType.getDeclaredConstructor(runnerType).newInstance(runner);
            Object launcher = Reflect.get(manager, "mLauncher");
            Class<?> transitionType = load(classLoader, "android.window.RemoteTransition");
            Object transition = transitionType.getDeclaredConstructor(
                    load(classLoader, "android.window.IRemoteTransition"),
                    load(classLoader, "android.app.IApplicationThread"), String.class)
                    .newInstance(bridge, Reflect.call(launcher, "getIApplicationThread"), "OverviewNativeIcon");
            Object options = Reflect.get(wrapper, "options");
            Reflect.method(options.getClass(), "setRemoteTransition", transitionType).invoke(options, transition);
            ROTATING_TRANSITIONS.add(bridge);
            ROTATING_FACTORIES.put(runner, factory);
            DIRECT_SOURCES.put(source, new Object[]{bridge, runner});
            Object callbacks = Reflect.get(wrapper, "onEndCallback");
            Reflect.method(callbacks.getClass(), "add", Runnable.class).invoke(callbacks, new Runnable() {
                @Override public void run() {
                    ROTATING_TRANSITIONS.remove(bridge);
                    ROTATING_FACTORIES.remove(runner);
                    Object[] direct = DIRECT_SOURCES.get(source);
                    if (direct != null && direct[0] == bridge) DIRECT_SOURCES.remove(source);
                }
            });
            log("dock rotating launch uses direct native icon transition");
        } catch (Throwable failure) { log("dock direct native transition unavailable: " + failure); }
    }

    /**
     * True means the launch will be posted after the live-recents controller is
     * finished. False means there is no controller and the caller should launch
     * immediately. The caller retains the source and owns pending/cancel state.
     */
    static boolean deferUntilRecentsFinished(Object container, final View source,
            final Runnable launch) throws Throwable {
        if (!installed || source == null || launch == null) return false;
        SOURCES.put(source, Boolean.TRUE);
        final View overview = (View) Reflect.call(container, "getOverviewPanel");
        Object controller = Reflect.get(overview, "mRecentsAnimationController");
        if (controller == null) {
            log("dock icon launch already outside live recents container="
                    + container.getClass().getSimpleName());
            return false;
        }
        // A live recents transition can absorb startActivity and never deliver
        // QuickstepLaunch's animation. Snapshot its last frame before finishing.
        // LauncherState, the source View and its ItemInfo are kept unchanged.
        final Runnable once = new Runnable() {
            boolean ran;
            @Override public void run() {
                if (ran) return;
                ran = true;
                log("dock live recents finished; launch source attached="
                        + (source.getWindowToken() != null));
                launch.run();
            }
        };
        final Runnable finish = new Runnable() {
            boolean ran;
            @Override public void run() {
                if (ran) return;
                ran = true;
                Runnable nativeFinish = new Runnable() {
                    @Override public void run() {
                        try {
                            finishRecents.invoke(overview, true, false, new Runnable() {
                                @Override public void run() {
                                    source.post(once);
                                }
                            });
                        } catch (Throwable failure) {
                            log("dock live recents finish failed: " + failure);
                            source.post(once);
                        }
                    }
                };
                nativeFinish.run();
            }

        };
        log("dock icon launch snapshots and finishes live recents container="
                + container.getClass().getSimpleName());
        final Runnable requestFinish = new Runnable() {
            @Override public void run() {
                try { snapshot.invoke(overview, finish); }
                catch (Throwable failure) {
                    log("dock snapshot unavailable: " + failure);
                    finish.run();
                }
            }
        };
        requestFinish.run();
        return true;
    }

    static void cancelSource(View source) {
        if (source == null) return;
        Object[] direct = DIRECT_SOURCES.remove(source);
        if (direct != null) {
            ROTATING_TRANSITIONS.remove(direct[0]);
            ROTATING_FACTORIES.remove(direct[1]);
        }
    }

    private static boolean isPredictionSource(View source) {
        return PredictionIcons.isPrediction(source) && RecentsDockHost.isDockView(source);
    }

    private static boolean known(View source) {
        return Boolean.TRUE.equals(SOURCES.get(source)) || isPredictionSource(source);
    }

    private static void prefetch(Object launcher, View source, Object item) {
        if (item == null) return;
        try { fetchIcon.invoke(null, launcher, source, item, true); }
        catch (Throwable failure) { log("dock native icon prefetch unavailable: " + failure); }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static void log(String text) { android.util.Log.i("PixelRecentsA10", text); }
}
