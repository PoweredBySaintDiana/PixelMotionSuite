package dev.pixelsuite.splash;

import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

public final class HomeSplash {
    private static final WeakHashMap<View,Ticket> fetched = new WeakHashMap<>(), launched = new WeakHashMap<>();
    private static final ThreadLocal<Ticket> click = new ThreadLocal<>();
    private static final ThreadLocal<Object> nativeIcon = new ThreadLocal<>();
    private static Handler worker;
    private static boolean installed;

    private static final class Ticket {
        final Object load;
        final long stamp = SystemClock.uptimeMillis();
        ComponentName target;
        volatile boolean accepted;
        Ticket(Object load) { this.load = load; }
    }

    public static boolean nativeFrame(Object icon) { return installed && nativeIcon.get() == icon; }

    public static void install(ClassLoader loader) {
        try {
            String base = "com.android.launcher3.";
            Class<?> launcher = loader.loadClass(base+"uioverrides.QuickstepLauncher"), item = loader.loadClass(base+"model.data.ItemInfo");
            Class<?> bubble = loader.loadClass(base+"BubbleTextView"), floating = loader.loadClass(base+"views.FloatingIconView");
            Class<?> runner = loader.loadClass(base+"QuickstepTransitionManager$7"), folder = loader.loadClass(base+"dragndrop.FolderAdaptiveIcon");
            Field container = item.getField("container"), type = item.getField("itemType"), user = item.getField("user");
            Field hasSplash = runner.getField("val$hasSplashScreen"), floatingView = runner.getField("val$floatingView"), original = runner.getField("val$v");
            Class<?> state = floating.getField("mIconLoadResult").getType();
            Field owner = state.getField("itemInfo"), loaded = state.getField("isIconLoaded"), drawable = state.getField("drawable"), offset = state.getField("iconOffset");
            Method bounds = bubble.getMethod("getIconBounds",Rect.class), cookie = ActivityOptions.class.getMethod("getLaunchCookie");
            Method options = launcher.getDeclaredMethod("getActivityLaunchOptions",View.class,item);
            Field wrapped = options.getReturnType().getField("options");
            Method launch = launcher.getDeclaredMethod("startActivitySafely",View.class,Intent.class,item);
            Method fetch = floating.getDeclaredMethod("fetchIcon",loader.loadClass(base+"Launcher"),View.class,item,boolean.class);
            Method update = runner.getDeclaredMethod("onUpdate",float.class,boolean.class);
            XposedBridge.hookMethod(fetch,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (installed && Looper.myLooper()==Looper.getMainLooper() && Boolean.TRUE.equals(call.args[3]) && bubble.isInstance(call.args[1]) && call.getResult()!=null)
                        fetched.put((View)call.args[1],new Ticket(call.getResult()));
                }
            });
            XposedBridge.hookMethod(launch,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Exception {
                    call.setObjectExtra("previousClick",click.get()); click.remove();
                    View source = (View)call.args[0]; Object info = call.args[2]; Intent intent = (Intent)call.args[1];
                    Ticket ticket = fetched.remove(source); launched.remove(source);
                    if (!installed || ticket==null || info==null || source.getTag()!=info || owner.get(ticket.load)!=info || intent==null || intent.getComponent()==null
                            || !SplashPolicy.fresh(ticket.stamp,SystemClock.uptimeMillis()) || !Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                            || !android.os.Process.myUserHandle().equals(user.get(info)) || !SplashPolicy.launcherApp(container.getInt(info),type.getInt(info))) return;
                    ticket.target = intent.getComponent(); click.set(ticket);
                    call.args[1]=new Intent(intent).putExtra(SplashPolicy.STAMP,ticket.stamp);
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Ticket previous = (Ticket)call.getObjectExtra("previousClick");
                    if (previous==null) click.remove(); else click.set(previous);
                }
            });
            XposedBridge.hookMethod(options,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) throws Exception {
                    Ticket ticket = click.get(); View source = (View)call.args[0];
                    if (ticket==null || source==null || owner.get(ticket.load)!=source.getTag() || call.getResult()==null) return;
                    IBinder key = (IBinder)cookie.invoke(wrapped.get(call.getResult()));
                    if (key==null) return;
                    Context context = source.getContext().getApplicationContext(); Rect box = new Rect(); bounds.invoke(source,box);
                    int blurId = context.getResources().getIdentifier("blur_size_medium_outline","dimen",context.getPackageName());
                    int blur = blurId==0 ? 0 : context.getResources().getDimensionPixelSize(blurId);
                    int width = box.width()+blur; if (width<=0) return;
                    launched.put(source,ticket);
                    new Handler(Looper.getMainLooper()).postDelayed(()->{ if (launched.get(source)==ticket) launched.remove(source); },SplashPolicy.LIFETIME);
                    worker.post(new Runnable() {
                        public void run() {
                            try {
                                if (SystemClock.uptimeMillis()-ticket.stamp>180) return;
                                Drawable icon; int padding;
                                synchronized (ticket.load) {
                                    if (!loaded.getBoolean(ticket.load)) { worker.postDelayed(this,2); return; }
                                    icon=(Drawable)drawable.get(ticket.load); padding=offset.getInt(ticket.load)-blur/2;
                                }
                                if (!(icon instanceof AdaptiveIconDrawable) || folder.isInstance(icon)) return;
                                LayerImage image=LayerImage.from((AdaptiveIconDrawable)icon,context.getResources(),padding/(float)width);
                                if (image==null) return;
                                Bundle payload=new Bundle(); payload.putBinder("cookie",key); payload.putLong("time",ticket.stamp);
                                payload.putString("target",ticket.target.flattenToString()); payload.putInt("background",image.background); payload.putIntArray("foreground",image.foreground);
                                payload.putParcelable("accepted",new ResultReceiver(null) {
                                    @Override protected void onReceiveResult(int code,Bundle ignored) { if (code==1) ticket.accepted=true; }
                                });
                                Intent delivery = new Intent(SplashPolicy.ACTION).setPackage(SplashPolicy.SHELL).putExtras(payload)
                                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY|Intent.FLAG_RECEIVER_FOREGROUND);
                                BroadcastOptions identity=BroadcastOptions.makeBasic(); identity.setShareIdentityEnabled(true);
                                context.sendBroadcast(delivery,null,identity.toBundle());
                            } catch (Exception failure) { Log.d("PixelSuiteSplash","No launcher layers for this launch",failure); }
                        }
                    });
                }
            });
            XposedBridge.hookMethod(update,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Exception {
                    call.setObjectExtra("previousIcon",nativeIcon.get()); nativeIcon.remove();
                    if (!installed || !hasSplash.getBoolean(call.thisObject)) return;
                    Ticket ticket = launched.get(original.get(call.thisObject));
                    if (ticket!=null && ticket.accepted && SplashPolicy.fresh(ticket.stamp,SystemClock.uptimeMillis())) nativeIcon.set(floatingView.get(call.thisObject));
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Object previous=call.getObjectExtra("previousIcon"); if (previous==null) nativeIcon.remove(); else nativeIcon.set(previous);
                }
            });
            HandlerThread thread=new HandlerThread("PixelSuite-icon"); thread.start(); worker=new Handler(thread.getLooper()); installed=true;
        } catch (Exception failure) { Log.w("PixelSuiteSplash","Launcher animation not supported",failure); }
    }
}
