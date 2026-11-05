package com.dailydocket;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;

/**
 * One tap: opens straight into listening, understands what was said, saves it,
 * confirms with a notification (Undo / Edit) and closes. No other taps needed.
 */
public class VoiceActivity extends Activity {
    private static final int REQ = 7;
    private String parentId;
    private boolean savedAny = false;

    public static Intent intent(Context c, String parentMeetingId) {
        Intent i = new Intent(c, VoiceActivity.class);
        if (parentMeetingId != null) i.putExtra("parent", parentMeetingId);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        parentId = getIntent().getStringExtra("parent");
        if (b == null) listen();
    }

    private void listen() {
        Item parent = parentId == null ? null : Store.get(this, parentId);
        String lang = Prefs.language(this);
        boolean auto = Prefs.AUTO.equals(lang);
        String primary = auto ? "en-IN" : lang;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary);
        if (auto) {
            ArrayList<String> langs = new ArrayList<>(java.util.Arrays.asList("en-IN", "hi-IN", "gu-IN"));
            // Google voice input: listen for these languages together and pick whichever is spoken
            i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"hi-IN", "gu-IN"});
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, langs);
            }
        }
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (Prefs.offline(this)) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, parent != null
                ? (savedAny ? "Next action point, or press back to finish" : "Action point from: " + parent.title)
                : "Say a task, meeting, follow-up or deadline");
        try {
            startActivityForResult(i, REQ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Voice input isn't available. Install or enable the Google app, then try again.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        ArrayList<String> results = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res != RESULT_OK || results == null || results.isEmpty() || results.get(0).trim().isEmpty()) {
            if (parentId != null) {
                Item p = Store.get(this, parentId);
                if (p != null && savedAny) { p.actionsAsked = true; Store.put(this, p); }
            }
            finish();
            return;
        }
        try {
            handle(this, results.get(0), parentId);
            savedAny = true;
        } catch (Throwable e) {
            App.save(this, e);
            Toast.makeText(this, "Couldn't save that. Open Daily Docket to see the error, or use Type instead.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (parentId != null) listen();   // keep taking action points until the user presses back
        else finish();
    }

    /** Understand and save one sentence. Also used by "Type instead". */
    public static String handle(Context c, String spoken, String parentId) {
        Item parent = parentId == null ? null : Store.get(c, parentId);
        Parser.Result r = new Parser(LocalDate.now()).parse(spoken, Store.open(c), parent);
        String title, text;
        switch (r.kind) {
            case "complete": {
                Item before = r.target.copy();
                r.target.done = true;
                r.target.doneAt = System.currentTimeMillis();
                Store.put(c, r.target);
                title = "Marked done";
                text = r.target.title;
                Notifier.saved(c, title, text, "complete", before, r.target);
                break;
            }
            case "reschedule": {
                Item before = r.target.copy();
                r.target.date = r.date;
                r.target.time = r.time;
                Store.put(c, r.target);
                title = "Moved to " + Parser.shortDate(r.date) + (r.time.isEmpty() ? "" : ", " + Parser.niceTime(r.time));
                text = r.target.title;
                Notifier.saved(c, title, text, "reschedule", before, r.target);
                break;
            }
            default: {
                Item it = r.item;
                Store.put(c, it);
                title = "Saved " + Item.typeLabel(it.type).toLowerCase() + ": " + it.title;
                text = describe(it) + "\nYou said: “" + spoken + "”";
                Notifier.saved(c, title, text, "add", null, it);
            }
        }
        Toast.makeText(c, title, Toast.LENGTH_SHORT).show();
        return title;
    }

    static String describe(Item it) {
        StringBuilder b = new StringBuilder();
        if (it.hasDate()) b.append(Parser.shortDate(it.date));
        if (it.hasTime()) b.append(b.length() > 0 ? ", " : "").append(Parser.niceTime(it.time));
        if (!it.person.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(it.person);
        if (!it.venue.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(it.venue);
        if (it.type.equals(Item.DEADLINE)) b.append(b.length() > 0 ? " · " : "").append(Item.categoryLabel(it.category));
        if (!it.ref.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(it.ref);
        if (b.length() == 0) b.append("No date");
        return b.toString();
    }
}
