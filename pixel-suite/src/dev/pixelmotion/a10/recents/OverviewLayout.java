package dev.pixelmotion.a10.recents;

import android.content.Context;
import android.graphics.Rect;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.WeakHashMap;

/** Keeps the overview card vertically aligned with the independent dock. */
final class OverviewLayout {
    private static final WeakHashMap<Object, Boolean> LOGGED = new WeakHashMap<>();
    private static boolean loggedError;

    private OverviewLayout() { }

    static void install(ClassLoader loader) throws Throwable {
        Class<?> base = Class.forName("com.android.quickstep.BaseContainerInterface", false, loader);
        Class<?> profile = Class.forName("com.android.launcher3.DeviceProfile", false, loader);
        Class<?> orientation = Class.forName(
                "com.android.quickstep.orientation.RecentsPagedOrientationHandler", false, loader);
        XposedBridge.hookMethod(Reflect.method(base, "calculateTaskSize", Context.class,
                profile, Rect.class, orientation), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try {
                    if (isSwipeGeometryCall()) return;
                    Object dp = p.args[1];
                    Object device = Reflect.get(dp, "mDeviceProperties");
                    if (!(Boolean) Reflect.get(device, "isPhone")
                            || (Boolean) Reflect.get(device, "isLandscape")
                            || (Boolean) Reflect.get(device, "isLargeScreen")
                            || !(Boolean) Reflect.call(p.args[3], "isLayoutNaturalToLauncher")) return;
                    Context context = (Context) p.args[0];
                    Rect insets = (Rect) Reflect.get(device, "insets");
                    int width = (Integer) Reflect.get(device, "widthPx");
                    int height = (Integer) Reflect.get(device, "heightPx");
                    Object overview = Reflect.get(dp, "overviewProfile");
                    int icon = (Integer) Reflect.get(overview, "taskIconSizePx");
                    int statusTop = insets == null ? 0 : insets.top;
                    int minTop = statusTop + icon
                            + Math.round(8 * context.getResources().getDisplayMetrics().density);
                    Rect task = (Rect) p.args[2];
                    int top = Math.max(minTop, task.top);
                    int topGap = top - statusTop;
                    int iconTop = height - RecentsDockHost.dockHeight(context, dp)
                            + RecentsDockHost.dockIconRowTop(context, dp);
                    int bottom = iconTop - topGap;
                    int sourceHeight = Math.max(1, task.height());
                    int targetHeight = Math.max(1, bottom - top);
                    int targetWidth = Math.max(1, Math.round(task.width()
                            * (targetHeight / (float) sourceHeight)));
                    int horizontalInset = insets == null ? 0 : Math.max(insets.left, insets.right);
                    int maxWidth = Math.max(1, width - horizontalInset * 2);
                    if (targetWidth > maxWidth) {
                        targetWidth = maxWidth;
                        targetHeight = Math.round(sourceHeight
                                * (targetWidth / (float) Math.max(1, task.width())));
                        bottom = top + targetHeight;
                    }
                    int centerX = task.centerX();
                    int left = centerX - targetWidth / 2;
                    left = Math.max(horizontalInset, Math.min(width - horizontalInset - targetWidth, left));
                    task.set(left, top, left + targetWidth, bottom);
                    if (!LOGGED.containsKey(dp)) {
                        LOGGED.put(dp, Boolean.TRUE);
                        RecentsMotion.log("shared task target=" + task + ", status gap=" + topGap
                                + ", dock icon top=" + iconTop + ", dock gap=" + (iconTop - bottom));
                    }
                } catch (Throwable error) {
                    if (!loggedError) {
                        loggedError = true;
                        RecentsMotion.log("Task shelf layout unavailable: " + error);
                    }
                }
            }
        });
    }

    private static boolean isSwipeGeometryCall() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String owner = frame.getClassName();
            String method = frame.getMethodName();
            if ((owner.contains("LauncherActivityInterface")
                    && method.contains("getSwipeUpDestinationAndLength"))
                    || (owner.contains("LayoutUtils")
                    && method.contains("getShelfTrackingDistance"))) return true;
        }
        return false;
    }
}
