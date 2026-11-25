package dev.pixelmotion.a11.motion;

import android.util.FloatProperty;
import android.view.MotionEvent;
import com.android.launcher3.Launcher;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.LauncherAnimUtils;
import com.android.launcher3.QuickstepTransitionManager;
import com.android.launcher3.views.FloatingIconView;
import com.android.launcher3.views.FloatingIconView$$ExternalSyntheticLambda4;
import com.android.launcher3.views.ListenerView;
import com.android.launcher3.statemanager.BaseState;
import com.android.quickstep.util.ScalingWorkspaceRevealAnim;
import de.robv.android.xposed.XposedBridge;

public final class HomeInterruptionTest {
    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError("check " + checks);
        checks++;
    }

    private static ListenerView listener(FloatingIconView icon) {
        ListenerView listener = new ListenerView();
        FloatingIconView$$ExternalSyntheticLambda4 callback = new FloatingIconView$$ExternalSyntheticLambda4();
        callback.c = icon;
        listener.mCloseListener = callback;
        return listener;
    }

    private static Object touch(ListenerView listener, int action) throws Throwable {
        return XposedBridge.fire(listener, "onControllerInterceptTouchEvent",
                new Class<?>[]{MotionEvent.class}, new MotionEvent(action)).getResult();
    }

    public static void main(String[] args) throws Throwable {
        HomeInterruption.install(HomeInterruptionTest.class.getClassLoader());
        QuickstepLauncher launcher = new QuickstepLauncher();
        FloatingIconView icon = new FloatingIconView();
        icon.mLauncher = launcher;
        ListenerView listener = listener(icon);
        check(Boolean.FALSE.equals(touch(listener, MotionEvent.ACTION_DOWN)));
        check(!listener.isOpen() && icon.finishes == 0 && Motion.settled == 1);
        QuickstepTransitionManager launch = new QuickstepTransitionManager();
        launch.mLauncher = new Launcher();
        Class<?>[] options = {android.view.View.class, com.android.launcher3.model.data.ItemInfo.class};
        XposedBridge.fire(launch, "getActivityLaunchOptions", options, icon, null);
        check(icon.finishes == 0);
        launch.mLauncher = launcher;
        XposedBridge.fire(launch, "getActivityLaunchOptions", options, icon, null);
        check(icon.finishes == 1);

        icon = new FloatingIconView();
        icon.mLauncher = launcher;
        icon.mIsOpening = true;
        listener = listener(icon);
        check(touch(listener, 0) == null && listener.isOpen());
        icon.mIsOpening = false;
        launcher.mStateManager.mState = LauncherState.OVERVIEW;
        check(touch(listener, 0) == null);
        launcher.mStateManager.mState = LauncherState.NORMAL;
        check(touch(listener, 2) == null);
        check(Boolean.FALSE.equals(touch(listener, 0)));
        XposedBridge.fire(launcher, "onStateSetStart", new Class<?>[]{BaseState.class}, LauncherState.BACKGROUND_APP);
        check(icon.finishes == 0);
        XposedBridge.fire(launcher, "onStateSetStart", new Class<?>[]{BaseState.class}, LauncherState.OVERVIEW);
        check(icon.finishes == 1);

        RevealScaleCleanup.install(HomeInterruptionTest.class.getClassLoader());
        launcher.mStateManager.mState = LauncherState.NORMAL;
        ScalingWorkspaceRevealAnim first = new ScalingWorkspaceRevealAnim();
        first.launcher = launcher;
        FloatProperty reveal = LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY.get(2);
        FloatProperty state = LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY.get(3);
        FloatProperty dock = LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY.get(2);
        state.setValue(launcher.mWorkspace, .75f);
        reveal.setValue(launcher.mWorkspace, .86f);
        dock.setValue(launcher.mHotseat, .92f);
        XposedBridge.fire(first, "start", new Class<?>[0]);
        first.animation.mAnim.end();
        check((Float) reveal.get(launcher.mWorkspace) == 1f);
        check((Float) dock.get(launcher.mHotseat) == 1f);
        check((Float) state.get(launcher.mWorkspace) == .75f);
        XposedBridge.fire(first, "start", new Class<?>[0]);
        ScalingWorkspaceRevealAnim second = new ScalingWorkspaceRevealAnim();
        second.launcher = launcher;
        XposedBridge.fire(second, "start", new Class<?>[0]);
        reveal.setValue(launcher.mWorkspace, .89f);
        first.animation.mAnim.end();
        check((Float) reveal.get(launcher.mWorkspace) == .89f);
        second.animation.mAnim.end();
        check((Float) reveal.get(launcher.mWorkspace) == 1f);
        check((Float) state.get(launcher.mWorkspace) == .75f);
        XposedBridge.fire(second, "start", new Class<?>[0]);
        launcher.mStateManager.mState = LauncherState.OVERVIEW;
        reveal.setValue(launcher.mWorkspace, .8f);
        second.animation.mAnim.end();
        check((Float) reveal.get(launcher.mWorkspace) == .8f);
        System.out.println("PASS: " + checks + " closing touch, opening scope, state handoff and scale ownership assertions");
    }
}
