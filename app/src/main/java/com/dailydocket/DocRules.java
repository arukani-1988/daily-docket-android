package com.dailydocket;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-phone reading of a scanned letter / notice when Gemini isn't available. Plain Java for testing. */
public class DocRules {

    public static class DocResult {
        public String summary = "";
        public List<Item> items = new ArrayList<>();
    }

    static final Pattern SUBJECT = Pattern.compile("^\\s*(?:subject|sub|re|विषय|વિષય)\\s*[:.\\-–]+\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern REFLINE = Pattern.compile("(?:\\bno\\.|\\bnumber\\b|letter no|\\bref|क्रमांक|क्र\\.|નં\\.|ક્રમાંક|જા\\.ક્ર|e-?file)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern DUE = Pattern.compile("(?:\\bby\\b|\\bbefore\\b|\\bwithin\\b|\\blatest\\b|on or before|\\bsubmit|\\bsend\\b|\\bfurnish|\\breply|\\bcompliance|\\blast date|तक|पूर्व|સુધીમાં|સુધી|પહેલાં|મોકલ)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern MEET = Pattern.compile("(?:meeting|\\bvc\\b|video ?conferenc|hearing|review|conference|inspection|visit|बैठक|मीटिंग|सुनवाई|બેઠક|મીટિંગ|સુનાવણી|મુલાકાત)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static DocResult fromText(String raw, LocalDate today) {
        DocResult out = new DocResult();
        String text = Parser.digits(raw == null ? "" : raw);
        String[] lines = text.split("\\r?\\n");

        // subject
        String subject = "";
        for (int i = 0; i < lines.length && subject.isEmpty(); i++) {
            Matcher m = SUBJECT.matcher(lines[i]);
            if (m.find()) {
                subject = m.group(1).trim();
                if (subject.length() < 40 && i + 1 < lines.length && !lines[i + 1].trim().isEmpty() && lines[i + 1].trim().length() < 80)
                    subject += " " + lines[i + 1].trim();
            }
        }
        if (subject.isEmpty()) {
            for (String l : lines) {
                String t = l.trim();
                if (t.length() >= 20 && !t.toLowerCase(Locale.ROOT).matches(".*(government|office of|date|dated|no\\.|tel|phone|email|fax|सरकार|कार्यालय|સરકાર|કચેરી).*")) { subject = t; break; }
            }
        }
        subject = Parser.shortenTitle(subject.replaceAll("[.\\s]+$", ""));

        // reference number
        String ref = "";
        for (String l : lines) {
            if (!REFLINE.matcher(l).find()) continue;
            Parser.Result r = new Parser(today).parse(l, null, null);
            if (r.item != null && !r.item.ref.isEmpty()) { ref = r.item.ref; break; }
        }

        // sentences with dates
        String flat = text.replaceAll("\\s*\\n\\s*", " ");
        String[] sentences = flat.split("(?<=[.।!?])\\s+|;\\s+");
        Set<String> seen = new HashSet<>();
        for (String s : sentences) {
            if (s.length() < 8 || s.length() > 400) continue;
            Parser.Result r = new Parser(today).parse(s, null, null);
            if (r.item == null || !r.hadDate) continue;
            if (r.item.date.compareTo(today.toString()) < 0) continue;            // the letter's own date, or past
            boolean meet = MEET.matcher(s).find() && (r.hadTime || s.toLowerCase(Locale.ROOT).contains("meeting"));
            boolean due = DUE.matcher(s).find();
            if (!meet && !due) continue;
            Item it = new Item();
            if (meet) {
                it.type = Item.MEETING;
                it.title = subject.isEmpty() ? r.item.title : "Meeting: " + subject;
                it.time = r.item.time;
                it.venue = r.item.venue;
            } else {
                it.type = Item.DEADLINE;
                it.title = subject.isEmpty() ? r.item.title : "Action due: " + subject;
                it.category = Parser.category(flat);
                it.time = r.item.time;
            }
            it.date = r.item.date;
            it.ref = ref;
            it.notes = "From scanned document: " + s.trim();
            if (seen.add(it.type + it.date)) out.items.add(it);
            if (out.items.size() >= 4) break;
        }
        if (out.items.isEmpty()) {
            Item it = new Item();
            it.type = Item.TASK;
            it.title = subject.isEmpty() ? "Review scanned document" : "Review: " + subject;
            it.ref = ref;
            it.notes = "From scanned document";
            out.items.add(it);
        }
        out.summary = (subject.isEmpty() ? "" : subject) + (ref.isEmpty() ? "" : (subject.isEmpty() ? "" : " · ") + ref);
        return out;
    }
}
