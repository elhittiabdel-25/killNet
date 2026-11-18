package com.coupenet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android efface les règles au redémarrage. Avec root, on les remet tout de suite.
 * Avec Shizuku, c'est App.java qui les remet dès que Shizuku est relancé.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (Firewall.backend() != Firewall.Backend.NONE) {
                    Firewall.applyAll(app);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
