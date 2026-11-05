package com.dailydocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms reminders after a restart, an app update, or a clock / time-zone change. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Scheduler.reschedule(c);
    }
}
