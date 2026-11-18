package com.coupenet;

import android.content.Context;

import com.coupenet.adb.AdbClient;
import com.coupenet.adb.AdbManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mode « pare-feu système » : coupe l'accès réseau d'une application au niveau du
 * noyau via la chaîne de pare-feu « chain3 » du service de connectivité d'Android.
 * Le blocage vise l'UID de l'application, donc TOUS les réseaux : Wi-Fi, 4G/5G,
 * partage de connexion, et même quand un VPN est actif.
 *
 * Les commandes sont lancées avec les droits « shell » via l'ADB intégré
 * (Débogage sans fil, sans Shizuku), ou via root si le téléphone est rooté.
 */
public final class Firewall {

    public enum Backend { ROOT, ADB, NONE }

    public static final class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    private static volatile Boolean rootCache = null;

    private Firewall() {}

    /** Ne pas appeler sur le thread principal (peut déclencher une demande Magisk). */
    public static boolean rootAvailable() {
        if (rootCache != null) return rootCache;
        boolean ok;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readAll(p.getInputStream());
            ok = p.waitFor() == 0 && out.contains("uid=0");
        } catch (Throwable t) {
            ok = false;
        }
        rootCache = ok;
        return ok;
    }

    /** Ne pas appeler sur le thread principal. */
    public static Backend backend(Context ctx) {
        if (rootAvailable()) return Backend.ROOT;
        if (Store.adbPaired(ctx) && AdbManager.supported()) return Backend.ADB;
        return Backend.NONE;
    }

    // ---------- Actions ----------

    public static Result block(Context ctx, String pkg) {
        if (!PKG.matcher(pkg).matches()) return new Result(false, "Nom de paquet invalide");
        return run(ctx, "cmd connectivity set-chain3-enabled true && "
                + "cmd connectivity set-package-networking-enabled false " + pkg);
    }

    public static Result unblock(Context ctx, String pkg, boolean lastOne) {
        if (!PKG.matcher(pkg).matches()) return new Result(false, "Nom de paquet invalide");
        String cmd = "cmd connectivity set-package-networking-enabled true " + pkg;
        if (lastOne) cmd += " && cmd connectivity set-chain3-enabled false";
        return run(ctx, cmd);
    }

    /** Réapplique toutes les règles enregistrées (Android les efface au redémarrage). */
    public static Result applyAll(Context ctx) {
        Set<String> blocked = Store.blocked(ctx);
        if (blocked.isEmpty()) return new Result(true, "");
        StringBuilder sb = new StringBuilder("cmd connectivity set-chain3-enabled true");
        for (String pkg : blocked) {
            if (PKG.matcher(pkg).matches()) {
                sb.append(" && cmd connectivity set-package-networking-enabled false ").append(pkg);
            }
        }
        return run(ctx, sb.toString());
    }

    public static Result unblockAll(Context ctx) {
        Set<String> blocked = Store.blocked(ctx);
        StringBuilder sb = new StringBuilder();
        for (String pkg : blocked) {
            if (PKG.matcher(pkg).matches()) {
                sb.append("cmd connectivity set-package-networking-enabled true ").append(pkg).append(" ; ");
            }
        }
        sb.append("cmd connectivity set-chain3-enabled false");
        return run(ctx, sb.toString());
    }

    // ---------- Exécution ----------

    private static Result run(Context ctx, String cmd) {
        Backend b = backend(ctx);
        try {
            String out;
            int code;
            if (b == Backend.ROOT) {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd + " 2>&1"});
                out = readAll(p.getInputStream()).trim();
                code = p.waitFor();
            } else if (b == Backend.ADB) {
                AdbClient.Result r = AdbManager.exec(ctx, cmd);
                out = r.output;
                code = r.code;
            } else {
                return new Result(false, ctx.getString(R.string.err_no_backend));
            }
            String low = out.toLowerCase();
            boolean ok = code == 0
                    && !low.contains("unknown command")
                    && !low.contains("exception")
                    && !low.contains("error");
            if (!ok && (low.contains("unknown command") || low.contains("unknown option"))) {
                out = ctx.getString(R.string.err_unsupported) + "\n\n" + out;
            }
            return new Result(ok, out.isEmpty() ? ("code " + code) : out);
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(false, m != null ? m : t.toString());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
