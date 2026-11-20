package dev.pixelsuite.splash;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;

public final class LaunchOrigin {
    public static void install(ClassLoader loader) {
        try {
            Class<?> task=loader.loadClass("com.android.server.wm.Task"), record=loader.loadClass("com.android.server.wm.ActivityRecord"), window=loader.loadClass("android.window.StartingWindowInfo");
            Field caller=record.getField("launchedFromPackage"), intent=record.getField("intent"), cookie=record.getField("mLaunchCookie");
            Field flags=window.getField("startingWindowTypeParameter"), info=window.getField("taskInfo");
            XposedBridge.hookMethod(task.getDeclaredMethod("getStartingWindowInfo",record),new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) throws Exception {
                    Object result=call.getResult(), activity=call.args[0]; if (result==null || activity==null) return;
                    ActivityManager.RunningTaskInfo running=(ActivityManager.RunningTaskInfo)info.get(result);
                    if (running==null || running.baseIntent==null) return;
                    Intent base=running.baseIntent, from=(Intent)intent.get(activity);
                    IBinder key=null;
                    if (SplashPolicy.LAUNCHER.equals(caller.get(activity)) && SplashPolicy.freshWindow(1,flags.getInt(result)) && from!=null && from.getComponent()!=null
                            && Intent.ACTION_MAIN.equals(from.getAction()) && from.hasCategory(Intent.CATEGORY_LAUNCHER)
                            && SplashPolicy.fresh(from.getLongExtra(SplashPolicy.STAMP,0L),SystemClock.uptimeMillis())) key=(IBinder)cookie.get(activity);
                    if (key!=null || base.hasExtra(SplashPolicy.COOKIE)) {
                        Intent copy=new Intent(base); copy.removeExtra(SplashPolicy.COOKIE);
                        if (key!=null) { Bundle extra=new Bundle(); extra.putBinder(SplashPolicy.COOKIE,key); copy.putExtras(extra); }
                        running.baseIntent=copy;
                    }
                }
            });
        } catch (Exception failure) { Log.w("PixelSuiteSplash","Window origin unavailable",failure); }
    }
}
