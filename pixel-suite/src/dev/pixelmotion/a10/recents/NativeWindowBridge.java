package dev.pixelmotion.a10.recents;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.Parcelable;
import android.os.ResultReceiver;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Calls NativeComplements' own launch API from its SystemUI host. */
final class NativeWindowBridge {
    static final String ACTION = "dev.pixelmotion.a10.recents.NATIVE_WINDOW";
    static final String MODULE_PACKAGE = "dev.nativecomplements.module";
    static final String RESULT = MODULE_PACKAGE + ".RESULT";
    static final String MANAGE_TASKS = "android.permission.MANAGE_ACTIVITY_TASKS";
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String HOST_CLASS = "dev.local.sunwindowdemo.SystemUiHost";
    private static final String WIRE_CLASS = "dev.local.sunwindowdemo.Wire";
    private static final long REQUEST_TIMEOUT_MS = 18000L;
    private static final int MAX_PENDING = 8;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<ResultReceiver, Pending> BACKEND_REPLIES = new IdentityHashMap<>();
    private static Context context;
    private static volatile ClassLoader nativeLoader;
    private static int launcherUid = -1;
    private static boolean installed;

    private NativeWindowBridge() { }

    static boolean probe(Context context, String id, ResultReceiver reply) {
        return send(context, "probe", id, null, null, reply);
    }

    static boolean open(Context context, String id, Intent intent, Parcelable user,
            ResultReceiver reply) {
        return send(context, "open", id, intent, user, reply);
    }

    private static boolean send(Context context, String operation, String id, Intent intent,
            Parcelable user, ResultReceiver reply) {
        try {
            Intent request = new Intent(ACTION).setPackage("com.android.systemui")
                    .putExtra("op", operation).putExtra("requestId", id);
            if (intent != null) request.putExtra("intent", intent);
            if (user != null) request.putExtra("user", user);
            if (reply != null) request.putExtra("reply", reply);
            Class<?> optionsType = Class.forName("android.app.BroadcastOptions");
            Object options = optionsType.getMethod("makeBasic").invoke(null);
            optionsType.getMethod("setShareIdentityEnabled", boolean.class).invoke(options, true);
            Bundle extras = (Bundle) optionsType.getMethod("toBundle").invoke(options);
            Context.class.getMethod("sendBroadcast", Intent.class, String.class, Bundle.class)
                    .invoke(context, request, null, extras);
            return true;
        } catch (Throwable failure) {
            log("request send failed op=" + operation + ": " + failure.getClass().getSimpleName());
            return false;
        }
    }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        Class<?> resultReceiver = Class.forName("android.os.ResultReceiver", false, loader);
        XposedBridge.hookMethod(Reflect.method(resultReceiver, "onReceiveResult",
                int.class, Bundle.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Pending pending = BACKEND_REPLIES.remove(p.thisObject);
                if (pending == null) return;
                int resultCode = ((Integer) p.args[0]).intValue();
                Bundle data = (Bundle) p.args[1];
                finish(pending.id, resultCode == 1, data,
                        resultCode == 1 ? null : "NativeComplements rejected the launch");
            }
        });
        int hooks = 0;
        for (Method method : ContextWrapper.class.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (!"registerReceiver".equals(method.getName()) || args.length != 5
                    || args[0] != BroadcastReceiver.class || args[1] != IntentFilter.class
                    || args[2] != String.class || args[3] != Handler.class || args[4] != int.class)
                continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) captureNativeLoader(p.args[0], p.args[1]);
                }
            });
            hooks++;
        }
        if (hooks == 0) throw new NoSuchMethodException("ContextWrapper.registerReceiver(...,int)");

        Class<?> app;
        try {
            app = Class.forName("com.android.systemui.application.impl.SystemUIApplicationImpl", false, loader);
        } catch (ClassNotFoundException olderSystemUi) {
            app = Class.forName("com.android.systemui.SystemUIApplication", false, loader);
        }
        XposedBridge.hookMethod(Reflect.method(app, "onCreate"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || context != null) return;
                try { attach((Application) p.thisObject); }
                catch (Throwable failure) { log("SystemUI bridge attach failed: " + failure); }
            }
        });
        installed = true;
    }

    private static void captureNativeLoader(Object receiver, Object rawFilter) {
        if (!(receiver instanceof BroadcastReceiver) || !(rawFilter instanceof IntentFilter)) return;
        BroadcastReceiver candidate = (BroadcastReceiver) receiver;
        String name = candidate.getClass().getName();
        if (!name.startsWith("dev.local.sunwindowdemo.")) return;
        IntentFilter filter = (IntentFilter) rawFilter;
        boolean listensForBackend = false;
        for (int i = 0; i < filter.countActions(); i++)
            if (RESULT.equals(filter.getAction(i))) listensForBackend = true;
        if (!listensForBackend) return;
        ClassLoader candidateLoader = candidate.getClass().getClassLoader();
        try {
            Class<?> host = Class.forName(HOST_CLASS, false, candidateLoader);
            Class<?> wire = Class.forName(WIRE_CLASS, false, candidateLoader);
            host.getDeclaredField("live");
            wire.getDeclaredMethod("openIntent", Context.class, String.class,
                    Intent.class, ResultReceiver.class);
            nativeLoader = candidateLoader;
            log("captured NativeComplements SystemUI host loader");
        } catch (Throwable failure) {
            log("NativeComplements host API unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static void attach(Application application) throws Throwable {
        Context app = application.getApplicationContext();
        if (app == null || !"com.android.systemui".equals(app.getPackageName()))
            throw new IllegalStateException("SystemUI application context unavailable");
        context = app;
        try { launcherUid = app.getPackageManager().getApplicationInfo(LAUNCHER, 0).uid; }
        catch (PackageManager.NameNotFoundException missing) { launcherUid = -1; }

        BroadcastReceiver requests = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent request) {
                receive(request, sentUid(this));
            }
        };
        Method register = Context.class.getMethod("registerReceiver", BroadcastReceiver.class,
                IntentFilter.class, String.class, Handler.class, int.class);
        register.invoke(app, requests, new IntentFilter(ACTION), MANAGE_TASKS,
                MAIN, 2);
        log("NativeComplements bridge receiver ready; launcherUid=" + launcherUid);
    }

    private static void receive(Intent request, int senderUid) {
        if (request == null || !ACTION.equals(request.getAction()) || !authorized(senderUid)) return;
        String operation = request.getStringExtra("op");
        String id = request.getStringExtra("requestId");
        if (!validId(id)) return;
        ResultReceiver reply = parcelableExtra(request, "reply", ResultReceiver.class);
        if (reply == null) return;
        if ("probe".equals(operation)) {
            reply(reply, available(), id, available() ? null : unavailableReason());
        } else if ("open".equals(operation)) {
            open(request, id, reply);
        }
    }

    private static boolean authorized(int uid) {
        Context app = context;
        return app != null && uid >= 0 && uid == launcherUid
                && app.checkPermission(MANAGE_TASKS, -1, uid) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean available() {
        try {
            Object host = host();
            if (host == null || !Boolean.TRUE.equals(call(host, "ready"))) return false;
            Object config = field(host, "config");
            if (config == null || !Boolean.TRUE.equals(call(config, "enabled"))) return false;
            if (!Boolean.TRUE.equals(field(host, "backendReady"))) return false;
            Object state = field(host, "backendState");
            return state == null || !"conflict".equals(state);
        } catch (Throwable failure) { return false; }
    }

    private static String unavailableReason() {
        if (nativeLoader == null) return "请在 LSPosed 中启用 NativeComplements 的系统界面作用域并重启";
        try {
            Object host = host();
            if (host == null || !Boolean.TRUE.equals(call(host, "ready")))
                return "NativeComplements 的系统界面宿主尚未就绪";
            Object config = field(host, "config");
            if (config == null || !Boolean.TRUE.equals(call(config, "enabled")))
                return "请在 NativeComplements 中开启小窗功能";
            if (!Boolean.TRUE.equals(field(host, "backendReady")))
                return "请在 LSPosed 中启用 NativeComplements 的系统框架作用域并重启手机";
            return "NativeComplements 小窗后端不可用";
        } catch (Throwable failure) { return "NativeComplements 接口暂不可用"; }
    }

    private static Object host() throws Throwable {
        ClassLoader loader = nativeLoader;
        if (loader == null) return null;
        Class<?> type = Class.forName(HOST_CLASS, false, loader);
        Field live = type.getDeclaredField("live");
        live.setAccessible(true);
        return live.get(null);
    }

    private static void open(Intent request, String id, ResultReceiver reply) {
        if (!available()) { reply(reply, false, id, unavailableReason()); return; }
        if (PENDING.containsKey(id)) { reply(reply, false, id, "duplicate request"); return; }
        if (PENDING.size() >= MAX_PENDING) { reply(reply, false, id, "too many pending launches"); return; }
        Intent launch = parcelableExtra(request, "intent", Intent.class);
        Object user = request.getExtras() == null ? null : request.getExtras().get("user");
        String pkg = targetPackage(launch);
        if (userIdentifier(user) != Process.myUid() / 100000
                || !validLaunch(launch, pkg)) {
            reply(reply, false, id, "invalid or unsupported app launch target");
            return;
        }

        Pending pending = new Pending(id, reply);
        PENDING.put(id, pending);
        MAIN.postDelayed(pending.timeout, REQUEST_TIMEOUT_MS);
        try {
            Object host = host();
            Class<?> wire = Class.forName(WIRE_CLASS, false, nativeLoader);
            Method open = wire.getDeclaredMethod("openIntent", Context.class, String.class,
                    Intent.class, ResultReceiver.class);
            open.setAccessible(true);
            ResultReceiver backendReply = new ResultReceiver(MAIN);
            pending.backendReply = backendReply;
            BACKEND_REPLIES.put(backendReply, pending);
            open.invoke(null, (Context) host, pkg, new Intent(launch).setPackage(pkg),
                    backendReply);
            log("delegated app open to NativeComplements package=" + pkg + " id=" + id);
        } catch (Throwable failure) {
            finish(id, false, null, "NativeComplements launch API failed: " + failure.getClass().getSimpleName());
        }
    }

    private static boolean validLaunch(Intent intent, String pkg) {
        if (intent == null || pkg == null || pkg.length() == 0) return false;
        if (intent.getComponent() != null && !pkg.equals(intent.getComponent().getPackageName())) return false;
        Context app = context;
        try {
            ResolveInfo resolved = app.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo info = resolved == null ? null : resolved.activityInfo;
            return info != null && pkg.equals(info.packageName) && info.exported && info.enabled;
        } catch (RuntimeException failure) { return false; }
    }

    private static String targetPackage(Intent intent) {
        if (intent == null) return null;
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        return intent.getPackage();
    }

    private static int userIdentifier(Object user) {
        if (user == null || !"android.os.UserHandle".equals(user.getClass().getName())) return -1;
        try { return ((Number) user.getClass().getMethod("getIdentifier").invoke(user)).intValue(); }
        catch (Throwable failure) { return -1; }
    }

    private static void finish(String id, boolean success, Bundle data, String reason) {
        Pending pending = PENDING.remove(id);
        if (pending == null || pending.done) return;
        pending.done = true;
        if (pending.backendReply != null) BACKEND_REPLIES.remove(pending.backendReply);
        MAIN.removeCallbacks(pending.timeout);
        Bundle result = data == null ? new Bundle() : new Bundle(data);
        result.putString("requestId", id);
        result.putString("backendPackage", MODULE_PACKAGE);
        if (reason != null) result.putString("reason", reason);
        try { pending.reply.send(success ? 1 : 0, result); }
        catch (RuntimeException failure) { log("request callback failed id=" + id); }
    }

    private static void reply(ResultReceiver receiver, boolean success, String id, String reason) {
        Bundle result = new Bundle();
        result.putString("requestId", id);
        result.putString("backendPackage", MODULE_PACKAGE);
        if (reason != null) result.putString("reason", reason);
        try { receiver.send(success ? 1 : 0, result); }
        catch (RuntimeException failure) { log("probe callback failed id=" + id); }
    }

    private static int sentUid(BroadcastReceiver receiver) {
        try { return ((Number) BroadcastReceiver.class.getMethod("getSentFromUid").invoke(receiver)).intValue(); }
        catch (Throwable unavailable) { return -1; }
    }

    private static <T> T parcelableExtra(Intent intent, String key, Class<T> type) {
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        Object value = extras.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private static Object field(Object target, String name) throws Throwable {
        for (Class<?> at = target.getClass(); at != null; at = at.getSuperclass()) {
            try {
                Field value = at.getDeclaredField(name);
                value.setAccessible(true);
                return value.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object call(Object target, String name) throws Throwable {
        for (Class<?> at = target.getClass(); at != null; at = at.getSuperclass()) {
            try {
                Method method = at.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static boolean validId(String value) {
        return value != null && value.length() >= 16 && value.length() <= 128
                && value.matches("[A-Za-z0-9._:-]+");
    }

    private static void log(String message) {
        android.util.Log.i("PixelRecentsA10", "NativeWindowBridge: " + message);
        XposedBridge.log("PixelRecentsA10 NativeWindowBridge: " + message);
    }

    private static final class Pending {
        final String id;
        final ResultReceiver reply;
        ResultReceiver backendReply;
        boolean done;
        final Runnable timeout;

        Pending(String requestId, ResultReceiver resultReceiver) {
            id = requestId;
            reply = resultReceiver;
            timeout = () -> finish(id, false, null, "NativeComplements launch timed out");
        }
    }
}
