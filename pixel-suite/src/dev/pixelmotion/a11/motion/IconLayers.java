package dev.pixelmotion.a11.motion;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** The adaptive foreground rides the spring independently of the app-to-background fade. */
final class IconLayers {
    private static final String DRAW = "pixelmotion.icon.layers";
    private static final WeakHashMap<View, Boolean> openingFades = new WeakHashMap<>();

    static void setOpeningFade(View icon, boolean enabled) {
        if (enabled) openingFades.put(icon, true);
        else openingFades.remove(icon);
    }

    static void install(ClassLoader loader, Class<?> floating, Class<?> spring) throws Throwable {
        Class<?> clip = Motion.load("com.android.launcher3.views.ClipIconView", loader);
        Class<?> wrapper = Motion.load("com.android.launcher3.graphics.PathWrapper", loader);
        Class<?> factory = Motion.load("com.android.quickstep.LauncherSwipeHandlerV2$3", loader);
        Field factoryIcon = Motion.field(factory, "val$floatingIconView");
        Field clipView = Motion.field(floating, "mClipIconView");
        Field opening = Motion.field(floating, "mIsOpening");
        Field original = Motion.field(floating, "mOriginalIcon");
        Field foreground = Motion.field(clip, "mForeground");
        Field background = Motion.field(clip, "mBackground");
        Field bounds = Motion.field(clip, "mFinalDrawableBounds");
        Field clipPath = Motion.field(clip, "mClipPath");
        Field path = Motion.field(wrapper, "path");
        Field adaptive = Motion.field(clip, "mIsAdaptiveIcon");
        Field folder = Motion.field(clip, "mIsFolderIcon");
        Field artist = Motion.field(clip, "mTaskViewArtist");
        Field targetBounds = Motion.field(floating, "mPositionOut");
        Field listeners = Motion.field(spring, "mAnimatorListeners");
        Method laidOut = Motion.method(View.class, "isLaidOut");
        WeakHashMap<Object, Frame> gestures = new WeakHashMap<>();

        // App launch from the home screen uses QuickstepTransitionManager's
        // AppLaunchAnimationRunner rather than RectFSpringAnim.start(). Capture
        // the returned FloatingIconView directly so the opening path gets the
        // same foreground/background layering as the closing path.
        try {
            Class<?> helper = Motion.load("com.android.quickstep.util.FloatingIconViewHelper", loader);
            for (Method method : helper.getDeclaredMethods()) {
                if (!"getFloatingIconView".equals(method.getName())
                        || !floating.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object icon = p.getResult();
                        if (icon == null || clipView.get(icon) == null) return;
                        Frame frame = new Frame();
                        frame.opening = opening.getBoolean(icon)
                                || (p.args.length > 0 && p.args[p.args.length - 1] instanceof Boolean
                                && (Boolean) p.args[p.args.length - 1]);
                        gestures.put(clipView.get(icon), frame);
                    }
                });
            }
        } catch (Throwable error) {
            Motion.log("v8 opening icon hook unavailable: " + error);
        }

        Class<?> deviceProfile = Motion.load("com.android.launcher3.DeviceProfile", loader);
        Method springStart = Motion.method(spring, "start", Context.class, deviceProfile,
                android.graphics.PointF.class);
        XposedBridge.hookMethod(springStart, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread()) return;
                for (Object entry : (java.util.List<?>) listeners.get(p.thisObject)) {
                    if (!floating.isInstance(entry) || clipView.get(entry) == null) continue;
                    Frame frame = new Frame();
                    frame.opening = opening.getBoolean(entry);
                    gestures.put(clipView.get(entry), frame);
                    break;
                }
            }
        });

        XposedBridge.hookMethod(Motion.method(factory, "setAnimation", spring), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread() || p.hasThrowable()) return;
                Object icon = factoryIcon.get(p.thisObject);
                if (icon != null && original.get(icon) != null) {
                    Frame frame = new Frame();
                    frame.opening = opening.getBoolean(icon);
                    gestures.put(clipView.get(icon), frame);
                }
            }
        });
        XposedBridge.hookMethod(Motion.method(floating, "recycle"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (Motion.onUiThread()) {
                    openingFades.remove(p.thisObject);
                    Frame frame = gestures.remove(clipView.get(p.thisObject));
                    if (frame != null) frame.endGeneration++;
                }
            }
        });
        Method animationEnd = Motion.method(floating, "onAnimationEnd", Animator.class);
        XposedBridge.hookMethod(animationEnd, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread()) return;
                Object child = clipView.get(p.thisObject);
                Frame frame = gestures.get(child);
                if (frame == null || frame.opening || frame.fastFinish || !frame.tailStarted
                        || frame.tailSettled || ((View) p.thisObject).getWindowToken() == null) return;
                View icon = (View) p.thisObject;
                Motion.log("v8.3 hold foreground until landing recoil settles: offset="
                        + frame.tailX + "," + frame.tailY);
                long generation = ++frame.endGeneration;
                long deadline = System.nanoTime() + (long) (520_000_000L * frame.tailDurationScale);
                if (icon.postDelayed(new FinishAfterLanding(icon, child, frame, gestures,
                        animationEnd, (Animator) p.args[0], generation, deadline), 16L)) {
                    p.setResult(null);
                }
            }
        });
        XposedBridge.hookMethod(Motion.method(floating, "fastFinish"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Frame frame = gestures.get(clipView.get(p.thisObject));
                if (frame != null) {
                    frame.fastFinish = true;
                    frame.endGeneration++;
                }
            }
        });
        XposedBridge.hookMethod(Motion.method(floating, "update", float.class, RectF.class,
                float.class, float.class, float.class, boolean.class, int.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread()) return;
                View icon = (View) p.thisObject;
                Object child = clipView.get(icon);
                Frame frame = gestures.get(child);
                boolean openingUpdate = (Boolean) p.args[5];
                if (frame == null && (openingUpdate || opening.getBoolean(icon)) && child != null) {
                    // AppLaunchAnimationRunner can begin updating before its
                    // helper callback is observed. Create the opening frame at
                    // the first update so the window reveal is still delayed.
                    frame = new Frame();
                    frame.opening = true;
                    gestures.put(child, frame);
                }
                if (frame == null) return;
                frame.opening |= openingUpdate || opening.getBoolean(icon);
                if (frame.opening && dev.pixelsuite.splash.HomeSplash.nativeFrame(icon)) {
                    frame.ready = false;
                    return;
                }
                frame.ready = (Boolean) laidOut.invoke(icon) && icon.getVisibility() == View.VISIBLE
                        && adaptive.getBoolean(child) && !folder.getBoolean(child)
                        && foreground.get(child) != null && background.get(child) != null;
                if (!frame.ready) return;
                frame.progress = (Float) p.args[2];
                if (!frame.opening) {
                    if (!frame.tailStarted) frame.throwSpring.onUpdate((RectF) p.args[1], icon);
                    RectF target = (RectF) targetBounds.get(icon);
                    frame.current.set((RectF) p.args[1]);
                    if (target != null) frame.target.set(target);
                    frame.owner = new WeakReference<>(icon);
                    if (!frame.clockReady) {
                        frame.clockReady = true;
                        frame.tailDurationScale = animatorDurationScale();
                    }
                }
                float coverage = frame.opening
                        ? MotionCurve.openingBackgroundCoverage(frame.progress)
                        : MotionCurve.backgroundCoverage(frame.progress);
                frame.coverage = Math.round(255f * coverage);
                // On a 701 content launch, let the runner fade the whole
                // floating view together with its separately drawn icon layers.
                if (!frame.opening || !openingFades.containsKey(icon)) {
                    p.args[0] = 1f;
                    p.args[3] = .85f;
                }
            }
        });
        XposedBridge.hookMethod(Motion.method(clip, "draw", Canvas.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!Motion.onUiThread()) return;
                Frame frame = gestures.get(p.thisObject);
                if (frame == null || !frame.ready) return;
                frame.foreground = (Drawable) foreground.get(p.thisObject);
                frame.background = (Drawable) background.get(p.thisObject);
                if (frame.foreground == null || frame.background == null) return;
                if (!frame.opening) {
                    if (advanceForeground(frame, (Rect) bounds.get(p.thisObject))) {
                        ((View) p.thisObject).postInvalidateOnAnimation();
                    }
                }
                p.setObjectExtra(DRAW, frame);
                // Let Launcher draw the task content once, then composite both icon layers over it.
                foreground.set(p.thisObject, null);
                background.set(p.thisObject, null);
            }

            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Frame frame = (Frame) p.getObjectExtra(DRAW);
                if (frame == null) return;
                Drawable fg = frame.foreground;
                Drawable bg = frame.background;
                foreground.set(p.thisObject, fg);
                background.set(p.thisObject, bg);
                frame.foreground = frame.background = null;
                if (p.hasThrowable()) return;

                Canvas canvas = (Canvas) p.args[0];
                int save = canvas.save();
                try {
                    Object currentClip = clipPath.get(p.thisObject);
                    if (currentClip != null) canvas.clipPath((Path) path.get(currentClip));
                    drawBackground(canvas, bg, frame.coverage);
                    // A11's closing foreground stays in the square at the window's origin.
                    // A17 centers it in the shrinking rectangle, shortening its visible path.
                    Rect base = (Rect) bounds.get(p.thisObject);
                    Rect centered = fg.getBounds();
                    canvas.translate(base.left - centered.left, base.top - centered.top);
                    if (!frame.opening) {
                        canvas.translate(frame.tailX, frame.tailY);
                    }
                    fg.draw(canvas);
                    if (!frame.logged) {
                        frame.logged = true;
                        Motion.log("v8 first adaptive foreground: opening=" + frame.opening
                                + ", progress=" + frame.progress
                                + ", background=" + frame.coverage + "/255, thumbnail="
                                + (artist.get(p.thisObject) != null));
                    }
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        });
    }

    private static float animatorDurationScale() {
        try {
            return (Float) ValueAnimator.class.getDeclaredMethod("getDurationScale").invoke(null);
        } catch (Throwable error) {
            Motion.log("v8.3 unable to read animation duration scale: " + error);
            return 1f;
        }
    }

    private static boolean advanceForeground(Frame frame, Rect base) {
        View owner = frame.owner == null ? null : frame.owner.get();
        if (owner == null || frame.target.isEmpty()) {
            boolean active = frame.throwSpring.advance();
            frame.tailX = frame.throwSpring.x();
            frame.tailY = frame.throwSpring.y();
            return active;
        }
        long now = System.nanoTime();
        float durationScale = frame.tailDurationScale;
        float dt = frame.lastDrawNanos == 0L || durationScale <= 0f ? 0f
                : (now - frame.lastDrawNanos) / 1_000_000_000f / durationScale;
        frame.lastDrawNanos = now;
        float scaleX = Math.max(.001f, owner.getScaleX());
        float scaleY = Math.max(.001f, owner.getScaleY());
        // Track the center of the foreground's fixed square in screen pixels.
        // Changing window scale contributes to its true velocity as well.
        float bodyX = frame.current.left + scaleX * base.exactCenterX();
        float bodyY = frame.current.top + scaleY * base.exactCenterY();
        float finalX = frame.target.left + base.exactCenterX();
        float finalY = frame.target.top + base.exactCenterY();
        if (!frame.tailStarted) {
            boolean active = frame.throwSpring.advance();
            frame.tailX = frame.throwSpring.x();
            frame.tailY = frame.throwSpring.y();
            float foregroundX = bodyX + scaleX * frame.tailX;
            float foregroundY = bodyY + scaleY * frame.tailY;
            float dx = frame.target.left - frame.current.left;
            float dy = frame.target.top - frame.current.top;
            float distance = (float) Math.hypot(dx, dy);
            boolean validSample = frame.sampleReady && dt >= .0005f && dt <= .1f;
            boolean approaching = frame.sampleReady && distance < frame.lastDistance - .1f;
            boolean iconSize = frame.current.width() <= frame.target.width() * 1.15f
                    && frame.current.height() <= frame.target.height() * 1.15f;
            float threshold = Math.min(frame.target.width(), frame.target.height()) * .22f;
            if (validSample && approaching && iconSize && distance <= threshold) {
                // Handoff the actual screen position AND velocity. There is no
                // injected speed, copied pulse, or second additive foreground.
                float vx = (foregroundX - frame.sampleX) / dt;
                float vy = (foregroundY - frame.sampleY) / dt;
                float speed = (float) Math.hypot(vx, vy);
                if (speed > .001f) {
                    frame.landingDirectionX = vx / speed;
                    frame.landingDirectionY = vy / speed;
                    frame.landingSpring.start(foregroundX - finalX, foregroundY - finalY, vx, vy);
                    frame.tailStartNanos = now;
                    frame.tailStarted = true;
                    Motion.log("v8.3 foreground momentum handoff: remaining=" + distance
                            + "px, position=" + (foregroundX - finalX) + "," + (foregroundY - finalY)
                            + ", velocity=" + vx + "," + vy + ", a10Offset=" + frame.tailX
                            + "," + frame.tailY + ", durationScale=" + durationScale);
                }
            }
            frame.sampleX = foregroundX;
            frame.sampleY = foregroundY;
            frame.lastDistance = distance;
            frame.sampleReady = true;
            return active || frame.tailStarted;
        }

        boolean active = frame.landingSpring.advance(Math.max(0f, dt));
        // Only the handed-off particle is drawn. Subtract the background's
        // current position, then undo its scale to get a local canvas offset.
        frame.tailX = (finalX + frame.landingSpring.x() - bodyX) / scaleX;
        frame.tailY = (finalY + frame.landingSpring.y() - bodyY) / scaleY;
        float projectedVelocity = frame.landingSpring.velocityX() * frame.landingDirectionX
                + frame.landingSpring.velocityY() * frame.landingDirectionY;
        if (!frame.tailPeaked && projectedVelocity <= 0f) {
            frame.tailPeaked = true;
            frame.landingSpring.settle();
            Motion.log("v8.3 landing foreground momentum peak: age="
                    + (now - frame.tailStartNanos) / 1_000_000L + "ms, absolute="
                    + frame.landingSpring.x() + "," + frame.landingSpring.y()
                    + ", relative=" + frame.tailX + "," + frame.tailY);
        }
        if (!frame.tailSettled && frame.landingSpring.isSettled()) {
            frame.tailSettled = true;
            Motion.log("v8.3 foreground momentum settled: age="
                    + (now - frame.tailStartNanos) / 1_000_000L + "ms");
        }
        return active;
    }

    private static final class FinishAfterLanding implements Runnable {
        private final WeakReference<View> icon;
        private final Object child;
        private final Frame frame;
        private final WeakHashMap<Object, Frame> gestures;
        private final Method animationEnd;
        private final Animator animator;
        private final long generation;
        private final long deadline;

        FinishAfterLanding(View icon, Object child, Frame frame,
                WeakHashMap<Object, Frame> gestures, Method animationEnd, Animator animator,
                long generation, long deadline) {
            this.icon = new WeakReference<>(icon);
            this.child = child;
            this.frame = frame;
            this.gestures = gestures;
            this.animationEnd = animationEnd;
            this.animator = animator;
            this.generation = generation;
            this.deadline = deadline;
        }

        @Override public void run() {
            View view = icon.get();
            if (view == null || gestures.get(child) != frame
                    || frame.endGeneration != generation) return;
            if (!frame.tailSettled && System.nanoTime() < deadline) {
                if (view.postDelayed(this, 16L)) return;
            }
            try {
                XposedBridge.invokeOriginalMethod(animationEnd, view, new Object[] { animator });
            } catch (Throwable error) {
                Motion.log("v8 landing foreground cleanup failed: " + error);
            }
        }
    }

    private static void drawBackground(Canvas canvas, Drawable background, int coverage) {
        if (coverage == 0) return;
        if (coverage == 255) {
            background.draw(canvas);
            return;
        }
        Rect bounds = background.getBounds();
        // Layer alpha preserves the drawable's own alpha and avoids mutating a shared asset.
        int layer = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                coverage, Canvas.ALL_SAVE_FLAG);
        try {
            background.draw(canvas);
        } finally {
            canvas.restoreToCount(layer);
        }
    }

    private static final class Frame {
        boolean ready, logged, opening;
        boolean sampleReady, tailStarted, tailSettled, fastFinish, clockReady, tailPeaked;
        int endGeneration;
        int coverage;
        float progress;
        float lastDistance, tailX, tailY, sampleX, sampleY;
        float landingDirectionX, landingDirectionY;
        float tailDurationScale = 1f;
        long lastDrawNanos, tailStartNanos;
        WeakReference<View> owner;
        final RectF current = new RectF();
        final RectF target = new RectF();
        final A10ForegroundSpring throwSpring = new A10ForegroundSpring();
        final LandingForegroundSpring landingSpring = new LandingForegroundSpring();
        Drawable foreground, background;
    }
}
