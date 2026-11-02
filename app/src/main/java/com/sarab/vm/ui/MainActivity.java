package com.sarab.vm.ui;

import com.sarab.vm.core.Lang;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sarab.vm.R;
import com.sarab.vm.core.PortForwards;
import com.sarab.vm.core.VmArchive;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.ImageStore;
import com.sarab.vm.data.VmRepository;
import com.sarab.vm.display.DisplayActivity;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Home screen: the list of machines (Lang.t("أنظمتي")). */
public final class MainActivity extends Activity implements VmManager.Listener {

    private static final int REQUEST_EXPORT = 31;
    private static final int REQUEST_IMPORT = 32;

    private VmRepository repo;
    private VmManager manager;
    private LinearLayout list;
    private LinearLayout chips;
    private VmConfig.OsType filter; // null = all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this);
        super.onCreate(savedInstanceState);
        repo = new VmRepository(this);
        manager = VmManager.get(this);
        uiStamp = Ui.stamp();
        setContentView(buildUi());
    }

    private String uiStamp;

    @Override
    protected void onResume() {
        super.onResume();
        if (uiStamp != null && !uiStamp.equals(Ui.stamp())) {
            recreate(); // language or theme changed in Settings
            return;
        }
        manager.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        manager.removeListener(this);
        super.onPause();
    }

    @Override
    public void onVmStateChanged() {
        refresh();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(Ui.dir());
        root.setFitsSystemWindows(true);

        LinearLayout content = Ui.column(this);

        // header: logo + name + settings
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setContentDescription(Lang.t("سراب"));
        header.addView(logo, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout brand = Ui.column(this);
        brand.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        brand.addView(Ui.text(this, Lang.t("سراب"), 22, Ui.TEXT, true));
        brand.addView(Ui.mono(this, "Sarab VM", 12, Ui.MUTED));
        brand.addView(Ui.text(this, Lang.t("المطوّر: مهند سات لنك"), 11, Ui.SAND, false));
        header.addView(brand, Ui.weight(1));
        TextView settings = Ui.iconButton(this, "⚙", Lang.t("الإعدادات"), Ui.TEXT);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);
        content.addView(header);

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        titles.addView(Ui.text(this, Lang.t("أنظمتي"), 26, Ui.TEXT, true));
        titles.addView(Ui.text(this, Lang.t("شغّل أي نظام من ملف صورة ISO أو IMG على هاتفك"), 14, Ui.MUTED, false));
        content.addView(titles);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(this);
        chips.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 6));
        chipScroll.addView(chips);
        content.addView(chipScroll);
        buildChips();

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 170));
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // floating "new system" button
        TextView fab = Ui.text(this, Lang.t("+  نظام جديد"), 16, Ui.ON_SAND, true);
        fab.setGravity(Gravity.CENTER);
        fab.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
        fab.setBackground(Ui.clickable(this, Ui.SAND, 18, 0, 0));
        fab.setElevation(Ui.dp(this, 6));
        fab.setClickable(true);
        fab.setOnClickListener(v -> chooseNewSource());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 56),
                Gravity.BOTTOM | Gravity.LEFT);
        fp.leftMargin = Ui.dp(this, 20);
        fp.bottomMargin = Ui.dp(this, 96);
        root.addView(fab, fp);

        // bottom navigation
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.NAV);
        nav.addView(navItem("▦", Lang.t("الأنظمة"), true, null), Ui.weight(1));
        nav.addView(navItem("◎", Lang.t("ملفات الصور"), false, v -> showImages()), Ui.weight(1));
        nav.addView(navItem("☰", Lang.t("الإعدادات"), false,
                v -> startActivity(new Intent(this, SettingsActivity.class))), Ui.weight(1));
        root.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 76), Gravity.BOTTOM));
        return root;
    }

    private void chooseNewSource() {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("نظام جديد"))
                .setItems(new String[]{Lang.t("من ملف ISO أو IMG على هاتفي"), Lang.t("تحميل نظام جاهز (Alpine، Debian، Tiny Core)"),
                        Lang.t("استيراد نظام مصدَّر (.sarab)")}, (d, which) -> {
                    if (which == 0) startActivity(new Intent(this, NewVmActivity.class));
                    else if (which == 1) startActivity(new Intent(this, OsStoreActivity.class));
                    else startImport();
                })
                .show();
    }

    private View navItem(String glyph, String label, boolean active, View.OnClickListener click) {
        TextView t = Ui.text(this, glyph + "\n" + label, 12, active ? Ui.ACCENT : Ui.MUTED, active);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(this, 64));
        if (click != null) {
            t.setBackground(Ui.clickable(this, Ui.NAV, 0, 0, 0));
            t.setOnClickListener(click);
        }
        return t;
    }

    private void buildChips() {
        chips.removeAllViews();
        addChip(Lang.t("الكل"), null);
        for (VmConfig.OsType t : VmConfig.OsType.values()) addChip(Ui.osLabel(t), t);
    }

    private void addChip(String label, VmConfig.OsType type) {
        boolean on = filter == type;
        TextView chip = Ui.text(this, label, 14, on ? Ui.ON_ACCENT : Ui.TEXT_2, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        chip.setBackground(on ? Ui.clickable(this, Ui.ACCENT, 18, 0, 0) : Ui.clickable(this, Ui.BG, 18, Ui.BORDER, 1));
        chip.setClickable(true);
        chip.setOnClickListener(v -> {
            filter = type;
            buildChips();
            refresh();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
        p.setMarginEnd(Ui.dp(this, 8));
        chips.addView(chip, p);
    }

    private void refresh() {
        list.removeAllViews();
        List<VmConfig> all = repo.list();
        int shown = 0;
        for (VmConfig c : all) {
            if (filter != null && c.osType != filter) continue;
            list.addView(card(c));
            shown++;
        }
        if (shown == 0) {
            TextView empty = Ui.text(this, all.isEmpty()
                    ? Lang.t("لا توجد أنظمة بعد.\nاضغط «نظام جديد» واختر ملف ISO، أو حمّل نظامًا جاهزًا.")
                    : Lang.t("لا توجد أنظمة من هذا النوع."), 15, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 60), 0, 0);
            list.addView(empty);
        }
    }

    private View card(VmConfig c) {
        boolean running = manager.isRunning(c.id);
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.clickable(this, Ui.SURFACE, 20, running ? Ui.ACCENT : Ui.BORDER, 1));
        card.setClickable(true);
        card.setOnClickListener(v -> {
            if (manager.isRunning(c.id)) openDisplay(c);
            else startVm(c);
        });
        card.setOnLongClickListener(v -> {
            showOptions(c);
            return true;
        });

        LinearLayout top = Ui.row(this);
        top.addView(Ui.osBadge(this, c.osType, 48));
        LinearLayout info = Ui.column(this);
        info.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        info.addView(Ui.text(this, c.name, 17, Ui.TEXT, true));
        info.addView(Ui.mono(this, c.summary(), 12, Ui.MUTED));
        if (c.snapshotSaved && !running) info.addView(Ui.text(this, Lang.t("◉ حالة محفوظة — يكمل من حيث توقف"), 11, Ui.SAND, true));
        top.addView(info, Ui.weight(1));

        if (running) {
            top.addView(Ui.text(this, Lang.t("● يعمل"), 12, Ui.ACCENT, true));
            card.addView(top);
            LinearLayout actions = Ui.row(this);
            actions.setPadding(0, Ui.dp(this, 14), 0, 0);
            TextView open = Ui.primaryButton(this, Lang.t("فتح الشاشة"));
            open.setTextSize(15);
            open.setMinHeight(Ui.dp(this, 44));
            open.setOnClickListener(v -> openDisplay(c));
            actions.addView(open, Ui.weight(1));
            TextView stop = Ui.iconButton(this, "■", Lang.t("إيقاف"), Ui.SAND);
            stop.setOnClickListener(v -> confirmStop(c));
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) stop.getLayoutParams();
            sp.setMarginStart(Ui.dp(this, 8));
            actions.addView(stop, sp);
            card.addView(actions);
        } else {
            TextView play = Ui.text(this, "▶", 16, Ui.ACCENT, true);
            play.setGravity(Gravity.CENTER);
            play.setBackground(Ui.clickable(this, Ui.SURFACE_2, 22, Ui.BORDER, 1));
            play.setContentDescription(Lang.t("تشغيل ") + c.name);
            play.setClickable(true);
            play.setOnClickListener(v -> startVm(c));
            top.addView(play, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)));
            card.addView(top);
        }

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(p);
        return card;
    }

    // ------------------------------------------------------------ actions

    private void startVm(VmConfig c) {
        VmStarter.start(this, c, null);
    }

    private void openDisplay(VmConfig c) {
        startActivity(VmManager.withVm(new Intent(this, DisplayActivity.class), c.id));
    }

    private void confirmStop(VmConfig c) {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("إيقاف ") + c.name + Lang.t("؟"))
                .setMessage(Lang.t("سيتوقف النظام فورًا وقد تضيع البيانات غير المحفوظة. للإيقاف الآمن افتح الشاشة واضغط زر الطاقة."))
                .setPositiveButton(Lang.t("إيقاف"), (d, w) -> new Thread(() -> manager.forceStop(c.id), "stop-vm").start())
                .setNegativeButton(Lang.t("إلغاء"), null)
                .show();
    }

    private void showOptions(VmConfig c) {
        boolean running = manager.isRunning(c.id);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        PortForwards ports = PortForwards.parse(c.portForwards);
        if (running && !ports.rules.isEmpty()) {
            for (PortForwards.Rule r : ports.rules) {
                labels.add(Lang.t("فتح المنفذ ") + r.hostPort + Lang.t(" في المتصفح"));
                actions.add(() -> openInBrowser(r.hostPort));
            }
        }
        if (!running && c.snapshotSaved) {
            labels.add(Lang.t("تشغيل من جديد (تجاهل الحالة المحفوظة)"));
            actions.add(() -> {
                c.snapshotSaved = false;
                saveQuietly(c);
                startVm(c);
            });
        }
        labels.add(Lang.t("تعديل"));
        actions.add(() -> whenStopped(c, () -> startActivity(VmManager.withVm(new Intent(this, NewVmActivity.class), c.id))));
        labels.add(Lang.t("نسخة مكررة"));
        actions.add(() -> whenStopped(c, () -> duplicate(c)));
        labels.add(Lang.t("تصدير إلى ملف (.sarab)"));
        actions.add(() -> whenStopped(c, () -> askExport(c)));
        labels.add(Lang.t("السجل (رسائل QEMU)"));
        actions.add(() -> LogActivity.open(this, c.id));
        labels.add(Lang.t("حذف"));
        actions.add(() -> whenStopped(c, () -> confirmDelete(c)));
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void whenStopped(VmConfig c, Runnable r) {
        if (manager.isRunning(c.id)) {
            new AlertDialog.Builder(this).setMessage(Lang.t("أوقف النظام أولًا")).setPositiveButton(Lang.t("حسنًا"), null).show();
        } else {
            r.run();
        }
    }

    private void saveQuietly(VmConfig c) {
        try {
            repo.save(c);
        } catch (IOException e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void openInBrowser(int port) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + port)));
        } catch (RuntimeException e) {
            Toast.makeText(this, Lang.t("لا يوجد متصفح"), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ duplicate / export / import

    private void duplicate(VmConfig c) {
        runWithProgress(Lang.t("جارٍ نسخ ") + c.name + "…", progress -> {
            VmConfig copy = VmArchive.duplicate(c, repo.diskFileFor(c).getParentFile(), progress);
            repo.save(copy);
            return Lang.t("أُنشئت «") + copy.name + "»";
        });
    }

    private void askExport(VmConfig c) {
        boolean hasImage = c.isoPath != null && !c.isoPath.isEmpty() && new File(c.isoPath).isFile();
        if (!hasImage) {
            startExport(c, false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("تصدير ") + c.name)
                .setMessage(Lang.t("تضمين ملف الصورة (") + new File(c.isoPath).getName() + Lang.t("، ") + Ui.formatSize(new File(c.isoPath).length())
                        + Lang.t(")؟ مفيد إذا ستنقل النظام لهاتف آخر."))
                .setPositiveButton(Lang.t("مع ملف الصورة"), (d, w) -> startExport(c, true))
                .setNegativeButton(Lang.t("بدونه"), (d, w) -> startExport(c, false))
                .show();
    }

    private VmConfig pendingExport;
    private boolean pendingExportImage;

    private void startExport(VmConfig c, boolean withImage) {
        pendingExport = c;
        pendingExportImage = withImage;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, c.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".sarab");
        try {
            startActivityForResult(i, REQUEST_EXPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, Lang.t("لا يوجد تطبيق لحفظ الملفات"), Toast.LENGTH_LONG).show();
        }
    }

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_IMPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, Lang.t("لا يوجد تطبيق لاختيار الملفات"), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT && pendingExport != null) {
            VmConfig c = pendingExport;
            boolean withImage = pendingExportImage;
            runWithProgress(Lang.t("جارٍ تصدير ") + c.name + "…", progress -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException(Lang.t("تعذر فتح الملف للكتابة"));
                    VmArchive.export(c, withImage, out, progress);
                }
                return Lang.t("صُدّر النظام ✓");
            });
        } else if (requestCode == REQUEST_IMPORT) {
            runWithProgress(Lang.t("جارٍ استيراد النظام…"), progress -> {
                VmConfig c;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException(Lang.t("تعذر فتح الملف"));
                    c = VmArchive.importFrom(in, repo.diskFileFor(new VmConfig()).getParentFile(),
                            new ImageStore(this).directory(), progress);
                }
                repo.save(c);
                return Lang.t("استُورد «") + c.name + "»" + (c.isoPath.isEmpty() ? Lang.t("\nملاحظة: ملف الصورة غير موجود، اختره من «تعديل» إذا احتجته.") : "");
            });
        }
    }

    private interface Job {
        String run(VmArchive.Progress progress) throws IOException;
    }

    /** Runs a long file job with a progress dialog, then refreshes the list. */
    private void runWithProgress(String title, Job job) {
        LinearLayout box = Ui.column(this);
        box.setLayoutDirection(Ui.dir());
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        box.addView(bar);
        TextView amount = Ui.mono(this, "", 12, Ui.MUTED);
        box.addView(amount);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false)
                .setNegativeButton(Lang.t("إلغاء"), (d, w) -> cancelled.set(true)).create();
        dialog.show();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        long[] last = {0};
        new Thread(() -> {
            String result;
            try {
                result = job.run((done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 250) {
                        last[0] = now;
                        main.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) (done * 1000 / total));
                            }
                            amount.setText(Ui.formatSize(done) + (total > 0 ? " / " + Ui.formatSize(total) : ""));
                        });
                    }
                    return !cancelled.get();
                });
            } catch (IOException | RuntimeException e) {
                result = Lang.t("تعذرت العملية: ") + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String msg = result;
            main.post(() -> {
                dialog.dismiss();
                refresh();
                if (!isFinishing() && !isDestroyed() && !cancelled.get()) {
                    new AlertDialog.Builder(this).setMessage(msg).setPositiveButton(Lang.t("حسنًا"), null).show();
                }
            });
        }, "file-job").start();
    }

    private void confirmDelete(VmConfig c) {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("حذف ") + c.name + Lang.t("؟"))
                .setMessage(Lang.t("سيُحذف النظام وقرصه الصلب الوهمي. ملف الصورة (ISO) يبقى في «ملفات الصور»."))
                .setPositiveButton(Lang.t("حذف"), (d, w) -> {
                    repo.delete(c);
                    refresh();
                })
                .setNegativeButton(Lang.t("إلغاء"), null)
                .show();
    }

    private void showImages() {
        ImageStore store = new ImageStore(this);
        List<File> files = store.list();
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(Lang.t("ملفات الصور"))
                    .setMessage(Lang.t("لا توجد ملفات بعد. تُضاف الملفات عند إنشاء نظام جديد."))
                    .setPositiveButton(Lang.t("حسنًا"), null)
                    .show();
            return;
        }
        String[] items = new String[files.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = files.get(i).getName() + "  ·  " + Ui.formatSize(files.get(i).length());
        }
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("ملفات الصور (المس للحذف)"))
                .setItems(items, (d, which) -> {
                    File f = files.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(Lang.t("حذف ") + f.getName() + Lang.t("؟"))
                            .setMessage(Lang.t("الأنظمة التي تستخدم هذا الملف لن تقلع منه بعد حذفه."))
                            .setPositiveButton(Lang.t("حذف"), (d2, w2) -> store.delete(f))
                            .setNegativeButton(Lang.t("إلغاء"), null)
                            .show();
                })
                .setPositiveButton(Lang.t("إغلاق"), null)
                .show();
    }
}
