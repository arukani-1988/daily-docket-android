package com.dailydocket;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    static final String AUTO = "auto";
    static final String[] LANGS = {AUTO, "en-IN", "hi-IN", "gu-IN"};
    static final String[] LANG_NAMES = {"Auto (English, Hindi, Gujarati)", "English only", "Hindi only", "Gujarati only"};

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("prefs", Context.MODE_PRIVATE); }

    public static String language(Context c) { return sp(c).getString("lang2", AUTO); }

    public static String languageName(Context c) {
        String l = language(c);
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(l)) return LANG_NAMES[i];
        return l;
    }

    public static void nextLanguage(Context c) {
        String l = language(c);
        int k = 0;
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(l)) k = i;
        sp(c).edit().putString("lang2", LANGS[(k + 1) % LANGS.length]).apply();
    }

    public static void setLanguage(Context c, String l) { sp(c).edit().putString("lang2", l).apply(); }

    public static boolean offline(Context c) { return sp(c).getBoolean("offline", false); }

    public static void setOffline(Context c, boolean v) { sp(c).edit().putBoolean("offline", v).apply(); }
}
