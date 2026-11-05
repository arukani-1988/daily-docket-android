package com.dailydocket;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** Saves the details of any crash so the app can show them next time it opens. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final Context c = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            save(c, e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static File file(Context c) { return new File(c.getFilesDir(), "last-error.txt"); }

    public static void save(Context c, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String info = "Daily Docket " + versionName(c) + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                    + Build.MANUFACTURER + " " + Build.MODEL + " · " + LocalDateTime.now() + "\n\n" + sw;
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(info.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }

    public static String read(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    public static void clear(Context c) { file(c).delete(); }

    static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
}
