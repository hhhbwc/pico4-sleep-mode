package com.picoxr.vsleep;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private static final String SAVED_PREFIX = "pico_vsleep_saved_";
    private static final String SNAPSHOT_KEY = "pico_vsleep_snapshot_valid";
    private static final String GOVERNOR_PREFIX = "governor_";
    private static final String CPU_POLICY_PATH = "/sys/devices/system/cpu/cpufreq";
    private static final String PROP_EYEBUFFER_W = "persist.pvr.config.eyebuffer_width";
    private static final String PROP_EYEBUFFER_H = "persist.pvr.config.eyebuffer_height";
    private static final String PROP_FFR = "persist.pvr.config.ffr";
    private static final String PROP_FPS = "persist.pvr.config.target_fps";
    private static final String MODULE_PACKAGE = "com.picoxr.vsleep";
    private static final String COORD_PREFIX = "pico_power_coord_";
    private static final String COORD_VERSION = COORD_PREFIX + "version";
    private static final String COORD_OWNER = COORD_PREFIX + "owner";
    private static final String COORD_ACTIVE = COORD_PREFIX + "sleep_active";
    private static final String COORD_GENERATION = COORD_PREFIX + "generation";
    private static final String COORD_SNAPSHOT_VALID = COORD_PREFIX + "snapshot_valid";
    private static final String COORD_SNAPSHOT_PREFIX = COORD_PREFIX + "snapshot_";
    private static final String COORD_POWER_MODE = COORD_PREFIX + "requested_power_mode";
    private static final int COORD_PROTOCOL_VERSION = 1;
    private static volatile Object sButton;

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
            XposedBridge.log(TAG + ": quick-settings hooks installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": failed to hook quick-settings adapter: " + t); }
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
            List target = getGlobalInt(context, TILE_ADDED_KEY, 1) == 1 ? added : more;
            if (hasType(target, V_SLEEP_TILE)) return;
            target.add(newPanelItem(getGlobalInt(context, TILE_INDEX_KEY, target.size()), getGlobalInt(context, TILE_ADDED_KEY, 1), cl));
            if (target == added) Collections.sort(added, new Comparator() { public int compare(Object a, Object b) { return panelIndex(a) - panelIndex(b); } });
        } catch (Throwable t) { XposedBridge.log(TAG + ": editor tile injection failed: " + t); }
    }

    private static void addTileToQuickSettings(ArrayList list, ClassLoader cl) {
        try {
            Object context = SettingApplication();
            if (hasButtonInfoType(list, V_SLEEP_TILE)) return;
            Class<?> info = XposedHelpers.findClass("com.picovr.quicksettings.button.QuickSettingButtonInfo", cl);
            Object item = info.newInstance();
            info.getMethod("m", int.class).invoke(item, V_SLEEP_TILE);
            list.add(0, item);
        } catch (Throwable t) { XposedBridge.log(TAG + ": quick tile list injection failed: " + t); }
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

    private static void hookEditorLabels(ClassLoader cl) {
        try {
            final Class<?> added = XposedHelpers.findClass("com.picovr.adapters.QuickPanelAddedAdapter", cl);
            final Class<?> more = XposedHelpers.findClass("com.picovr.adapters.QuickPanelMoreAdapter", cl);
            Class<?> holderA = XposedHelpers.findClass("com.picovr.adapters.QuickPanelAddedAdapter$AddedHolder", cl);
            Class<?> holderM = XposedHelpers.findClass("com.picovr.adapters.QuickPanelMoreAdapter$MoreHolder", cl);
            XC_MethodHook label = new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam p) { setEditorLabel(p.args[0], p.thisObject, ((Integer) p.args[1]).intValue()); } };
            XposedHelpers.findAndHookMethod(added, "m", holderA, int.class, label);
            XposedHelpers.findAndHookMethod(more, "c", holderM, int.class, label);
        } catch (Throwable t) { XposedBridge.log(TAG + ": editor label hooks failed: " + t); }
    }

    private static void setEditorLabel(Object holder, Object adapter, int position) {
        try {
            java.lang.reflect.Field data = adapter.getClass().getDeclaredField("b"); data.setAccessible(true);
            Object item = ((List) data.get(adapter)).get(position);
            if (panelType(item) != V_SLEEP_TILE) return;
            java.lang.reflect.Field text = holder.getClass().getDeclaredField(holder.getClass().getName().contains("Added") ? "d" : "c");
            text.setAccessible(true); Object label = text.get(holder);
            label.getClass().getMethod("setText", CharSequence.class).invoke(label, "V-Sleep");
        } catch (Throwable t) { XposedBridge.log(TAG + ": editor label failed: " + t); }
    }

    private static synchronized void configureSleepButton(Object adapter, Object holder, int position) {
        try {
            if (position != 0) return;
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

    private static void mapSleepTypeForBind(Object adapter, int position) { setSleepType(adapter, position, V_SLEEP_TILE, 1); }
    private static void restoreSleepTypeAfterBind(Object adapter, int position) { setSleepType(adapter, position, 1, V_SLEEP_TILE); }
    private static void setSleepType(Object adapter, int position, int expected, int replacement) {
        try {
            if (position != 0) return;
            java.lang.reflect.Field data = adapter.getClass().getDeclaredField("a"); data.setAccessible(true);
            Object info = ((List) data.get(adapter)).get(position);
            if (((Integer) info.getClass().getMethod("f").invoke(info)).intValue() == expected) info.getClass().getMethod("m", int.class).invoke(info, replacement);
        } catch (Throwable t) { XposedBridge.log(TAG + ": tile bind mapping failed: " + t); }
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

    private static Object newPanelItem(int index, int added, ClassLoader cl) throws Exception {
        Class<?> item = XposedHelpers.findClass("com.picovr.database.quickpanel.QuickPanelItem", cl);
        int icon = moduleIcon(SettingApplication());
        return item.getConstructor(int.class, int.class, int.class, int.class, int.class, String.class).newInstance(V_SLEEP_TILE, index, added, 0, icon, "vsleep");
    }
    private static boolean hasType(List list, int type) { for (Object item : list) if (panelType(item) == type) return true; return false; }
    private static boolean hasButtonInfoType(List list, int type) { try { for (Object item : list) if (((Integer) item.getClass().getMethod("f").invoke(item)).intValue() == type) return true; } catch (Throwable ignored) {} return false; }
    private static int panelType(Object item) { try { return ((Integer) item.getClass().getMethod("f").invoke(item)).intValue(); } catch (Throwable t) { return -1; } }
    private static int panelIndex(Object item) { try { return ((Integer) item.getClass().getMethod("d").invoke(item)).intValue(); } catch (Throwable t) { return 0; } }
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
            boolean enabled = isEnabled(c);
            button.getClass().getMethod("h", boolean.class).invoke(button, enabled);
            button.getClass().getMethod("setTipText", String.class).invoke(button, enabled ? "V-Sleep Mode 已开启" : "V-Sleep Mode");
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
    private static boolean hasSnapshot(Object c) { return getGlobalInt(c, SNAPSHOT_KEY, 0) == 1; }

    private static synchronized void enable(Object c) {
        if (isEnabled(c)) return;
        if (hasSnapshot(c) || hasCoordSnapshot(c)) {
            XposedBridge.log(TAG + ": refusing enable while a previous snapshot needs restoration");
            return;
        }
        Snapshot snapshot = captureSnapshot(c);
        if (snapshot == null || !saveSnapshot(c, snapshot) || !beginCoordination(c)) {
            XposedBridge.log(TAG + ": V-Sleep enable aborted: could not create a complete transaction");
            return;
        }
        if (!applySleepState(c, snapshot.governors)) {
            XposedBridge.log(TAG + ": V-Sleep enable failed; keeping transaction for recovery");
            return;
        }
        if (!putGlobalInt(c, MODE_KEY, 1) || !putGlobalInt(c, COORD_ACTIVE, 1)) {
            XposedBridge.log(TAG + ": V-Sleep enable could not commit state; keeping transaction for recovery");
            return;
        }
        advanceGeneration(c);
        XposedBridge.log(TAG + ": V-Sleep enabled under shared coordination");
    }

    private static synchronized void disable(Object c) {
        if (!hasSnapshot(c) && !hasCoordSnapshot(c) && !migrateLegacySnapshot(c)) {
            XposedBridge.log(TAG + ": V-Sleep disable aborted: no valid snapshot is available");
            return;
        }
        Snapshot snapshot = readSnapshot(c);
        if (snapshot == null) {
            XposedBridge.log(TAG + ": V-Sleep disable aborted: snapshot is incomplete");
            return;
        }
        int requestedMode = getGlobalInt(c, COORD_POWER_MODE, -1);
        boolean restored = requestedMode >= 0 && requestedMode <= 2
                ? applyRequestedPowerMode(c, requestedMode) : restoreSnapshot(c, snapshot);
        if (!restored) {
            XposedBridge.log(TAG + ": V-Sleep exit failed; keeping active transaction for retry");
            return;
        }
        if (!putGlobalInt(c, COORD_ACTIVE, 0) || !putGlobalInt(c, MODE_KEY, 0)
                || !clearSnapshot(c) || !clearCoordination(c)) {
            XposedBridge.log(TAG + ": V-Sleep exit completed but transaction cleanup failed");
            return;
        }
        advanceGeneration(c);
        XposedBridge.log(TAG + ": V-Sleep disabled; "
                + (requestedMode >= 0 ? "applied deferred power mode " + requestedMode : "restored baseline"));
    }

    private static boolean migrateLegacySnapshot(Object c) {
        if (!isEnabled(c)) return false;
        String width = saved(c, "eyebuffer_w"); String height = saved(c, "eyebuffer_h"); String ffr = saved(c, "ffr"); String fps = saved(c, "fps");
        int brightness = parseInt(saved(c, "brightness"), -1); String governor = saved(c, "governor"); Map current = readGovernors();
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || brightness < 0 || !validGovernor(governor) || current.isEmpty()) return false;
        Snapshot legacy = new Snapshot(width, height, ffr, fps == null ? "" : fps, brightness, governorsWithValue(current, governor));
        boolean migrated = saveSnapshot(c, legacy);
        if (migrated) XposedBridge.log(TAG + ": migrated legacy V-Sleep snapshot for " + current.size() + " CPU policies");
        return migrated;
    }

    private static Snapshot captureSnapshot(Object c) {
        String width = getProp(PROP_EYEBUFFER_W); String height = getProp(PROP_EYEBUFFER_H); String ffr = getProp(PROP_FFR); String fps = getProp(PROP_FPS);
        int brightness = getSystemInt(c, "screen_brightness", -1); Map governors = readGovernors();
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || brightness < 0 || governors.isEmpty()) {
            XposedBridge.log(TAG + ": invalid snapshot width=" + width + " height=" + height + " ffr=" + ffr + " brightness=" + brightness + " governors=" + governors.size());
            return null;
        }
        return new Snapshot(width, height, ffr, fps, brightness, governors);
    }

    private static boolean saveSnapshot(Object c, Snapshot s) {
        boolean saved = save(c, "eyebuffer_w", s.width); saved = save(c, "eyebuffer_h", s.height) && saved;
        saved = save(c, "ffr", s.ffr) && saved; saved = save(c, "fps", s.fps) && saved;
        saved = save(c, "brightness", String.valueOf(s.brightness)) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_w", s.width) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "eyebuffer_h", s.height) && saved;
        saved = putGlobalString(c, COORD_SNAPSHOT_PREFIX + "ffr", s.ffr) && saved;
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
        String fps = coordinated ? getGlobalString(c, COORD_SNAPSHOT_PREFIX + "fps") : saved(c, "fps");
        int brightness = coordinated ? getGlobalInt(c, COORD_SNAPSHOT_PREFIX + "brightness", -1)
                : parseInt(saved(c, "brightness"), -1);
        Map governors = coordinated ? readCoordGovernors(c) : readSavedGovernors(c);
        if (isEmpty(width) || isEmpty(height) || isEmpty(ffr) || brightness < 0 || governors.isEmpty()) return null;
        return new Snapshot(width, height, ffr, fps == null ? "" : fps, brightness, governors);
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
        if (s.fps.length() > 0) restored = setProp(PROP_FPS, s.fps) && restored;
        restored = putSystemInt(c, "screen_brightness", s.brightness) && restored;
        return setGovernors(s.governors) && restored;
    }

    private static boolean beginCoordination(Object c) {
        return putGlobalInt(c, COORD_VERSION, COORD_PROTOCOL_VERSION)
                && putGlobalString(c, COORD_OWNER, "vsleep")
                && putGlobalInt(c, COORD_ACTIVE, 0);
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
    private static boolean applyRequestedPowerMode(Object c, int mode) {
        try {
            ClassLoader cl = c.getClass().getClassLoader();
            Class<?> context = Class.forName("android.content.Context", false, cl);
            Class<?> dsu = XposedHelpers.findClass("com.picovr.settings.custom.DeviceSwitchUtilsKt", cl);
            dsu.getMethod("e", context, int.class).invoke(null, c, mode);
            String buffer = mode == 2 ? "2448" : "1504";
            return setProp(PROP_EYEBUFFER_W, buffer) && setProp(PROP_EYEBUFFER_H, buffer);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": deferred power mode apply failed: " + t);
            return false;
        }
    }
    private static boolean isEmpty(String value) { return value == null || value.length() == 0; }

    private static final class Snapshot {
        final String width, height, ffr, fps;
        final int brightness;
        final Map governors;
        Snapshot(String width, String height, String ffr, String fps, int brightness, Map governors) {
            this.width = width; this.height = height; this.ffr = ffr; this.fps = fps; this.brightness = brightness; this.governors = governors;
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
