package com.dailydocket;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one spoken sentence into an action, entirely on the phone.
 * Understands English, Hinglish (romanised Hindi) and common Hindi / Gujarati
 * words for dates and times.
 */
public class Parser {

    public static class Result {
        public String kind = "add";      // add | complete | reschedule
        public Item item;                // for add
        public Item target;              // for complete / reschedule
        public String date = "", time = "";
        public String spoken = "";
    }

    // Android's regex engine (ICU) is already Unicode-aware and rejects UNICODE_CHARACTER_CLASS;
    // desktop Java needs the flag so Hindi / Gujarati words match the same way.
    private static final int F = flags();

    static int flags() {
        int base = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        String vm = System.getProperty("java.vm.name", "") + System.getProperty("java.vendor", "");
        if (vm.contains("Dalvik") || vm.contains("Android")) return base;
        try { Pattern.compile("\\bx\\b", base | Pattern.UNICODE_CHARACTER_CLASS); return base | Pattern.UNICODE_CHARACTER_CLASS; }
        catch (Throwable t) { return base; }
    }
    private static Pattern p(String re) { return Pattern.compile(re, F); }

    private final LocalDate today;
    private String rest;            // working text with recognised parts removed
    private String date = "", time = "";

    public Parser(LocalDate today) { this.today = today; }

    // ---------------------------------------------------------------- public

    public Result parse(String spoken, List<Item> openItems, Item parentMeeting) {
        String text = correct(digits(spoken == null ? "" : spoken).trim().replaceAll("\\s+", " "));
        Result r = new Result();
        r.spoken = text;

        if (parentMeeting == null) {
            Result c = tryComplete(text, openItems);
            if (c != null) { c.spoken = text; return c; }
            Result s = tryReschedule(text, openItems);
            if (s != null) { s.spoken = text; return s; }
        }

        rest = " " + text + " ";
        date = ""; time = "";
        Item it = new Item();
        it.notes = "Said: " + text;

        boolean meetingWord = MEETING.matcher(text).find();
        extractTime(meetingWord);
        extractDate();
        it.ref = extractRef();

        // Who is responsible? (follow-ups)
        String[] fu = extractFollowup();
        if (fu != null || parentMeeting != null && looksDelegated(text)) {
            it.type = Item.FOLLOWUP;
            if (fu != null) { it.person = fu[0]; it.title = fu[1]; }
        } else if (meetingWord && !DEADLINE.matcher(text).find() || meetingWord && time.length() > 0) {
            it.type = Item.MEETING;
            it.venue = extractVenue();
            it.attendees = findAttendees();
        } else if (DEADLINE.matcher(text).find()) {
            it.type = Item.DEADLINE;
            it.category = category(text);
        } else {
            it.type = Item.TASK;
        }

        if (it.title.isEmpty()) it.title = clean(rest);
        else it.title = clean(it.title);
        if (it.title.isEmpty()) it.title = clean(text);
        it.title = clean(shortenTitle(it.title));

        it.date = date;
        it.time = time;
        if (!it.hasDate() && it.hasTime()) it.date = today.toString();
        if (!it.hasDate() && (it.type.equals(Item.MEETING) || it.type.equals(Item.DEADLINE))) it.date = today.toString();
        if (!it.hasDate() && it.type.equals(Item.FOLLOWUP)) it.date = today.plusDays(3).toString();
        if (parentMeeting != null) { it.parent = parentMeeting.id; it.parentTitle = parentMeeting.title; }
        r.item = it;
        return r;
    }

    // ---------------------------------------------------------------- vocab

    static final Pattern MEETING = p("\\b(meeting|meet|vc|video ?conference|conference|visit|inspection|hearing|baithak|baithaq|call with|tour|dauro|daura|प्रवास|मीटिंग|बैठक|निरीक्षण|दौरा|મીટિંગ|બેઠક|સભા|મુલાકાત|નિરીક્ષણ)");
    static final Pattern DEADLINE = p("\\b(deadline|due|last date|court|affidavit|writ|petition|high court|hc|sca|assembly|vidhan ?sabha|parliament|lok ?sabha|rajya ?sabha|laq|starred|unstarred|question|rti|compliance|vip|mla|minister|cmo|submission|submit by|return|कोर्ट|अदालत|न्यायालय|हलफनामा|विधानसभा|विधान सभा|संसद|प्रश्न|आरटीआई|अंतिम तिथि|કોર્ટ|અદાલત|વિધાનસભા|પ્રશ્ન|આરટીઆઈ|છેલ્લી તારીખ)");
    static final Pattern DELEGATE = p("\\b(told|asked|instructed|directed|tell|ask|instruct|direct|ko bola|ko kaha|ko bolo|ko kaho|को बोला|को कहा|ને કહ્યું|ne kahyu)\\b");

    static String category(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        for (String w : new String[]{"कोर्ट", "अदालत", "न्यायालय", "हलफनामा", "કોર્ટ", "અદાલત"}) if (s.contains(w)) return "court";
        for (String w : new String[]{"विधानसभा", "विधान सभा", "संसद", "प्रश्न", "વિધાનસભા", "પ્રશ્ન"}) if (s.contains(w)) return "assembly";
        for (String w : new String[]{"आरटीआई", "આરટીઆઈ"}) if (s.contains(w)) return "rti";
        if (s.matches("(?s).*\\b(court|affidavit|writ|petition|high court|hc|sca|hearing)\\b.*")) return "court";
        if (s.matches("(?s).*\\b(assembly|vidhan ?sabha|parliament|lok ?sabha|rajya ?sabha|laq|starred|unstarred|question)\\b.*")) return "assembly";
        if (s.matches("(?s).*\\brti\\b.*")) return "rti";
        if (s.matches("(?s).*\\b(vip|mla|mp|minister|cmo|chief secretary|cs office)\\b.*")) return "vip";
        if (s.matches("(?s).*\\b(compliance|report|return|audit|statement)\\b.*")) return "compliance";
        return "other";
    }

    private boolean looksDelegated(String t) { return DELEGATE.matcher(t).find(); }

    // ---------------------------------------------------------------- time

    static final String QUAL = "(subah|subha|morning|savare|सुबह|સવારે|shaam|sham|evening|sanje|शाम|સાંજે|raat|night|रात|રાત્રે|dopahar|dopehar|afternoon|दोपहर|બપોરે|bapore)";
    static final Pattern T_FULL = p("(?:\\b" + QUAL + "\\s+)?(?:\\b(?:at|by|around)\\s+)?\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?|baje|बजे|વાગ્યે|vagye|o'?\\s?clock)(?:\\s+" + QUAL + "\\b)?");
    static final Pattern T_COLON = p("(?:\\b(?:at|by|around)\\s+)?\\b(\\d{1,2}):(\\d{2})\\b");
    static final Pattern T_NOON = p("(?:\\b(?:at|by)\\s+)?\\b(?:12\\s+)?(noon|midday)\\b");
    static final Pattern T_BARE_AT;

    private void extractTime(boolean meetingWord) {
        Matcher m = T_FULL.matcher(rest);
        if (m.find()) {
            int h = Integer.parseInt(m.group(2));
            int min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            String suf = m.group(4).toLowerCase(Locale.ROOT).replaceAll("[.\\s]", "");
            String q = (m.group(1) != null ? m.group(1) : m.group(5) != null ? m.group(5) : "").toLowerCase(Locale.ROOT);
            h = to24(h, suf, q);
            if (h >= 0 && h < 24 && min < 60) { time = String.format(Locale.ROOT, "%02d:%02d", h, min); cut(m); return; }
        }
        m = T_COLON.matcher(rest);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            if (h < 24 && min < 60) {
                if (h >= 1 && h <= 7) h += 12;
                time = String.format(Locale.ROOT, "%02d:%02d", h, min); cut(m); return;
            }
        }
        m = T_NOON.matcher(rest);
        if (m.find()) { time = "12:00"; cut(m); return; }
        {
            m = T_BARE_AT.matcher(rest);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                if (h >= 1 && h <= 12) { time = String.format(Locale.ROOT, "%02d:00", h <= 7 ? h + 12 : h); cut(m); }
            }
        }
    }

    static int to24(int h, String suf, String q) {
        boolean pmQ = q.matches("shaam|sham|evening|sanje|शाम|સાંજે|raat|night|रात|રાત્રે");
        boolean noonQ = q.matches("dopahar|dopehar|afternoon|दोपहर|બપોરે|bapore");
        boolean amQ = q.matches("subah|subha|morning|savare|सुबह|સવારે");
        if (suf.startsWith("p")) return h == 12 ? 12 : h + 12;
        if (suf.startsWith("a")) return h == 12 ? 0 : h;
        if (pmQ) return h < 12 ? h + 12 : h;
        if (noonQ) return h < 6 || h == 12 ? (h == 12 ? 12 : h + 12) : h;
        if (amQ) return h == 12 ? 0 : h;
        if (h >= 1 && h <= 7) return h + 12;      // "5 baje" in office context = 5 pm
        return h;
    }

    // ---------------------------------------------------------------- date

    static final String PRE = "(?:\\b(?:due\\s+)?(?:on|by|before|till|until|for|from)\\s+(?:the\\s+)?)?";
    static final String POST = "(?:\\s+(?:tak|तक|sudhi|સુધી|ko|को|ne|ને))?";
    static final Pattern D_DAYAFTER = p(PRE + "\\b(day after tomorrow|parso|parson|parsoon|परसों|પરમદિવસે|paramdivase)\\b" + POST);
    static final Pattern D_TOMORROW = p(PRE + "\\b(tomorrow|tomorow|tmrw|kal|कल|આવતીકાલે|avtikale|kale)\\b" + POST);
    static final Pattern D_TODAY = p(PRE + "\\b(today|tonight|aaj|aj|आज|આજે|aaje)\\b" + POST);
    static final Pattern D_NEXTWEEK = p(PRE + "\\b(next week|agle hafte|agle week|अगले हफ्ते|આવતા અઠવાડિયે)\\b" + POST);
    static final Pattern D_INDAYS = p("\\b(?:in|after)\\s+(\\d{1,2})\\s+days?\\b|\\b(\\d{1,2})\\s+(?:din|दिन|દિવસ)\\s*(?:baad|bad|me|mein|में|बाद|પછી)?\\b");
    static final Pattern D_NUM = p(PRE + "\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b" + POST);

    static final String[][] WEEKDAYS = {
            {"monday", "mon", "somvar", "somvaar", "सोमवार", "સોમવાર", "somvare"},
            {"tuesday", "tue", "tues", "mangalvar", "mangalvaar", "मंगलवार", "મંગળવાર", "mangalvare"},
            {"wednesday", "wed", "budhvar", "budhvaar", "बुधवार", "બુધવાર", "budhvare"},
            {"thursday", "thu", "thur", "thurs", "guruvar", "guruvaar", "brihaspativar", "गुरुवार", "ગુરુવાર", "guruvare"},
            {"friday", "fri", "shukravar", "shukravaar", "शुक्रवार", "શુક્રવાર", "shukravare"},
            {"saturday", "sat", "shanivar", "shanivaar", "शनिवार", "શનિવાર", "shanivare"},
            {"sunday", "sun", "ravivar", "ravivaar", "रविवार", "રવિવાર", "ravivare"}};
    static final String[][] MONTHS = {
            {"january", "jan"}, {"february", "feb"}, {"march", "mar"}, {"april", "apr"}, {"may"}, {"june", "jun"},
            {"july", "jul"}, {"august", "aug"}, {"september", "sep", "sept"}, {"october", "oct"}, {"november", "nov"}, {"december", "dec"}};

    static String monthAlt() {
        StringBuilder b = new StringBuilder();
        for (String[] m : MONTHS) for (String s : m) { if (b.length() > 0) b.append('|'); b.append(s); }
        return b.toString();
    }
    static int monthIndex(String s) {
        s = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 12; i++) for (String x : MONTHS[i]) if (x.equals(s)) return i + 1;
        return -1;
    }

    static final Pattern D_WEEKDAY;
    static final Pattern D_DAY_MONTH = p(PRE + "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + monthAlt() + ")\\b\\.?(?:,?\\s+(\\d{4}))?" + POST);
    static final Pattern D_MONTH_DAY = p(PRE + "\\b(" + monthAlt() + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?" + POST);
    static final Pattern D_ORDINAL = p(PRE + "\\b(\\d{1,2})(st|nd|rd|th)\\b" + POST);
    static final Pattern D_TARIKH = p(PRE + "\\b(\\d{1,2})\\s+(?:tarikh|tareekh|तारीख|તારીખ|tarikhe)\\b" + POST);

    static {
        StringBuilder b = new StringBuilder();
        for (String[] w : WEEKDAYS) for (String s : w) { if (b.length() > 0) b.append('|'); b.append(s); }
        T_BARE_AT = p("\\bat\\s+(\\d{1,2})\\b(?!\\s*(?:st|nd|rd|th|/|-|\\d|" + monthAlt() + "))");
        D_WEEKDAY = p(PRE + "(?:\\b(next|this|coming|agle|agla|is|aavta|આવતા)\\s+)?\\b(" + b + ")\\b" + POST);
    }

    private void extractDate() {
        Matcher m;
        if ((m = D_DAYAFTER.matcher(rest)).find()) { date = today.plusDays(2).toString(); cut(m); return; }
        if ((m = D_TOMORROW.matcher(rest)).find()) { date = today.plusDays(1).toString(); cut(m); return; }
        if ((m = D_TODAY.matcher(rest)).find()) { date = today.toString(); cut(m); return; }
        if ((m = D_NEXTWEEK.matcher(rest)).find()) { date = today.plusDays(7).toString(); cut(m); return; }
        if ((m = D_INDAYS.matcher(rest)).find()) {
            int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            date = today.plusDays(n).toString(); cut(m); return;
        }
        if ((m = D_DAY_MONTH.matcher(rest)).find()) {
            LocalDate d = dayMonth(Integer.parseInt(m.group(1)), monthIndex(m.group(2)), m.group(3));
            if (d != null) { date = d.toString(); cut(m); return; }
        }
        if ((m = D_MONTH_DAY.matcher(rest)).find()) {
            LocalDate d = dayMonth(Integer.parseInt(m.group(2)), monthIndex(m.group(1)), m.group(3));
            if (d != null) { date = d.toString(); cut(m); return; }
        }
        if ((m = D_WEEKDAY.matcher(rest)).find()) {
            int target = -1;
            String w = m.group(2).toLowerCase(Locale.ROOT);
            for (int i = 0; i < 7; i++) for (String s : WEEKDAYS[i]) if (s.equals(w)) target = i + 1;
            if (target > 0) {
                int cur = today.getDayOfWeek().getValue();
                int add = (target - cur + 7) % 7;
                if (add == 0) add = 7;
                date = today.plusDays(add).toString(); cut(m); return;
            }
        }
        if ((m = D_NUM.matcher(rest)).find()) {
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
            LocalDate x = (mo >= 1 && mo <= 12) ? dayMonth(d, mo, m.group(3)) : null;
            if (x != null) { date = x.toString(); cut(m); return; }
        }
        if ((m = D_ORDINAL.matcher(rest)).find() || (m = D_TARIKH.matcher(rest)).find()) {
            int d = Integer.parseInt(m.group(1));
            if (d >= 1 && d <= 31) {
                LocalDate x = null;
                for (int k = 0; k < 3 && x == null; k++) {
                    LocalDate base = today.withDayOfMonth(1).plusMonths(k);
                    if (d <= base.lengthOfMonth()) {
                        LocalDate c = base.withDayOfMonth(d);
                        if (!c.isBefore(today)) x = c;
                    }
                }
                if (x != null) { date = x.toString(); cut(m); }
            }
        }
    }

    private LocalDate dayMonth(int d, int mo, String yr) {
        if (mo < 1 || d < 1 || d > 31) return null;
        int y = today.getYear();
        if (yr != null) { y = Integer.parseInt(yr); if (y < 100) y += 2000; }
        try {
            LocalDate x = LocalDate.of(y, mo, d);
            if (yr == null && x.isBefore(today.minusDays(1))) x = x.plusYears(1);
            return x;
        } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------- ref no.

    static final Set<String> NOT_REF = new HashSet<>(Arrays.asList(
            "at", "on", "by", "in", "of", "to", "the", "and", "kal", "aaj", "baje", "for", "till", "from", "with",
            "about", "due", "day", "days", "din", "ko", "se", "room", "hall", "within", "after", "before", "around"));
    static final Pattern R_LABELLED = p("\\b(file|e-?file|letter|reference|ref|case|outward|inward|patra|पत्र)\\s*(?:no\\.?|number|num|nambar|क्रमांक)?\\s*[:#]?\\s*([\\w/.-]*\\d[\\w/.-]*)");
    static final Pattern R_CODE = p("\\b([a-z]{2,8})[\\s/.-]?(\\d{2,6}(?:/\\d{2,4})?)\\b");
    static final Pattern R_SLASH = p("\\b(\\d{1,6}/\\d{2,4})\\b");

    private String extractRef() {
        Matcher m = R_LABELLED.matcher(rest);
        if (m.find()) {
            String label = m.group(1);
            String ref = (label.toLowerCase(Locale.ROOT).startsWith("e") ? "e-File" : cap(label)) + " " + m.group(2);
            cut(m);
            return ref.replaceAll("[.]$", "");
        }
        m = R_CODE.matcher(rest);
        while (m.find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            String num = m.group(2);
            if (NOT_REF.contains(word)) continue;
            if (num.length() < 3 && !num.contains("/")) continue;
            if (monthIndex(word) > 0) continue;
            String ref = (word.length() <= 4 ? word.toUpperCase(Locale.ROOT) : cap(word)) + " " + num;
            cut(m);
            return ref;
        }
        m = R_SLASH.matcher(rest);
        if (m.find()) { String ref = m.group(1); cut(m); return ref; }
        return "";
    }

    // ---------------------------------------------------------------- follow-up

    static final Pattern F_TOLD = p("\\b(?:i\\s+(?:have\\s+)?)?(?:told|asked|instructed|directed|tell|ask|instruct|direct|remind)\\s+(.{2,40}?)\\s+(?:to|that\\s+(?:he|she|they)\\s+(?:should|will|must))\\s+(.+)");
    static final Pattern F_WITH = p("\\b(?:follow[\\s-]?up|check|confirm|pursue)\\s+with\\s+(.{2,40}?)\\s+(?:on|about|regarding|for|re)\\s+(.+)");
    static final Pattern F_KO_FIRST = p("^\\s*(.{2,30}?)\\s+(?:ko|को|ને|ne)\\s+(?:bola|bolo|kaha|kaho|bataya|batao|बोला|कहा|बोलो|कहो|કહ્યું|kahyu|kaho)\\s+(?:ki|कि|के|કે|ke)?\\s*(.+)$");
    static final Pattern F_KO_LAST = p("^\\s*(.{2,30}?)\\s+(?:ko|को|ને|ne)\\s+(.+?)\\s+(?:(?:ko|को|ne)\\s+)?(?:bola|kaha|bolo|kaho|बोला|कहा|बोलो|कहो|કહ્યું|kahyu)\\s*$");

    private String[] extractFollowup() {
        String s = rest.trim();
        Matcher m;
        for (Pattern pat : new Pattern[]{F_TOLD, F_WITH, F_KO_FIRST, F_KO_LAST}) {
            m = pat.matcher(s);
            if (m.find()) {
                String person = person(m.group(1));
                String task = m.group(2);
                if (person.isEmpty() || task.trim().length() < 2) continue;
                if (person.matches("(?i)me|myself|us|mujhe|muje")) continue;
                return new String[]{person, task};
            }
        }
        return null;
    }

    static final Set<String> ABBR = new HashSet<>(Arrays.asList(
            "sp", "dsp", "sdm", "ddo", "bdo", "tdo", "ee", "de", "ae", "dso", "adm", "rac", "cmo", "dm", "dc", "pa", "ps",
            "os", "jc", "ceo", "dpo", "deo", "dho", "cdho", "ro", "aro", "si", "pi", "io", "dyso", "mlo", "pwd", "rnb",
            "dysp", "ias", "gas", "mla", "mp", "cs", "acs", "pso", "dgp", "igp", "ig", "dig", "ssp", "sho", "eo", "ao", "dao", "ddpo", "tpo"));

    static String person(String raw) {
        String s = raw.trim().replaceAll("^(?i)(the|our|my|shri|shree|smt|श्री)\\s+", "");
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            String lw = w.toLowerCase(Locale.ROOT).replaceAll("[.,]", "");
            String out;
            if (ABBR.contains(lw)) out = lw.toUpperCase(Locale.ROOT);
            else if (lw.equals("mr") || lw.equals("mrs") || lw.equals("ms") || lw.equals("dr")) out = cap(lw) + ".";
            else if (lw.equals("sir") || lw.equals("saheb") || lw.equals("sahab") || lw.equals("ji") || lw.equals("madam")) out = lw;
            else out = cap(w);
            if (b.length() > 0) b.append(' ');
            b.append(out);
        }
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- meeting extras

    static final Pattern VENUE = p("\\b(?:at|in|venue)\\s+(?:the\\s+)?([^,]{3,60}?)(?=\\s+(?:about|regarding|on|for|re|with|to discuss|regarding)\\b|[,.]|\\s*$)");
    static final Set<String> NOT_VENUE_START = new HashSet<>(Arrays.asList("the", "a", "an", "my", "this", "that", "time", "advance"));

    private String extractVenue() {
        Matcher m = VENUE.matcher(rest);
        while (m.find()) {
            String v = m.group(1).trim();
            String first = v.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (NOT_VENUE_START.contains(first) || first.matches("\\d+")) continue;
            cut(m);
            return capWords(v);
        }
        return "";
    }

    private String findAttendees() {
        Matcher m = p("\\bwith\\s+(.{2,40}?)(?=\\s+(?:about|regarding|on|for|re|at|to discuss)\\b|[,.]|\\s*$)").matcher(rest);
        return m.find() ? person(m.group(1)) : "";
    }

    // ---------------------------------------------------------------- complete / reschedule

    static final Pattern C1 = p("^(?:please\\s+)?(?:mark|set)\\s+(.+?)\\s+(?:as\\s+)?(?:done|complete|completed|finished|closed)\\.?$");
    static final Pattern C2 = p("^(.+?)\\s+(?:is\\s+|has been\\s+|was\\s+)?(?:done|completed|finished|closed|over)\\.?$");
    static final Pattern C3 = p("^(.+?)\\s+(?:ho gaya|ho gayi|hogaya|ho gya|ho gai|pura ho gaya|poora ho gaya|हो गया|हो गई|पूरा हो गया|થઈ ગયું|thai gayu|patyu|પત્યું)\\.?$");
    static final Pattern C4 = p("^(?:close|complete|finish|tick)\\s+(.+)$");
    static final Pattern S1 = p("^(?:please\\s+)?(?:move|shift|postpone|reschedule|push|change)\\s+(.+?)\\s+(?:to|till|for|on)\\s+(.+)$");
    static final Pattern S2 = p("^(.+?)\\s+(?:ko|को)\\s+(.+?)\\s+(?:kar do|kardo|karo|par kar do|pe kar do|shift karo|kar dijiye|कर दो|करो)\\.?$");

    private Result tryComplete(String text, List<Item> open) {
        for (Pattern pat : new Pattern[]{C1, C3, C4, C2}) {
            Matcher m = pat.matcher(text);
            if (m.find()) {
                Item t = match(m.group(1), open);
                if (t != null) { Result r = new Result(); r.kind = "complete"; r.target = t; return r; }
            }
        }
        return null;
    }

    private Result tryReschedule(String text, List<Item> open) {
        for (Pattern pat : new Pattern[]{S1, S2}) {
            Matcher m = pat.matcher(text);
            if (!m.find()) continue;
            Item t = match(m.group(1), open);
            if (t == null) continue;
            rest = " " + m.group(2) + " ";
            date = ""; time = "";
            extractTime(true);
            extractDate();
            if (date.isEmpty() && time.isEmpty()) continue;
            Result r = new Result();
            r.kind = "reschedule"; r.target = t;
            r.date = date.isEmpty() ? t.date : date;
            r.time = time.isEmpty() ? t.time : time;
            if (r.date == null || r.date.isEmpty()) r.date = today.toString();
            return r;
        }
        return null;
    }

    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "a", "an", "of", "for", "to", "and", "with", "on", "in", "at", "my", "file", "task", "item", "entry",
            "wala", "wali", "vala", "vali", "ka", "ki", "ke", "about", "regarding", "mark", "is", "as", "done"));

    static Item match(String query, List<Item> open) {
        if (open == null) return null;
        List<String> q = tokens(query);
        if (q.isEmpty()) return null;
        Item best = null; double bestScore = 0;
        for (Item it : open) {
            List<String> t = tokens(it.title + " " + it.person + " " + it.ref);
            int hit = 0;
            for (String w : q) {
                for (String x : t) {
                    if (x.equals(w) || (w.length() >= 4 && x.length() >= 4 && (x.startsWith(w.substring(0, 4)) || w.startsWith(x.substring(0, 4))))) { hit++; break; }
                }
            }
            double score = (double) hit / q.size();
            if (score > bestScore) { bestScore = score; best = it; }
        }
        return bestScore >= 0.5 ? best : null;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}\\p{M}]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    // ---------------------------------------------------------------- speech clean-up

    /** Acronyms that voice input often spells out letter by letter ("d d o"). */
    static final Set<String> ACRONYMS = new HashSet<>(Arrays.asList(
            "rti", "laq", "sca", "vc", "pwd", "gad", "nic", "cmo", "pmo", "gr", "mou", "dpr", "atr", "pil", "fir", "mla", "mp",
            "sdm", "ddo", "bdo", "tdo", "dso", "adm", "rac", "dm", "dc", "sp", "dsp", "dysp", "ee", "de", "ae", "ceo", "dpo", "deo",
            "dho", "cdho", "ro", "aro", "pi", "psi", "ias", "ips", "gas", "cs", "acs", "dgp", "igp", "ssp", "sho", "eo", "ao", "phc", "chc"));

    static final String[][] FIXES = {
            {"collecter|kalektar|kalaktar|kalekter|collector's|collectors", "Collector"},
            {"mamlatdar's|mamledar|mamlatdaar|mamaltdar|mamlatdar", "Mamlatdar"},
            {"dido|d\\.d\\.o\\.?", "DDO"},
            {"e[ -]?file|ee file|efile", "e-File"},
            {"circus house|circle house|circuit house", "Circuit House"},
            {"jilla panchayat|zila panchayat|zilla panchayat|jila panchayat|jilla panchayat", "District Panchayat"},
            {"r and b|r ?& ?b", "R&B"},
            {"collectorate|collectrate|kalektoret", "Collectorate"},
            {"vidhan sabha|vidhansabha", "Vidhan Sabha"},
            {"gandhi nagar", "Gandhinagar"},
            {"video conference|video conferencing|v\\.c\\.", "VC"},
    };
    static final Pattern SPELLED = p("\\b([a-z])\\.?\\s([a-z])\\.?(?:\\s([a-z])\\.?)?(?:\\s([a-z])\\.?)?(?=\\s|$|[,.?!'])");
    static final Pattern POSSESSIVE = p("\\b([A-Z]{2,5})'s\\b");
    static final Pattern FILLER = p("^(?:(?:so|okay|ok|hello|hi|basically|actually|you know|matlab|achha|accha|haan|please note(?: that)?|note(?: that)?|"
            + "i want to|i would like to|i'd like to|i am supposed to|i'm supposed to|i've got to|i have got to|i have to|i need to|i must|"
            + "we have to|we need to|we have a|there is a|there's a|there is|mujhe|humein|hamein|hume|mere ko|apne ko)\\b[\\s,]*)+");
    static final Pattern VISIT_TO = p("\\b(visit|visiting|tour|inspection)\\s+to\\s+");

    static String correct(String t) {
        Matcher m = SPELLED.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder w = new StringBuilder();
            for (int g = 1; g <= 4; g++) if (m.group(g) != null) w.append(m.group(g));
            String word = w.toString().toLowerCase(Locale.ROOT);
            m.appendReplacement(sb, Matcher.quoteReplacement(ACRONYMS.contains(word) ? word.toUpperCase(Locale.ROOT) : m.group()));
        }
        m.appendTail(sb);
        t = sb.toString();
        for (String[] f : FIXES) t = p("\\b(?:" + f[0] + ")(?=\\s|$|[,.?!])").matcher(t).replaceAll(Matcher.quoteReplacement(f[1]));
        for (String a : ACRONYMS) t = p("\\b" + a + "\\b").matcher(t).replaceAll(a.toUpperCase(Locale.ROOT));
        t = POSSESSIVE.matcher(t).replaceAll("$1");
        t = FILLER.matcher(t).replaceFirst("");
        t = VISIT_TO.matcher(t).replaceAll("$1 ");
        return t.trim();
    }

    static final Pattern CUT = p("\\s+(?:because|since|as per|so that|which|where|kyunki|kyonki|jisme|taaki|kem ke|karan ke)\\s+");

    /** Keep titles short: drop the explanation after "because", "so that", etc. */
    static String shortenTitle(String t) {
        if (t.length() <= 60) return t;
        Matcher m = CUT.matcher(t);
        if (m.find() && m.start() >= 12) t = t.substring(0, m.start());
        if (t.length() > 70) {
            int comma = t.indexOf(", ", 15);
            if (comma > 0) t = t.substring(0, comma);
        }
        if (t.length() > 90) t = t.substring(0, 87).trim() + "…";
        return t;
    }

    // ---------------------------------------------------------------- helpers

    /** "११ बजे" / "૧૧ વાગ્યે" -> "11 बजे" */
    static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '\u0966' && ch <= '\u096F') b.append((char) ('0' + ch - '\u0966'));
            else if (ch >= '\u0AE6' && ch <= '\u0AEF') b.append((char) ('0' + ch - '\u0AE6'));
            else b.append(ch);
        }
        return b.toString();
    }

    private void cut(Matcher m) {
        rest = rest.substring(0, m.start()) + " " + rest.substring(m.end());
    }

    static final Pattern LEAD = p("^(?:(?:the|ok|okay|please|hey|add|create|schedule|set|set up|note|note down|make|new|a|an|remind me to|remind me|i have to|i need to|need to|have to|there is|there's|ek|mujhe|yaad dilana|yaad dila do)\\b[\\s,]*)+");
    static final Pattern TRAIL = p("(?:\\s+(?:on|by|at|before|till|until|for|due|the|and|in|of|with|about|regarding|ko|tak|hai|he|please|karna|karna hai|kar do|ke liye|is|are|was|che|chhe|hoga|hogi))+\\s*$");

    static String clean(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[,.;:\\-\\s]+|[,.;:\\-\\s]+$", "");
        String prev;
        do {
            prev = t;
            t = LEAD.matcher(t).replaceFirst("").trim();
            t = TRAIL.matcher(t).replaceFirst("").trim();
            t = t.replaceAll("^[,.;:\\-\\s]+|[,.;:\\-\\s]+$", "");
        } while (!t.equals(prev));
        t = t.replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ");
        if (t.isEmpty()) return t;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String cap(String w) {
        if (w == null || w.isEmpty()) return "";
        return Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT);
    }

    static String capWords(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (b.length() > 0) b.append(' ');
            String lw = w.toLowerCase(Locale.ROOT);
            b.append(ABBR.contains(lw) ? lw.toUpperCase(Locale.ROOT) : (lw.equals("of") || lw.equals("the")) ? lw : cap(w));
        }
        return b.toString();
    }

    /** "Fri 6 Nov" */
    public static String shortDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        LocalDate d = LocalDate.parse(iso);
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth() + " "
                + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** "11:00 am" */
    public static String niceTime(String t) {
        if (t == null || t.isEmpty()) return "";
        LocalTime x = LocalTime.parse(t);
        int h = x.getHour() % 12 == 0 ? 12 : x.getHour() % 12;
        return h + ":" + String.format(Locale.ROOT, "%02d", x.getMinute()) + (x.getHour() < 12 ? " am" : " pm");
    }

    @SuppressWarnings("unused")
    private static DayOfWeek unused() { return DayOfWeek.MONDAY; }
}
