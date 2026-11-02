package com.sarab.vm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sarab.vm.R;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.ImageStore;
import com.sarab.vm.data.VmRepository;
import com.sarab.vm.display.DisplayActivity;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Home screen: the list of machines ("أنظمتي"). */
public final class MainActivity extends Activity implements VmManager.Listener {

    private static final String NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS";

    private final Handler main = new Handler(Looper.getMainLooper());
    private VmRepository repo;
    private VmManager manager;
    private LinearLayout list;
    private LinearLayout chips;
    private VmConfig.OsType filter; // null = all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new VmRepository(this);
        manager = VmManager.get(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout content = Ui.column(this);

        // header: logo + name + settings
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setContentDescription("سراب");
        header.addView(logo, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout brand = Ui.column(this);
        brand.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        brand.addView(Ui.text(this, "سراب", 22, Ui.TEXT, true));
        brand.addView(Ui.mono(this, "Sarab VM", 12, Ui.MUTED));
        header.addView(brand, Ui.weight(1));
        TextView settings = Ui.iconButton(this, "⚙", "الإعدادات", Ui.TEXT);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);
        content.addView(header);

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        titles.addView(Ui.text(this, "أنظمتي", 26, Ui.TEXT, true));
        titles.addView(Ui.text(this, "شغّل أي نظام من ملف صورة ISO أو IMG على هاتفك", 14, Ui.MUTED, false));
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
        TextView fab = Ui.text(this, "+  نظام جديد", 16, Ui.ON_SAND, true);
        fab.setGravity(Gravity.CENTER);
        fab.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
        fab.setBackground(Ui.clickable(this, Ui.SAND, 18, 0, 0));
        fab.setElevation(Ui.dp(this, 6));
        fab.setClickable(true);
        fab.setOnClickListener(v -> startActivity(new Intent(this, NewVmActivity.class)));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 56),
                Gravity.BOTTOM | Gravity.LEFT);
        fp.leftMargin = Ui.dp(this, 20);
        fp.bottomMargin = Ui.dp(this, 96);
        root.addView(fab, fp);

        // bottom navigation
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.NAV);
        nav.addView(navItem("▦", "الأنظمة", true, null), Ui.weight(1));
        nav.addView(navItem("◎", "ملفات الصور", false, v -> showImages()), Ui.weight(1));
        nav.addView(navItem("☰", "الإعدادات", false,
                v -> startActivity(new Intent(this, SettingsActivity.class))), Ui.weight(1));
        root.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 76), Gravity.BOTTOM));
        return root;
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
        addChip("الكل", null);
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
                    ? "لا توجد أنظمة بعد.\nاضغط «نظام جديد» واختر ملف ISO لتبدأ."
                    : "لا توجد أنظمة من هذا النوع.", 15, Ui.MUTED, false);
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
        top.addView(info, Ui.weight(1));

        if (running) {
            top.addView(Ui.text(this, "● يعمل", 12, Ui.ACCENT, true));
            card.addView(top);
            LinearLayout actions = Ui.row(this);
            actions.setPadding(0, Ui.dp(this, 14), 0, 0);
            TextView open = Ui.primaryButton(this, "فتح الشاشة");
            open.setTextSize(15);
            open.setMinHeight(Ui.dp(this, 44));
            open.setOnClickListener(v -> openDisplay(c));
            actions.addView(open, Ui.weight(1));
            TextView stop = Ui.iconButton(this, "■", "إيقاف", Ui.SAND);
            stop.setOnClickListener(v -> confirmStop(c));
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) stop.getLayoutParams();
            sp.setMarginStart(Ui.dp(this, 8));
            actions.addView(stop, sp);
            card.addView(actions);
        } else {
            TextView play = Ui.text(this, "▶", 16, Ui.ACCENT, true);
            play.setGravity(Gravity.CENTER);
            play.setBackground(Ui.clickable(this, Ui.SURFACE_2, 22, Ui.BORDER, 1));
            play.setContentDescription("تشغيل " + c.name);
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{NOTIFICATION_PERMISSION}, 1);
        }
        AlertDialog progress = progressDialog("جارٍ تجهيز " + c.name + "…");
        progress.show();
        Thread t = new Thread(() -> {
            try {
                manager.start(c);
                main.post(() -> {
                    progress.dismiss();
                    openDisplay(c);
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("تعذر التشغيل")
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton("حسنًا", null)
                            .show();
                });
            }
        }, "start-vm");
        t.start();
    }

    private void openDisplay(VmConfig c) {
        startActivity(VmManager.withVm(new Intent(this, DisplayActivity.class), c.id));
    }

    private void confirmStop(VmConfig c) {
        new AlertDialog.Builder(this)
                .setTitle("إيقاف " + c.name + "؟")
                .setMessage("سيتوقف النظام فورًا وقد تضيع البيانات غير المحفوظة. للإيقاف الآمن افتح الشاشة واضغط زر الطاقة.")
                .setPositiveButton("إيقاف", (d, w) -> new Thread(() -> manager.forceStop(c.id), "stop-vm").start())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showOptions(VmConfig c) {
        boolean running = manager.isRunning(c.id);
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(new String[]{"تعديل", "حذف"}, (d, which) -> {
                    if (running) {
                        new AlertDialog.Builder(this).setMessage("أوقف النظام أولًا").setPositiveButton("حسنًا", null).show();
                        return;
                    }
                    if (which == 0) {
                        startActivity(VmManager.withVm(new Intent(this, NewVmActivity.class), c.id));
                    } else {
                        confirmDelete(c);
                    }
                })
                .show();
    }

    private void confirmDelete(VmConfig c) {
        new AlertDialog.Builder(this)
                .setTitle("حذف " + c.name + "؟")
                .setMessage("سيُحذف النظام وقرصه الصلب الوهمي. ملف الصورة (ISO) يبقى في «ملفات الصور».")
                .setPositiveButton("حذف", (d, w) -> {
                    repo.delete(c);
                    refresh();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showImages() {
        ImageStore store = new ImageStore(this);
        List<File> files = store.list();
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("ملفات الصور")
                    .setMessage("لا توجد ملفات بعد. تُضاف الملفات عند إنشاء نظام جديد.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        String[] items = new String[files.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = files.get(i).getName() + "  ·  " + Ui.formatSize(files.get(i).length());
        }
        new AlertDialog.Builder(this)
                .setTitle("ملفات الصور (المس للحذف)")
                .setItems(items, (d, which) -> {
                    File f = files.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("حذف " + f.getName() + "؟")
                            .setMessage("الأنظمة التي تستخدم هذا الملف لن تقلع منه بعد حذفه.")
                            .setPositiveButton("حذف", (d2, w2) -> store.delete(f))
                            .setNegativeButton("إلغاء", null)
                            .show();
                })
                .setPositiveButton("إغلاق", null)
                .show();
    }

    private AlertDialog progressDialog(String message) {
        LinearLayout box = Ui.row(this);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar, Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView t = Ui.text(this, message, 15, Ui.TEXT, false);
        t.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        box.addView(t);
        return new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
    }
}
