package com.dailydocket;

import android.app.Activity;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Optional "smart understanding" with the user's own Gemini API key.
 * Sends only the spoken sentence and today's date; never other entries.
 * Any failure falls back to the on-phone rules.
 */
public class Smart {
    static final String[] MODELS = {"gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash"};

    public interface Done { void saved(String title, boolean smart); }

    /** Understand and save. Calls back on the UI thread with a confirmation, or null on failure. */
    public static void run(Activity a, String spoken, String parentId, boolean toast, Done cb) {
        String key = Prefs.geminiKey(a);
        if (key.isEmpty()) {
            cb.saved(VoiceActivity.handle(a, spoken, parentId, toast), false);
            return;
        }
        final Item parent = parentId == null ? null : Store.get(a, parentId);
        final List<Item> open = Store.open(a);
        new Thread(() -> {
            Parser.Result r = null;
            String err = null;
            try { r = understand(key, spoken, parent, open); }
            catch (Exception e) { err = e.getMessage(); }
            final Parser.Result fr = r;
            final String fe = err;
            a.runOnUiThread(() -> {
                try {
                    String t = fr != null ? VoiceActivity.apply(a, fr, spoken, toast) : VoiceActivity.handle(a, spoken, parentId, toast);
                    if (fr == null && fe != null) Toast.makeText(a, "Gemini not reachable (" + fe + "). Used on-phone rules.", Toast.LENGTH_LONG).show();
                    cb.saved(t, fr != null);
                } catch (Throwable ex) {
                    App.save(a, ex);
                    cb.saved(null, false);
                }
            });
        }, "smart").start();
    }

    static Parser.Result understand(String key, String spoken, Item parent, List<Item> open) throws Exception {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        String prompt = "You read one instruction that a senior Indian government officer spoke into his personal docket app. "
                + "Speech-to-text often mishears words: fix them from context (official titles such as Collector, DDO, SDM, Mamlatdar, TDO, BDO, SP, "
                + "Principal Secretary; offices; places in Gujarat; file, letter and case numbers). The speech may be English, Hindi, Gujarati or a mix. "
                + "Find the ONE main thing he wants recorded. Ignore filler words, greetings and explanations.\n\n"
                + "Today: " + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + today + ", time " + String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute()) + " (India).\n"
                + (parent != null ? "This is an action point from the meeting \"" + parent.title + "\". If someone else is responsible, it is a followup.\n" : "")
                + "\nReply with only this JSON object:\n"
                + "{\"intent\":\"add|complete|reschedule\",\"type\":\"task|meeting|followup|deadline\","
                + "\"title\":\"short clear title in English, at most 8 words, no date or time in it\","
                + "\"date\":\"YYYY-MM-DD or empty\",\"time\":\"HH:MM 24-hour or empty\",\"venue\":\"\",\"attendees\":\"\","
                + "\"person\":\"officer responsible (followup only)\",\"category\":\"court|assembly|rti|vip|compliance|other (deadline only)\","
                + "\"ref\":\"file / letter / case / e-File number if said\",\"notes\":\"other useful detail, one short line, or empty\","
                + "\"target\":\"for complete or reschedule: a few words naming the existing entry\"}\n\n"
                + "Rules: followup = he told, asked or directed someone else to do something (date = when to check back; if none, 3 days from today). "
                + "deadline = court matters, Assembly or Parliament questions, RTI replies, VIP or senior-officer references, compliance or report due dates. "
                + "meeting = meetings, VCs, visits, tours, inspections, hearings he attends. Otherwise task. "
                + "complete = he says something is done / ho gaya. reschedule = he asks to move, shift or postpone something. "
                + "Resolve relative dates from Today (kal = tomorrow, parso = day after tomorrow, aaj = today). "
                + "Times like '5 baje' with no am/pm in office context mean 5 pm for 1-7 and am for 8-11.\n\n"
                + "Instruction:\n<<<\n" + spoken + "\n>>>";

        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
        contents.put(msg);
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"));

        String reply = null;
        Exception last = null;
        for (String model : MODELS) {
            try {
                reply = post("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", key, body.toString());
                break;
            } catch (HttpError e) {
                last = e;
                if (e.code != 404) throw e;      // only try another model if this one doesn't exist
            }
        }
        if (reply == null) throw last != null ? last : new Exception("no reply");

        JSONObject res = new JSONObject(reply);
        JSONArray parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) txt.append(parts.getJSONObject(i).optString("text"));
        String j = txt.toString().trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        JSONObject o = new JSONObject(j.substring(j.indexOf('{'), j.lastIndexOf('}') + 1));
        return toResult(o, spoken, parent, open, today);
    }

    static Parser.Result toResult(JSONObject o, String spoken, Item parent, List<Item> open, LocalDate today) {
        String intent = o.optString("intent", "add");
        String date = o.optString("date", "").trim(), time = o.optString("time", "").trim();
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) date = "";
        if (!time.matches("\\d{2}:\\d{2}")) time = "";
        Parser.Result r = new Parser.Result();
        r.spoken = spoken;
        if (parent == null && (intent.equals("complete") || intent.equals("reschedule"))) {
            Item t = Parser.match(o.optString("target", o.optString("title", "")), open);
            if (t == null) return null;              // couldn't find it: let the rules try
            r.kind = intent;
            r.target = t;
            if (intent.equals("reschedule")) {
                if (date.isEmpty() && time.isEmpty()) return null;
                r.date = date.isEmpty() ? (t.hasDate() ? t.date : today.toString()) : date;
                r.time = time.isEmpty() ? t.time : time;
            }
            return r;
        }
        Item it = new Item();
        String type = o.optString("type", Item.TASK);
        it.type = type.equals(Item.MEETING) || type.equals(Item.FOLLOWUP) || type.equals(Item.DEADLINE) ? type : Item.TASK;
        it.title = o.optString("title", "").trim();
        if (it.title.isEmpty()) return null;
        it.date = date;
        it.time = time;
        if (it.type.equals(Item.MEETING)) { it.venue = o.optString("venue", "").trim(); it.attendees = o.optString("attendees", "").trim(); }
        if (it.type.equals(Item.FOLLOWUP)) it.person = o.optString("person", "").trim();
        if (it.type.equals(Item.DEADLINE)) {
            String c = o.optString("category", "other");
            it.category = c.matches("court|assembly|rti|vip|compliance") ? c : "other";
        }
        it.ref = o.optString("ref", "").trim();
        String n = o.optString("notes", "").trim();
        it.notes = (n.isEmpty() ? "" : n + "\n") + "Said: " + spoken;
        if (!it.hasDate() && it.hasTime()) it.date = today.toString();
        if (!it.hasDate() && (it.type.equals(Item.MEETING) || it.type.equals(Item.DEADLINE))) it.date = today.toString();
        if (!it.hasDate() && it.type.equals(Item.FOLLOWUP)) it.date = today.plusDays(3).toString();
        if (parent != null) { it.parent = parent.id; it.parentTitle = parent.title; }
        r.kind = "add";
        r.item = it;
        return r;
    }

    static class HttpError extends Exception {
        final int code;
        HttpError(int code, String m) { super(m); this.code = code; }
    }

    static String post(String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = read(in);
        c.disconnect();
        if (code >= 400) {
            String why = code == 400 || code == 403 ? "key not accepted" : code == 429 ? "free limit reached, try later" : "error " + code;
            throw new HttpError(code, why);
        }
        return text;
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
