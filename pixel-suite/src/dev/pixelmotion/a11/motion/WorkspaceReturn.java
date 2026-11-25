package dev.pixelmotion.a11.motion;

import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Leaves the launch offset raised until a committed return or a desktop touch. */
final class WorkspaceReturn {
    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> launcher = Motion.load("com.android.launcher3.Launcher", loader);
        Class<?> layer = Motion.load("com.android.launcher3.views.BaseDragLayer", loader);
        Class<?> state = Motion.load("com.android.launcher3.LauncherState", loader);
        Field owner = Motion.field(layer, "mContainer");
        Field manager = Motion.field(launcher, "mStateManager");
        Field current = Motion.field(manager.getType(), "mState");
        Method dragLayer = Motion.method(launcher, "getDragLayer");
        Object normal = Motion.field(state, "NORMAL").get(null);
        Object background = Motion.field(state, "BACKGROUND_APP").get(null);
        XC_MethodHook touch = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                MotionEvent event = (MotionEvent) p.args[0];
                if (!Motion.onUiThread() || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                Object activity = owner.get(p.thisObject);
                // Launcher may receive proxy input while another app still owns focus.
                if (launcher.isInstance(activity) && ((View) p.thisObject).hasWindowFocus()
                        && current.get(manager.get(activity)) == normal)
                    Motion.continueWorkspace(activity);
            }
        };
        XposedBridge.hookMethod(Motion.method(layer, "dispatchTouchEvent", MotionEvent.class), touch);
        XposedBridge.hookMethod(Motion.method(layer, "proxyTouchEvent", MotionEvent.class, boolean.class), touch);
        XposedBridge.hookMethod(Motion.method(launcher, "onStateSetStart",
                Motion.load("com.android.launcher3.statemanager.BaseState", loader)), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                // NORMAL is prepared at the start of a home gesture, before release.
                // Its committed home factory must start the sink, not this notification.
                if (!Motion.onUiThread() || p.args[0] == normal || p.args[0] == background) return;
                View view = (View) dragLayer.invoke(p.thisObject);
                if (view != null && view.hasWindowFocus()) Motion.continueWorkspace(p.thisObject);
            }
        });
        XposedBridge.hookMethod(Motion.method(launcher, "onWindowFocusChanged", boolean.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread() || !(Boolean) p.args[0]) return;
                View view = (View) dragLayer.invoke(p.thisObject);
                if (view != null && view.hasWindowFocus() && current.get(manager.get(p.thisObject)) != background)
                    Motion.continueWorkspace(p.thisObject);
            }
        });
        Motion.log("Workspace return waits for committed home animation");
    }
}
