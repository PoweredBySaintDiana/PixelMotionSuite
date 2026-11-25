from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
source = here.parent / 'pixel-suite/src/dev/pixelmotion/a11/motion'
fixtures = {
    'android.view.View': 'public class View {public Object getWindowToken(){return this;}}',
    'android.view.MotionEvent': 'public class MotionEvent {public static final int ACTION_DOWN=0;public int action;public MotionEvent(int a){action=a;}public int getActionMasked(){return action;}}',
    'android.util.FloatProperty': 'public abstract class FloatProperty<T>{public FloatProperty(String name){}public abstract Float get(T target);public abstract void setValue(T target,float value);}',
    'android.animation.Animator': 'public class Animator {}',
    'android.animation.AnimatorListenerAdapter': 'public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}',
    'android.animation.AnimatorSet': '''public class AnimatorSet extends Animator {
        java.util.List<AnimatorListenerAdapter> callbacks=new java.util.ArrayList<>();
        public void addListener(AnimatorListenerAdapter c){callbacks.add(c);}
        public void end(){for(AnimatorListenerAdapter c:callbacks)c.onAnimationEnd(this);}
    }''',
    'de.robv.android.xposed.XC_MethodHook': '''public class XC_MethodHook {
        public static class MethodHookParam {public Object thisObject;public Object[] args;public Object result;
            java.util.Map<String,Object> extras=new java.util.HashMap<>();
            public Object getResult(){return result;}public void setResult(Object r){result=r;}
            public boolean hasThrowable(){return false;}public Object getObjectExtra(String k){return extras.get(k);}
            public void setObjectExtra(String k,Object v){extras.put(k,v);}}
        protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
        protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
    }''',
    'de.robv.android.xposed.XposedBridge': '''public class XposedBridge {
        static java.util.Map<java.lang.reflect.Method,XC_MethodHook> hooks=new java.util.HashMap<>();
        public static void hookMethod(java.lang.reflect.Method m,XC_MethodHook h){hooks.put(m,h);}
        public static XC_MethodHook.MethodHookParam fire(Object o,String name,Class<?>[] types,Object... args)throws Throwable {
            java.lang.reflect.Method m=o.getClass().getMethod(name,types);XC_MethodHook h=hooks.get(m);
            XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=o;p.args=args;
            h.beforeHookedMethod(p);h.afterHookedMethod(p);return p;}
    }''',
    'dev.pixelmotion.a11.motion.Motion': '''class Motion {
        static boolean onUiThread(){return true;}static int settled;
        static void log(String message){}
        static void continueWorkspace(Object owner){settled++;}
        static Class<?> load(String name,ClassLoader loader)throws ClassNotFoundException{return loader.loadClass(name);}
        static java.lang.reflect.Field field(Class<?> c,String name)throws NoSuchFieldException{
            java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
        static java.lang.reflect.Method method(Class<?> c,String name,Class<?>... args)throws NoSuchMethodException{
            java.lang.reflect.Method m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    }''',
    'com.android.launcher3.LauncherState': '''public class LauncherState extends com.android.launcher3.statemanager.BaseState {
        public static final LauncherState NORMAL=new LauncherState(),BACKGROUND_APP=new LauncherState(),OVERVIEW=new LauncherState();
    }''',
    'com.android.launcher3.statemanager.BaseState': 'public class BaseState {}',
    'com.android.launcher3.statemanager.StateManager': 'public class StateManager {public Object mState=com.android.launcher3.LauncherState.NORMAL;}',
    'com.android.launcher3.Launcher': '''public class Launcher {
        public com.android.launcher3.statemanager.StateManager mStateManager=new com.android.launcher3.statemanager.StateManager();
        public android.view.View mWorkspace=new android.view.View(),mHotseat=new android.view.View();
        public void onStateSetStart(com.android.launcher3.statemanager.BaseState state){}
    }''',
    'com.android.launcher3.uioverrides.QuickstepLauncher': 'public class QuickstepLauncher extends com.android.launcher3.Launcher {}',
    'com.android.launcher3.AbstractFloatingView': '''public class AbstractFloatingView extends android.view.View {
        protected boolean mIsOpen=true;
        public boolean isOpen(){return mIsOpen;}
    }''',
    'com.android.launcher3.model.data.ItemInfo': 'public class ItemInfo {}',
    'com.android.launcher3.QuickstepTransitionManager': '''public class QuickstepTransitionManager {
        public Launcher mLauncher;public void getActivityLaunchOptions(android.view.View v,com.android.launcher3.model.data.ItemInfo item){}
    }''',
    'com.android.launcher3.views.FloatingIconView': '''public class FloatingIconView extends android.view.View {
        public boolean mIsOpening;public Runnable mEndRunnable=()->{};public com.android.launcher3.Launcher mLauncher;
        public int finishes;public void fastFinish(){finishes++;mEndRunnable=null;}public void recycle(){}
    }''',
    'com.android.launcher3.views.FloatingIconView$$ExternalSyntheticLambda4': '''public class FloatingIconView$$ExternalSyntheticLambda4 implements Runnable {
        public FloatingIconView c;public void run(){c.fastFinish();}
    }''',
    'com.android.launcher3.views.ListenerView': '''public class ListenerView extends com.android.launcher3.AbstractFloatingView {
        public Runnable mCloseListener;public boolean onControllerInterceptTouchEvent(android.view.MotionEvent e){return false;}
    }''',
    'com.android.launcher3.anim.AnimatedPropertySetter': 'public class AnimatedPropertySetter {public android.animation.AnimatorSet mAnim=new android.animation.AnimatorSet();}',
    'com.android.launcher3.anim.PendingAnimation': 'public class PendingAnimation extends AnimatedPropertySetter {}',
    'com.android.quickstep.util.ScalingWorkspaceRevealAnim': '''public class ScalingWorkspaceRevealAnim {
        public com.android.launcher3.uioverrides.QuickstepLauncher launcher;
        public com.android.launcher3.anim.PendingAnimation animation=new com.android.launcher3.anim.PendingAnimation();
        public void start(){}
    }''',
    'com.android.launcher3.LauncherAnimUtils': '''public class LauncherAnimUtils {
        public static final Factory WORKSPACE_SCALE_PROPERTY_FACTORY=new Factory(),HOTSEAT_SCALE_PROPERTY_FACTORY=new Factory();
        public static class Factory {
            public java.util.Map<Integer,Scale> scales=new java.util.HashMap<>();
            public android.util.FloatProperty get(Integer channel){return scales.computeIfAbsent(channel,c->new Scale());}
        }
        public static class Scale extends android.util.FloatProperty<android.view.View>{
            java.util.Map<android.view.View,Float> values=new java.util.HashMap<>();
            public Scale(){super("scale");}public Float get(android.view.View v){return values.getOrDefault(v,1f);}
            public void setValue(android.view.View v,float f){values.put(v,f);}
        }
    }''',
}
with tempfile.TemporaryDirectory() as directory:
    output = Path(directory)
    paths = []
    for name, body in fixtures.items():
        path = output / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf-8')
        paths.append(path)
    subprocess.run([java, '-jar', tools/'ecj.jar', '-8', '-proc:none', '-nowarn', '-d', output,
                    *paths, source/'HomeInterruption.java', source/'RevealScaleCleanup.java',
                    here/'HomeInterruptionTest.java'], check=True)
    subprocess.run([java, '-cp', output, 'dev.pixelmotion.a11.motion.HomeInterruptionTest'], check=True)
