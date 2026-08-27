package com.picoxr.vsleep;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


/** Adds a V-Sleep toggle to the quick-settings panel without modifying the system APK. */
public final class VSleepHook implements IXposedHookLoadPackage {
    private static final String TAG = "PicoVSleep";
    private static final String SETTINGS_PACKAGE = "com.picovr.settings";
    private static final int SLEEP_EYEBUFFER = 1024;
    private static final int V_SLEEP_TILE = 9001;
    private static final String MODE_KEY = "pico_vsleep_enabled";
    private static final String TILE_ADDED_KEY = "pico_vsleep_quick_added";
    private static final String TILE_INDEX_KEY = "pico_vsleep_quick_index";
    private static final String WORN_KEY = "pico_vsleep_headset_worn";
    private static final String REMOVED_AT_KEY = "pico_vsleep_headset_removed_at";
    private static final String SAVED_PREFIX = "pico_vsleep_saved_";
    private static final String SNAPSHOT_KEY = "pico_vsleep_snapshot_valid";
    private static final String GOVERNOR_PREFIX = "governor_";
    private static final String CPU_POLICY_PATH = "/sys/devices/system/cpu/cpufreq";
    private static final String PROP_EYEBUFFER_W = "persist.pvr.config.eyebuffer_width";
    private static final String PROP_EYEBUFFER_H = "persist.pvr.config.eyebuffer_height";
    private static final String PROP_FFR = "persist.pvr.config.ffr";
    private static final String PROP_ENABLE_FFR = "persist.pvr.config.enable_ffr";
    private static final String PROP_FOVEATION = "persist.pvr.foveation.level";
    private static final String PROP_FPS = "persist.pvr.config.target_fps";
    private static final String MODULE_PACKAGE = "com.picoxr.vsleep";
    private static final String COORD_PREFIX = "pico_power_coord_";
    private static final String COORD_VERSION = COORD_PREFIX + "version";
    private static final String COORD_OWNER = COORD_PREFIX + "owner";
    private static final String COORD_ACTIVE = COORD_PREFIX + "sleep_active";
    private static final String COORD_GENERATION = COORD_PREFIX + "generation";
    private static final String COORD_SNAPSHOT_VALID = COORD_PREFIX + "snapshot_valid";
    private static final String COORD_SNAPSHOT_PREFIX = COORD_PREFIX + "snapshot_";
    private static final String COORD_REQUEST = COORD_PREFIX + "v2_request";
    private static final String COORD_ACK = COORD_PREFIX + "v2_ack";
    private static final String COORD_EFFECTIVE_OWNER = COORD_PREFIX + "v2_effective_owner";
    private static final String COORD_PHASE = COORD_PREFIX + "v2_phase";
    private static final String COORD_ERROR = COORD_PREFIX + "v2_error";
    private static final String PNS_VSLEEP_WAS_ENABLED = "pico_neversleep_vsleep_was_enabled";
    private static final int COORD_PROTOCOL_VERSION = 2;
    private static final long COORD_POLL_MS = 250L;
    private static final long UNWORN_SLEEP_DELAY_MS = TimeUnit.MINUTES.toMillis(3);
    private static final ScheduledExecutorService SLEEP_EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static volatile Object sButton;
    private static volatile Object sAppContext;
    private static volatile Object sSensorManager;
    private static volatile Object sProximityListener;
    private static volatile ScheduledFuture sPendingSleep;
    private static volatile boolean sWearKnown;
    private static volatile boolean sWorn;
    private static volatile boolean sCoordinationPollStarted;
    private static final ThreadLocal MAPPED_BIND_ITEM = new ThreadLocal();
    private static Object sWakeLock;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if (!SETTINGS_PACKAGE.equals(lp.packageName)) return;
        XposedBridge.log(TAG + ": loading in Quick Settings " + lp.packageName);
        try {
            final Class<?> adapter = XposedHelpers.findClass("com.picovr.quicksettings.ButtonListAdapter", lp.classLoader);
            XposedHelpers.findAndHookMethod(adapter, "getItemViewType", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        java.lang.reflect.Field data = p.thisObject.getClass().getDeclaredField("a"); data.setAccessible(true);
                        Object info = ((List) data.get(p.thisObject)).get(((Integer) p.args[0]).intValue());
                        if (((Integer) info.getClass().getMethod("f").invoke(info)).intValue() == V_SLEEP_TILE) p.setResult(1);
                    } catch (Throwable t) { XposedBridge.log(TAG + ": view type mapping failed: " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(adapter, "onBindViewHolder", Class.forName("androidx.recyclerview.widget.RecyclerView$ViewHolder", false, lp.classLoader), int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { mapSleepTypeForBind(p.thisObject, ((Integer) p.args[1]).intValue()); }
                @Override protected void afterHookedMethod(MethodHookParam p) { int position = ((Integer) p.args[1]).intValue(); configureSleepButton(p.thisObject, p.args[0], position); restoreSleepTypeAfterBind(p.thisObject, position); }
            });
            XposedHelpers.findAndHookMethod(adapter, "b", ArrayList.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { removeDuplicateTile((ArrayList) p.args[0]); }
            });
            final Class<?> utils = XposedHelpers.findClass("com.picovr.quicksettings.utils.QuickSettingUtils", lp.classLoader);
            final Class<?> loadCallback = XposedHelpers.findClass("com.picovr.quicksettings.utils.QuickSettingUtils$LoadButtonsCallBack", lp.classLoader);
            XposedHelpers.findAndHookMethod(utils, "b", Class.forName("android.content.Context", false, lp.classLoader), loadCallback, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.args[1] = quickPanelCallback(p.args[1], loadCallback, lp.classLoader); }
            });
            installEditorHooks(lp.classLoader);
            installWearLifecycleHook(lp.classLoader);
            XposedBridge.log(TAG + ": quick-settings hooks installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": failed to hook quick-settings adapter: " + t); }
    }

    private static void installEditorHooks(final ClassLoader cl) {
        try {
            final Class<?> fragment = XposedHelpers.findClass("com.picovr.fragments.QuickPanelFragment", cl);
            final Class<?> manager = XposedHelpers.findClass("com.picovr.database.quickpanel.QuickPanelManager", cl);
            final Class<?> callback = XposedHelpers.findClass("com.picovr.listener.ResultCallback", cl);
            final Class<?> added = XposedHelpers.findClass("com.picovr.adapters.QuickPanelAddedAdapter", cl);
            final Class<?> more = XposedHelpers.findClass("com.picovr.adapters.QuickPanelMoreAdapter", cl);
            final Class<?> holderA = XposedHelpers.findClass("com.picovr.adapters.QuickPanelAddedAdapter$AddedHolder", cl);
            final Class<?> holderM = XposedHelpers.findClass("com.picovr.adapters.QuickPanelMoreAdapter$MoreHolder", cl);
            fragment.getDeclaredMethod("I", List.class, List.class, boolean.class);
            manager.getDeclaredMethod("A", List.class, callback);
            added.getDeclaredMethod("m", holderA, int.class);
            more.getDeclaredMethod("c", holderM, int.class);
            XposedHelpers.findClass("com.picovr.database.quickpanel.QuickPanelItem", cl)
                    .getConstructor(int.class, int.class, int.class, int.class, int.class, String.class);

            XposedHelpers.findAndHookMethod(manager, "A", List.class, callback, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.args[0] = saveTileEdit((List) p.args[0]);
                }
            });
            XposedHelpers.findAndHookMethod(fragment, "I", List.class, List.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { addTileToEditor((List) p.args[0], (List) p.args[1], cl); }
                    catch (Throwable t) { XposedBridge.log(TAG + ": editor load hook failed: " + t); }
                }
            });
            hookEditorLabels(added, more, holderA, holderM);
            XposedBridge.log(TAG + ": editor hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": editor hooks unavailable; runtime tile remains safe: " + t);
        }
    }

    private static void installWearLifecycleHook(ClassLoader cl) {
        try {
            Class<?> app = XposedHelpers.findClass("com.picovr.settings.SettingApplication", cl);
            XposedHelpers.findAndHookMethod(app, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { registerWearSensor(p.thisObject); startCoordinationPoll(p.thisObject); }
            });
            Object current = SettingApplication();
            if (current != null) {
                registerWearSensor(current);
                startCoordinationPoll(current);
            }
            XposedBridge.log(TAG + ": wear lifecycle hook installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": wear lifecycle hook failed: " + t); }
    }

    private static synchronized void registerWearSensor(final Object context) {
        try {
            if (sProximityListener != null) return;
            Object manager = context.getClass().getMethod("getSystemService", String.class).invoke(context, "sensor");
            Class<?> sensorClass = Class.forName("android.hardware.Sensor");
            Object sensor = manager.getClass().getMethod("getDefaultSensor", int.class).invoke(manager, 8);
            if (sensor == null) throw new IllegalStateException("proximity sensor unavailable");
            final float maxRange = ((Float) sensorClass.getMethod("getMaximumRange").invoke(sensor)).floatValue();
            final Class<?> listenerClass = Class.forName("android.hardware.SensorEventListener");
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("onSensorChanged".equals(name) && args != null && args.length == 1) {
                        try {
                            float[] values = (float[]) args[0].getClass().getField("values").get(args[0]);
                            if (values != null && values.length > 0) onWearState(context, values[0] < maxRange);
                        } catch (Throwable t) { XposedBridge.log(TAG + ": proximity event failed: " + t); }
                    }
                    if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
                    if ("equals".equals(name)) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                    if ("toString".equals(name)) return TAG + "ProximityListener";
                    return null;
                }
            });
            boolean registered = ((Boolean) manager.getClass().getMethod("registerListener", listenerClass, sensorClass, int.class)
                    .invoke(manager, listener, sensor, 3)).booleanValue();
            if (!registered) throw new IllegalStateException("proximity listener rejected");
            sAppContext = context;
            sSensorManager = manager;
            sProximityListener = listener;
            int savedWorn = getGlobalInt(context, WORN_KEY, -1);
            if (savedWorn == 0) {
                onWearState(context, false);
            } else {
                if (savedWorn == 1) putGlobalString(context, REMOVED_AT_KEY, "");
                releaseWakeLock();
                XposedBridge.log(TAG + ": current wear state unavailable; deferring to the native proximity policy");
            }
            XposedBridge.log(TAG + ": proximity listener registered maxRange=" + maxRange + " savedWorn=" + savedWorn);
        } catch (Throwable t) { XposedBridge.log(TAG + ": proximity registration failed: " + t); }
    }

    private static synchronized void onWearState(final Object context, boolean worn) {
        if (sWearKnown && sWorn == worn) {
            if (worn && (!isEnabled(context) || sWakeLock != null)) return;
            if (!worn && (!isEnabled(context) || sPendingSleep != null)) return;
        }
        sWearKnown = true;
        sWorn = worn;
        putGlobalInt(context, WORN_KEY, worn ? 1 : 0);
        cancelPendingSleep();
        if (worn) {
            putGlobalString(context, REMOVED_AT_KEY, "");
            if (isEnabled(context)) acquireWakeLock(context);
            XposedBridge.log(TAG + ": headset worn; pending sleep cancelled");
            return;
        }
        if (!isEnabled(context)) return;
        long removedAt = parseLong(getGlobalString(context, REMOVED_AT_KEY), 0L);
        long now = System.currentTimeMillis();
        if (removedAt <= 0L || removedAt > now) {
            removedAt = now;
            putGlobalString(context, REMOVED_AT_KEY, String.valueOf(removedAt));
        }
        final long delay = remainingSleepDelay(now, removedAt);
        sPendingSleep = SLEEP_EXECUTOR.schedule(new Runnable() {
            @Override public void run() {
                synchronized (VSleepHook.class) {
                    if (sWorn || !isEnabled(context)) { sPendingSleep = null; return; }
                    sPendingSleep = null;
                    releaseWakeLock();
                    requestSleep(context);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
        XposedBridge.log(TAG + ": headset removed; sleep scheduled in " + (delay / 1000L) + " seconds");
    }

    private static synchronized void cancelPendingSleep() {
        ScheduledFuture pending = sPendingSleep;
        sPendingSleep = null;
        if (pending != null) pending.cancel(false);
    }

    private static void requestSleep(Object context) {
        try {
            Object pm = context.getClass().getMethod("getSystemService", String.class).invoke(context, "power");
            long now = ((Long) Class.forName("android.os.SystemClock").getMethod("uptimeMillis").invoke(null)).longValue();
            pm.getClass().getMethod("goToSleep", long.class).invoke(pm, Long.valueOf(now));
            XposedBridge.log(TAG + ": headset remained removed; requested sleep");
        } catch (Throwable t) { XposedBridge.log(TAG + ": sleep request failed: " + t); }
    }

    private static Object editableCallback(final Object original, Class<?> callback, final ClassLoader cl) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("a".equals(method.getName()) && args != null && args.length == 2) addTileToEditor((List) args[0], (List) args[1], cl);
                return method.invoke(original, args);
            }
        });
    }

    private static Object quickPanelCallback(final Object original, Class<?> callback, final ClassLoader cl) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{callback}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("a".equals(method.getName()) && args != null && args.length == 1) addTileToQuickSettings((ArrayList) args[0], cl);
                return method.invoke(original, args);
            }
        });
    }

    private static void addTileToEditor(List added, List more, ClassLoader cl) {
        try {
            Object context = SettingApplication();
            int addedState = getGlobalInt(context, TILE_ADDED_KEY, 1);
            List target = addedState == 1 ? added : more;
            if (hasType(target, V_SLEEP_TILE)) return;
            int index = clampIndex(getGlobalInt(context, TILE_INDEX_KEY, target.size()), target.size());
            Object template = !added.isEmpty() ? added.get(0) : (!more.isEmpty() ? more.get(0) : null);
            if (template == null) throw new IllegalStateException("no stock shortcut is available as a resource template");
            target.add(index, newPanelItem(index, addedState, panelName(template), panelIcon(template), cl));
        } catch (Throwable t) { XposedBridge.log(TAG + ": editor tile injection failed: " + t); }
    }

    private static void addTileToQuickSettings(ArrayList list, ClassLoader cl) {
        try {
            Object context = SettingApplication();
            if (getGlobalInt(context, TILE_ADDED_KEY, 1) != 1 || hasButtonInfoType(list, V_SLEEP_TILE)) return;
            Class<?> info = XposedHelpers.findClass("com.picovr.quicksettings.button.QuickSettingButtonInfo", cl);
            Object item = info.newInstance();
            info.getMethod("m", int.class).invoke(item, V_SLEEP_TILE);
            int index = clampIndex(getGlobalInt(context, TILE_INDEX_KEY, 0), list.size());
            list.add(index, item);
        } catch (Throwable t) { XposedBridge.log(TAG + ": quick tile list injection failed: " + t); }
    }

    static int clampIndex(int index, int size) {
        if (index < 0) return 0;
        return index > size ? size : index;
    }

    static long remainingSleepDelay(long now, long removedAt) {
        long elapsed = now > removedAt ? now - removedAt : 0L;
        return elapsed >= UNWORN_SLEEP_DELAY_MS ? 0L : UNWORN_SLEEP_DELAY_MS - elapsed;
    }

    private static void removeDuplicateTile(ArrayList list) {
        try {
            boolean seen = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (((Integer) item.getClass().getMethod("f").invoke(item)).intValue() != V_SLEEP_TILE) continue;
                if (seen) list.remove(i); else seen = true;
            }
        } catch (Throwable t) { XposedBridge.log(TAG + ": duplicate tile cleanup failed: " + t); }
    }

    private static void hookEditorLabels(Class<?> added, Class<?> more, Class<?> holderA, Class<?> holderM) {
        XC_MethodHook label = new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam p) { setEditorTile(p.args[0], p.thisObject, ((Integer) p.args[1]).intValue()); } };
        XposedHelpers.findAndHookMethod(added, "m", holderA, int.class, label);
        XposedHelpers.findAndHookMethod(more, "c", holderM, int.class, label);
    }

    private static void setEditorTile(Object holder, Object adapter, int position) {
        try {
            java.lang.reflect.Field data = adapter.getClass().getDeclaredField("b"); data.setAccessible(true);
            Object item = ((List) data.get(adapter)).get(position);
            if (panelType(item) != V_SLEEP_TILE) return;
            boolean added = holder.getClass().getName().contains("Added");
            java.lang.reflect.Field text = holder.getClass().getDeclaredField(added ? "d" : "c");
            text.setAccessible(true); Object label = text.get(holder);
            label.getClass().getMethod("setText", CharSequence.class).invoke(label, "V-Sleep Mode");
            java.lang.reflect.Field image = holder.getClass().getDeclaredField(added ? "c" : "b");
            image.setAccessible(true); Object imageView = image.get(holder);
            Object drawable = moduleDrawable(SettingApplication());
            if (drawable != null) imageView.getClass().getMethod("setImageDrawable", Class.forName("android.graphics.drawable.Drawable")).invoke(imageView, drawable);
        } catch (Throwable t) { XposedBridge.log(TAG + ": editor tile binding failed: " + t); }
    }

    private static synchronized void configureSleepButton(Object adapter, Object holder, int position) {
        try {
            if (!isButtonInfoTypeAt(adapter, position, V_SLEEP_TILE)) return;
            java.lang.reflect.Field buttonField = holder.getClass().getDeclaredField("a");
            buttonField.setAccessible(true);
            Object button = buttonField.get(holder);
            Object context = button.getClass().getMethod("getContext").invoke(button);
            ClassLoader cl = button.getClass().getClassLoader();
            Class<?> listenerClass = Class.forName("android.view.View$OnClickListener", false, cl);
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{listenerClass}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onClick".equals(method.getName())) {
                        if (isEnabled(context) || hasSnapshot(context)) disable(context); else enable(context);
                        refreshTile(context);
                    }
                    return null;
                }
            });
            button.getClass().getMethod("setOnClickListener", listenerClass).invoke(button, listener);
            sButton = button;
            button.getClass().getMethod("post", Runnable.class).invoke(button, new Runnable() {
                @Override public void run() { refreshTile(context); }
            });
            XposedBridge.log(TAG + ": V-Sleep quick tile configured");
        } catch (Throwable t) { XposedBridge.log(TAG + ": quick tile configuration failed: " + t); }
    }

    private static void mapSleepTypeForBind(Object adapter, int position) {
        MAPPED_BIND_ITEM.remove();
        try {
            Object info = buttonInfoAt(adapter, position);
            if (buttonInfoType(info) != V_SLEEP_TILE) return;
            info.getClass().getMethod("m", int.class).invoke(info, 1);
            MAPPED_BIND_ITEM.set(info);
        } catch (Throwable t) { XposedBridge.log(TAG + ": tile bind mapping failed: " + t); }
    }
    private static void restoreSleepTypeAfterBind(Object adapter, int position) {
        Object info = MAPPED_BIND_ITEM.get();
        MAPPED_BIND_ITEM.remove();
        if (info == null) return;
        try { info.getClass().getMethod("m", int.class).invoke(info, V_SLEEP_TILE); }
        catch (Throwable t) { XposedBridge.log(TAG + ": tile bind restoration failed: " + t); }
    }
    private static Object buttonInfoAt(Object adapter, int position) throws Exception {
        java.lang.reflect.Field data = adapter.getClass().getDeclaredField("a");
        data.setAccessible(true);
        List list = (List) data.get(adapter);
        if (position < 0 || position >= list.size()) return null;
        return list.get(position);
    }
    private static int buttonInfoType(Object info) throws Exception {
        return info == null ? -1 : ((Integer) info.getClass().getMethod("f").invoke(info)).intValue();
    }
    private static boolean isButtonInfoTypeAt(Object adapter, int position, int type) {
        try {
            Object mapped = MAPPED_BIND_ITEM.get();
            return mapped != null ? mapped == buttonInfoAt(adapter, position) : buttonInfoType(buttonInfoAt(adapter, position)) == type;
        } catch (Throwable t) { return false; }
    }

    private static List saveTileEdit(List list) {
        try {
            if (list == null) return list;
            Object context = SettingApplication();
            ArrayList dbItems = new ArrayList(list);
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (panelType(item) != V_SLEEP_TILE) continue;
                putGlobalInt(context, TILE_ADDED_KEY, panelAdded(item) ? 1 : 0);
                putGlobalInt(context, TILE_INDEX_KEY, panelIndex(item));
                dbItems.remove(item); // This is module-owned metadata, not a Room database record.
                XposedBridge.log(TAG + ": saved editable tile state");
            }
            return dbItems;
        } catch (Throwable t) { XposedBridge.log(TAG + ": save tile edit failed: " + t); }
        return list;
    }

    private static Object newPanelItem(int index, int added, int name, int icon, ClassLoader cl) throws Exception {
        Class<?> item = XposedHelpers.findClass("com.picovr.database.quickpanel.QuickPanelItem", cl);
        return item.getConstructor(int.class, int.class, int.class, int.class, int.class, String.class)
                .newInstance(V_SLEEP_TILE, index, added, name, icon, "vsleep");
    }
    private static boolean hasType(List list, int type) { for (Object item : list) if (panelType(item) == type) return true; return false; }
    private static boolean hasButtonInfoType(List list, int type) { try { for (Object item : list) if (((Integer) item.getClass().getMethod("f").invoke(item)).intValue() == type) return true; } catch (Throwable ignored) {} return false; }
    private static int panelType(Object item) { try { return ((Integer) item.getClass().getMethod("f").invoke(item)).intValue(); } catch (Throwable t) { return -1; } }
    private static int panelIndex(Object item) { try { return ((Integer) item.getClass().getMethod("d").invoke(item)).intValue(); } catch (Throwable t) { return 0; } }
    private static int panelName(Object item) { try { return ((Integer) item.getClass().getMethod("e").invoke(item)).intValue(); } catch (Throwable t) { return 0; } }
    private static int panelIcon(Object item) { try { return ((Integer) item.getClass().getMethod("b").invoke(item)).intValue(); } catch (Throwable t) { return 0; } }
    private static boolean panelAdded(Object item) { try { return ((Boolean) item.getClass().getMethod("g").invoke(item)).booleanValue(); } catch (Throwable t) { return false; } }
    private static Object SettingApplication() throws Exception {
        return Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    }
    private static int moduleIcon(Object context) {
        try {
            Object packageManager = context.getClass().getMethod("getPackageManager").invoke(context);
            Object resources = packageManager.getClass().getMethod("getResourcesForApplication", String.class).invoke(packageManager, MODULE_PACKAGE);
            return ((Integer) resources.getClass().getMethod("getIdentifier", String.class, String.class, String.class).invoke(resources, "ic_vsleep", "drawable", MODULE_PACKAGE)).intValue();
        } catch (Throwable t) { XposedBridge.log(TAG + ": module icon lookup failed: " + t); return 0; }
    }
    private static Object moduleDrawable(Object context) {
        try {
            Object packageManager = context.getClass().getMethod("getPackageManager").invoke(context);
            Object resources = packageManager.getClass().getMethod("getResourcesForApplication", String.class).invoke(packageManager, MODULE_PACKAGE);
            int id = ((Integer) resources.getClass().getMethod("getIdentifier", String.class, String.class, String.class).invoke(resources, "ic_vsleep", "drawable", MODULE_PACKAGE)).intValue();
            return resources.getClass().getMethod("getDrawable", int.class).invoke(resources, id);
        } catch (Throwable t) { XposedBridge.log(TAG + ": module drawable lookup failed: " + t); return null; }
    }

    private static void refreshTile(Object c) {
        try {
            Object button = sButton;
            if (button == null) return;
            boolean enabled = effectiveUiEnabled(c);
            button.getClass().getMethod("h", boolean.class).invoke(button, enabled);
            String phase = getGlobalString(c, COORD_PHASE);
            String tip = enabled ? "V-Sleep Mode \u5df2\u5f00\u542f" : (CoordinationProtocol.PHASE_RESTORING.equals(phase) ? "\u6b63\u5728\u6062\u590d\u7535\u6e90\u6a21\u5f0f" : (CoordinationProtocol.PHASE_ERROR.equals(phase) ? "\u7535\u6e90\u6a21\u5f0f\u6062\u590d\u5931\u8d25" : "V-Sleep Mode"));
            button.getClass().getMethod("setTipText", String.class).invoke(button, tip);
            Object image = findImageView(button);
            if (image == null) return;
            Object drawable = moduleDrawable(c);
            if (drawable != null) {
                Class<?> drawableClass = Class.forName("android.graphics.drawable.Drawable");
                image.getClass().getMethod("setBackground", drawableClass).invoke(image, new Object[] { null });
                image.getClass().getMethod("setImageDrawable", drawableClass).invoke(image, drawable);
                Object layout = image.getClass().getMethod("getLayoutParams").invoke(image);
                layout.getClass().getField("width").setInt(layout, 33);
                layout.getClass().getField("height").setInt(layout, 33);
                image.getClass().getMethod("setLayoutParams", Class.forName("android.view.ViewGroup$LayoutParams")).invoke(image, layout);
                Class<?> scaleType = Class.forName("android.widget.ImageView$ScaleType");
                image.getClass().getMethod("setScaleType", scaleType).invoke(image, Enum.valueOf((Class) scaleType, "CENTER_INSIDE"));
            }
        } catch (Throwable t) { XposedBridge.log(TAG + ": tile refresh failed: " + t); }
    }
    private static Object findImageView(Object view) {
        try {
            if (Class.forName("android.widget.ImageView").isInstance(view)) return view;
            int count = ((Integer) view.getClass().getMethod("getChildCount").invoke(view)).intValue();
            for (int i = 0; i < count; i++) {
                Object found = findImageView(view.getClass().getMethod("getChildAt", int.class).invoke(view, i));
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static boolean isEnabled(Object c) { return getGlobalInt(c, MODE_KEY, 0) == 1; }
    private static boolean effectiveUiEnabled(Object c) {
        return CoordinationProtocol.effectiveUiEnabled(getGlobalString(c, COORD_EFFECTIVE_OWNER), getGlobalString(c, COORD_PHASE), isEnabled(c));
    }
    private static boolean hasSnapshot(Object c) { return getGlobalInt(c, SNAPSHOT_KEY, 0) == 1; }

    private static synchronized void enable(Object c) {
        if (getGlobalInt(c, "pvr_never_sleep_enabled", 0) == 1) {
            XposedBridge.log(TAG + ": enable refused while PicoNeverSleep owns sleep policy");
            return;
        }
        if (isEnabled(c)) return;
        if (hasSnapshot(c) || hasCoordSnapshot(c)) {
            XposedBridge.log(TAG + ": refusing enable while a previous snapshot needs restoration");
            return;
        }
        String token = newToken();
        String request = CoordinationProtocol.request(token, "vsleep", "enable");
        if (request == null || !putGlobalString(c, COORD_REQUEST, request)) return;
        if (!isLatestRequest(c, request)) return;
        Snapshot snapshot = captureSnapshot(c);
        if (snapshot == null || !saveSnapshot(c, snapshot) || !beginCoordination(c)) {
            putGlobalString(c, COORD_ERROR, "\u5feb\u7167\u5931\u8d25");
            return;
        }
        if (!isLatestRequest(c, request) || hasPowerRequest(c, request)) return;
        if (!applySleepState(c, snapshot.governors)) {
            putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
            return;
        }
        if (!isLatestRequest(c, request)) {
            XposedBridge.log(TAG + ": V-Sleep request was superseded during apply; recovery is delegated to poll");
            return;
        }
        // MODE_KEY is the final commit point for enabling V-Sleep.
        if (!putGlobalInt(c, COORD_ACTIVE, 1)
                || !putGlobalString(c, COORD_EFFECTIVE_OWNER, "vsleep")
                || !putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ACTIVE)
                || !putGlobalString(c, COORD_ACK, request)
                || !putGlobalInt(c, MODE_KEY, 1)) {
            putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
            return;
        }
        advanceGeneration(c);
        if (sWearKnown) onWearState(c, sWorn);
        else XposedBridge.log(TAG + ": V-Sleep enabled with native proximity policy until wear state is known");
    }

    private static synchronized void disable(Object c) {
        cancelPendingSleep();
        putGlobalString(c, REMOVED_AT_KEY, "");
        try {
            if (!hasSnapshot(c) && !hasCoordSnapshot(c) && !migrateLegacySnapshot(c)) {
                XposedBridge.log(TAG + ": V-Sleep disable aborted: no valid snapshot is available");
                return;
            }
            Snapshot snapshot = readSnapshot(c);
            if (snapshot == null) {
                XposedBridge.log(TAG + ": V-Sleep disable aborted: snapshot is incomplete");
                return;
            }
            if (!restoreSnapshot(c, snapshot)) {
                XposedBridge.log(TAG + ": V-Sleep exit failed; keeping active transaction for retry");
                return;
            }
            boolean cleaned = putGlobalInt(c, COORD_ACTIVE, 0);
            cleaned = putGlobalInt(c, MODE_KEY, 0) && cleaned;
            cleaned = clearSnapshot(c) && cleaned;
            cleaned = clearCoordination(c) && cleaned;
            if (!cleaned) {
                XposedBridge.log(TAG + ": V-Sleep exit completed but transaction cleanup failed");
                return;
            }
            advanceGeneration(c);
            XposedBridge.log(TAG + ": V-Sleep disabled; restored pre-sleep baseline");
        } finally {
            releaseWakeLock();
        }
    }

    private static synchronized void acquireWakeLock(Object c) {
        try {
            if (sWakeLock != null) return;
            Object pm = c.getClass().getMethod("getSystemService", String.class).invoke(c, "power");
            Object wl = pm.getClass().getMethod("newWakeLock", int.class, String.class)
                    .invoke(pm, 0x0000000a, TAG + ":WakeLock");
            wl.getClass().getMethod("acquire").invoke(wl);
            sWakeLock = wl;
            XposedBridge.log(TAG + ": screen wakelock acquired while headset is worn");
        } catch (Throwable t) { XposedBridge.log(TAG + ": failed to acquire wakelock: " + t); }
    }

    private static synchronized void releaseWakeLock() {
        Object wakeLock = sWakeLock;
        sWakeLock = null;
        if (wakeLock == null) return;
        try {
            wakeLock.getClass().getMethod("release").invoke(wakeLock);
            XposedBridge.log(TAG + ": wakelock released");
        } catch (Throwable t) { XposedBridge.log(TAG + ": failed to release wakelock: " + t); }
    }

    private static boolean migrateLegacySnapshot(Object c) {
        if (!isEnabled(c)) return false;
        String width = saved(c, "eyebuffer_w"); String height = saved(c, "eyebuffer_h"); String ffr = saved(c, "ffr"); String enableFfr = saved(c, "enable_ffr"); String foveation = saved(c, "foveation"); String fps = saved(c, "fps");
        int brightness = parseInt(saved(c, "brightness"), -1); String governor = saved(c, "governor"); Map current = readGovernors();
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || isEmpty(enableFfr) || isEmpty(foveation) || brightness < 0 || !validGovernor(governor) || current.isEmpty()) return false;
        Snapshot legacy = new Snapshot(width, height, ffr, enableFfr, foveation, fps == null ? "" : fps, brightness, governorsWithValue(current, governor));
        boolean migrated = saveSnapshot(c, legacy);
        if (migrated) XposedBridge.log(TAG + ": migrated legacy V-Sleep snapshot for " + current.size() + " CPU policies");
        return migrated;
    }

    private static Snapshot captureSnapshot(Object c) {
        String width = getProp(PROP_EYEBUFFER_W); String height = getProp(PROP_EYEBUFFER_H); String ffr = getProp(PROP_FFR); String enableFfr = getProp(PROP_ENABLE_FFR); String foveation = getProp(PROP_FOVEATION); String fps = getProp(PROP_FPS);
        int brightness = getSystemInt(c, "screen_brightness", -1); Map governors = readGovernors();
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || isEmpty(enableFfr) || isEmpty(foveation) || brightness < 0 || governors.isEmpty()) {
            XposedBridge.log(TAG + ": invalid snapshot width=" + width + " height=" + height + " ffr=" + ffr + " brightness=" + brightness + " governors=" + governors.size());
            return null;
        }
        return new Snapshot(width, height, ffr, enableFfr, foveation, fps, brightness, governors);
    }

    private static boolean saveSnapshot(Object c, Snapshot s) {
        boolean saved = save(c, "eyebuffer_w", s.width); saved = save(c, "eyebuffer_h", s.height) && saved;
        saved = save(c, "ffr", s.ffr) && saved; saved = save(c, "enable_ffr", s.enableFfr) && saved;
        saved = save(c, "foveation", s.foveation) && saved; saved = save(c, "fps", s.fps) && saved;
        saved = save(c, "brightness", String.valueOf(s.brightness)) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_w", s.width) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_h", s.height) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "ffr", s.ffr) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "enable_ffr", s.enableFfr) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "foveation", s.foveation) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "fps", s.fps) && saved;
        saved = putGlobalInt(c, COORD_SNAPSHOT_PREFIX + "brightness", s.brightness) && saved;
        for (Object entryObject : s.governors.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            String policy = (String) entry.getKey(); String governor = (String) entry.getValue();
            saved = save(c, GOVERNOR_PREFIX + policy, governor) && saved;
            saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + GOVERNOR_PREFIX + policy, governor) && saved;
        }
        return saved && putGlobalInt(c, SNAPSHOT_KEY, 1) && putGlobalInt(c, COORD_SNAPSHOT_VALID, 1);
    }

    private static Snapshot readSnapshot(Object c) {
        boolean coordinated = hasCoordSnapshot(c);
        String width = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_w") : saved(c, "eyebuffer_w");
        String height = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_h") : saved(c, "eyebuffer_h");
        String ffr = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "ffr") : saved(c, "ffr");
        String enableFfr = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "enable_ffr") : saved(c, "enable_ffr");
        String foveation = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "foveation") : saved(c, "foveation");
        // v1 never changed these properties, so the live values are their baseline during migration.
        if (isEmpty(enableFfr)) enableFfr = getProp(PROP_ENABLE_FFR);
        if (isEmpty(foveation)) foveation = getProp(PROP_FOVEATION);
        String fps = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "fps") : saved(c, "fps");
        int brightness = coordinated ? getGlobalInt(c, COORD_SNAPSHOT_PREFIX + "brightness", -1)
                : parseInt(saved(c, "brightness"), -1);
        Map governors = coordinated ? readCoordGovernors(c) : readSavedGovernors(c);
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || isEmpty(enableFfr) || isEmpty(foveation) || brightness < 0 || governors.isEmpty()) return null;
        return new Snapshot(width, height, ffr, enableFfr, foveation, fps == null ? "" : fps, brightness, governors);
    }

    private static boolean applySleepState(Object c, Map originalGovernors) {
        boolean applied = setProp(PROP_EYEBUFFER_W, String.valueOf(SLEEP_EYEBUFFER));
        applied = setProp(PROP_EYEBUFFER_H, String.valueOf(SLEEP_EYEBUFFER)) && applied;
        applied = setProp(PROP_FFR, "1") && applied;
        applied = putSystemInt(c, "screen_brightness", 1) && applied;
        return setGovernors(governorsWithValue(originalGovernors, "powersave")) && applied;
    }

    private static boolean restoreSnapshot(Object c, Snapshot s) {
        boolean restored = setProp(PROP_EYEBUFFER_W, s.width); restored = setProp(PROP_EYEBUFFER_H, s.height) && restored;
        restored = setProp(PROP_FFR, s.ffr) && restored;
        restored = setProp(PROP_ENABLE_FFR, s.enableFfr) && restored;
        restored = setProp(PROP_FOVEATION, s.foveation) && restored;
        restored = setProp(PROP_FPS, s.fps) && restored;
        restored = putSystemInt(c, "screen_brightness", s.brightness) && restored;
        return setGovernors(s.governors) && restored;
    }

    private static boolean beginCoordination(Object c) {
        return putGlobalInt(c, COORD_VERSION, COORD_PROTOCOL_VERSION)
                && putGlobalString(c, COORD_OWNER, "vsleep")
                && putGlobalString(c, COORD_EFFECTIVE_OWNER, "vsleep")
                && putGlobalString(c, COORD_PHASE, "preparing")
                && putGlobalInt(c, COORD_ACTIVE, 0);
    }

    private static String newToken() { return UUID.randomUUID().toString(); }

    private static boolean isLatestRequest(Object c, String request) {
        return request != null && request.equals(getGlobalString(c, COORD_REQUEST));
    }

    private static boolean hasPowerRequest(Object c, String ownRequest) {
        CoordinationProtocol.Request r = CoordinationProtocol.parse(getGlobalString(c, COORD_REQUEST));
        return r != null && "power".equals(r.owner) && !ownRequest.equals(r.raw);
    }

    private static synchronized void startCoordinationPoll(final Object c) {
        if (sCoordinationPollStarted) return;
        sCoordinationPollStarted = true;
        SLEEP_EXECUTOR.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { pollPowerRequest(c); }
        }, COORD_POLL_MS, COORD_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private static synchronized void pollPowerRequest(Object c) {
        if (!isEnabled(c) && !hasSnapshot(c) && !hasCoordSnapshot(c)
                && getGlobalInt(c, PNS_VSLEEP_WAS_ENABLED, 0) != 1) return;
        CoordinationProtocol.Request request = CoordinationProtocol.parse(getGlobalString(c, COORD_REQUEST));
        if (request == null || !"power".equals(request.owner)) return;
        String ack = getGlobalString(c, COORD_ACK);
        if (request.raw.equals(ack)) return;
        putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_RESTORING);
        postRefresh(c);
        cancelPendingSleep();
        releaseWakeLock();
        if (hasSnapshot(c) || hasCoordSnapshot(c)) {
            Snapshot snapshot = readSnapshot(c);
            if (snapshot == null || !restoreSnapshot(c, snapshot)) {
                putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
                putGlobalString(c, COORD_ERROR, "\u6062\u590d\u5931\u8d25");
                return;
            }
            if (!clearSnapshot(c) || !clearCoordination(c)) {
                putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
                putGlobalString(c, COORD_ERROR, "\u6e05\u7406\u5931\u8d25");
                return;
            }
        }
        if (!putGlobalInt(c, MODE_KEY, 0)) {
            putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
            putGlobalString(c, COORD_ERROR, "\u6a21\u5f0f\u6e05\u9664\u5931\u8d25");
            return;
        }
        CoordinationProtocol.Request latest = CoordinationProtocol.parse(getGlobalString(c, COORD_REQUEST));
        if (latest == null || !"power".equals(latest.owner)) return;
        boolean restoreVSleep = "disable".equals(latest.payload)
                && getGlobalInt(c, PNS_VSLEEP_WAS_ENABLED, 0) == 1;
        if (!putGlobalString(c, COORD_ACK, latest.raw)) {
            putGlobalString(c, COORD_PHASE, CoordinationProtocol.PHASE_ERROR);
            putGlobalString(c, COORD_ERROR, "确认失败");
            return;
        }
        putGlobalString(c, COORD_EFFECTIVE_OWNER, "power:" + latest.payload);
        putGlobalString(c, COORD_PHASE, "idle");
        if (restoreVSleep) {
            putGlobalInt(c, PNS_VSLEEP_WAS_ENABLED, 0);
            putGlobalString(c, COORD_REQUEST, "");
            enable(c);
            XposedBridge.log(TAG + ": restored V-Sleep after PicoNeverSleep release");
        }
        postRefresh(c);
    }

    private static void postRefresh(final Object c) {
        try {
            Object button = sButton;
            if (button != null) button.getClass().getMethod("post", Runnable.class).invoke(button, new Runnable() {
                @Override public void run() { refreshTile(c); }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": UI refresh post failed: " + t); }
    }
    private static boolean hasCoordSnapshot(Object c) { return getGlobalInt(c, COORD_SNAPSHOT_VALID, 0) == 1; }
    private static boolean clearSnapshot(Object c) { return putGlobalInt(c, SNAPSHOT_KEY, 0); }
    private static boolean clearCoordination(Object c) {
        return putGlobalInt(c, COORD_SNAPSHOT_VALID, 0)
                && putGlobalInt(c, COORD_ACTIVE, 0)
                && putGlobalString(c, COORD_OWNER, "");
    }
    private static void advanceGeneration(Object c) {
        int generation = getGlobalInt(c, COORD_GENERATION, 0);
        if (!putGlobalInt(c, COORD_GENERATION, generation + 1)) {
            XposedBridge.log(TAG + ": unable to advance coordination generation");
        }
    }
    private static boolean isEmpty(String value) { return value == null || value.length() == 0; }

    private static final class Snapshot {
        final String width, height, ffr, enableFfr, foveation, fps;
        final int brightness;
        final Map governors;
        Snapshot(String width, String height, String ffr, String enableFfr, String foveation, String fps, int brightness, Map governors) {
            this.width = width; this.height = height; this.ffr = ffr; this.enableFfr = enableFfr; this.foveation = foveation; this.fps = fps; this.brightness = brightness; this.governors = governors;
        }
    }

    private static String getProp(String k) { try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, k); } catch (Throwable t) { XposedBridge.log(TAG + ": cannot read " + k + ": " + t); return null; } }
    private static boolean setProp(String k, String v) {
        try {
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, k, v);
            if (v.equals(getProp(k))) return true;
            XposedBridge.log(TAG + ": property write verification failed " + k + "=" + v);
        } catch (Throwable t) { XposedBridge.log(TAG + ": cannot set " + k + ": " + t); }
        return false;
    }
    private static Object resolver(Object c) throws Exception { return c.getClass().getMethod("getContentResolver").invoke(c); }
    private static int getGlobalInt(Object c, String k, int d) { return getInt("android.provider.Settings$Global", c, k, d); }
    private static int getSystemInt(Object c, String k, int d) { return getInt("android.provider.Settings$System", c, k, d); }
    private static int getInt(String cls, Object c, String k, int d) { try { return ((Integer) Class.forName(cls).getMethod("getInt", Class.forName("android.content.ContentResolver"), String.class, int.class).invoke(null, resolver(c), k, d)).intValue(); } catch (Throwable t) { XposedBridge.log(TAG + ": setting read failed " + k + ": " + t); return d; } }
    private static boolean putGlobalInt(Object c, String k, int v) { return putInt("android.provider.Settings$Global", c, k, v); }
    private static boolean putSystemInt(Object c, String k, int v) { return putInt("android.provider.Settings$System", c, k, v); }
    private static String getGlobalString(Object c, String k) {
        try { return (String) Class.forName("android.provider.Settings$Global").getMethod("getString", Class.forName("android.content.ContentResolver"), String.class).invoke(null, resolver(c), k); }
        catch (Throwable t) { XposedBridge.log(TAG + ": string read failed " + k + ": " + t); return null; }
    }
    private static boolean putGlobalString(Object c, String k, String v) {
        try {
            Boolean written = (Boolean) Class.forName("android.provider.Settings$Global").getMethod("putString", Class.forName("android.content.ContentResolver"), String.class, String.class).invoke(null, resolver(c), k, v);
            if (written.booleanValue() && v.equals(getGlobalString(c, k))) return true;
            XposedBridge.log(TAG + ": string write verification failed " + k);
        } catch (Throwable t) { XposedBridge.log(TAG + ": string write failed " + k + ": " + t); }
        return false;
    }
    private static boolean putInt(String cls, Object c, String k, int v) { try { Boolean written = (Boolean) Class.forName(cls).getMethod("putInt", Class.forName("android.content.ContentResolver"), String.class, int.class).invoke(null, resolver(c), k, v); if (written.booleanValue() && getInt(cls, c, k, Integer.MIN_VALUE) == v) return true; XposedBridge.log(TAG + ": setting write verification failed " + k + "=" + v); } catch (Throwable t) { XposedBridge.log(TAG + ": setting write failed " + k + ": " + t); } return false; }
    private static boolean save(Object c, String s, String v) { try { Boolean written = (Boolean) Class.forName("android.provider.Settings$Global").getMethod("putString", Class.forName("android.content.ContentResolver"), String.class, String.class).invoke(null, resolver(c), SAVED_PREFIX + s, v); if (written.booleanValue() && v.equals(saved(c, s))) return true; XposedBridge.log(TAG + ": save verification failed " + s); } catch (Throwable t) { XposedBridge.log(TAG + ": save failed " + s + ": " + t); } return false; }
    private static String saved(Object c, String s) { try { return (String) Class.forName("android.provider.Settings$Global").getMethod("getString", Class.forName("android.content.ContentResolver"), String.class).invoke(null, resolver(c), SAVED_PREFIX + s); } catch (Throwable t) { XposedBridge.log(TAG + ": saved value read failed " + s + ": " + t); return null; } }
    private static int parseInt(String v, int d) { try { return Integer.parseInt(v); } catch (Throwable t) { return d; } }
    private static long parseLong(String v, long d) { try { return Long.parseLong(v); } catch (Throwable t) { return d; } }

    private static Map readGovernors() {
        Map governors = new HashMap(); File[] policies = new File(CPU_POLICY_PATH).listFiles();
        if (policies == null) return governors;
        for (int i = 0; i < policies.length; i++) {
            File governor = new File(policies[i], "scaling_governor");
            if (!governor.isFile()) continue;
            try { String value = new String(java.nio.file.Files.readAllBytes(governor.toPath())).trim(); if (validGovernor(value)) governors.put(policies[i].getName(), value); else XposedBridge.log(TAG + ": invalid governor at " + governor); }
            catch (Throwable t) { XposedBridge.log(TAG + ": governor read failed " + governor + ": " + t); }
        }
        return governors;
    }
    private static Map readSavedGovernors(Object c) {
        Map current = readGovernors(); Map savedGovernors = new HashMap();
        for (Object policyObject : current.keySet()) { String policy = (String) policyObject; String governor = saved(c, GOVERNOR_PREFIX + policy); if (!validGovernor(governor)) return new HashMap(); savedGovernors.put(policy, governor); }
        return savedGovernors;
    }
    private static Map readCoordGovernors(Object c) {
        Map current = readGovernors(); Map savedGovernors = new HashMap();
        for (Object policyObject : current.keySet()) {
            String policy = (String) policyObject;
            String governor = getGlobalString(c, COORD_SNAPSHOT_PREFIX + GOVERNOR_PREFIX + policy);
            if (!validGovernor(governor)) return new HashMap();
            savedGovernors.put(policy, governor);
        }
        return savedGovernors;
    }
    private static Map governorsWithValue(Map governors, String value) { Map values = new HashMap(); for (Object policy : governors.keySet()) values.put(policy, value); return values; }
    private static boolean setGovernors(Map governors) {
        boolean set = true;
        for (Object entryObject : governors.entrySet()) { Map.Entry entry = (Map.Entry) entryObject; String policy = (String) entry.getKey(); String governor = (String) entry.getValue(); set = setGovernor(policy, governor) && set; }
        return set;
    }
    private static boolean validGovernor(String governor) { return governor != null && governor.matches("[A-Za-z0-9_.-]+"); }
    private static boolean setGovernor(String policy, String governor) {
        if (!policy.matches("policy[0-9]+") || !validGovernor(governor)) { XposedBridge.log(TAG + ": invalid governor write request " + policy + "=" + governor); return false; }
        File file = new File(new File(CPU_POLICY_PATH, policy), "scaling_governor");
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo " + governor + " > " + file.getPath()});
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                XposedBridge.log(TAG + ": governor write timed out " + file);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) { XposedBridge.log(TAG + ": governor write failed " + file + " exit=" + exitCode); return false; }
            String actual = new String(java.nio.file.Files.readAllBytes(file.toPath())).trim();
            if (governor.equals(actual)) return true;
            XposedBridge.log(TAG + ": governor write verification failed " + file + " expected=" + governor + " actual=" + actual);
        } catch (Throwable t) { XposedBridge.log(TAG + ": governor write failed " + file + ": " + t); }
        return false;
    }
}
