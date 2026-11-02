package com.sarab.vm.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Sends one command to QEMU's control socket (QMP), e.g. "system_powerdown"
 * for a clean shutdown or "quit" to stop immediately. Call from a background thread.
 */
public final class QmpClient {
    private QmpClient() { }

    /** @return the raw JSON reply line to the command. */
    public static String execute(String host, int port, String command, int timeoutMs) throws IOException {
        return send(host, port, "{\"execute\":\"" + command + "\"}", timeoutMs);
    }

    /**
     * Runs a monitor (HMP) command such as "savevm sarab" and returns its text output.
     * HMP reports failures as text, so output that starts with "Error" becomes an IOException.
     */
    public static String hmp(String host, int port, String commandLine, int timeoutMs) throws IOException {
        String json = "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\""
                + jsonEscape(commandLine) + "\"}}";
        String reply = send(host, port, json, timeoutMs);
        String text = returnString(reply);
        if (text.trim().startsWith("Error") || text.contains("\nError")) throw new IOException(text.trim());
        return text;
    }

    /** Value of {"return": "…"} (empty for {"return": {}}). */
    static String returnString(String reply) {
        int i = reply.indexOf("\"return\"");
        if (i < 0) return "";
        int colon = reply.indexOf(':', i);
        int q = reply.indexOf('"', colon + 1);
        int brace = reply.indexOf('{', colon + 1);
        if (q < 0 || (brace >= 0 && brace < q)) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = q + 1; k < reply.length(); k++) {
            char ch = reply.charAt(k);
            if (ch == '\\' && k + 1 < reply.length()) {
                char n = reply.charAt(++k);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(n);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String send(String host, int port, String commandJson, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream o = s.getOutputStream();

            String greeting = r.readLine();
            if (greeting == null || !greeting.contains("\"QMP\"")) {
                throw new IOException("Not a QMP server: " + greeting);
            }
            o.write("{\"execute\":\"qmp_capabilities\"}\r\n".getBytes(StandardCharsets.UTF_8));
            o.flush();
            readReply(r);
            o.write((commandJson + "\r\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            return readReply(r);
        }
    }

    /** Skips asynchronous events and returns the first "return"/"error" line. */
    private static String readReply(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            if (line.contains("\"return\"")) return line;
            if (line.contains("\"error\"")) throw new IOException("QMP error: " + line);
        }
        throw new IOException("QMP connection closed");
    }
}
