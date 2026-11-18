package com.coupenet.adb;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import com.coupenet.Store;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Point d'entrée « ADB intégré » : retrouve le Débogage sans fil du téléphone,
 * s'y connecte avec la clé appairée et exécute une commande avec les droits shell.
 *
 * Si Coupe-Net a reçu WRITE_SECURE_SETTINGS (accordé automatiquement lors du
 * premier appairage), il peut allumer lui-même le Débogage sans fil quand il en
 * a besoin, puis le rééteindre : c'est ce qui permet de tout remettre seul après
 * un redémarrage, tant que le téléphone est en Wi-Fi.
 */
public final class AdbManager {

    private static final String HOST = "127.0.0.1";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    private AdbManager() {}

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    public static boolean canToggleWirelessDebugging(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean wirelessDebuggingOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), ADB_WIFI_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Exécute une commande. Lève une exception explicite en cas d'échec. */
    public static AdbClient.Result exec(Context ctx, String command) throws Exception {
        if (!supported()) throw new Exception("Le Débogage sans fil demande Android 11 ou plus récent.");
        if (!Store.adbPaired(ctx)) throw new AdbClient.NotPairedException("Coupe-Net n'est pas encore appairé.");

        ContentResolver cr = ctx.getContentResolver();
        boolean weEnabled = false;
        boolean canToggle = canToggleWirelessDebugging(ctx);
        try {
            if (!wirelessDebuggingOn(ctx) && canToggle) {
                try {
                    Settings.Global.putInt(cr, "development_settings_enabled", 1);
                    Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1);
                    weEnabled = true;
                } catch (Throwable ignored) {
                }
            }
            long deadline = System.currentTimeMillis() + (weEnabled ? 20_000 : 8_000);
            Exception last = null;
            while (System.currentTimeMillis() < deadline) {
                for (int port : candidatePorts(ctx, weEnabled)) {
                    if (port <= 0) continue;
                    try (AdbClient c = AdbClient.connect(ctx, HOST, port)) {
                        Store.setAdbPort(ctx, port);
                        return c.shell(command);
                    } catch (AdbClient.NotPairedException e) {
                        throw e;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                Thread.sleep(1_000);
            }
            if (!wirelessDebuggingOn(ctx)) {
                throw new Exception(canToggle
                        ? "Impossible d'allumer le Débogage sans fil. Le téléphone est-il connecté à un Wi-Fi ?"
                        : "Le Débogage sans fil est éteint. Allumez-le (Options pour les développeurs) puis réessayez.");
            }
            throw new Exception("Débogage sans fil introuvable"
                    + (last != null ? " (" + last.getMessage() + ")" : "")
                    + ". Vérifiez que le Wi-Fi est connecté.");
        } finally {
            if (weEnabled && Store.autoDisableWifiDebug(ctx)) {
                try { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0); } catch (Throwable ignored) {}
            }
        }
    }

    private static int[] candidatePorts(Context ctx, boolean fresh) {
        int prop = propPort();
        int saved = fresh ? -1 : Store.adbPort(ctx);
        if (prop > 0) return new int[]{prop};
        if (saved > 0) {
            // On tente l'ancien port, puis on cherche en mDNS.
            return new int[]{saved, AdbMdns.findPort(ctx, AdbMdns.TYPE_CONNECT, 4_000)};
        }
        return new int[]{AdbMdns.findPort(ctx, AdbMdns.TYPE_CONNECT, 5_000)};
    }

    /** Le port est parfois lisible directement dans les propriétés système. */
    private static int propPort() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", "service.adb.tls.port"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l = r.readLine();
                p.waitFor();
                if (l != null && !l.trim().isEmpty()) return Integer.parseInt(l.trim());
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** Appaire puis vérifie la connexion et s'accorde WRITE_SECURE_SETTINGS. */
    public static String pairAndSetup(Context ctx, int pairingPort, String code) throws Exception {
        PairingClient.pair(ctx, HOST, pairingPort, code);
        Store.setAdbPaired(ctx, true);
        // Juste après l'appairage, le Débogage sans fil est forcément allumé.
        AdbClient.Result r = exec(ctx, "pm grant " + ctx.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS");
        return r.output;
    }
}
