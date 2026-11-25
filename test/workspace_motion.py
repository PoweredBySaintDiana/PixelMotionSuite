from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
source = here.parent / 'pixel-suite/src/dev/pixelmotion/a11/motion'
fixtures = {
    'android.content.Context': 'public class Context {}',
    'android.util.FloatProperty': '''public abstract class FloatProperty<T> {
        public FloatProperty(String name) {} public abstract Float get(T target);
        public abstract void setValue(T target, float value);
    }''',
    'android.view.View': '''public class View {
        public float y; public float getTranslationY(){return y;}
        public void setTranslationY(float next){
            Object[] args={next};
            de.robv.android.xposed.XposedBridge.before(this,"setTranslationY",args);
            y=(Float)args[0];
        }
    }''',
    'android.animation.AnimatorListenerAdapter': '''public class AnimatorListenerAdapter {
        public void onAnimationEnd(Animator a){} public void onAnimationCancel(Animator a){}
    }''',
    'android.animation.Animator': '''public class Animator {
        public boolean started; public long delay;
        public java.util.List<AnimatorListenerAdapter> listeners=new java.util.ArrayList<>();
        public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
        public boolean isStarted(){return started;} public boolean isRunning(){return started;}
        public void setStartDelay(long ms){delay=ms;}
        public void start(){started=true;}
        public void cancel(){started=false;for(AnimatorListenerAdapter l:listeners)l.onAnimationCancel(this);
            for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}
        public void end(){started=false;for(AnimatorListenerAdapter l:listeners)l.onAnimationEnd(this);}
    }''',
    'android.animation.AnimatorSet': '''public class AnimatorSet extends Animator {
        public static AnimatorSet latest;
        public java.util.ArrayList<Animator> children=new java.util.ArrayList<>();
        public void play(Animator a){children.add(a);}
        public java.util.ArrayList<Animator> getChildAnimations(){return children;}
        public void start(){latest=this;super.start();for(Animator a:children)a.start();}
        public void cancel(){for(Animator a:children)a.cancel();super.cancel();}
        public void end(){for(Animator a:children)a.end();super.end();}
    }''',
    'android.animation.ValueAnimator': '''public class ValueAnimator extends Animator {
        public Object target; public android.util.FloatProperty property;
        public float initial, destination, velocity; public long time;
        public long getCurrentPlayTime(){return time;}
        public void sample(long ms){
            time=ms;double t=ms/1000.0, decay=.7f*Math.sqrt(150), w=Math.sqrt(150*(1-.7f*.7f));
            double a=initial-destination,b=(velocity+decay*a)/w;
            property.setValue(target,(float)(destination+Math.exp(-decay*t)*(a*Math.cos(w*t)+b*Math.sin(w*t))));
        }
        public void end(){property.setValue(target,destination);super.end();}
    }''',
    'com.android.launcher3.anim.SpringAnimationBuilder': '''public class SpringAnimationBuilder {
        public float mStartValue,mEndValue,mVelocity;
        public SpringAnimationBuilder(android.content.Context c){}
        public SpringAnimationBuilder setStiffness(float f){return this;}
        public SpringAnimationBuilder setDampingRatio(float f){return this;}
        public SpringAnimationBuilder setMinimumVisibleChange(float f){return this;}
        public android.animation.ValueAnimator build(Object target,android.util.FloatProperty p){
            android.animation.ValueAnimator a=new android.animation.ValueAnimator();
            a.target=target;a.property=p;a.initial=mStartValue;a.destination=mEndValue;a.velocity=mVelocity;return a;
        }
    }''',
    'dev.pixelmotion.a11.motion.Motion': '''class Motion {
        static boolean onUiThread(){return true;} static void log(String s){}
        static Class<?> load(String name,ClassLoader loader)throws ClassNotFoundException{return loader.loadClass(name);}
        static java.lang.reflect.Field field(Class<?> c,String name)throws NoSuchFieldException{
            java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
        static java.lang.reflect.Method method(Class<?> c,String name,Class<?>... args)throws NoSuchMethodException{
            java.lang.reflect.Method m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    }''',
    'de.robv.android.xposed.XC_MethodHook': '''public class XC_MethodHook {
        public static class MethodHookParam{public Object thisObject; public Object[] args;}
        protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
    }''',
    'de.robv.android.xposed.XposedBridge': '''public class XposedBridge {
        static XC_MethodHook callback;
        public static void hookMethod(java.lang.reflect.Method m,XC_MethodHook hook){callback=hook;}
        public static void before(Object view,String method,Object[] args){
            XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=view;p.args=args;
            try{if(callback!=null)callback.beforeHookedMethod(p);}catch(Throwable e){throw new AssertionError(e);}
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
                    *paths, source/'WorkspaceMotion.java', here/'WorkspaceMotionTest.java'], check=True)
    subprocess.run([java, '-cp', output, 'dev.pixelmotion.a11.motion.WorkspaceMotionTest'], check=True)
