package com.dailydocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Keeps exactly one alarm set: the next reminder or briefing that is due. */
public class Scheduler {
    private static final String PREFS = "scheduler";

    public static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime from = LocalDateTime.now();
        LocalDateTime last = lastFired(c);
        if (last != null && last.isAfter(from)) from = last;
        Planner.Reminder r = Planner.next(Store.all(c), from);
        if (r == null) { am.cancel(pi); return; }
        long ms = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
        }
    }

    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    static LocalDateTime lastFired(Context c) {
        long v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastFired", 0);
        if (v == 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneId.systemDefault());
    }

    static void setLastFired(Context c, LocalDateTime t) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putLong("lastFired", t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        e.apply();
    }
}
