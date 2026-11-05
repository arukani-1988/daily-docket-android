package com.dailydocket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    // palette (light / dark)
    int PAPER, SURFACE, INK, MUTED, LINE, ACCENT, ACCENT_INK, ACCENT_SOFT, MEET, MEET_SOFT, WARN, WARN_SOFT, DANGER, DANGER_SOFT;

    LinearLayout content;
    ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        palette();
        Notifier.channels(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(40));
        scroll.addView(content);
        setContentView(scroll);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Scheduler.reschedule(this);
        handleEdit(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleEdit(i); }

    @Override
    protected void onResume() { super.onResume(); render(); }

    void handleEdit(Intent i) {
        String id = i == null ? null : i.getStringExtra("edit");
        if (id == null) return;
        i.removeExtra("edit");
        Item it = Store.get(this, id);
        if (it != null) EditDialog.show(this, it, this::render);
    }

    // ------------------------------------------------------------------ render

    void render() {
        content.removeAllViews();
        LocalDate today = LocalDate.now();
        List<Item> all = Store.all(this);
        List<Item> open = Planner.open(all);
        int hr = LocalTime.now().getHour();

        // header
        content.addView(label(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ROOT) + ", "
                + today.getDayOfMonth() + " " + today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ROOT)));
        TextView h = text(hr < 12 ? "Good morning" : hr < 17 ? "Good afternoon" : "Good evening", 28, INK, true);
        h.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        content.addView(h, lp(0, dp(2), 0, dp(2)));

        int meetToday = 0, dlWeek = 0, fuDue = 0, od = 0;
        for (Item i : open) {
            if (!i.hasDate()) continue;
            long n = days(i.date, today);
            if (i.type.equals(Item.MEETING) && n == 0) meetToday++;
            if (i.type.equals(Item.DEADLINE) && n >= 0 && n <= 7) dlWeek++;
            if (i.type.equals(Item.FOLLOWUP) && n <= 0) fuDue++;
            if (!i.type.equals(Item.MEETING) && n < 0) od++;
        }
        TextView tally = text(meetToday + (meetToday == 1 ? " meeting" : " meetings") + " today · " + dlWeek
                + (dlWeek == 1 ? " deadline" : " deadlines") + " this week · " + fuDue + " follow-up" + (fuDue == 1 ? "" : "s") + " due"
                + (od > 0 ? " · " + od + " overdue" : ""), 14, od > 0 ? DANGER : MUTED, false);
        content.addView(tally);

        // speak button
        Button speak = new Button(this);
        speak.setText("  Tap and speak");
        speak.setAllCaps(false);
        speak.setTextSize(19);
        speak.setTypeface(Typeface.DEFAULT_BOLD);
        speak.setTextColor(ACCENT_INK);
        speak.setBackground(round(ACCENT, 16, 0));
        speak.setCompoundDrawablesRelativeWithIntrinsicBounds(tint(R.drawable.ic_mic, ACCENT_INK), null, null, null);
        speak.setPadding(dp(20), 0, dp(20), 0);
        speak.setOnClickListener(v -> startActivity(VoiceActivity.intent(this, null)));
        content.addView(speak, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)) {{ topMargin = dp(18); }});

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(link("Type instead", v -> typeDialog()));
        tools.addView(link("Add by form", v -> EditDialog.show(this, null, this::render)));
        tools.addView(link("Voice: " + Prefs.languageName(this), v -> { Prefs.nextLanguage(this); render(); }));
        content.addView(tools, lp(0, dp(6), 0, 0));

        // meetings that ended: ask for action points
        LocalDateTime now = LocalDateTime.now();
        for (Item m : open) {
            if (!m.type.equals(Item.MEETING) || m.actionsAsked || !m.hasDate()) continue;
            LocalDateTime end = LocalDate.parse(m.date).atTime(m.hasTime() ? LocalTime.parse(m.time) : LocalTime.of(18, 0)).plusMinutes(60);
            if (end.isAfter(now) || ChronoUnit.DAYS.between(LocalDate.parse(m.date), today) > 3) continue;
            content.addView(actionPointCard(m));
        }

        // evening wrap-up
        if (hr >= 17) {
            int left = 0;
            for (Item i : open) if (!i.type.equals(Item.MEETING) && i.hasDate() && days(i.date, today) <= 0) left++;
            if (left > 0) content.addView(wrapUpCard(left));
        }

        List<Item> overdue = new ArrayList<>(), meet = new ArrayList<>(), due = new ArrayList<>(), dl = new ArrayList<>(),
                soon = new ArrayList<>(), nodate = new ArrayList<>(), doneToday = new ArrayList<>();
        Map<String, List<Item>> fuBy = new LinkedHashMap<>();
        long startToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Item i : all) if (i.done && i.doneAt >= startToday) doneToday.add(i);
        for (Item i : open) {
            if (i.type.equals(Item.FOLLOWUP)) fuBy.computeIfAbsent(i.person.isEmpty() ? "Not assigned" : i.person, k -> new ArrayList<>()).add(i);
            if (!i.hasDate()) { if (!i.type.equals(Item.MEETING)) nodate.add(i); continue; }
            long n = days(i.date, today);
            if (i.type.equals(Item.MEETING)) { if (n == 0) meet.add(i); else if (n > 0 && n <= 14) soon.add(i); continue; }
            if (n < 0) overdue.add(i);
            else if (n == 0) due.add(i);
            else if (i.type.equals(Item.DEADLINE) && n <= 7) dl.add(i);
            else if (n <= 14 && !i.type.equals(Item.FOLLOWUP)) soon.add(i);
        }
        section("Overdue", overdue, null);
        section("Meetings today", meet, "No meetings today.");
        section("Due today", due, "Nothing else is due today.");
        section("Hard deadlines this week", dl, null);

        if (!fuBy.isEmpty()) {
            content.addView(heading("Follow-ups pending", countFu(fuBy)));
            for (Map.Entry<String, List<Item>> e : fuBy.entrySet()) {
                TextView p = text(e.getKey(), 15, INK, true);
                p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                content.addView(p, lp(0, dp(10), 0, dp(4)));
                content.addView(list(e.getValue()));
            }
        }
        section("Coming up (2 weeks)", soon, null);
        section("Not yet dated", nodate, null);
        section("Done today", doneToday, null);

        // footer
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        content.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) {{ topMargin = dp(30); bottomMargin = dp(12); }});
        content.addView(text("Notifications: morning briefing 7:30 am, evening wrap-up 7:00 pm, follow-up report Mondays 8:00 am. "
                + "Meetings: evening before and 30 minutes before. Deadlines: 7, 3 and 1 day before and on the day. "
                + "Entries stay on this phone.", 12.5f, MUTED, false));
        if (!Scheduler.canExact(this)) {
            TextView warn = text("Reminders may arrive a few minutes late. Tap here and allow “Alarms & reminders” for Daily Docket.", 13, WARN, true);
            warn.setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))); }
                catch (Exception ignored) { }
            });
            content.addView(warn, lp(0, dp(8), 0, 0));
        }
        LinearLayout foot = new LinearLayout(this);
        foot.addView(link("Show today's briefing", v -> {
            Notifier.reminder(this, new Planner.Reminder(LocalDateTime.now(), "morning", "", "", ""), Store.all(this));
            Toast.makeText(this, "Briefing sent to your notifications", Toast.LENGTH_SHORT).show();
        }));
        foot.addView(link(Prefs.offline(this) ? "Offline voice: on" : "Offline voice: off", v -> {
            Prefs.setOffline(this, !Prefs.offline(this)); render();
            Toast.makeText(this, Prefs.offline(this) ? "Voice will stay on the phone. Needs the offline speech pack for your language." : "Voice uses Google's recognition (most accurate).", Toast.LENGTH_LONG).show();
        }));
        content.addView(foot, lp(0, dp(8), 0, 0));
    }

    int countFu(Map<String, List<Item>> m) { int n = 0; for (List<Item> l : m.values()) n += l.size(); return n; }

    void section(String title, List<Item> items, String empty) {
        if (items.isEmpty() && empty == null) return;
        content.addView(heading(title, items.size()));
        if (items.isEmpty()) {
            TextView e = text(empty, 14, MUTED, false);
            e.setPadding(dp(14), dp(12), dp(14), dp(12));
            e.setBackground(round(SURFACE, 12, LINE));
            content.addView(e);
        } else content.addView(list(items));
    }

    View heading(String t, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        TextView h = text(t, 17, INK, true);
        h.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        row.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (count > 0) row.addView(text(String.valueOf(count), 12, MUTED, false));
        row.setLayoutParams(lp(0, dp(24), 0, dp(8)));
        return row;
    }

    View list(List<Item> items) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(SURFACE, 12, LINE));
        box.setClipToOutline(true);
        for (int k = 0; k < items.size(); k++) {
            if (k > 0) { View d = new View(this); d.setBackgroundColor(LINE); box.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            box.addView(row(items.get(k)));
        }
        return box;
    }

    View row(Item it) {
        LocalDate today = LocalDate.now();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(10), dp(10), dp(10));
        boolean meeting = it.type.equals(Item.MEETING);

        // urgency stripe
        View stripe = new View(this);
        int sc = Color.TRANSPARENT;
        if (!it.done && it.hasDate() && !meeting) {
            long n = days(it.date, today);
            if (n < 0 || (it.type.equals(Item.DEADLINE) && n <= 1)) sc = DANGER;
            else if (it.type.equals(Item.DEADLINE) && n <= 3) sc = WARN;
        }
        stripe.setBackground(round(sc, 2, 0));
        r.addView(stripe, new LinearLayout.LayoutParams(dp(3), dp(40)) {{ rightMargin = dp(6); }});

        if (meeting) {
            TextView t = text(it.hasTime() ? it.time : "—", 14, MEET, true);
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            r.addView(t, new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(it.done);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            cb.setOnClickListener(v -> {
                it.done = cb.isChecked();
                it.doneAt = it.done ? System.currentTimeMillis() : 0;
                Store.put(this, it);
                if (it.done) Toast.makeText(this, "Marked done", Toast.LENGTH_SHORT).show();
                render();
            });
            r.addView(cb);
        }

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(it.title, 15.5f, it.done ? MUTED : INK, false);
        if (it.done) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        mid.addView(title);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        String tagText = it.type.equals(Item.DEADLINE) ? Item.categoryLabel(it.category).split(" /")[0] : Item.typeLabel(it.type);
        int[] tc = it.type.equals(Item.MEETING) ? new int[]{MEET_SOFT, MEET} : it.type.equals(Item.FOLLOWUP) ? new int[]{ACCENT_SOFT, ACCENT}
                : it.type.equals(Item.DEADLINE) ? new int[]{DANGER_SOFT, DANGER} : new int[]{LINE, INK};
        TextView tag = text(tagText.toUpperCase(Locale.ROOT), 10, tc[1], true);
        tag.setLetterSpacing(0.06f);
        tag.setPadding(dp(5), dp(1), dp(5), dp(1));
        tag.setBackground(round(tc[0], 4, 0));
        meta.addView(tag);
        StringBuilder m = new StringBuilder();
        if (!it.person.isEmpty() && !it.type.equals(Item.MEETING)) m.append("  ").append(it.person);
        if (meeting && !it.venue.isEmpty()) m.append("  ").append(it.venue);
        if (it.hasDate() && !it.done) {
            long n = days(it.date, today);
            String rel = n < 0 ? (-n) + (n == -1 ? " day overdue" : " days overdue") : n == 0 ? (meeting ? "" : "Today") : n == 1 ? "Tomorrow" : Parser.shortDate(it.date);
            if (!rel.isEmpty()) m.append("  ").append(rel);
        }
        if (it.hasTime() && !meeting) m.append(" ").append(Parser.niceTime(it.time));
        if (!it.ref.isEmpty()) m.append("  ").append(it.ref);
        TextView mt = text(m.toString(), 12.5f, MUTED, false);
        mt.setSingleLine(false);
        meta.addView(mt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mid.addView(meta, lp(0, dp(3), 0, 0));
        if (!it.parentTitle.isEmpty()) mid.addView(text("From: " + it.parentTitle, 12, MUTED, false));
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) {{ leftMargin = dp(6); }});

        if (!meeting && !it.done) {
            TextView tm = text("Tomorrow", 12.5f, MUTED, false);
            tm.setPadding(dp(9), dp(5), dp(9), dp(5));
            tm.setBackground(round(SURFACE, 8, LINE));
            tm.setOnClickListener(v -> {
                it.date = today.plusDays(1).toString();
                Store.put(this, it);
                Toast.makeText(this, "Moved to tomorrow", Toast.LENGTH_SHORT).show();
                render();
            });
            r.addView(tm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ leftMargin = dp(8); }});
        }
        r.setOnClickListener(v -> EditDialog.show(this, it, this::render));
        r.setBackground(ripple());
        return r;
    }

    View actionPointCard(Item m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(MEET_SOFT, 12, 0));
        TextView t = text("Any action points from “" + m.title + "”?", 15.5f, MEET, true);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        card.addView(t);
        card.addView(text("Tap speak and say each action point. Naming an officer makes it a follow-up. Press back when finished.", 13.5f, MUTED, false), lp(0, dp(4), 0, dp(10)));
        LinearLayout btns = new LinearLayout(this);
        Button go = pill("Speak action points", ACCENT, ACCENT_INK);
        go.setOnClickListener(v -> startActivity(VoiceActivity.intent(this, m.id)));
        Button none = pill("None", SURFACE, INK);
        none.setOnClickListener(v -> { m.actionsAsked = true; Store.put(this, m); render(); });
        btns.addView(go);
        btns.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ leftMargin = dp(8); }});
        card.addView(btns);
        card.setLayoutParams(lp(0, dp(16), 0, 0));
        return card;
    }

    View wrapUpCard(int left) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(ACCENT_SOFT, 12, 0));
        TextView t = text("Evening wrap-up", 15.5f, ACCENT, true);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        card.addView(t);
        card.addView(text(left + (left == 1 ? " item is" : " items are") + " still open for today. Tick what you finished, then move the rest to tomorrow.", 13.5f, INK, false), lp(0, dp(4), 0, dp(10)));
        Button roll = pill("Move " + left + " to tomorrow", ACCENT, ACCENT_INK);
        roll.setOnClickListener(v -> {
            LocalDate today = LocalDate.now();
            for (Item i : Store.open(this)) {
                if (!i.type.equals(Item.MEETING) && i.hasDate() && days(i.date, today) <= 0) { i.date = today.plusDays(1).toString(); Store.put(this, i); }
            }
            Toast.makeText(this, "Moved to tomorrow", Toast.LENGTH_SHORT).show();
            render();
        });
        card.addView(roll);
        card.setLayoutParams(lp(0, dp(16), 0, 0));
        return card;
    }

    void typeDialog() {
        EditText e = new EditText(this);
        e.setHint("e.g. Meeting with SP tomorrow 11 am at Circuit House");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Type an instruction")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (!s.isEmpty()) { VoiceActivity.handle(this, s, null); render(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ helpers

    static long days(String iso, LocalDate today) { return ChronoUnit.DAYS.between(today, LocalDate.parse(iso)); }

    void palette() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            PAPER = 0xFF0F141B; SURFACE = 0xFF171D27; INK = 0xFFE5E9EF; MUTED = 0xFF97A1B0; LINE = 0xFF28303D;
            ACCENT = 0xFF5FC391; ACCENT_INK = 0xFF0B1A12; ACCENT_SOFT = 0xFF16301F; MEET = 0xFF91B2EF; MEET_SOFT = 0xFF1A2540;
            WARN = 0xFFE8A742; WARN_SOFT = 0xFF30240E; DANGER = 0xFFF07B70; DANGER_SOFT = 0xFF381917;
        } else {
            PAPER = 0xFFF3F5F7; SURFACE = 0xFFFFFFFF; INK = 0xFF18212F; MUTED = 0xFF5B6574; LINE = 0xFFDCE1E7;
            ACCENT = 0xFF1D6B47; ACCENT_INK = 0xFFFFFFFF; ACCENT_SOFT = 0xFFE2F0E7; MEET = 0xFF2C4D8C; MEET_SOFT = 0xFFE5EBF6;
            WARN = 0xFF9A5C00; WARN_SOFT = 0xFFFBEFD9; DANGER = 0xFFB0261C; DANGER_SOFT = 0xFFFBE7E4;
        }
    }

    int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s, 12, MUTED, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    TextView link(String s, View.OnClickListener l) {
        TextView t = text(s, 13.5f, ACCENT, true);
        t.setPadding(dp(4), dp(10), dp(14), dp(10));
        t.setOnClickListener(l);
        return t;
    }

    Button pill(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(bg, 10, bg == SURFACE ? LINE : 0));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        return b;
    }

    GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    android.graphics.drawable.Drawable ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return getDrawable(tv.resourceId);
    }

    android.graphics.drawable.Drawable tint(int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }
}
