package com.coupenet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Mode « VPN » : un VPN local qui ne laisse entrer QUE les applications bloquées,
 * et jette tout ce qu'elles envoient. Les autres applications ne passent pas par
 * lui et gardent internet normalement. Marche en Wi-Fi, 4G/5G, etc.
 * Limite d'Android : un seul VPN à la fois.
 *
 * Sert aussi de « secours » au mode pare-feu système : si le pare-feu n'a pas pu
 * être remis après un redémarrage (pas de Wi-Fi), ce VPN garde les apps bloquées
 * en attendant, puis s'arrête dès que le pare-feu est rétabli.
 */
public class BlockVpnService extends VpnService {

    public static final String ACTION_START = "com.coupenet.vpn.START";
    public static final String ACTION_STOP = "com.coupenet.vpn.STOP";
    private static final String CHANNEL = "vpn";
    private static final int NOTIF_ID = 2;

    private static volatile boolean running = false;

    private ParcelFileDescriptor tun;
    private Thread drain;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private volatile boolean restoring = false;

    public static boolean isRunning() { return running; }

    /** Démarre (ou met à jour) le VPN. Renvoie false si l'autorisation VPN manque. */
    public static boolean start(Context ctx) {
        if (VpnService.prepare(ctx) != null) return false;
        Intent i = new Intent(ctx, BlockVpnService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void stop(Context ctx) {
        if (!running) return;
        try {
            ctx.startService(new Intent(ctx, BlockVpnService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) {
        }
    }

    /** À appeler quand la liste change : relance le VPN avec la nouvelle liste. */
    public static void refresh(Context ctx) {
        boolean wanted = !Store.blocked(ctx).isEmpty()
                && (!Store.systemMode(ctx) || running);
        if (wanted) start(ctx); else stop(ctx);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        startForegroundCompat();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        // Démarrage par le système (VPN permanent) : intent nul ou SERVICE_INTERFACE.
        if (!establish()) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (Store.systemMode(this)) watchWifiForRestore();
        return START_STICKY;
    }

    private boolean establish() {
        Set<String> blocked = Store.blocked(this);
        if (blocked.isEmpty()) return false;

        Builder b = new Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.111.222.2")
                .setMtu(1500)
                .setBlocking(true);
        try {
            b.addAddress("fd00:c0:fe::1", 128).addRoute("::", 0);
        } catch (Throwable ignored) {
        }
        int added = 0;
        PackageManager pm = getPackageManager();
        for (String pkg : blocked) {
            if (pkg.equals(getPackageName())) continue;
            try {
                pm.getApplicationInfo(pkg, 0);
                b.addAllowedApplication(pkg);
                added++;
            } catch (Exception ignored) {
                // application désinstallée
            }
        }
        // Sans application autorisée, le VPN capterait TOUT le téléphone : on refuse.
        if (added == 0) return false;
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

        ParcelFileDescriptor old = tun;
        try {
            tun = b.establish();
        } catch (Exception e) {
            tun = null;
        }
        closeQuietly(old);
        if (tun == null) return false;
        running = true;
        startDrain(tun);
        updateNotification();
        return true;
    }

    /** Lit et jette les paquets des applications bloquées. */
    private void startDrain(ParcelFileDescriptor fd) {
        if (drain != null) drain.interrupt();
        drain = new Thread(() -> {
            byte[] buf = new byte[32767];
            try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                //noinspection InfiniteLoopStatement
                while (true) {
                    if (in.read(buf) < 0) break;
                }
            } catch (IOException ignored) {
            }
        }, "vpn-drain");
        drain.start();
    }

    // ---------- Secours du mode pare-feu système ----------

    private void watchWifiForRestore() {
        if (wifiCallback != null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { tryRestoreFirewall(); }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), wifiCallback);
        } catch (Throwable ignored) {
            wifiCallback = null;
        }
    }

    private void tryRestoreFirewall() {
        if (restoring || !Store.systemMode(this)) return;
        restoring = true;
        new Thread(() -> {
            try {
                Thread.sleep(4_000); // laisser le Wi-Fi se stabiliser
                if (Firewall.backend(this) != Firewall.Backend.NONE && Firewall.applyAll(this).ok) {
                    Notifications.firewallRestored(this);
                    stop(this);
                }
            } catch (InterruptedException ignored) {
            } finally {
                restoring = false;
            }
        }).start();
    }

    // ---------- Notification / arrêt ----------

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW));
        }
        int count = Store.blocked(this).size();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return nb.setSmallIcon(R.drawable.ic_stat_block)
                .setContentTitle(getString(Store.systemMode(this)
                        ? R.string.notif_vpn_fallback_title : R.string.notif_vpn_title))
                .setContentText(getString(R.string.notif_vpn_text, count))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void shutdown() {
        running = false;
        if (wifiCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiCallback); }
            catch (Throwable ignored) {}
            wifiCallback = null;
        }
        if (drain != null) drain.interrupt();
        closeQuietly(tun);
        tun = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        // Un autre VPN a pris la place (Android n'en autorise qu'un).
        shutdown();
        Notifications.vpnRevoked(this);
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly(tun);
        super.onDestroy();
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try { fd.close(); } catch (IOException ignored) {}
    }
}
