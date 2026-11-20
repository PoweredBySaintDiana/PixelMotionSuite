from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
motion = here.parent / 'pixel-suite/src/dev/pixelmotion/a11/motion'
sources = [motion / name for name in ('OpeningContent.java', 'MotionCurve.java', 'OpeningTiming.java')]
fixtures = {
    'android.view.animation.Interpolator': 'public interface Interpolator {float getInterpolation(float fraction);}',
    'android.content.res.Resources': 'public class Resources {}',
    'android.graphics.Rect': 'public class Rect {public int left,top,right,bottom;public Rect(int width,int height){right=width;bottom=height;}public boolean isEmpty(){return width()<=0||height()<=0;}public int width(){return right-left;}public int height(){return bottom-top;}}',
    'android.graphics.RectF': 'public class RectF {public float width(){return 96;}public float height(){return 96;}public boolean isEmpty(){return false;}}',
    'android.view.ViewParent': 'public interface ViewParent {ViewParent getParent();}',
    'android.view.WindowInsets': 'public class WindowInsets {public int getStableInsetBottom(){return 80;}}',
    'android.view.View': 'public class View implements ViewParent {public View parent; public int width=1080,height=2400;public View getParent(){return parent;}public View getRootView(){return parent==null?this:parent.getRootView();}public Object getWindowToken(){return this;}public WindowInsets getRootWindowInsets(){return new WindowInsets();}public int getWidth(){return width;}public int getHeight(){return height;}}',
    'de.robv.android.xposed.XC_MethodHook': 'public class XC_MethodHook {public static class MethodHookParam {public Object[] args;public Object thisObject;public boolean hasThrowable(){return false;}}protected void afterHookedMethod(MethodHookParam call)throws Throwable{}}',
    'de.robv.android.xposed.XposedBridge': 'public class XposedBridge {static XC_MethodHook hook;public static void hookMethod(java.lang.reflect.Member member, XC_MethodHook callback){hook=callback;}public static void after(Object owner,Object... args)throws Throwable{XC_MethodHook.MethodHookParam call=new XC_MethodHook.MethodHookParam();call.thisObject=owner;call.args=args;hook.afterHookedMethod(call);}}',
    'dev.pixelmotion.a11.motion.Motion': 'class Motion {static Class<?> load(String name,ClassLoader loader)throws ClassNotFoundException{return loader.loadClass(name);}static java.lang.reflect.Field field(Class<?> owner,String name)throws NoSuchFieldException{java.lang.reflect.Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}static boolean onUiThread(){return true;}static void log(String message){}}',
    'com.android.launcher3.remoteanimations.AnimOpenProperties': '''public class AnimOpenProperties {public final int cropWidthStart,cropHeightStart,cropCenterXStart,cropCenterYStart,cropWidthEnd,cropHeightEnd;public AnimOpenProperties(android.content.res.Resources res,android.graphics.Rect target,android.graphics.RectF icon,android.view.View view,int dx,int dy,boolean splash,boolean themed){cropWidthStart=cropHeightStart=144+(dx-dy);cropCenterXStart=target.width()/2;cropCenterYStart=target.height()/2;cropWidthEnd=target.width();cropHeightEnd=target.height();}}''',
}
for view in ['BubbleTextView','Workspace','Hotseat','allapps.ActivityAllAppsContainerView']:
    fixtures['com.android.launcher3.'+view] = 'public class '+view.split('.')[-1]+' extends android.view.View {}'
with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    paths = []
    for name, body in fixtures.items():
        path = root / (name.replace('.', '/')+'.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package '+name.rsplit('.',1)[0]+';\n'+body, encoding='utf-8')
        paths.append(path)
    subprocess.run([java,'-jar',tools/'ecj.jar','-8','-proc:none','-nowarn','-d',root,*paths,*sources,here/'OpeningContentTest.java'],check=True)
    subprocess.run([java,'-cp',root,'dev.pixelmotion.a11.motion.OpeningContentTest'],check=True)
