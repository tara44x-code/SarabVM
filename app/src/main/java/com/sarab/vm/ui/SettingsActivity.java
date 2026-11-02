package com.sarab.vm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.widget.Toast;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.sarab.vm.core.EmulatorSettings;
import com.sarab.vm.core.StoragePaths;
import com.sarab.vm.data.StorageAccess;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.QemuEnvironment;
import com.sarab.vm.data.SettingsStore;
import com.sarab.vm.util.Ui;

import java.io.File;

/** Emulator, storage, network and interface options. Changes apply to the next start. */
public final class SettingsActivity extends Activity {

    private static final int[] DISK_OPTIONS = {8, 16, 32, 64};

    private static final int REQUEST_FOLDER = 21;

    private SettingsStore store;
    private TextView sharedValue;
    private View sharedItem;
    private TextView permissionValue;
    private EmulatorSettings settings;
    private LinearLayout engineToggle;
    private TextView diskValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
        settings = store.load();
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 12));
        TextView back = Ui.iconButton(this, "›", "رجوع", Ui.TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.text(this, "الإعدادات", 22, Ui.TEXT, true);
        title.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 32));

        // emulation
        LinearLayout emu = section(body, "المحاكاة");
        engineToggle = Ui.row(this);
        engineToggle.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        engineToggle.setBackground(Ui.rounded(this, Ui.BG, 10, 0, 0));
        engineToggle.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        refreshEngine();
        emu.addView(item("محرك التشغيل", "TCG يعمل دائمًا. KVM أسرع لكنه يحتاج روت ويعمل مع aarch64 فقط", engineToggle));
        emu.addView(Ui.divider(this));
        emu.addView(item("طريقة العرض", null, Ui.mono(this, "VNC", 13, Ui.TEXT_2)));
        emu.addView(Ui.divider(this));
        QemuEnvironment env = new QemuEnvironment(this);
        emu.addView(item("المحركات المتوفرة", null, Ui.mono(this, engines(env), 12, Ui.TEXT_2)));

        // storage & network
        LinearLayout storage = section(body, "التخزين والشبكة");
        storage.addView(item("مجلد الأنظمة", formatUsage(), null));
        storage.addView(Ui.divider(this));
        sharedValue = Ui.text(this, "تغيير", 13, Ui.ACCENT, true);
        sharedValue.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        sharedValue.setBackground(Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
        sharedValue.setClickable(true);
        sharedValue.setOnClickListener(v -> pickSharedFolder());
        sharedItem = item("المجلد المشترك مع الأنظمة", settings.sharedFolderPath, sharedValue);
        storage.addView(sharedItem);
        storage.addView(Ui.divider(this));
        permissionValue = Ui.text(this, "", 13, Ui.TEXT_2, true);
        permissionValue.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        permissionValue.setBackground(Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
        permissionValue.setClickable(true);
        permissionValue.setOnClickListener(v -> {
            if (!StorageAccess.has(this)) StorageAccess.request(this);
        });
        storage.addView(item("إذن الوصول إلى ملفات الهاتف", "مطلوب للمجلد المشترك فقط", permissionValue));
        storage.addView(Ui.divider(this));
        diskValue = Ui.mono(this, settings.defaultDiskGb + " GB", 13, Ui.ACCENT);
        diskValue.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        diskValue.setBackground(Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
        diskValue.setClickable(true);
        diskValue.setOnClickListener(v -> chooseDisk());
        storage.addView(item("حجم القرص الافتراضي", null, diskValue));
        storage.addView(Ui.divider(this));
        storage.addView(item("الإنترنت داخل النظام", null, toggle(settings.network, on -> {
            settings.network = on;
            store.save(settings);
        })));

        // interface
        LinearLayout iface = section(body, "الواجهة");
        iface.addView(item("اللغة", null, Ui.text(this, "العربية", 14, Ui.TEXT_2, false)));
        iface.addView(Ui.divider(this));
        iface.addView(item("اهتزاز عند الضغط", null, toggle(settings.haptics, on -> {
            settings.haptics = on;
            store.save(settings);
        })));

        TextView about = Ui.text(this, "سراب " + version() + " · المطوّر: مهند سات لنك\nمبني على QEMU (GPL v2)\nالإعدادات تُطبّق عند التشغيل التالي للنظام.",
                12, Ui.MUTED, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, Ui.dp(this, 24), 0, 0);
        body.addView(about);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = StorageAccess.has(this);
        permissionValue.setText(ok ? "مسموح ✓" : "السماح");
        permissionValue.setTextColor(ok ? Ui.ACCENT : Ui.SAND);
    }

    private void pickSharedFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
        } catch (RuntimeException e) {
            Toast.makeText(this, "لا يوجد تطبيق لاختيار المجلدات", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String path = null;
        try {
            path = StoragePaths.fromTreeDocumentId(DocumentsContract.getTreeDocumentId(data.getData()));
        } catch (RuntimeException ignored) {
            // unsupported provider
        }
        if (path == null) {
            new AlertDialog.Builder(this)
                    .setTitle("مجلد غير مدعوم")
                    .setMessage("اختر مجلدًا من ذاكرة الهاتف الداخلية أو بطاقة الذاكرة، وليس من التنزيلات الحديثة أو تطبيقات أخرى.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        settings.sharedFolderPath = path;
        store.save(settings);
        recreate();
    }

    private LinearLayout section(LinearLayout parent, String title) {
        TextView t = Ui.text(this, title, 13, Ui.MUTED, true);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 6));
        parent.addView(t);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER, 1));
        parent.addView(card);
        return card;
    }

    private View item(String title, String subtitle, View trailing) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT, true));
        if (subtitle != null) texts.addView(Ui.text(this, subtitle, 11, Ui.MUTED, false));
        row.addView(texts, Ui.weight(1));
        if (trailing != null) {
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginStart(Ui.dp(this, 12));
            row.addView(trailing, p);
        }
        return row;
    }

    private interface OnToggle {
        void changed(boolean on);
    }

    private Switch toggle(boolean initial, OnToggle cb) {
        Switch s = new Switch(this);
        s.setChecked(initial);
        s.setOnCheckedChangeListener((b, on) -> cb.changed(on));
        return s;
    }

    private void refreshEngine() {
        engineToggle.removeAllViews();
        for (EmulatorSettings.Accel a : EmulatorSettings.Accel.values()) {
            boolean on = settings.accel == a;
            TextView b = Ui.mono(this, a.name(), 12, on ? Ui.ON_ACCENT : Ui.TEXT_2);
            b.setGravity(Gravity.CENTER);
            b.setBackground(on ? Ui.clickable(this, Ui.ACCENT, 8, 0, 0) : Ui.clickable(this, Ui.BG, 8, 0, 0));
            b.setClickable(true);
            b.setOnClickListener(v -> selectEngine(a));
            engineToggle.addView(b, Ui.lp(Ui.dp(this, 56), Ui.dp(this, 36)));
        }
    }

    private void selectEngine(EmulatorSettings.Accel a) {
        if (a == EmulatorSettings.Accel.KVM && !new File("/dev/kvm").canRead()) {
            new AlertDialog.Builder(this)
                    .setTitle("KVM غير متاح")
                    .setMessage("هذا الهاتف لا يسمح للتطبيق باستخدام KVM (يحتاج غالبًا روت ونواة تدعمه). سيُحفظ الخيار، لكن الأنظمة ستعمل بـ TCG حتى يصبح KVM متاحًا.")
                    .setPositiveButton("فهمت", (d, w) -> applyEngine(a))
                    .setNegativeButton("إلغاء", null)
                    .show();
            return;
        }
        applyEngine(a);
    }

    private void applyEngine(EmulatorSettings.Accel a) {
        settings.accel = a;
        store.save(settings);
        refreshEngine();
    }

    private void chooseDisk() {
        String[] labels = new String[DISK_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = DISK_OPTIONS[i] + " GB";
        new AlertDialog.Builder(this)
                .setTitle("حجم القرص الافتراضي")
                .setItems(labels, (d, which) -> {
                    settings.defaultDiskGb = DISK_OPTIONS[which];
                    store.save(settings);
                    diskValue.setText(settings.defaultDiskGb + " GB");
                })
                .show();
    }

    private static String engines(QemuEnvironment env) {
        StringBuilder sb = new StringBuilder();
        if (env.hasEngine(VmConfig.Arch.X86_64)) sb.append("x86_64");
        if (env.hasEngine(VmConfig.Arch.AARCH64)) sb.append(sb.length() > 0 ? " · " : "").append("aarch64");
        return sb.length() == 0 ? "غير موجود" : sb.toString();
    }

    private String formatUsage() {
        long total = folderSize(getFilesDir());
        long free = getFilesDir().getUsableSpace();
        return "مساحة التطبيق: " + Ui.formatSize(total) + " · المتاح على الهاتف: " + Ui.formatSize(free);
    }

    private static long folderSize(File f) {
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += folderSize(k);
        return sum;
    }

    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
