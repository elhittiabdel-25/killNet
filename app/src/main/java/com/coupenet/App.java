package com.coupenet;

import android.app.Application;

import rikka.shizuku.Shizuku;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Quand Shizuku démarre (ex. après un redémarrage), on réapplique les blocages.
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                if (Firewall.shizukuReady()) {
                    new Thread(() -> Firewall.applyAll(this)).start();
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
