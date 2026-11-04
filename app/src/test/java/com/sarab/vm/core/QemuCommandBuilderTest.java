package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class QemuCommandBuilderTest {

    private final QemuCommandBuilder builder =
            new QemuCommandBuilder(new File("/app/lib"), new File("/files/qemu-data"));

    private static String joined(List<String> args) {
        return String.join(" ", args);
    }

    /** Value that follows {@code flag}, failing if the flag is missing. */
    private static String after(List<String> args, String flag) {
        int i = args.indexOf(flag);
        assertTrue("missing " + flag + " in " + args, i >= 0 && i + 1 < args.size());
        return args.get(i + 1);
    }

    @Test
    public void windowsIsoOnX86() {
        VmConfig c = new VmConfig();
        c.name = "Windows PE";
        c.osType = VmConfig.OsType.WINDOWS;
        c.arch = VmConfig.Arch.X86_64;
        c.ramMb = 2048;
        c.cpus = 2;
        c.isoPath = "/files/isos/winpe.iso";
        EmulatorSettings s = new EmulatorSettings();

        List<String> a = builder.build(c, s, 1);

        assertEquals("/app/lib/libqemu-system-x86_64.so", a.get(0));
        assertEquals("/files/qemu-data", after(a, "-L"));
        assertEquals("2048", after(a, "-m"));
        assertEquals("2", after(a, "-smp"));
        assertEquals("pc", after(a, "-machine"));
        assertEquals("tcg,tb-size=1024", after(a, "-accel"));
        assertEquals("std", after(a, "-vga"));
        assertEquals("127.0.0.1:1", after(a, "-vnc"));
        assertEquals("tcp:127.0.0.1:4445,server=on,wait=off", after(a, "-qmp"));
        assertEquals("order=dc,menu=off", after(a, "-boot"));
        String all = joined(a);
        assertTrue(all.contains("file=/files/isos/winpe.iso,if=ide,index=2,media=cdrom,readonly=on"));
        assertTrue(all.contains("usb-tablet"));
        assertTrue(all.contains("e1000,netdev=net0"));
        assertFalse("no disk configured", all.contains("media=disk"));
        assertEquals("base=localtime", after(a, "-rtc"));
    }

    @Test
    public void linuxWithDiskAndNoNetwork() {
        VmConfig c = new VmConfig();
        c.name = "Ubuntu, test";
        c.isoPath = "/files/isos/ubuntu.iso";
        c.diskPath = "/files/disks/u,1.qcow2";
        c.fastMode = false; // a machine from before performance mode keeps the IDE disk
        EmulatorSettings s = new EmulatorSettings();
        s.network = false;

        List<String> a = builder.build(c, s, 0);
        String all = joined(a);
        assertEquals("Ubuntu  test", after(a, "-name"));
        assertTrue("commas doubled", all.contains("file=/files/disks/u,,1.qcow2,if=ide,index=0,media=disk,format=qcow2"));
        assertEquals("none", after(a, "-nic"));
        assertFalse("linux keeps UTC clock", a.contains("-rtc"));
        assertFalse(all.contains("-netdev"));
        assertEquals("127.0.0.1:0", after(a, "-vnc"));
    }

    @Test
    public void rawImageBootsAsDisk() {
        VmConfig c = new VmConfig();
        c.name = "Android x86";
        c.isoPath = "/files/isos/android.img";
        List<String> a = builder.build(c, new EmulatorSettings(), 2);
        assertTrue(joined(a).contains("file=/files/isos/android.img,if=ide,index=1,media=disk,format=raw"));
        assertEquals("order=c,menu=off", after(a, "-boot"));
    }

    @Test
    public void i386UsesOlderCpu() {
        VmConfig c = new VmConfig();
        c.name = "old";
        c.arch = VmConfig.Arch.I386;
        c.isoPath = "/a.iso";
        List<String> a = builder.build(c, new EmulatorSettings(), 0);
        assertEquals("/app/lib/libqemu-system-x86_64.so", a.get(0));
        assertEquals("pentium3", after(a, "-cpu"));
    }

    @Test
    public void armWithKvm() {
        VmConfig c = new VmConfig();
        c.name = "Debian";
        c.arch = VmConfig.Arch.AARCH64;
        c.isoPath = "/files/isos/debian.iso";
        c.diskPath = "/files/disks/d.qcow2";
        EmulatorSettings s = new EmulatorSettings();
        s.accel = EmulatorSettings.Accel.KVM;

        List<String> a = builder.build(c, s, 0);
        String all = joined(a);
        assertEquals("/app/lib/libqemu-system-aarch64.so", a.get(0));
        assertEquals("virt", after(a, "-machine"));
        assertEquals("kvm", after(a, "-accel"));
        assertEquals("host", after(a, "-cpu"));
        assertEquals("/files/qemu-data/edk2-aarch64-code.fd", after(a, "-bios"));
        assertTrue(all.contains("scsi-cd,drive=cd0,bus=scsi0.0,bootindex=0"));
        assertTrue(all.contains("virtio-blk-pci,drive=hd0,bootindex=1"));
        assertTrue(all.contains("virtio-net-pci,netdev=net0"));
    }

    @Test
    public void kvmIgnoredForX86Guests() {
        VmConfig c = new VmConfig();
        c.name = "w";
        c.isoPath = "/w.iso";
        EmulatorSettings s = new EmulatorSettings();
        s.accel = EmulatorSettings.Accel.KVM;
        List<String> a = builder.build(c, s, 0);
        assertEquals("tcg,tb-size=1024", after(a, "-accel"));
    }

    @Test
    public void sharedFolderX86ReadOnlyAndWritable() {
        VmConfig c = new VmConfig();
        c.name = "w";
        c.isoPath = "/w.iso";
        EmulatorSettings s = new EmulatorSettings();
        s.sharedFolderPath = "/storage/emulated/0/Sarab";
        assertFalse("off by default", joined(builder.build(c, s, 0)).contains("fat:"));

        c.shareFolder = true;
        String ro = joined(builder.build(c, s, 0));
        assertTrue(ro.contains("-device usb-ehci,id=ehci"));
        assertTrue(ro.contains("file=fat:/storage/emulated/0/Sarab,format=raw,readonly=on,if=none,id=shared0"));
        assertTrue(ro.contains("-device usb-storage,bus=ehci.0,drive=shared0"));
        assertFalse("never a read-only IDE disk", ro.contains("readonly=on,if=ide"));

        c.shareWritable = true;
        assertTrue(joined(builder.build(c, s, 0)).contains(
                "file=fat:rw:/storage/emulated/0/Sarab,format=raw,if=none,id=shared0"));
    }

    @Test
    public void sharedFolderArmUsesUsbStorage() {
        VmConfig c = new VmConfig();
        c.name = "a";
        c.arch = VmConfig.Arch.AARCH64;
        c.isoPath = "/a.iso";
        c.shareFolder = true;
        EmulatorSettings s = new EmulatorSettings();
        s.sharedFolderPath = "/sdcard/Sarab";
        String all = joined(builder.build(c, s, 0));
        assertTrue(all.contains("file=fat:/sdcard/Sarab,format=raw,readonly=on,if=none,id=shared0"));
        assertTrue(all.contains("usb-storage,drive=shared0"));
    }

    @Test
    public void performanceMode() {
        VmConfig c = new VmConfig();
        c.name = "deb";
        c.osType = VmConfig.OsType.LINUX;
        c.isoPath = "/d.iso";
        c.diskPath = "/d.qcow2";
        assertTrue("new machines are fast", c.fastMode);
        List<String> a = builder.build(c, new EmulatorSettings(), 0);
        String all = joined(a);
        assertEquals("tcg,tb-size=1024", after(a, "-accel"));
        assertTrue(all.contains("file=/d.qcow2,if=virtio,format=qcow2"));
        assertTrue(all.contains("virtio-net-pci,netdev=net0"));

        c.allCores = true;
        assertEquals("tcg,tb-size=1024,thread=multi", after(builder.build(c, new EmulatorSettings(), 0), "-accel"));

        c.osType = VmConfig.OsType.WINDOWS;
        all = joined(builder.build(c, new EmulatorSettings(), 0));
        assertTrue("windows keeps IDE (no virtio drivers)", all.contains("if=ide,index=0,media=disk,format=qcow2"));
        assertTrue(all.contains("e1000,netdev=net0"));

        c.fastMode = false;
        c.allCores = false;
        assertEquals("tcg,tb-size=256", after(builder.build(c, new EmulatorSettings(), 0), "-accel"));

        // ARM guests are already multi-threaded by QEMU; allCores does not change them
        VmConfig arm = new VmConfig();
        arm.name = "a";
        arm.arch = VmConfig.Arch.AARCH64;
        arm.isoPath = "/a.iso";
        arm.allCores = true;
        assertEquals("tcg,tb-size=1024", after(builder.build(arm, new EmulatorSettings(), 0), "-accel"));

        // machines saved before this version load with performance mode off
        java.util.Properties old = c.toProperties();
        old.remove("fastMode");
        assertFalse(VmConfig.fromProperties(old).fastMode);
        c.fastMode = true;
        assertTrue(VmConfig.fromProperties(c.toProperties()).fastMode);
    }

    @Test
    public void installHelpers() {
        assertEquals(32, VmConfig.suggestedInstallDiskGb(VmConfig.OsType.WINDOWS));
        assertEquals(16, VmConfig.suggestedInstallDiskGb(VmConfig.OsType.LINUX));
        VmConfig c = new VmConfig();
        c.isoPath = "/x.iso";
        assertFalse(c.looksInstalledWithIso());
        c.diskPath = "/x.qcow2";
        assertTrue(c.looksInstalledWithIso());
        c.isoPath = "";   // "installation finished": boots from the disk
        c.name = "x";
        assertTrue(c.validate().isEmpty());
        List<String> a = builder.build(c, new EmulatorSettings(), 0);
        assertEquals("order=c,menu=off", after(a, "-boot"));
        assertFalse(joined(a).contains("media=cdrom"));
    }

    @Test
    public void diskCreation() {
        assertEquals(
                Arrays.asList("/app/lib/libqemu-img.so", "create", "-f", "qcow2", "/d/x.qcow2", "16G"),
                builder.createDiskCommand("/d/x.qcow2", 16));
    }

    @Test
    public void formats() {
        assertEquals("qcow2", QemuCommandBuilder.diskFormat("A.QCOW2"));
        assertEquals("vpc", QemuCommandBuilder.diskFormat("a.vhd"));
        assertEquals("raw", QemuCommandBuilder.diskFormat("a.img"));
        assertTrue(QemuCommandBuilder.isOpticalImage("X.ISO"));
        assertFalse(QemuCommandBuilder.isOpticalImage("x.img"));
    }
}
