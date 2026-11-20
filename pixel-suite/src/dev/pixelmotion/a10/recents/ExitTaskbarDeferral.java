package dev.pixelmotion.a10.recents;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Keep rotation-triggered taskbar inflation out of the visible home animation. */
final class ExitTaskbarDeferral {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Boolean> CLOCKS = new WeakHashMap<>();
    private static final Map<Object, Request> PENDING = new IdentityHashMap<>();
    private static final Map<Object, Runnable> AFTER_HOME = new IdentityHashMap<>();
    private static final Map<Object, Boolean> CONFIG_HOLDS = new IdentityHashMap<>();
    private static Method recreate;
    private static boolean scheduled, replaying;

    static void install(ClassLoader loader) throws Throwable {
        Class<?> manager = Class.forName("com.android.launcher3.taskbar.TaskbarManagerImpl", false, loader);
        Class<?> resource = Class.forName("com.android.launcher3.taskbar.PerDisplayTaskbarResource", false, loader);
        recreate = Reflect.method(manager, "recreateTaskbarForDisplay", resource, int.class, String.class);
        XposedBridge.hookMethod(recreate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (replaying || Looper.myLooper() != Looper.getMainLooper()) return;
                Object resource = p.args[0];
                try {
                    String caller = (String) p.args[2];
                    boolean config = caller != null && (caller.startsWith("onConfigChanged")
                            || caller.startsWith("onDisplayInfoChanged: CHANGE_ROTATION"));
                    if (!config || resource != Reflect.get(p.thisObject, "mPrimaryResource")
                            || Boolean.TRUE.equals(Reflect.get(resource, "isExternalDisplay"))
                            || Boolean.TRUE.equals(Reflect.get(resource, "isDestroyed"))
                            || Reflect.get(resource, "taskbar") == null) {
                        // A real lifecycle/settings change supersedes a queued rotation.
                        PENDING.remove(resource);
                        return;
                    }
                    if (CLOCKS.isEmpty() && CONFIG_HOLDS.isEmpty() && !PENDING.containsKey(resource)) return;
                    Request previous = PENDING.get(resource);
                    Request request = new Request(p.thisObject, resource,
                            ((Number) p.args[1]).intValue(), caller);
                    request.count = previous == null ? 1 : previous.count + 1;
                    PENDING.put(resource, request);
                    p.setResult(null);
                    // Logcat only: avoid synchronous LSPosed log IO on a frame.
                    android.util.Log.i("PixelRecentsA10", "defer rotation taskbar rebuild count=" + request.count
                            + " caller=" + caller);
                    scheduleDrain();
                } catch (Throwable failure) {
                    RecentsMotion.log("Taskbar deferral unavailable: " + failure);
                }
            }
        });
    }

    static void holdUntil(final Animator animator) {
        if (recreate == null || animator == null || CLOCKS.containsKey(animator)) return;
        hold(animator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animation) {
                // A seekable playback controller can dispatch CANCEL alone.
                release(animator);
            }
            @Override public void onAnimationEnd(Animator animation) {
                release(animator);
            }
        });
    }

    static void hold(Object nativeClock) {
        if (recreate != null && nativeClock != null) CLOCKS.put(nativeClock, Boolean.TRUE);
    }

    static void release(Object nativeClock) {
        CLOCKS.remove(nativeClock);
        scheduleDrain();
    }

    static void afterHomeFrame(Object key, Runnable action) {
        AFTER_HOME.put(key, action);
        scheduleDrain();
    }

    static void cancelAfterHomeFrame(Object key) { AFTER_HOME.remove(key); }

    static void holdConfiguration(Object key) { CONFIG_HOLDS.put(key, Boolean.TRUE); }
    static void releaseConfiguration(Object key) {
        CONFIG_HOLDS.remove(key);
        scheduleDrain();
    }

    private static void scheduleDrain() {
        if (scheduled || !CLOCKS.isEmpty() || (PENDING.isEmpty() && AFTER_HOME.isEmpty())) return;
        scheduled = true;
        // Let the native endpoint's final frame reach traversal before doing
        // inflation. This follows actual completion, including animation scale.
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                MAIN.post(new Runnable() {
                    @Override public void run() { drain(); }
                });
            }
        });
    }

    private static void drain() {
        scheduled = false;
        if (!CLOCKS.isEmpty()) return;
        // Cancelling HOME can hand ownership straight to a new overview drag.
        // Its new native motion must finish before any queued inflation runs.
        try {
            Object launcher = OverviewDock.launcher();
            if (launcher != null) {
                Object manager = Reflect.call(launcher, "getStateManager");
                Object overview = Reflect.call(launcher, "getOverviewPanel");
                if (Boolean.TRUE.equals(Reflect.call(manager, "isInTransition"))
                        || Boolean.TRUE.equals(Reflect.call(overview, "isGestureActive"))) {
                    scheduleDrain();
                    return;
                }
            }
        } catch (Throwable failure) {
            RecentsMotion.log("Taskbar transition check unavailable: " + failure);
        }
        List<Runnable> completions = new ArrayList<>(AFTER_HOME.values());
        AFTER_HOME.clear();
        for (Runnable completion : completions) completion.run();
        if (!CONFIG_HOLDS.isEmpty()) return;
        List<Request> requests = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Request request : requests) {
            try {
                if (Boolean.TRUE.equals(Reflect.get(request.resource, "isDestroyed"))
                        || Reflect.get(request.manager, "mPrimaryResource") != request.resource) continue;
                replaying = true;
                // Re-read current native configuration in the original method;
                // the two callbacks do not require separate taskbar instances.
                recreate.invoke(request.manager, request.resource, request.mode, request.caller);
                android.util.Log.i("PixelRecentsA10", "rotation taskbar rebuilt after home frame merged=" + request.count);
            } catch (Throwable failure) {
                RecentsMotion.log("Deferred taskbar rebuild failed: " + failure);
            } finally { replaying = false; }
        }
    }

    private static final class Request {
        final Object manager, resource;
        final int mode;
        final String caller;
        int count;
        Request(Object manager, Object resource, int mode, String caller) {
            this.manager = manager;
            this.resource = resource;
            this.mode = mode;
            this.caller = caller;
        }
    }
}
