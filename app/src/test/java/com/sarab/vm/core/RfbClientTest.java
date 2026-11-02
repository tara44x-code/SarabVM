package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Talks to a tiny fake VNC server that behaves like QEMU. */
public class RfbClientTest {

    static class Recorder implements RfbClient.Listener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch frames = new CountDownLatch(2);
        final CountDownLatch resized = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String name;

        @Override public void onConnected(String desktopName, int width, int height) {
            name = desktopName;
            connected.countDown();
        }
        @Override public void onResize(int width, int height) { resized.countDown(); }
        @Override public void onFrameUpdated(int x, int y, int w, int h) { frames.countDown(); }
        @Override public void onBell() { }
        @Override public void onDisconnected(Exception reason) { disconnected.countDown(); }
    }

    /** Reads client messages after the handshake and records key/pointer events. */
    static void readClientMessages(DataInputStream in, List<String> events, CountDownLatch gotInput)
            throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 3) {
                in.readFully(new byte[9]);
            } else if (type == 4) {
                int down = in.readUnsignedByte();
                in.readUnsignedShort();
                int key = in.readInt();
                events.add("key " + down + " " + Integer.toHexString(key));
                gotInput.countDown();
            } else if (type == 5) {
                int mask = in.readUnsignedByte();
                int x = in.readUnsignedShort();
                int y = in.readUnsignedShort();
                events.add("ptr " + mask + " " + x + "," + y);
                gotInput.countDown();
            } else {
                throw new IOException("unexpected client message " + type);
            }
        }
    }

    @Test
    public void handshakeFramesCopyRectResizeAndInput() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch gotInput = new CountDownLatch(4);
        CountDownLatch handshakeChecked = new CountDownLatch(1);
        final String[] serverError = {null};

        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] v = new byte[12];
                    in.readFully(v);
                    if (!new String(v, StandardCharsets.US_ASCII).equals("RFB 003.008\n")) throw new IOException("bad version");
                    out.writeByte(1);
                    out.writeByte(1);
                    if (in.readUnsignedByte() != 1) throw new IOException("bad security choice");
                    out.writeInt(0);
                    if (in.readUnsignedByte() != 1) throw new IOException("not shared");
                    out.writeShort(4);
                    out.writeShort(2);
                    out.write(new byte[16]);
                    byte[] name = "QEMU (test)".getBytes(StandardCharsets.UTF_8);
                    out.writeInt(name.length);
                    out.write(name);
                    out.flush();

                    // SetPixelFormat
                    if (in.readUnsignedByte() != 0) throw new IOException("expected SetPixelFormat");
                    byte[] pf = new byte[19];
                    in.readFully(pf);
                    if (pf[3] != 32 || pf[6] != 1 || pf[13] != 16 || pf[14] != 8 || pf[15] != 0) throw new IOException("bad pixel format");
                    // SetEncodings
                    if (in.readUnsignedByte() != 2) throw new IOException("expected SetEncodings");
                    in.readUnsignedByte();
                    int n = in.readUnsignedShort();
                    boolean raw = false, copy = false, size = false;
                    for (int i = 0; i < n; i++) {
                        int e = in.readInt();
                        raw |= e == 0;
                        copy |= e == 1;
                        size |= e == -223;
                    }
                    if (!(raw && copy && size)) throw new IOException("missing encodings");
                    // first full update request
                    if (in.readUnsignedByte() != 3) throw new IOException("expected update request");
                    if (in.readUnsignedByte() != 0) throw new IOException("first request must be full");
                    in.readFully(new byte[8]);
                    handshakeChecked.countDown();

                    // Frame: raw red+green at (0,0), then copy them to (2,1)
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeShort(2);
                    out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(1); out.writeInt(0);
                    out.write(new byte[]{0, 0, (byte) 255, 0});          // red   (B,G,R,X)
                    out.write(new byte[]{0, (byte) 200, 0, 0});          // green
                    out.writeShort(2); out.writeShort(1); out.writeShort(2); out.writeShort(1); out.writeInt(1);
                    out.writeShort(0); out.writeShort(0);
                    out.flush();
                    Thread.sleep(150);
                    // Resize to 8x4
                    out.writeByte(0);
                    out.writeByte(0);
                    out.writeShort(1);
                    out.writeShort(0); out.writeShort(0); out.writeShort(8); out.writeShort(4); out.writeInt(-223);
                    // Bell + clipboard text, both must be skipped cleanly
                    out.writeByte(2);
                    out.writeByte(3); out.write(new byte[3]); out.writeInt(5); out.write("hello".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    readClientMessages(in, events, gotInput);
                } catch (Exception e) {
                    if (gotInput.getCount() > 0) serverError[0] = e.toString();
                }
            });
            server.setDaemon(true);
            server.start();

            Recorder rec = new Recorder();
            RfbClient client = new RfbClient(rec);
            client.connect("127.0.0.1", ss.getLocalPort(), 3000);

            assertTrue(rec.connected.await(3, TimeUnit.SECONDS));
            assertEquals("QEMU (test)", rec.name);
            assertTrue("handshake verified by server " + serverError[0], handshakeChecked.await(3, TimeUnit.SECONDS));
            assertTrue("frames received", rec.frames.await(3, TimeUnit.SECONDS));
            Thread.sleep(50);
            synchronized (client.framebufferLock()) {
                int[] fb = client.getFramebuffer();
                assertEquals(4, client.getWidth());
                assertEquals(0xFFFF0000, fb[0]);
                assertEquals(0xFF00C800, fb[1]);
                assertEquals("copy of red", 0xFFFF0000, fb[4 + 2]);
                assertEquals("copy of green", 0xFF00C800, fb[4 + 3]);
            }
            assertTrue("resized", rec.resized.await(3, TimeUnit.SECONDS));
            assertEquals(8, client.getWidth());
            assertEquals(4, client.getHeight());

            client.sendCombo(Keysyms.CONTROL_L, Keysyms.ALT_L);
            client.sendPointer(RfbClient.BUTTON_LEFT, 100, -5); // clamped to 7,0
            client.sendPointer(0, 3, 2);
            assertTrue("input received " + events + " " + serverError[0], gotInput.await(3, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals("key 1 ffe3", events.get(0));
            assertEquals("key 1 ffe9", events.get(1));
            assertEquals("key 0 ffe9", events.get(2));
            assertEquals("key 0 ffe3", events.get(3));
            assertTrue(events.contains("ptr 1 7,0"));

            client.close();
            assertTrue(rec.disconnected.await(3, TimeUnit.SECONDS));
            assertEquals(null, serverError[0]);
        }
    }

    @Test
    public void protocol33Handshake() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.write("RFB 003.003\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] v = new byte[12];
                    in.readFully(v);
                    if (!new String(v, StandardCharsets.US_ASCII).equals("RFB 003.003\n")) return;
                    out.writeInt(1);
                    in.readUnsignedByte();
                    out.writeShort(640);
                    out.writeShort(480);
                    out.write(new byte[16]);
                    out.writeInt(0);
                    out.flush();
                    in.readFully(new byte[20 + 16 + 10]);
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
            });
            server.setDaemon(true);
            server.start();

            Recorder rec = new Recorder();
            RfbClient client = new RfbClient(rec);
            client.connect("127.0.0.1", ss.getLocalPort(), 3000);
            assertTrue(rec.connected.await(3, TimeUnit.SECONDS));
            assertEquals(640, client.getWidth());
            assertEquals(480, client.getHeight());
            assertEquals(640 * 480, client.getFramebuffer().length);
            client.close();
        }
    }

    @Test
    public void retriesUntilServerAppears() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        final int p = port;
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(900);
                try (ServerSocket ss = new ServerSocket(p); Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    in.readFully(new byte[12]);
                    out.writeByte(1); out.writeByte(1);
                    in.readUnsignedByte();
                    out.writeInt(0);
                    in.readUnsignedByte();
                    out.writeShort(16); out.writeShort(16); out.write(new byte[16]); out.writeInt(0);
                    out.flush();
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {
            }
        });
        late.setDaemon(true);
        late.start();

        Recorder rec = new Recorder();
        RfbClient client = new RfbClient(rec);
        client.connect("127.0.0.1", p, 5000);
        assertTrue(rec.connected.await(3, TimeUnit.SECONDS));
        client.close();
    }

    @Test(expected = IOException.class)
    public void passwordServerIsRejected() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    in.readFully(new byte[12]);
                    out.writeByte(1);
                    out.writeByte(2); // VNC password only
                    out.flush();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
            });
            server.setDaemon(true);
            server.start();
            new RfbClient(new Recorder()).connect("127.0.0.1", ss.getLocalPort(), 2000);
        }
    }
}
