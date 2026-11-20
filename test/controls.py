from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
fixtures = {
'android.os.Bundle': '''public class Bundle extends java.util.HashMap<String,Object>{public void putString(String k,String v){put(k,v);}public String getString(String k,String d){return (String)getOrDefault(k,d);}public void putBoolean(String k,boolean b){put(k,b);}public boolean flag(String k){return Boolean.TRUE.equals(get(k));}}''',
'android.os.SystemClock':'public class SystemClock {public static long elapsedRealtime(){return 100;}}',
'android.os.Parcel':'public class Parcel {ResultReceiver r;public static Parcel obtain(){return new Parcel();}public void setDataPosition(int p){}public void recycle(){}}',
'android.os.ResultReceiver':'''public class ResultReceiver{public ResultReceiver(Object h){}protected void onReceiveResult(int c,Bundle data){}public void send(int c,Bundle d){onReceiveResult(c,d);}public void writeToParcel(Parcel p,int f){p.r=this;}public static class Creator{public ResultReceiver createFromParcel(Parcel p){return p.r;}}public static Creator CREATOR=new Creator();}''',
'android.os.UserHandle':'public class UserHandle {int user;public static UserHandle getUserHandleForUid(int uid){UserHandle u=new UserHandle();u.user=uid/100000;return u;}public boolean equals(Object other){return other instanceof UserHandle&&((UserHandle)other).user==user;}}',
'android.os.Process':'public class Process {public static UserHandle myUserHandle(){return UserHandle.getUserHandleForUid(1000);}}',
'android.util.Log':'public class Log {public static int w(String tag,String msg,Throwable t){return 0;}}',
'android.app.BroadcastOptions':'public class BroadcastOptions{android.os.Bundle opts=new android.os.Bundle();public static BroadcastOptions makeBasic(){return new BroadcastOptions();}public void setShareIdentityEnabled(boolean b){opts.putBoolean("id",b);}public android.os.Bundle toBundle(){return opts;}}',
'android.content.Intent':'''public class Intent {public static final int FLAG_RECEIVER_REGISTERED_ONLY=1,FLAG_RECEIVER_FOREGROUND=2;public String action,target;java.util.Map<String,Object> values=new java.util.HashMap<>();public Intent(String a){action=a;}public Intent setPackage(String p){target=p;return this;}public String getAction(){return action;}public Intent addFlags(int n){return this;}public Intent putExtra(String k,Object v){values.put(k,v);return this;}public boolean hasExtra(String k){return values.containsKey(k);}public String getStringExtra(String k){return (String)values.get(k);}public boolean getBooleanExtra(String k,boolean b){return (boolean)values.getOrDefault(k,b);}public long getLongExtra(String k,long d){return (long)values.getOrDefault(k,d);}public <T>T getParcelableExtra(String k,Class<T> c){return c.cast(values.get(k));}}''',
'android.content.IntentFilter':'public class IntentFilter{String a;public IntentFilter(String s){a=s;}}',
'android.content.BroadcastReceiver':'public abstract class BroadcastReceiver{public static String caller;public static int uid=10123;public String getSentFromPackage(){return caller;}public int getSentFromUid(){return uid;}public abstract void onReceive(Context c,Intent i);}',
'android.content.Context':'''public class Context{public static final int RECEIVER_EXPORTED=2;public String name;public boolean signed=true;public static Context host;public static BroadcastReceiver receiver;public static String permission;public Context(String n){name=n;}public String getPackageName(){return name;}public Context getContentResolver(){return this;}public void registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Object handler,int flags){receiver=r;permission=p;host=this;}public void sendBroadcast(Intent i,String ignored,android.os.Bundle opts){if(!signed || receiver==null || !host.name.equals(i.target))return;BroadcastReceiver.caller=opts.flag("id")?name:null;receiver.onReceive(host,i);}}''',
'android.app.Application':'public class Application extends android.content.Context{public Application(String n){super(n);}}',
'android.app.Instrumentation':'public class Instrumentation {public void callApplicationOnCreate(Application app){}}',
'android.provider.Settings':'''public class Settings{public static class Secure{public static final java.util.Map<String,String> values=new java.util.HashMap<>();public static boolean reject;public static String getString(android.content.Context c,String k){if(!c.name.equals("com.android.systemui"))throw new SecurityException();return values.get(k);}public static boolean putString(android.content.Context c,String k,String v){if(!c.name.equals("com.android.systemui"))throw new SecurityException();if(reject)return false;if(v==null)values.remove(k);else values.put(k,v);return true;}}}''',
'dev.pixelambient.aod.AmbientSettings':'class AmbientSettings{static final String WALLPAPER_ENABLED="wall",ALWAYS_ON="clock",DIM_ENABLED="shade",GAIN_ENABLED="gain";}',
'dev.pixelambient.aod.AmbientAodModule':'class AmbientAodModule{static void log(String msg){}}',
'dev.pixelambient.aod.AodTimeout':'class AodTimeout {static boolean ready=true;}',
'dev.pixelambient.aod.AodSystemUiPort':'class AodSystemUiPort {static boolean ready=true;}',
'de.robv.android.xposed.XC_MethodHook':'public abstract class XC_MethodHook{public static class MethodHookParam{public Object[] args;public boolean hasThrowable(){return false;}}protected void afterHookedMethod(MethodHookParam p){}}',
'de.robv.android.xposed.XposedBridge':'public class XposedBridge {public static XC_MethodHook last;public static void hookMethod(java.lang.reflect.Method m,XC_MethodHook h){last=h;}public static void start(android.app.Application a){XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{a};last.afterHookedMethod(p);}}',
}
with tempfile.TemporaryDirectory() as tmp:
    temp = Path(tmp)
    files = []
    for name, body in fixtures.items():
        file = temp / (name.replace('.', '/') + '.java')
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf8')
        files.append(file)
    prod = root / 'pixel-suite/src/dev/pixelambient/aod'
    subprocess.run([java, '-jar', tools / 'ecj.jar', '-8', '-nowarn', '-proc:none', '-d', temp, *files, *[prod / f for f in ('AodControls.java','AodControlsHost.java','AodPreferences.java')],root / 'test/ControlsTest.java'],check=True)
    subprocess.run([java,'-cp',temp,'dev.pixelambient.aod.ControlsTest'],check=True)
