package com.sarab.vm.data;

import com.sarab.vm.core.Lang;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.sarab.vm.core.QemuCommandBuilder;
import com.sarab.vm.core.VmConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Where QEMU lives on the phone: binaries in the APK's native library folder,
 * firmware files (BIOS, EDK2, keymaps) copied once from assets/qemu-data.
 */
public final class QemuEnvironment {

    private static final String ASSET_DIR = "qemu-data";

    private final Context context;
    private final File nativeLibDir;
    private final File dataDir;

    public QemuEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
        this.dataDir = new File(context.getFilesDir(), ASSET_DIR);
    }

    public File nativeLibDir() {
        return nativeLibDir;
    }

    public File dataDir() {
        return dataDir;
    }

    public QemuCommandBuilder builder() {
        return new QemuCommandBuilder(nativeLibDir, dataDir);
    }

    public boolean hasEngine(VmConfig.Arch arch) {
        return builder().binaryFor(arch).isFile();
    }

    public boolean hasQemuImg() {
        return builder().qemuImgBinary().isFile();
    }

    /** True when the phone's CPU can run the bundled arm64 binaries. */
    public static boolean deviceSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /** Copies firmware files from the APK the first time (and after each app update). Blocking. */
    public synchronized void ensureInstalled() throws IOException {
        String version = appVersion();
        File marker = new File(dataDir, ".installed");
        if (marker.isFile() && version.equals(readText(marker))) return;

        deleteRecursive(dataDir);
        if (!dataDir.mkdirs() && !dataDir.isDirectory()) throw new IOException(Lang.t("تعذر إنشاء ") + dataDir);
        copyAssetDir(context.getAssets(), ASSET_DIR, dataDir);
        try (OutputStream o = new FileOutputStream(marker)) {
            o.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyAssetDir(AssetManager am, String path, File target) throws IOException {
        String[] children = am.list(path);
        if (children == null) return;
        for (String child : children) {
            String childPath = path + "/" + child;
            String[] grand = am.list(childPath);
            if (grand != null && grand.length > 0) {
                File sub = new File(target, child);
                //noinspection ResultOfMethodCallIgnored
                sub.mkdirs();
                copyAssetDir(am, childPath, sub);
            } else {
                try (InputStream in = am.open(childPath); OutputStream out = new FileOutputStream(new File(target, child))) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
    }

    private String appVersion() {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName + "/" + pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
