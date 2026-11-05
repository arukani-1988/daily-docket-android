package com.dailydocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;

/** Form to add or correct an entry. */
public class EditDialog {
    static final String[] TYPES = {Item.TASK, Item.MEETING, Item.FOLLOWUP, Item.DEADLINE};
    static final String[] TYPE_NAMES = {"Task", "Meeting", "Follow-up", "Deadline"};
    static final String[] CATS = {"court", "assembly", "rti", "vip", "compliance", "other"};
    static final String[] CAT_NAMES = {"Court matter", "Assembly / Parliament question", "RTI reply", "VIP / senior officer reference", "Compliance / report", "Other"};

    public static void show(Activity a, Item existing, Runnable after) {
        final boolean isNew = existing == null;
        final Item it = isNew ? new Item() : existing.copy();
        if (isNew) it.date = LocalDate.now().toString();

        int pad = dp(a, 20);
        LinearLayout f = new LinearLayout(a);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(pad, dp(a, 8), pad, 0);

        Spinner type = new Spinner(a);
        type.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, TYPE_NAMES));
        type.setSelection(indexOf(TYPES, it.type));
        f.addView(label(a, "Type")); f.addView(type);

        EditText title = field(a, f, "What", it.title, "e.g. Review flood relief file");
        EditText person = field(a, f, "Responsible officer", it.person, "e.g. Mr. Patel (EE)");
        View personLabel = f.getChildAt(f.getChildCount() - 2);

        TextView catLabel = label(a, "Kind of deadline");
        Spinner cat = new Spinner(a);
        cat.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, CAT_NAMES));
        cat.setSelection(Math.max(0, indexOf(CATS, it.category.isEmpty() ? "other" : it.category)));
        f.addView(catLabel); f.addView(cat);

        f.addView(label(a, "Date and time"));
        LinearLayout dt = new LinearLayout(a);
        Button date = new Button(a), time = new Button(a), clear = new Button(a);
        date.setAllCaps(false); time.setAllCaps(false); clear.setAllCaps(false);
        clear.setText("No date");
        Runnable showDT = () -> {
            date.setText(it.hasDate() ? Parser.shortDate(it.date) : "Pick date");
            time.setText(it.hasTime() ? Parser.niceTime(it.time) : "Add time");
        };
        showDT.run();
        date.setOnClickListener(v -> {
            LocalDate d = it.hasDate() ? LocalDate.parse(it.date) : LocalDate.now();
            new DatePickerDialog(a, (p, y, m, dd) -> { it.date = LocalDate.of(y, m + 1, dd).toString(); showDT.run(); },
                    d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
        });
        time.setOnClickListener(v -> {
            LocalTime t = it.hasTime() ? LocalTime.parse(it.time) : LocalTime.of(10, 0);
            new TimePickerDialog(a, (p, h, m) -> {
                it.time = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m);
                if (!it.hasDate()) it.date = LocalDate.now().toString();
                showDT.run();
            }, t.getHour(), t.getMinute(), false).show();
        });
        clear.setOnClickListener(v -> { it.date = ""; it.time = ""; showDT.run(); });
        dt.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dt.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dt.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        f.addView(dt);

        EditText venue = field(a, f, "Venue", it.venue, "e.g. Collectorate hall");
        View venueLabel = f.getChildAt(f.getChildCount() - 2);
        EditText ref = field(a, f, "File / letter / e-Office number", it.ref, "e.g. e-File 23/2026");
        EditText notes = field(a, f, "Notes", it.notes, "Optional");
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        Runnable vis = () -> {
            String t = TYPES[type.getSelectedItemPosition()];
            int fu = t.equals(Item.FOLLOWUP) ? View.VISIBLE : View.GONE;
            int me = t.equals(Item.MEETING) ? View.VISIBLE : View.GONE;
            int dl = t.equals(Item.DEADLINE) ? View.VISIBLE : View.GONE;
            person.setVisibility(fu); personLabel.setVisibility(fu);
            venue.setVisibility(me); venueLabel.setVisibility(me);
            cat.setVisibility(dl); catLabel.setVisibility(dl);
        };
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { vis.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        vis.run();

        ScrollView sv = new ScrollView(a);
        sv.addView(f);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(isNew ? "New entry" : "Edit entry")
                .setView(sv)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null);
        if (!isNew) b.setNeutralButton("Delete", null);
        AlertDialog d = b.create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String tt = title.getText().toString().trim();
                if (tt.isEmpty()) { title.setError("Write what needs doing"); return; }
                it.type = TYPES[type.getSelectedItemPosition()];
                it.title = tt;
                it.person = it.type.equals(Item.FOLLOWUP) ? person.getText().toString().trim() : "";
                it.venue = it.type.equals(Item.MEETING) ? venue.getText().toString().trim() : "";
                it.category = it.type.equals(Item.DEADLINE) ? CATS[cat.getSelectedItemPosition()] : "";
                it.ref = ref.getText().toString().trim();
                it.notes = notes.getText().toString().trim();
                if (!it.hasDate() && (it.type.equals(Item.MEETING) || it.type.equals(Item.DEADLINE))) it.date = LocalDate.now().toString();
                Store.put(a, it);
                Toast.makeText(a, isNew ? "Saved" : "Updated", Toast.LENGTH_SHORT).show();
                d.dismiss();
                after.run();
            });
            if (!isNew) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(a)
                            .setMessage("Delete “" + it.title + "” for good?")
                            .setPositiveButton("Delete", (q, w) -> {
                                Store.delete(a, it.id);
                                Toast.makeText(a, "Deleted", Toast.LENGTH_SHORT).show();
                                d.dismiss();
                                after.run();
                            })
                            .setNegativeButton("Keep", null)
                            .show());
        });
        d.show();
    }

    static EditText field(Activity a, LinearLayout f, String label, String value, String hint) {
        f.addView(label(a, label));
        EditText e = new EditText(a);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        f.addView(e);
        return e;
    }

    static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setPadding(0, dp(a, 12), 0, 0);
        t.setAlpha(0.7f);
        return t;
    }

    static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    static int dp(Activity a, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()));
    }
}
