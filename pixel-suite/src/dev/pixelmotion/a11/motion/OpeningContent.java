package dev.pixelmotion.a11.motion;

import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

final class OpeningContent {
    // Both hooks run on the launcher UI thread. Match the exact properties
    // instance handed to the runner, even when two starts overlap.
    private static final WeakHashMap<Object, Boolean> matched = new WeakHashMap<>();

    static boolean matchesIconLaunch(Object properties) {
        return matched.containsKey(properties);
    }

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> properties = Motion.load("com.android.launcher3.remoteanimations.AnimOpenProperties", loader);
        Class<?> bubble = Motion.load("com.android.launcher3.BubbleTextView", loader);
        Class<?> workspace = Motion.load("com.android.launcher3.Workspace", loader);
        Class<?> hotseat = Motion.load("com.android.launcher3.Hotseat", loader);
        Class<?> drawer = Motion.load("com.android.launcher3.allapps.ActivityAllAppsContainerView", loader);
        Field width = Motion.field(properties, "cropWidthStart");
        Field height = Motion.field(properties, "cropHeightStart");
        Field centerX = Motion.field(properties, "cropCenterXStart");
        Field centerY = Motion.field(properties, "cropCenterYStart");
        Constructor<?> init = properties.getDeclaredConstructor(Resources.class, Rect.class, RectF.class,
                View.class, int.class, int.class, boolean.class, boolean.class);
        XposedBridge.hookMethod(init, new XC_MethodHook() {
            private boolean reported;
            @Override protected void afterHookedMethod(MethodHookParam call) throws IllegalAccessException {
                if (call.hasThrowable() || !Motion.onUiThread() || !bubble.isInstance(call.args[3])) return;
                View icon = (View) call.args[3];
                Rect target = (Rect) call.args[1];
                RectF source = (RectF) call.args[2];
                if (target == null || source == null || source.isEmpty() || target.isEmpty()
                        || target.left != 0 || target.top != 0 || icon.getWindowToken() == null) return;
                View root = icon.getRootView();
                android.view.WindowInsets insets = root.getRootWindowInsets();
                int bottomInset = insets == null ? 0 : insets.getStableInsetBottom();
                if (root.getWidth() != target.width() || target.height() > root.getHeight()
                        || root.getHeight() - target.height() > bottomInset) return;
                boolean fromLauncher = false;
                for (ViewParent parent = icon.getParent(); parent instanceof View; parent = parent.getParent()) {
                    if (workspace.isInstance(parent) || hotseat.isInstance(parent) || drawer.isInstance(parent)) {
                        fromLauncher = true; break;
                    }
                }
                if (!fromLauncher) return;
                int side = Math.min(target.width(), target.height());
                if (side <= Math.max(source.width(), source.height())) return;
                matched.put(call.thisObject, true);
                // A real starting window owns its splash artwork and source crop.
                if ((Boolean) call.args[6]) return;
                width.setInt(call.thisObject, side);
                height.setInt(call.thisObject, side);
                centerX.setInt(call.thisObject, side / 2);
                centerY.setInt(call.thisObject, side / 2);
                if (!reported) {
                    reported = true;
                    Motion.log("A10 opening content: source crop " + side + "x" + side + " -> "
                            + target.width() + "x" + target.height());
                }
            }
        });
    }
}
