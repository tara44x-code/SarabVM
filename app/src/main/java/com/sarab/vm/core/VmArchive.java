package com.sarab.vm.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Export / import of one machine as a ".sarab" file (a ZIP):
 *   vm.properties   the settings (paths replaced by file names)
 *   disk.qcow2      the virtual hard disk, if any
 *   image/NAME      the boot image, only when the user chose to include it
 * Pure Java so it can be tested on a computer.
 */
public final class VmArchive {

    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(long done, long total);
    }

    private static final String CONFIG = "vm.properties";
    private static final String DISK = "disk.qcow2";
    private static final String IMAGE_DIR = "image/";

    private VmArchive() { }

    public static void export(VmConfig c, boolean includeImage, OutputStream target, Progress progress) throws IOException {
        File disk = notEmpty(c.diskPath) ? new File(c.diskPath) : null;
        File image = includeImage && notEmpty(c.isoPath) ? new File(c.isoPath) : null;
        long total = (disk != null && disk.isFile() ? disk.length() : 0) + (image != null && image.isFile() ? image.length() : 0);
        long[] done = {0};

        Properties p = c.toProperties();
        p.setProperty("diskPath", disk != null && disk.isFile() ? DISK : "");
        p.setProperty("isoPath", notEmpty(c.isoPath) ? new File(c.isoPath).getName() : "");
        p.setProperty("snapshotSaved", Boolean.toString(c.snapshotSaved && disk != null && disk.isFile()));

        ZipOutputStream zip = new ZipOutputStream(target);
        zip.setLevel(Deflater.BEST_SPEED);
        zip.putNextEntry(new ZipEntry(CONFIG));
        p.store(zip, "Sarab VM export");
        zip.closeEntry();
        if (disk != null && disk.isFile()) addFile(zip, DISK, disk, done, total, progress);
        if (image != null && image.isFile()) addFile(zip, IMAGE_DIR + image.getName(), image, done, total, progress);
        zip.finish();
        zip.flush();
    }

    /**
     * Reads a .sarab file. The machine gets a new id; its disk goes to disksDir and an included
     * image to imagesDir (an image with the same name and size already there is reused).
     */
    public static VmConfig importFrom(InputStream source, File disksDir, File imagesDir, Progress progress) throws IOException {
        VmConfig c = null;
        String newId = new VmConfig().id;
        File disk = null;
        File image = null;
        String isoName = "";
        long[] done = {0};
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/")) throw new IOException("ملف غير صالح");
                if (CONFIG.equals(name)) {
                    Properties p = new Properties();
                    p.load(zip);
                    c = VmConfig.fromProperties(p);
                    isoName = p.getProperty("isoPath", "");
                } else if (DISK.equals(name)) {
                    disk = new File(disksDir, newId + ".qcow2");
                    copy(zip, disk, done, -1, progress);
                } else if (name.startsWith(IMAGE_DIR) && name.length() > IMAGE_DIR.length()) {
                    String fileName = new File(name).getName();
                    File target = new File(imagesDir, fileName);
                    if (target.isFile() && e.getSize() > 0 && target.length() == e.getSize()) {
                        image = target;
                    } else {
                        File tmp = new File(imagesDir, fileName + ".part");
                        copy(zip, tmp, done, -1, progress);
                        if (target.exists() && !target.delete()) throw new IOException("تعذر استبدال " + fileName);
                        if (!tmp.renameTo(target)) throw new IOException("تعذر حفظ " + fileName);
                        image = target;
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException ex) {
            if (disk != null) {
                //noinspection ResultOfMethodCallIgnored
                disk.delete();
            }
            throw ex;
        }
        if (c == null) {
            if (disk != null) {
                //noinspection ResultOfMethodCallIgnored
                disk.delete();
            }
            throw new IOException("هذا ليس ملف نظام من سراب");
        }
        c.id = newId;
        c.createdAt = System.currentTimeMillis();
        c.diskPath = disk != null ? disk.getAbsolutePath() : "";
        if (disk == null) c.snapshotSaved = false;
        if (image != null) {
            c.isoPath = image.getAbsolutePath();
        } else if (notEmpty(isoName) && new File(imagesDir, isoName).isFile()) {
            c.isoPath = new File(imagesDir, isoName).getAbsolutePath();
        } else {
            c.isoPath = "";
        }
        return c;
    }

    /** Copy of a machine with its own disk. Blocking. */
    public static VmConfig duplicate(VmConfig src, File disksDir, Progress progress) throws IOException {
        VmConfig c = VmConfig.fromProperties(src.toProperties());
        c.id = new VmConfig().id;
        c.createdAt = System.currentTimeMillis();
        c.name = src.name + " (نسخة)";
        if (notEmpty(src.diskPath) && new File(src.diskPath).isFile()) {
            File from = new File(src.diskPath);
            File to = new File(disksDir, c.id + ".qcow2");
            try (InputStream in = new FileInputStream(from)) {
                copy(in, to, new long[]{0}, from.length(), progress);
            }
            c.diskPath = to.getAbsolutePath();
        } else {
            c.diskPath = "";
            c.snapshotSaved = false;
        }
        return c;
    }

    private static void addFile(ZipOutputStream zip, String entry, File f, long[] done, long total, Progress progress) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        try (InputStream in = new FileInputStream(f)) {
            pump(in, zip, done, total, progress);
        }
        zip.closeEntry();
    }

    private static void copy(InputStream in, File target, long[] done, long total, Progress progress) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            pump(in, out, done, total, progress);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
    }

    private static void pump(InputStream in, OutputStream out, long[] done, long total, Progress progress) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done[0] += n;
            if (progress != null && !progress.onProgress(done[0], total)) throw new IOException("أُلغيت العملية");
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
