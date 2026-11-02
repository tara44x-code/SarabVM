package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

public class Batch2CoreTest {

    private final QemuCommandBuilder builder = new QemuCommandBuilder(new File("/lib"), new File("/data"));

    @Test
    public void portForwardParsing() {
        PortForwards ok = PortForwards.parse("8080:80, 2222:22، 3000:3000");
        assertTrue(ok.errors.toString(), ok.errors.isEmpty());
        assertEquals(3, ok.rules.size());
        assertEquals(",hostfwd=tcp:127.0.0.1:8080-:80,hostfwd=tcp:127.0.0.1:2222-:22,hostfwd=tcp:127.0.0.1:3000-:3000",
                ok.toQemuSuffix());

        assertTrue(PortForwards.parse("").rules.isEmpty());
        assertEquals(1, PortForwards.parse("80:80").errors.size());      // phone port < 1024
        assertEquals(1, PortForwards.parse("8080").errors.size());       // no colon
        assertEquals(1, PortForwards.parse("8080:0").errors.size());     // bad guest port
        assertEquals(1, PortForwards.parse("8080:80,8080:81").errors.size()); // duplicate
        assertEquals(1, PortForwards.parse("x:80").errors.size());
    }

    @Test
    public void portForwardsInCommandAndValidation() {
        VmConfig c = new VmConfig();
        c.name = "web";
        c.isoPath = "/a.iso";
        c.portForwards = "8080:80";
        assertTrue(String.join(" ", builder.build(c, new EmulatorSettings(), 0))
                .contains("user,id=net0,hostfwd=tcp:127.0.0.1:8080-:80"));
        c.portForwards = "80:80";
        assertFalse(c.validate().isEmpty());
    }

    @Test
    public void soundUsesWavPipeAndHda() {
        VmConfig c = new VmConfig();
        c.name = "s";
        c.isoPath = "/a.iso";
        assertFalse(String.join(" ", builder.build(c, new EmulatorSettings(), 0, "/cache/a.pipe")).contains("audiodev"));
        c.sound = true;
        String all = String.join(" ", builder.build(c, new EmulatorSettings(), 0, "/cache/a.pipe"));
        assertTrue(all.contains("-audiodev wav,id=snd0,path=/cache/a.pipe"));
        assertTrue(all.contains("-device intel-hda -device hda-output,audiodev=snd0"));
        assertFalse("no pipe → no sound args", String.join(" ", builder.build(c, new EmulatorSettings(), 0)).contains("audiodev"));
        assertTrue(VmConfig.fromProperties(c.toProperties()).sound);
    }

    @Test
    public void wavHeaderParsing() {
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[22] = 2;                                   // channels
        h[24] = (byte) 0x44; h[25] = (byte) 0xAC;    // 44100
        h[34] = 16;                                  // bits
        WavHeader w = WavHeader.parse(h);
        assertEquals(2, w.channels);
        assertEquals(44100, w.sampleRate);
        assertEquals(16, w.bits);
        h[0] = 'X';
        assertEquals(null, WavHeader.parse(h));
    }

    @Test
    public void snapshotLoadOnlyWithDisk() {
        VmConfig c = new VmConfig();
        c.name = "w";
        c.isoPath = "/w.iso";
        c.snapshotSaved = true;
        assertFalse("no disk → no loadvm", builder.build(c, new EmulatorSettings(), 0).contains("-loadvm"));
        c.diskPath = "/d.qcow2";
        List<String> a = builder.build(c, new EmulatorSettings(), 0);
        assertEquals("sarab", a.get(a.indexOf("-loadvm") + 1));
        c.shareFolder = true;
        c.shareWritable = true;
        assertFalse("writable shared folder blocks snapshots", c.canSaveState());

        VmConfig back = VmConfig.fromProperties(c.toProperties());
        assertTrue(back.snapshotSaved);
    }

    @Test
    public void hmpSavevmAndErrors() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        String[] replies = {"{\"return\": \"\"}", "{\"return\": \"Error: Device 'ide0-hd1' is writable but does not support snapshots\\r\\n\"}"};
        for (String reply : replies) {
            try (ServerSocket ss = new ServerSocket(0)) {
                Thread t = new Thread(() -> {
                    try (Socket s = ss.accept()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        OutputStream o = s.getOutputStream();
                        o.write("{\"QMP\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                        r.readLine();
                        o.write("{\"return\": {}}\r\n".getBytes(StandardCharsets.UTF_8));
                        got.add(r.readLine());
                        o.write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
                        o.flush();
                    } catch (IOException ignored) {
                    }
                });
                t.setDaemon(true);
                t.start();
                try {
                    String out = QmpClient.hmp("127.0.0.1", ss.getLocalPort(), "savevm sarab", 3000);
                    assertEquals(replies[0], reply);
                    assertEquals("", out);
                } catch (IOException e) {
                    assertEquals(replies[1], reply);
                    assertTrue(e.getMessage().startsWith("Error: Device"));
                }
                t.join(2000);
            }
        }
        assertEquals("{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"savevm sarab\"}}", got.get(0));
        assertEquals("line1\nline2", QmpClient.returnString("{\"return\": \"line1\\r\\nline2\"}"));
        assertEquals("", QmpClient.returnString("{\"return\": {}}"));
    }

    @Test
    public void exportImportRoundTripAndDuplicate() throws Exception {
        File root = Files.createTempDirectory("arch").toFile();
        File disks = new File(root, "disks");
        File images = new File(root, "images");
        disks.mkdirs();
        images.mkdirs();
        File disk = new File(disks, "orig.qcow2");
        File iso = new File(images, "linux.iso");
        write(disk, 200_000, 7);
        write(iso, 50_000, 3);

        VmConfig c = new VmConfig();
        c.name = "Ubuntu";
        c.diskPath = disk.getAbsolutePath();
        c.isoPath = iso.getAbsolutePath();
        c.portForwards = "8080:80";
        c.snapshotSaved = true;

        // export with image, import into a fresh "phone"
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        VmArchive.export(c, true, zip, null);
        File root2 = Files.createTempDirectory("arch2").toFile();
        File disks2 = new File(root2, "disks");
        File images2 = new File(root2, "images");
        disks2.mkdirs();
        images2.mkdirs();
        long[] last = {0};
        VmConfig in = VmArchive.importFrom(new ByteArrayInputStream(zip.toByteArray()), disks2, images2, (d, t) -> {
            last[0] = d;
            return true;
        });
        assertFalse(in.id.equals(c.id));
        assertEquals("Ubuntu", in.name);
        assertEquals("8080:80", in.portForwards);
        assertTrue(in.snapshotSaved);
        assertEquals(disk.length(), new File(in.diskPath).length());
        assertTrue(Files.readAllBytes(disk.toPath()).length == Files.readAllBytes(new File(in.diskPath).toPath()).length);
        assertEquals(new File(images2, "linux.iso").getAbsolutePath(), in.isoPath);
        assertEquals(disk.length() + iso.length(), last[0]);

        // export without image: iso name kept only if that file exists on the new phone
        ByteArrayOutputStream zip2 = new ByteArrayOutputStream();
        VmArchive.export(c, false, zip2, null);
        VmConfig in2 = VmArchive.importFrom(new ByteArrayInputStream(zip2.toByteArray()), disks2, images2, null);
        assertEquals(new File(images2, "linux.iso").getAbsolutePath(), in2.isoPath);
        File root3 = Files.createTempDirectory("arch3").toFile();
        VmConfig in3 = VmArchive.importFrom(new ByteArrayInputStream(zip2.toByteArray()), root3, root3, null);
        assertEquals("", in3.isoPath);

        // garbage is rejected
        try {
            VmArchive.importFrom(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)), root3, root3, null);
            throw new AssertionError("garbage accepted");
        } catch (IOException expected) {
            // ok
        }

        // duplicate copies the disk
        VmConfig dup = VmArchive.duplicate(c, disks, null);
        assertEquals("Ubuntu (نسخة)", dup.name);
        assertFalse(dup.diskPath.equals(c.diskPath));
        assertEquals(disk.length(), new File(dup.diskPath).length());
        assertEquals(c.isoPath, dup.isoPath);
    }

    private static void write(File f, int size, int seed) throws IOException {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * seed);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }
}
