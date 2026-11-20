package dev.pixelmotion.a11.motion;

import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

final class HomeSwipeProbe {
    private static final String TAG = "PixelFling239";
    private static final int LIMIT = 6;
    private static final int[] TIMES = {16, 48, 96};
    private final WeakHashMap<Object, Sample> gestures = new WeakHashMap<>();
    private final WeakHashMap<Object, Sample> animations = new WeakHashMap<>();
    private final Field interaction, down, handles, currentRect;
    private final Method simulator, windowRect;
    private int reported;

    static HomeSwipeProbe install(ClassLoader loader, Class<?> launcher) {
        try {
            return new HomeSwipeProbe(loader, launcher);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Left trace unavailable", error);
            return null;
        }
    }

    private HomeSwipeProbe(ClassLoader loader, Class<?> launcher) throws ReflectiveOperationException {
        Class<?> input = Motion.load("com.android.quickstep.inputconsumers.OtherActivityInputConsumer", loader);
        interaction = Motion.field(input, "mInteractionHandler");
        down = Motion.field(input, "mDownPos");
        handles = Motion.field(Motion.load("com.android.quickstep.SwipeUpAnimationLogic", loader), "mRemoteTargetHandles");
        simulator = Motion.method(handles.getType().getComponentType(), "getTaskViewSimulator");
        windowRect = Motion.method(simulator.getReturnType(), "getCurrentRect");
        currentRect = Motion.field(Motion.load("com.android.quickstep.util.RectFSpringAnim", loader), "mCurrentRect");
        XposedBridge.hookMethod(Motion.method(input, "onMotionEvent", MotionEvent.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                if (reported >= LIMIT || !Motion.onUiThread()) return;
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();
                if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || event.getPointerCount() != 1
                        || (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP)) return;
                Object swipe = interaction.get(param.thisObject);
                if (!launcher.isInstance(swipe)) return;
                Sample sample = gestures.get(swipe);
                if (sample == null) { sample = new Sample(); gestures.put(swipe, sample); }
                sample.finger = event.getX() - ((PointF) down.get(param.thisObject)).x;
                if (action != MotionEvent.ACTION_UP && event.getEventTime() - sample.lastSample < 24) return;
                sample.lastSample = event.getEventTime();
                Object[] targets = (Object[]) handles.get(swipe);
                if (targets == null || targets.length != 1 || targets[0] == null) return;
                try {
                    RectF bounds = (RectF) windowRect.invoke(simulator.invoke(targets[0]));
                    if (bounds == null || bounds.isEmpty() || !Float.isFinite(bounds.centerX())) return;
                    if (!Float.isFinite(sample.first)) {
                        sample.first = bounds.centerX(); sample.fingerAtFirst = sample.finger;
                    }
                    sample.last = bounds.centerX(); sample.fingerAtLast = sample.finger;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        });
    }

    void decision(Object swipe, Object target, PointF velocity, boolean fling) {
        if (reported >= LIMIT) return;
        Sample sample = gestures.get(swipe);
        if (sample == null || sample.id != 0) return;
        if (!fling || velocity == null || !(velocity.x < 0f && velocity.y < 0f)) {
            gestures.remove(swipe); return;
        }
        sample.id = ++reported;
        Log.i(TAG, "left#" + sample.id + " target=" + target + " fingerDx=" + sample.finger
                + " sampledFingerDx=" + (sample.fingerAtLast - sample.fingerAtFirst)
                + " dragWindowDx=" + (sample.last - sample.first) + " release=" + velocity.x + "," + velocity.y);
        if (!(target instanceof Enum) || !"HOME".equals(((Enum<?>) target).name())) gestures.remove(swipe);
    }

    void bind(Object swipe, Object[] created) {
        Sample sample = gestures.remove(swipe);
        if (sample != null && sample.id > 0 && created != null && created.length == 1 && created[0] != null)
            animations.put(created[0], sample);
    }

    void start(Object spring, Field fromField, Field toField) throws IllegalAccessException {
        Sample sample = animations.get(spring);
        if (sample == null) return;
        RectF from = (RectF) fromField.get(spring), to = (RectF) toField.get(spring);
        if (from == null || to == null || from.isEmpty() || to.isEmpty()) { animations.remove(spring); return; }
        sample.start = from.centerX(); sample.time = SystemClock.uptimeMillis();
        Log.i(TAG, "left#" + sample.id + " handoffDx=" + (sample.start - sample.last)
                + " targetDx=" + (to.centerX() - sample.start));
    }

    void frame(Object spring) throws IllegalAccessException {
        Sample sample = animations.get(spring);
        if (sample == null || sample.time == 0) return;
        RectF rect = (RectF) currentRect.get(spring);
        if (rect == null || rect.isEmpty()) return;
        long elapsed = SystemClock.uptimeMillis() - sample.time;
        float delta = rect.centerX() - sample.start;
        if (!Float.isFinite(delta)) return;
        sample.min = Math.min(sample.min, delta);
        if (elapsed < TIMES[sample.slot]) return;
        Log.i(TAG, "left#" + sample.id + " t=" + elapsed + "ms springDx=" + delta + " minDx=" + sample.min);
        do { sample.slot++; } while (sample.slot < TIMES.length && elapsed >= TIMES[sample.slot]);
        if (sample.slot == TIMES.length) animations.remove(spring);
    }

    private static final class Sample {
        float first = Float.NaN, last = Float.NaN, fingerAtFirst = Float.NaN, fingerAtLast = Float.NaN;
        float finger, start, min;
        int id, slot;
        long lastSample, time;
    }
}
