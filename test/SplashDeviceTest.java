package dev.pixelsuite.splash;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.*;

public final class SplashDeviceTest {
    private static void check(boolean condition,String name) { if (!condition) throw new AssertionError(name); }

    public static void main(String[] args) {
        try { verify(); } catch (Throwable failed) { failed.printStackTrace(); System.exit(1); }
    }

    private static void verify() throws Exception {
        Looper.prepare(); Resources resources=Resources.getSystem();
        Bitmap mark=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        for (int row=8;row<24;row++) for (int column=12;column<20;column++) mark.setPixel(column,row,0xffffffff);
        for (Bitmap bitmap : new Bitmap[]{mark,mark.copy(Bitmap.Config.HARDWARE,false)}) {
            BitmapDrawable foreground=new BitmapDrawable(resources,bitmap);
            ColorDrawable background=new ColorDrawable(0xff1263bf);
            AdaptiveIconDrawable icon=new AdaptiveIconDrawable(background,foreground); icon.setBounds(5,7,51,53);
            Rect previous=new Rect(foreground.getBounds()), previousBack=new Rect(background.getBounds());
            LayerImage image=LayerImage.from(icon,resources,-.1f);
            check(image!=null && image.background==0xff1263bf,"exact pack background");
            check(image.foreground[0]==0 && image.foreground[72*144+72]==0xffffffff,"only foreground in center");
            check(previous.equals(foreground.getBounds()) && previousBack.equals(background.getBounds()),"exit layers unchanged");
        }
        check(LayerImage.from(new AdaptiveIconDrawable(new ColorDrawable(-1),new ColorDrawable(0)),resources,0)==null,"blank fallback");
        Bitmap pattern=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        pattern.eraseColor(0xff1263bf);
        for (int row=0;row<32;row++) for (int column=0;column<8;column++) pattern.setPixel(column,row,0xffff9900);
        LayerImage multicolor=LayerImage.from(new AdaptiveIconDrawable(new BitmapDrawable(resources,pattern),new BitmapDrawable(resources,mark)),resources,0);
        check(multicolor!=null && multicolor.background==0xff1263bf,"only background contributes to dominant color");
        check(multicolor.foreground[0]==0 && multicolor.foreground[72*144+72]==0xffffffff,"pattern does not leak into foreground");
        IBinder cookie=new Binder(); ActivityOptions options=ActivityOptions.makeBasic();
        ActivityOptions.class.getMethod("setLaunchCookie",IBinder.class).invoke(options,cookie);
        check(ActivityOptions.class.getMethod("getLaunchCookie").invoke(options)==cookie,"activity options identity");
        Class<?> window=Class.forName("android.window.StartingWindowInfo"); Object original=window.getConstructor().newInstance();
        ActivityManager.RunningTaskInfo running=new ActivityManager.RunningTaskInfo(); running.baseIntent=new Intent(Intent.ACTION_MAIN);
        Bundle extra=new Bundle(); extra.putBinder(SplashPolicy.COOKIE,cookie); running.baseIntent.putExtras(extra); window.getField("taskInfo").set(original,running);
        Parcel parcel=Parcel.obtain(), tokenParcel=Parcel.obtain();
        try {
            tokenParcel.writeStrongBinder(new Binder()); tokenParcel.setDataPosition(0);
            Class<?> token=Class.forName("android.window.WindowContainerToken");
            running.getClass().getField("token").set(running,((Parcelable.Creator<?>)token.getField("CREATOR").get(null)).createFromParcel(tokenParcel));
            ((Parcelable)original).writeToParcel(parcel,0); parcel.setDataPosition(0);
            Object restored=((Parcelable.Creator<?>)window.getField("CREATOR").get(null)).createFromParcel(parcel);
            ActivityManager.RunningTaskInfo copy=(ActivityManager.RunningTaskInfo)window.getField("taskInfo").get(restored);
            check(copy.baseIntent.getExtras().getBinder(SplashPolicy.COOKIE)==cookie,"starting-window cookie survives parcel");
        } finally { tokenParcel.recycle(); parcel.recycle(); }
        System.out.println("PASS: device renders software/hardware layers, protects original bounds, preserves launch/window cookie");
    }
}
