package com.coupenet.adb;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import com.coupenet.Log;
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
        return exec(ctx, command, true);
    }

    private static AdbClient.Result exec(Context ctx, String command, boolean allowAutoDisable) throws Exception {
        Log.d("exec: " + (command.length() > 90 ? command.substring(0, 90) + "…" : command));
        if (!supported()) throw new Exception("Le Débogage sans fil demande Android 11 ou plus récent.");
        if (!Store.adbPaired(ctx)) throw new AdbClient.NotPairedException("Coupe-Net n'est pas encore appairé.");

        // 1. Port TCP local 5555 (« mode 4G ») : marche sans Wi-Fi, même avec un VPN.
        if (tcpPortOpen()) {
            try (AdbClient c = AdbClient.connect(ctx, HOST, TCP_PORT)) {
                AdbClient.Result r = c.shell(command);
                Log.d("port 5555 OK, code " + r.code + (r.output.isEmpty() ? "" : " : " + r.output));
                return r;
            } catch (Exception e) {
                Log.e("port 5555 échec", e);
            }
        } else {
            Log.d("port 5555 fermé");
        }

        // 2. Débogage sans fil (Wi-Fi).
        ContentResolver cr = ctx.getContentResolver();
        boolean weEnabled = false;
        boolean canToggle = canToggleWirelessDebugging(ctx);
        try {
            if (!wirelessDebuggingOn(ctx) && canToggle) {
                try {
                    Settings.Global.putInt(cr, "development_settings_enabled", 1);
                    Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1);
                    weEnabled = true;
                    Log.d("Débogage sans fil allumé par Coupe-Net");
                } catch (Throwable t) {
                    Log.e("allumage Débogage sans fil", t);
                }
            }
            long deadline = System.currentTimeMillis() + (weEnabled ? 20_000 : 8_000);
            Exception last = null;
            while (System.currentTimeMillis() < deadline) {
                for (int port : candidatePorts(ctx, weEnabled)) {
                    if (port <= 0) continue;
                    try (AdbClient c = AdbClient.connect(ctx, HOST, port)) {
                        Store.setAdbPort(ctx, port);
                        AdbClient.Result r = c.shell(command);
                        Log.d("Débogage sans fil port " + port + " OK, code " + r.code
                                + (r.output.isEmpty() ? "" : " : " + r.output));
                        if (Store.tcpMode(ctx) && !tcpPortOpen()) {
                            // Le port 5555 a disparu (redémarrage) : on le rouvre.
                            try {
                                Log.d("tcpip:" + TCP_PORT + " → « " + c.service("tcpip:" + TCP_PORT) + " »");
                            } catch (Exception e) {
                                Log.e("tcpip", e);
                            }
                        }
                        return r;
                    } catch (AdbClient.NotPairedException e) {
                        Log.e("port " + port + " refusé", e);
                        throw e;
                    } catch (Exception e) {
                        Log.e("port " + port, e);
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
            if (weEnabled && allowAutoDisable && Store.autoDisableWifiDebug(ctx)) {
                try { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0); } catch (Throwable ignored) {}
            }
        }
    }

    public static final int TCP_PORT = 5555;

    /** Le port adb TCP local (mode 4G) est-il ouvert en ce moment ? */
    public static boolean tcpPortOpen() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(HOST, TCP_PORT), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Active le « mode 4G » : demande à adbd d'écouter aussi sur le port TCP 5555
     * (comme « adb tcpip 5555 »). Ce port reste ouvert sans Wi-Fi jusqu'au prochain
     * redémarrage. À faire une fois en Wi-Fi ; la première fois, Android demande
     * « Autoriser le débogage ? » : cocher « Toujours autoriser ».
     */
    public static String enableTcpMode(Context ctx) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        if (canToggleWirelessDebugging(ctx)) {
            // Le port TCP n'existe que si le « Débogage USB » est activé.
            try { Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1); } catch (Throwable ignored) {}
        }
        Store.setTcpMode(ctx, true);
        if (!tcpPortOpen()) {
            // exec() passe par le Débogage sans fil puis ouvre tcpip:5555 (mode demandé).
            // persist.adb.tcp.port garderait le port après redémarrage : refusé sur la
            // plupart des téléphones, on essaie quand même.
            exec(ctx, "setprop persist.adb.tcp.port " + TCP_PORT + " 2>/dev/null; true", false);
        }
        long end = System.currentTimeMillis() + 20_000;
        while (!tcpPortOpen() && System.currentTimeMillis() < end) Thread.sleep(500);
        Log.d("port 5555 ouvert : " + tcpPortOpen() + " ; Débogage USB : "
                + Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1));
        if (!tcpPortOpen()) {
            throw new Exception("adbd n'a pas ouvert le port " + TCP_PORT
                    + ". Vérifiez que « Débogage USB » est activé dans les Options pour les développeurs.");
        }
        // Premier contact : déclenche si besoin « Autoriser le débogage ? ».
        try (AdbClient c = AdbClient.connect(ctx, HOST, TCP_PORT)) {
            String out = c.shell("echo ok").output;
            Log.d("mode 4G vérifié : " + out);
            return out;
        } catch (Exception e) {
            Log.e("connexion port 5555", e);
            throw e;
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
        Log.d("appairage sur le port " + pairingPort);
        PairingClient.pair(ctx, HOST, pairingPort, code);
        Log.d("appairage réussi");
        Store.setAdbPaired(ctx, true);
        // Juste après l'appairage, le Débogage sans fil est forcément allumé.
        AdbClient.Result r = exec(ctx, "pm grant " + ctx.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS");
        // Mode 4G dans la foulée (meilleur effort ; bouton disponible sinon).
        try { enableTcpMode(ctx); } catch (Exception e) { Log.e("mode 4G après appairage", e); }
        return r.output;
    }
}
