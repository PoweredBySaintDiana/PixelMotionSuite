package dev.pixelmotion.a10.recents;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.SparseArray;
import android.database.Cursor;
import android.net.Uri;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads native ranked suggestions without accessing home hotseat views or positions. */
final class DockSuggestions {
    private static final int ALL_APPS = -102;
    private static final int HOTSEAT_PREDICTIONS = -103;
    private static final Object LOCK = new Object();
    private static final List<WeakReference<Runnable>> LISTENERS = new ArrayList<>();
    private static List<Object> allAppsCache = Collections.emptyList();
    private static List<Object> hotseatCache = Collections.emptyList();
    private static Class<?> launcherClass;
    private static Class<?> iconClass;
    private static Class<?> predictionRowClass;
    private static Handler main;
    private static boolean installed;
    private static boolean notificationPending;
    private static boolean loggedError;
    private static String lastSource;
    private static final Uri SELECTION_URI = Uri.parse(
            "content://dev.pixelmotion.a10.recents.selection/apps");
    private static final Object SELECTION_LOCK = new Object();
    private static final Map<String, Object> SELECTED_ITEMS = new LinkedHashMap<>();
    private static final Map<String, Object> SELECTED_SOURCES = new LinkedHashMap<>();
    private static Handler selectionWorker;
    private static Class<?> appInfoClass;
    private static long selectionRevision = -1;
    private static long appsGeneration;
    private static long boundAppsGeneration = -1;
    private static boolean selectionConfigured;
    private static boolean selectionEnabled;
    private static boolean selectionReady;
    private static boolean selectionPending;
    private static boolean selectionDirty = true;
    private static List<String> selectedPackages = Collections.emptyList();
    private static int selectionPage;
    private static int selectionShownPage;
    private static boolean selectionHasShownPage;
    private static final List<Runnable> SELECTION_CALLBACKS = new ArrayList<>();

    private DockSuggestions() { }

    static void install(ClassLoader loader) throws Throwable {
        if (installed) return;
        launcherClass = Class.forName("com.android.launcher3.Launcher", false, loader);
        appInfoClass = Class.forName("com.android.launcher3.model.data.AppInfo", false, loader);
        iconClass = Class.forName("com.android.launcher3.model.data.ItemInfoWithIcon", false, loader);
        predictionRowClass = Class.forName("com.android.launcher3.appprediction.PredictionRowView", false, loader);
        Class<?> predicted = Class.forName("com.android.launcher3.model.data.PredictedContainerInfo", false, loader);
        Class<?> fixed = Class.forName("com.android.launcher3.model.BgDataModel$FixedContainerItems", false, loader);
        Class<?> callbacks = Class.forName("com.android.launcher3.ModelCallbacks", false, loader);
        Class<?> nexus = Class.forName("com.google.android.apps.nexuslauncher.NexusLauncherActivity", false, loader);
        main = new Handler(Looper.getMainLooper());

        // Launcher 907 delivers current predictions as workspace containers, rather
        // than only through the older FixedContainerItems callback.
        for (Method method : nexus.getDeclaredMethods()) {
            if (method.getReturnType() != void.class || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != predicted) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) captureContainer(p.args[0], "id");
                }
            });
        }
        XposedBridge.hookMethod(method(callbacks, "bindExtraContainerItems", fixed), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) captureContainer(p.args[0], "containerId");
            }
        });
        XposedBridge.hookMethod(method(predictionRowClass, "setPredictedApps", List.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) remember(ALL_APPS, read(p.thisObject, "mPredictedApps"));
            }
        });
        Class<?> allAppsList = Class.forName("com.android.launcher3.model.AllAppsList", false, loader);
        for (Method method : allAppsList.getDeclaredMethods()) {
            String name = method.getName();
            if (!"add".equals(name) && !"setFlags".equals(name) && !"updateIconsAndLabels".equals(name)
                    && !"updateRuntimeFlags".equals(name) && !"updatePromiseInstallInfo".equals(name)) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) markAppsDirty();
                }
            });
        }
        installed = true;
        log("native suggestions connected: all-apps -102; independent overview slots");
    }

    /** Returns original ItemInfoWithIcon objects in prediction order, never home icons. */
    static List<Object> snapshot(Object container, int count) {
        if (count <= 0) return Collections.emptyList();
        synchronized (SELECTION_LOCK) {
            if (selectionConfigured && selectionEnabled && selectionReady)
                return selectedSnapshotLocked(count, false);
        }
        if (!installed || count <= 0) return Collections.emptyList();
        try {
            Object launcher = currentLauncher(container);
            List<Object> result = select(items(read(launcher, "mAllAppsPredictions")), count);
            if (!result.isEmpty()) return from("all-apps container -102", result);

            result = select(predictionRowItems(launcher), count);
            if (!result.isEmpty()) return from("native prediction row -102", result);

            Object model = read(launcher, "mModel");
            Object data = read(model, "mBgDataModel");
            result = select(modelItems(data, ALL_APPS), count);
            if (!result.isEmpty()) return from("prediction model -102", result);

            synchronized (LOCK) { result = select(allAppsCache, count); }
            if (!result.isEmpty()) return from("bound predictions -102", result);

            // This is the prediction model's ranked fallback, not the user's
            // pinned hotseat contents. It needs no extra permissions or ranking.
            result = select(modelItems(data, HOTSEAT_PREDICTIONS), count);
            if (!result.isEmpty()) return from("prediction model -103", result);
            synchronized (LOCK) { result = select(hotseatCache, count); }
            if (!result.isEmpty()) return from("bound predictions -103", result);
            Object controller = read(launcher, "mHotseatPredictionController");
            Object organizer = read(controller, "mHotseatOrganizer");
            result = select(read(organizer, "predictedItems"), count);
            return from(result.isEmpty() ? "waiting for native suggestions" : "native predictor -103", result);
        } catch (Throwable failure) {
            error(failure);
            return Collections.emptyList();
        }
    }

    interface SnapshotCallback { void onSnapshot(List<Object> items); }

    /** Reads the module-owned selection off-main, then resolves native items on the model thread. */
    static void requestSelectedSnapshot(final Context context, final Object container,
            final int count, final SnapshotCallback callback) {
        if (context == null || count <= 0 || callback == null) return;
        synchronized (SELECTION_LOCK) {
            SELECTION_CALLBACKS.add(new Runnable() {
                @Override public void run() {
                    List<Object> items = selectionSnapshot(count, true);
                    if (items == null) items = snapshot(container, count);
                    callback.onSnapshot(items);
                }
            });
            if (selectionPending) return;
            selectionPending = true;
        }
        worker().post(new Runnable() {
            @Override public void run() {
                ProviderSelection provider = readProvider(context);
                if (provider != null) applyProviderSelection(provider);
                final boolean shouldBuild;
                synchronized (SELECTION_LOCK) {
                    shouldBuild = selectionConfigured && selectionEnabled
                            && (selectionDirty || boundAppsGeneration != appsGeneration);
                    if (!shouldBuild) {
                        deliverCallbacks();
                        return;
                    }
                }
                boolean scheduled = runOnModelThread(container, new Runnable() {
                    @Override public void run() {
                        if (buildSelectedItems(context, container)) {
                            synchronized (SELECTION_LOCK) {
                                boundAppsGeneration = appsGeneration;
                                selectionDirty = false;
                                selectionReady = true;
                            }
                        }
                        deliverCallbacks();
                    }
                });
                if (!scheduled) deliverCallbacks();
            }
        });
    }

    private static void deliverCallbacks() {
        MAIN_POST.post(new Runnable() {
            @Override public void run() {
                List<Runnable> callbacks;
                synchronized (SELECTION_LOCK) {
                    selectionPending = false;
                    callbacks = new ArrayList<>(SELECTION_CALLBACKS);
                    SELECTION_CALLBACKS.clear();
                }
                for (Runnable callback : callbacks) {
                    try { callback.run(); }
                    catch (Throwable failure) { logSelectionError("snapshot callback failed", failure); }
                }
            }
        });
    }

    private static List<Object> selectionSnapshot(int count, boolean advance) {
        synchronized (SELECTION_LOCK) { return selectedSnapshotLocked(count, advance); }
    }

    private static final Handler MAIN_POST = new Handler(Looper.getMainLooper());

    private static Handler worker() {
        synchronized (SELECTION_LOCK) {
            if (selectionWorker == null) {
                HandlerThread thread = new HandlerThread("PixelRecentsSelection");
                thread.start();
                selectionWorker = new Handler(thread.getLooper());
            }
            return selectionWorker;
        }
    }

    private static ProviderSelection readProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SELECTION_URI,
                    new String[] { "enabled", "revision", "package_name", "position" },
                    null, null, "position ASC");
            if (cursor == null) return null;
            int enabledColumn = cursor.getColumnIndexOrThrow("enabled");
            int revisionColumn = cursor.getColumnIndexOrThrow("revision");
            int packageColumn = cursor.getColumnIndexOrThrow("package_name");
            int positionColumn = cursor.getColumnIndexOrThrow("position");
            if (!cursor.moveToFirst()) return null;
            int enabled = cursor.getInt(enabledColumn);
            long revision = cursor.getLong(revisionColumn);
            if ((enabled != 0 && enabled != 1) || revision < 0) return null;
            final Map<Integer, String> ordered = new java.util.TreeMap<>();
            final Set<String> uniquePackages = new HashSet<>();
            do {
                if (cursor.getInt(enabledColumn) != enabled || cursor.getLong(revisionColumn) != revision)
                    return null;
                String pkg = cursor.isNull(packageColumn) ? null : cursor.getString(packageColumn);
                int position = cursor.getInt(positionColumn);
                if (pkg == null) {
                    if (position != -1 || ordered.size() != 0) return null;
                    continue;
                }
                if (position < 0 || position >= 512 || ordered.put(position, pkg) != null
                        || !uniquePackages.add(pkg)) return null;
            } while (cursor.moveToNext());
            List<String> packages = new ArrayList<>(ordered.values());
            if (!ordered.isEmpty() && (ordered.size() != ordered.keySet().stream().mapToInt(Integer::intValue).max().getAsInt() + 1))
                return null;
            return new ProviderSelection(enabled == 1, revision, packages);
        } catch (Throwable failure) {
            logSelectionError("provider read failed", failure);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void applyProviderSelection(ProviderSelection provider) {
        synchronized (SELECTION_LOCK) {
            if (selectionConfigured && provider.revision < selectionRevision) return;
            boolean changed = !selectionConfigured || provider.revision != selectionRevision
                    || provider.enabled != selectionEnabled || !provider.packages.equals(selectedPackages);
            selectionConfigured = true;
            selectionRevision = provider.revision;
            selectionEnabled = provider.enabled;
            if (changed) {
                selectedPackages = provider.packages;
                selectionPage = 0;
                selectionShownPage = 0;
                selectionHasShownPage = false;
                // A successful settings query is not yet a usable icon model.
                // Keep the native row visible until model resolution succeeds.
                // An explicitly empty custom list is authoritative immediately.
                selectionReady = !selectionEnabled || selectedPackages.isEmpty();
                SELECTED_ITEMS.keySet().retainAll(selectedPackages);
                SELECTED_SOURCES.keySet().retainAll(selectedPackages);
                selectionDirty = true;
            }
            if (!selectionEnabled) {
                SELECTED_ITEMS.clear();
                SELECTED_SOURCES.clear();
                selectionDirty = true;
            }
        }
    }

    private static boolean runOnModelThread(Object container, Runnable action) {
        try {
            Object launcher = currentLauncher(container);
            Object model = read(launcher, "mModel");
            if (!Boolean.TRUE.equals(read(model, "mModelLoaded"))) return false;
            Class<?> executors = Class.forName("com.android.launcher3.util.Executors", false,
                    launcherClass.getClassLoader());
            Object executor = read(executors, "MODEL_EXECUTOR");
            if (!(executor instanceof java.util.concurrent.Executor)) return false;
            ((java.util.concurrent.Executor) executor).execute(action);
            return true;
        } catch (Throwable failure) {
            logSelectionError("model executor unavailable", failure);
            return false;
        }
    }

    private static boolean buildSelectedItems(Context context, Object container) {
        try {
            Object launcher = currentLauncher(container);
            Object model = read(launcher, "mModel");
            if (!Boolean.TRUE.equals(read(model, "mModelLoaded"))) return false;
            Object allApps = read(model, "mBgAllAppsList");
            Object raw = read(allApps, "data");
            if (!(raw instanceof List)) return false;
            Set<String> wanted;
            synchronized (SELECTION_LOCK) { wanted = new HashSet<>(selectedPackages); }
            // Loaded state can precede a complete all-apps delivery. Do not
            // replace a populated row with an unresolved empty snapshot.
            if (!wanted.isEmpty() && ((List<?>) raw).isEmpty()) return false;
            Map<String, Object> sources = new LinkedHashMap<>();
            Map<String, Object> items = new LinkedHashMap<>();
            Class<?> process = Class.forName("android.os.Process", false,
                    launcherClass.getClassLoader());
            Object currentUser = method(process, "myUserHandle").invoke(null);
            for (Object app : new ArrayList<>((List<?>) raw)) {
                if (!appInfoClass.isInstance(app)) continue;
                Object user = read(app, "user");
                if (user != null && !currentUser.equals(user)) continue;
                ComponentName component = (ComponentName) method(app.getClass(), "getTargetComponent").invoke(app);
                Intent intent = (Intent) method(app.getClass(), "getIntent").invoke(app);
                String pkg = component == null ? (intent == null ? null : intent.getPackage())
                        : component.getPackageName();
                if (pkg == null || !wanted.contains(pkg) || sources.containsKey(pkg)) continue;
                Object item = method(app.getClass(), "makeWorkspaceItem", Context.class).invoke(app, context);
                sources.put(pkg, app);
                items.put(pkg, item);
            }
            synchronized (SELECTION_LOCK) {
                boolean same = sources.size() == SELECTED_SOURCES.size();
                if (same) for (Map.Entry<String, Object> entry : sources.entrySet())
                    if (SELECTED_SOURCES.get(entry.getKey()) != entry.getValue()) { same = false; break; }
                if (same && !selectionDirty) return true;
                SELECTED_SOURCES.clear();
                SELECTED_SOURCES.putAll(sources);
                SELECTED_ITEMS.clear();
                SELECTED_ITEMS.putAll(items);
                log("custom dock model resolved=" + items.size() + " selected=" + selectedPackages.size()
                        + " revision=" + selectionRevision);
                return true;
            }
        } catch (Throwable failure) {
            logSelectionError("selected app model unavailable", failure);
            return false;
        }
    }

    private static void markAppsDirty() {
        synchronized (SELECTION_LOCK) {
            appsGeneration++;
            selectionDirty = true;
        }
    }

    private static List<Object> selectedSnapshotLocked(int count, boolean advance) {
        if (!selectionConfigured || !selectionEnabled || !selectionReady) return null;
        if (selectedPackages.isEmpty()) return Collections.emptyList();
        List<Object> ordered = new ArrayList<>();
        for (String pkg : selectedPackages) {
            Object item = SELECTED_ITEMS.get(pkg);
            if (item != null) ordered.add(item);
        }
        if (ordered.isEmpty()) return Collections.emptyList();
        int pages = Math.max(1, (ordered.size() + count - 1) / count);
        int page = (advance ? selectionPage : (selectionHasShownPage ? selectionShownPage : 0)) % pages;
        int start = page * count;
        List<Object> result = new ArrayList<>(Math.min(count, ordered.size()));
        Set<String> emitted = new HashSet<>();
        for (int offset = 0; offset < count && offset < ordered.size(); offset++) {
            int index = (start + offset) % ordered.size();
            Object item = ordered.get(index);
            String pkg = targetPackage(item);
            if (pkg == null || emitted.add(pkg)) result.add(item);
        }
        if (advance) {
            selectionShownPage = page;
            selectionHasShownPage = true;
            selectionPage = (page + 1) % pages;
        }
        return result;
    }

    private static String targetPackage(Object item) {
        try {
            ComponentName component = (ComponentName) method(item.getClass(), "getTargetComponent").invoke(item);
            if (component != null) return component.getPackageName();
            Intent intent = (Intent) method(item.getClass(), "getIntent").invoke(item);
            return intent == null ? null : intent.getPackage();
        } catch (Throwable ignored) { return null; }
    }

    private static void logSelectionError(String message, Throwable failure) {
        android.util.Log.w("PixelRecentsA10", message, failure);
    }

    private static final class ProviderSelection {
        final boolean enabled;
        final long revision;
        final List<String> packages;
        ProviderSelection(boolean enabled, long revision, List<String> packages) {
            this.enabled = enabled;
            this.revision = revision;
            this.packages = packages;
        }
    }

    static void addListener(Runnable listener) {
        if (listener == null) return;
        synchronized (LOCK) {
            for (Iterator<WeakReference<Runnable>> it = LISTENERS.iterator(); it.hasNext();) {
                Runnable existing = it.next().get();
                if (existing == null) it.remove();
                else if (existing == listener) return;
            }
            LISTENERS.add(new WeakReference<>(listener));
        }
    }

    static void removeListener(Runnable listener) {
        synchronized (LOCK) {
            for (Iterator<WeakReference<Runnable>> it = LISTENERS.iterator(); it.hasNext();) {
                Runnable existing = it.next().get();
                if (existing == null || existing == listener) it.remove();
            }
        }
    }

    private static Object currentLauncher(Object container) throws Throwable {
        if (launcherClass.isInstance(container)) return container;
        Object tracker = read(launcherClass, "ACTIVITY_TRACKER");
        return tracker == null ? null : method(tracker.getClass(), "getCreatedContext").invoke(tracker);
    }

    private static Object predictionRowItems(Object launcher) {
        try {
            Object apps = read(launcher, "mAppsView");
            Object header = read(apps, "mHeader");
            Object row = header == null ? null : method(header.getClass(), "findFixedRowByType", Class.class)
                    .invoke(header, predictionRowClass);
            return read(row, "mPredictedApps");
        } catch (Throwable failure) {
            // The row can be absent or not yet inflated. The model and bind cache
            // remain valid sources independently of the all-apps view lifecycle.
            error(failure);
            return null;
        }
    }

    private static Object modelItems(Object data, int container) {
        if (data == null) return null;
        synchronized (data) {
            // Android 17 stores PredictedContainerInfo in WorkspaceData's map.
            Object workspace = read(data, "mutableWorkspaceData");
            Object map = read(workspace, "itemsIdMap");
            if (map instanceof SparseArray) {
                Object value = ((SparseArray<?>) map).get(container);
                Object predictions = items(value);
                if (predictions instanceof List) return new ArrayList<>((List<?>) predictions);
            }
            // Retain compatibility with already-bound FixedContainerItems.
            Object extras = read(data, "extraItems");
            if (extras instanceof SparseArray) {
                Object predictions = items(((SparseArray<?>) extras).get(container));
                if (predictions instanceof List) return new ArrayList<>((List<?>) predictions);
            }
        }
        return null;
    }

    private static Object items(Object value) { return read(value, "items"); }

    private static void captureContainer(Object value, String idField) {
        Object id = read(value, idField);
        if (id instanceof Number) remember(((Number) id).intValue(), items(value));
    }

    private static void remember(int container, Object value) {
        if ((container != ALL_APPS && container != HOTSEAT_PREDICTIONS) || !(value instanceof List)) return;
        synchronized (LOCK) {
            List<Object> snapshot = new ArrayList<>((List<?>) value);
            if (container == ALL_APPS) allAppsCache = snapshot; else hotseatCache = snapshot;
            if (notificationPending || main == null || LISTENERS.isEmpty()) return;
            notificationPending = true;
        }
        // Both update paths can fire together. Refresh each attached dock once on
        // the main loop, after the launcher's own prediction binding completes.
        main.post(new Runnable() {
            @Override public void run() {
                List<Runnable> callbacks = new ArrayList<>();
                synchronized (LOCK) {
                    notificationPending = false;
                    for (Iterator<WeakReference<Runnable>> it = LISTENERS.iterator(); it.hasNext();) {
                        Runnable callback = it.next().get();
                        if (callback == null) it.remove(); else callbacks.add(callback);
                    }
                }
                for (Runnable callback : callbacks) {
                    try { callback.run(); } catch (Throwable failure) { error(failure); }
                }
            }
        });
    }

    private static List<Object> select(Object value, int count) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Object> result = new ArrayList<>();
        Set<Key> seen = new HashSet<>();
        for (Object item : (List<?>) value) {
            if (!iconClass.isInstance(item)) continue;
            try {
                Object component = method(item.getClass(), "getTargetComponent").invoke(item);
                Object intent = method(item.getClass(), "getIntent").invoke(item);
                if (component == null || intent == null) continue;
                Key key = new Key(component, read(item, "user"));
                if (!seen.add(key)) continue;
                result.add(item);
            } catch (Throwable failure) {
                // One stale or unsupported suggestion must not hide its siblings.
                error(failure);
                continue;
            }
            if (result.size() == count) break;
        }
        return result;
    }

    private static List<Object> from(String source, List<Object> items) {
        if (!source.equals(lastSource)) {
            lastSource = source;
            log("dock suggestions source=" + source + " visible=" + items.size());
        }
        return items;
    }

    private static Object read(Object owner, String name) {
        if (owner == null) return null;
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner instanceof Class ? null : owner);
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Method method = at.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void error(Throwable failure) {
        if (loggedError) return;
        loggedError = true;
        log("suggestions unavailable: " + failure);
    }

    private static void log(String message) {
        android.util.Log.i("PixelRecentsA10", message);
        XposedBridge.log("PixelRecentsA10: " + message);
    }

    private static final class Key {
        final Object component, user;
        Key(Object component, Object user) { this.component = component; this.user = user; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return component.equals(key.component) && (user == null ? key.user == null : user.equals(key.user));
        }
        @Override public int hashCode() { return 31 * component.hashCode() + (user == null ? 0 : user.hashCode()); }
    }
}
