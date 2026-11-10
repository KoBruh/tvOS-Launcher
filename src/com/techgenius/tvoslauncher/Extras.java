package com.techgenius.tvoslauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Settings and helpers for the fork's "EXTRAS !" menu.
 *
 * Netflix button → Stremio works in two layers, both through the accessibility service
 * (PowerService), so nothing runs in the background on its own:
 *  1. Key layer: the learned Netflix key code is swallowed and Stremio is opened instead, so
 *     Netflix never even starts (saves RAM on low-memory TVs).
 *  2. Window layer (optional): some firmware launches Netflix below the accessibility key filter.
 *     If Netflix comes to the front anyway, switch straight to Stremio.
 */
public final class Extras {
    private Extras() { }

    public static final String STREMIO_PKG = "com.stremio.one";
    public static final String[] NETFLIX_PKGS = {"com.netflix.ninja", "com.netflix.mediaclient"};

    private static final String PREFS = "extras";
    private static final String K_NFX_ON = "netflix_to_stremio";
    private static final String K_NFX_CODE = "netflix_key_code";
    private static final String K_NFX_WINDOW = "netflix_window_redirect";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastRedirectMs = 0L;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ Netflix button → Stremio

    public static boolean isNetflixRedirectOn(Context c) {
        return c != null && prefs(c).getBoolean(K_NFX_ON, false);
    }

    public static void setNetflixRedirectOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_NFX_ON, on).apply();
    }

    /** Learned key code of the remote's Netflix button, or -1 if not detected yet. */
    public static int getNetflixKeyCode(Context c) {
        return c == null ? -1 : prefs(c).getInt(K_NFX_CODE, -1);
    }

    public static void setNetflixKeyCode(Context c, int code) {
        prefs(c).edit().putInt(K_NFX_CODE, code).apply();
    }

    /** Also switch to Stremio when Netflix comes to the front by any route (default on). */
    public static boolean isNetflixWindowRedirectOn(Context c) {
        return c != null && prefs(c).getBoolean(K_NFX_WINDOW, true);
    }

    public static void setNetflixWindowRedirectOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_NFX_WINDOW, on).apply();
    }

    public static boolean isNetflixPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : NETFLIX_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    /**
     * Key layer, called from ButtonMapper before regular mappings. Returns true if the key was
     * the learned Netflix button and has been consumed.
     */
    static boolean handleNetflixKey(Context c, KeyEvent ev) {
        if (!isNetflixRedirectOn(c)) return false;
        int learned = getNetflixKeyCode(c);
        if (learned <= 0 || ev.getKeyCode() != learned) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            final Context app = c.getApplicationContext();
            MAIN.post(() -> openStremio(app, true));
        }
        return true;
    }

    /** Window layer, called from PowerService when a new window comes to the front. */
    static boolean onForegroundPackage(Context c, String pkg) {
        if (!isNetflixPackage(pkg) || !isNetflixRedirectOn(c) || !isNetflixWindowRedirectOn(c)) {
            return false;
        }
        final Context app = c.getApplicationContext();
        MAIN.post(() -> {
            openStremio(app, true);
            // Netflix is now in the background; ask the system to drop it to free RAM.
            MAIN.postDelayed(() -> killBackground(app, pkg), 1500);
        });
        return true;
    }

    public static boolean isStremioInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(STREMIO_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens Stremio. With debounce=true, repeated calls within 1.5 s are ignored. */
    public static void openStremio(Context c, boolean debounce) {
        long now = SystemClock.uptimeMillis();
        if (debounce && now - lastRedirectMs < 1500L) return;
        lastRedirectMs = now;
        try {
            ControlPanelOverlay.dismiss();
            RecentAppsOverlay.dismiss();
        } catch (Throwable ignored) { }
        PackageManager pm = c.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(STREMIO_PKG);
        if (launch == null) launch = pm.getLaunchIntentForPackage(STREMIO_PKG);
        if (launch == null) {
            Toast.makeText(c, I18n.s(c, "Stremio is not installed", null), Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            RecentAppsOverlay.recordAppUsed(c, STREMIO_PKG);
            c.startActivity(launch);
        } catch (Throwable ignored) { }
    }

    private static void killBackground(Context c, String pkg) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) { }
    }
}
