package dev.pixelambient.aod;

import android.content.Context;
import android.provider.Settings.Secure;
import com.android.server.wallpaper.WallpaperManagerService;
import com.android.systemui.doze.DozeScreenBrightness;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl;
import com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl$special$$inlined$map$1$2;
import kotlin.Triple;
import kotlin.coroutines.Continuation;
import static de.robv.android.xposed.XposedBridge.invoke;
import static de.robv.android.xposed.Frame.send;
import com.android.systemui.doze.DozeService;

public class PortTest {
    enum Mode {INITIALIZED, DOZE_AOD, DOZE_PULSING, DOZE_PULSING_BRIGHT, FINISH}
    static int checks;
    static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    static void transition(DozeService dream, Mode target) throws Throwable {
        dream.mDozeMachine.transitionTo(target,-1);
        invoke("transitionTo",dream.mDozeMachine,target,-1);
    }
    static void open(DozeService dream) throws Throwable {
        transition(dream,Mode.INITIALIZED);
        transition(dream,Mode.DOZE_AOD);
    }
    public static void main(String[] args) throws Throwable {
        Context host = new Context();
        AodControlsHost.context = host;
        Secure.values.put(AmbientSettings.ALWAYS_ON, 1);
        Secure.values.put(AmbientSettings.WALLPAPER_ENABLED, 1);
        AodSystemUiPort.install(PortTest.class.getClassLoader());
        AodWallpaperServicePort.install(PortTest.class.getClassLoader());
        check(AodSystemUiPort.ready);
        WallpaperRepositoryImpl$special$$inlined$map$1$2 mapper = new WallpaperRepositoryImpl$special$$inlined$map$1$2();
        mapper.this$0 = new WallpaperRepositoryImpl();
        mapper.this$0.context = host;
        Continuation pending = new Continuation();
        Object suspended = new Object();
        mapper.$this_unsafeFlow = (value, cont) -> {check(Boolean.TRUE.equals(value) && cont == pending); return suspended;};
        check(invoke("emit",mapper,new Triple(),pending).result == suspended);
        check(!invoke("emit",mapper,null,pending).done);
        mapper.$r8$classId=1;
        check(!invoke("emit",mapper,new Triple(),pending).done);
        mapper.$r8$classId=0;
        RuntimeException cancelled = new RuntimeException();
        mapper.$this_unsafeFlow = (value, cont) -> {throw cancelled;};
        check(invoke("emit",mapper,new Triple(),pending).thrown == cancelled);
        WallpaperManagerService service = new WallpaperManagerService();
        service.mContext = host;
        Object[] forwarded = invoke("setInAmbientMode",service,true,432L).args;
        check(Boolean.FALSE.equals(forwarded[0]) && Long.valueOf(432).equals(forwarded[1]));
        check(Boolean.FALSE.equals(invoke("setInAmbientMode",service,false,432L).args[0]));
        service.mCurrentUserId=10;
        check(Boolean.TRUE.equals(invoke("setInAmbientMode",service,true,0L).args[0]));
        service.mCurrentUserId=0;
        host.foreground=false;
        check(!invoke("emit",mapper,new Triple(),pending).done);
        check(Boolean.TRUE.equals(invoke("setInAmbientMode",service,true,0L).args[0]));
        host.foreground=true;
        ScrimState aod=ScrimState.AOD;
        invoke("prepare",aod,ScrimState.LOCK);
        check(aod.mBehindAlpha == 0.8984375f);
        Secure.values.put(AmbientSettings.DIM_ENABLED,0);
        aod.mBehindAlpha=.25f;
        invoke("prepare",aod,ScrimState.LOCK);
        check(aod.mBehindAlpha == .25f);
        DozeScreenBrightness brightness=new DozeScreenBrightness();
        for (Mode mode:Mode.values()) {
            brightness.mState=mode;
            Object value=invoke("clampToDimBrightnessForScreenOff",brightness,.45f).result;
            check(mode == Mode.INITIALIZED || mode == Mode.DOZE_AOD ? Float.valueOf(0f).equals(value) : value == null);
        }
        for (String key:new String[]{AmbientSettings.WALLPAPER_ENABLED,AmbientSettings.ALWAYS_ON}) {
            Secure.values.put(key,0);
            check(!invoke("emit",mapper,new Triple(),pending).done);
            check(Boolean.TRUE.equals(invoke("setInAmbientMode",service,true,0L).args[0]));
            brightness.mState=Mode.DOZE_AOD;
            check(!invoke("clampToDimBrightnessForScreenOff",brightness,.45f).done);
            Secure.values.put(AmbientSettings.DIM_ENABLED,1);
            invoke("prepare",aod,ScrimState.LOCK);
            check(aod.mBehindAlpha == .25f);
            Secure.values.put(key,1);
        }
        AodPanelBrightness.install(PortTest.class.getClassLoader());
        com.android.systemui.doze.DozeService dream = new com.android.systemui.doze.DozeService();
        dream.mDozeMachine.mState=Mode.DOZE_AOD;
        for (int screen : new int[]{3,4}) {
            check(de.robv.android.xposed.Frame.send(dream,screen,0f,false)==.025f);
            check(de.robv.android.xposed.Frame.send(dream,screen,.08f,false)==.08f);
            check(de.robv.android.xposed.Frame.send(dream,screen,-1f,false)==-1f);
            check(Float.isNaN(de.robv.android.xposed.Frame.send(dream,screen,Float.NaN,false)));
            check(de.robv.android.xposed.Frame.send(dream,screen,0f,true)==0f);
        }
        for (int screen:new int[]{0,1,2,5,6}) check(de.robv.android.xposed.Frame.send(dream,screen,0f,false)==0f);
        for (Mode mode:new Mode[]{Mode.FINISH,Mode.INITIALIZED,Mode.DOZE_PULSING,Mode.DOZE_PULSING_BRIGHT}) {
            dream.mDozeMachine.mState=mode;
            check(de.robv.android.xposed.Frame.send(dream,3,0f,false)==0f);
        }
        dream.mDozeMachine.mState=Mode.DOZE_AOD;
        for (String key:new String[]{AmbientSettings.GAIN_ENABLED,AmbientSettings.ALWAYS_ON,AmbientSettings.WALLPAPER_ENABLED}) {
            Secure.values.put(key,0);check(de.robv.android.xposed.Frame.send(dream,3,0f,false)==0f);Secure.values.put(key,1);
        }
        dream.power.interactive=true;check(de.robv.android.xposed.Frame.send(dream,3,0f,false)==0f);dream.power.interactive=false;
        dream.display=1;check(de.robv.android.xposed.Frame.send(dream,3,0f,false)==0f);dream.display=0;
        dream.foreground=false;check(de.robv.android.xposed.Frame.send(dream,3,0f,false)==0f);
        check(de.robv.android.xposed.Frame.send(new android.service.dreams.DreamService(),3,0f,false)==0f);
        check((Float)invoke("startDozingOneway",null,new android.os.IBinder(){},3,0,0f,false).args[3]==0f);
        dream.foreground=true;
        open(dream);
        check(send(dream,2,0f,false)==0f);
        dream.power.reason=android.os.PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;
        open(dream);
        dream.power.interactive=true;check(send(dream,2,0f,false)==0f);dream.power.interactive=false;
        for (String key:new String[]{AmbientSettings.GAIN_ENABLED,AmbientSettings.ALWAYS_ON,AmbientSettings.WALLPAPER_ENABLED}) {
            Secure.values.put(key,0);check(send(dream,2,0f,false)==0f);Secure.values.put(key,1);
        }
        check(send(dream,2,0f,true)==0f);
        check(send(dream,2,.08f,false)==.08f);
        check(send(dream,2,0f,false)==.025f);
        transition(dream,Mode.DOZE_PULSING);
        check(dream.updates==1 && send(dream,2,0f,false)==0f);
        transition(dream,Mode.DOZE_AOD);
        check(send(dream,2,0f,false)==0f);
        for (int screen:new int[]{1,3,4}) {
            open(dream);
            check(send(dream,2,0f,false)==.025f);
            check(send(dream,screen,0f,false)==(screen==1?0f:.025f));
            check(send(dream,2,0f,false)==0f);
        }
        System.out.println("PASS: "+checks+" upstream and AOD panel assertions");
    }
}
