package dev.pixelsuite.splash;

public final class SplashTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        for (int container : new int[]{-100,-101,-104,-102}) {
            check(SplashPolicy.launcherApp(container,0));
            check(!SplashPolicy.launcherApp(container,2) && !SplashPolicy.launcherApp(container,6));
        }
        for (int container : new int[]{-1,-103,-110,25}) check(!SplashPolicy.launcherApp(container,0));
        check(SplashPolicy.freshWindow(1,0x200) && !SplashPolicy.freshWindow(3,0) && !SplashPolicy.freshWindow(4,0));
        check(!SplashPolicy.freshWindow(1,0x10) && !SplashPolicy.freshWindow(1,0x40));
        check(!SplashPolicy.fresh(200,100) && !SplashPolicy.fresh(0,100) && !SplashPolicy.fresh(100,1300));
        check(SplashPolicy.background(new int[]{0,0x20ff0000})==0);
        check(SplashPolicy.background(new int[]{0,0xff1a64df,0xff1a64df,0xffff0000})==0xff1a64df);
        check(SplashPolicy.background(new int[]{0xff000000})==0xff000000);
        check(!SplashPolicy.visibleForeground(new int[100]) && !SplashPolicy.visibleForeground(new int[]{-1,-1}));
        check(SplashPolicy.visibleForeground(new int[]{0,-1}));
        LaunchLayers<Object,String> pairs=new LaunchLayers<>(); Object first=new Object(), second=new Object();
        pairs.put(first,100,"phone","white handset",100); pairs.put(second,101,"mail","envelope",102);
        check(pairs.take(first,"mail",104)==null && pairs.take(new Object(),"phone",104)==null);
        check("envelope".equals(pairs.take(second,"mail",105)) && "white handset".equals(pairs.take(first,"phone",106)));
        check(pairs.take(first,"phone",107)==null);
        pairs.put(first,110,"phone","old theme",112); pairs.put(first,120,"phone","new theme",120); pairs.discard(first,110);
        check("new theme".equals(pairs.take(first,"phone",125)));
        pairs.put(first,130,"phone","expired",130); check(pairs.take(first,"phone",1330)==null);
        pairs.put(first,1000,"phone","first",1000);
        for (int index=0;index<8;index++) pairs.put(new Object(),1010,"other","layer",1010);
        check(pairs.take(first,"phone",1020)==null);
        System.out.println("OK: launch origin, window types, layer palette, timeout, one-shot matching and expiry");
    }
}
