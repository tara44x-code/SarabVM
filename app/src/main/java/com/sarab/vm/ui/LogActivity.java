package com.sarab.vm.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.VmRepository;
import com.sarab.vm.service.VmManager;
import com.sarab.vm.util.Ui;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Shows QEMU's messages for one machine (files/logs/&lt;id&gt;.log). */
public final class LogActivity extends Activity {

    private static final int MAX_BYTES = 200 * 1024;

    private String vmId;
    private TextView body;
    private ScrollView scroll;

    public static void open(Activity from, String vmId) {
        from.startActivity(VmManager.withVm(new Intent(from, LogActivity.class), vmId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vmId = getIntent().getStringExtra(VmManager.EXTRA_VM_ID);
        VmConfig c = new VmRepository(this).get(vmId);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 12));
        TextView back = Ui.iconButton(this, "›", "رجوع", Ui.TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        LinearLayout titles = Ui.column(this);
        titles.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        titles.addView(Ui.text(this, "السجل", 20, Ui.TEXT, true));
        titles.addView(Ui.text(this, c == null ? "" : c.name, 12, Ui.MUTED, false));
        header.addView(titles, Ui.weight(1));
        root.addView(header);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10));
        TextView refresh = Ui.secondaryButton(this, "تحديث");
        refresh.setOnClickListener(v -> load());
        TextView copy = Ui.secondaryButton(this, "نسخ");
        copy.setOnClickListener(v -> copy());
        TextView share = Ui.secondaryButton(this, "مشاركة");
        share.setOnClickListener(v -> share());
        for (TextView b : new TextView[]{refresh, copy, share}) {
            LinearLayout.LayoutParams p = Ui.weight(1);
            p.setMarginEnd(Ui.dp(this, 8));
            actions.addView(b, p);
        }
        root.addView(actions);

        scroll = new ScrollView(this);
        body = Ui.mono(this, "", 11, Ui.TEXT_2);
        body.setTextIsSelectable(true);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 24));
        body.setBackgroundColor(Ui.BG_DEEP);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private File logFile() {
        return new File(new File(getFilesDir(), "logs"), vmId + ".log");
    }

    private void load() {
        File f = logFile();
        String text;
        if (!f.isFile()) {
            text = "لا يوجد سجل بعد. يُنشأ السجل عند أول تشغيل لهذا النظام.";
        } else {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                long len = r.length();
                long start = Math.max(0, len - MAX_BYTES);
                byte[] b = new byte[(int) (len - start)];
                r.seek(start);
                r.readFully(b);
                text = (start > 0 ? "… (آخر 200 KB فقط)\n" : "") + new String(b, StandardCharsets.UTF_8);
                if (text.trim().isEmpty()) text = "السجل فارغ: لم يكتب QEMU أي رسالة.";
            } catch (IOException e) {
                text = "تعذر قراءة السجل: " + e.getMessage();
            }
        }
        VmManager.ExitInfo exit = VmManager.get(this).lastExit(vmId);
        if (exit != null && !VmManager.get(this).isRunning(vmId)) {
            text += "\n\n— توقّف النظام برمز " + exit.exitCode + " بعد " + (exit.uptimeMs / 1000) + " ثانية —";
        }
        body.setText(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Sarab log", body.getText()));
            Toast.makeText(this, "نُسخ السجل", Toast.LENGTH_SHORT).show();
        }
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, body.getText().toString());
        startActivity(Intent.createChooser(i, "مشاركة السجل"));
    }
}
