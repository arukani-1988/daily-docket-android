package com.dailydocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.time.LocalDate;

/** Handles the Done / Tomorrow / Undo buttons on notifications. */
public class ActionReceiver extends BroadcastReceiver {
    static final String DONE = "com.dailydocket.DONE", TOMORROW = "com.dailydocket.TOMORROW", UNDO = "com.dailydocket.UNDO";

    @Override
    public void onReceive(Context c, Intent in) {
        String id = in.getStringExtra("id");
        int notif = in.getIntExtra("notif", 0);
        Notifier.cancel(c, notif);
        if (id == null) return;
        Item it = Store.get(c, id);
        String act = in.getAction() == null ? "" : in.getAction();
        String msg = null;
        switch (act) {
            case DONE:
                if (it != null) { it.done = true; it.doneAt = System.currentTimeMillis(); Store.put(c, it); msg = "Marked done"; }
                break;
            case TOMORROW:
                if (it != null) { it.date = LocalDate.now().plusDays(1).toString(); Store.put(c, it); msg = "Moved to tomorrow"; }
                break;
            case UNDO:
                String kind = in.getStringExtra("undo");
                if ("add".equals(kind)) { Store.delete(c, id); msg = "Removed"; }
                else if (it != null && "complete".equals(kind)) { it.done = false; it.doneAt = 0; Store.put(c, it); msg = "Marked not done"; }
                else if (it != null && "reschedule".equals(kind)) {
                    String d = in.getStringExtra("oldDate"), t = in.getStringExtra("oldTime");
                    it.date = d == null ? "" : d; it.time = t == null ? "" : t;
                    Store.put(c, it); msg = "Date restored";
                }
                break;
        }
        if (msg != null) Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }
}
