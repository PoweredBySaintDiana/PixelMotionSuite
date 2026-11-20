package dev.pixelsuite.splash;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;

public final class ShellSplash {
    private static final LaunchLayers<IBinder,Bundle> entries=new LaunchLayers<>();
    private static final ThreadLocal<Window> current=new ThreadLocal<>();
    private static Handler delivery;
    private static volatile boolean registered;

    private static final class Window {
        final Object drawer;
        final IBinder cookie;
        final long began=SystemClock.uptimeMillis();
        String target;
        int size;
        Window(Object drawer,IBinder cookie) { this.drawer=drawer; this.cookie=cookie; }
    }

    private static synchronized void listen(Context context) {
        if (registered) return;
        try {
            int launcherUid=context.getPackageManager().getPackageUid(SplashPolicy.LAUNCHER,0);
            if (delivery==null) { HandlerThread thread=new HandlerThread("PixelSuite-layers"); thread.start(); delivery=new Handler(thread.getLooper()); }
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context unused,Intent intent) {
                    if (getSentFromUid()!=launcherUid || !SplashPolicy.LAUNCHER.equals(getSentFromPackage()) || !SplashPolicy.ACTION.equals(intent.getAction())) return;
                    Bundle data=intent.getExtras(); if (data==null) return;
                    IBinder key=data.getBinder("cookie"); long stamp=data.getLong("time"); int[] pixels=data.getIntArray("foreground");
                    if (key==null || !SplashPolicy.fresh(stamp,SystemClock.uptimeMillis()) || pixels==null || pixels.length!=SplashPolicy.SIZE*SplashPolicy.SIZE
                            || (data.getInt("background")>>>24)!=255 || data.getString("target")==null) return;
                    synchronized(entries) {
                        entries.put(key,stamp,data.getString("target"),data,SystemClock.uptimeMillis()); entries.notifyAll();
                    }
                    delivery.postAtTime(()->{ synchronized(entries) { entries.discard(key,stamp); } },stamp+SplashPolicy.LIFETIME);
                }
            },new IntentFilter(SplashPolicy.ACTION),"android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS",delivery,Context.RECEIVER_EXPORTED);
            registered=true;
        } catch (Exception failure) { Log.w("PixelSuiteSplash","Cannot receive launcher layers",failure); }
    }

    private static Bundle take(Window window) {
        synchronized(entries) {
            for (;;) {
                long now=SystemClock.uptimeMillis(); Bundle data=entries.take(window.cookie,window.target,now);
                if (data!=null) return data;
                long remaining=window.began+32-now; if (remaining<=0) return null;
                try { entries.wait(remaining); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return null; }
            }
        }
    }

    private static Field privateField(Class<?> type,String name) throws Exception {
        Field result=type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> drawer=loader.loadClass("com.android.wm.shell.startingsurface.SplashscreenContentDrawer"), builder=loader.loadClass(drawer.getName()+"$SplashViewBuilder");
            Class<?> platform=loader.loadClass("android.window.SplashScreenView$Builder"), window=loader.loadClass("android.window.StartingWindowInfo");
            Class<?> callback=loader.loadClass("com.android.wm.shell.startingsurface.SplashscreenWindowCreator$$ExternalSyntheticLambda4");
            Field task=window.getField("taskInfo"), flags=window.getField("startingWindowTypeParameter"), context=drawer.getField("mContext"), work=drawer.getField("mSplashscreenWorkerHandler");
            Field parent=builder.getField("this$0"), type=builder.getField("mSuggestType"), attrs=drawer.getField("mTmpAttrs"), overlay=builder.getField("mOverlayDrawable");
            Field custom=attrs.getType().getField("mSplashScreenIcon"), brand=attrs.getType().getField("mBrandingImage"), activity=builder.getField("mActivityInfo"), size=drawer.getField("mIconSize");
            Field center=privateField(platform,"mIconDrawable"), back=privateField(platform,"mIconBackground"), color=privateField(platform,"mBackgroundColor"), iconSize=privateField(platform,"mIconSize");
            XposedBridge.hookMethod(platform.getDeclaredMethod("build"),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Exception {
                    Window frame=current.get(); if (frame==null || frame.target==null || frame.size<=0 || center.get(call.thisObject)==null || iconSize.getInt(call.thisObject)<=0) return;
                    Bundle data=take(frame); if (data==null) return;
                    Bitmap image=Bitmap.createBitmap(data.getIntArray("foreground"),SplashPolicy.SIZE,SplashPolicy.SIZE,Bitmap.Config.ARGB_8888);
                    Context shell=(Context)context.get(frame.drawer); image.setDensity(shell.getResources().getDisplayMetrics().densityDpi);
                    BitmapDrawable foreground=new BitmapDrawable(shell.getResources(),image); foreground.setFilterBitmap(true);
                    color.setInt(call.thisObject,data.getInt("background")); back.set(call.thisObject,null); center.set(call.thisObject,foreground); iconSize.setInt(call.thisObject,frame.size);
                    call.setObjectExtra("accepted",data.getParcelable("accepted",ResultReceiver.class)); frame.target=null;
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    ResultReceiver accepted=(ResultReceiver)call.getObjectExtra("accepted"); if (accepted!=null && call.getResult()!=null) accepted.send(1,null);
                }
            });
            XposedBridge.hookMethod(builder.getDeclaredMethod("build"),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Exception {
                    Window frame=current.get(); if (frame==null || frame.drawer!=parent.get(call.thisObject)) return;
                    Object attributes=attrs.get(frame.drawer); ActivityInfo info=(ActivityInfo)activity.get(call.thisObject);
                    if (type.getInt(call.thisObject)!=1 || overlay.get(call.thisObject)!=null || custom.get(attributes)!=null || brand.get(attributes)!=null || info==null) return;
                    frame.target=new ComponentName(info.packageName,info.name).flattenToString(); frame.size=size.getInt(frame.drawer);
                }
                @Override protected void afterHookedMethod(MethodHookParam call) { Window frame=current.get(); if (frame!=null) frame.target=null; }
            });
            XposedBridge.hookMethod(drawer.getDeclaredMethod("makeSplashScreenContentView",Context.class,window,int.class,callback),new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Exception {
                    call.setObjectExtra("previousWindow",current.get()); current.remove();
                    if (!registered || !SplashPolicy.freshWindow((Integer)call.args[2],flags.getInt(call.args[1])) || Looper.myLooper()==Looper.getMainLooper()
                            || ((Handler)work.get(call.thisObject)).getLooper()!=Looper.myLooper()) return;
                    ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)task.get(call.args[1]);
                    if (info==null || info.baseIntent==null || info.baseIntent.getExtras()==null) return;
                    IBinder key=info.baseIntent.getExtras().getBinder(SplashPolicy.COOKIE); if (key!=null) current.set(new Window(call.thisObject,key));
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Window previous=(Window)call.getObjectExtra("previousWindow"); if (previous==null) current.remove(); else current.set(previous);
                }
            });
            for (java.lang.reflect.Constructor<?> constructor : drawer.getDeclaredConstructors()) XposedBridge.hookMethod(constructor,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) throws Exception { if (!call.hasThrowable()) listen((Context)context.get(call.thisObject)); }
            });
        } catch (Exception failure) { Log.w("PixelSuiteSplash","System splash layout unavailable",failure); }
    }
}
