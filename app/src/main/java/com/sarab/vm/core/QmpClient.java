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
            o.write(("{\"execute\":\"" + command + "\"}\r\n").getBytes(StandardCharsets.UTF_8));
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
