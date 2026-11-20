package dev.pixelambient.aod;

import android.app.AlarmManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.view.Display;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class AodTimeout {
    static boolean ready;
    private static Session active;

    static void install(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.systemui.doze.DozeService", false, loader);
            Class<?> machine = Class.forName("com.android.systemui.doze.DozeMachine", false, loader);
            Class<?> state = Class.forName(machine.getName() + "$State", false, loader);
            Field owner = service.getDeclaredField("mDozeMachine"), current = machine.getDeclaredField("mState");
            owner.setAccessible(true);
            current.setAccessible(true);
            Method request = machine.getDeclaredMethod("requestState", state);
            request.setAccessible(true);
            Object off = state.getField("DOZE").get(null);
            Object aod = state.getField("DOZE_AOD").get(null);
            Object pulse = state.getField("DOZE_REQUEST_PULSE").get(null);
            Method transition = machine.getDeclaredMethod("transitionTo", state, int.class);
            Method start = service.getDeclaredMethod("onDreamingStarted");
            Method stop = service.getDeclaredMethod("onDreamingStopped");
            Method destroy = service.getDeclaredMethod("onDestroy");
            XC_MethodHook cleanup = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (active != null && active.service == param.thisObject) clear();
                }
            };
            XposedBridge.hookMethod(stop, cleanup);
            XposedBridge.hookMethod(destroy, cleanup);
            XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                    clear();
                    Object instance = owner.get(param.thisObject);
                    if (instance != null) {
                        active = new Session((DreamService) param.thisObject, instance, current, request, off);
                        active.watch();
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() && active != null && active.service == param.thisObject) clear();
                }
            });
            XposedBridge.hookMethod(transition, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                    Session session = active;
                    if (session == null || session.machine != param.thisObject) return;
                    String target = ((Enum<?>) param.args[0]).name();
                    if ("FINISH".equals(target)) { clear(); return; }
                    if (!AmbientSettings.isEnabled(session.service)) { session.cancel(); return; }
                    if (param.args[0] == pulse && ((Integer)param.args[1]) == 3) {
                        PowerManager power = session.service.getSystemService(PowerManager.class);
                        if (power != null && !power.isInteractive()) {
                            Object from = current.get(session.machine);
                            if (from == off) {
                                session.cancel();
                                param.args[0] = aod;
                                param.args[1] = -1;
                                return;
                            }
                            if (AodWindow.visible(session.state())) {
                                param.args[0] = session.window.expired(SystemClock.elapsedRealtime()) ? off : from;
                                param.args[1] = -1;
                            }
                        }
                    }
                    if (session.window.suppress(target, SystemClock.elapsedRealtime())) param.args[0] = off;
                }
                @Override protected void afterHookedMethod(MethodHookParam param) throws Exception {
                    Session session = active;
                    if (param.hasThrowable() || session == null || session.machine != param.thisObject) return;
                    session.checkDisplay();
                }
            });
            ready = true;
        } catch (Throwable error) { AmbientAodModule.log("AOD timer unavailable: " + error); }
    }

    private static void clear() {
        if (active != null) active.close();
        active = null;
    }

    private static final class Session implements DisplayManager.DisplayListener {
        final DreamService service;
        final Object machine, off;
        final Field current;
        final Method request;
        final AlarmManager alarms;
        final DisplayManager displays;
        boolean watching;
        final Handler main = new Handler(Looper.getMainLooper());
        final AodWindow window = new AodWindow();
        AlarmManager.OnAlarmListener pending;
        PowerManager.WakeLock fallbackLock;
        Runnable fallbackTask;

        Session(DreamService service, Object machine, Field current, Method request, Object off) {
            this.service = service; this.machine = machine; this.current = current; this.request = request; this.off = off;
            alarms = service.getSystemService(AlarmManager.class);
            displays = service.getSystemService(DisplayManager.class);
        }

        String state() throws IllegalAccessException { return ((Enum<?>) current.get(machine)).name(); }

        void watch() {
            try { displays.registerDisplayListener(this, main); watching = true; }
            catch (RuntimeException error) { AmbientAodModule.log("AOD display observation unavailable: " + error); }
        }
        public void onDisplayAdded(int id) { }
        public void onDisplayRemoved(int id) { }
        public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) checkDisplay(); }

        void checkDisplay() {
            if (active != this || window.deadline() >= 0 || !AmbientSettings.isEnabled(service)) return;
            try {
                Display display = watching ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
                int status = display == null ? Display.STATE_UNKNOWN : display.getState();
                if (status != Display.STATE_UNKNOWN && status != Display.STATE_DOZE && status != Display.STATE_DOZE_SUSPEND) return;
                if (window.start(state(), SystemClock.elapsedRealtime())) arm();
            } catch (Exception error) { AmbientAodModule.log("AOD display state: " + error); }
        }

        void close() {
            cancel();
            if (watching) displays.unregisterDisplayListener(this);
            watching = false;
        }

        void arm() {
            pending = new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    if (active != Session.this || pending != this) return;
                    if (SystemClock.elapsedRealtime() < window.deadline()) { releaseFallback(); arm(); return; }
                    pending = null;
                    releaseFallback();
                    try {
                        PowerManager power = service.getSystemService(PowerManager.class);
                        if (!AmbientSettings.isEnabled(service)) { cancel(); return; }
                        if (power != null && !power.isInteractive() && window.suppress(state(), SystemClock.elapsedRealtime())) request.invoke(machine, off);
                    } catch (Exception error) { AmbientAodModule.log("AOD timer expiry: " + error); }
                }
            };
            try {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, window.deadline(), "PixelSuite:AOD", pending, main);
            } catch (RuntimeException error) {
                long remaining = Math.max(0L, window.deadline() - SystemClock.elapsedRealtime());
                try {
                    fallbackLock = service.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelSuite:AodTimeout");
                    fallbackLock.acquire(remaining + 500L);
                } catch (RuntimeException fallback) { AmbientAodModule.log("AOD timer wake fallback: " + fallback); }
                fallbackTask = pending::onAlarm;
                main.postDelayed(fallbackTask, remaining);
            }
        }

        void releaseFallback() {
            if (fallbackTask != null) main.removeCallbacks(fallbackTask);
            fallbackTask = null;
            if (fallbackLock != null && fallbackLock.isHeld()) fallbackLock.release();
            fallbackLock = null;
        }

        void cancel() {
            AlarmManager.OnAlarmListener old = pending;
            pending = null;
            window.reset();
            releaseFallback();
            if (old != null && alarms != null) try { alarms.cancel(old); } catch (RuntimeException ignored) { }
        }
    }
}
