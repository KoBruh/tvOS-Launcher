package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * EXTRAS ! auto screen off: after a set time with no remote presses while sound is playing, the
 * picture goes off but the TV stays on, so Stremio / the player keep playing audio. The first
 * button press brings the picture back (and is swallowed so it doesn't also pause or navigate);
 * volume and mute keep working with the screen off.
 *
 * Two methods:
 *  - Panel off: switches the display's power mode through Local ADB ({@link DisplayPowerMain}),
 *    so the backlight really goes off. Falls back to Black screen if ADB isn't reachable.
 *  - Black screen: an opaque accessibility overlay. Works everywhere, but an LCD's backlight
 *    still glows behind it.
 *
 * Driven by PowerService (the Button mapper accessibility service): it feeds every remote key in
 * through {@link #onKey} and starts the idle check. Main-thread API; ADB work runs on its own
 * thread, one command at a time, so a quick press can never leave the panel off by mistake.
 */
final class ScreenOff {
    private ScreenOff() { }

    private static final long CHECK_MS = 20_000L;
    /** Presses right after the screen goes off belong to the press that turned it off. */
    private static final long ENTRY_GRACE_MS = 800L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;

    private static volatile boolean off;
    /** TCL Audio only is (probably) on: don't start it again until the next remote press. */
    private static volatile boolean tclAudioOnly;
    private static long offSinceMs;
    private static volatile long lastKeyMs = SystemClock.uptimeMillis();
    private static int swallowUpCode = -1;

    // Worker-thread state.
    private static boolean panelOff;
    private static boolean openedAdbPort;

    // Main-thread state.
    private static View blackView;
    private static WindowManager blackWm;

    private static volatile String lastNote = "";

    static boolean isOff() {
        return off;
    }

    /** How the last screen-off went, for the settings page ("" before the first one). */
    static String lastNote() {
        return lastNote;
    }

    // ------------------------------------------------------------ Idle check

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            PowerService svc = PowerService.instance;
            if (svc == null) return;
            int min = Extras.getAutoScreenOffMinutes(svc);
            if (min <= 0) return;
            long idle = SystemClock.uptimeMillis() - lastKeyMs;
            if (!off && !tclAudioOnly && !TclAudioOnly.isRunning()
                    && idle >= min * 60_000L && isSoundPlaying(svc) && isInteractive(svc)) {
                turnOff(svc);
            }
            MAIN.postDelayed(this, CHECK_MS);
        }
    };

    /** Starts or stops the idle check to match the settings. Safe to call often. */
    static void sync(Context c) {
        MAIN.removeCallbacks(TICK);
        if (c != null && PowerService.instance != null && Extras.getAutoScreenOffMinutes(c) > 0) {
            lastKeyMs = SystemClock.uptimeMillis();
            MAIN.postDelayed(TICK, CHECK_MS);
        }
    }

    /** PowerService is going away: it can't wake the screen any more, so bring it back now. */
    static void onServiceGone(Context c) {
        MAIN.removeCallbacks(TICK);
        if (off) wake(c);
    }

    // ------------------------------------------------------------ Keys

    /**
     * Called first for every key PowerService sees. Returns true if the key was used to wake the
     * screen (or belongs to that press) and must not reach the app.
     */
    static boolean onKey(Context c, KeyEvent ev) {
        if (ev == null) return false;
        int code = ev.getKeyCode();
        int action = ev.getAction();
        long now = SystemClock.uptimeMillis();
        if (!off) {
            // Our own injected menu keys arrive here too while the walker runs; ignore those.
            if (action == KeyEvent.ACTION_DOWN && !TclAudioOnly.isRunning()) {
                lastKeyMs = now;
                tclAudioOnly = false;
            }
            if (action == KeyEvent.ACTION_UP && code == swallowUpCode) {
                swallowUpCode = -1;
                return true;
            }
            return false;
        }
        lastKeyMs = now;
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;  // adjust the sound without waking the picture
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_SOFT_SLEEP:
                forgetAfterPowerKey(c);  // the TV turns its own display off and back on
                return false;
            default:
                break;
        }
        if (now - offSinceMs < ENTRY_GRACE_MS) return true;
        if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            swallowUpCode = code;
            wake(c);
        }
        return true;
    }

    // ------------------------------------------------------------ Off / on

    /** Turns the picture off now (sound keeps playing). Needs PowerService for the wake-up key. */
    static boolean turnOff(Context c) {
        PowerService svc = PowerService.instance;
        if (c == null || svc == null) return false;
        if (off) return true;
        off = true;
        offSinceMs = SystemClock.uptimeMillis();
        lastKeyMs = offSinceMs;
        try {
            ControlPanelOverlay.dismiss();
        } catch (Throwable ignored) { }
        if (Extras.getScreenOffMethod(c) == Extras.SCREEN_OFF_TCL) {
            // The TV handles Audio only itself, including waking on any button, so keys aren't
            // intercepted here; only the idle check pauses until the next press.
            off = false;
            tclAudioOnly = true;
            lastNote = I18n.s(c, "TCL Audio only: opening the menu…", null);
            final Context tclApp = c.getApplicationContext();
            TclAudioOnly.start(svc, ok -> {
                if (!ok) tclAudioOnly = false;
                worker().post(() -> closeAdbPortIfWeOpenedIt(tclApp));
                lastNote = ok ? I18n.s(svc, "TCL Audio only", null)
                        : I18n.s(svc, "TCL Audio only not found (see the menu log)", null);
            });
            return true;
        }
        if (Extras.getScreenOffMethod(c) == Extras.SCREEN_OFF_BLACK) {
            lastNote = I18n.s(c, "Black screen", null);
            showBlack(svc);
            return true;
        }
        final Context app = c.getApplicationContext();
        worker().post(() -> {
            if (!off) return;
            if (runDisplayPower(app, "off")) {
                panelOff = true;
                lastNote = I18n.s(app, "Panel off", null);
                if (!off) setPanelOn(app);  // woken while the command ran
            } else {
                closeAdbPortIfWeOpenedIt(app);
                lastNote = I18n.s(app, "Black screen (panel off needs Local ADB)", null);
                MAIN.post(() -> {
                    if (off && PowerService.instance != null) showBlack(PowerService.instance);
                });
            }
        });
        return true;
    }

    /** Brings the picture back. */
    static void wake(Context c) {
        off = false;
        lastKeyMs = SystemClock.uptimeMillis();
        removeBlack();
        if (c == null) return;
        final Context app = c.getApplicationContext();
        worker().post(() -> {
            if (panelOff && !off) setPanelOn(app);
        });
    }

    /**
     * Power / sleep while the picture is off: standby switches the display off and back on by
     * itself, so only drop our state (sending "on" now could light the panel during standby).
     */
    private static void forgetAfterPowerKey(Context c) {
        off = false;
        removeBlack();
        if (c == null) return;
        final Context app = c.getApplicationContext();
        worker().post(() -> {
            panelOff = false;
            closeAdbPortIfWeOpenedIt(app);
        });
    }

    // Worker thread.
    private static void closeAdbPortIfWeOpenedIt(Context app) {
        if (openedAdbPort && LocalAdb.getAdbMode(app) == LocalAdb.ADB_MODE_SECURE_CLOSE
                && LocalAdb.hasWriteSecureSettings(app)) {
            try {
                Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            } catch (Throwable ignored) { }
        }
        openedAdbPort = false;
    }

    // Worker thread.
    private static void setPanelOn(Context app) {
        boolean ok = runDisplayPower(app, "on");
        if (!ok) {
            // One retry: if this fails the picture stays dark until the TV is powered off and on.
            SystemClock.sleep(700);
            runDisplayPower(app, "on");
        }
        panelOff = false;
        closeAdbPortIfWeOpenedIt(app);
    }

    /**
     * Runs a shell command through Local ADB, opening the ADB port first if Secure mode closed it.
     * Returns the output, or null if ADB can't be reached. Worker thread only.
     */
    static String adbShell(Context app, String cmd) {
        try {
            if (!LocalAdb.isPortOpen("127.0.0.1", 5555, 400)) {
                if (!LocalAdb.hasWriteSecureSettings(app)) return null;
                Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                openedAdbPort = true;
                boolean open = false;
                for (int i = 0; i < 10 && !open; i++) {
                    SystemClock.sleep(300);
                    open = LocalAdb.isPortOpen("127.0.0.1", 5555, 300);
                }
                if (!open) return null;
            }
            String out = LocalAdb.execShellOutput(app, cmd);
            return out == null ? null : out;
        } catch (Throwable t) {
            android.util.Log.w("tvOSLauncher", "ADB shell failed: " + t);
            return null;
        }
    }

    // Worker thread.
    private static boolean runDisplayPower(Context app, String arg) {
        String apk = app.getApplicationInfo().sourceDir;
        String out = adbShell(app, "CLASSPATH=" + apk
                + " app_process /system/bin " + DisplayPowerMain.class.getName() + " " + arg);
        if (out != null && out.contains("OK")) return true;
        android.util.Log.w("tvOSLauncher", "Display power " + arg + " failed: " + out);
        return false;
    }

    static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("screen-off");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    // ------------------------------------------------------------ Black screen

    private static void showBlack(Context svc) {
        if (blackView != null) return;
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            View v = new View(svc);
            v.setBackgroundColor(0xFF000000);
            v.setFocusable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("tvOS Launcher screen off");
            wm.addView(v, lp);
            blackView = v;
            blackWm = wm;
        } catch (Throwable t) {
            android.util.Log.w("tvOSLauncher", "Screen off overlay failed: " + t);
        }
    }

    private static void removeBlack() {
        View v = blackView;
        WindowManager wm = blackWm;
        blackView = null;
        blackWm = null;
        if (v != null && wm != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ Helpers

    static boolean isSoundPlaying(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isInteractive(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }
}
