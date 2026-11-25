package dev.pixelmotion.a11.motion;

import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Lets desktop touches proceed while a closing icon finishes its native spring. */
final class HomeInterruption {
    private static final WeakHashMap<View, Boolean> CONTINUING = new WeakHashMap<>();

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> floating = Motion.load("com.android.launcher3.views.FloatingIconView", loader);
        Class<?> listener = Motion.load("com.android.launcher3.views.ListenerView", loader);
        Class<?> callback = Motion.load("com.android.launcher3.views.FloatingIconView$$ExternalSyntheticLambda4", loader);
        Class<?> launcher = Motion.load("com.android.launcher3.Launcher", loader);
        Class<?> state = Motion.load("com.android.launcher3.LauncherState", loader);
        Class<?> transition = Motion.load("com.android.launcher3.QuickstepTransitionManager", loader);
        Class<?> baseState = Motion.load("com.android.launcher3.statemanager.BaseState", loader);
        Object normal = Motion.field(state, "NORMAL").get(null);
        Object background = Motion.field(state, "BACKGROUND_APP").get(null);
        Field close = Motion.field(listener, "mCloseListener");
        Field isOpen = Motion.field(listener, "mIsOpen");
        Field icon = Motion.field(callback, "c");
        Field opening = Motion.field(floating, "mIsOpening");
        Field end = Motion.field(floating, "mEndRunnable");
        Field owner = Motion.field(floating, "mLauncher");
        Field stateManager = Motion.field(launcher, "mStateManager");
        Field managerState = Motion.field(stateManager.getType(), "mState");
        Field transitionOwner = Motion.field(transition, "mLauncher");
        Method finish = Motion.method(floating, "fastFinish");
        XposedBridge.hookMethod(Motion.method(listener, "onControllerInterceptTouchEvent", MotionEvent.class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread() || ((MotionEvent) p.args[0]).getActionMasked() != MotionEvent.ACTION_DOWN) return;
                Object action = close.get(p.thisObject);
                if (!callback.isInstance(action)) return;
                View view = (View) icon.get(action);
                if (view == null || view.getWindowToken() == null || opening.getBoolean(view) || end.get(view) == null) return;
                Object launcher = owner.get(view);
                if (managerState.get(stateManager.get(launcher)) != normal) return;
                CONTINUING.put(view, Boolean.TRUE);
                isOpen.setBoolean(p.thisObject, false);
                Motion.continueWorkspace(launcher);
                p.setResult(false);
            }
        });
        XposedBridge.hookMethod(Motion.method(transition, "getActivityLaunchOptions", View.class,
                Motion.load("com.android.launcher3.model.data.ItemInfo", loader)), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (Motion.onUiThread()) finishContinuing(finish, owner, transitionOwner.get(p.thisObject));
            }
        });
        XposedBridge.hookMethod(Motion.method(launcher, "onStateSetStart", baseState), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread() || p.args[0] == normal || p.args[0] == background) return;
                finishContinuing(finish, owner, p.thisObject);
            }
        });
        XposedBridge.hookMethod(Motion.method(floating, "recycle"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { CONTINUING.remove(p.thisObject); }
        });
    }

    private static void finishContinuing(Method finish, Field owner, Object launcher) throws ReflectiveOperationException {
        for (View view : new ArrayList<>(CONTINUING.keySet())) {
            if (view == null || owner.get(view) != launcher) continue;
            CONTINUING.remove(view);
            finish.invoke(view);
        }
    }
}
