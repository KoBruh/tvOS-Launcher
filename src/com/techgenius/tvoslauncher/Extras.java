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
    /** Netflix button as seen by `getevent` (for TVs that grab the key before apps see it). */
    private static final String K_NFX_RAW = "netflix_raw_key";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastRedirectMs = 0L;
    private static long windowBurstStartMs = 0L;
    private static int windowBurstCount = 0;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ Dock position

    public static final int DOCK_BOTTOM = 0, DOCK_TOP = 1, DOCK_LEFT = 2, DOCK_RIGHT = 3;
    private static final String K_DOCK_POS = "dock_position";

    public static int getDockPosition(Context c) {
        if (c == null) return DOCK_BOTTOM;
        int p = prefs(c).getInt(K_DOCK_POS, DOCK_BOTTOM);
        return (p < DOCK_BOTTOM || p > DOCK_RIGHT) ? DOCK_BOTTOM : p;
    }

    public static void setDockPosition(Context c, int pos) {
        prefs(c).edit().putInt(K_DOCK_POS, pos).apply();
    }

    public static boolean isDockVertical(int pos) {
        return pos == DOCK_LEFT || pos == DOCK_RIGHT;
    }

    public static String dockPositionLabel(Context c, int pos) {
        switch (pos) {
            case DOCK_TOP: return I18n.s(c, "Top", null);
            case DOCK_LEFT: return I18n.s(c, "Left", null);
            case DOCK_RIGHT: return I18n.s(c, "Right", null);
            default: return I18n.s(c, "Bottom (default)", null);
        }
    }

    // ------------------------------------------------------------ Single-app dock

    private static final String K_SINGLE_DOCK = "single_app_dock";
    private static final String K_SINGLE_PKG = "single_app_pkg";

    /** Dock shows a single chosen app (plus the Settings gear, so nothing is ever locked away). */
    public static boolean isSingleAppDock(Context c) {
        return c != null && prefs(c).getBoolean(K_SINGLE_DOCK, false);
    }

    public static void setSingleAppDock(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_SINGLE_DOCK, on).apply();
    }

    public static String getSingleAppPkg(Context c) {
        return c == null ? STREMIO_PKG : prefs(c).getString(K_SINGLE_PKG, STREMIO_PKG);
    }

    public static void setSingleAppPkg(Context c, String pkg) {
        prefs(c).edit().putString(K_SINGLE_PKG, pkg).apply();
    }

    // ------------------------------------------------------------ Continue watching style

    private static final String K_CW_POSTERS = "cw_posters";

    /** Stremio/Nuvio continue-watching row as tall poster cards (default) instead of wide bars. */
    public static boolean isCwPosters(Context c) {
        return c == null || prefs(c).getBoolean(K_CW_POSTERS, true);
    }

    public static void setCwPosters(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_CW_POSTERS, on).apply();
    }

    // ------------------------------------------------------------ Netflix button → Stremio

    public static boolean isNetflixRedirectOn(Context c) {
        return c != null && prefs(c).getBoolean(K_NFX_ON, false);
    }

    public static void setNetflixRedirectOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_NFX_ON, on).apply();
        syncNetflixWatcher(c);
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

    // ------------------------------------------------------------ Raw-input layer (LocalAdb getevent)

    /** Keys never used as the Netflix button (so pressing OK/arrows during detection is ignored). */
    private static final java.util.Set<String> NAV_TOKENS = new java.util.HashSet<>(java.util.Arrays.asList(
            "KEY_ENTER", "KEY_OK", "KEY_SELECT", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT",
            "KEY_BACK", "KEY_ESC", "KEY_HOME", "KEY_POWER", "KEY_VOLUMEUP", "KEY_VOLUMEDOWN", "KEY_MUTE",
            "BTN_LEFT", "BTN_TOUCH", "BTN_MOUSE"));

    public static String getNetflixRawKey(Context c) {
        return c == null ? "" : prefs(c).getString(K_NFX_RAW, "");
    }

    public static void setNetflixRawKey(Context c, String token) {
        prefs(c).edit().putString(K_NFX_RAW, token == null ? "" : token).apply();
        syncNetflixWatcher(c);
    }

    /** A getevent token that could be the Netflix button (not a navigation key). */
    static boolean isCandidateRawKey(String token) {
        return token != null && !token.isEmpty() && !NAV_TOKENS.contains(token);
    }

    private static volatile Thread rawWatcher;

    private static boolean rawWatcherWanted(Context c) {
        return isNetflixRedirectOn(c) && !getNetflixRawKey(c).isEmpty()
                && LocalAdb.getAdbMode(c) == LocalAdb.ADB_MODE_PERSISTENT;
    }

    /**
     * Starts or stops the raw Netflix-button watcher: a single `getevent` stream over LocalAdb that
     * only wakes up when a remote button is pressed. Safe to call often.
     */
    public static synchronized void syncNetflixWatcher(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (!rawWatcherWanted(app)) return;  // a running watcher stops itself at its next event
        Thread t = rawWatcher;
        if (t != null && t.isAlive()) return;
        t = new Thread(() -> LocalAdb.watchGeteventLines(app, line -> {
            String[] k = LocalAdb.parseKeyLine(line);
            if (k != null && "DOWN".equals(k[1]) && k[0].equals(getNetflixRawKey(app))) {
                MAIN.post(() -> openStremio(app, true));
            }
        }, null, null, () -> rawWatcherWanted(app)), "netflix-key-watch");
        t.setDaemon(true);
        rawWatcher = t;
        t.start();
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
        // Netflix re-fronts its own window several times while it starts, so redirect every time
        // (no debounce), but cap it so a misbehaving firmware can never cause an endless loop.
        long now = SystemClock.uptimeMillis();
        if (now - windowBurstStartMs > 20_000L) {
            windowBurstStartMs = now;
            windowBurstCount = 0;
        }
        if (++windowBurstCount > 12) return false;
        final Context app = c.getApplicationContext();
        MAIN.post(() -> {
            openStremio(app, false);
            // Close Netflix once it is behind Stremio so it stops coming back and frees its RAM.
            MAIN.postDelayed(() -> killBackground(app, pkg), 400);
            MAIN.postDelayed(() -> killBackground(app, pkg), 2000);
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
