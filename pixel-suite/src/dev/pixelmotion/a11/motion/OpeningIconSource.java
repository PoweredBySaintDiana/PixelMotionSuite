package dev.pixelmotion.a11.motion;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowInsets;

/** Identifies full-screen launcher icon opens eligible for Launcher 701 timing. */
final class OpeningIconSource {
    private final Class<?> bubble, workspace, hotseat, drawer;

    private OpeningIconSource(ClassLoader loader) throws ClassNotFoundException {
        bubble = loader.loadClass("com.android.launcher3.BubbleTextView");
        workspace = loader.loadClass("com.android.launcher3.Workspace");
        hotseat = loader.loadClass("com.android.launcher3.Hotseat");
        drawer = loader.loadClass("com.android.launcher3.allapps.ActivityAllAppsContainerView");
    }

    static OpeningIconSource load(ClassLoader loader) {
        try {
            return new OpeningIconSource(loader);
        } catch (ClassNotFoundException | RuntimeException | LinkageError error) {
            Motion.log("A10 icon timing unavailable: " + error);
            return null;
        }
    }

    boolean matches(View icon, Rect target, RectF source) {
        if (!bubble.isInstance(icon) || icon.getWindowToken() == null || target == null || source == null
                || target.isEmpty() || source.isEmpty() || target.left != 0 || target.top != 0
                || Math.min(target.width(), target.height()) <= Math.max(source.width(), source.height())) {
            return false;
        }
        View root = icon.getRootView();
        WindowInsets insets = root.getRootWindowInsets();
        int bottomInset = insets == null ? 0 : insets.getStableInsetBottom();
        if (root.getWidth() != target.width() || target.height() > root.getHeight()
                || root.getHeight() - target.height() > bottomInset) return false;
        for (ViewParent parent = icon.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (workspace.isInstance(parent) || hotseat.isInstance(parent) || drawer.isInstance(parent)) return true;
        }
        return false;
    }
}
