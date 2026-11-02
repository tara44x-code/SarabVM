package com.sarab.vm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sarab.vm.core.Downloader;
import com.sarab.vm.core.OsCatalog;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.ImageStore;
import com.sarab.vm.data.VmRepository;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ready-made systems downloaded from their official websites. */
public final class OsStoreActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        TextView back = Ui.iconButton(this, "›", "رجوع", Ui.TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.text(this, "أنظمة جاهزة", 22, Ui.TEXT, true);
        title.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = Ui.column(this);
        list.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 32));
        list.addView(Ui.text(this, "تُحمّل من المواقع الرسمية لكل نظام، ويُتحقّق من سلامة الملف، ثم يُنشأ النظام تلقائيًا.",
                13, Ui.MUTED, false), Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));
        for (OsCatalog.Entry e : OsCatalog.entries()) list.addView(card(e));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private View card(OsCatalog.Entry e) {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.clickable(this, Ui.SURFACE, 18, Ui.BORDER, 1));
        card.setClickable(true);
        card.setOnClickListener(v -> confirm(e));

        LinearLayout top = Ui.row(this);
        top.addView(Ui.osBadge(this, VmConfig.OsType.LINUX, 44));
        LinearLayout info = Ui.column(this);
        info.setPaddingRelative(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        info.addView(Ui.text(this, e.title, 16, Ui.TEXT, true));
        info.addView(Ui.mono(this, e.arch.label + " · " + e.approxSize + " · " + (e.ramMb >= 1024 ? e.ramMb / 1024 + " GB" : e.ramMb + " MB"), 12, Ui.MUTED));
        top.addView(info, Ui.weight(1));
        TextView get = Ui.text(this, "تحميل", 14, Ui.ON_ACCENT, true);
        get.setGravity(Gravity.CENTER);
        get.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        get.setBackground(Ui.clickable(this, Ui.ACCENT, 12, 0, 0));
        get.setClickable(true);
        get.setOnClickListener(v -> confirm(e));
        top.addView(get, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)));
        card.addView(top);
        card.addView(Ui.text(this, e.description, 13, Ui.TEXT_2, false),
                Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(p);
        return card;
    }

    private void confirm(OsCatalog.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("تحميل " + e.title + "؟")
                .setMessage("الحجم تقريبًا " + e.approxSize + ". يُفضّل استخدام Wi-Fi. أبقِ هذه الشاشة مفتوحة حتى ينتهي التحميل.")
                .setPositiveButton("تحميل", (d, w) -> download(e))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void download(OsCatalog.Entry e) {
        LinearLayout box = Ui.column(this);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        TextView label = Ui.text(this, "جارٍ البحث عن أحدث إصدار…", 14, Ui.TEXT, false);
        box.addView(label);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        box.addView(bar, Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        TextView amount = Ui.mono(this, "", 12, Ui.MUTED);
        box.addView(amount);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("إلغاء", (d, w) -> cancelled.set(true))
                .create();
        dialog.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final long[] lastUi = {0};
        Thread t = new Thread(() -> {
            try {
                OsCatalog.Resolved r = OsCatalog.resolve(e, Downloader::fetchText);
                File target = new File(new ImageStore(this).directory(), r.fileName);
                main.post(() -> {
                    label.setText("جارٍ تحميل " + r.fileName);
                    bar.setIndeterminate(false);
                });
                if (!target.isFile()) {
                    Downloader.download(r.url, target, r.checksum, r.algorithm, (done, total) -> {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] > 250) {
                            lastUi[0] = now;
                            main.post(() -> {
                                if (total > 0) bar.setProgress((int) (done * 1000 / total));
                                else bar.setIndeterminate(true);
                                amount.setText(Ui.formatSize(done) + (total > 0 ? " / " + Ui.formatSize(total) : ""));
                            });
                        }
                        return !cancelled.get();
                    });
                }
                VmConfig c = e.newConfig(target.getAbsolutePath());
                new VmRepository(this).save(c);
                main.post(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    dialog.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("جاهز ✓")
                            .setMessage("أُنشئ النظام «" + c.name + "». تشغّله الآن؟")
                            .setPositiveButton("تشغيل", (d, w) -> VmStarter.start(this, c, this::finish))
                            .setNegativeButton("لاحقًا", (d, w) -> finish())
                            .show();
                });
            } catch (IOException | RuntimeException ex) {
                main.post(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    dialog.dismiss();
                    if (cancelled.get() || isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("تعذر التحميل")
                            .setMessage((ex.getMessage() == null ? ex.toString() : ex.getMessage())
                                    + "\n\nتأكد من اتصال الإنترنت وحاول مرة أخرى.")
                            .setPositiveButton("حسنًا", null)
                            .show();
                });
            }
        }, "os-download");
        t.start();
    }
}
