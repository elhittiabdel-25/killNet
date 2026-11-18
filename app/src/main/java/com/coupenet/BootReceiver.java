package com.coupenet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Au démarrage du téléphone (et après une mise à jour de Coupe-Net) :
 *  - mode VPN : relance le VPN de blocage ;
 *  - mode pare-feu système : Android a effacé les règles, on les remet
 *    (root, ou ADB intégré qui rallume seul le Débogage sans fil en Wi-Fi).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        Log.init(app);
        Log.d("démarrage du téléphone / mise à jour");
        if (Store.blocked(app).isEmpty()) return;
        if (Store.systemMode(app)) {
            FirewallService.restore(app);
        } else {
            BlockVpnService.start(app);
        }
    }
}
