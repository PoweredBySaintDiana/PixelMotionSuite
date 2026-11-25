package dev.pixelmotion.a11.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Application;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import dev.pixelsuite.FeatureConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;

public final class Motion implements IXposedHookLoadPackage {
    private static final String PKG = "com.google.android.apps.nexuslauncher";
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final String UTIL = "com.android.quickstep.util.";
    private static final WeakHashMap<Object, Boolean> FIXED_TARGETS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> SYNCED_SPRINGS = new WeakHashMap<>();
    private static final WeakHashMap<Object, AnimatorSet> STAGGERED_ANIMATIONS = new WeakHashMap<>();
    private static final WeakHashMap<Object, WorkspaceMotion> WORKSPACE_MOTIONS = new WeakHashMap<>();
    private static final WeakHashMap<Object, IdentityHashMap<View, Float>> STAGGERED_BASES = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> SMARTSPACE_BASES = new WeakHashMap<>();
    private static final WeakHashMap<Object, Landing> LANDINGS = new WeakHashMap<>();
    private static final HomeFling HOME_FLINGS = new HomeFling();
    private static boolean loggedHomeFling;
    private static HomeSwipeProbe swipeProbe;
    private static final WeakHashMap<View, Shift> SHIFTS = new WeakHashMap<>();
    // A null entry is a configured runner using the existing fallback timing.
    private static final WeakHashMap<Object, OpeningTiming> OPENING_CURVES = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> OPEN_ANIMATORS = new WeakHashMap<>();
    /** Android 17 starts the launch with a 500 ms ValueAnimator; 1.27x is 635 ms. */
    private static final int STOCK_OPEN_MS = 500;
    private static final float OPEN_DURATION_SCALE = 1.27f;
    private static final int OPEN_TOTAL_MS = Math.round(STOCK_OPEN_MS * OPEN_DURATION_SCALE);
    /** Fixed, gesture-independent strength shared by the back sink and the launch rise. */
    private static final float WORKSPACE_SHIFT_VELOCITY = 6f;
    private static boolean loggedAnchor, loggedRetarget, loggedSync, loggedClip,
            loggedOpeningCurve, installed, openDurationStretched, loggedBackSink, loggedShift,
            dockLaunch, loggedRecentsDock;

    @Override public void handleLoadPackage(final LoadPackageParam pkg) throws Throwable {
        if (!PKG.equals(pkg.packageName) || !PKG.equals(pkg.processName) || Build.VERSION.SDK_INT != 37) return;
        if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)
                && !FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true)
                && !FeatureConfig.enabled(FeatureConfig.MOTION_PAGE, true)) return;
        Class<?> app = load("com.android.launcher3.LauncherApplication", pkg.classLoader);
        XposedBridge.hookMethod(method(app, "onCreate"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (installed) return;
                installed = true;
                try {
                    Application application = (Application) p.thisObject;
                    int version = application.getPackageManager().getPackageInfo(PKG, 0).versionCode;
                    if (version != 907) { log("Skipped launcher version " + version + "; expected 907"); return; }
                    install(pkg.classLoader);
                    log("v8 active (907)");
                } catch (Throwable error) { log("Unable to install v8 hooks: " + error); }
            }
        });
    }

    private static void install(ClassLoader loader) throws Throwable {
        boolean iconMotion = FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true);
        boolean workspaceMotion = FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true);
        boolean pageMotion = FeatureConfig.enabled(FeatureConfig.MOTION_PAGE, true);
        if (!iconMotion && !workspaceMotion) {
            if (pageMotion) PageRecoil.install(loader);
            return;
        }
        Class<?> launcher = load(LAUNCHER, loader);
        final Field workspace = field(launcher, "mWorkspace");
        final Field hotseat = field(launcher, "mHotseat");
        if (workspaceMotion) WorkspaceMotion.install();
        if (workspaceMotion) try { installWorkspaceInterruption(loader, launcher); }
        catch (ReflectiveOperationException error) { log("Workspace touch hooks unavailable: " + error); }
        try { RevealScaleCleanup.install(loader); }
        catch (ReflectiveOperationException error) { log("Reveal scale cleanup unavailable: " + error); }
        Class<?> spring = load(UTIL + "RectFSpringAnim", loader);
        Class<?> deviceProfile = load("com.android.launcher3.DeviceProfile", loader);
        final Field listeners = field(spring, "mAnimatorListeners");
        final Field tracking = field(spring, "mTracking");
        final Field startRect = field(spring, "mStartRect");
        final Field targetRect = field(spring, "mTargetRect");
        final Field currentY = field(spring, "mCurrentY");
        final Field currentX = field(spring, "mCurrentCenterX");
        final Field xSpring = field(spring, "mRectXSpring");
        final Class<?> updateListener = load(UTIL + "RectFSpringAnim$OnUpdateListener", loader);
        final Method addUpdate = method(spring, "addOnUpdateListener", updateListener);
        final Method addAnimatorListener = method(spring, "addAnimatorListener", Animator.AnimatorListener.class);
        final Method getTrackedY = method(spring, "getTrackedYFromRect", RectF.class);
        getTrackedY.setAccessible(true);
        Class<?> scaling = load(UTIL + "ScalingWorkspaceRevealAnim", loader);
        final Field pendingAnimation = field(scaling, "animation");
        final Field revealCurve = field(scaling, "SCALE_INTERPOLATOR");
        final Field pendingSet = field(load("com.android.launcher3.anim.AnimatedPropertySetter", loader), "mAnim");
        java.lang.reflect.Constructor<?> scalingConstructor = scaling.getDeclaredConstructor(
                load("com.android.launcher3.uioverrides.QuickstepLauncher", loader), spring, RectF.class, boolean.class, boolean.class);
        Class<?> retarget = load(UTIL + "ScalingWorkspaceRevealAnim$2", loader);
        final Field retargetSpring = field(retarget, "$siblingAnimation");
        Method retargetUpdate = method(retarget, "onAnimationUpdate", android.animation.ValueAnimator.class);
        Class<?> factory = load("com.android.quickstep.LauncherSwipeHandlerV2$LauncherHomeAnimationFactory", loader);
        Class<?> floatingFactory = load("com.android.quickstep.LauncherSwipeHandlerV2$FloatingViewHomeAnimationFactory", loader);
        final Field outerHandler = field(factory, "this$0");
        final Field sibling = field(floatingFactory, "mSiblingAnimation");
        final Field container = field(load("com.android.quickstep.AbsSwipeUpHandler", loader), "mContainer");
        final Class<?> handler = load("com.android.quickstep.LauncherSwipeHandlerV2", loader);
        Method homeAtomic = method(factory, "playAtomicAnimation", float.class);
        Class<?> floatingIcon = load("com.android.launcher3.views.FloatingIconView", loader);
        final Field opening = field(floatingIcon, "mIsOpening");
        final Field original = field(floatingIcon, "mOriginalIcon");
        final Field ySpring = field(spring, "mRectYSpring");
        final Field axisForce = field(ySpring.getType(), "x");
        final Field axisVelocity = field(ySpring.getType().getSuperclass(), "a");
        final Field axisPosition = field(ySpring.getType().getSuperclass(), "b");
        final Field forceFrequency = field(axisForce.getType(), "a");
        final Field forceTarget = field(axisForce.getType(), "i");
        final Method setForceDamping = method(axisForce.getType(), "a", float.class);
        final Method setForceStiffness = method(axisForce.getType(), "b", float.class);
        final Class<?> logic = load("com.android.quickstep.SwipeUpAnimationLogic", loader);
        final Field dragDistance = field(logic, "mCurrentDisplacement");
        final Field dragLength = field(logic, "mTransitionDragLength");
        final WeakHashMap<Object, Float> dragFractions = new WeakHashMap<>();
        final Class<?> iconFactory = load("com.android.quickstep.LauncherSwipeHandlerV2$3", loader);
        Method springStart = method(spring, "start", Context.class, deviceProfile, PointF.class);
        Method springCancel = method(spring, "cancel");
        Method springUpdate = method(spring, "onUpdate");

        if (iconMotion) installHomeFlingTracking(loader, handler);

        XposedBridge.hookMethod(method(iconFactory, "setAnimation", spring), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!onUiThread() || p.hasThrowable() || p.args[0] == null) return;
                Object swipe = outerHandler.get(p.thisObject);
                if (swipe == null) return;
                int length = dragLength.getInt(swipe);
                dragFractions.put(p.args[0], length > 0 ? dragDistance.getFloat(swipe) / length : 0f);
            }
        });
        XposedBridge.hookMethod(springStart, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)) return;
                if (!onUiThread() || p.thisObject.getClass() != spring) return;
                if (swipeProbe != null) swipeProbe.start(p.thisObject, startRect, targetRect);
                Object icon = closingIcon(p.thisObject, floatingIcon, listeners, opening, original);
                if (icon == null) return;
                RectF from = (RectF) startRect.get(p.thisObject);
                RectF to = (RectF) targetRect.get(p.thisObject);
                Float drag = dragFractions.remove(p.thisObject);
                boolean legacyGeometry = MotionCurve.iconGeometry(from.width(), from.height(), to.width(), to.height());
                if (!MotionCurve.hasBounds(from.width(), from.height())
                        || !MotionCurve.hasBounds(to.width(), to.height())
                        || (drag == null && !legacyGeometry)) return;
                // The icon factory certifies this is a real home icon target,
                // even when the window is already small at the end of a long drag.
                if (!legacyGeometry) log("v7.8 compact-window landing: "
                        + from.width() + "x" + from.height() + " -> " + to.width() + "x" + to.height());
                int stock = tracking.getInt(p.thisObject);
                if (stock == 1 || stock == 2) {
                    tracking.setInt(p.thisObject, 0);
                    currentY.setFloat(p.thisObject, from.top);
                    if (!loggedAnchor) { loggedAnchor = true; log("v6 icon anchor: " + stock + " -> 0 (top)"); }
                }
                FIXED_TARGETS.put(p.thisObject, true);

                float density = ((View) icon).getResources().getDisplayMetrics().density;
                PointF velocity = (PointF) p.args[2];
                float speed = velocity == null ? 0f : Math.abs(velocity.y) / density;
                final Object owner = p.thisObject;
                final Landing landing = new Landing(speed, density, drag == null ? 0f : drag);
                addAnimatorListener.invoke(owner, new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (LANDINGS.remove(owner) == landing && landing.axis != null) {
                            log("v7.8 landing: speed=" + landing.speed + "dp/ms, drag=" + landing.drag
                                    + ", travel=" + landing.travel + "dp, damping=" + landing.damping
                                    + ", peak=" + landing.peak + "/" + landing.target + "dp; settle="
                                    + landing.settling);
                        }
                    }
                });
                LANDINGS.put(owner, landing);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)) return;
                if (!onUiThread()) return;
                if (p.hasThrowable()) { clearSpring(p.thisObject); return; }
                if (HOME_FLINGS.take(p.thisObject) && p.args[2] instanceof PointF) {
                    Object horizontal = xSpring.get(p.thisObject);
                    Object vertical = ySpring.get(p.thisObject);
                    PointF release = (PointF) p.args[2];
                    if (horizontal != null && vertical != null) {
                        float[] corrected = HomeFling.redirect(release.x, release.y,
                                axisVelocity.getFloat(horizontal), axisVelocity.getFloat(vertical));
                        if (corrected != null) {
                            axisVelocity.setFloat(horizontal, corrected[0]);
                            axisVelocity.setFloat(vertical, corrected[1]);
                            if (!loggedHomeFling) {
                                loggedHomeFling = true;
                                log("Home fling: release(px/ms)=" + release.x + "," + release.y
                                        + " spring(px/s)=" + corrected[0] + "," + corrected[1]);
                            }
                        }
                    }
                }
                Landing landing = LANDINGS.get(p.thisObject);
                if (landing == null) return;
                // Launcher has seeded the Y spring and queued its first frame, but
                // it has not integrated yet. Use the velocity Launcher actually gave it.
                Object axis = ySpring.get(p.thisObject);
                if (axis == null) return;
                Object force = axisForce.get(axis);
                // Pixel Launcher 17 raised the X/Y spring to 450/400 with nearly
                // critical damping. Android 11 used 200 and 0.8, which gives the
                // characteristic quick travel followed by one soft settling arc.
                setForceStiffness.invoke(force, 200f);
                setForceDamping.invoke(force, .8f);
                Object xAxis = xSpring.get(p.thisObject);
                if (xAxis != null) {
                    Object xForce = axisForce.get(xAxis);
                    if (xForce != null) {
                        setForceStiffness.invoke(xForce, 200f);
                        setForceDamping.invoke(xForce, .8f);
                    }
                }
                double delta = forceTarget.getDouble(force) - axisPosition.getFloat(axis);
                float velocity = axisVelocity.getFloat(axis);
                landing.direction = (float) Math.signum(delta == 0 ? velocity : delta);
                landing.travel = (float) (Math.abs(delta) / landing.density);
                float damping = MotionCurve.landingDamping(landing.travel,
                        velocity * landing.direction / landing.density,
                        forceFrequency.getDouble(force), landing.target);
                if (!Float.isFinite(damping) || landing.direction == 0) return;
                setForceDamping.invoke(force, damping);
                landing.axis = axis;
                landing.force = force;
                landing.damping = damping;
            }
        });
        XposedBridge.hookMethod(springUpdate, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)) return;
                if (!onUiThread()) return;
                if (swipeProbe != null && !p.hasThrowable()) swipeProbe.frame(p.thisObject);
                Landing landing = LANDINGS.get(p.thisObject);
                if (landing == null || landing.axis == null) return;
                double target = forceTarget.getDouble(landing.force);
                float over = (float) ((currentY.getFloat(p.thisObject) - target)
                        * landing.direction / landing.density);
                landing.peak = Math.max(landing.peak, over);
                if (!landing.settling && landing.damping != 1f && over > 0f
                        && axisVelocity.getFloat(landing.axis) * landing.direction <= 0f) {
                    // Keep the first overshoot; damp its return to avoid a second bob.
                    setForceDamping.invoke(landing.force, 1f);
                    landing.settling = true;
                }
            }
        });
        XposedBridge.hookMethod(springCancel, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true) && onUiThread())
                    clearSpring(p.thisObject);
            }
        });
        XposedBridge.hookMethod(homeAtomic, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true)) return;
                if (!onUiThread() || p.hasThrowable()) return;
                if (!floatingFactory.isInstance(p.thisObject)) return;
                Object springObject = sibling.get(p.thisObject);
                Object swipe = outerHandler.get(p.thisObject);
                Object owner = swipe != null && handler.isInstance(swipe) ? container.get(swipe) : null;
                if (springObject != null && owner != null && launcher.isInstance(owner)) {
                    float velocity = p.args.length > 0 && p.args[0] instanceof Number
                            ? ((Number) p.args[0]).floatValue() : 0f;
                    try {
                        shiftWorkspace(owner, velocity, loader, workspace, hotseat, false);
                    } catch (Throwable error) {
                        log("v6 workspace spring skipped: " + error);
                    }
                }
            }
        });
        XposedBridge.hookMethod(scalingConstructor, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)) return;
                if (!onUiThread() || p.hasThrowable() || p.args[1] == null) return;
                Object paired = p.args[1];
                if (!FIXED_TARGETS.containsKey(paired)
                        && closingIcon(paired, floatingIcon, listeners, opening, original) == null) return;
                AnimatorSet set = (AnimatorSet) pendingSet.get(pendingAnimation.get(p.thisObject));
                Object curve = revealCurve.get(null);
                Object page = workspace.get(p.args[0]);
                Object dock = hotseat.get(p.args[0]);
                for (Animator child : set.getChildAnimations()) {
                    if (!(child instanceof ObjectAnimator)) continue;
                    ObjectAnimator anim = (ObjectAnimator) child;
                    if (anim.getInterpolator() == curve && (anim.getTarget() == page || anim.getTarget() == dock)) {
                        // Preserve the known scale correction; the A11 springs have no timer.
                        anim.setDuration(100L);
                        anim.setInterpolator(new DecelerateInterpolator(1.35f));
                    }
                }
            }
        });
        XposedBridge.hookMethod(retargetUpdate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_ICON, true)) return;
                if (!onUiThread()) return;
                Object paired = retargetSpring.get(p.thisObject);
                if (paired != null && FIXED_TARGETS.containsKey(paired)) {
                    p.setResult(null);
                    if (!loggedRetarget) { loggedRetarget = true; log("v6 disabled reveal-scale retargeting for a home icon"); }
                }
            }
        });
        // Only remember the launch origin for the workspace sink. Leave the
        // returned ActivityOptions and the app's native splash style untouched.
        if (workspaceMotion) try {
            Class<?> transition = load("com.android.launcher3.QuickstepTransitionManager", loader);
            Class<?> itemInfo = load("com.android.launcher3.model.data.ItemInfo", loader);
            final Field transitionLauncher = field(transition, "mLauncher");
            XposedBridge.hookMethod(method(transition, "getActivityLaunchOptions", View.class, itemInfo),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (!onUiThread() || p.hasThrowable()) return;
                            try {
                                Object owner = transitionLauncher.get(p.thisObject);
                                View clicked = (View) p.args[0];
                                View dock = (View) hotseat.get(owner);
                                dockLaunch = dock instanceof ViewGroup
                                        && isInside(clicked, (ViewGroup) dock);
                            } catch (Throwable ignored) {
                                dockLaunch = false;
                            }
                        }
                    });
        } catch (Throwable error) { log("Dock launch tracking unavailable: " + error); }
        if (iconMotion) {
            try { HomeInterruption.install(loader); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log("Home interruption hooks unavailable: " + error);
            }
            try { IconLayers.install(loader, floatingIcon, spring); }
            catch (Throwable error) { log("Icon layer hooks unavailable: " + error); }
            try { installOpeningCurve(loader, launcher, workspace, hotseat, deviceProfile); }
            catch (Throwable error) { log("A11 opening curve hook unavailable: " + error); }
        }
        if (workspaceMotion) try { installBackHomeSink(loader, launcher, workspace, hotseat, deviceProfile); }
        catch (Throwable error) { log("A11 back-home sink unavailable: " + error); }
        if (workspaceMotion) try {
            XposedBridge.hookMethod(method(launcher, "onResume"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!onUiThread()) return;
                    try { settleShift(p.thisObject, loader, workspace, hotseat); }
                    catch (Throwable error) { log("A11 settle shift skipped: " + error); }
                }
            });
        } catch (Throwable error) { log("A11 settle hook unavailable: " + error); }
        if (workspaceMotion) try {
            XposedBridge.hookMethod(method(launcher, "onDestroy"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    WorkspaceMotion motion = WORKSPACE_MOTIONS.remove(p.thisObject);
                    if (motion != null) motion.clear();
                }
            });
        } catch (ReflectiveOperationException error) { log("Workspace teardown unavailable: " + error); }
        if (pageMotion) try { PageRecoil.install(loader); }
        catch (Throwable error) { log("Workspace page recoil unavailable: " + error); }
    }

    /**
     * The side-edge back gesture closes an app through
     * LauncherBackAnimationController instead of the swipe-up home factory, so
     * the A11 StaggeredWorkspaceAnim sink never started. Reuse the same stagger
     * with a fixed, gesture-independent strength when the back transition
     * commits; the swipe-up path keeps its velocity-sensitive sink.
     */
    private static void installBackHomeSink(ClassLoader loader, Class<?> launcher,
            final Field workspace, final Field hotseat, final Class<?> deviceProfile) throws Throwable {
        Class<?> back = load("com.android.quickstep.LauncherBackAnimationController", loader);
        final Field backLauncher = field(back, "mLauncher");
        XposedBridge.hookMethod(method(back, "startTransition"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true)) return;
                if (!onUiThread() || p.hasThrowable()) return;
                Object owner = backLauncher.get(p.thisObject);
                if (!launcher.isInstance(owner)) return;
                log("v8 back sink trigger");
                try {
                    shiftWorkspace(owner, WORKSPACE_SHIFT_VELOCITY, loader, workspace, hotseat, false);
                } catch (Throwable error) {
                    log("v8 back-home sink skipped: " + error);
                }
            }
        });
    }

    /**
     * Android 17 marks the hotseat invisible in Overview (and BackgroundApp),
     * which is why it only appeared when forced at the end of the transition.
     * Adding the hotseat bit makes the state animation itself fade/translate
     * it in continuously, so the dock is revealed with the swipe.
     */
    private static void installRecentsDock(ClassLoader loader, Class<?> launcher,
            final Field hotseatField) throws Throwable {
        Class<?> uiState = load("com.android.launcher3.LauncherUiState", loader);
        Class<?> quickstep = load("com.android.launcher3.uioverrides.QuickstepLauncher", loader);
        Class<?> baseState = load("com.android.launcher3.statemanager.BaseState", loader);
        XC_MethodHook showHotseat = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String name = p.thisObject.getClass().getName();
                if (!name.endsWith("OverviewState") && !name.endsWith("BackgroundAppState")) return;
                Object result = p.getResult();
                if (result instanceof Integer) p.setResult(((Integer) result) | 0x1);
            }
        };
        for (String className : new String[]{
                "com.android.launcher3.uioverrides.states.OverviewState",
                "com.android.launcher3.uioverrides.states.BackgroundAppState"}) {
            Class<?> type = load(className, loader);
            XposedBridge.hookMethod(method(type, "getVisibleElements", uiState), showHotseat);
        }
        XC_MethodHook front = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!onUiThread()) return;
                Object state = p.args[0];
                if (state == null) return;
                String name = state.getClass().getName();
                if (!name.endsWith("OverviewState") && !name.endsWith("BackgroundAppState")) return;
                Object dock = hotseatField.get(p.thisObject);
                if (!(dock instanceof View)) return;
                View view = (View) dock;
                view.setVisibility(View.VISIBLE);
                // Draw above the RecentsView so it stays visible while the app
                // window lifts; the state still animates its alpha/transform.
                view.bringToFront();
                if (!loggedRecentsDock) {
                    loggedRecentsDock = true;
                    log("v8.2 recents dock visible in state");
                }
            }
        };
        XposedBridge.hookMethod(method(quickstep, "onStateSetStart", baseState), front);
        XposedBridge.hookMethod(method(quickstep, "onStateSetEnd", baseState), front);
    }

    /** Drives the launcher's icon-to-window properties on a single animation clock. */
    private static void installOpeningCurve(ClassLoader loader, Class<?> launcher,
            final Field workspace, final Field hotseat, final Class<?> deviceProfile) throws Throwable {
        Class<?> runner = load("com.android.launcher3.QuickstepTransitionManager$7", loader);
        Class<?> prop = load("com.android.quickstep.util.MultiValueUpdateListener$FloatProp", loader);
        final Field dx = field(runner, "mDx");
        final Field dy = field(runner, "mDy");
        final Field iconScale = field(runner, "mIconScaleToFitScreen");
        final Field cropCenterX = field(runner, "mCropRectCenterX");
        final Field cropCenterY = field(runner, "mCropRectCenterY");
        final Field cropWidth = field(runner, "mCropRectWidth");
        final Field cropHeight = field(runner, "mCropRectHeight");
        final Field windowRadius = field(runner, "mWindowRadius");
        final Field iconAlpha = field(runner, "mIconAlpha");
        final Field cropToInset = field(runner, "val$cropToInset");
        final Field interpolator = field(prop, "interpolator");
        final Method update = method(runner, "onUpdate", float.class, boolean.class);
        final Class<?> transition = load("com.android.launcher3.QuickstepTransitionManager", loader);
        final Field transitionOwner = field(runner, "this$0");
        final Field transitionLauncher = field(transition, "mLauncher");
        final Field profile = field(transition, "mDeviceProfile");
        final Field grid = field(deviceProfile, "mWorkspaceProfile");
        final Field cellHeight = field(grid.getType(), "cellHeightPx");
        final OpeningIconSource iconSource = OpeningIconSource.load(loader);
        final Class<?> openingRunner = runner;
        // The constructor selects the profile before the runner is attached
        // to its animator. Keep the existing duration for the other launches.
        try {
            XposedBridge.hookMethod(ValueAnimator.class.getDeclaredMethod("addUpdateListener",
                    ValueAnimator.AnimatorUpdateListener.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length != 1 || !openingRunner.isInstance(p.args[0])
                            || !(p.thisObject instanceof ValueAnimator)) return;
                    ValueAnimator animator = (ValueAnimator) p.thisObject;
                    if (OPEN_ANIMATORS.containsKey(animator)) return;
                    long duration = animator.getDuration();
                    if (duration <= 0) return;
                    OpeningTiming timing = OPENING_CURVES.get(p.args[0]);
                    OPEN_ANIMATORS.put(animator, timing != null);
                    long scaled = timing == null ? Math.max(1L, Math.round(duration * OPEN_DURATION_SCALE))
                            : OpeningTiming.TOTAL_MS;
                    animator.setDuration(scaled);
                    log("app open " + (timing == null ? "default" : "A10 " + timing)
                            + ": " + duration + " -> " + scaled + " ms");
                }
            });
            openDurationStretched = true;
        } catch (Throwable error) {
            log("v8 app open duration hook unavailable: " + error);
        }
        try {
            Class<?> result = load("com.android.launcher3.LauncherAnimationRunner$AnimationResult", loader);
            XposedBridge.hookMethod(method(result, "setAnimation", AnimatorSet.class, Context.class,
                    Runnable.class, boolean.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !onUiThread() || (Boolean) p.args[3]) return;
                    long skipped = OpeningStart.advance((AnimatorSet) p.args[0], OPEN_ANIMATORS);
                    if (skipped >= 0) log("A10 icon start: wrapper delay " + skipped + " ms, icon time 16 ms");
                }
            });
        } catch (ReflectiveOperationException error) {
            log("A10 icon start unavailable: " + error);
        }
        final XC_MethodHook curveHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!onUiThread()) return;
                Object target = p.thisObject;
                OpeningTiming timing = OPENING_CURVES.get(target);
                if (timing != null) {
                    // Launcher 907 may replace these four FloatProps while
                    // recomputing the bottom navigation inset in onUpdate.
                    if (cropToInset.getBoolean(target) && interpolator.get(dy.get(target)) != timing.y) {
                        setInterpolator(dy.get(target), interpolator, timing.y);
                        setInterpolator(iconScale.get(target), interpolator, OpeningTiming.SCALE);
                        setInterpolator(cropCenterY.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
                        setInterpolator(cropHeight.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
                    }
                } else if (!OPENING_CURVES.containsKey(target)) {
                    applyOpeningCurve(target, dx, dy, iconScale, cropCenterX, cropCenterY, cropWidth,
                            cropHeight, windowRadius, iconAlpha, interpolator, null);
                    OPENING_CURVES.put(target, null);
                }
            }
        };
        XposedBridge.hookMethod(update, curveHook);
        for (Constructor<?> constructor : runner.getDeclaredConstructors()) {
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.hasThrowable() || OPENING_CURVES.containsKey(p.thisObject)) return;
                    Object manager = transitionOwner.get(p.thisObject);
                    OpeningTiming timing = null;
                    // Runner 907 receives the original icon view and bounds for this launch.
                    Rect target = (Rect) p.args[9];
                    RectF source = (RectF) p.args[10];
                    if (openDurationStretched && onUiThread() && iconSource != null
                            && iconSource.matches((View) p.args[11], target, source)) {
                        int[] offset = (int[]) p.args[12];
                        Object currentProfile = profile.get(manager);
                        Object workspaceGrid = currentProfile == null ? null : grid.get(currentProfile);
                        if (workspaceGrid != null) {
                            timing = OpeningTiming.select(source.top, source.centerY(),
                                    target.centerY() - offset[1], cellHeight.getInt(workspaceGrid));
                        }
                    }
                    applyOpeningCurve(p.thisObject, dx, dy, iconScale, cropCenterX,
                            cropCenterY, cropWidth, cropHeight, windowRadius, iconAlpha, interpolator, timing);
                    OPENING_CURVES.put(p.thisObject, timing);
                    IconLayers.setOpeningFade((View) p.args[14], timing != null && !(Boolean) p.args[13]);
                    // Sign-flipped version of the back-to-home sink: the grid
                    // starts below its resting place and rises as the app opens.
                    Object owner = manager == null ? null : transitionLauncher.get(manager);
                    if (FeatureConfig.enabled(FeatureConfig.MOTION_WORKSPACE, true)
                            && onUiThread() && launcher.isInstance(owner)) {
                        log("v8 launch rise trigger");
                        try {
                            shiftWorkspace(owner, WORKSPACE_SHIFT_VELOCITY, loader, workspace, hotseat, true);
                        } catch (Throwable error) {
                            log("v8 launch rise skipped: " + error);
                        }
                    }
                }
            });
        }
    }

    private static void applyOpeningCurve(Object target, Field dx, Field dy,
            Field iconScale, Field cropCenterX, Field cropCenterY, Field cropWidth,
            Field cropHeight, Field windowRadius, Field iconAlpha, Field interpolator,
            OpeningTiming timing) throws IllegalAccessException {
        if (timing != null) {
            setInterpolator(dx.get(target), interpolator, timing.x);
            setInterpolator(dy.get(target), interpolator, timing.y);
            setInterpolator(iconScale.get(target), interpolator, OpeningTiming.SCALE);
            setInterpolator(cropCenterX.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
            setInterpolator(cropCenterY.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
            setInterpolator(cropWidth.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
            setInterpolator(cropHeight.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
            setInterpolator(windowRadius.get(target), interpolator, OpeningTiming.CROP_AND_RADIUS);
            setInterpolator(iconAlpha.get(target), interpolator, timing.fade);
            return;
        }
        // The Android 11 launch runner used a 500 ms animator, but each
        // property had an independent local clock. This build stretches the
        // whole opening to 1.27x that duration (635 ms) and scales every
        // local clock by the same factor, so the A11 shape is unchanged.
        int total = openDurationStretched ? OPEN_TOTAL_MS : STOCK_OPEN_MS;
        float factor = openDurationStretched ? OPEN_DURATION_SCALE : 1f;
        setInterpolator(dx.get(target), interpolator,
                MotionCurve.a11Aggressive(Math.round(360 * factor), total));
        setInterpolator(dy.get(target), interpolator,
                MotionCurve.a11Aggressive(Math.round(200 * factor), total));
        setInterpolator(cropCenterX.get(target), interpolator,
                MotionCurve.a11Exaggerated(Math.round(375 * factor), total));
        setInterpolator(cropCenterY.get(target), interpolator,
                MotionCurve.a11Exaggerated(Math.round(375 * factor), total));
        setInterpolator(iconScale.get(target), interpolator,
                MotionCurve.a11Exaggerated(Math.round(450 * factor), total));
        setInterpolator(cropWidth.get(target), interpolator,
                MotionCurve.a11Exaggerated(Math.round(375 * factor), total));
        setInterpolator(cropHeight.get(target), interpolator,
                MotionCurve.a11Exaggerated(Math.round(375 * factor), total));
        setInterpolator(windowRadius.get(target), interpolator, MotionCurve.a11Exaggerated());
        if (!loggedOpeningCurve) {
            loggedOpeningCurve = true;
            log("v8 Android 11 opening curve: total=" + total
                    + "ms, dx=" + Math.round(360 * factor) + ", dy=" + Math.round(200 * factor)
                    + ", scale=" + Math.round(450 * factor) + ", crop=" + Math.round(375 * factor)
                    + (openDurationStretched ? " (1.27x, aggressive lead)" : " (stock 500 ms)"));
        }
    }

    private static void setInterpolator(Object prop, Field field, Interpolator value)
            throws IllegalAccessException {
        if (prop != null) field.set(prop, value);
    }

    private static Object closingIcon(Object spring, Class<?> floatingIcon, Field listeners, Field opening, Field original) throws IllegalAccessException {
        for (Object entry : (List<?>) listeners.get(spring)) {
            if (floatingIcon.isInstance(entry) && !opening.getBoolean(entry) && original.get(entry) != null) return entry;
        }
        return null;
    }

    private static final class Landing {
        final float speed, density, drag, target;
        float direction, travel, damping, peak;
        boolean settling;
        Object axis, force;
        Landing(float speed, float density, float drag) {
            this.speed = speed; this.density = density; this.drag = drag;
            this.target = MotionCurve.landingTarget(speed, drag);
        }
    }

    private static void syncToSpring(final Object spring, final Object owner, final Field workspace, final Field hotseat,
            Class<?> updateListener, Method addUpdate, Method addAnimatorListener, Field startRect,
            Field targetRect, Field currentX, Field currentY, Method getTrackedY) throws Throwable {
        if (SYNCED_SPRINGS.containsKey(spring)) return;
        final View page = (View) workspace.get(owner);
        final View dock = (View) hotseat.get(owner);
        if (page == null || dock == null || page.getWindowToken() == null) return;
        final Shift[] shifts = {new Shift(page), new Shift(dock)};
        final float density = page.getResources().getDisplayMetrics().density;
        final RectF from = new RectF((RectF) startRect.get(spring));
        final RectF to = (RectF) targetRect.get(spring);
        final float startY = ((Number) getTrackedY.invoke(spring, from)).floatValue();
        final float finalY = ((Number) getTrackedY.invoke(spring, to)).floatValue();
        final float startX = from.centerX();
        final float finalX = to.centerX();
        final WeakReference<Object> springRef = new WeakReference<>(spring);
        final MotionCurve.Dip dip = new MotionCurve.Dip();
        for (Shift shift : shifts) shift.begin();
        SYNCED_SPRINGS.put(spring, true);
        shifts[0].write(dip.update(0f) * density);
        shifts[1].write(dip.update(0f) * density);
        InvocationHandler updates = (proxy, method, args) -> {
            if ("onUpdate".equals(method.getName()) && args != null && args.length == 2) {
                Object running = springRef.get();
                if (running == null) { clearShifts(shifts); return null; }
                float scale = ((Number) args[1]).floatValue();
                float progress = MotionCurve.positionProgress(startX, startY, finalX, finalY,
                        currentX.getFloat(running), currentY.getFloat(running), scale, density);
                float offset = dip.update(progress) * density;
                shifts[0].write(offset); shifts[1].write(offset);
            } else if ("onCancel".equals(method.getName())) clearShifts(shifts);
            else if ("equals".equals(method.getName())) return proxy == args[0];
            else if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            else if ("toString".equals(method.getName())) return "PixelMotionA11SpringSync";
            return null;
        };
        Object callback = Proxy.newProxyInstance(updateListener.getClassLoader(), new Class<?>[]{updateListener}, updates);
        addUpdate.invoke(spring, callback);
        addAnimatorListener.invoke(spring, new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { clearSpring(spring); clearShifts(shifts); }
            @Override public void onAnimationCancel(Animator animation) { clearSpring(spring); clearShifts(shifts); }
        });
        if (!loggedSync) { loggedSync = true; log("v6 attached workspace dip to RectFSpringAnim progress"); }
    }

    private static void clearSpring(Object spring) { FIXED_TARGETS.remove(spring); SYNCED_SPRINGS.remove(spring); LANDINGS.remove(spring); }
    private static void clearShifts(Shift[] shifts) { for (Shift shift : shifts) shift.clear(); }

    /**
     * Shifts the current page, dock and search bar as whole blocks. Android 17
     * rebinds the individual icon views during transitions, which dropped the
     * per-icon stagger and left only the dock (a single view) moving. Applying
     * the same A11 spring to the containers moves every icon reliably.
     * Opening an app lifts the blocks and leaves them raised; returning home
     * (back gesture, swipe up or home) drops them back to rest.
     */
    private static void shiftWorkspace(final Object owner, float velocity, ClassLoader loader,
            Field workspaceField, Field hotseatField, boolean rising) throws Throwable {
        ViewGroup workspace = (ViewGroup) workspaceField.get(owner);
        ViewGroup hotseat = (ViewGroup) hotseatField.get(owner);
        if (workspace == null || hotseat == null || workspace.getWindowToken() == null) return;
        Class<?> cellClass = load("com.android.launcher3.CellLayout", loader);
        Field itemsField = field(cellClass, "mShortcutsAndWidgets");
        Method getCurrentPage = workspace.getClass().getMethod("getCurrentPage");
        int current = ((Number) getCurrentPage.invoke(workspace)).intValue();
        View page = current >= 0 && current < workspace.getChildCount()
                ? workspace.getChildAt(current) : null;
        final List<View> targets = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        ViewGroup pageItems = null;
        if (page != null) {
            Object items = itemsField.get(page);
            targets.add(items instanceof View ? (View) items : page);
            delays.add(0L);
            if (items instanceof ViewGroup) pageItems = (ViewGroup) items;
        }
        // Move the Hotseat view itself so its drawing bounds travel with the
        // dock icons; moving only its inner container clips them at the dock.
        final int hotseatIndex = targets.size();
        targets.add(hotseat);
        delays.add(15L);
        try {
            Class<?> ids = load("com.android.launcher3.R$id", loader);
            int id = field(ids, "search_container_workspace").getInt(null);
            Method findViewById = method(load("android.app.Activity", loader), "findViewById", int.class);
            View qsb = (View) findViewById.invoke(owner, id);
            if (qsb != null && !isInside(qsb, hotseat)) {
                targets.add(qsb);
                delays.add(15L);
            }
        } catch (Throwable ignored) {
            // Some launcher builds keep the search bar inside the workspace.
        }
        float density = workspace.getResources().getDisplayMetrics().density;
        float factor = .2f + .9f * Math.abs(velocity) / 22f;
        float offset = 60f * density * factor;
        WorkspaceMotion motion = WORKSPACE_MOTIONS.get(owner);
        if (motion == null) {
            motion = new WorkspaceMotion(loader);
            WORKSPACE_MOTIONS.put(owner, motion);
        }
        final ClipGuard clipGuard = new ClipGuard();
        clipGuard.add(workspace);
        if (page instanceof ViewGroup) clipGuard.add((ViewGroup) page);
        clipGuard.add(hotseat);
        // The dock sits near the screen edge; open every ancestor that could
        // clip its overshoot while it travels with the sink/rise.
        addParentClipChain(clipGuard, workspace, 5);
        addParentClipChain(clipGuard, hotseat, 5);
        final float dockDistance = dockLaunch ? offset * 1.45f : offset;
        List<Float> distances = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            distances.add(i == hotseatIndex ? dockDistance : offset);
        }
        // Row gradient: the dock uses the base distance and each page row adds
        // a little more, so the top row travels farthest (A11's 1.00-1.24 ramp).
        if (pageItems != null) {
            Field cellY = field(load("com.android.launcher3.celllayout.CellLayoutLayoutParams", loader), "mCellY");
            Field cellSpan = field(load("com.android.launcher3.celllayout.CellLayoutLayoutParams", loader), "cellVSpan");
            int rows = field(cellClass, "mCountY").getInt(page);
            for (int i = 0; i < pageItems.getChildCount(); i++) {
                final View child = pageItems.getChildAt(i);
                int row = 1;
                try {
                    row = cellY.getInt(child.getLayoutParams()) + cellSpan.getInt(child.getLayoutParams());
                } catch (Throwable ignored) {
                    // Keep the base distance for non-cell children.
                }
                float height = Math.max(0f, Math.min(1f, (rows + 1 - row) / (float) Math.max(1, rows)));
                final float extra = offset * .35f * height;
                if (extra < 1f) continue;
                targets.add(child);
                distances.add(extra);
                delays.add(0L);
            }
        }
        motion.animate((Context) owner, targets, distances, delays, rising,
                clipGuard::disable, clipGuard::restore);
        if (!loggedShift) {
            loggedShift = true;
        }
        log("v8.1 shift " + (rising ? "rise" : "sink")
                + " blocks=" + targets.size() + " offset=" + Math.round(offset)
                + " dock=" + Math.round(dockDistance) + (dockLaunch ? " (dock launch)" : ""));
    }

    /** Restores a raised desktop when a return path did not run the sink. */
    private static void settleShift(Object owner, ClassLoader loader,
            Field workspaceField, Field hotseatField) throws Throwable {
        WorkspaceMotion motion = WORKSPACE_MOTIONS.get(owner);
        if (motion == null || !motion.raised() || (motion.active() && !motion.rising())) return;
        motion.settle((Context) owner);
    }

    static void continueWorkspace(Object owner) {
        WorkspaceMotion motion = WORKSPACE_MOTIONS.get(owner);
        if (motion == null) return;
        try { motion.settle((Context) owner); }
        catch (ReflectiveOperationException error) {
            motion.clear();
            log("Workspace interruption settle unavailable: " + error);
        }
    }

    private static void installWorkspaceInterruption(ClassLoader loader, Class<?> launcher)
            throws ReflectiveOperationException {
        Class<?> layer = load("com.android.launcher3.views.BaseDragLayer", loader);
        Field owner = field(layer, "mContainer");
        Field manager = field(launcher, "mStateManager");
        Field current = field(manager.getType(), "mState");
        Class<?> state = load("com.android.launcher3.LauncherState", loader);
        Object normal = field(state, "NORMAL").get(null);
        Object background = field(state, "BACKGROUND_APP").get(null);
        XC_MethodHook touch = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                android.view.MotionEvent event = (android.view.MotionEvent) p.args[0];
                if (!onUiThread() || event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return;
                Object activity = owner.get(p.thisObject);
                if (launcher.isInstance(activity) && current.get(manager.get(activity)) == normal)
                    continueWorkspace(activity);
            }
        };
        XposedBridge.hookMethod(method(layer, "dispatchTouchEvent", android.view.MotionEvent.class), touch);
        XposedBridge.hookMethod(method(layer, "proxyTouchEvent", android.view.MotionEvent.class, boolean.class), touch);
        XposedBridge.hookMethod(method(launcher, "onStateSetStart",
                load("com.android.launcher3.statemanager.BaseState", loader)), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (onUiThread() && p.args[0] != background) continueWorkspace(p.thisObject);
            }
        });
    }

    /**
     * Recreates Android 11's StaggeredWorkspaceAnim. Android 17 removed that
     * animation from the launcher home factory, leaving only the scale reveal.
     * Preserve the bottom-up start order and use slightly longer, faster springs
     * higher in the grid. The dock keeps its original spring and moves as one row.
     */
    private static void staggeredWorkspace(final Object owner, float velocity, ClassLoader loader,
            Field workspaceField, Field hotseatField, Class<?> deviceProfileClass,
            boolean rising) throws Throwable {
        ViewGroup workspace = (ViewGroup) workspaceField.get(owner);
        ViewGroup hotseat = (ViewGroup) hotseatField.get(owner);
        if (workspace == null || hotseat == null || workspace.getWindowToken() == null) return;

        AnimatorSet previous = STAGGERED_ANIMATIONS.get(owner);
        IdentityHashMap<View, Float> previousBases = STAGGERED_BASES.get(owner);
        if (previousBases != null) {
            for (java.util.Map.Entry<View, Float> entry : previousBases.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    entry.getKey().setTranslationY(entry.getValue());
                }
            }
        }
        if (previous != null && previous.isRunning()) previous.cancel();

        Class<?> cellLayoutClass = load("com.android.launcher3.CellLayout", loader);
        // Android 11 used CellLayout$LayoutParams; Android 17 moved it to
        // celllayout.CellLayoutLayoutParams and renamed cellY to mCellY.
        Class<?> cellParamsClass = load("com.android.launcher3.celllayout.CellLayoutLayoutParams", loader);
        Field itemsField = field(cellLayoutClass, "mShortcutsAndWidgets");
        Field cellYField = field(cellParamsClass, "mCellY");
        Field cellSpanField = field(cellParamsClass, "cellVSpan");
        Method getCurrentPage = workspace.getClass().getMethod("getCurrentPage");
        ViewGroup page = (ViewGroup) workspace.getChildAt(((Number) getCurrentPage.invoke(workspace)).intValue());
        if (page == null) return;
        ViewGroup items = (ViewGroup) itemsField.get(page);


        Method getDeviceProfile = method(load("com.android.launcher3.BaseActivity", loader), "getDeviceProfile");
        Object deviceProfile = getDeviceProfile.invoke(owner);
        Field invField = field(deviceProfileClass, "inv");
        Object invariant = invField.get(deviceProfile);
        Field numRowsField = field(load("com.android.launcher3.InvariantDeviceProfile", loader), "numRows");
        int numRows = numRowsField.getInt(invariant);
        Method verticalBar = method(deviceProfileClass, "isVerticalBarLayout");
        boolean isVertical = (Boolean) verticalBar.invoke(deviceProfile);
        int totalRows = numRows + (isVertical ? 0 : 2);

        Class<?> builderClass = load("com.android.launcher3.anim.SpringAnimationBuilder", loader);
        Constructor<?> builderConstructor = builderClass.getDeclaredConstructor(Context.class);
        Method setStiffness = method(builderClass, "setStiffness", float.class);
        Method setDamping = method(builderClass, "setDampingRatio", float.class);
        Method setMinimum = method(builderClass, "setMinimumVisibleChange", float.class);
        Class<?> floatPropertyClass = load("android.util.FloatProperty", loader);
        Method build = method(builderClass, "build", Object.class, floatPropertyClass);
        Field startValue = field(builderClass, "mStartValue");
        Field endValue = field(builderClass, "mEndValue");
        Field springVelocity = field(builderClass, "mVelocity");
        Field translateY = field(load("com.android.launcher3.LauncherAnimUtils", loader), "VIEW_TRANSLATE_Y");
        Object translateProperty = translateY.get(null);
        Context context = (Context) owner;

        float factor = .2f + .9f * Math.abs(velocity) / 22f;
        // The sink starts above the resting place and settles down; the launch
        // rise is the sign-flipped twin: start below and spring up into place.
        float direction = rising ? 1f : -1f;
        float startOffset = direction * 60f * context.getResources().getDisplayMetrics().density * factor;
        List<StaggeredView> views = new ArrayList<>();
        IdentityHashMap<View, Boolean> viewSet = new IdentityHashMap<>();
        for (int i = items.getChildCount() - 1; i >= 0; i--) {
            View child = items.getChildAt(i);
            Object lp = child.getLayoutParams();
            int row = cellYField.getInt(lp) + cellSpanField.getInt(lp);
            views.add(new StaggeredView(child, row, totalRows, numRows));
            viewSet.put(child, true);
            if (isSmartspaceView(child)) stabilizeSmartspace(child);
        }

        ViewGroup hotseatItems = (ViewGroup) itemsField.get(hotseat);
        if (hotseatItems != null) {
            if (!isVertical && hotseatItems.getChildCount() > 0) {
                // The horizontal row already uses one offset and one delay.
                // Apply that motion to the dock itself so its drawing bounds
                // travel with its contents, even if a renderer clips the layer.
                views.add(new StaggeredView(hotseat, numRows + 1, totalRows, numRows));
            } else {
                for (int i = hotseatItems.getChildCount() - 1; i >= 0; i--) {
                    View child = hotseatItems.getChildAt(i);
                    int row = numRows;
                    if (child.getLayoutParams() != null) {
                        row = cellYField.getInt(child.getLayoutParams()) + 1;
                    }
                    views.add(new StaggeredView(child, row, totalRows, numRows));
                }
            }
        }

        if (!isVertical) {
            try {
                Class<?> ids = load("com.android.launcher3.R$id", loader);
                int id = field(ids, "search_container_workspace").getInt(null);
                Method findViewById = method(load("android.app.Activity", loader), "findViewById", int.class);
                View qsb = (View) findViewById.invoke(owner, id);
                if (qsb != null && !isInside(qsb, hotseat)) {
                    views.add(new StaggeredView(qsb, numRows + 1, totalRows, numRows));
                    viewSet.put(qsb, true);
                }
            } catch (Throwable ignored) {
                // Some launcher builds do not expose a workspace search view.
            }
        }

        // Smartspace is a launcher-owned view on some Pixel builds rather than a
        // CellLayout child. Include the outer Smartspace view once so its stable
        // layout translation participates in the same baseline-safe animation.
        collectSmartspaceViews(workspace.getRootView(), views, viewSet, totalRows);

        if (views.isEmpty()) return;
        final ClipGuard clipGuard = new ClipGuard();
        clipGuard.add(workspace); clipGuard.add(page); clipGuard.add(hotseat); clipGuard.add(hotseatItems);
        addParentClipChain(clipGuard, workspace, 5);
        addParentClipChain(clipGuard, hotseat, 5);
        clipGuard.disable();
        if (!loggedClip) {
            loggedClip = true;
            log("v7.4 dock clip groups=" + clipGuard.size()
                    + ", dock moves as one row=" + (!isVertical && hotseatItems != null
                            && hotseatItems.getChildCount() > 0));
        }

        final AnimatorSet animators = new AnimatorSet();
        final IdentityHashMap<View, Float> bases = new IdentityHashMap<>();
        for (final StaggeredView entry : views) {
            final View view = entry.view;
            Float knownBase = previousBases == null ? null : previousBases.get(view);
            if (isSmartspaceView(view)) {
                Float smartBase = SMARTSPACE_BASES.get(view);
                if (smartBase == null) {
                    smartBase = view.getTranslationY();
                    SMARTSPACE_BASES.put(view, smartBase);
                }
                view.setTranslationY(smartBase);
                knownBase = smartBase;
            }
            final float base = knownBase == null ? view.getTranslationY() : knownBase;
            bases.put(view, base);
            final float start = base + startOffset * entry.distanceScale;
            view.setTranslationY(start);
            Object builder = builderConstructor.newInstance(context);
            startValue.setFloat(builder, start);
            endValue.setFloat(builder, base);
            springVelocity.setFloat(builder, -direction * velocity * entry.distanceScale * entry.speedScale);
            setStiffness.invoke(builder, 150f * entry.speedScale * entry.speedScale);
            setDamping.invoke(builder, .7f);
            setMinimum.invoke(builder, 1f);
            Animator animator = (Animator) build.invoke(builder, view, translateProperty);
            animator.setStartDelay(entry.delayMs);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { view.setTranslationY(base); }
            });
            animators.play(animator);
        }
        animators.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                clipGuard.restore();
                if (STAGGERED_ANIMATIONS.get(owner) == animators) STAGGERED_ANIMATIONS.remove(owner);
                if (STAGGERED_BASES.get(owner) == bases) STAGGERED_BASES.remove(owner);
            }
        });
        STAGGERED_BASES.put(owner, bases);
        STAGGERED_ANIMATIONS.put(owner, animators);
        animators.start();
        if (!loggedSync) {
            loggedSync = true;
            log("v7.9 workspace row gradient: " + numRows + " rows, bottom to top "
                    + "distance x1.00鈥?.24, speed x1.00鈥?.15");
        }
    }

    private static final class StaggeredView {
        final View view; final long delayMs;
        final float distanceScale, speedScale;
        StaggeredView(View view, int row, int totalRows, int gridRows) {
            this.view = view;
            this.delayMs = (long) (totalRows - row + 1) * 10L;
            float height = MotionCurve.workspaceRow(totalRows - (totalRows > gridRows ? 1 : 0) - row, gridRows);
            distanceScale = MotionCurve.rowDistanceScale(height);
            speedScale = MotionCurve.rowSpeedScale(height);
        }
    }

    private static void collectSmartspaceViews(View view, List<StaggeredView> views,
            IdentityHashMap<View, Boolean> seen, int totalRows) {
        if (view == null || seen.containsKey(view)) return;
        if (isSmartspaceView(view)) {
            seen.put(view, true);
            stabilizeSmartspace(view);
            views.add(new StaggeredView(view, totalRows + 1, totalRows, Math.max(1, totalRows - 2)));
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectSmartspaceViews(group.getChildAt(i), views, seen, totalRows);
        }
    }

    private static boolean isSmartspaceView(View view) {
        if (view == null) return false;
        String name = view.getClass().getName().toLowerCase(java.util.Locale.US);
        CharSequence description = view.getContentDescription();
        String desc = description == null ? "" : description.toString().toLowerCase(java.util.Locale.US);
        return name.contains("smartspace") || desc.contains("at a glance");
    }

    private static void stabilizeSmartspace(View view) {
        Float base = SMARTSPACE_BASES.get(view);
        if (base == null) {
            base = view.getTranslationY();
            SMARTSPACE_BASES.put(view, base);
        }
        // A Smartspace implementation can retain the previous reveal
        // translation when the launcher cancels an animation. Restore the
        // layout baseline before assigning the next run's start value.
        view.setTranslationY(base);
    }

    private static void addParentClipChain(ClipGuard guard, View view, int maxDepth) {
        ViewParent parent = view == null ? null : view.getParent();
        for (int depth = 0; depth < maxDepth && parent instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) parent;
            guard.add(group);
            parent = group.getParent();
        }
    }

    private static boolean isInside(View view, ViewGroup ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    /** Temporarily opens every parent which can clip a dock icon's overshoot. */
    private static final class ClipGuard {
        private final List<GroupClip> groups = new ArrayList<>();
        private final IdentityHashMap<ViewGroup, Boolean> seen = new IdentityHashMap<>();

        void add(ViewGroup group) {
            if (group != null && !seen.containsKey(group)) {
                seen.put(group, true);
                groups.add(new GroupClip(group));
            }
        }

        void disable() {
            for (GroupClip state : groups) {
                try {
                    state.clipChildren = (Boolean) ViewGroup.class.getMethod("getClipChildren").invoke(state.group);
                    state.clipToPadding = (Boolean) ViewGroup.class.getMethod("getClipToPadding").invoke(state.group);
                    state.clipBounds = (android.graphics.Rect) View.class.getMethod("getClipBounds").invoke(state.group);
                    ViewGroup.class.getMethod("setClipChildren", boolean.class).invoke(state.group, false);
                    ViewGroup.class.getMethod("setClipToPadding", boolean.class).invoke(state.group, false);
                    if (state.clipBounds != null) View.class.getMethod("setClipBounds", android.graphics.Rect.class).invoke(state.group, new Object[]{null});
                } catch (Throwable error) { log("Unable to open clip chain: " + error); }
            }
        }

        void restore() {
            for (int i = groups.size() - 1; i >= 0; i--) groups.get(i).restore();
        }

        int size() { return groups.size(); }
    }

    private static final class GroupClip {
        final ViewGroup group;
        boolean clipChildren, clipToPadding;
        android.graphics.Rect clipBounds;
        GroupClip(ViewGroup group) { this.group = group; }
        void restore() {
            try {
                ViewGroup.class.getMethod("setClipChildren", boolean.class).invoke(group, clipChildren);
                ViewGroup.class.getMethod("setClipToPadding", boolean.class).invoke(group, clipToPadding);
                View.class.getMethod("setClipBounds", android.graphics.Rect.class).invoke(group, clipBounds);
            } catch (Throwable error) { log("Unable to restore clip chain: " + error); }
        }
    }

    private static final class Shift {
        final View view; float lastValue; float lastOffset; boolean active;
        Shift(View view) { this.view = view; }
        void begin() { Shift previous = SHIFTS.get(view); if (previous != null) previous.clear(); lastValue = view.getTranslationY(); lastOffset = 0f; active = true; SHIFTS.put(view, this); }
        void write(float nextOffset) { if (!active) return; if (view.getWindowToken() == null) { clear(); return; } float current = view.getTranslationY(); float base = Math.abs(current - lastValue) < .025f ? current - lastOffset : current; lastOffset = nextOffset; lastValue = base + nextOffset; view.setTranslationY(lastValue); }
        void clear() { if (!active) return; active = false; if (Math.abs(view.getTranslationY() - lastValue) < .025f) view.setTranslationY(lastValue - lastOffset); if (SHIFTS.get(view) == this) SHIFTS.remove(view); }
    }

    private static void installHomeFlingTracking(ClassLoader loader, Class<?> launcherHandler) {
        try {
            Class<?> handler = load("com.android.quickstep.AbsSwipeUpHandler", loader);
            Method decision = method(handler, "calculateEndTarget", PointF.class, float.class,
                    boolean.class, boolean.class, boolean.class);
            Method create = method(handler, "createWindowAnimationToHome", float.class,
                    load("com.android.quickstep.SwipeUpAnimationLogic$HomeAnimationFactory", loader));
            Field gesture = field(load("com.android.quickstep.SwipeUpAnimationLogic", loader), "mGestureState");
            Field device = field(handler, "mDeviceState");
            Method home = method(handler, "getHomeTarget");
            Method atomic = method(gesture.getType(), "isHandlingAtomicEvent");
            Method display = method(gesture.getType(), "getDisplayId");
            Method trackpad = method(gesture.getType(), "isTrackpadGesture");
            Method fullyGestural = method(device.getType(), "isFullyGesturalNavMode");
            Method homeDisabled = method(device.getType(), "isHomeDisabled");
            Field context = field(gesture.getDeclaringClass(), "mContext");
            XposedBridge.hookMethod(decision, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (!onUiThread() || !launcherHandler.isInstance(p.thisObject)) return;
                    Object target = p.hasThrowable() ? null : p.getResult();
                    boolean fling = (Boolean) p.args[2], cancelled = (Boolean) p.args[3];
                    PointF velocity = (PointF) p.args[0];
                    if (velocity != null && HomeFling.diagonalSwitch(target, fling, cancelled,
                            velocity.x, velocity.y, (Float) p.args[1])) {
                        Object state = gesture.get(p.thisObject), nav = device.get(p.thisObject);
                        Context host = (Context) context.get(p.thisObject);
                        if (state != null && nav != null && host != null &&
                                host.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                                && (Integer) display.invoke(state) == 0 && !(Boolean) atomic.invoke(state)
                                && !(Boolean) trackpad.invoke(state) && (Boolean) fullyGestural.invoke(nav)
                                && !(Boolean) homeDisabled.invoke(nav)) {
                            Object allowed = home.invoke(p.thisObject);
                            if (allowed instanceof Enum && "HOME".equals(((Enum<?>) allowed).name())) {
                                target = allowed;
                                p.setResult(target);
                                android.util.Log.i("PixelFling239", "diagonal NEW_TASK -> HOME vx=" + velocity.x + " vy=" + velocity.y);
                            }
                        }
                    }
                    HOME_FLINGS.decide(p.thisObject, target, fling, cancelled);
                    if (swipeProbe != null) swipeProbe.decision(p.thisObject, target, velocity, fling && !cancelled);
                }
            });
            XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!onUiThread() || !launcherHandler.isInstance(p.thisObject)) return;
                    Object[] created = p.hasThrowable() ? null : (Object[]) p.getResult();
                    HOME_FLINGS.bind(p.thisObject, created);
                    if (swipeProbe != null) swipeProbe.bind(p.thisObject, created);
                }
            });
            swipeProbe = HomeSwipeProbe.install(loader, launcherHandler);
            android.util.Log.i("PixelFling239", "15 degree quick-switch rule active; left trace=" + (swipeProbe != null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Home fling direction unavailable: " + error);
        }
    }

    static boolean onUiThread() { return Looper.myLooper() == Looper.getMainLooper(); }
    static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException { return type.getDeclaredMethod(name, args); }
    static Field field(Class<?> type, String name) throws NoSuchFieldException { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    static void log(String message) {
        android.util.Log.i("PixelMotionA11", message);
        XposedBridge.log("PixelMotionA11: " + message);
    }
}
