package dev.pixelmotion.a11.motion;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.OverScroller;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Extends Workspace's own page snap, preserving its destination and touch interruption. */
final class PageRecoil {
    private static final String EXTRA = "pixelmotion.page";
    /** Reduce the previous page-turn overshoot by a further 15%: .9 * .85. */
    private static final float RECOIL_SCALE = .765f;

    static void install(ClassLoader loader) throws Throwable {
        Class<?> paged = Motion.load("com.android.launcher3.PagedView", loader);
        Class<?> workspace = Motion.load("com.android.launcher3.Workspace", loader);
        Field scrollerField = Motion.field(paged, "mScroller");
        Field dragging = Motion.field(paged, "mIsBeingDragged");
        Field velocityTracker = Motion.field(paged, "mVelocityTracker");
        Field pointerId = Motion.field(paged, "mActivePointerId");
        Field current = Motion.field(paged, "mCurrentPage");
        Field firstLayout = Motion.field(paged, "mFirstLayout");
        Field minScroll = Motion.field(paged, "mMinScroll");
        Field maxScroll = Motion.field(paged, "mMaxScroll");
        Field switching = Motion.field(workspace, "mIsSwitchingState");
        Method modal = Motion.method(workspace, "workspaceInModalState");
        Method validate = Motion.method(paged, "validateNewPage", int.class);
        Method pageX = Motion.method(paged, "getScrollForPage", int.class);
        Method orientation = Motion.method(paged, "getPagedOrientationHandler");
        Method primaryScroll = Motion.method(Motion.load("com.android.launcher3.touch.PagedOrientationHandler",
                loader), "getPrimaryScroll", android.view.View.class);
        Interpolator stock = (Interpolator) Motion.field(
                Motion.load("com.android.app.animation.Interpolators", loader), "SCROLL").get(null);

        XposedBridge.hookMethod(Motion.method(paged, "onTouchEvent", MotionEvent.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!workspace.isInstance(p.thisObject) || !Motion.onUiThread()) return;
                if (((MotionEvent) p.args[0]).getActionMasked() != MotionEvent.ACTION_UP
                        || !dragging.getBoolean(p.thisObject)) return;
                PageScroller scroller = getScroller(scrollerField, p.thisObject);
                if (scroller != null) {
                    scroller.releasing = true;
                    p.setObjectExtra(EXTRA, scroller);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                PageScroller scroller = (PageScroller) p.getObjectExtra(EXTRA);
                if (scroller != null) scroller.releasing = false;
            }
        });
        XposedBridge.hookMethod(Motion.method(paged, "snapToPage", int.class, int.class, int.class,
                boolean.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!workspace.isInstance(p.thisObject) || !Motion.onUiThread()) return;
                ViewGroup view = (ViewGroup) p.thisObject;
                PageScroller scroller = getScroller(scrollerField, view);
                if (scroller == null || !scroller.releasing || (Boolean) p.args[3]
                        || firstLayout.getBoolean(view) || switching.getBoolean(view)
                        || (Boolean) modal.invoke(view) || view.getChildCount() < 2) return;

                int source = current.getInt(view);
                int target = (Integer) validate.invoke(view, (Integer) p.args[0]);
                if (source == target) return;
                int start = view.getScrollX();
                if ((Integer) primaryScroll.invoke(orientation.invoke(view), view) != start) return;
                int fromPage = (Integer) pageX.invoke(view, source);
                int end = (Integer) pageX.invoke(view, target);
                int dx = (Integer) p.args[1];
                int direction = Integer.signum(end - fromPage);
                if (direction == 0 || direction != Integer.signum(dx) || start + dx != end) return;
                int space = direction > 0 ? maxScroll.getInt(view) - end : end - minScroll.getInt(view);
                if (space < 0) return;
                // PagedView has computed release velocity by the time it starts the snap.
                VelocityTracker tracker = (VelocityTracker) velocityTracker.get(view);
                float release = tracker == null ? 0f : -direction * tracker.getXVelocity(pointerId.getInt(view));
                if (!Float.isFinite(release)) release = 0f;
                release = Math.max(0f, release);
                int overshoot = MotionCurve.pageOvershoot(view.getWidth(), space, release);
                // A release already within a couple of pixels uses the stock settle.
                // Limit the peak on other near-target releases to maintain continuity.
                overshoot = Math.min(overshoot, Math.abs(dx) / 3);
                // Scale after the continuity clamp, including near-target releases.
                overshoot = Math.round(overshoot * RECOIL_SCALE);
                if (overshoot < 3) return;
                MotionCurve.PageSpring spring = new MotionCurve.PageSpring(
                        Math.abs(dx), overshoot, release, view.getWidth());
                scroller.pending = new Snap(source, target, direction, end, dx, overshoot, space == 0, release, spring);
                p.setObjectExtra(EXTRA, scroller);
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                PageScroller scroller = (PageScroller) p.getObjectExtra(EXTRA);
                if (scroller != null) scroller.pending = null;
            }
        });
        XposedBridge.hookMethod(Motion.method(paged, "computeScrollHelper"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (!workspace.isInstance(p.thisObject) || !Motion.onUiThread()) return;
                PageScroller scroller = getScroller(scrollerField, p.thisObject);
                if (scroller == null || scroller.curve.active == null) return;
                Snap snap = scroller.curve.active;
                p.setObjectExtra(EXTRA, snap);
                if (!snap.edge) return;
                // Temporarily let the normal scroll update draw the small edge
                // excursion without activating the separate edge/Google Feed gesture.
                Field edge = snap.direction > 0 ? maxScroll : minScroll;
                int bound = edge.getInt(p.thisObject);
                if (bound == snap.end) {
                    edge.setInt(p.thisObject, bound + snap.direction * snap.overshoot);
                    snap.widened = true;
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Snap snap = (Snap) p.getObjectExtra(EXTRA);
                if (snap == null) return;
                if (snap.widened) {
                    snap.widened = false;
                    Field edge = snap.direction > 0 ? maxScroll : minScroll;
                    if (edge.getInt(p.thisObject) == snap.end + snap.direction * snap.overshoot) {
                        edge.setInt(p.thisObject, snap.end);
                    }
                }
                int scroll = ((ViewGroup) p.thisObject).getScrollX();
                snap.max = Math.max(snap.max, snap.direction * (scroll - snap.end));
                PageScroller scroller = getScroller(scrollerField, p.thisObject);
                if (scroller != null && scroller.isFinished() && !snap.logged) {
                    snap.logged = true;
                    Motion.log("v8 page " + (snap.source + 1) + " -> " + (snap.target + 1)
                            + ", peak=" + snap.max + "px, final=" + (scroll - snap.end)
                            + "px, edge=" + snap.edge + ", distance=" + Math.abs(snap.delta)
                            + "px, release=" + Math.round(snap.release) + "px/s, arrive="
                            + Math.round(snap.spring.crossingAt * 1000) + "ms, crest="
                            + Math.round(snap.spring.peakAt * 1000) + "ms, settle="
                            + snap.spring.durationMs + "ms");
                }
            }
        });
        XposedBridge.hookMethod(Motion.method(paged, "onPageScrollsInitialized"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!workspace.isInstance(p.thisObject)) return;
                PageScroller scroller = getScroller(scrollerField, p.thisObject);
                int count = ((ViewGroup) p.thisObject).getChildCount();
                if (scroller != null && scroller.lastCount != count) {
                    scroller.lastCount = count;
                    Motion.log("v8 workspace pages=" + count);
                }
            }
        });
        for (Constructor<?> constructor : workspace.getDeclaredConstructors()) {
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.hasThrowable() || getScroller(scrollerField, p.thisObject) != null) return;
                    ViewGroup view = (ViewGroup) p.thisObject;
                    scrollerField.set(view, new PageScroller(view.getContext(), new Curve(stock)));
                }
            });
        }
    }

    private static PageScroller getScroller(Field field, Object view) throws IllegalAccessException {
        Object result = field.get(view);
        return result instanceof PageScroller ? (PageScroller) result : null;
    }

    private static final class PageScroller extends OverScroller {
        final Curve curve;
        boolean releasing;
        int lastCount;
        Snap pending;

        PageScroller(Context context, Curve curve) {
            super(context, curve);
            this.curve = curve;
        }

        @Override public void startScroll(int startX, int startY, int dx, int dy, int duration) {
            curve.active = pending;
            if (pending != null) duration = pending.spring.durationMs;
            super.startScroll(startX, startY, dx, dy, duration);
        }

        @Override public void abortAnimation() {
            super.abortAnimation();
            curve.active = null;
        }

        @Override public boolean computeScrollOffset() {
            boolean result = super.computeScrollOffset();
            if (isFinished()) curve.active = null;
            return result;
        }
    }

    private static final class Curve implements Interpolator {
        final Interpolator stock;
        Snap active;

        Curve(Interpolator stock) { this.stock = stock; }

        @Override public float getInterpolation(float t) {
            Snap snap = active;
            if (snap == null) return stock.getInterpolation(t);
            float travel = snap.spring.fraction(t);
            // PagedView stops immediately upon seeing the exact final pixel.
            // Skip that pixel when crossing the destination and on the last
            // subpixel of the return. OverScroller still finishes exactly on it.
            if (t > 0f && t < 1f && Math.round(travel * snap.delta) == snap.delta) {
                return (snap.delta + (travel < 1f ? -snap.direction : snap.direction)) / (float) snap.delta;
            }
            return travel;
        }
    }

    private static final class Snap {
        final int source, target, direction, end, delta, overshoot;
        final boolean edge;
        final float release;
        final MotionCurve.PageSpring spring;
        boolean widened, logged;
        int max;

        Snap(int source, int target, int direction, int end, int delta, int overshoot, boolean edge,
                float release, MotionCurve.PageSpring spring) {
            this.source = source;
            this.target = target;
            this.direction = direction;
            this.end = end;
            this.delta = delta;
            this.overshoot = overshoot;
            this.edge = edge;
            this.release = release;
            this.spring = spring;
        }
    }
}
