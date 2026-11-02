package com.sarab.vm.core;

import java.util.Properties;
import java.util.UUID;

/**
 * Description of one virtual machine. Pure Java (no Android classes) so it can
 * be unit-tested on a normal JVM. Stored as a .properties file per machine.
 */
public final class VmConfig {

    public enum OsType { WINDOWS, LINUX, ANDROID, OTHER }

    public enum Arch {
        X86_64("x86_64", "qemu-system-x86_64"),
        I386("i386", "qemu-system-x86_64"),
        AARCH64("aarch64", "qemu-system-aarch64");

        public final String label;
        public final String qemuBinary;

        Arch(String label, String qemuBinary) {
            this.label = label;
            this.qemuBinary = qemuBinary;
        }
    }

    public static final int MIN_RAM_MB = 256;
    public static final int MIN_CPUS = 1;
    public static final int MAX_CPUS = 8;

    public String id;
    public String name;
    public OsType osType = OsType.LINUX;
    public Arch arch = Arch.X86_64;
    public int ramMb = 2048;
    public int cpus = 2;
    /** Absolute path of the boot image (ISO / IMG) inside app storage, or empty. */
    public String isoPath = "";
    /** Absolute path of the virtual hard disk (qcow2), or empty for none. */
    public String diskPath = "";
    /** Size used when the disk is first created; 0 means "no hard disk". */
    public int diskSizeGb = 16;
    public long createdAt;
    /** Show the phone's shared folder inside the machine as an extra disk. */
    public boolean shareFolder;
    /** Let the machine write into the shared folder (QEMU calls this experimental). */
    public boolean shareWritable;

    public VmConfig() {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.createdAt = System.currentTimeMillis();
    }

    /** Returns a list of problems; empty when the config can be started. */
    public java.util.List<String> validate() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (name == null || name.trim().isEmpty()) errors.add("الاسم فارغ");
        if (ramMb < MIN_RAM_MB) errors.add("الذاكرة أقل من " + MIN_RAM_MB + " MB");
        if (cpus < MIN_CPUS || cpus > MAX_CPUS) errors.add("عدد الأنوية يجب أن يكون بين 1 و 8");
        boolean hasIso = isoPath != null && !isoPath.isEmpty();
        boolean hasDisk = diskSizeGb > 0 || (diskPath != null && !diskPath.isEmpty());
        if (!hasIso && !hasDisk) errors.add("اختر ملف صورة أو قرصًا صلبًا");
        if (diskSizeGb < 0) errors.add("حجم القرص غير صالح");
        return errors;
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("id", id);
        p.setProperty("name", name == null ? "" : name);
        p.setProperty("osType", osType.name());
        p.setProperty("arch", arch.name());
        p.setProperty("ramMb", Integer.toString(ramMb));
        p.setProperty("cpus", Integer.toString(cpus));
        p.setProperty("isoPath", isoPath == null ? "" : isoPath);
        p.setProperty("diskPath", diskPath == null ? "" : diskPath);
        p.setProperty("diskSizeGb", Integer.toString(diskSizeGb));
        p.setProperty("createdAt", Long.toString(createdAt));
        p.setProperty("shareFolder", Boolean.toString(shareFolder));
        p.setProperty("shareWritable", Boolean.toString(shareWritable));
        return p;
    }

    public static VmConfig fromProperties(Properties p) {
        VmConfig c = new VmConfig();
        c.id = p.getProperty("id", c.id);
        c.name = p.getProperty("name", "");
        c.osType = parseEnum(OsType.class, p.getProperty("osType"), OsType.OTHER);
        c.arch = parseEnum(Arch.class, p.getProperty("arch"), Arch.X86_64);
        c.ramMb = parseInt(p.getProperty("ramMb"), 2048);
        c.cpus = parseInt(p.getProperty("cpus"), 2);
        c.isoPath = p.getProperty("isoPath", "");
        c.diskPath = p.getProperty("diskPath", "");
        c.diskSizeGb = parseInt(p.getProperty("diskSizeGb"), 0);
        c.createdAt = parseLong(p.getProperty("createdAt"), c.createdAt);
        c.shareFolder = Boolean.parseBoolean(p.getProperty("shareFolder", "false"));
        c.shareWritable = Boolean.parseBoolean(p.getProperty("shareWritable", "false"));
        return c;
    }

    /** Short technical line shown under the name, e.g. "x86_64 · 4 GB · ISO". */
    public String summary() {
        StringBuilder sb = new StringBuilder(arch.label).append(" · ");
        if (ramMb % 1024 == 0) sb.append(ramMb / 1024).append(" GB");
        else sb.append(ramMb).append(" MB");
        sb.append(" · ").append(cpus).append(" CPU");
        if (isoPath != null && !isoPath.isEmpty()) {
            String lower = isoPath.toLowerCase(java.util.Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                sb.append(" · ").append(lower.substring(dot + 1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
