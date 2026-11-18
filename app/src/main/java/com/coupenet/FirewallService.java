package com.coupenet;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.coupenet.adb.AdbManager;
import com.coupenet.adb.AdbMdns;
import com.coupenet.adb.PairingClient;

/**
 * Service de premier plan pour les tâches longues du mode pare-feu système :
 *  - l'appairage avec le Débogage sans fil (code saisi depuis la notification,
 *    sans quitter l'écran des Paramètres) ;
 *  - la remise en place des blocages après un redémarrage.
 */
public class FirewallService extends Service {

    public static final String ACTION_PAIR_START = "com.coupenet.PAIR_START";
    public static final String ACTION_PAIR_CODE = "com.coupenet.PAIR_CODE";
    public static final String ACTION_PAIR_CANCEL = "com.coupenet.PAIR_CANCEL";
    public static final String ACTION_RESTORE = "com.coupenet.RESTORE";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_PORT = "port";

    private static final String CHANNEL = "pairing";
    private static final int NOTIF_ID = 3;
    private static final long PAIR_SEARCH_MS = 10 * 60_000L;

    /** Écouteur facultatif (l'écran principal) pour suivre l'appairage. */
    public interface Listener { void onPairingState(String message, boolean finished, boolean success); }
    private static volatile Listener listener;
    public static void setListener(Listener l) { listener = l; }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int pairingPort = -1;
    private volatile boolean searching = false;
    private volatile boolean busy = false;
    private Thread searchThread;
    private int tasks = 0;

    public static void startPairing(Context ctx) {
        start(ctx, new Intent(ctx, FirewallService.class).setAction(ACTION_PAIR_START));
    }

    public static void submitCode(Context ctx, int port, String code) {
        start(ctx, new Intent(ctx, FirewallService.class).setAction(ACTION_PAIR_CODE)
                .putExtra(EXTRA_PORT, port).putExtra(EXTRA_CODE, code));
    }

    public static void restore(Context ctx) {
        start(ctx, new Intent(ctx, FirewallService.class).setAction(ACTION_RESTORE));
    }

    private static void start(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        startForegroundCompat(baseNotification(getString(R.string.notif_working)).build());
        if (ACTION_PAIR_START.equals(action)) {
            beginSearch();
        } else if (ACTION_PAIR_CODE.equals(action)) {
            String code = intent.getStringExtra(EXTRA_CODE);
            Bundle ri = RemoteInput.getResultsFromIntent(intent);
            if (ri != null && ri.getCharSequence(EXTRA_CODE) != null) {
                code = ri.getCharSequence(EXTRA_CODE).toString();
            }
            int port = intent.getIntExtra(EXTRA_PORT, -1);
            if (port > 0) pairingPort = port;
            doPair(code);
        } else if (ACTION_PAIR_CANCEL.equals(action)) {
            searching = false;
            if (searchThread != null) searchThread.interrupt();
            finish();
        } else if (ACTION_RESTORE.equals(action)) {
            doRestore();
        } else {
            finish();
        }
        return START_NOT_STICKY;
    }

    // ---------- Appairage ----------

    private void beginSearch() {
        if (searching) { showWaitingForService(); return; }
        searching = true;
        tasks++;
        pairingPort = -1;
        showWaitingForService();
        report(getString(R.string.pair_searching), false, false);
        searchThread = new Thread(() -> {
            long end = System.currentTimeMillis() + PAIR_SEARCH_MS;
            while (searching && System.currentTimeMillis() < end) {
                int p = AdbMdns.findPort(this, AdbMdns.TYPE_PAIRING, 15_000);
                if (p > 0 && p != pairingPort) {
                    pairingPort = p;
                    ui.post(this::showCodeInput);
                    report(getString(R.string.pair_found), false, false);
                }
            }
            if (searching) {
                searching = false;
                ui.post(this::taskDone);
            }
        }, "pair-search");
        searchThread.start();
    }

    private void doPair(String code) {
        if (busy) return;
        if (code == null || code.trim().isEmpty()) {
            showCodeInput();
            return;
        }
        if (pairingPort <= 0) {
            notifyPairing(getString(R.string.pair_no_port), true);
            report(getString(R.string.pair_no_port), false, false);
            if (!searching) beginSearch();
            return;
        }
        busy = true;
        tasks++;
        final int port = pairingPort;
        final String c = code.replaceAll("\\s", "");
        notifyPairing(getString(R.string.pair_in_progress), false);
        report(getString(R.string.pair_in_progress), false, false);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                AdbManager.pairAndSetup(this, port, c);
                ok = true;
                msg = getString(R.string.pair_success);
                searching = false;
                if (searchThread != null) searchThread.interrupt();
                Firewall.applyAll(this);
            } catch (PairingClient.WrongCodeException e) {
                msg = getString(R.string.pair_wrong_code);
            } catch (Exception e) {
                msg = getString(R.string.pair_failed, e.getMessage() != null ? e.getMessage() : e.toString());
            }
            final String m = msg;
            final boolean success = ok;
            ui.post(() -> {
                busy = false;
                report(m, success, success);
                if (success) {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    nm.notify(NOTIF_ID + 1, baseNotification(m).setAutoCancel(true).build());
                    searching = false;
                    tasks = 0;
                    finish();
                } else {
                    notifyPairing(m, true);
                    taskDone();
                }
            });
        }, "pair").start();
    }

    private void showWaitingForService() {
        Notification.Builder b = baseNotification(getString(R.string.notif_pair_waiting))
                .setContentTitle(getString(R.string.notif_pair_title))
                .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.notif_pair_waiting)))
                .addAction(cancelAction());
        startForegroundCompat(b.build());
    }

    private void showCodeInput() {
        notifyPairing(getString(R.string.notif_pair_found), true);
    }

    private void notifyPairing(String text, boolean withInput) {
        Notification.Builder b = baseNotification(text)
                .setContentTitle(getString(R.string.notif_pair_title))
                .setStyle(new Notification.BigTextStyle().bigText(text));
        if (withInput) {
            RemoteInput ri = new RemoteInput.Builder(EXTRA_CODE)
                    .setLabel(getString(R.string.notif_pair_code_label)).build();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent i = new Intent(this, FirewallService.class).setAction(ACTION_PAIR_CODE);
            PendingIntent pi = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, 1, i, flags)
                    : PendingIntent.getService(this, 1, i, flags);
            Notification.Action a = new Notification.Action.Builder(null,
                    getString(R.string.notif_pair_enter_code), pi).addRemoteInput(ri).build();
            b.addAction(a);
        }
        b.addAction(cancelAction());
        startForegroundCompat(b.build());
    }

    private Notification.Action cancelAction() {
        Intent i = new Intent(this, FirewallService.class).setAction(ACTION_PAIR_CANCEL);
        PendingIntent pi = PendingIntent.getService(this, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, getString(android.R.string.cancel), pi).build();
    }

    private void report(String msg, boolean finished, boolean success) {
        Listener l = listener;
        if (l != null) ui.post(() -> l.onPairingState(msg, finished, success));
    }

    // ---------- Remise en place après redémarrage ----------

    private void doRestore() {
        tasks++;
        startForegroundCompat(baseNotification(getString(R.string.notif_restoring)).build());
        new Thread(() -> {
            String reason = "";
            boolean ok = false;
            // Le Wi-Fi peut mettre un moment à se connecter après le démarrage.
            for (int attempt = 0; attempt < 6 && !ok; attempt++) {
                if (Firewall.backend(this) == Firewall.Backend.NONE) {
                    reason = getString(R.string.err_no_backend);
                    break;
                }
                Firewall.Result r = Firewall.applyAll(this);
                ok = r.ok;
                reason = r.output;
                if (!ok) {
                    try { Thread.sleep(20_000); } catch (InterruptedException e) { break; }
                }
            }
            final boolean success = ok;
            final String why = reason;
            ui.post(() -> {
                if (success) {
                    BlockVpnService.stop(this);
                } else {
                    boolean vpn = Store.vpnFallback(this) && BlockVpnService.start(this);
                    Notifications.firewallNotRestored(this, vpn
                            ? getString(R.string.notif_fallback_on) : why);
                }
                taskDone();
            });
        }, "restore").start();
    }

    // ---------- Outils ----------

    private void taskDone() {
        tasks = Math.max(0, tasks - 1);
        if (tasks == 0 && !searching && !busy) finish();
    }

    private void finish() {
        stopForeground(true);
        stopSelf();
    }

    private Notification.Builder baseNotification(String text) {
        return Notifications.builder(this, CHANNEL, R.string.channel_pairing, NotificationManager.IMPORTANCE_HIGH)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
