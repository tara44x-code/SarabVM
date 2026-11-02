package com.sarab.vm.data;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/** Permission to read/write the phone's storage, needed for the shared folder. */
public final class StorageAccess {
    private StorageAccess() { }

    private static final String WRITE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final String READ = "android.permission.READ_EXTERNAL_STORAGE";

    public static boolean has(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens the system screen (Android 11+) or the permission prompt (older). */
    public static void request(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{READ, WRITE}, 42);
        }
    }
}
