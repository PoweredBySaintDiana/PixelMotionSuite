package dev.pixelambient.aod;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class AodSystemUiPort {
    static volatile boolean ready;

    static void install(ClassLoader loader) {
        try {
            installCapability(loader);
            installBackScrim(loader);
            installDozeClamp(loader);
            ready = AodTimeout.ready;
            AmbientAodModule.log("Upstream SystemUI port: ready=" + ready);
        } catch (Throwable error) { AmbientAodModule.log("Upstream SystemUI port unavailable: " + error); }
    }

    private static void installCapability(ClassLoader loader) throws ReflectiveOperationException {
        String name = "com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl";
        Class<?> repository = Class.forName(name, false, loader);
        Class<?> mapper = Class.forName(name + "$special$$inlined$map$1$2", false, loader);
        Class<?> triple = Class.forName("kotlin.Triple", false, loader);
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
        Class<?> collector = Class.forName("kotlinx.coroutines.flow.FlowCollector", false, loader);
        Field variant = field(mapper, "$r8$classId"), owner = field(mapper, "this$0");
        Field context = field(repository, "context"), downstream = field(mapper, "$this_unsafeFlow");
        Method forward = collector.getDeclaredMethod("emit", Object.class, continuation);
        forward.setAccessible(true);
        XposedBridge.hookMethod(mapper.getDeclaredMethod("emit", Object.class, continuation), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!ready || !triple.isInstance(param.args[0]) || variant.getInt(param.thisObject) != 0) return;
                Context host = (Context) context.get(owner.get(param.thisObject));
                if (!AmbientSettings.isEnabled(host)) return;
                try {
                    param.setResult(forward.invoke(downstream.get(param.thisObject), Boolean.TRUE, param.args[1]));
                } catch (InvocationTargetException error) { param.setThrowable(error.getCause()); }
            }
        });
    }

    private static void installBackScrim(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> scrim = Class.forName("com.android.systemui.statusbar.phone.ScrimState", false, loader);
        Object aod = field(scrim, "AOD").get(null);
        Field behind = field(scrim, "mBehindAlpha");
        XposedBridge.hookMethod(aod.getClass().getDeclaredMethod("prepare", scrim), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws IllegalAccessException {
                Context host = AodControlsHost.context;
                if (ready && !param.hasThrowable() && param.thisObject == aod && AmbientSettings.isEnabled(host)
                        && AmbientSettings.isDimmed(host)) behind.setFloat(aod, 0.8984375f);
            }
        });
    }

    private static void installDozeClamp(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> brightness = Class.forName("com.android.systemui.doze.DozeScreenBrightness", false, loader);
        Field state = field(brightness, "mState");
        XposedBridge.hookMethod(brightness.getDeclaredMethod("clampToDimBrightnessForScreenOff", float.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws IllegalAccessException {
                if (!ready) return;
                Enum<?> current = (Enum<?>) state.get(param.thisObject);
                if (current == null) return;
                String mode = current.name();
                if (("INITIALIZED".equals(mode) || "DOZE_AOD".equals(mode) || "DOZE_AOD_DOCKED".equals(mode)
                        || "DOZE_AOD_MINMODE".equals(mode)) && AmbientSettings.isEnabled(AodControlsHost.context))
                    param.setResult(0f);
            }
        });
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
}
