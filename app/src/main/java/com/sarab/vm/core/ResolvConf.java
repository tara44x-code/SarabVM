package com.sarab.vm.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The DNS server list QEMU's virtual network forwards name lookups to (resolv.conf format).
 * The phone's own DNS servers come first, then public fallbacks. Pure Java.
 */
public final class ResolvConf {
    private ResolvConf() { }

    static final String[] FALLBACK = {"8.8.8.8", "1.1.1.1"};

    /** @param phoneDns addresses as text ("192.168.1.1", "2001:4860::8888"…); may be empty */
    public static String render(List<String> phoneDns) {
        Set<String> v4 = new LinkedHashSet<>();
        Set<String> v6 = new LinkedHashSet<>();
        for (String raw : phoneDns) {
            if (raw == null) continue;
            String a = raw.trim();
            int pct = a.indexOf('%'); // drop IPv6 scope ("fe80::1%wlan0")
            if (pct >= 0) a = a.substring(0, pct);
            if (a.startsWith("/")) a = a.substring(1);
            if (a.isEmpty() || a.startsWith("127.") || a.equals("::1")) continue;
            if (a.matches("\\d{1,3}(\\.\\d{1,3}){3}")) v4.add(a);
            else if (a.contains(":") && !a.toLowerCase(java.util.Locale.ROOT).startsWith("fe80")) v6.add(a);
        }
        // QEMU uses the first IPv4 server for IPv4 guests, so put the phone's own first
        for (String f : FALLBACK) v4.add(f);
        List<String> all = new ArrayList<>(v4);
        all.addAll(v6);
        StringBuilder sb = new StringBuilder("# written by Sarab before each start\n");
        for (String a : all) sb.append("nameserver ").append(a).append('\n');
        return sb.toString();
    }
}
