package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class OsCatalogAndDownloaderTest {

    static final String ALPINE_YAML = "---\n"
            + "-\n"
            + "  title: \"Standard\"\n"
            + "  flavor: alpine-standard\n"
            + "  file: alpine-standard-3.22.1-x86_64.iso\n"
            + "  iso: alpine-standard-3.22.1-x86_64.iso\n"
            + "  sha256: 1111111111111111111111111111111111111111111111111111111111111111\n"
            + "-\n"
            + "  title: \"Virtual\"\n"
            + "  desc: \"Slimmed down kernel.\"\n"
            + "  flavor: alpine-virt\n"
            + "  file: alpine-virt-3.22.1-x86_64.iso\n"
            + "  iso: alpine-virt-3.22.1-x86_64.iso\n"
            + "  sha256: ABCDEF0000000000000000000000000000000000000000000000000000000000\n"
            + "-\n"
            + "  flavor: alpine-minirootfs\n"
            + "  file: alpine-minirootfs-3.22.1-x86_64.tar.gz\n";

    static final String DEBIAN_SUMS =
            "2222222222222222222222222222222222222222222222222222222222222222  debian-13.1.0-amd64-netinst.iso\n"
            + "3333333333333333333333333333333333333333333333333333333333333333  debian-edu-13.1.0-amd64-netinst.iso\n"
            + "4444444444444444444444444444444444444444444444444444444444444444  debian-mac-13.1.0-amd64-netinst.iso\n";

    @Test
    public void alpineVirtIsPicked() throws Exception {
        OsCatalog.Resolved r = OsCatalog.parseAlpine(ALPINE_YAML, "https://x/");
        assertEquals("https://x/alpine-virt-3.22.1-x86_64.iso", r.url);
        assertEquals("alpine-virt-3.22.1-x86_64.iso", r.fileName);
        assertEquals("abcdef0000000000000000000000000000000000000000000000000000000000", r.checksum);
        assertEquals("SHA-256", r.algorithm);
    }

    @Test
    public void debianNetinstIsPicked() throws Exception {
        OsCatalog.Resolved r = OsCatalog.parseDebian(DEBIAN_SUMS, "https://d/", "amd64");
        assertEquals("debian-13.1.0-amd64-netinst.iso", r.fileName);
        assertEquals("https://d/debian-13.1.0-amd64-netinst.iso", r.url);
        assertEquals("2222222222222222222222222222222222222222222222222222222222222222", r.checksum);
    }

    @Test(expected = IOException.class)
    public void debianWrongArchFails() throws Exception {
        OsCatalog.parseDebian(DEBIAN_SUMS, "https://d/", "arm64");
    }

    @Test
    public void tinyCoreNewestFolderAndVersion() throws Exception {
        Map<String, String> web = new HashMap<>();
        String dir = OsCatalog.TINYCORE_BASE + "17.x/x86/release/";
        web.put(dir, "<a href=\"TinyCore-16.2.iso\">x</a> <a href=\"TinyCore-17.0.iso\">TinyCore-17.0.iso</a>"
                + " <a href=\"TinyCore-17.0.iso.md5.txt\">m</a> <a href=\"TinyCore-current.iso\">c</a>");
        web.put(dir + "TinyCore-17.0.iso.md5.txt", "0123456789abcdef0123456789ABCDEF  TinyCore-17.0.iso\n");
        OsCatalog.Resolved r = OsCatalog.resolveTinyCore(url -> {
            String body = web.get(url);
            if (body == null) throw new IOException("404 " + url);
            return body;
        }, "x86");
        assertEquals(dir + "TinyCore-17.0.iso", r.url);
        assertEquals("0123456789abcdef0123456789abcdef", r.checksum);
        assertEquals("MD5", r.algorithm);
    }

    @Test
    public void catalogEntriesMakeValidMachines() {
        assertTrue(OsCatalog.entries().size() >= 5);
        for (OsCatalog.Entry e : OsCatalog.entries()) {
            VmConfig c = e.newConfig("/files/images/x.iso");
            assertTrue(e.id + " " + c.validate(), c.validate().isEmpty());
            assertEquals(e, OsCatalog.byId(e.id));
        }
    }

    @Test
    public void downloadFollowsRedirectAndVerifiesChecksum() throws Exception {
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        String sha = Downloader.hex(MessageDigest.getInstance("SHA-256").digest(payload));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old.iso", ex -> {
            ex.getResponseHeaders().add("Location", "/new.iso");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/new.iso", ex -> {
            ex.sendResponseHeaders(200, payload.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(payload);
            }
        });
        server.createContext("/list.txt", ex -> {
            byte[] b = "hello list".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        File dir = Files.createTempDirectory("dl").toFile();
        try {
            assertEquals("hello list", Downloader.fetchText(base + "/list.txt"));

            File target = new File(dir, "os.iso");
            long[] last = {0};
            Downloader.download(base + "/old.iso", target, sha, "SHA-256", (done, total) -> {
                last[0] = done;
                return true;
            });
            assertTrue(target.isFile());
            assertEquals(payload.length, target.length());
            assertEquals(payload.length, last[0]);
            assertFalse(new File(dir, "os.iso.part").exists());

            File bad = new File(dir, "bad.iso");
            try {
                Downloader.download(base + "/new.iso", bad, "00", "SHA-256", null);
                throw new AssertionError("checksum mismatch not detected");
            } catch (IOException expected) {
                assertFalse(bad.exists());
                assertFalse(new File(dir, "bad.iso.part").exists());
            }

            File cancelled = new File(dir, "c.iso");
            try {
                Downloader.download(base + "/new.iso", cancelled, null, null, (done, total) -> false);
                throw new AssertionError("cancel ignored");
            } catch (IOException expected) {
                assertFalse(cancelled.exists());
            }

            try {
                Downloader.fetchText(base + "/missing");
                throw new AssertionError("404 not reported");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("404"));
            }
        } finally {
            server.stop(0);
        }
    }
}
