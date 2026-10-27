package com.apramed.smartchartrc;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;

/** Procura TVs Android na rede Wi‑Fi (o mesmo anúncio que o app Google TV usa). */
@SuppressWarnings("deprecation")
public class TvFinder {

    public interface Listener {
        void onTvFound(String name, String host);
    }

    private static final String TYPE = "_androidtvremote2._tcp.";

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;

    public TvFinder(Context c, Listener l) {
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        listener = l;
    }

    public void start() {
        stop();
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                main.post(() -> { toResolve.add(s); resolveNext(); });
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }
    }

    public void stop() {
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
            discovery = null;
        }
        toResolve.clear();
    }

    private void resolveNext() {
        if (resolving || toResolve.isEmpty()) return;
        resolving = true;
        NsdServiceInfo s = toResolve.poll();
        nsd.resolveService(s, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                main.post(() -> { resolving = false; resolveNext(); });
            }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                String host = pickHost(i);
                main.post(() -> {
                    resolving = false;
                    if (host != null) listener.onTvFound(i.getServiceName(), host);
                    resolveNext();
                });
            }
        });
    }

    private static String pickHost(NsdServiceInfo i) {
        if (Build.VERSION.SDK_INT >= 34) {
            List<InetAddress> all = i.getHostAddresses();
            for (InetAddress a : all) if (a instanceof Inet4Address) return a.getHostAddress();
            if (!all.isEmpty()) return all.get(0).getHostAddress();
            return null;
        }
        InetAddress a = i.getHost();
        return a != null ? a.getHostAddress() : null;
    }
}
