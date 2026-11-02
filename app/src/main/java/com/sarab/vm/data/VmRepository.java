package com.sarab.vm.data;

import com.sarab.vm.core.Lang;

import android.content.Context;

import com.sarab.vm.core.VmConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Saves each machine as files/vms/&lt;id&gt;.properties; disks live in files/disks/. */
public final class VmRepository {

    private final File vmDir;
    private final File diskDir;

    public VmRepository(Context context) {
        File base = context.getFilesDir();
        vmDir = new File(base, "vms");
        diskDir = new File(base, "disks");
        //noinspection ResultOfMethodCallIgnored
        vmDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
    }

    public File diskFileFor(VmConfig c) {
        return new File(diskDir, c.id + ".qcow2");
    }

    public List<VmConfig> list() {
        List<VmConfig> out = new ArrayList<>();
        File[] files = vmDir.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files != null) {
            for (File f : files) {
                VmConfig c = read(f);
                if (c != null) out.add(c);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    public VmConfig get(String id) {
        if (id == null) return null;
        File f = new File(vmDir, id + ".properties");
        return f.exists() ? read(f) : null;
    }

    public void save(VmConfig c) throws IOException {
        File tmp = new File(vmDir, c.id + ".properties.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            c.toProperties().store(o, "Sarab VM");
        }
        File target = new File(vmDir, c.id + ".properties");
        if (!tmp.renameTo(target)) {
            throw new IOException(Lang.t("تعذر حفظ الإعدادات"));
        }
    }

    /** Deletes the machine and its virtual hard disk. Boot images (ISO) are kept. */
    public void delete(VmConfig c) {
        //noinspection ResultOfMethodCallIgnored
        new File(vmDir, c.id + ".properties").delete();
        if (c.diskPath != null && !c.diskPath.isEmpty()) {
            File disk = new File(c.diskPath);
            if (disk.getParentFile() != null && disk.getParentFile().equals(diskDir)) {
                //noinspection ResultOfMethodCallIgnored
                disk.delete();
            }
        }
    }

    private static VmConfig read(File f) {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            return VmConfig.fromProperties(p);
        } catch (IOException e) {
            return null;
        }
    }
}
