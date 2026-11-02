package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.Test;

public class VmConfigTest {

    @Test
    public void roundTripThroughProperties() {
        VmConfig c = new VmConfig();
        c.name = "Windows PE";
        c.osType = VmConfig.OsType.WINDOWS;
        c.arch = VmConfig.Arch.X86_64;
        c.ramMb = 3072;
        c.cpus = 4;
        c.isoPath = "/data/isos/winpe.iso";
        c.diskPath = "/data/disks/a.qcow2";
        c.diskSizeGb = 20;

        Properties p = c.toProperties();
        VmConfig back = VmConfig.fromProperties(p);

        assertEquals(c.id, back.id);
        assertEquals("Windows PE", back.name);
        assertEquals(VmConfig.OsType.WINDOWS, back.osType);
        assertEquals(VmConfig.Arch.X86_64, back.arch);
        assertEquals(3072, back.ramMb);
        assertEquals(4, back.cpus);
        assertEquals("/data/isos/winpe.iso", back.isoPath);
        assertEquals("/data/disks/a.qcow2", back.diskPath);
        assertEquals(20, back.diskSizeGb);
        assertEquals(c.createdAt, back.createdAt);
    }

    @Test
    public void badValuesFallBackToDefaults() {
        Properties p = new Properties();
        p.setProperty("name", "x");
        p.setProperty("osType", "BEOS");
        p.setProperty("arch", "mips");
        p.setProperty("ramMb", "lots");
        VmConfig c = VmConfig.fromProperties(p);
        assertEquals(VmConfig.OsType.OTHER, c.osType);
        assertEquals(VmConfig.Arch.X86_64, c.arch);
        assertEquals(2048, c.ramMb);
    }

    @Test
    public void validation() {
        VmConfig c = new VmConfig();
        c.name = "";
        c.isoPath = "";
        c.diskSizeGb = 0;
        List<String> errors = c.validate();
        assertEquals(2, errors.size());

        c.name = "Ubuntu";
        c.isoPath = "/x/ubuntu.iso";
        assertTrue(c.validate().isEmpty());

        c.cpus = 9;
        assertFalse(c.validate().isEmpty());
    }

    @Test
    public void summaryLine() {
        VmConfig c = new VmConfig();
        c.ramMb = 4096;
        c.cpus = 4;
        c.isoPath = "/x/ubuntu.iso";
        assertEquals("x86_64 · 4 GB · 4 CPU · ISO", c.summary());
        c.ramMb = 1536;
        c.isoPath = "";
        assertEquals("x86_64 · 1536 MB · 4 CPU", c.summary());
    }
}
