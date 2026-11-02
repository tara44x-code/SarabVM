package com.sarab.vm.core;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import org.junit.Test;

public class StoragePathsTest {

    @Test
    public void treeIdsBecomePaths() {
        assertEquals("/storage/emulated/0/Download/Sarab", StoragePaths.fromTreeDocumentId("primary:Download/Sarab"));
        assertEquals("/storage/emulated/0", StoragePaths.fromTreeDocumentId("primary:"));
        assertEquals("/storage/1A2B-3C4D/Movies", StoragePaths.fromTreeDocumentId("1a2b-3c4d:Movies/"));
        assertEquals(null, StoragePaths.fromTreeDocumentId("downloads"));
        assertEquals(null, StoragePaths.fromTreeDocumentId("msf:12"));
        assertEquals(null, StoragePaths.fromTreeDocumentId(null));
    }

    @Test
    public void folderSizeAddsFiles() throws Exception {
        File dir = Files.createTempDirectory("share").toFile();
        File sub = new File(dir, "a");
        sub.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "x"))) { o.write(new byte[100]); }
        try (FileOutputStream o = new FileOutputStream(new File(sub, "y"))) { o.write(new byte[50]); }
        assertEquals(150, StoragePaths.folderSize(dir));
    }
}
