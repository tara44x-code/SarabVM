package com.sarab.vm.core;

import java.io.File;

/** Helpers for the shared phone folder. Pure Java. */
public final class StoragePaths {
    private StoragePaths() { }

    /** QEMU's virtual FAT disk (FAT16) cannot hold more than about 500 MB. */
    public static final long SHARED_LIMIT_BYTES = 480L * 1024 * 1024;

    /**
     * Turns a folder picked with the system file picker ("primary:Download/Sarab") into a
     * real path ("/storage/emulated/0/Download/Sarab"). Returns null for unsupported places.
     */
    public static String fromTreeDocumentId(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon <= 0) return null;
        String volume = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        String root;
        if ("primary".equalsIgnoreCase(volume)) {
            root = "/storage/emulated/0";
        } else if (volume.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
            root = "/storage/" + volume.toUpperCase(java.util.Locale.ROOT);
        } else {
            return null;
        }
        return rel.isEmpty() ? root : root + "/" + rel;
    }

    /** Total size of a folder's files (follows no symlinks deeper than the folder itself). */
    public static long folderSize(File dir) {
        if (dir.isFile()) return dir.length();
        long sum = 0;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) sum += folderSize(k);
        return sum;
    }
}
