package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

public class QmpAndKeysymsTest {

    @Test
    public void qmpSendsCapabilitiesThenCommand() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream o = s.getOutputStream();
                    o.write("{\"QMP\": {\"version\": {}, \"capabilities\": []}}\r\n".getBytes(StandardCharsets.UTF_8));
                    received.add(r.readLine());
                    o.write("{\"return\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                    received.add(r.readLine());
                    o.write("{\"event\": \"POWERDOWN\", \"timestamp\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                    o.write("{\"return\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (IOException ignored) {
                }
            });
            server.setDaemon(true);
            server.start();

            String reply = QmpClient.execute("127.0.0.1", ss.getLocalPort(), "system_powerdown", 3000);
            assertTrue(reply.contains("return"));
            server.join(2000);
            assertEquals("{\"execute\":\"qmp_capabilities\"}", received.get(0));
            assertEquals("{\"execute\":\"system_powerdown\"}", received.get(1));
        }
    }

    @Test(expected = IOException.class)
    public void qmpErrorIsReported() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream o = s.getOutputStream();
                    o.write("{\"QMP\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                    r.readLine();
                    o.write("{\"return\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                    r.readLine();
                    o.write("{\"error\": {\"class\": \"CommandNotFound\"}}\r\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
            });
            server.setDaemon(true);
            server.start();
            QmpClient.execute("127.0.0.1", ss.getLocalPort(), "nope", 3000);
        }
    }

    @Test
    public void keysyms() {
        assertEquals('a', Keysyms.forChar('a'));
        assertEquals('Z', Keysyms.forChar('Z'));
        assertEquals(0xE9, Keysyms.forChar('é'));
        assertEquals(0x01000628, Keysyms.forChar('ب'));
        assertEquals(Keysyms.RETURN, Keysyms.forChar('\n'));
        assertEquals(0xFFBE, Keysyms.function(1));
        assertEquals(0xFFC9, Keysyms.function(12));
    }
}
