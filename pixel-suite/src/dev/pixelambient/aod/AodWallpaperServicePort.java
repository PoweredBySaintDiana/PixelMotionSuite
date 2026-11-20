package dev.pixelambient.aod;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class AodWallpaperServicePort {
    static void install(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.server.wallpaper.WallpaperManagerService", false, loader);
            Field context = AodSystemUiPort.field(service, "mContext"), user = AodSystemUiPort.field(service, "mCurrentUserId");
            Method contextUser = Context.class.getMethod("getUserId");
            XposedBridge.hookMethod(service.getDeclaredMethod("setInAmbientMode", boolean.class, long.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                    if (!Boolean.TRUE.equals(param.args[0])) return;
                    Context host = (Context) context.get(param.thisObject);
                    if (host != null && user.getInt(param.thisObject) == (int) contextUser.invoke(host)
                            && AmbientSettings.isEnabled(host)) param.args[0] = false;
                }
            });
            AmbientAodModule.log("Upstream wallpaper service port installed");
        } catch (Throwable error) { AmbientAodModule.log("Upstream wallpaper service port unavailable: " + error); }
    }
}
