package dev.pixelmotion.a10.recents;

import android.content.Context;
import android.content.Intent;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.ResultReceiver;
import android.os.Parcelable;
import android.os.Looper;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Preserves the native card handoff, then delegates small-window launch to NativeComplements. */
final class DockAppBubble {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Method launchTask;
    private static Method goToState;
    private static Object normalState;
    private static Class<?> userClass;
    private static Session active;
    private static boolean installed;

    private DockAppBubble() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        userClass = Class.forName("android.os.UserHandle", false, loader);
        Class<?> recentsClass = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
        Class<?> taskClass = Class.forName("com.android.quickstep.views.TaskView", false, loader);
        launchTask = Reflect.method(taskClass, "launchWithAnimation");
        Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, loader);
        Class<?> baseStateClass = Class.forName("com.android.launcher3.statemanager.BaseState", false, loader);
        Class<?> managerClass = Class.forName("com.android.launcher3.statemanager.StateManager", false, loader);
        normalState = Reflect.field(stateClass, "NORMAL").get(null);
        goToState = Reflect.method(managerClass, "goToState", baseStateClass,
                boolean.class, Animator.AnimatorListener.class);
        XposedBridge.hookMethod(Reflect.method(recentsClass,
                "onTaskLaunchedInLiveTileModeCancelled"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Session session = active;
                if (session != null && session.overview == p.thisObject)
                    session.cancel("native task animation cancelled");
            }
        });
        installed = true;
        log("native card-to-NativeComplements small-window route ready");
    }

    /**
     * A live session restores its underlying task and awaits Shell's finish
     * acknowledgement. Overview entered from HOME instead returns to NORMAL
     * through its native state animation, including when there are no cards.
     */
    static boolean launch(Object container, View source, Object item, Intent intent,
            Runnable onHandedOff, Runnable onRejected) throws Throwable {
        if (!installed) throw new IllegalStateException("Application bubble route is not installed");
        if (source == null || source.getWindowToken() == null || item == null || intent == null)
            throw new IllegalStateException("Application bubble source is no longer attached");
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Application bubble must be requested on the UI thread");

        final Context context = container instanceof Context ? (Context) container : source.getContext();
        final Object overview = Reflect.call(container, "getOverviewPanel");
        final Object controller = Reflect.get(overview, "mRecentsAnimationController");
        if (controller != null && Boolean.TRUE.equals(Reflect.get(controller, "mFinishRequested")))
            throw new IllegalStateException("Live Recents controller is no longer available");
        final Object task = controller == null ? null : Reflect.call(overview, "getRunningTaskView");
        if (controller != null) {
            if (!(task instanceof View) || ((View) task).getWindowToken() == null
                || !Boolean.TRUE.equals(Reflect.call(task, "isRunningTask"))
                || !Boolean.TRUE.equals(Reflect.get(task, "isClickableAsLiveTile")))
                throw new IllegalStateException("Running application card cannot animate as a live tile");
            Object handles = Reflect.call(overview, "getRemoteTargetHandles");
            if (handles == null || Array.getLength(handles) == 0)
                throw new IllegalStateException("Running application card has no live targets");
            for (int i = 0; i < Array.getLength(handles); i++) {
                Object handle = Array.get(handles, i);
                if (handle == null) throw new IllegalStateException("Running application live target is missing");
                Object params = Reflect.call(handle, "getTransformParams");
                Object targets = params == null ? null : Reflect.call(params, "getTargetSet");
                Object apps = targets == null ? null : Reflect.get(targets, "apps");
                if (apps == null || Array.getLength(apps) == 0)
                    throw new IllegalStateException("Running application live surfaces are missing");
            }
        }

        final Intent bubbleIntent = new Intent(intent);
        String targetPackage = bubbleIntent.getPackage();
        if (targetPackage == null) {
            Object target = Reflect.call(item, "getTargetPackage");
            if (target instanceof String) targetPackage = (String) target;
        }
        if (targetPackage == null && bubbleIntent.getComponent() != null)
            targetPackage = bubbleIntent.getComponent().getPackageName();
        if (targetPackage == null || targetPackage.length() == 0)
            throw new IllegalStateException("Application bubble has no target package");
        bubbleIntent.setPackage(targetPackage);
        final Object profile = Reflect.get(item, "user");
        if (!userClass.isInstance(profile))
            throw new IllegalStateException("Application bubble has no user profile");
        // Cancel only our pending bubble request. Native task animation retains
        // ownership of its surfaces, cancellation callbacks and controller.
        Session previous = active;
        if (previous != null) previous.cancel("superseded by a new bubble request");
        if (active != null) throw new IllegalStateException("Another bubble launch replaced this request");
        final Session session = new Session(context, container, overview, controller, bubbleIntent,
                profile, targetPackage, onHandedOff, onRejected);
        active = session;
        session.probeAndStartCard(task);
        return true;
    }

    /** HOME or a new gesture can abandon the request without cancelling native surfaces. */
    static void cancel() {
        Session session = active;
        if (session != null) session.cancel("navigation superseded the bubble request");
    }

    private static final class Session {
        final Context context;
        final Object container, overview, controller, profile;
        final Intent intent;
        final String requestId = UUID.randomUUID().toString();
        final String packageName;
        final Runnable cleanup, rejected;
        boolean ended, cleanupRan, waitingForRecents, submitted, transitionStarted, probing;
        final Runnable submit = new Runnable() {
            @Override public void run() { submitBubble(); }
        };
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                fail(new IllegalStateException(probing
                        ? "NativeComplements small-window host is unavailable"
                        : "NativeComplements did not acknowledge the launch"));
            }
        };

        Session(Context context, Object container, Object overview, Object controller,
                Intent intent, Object profile, String packageName, Runnable cleanup, Runnable rejected) {
            this.context = context;
            this.container = container;
            this.overview = overview;
            this.controller = controller;
            this.intent = intent;
            this.profile = profile;
            this.packageName = packageName;
            this.cleanup = cleanup;
            this.rejected = rejected;
        }

        boolean current() { return active == this && !ended; }

        void probeAndStartCard(final Object task) {
            probing = true;
            MAIN.postDelayed(timeout, 4000L);
            try {
                boolean sent = NativeWindowBridge.probe(context, requestId, new ResultReceiver(MAIN) {
                    @Override protected void onReceiveResult(int resultCode, android.os.Bundle data) {
                        if (!current() || !probing) return;
                        if (resultCode != 1) {
                            String reason = data == null ? null : data.getString("reason");
                            fail(new IllegalStateException(reason == null
                                    ? "NativeComplements backend unavailable" : reason));
                            return;
                        }
                        probing = false;
                        MAIN.removeCallbacks(timeout);
                        startCard(task);
                    }
                });
                if (!sent) fail(new IllegalStateException("NativeComplements probe could not be sent"));
            } catch (Throwable failure) { fail(failure); }
        }

        void startCard(Object task) {
            if (!current()) return;
            try {
                if (Reflect.get(overview, "mRecentsAnimationController") != controller
                        || (controller != null && Boolean.TRUE.equals(Reflect.get(controller, "mFinishRequested")))) {
                    cancel("Recents changed while checking small-window service");
                    return;
                }
                float scale = 1;
                try {
                    scale = ((Number) android.animation.ValueAnimator.class
                            .getMethod("getDurationScale").invoke(null)).floatValue();
                } catch (Throwable ignored) { }
                if (Float.isNaN(scale) || Float.isInfinite(scale)) scale = 1;
                MAIN.postDelayed(timeout, (long) (8000 * Math.max(1, Math.min(20, scale))));
                if (controller == null) {
                    startHome();
                    return;
                }
                log("small-window handoff animates running card package=" + packageName);
                Object callbacks = invoke(launchTask, task);
                if (callbacks == null)
                    throw new IllegalStateException("Native running application card launch was rejected");
                transitionStarted = true;
                invoke(Reflect.method(callbacks.getClass(), "add", Runnable.class), callbacks, new Runnable() {
                    @Override public void run() { onCardAnimationFinished(); }
                });
            } catch (Throwable failure) { fail(failure); }
        }

        void startHome() throws Throwable {
            final Object manager = Reflect.call(container, "getStateManager");
            log("small-window handoff animates overview to home package=" + packageName);
            transitionStarted = true;
            invoke(goToState, manager, normalState, true, new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                    cancel("native overview-to-home animation cancelled");
                }
                @Override public void onAnimationEnd(Animator animation) {
                    if (!cancelled && current()) MAIN.post(submit);
                }
            });
        }

        void onCardAnimationFinished() {
            if (!current() || waitingForRecents) return;
            try {
                // TaskView's list is also drained on animation cancellation.
                // The cancellation hook invalidates this session before that.
                if (!Boolean.TRUE.equals(Reflect.get(controller, "mFinishRequested"))
                        || Boolean.TRUE.equals(Reflect.call(controller, "getFinishTargetIsLauncher")))
                    throw new IllegalStateException("Native card did not finish Recents to its application");
                Object finishCallbacks = Reflect.get(controller, "mPendingFinishCallbacks");
                if (finishCallbacks == null)
                    throw new IllegalStateException("Native Recents completion callbacks are missing");
                waitingForRecents = true;
                log("bubble running card completed; awaiting native Recents finish package=" + packageName);
                invoke(Reflect.method(finishCallbacks.getClass(), "add", Runnable.class), finishCallbacks,
                        new Runnable() {
                    @Override public void run() {
                        if (!current()) return;
                        // RecentsView's own callback clears its controller after
                        // running the client's callback. Queue after all of it.
                        MAIN.post(submit);
                    }
                });
            } catch (Throwable failure) { fail(failure); }
        }

        void submitBubble() {
            if (!current() || submitted) return;
            try {
                if (controller != null) {
                    if (!Boolean.TRUE.equals(Reflect.get(controller, "mFinishRequested"))
                            || Boolean.TRUE.equals(Reflect.call(controller, "getFinishTargetIsLauncher")))
                        throw new IllegalStateException("Native Recents completion changed its target");
                } else if (Reflect.get(Reflect.call(container, "getStateManager"), "mState") != normalState) {
                    cancel("Launcher left home before the small-window handoff");
                    return;
                }
                // A new gesture must not make the bubble cancel another Recents
                // transition to HOME after this session's completion signal.
                if (Reflect.get(overview, "mRecentsAnimationController") != null) {
                    cancel("Recents restarted before the bubble handoff");
                    return;
                }
                submitted = true;
                MAIN.removeCallbacks(timeout);
                MAIN.postDelayed(timeout, 20000L);
                boolean sent = NativeWindowBridge.open(context, requestId, intent,
                        (Parcelable) profile, new ResultReceiver(MAIN) {
                    @Override protected void onReceiveResult(int resultCode, android.os.Bundle data) {
                        if (!current()) return;
                        if (resultCode != 1) {
                            String reason = data == null ? null : data.getString("reason");
                            fail(new IllegalStateException(reason == null
                                    ? "NativeComplements rejected the launch" : reason));
                            return;
                        }
                        log("NativeComplements accepted dock app package="
                                + packageName + " user=" + profile);
                        finish();
                    }
                });
                if (!sent) fail(new IllegalStateException("NativeComplements launch could not be sent"));
            } catch (Throwable failure) { fail(failure); }
        }

        void cancel(String reason) {
            if (!current()) return;
            if (submitted) {
                log("navigation arrived after NativeComplements accepted dock handoff; awaiting result package="
                        + packageName);
                return;
            }
            log("native application bubble cancelled: " + reason + " package=" + packageName);
            finish();
        }

        void fail(Throwable failure) {
            if (!current()) return;
            log("native application bubble failed: " + failure + " package=" + packageName);
            String message = probing || !transitionStarted
                    ? failure.getMessage() : "小窗启动失败，请查看模块日志";
            Toast.makeText(context, message == null ? "小窗服务暂不可用" : message,
                    Toast.LENGTH_LONG).show();
            finish(!transitionStarted);
        }

        void finish() {
            finish(false);
        }

        void finish(boolean wasRejected) {
            if (!current()) return;
            ended = true;
            active = null;
            MAIN.removeCallbacks(submit);
            MAIN.removeCallbacks(timeout);
            if (cleanupRan) return;
            cleanupRan = true;
            try {
                Runnable completion = wasRejected ? rejected : cleanup;
                if (completion != null) completion.run();
            }
            catch (Throwable failure) { log("application bubble cleanup failed: " + failure); }
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void log(String message) {
        android.util.Log.i("PixelRecentsA10", message);
        XposedBridge.log("PixelRecentsA10: " + message);
    }
}
