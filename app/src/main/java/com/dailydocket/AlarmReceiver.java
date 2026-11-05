package com.dailydocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDateTime;
import java.util.List;

/** Fires when a reminder is due: shows every reminder due now, then sets the next alarm. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = Scheduler.lastFired(c);
        if (last == null || last.isBefore(now.minusHours(12))) last = now.minusMinutes(20);
        LocalDateTime upto = now.plusSeconds(30);
        List<Item> items = Store.all(c);
        for (Planner.Reminder r : Planner.between(items, last, upto)) Notifier.reminder(c, r, items);
        Scheduler.setLastFired(c, upto);
        Scheduler.reschedule(c);
    }
}
