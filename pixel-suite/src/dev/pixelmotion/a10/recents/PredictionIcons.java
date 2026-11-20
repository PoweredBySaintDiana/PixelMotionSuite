package dev.pixelmotion.a10.recents;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;

/** Native prediction plates and the launcher's own slot replacement animation. */
final class PredictionIcons {
    private static Class<?> predictionClass;
    private static Class<?> workspaceItemClass;
    private static Method applyWithAnimation;
    private static Method verifyHighRes;
    private static boolean installed;

    private PredictionIcons() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        predictionClass = Class.forName("com.android.launcher3.views.PredictedAppIcon", false, loader);
        workspaceItemClass = Class.forName("com.android.launcher3.model.data.WorkspaceItemInfo", false, loader);
        applyWithAnimation = Reflect.method(predictionClass, "applyFromWorkspaceItemWithAnimation",
                int.class, workspaceItemClass);
        verifyHighRes = Reflect.method(predictionClass, "verifyHighRes");
        installed = true;
    }

    static boolean canBind(Object item) {
        return installed && workspaceItemClass.isInstance(item);
    }

    static boolean isPrediction(View icon) {
        return installed && predictionClass.isInstance(icon);
    }

    /** Inflate the real launcher layout, so icon normalization, shape and theme stay native. */
    static View create(Context context, ViewGroup parent, Object item) throws Throwable {
        if (!canBind(item)) throw new IllegalArgumentException("Prediction requires WorkspaceItemInfo");
        int layout = context.getResources().getIdentifier("predicted_app_icon", "layout", context.getPackageName());
        if (layout == 0) throw new IllegalStateException("Missing native predicted_app_icon layout");
        View icon = LayoutInflater.from(context).inflate(layout, parent, false);
        if (!isPrediction(icon)) throw new IllegalStateException("Layout did not create PredictedAppIcon");
        // This first bind is not shown yet. The native method assigns its initial
        // plate color immediately and preserves the original user/shortcut tag.
        bind(icon, item, 0);
        icon.setLongClickable(false);
        return icon;
    }

    /**
     * Reuse a slot's View and let native code compare the old tag with the new one.
     * A changed, visible component receives the native 360 ms reel/plate animation,
     * staggered by 50 ms per preceding animated slot. No source item is rewritten.
     * The returned value indicates whether that native replacement animation ran.
     */
    static boolean bind(View icon, Object item, int animationIndex) throws Throwable {
        if (!isPrediction(icon) || !canBind(item)) return false;
        boolean animated = Boolean.TRUE.equals(applyWithAnimation.invoke(icon, Math.max(0, animationIndex), item));
        // This is also part of ItemInflater's native prediction binding path.
        verifyHighRes.invoke(icon);
        return animated;
    }
}
