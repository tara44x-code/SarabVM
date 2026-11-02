package com.sarab.vm.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Minimal VNC (RFB 3.3 / 3.7 / 3.8) client used to show the virtual machine's
 * screen. Supports the encodings QEMU needs: Raw, CopyRect and DesktopSize.
 *
 * Pure Java: the framebuffer is an int[] of 0xAARRGGBB pixels that the Android
 * view copies into a Bitmap. All network writes run on a private background
 * thread, so input methods are safe to call from the UI thread.
 */
public final class RfbClient implements Closeable {

    public interface Listener {
        void onConnected(String desktopName, int width, int height);
        void onResize(int width, int height);
        void onFrameUpdated(int x, int y, int w, int h);
        void onBell();
        void onDisconnected(Exception reason);
    }

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 4;
    public static final int WHEEL_UP = 8;
    public static final int WHEEL_DOWN = 16;

    static final int ENC_RAW = 0;
    static final int ENC_COPY_RECT = 1;
    static final int ENC_DESKTOP_SIZE = -223;

    private final Listener listener;
    private final Object fbLock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rfb-send");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread reader;
    private volatile boolean closed;

    private int width;
    private int height;
    private int[] framebuffer = new int[0];
    private String desktopName = "";

    public RfbClient(Listener listener) {
        this.listener = listener;
    }

    /**
     * Connects, retrying until {@code waitMs} has passed (QEMU needs a moment to
     * open its VNC port after starting). Performs the handshake on the calling
     * thread, then starts the reader thread. Call from a background thread.
     */
    public void connect(String host, int port, long waitMs) throws IOException {
        long deadline = System.currentTimeMillis() + waitMs;
        IOException last = null;
        while (!closed) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 2000);
                s.setTcpNoDelay(true);
                socket = s;
                break;
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignored) { }
                if (System.currentTimeMillis() >= deadline) throw last;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        if (closed) throw new IOException("closed");
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        handshake();
        reader = new Thread(this::readLoop, "rfb-read");
        reader.setDaemon(true);
        reader.start();
    }

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String v = new String(ver, StandardCharsets.US_ASCII);
        if (!v.startsWith("RFB ")) throw new IOException("Not a VNC server: " + v.trim());
        int major = Integer.parseInt(v.substring(4, 7));
        int minor = Integer.parseInt(v.substring(8, 11));
        int useMinor;
        if (major > 3 || minor >= 8) useMinor = 8;
        else if (minor >= 7) useMinor = 7;
        else useMinor = 3;
        out.write(("RFB 003.00" + useMinor + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        if (useMinor == 3) {
            int type = in.readInt();
            if (type == 0) throw new IOException("Server refused: " + readString());
            if (type != 1) throw new IOException("Password-protected VNC is not supported (type " + type + ")");
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) throw new IOException("Server refused: " + readString());
            boolean hasNone = false;
            for (int i = 0; i < count; i++) {
                if (in.readUnsignedByte() == 1) hasNone = true;
            }
            if (!hasNone) throw new IOException("Password-protected VNC is not supported");
            out.writeByte(1);
            out.flush();
            if (useMinor == 8) {
                int result = in.readInt();
                if (result != 0) throw new IOException("Security failed: " + readString());
            }
        }

        out.writeByte(1); // ClientInit: shared
        out.flush();

        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        byte[] pf = new byte[16];
        in.readFully(pf);
        desktopName = readString();
        resize(w, h);

        // SetPixelFormat: 32bpp, depth 24, little-endian, true colour, RGB888
        out.writeByte(0);
        out.write(new byte[3]);
        out.writeByte(32);
        out.writeByte(24);
        out.writeByte(0);
        out.writeByte(1);
        out.writeShort(255);
        out.writeShort(255);
        out.writeShort(255);
        out.writeByte(16);
        out.writeByte(8);
        out.writeByte(0);
        out.write(new byte[3]);

        // SetEncodings
        int[] encodings = {ENC_COPY_RECT, ENC_RAW, ENC_DESKTOP_SIZE};
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int e : encodings) out.writeInt(e);

        writeUpdateRequest(false);
        out.flush();
        listener.onConnected(desktopName, w, h);
    }

    private void readLoop() {
        Exception reason = null;
        try {
            byte[] row = new byte[0];
            while (!closed) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: {
                        in.readUnsignedByte();
                        int rects = in.readUnsignedShort();
                        for (int i = 0; i < rects; i++) {
                            int x = in.readUnsignedShort();
                            int y = in.readUnsignedShort();
                            int w = in.readUnsignedShort();
                            int h = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == ENC_RAW) {
                                if (row.length < w * 4) row = new byte[w * 4];
                                readRaw(x, y, w, h, row);
                                listener.onFrameUpdated(x, y, w, h);
                            } else if (enc == ENC_COPY_RECT) {
                                int sx = in.readUnsignedShort();
                                int sy = in.readUnsignedShort();
                                copyRect(sx, sy, x, y, w, h);
                                listener.onFrameUpdated(x, y, w, h);
                            } else if (enc == ENC_DESKTOP_SIZE) {
                                resize(w, h);
                                listener.onResize(w, h);
                            } else {
                                throw new IOException("Unsupported encoding " + enc);
                            }
                        }
                        synchronized (this) {
                            writeUpdateRequest(true);
                            out.flush();
                        }
                        break;
                    }
                    case 1: {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        int n = in.readUnsignedShort();
                        skipFully(n * 6L);
                        break;
                    }
                    case 2:
                        listener.onBell();
                        break;
                    case 3: {
                        skipFully(3);
                        long len = in.readInt() & 0xFFFFFFFFL;
                        skipFully(len);
                        break;
                    }
                    default:
                        throw new IOException("Unknown server message " + type);
                }
            }
        } catch (Exception e) {
            if (!closed) reason = e;
        } finally {
            closeQuietly();
            listener.onDisconnected(reason);
        }
    }

    private void readRaw(int x, int y, int w, int h, byte[] row) throws IOException {
        for (int j = 0; j < h; j++) {
            in.readFully(row, 0, w * 4);
            synchronized (fbLock) {
                int yy = y + j;
                if (yy >= height) continue;
                int base = yy * width;
                int maxW = Math.min(w, width - x);
                for (int i = 0; i < maxW; i++) {
                    int o = i * 4;
                    int b = row[o] & 0xFF;
                    int g = row[o + 1] & 0xFF;
                    int r = row[o + 2] & 0xFF;
                    framebuffer[base + x + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
    }

    private void copyRect(int sx, int sy, int dx, int dy, int w, int h) {
        synchronized (fbLock) {
            w = Math.min(w, Math.min(width - sx, width - dx));
            h = Math.min(h, Math.min(height - sy, height - dy));
            if (w <= 0 || h <= 0) return;
            int[] tmp = new int[w * h];
            for (int j = 0; j < h; j++) {
                System.arraycopy(framebuffer, (sy + j) * width + sx, tmp, j * w, w);
            }
            for (int j = 0; j < h; j++) {
                System.arraycopy(tmp, j * w, framebuffer, (dy + j) * width + dx, w);
            }
        }
    }

    private void resize(int w, int h) {
        synchronized (fbLock) {
            width = w;
            height = h;
            framebuffer = new int[Math.max(0, w * h)];
        }
    }

    private void writeUpdateRequest(boolean incremental) throws IOException {
        out.writeByte(3);
        out.writeByte(incremental ? 1 : 0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(width);
        out.writeShort(height);
    }

    private String readString() throws IOException {
        long len = in.readInt() & 0xFFFFFFFFL;
        if (len > 1 << 20) throw new IOException("String too long");
        byte[] b = new byte[(int) len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void skipFully(long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    // ---------------------------------------------------------------- input

    /** Absolute pointer position in framebuffer pixels plus a button mask. */
    public void sendPointer(int buttonMask, int x, int y) {
        final int cx = clamp(x, 0, Math.max(0, width - 1));
        final int cy = clamp(y, 0, Math.max(0, height - 1));
        send(o -> {
            o.writeByte(5);
            o.writeByte(buttonMask & 0xFF);
            o.writeShort(cx);
            o.writeShort(cy);
        });
    }

    public void sendKey(boolean down, int keysym) {
        send(o -> {
            o.writeByte(4);
            o.writeByte(down ? 1 : 0);
            o.writeShort(0);
            o.writeInt(keysym);
        });
    }

    /** Press then release a key. */
    public void tapKey(int keysym) {
        sendKey(true, keysym);
        sendKey(false, keysym);
    }

    /** Press keys in order, release them in reverse order (e.g. Ctrl+Alt+Del). */
    public void sendCombo(int... keysyms) {
        for (int k : keysyms) sendKey(true, k);
        for (int i = keysyms.length - 1; i >= 0; i--) sendKey(false, keysyms[i]);
    }

    private interface Writer {
        void write(DataOutputStream o) throws IOException;
    }

    private void send(Writer w) {
        if (closed || out == null) return;
        try {
            sender.execute(() -> {
                try {
                    synchronized (this) {
                        w.write(out);
                        out.flush();
                    }
                } catch (IOException e) {
                    closeQuietly();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // client already closed
        }
    }

    // ---------------------------------------------------------------- access

    /** Lock to hold while reading {@link #getFramebuffer()}. */
    public Object framebufferLock() {
        return fbLock;
    }

    public int[] getFramebuffer() {
        return framebuffer;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getDesktopName() {
        return desktopName;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closeQuietly();
        Thread r = reader;
        if (r != null && r != Thread.currentThread()) r.interrupt();
    }

    private void closeQuietly() {
        closed = true;
        sender.shutdownNow();
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
