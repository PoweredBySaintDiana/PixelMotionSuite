from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
names = ('AodSystemUiPort', 'AodWallpaperServicePort', 'AmbientSettings', 'AodPanelBrightness', 'AodWindow')
mapper = 'WallpaperRepositoryImpl$special$$inlined$map$1$2'
fixtures = {
    'android.content.Context': 'public class Context {public boolean foreground=true;public int user=0,display=0;public int getDisplayId(){return display;}public android.os.PowerManager power=new android.os.PowerManager();public int getUserId(){return user;} public <T>T getSystemService(Class<T> type){return type.cast(type==android.os.PowerManager.class?power:new android.os.UserManager(foreground));} public Context getContentResolver(){return this;}}',
    'android.os.IBinder': 'public interface IBinder {}',
    'android.os.PowerManager': 'public class PowerManager {public static final int GO_TO_SLEEP_REASON_POWER_BUTTON=4;public int reason;public boolean interactive;public int getLastSleepReason(){return reason;}public boolean isInteractive(){return interactive;}}',
    'android.view.Display': 'public class Display {public static final int STATE_OFF=1,STATE_ON=2,STATE_DOZE=3,STATE_DOZE_SUSPEND=4,DEFAULT_DISPLAY=0;}',
    'android.service.dreams.DreamService':'public class DreamService extends android.content.Context {public int updates;private void updateDoze(){updates++;}}',
    'android.service.dreams.IDreamManager':'public interface IDreamManager {class Stub {public static class Proxy {public void startDozingOneway(android.os.IBinder token,int screen,int reason,float brightness,boolean normal){}}}}',
    'com.android.systemui.doze.DozeService':'public class DozeService extends android.service.dreams.DreamService {public static class Machine {public Enum<?> mState;public void transitionTo(Enum<?> target,int reason){mState=target;}}public Machine mDozeMachine=new Machine();}',
    'de.robv.android.xposed.Frame':'public class Frame {public static float send(Object service,int display,float brightness,boolean normal)throws Throwable {XC_MethodHook hook=XposedBridge.hooks.get("updateDoze");XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.thisObject=service;hook.beforeHookedMethod(param);try{return (Float)XposedBridge.invoke("startDozingOneway",null,new android.os.IBinder(){},display,0,brightness,normal).args[3];}finally{hook.afterHookedMethod(param);}}}',
    'android.os.UserManager': 'public class UserManager {boolean foreground;public UserManager(boolean value){foreground=value;}public boolean isUserForeground(){return foreground;}}',
    'android.provider.Settings': 'public class Settings {public static class Secure{ public static java.util.Map<String,Integer> values=new java.util.HashMap<>(); public static int getInt(android.content.Context host,String key,int fallback){return values.getOrDefault(key,fallback);}}}',
    'dev.pixelambient.aod.AodTimeout': 'class AodTimeout {static boolean ready=true;}',
    'dev.pixelambient.aod.AodControlsHost': 'class AodControlsHost {static android.content.Context context;}',
    'dev.pixelambient.aod.AmbientAodModule': 'class AmbientAodModule {static void log(String msg){System.out.println(msg);}}',
    'kotlin.Triple': 'public class Triple {}',
    'kotlin.coroutines.Continuation': 'public class Continuation {}',
    'kotlinx.coroutines.flow.FlowCollector': 'public interface FlowCollector {Object emit(Object value,kotlin.coroutines.Continuation continuation);}',
    'com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl': 'public class WallpaperRepositoryImpl {public android.content.Context context;}',
    'com.android.systemui.wallpapers.data.repository.' + mapper: 'public class '+mapper+' {public int $r8$classId;public WallpaperRepositoryImpl this$0;public kotlinx.coroutines.flow.FlowCollector $this_unsafeFlow;public Object emit(Object data,kotlin.coroutines.Continuation continuation){return null;}}',
    'com.android.systemui.statusbar.phone.ScrimState': 'public enum ScrimState { AOD {public void prepare(ScrimState old){mBehindAlpha=0f;}}, LOCK;public float mBehindAlpha;public void prepare(ScrimState old){}}',
    'com.android.systemui.doze.DozeScreenBrightness': 'public class DozeScreenBrightness {public Enum<?> mState;public float clampToDimBrightnessForScreenOff(float value){return value;}}',
    'com.android.server.wallpaper.WallpaperManagerService': 'public class WallpaperManagerService {public android.content.Context mContext;public int mCurrentUserId;public void setInAmbientMode(boolean ambient,long duration){}}',
    'de.robv.android.xposed.XC_MethodHook': '''public class XC_MethodHook {public static class MethodHookParam {public Object thisObject,result;public Object[] args;public Throwable thrown;public boolean done;public void setResult(Object value){result=value;done=true;}public boolean hasThrowable(){return thrown!=null;}public void setThrowable(Throwable error){thrown=error;done=true;}}protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}}''',
    'de.robv.android.xposed.XposedBridge': '''import java.lang.reflect.Method;import java.util.*;public class XposedBridge {public static Map<String,XC_MethodHook> hooks=new HashMap<>();public static void hookMethod(Method m,XC_MethodHook h){hooks.put(m.getName(),h);}public static XC_MethodHook.MethodHookParam invoke(String name,Object obj,Object... args)throws Throwable{XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=obj;p.args=args;XC_MethodHook hook=hooks.get(name);hook.beforeHookedMethod(p);hook.afterHookedMethod(p);return p;}}''',
}

with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    files = []
    for name, body in fixtures.items():
        path = root / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package '+name.rsplit('.',1)[0]+';\n'+body, encoding='utf8')
        files.append(path)
    project = here.parent / 'pixel-suite/src/dev/pixelambient/aod'
    files.extend(project / (name+'.java') for name in names)
    subprocess.run([java, '-jar', tools/'ecj.jar', '-8', '-proc:none', '-nowarn', '-d', root, *files, here/'PortTest.java'], check=True)
    subprocess.run([java, '-cp', root, 'dev.pixelambient.aod.PortTest'], check=True)
