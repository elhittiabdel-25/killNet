package com.coupenet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Réglages et liste des applications bloquées, enregistrés sur le téléphone. */
public final class Store {
    private static final String PREFS = "coupenet";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_SHOW_SYSTEM = "show_system";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ADB_PAIRED = "adb_paired";
    private static final String KEY_ADB_PORT = "adb_port";
    private static final String KEY_AUTO_DISABLE = "auto_disable_wifi_debug";
    private static final String KEY_VPN_FALLBACK = "vpn_fallback";

    public static final String MODE_VPN = "vpn";
    public static final String MODE_SYSTEM = "system";

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
        prefs(c).edit().putStringSet(KEY_BLOCKED, s).commit();
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().putStringSet(KEY_BLOCKED, new HashSet<>()).commit();
    }

    public static boolean showSystem(Context c) { return prefs(c).getBoolean(KEY_SHOW_SYSTEM, false); }
    public static void setShowSystem(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_SHOW_SYSTEM, v).apply(); }

    public static String mode(Context c) {
        // Par défaut : pare-feu système (compatible avec un VPN comme Proton) quand Android le permet.
        return prefs(c).getString(KEY_MODE, android.os.Build.VERSION.SDK_INT >= 30 ? MODE_SYSTEM : MODE_VPN);
    }
    public static void setMode(Context c, String m) { prefs(c).edit().putString(KEY_MODE, m).commit(); }
    public static boolean systemMode(Context c) { return MODE_SYSTEM.equals(mode(c)); }

    public static boolean adbPaired(Context c) { return prefs(c).getBoolean(KEY_ADB_PAIRED, false); }
    public static void setAdbPaired(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_ADB_PAIRED, v).commit(); }

    public static int adbPort(Context c) { return prefs(c).getInt(KEY_ADB_PORT, -1); }
    public static void setAdbPort(Context c, int p) { prefs(c).edit().putInt(KEY_ADB_PORT, p).apply(); }

    public static boolean autoDisableWifiDebug(Context c) { return prefs(c).getBoolean(KEY_AUTO_DISABLE, true); }
    public static void setAutoDisableWifiDebug(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_AUTO_DISABLE, v).apply(); }

    public static boolean vpnFallback(Context c) { return prefs(c).getBoolean(KEY_VPN_FALLBACK, true); }
    public static void setVpnFallback(Context c, boolean v) { prefs(c).edit().putBoolean(KEY_VPN_FALLBACK, v).apply(); }
}
