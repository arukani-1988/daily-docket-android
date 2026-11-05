package com.dailydocket;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Entries saved as JSON in the app's private storage on the phone. */
public class Store {
    private static List<Item> cache;

    private static File file(Context c) { return new File(c.getFilesDir(), "items.json"); }

    public static synchronized List<Item> all(Context c) {
        if (cache == null) load(c);
        List<Item> out = new ArrayList<>();
        for (Item i : cache) out.add(i.copy());
        return out;
    }

    public static synchronized List<Item> open(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item i : all(c)) if (!i.done) out.add(i);
        return out;
    }

    public static synchronized Item get(Context c, String id) {
        for (Item i : all(c)) if (i.id.equals(id)) return i;
        return null;
    }

    public static synchronized void put(Context c, Item it) {
        if (cache == null) load(c);
        if (it.id == null || it.id.isEmpty()) it.id = newId();
        if (it.createdAt == 0) it.createdAt = System.currentTimeMillis();
        boolean replaced = false;
        for (int k = 0; k < cache.size(); k++) {
            if (cache.get(k).id.equals(it.id)) { cache.set(k, it.copy()); replaced = true; break; }
        }
        if (!replaced) cache.add(it.copy());
        save(c);
        Scheduler.reschedule(c);
    }

    public static synchronized void delete(Context c, String id) {
        if (cache == null) load(c);
        for (int k = cache.size() - 1; k >= 0; k--) if (cache.get(k).id.equals(id)) cache.remove(k);
        save(c);
        Scheduler.reschedule(c);
    }

    public static String newId() { return "i" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private static void load(Context c) {
        cache = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) cache.add(fromJson(a.getJSONObject(i)));
        } catch (Exception e) {
            // keep a copy of an unreadable file instead of overwriting it
            f.renameTo(new File(c.getFilesDir(), "items-unreadable-" + System.currentTimeMillis() + ".json"));
        }
    }

    private static void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : cache) a.put(toJson(i));
            File tmp = new File(c.getFilesDir(), "items.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file(c))) {
                file(c).delete();
                tmp.renameTo(file(c));
            }
        } catch (Exception ignored) { }
    }

    static JSONObject toJson(Item i) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", i.id); o.put("type", i.type); o.put("title", i.title); o.put("date", i.date); o.put("time", i.time);
        o.put("venue", i.venue); o.put("attendees", i.attendees); o.put("person", i.person); o.put("category", i.category);
        o.put("ref", i.ref); o.put("notes", i.notes); o.put("parent", i.parent); o.put("parentTitle", i.parentTitle);
        o.put("done", i.done); o.put("actionsAsked", i.actionsAsked); o.put("doneAt", i.doneAt); o.put("createdAt", i.createdAt);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.id = o.optString("id"); i.type = o.optString("type", Item.TASK); i.title = o.optString("title");
        i.date = o.optString("date"); i.time = o.optString("time"); i.venue = o.optString("venue");
        i.attendees = o.optString("attendees"); i.person = o.optString("person"); i.category = o.optString("category");
        i.ref = o.optString("ref"); i.notes = o.optString("notes"); i.parent = o.optString("parent");
        i.parentTitle = o.optString("parentTitle"); i.done = o.optBoolean("done"); i.actionsAsked = o.optBoolean("actionsAsked");
        i.doneAt = o.optLong("doneAt"); i.createdAt = o.optLong("createdAt");
        return i;
    }
}
