package com.dailydocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public class Notifier {
    static final String CH_BRIEF = "briefings", CH_ALERT = "reminders", CH_SAVED = "saved";

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel b = new NotificationChannel(CH_BRIEF, "Briefings", NotificationManager.IMPORTANCE_DEFAULT);
        b.setDescription("Morning briefing, evening wrap-up and the Monday follow-up report");
        NotificationChannel a = new NotificationChannel(CH_ALERT, "Meeting and deadline reminders", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("30 minutes before meetings, deadline warnings, follow-up checks");
        a.enableVibration(true);
        NotificationChannel s = new NotificationChannel(CH_SAVED, "Saved by voice", NotificationManager.IMPORTANCE_DEFAULT);
        s.setDescription("Confirms what was saved after you speak, with Undo");
        s.setSound(null, null);
        nm.createNotificationChannel(b);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(s);
    }

    static PendingIntent openApp(Context c, String editId) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (editId != null) i.putExtra("edit", editId);
        int code = editId == null ? 0 : editId.hashCode();
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context c, String act, String id, int notifId, Intent extra) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(act);
        i.putExtra("id", id);
        i.putExtra("notif", notifId);
        if (extra != null && extra.getExtras() != null) i.putExtras(extra.getExtras());
        return PendingIntent.getBroadcast(c, (act + id + notifId).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void post(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try { nm.notify(id, n); } catch (SecurityException ignored) { }
    }

    public static void reminder(Context c, Planner.Reminder r, List<Item> items) {
        channels(c);
        LocalDate today = LocalDate.now();
        String title = r.title, text = r.text;
        String ch = CH_ALERT;
        if (r.kind.equals("morning") || r.kind.equals("evening") || r.kind.equals("weekly")) {
            long startToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            String[] t = r.kind.equals("morning") ? Planner.morning(items, today)
                    : r.kind.equals("evening") ? Planner.evening(items, today, startToday)
                    : Planner.weekly(items, today, startToday - 7L * 86400000L);
            title = t[0]; text = t[1];
            ch = CH_BRIEF;
        }
        int nid = r.key().hashCode();
        String first = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
        Notification.Builder b = new Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(first)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c, r.itemId.isEmpty() ? null : r.itemId))
                .setAutoCancel(true)
                .setCategory(ch.equals(CH_ALERT) ? Notification.CATEGORY_REMINDER : Notification.CATEGORY_STATUS);
        if (!r.itemId.isEmpty() && !r.kind.startsWith("meeting")) {
            b.addAction(new Notification.Action.Builder(null, "Done", action(c, ActionReceiver.DONE, r.itemId, nid, null)).build());
            b.addAction(new Notification.Action.Builder(null, "Tomorrow", action(c, ActionReceiver.TOMORROW, r.itemId, nid, null)).build());
        }
        post(c, nid, b.build());
    }

    /** Confirmation after a voice command, with Undo. */
    public static void saved(Context c, String title, String text, String undoKind, Item before, Item after) {
        channels(c);
        int nid = 4000 + (after.id.hashCode() & 0xfff);
        Intent extra = new Intent();
        extra.putExtra("undo", undoKind);
        if (before != null) {
            extra.putExtra("oldDate", before.date);
            extra.putExtra("oldTime", before.time);
        }
        Notification.Builder b = new Notification.Builder(c, CH_SAVED)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c, after.id))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60 * 1000L)
                .addAction(new Notification.Action.Builder(null, "Undo", action(c, ActionReceiver.UNDO, after.id, nid, extra)).build())
                .addAction(new Notification.Action.Builder(null, "Edit", openApp(c, after.id)).build());
        post(c, nid, b.build());
    }

    public static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
