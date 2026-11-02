package com.sarab.vm.data;

import com.sarab.vm.core.Lang;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Boot images (ISO / IMG) copied into the app's own storage. QEMU needs a real
 * file path, and files picked from the phone come as content:// links.
 */
public final class ImageStore {

    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(long copied, long total);
    }

    private final Context context;
    private final File dir;

    public ImageStore(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(context.getFilesDir(), "images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public File directory() {
        return dir;
    }

    public List<File> list() {
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            out.addAll(Arrays.asList(files));
        }
        return out;
    }

    public String displayName(Uri uri) {
        String name = null;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // some providers do not support queries
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "image.iso";
        return sanitize(name);
    }

    public long size(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // unknown size
        }
        return -1;
    }

    /** Copies the picked file. Reuses an existing copy with the same name and size. Blocking. */
    public File importUri(Uri uri, Progress progress) throws IOException {
        String name = displayName(uri);
        long total = size(uri);
        File target = new File(dir, name);
        if (target.exists() && total > 0 && target.length() == total) return target;

        File part = new File(dir, name + ".part");
        ContentResolver cr = context.getContentResolver();
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException(Lang.t("تعذر فتح الملف"));
            byte[] buf = new byte[1 << 20];
            long copied = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (progress != null && !progress.onProgress(copied, total)) {
                    throw new IOException(Lang.t("أُلغي النسخ"));
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        if (!part.renameTo(target)) throw new IOException(Lang.t("تعذر حفظ الملف"));
        return target;
    }

    public boolean delete(File f) {
        return f.getParentFile() != null && f.getParentFile().equals(dir) && f.delete();
    }

    static String sanitize(String name) {
        String n = name.replace('/', '_').replace('\\', '_').replace(',', '_').trim();
        if (n.isEmpty() || n.startsWith(".")) n = "image" + n;
        return n;
    }
}
