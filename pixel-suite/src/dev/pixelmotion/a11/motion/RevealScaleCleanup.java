package dev.pixelmotion.a11.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.util.FloatProperty;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Releases only the reveal scale channel after its animation is cancelled. */
final class RevealScaleCleanup {
    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> reveal = Motion.load("com.android.quickstep.util.ScalingWorkspaceRevealAnim", loader);
        Class<?> animUtils = Motion.load("com.android.launcher3.LauncherAnimUtils", loader);
        Object workspaceFactory = Motion.field(animUtils, "WORKSPACE_SCALE_PROPERTY_FACTORY").get(null);
        Object hotseatFactory = Motion.field(animUtils, "HOTSEAT_SCALE_PROPERTY_FACTORY").get(null);
        Method get = Motion.method(workspaceFactory.getClass(), "get", Integer.class);
        FloatProperty workspaceScale = (FloatProperty) get.invoke(workspaceFactory, 2);
        FloatProperty hotseatScale = (FloatProperty) get.invoke(hotseatFactory, 2);
        Field launcher = Motion.field(reveal, "launcher");
        Field workspace = Motion.field(launcher.getType(), "mWorkspace");
        Field hotseat = Motion.field(launcher.getType(), "mHotseat");
        Field manager = Motion.field(launcher.getType(), "mStateManager");
        Field state = Motion.field(manager.getType(), "mState");
        Object normal = Motion.field(Motion.load("com.android.launcher3.LauncherState", loader), "NORMAL").get(null);
        Field pending = Motion.field(reveal, "animation");
        Field anim = Motion.field(Motion.load("com.android.launcher3.anim.AnimatedPropertySetter", loader), "mAnim");
        WeakHashMap<View, AnimatorSet> owners = new WeakHashMap<>();
        XposedBridge.hookMethod(Motion.method(reveal, "start"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread()) return;
                Object activity = launcher.get(p.thisObject);
                View page = (View) workspace.get(activity);
                View dock = (View) hotseat.get(activity);
                AnimatorSet set = (AnimatorSet) anim.get(pending.get(p.thisObject));
                owners.put(page, set);
                owners.put(dock, set);
                set.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        boolean onDesktop;
                        try { onDesktop = state.get(manager.get(activity)) == normal; }
                        catch (IllegalAccessException error) { return; }
                        if (owners.get(page) == set) {
                            owners.remove(page);
                            if (onDesktop) workspaceScale.setValue(page, 1f);
                        }
                        if (owners.get(dock) == set) {
                            owners.remove(dock);
                            if (onDesktop) hotseatScale.setValue(dock, 1f);
                        }
                    }
                });
            }
        });
    }
}
