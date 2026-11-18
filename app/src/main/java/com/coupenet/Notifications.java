package com.coupenet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Notifications ponctuelles (canal « alertes »). */
public final class Notifications {

    public static final String CHANNEL_ALERTS = "alerts";
    private static final int ID_ALERT = 10;

    private Notifications() {}

    static Notification.Builder builder(Context ctx, String channel, int nameRes, int importance) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(channel, ctx.getString(nameRes), importance));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, channel) : new Notification.Builder(ctx);
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_stat_block).setContentIntent(open);
    }

    private static void alert(Context ctx, int title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = builder(ctx, CHANNEL_ALERTS, R.string.channel_alerts, NotificationManager.IMPORTANCE_DEFAULT)
                .setContentTitle(ctx.getString(title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        try { nm.notify(ID_ALERT, n); } catch (Throwable ignored) {}
    }

    public static void firewallRestored(Context ctx) {
        alert(ctx, R.string.notif_restored_title, ctx.getString(R.string.notif_restored_text));
    }

    public static void firewallNotRestored(Context ctx, String reason) {
        alert(ctx, R.string.notif_not_restored_title, ctx.getString(R.string.notif_not_restored_text, reason));
    }

    public static void vpnRevoked(Context ctx) {
        alert(ctx, R.string.notif_revoked_title, ctx.getString(R.string.notif_revoked_text));
    }
}
