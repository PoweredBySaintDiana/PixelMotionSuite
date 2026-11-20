from pathlib import Path
import subprocess
import sys
import tempfile

here = Path(__file__).resolve().parent
tools = Path(sys.argv[1]).resolve()
java = next((tools / 'jre').rglob('java.exe'))
motion = here.parent / 'pixel-suite/src/dev/pixelmotion/a11/motion'
sources = [motion / name for name in ('OpeningIconSource.java', 'MotionCurve.java', 'OpeningTiming.java')]
fixtures = {
    'android.view.animation.Interpolator': 'public interface Interpolator {float getInterpolation(float fraction);}',
    'android.graphics.Rect': 'public class Rect {public int left,top,right,bottom;public Rect(int width,int height){right=width;bottom=height;}public boolean isEmpty(){return width()<=0||height()<=0;}public int width(){return right-left;}public int height(){return bottom-top;}}',
    'android.graphics.RectF': 'public class RectF {public float width(){return 96;}public float height(){return 96;}public boolean isEmpty(){return false;}}',
    'android.view.ViewParent': 'public interface ViewParent {ViewParent getParent();}',
    'android.view.WindowInsets': 'public class WindowInsets {public int getStableInsetBottom(){return 80;}}',
    'android.view.View': 'public class View implements ViewParent {public View parent; public int width=1080,height=2400;public View getParent(){return parent;}public View getRootView(){return parent==null?this:parent.getRootView();}public Object getWindowToken(){return this;}public WindowInsets getRootWindowInsets(){return new WindowInsets();}public int getWidth(){return width;}public int getHeight(){return height;}}',
    'dev.pixelmotion.a11.motion.Motion': 'class Motion {static void log(String message){throw new AssertionError(message);}}',
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
