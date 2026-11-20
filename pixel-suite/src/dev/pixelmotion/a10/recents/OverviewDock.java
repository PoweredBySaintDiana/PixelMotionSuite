package dev.pixelmotion.a10.recents;

import android.content.Context;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Supplies Android 10 shelf geometry and a dedicated overlay owned by overview.
 * The real hotseat keeps the stock launcher state animation and home-motion module.
 */
final class OverviewDock {
    private static final String TAG = "PixelRecentsA10";
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final WeakHashMap<Object, Session> SESSIONS = new WeakHashMap<>();
    private static Class<?> launcherClass;
    private static boolean installed;
    private static boolean loggedError;

    private OverviewDock() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        launcherClass = Class.forName(LAUNCHER, false, loader);
        RecentsDockHost.install(loader);
        installed = true;
        log("independent prediction dock installed; own layout and gesture tracking");
    }

    /**
     * Drives the separate host from the real shift in both launcher and fallback windows.
     */
    static void onProgress(Object handler, View recents, float progress, boolean endingHome) {
        if (handler == null || recents == null) return;
        try {
            RecentsDockHost.onProgress(handler, recents, progress, endingHome);
            Session session = SESSIONS.get(handler);
            if (session == null) {
                session = new Session();
                SESSIONS.put(handler, session);
            }
            if (session.logged || endingHome) return;
            session.logged = true;
            log("independent dock gesture progress=" + progress
                    + " panel=" + recents.getClass().getSimpleName());
        } catch (Throwable failure) {
            error("gesture diagnostic", failure);
        }
    }

    static void onOverview(View recents, boolean overview) {
        if (recents == null) return;
        try {
            RecentsDockHost.onOverview(recents, overview);
            log("independent dock overview=" + overview);
        } catch (Throwable failure) {
            error("overview diagnostic", failure);
        }
    }

    static void reset(Object handler) {
        RecentsDockHost.reset(handler);
        if (handler != null) SESSIONS.remove(handler);
    }

    static void abort(Object handler) {
        RecentsDockHost.abort(handler);
        if (handler != null) SESSIONS.remove(handler);
    }

    static Object launcher() throws Throwable {
        if (launcherClass == null) return null;
        Object tracker = field(launcherClass, "ACTIVITY_TRACKER");
        return tracker == null ? null : method(tracker.getClass(), "getCreatedContext").invoke(tracker);
    }

    static float shelfDistance(Context context, Object profile) throws Throwable {
        // The tracking distance is owned by the independent dock. This extra
        // card gap belongs only to the overview target rectangle.
        int cardSpace = (int) (40f * context.getResources().getDisplayMetrics().density);
        return RecentsDockHost.dockHeight(context, profile) + cardSpace;
    }

    static boolean sameWindow(View first, View second) {
        return first.getRootView() == second.getRootView()
                || (first.getWindowToken() != null
                && first.getWindowToken().equals(second.getWindowToken()));
    }

    static Object deviceProfile(Object launcher) throws Throwable {
        return method(launcher.getClass(), "getDeviceProfile").invoke(launcher);
    }

    static boolean phonePortrait(Object profile) throws Throwable {
        if (profile == null) return false;
        Object properties = field(profile, "mDeviceProperties");
        return Boolean.TRUE.equals(field(properties, "isPhone"))
                && !Boolean.TRUE.equals(field(properties, "isLandscape"));
    }

    static Object field(Object owner, String name) throws Throwable {
        if (owner == null) return null;
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner instanceof Class ? null : owner);
            } catch (NoSuchFieldException continueSearch) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Method method(Class<?> owner, String name, Class<?>... parameters) throws Throwable {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException continueSearch) { }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static void error(String operation, Throwable failure) {
        if (loggedError) return;
        loggedError = true;
        log("dock " + operation + " failed: " + failure);
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log("[" + TAG + "] " + message);
    }

    private static final class Session {
        boolean logged;
    }
}
