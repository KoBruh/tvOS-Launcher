package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * EXTRAS ! night dim: a see-through black layer over everything on screen (home, Stremio, the
 * video player), for when the TV's lowest backlight is still too bright.
 *
 * Drawn as an accessibility overlay through {@link PowerService}, so it needs no "Display over
 * other apps" permission and covers every app. If the service isn't running but the TV has
 * granted that permission anyway, a normal app overlay is used instead.
 *
 * The layer never takes focus or input, so the remote keeps working underneath it. All calls
 * must be on the main thread.
 */
final class NightDim {
    private NightDim() { }

    private static View view;
    private static WindowManager wm;
    private static boolean viaService;

    /**
     * Shows, updates or removes the layer to match the saved settings. Safe to call often.
     * Returns false if dimming is on but there is no way to draw it right now.
     */
    static boolean sync(Context c) {
        if (c == null) return false;
        if (!Extras.isNightDimOn(c)) {
            detach();
            return true;
        }
        int color = Color.argb(Math.round(Extras.getNightDimPercent(c) * 2.55f), 0, 0, 0);
        PowerService svc = PowerService.instance;
        boolean wantService = svc != null;

        if (view != null && viaService == wantService) {
            view.setBackgroundColor(color);
            return true;
        }
        detach();

        Context host;
        int type;
        if (wantService) {
            host = svc;
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (canDrawOverApps(c)) {
            host = c.getApplicationContext();
            type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
        } else {
            return false;
        }

        try {
            WindowManager m = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
            if (m == null) return false;
            View v = new View(host);
            v.setBackgroundColor(color);
            v.setFocusable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("tvOS Launcher night dim");
            m.addView(v, lp);
            view = v;
            wm = m;
            viaService = wantService;
            return true;
        } catch (Throwable t) {
            android.util.Log.w("tvOSLauncher", "Night dim overlay failed: " + t);
            view = null;
            wm = null;
            return false;
        }
    }

    /** Called when PowerService goes away: its window dies with it, so fall back if possible. */
    static void onServiceGone(Context c) {
        if (view != null && viaService) {
            detach();
            if (c != null) sync(c.getApplicationContext());
        }
    }

    static boolean isShowing() {
        return view != null;
    }

    private static boolean canDrawOverApps(Context c) {
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void detach() {
        View v = view;
        WindowManager m = wm;
        view = null;
        wm = null;
        if (v != null && m != null) {
            try {
                m.removeViewImmediate(v);
            } catch (Throwable ignored) { }
        }
    }
}
