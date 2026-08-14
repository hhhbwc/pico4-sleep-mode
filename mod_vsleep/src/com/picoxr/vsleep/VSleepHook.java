package com.picoxr.vsleep;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
    private static final String PROP_EYEBUFFER_W = "persist.pvr.config.eyebuffer_width";
    private static final String PROP_EYEBUFFER_H = "persist.pvr.config.eyebuffer_height";
    private static final String PROP_FFR = "persist.pvr.config.ffr";
    private static final String PROP_FPS = "persist.pvr.config.target_fps";
    private static final String MODULE_PACKAGE = "com.picoxr.vsleep";
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
                @Override protected void afterHookedMethod(MethodHookParam p) { configureSleepButton(p.thisObject, p.args[0], ((Integer) p.args[1]).intValue()); }
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
                        if (isEnabled(context)) disable(context); else enable(context);
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
        try {
            if (position != 0) return;
            java.lang.reflect.Field data = adapter.getClass().getDeclaredField("a"); data.setAccessible(true);
            Object info = ((List) data.get(adapter)).get(position);
            if (((Integer) info.getClass().getMethod("f").invoke(info)).intValue() == V_SLEEP_TILE) info.getClass().getMethod("m", int.class).invoke(info, 1);
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
            button.getClass().getMethod("setTipText", String.class).invoke(button, enabled ? "Sleep Mode 已开启" : "Sleep Mode");
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

    private static synchronized void enable(Object c) {
        if (isEnabled(c)) return;
        save(c, "eyebuffer_w", getProp(PROP_EYEBUFFER_W, "1504")); save(c, "eyebuffer_h", getProp(PROP_EYEBUFFER_H, "1504"));
        save(c, "ffr", getProp(PROP_FFR, "0")); save(c, "fps", getProp(PROP_FPS, ""));
        save(c, "brightness", String.valueOf(getSystemInt(c, "screen_brightness", 128))); save(c, "governor", getGovernor());
        setProp(PROP_EYEBUFFER_W, String.valueOf(SLEEP_EYEBUFFER)); setProp(PROP_EYEBUFFER_H, String.valueOf(SLEEP_EYEBUFFER));
        setProp(PROP_FFR, "1"); putSystemInt(c, "screen_brightness", 1); setGovernor("powersave"); putGlobalInt(c, MODE_KEY, 1);
        XposedBridge.log(TAG + ": V-Sleep enabled");
    }
    private static synchronized void disable(Object c) {
        if (!isEnabled(c)) return;
        setProp(PROP_EYEBUFFER_W, saved(c, "eyebuffer_w", "1504")); setProp(PROP_EYEBUFFER_H, saved(c, "eyebuffer_h", "1504"));
        setProp(PROP_FFR, saved(c, "ffr", "0")); String fps = saved(c, "fps", ""); if (fps.length() > 0) setProp(PROP_FPS, fps);
        putSystemInt(c, "screen_brightness", parseInt(saved(c, "brightness", "128"), 128)); String gov = saved(c, "governor", ""); if (gov.length() > 0) setGovernor(gov);
        putGlobalInt(c, MODE_KEY, 0); XposedBridge.log(TAG + ": V-Sleep disabled and state restored");
    }

    private static String getProp(String k, String d) { try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, k, d); } catch (Throwable t) { return d; } }
    private static void setProp(String k, String v) { try { Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, k, v); } catch (Throwable t) { XposedBridge.log(TAG + ": cannot set " + k + ": " + t); } }
    private static Object resolver(Object c) throws Exception { return c.getClass().getMethod("getContentResolver").invoke(c); }
    private static int getGlobalInt(Object c, String k, int d) { return getInt("android.provider.Settings$Global", c, k, d); }
    private static int getSystemInt(Object c, String k, int d) { return getInt("android.provider.Settings$System", c, k, d); }
    private static int getInt(String cls, Object c, String k, int d) { try { return ((Integer) Class.forName(cls).getMethod("getInt", Class.forName("android.content.ContentResolver"), String.class, int.class).invoke(null, resolver(c), k, d)).intValue(); } catch (Throwable t) { return d; } }
    private static void putGlobalInt(Object c, String k, int v) { putInt("android.provider.Settings$Global", c, k, v); }
    private static void putSystemInt(Object c, String k, int v) { putInt("android.provider.Settings$System", c, k, v); }
    private static void putInt(String cls, Object c, String k, int v) { try { Class.forName(cls).getMethod("putInt", Class.forName("android.content.ContentResolver"), String.class, int.class).invoke(null, resolver(c), k, v); } catch (Throwable t) { XposedBridge.log(TAG + ": setting write failed " + k + ": " + t); } }
    private static void save(Object c, String s, String v) { try { Class.forName("android.provider.Settings$Global").getMethod("putString", Class.forName("android.content.ContentResolver"), String.class, String.class).invoke(null, resolver(c), SAVED_PREFIX + s, v); } catch (Throwable t) { XposedBridge.log(TAG + ": save failed " + s + ": " + t); } }
    private static String saved(Object c, String s, String d) { try { Object v = Class.forName("android.provider.Settings$Global").getMethod("getString", Class.forName("android.content.ContentResolver"), String.class).invoke(null, resolver(c), SAVED_PREFIX + s); return v == null ? d : (String) v; } catch (Throwable t) { return d; } }
    private static int parseInt(String v, int d) { try { return Integer.parseInt(v); } catch (Throwable t) { return d; } }
    private static String getGovernor() { try { return new String(java.nio.file.Files.readAllBytes(new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").toPath())).trim(); } catch (Throwable t) { return ""; } }
    private static void setGovernor(String g) { try { Runtime.getRuntime().exec(new String[]{"su", "-c", "for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo " + g + " > $p; done"}).waitFor(); } catch (Throwable t) { XposedBridge.log(TAG + ": governor failed: " + t); } }
}
