package dev.pixelambient.aod;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import static dev.pixelambient.aod.AodControls.*;

final class AodControlsHost {
    static volatile Context context;
    private static boolean registered;

    static void install() {
        try {
            XposedBridge.hookMethod(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    if (param.hasThrowable() || registered || !HOST.equals(app.getPackageName())) return;
                    context = app;
                    try {
                        app.registerReceiver(new ControlsReceiver(), new IntentFilter(ACTION), PERMISSION, null, Context.RECEIVER_EXPORTED);
                        registered = true;
                    } catch (RuntimeException error) { AmbientAodModule.log("AOD controls registration: " + error); }
                }
            });
        } catch (Throwable error) { AmbientAodModule.log("AOD controls installation: " + error); }
    }

    private static final class ControlsReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (!ACTION.equals(intent.getAction()) || !MODULE.equals(getSentFromPackage())
                        || getSentFromUid() < 0 || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(getSentFromUid()))) return;
                long remaining = intent.getLongExtra("until", 0L) - SystemClock.elapsedRealtime();
                if (remaining <= 0 || remaining > WAIT) return;
                ResultReceiver reply = intent.getParcelableExtra("reply", ResultReceiver.class);
                if (reply == null) return;
                Bundle response = new Bundle();
                try {
                    String operation = intent.getStringExtra("op");
                    boolean enabled = intent.getBooleanExtra("on", false);
                    if (!READ.equals(operation) && !intent.hasExtra("on")) return;
                    if (WALLPAPER.equals(operation) && enabled && (!AodTimeout.ready || !AodSystemUiPort.ready)) {
                        response.putString("status", "unsupported");
                    } else {
                        AodPreferences.Store store = new AodPreferences.Store() {
                            public String read(String name) { return Settings.Secure.getString(context.getContentResolver(), name); }
                            public boolean write(String name, String value) { return Settings.Secure.putString(context.getContentResolver(), name, value); }
                        };
                        if (WALLPAPER.equals(operation)) AodPreferences.wallpaper(store, enabled);
                        else if (SHADE.equals(operation)) AodPreferences.shade(store, enabled);
                        else if (GAIN.equals(operation)) AodPreferences.gain(store, enabled);
                        else if (!READ.equals(operation)) return;
                        response.putBoolean(WALLPAPER, AodPreferences.enabled(store));
                        response.putBoolean(SHADE, AodPreferences.dimmed(store));
                        response.putBoolean(GAIN, AodPreferences.gainEnabled(store));
                        response.putString("status", "saved");
                    }
                } catch (RuntimeException error) {
                    AmbientAodModule.log("AOD controls: " + error);
                    response.putString("status", "write_failed");
                }
                reply.send(0, response);
            } catch (Exception error) { AmbientAodModule.log("AOD request rejected: " + error); }
        }
    }
}
