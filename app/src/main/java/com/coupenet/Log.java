package com.coupenet;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Petit journal de diagnostic (Menu › Journal), pour comprendre ce qui bloque. */
public final class Log {
    private static Context app;
    private static final int MAX = 60_000;

    private Log() {}

    public static void init(Context ctx) { app = ctx.getApplicationContext(); }

    private static File file() { return app == null ? null : new File(app.getFilesDir(), "journal.txt"); }

    public static synchronized void d(String msg) {
        File f = file();
        if (f == null) return;
        try {
            if (f.length() > MAX) {
                String old = read();
                try (FileWriter w = new FileWriter(f, false)) { w.write(old.substring(old.length() / 2)); }
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    public static void e(String what, Throwable t) {
        d(what + " : " + t.getClass().getSimpleName() + " : " + t.getMessage());
    }

    public static synchronized String read() {
        File f = file();
        try { return f != null && f.exists() ? new String(Files.readAllBytes(f.toPath()), "UTF-8") : ""; }
        catch (Exception e) { return ""; }
    }

    public static synchronized void clear() {
        File f = file();
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
