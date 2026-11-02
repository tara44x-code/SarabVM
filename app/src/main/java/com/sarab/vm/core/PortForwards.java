package com.sarab.vm.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses "8080:80, 2222:22" (phone port : machine port, TCP). Pure Java. */
public final class PortForwards {

    public static final class Rule {
        public final int hostPort;
        public final int guestPort;

        Rule(int hostPort, int guestPort) {
            this.hostPort = hostPort;
            this.guestPort = guestPort;
        }
    }

    public final List<Rule> rules = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    private PortForwards() { }

    public static PortForwards parse(String text) {
        PortForwards out = new PortForwards();
        if (text == null || text.trim().isEmpty()) return out;
        Set<Integer> hosts = new HashSet<>();
        for (String raw : text.split("[,،;\\s]+")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            String[] hg = part.split(":");
            if (hg.length != 2) {
                out.errors.add(Lang.t("صيغة المنفذ غير صحيحة: ") + part + Lang.t(" (مثال: 8080:80)"));
                continue;
            }
            int host, guest;
            try {
                host = Integer.parseInt(hg[0].trim());
                guest = Integer.parseInt(hg[1].trim());
            } catch (NumberFormatException e) {
                out.errors.add(Lang.t("رقم منفذ غير صحيح: ") + part);
                continue;
            }
            if (host < 1024 || host > 65535) {
                out.errors.add(Lang.t("منفذ الهاتف ") + host + Lang.t(" يجب أن يكون بين 1024 و 65535"));
                continue;
            }
            if (guest < 1 || guest > 65535) {
                out.errors.add(Lang.t("منفذ النظام ") + guest + Lang.t(" غير صالح"));
                continue;
            }
            if (!hosts.add(host)) {
                out.errors.add(Lang.t("منفذ الهاتف ") + host + Lang.t(" مكرر"));
                continue;
            }
            out.rules.add(new Rule(host, guest));
        }
        return out;
    }

    /** ",hostfwd=tcp:127.0.0.1:8080-:80,…" appended to QEMU's -netdev user option. */
    public String toQemuSuffix() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) sb.append(",hostfwd=tcp:127.0.0.1:").append(r.hostPort).append("-:").append(r.guestPort);
        return sb.toString();
    }
}
