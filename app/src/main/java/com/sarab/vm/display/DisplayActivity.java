package com.sarab.vm.display;

import com.sarab.vm.core.Lang;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sarab.vm.core.Keysyms;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.SettingsStore;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The running machine's screen with its toolbar and special keys. */
public final class DisplayActivity extends Activity implements VncView.Callback, VmManager.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());

    private VmManager manager;
    private VmManager.RunningVm vm;
    private VncView vnc;
    private LinearLayout header;
    private LinearLayout toolbar;
    private LinearLayout keyBar;
    private TextView status;
    private TextView uptime;
    private TextView overlay;
    private TextView kbButton;
    private TextView mouseButton;
    private final TextView[] stickyButtons = new TextView[3];
    private final int[] stickyKeys = {Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.SUPER_L};
    private boolean keyboardShown;
    private boolean fullscreen;
    private boolean exitHandled;
    private long lastLanguageHint;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (vm != null) {
                long s = (System.currentTimeMillis() - vm.startedAt) / 1000;
                uptime.setText(String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this);
        super.onCreate(savedInstanceState);
        manager = VmManager.get(this);
        String id = getIntent().getStringExtra(VmManager.EXTRA_VM_ID);
        vm = manager.getRunning(id);
        if (vm == null) {
            Toast.makeText(this, Lang.t("النظام غير مشغّل"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUi(vm.config);
        vnc.setHaptics(new SettingsStore(this).load().haptics);
        vnc.setCallback(this);
        vnc.setStickyListener(this::refreshSticky);
        vnc.setNonLatinListener(() -> {
            long now = System.currentTimeMillis();
            if (now - lastLanguageHint > 8000) {
                lastLanguageHint = now;
                Toast.makeText(this, Lang.t("بدّل كيبورد الهاتف إلى الإنجليزية، فالنظام الوهمي يستقبل الحروف الإنجليزية فقط"),
                        Toast.LENGTH_LONG).show();
            }
        });
        vnc.connect(vm.vncPort());
    }

    private void buildUi(VmConfig c) {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG_DEEP);
        root.setLayoutDirection(Ui.dir());
        root.setFitsSystemWindows(true);

        // header
        header = Ui.row(this);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        TextView back = Ui.iconButton(this, Ui.backGlyph(), Lang.t("رجوع"), Ui.TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        titles.addView(Ui.text(this, c.name, 17, Ui.TEXT, true));
        LinearLayout statusRow = Ui.row(this);
        status = Ui.text(this, Lang.t("● يعمل · "), 12, Ui.ACCENT, true);
        uptime = Ui.mono(this, "00:00:00", 12, Ui.ACCENT);
        statusRow.addView(status);
        statusRow.addView(uptime);
        titles.addView(statusRow);
        header.addView(titles, Ui.weight(1));
        TextView power = Ui.iconButton(this, "⏻", Lang.t("إيقاف التشغيل"), Ui.SAND);
        power.setBackground(Ui.clickable(this, Ui.SAND_BG, 12, Ui.SAND_BORDER, 1));
        power.setOnClickListener(v -> askPower());
        header.addView(power);
        root.addView(header);

        // screen
        FrameLayout screenFrame = new FrameLayout(this);
        vnc = new VncView(this);
        vnc.setContentDescription(Lang.t("شاشة النظام الوهمي"));
        screenFrame.addView(vnc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = Ui.text(this, Lang.t("جارٍ الاتصال بالشاشة…"), 15, Ui.TEXT_2, false);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        screenFrame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screenFrame.setOnLongClickListener(v -> {
            if (fullscreen) setFullscreen(false);
            return true;
        });
        root.addView(screenFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // toolbar
        toolbar = Ui.row(this);
        toolbar.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 6));
        kbButton = toolButton("⌨", Lang.t("لوحة مفاتيح"));
        kbButton.setOnClickListener(v -> toggleKeyboard());
        mouseButton = toolButton("◎", Lang.t("لمس مباشر"));
        mouseButton.setOnClickListener(v -> toggleMouse());
        TextView cad = toolButton("C+A+Del", Lang.t("إعادة ضبط"));
        cad.setOnClickListener(v -> {
            if (vnc.client() != null) vnc.client().sendCombo(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.DELETE);
        });
        TextView full = toolButton("⛶", Lang.t("ملء الشاشة"));
        full.setOnClickListener(v -> setFullscreen(true));
        TextView shot = toolButton("◉", Lang.t("لقطة"));
        shot.setOnClickListener(v -> saveScreenshot());
        for (TextView b : new TextView[]{kbButton, mouseButton, cad, full, shot}) {
            LinearLayout.LayoutParams p = Ui.weight(1);
            p.height = Ui.dp(this, 60);
            p.setMarginStart(Ui.dp(this, 4));
            p.setMarginEnd(Ui.dp(this, 4));
            toolbar.addView(b, p);
        }
        root.addView(toolbar);

        // special keys
        keyBar = Ui.column(this);
        keyBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyBar.setBackgroundColor(Ui.NAV);
        keyBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 12));
        keyBar.addView(keyRow(new String[]{"Esc", "Tab", "Ctrl", "Alt", "Win", "Del"},
                new int[]{Keysyms.ESCAPE, Keysyms.TAB, -1, -2, -3, Keysyms.DELETE}));
        keyBar.addView(keyRow(new String[]{"F1", "F2", "F5", "F8", "F11", "F12"},
                new int[]{Keysyms.function(1), Keysyms.function(2), Keysyms.function(5), Keysyms.function(8), Keysyms.function(11), Keysyms.function(12)}));
        keyBar.addView(keyRow(new String[]{"Home", "End", "←", "↑", "↓", "→"},
                new int[]{Keysyms.HOME, Keysyms.END, Keysyms.LEFT, Keysyms.UP, Keysyms.DOWN, Keysyms.RIGHT}));
        keyBar.addView(keyRow(new String[]{"⌫", "Space", "Enter ↵"},
                new int[]{Keysyms.BACKSPACE, Keysyms.SPACE, Keysyms.RETURN},
                new float[]{1f, 2f, 3f}));
        keyBar.setVisibility(View.GONE);
        root.addView(keyBar);

        setContentView(root);
    }

    private TextView toolButton(String glyph, String label) {
        TextView b = Ui.text(this, glyph + "\n" + label, 11, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(0, 1.1f);
        b.setBackground(Ui.clickable(this, Ui.SURFACE, 14, Ui.BORDER, 1));
        b.setContentDescription(label);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Codes -1/-2/-3 are the sticky Ctrl / Alt / Win buttons. */
    private LinearLayout keyRow(String[] labels, int[] keys) {
        float[] weights = new float[labels.length];
        java.util.Arrays.fill(weights, 1f);
        return keyRow(labels, keys, weights);
    }

    private LinearLayout keyRow(String[] labels, int[] keys, float[] weights) {
        LinearLayout row = Ui.row(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 0; i < labels.length; i++) {
            TextView k = Ui.mono(this, labels[i], 12, Ui.TEXT);
            k.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            k.setGravity(Gravity.CENTER);
            k.setBackground(Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
            k.setClickable(true);
            final int key = keys[i];
            if (key < 0) {
                int idx = -key - 1;
                stickyButtons[idx] = k;
                k.setOnClickListener(v -> {
                    vnc.toggleSticky(stickyKeys[idx]);
                    refreshSticky();
                });
            } else {
                k.setOnClickListener(v -> vnc.pressKey(key));
            }
            LinearLayout.LayoutParams p = Ui.weight(weights[i]);
            p.height = Ui.dp(this, 44);
            p.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            row.addView(k, p);
        }
        return row;
    }

    private void refreshSticky() {
        for (int i = 0; i < stickyButtons.length; i++) {
            TextView b = stickyButtons[i];
            if (b == null) continue;
            boolean on = vnc.isSticky(stickyKeys[i]);
            b.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
            b.setBackground(on ? Ui.clickable(this, Ui.ACCENT, 10, 0, 0) : Ui.clickable(this, Ui.SURFACE_2, 10, Ui.BORDER, 1));
        }
    }

    private void toggleKeyboard() {
        keyboardShown = !keyboardShown;
        keyBar.setVisibility(keyboardShown ? View.VISIBLE : View.GONE);
        kbButton.setTextColor(keyboardShown ? Ui.ON_ACCENT : Ui.TEXT);
        kbButton.setBackground(keyboardShown ? Ui.clickable(this, Ui.ACCENT, 14, 0, 0) : Ui.clickable(this, Ui.SURFACE, 14, Ui.BORDER, 1));
        vnc.showKeyboard(keyboardShown);
    }

    private void toggleMouse() {
        boolean pad = !vnc.isTouchpadMode();
        vnc.setTouchpadMode(pad);
        mouseButton.setText(pad ? Lang.t("◎\nلوحة لمس") : Lang.t("◎\nلمس مباشر"));
        Toast.makeText(this, pad ? Lang.t("حرّك إصبعك لتحريك المؤشر، والمس للنقر") : Lang.t("المس مكان النقر مباشرة، والضغط المطوّل = زر أيمن"),
                Toast.LENGTH_SHORT).show();
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        int vis = on ? View.GONE : View.VISIBLE;
        header.setVisibility(vis);
        toolbar.setVisibility(vis);
        keyBar.setVisibility(on || !keyboardShown ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        }
        if (on) Toast.makeText(this, Lang.t("اضغط زر الرجوع للخروج من ملء الشاشة"), Toast.LENGTH_SHORT).show();
    }

    /**
     * Real keyboards and mice: every key goes to the machine, even when a toolbar button has
     * focus. A mouse's right button arrives as BACK on Android, so BACK from a mouse is ignored
     * (the right click itself is sent by the screen view).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getSource() == InputDevice.SOURCE_MOUSE || (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(e);
        }
        if (vnc != null && vnc.onHardwareKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    private void askPower() {
        String[] items = {
                Lang.t("حفظ الحالة وإيقاف — يرجع من نفس النقطة"),
                Lang.t("إيقاف آمن — يطلب من النظام أن يُغلق نفسه"),
                Lang.t("إيقاف فوري — مثل فصل الكهرباء"),
        };
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("إيقاف ") + vm.config.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) saveState();
                    else if (which == 1) runInBackground(() -> {
                        try {
                            manager.requestShutdown(vm.config.id);
                            toast(Lang.t("أُرسل طلب الإيقاف إلى النظام"));
                        } catch (IOException e) {
                            toast(Lang.t("تعذر إرسال الطلب: ") + e.getMessage());
                        }
                    });
                    else runInBackground(() -> manager.forceStop(vm.config.id));
                })
                .setNegativeButton(Lang.t("إلغاء"), null)
                .show();
    }

    private void saveState() {
        if (!vm.config.canSaveState()) {
            new AlertDialog.Builder(this)
                    .setTitle(Lang.t("لا يمكن حفظ الحالة"))
                    .setMessage(vm.config.diskPath == null || vm.config.diskPath.isEmpty()
                            ? Lang.t("حفظ الحالة يحتاج قرصًا صلبًا وهميًا (القرص يحفظ الحالة بداخله). أوقف النظام وأضف قرصًا من «تعديل».")
                            : Lang.t("حفظ الحالة لا يعمل عندما يكون المجلد المشترك قابلًا للكتابة. أوقف خيار الكتابة من «تعديل»."))
                    .setPositiveButton(Lang.t("حسنًا"), null)
                    .show();
            return;
        }
        android.widget.LinearLayout box = Ui.row(this);
        box.setLayoutDirection(Ui.dir());
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        box.addView(new android.widget.ProgressBar(this), Ui.lp(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView t = Ui.text(this, Lang.t("جارٍ حفظ الحالة… قد يأخذ دقيقة أو أكثر حسب حجم الذاكرة."), 15, Ui.TEXT, false);
        t.setPaddingRelative(Ui.dp(this, 16), 0, 0, 0);
        box.addView(t, Ui.weight(1));
        AlertDialog progress = new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
        progress.show();
        runInBackground(() -> {
            try {
                manager.saveStateAndStop(vm.config.id);
                main.post(() -> {
                    progress.dismiss();
                    toast(Lang.t("حُفظت الحالة ✓ التشغيل القادم يكمل من نفس النقطة"));
                });
            } catch (IOException e) {
                main.post(() -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle(Lang.t("تعذر حفظ الحالة"))
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton(Lang.t("حسنًا"), null)
                            .show();
                });
            }
        });
    }

    private void saveScreenshot() {
        Bitmap b = vnc.snapshot();
        if (b == null) {
            toast(Lang.t("لا توجد صورة بعد"));
            return;
        }
        String name = "sarab_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        runInBackground(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sarab");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("MediaStore");
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        if (o == null) throw new IOException("MediaStore");
                        b.compress(Bitmap.CompressFormat.PNG, 100, o);
                    }
                    toast(Lang.t("حُفظت اللقطة في الصور / Sarab"));
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (dir == null) throw new IOException("storage");
                    File f = new File(dir, name);
                    try (OutputStream o = new FileOutputStream(f)) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, o);
                    }
                    toast(Lang.t("حُفظت اللقطة: ") + f.getAbsolutePath());
                }
            } catch (IOException e) {
                toast(Lang.t("تعذر حفظ اللقطة"));
            }
        });
    }

    // ------------------------------------------------------------ callbacks

    @Override
    public void onConnected(String name, int width, int height) {
        overlay.setVisibility(View.GONE);
    }

    @Override
    public void onDisconnected(Exception reason) {
        if (isFinishing() || isDestroyed()) return;
        // Give the waiter thread a moment to record the exit before deciding.
        main.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (manager.isRunning(vm.config.id)) {
                overlay.setVisibility(View.VISIBLE);
                overlay.setText(Lang.t("انقطع الاتصال بالشاشة. المس هنا لإعادة المحاولة."));
                overlay.setOnClickListener(v -> {
                    overlay.setText(Lang.t("جارٍ الاتصال بالشاشة…"));
                    overlay.setOnClickListener(null);
                    vnc.connect(vm.vncPort());
                });
            } else {
                showExit();
            }
        }, 600);
    }

    @Override
    public void onVmStateChanged() {
        if (!manager.isRunning(vm.config.id)) showExit();
    }

    private void showExit() {
        if (exitHandled || isFinishing() || isDestroyed()) return;
        exitHandled = true;
        vnc.disconnect();
        VmManager.ExitInfo info = manager.lastExit(vm.config.id);
        if (info == null || info.exitCode == 0) {
            toast(Lang.t("توقّف النظام"));
            finish();
            return;
        }
        String log = info.logTail == null || info.logTail.isEmpty() ? Lang.t("(لا توجد رسائل)") : info.logTail;
        TextView t = Ui.mono(this, log, 11, Ui.TEXT_2);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("توقّف النظام بخطأ (رمز ") + info.exitCode + ")")
                .setView(sv)
                .setPositiveButton(Lang.t("حسنًا"), (d, w) -> finish())
                .setNeutralButton(Lang.t("السجل الكامل"), (d, w) -> {
                    com.sarab.vm.ui.LogActivity.open(this, vm.config.id);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void runInBackground(Runnable r) {
        Thread t = new Thread(r, "display-bg");
        t.setDaemon(true);
        t.start();
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (manager != null && vm != null) {
            manager.addListener(this);
            main.post(tick);
            if (!manager.isRunning(vm.config.id)) showExit();
        }
    }

    @Override
    protected void onStop() {
        if (manager != null) manager.removeListener(this);
        main.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (vnc != null) vnc.disconnect();
        super.onDestroy();
    }
}
