package com.dailydocket;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * One tap: opens straight into listening. Keeps listening through pauses, shows the words
 * as they are heard, and saves automatically a couple of seconds after you stop talking
 * (or at once when you tap Done).
 */
public class VoiceActivity extends Activity {
    private static final int REQ_FALLBACK = 7, REQ_MIC = 8;
    private static final long SILENCE_MS = 2600;      // auto-save after this much quiet
    private static final long NOTHING_MS = 9000;      // give up if nothing is said at all

    private String parentId;
    private boolean savedAny = false;

    private SpeechRecognizer rec;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder heard = new StringBuilder();
    private String partial = "";
    private boolean finishing = false, listening = false, saving = false;
    private long startedAt;

    // UI
    private TextView status, words, hint;
    private View level;
    private Button doneBtn, cancelBtn;
    private int INK, MUTED, SURFACE, ACCENT, ACCENT_INK, LINE;

    public static Intent intent(Context c, String parentMeetingId) {
        Intent i = new Intent(c, VoiceActivity.class);
        if (parentMeetingId != null) i.putExtra("parent", parentMeetingId);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        parentId = getIntent().getStringExtra("parent");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fallback(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        begin();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) begin();
        else fallback();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (rec != null) { try { rec.destroy(); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ listening

    private void begin() {
        heard.setLength(0);
        partial = "";
        finishing = false;
        saving = false;
        startedAt = System.currentTimeMillis();
        Item parent = parentId == null ? null : Store.get(this, parentId);
        status.setText(parent != null ? (savedAny ? "Next action point…" : "Action points: " + parent.title) : "Listening…");
        words.setText("");
        hint.setText("Speak your full sentence. Short pauses are fine. It saves by itself when you stop, or tap Done.");
        doneBtn.setText(parentId != null && savedAny ? "Finish" : "Done");
        h.removeCallbacksAndMessages(null);
        h.postDelayed(nothingSaid, NOTHING_MS);
        startRecognizer();
    }

    private void startRecognizer() {
        if (finishing) return;
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        }
        try {
            rec.startListening(recognizerIntent(false));
            listening = true;
        } catch (Exception e) {
            App.save(this, e);
            fallback();
        }
    }

    private Intent recognizerIntent(boolean forPopup) {
        String lang = Prefs.language(this);
        boolean auto = Prefs.AUTO.equals(lang);
        String primary = auto ? "en-IN" : lang;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L);
        if (auto) {
            ArrayList<String> langs = new ArrayList<>(Arrays.asList("en-IN", "hi-IN", "gu-IN"));
            i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"hi-IN", "gu-IN"});
            if (Build.VERSION.SDK_INT >= 34) {
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
                i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, langs);
            }
        }
        if (Prefs.offline(this)) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        if (forPopup) i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task, meeting, follow-up or deadline");
        return i;
    }

    private final Runnable silenceDone = this::complete;
    private final Runnable nothingSaid = () -> {
        if (text().isEmpty()) {
            stopRecognizer();
            status.setText("Didn't hear anything");
            hint.setText("Tap the Speak icon to try again. Check that the microphone isn't blocked.");
            h.postDelayed(this::closeOrFinishActionPoints, 1800);
        }
    };

    private String text() {
        String s = (heard + " " + partial).trim();
        return s.replaceAll("\\s+", " ");
    }

    private void showWords() {
        words.setText(text());
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { h.removeCallbacks(silenceDone); status.setText("Listening…"); }
        @Override public void onRmsChanged(float rms) {
            float k = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
            level.setScaleX(0.08f + 0.92f * k);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int type, Bundle params) { }

        @Override public void onPartialResults(Bundle r) {
            String p = best(r);
            if (p != null && !p.isEmpty()) {
                partial = p;
                h.removeCallbacks(silenceDone);
                h.removeCallbacks(nothingSaid);
                showWords();
            }
        }

        @Override public void onResults(Bundle r) {
            listening = false;
            String p = best(r);
            if (p != null && !p.trim().isEmpty()) {
                if (heard.length() > 0) heard.append(' ');
                heard.append(p.trim());
                h.removeCallbacks(nothingSaid);
            }
            partial = "";
            showWords();
            if (finishing) { complete(); return; }
            // keep listening in case they continue after a pause; save if they stay quiet
            if (!text().isEmpty()) {
                status.setText("Saving in a moment… keep talking to add more");
                h.removeCallbacks(silenceDone);
                h.postDelayed(silenceDone, SILENCE_MS);
            }
            h.postDelayed(() -> startRecognizer(), 120);
        }

        @Override public void onError(int error) {
            listening = false;
            if (finishing) { complete(); return; }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (!partial.isEmpty()) { heard.append(heard.length() > 0 ? " " : "").append(partial); partial = ""; }
                    if (!text().isEmpty()) { complete(); return; }
                    if (System.currentTimeMillis() - startedAt < NOTHING_MS) h.postDelayed(() -> startRecognizer(), 150);
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    try { rec.cancel(); } catch (Exception ignored) { }
                    h.postDelayed(() -> startRecognizer(), 400);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    fallback();
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    if (!text().isEmpty()) { complete(); return; }
                    status.setText("No internet for voice");
                    hint.setText("Connect to the internet, or turn on Offline voice in Daily Docket (needs the offline speech pack).");
                    return;
                default:
                    if (!text().isEmpty()) { complete(); return; }
                    if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        Prefs.setLanguage(VoiceActivity.this, "en-IN");
                        h.postDelayed(() -> startRecognizer(), 200);
                        return;
                    }
                    fallback();
            }
        }
    };

    private static String best(Bundle r) {
        if (r == null) return null;
        ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private void stopRecognizer() {
        finishing = true;
        h.removeCallbacks(silenceDone);
        if (rec != null) { try { rec.cancel(); } catch (Exception ignored) { } }
        listening = false;
    }

    /** Done (tap) or silence: save what was heard. */
    private void complete() {
        if (isFinishing() || saving) return;
        saving = true;
        String said = text();
        stopRecognizer();
        if (said.isEmpty()) { closeOrFinishActionPoints(); return; }
        String title;
        try {
            title = handle(this, said, parentId, false);
            savedAny = true;
        } catch (Throwable e) {
            App.save(this, e);
            status.setText("Couldn't save that");
            hint.setText("Open Daily Docket to see the error, or add it with Type instead.");
            h.postDelayed(this::finish, 2500);
            return;
        }
        status.setText("Saved ✓");
        words.setText(title);
        hint.setText("You can undo or edit it from the notification.");
        level.setScaleX(0.08f);
        if (parentId != null) {
            h.postDelayed(this::begin, 1300);      // next action point
            doneBtn.setText("Finish");
        } else {
            h.postDelayed(this::finish, 1600);
        }
    }

    private void closeOrFinishActionPoints() {
        if (parentId != null && savedAny) {
            Item p = Store.get(this, parentId);
            if (p != null) { p.actionsAsked = true; Store.put(this, p); }
        }
        finish();
    }

    /** Google's own pop-up, used only when the in-app listener can't run. */
    private void fallback() {
        stopRecognizer();
        try {
            startActivityForResult(recognizerIntent(true), REQ_FALLBACK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Voice input isn't available. Install or enable the Google app, then try again.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FALLBACK) return;
        ArrayList<String> results = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res == RESULT_OK && results != null && !results.isEmpty() && !results.get(0).trim().isEmpty()) {
            try { handle(this, results.get(0), parentId, true); }
            catch (Throwable e) { App.save(this, e); Toast.makeText(this, "Couldn't save that. Open Daily Docket to see the error.", Toast.LENGTH_LONG).show(); }
        }
        finish();
    }

    // ------------------------------------------------------------------ saving

    /** Understand and save one sentence. Also used by "Type instead". Returns a short confirmation. */
    public static String handle(Context c, String spoken, String parentId) { return handle(c, spoken, parentId, true); }

    public static String handle(Context c, String spoken, String parentId, boolean toast) {
        Item parent = parentId == null ? null : Store.get(c, parentId);
        Parser.Result r = new Parser(LocalDate.now()).parse(spoken, Store.open(c), parent);
        String title, text;
        switch (r.kind) {
            case "complete": {
                Item before = r.target.copy();
                r.target.done = true;
                r.target.doneAt = System.currentTimeMillis();
                Store.put(c, r.target);
                title = "Marked done: " + r.target.title;
                text = r.target.title;
                Notifier.saved(c, "Marked done", text, "complete", before, r.target);
                break;
            }
            case "reschedule": {
                Item before = r.target.copy();
                r.target.date = r.date;
                r.target.time = r.time;
                Store.put(c, r.target);
                String when = Parser.shortDate(r.date) + (r.time.isEmpty() ? "" : ", " + Parser.niceTime(r.time));
                title = "Moved to " + when + ": " + r.target.title;
                text = r.target.title;
                Notifier.saved(c, "Moved to " + when, text, "reschedule", before, r.target);
                break;
            }
            default: {
                Item it = r.item;
                Store.put(c, it);
                title = Item.typeLabel(it.type) + ": " + it.title + "\n" + describe(it);
                text = describe(it) + "\nYou said: “" + spoken + "”";
                Notifier.saved(c, "Saved " + Item.typeLabel(it.type).toLowerCase() + ": " + it.title, text, "add", null, it);
            }
        }
        if (toast) Toast.makeText(c, title.split("\n")[0], Toast.LENGTH_SHORT).show();
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

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) { SURFACE = 0xFF171C27; INK = 0xFFE6E9F0; MUTED = 0xFF98A2B3; ACCENT = 0xFFA9BDF2; ACCENT_INK = 0xFF0D1730; LINE = 0xFF283041; }
        else { SURFACE = 0xFFFFFFFF; INK = 0xFF161D2C; MUTED = 0xFF596377; ACCENT = 0xFF1E3A75; ACCENT_INK = 0xFFFFFFFF; LINE = 0xFFDBE0E8; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x88000000);
        root.setOnClickListener(v -> { });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);

        status = tv("Listening…", 20, INK, true);
        status.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        card.addView(status);

        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(LINE); tb.setCornerRadius(dp(3));
        track.setBackground(tb);
        level = new View(this);
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(ACCENT); lb.setCornerRadius(dp(3));
        level.setBackground(lb);
        level.setPivotX(0);
        level.setScaleX(0.08f);
        track.addView(level, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        tp.topMargin = dp(12); tp.bottomMargin = dp(14);
        card.addView(track, tp);

        words = tv("", 22, INK, false);
        words.setMinHeight(dp(90));
        card.addView(words);

        hint = tv("", 13.5f, MUTED, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(10);
        card.addView(hint, hp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        cancelBtn = button("Cancel", SURFACE, INK, LINE);
        cancelBtn.setOnClickListener(v -> { stopRecognizer(); closeOrFinishActionPoints(); });
        doneBtn = button("Done", ACCENT, ACCENT_INK, 0);
        doneBtn.setOnClickListener(v -> {
            if (text().isEmpty() && parentId != null) { stopRecognizer(); closeOrFinishActionPoints(); return; }
            if (text().isEmpty()) { stopRecognizer(); finish(); return; }
            finishing = true;
            if (rec != null && listening) { try { rec.stopListening(); } catch (Exception ignored) { } h.postDelayed(this::complete, 900); }
            else complete();
        });
        btns.addView(cancelBtn);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp2.leftMargin = dp(10);
        btns.addView(doneBtn, dp2);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(18);
        card.addView(btns, bp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(card, cp);
        setContentView(root);
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bgc, int fg, int stroke) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgc);
        g.setCornerRadius(dp(12));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        b.setBackground(g);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
