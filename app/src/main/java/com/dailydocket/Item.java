package com.dailydocket;

/** One docket entry. Plain Java so the logic can be tested off-device. */
public class Item {
    public static final String TASK = "task", MEETING = "meeting", FOLLOWUP = "followup", DEADLINE = "deadline";

    public String id = "";
    public String type = TASK;
    public String title = "";
    public String date = "";      // yyyy-MM-dd or ""
    public String time = "";      // HH:mm or ""
    public String venue = "";
    public String attendees = "";
    public String person = "";
    public String category = "";  // deadlines: court, assembly, rti, vip, compliance, other
    public String ref = "";
    public String notes = "";
    public String parent = "";    // meeting id for action points
    public String parentTitle = "";
    public boolean done = false;
    public boolean actionsAsked = false;
    public long doneAt = 0;
    public long createdAt = 0;

    public Item copy() {
        Item c = new Item();
        c.id = id; c.type = type; c.title = title; c.date = date; c.time = time; c.venue = venue;
        c.attendees = attendees; c.person = person; c.category = category; c.ref = ref; c.notes = notes;
        c.parent = parent; c.parentTitle = parentTitle; c.done = done; c.actionsAsked = actionsAsked;
        c.doneAt = doneAt; c.createdAt = createdAt;
        return c;
    }

    public boolean hasDate() { return date != null && !date.isEmpty(); }
    public boolean hasTime() { return time != null && !time.isEmpty(); }

    public static String typeLabel(String t) {
        switch (t) {
            case MEETING: return "Meeting";
            case FOLLOWUP: return "Follow-up";
            case DEADLINE: return "Deadline";
            default: return "Task";
        }
    }

    public static String categoryLabel(String c) {
        if (c == null) return "Deadline";
        switch (c) {
            case "court": return "Court matter";
            case "assembly": return "Assembly / Parliament";
            case "rti": return "RTI reply";
            case "vip": return "VIP reference";
            case "compliance": return "Compliance / report";
            default: return "Deadline";
        }
    }
}
