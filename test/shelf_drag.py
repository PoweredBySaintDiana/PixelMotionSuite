from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
source = here.parent / 'pixel-suite/src/dev/pixelmotion/a10/recents'
fixtures = {
    'android.content.res.Configuration': 'public class Configuration {public static final int ORIENTATION_PORTRAIT=1;public int orientation=1;}',
    'android.content.res.Resources': 'public class Resources {public final Configuration config=new Configuration();public Configuration getConfiguration(){return config;}}',
    'android.graphics.PointF': 'public class PointF {public float x,y;}',
    'android.view.InputDevice': 'public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}',
    'android.view.Display': 'public class Display {public static final int DEFAULT_DISPLAY=0;public int getDisplayId(){return 0;}}',
    'android.view.MotionEvent': 'public class MotionEvent {public static final int ACTION_MOVE=2;public float x,y;public int getActionMasked(){return 2;}public int getPointerCount(){return 1;}public boolean isFromSource(int source){return true;}public float getX(){return x;}public float getY(){return y;}}',
    'android.view.View': 'public class View {public static final int LAYOUT_DIRECTION_LTR=0;public int getLayoutDirection(){return 0;}public Display getDisplay(){return new Display();}public final android.content.res.Resources res=new android.content.res.Resources();public android.content.res.Resources getResources(){return res;}}',
    'de.robv.android.xposed.XC_MethodHook': 'public class XC_MethodHook {public static class MethodHookParam {public Object thisObject;public Object[] args;}protected void beforeHookedMethod(MethodHookParam param)throws Throwable{}}',
    'de.robv.android.xposed.XposedBridge': 'public class XposedBridge {static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();public static void hookMethod(java.lang.reflect.Method method,XC_MethodHook hook){hooks.put(method.getName(),hook);}public static void before(String name,Object host,Object... args)throws Throwable{XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.args=args;param.thisObject=host;hooks.get(name).beforeHookedMethod(param);}public static void log(String msg){throw new AssertionError(msg);}}',
    'com.android.quickstep.views.RecentsView': 'public class RecentsView extends android.view.View {public static final Setter ADJACENT_PAGE_HORIZONTAL_OFFSET=new Setter();public static class Setter {public void setValue(RecentsView view,float value)throws Throwable{Object[] args={view,value};de.robv.android.xposed.XposedBridge.before("setValue",this,args);view.mAdjacentPageHorizontalOffset=(Float)args[1];}} public float mAdjacentPageHorizontalOffset;public int running,next;public int getNextPage(){return next;}public int getRunningTaskIndex(){return running;}}',
    'com.android.quickstep.GestureState': 'public class GestureState {public Object getEndTarget(){return null;}}',
    'com.android.quickstep.AbsSwipeUpHandler': 'public class AbsSwipeUpHandler {public GestureState mGestureState=new GestureState();public com.android.quickstep.views.RecentsView mRecentsView=new com.android.quickstep.views.RecentsView();public boolean mIsMotionPaused,mIsLikelyToStartNewTask,mContinuingLastGesture;public Nav mDeviceState=new Nav();public static class Nav {public boolean isFullyGesturalNavMode(){return true;}}}',
    'com.android.quickstep.inputconsumers.OtherActivityInputConsumer': 'public class OtherActivityInputConsumer {public com.android.quickstep.AbsSwipeUpHandler mInteractionHandler=new com.android.quickstep.AbsSwipeUpHandler();public android.graphics.PointF mDownPos=new android.graphics.PointF();public float mTouchSlop=10;public void onMotionEvent(android.view.MotionEvent event){}}',
}
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    paths = []
    for name, body in fixtures.items():
        path = root / (name.replace('.', '/')+'.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package '+name.rsplit('.',1)[0]+';\n'+body, encoding='utf-8')
        paths.append(path)
    subprocess.run([java,'-jar',tools/'ecj.jar','-8','-proc:none','-nowarn','-d',root,
                    *paths,source/'TaskShelf.java',source/'Reflect.java',source/'ShelfDrag.java',here/'ShelfDragTest.java'],check=True)
    subprocess.run([java,'-cp',root,'dev.pixelmotion.a10.recents.ShelfDragTest'],check=True)
