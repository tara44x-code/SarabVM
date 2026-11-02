package com.sarab.vm.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.sarab.vm.core.EmulatorSettings;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.ImageStore;
import com.sarab.vm.data.SettingsStore;
import com.sarab.vm.data.VmRepository;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Create or edit a machine: type, name, boot image, memory, CPU, architecture, disk. */
public final class NewVmActivity extends Activity {

    private static final int REQUEST_IMAGE = 10;
    private static final int RAM_STEP = 256;
    private static final int RAM_MIN = 512;
    private static final int[] DISK_OPTIONS = {0, 8, 16, 32, 64};

    private final Handler main = new Handler(Looper.getMainLooper());
    private VmRepository repo;
    private VmConfig config;
    private boolean editing;
    private boolean nameEdited;
    private int ramMax;
    private String originalMachine;
    private EditText portsField;

    /** RAM / CPU / architecture: a saved state only fits the exact same machine. */
    private static String machineKey(VmConfig c) {
        return c.ramMb + "/" + c.cpus + "/" + c.arch + "/" + c.isoPath + "/" + c.shareFolder;
    }

    private LinearLayout typeGrid;
    private EditText nameField;
    private TextView imageLabel;
    private TextView ramLabel;
    private SeekBar ramBar;
    private TextView cpuLabel;
    private LinearLayout archRow;
    private LinearLayout diskRow;
    private TextView diskNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new VmRepository(this);
        String id = getIntent().getStringExtra(VmManager.EXTRA_VM_ID);
        VmConfig existing = repo.get(id);
        if (existing != null) {
            config = existing;
            editing = true;
            nameEdited = true;
            originalMachine = machineKey(existing);
        } else {
            config = new VmConfig();
            EmulatorSettings s = new SettingsStore(this).load();
            config.diskSizeGb = s.defaultDiskGb;
            applyTypeDefaults(VmConfig.OsType.LINUX);
        }
        ramMax = deviceRamLimit();
        config.ramMb = Math.min(config.ramMb, ramMax);
        setContentView(buildUi());
        refreshAll();
    }

    private int deviceRamLimit() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        long total = 4L << 30;
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            total = mi.totalMem;
        }
        long limitMb = (long) (total / (1024 * 1024) * 0.6);
        int capped = (int) Math.max(RAM_MIN, Math.min(limitMb, 8192));
        return capped - (capped % RAM_STEP);
    }

    private void applyTypeDefaults(VmConfig.OsType type) {
        config.osType = type;
        switch (type) {
            case WINDOWS:
                if (!nameEdited) config.name = "Windows PE";
                config.ramMb = 2048;
                config.arch = VmConfig.Arch.X86_64;
                break;
            case LINUX:
                if (!nameEdited) config.name = "Linux";
                config.ramMb = 2048;
                break;
            case ANDROID:
                if (!nameEdited) config.name = "Android x86";
                config.ramMb = 2048;
                config.arch = VmConfig.Arch.X86_64;
                break;
            default:
                if (!nameEdited) config.name = "نظام مخصص";
                config.ramMb = 1024;
                break;
        }
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout content = Ui.column(this);
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 12));
        TextView back = Ui.iconButton(this, "›", "رجوع", Ui.TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.text(this, editing ? "تعديل النظام" : "نظام جديد", 22, Ui.TEXT, true);
        title.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        content.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 120));

        form.addView(sectionLabel("نوع النظام"));
        typeGrid = Ui.column(this);
        form.addView(typeGrid);

        form.addView(sectionLabel("الاسم"));
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT);
        nameField.setTextColor(Ui.TEXT);
        nameField.setHintTextColor(Ui.MUTED);
        nameField.setHint("مثلًا: Ubuntu 24.04");
        nameField.setTextSize(15);
        nameField.setBackground(Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER, 1));
        nameField.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        nameField.setText(config.name);
        nameField.setContentDescription("الاسم");
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (nameField.hasFocus()) nameEdited = true;
                config.name = s.toString();
            }
        });
        form.addView(nameField, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

        form.addView(sectionLabel("ملف الصورة"));
        LinearLayout picker = Ui.row(this);
        picker.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        picker.setBackground(Ui.clickable(this, Ui.ACCENT_BG, 14, Ui.ACCENT, 1.5f));
        picker.setClickable(true);
        picker.setOnClickListener(v -> pickImage());
        picker.addView(Ui.text(this, "◎", 22, Ui.ACCENT, true));
        LinearLayout pickerText = Ui.column(this);
        pickerText.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        imageLabel = Ui.text(this, "", 14, Ui.TEXT, true);
        pickerText.addView(imageLabel);
        pickerText.addView(Ui.mono(this, ".iso · .img · .qcow2", 11, Ui.MUTED));
        picker.addView(pickerText, Ui.weight(1));
        form.addView(picker);

        form.addView(sectionLabel("الموارد"));
        LinearLayout res = Ui.column(this);
        res.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        res.setBackground(Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER, 1));

        LinearLayout ramHead = Ui.row(this);
        ramHead.addView(Ui.text(this, "الذاكرة RAM", 14, Ui.TEXT, true), Ui.weight(1));
        ramLabel = Ui.mono(this, "", 14, Ui.ACCENT);
        ramHead.addView(ramLabel);
        res.addView(ramHead);
        ramBar = new SeekBar(this);
        ramBar.setMax((ramMax - RAM_MIN) / RAM_STEP);
        ramBar.setContentDescription("الذاكرة");
        ramBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    config.ramMb = RAM_MIN + p * RAM_STEP;
                    refreshRam();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        res.addView(ramBar, Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40), 0, 4, 0, 4));
        res.addView(Ui.divider(this));

        LinearLayout cpuRow = Ui.row(this);
        cpuRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        cpuRow.addView(Ui.text(this, "أنوية المعالج", 14, Ui.TEXT, true), Ui.weight(1));
        TextView minus = Ui.iconButton(this, "−", "تقليل", Ui.TEXT);
        minus.setOnClickListener(v -> {
            config.cpus = Math.max(VmConfig.MIN_CPUS, config.cpus - 1);
            refreshCpu();
        });
        cpuLabel = Ui.mono(this, "", 16, Ui.TEXT);
        cpuLabel.setGravity(Gravity.CENTER);
        TextView plus = Ui.iconButton(this, "+", "زيادة", Ui.TEXT);
        int maxCpus = Math.min(VmConfig.MAX_CPUS, Runtime.getRuntime().availableProcessors());
        plus.setOnClickListener(v -> {
            config.cpus = Math.min(maxCpus, config.cpus + 1);
            refreshCpu();
        });
        cpuRow.addView(minus);
        cpuRow.addView(cpuLabel, Ui.lp(Ui.dp(this, 40), ViewGroup.LayoutParams.WRAP_CONTENT));
        cpuRow.addView(plus);
        res.addView(cpuRow);
        res.addView(Ui.divider(this));

        LinearLayout archHead = Ui.row(this);
        archHead.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        archHead.addView(Ui.text(this, "المعمارية", 14, Ui.TEXT, true));
        res.addView(archHead);
        archRow = Ui.row(this);
        archRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        res.addView(archRow);
        res.addView(Ui.text(this, "x86_64 لأغلب أنظمة ويندوز ولينكس وأندرويد x86. aarch64 لأنظمة ARM وهو الأسرع على الهاتف.",
                12, Ui.MUTED, false), Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 6));
        form.addView(res);

        form.addView(sectionLabel("القرص الصلب الوهمي"));
        diskRow = Ui.row(this);
        diskRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        form.addView(diskRow);
        diskNote = Ui.text(this, "", 12, Ui.MUTED, false);
        form.addView(diskNote, Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        form.addView(sectionLabel("تحويل المنافذ (اختياري)"));
        portsField = new EditText(this);
        portsField.setSingleLine(true);
        portsField.setInputType(InputType.TYPE_CLASS_TEXT);
        portsField.setTextDirection(View.TEXT_DIRECTION_LTR);
        portsField.setTextColor(Ui.TEXT);
        portsField.setHintTextColor(Ui.MUTED);
        portsField.setHint("8080:80, 2222:22");
        portsField.setTextSize(15);
        portsField.setTypeface(android.graphics.Typeface.MONOSPACE);
        portsField.setBackground(Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER, 1));
        portsField.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        portsField.setText(config.portForwards);
        portsField.setContentDescription("تحويل المنافذ");
        form.addView(portsField, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));
        form.addView(Ui.text(this, "منفذ الهاتف:منفذ النظام. مثلًا 8080:80 يجعل موقعًا يعمل داخل النظام على المنفذ 80 يفتح من متصفح هاتفك على http://127.0.0.1:8080. و 2222:22 للدخول عبر SSH.",
                12, Ui.MUTED, false), Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        form.addView(sectionLabel("المجلد المشترك مع الهاتف"));
        LinearLayout share = Ui.column(this);
        share.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 10));
        share.setBackground(Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER, 1));
        Switch shareSwitch = new Switch(this);
        Switch writeSwitch = new Switch(this);
        shareSwitch.setChecked(config.shareFolder);
        writeSwitch.setChecked(config.shareWritable);
        writeSwitch.setEnabled(config.shareFolder);
        shareSwitch.setOnCheckedChangeListener((b, on) -> {
            config.shareFolder = on;
            writeSwitch.setEnabled(on);
        });
        writeSwitch.setOnCheckedChangeListener((b, on) -> config.shareWritable = on);
        share.addView(switchRow("إظهار مجلد الهاتف داخل النظام كقرص", shareSwitch));
        share.addView(Ui.divider(this));
        share.addView(switchRow("السماح للنظام بالكتابة فيه (تجريبي)", writeSwitch));
        String path = new SettingsStore(this).load().sharedFolderPath;
        share.addView(Ui.text(this, "المجلد: " + path + "\nيظهر كقرص FAT صغير (حتى 480 MB). بدون الكتابة، النقل يكون من الهاتف إلى النظام فقط. غيّر المجلد من الإعدادات.",
                12, Ui.MUTED, false), Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        form.addView(share);

        scroll.addView(form);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);

        LinearLayout bottom = Ui.row(this);
        bottom.setBackgroundColor(Ui.BG);
        bottom.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 16));
        TextView start = Ui.primaryButton(this, editing ? "حفظ وتشغيل" : "إنشاء وتشغيل");
        start.setOnClickListener(v -> save(true));
        bottom.addView(start, Ui.weight(1));
        TextView saveOnly = Ui.secondaryButton(this, "حفظ");
        saveOnly.setOnClickListener(v -> save(false));
        LinearLayout.LayoutParams sp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 52));
        sp.setMarginStart(Ui.dp(this, 10));
        bottom.addView(saveOnly, sp);
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return root;
    }

    private View switchRow(String label, Switch sw) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(Ui.dp(this, 52));
        row.addView(Ui.text(this, label, 14, Ui.TEXT, true), Ui.weight(1));
        row.addView(sw);
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView t = Ui.text(this, text, 15, Ui.TEXT_2, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        return t;
    }

    // ------------------------------------------------------------ refresh

    private void refreshAll() {
        refreshTypes();
        refreshImage();
        refreshRam();
        refreshCpu();
        refreshArch();
        refreshDisk();
    }

    private void refreshTypes() {
        typeGrid.removeAllViews();
        VmConfig.OsType[] types = VmConfig.OsType.values();
        String[] hints = {"PE · 7 · 10", "Ubuntu · Debian", "Android x86", "أي ملف ISO"};
        LinearLayout row = null;
        for (int i = 0; i < types.length; i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                typeGrid.addView(row);
            }
            VmConfig.OsType t = types[i];
            boolean on = config.osType == t;
            LinearLayout cell = Ui.row(this);
            cell.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            cell.setBackground(Ui.clickable(this, on ? Ui.ACCENT_BG : Ui.SURFACE, 16, on ? Ui.ACCENT : Ui.BORDER, on ? 2 : 1));
            cell.setClickable(true);
            cell.setContentDescription(Ui.osLabel(t) + (on ? "، محدد" : ""));
            cell.setOnClickListener(v -> {
                applyTypeDefaults(t);
                if (!nameEdited) nameField.setText(config.name);
                config.ramMb = Math.min(config.ramMb, ramMax);
                refreshAll();
            });
            cell.addView(Ui.osBadge(this, t, 40));
            LinearLayout labels = Ui.column(this);
            labels.setPaddingRelative(Ui.dp(this, 10), 0, 0, 0);
            labels.addView(Ui.text(this, Ui.osLabel(t), 15, Ui.TEXT, true));
            labels.addView(Ui.text(this, hints[i], 11, Ui.MUTED, false));
            cell.addView(labels);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 68), 1f);
            p.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            row.addView(cell, p);
        }
    }

    private void refreshImage() {
        if (config.isoPath == null || config.isoPath.isEmpty()) {
            imageLabel.setText("اختر ملف ISO أو IMG");
        } else {
            File f = new File(config.isoPath);
            imageLabel.setText(f.getName() + (f.isFile() ? "  ·  " + Ui.formatSize(f.length()) : "  (غير موجود)"));
        }
    }

    private void refreshRam() {
        ramBar.setProgress((config.ramMb - RAM_MIN) / RAM_STEP);
        ramLabel.setText(config.ramMb % 1024 == 0
                ? (config.ramMb / 1024) + " GB"
                : String.format(Locale.US, "%.2f GB", config.ramMb / 1024f));
    }

    private void refreshCpu() {
        cpuLabel.setText(String.valueOf(config.cpus));
    }

    private void refreshArch() {
        archRow.removeAllViews();
        for (VmConfig.Arch a : VmConfig.Arch.values()) {
            boolean on = config.arch == a;
            TextView b = Ui.mono(this, a.label, 13, on ? Ui.ON_ACCENT : Ui.TEXT_2);
            b.setGravity(Gravity.CENTER);
            b.setBackground(on ? Ui.clickable(this, Ui.ACCENT, 10, 0, 0) : Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
            b.setClickable(true);
            b.setOnClickListener(v -> {
                config.arch = a;
                refreshArch();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
            p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            archRow.addView(b, p);
        }
    }

    private void refreshDisk() {
        diskRow.removeAllViews();
        boolean hasDisk = config.diskPath != null && !config.diskPath.isEmpty() && new File(config.diskPath).isFile();
        if (hasDisk) {
            diskNote.setText("القرص موجود (" + config.diskSizeGb + " GB، يشغل حاليًا "
                    + Ui.formatSize(new File(config.diskPath).length()) + "). لتغيير حجمه احذف النظام وأنشئه من جديد.");
            return;
        }
        for (int size : DISK_OPTIONS) {
            boolean on = config.diskSizeGb == size;
            TextView b = Ui.mono(this, size == 0 ? "بدون" : size + " GB", 13, on ? Ui.ON_ACCENT : Ui.TEXT_2);
            b.setGravity(Gravity.CENTER);
            b.setBackground(on ? Ui.clickable(this, Ui.ACCENT, 10, 0, 0) : Ui.clickable(this, Ui.SURFACE, 10, Ui.BORDER, 1));
            b.setClickable(true);
            b.setOnClickListener(v -> {
                config.diskSizeGb = size;
                refreshDisk();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
            p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            diskRow.addView(b, p);
        }
        diskNote.setText(config.diskSizeGb == 0
                ? "بدون قرص: مناسب لأنظمة تعمل من الصورة مباشرة مثل Windows PE. لن يُحفظ أي شيء."
                : "يُنشأ القرص عند أول تشغيل، ويكبر حجمه الفعلي تدريجيًا حتى " + config.diskSizeGb + " GB.");
    }

    // ------------------------------------------------------------ image picking

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_IMAGE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "لا يوجد تطبيق لاختيار الملفات", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importImage(data.getData());
    }

    private void importImage(Uri uri) {
        ImageStore store = new ImageStore(this);
        String name = store.displayName(uri);

        LinearLayout box = Ui.column(this);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 8));
        TextView label = Ui.text(this, "جارٍ نسخ " + name + "…", 14, Ui.TEXT, false);
        box.addView(label);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar, Ui.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        TextView amount = Ui.mono(this, "", 12, Ui.MUTED);
        box.addView(amount);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("نسخ ملف الصورة")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("إلغاء", (d, w) -> cancelled.set(true))
                .create();
        dialog.show();

        final long[] lastUi = {0};
        Thread t = new Thread(() -> {
            try {
                File f = store.importUri(uri, (copied, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUi[0] > 200) {
                        lastUi[0] = now;
                        main.post(() -> {
                            if (total > 0) bar.setProgress((int) (copied * 1000 / total));
                            else bar.setIndeterminate(true);
                            amount.setText(Ui.formatSize(copied) + (total > 0 ? " / " + Ui.formatSize(total) : ""));
                        });
                    }
                    return !cancelled.get();
                });
                main.post(() -> {
                    dialog.dismiss();
                    config.isoPath = f.getAbsolutePath();
                    refreshImage();
                });
            } catch (IOException e) {
                main.post(() -> {
                    dialog.dismiss();
                    if (!cancelled.get()) {
                        new AlertDialog.Builder(this).setTitle("تعذر نسخ الملف")
                                .setMessage(String.valueOf(e.getMessage()))
                                .setPositiveButton("حسنًا", null).show();
                    }
                });
            }
        }, "import-image");
        t.start();
    }

    // ------------------------------------------------------------ save

    private void save(boolean andStart) {
        config.name = nameField.getText().toString().trim();
        config.portForwards = portsField.getText().toString().trim();
        if (editing && config.snapshotSaved && !machineKey(config).equals(originalMachine)) {
            config.snapshotSaved = false; // the saved state belongs to the old machine setup
            Toast.makeText(this, "تغيّرت مواصفات النظام، لذلك سيبدأ من جديد بدل الحالة المحفوظة", Toast.LENGTH_LONG).show();
        }
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("أكمل البيانات")
                    .setMessage(String.join("\n", errors))
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        try {
            repo.save(config);
        } catch (IOException e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        if (!andStart) {
            finish();
            return;
        }
        VmStarter.start(this, config, this::finish);
    }
}
