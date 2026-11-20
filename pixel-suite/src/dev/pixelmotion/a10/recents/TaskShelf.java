package dev.pixelmotion.a10.recents;

import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Drive adjacent tasks through Quickstep's own thumbnail and live-surface property. */
final class TaskShelf {
    private static final WeakHashMap<Object, State> HANDLERS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> ENTRY_RELEASED = new WeakHashMap<>();
    private static final WeakHashMap<View, State> PANELS = new WeakHashMap<>();
    private static Object nativeProperty;
    private static Method nativeSetter;
    private static Field adjacentOffset;
    private static Field likelyNewTask;
    private static Field continuingGesture;
    private static Field gestureState;
    private static Method getEndTarget;
    private static boolean installed;
    private static boolean loggedError;

    private TaskShelf() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        Class<?> recents = Class.forName("com.android.quickstep.views.RecentsView", false, loader);
        Class<?> handler = Class.forName("com.android.quickstep.AbsSwipeUpHandler", false, loader);
        Class<?> gesture = Class.forName("com.android.quickstep.GestureState", false, loader);
        Field property = field(recents, "ADJACENT_PAGE_HORIZONTAL_OFFSET");
        nativeProperty = property.get(null);
        adjacentOffset = field(recents, "mAdjacentPageHorizontalOffset");
        likelyNewTask = field(handler, "mIsLikelyToStartNewTask");
        continuingGesture = field(handler, "mContinuingLastGesture");
        gestureState = field(handler, "mGestureState");
        getEndTarget = gesture.getDeclaredMethod("getEndTarget");
        getEndTarget.setAccessible(true);
        nativeSetter = nativeProperty.getClass().getDeclaredMethod("setValue", recents, float.class);
        nativeSetter.setAccessible(true);

        XposedBridge.hookMethod(nativeSetter, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (p.thisObject != nativeProperty || !(p.args[0] instanceof View)) return;
                State state = PANELS.get((View) p.args[0]);
                if (state == null || !state.active || state.writing) return;
                float stockValue = (Float) p.args[1];
                if (Float.isFinite(stockValue)) state.lastNativeOffset = stockValue;
                // Preserve the setter's native updatePageOffsets() callbacks. Those
                // write TaskView translation AND TaskViewSimulator live surfaces.
                p.args[1] = state.offset;
            }
        });
        installed = true;
    }

    static void onProgress(Object handler, View recents, float progress, boolean endingHome) {
        if (!installed || handler == null || recents == null || !Float.isFinite(progress)) return;
        try {
            if (endingHome || yieldsToNative(handler)) {
                reset(handler);
                return;
            }
            if (ENTRY_RELEASED.containsKey(handler)) return;
            State state = HANDLERS.get(handler);
            if (state != null && state.panel.get() != recents) {
                reset(handler);
                state = null;
            }
            if (state == null) {
                State previous = PANELS.remove(recents);
                if (previous != null) previous.active = false;
                state = new State(recents, adjacentOffset.getFloat(recents));
                // A replacement handler can attach before Quickstep has written
                // again. Its cancellation must restore native state, not our
                // outgoing handler's forced position.
                if (previous != null && Float.isFinite(previous.lastNativeOffset)) {
                    state.lastNativeOffset = previous.lastNativeOffset;
                }
                HANDLERS.put(handler, state);
                PANELS.put(recents, state);
            } else if (PANELS.get(recents) != state || !state.active) {
                // A completed overview or a newer gesture owns this panel now.
                return;
            }
            state.offset = 1f - clamp(progress);
            write(state, state.offset);
        } catch (Throwable error) {
            logError(error);
            reset(handler);
        }
    }

    /** Native attachment now continues from the displayed card position. */
    static void handToEntry(Object handler) {
        if (handler == null) return;
        ENTRY_RELEASED.put(handler, Boolean.TRUE);
        State state = HANDLERS.get(handler);
        if (state == null) return;
        View recents = state.panel.get();
        if (recents != null && PANELS.get(recents) == state) PANELS.remove(recents);
        // Retain the inactive state until reset so later shift callbacks cannot
        // reclaim the property. Do not write a stale pre-entry native offset.
        state.active = false;
    }

    static void reset(Object handler) {
        ENTRY_RELEASED.remove(handler);
        State state = HANDLERS.remove(handler);
        if (state == null) return;
        View recents = state.panel.get();
        if (recents == null || PANELS.get(recents) != state) {
            state.active = false;
            return;
        }
        PANELS.remove(recents);
        state.active = false;
        try {
            // Hand the property back to the last value requested by Quickstep.
            // Its in-flight attachment or cancellation animation can then continue.
            write(state, state.lastNativeOffset);
        } catch (Throwable error) {
            logError(error);
        }
    }

    static void onOverview(View recents, boolean overview) {
        if (!installed || recents == null) return;
        State state = PANELS.remove(recents);
        if (state == null) return;
        state.active = false;
        try {
            // An entered overview has no adjacent-page displacement. Clear ownership
            // before writing so settled horizontal task scrolling remains native.
            write(state, overview ? 0f : state.lastNativeOffset);
        } catch (Throwable error) {
            logError(error);
        }
    }

    private static boolean yieldsToNative(Object handler) throws Throwable {
        if (likelyNewTask.getBoolean(handler) || continuingGesture.getBoolean(handler)) return true;
        Object gesture = gestureState.get(handler);
        Object target = gesture == null ? null : getEndTarget.invoke(gesture);
        if (target == null) return false;
        String name = target instanceof Enum ? ((Enum<?>) target).name() : target.toString();
        return !"RECENTS".equals(name);
    }

    private static void write(State state, float value) throws Throwable {
        View recents = state.panel.get();
        if (recents == null) return;
        state.writing = true;
        try {
            nativeSetter.invoke(nativeProperty, recents, clamp(value));
        } finally {
            state.writing = false;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException missing) {
                // SwipeUpAnimationLogic owns mGestureState in this launcher.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void logError(Throwable error) {
        if (loggedError) return;
        loggedError = true;
        XposedBridge.log("A10Recents TaskShelf unavailable: " + error);
    }

    private static final class State {
        final WeakReference<View> panel;
        boolean active = true;
        boolean writing;
        float offset;
        float lastNativeOffset;

        State(View panel, float initialOffset) {
            this.panel = new WeakReference<>(panel);
            this.offset = Float.isFinite(initialOffset) ? clamp(initialOffset) : 1f;
            this.lastNativeOffset = this.offset;
        }
    }
}
