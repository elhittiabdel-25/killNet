package com.coupenet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Liste des applications bloquées, enregistrée sur le téléphone. */
public final class Store {
    private static final String PREFS = "coupenet";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_SHOW_SYSTEM = "show_system";

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized Set<String> blocked(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_BLOCKED, new HashSet<>()));
    }

    public static synchronized void setBlocked(Context c, String pkg, boolean blocked) {
        Set<String> s = blocked(c);
        if (blocked) s.add(pkg); else s.remove(pkg);
        prefs(c).edit().putStringSet(KEY_BLOCKED, s).apply();
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().putStringSet(KEY_BLOCKED, new HashSet<>()).apply();
    }

    public static boolean showSystem(Context c) {
        return prefs(c).getBoolean(KEY_SHOW_SYSTEM, false);
    }

    public static void setShowSystem(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_SHOW_SYSTEM, v).apply();
    }
}
