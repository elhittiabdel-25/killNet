package com.coupenet.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Trouve le port du Débogage sans fil annoncé en mDNS par le téléphone lui-même. */
public final class AdbMdns {

    public static final String TYPE_CONNECT = "_adb-tls-connect._tcp";
    public static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";

    private AdbMdns() {}

    /** Bloquant (ne pas appeler sur le thread principal). Renvoie -1 si rien trouvé. */
    public static int findPort(Context ctx, String type, long timeoutMs) {
        NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;
        final Set<String> local = localAddresses();
        final AtomicInteger port = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);
        final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        final boolean[] resolving = {false};

        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            NsdServiceInfo info;
            synchronized (queue) {
                if (resolving[0] || queue.isEmpty() || done.getCount() == 0) return;
                info = queue.poll();
                resolving[0] = true;
            }
            try {
                //noinspection deprecation
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int err) {
                        synchronized (queue) { resolving[0] = false; }
                        next[0].run();
                    }
                    @Override public void onServiceResolved(NsdServiceInfo s) {
                        //noinspection deprecation
                        InetAddress h = s.getHost();
                        String ha = h == null ? "" : h.getHostAddress();
                        if (ha.indexOf('%') >= 0) ha = ha.substring(0, ha.indexOf('%'));
                        boolean mine = h == null || local.isEmpty() || h.isLoopbackAddress()
                                || local.contains(ha);
                        if (mine && s.getPort() > 0) {
                            port.set(s.getPort());
                            done.countDown();
                        }
                        synchronized (queue) { resolving[0] = false; }
                        next[0].run();
                    }
                });
            } catch (Throwable t) {
                synchronized (queue) { resolving[0] = false; }
            }
        };

        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                synchronized (queue) { queue.add(s); }
                next[0].run();
            }
            @Override public void onServiceLost(NsdServiceInfo s) {}
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Throwable t) {
            return -1;
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            try { nsd.stopServiceDiscovery(dl); } catch (Throwable ignored) {}
        }
        return port.get();
    }

    private static Set<String> localAddresses() {
        Set<String> s = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String h = a.getHostAddress();
                    int pct = h.indexOf('%');
                    s.add(pct >= 0 ? h.substring(0, pct) : h);
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }
}
