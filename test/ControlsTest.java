package dev.pixelambient.aod;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import de.robv.android.xposed.XposedBridge;

public class ControlsTest {
    static int count;
    static void check(boolean value) { if (!value) throw new AssertionError("controls assertion " + (count + 1)); count++; }
    public static void main(String[] args) {
        Context client = new Context(AodControls.MODULE);
        AodControlsHost.install();
        XposedBridge.start(new Application(AodControls.HOST));
        check(AodControls.PERMISSION.equals(Context.permission));
        Bundle read = AodControls.request(client, AodControls.READ, false);
        check(read.flag("shade") && !read.flag("wallpaper") && Settings.Secure.values.isEmpty());
        check(read.flag("gain") && !AodControls.request(client,AodControls.GAIN,false).flag("gain"));
        check(!AodControls.request(client,AodControls.READ,false).flag("gain") && !Settings.Secure.values.containsKey("wall"));
        Bundle on = AodControls.request(client, AodControls.WALLPAPER, true);
        check("saved".equals(on.get("status")) && on.flag("wallpaper"));
        check(AodControls.request(client, AodControls.READ, false).flag("wallpaper"));
        check(!AodControls.request(client, AodControls.SHADE, false).flag("shade"));
        check(!AodControls.request(client,AodControls.WALLPAPER,false).flag("wallpaper") && "1".equals(Settings.Secure.values.get("clock")));
        Settings.Secure.reject = true;
        check("write_failed".equals(AodControls.request(client,AodControls.WALLPAPER,true).get("status")));
        Settings.Secure.reject = false;
        AodTimeout.ready = false;
        check("unsupported".equals(AodControls.request(client,AodControls.WALLPAPER,true).get("status")));
        AodTimeout.ready = true;
        AodSystemUiPort.ready = false;
        check("unsupported".equals(AodControls.request(client,AodControls.WALLPAPER,true).get("status")));
        check("saved".equals(AodControls.request(client,AodControls.WALLPAPER,false).get("status")));
        AodSystemUiPort.ready = true;
        check(AodControls.request(new Context("other.app"),AodControls.WALLPAPER,true).isEmpty());
        BroadcastReceiver.uid=111234;
        check(AodControls.request(client,AodControls.WALLPAPER,true).isEmpty());
        BroadcastReceiver.uid=10123;
        client.signed=false;
        check(AodControls.request(client,AodControls.WALLPAPER,true).isEmpty());
        check("0".equals(Settings.Secure.values.get("wall")));
        System.out.println("PASS: " + count + " scoped settings protocol assertions");
    }
}
