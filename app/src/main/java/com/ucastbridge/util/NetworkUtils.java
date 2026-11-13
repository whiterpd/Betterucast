package com.ucastbridge.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

public final class NetworkUtils {
    private NetworkUtils() {}

    public static Inet4Address getWifiIpv4(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    LinkProperties props = cm.getLinkProperties(active);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && props != null) {
                        for (LinkAddress link : props.getLinkAddresses()) {
                            InetAddress address = link.getAddress();
                            if (isUsableIpv4(address)) return (Inet4Address) address;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            Inet4Address fallback = null;
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                    String name = networkInterface.getName().toLowerCase();
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        if (!isUsableIpv4(address)) continue;
                        if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap")) return (Inet4Address) address;
                        if (fallback == null) fallback = (Inet4Address) address;
                    }
                } catch (Exception ignored) { }
            }
            return fallback;
        } catch (Exception ignored) { return null; }
    }

    private static boolean isUsableIpv4(InetAddress address) {
        return address instanceof Inet4Address && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress() && !address.isLinkLocalAddress();
    }
}
