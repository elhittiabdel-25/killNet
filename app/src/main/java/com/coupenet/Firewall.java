package com.coupenet;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Coupe l'accès réseau d'une application au niveau du noyau, en utilisant la
 * chaîne de pare-feu "chain3" du service de connectivité d'Android.
 * Le blocage s'applique à l'UID de l'application, donc sur TOUS les réseaux :
 * Wi-Fi, 4G/5G, partage de connexion, et même quand un VPN est actif.
 *
 * Les commandes sont exécutées avec les droits "shell" via Shizuku, ou via root.
 */
public final class Firewall {

    public enum Backend { SHIZUKU, ROOT, NONE }

    public static final class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    private static volatile Boolean rootCache = null;

    private Firewall() {}

    // ---------- Détection du moteur disponible ----------

    public static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shizukuReady() {
        try {
            return shizukuRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

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
    public static Backend backend() {
        if (shizukuReady()) return Backend.SHIZUKU;
        if (rootAvailable()) return Backend.ROOT;
        return Backend.NONE;
    }

    // ---------- Actions ----------

    public static Result block(String pkg) {
        if (!PKG.matcher(pkg).matches()) return new Result(false, "Nom de paquet invalide");
        return run("cmd connectivity set-chain3-enabled true && "
                + "cmd connectivity set-package-networking-enabled false " + pkg);
    }

    public static Result unblock(String pkg, boolean lastOne) {
        if (!PKG.matcher(pkg).matches()) return new Result(false, "Nom de paquet invalide");
        String cmd = "cmd connectivity set-package-networking-enabled true " + pkg;
        if (lastOne) cmd += " && cmd connectivity set-chain3-enabled false";
        return run(cmd);
    }

    /** Réapplique toutes les règles enregistrées (elles sont effacées par Android au redémarrage). */
    public static Result applyAll(Context ctx) {
        Set<String> blocked = Store.blocked(ctx);
        if (blocked.isEmpty()) return new Result(true, "");
        StringBuilder sb = new StringBuilder("cmd connectivity set-chain3-enabled true");
        for (String pkg : blocked) {
            if (PKG.matcher(pkg).matches()) {
                sb.append(" && cmd connectivity set-package-networking-enabled false ").append(pkg);
            }
        }
        return run(sb.toString());
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
        return run(sb.toString());
    }

    // ---------- Exécution ----------

    private static Result run(String cmd) {
        Backend b = backend();
        try {
            Process p;
            if (b == Backend.SHIZUKU) {
                p = shizukuProcess(new String[]{"sh", "-c", cmd + " 2>&1"});
            } else if (b == Backend.ROOT) {
                p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd + " 2>&1"});
            } else {
                return new Result(false, "Shizuku n'est pas actif (ni root disponible).");
            }
            String out = readAll(p.getInputStream()).trim();
            int code = p.waitFor();
            boolean ok = code == 0
                    && !out.toLowerCase().contains("unknown command")
                    && !out.toLowerCase().contains("exception")
                    && !out.toLowerCase().contains("error");
            return new Result(ok, out.isEmpty() ? ("code " + code) : out);
        } catch (Throwable t) {
            return new Result(false, t.toString());
        }
    }

    /** Shizuku.newProcess est privé dans l'API 13 : on y accède par réflexion. */
    private static Process shizukuProcess(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
