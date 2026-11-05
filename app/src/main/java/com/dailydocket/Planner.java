package com.dailydocket;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Works out briefings and every reminder the phone should ring. Plain Java for testing. */
public class Planner {

    public static final LocalTime MORNING = LocalTime.of(7, 30);
    public static final LocalTime EVENING = LocalTime.of(19, 0);
    public static final LocalTime WEEKLY = LocalTime.of(8, 0);   // Mondays

    public static class Reminder {
        public LocalDateTime at;
        public String kind;      // morning | evening | weekly | meeting | meeting-eve | deadline | followup | task
        public String itemId = "";
        public String title;
        public String text;
        public Reminder(LocalDateTime at, String kind, String itemId, String title, String text) {
            this.at = at; this.kind = kind; this.itemId = itemId; this.title = title; this.text = text;
        }
        public String key() { return kind + "|" + itemId + "|" + at; }
    }

    // ------------------------------------------------------------ reminders

    /** All reminders with time in (from, to]. Briefing texts are filled in when they fire. */
    public static List<Reminder> between(List<Item> items, LocalDateTime from, LocalDateTime to) {
        List<Reminder> out = new ArrayList<>();
        LocalDate d0 = from.toLocalDate(), d1 = to.toLocalDate();
        for (LocalDate d = d0; !d.isAfter(d1); d = d.plusDays(1)) {
            add(out, from, to, new Reminder(d.atTime(MORNING), "morning", "", "Morning briefing", ""));
            add(out, from, to, new Reminder(d.atTime(EVENING), "evening", "", "Evening wrap-up", ""));
            if (d.getDayOfWeek() == DayOfWeek.MONDAY)
                add(out, from, to, new Reminder(d.atTime(WEEKLY), "weekly", "", "Weekly follow-up report", ""));
        }
        for (Item it : items) {
            if (it.done || !it.hasDate()) continue;
            LocalDate d;
            try { d = LocalDate.parse(it.date); } catch (Exception e) { continue; }
            LocalTime t = null;
            if (it.hasTime()) try { t = LocalTime.parse(it.time); } catch (Exception ignored) { }
            switch (it.type) {
                case Item.MEETING:
                    if (t != null) {
                        String where = it.venue.isEmpty() ? "" : " · " + it.venue;
                        add(out, from, to, new Reminder(d.atTime(t).minusMinutes(30), "meeting", it.id,
                                "In 30 min: " + it.title, Parser.niceTime(it.time) + where));
                        add(out, from, to, new Reminder(d.minusDays(1).atTime(20, 0), "meeting-eve", it.id,
                                "Tomorrow " + Parser.niceTime(it.time) + ": " + it.title, it.venue.isEmpty() ? "Meeting tomorrow" : it.venue));
                    } else {
                        add(out, from, to, new Reminder(d.atTime(8, 30), "meeting", it.id,
                                "Today: " + it.title, it.venue.isEmpty() ? "Meeting today (no time set)" : it.venue));
                    }
                    break;
                case Item.DEADLINE:
                    String cat = Item.categoryLabel(it.category);
                    String refTxt = it.ref.isEmpty() ? "" : " · " + it.ref;
                    for (int n : new int[]{7, 3, 1}) {
                        add(out, from, to, new Reminder(d.minusDays(n).atTime(9, 0), "deadline", it.id,
                                (n == 1 ? "Due TOMORROW: " : n + " days left: ") + it.title,
                                cat + " · due " + Parser.shortDate(it.date) + refTxt));
                    }
                    add(out, from, to, new Reminder(d.atTime(8, 0), "deadline", it.id,
                            "Due TODAY: " + it.title, cat + refTxt));
                    break;
                case Item.FOLLOWUP:
                    LocalDateTime when = t != null ? d.atTime(t) : d.atTime(10, 0);
                    add(out, from, to, new Reminder(when, "followup", it.id,
                            "Check with " + (it.person.isEmpty() ? "officer" : it.person) + ": " + it.title,
                            it.ref.isEmpty() ? "Follow-up due" : it.ref));
                    break;
                default:
                    if (t != null) add(out, from, to, new Reminder(d.atTime(t), "task", it.id, it.title,
                            it.ref.isEmpty() ? "Task due now" : it.ref));
            }
        }
        out.sort(Comparator.comparing(r -> r.at));
        return out;
    }

    private static void add(List<Reminder> out, LocalDateTime from, LocalDateTime to, Reminder r) {
        if (r.at.isAfter(from) && !r.at.isAfter(to)) out.add(r);
    }

    /** The first reminder strictly after `from`, looking up to 40 days ahead. */
    public static Reminder next(List<Item> items, LocalDateTime from) {
        List<Reminder> l = between(items, from, from.plusDays(40));
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------ briefings

    static List<Item> open(List<Item> items) {
        List<Item> o = new ArrayList<>();
        for (Item i : items) if (!i.done) o.add(i);
        o.sort(Comparator.comparing((Item i) -> i.hasDate() ? i.date : "9999").thenComparing(i -> i.hasTime() ? i.time : "99"));
        return o;
    }

    static String strip(String t) { return t.replaceFirst("^Example: ", ""); }

    static long days(String iso, LocalDate today) { return ChronoUnit.DAYS.between(today, LocalDate.parse(iso)); }

    /** {notification title, full text} */
    public static String[] morning(List<Item> items, LocalDate today) {
        List<String> meet = new ArrayList<>(), overdue = new ArrayList<>(), due = new ArrayList<>(), dl = new ArrayList<>(), fu = new ArrayList<>();
        List<String> meetShort = new ArrayList<>();
        int dlCount = 0, fuCount = 0, odCount = 0;
        for (Item i : open(items)) {
            if (!i.hasDate()) continue;
            long n = days(i.date, today);
            String t = strip(i.title);
            if (i.type.equals(Item.MEETING)) {
                if (n == 0) {
                    meet.add((i.hasTime() ? Parser.niceTime(i.time) + "  " : "") + t + (i.venue.isEmpty() ? "" : " · " + i.venue));
                    meetShort.add((i.hasTime() ? i.time + " " : "") + shorten(t));
                }
                continue;
            }
            if (n < 0) { overdue.add(t + who(i) + " (" + (-n) + "d overdue)"); odCount++; }
            else if (n == 0) due.add(t + who(i));
            if (i.type.equals(Item.DEADLINE) && n >= 0 && n <= 7) { dl.add(t + " · " + (n == 0 ? "TODAY" : n == 1 ? "tomorrow" : "in " + n + " days") + (i.ref.isEmpty() ? "" : " · " + i.ref)); dlCount++; }
            if (i.type.equals(Item.FOLLOWUP) && n <= 0) { fu.add(i.person + ": " + t); fuCount++; }
        }
        StringBuilder head = new StringBuilder();
        if (meet.isEmpty() && dlCount == 0 && odCount == 0 && due.isEmpty()) head.append("Clear day: no meetings or deadlines today.");
        else {
            head.append(meet.size()).append(meet.size() == 1 ? " meeting" : " meetings");
            if (!meetShort.isEmpty()) head.append(" (").append(String.join(", ", meetShort.subList(0, Math.min(2, meetShort.size())))).append(")");
            head.append(" · ").append(dlCount).append(dlCount == 1 ? " deadline" : " deadlines").append(" this week");
            if (fuCount > 0) head.append(" · ").append(fuCount).append(" follow-up").append(fuCount == 1 ? "" : "s").append(" due");
            if (odCount > 0) head.append(" · ").append(odCount).append(" overdue");
        }
        StringBuilder b = new StringBuilder(head).append("\n");
        section(b, "Meetings today", meet);
        section(b, "Overdue", overdue);
        section(b, "Due today", due);
        section(b, "Deadlines this week", dl);
        section(b, "Follow-ups to check", fu);
        return new String[]{"Good morning · " + Parser.shortDate(today.toString()), b.toString().trim()};
    }

    public static String[] evening(List<Item> items, LocalDate today, long startOfTodayMillis) {
        List<String> left = new ArrayList<>(), tomorrow = new ArrayList<>(), dl = new ArrayList<>();
        int doneToday = 0;
        for (Item i : items) if (i.done && i.doneAt >= startOfTodayMillis) doneToday++;
        for (Item i : open(items)) {
            if (!i.hasDate()) continue;
            long n = days(i.date, today);
            String t = strip(i.title);
            if (!i.type.equals(Item.MEETING) && n <= 0) left.add(t + who(i));
            if (n == 1) tomorrow.add((i.hasTime() ? Parser.niceTime(i.time) + "  " : "") + t + (i.type.equals(Item.MEETING) ? "" : " (" + Item.typeLabel(i.type) + ")"));
            if (i.type.equals(Item.DEADLINE) && n >= 1 && n <= 3) dl.add(t + " · " + (n == 1 ? "tomorrow" : "in " + n + " days"));
        }
        String head = left.isEmpty() ? "All done for today · " + doneToday + " closed" : left.size() + " still open · " + doneToday + " closed today";
        if (!tomorrow.isEmpty()) head += " · " + tomorrow.size() + " tomorrow";
        StringBuilder b = new StringBuilder(head).append("\n");
        section(b, "Still open", left);
        section(b, "Tomorrow", tomorrow);
        section(b, "Deadlines in 3 days", dl);
        if (!left.isEmpty()) b.append("\nTap to tick what's done or move the rest to tomorrow.");
        return new String[]{"Evening wrap-up", b.toString().trim()};
    }

    public static String[] weekly(List<Item> items, LocalDate today, long weekAgoMillis) {
        Map<String, List<String>> byPerson = new LinkedHashMap<>();
        int pending = 0, overdue = 0, closed = 0;
        for (Item i : items) if (i.type.equals(Item.FOLLOWUP) && i.done && i.doneAt >= weekAgoMillis) closed++;
        for (Item i : open(items)) {
            if (!i.type.equals(Item.FOLLOWUP)) continue;
            pending++;
            String p = i.person.isEmpty() ? "Not assigned" : i.person;
            String s = strip(i.title);
            if (i.hasDate()) {
                long n = days(i.date, today);
                if (n < 0) { overdue++; s += " (" + (-n) + "d overdue)"; } else s += " (" + Parser.shortDate(i.date) + ")";
            }
            byPerson.computeIfAbsent(p, k -> new ArrayList<>()).add(s);
        }
        String head = pending + " follow-ups pending with " + byPerson.size() + (byPerson.size() == 1 ? " officer" : " officers")
                + (overdue > 0 ? " · " + overdue + " overdue" : "") + " · " + closed + " closed last week";
        StringBuilder b = new StringBuilder(head).append("\n");
        for (Map.Entry<String, List<String>> e : byPerson.entrySet()) section(b, e.getKey(), e.getValue());
        return new String[]{"Weekly follow-up report", b.toString().trim()};
    }

    static String who(Item i) { return i.type.equals(Item.FOLLOWUP) && !i.person.isEmpty() ? " — " + i.person : ""; }

    static String shorten(String s) { return s.length() > 28 ? s.substring(0, 27).trim() + "…" : s; }

    static void section(StringBuilder b, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        b.append("\n").append(title.toUpperCase(Locale.ROOT)).append("\n");
        for (String l : lines) b.append("• ").append(l).append("\n");
    }
}
