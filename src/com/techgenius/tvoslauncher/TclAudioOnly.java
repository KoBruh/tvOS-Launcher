package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns on the TV's own "Audio only" (TCL: TV settings > Sound > Audio only): the panel goes off,
 * the TV stays on and keeps playing sound, and any remote button brings the picture back.
 *
 * There is no command for it, so this does what a person would: presses the remote's Settings key
 * (through Local ADB), then reads the menu through PowerService and clicks "Sound", then
 * "Audio only", then any OK prompt. Labels are matched by text, so it copes with menus laid out a
 * little differently. Every screen it sees is written to a short log the settings page can show.
 *
 * Main thread, except where noted.
 */
final class TclAudioOnly {
    private TclAudioOnly() { }

    interface Done {
        void onDone(boolean ok);
    }

    private static final String[] AUDIO_ONLY = {
            "audio only", "only audio", "audio only mode", "sound only", "picture off",
            "screen off", "turn off screen", "turn screen off"};
    private static final String[] SOUND = {
            "sound", "audio", "sound settings", "display & sound", "display and sound"};
    private static final String[] CONFIRM = {"ok", "yes", "confirm", "start", "android:id/button1"};

    private static final long STEP_MS = 450L;
    private static final long TIMEOUT_MS = 16_000L;
    private static final int MAX_SCROLLS = 14;
    private static final String K_LOG = "tcl_audio_only_log";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean running;
    private static Done done;
    private static long startedMs, confirmUntilMs;
    private static boolean clickedSound, clickedAudio, sawSettings;
    private static int scrolls, step;
    private static String lastScreen = "";
    private static final StringBuilder LOG = new StringBuilder();

    static boolean isRunning() {
        return running;
    }

    /** What the last attempt saw on each menu screen (for troubleshooting a different TV menu). */
    static String lastLog(Context c) {
        return c.getSharedPreferences("extras", Context.MODE_PRIVATE).getString(K_LOG, "");
    }

    static void start(Context c, Done callback) {
        PowerService svc = PowerService.instance;
        if (svc == null) {
            if (callback != null) callback.onDone(false);
            return;
        }
        if (running) return;
        running = true;
        done = callback;
        startedMs = SystemClock.uptimeMillis();
        confirmUntilMs = 0;
        clickedSound = clickedAudio = sawSettings = false;
        scrolls = step = 0;
        lastScreen = "";
        LOG.setLength(0);
        final Context app = c.getApplicationContext();
        ScreenOff.worker().post(() -> {
            // Same as pressing the remote's Settings (gear) button: opens TCL's TV settings panel.
            boolean keyed = ScreenOff.adbShell(app, "input keyevent " + KeyEvent.KEYCODE_SETTINGS) != null;
            MAIN.post(() -> {
                if (!keyed) {
                    log("Local ADB not reachable, opening Android sound settings instead");
                    openSoundSettings(app);
                }
                MAIN.postDelayed(TclAudioOnly::poll, keyed ? 1200L : 1500L);
            });
        });
    }

    private static void poll() {
        if (!running) return;
        PowerService svc = PowerService.instance;
        long now = SystemClock.uptimeMillis();
        if (svc == null) {
            finish(false, "accessibility service stopped");
            return;
        }
        List<AccessibilityNodeInfo> roots = roots(svc);
        logScreen(roots);

        if (clickedAudio) {
            AccessibilityNodeInfo ok = find(roots, CONFIRM);
            if (ok != null) {
                press(ok);
                finish(true, "pressed OK");
                return;
            }
            if (now >= confirmUntilMs) {
                finish(true, "no OK prompt");
                return;
            }
        } else if (now - startedMs > TIMEOUT_MS) {
            finish(false, "couldn't find Audio only");
            return;
        } else {
            AccessibilityNodeInfo audio = find(roots, AUDIO_ONLY);
            if (audio != null) {
                log("clicking \"" + label(audio) + "\"");
                press(audio);
                clickedAudio = true;
                confirmUntilMs = now + 2500L;
            } else if (!clickedSound) {
                AccessibilityNodeInfo sound = find(roots, SOUND);
                if (sound != null) {
                    log("clicking \"" + label(sound) + "\"");
                    press(sound);
                    clickedSound = true;
                }
            } else if (scrolls < MAX_SCROLLS) {
                scrolls++;
                if (!scrollForward(roots)) adbKey(KeyEvent.KEYCODE_DPAD_DOWN);
            }
        }
        MAIN.postDelayed(TclAudioOnly::poll, STEP_MS);
    }

    private static void finish(boolean ok, String why) {
        log((ok ? "done: " : "failed: ") + why);
        running = false;
        PowerService svc = PowerService.instance;
        if (!ok && svc != null && sawSettings) {
            // Close the menus this opened; only when a settings screen was seen, so a stray Back
            // never reaches the video player.
            int backs = clickedSound ? 2 : 1;
            for (int i = 0; i < backs; i++) {
                MAIN.postDelayed(() -> {
                    PowerService s = PowerService.instance;
                    if (s != null) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                }, 150L + i * 350L);
            }
        }
        if (svc != null) {
            String text = LOG.length() > 3500 ? LOG.substring(LOG.length() - 3500) : LOG.toString();
            svc.getSharedPreferences("extras", Context.MODE_PRIVATE).edit().putString(K_LOG, text).apply();
        }
        Done d = done;
        done = null;
        if (d != null) d.onDone(ok);
    }

    // ------------------------------------------------------------ Reading the screen

    private static List<AccessibilityNodeInfo> roots(PowerService svc) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) out.add(r);
            }
        } catch (Throwable ignored) { }
        try {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null) out.add(r);
        } catch (Throwable ignored) { }
        return out;
    }

    /** First visible node whose text or description is exactly one of the labels (any case). */
    private static AccessibilityNodeInfo find(List<AccessibilityNodeInfo> roots, String[] labels) {
        for (String want : labels) {
            for (AccessibilityNodeInfo r : roots) {
                if (!isMenuWindow(r)) continue;
                AccessibilityNodeInfo n = findIn(r, want, 0);
                if (n != null) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findIn(AccessibilityNodeInfo n, String want, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isVisibleToUser()) {
            String id = n.getViewIdResourceName();
            if (want.equals(norm(n.getText())) || want.equals(norm(n.getContentDescription()))
                    || (want.contains(":id/") && want.equals(id))) {
                return n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findIn(n.getChild(i), want, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Only TCL / settings / system windows are clicked, never the video player or another app
     * (whose own "Audio" button would otherwise match).
     */
    private static boolean isMenuWindow(AccessibilityNodeInfo r) {
        CharSequence p = r.getPackageName();
        if (p == null) return false;
        String s = p.toString();
        return !isOwnWindow(r) && (s.contains("tcl") || s.contains("settings")
                || s.equals("android") || s.equals("com.android.systemui"));
    }

    private static boolean isOwnWindow(AccessibilityNodeInfo r) {
        CharSequence p = r.getPackageName();
        return p != null && "com.techgenius.tvoslauncher".contentEquals(p);
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        return t == null ? "?" : t.toString();
    }

    // ------------------------------------------------------------ Acting

    /** Click the item; if the TV's menu doesn't take clicks, focus it and press OK instead. */
    private static void press(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isFocusable()) {
                p.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                break;
            }
        }
        adbKey(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private static boolean scrollForward(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) {
            if (!isMenuWindow(r)) continue;
            AccessibilityNodeInfo s = findScrollable(r, 0);
            if (s != null && s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static void adbKey(int code) {
        PowerService svc = PowerService.instance;
        if (svc == null) return;
        final Context app = svc.getApplicationContext();
        ScreenOff.worker().post(() -> ScreenOff.adbShell(app, "input keyevent " + code));
    }

    private static void openSoundSettings(Context app) {
        try {
            app.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ Log

    private static void logScreen(List<AccessibilityNodeInfo> roots) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo r : roots) {
            if (isOwnWindow(r)) continue;
            CharSequence pkg = r.getPackageName();
            String p = pkg == null ? "?" : pkg.toString();
            if (isMenuWindow(r)) sawSettings = true;
            sb.append('[').append(p).append("] ");
            collectTexts(r, sb, 0);
        }
        String screen = sb.toString();
        if (screen.length() > 600) screen = screen.substring(0, 600) + "…";
        if (!screen.equals(lastScreen)) {
            lastScreen = screen;
            log("screen " + (++step) + ": " + screen);
        }
    }

    private static void collectTexts(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 700) return;
        if (n.isVisibleToUser()) {
            String t = n.getText() != null ? n.getText().toString().trim() : "";
            if (!t.isEmpty() && t.length() < 60) sb.append(t).append(" | ");
        }
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), sb, depth + 1);
    }

    private static void log(String line) {
        LOG.append(line).append('\n');
        android.util.Log.i("tvOSLauncher", "Audio only: " + line);
    }
}
