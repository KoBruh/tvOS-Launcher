package com.techgenius.tvoslauncher;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point, run as the shell user through Local ADB (never inside the app):
 *
 *   CLASSPATH=&lt;this apk&gt; app_process /system/bin com.techgenius.tvoslauncher.DisplayPowerMain off|on
 *
 * Switches the built-in display's power mode (SurfaceControl.setDisplayPowerMode) without putting
 * the TV to sleep, so the panel goes dark while apps and audio keep running. Same technique as
 * scrcpy's "turn screen off". Prints "OK &lt;displays&gt;" or "ERR &lt;reason&gt;".
 *
 * Uses only framework classes: no Context, resources or other app classes.
 */
public final class DisplayPowerMain {
    private DisplayPowerMain() { }

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    public static void main(String[] args) {
        int mode = args.length > 0 && "on".equals(args[0]) ? POWER_MODE_NORMAL : POWER_MODE_OFF;
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            List<IBinder> tokens = new ArrayList<>();
            Class<?> owner = displayTokens(sc, tokens);
            if (tokens.isEmpty()) {
                System.out.println("ERR no display token");
                return;
            }
            Method set = findSetPowerMode(sc, owner);
            for (IBinder t : tokens) set.invoke(null, t, mode);
            System.out.println("OK " + tokens.size());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("ERR " + cause);
        }
    }

    /** Fills tokens and returns the class that provided them (SurfaceControl or DisplayControl). */
    private static Class<?> displayTokens(Class<?> sc, List<IBinder> out) throws Exception {
        // Android 10-13: every physical display.
        try {
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            Method tok = sc.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) addToken(out, tok.invoke(null, id));
            if (!out.isEmpty()) return sc;
        } catch (NoSuchMethodException ignored) { }
        // Android 10 builds without the id list.
        try {
            addToken(out, sc.getMethod("getInternalDisplayToken").invoke(null));
            if (!out.isEmpty()) return sc;
        } catch (NoSuchMethodException ignored) { }
        // Android 9 and older: built-in display 0.
        try {
            addToken(out, sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0));
            if (!out.isEmpty()) return sc;
        } catch (NoSuchMethodException ignored) { }
        // Android 14+: the display ids moved to DisplayControl inside services.jar.
        Class<?> dc = loadDisplayControl();
        long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
        Method tok = dc.getMethod("getPhysicalDisplayToken", long.class);
        for (long id : ids) addToken(out, tok.invoke(null, id));
        return dc;
    }

    private static Method findSetPowerMode(Class<?> sc, Class<?> owner) throws Exception {
        try {
            return sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        } catch (NoSuchMethodException e) {
            return owner.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        }
    }

    private static void addToken(List<IBinder> out, Object t) {
        if (t instanceof IBinder) out.add((IBinder) t);
    }

    private static Class<?> loadDisplayControl() throws Exception {
        Class<?> clf = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = clf.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader cl = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), dc, "android_servers");
        return dc;
    }
}
