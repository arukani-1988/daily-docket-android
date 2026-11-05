package com.dailydocket;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Scan a letter, notice or order (camera or gallery) and turn it into docket entries. */
public class ScanActivity extends Activity {
    private static final int REQ_SCAN = 21, REQ_PICK = 22;

    private int PAPER, SURFACE, INK, MUTED, LINE, ACCENT, ACCENT_INK, MEET, DANGER;
    private LinearLayout body;
    private final List<Item> found = new ArrayList<>();
    private final List<Boolean> keep = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        palette();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAPER);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(32));
        sv.addView(body);
        setContentView(sv);
        getWindow().setStatusBarColor(PAPER);
        if (b == null) startScanner();
    }

    // ------------------------------------------------------------------ getting the image

    private void startScanner() {
        showStatus("Opening the scanner…", "Point the camera at the letter. You can also pick a photo from your gallery.");
        try {
            GmsDocumentScannerOptions o = new GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(3)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build();
            GmsDocumentScanning.getClient(o).getStartScanIntent(this)
                    .addOnSuccessListener(sender -> {
                        try { startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0); }
                        catch (IntentSender.SendIntentException e) { pickFromGallery(); }
                    })
                    .addOnFailureListener(e -> pickFromGallery());
        } catch (Throwable t) {
            pickFromGallery();
        }
    }

    private void pickFromGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(Intent.createChooser(i, "Choose a photo of the document"), REQ_PICK); }
        catch (Exception e) { Toast.makeText(this, "No camera scanner or gallery available.", Toast.LENGTH_LONG).show(); finish(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) { finish(); return; }
        List<Uri> pages = new ArrayList<>();
        if (req == REQ_SCAN) {
            GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (r != null && r.getPages() != null) for (GmsDocumentScanningResult.Page p : r.getPages()) pages.add(p.getImageUri());
        } else if (req == REQ_PICK) {
            if (data.getClipData() != null) for (int k = 0; k < Math.min(3, data.getClipData().getItemCount()); k++) pages.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) pages.add(data.getData());
        }
        if (pages.isEmpty()) { finish(); return; }
        read(pages);
    }

    // ------------------------------------------------------------------ reading

    private void read(List<Uri> pages) {
        String key = Prefs.geminiKey(this);
        showStatus("Reading the document…", key.isEmpty() ? "Reading the text on the phone." : "Reading it with Gemini. This takes a few seconds.");
        new Thread(() -> {
            DocRules.DocResult result = null;
            String note = null;
            if (!key.isEmpty()) {
                try { result = gemini(key, pages); }
                catch (Exception e) { note = "Gemini not reachable (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "). Read on the phone instead."; }
            }
            if (result == null) {
                try { result = DocRules.fromText(ocr(pages), LocalDate.now()); }
                catch (Throwable e) { App.save(this, e); }
            }
            final DocRules.DocResult fr = result;
            final String fn = note;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (fr == null) {
                    showStatus("Couldn't read that document", "Try again with the page flat and well lit, or add the details by hand.");
                    addButtons(false);
                    return;
                }
                if (fn != null) Toast.makeText(this, fn, Toast.LENGTH_LONG).show();
                showResult(fr);
            });
        }, "scan").start();
    }

    /** On-phone text recognition: English and Hindi (Devanagari) script. */
    private String ocr(List<Uri> pages) throws Exception {
        StringBuilder all = new StringBuilder();
        for (Uri u : pages) {
            InputImage img = InputImage.fromFilePath(this, u);
            String latin = "", deva = "";
            try { latin = Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)).getText(); } catch (Exception ignored) { }
            try {
                Text t = Tasks.await(TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build()).process(img));
                deva = t.getText();
            } catch (Exception ignored) { }
            all.append(deva.length() > latin.length() * 1.15 ? deva : latin).append("\n");
        }
        return all.toString();
    }

    private DocRules.DocResult gemini(String key, List<Uri> pages) throws Exception {
        LocalDate today = LocalDate.now();
        String prompt = "These are photos of an official letter, notice, order, circular or court document received by a senior Indian government officer. "
                + "It may be in English, Gujarati or Hindi. Read it and pick out what he must put in his diary.\n"
                + "Today is " + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + today + ".\n"
                + "Reply with only this JSON:\n"
                + "{\"summary\":\"2 short lines in English: who sent it, what it is about, what is asked\","
                + "\"items\":[{\"type\":\"meeting|deadline|task|followup\",\"title\":\"short English title, at most 9 words\","
                + "\"date\":\"YYYY-MM-DD or empty\",\"time\":\"HH:MM 24-hour or empty\",\"venue\":\"\",\"attendees\":\"\","
                + "\"person\":\"officer who must act (followup only)\",\"category\":\"court|assembly|rti|vip|compliance|other (deadline only)\","
                + "\"ref\":\"letter / file / case number\",\"notes\":\"one line: what exactly must be done or brought\"}]}\n"
                + "Rules: a meeting, VC, hearing or inspection he must attend = meeting (with its date, time and venue). "
                + "Anything he must send, submit, reply to or comply with by a date = deadline. "
                + "If nothing is dated, give one task to review the document. At most 4 items. Ignore the letter's own issue date. "
                + "Never invent dates, times or numbers that are not in the document.";
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
        for (Uri u : pages) {
            parts.put(new JSONObject().put("inline_data", new JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg(u, 1600), Base64.NO_WRAP))));
        }
        String j = Smart.generate(key, parts);
        JSONObject o = new JSONObject(j.substring(j.indexOf('{'), j.lastIndexOf('}') + 1));
        DocRules.DocResult r = new DocRules.DocResult();
        r.summary = o.optString("summary", "");
        JSONArray items = o.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length() && i < 6; i++) {
            Parser.Result pr = Smart.toResult(items.getJSONObject(i).put("intent", "add"), "", null, null, today);
            if (pr == null || pr.item == null) continue;
            pr.item.notes = pr.item.notes.replace("\nSaid: ", "").replace("Said: ", "");
            pr.item.notes = (pr.item.notes.isEmpty() ? "" : pr.item.notes + "\n") + "From scanned document: " + r.summary;
            r.items.add(pr.item);
        }
        if (r.items.isEmpty()) throw new Exception("nothing found");
        return r;
    }

    private byte[] jpeg(Uri u, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > max * 2) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o2); }
        if (bm == null) throw new Exception("couldn't open the photo");
        float k = Math.min(1f, (float) max / Math.max(bm.getWidth(), bm.getHeight()));
        if (k < 1f) bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ UI

    private void showStatus(String title, String hint) {
        body.removeAllViews();
        TextView t = tv(title, 22, INK, true);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        body.addView(t);
        body.addView(tv(hint, 14, MUTED, false), lp(dp(8)));
    }

    private void showResult(DocRules.DocResult r) {
        body.removeAllViews();
        found.clear();
        keep.clear();
        TextView t = tv("Found in the document", 22, INK, true);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        body.addView(t);
        if (!r.summary.isEmpty()) body.addView(tv(r.summary, 14.5f, MUTED, false), lp(dp(8)));
        body.addView(tv("Untick anything you don't want. You can edit entries after saving.", 13, MUTED, false), lp(dp(10)));
        for (Item it : r.items) {
            found.add(it);
            keep.add(true);
            final int idx = found.size() - 1;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(12), dp(14), dp(12));
            row.setBackground(round(SURFACE, 12, LINE));
            CheckBox cb = new CheckBox(this);
            cb.setChecked(true);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            cb.setOnCheckedChangeListener((v, on) -> { keep.set(idx, on); updateSave(); });
            row.addView(cb);
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            String label = it.type.equals(Item.DEADLINE) ? Item.categoryLabel(it.category).split(" /")[0] : Item.typeLabel(it.type);
            mid.addView(tv(label.toUpperCase(Locale.ROOT), 11, it.type.equals(Item.MEETING) ? MEET : it.type.equals(Item.DEADLINE) ? DANGER : ACCENT, true));
            mid.addView(tv(it.title, 16, INK, false));
            mid.addView(tv(VoiceActivity.describe(it), 13, MUTED, false));
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> cb.toggle());
            body.addView(row, lp(dp(10)));
        }
        addButtons(true);
    }

    private Button saveBtn;

    private void addButtons(boolean canSave) {
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        Button again = button("Scan again", SURFACE, INK, LINE);
        again.setOnClickListener(v -> startScanner());
        btns.addView(again);
        if (canSave) {
            saveBtn = button("Save", ACCENT, ACCENT_INK, 0);
            saveBtn.setOnClickListener(v -> save());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(10);
            btns.addView(saveBtn, p);
            updateSave();
        }
        body.addView(btns, lp(dp(20)));
    }

    private void updateSave() {
        if (saveBtn == null) return;
        int n = 0;
        for (Boolean k : keep) if (k) n++;
        saveBtn.setText(n == 0 ? "Close" : "Save " + n);
    }

    private void save() {
        int n = 0;
        for (int i = 0; i < found.size(); i++) if (keep.get(i)) { Store.put(this, found.get(i)); n++; }
        if (n > 0) Toast.makeText(this, n + (n == 1 ? " entry saved" : " entries saved"), Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void palette() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) { PAPER = 0xFF0E121A; SURFACE = 0xFF171C27; INK = 0xFFE6E9F0; MUTED = 0xFF98A2B3; LINE = 0xFF283041; ACCENT = 0xFFA9BDF2; ACCENT_INK = 0xFF0D1730; MEET = 0xFF6FCBC6; DANGER = 0xFFF07B70; }
        else { PAPER = 0xFFF4F5F8; SURFACE = 0xFFFFFFFF; INK = 0xFF161D2C; MUTED = 0xFF596377; LINE = 0xFFDBE0E8; ACCENT = 0xFF1E3A75; ACCENT_INK = 0xFFFFFFFF; MEET = 0xFF0D6A6E; DANGER = 0xFFB0261C; }
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg, int fg, int stroke) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(bg, 12, stroke));
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private GradientDrawable round(int color, int r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("unused") private static View unused;
}
