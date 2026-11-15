package com.techgenius.tvoslauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sleep support for Control Center: Power Off and the Sleep Timer turn the TV screen off
 * through ControlPanelOverlay.showScreenOff() or AccessibilityService sleep.
 */
public class SleepReceiver extends BroadcastReceiver {

    static boolean canSleep(Context c) {
        return true;
    }

    /** Projector-safe screen-off first (keeps Wanbo projector running); falls back to accessibility sleep. */
    static boolean sleepNow(Context c) {
        if (ControlPanelOverlay.showScreenOff(c)) {
            return true;
        }
        return PowerService.sleep();
    }

    static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Schedules sleep in `minutes` (0 cancels). Survives the launcher being closed. */
    static void setTimer(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pending(c));
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        c.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putLong("sleep_at", at).apply();
        if (minutes > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    /** Sleep timer choices in minutes: 15 to 60, then every 30 minutes up to 5 hours. */
    static final int[] STEPS = {15, 30, 45, 60, 90, 120, 150, 180, 210, 240, 270, 300};

    /** "45 min", "2 h", "2 h 30 min". */
    static String duration(Context c, int minutes) {
        if (minutes < 60) return minutes + I18n.s(c, " min", " dk");
        int h = minutes / 60, m = minutes % 60;
        return h + I18n.s(c, " h", " sa") + (m > 0 ? " " + m + I18n.s(c, " min", " dk") : "");
    }

    /** Short form for buttons: "45m", "2h", "2h 30m". */
    static String compact(Context c, int minutes) {
        if (minutes < 60) return minutes + I18n.s(c, "m", " dk");
        int h = minutes / 60, m = minutes % 60;
        return h + I18n.s(c, "h", " sa") + (m > 0 ? " " + m + I18n.s(c, "m", " dk") : "");
    }

    /** Next step for a "press to cycle" button: Off, 15 min ... 5 h, Off. */
    static int nextStep(Context c) {
        int left = timerLeft(c);
        if (left <= 0) return STEPS[0];
        for (int st : STEPS) if (st > left + 1) return st;
        return 0;
    }

    /** Minutes left on the sleep timer, or 0 when off. */
    static int timerLeft(Context c) {
        long at = c.getSharedPreferences("controls", Context.MODE_PRIVATE).getLong("sleep_at", 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? (int) Math.ceil(left / 60_000.0) : 0;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        c.getSharedPreferences("controls", Context.MODE_PRIVATE).edit().putLong("sleep_at", 0).apply();
        sleepNow(c);
    }
}
