package com.sarab.vm.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Plain HTTPS downloads with progress, cancel and checksum check. Pure Java; call off the UI thread. */
public final class Downloader {

    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(long done, long total);
    }

    private static final int MAX_REDIRECTS = 6;

    private Downloader() { }

    /** Small text files (release lists), at most 2 MB. */
    public static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) throw new IOException(Lang.t("الملف أكبر من المتوقع"));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Downloads to {@code target} (via a .part file) and verifies the checksum when given.
     * algorithm is "SHA-256", "MD5" or null.
     */
    public static void download(String url, File target, String checksum, String algorithm, Progress progress)
            throws IOException {
        File part = new File(target.getPath() + ".part");
        MessageDigest digest = null;
        if (checksum != null && algorithm != null) {
            try {
                digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                digest = null;
            }
        }
        HttpURLConnection c = open(url);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (digest != null) digest.update(buf, 0, n);
                done += n;
                if (progress != null && !progress.onProgress(done, total)) {
                    throw new IOException(Lang.t("أُلغي التحميل"));
                }
            }
            if (total > 0 && done != total) throw new IOException(Lang.t("انقطع التحميل قبل اكتماله"));
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (digest != null) {
            String got = hex(digest.digest());
            if (!got.equalsIgnoreCase(checksum)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException(Lang.t("الملف المحمّل تالف (لا يطابق رمز التحقق). حاول مرة أخرى."));
            }
        }
        if (target.exists() && !target.delete()) throw new IOException(Lang.t("تعذر استبدال الملف القديم"));
        if (!part.renameTo(target)) throw new IOException(Lang.t("تعذر حفظ الملف"));
    }

    /** Opens a GET connection and follows redirects (including http → https). */
    static HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "SarabVM/1.0 (Android)");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException(Lang.t("تحويل بدون عنوان"));
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code + " — " + current);
            }
            return c;
        }
        throw new IOException(Lang.t("تحويلات كثيرة جدًا"));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
