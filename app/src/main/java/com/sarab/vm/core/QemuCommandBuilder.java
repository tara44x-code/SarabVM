package com.sarab.vm.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link VmConfig} into the QEMU command line. Pure Java so the exact
 * arguments can be unit-tested without a phone.
 *
 * QEMU binaries are shipped inside the APK's native library folder, renamed to
 * lib&lt;name&gt;.so, because Android only allows executing files from there.
 */
public final class QemuCommandBuilder {

    public static final int VNC_BASE_PORT = 5900;
    public static final int QMP_BASE_PORT = 4444;
    /**
     * Software CPU emulation. QEMU picks multi-threading itself where it is safe;
     * a 256 MB translation cache keeps memory use reasonable on phones.
     */
    static final String TCG = "tcg,tb-size=256";
    /** Performance mode: a 1 GB cache means code is translated less often. */
    static final String TCG_FAST = "tcg,tb-size=1024";

    static String tcg(VmConfig c) {
        String base = c.fastMode ? TCG_FAST : TCG;
        // x86 on ARM: QEMU only uses one host core unless asked, because the memory models differ
        boolean x86 = c.arch != VmConfig.Arch.AARCH64;
        return x86 && c.allCores ? base + ",thread=multi" : base;
    }

    /** virtio disk/network: much faster than IDE/e1000, built into Linux; Windows would need drivers. */
    static boolean useVirtio(VmConfig c) {
        return c.fastMode && c.osType == VmConfig.OsType.LINUX;
    }

    private final File nativeLibDir;
    private final File dataDir;

    /**
     * @param nativeLibDir the app's nativeLibraryDir (holds libqemu-*.so)
     * @param dataDir      folder with QEMU firmware files (bios-256k.bin, edk2, keymaps…)
     */
    public QemuCommandBuilder(File nativeLibDir, File dataDir) {
        this.nativeLibDir = nativeLibDir;
        this.dataDir = dataDir;
    }

    public static String libFileName(String binary) {
        return "lib" + binary + ".so";
    }

    public File binaryFor(VmConfig.Arch arch) {
        return new File(nativeLibDir, libFileName(arch.qemuBinary));
    }

    public File qemuImgBinary() {
        return new File(nativeLibDir, libFileName("qemu-img"));
    }

    public static int vncPort(int display) {
        return VNC_BASE_PORT + display;
    }

    public static int qmpPort(int display) {
        return QMP_BASE_PORT + display;
    }

    /** Command that creates the virtual hard disk. */
    public List<String> createDiskCommand(String diskPath, int sizeGb) {
        List<String> cmd = new ArrayList<>();
        cmd.add(qemuImgBinary().getAbsolutePath());
        cmd.add("create");
        cmd.add("-f");
        cmd.add("qcow2");
        cmd.add(diskPath);
        cmd.add(sizeGb + "G");
        return cmd;
    }

    /** Full command line to start the machine with its screen on VNC display {@code display}. */
    public List<String> build(VmConfig c, EmulatorSettings s, int display) {
        return build(c, s, display, null);
    }

    /**
     * @param audioPipe when the machine has sound: a named pipe the app reads. QEMU's "wav"
     *                  backend writes the sound card's output there as a WAV stream.
     */
    public List<String> build(VmConfig c, EmulatorSettings s, int display, String audioPipe) {
        List<String> a = new ArrayList<>();
        a.add(binaryFor(c.arch).getAbsolutePath());
        a.add("-L");
        a.add(dataDir.getAbsolutePath());
        a.add("-name");
        a.add(safeName(c.name));
        a.add("-m");
        a.add(Integer.toString(c.ramMb));
        a.add("-smp");
        a.add(Integer.toString(c.cpus));

        boolean kvm = s.accel == EmulatorSettings.Accel.KVM && c.arch == VmConfig.Arch.AARCH64;

        if (c.arch == VmConfig.Arch.AARCH64) {
            addArm(a, c, s, kvm);
        } else {
            addX86(a, c, s);
        }

        if (c.sound && audioPipe != null) {
            a.add("-audiodev");
            a.add("wav,id=snd0,path=" + escape(audioPipe));
            a.add("-device");
            a.add("intel-hda");
            a.add("-device");
            a.add("hda-output,audiodev=snd0");
        }
        if (c.snapshotSaved && c.canSaveState()) {
            a.add("-loadvm");
            a.add(VmConfig.SNAPSHOT_NAME);
        }
        a.add("-vnc");
        a.add("127.0.0.1:" + display);
        a.add("-qmp");
        a.add("tcp:127.0.0.1:" + qmpPort(display) + ",server=on,wait=off");
        return a;
    }

    private void addX86(List<String> a, VmConfig c, EmulatorSettings s) {
        a.add("-machine");
        a.add("pc");
        a.add("-accel");
        a.add(tcg(c));
        a.add("-cpu");
        a.add(c.arch == VmConfig.Arch.I386 ? "pentium3" : "max");
        a.add("-vga");
        a.add("std");
        a.add("-usb");
        a.add("-device");
        a.add("usb-tablet");
        if (c.osType == VmConfig.OsType.WINDOWS) {
            a.add("-rtc");
            a.add("base=localtime"); // Windows expects the hardware clock in local time
        }

        boolean hasDisk = notEmpty(c.diskPath);
        if (hasDisk) {
            a.add("-drive");
            a.add("file=" + escape(c.diskPath) + (useVirtio(c) ? ",if=virtio" : ",if=ide,index=0,media=disk") + ",format=qcow2");
        }
        if (notEmpty(c.isoPath)) {
            if (isOpticalImage(c.isoPath)) {
                a.add("-drive");
                a.add("file=" + escape(c.isoPath) + ",if=ide,index=2,media=cdrom,readonly=on");
            } else {
                a.add("-drive");
                a.add("file=" + escape(c.isoPath) + ",if=ide,index=1,media=disk,format=" + diskFormat(c.isoPath));
            }
        }
        if (sharing(c, s)) {
            a.add("-drive");
            a.add(sharedDrive(c, s) + ",if=ide,index=3,media=disk");
        }
        a.add("-boot");
        a.add(notEmpty(c.isoPath) && isOpticalImage(c.isoPath) ? "order=dc,menu=off" : "order=c,menu=off");

        if (s.network) {
            a.add("-netdev");
            a.add("user,id=net0" + PortForwards.parse(c.portForwards).toQemuSuffix());
            a.add("-device");
            a.add(useVirtio(c) ? "virtio-net-pci,netdev=net0" : "e1000,netdev=net0");
        } else {
            a.add("-nic");
            a.add("none");
        }
    }

    private void addArm(List<String> a, VmConfig c, EmulatorSettings s, boolean kvm) {
        a.add("-machine");
        a.add("virt");
        a.add("-accel");
        a.add(kvm ? "kvm" : tcg(c));
        a.add("-cpu");
        a.add(kvm ? "host" : "max");
        a.add("-bios");
        a.add(new File(dataDir, "edk2-aarch64-code.fd").getAbsolutePath());
        a.add("-device");
        a.add("virtio-gpu-pci");
        a.add("-device");
        a.add("qemu-xhci");
        a.add("-device");
        a.add("usb-tablet");
        a.add("-device");
        a.add("usb-kbd");
        a.add("-device");
        a.add("virtio-scsi-pci,id=scsi0");

        int bootIndex = 0;
        if (notEmpty(c.isoPath)) {
            if (isOpticalImage(c.isoPath)) {
                a.add("-drive");
                a.add("file=" + escape(c.isoPath) + ",if=none,id=cd0,media=cdrom,readonly=on");
                a.add("-device");
                a.add("scsi-cd,drive=cd0,bus=scsi0.0,bootindex=" + bootIndex++);
            } else {
                a.add("-drive");
                a.add("file=" + escape(c.isoPath) + ",if=none,id=img0,format=" + diskFormat(c.isoPath));
                a.add("-device");
                a.add("virtio-blk-pci,drive=img0,bootindex=" + bootIndex++);
            }
        }
        if (notEmpty(c.diskPath)) {
            a.add("-drive");
            a.add("file=" + escape(c.diskPath) + ",if=none,id=hd0,format=qcow2");
            a.add("-device");
            a.add("virtio-blk-pci,drive=hd0,bootindex=" + bootIndex);
        }
        if (sharing(c, s)) {
            a.add("-drive");
            a.add(sharedDrive(c, s) + ",if=none,id=shared0");
            a.add("-device");
            a.add("usb-storage,drive=shared0");
        }

        if (s.network) {
            a.add("-netdev");
            a.add("user,id=net0" + PortForwards.parse(c.portForwards).toQemuSuffix());
            a.add("-device");
            a.add("virtio-net-pci,netdev=net0");
        } else {
            a.add("-nic");
            a.add("none");
        }
    }

    static boolean sharing(VmConfig c, EmulatorSettings s) {
        return c.shareFolder && notEmpty(s.sharedFolderPath);
    }

    /**
     * The shared phone folder as a virtual FAT disk (QEMU "vvfat").
     * Read-only unless the machine is allowed to write.
     */
    static String sharedDrive(VmConfig c, EmulatorSettings s) {
        String dir = escape(s.sharedFolderPath);
        return c.shareWritable
                ? "file=fat:rw:" + dir + ",format=raw"
                : "file=fat:" + dir + ",format=raw,readonly=on";
    }

    /** .iso files boot as a CD; .img/.raw/.qcow2 are attached as a disk. */
    public static boolean isOpticalImage(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".iso");
    }

    static String diskFormat(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".qcow2")) return "qcow2";
        if (p.endsWith(".vdi")) return "vdi";
        if (p.endsWith(".vmdk")) return "vmdk";
        if (p.endsWith(".vhd")) return "vpc";
        return "raw";
    }

    /** In QEMU option strings a comma must be written twice. */
    static String escape(String path) {
        return path.replace(",", ",,");
    }

    private static String safeName(String name) {
        String n = name == null ? "vm" : name.trim();
        if (n.isEmpty()) n = "vm";
        return n.replace(",", " ");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
