package com.sarab.vm.ui;

import com.sarab.vm.core.Lang;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.StorageAccess;
import com.sarab.vm.display.DisplayActivity;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.IOException;

/** Starts a machine from any screen: permissions, progress dialog, errors, then the display. */
final class VmStarter {
    private VmStarter() { }

    private static final String NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS";

    /** @param after runs on the UI thread after the display opened (may be null) */
    static void start(Activity a, VmConfig c, Runnable after) {
        if (c.shareFolder && !StorageAccess.has(a)) {
            new AlertDialog.Builder(a)
                    .setTitle(Lang.t("إذن الوصول إلى الملفات"))
                    .setMessage(Lang.t("هذا النظام يستخدم المجلد المشترك مع الهاتف، ويحتاج التطبيق إذنًا للوصول إلى الملفات. فعّل الإذن في الشاشة التالية، ثم ارجع وشغّل النظام مرة أخرى."))
                    .setPositiveButton(Lang.t("السماح"), (d, w) -> StorageAccess.request(a))
                    .setNegativeButton(Lang.t("إلغاء"), null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{NOTIFICATION_PERMISSION}, 1);
        }
        AlertDialog progress = progressDialog(a, Lang.t("جارٍ تجهيز ") + c.name + "…");
        progress.show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            try {
                VmManager.get(a).start(c);
                main.post(() -> {
                    progress.dismiss();
                    a.startActivity(VmManager.withVm(new Intent(a, DisplayActivity.class), c.id));
                    if (after != null) after.run();
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    progress.dismiss();
                    if (a.isFinishing() || a.isDestroyed()) return;
                    new AlertDialog.Builder(a)
                            .setTitle(Lang.t("تعذر التشغيل"))
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton(Lang.t("حسنًا"), null)
                            .setNeutralButton(Lang.t("السجل"), (d, w) -> LogActivity.open(a, c.id))
                            .show();
                });
            }
        }, "start-vm");
        t.start();
    }

    static AlertDialog progressDialog(Activity a, String message) {
        LinearLayout box = Ui.row(a);
        box.setLayoutDirection(Ui.dir());
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 24), Ui.dp(a, 24), Ui.dp(a, 24));
        ProgressBar bar = new ProgressBar(a);
        box.addView(bar, Ui.lp(Ui.dp(a, 36), Ui.dp(a, 36)));
        TextView t = Ui.text(a, message, 15, Ui.TEXT, false);
        t.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        box.addView(t);
        return new AlertDialog.Builder(a).setView(box).setCancelable(false).create();
    }
}
