import pathlib
import subprocess
import sys
import tempfile

here = pathlib.Path(__file__).resolve().parent
prod = here.parent / 'pixel-suite/src/dev/pixelambient/aod'
tools = pathlib.Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
fixtures = {
    'android.os.SystemClock': 'public class SystemClock {public static long now; public static long elapsedRealtime(){return now;}}',
    'android.os.Looper': 'public class Looper {public static Looper getMainLooper(){return null;}}',
    'android.os.Handler': 'public class Handler {public Handler(Looper x){}public boolean postDelayed(Runnable r,long t){return true;}public void removeCallbacks(Runnable r){}}',
    'android.os.PowerManager': 'public class PowerManager {public static final int PARTIAL_WAKE_LOCK=1;public boolean interactive;public boolean isInteractive(){return interactive;}public WakeLock newWakeLock(int flags,String name){return new WakeLock();}public class WakeLock{public boolean isHeld(){return false;}public void acquire(long t){}public void release(){}}}',
    'android.app.AlarmManager': 'public class AlarmManager {public static final int ELAPSED_REALTIME_WAKEUP=2;public interface OnAlarmListener{void onAlarm();} public long at;public int count;public OnAlarmListener listener;public void setExact(int type,long when,String name,OnAlarmListener who,android.os.Handler h){at=when;listener=who;count++;}public void cancel(OnAlarmListener l){if(listener==l)listener=null;}}',
    'android.view.Display': 'public class Display {public static final int STATE_UNKNOWN=0,STATE_OFF=1,STATE_DOZE=3,STATE_DOZE_SUSPEND=4,DEFAULT_DISPLAY=0;public int state=2;public int getState(){return state;}}',
    'android.hardware.display.DisplayManager': 'public class DisplayManager {public interface DisplayListener{void onDisplayChanged(int id);void onDisplayAdded(int id);void onDisplayRemoved(int id);}public DisplayListener listener;public android.view.Display d=new android.view.Display();public android.view.Display getDisplay(int id){return d;}public void registerDisplayListener(DisplayListener l,android.os.Handler h){listener=l;}public void unregisterDisplayListener(DisplayListener l){listener=null;}public void show(int state){d.state=state;listener.onDisplayChanged(0);}}',
    'android.service.dreams.DreamService': 'public class DreamService {public java.util.Map<Class<?>,Object> services=new java.util.HashMap<>();public <T>T getSystemService(Class<T> c){try{if(!services.containsKey(c))services.put(c,c.newInstance());return c.cast(services.get(c));}catch(Exception e){throw new AssertionError(e);}}}',
    'dev.pixelambient.aod.AmbientSettings': 'class AmbientSettings {static final String WALLPAPER_ENABLED="wall",ALWAYS_ON="clock",DIM_ENABLED="shade",GAIN_ENABLED="gain";static boolean on=true;static boolean isEnabled(android.service.dreams.DreamService service){return on;}}',
    'dev.pixelambient.aod.AmbientAodModule': 'class AmbientAodModule {static void log(String msg){System.out.println(msg);}}',
    'de.robv.android.xposed.XC_MethodHook': 'public abstract class XC_MethodHook {protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}public static class MethodHookParam {public Object thisObject;public Object[] args;public boolean hasThrowable(){return false;}}}',
    'de.robv.android.xposed.XposedBridge': '''import java.lang.reflect.*;import java.util.*;public class XposedBridge {static Map<Method,XC_MethodHook> hooks=new HashMap<>();public static void hookMethod(Method m,XC_MethodHook h){hooks.put(m,h);}public static void fire(Object obj,Method m,Object[] args,java.util.function.Consumer<Object[]> body){try{XC_MethodHook hook=hooks.get(m);XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=obj;p.args=args;hook.beforeHookedMethod(p);body.accept(args);hook.afterHookedMethod(p);}catch(Throwable e){throw new AssertionError(e);}}}''',
    'com.android.systemui.doze.DozeMachine': '''public class DozeMachine {public enum State{DOZE,DOZE_AOD,DOZE_AOD_PAUSING,DOZE_REQUEST_PULSE,DOZE_PULSING,FINISH}public State mState=State.DOZE;public void transitionTo(State s,int reason){}public void requestState(State s){requestState(s,-1);}public void requestState(State s,int reason){try{de.robv.android.xposed.XposedBridge.fire(this,getClass().getMethod("transitionTo",State.class,int.class),new Object[]{s,reason},a->mState=(State)a[0]);}catch(Exception e){throw new AssertionError(e);}}}''',
    'com.android.systemui.doze.DozeService': '''public class DozeService extends android.service.dreams.DreamService {public DozeMachine mDozeMachine=new DozeMachine();public void onDreamingStarted(){}public void onDreamingStopped(){}public void onDestroy(){}public void start()throws Exception{de.robv.android.xposed.XposedBridge.fire(this,getClass().getMethod("onDreamingStarted"),new Object[]{},a->{});}public void stop()throws Exception{de.robv.android.xposed.XposedBridge.fire(this,getClass().getMethod("onDreamingStopped"),new Object[]{},a->{});}}''',
}
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    files = []
    for name, body in fixtures.items():
        file = root / (name.replace('.', '/') + '.java')
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf8')
        files.append(file)
    files += [prod / (name + '.java') for name in ['AodWindow', 'AodTimeout', 'AodPreferences']]
    subprocess.run([java, '-jar', tools / 'ecj.jar', '-8', '-nowarn', '-proc:none', '-d', root, *files, here / 'SessionTest.java'], check=True)
    subprocess.run([java, '-cp', root, 'dev.pixelambient.aod.SessionTest'], check=True)
