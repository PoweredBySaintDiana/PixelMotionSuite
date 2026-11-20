package dev.pixelambient.aod;

import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.view.Display;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class AodPanelBrightness {
    private static final float FLOOR = .025f;
    private static final ThreadLocal<DreamService> sender = new ThreadLocal<>();

    static void install(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.systemui.doze.DozeService", false, loader);
            Field owner = AodSystemUiPort.field(service, "mDozeMachine");
            Field state = AodSystemUiPort.field(owner.getType(), "mState");
            Method update = DreamService.class.getDeclaredMethod("updateDoze");
            Method screen = DreamService.class.getMethod("getDisplayId");
            Class<?> proxy = Class.forName("android.service.dreams.IDreamManager$Stub$Proxy", false, loader);
            Method send = proxy.getDeclaredMethod("startDozingOneway", IBinder.class, int.class, int.class, float.class, boolean.class);
            PowerKeyEntry entry = PowerKeyEntry.install(owner.getType(), state, update);
            XposedBridge.hookMethod(send, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    DreamService host = sender.get();
                    if (host == null || !AodSystemUiPort.ready || param.args[0] == null) return;
                    int display = (Integer)param.args[1];
                    float original = (Float)param.args[3];
                    try {
                        Object machine = owner.get(host);
                        boolean doze = display == Display.STATE_DOZE || display == Display.STATE_DOZE_SUSPEND;
                        if (entry != null && (doze || display == Display.STATE_OFF)) entry.close(machine);
                        if ((!doze && (display != Display.STATE_ON || entry == null || !entry.isOpening(machine)))
                                || (Boolean)param.args[4] || !(original >= 0f && original < FLOOR)) return;
                        Enum<?> mode = machine == null ? null : (Enum<?>)state.get(machine);
                        if (mode == null || !AodWindow.visible(mode.name()) || (Integer)screen.invoke(host) != Display.DEFAULT_DISPLAY
                                || !AmbientSettings.isEnabled(host)) return;
                        PowerManager power = host.getSystemService(PowerManager.class);
                        if (power != null && !power.isInteractive()
                                && Settings.Secure.getInt(host.getContentResolver(), AmbientSettings.GAIN_ENABLED, 1) == 1
                                && (doze || ("DOZE_AOD".equals(mode.name()) && entry.accept(machine, host, power))))
                            param.args[3] = FLOOR;
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            });
            XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (service.isInstance(param.thisObject)) sender.set((DreamService)param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) { sender.remove(); }
            });
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            AmbientAodModule.log("AOD panel brightness unavailable: " + error);
        }
    }

    private static final class PowerKeyEntry extends XC_MethodHook {
        private final Field state;
        private final Method refresh, sleepReason;
        private final int powerButton;
        private Object opening;
        private DreamService boosted;

        private PowerKeyEntry(Field state, Method refresh) throws ReflectiveOperationException {
            this.state = state;
            this.refresh = refresh;
            refresh.setAccessible(true);
            sleepReason = PowerManager.class.getMethod("getLastSleepReason");
            powerButton = PowerManager.class.getField("GO_TO_SLEEP_REASON_POWER_BUTTON").getInt(null);
        }

        static PowerKeyEntry install(Class<?> machine, Field state, Method refresh) {
            try {
                PowerKeyEntry entry = new PowerKeyEntry(state, refresh);
                XposedBridge.hookMethod(machine.getDeclaredMethod("transitionTo", state.getType(), int.class), entry);
                return entry;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                AmbientAodModule.log("Power-key AOD entry unavailable: " + error);
                return null;
            }
        }

        @Override protected synchronized void beforeHookedMethod(MethodHookParam param) {
            if ("INITIALIZED".equals(((Enum<?>)param.args[0]).name())) { opening = param.thisObject; boosted = null; }
        }

        @Override protected void afterHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
            if (!isOpening(param.thisObject)) return;
            Enum<?> actual = (Enum<?>)state.get(param.thisObject);
            if (!param.hasThrowable() && actual != null && ("INITIALIZED".equals(actual.name()) || "DOZE_AOD".equals(actual.name()))) return;
            DreamService host = close(param.thisObject);
            if (host != null && actual != null && !param.hasThrowable() && !"FINISH".equals(actual.name())) refresh.invoke(host);
        }

        synchronized boolean isOpening(Object machine) { return machine != null && opening == machine; }

        boolean accept(Object machine, DreamService host, PowerManager power) throws ReflectiveOperationException {
            if ((Integer)sleepReason.invoke(power) != powerButton) return false;
            synchronized (this) {
                if (!isOpening(machine)) return false;
                boosted = host;
                return true;
            }
        }

        synchronized DreamService close(Object machine) {
            if (!isOpening(machine)) return null;
            DreamService host = boosted;
            opening = null;
            boosted = null;
            return host;
        }
    }
}
