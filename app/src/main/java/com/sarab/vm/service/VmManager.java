package com.sarab.vm.service;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.sarab.vm.core.EmulatorSettings;
import com.sarab.vm.core.QemuCommandBuilder;
import com.sarab.vm.core.QmpClient;
import com.sarab.vm.core.StoragePaths;
import com.sarab.vm.core.VmConfig;
import com.sarab.vm.data.QemuEnvironment;
import com.sarab.vm.data.SettingsStore;
import com.sarab.vm.data.StorageAccess;
import com.sarab.vm.data.VmRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Starts, tracks and stops QEMU processes. One instance per app process. */
public final class VmManager {

    public interface Listener {
        void onVmStateChanged();
    }

    /** A machine that is currently running. */
    public static final class RunningVm {
        public final VmConfig config;
        public final int display;
        public final long startedAt;
        final Process process;
        final File logFile;

        RunningVm(VmConfig config, int display, Process process, File logFile) {
            this.config = config;
            this.display = display;
            this.process = process;
            this.logFile = logFile;
            this.startedAt = System.currentTimeMillis();
        }

        public int vncPort() {
            return QemuCommandBuilder.vncPort(display);
        }

        public boolean isAlive() {
            return process.isAlive();
        }
    }

    /** What happened when a machine stopped, so the UI can explain a crash. */
    public static final class ExitInfo {
        public final int exitCode;
        public final String logTail;
        public final long uptimeMs;

        ExitInfo(int exitCode, String logTail, long uptimeMs) {
            this.exitCode = exitCode;
            this.logTail = logTail;
            this.uptimeMs = uptimeMs;
        }
    }

    private static VmManager instance;

    public static synchronized VmManager get(Context context) {
        if (instance == null) instance = new VmManager(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final QemuEnvironment env;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, RunningVm> running = new ConcurrentHashMap<>();
    private final Map<String, ExitInfo> exits = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> logs = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private VmManager(Context app) {
        this.app = app;
        this.env = new QemuEnvironment(app);
    }

    public QemuEnvironment environment() {
        return env;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isRunning(String id) {
        RunningVm r = running.get(id);
        return r != null && r.isAlive();
    }

    public RunningVm getRunning(String id) {
        return running.get(id);
    }

    public int runningCount() {
        return running.size();
    }

    public List<RunningVm> allRunning() {
        return new ArrayList<>(running.values());
    }

    public ExitInfo lastExit(String id) {
        return exits.get(id);
    }

    /**
     * Prepares the disk and starts QEMU. Blocking: call from a background thread.
     * Throws IOException with an Arabic message the UI can show directly.
     */
    public RunningVm start(VmConfig c) throws IOException {
        RunningVm existing = running.get(c.id);
        if (existing != null && existing.isAlive()) return existing;

        List<String> problems = c.validate();
        if (!problems.isEmpty()) throw new IOException(problems.get(0));
        if (!QemuEnvironment.deviceSupported()) {
            throw new IOException("هذا الهاتف لا يدعم معالجات arm64 المطلوبة لتشغيل المحرك");
        }
        if (!env.hasEngine(c.arch)) {
            throw new IOException("محرك QEMU غير موجود في هذه النسخة من التطبيق ("
                    + c.arch.qemuBinary + "). هذه النسخة بُنيت بدون تشغيل scripts/fetch_qemu.py، استخدم ملف APK الناتج من GitHub Actions كما في ملف README.");
        }
        if (c.isoPath != null && !c.isoPath.isEmpty() && !new File(c.isoPath).isFile()) {
            throw new IOException("ملف الصورة غير موجود: " + new File(c.isoPath).getName());
        }
        env.ensureInstalled();

        VmRepository repo = new VmRepository(app);
        if (c.diskSizeGb > 0 && (c.diskPath == null || c.diskPath.isEmpty() || !new File(c.diskPath).isFile())) {
            createDisk(c, repo);
        }

        EmulatorSettings settings = new SettingsStore(app).load();
        if (settings.accel == EmulatorSettings.Accel.KVM && !new File("/dev/kvm").canRead()) {
            settings.accel = EmulatorSettings.Accel.TCG; // KVM not usable on this phone
        }
        if (c.shareFolder) prepareSharedFolder(settings);
        int display = allocateDisplay();
        QemuCommandBuilder builder = env.builder();
        List<String> cmd = builder.build(c, settings, display);
        if (c.snapshotSaved) {
            // a saved state is resumed once; afterwards the machine moves on from it
            c.snapshotSaved = false;
            repo.save(c);
        }

        File logDir = new File(app.getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        logDir.mkdirs();
        File logFile = new File(logDir, c.id + ".log");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureEnv(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        RunningVm vm = new RunningVm(c, display, p, logFile);
        running.put(c.id, vm);
        exits.remove(c.id);
        synchronized (logs) {
            logs.put(c.id, new ArrayDeque<>());
        }
        startPump(vm, cmd);
        startWaiter(vm);
        VmService.ensureStarted(app);
        notifyChanged();
        return vm;
    }

    private void createDisk(VmConfig c, VmRepository repo) throws IOException {
        if (!env.hasQemuImg()) throw new IOException("أداة إنشاء الأقراص (qemu-img) غير موجودة في هذه النسخة");
        File disk = repo.diskFileFor(c);
        ProcessBuilder pb = new ProcessBuilder(env.builder().createDiskCommand(disk.getAbsolutePath(), c.diskSizeGb));
        configureEnv(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("انتهت مهلة إنشاء القرص");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("أُلغي إنشاء القرص", e);
        }
        if (p.exitValue() != 0 || !disk.isFile()) {
            throw new IOException("فشل إنشاء القرص:\n" + out.toString().trim());
        }
        c.diskPath = disk.getAbsolutePath();
        repo.save(c);
    }

    private void prepareSharedFolder(EmulatorSettings settings) throws IOException {
        if (!StorageAccess.has(app)) {
            throw new IOException("المجلد المشترك يحتاج إذن الوصول إلى ملفات الهاتف. اسمح به ثم شغّل النظام مرة أخرى.");
        }
        File dir = new File(settings.sharedFolderPath);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("تعذر إنشاء المجلد المشترك: " + dir);
        }
        long size = StoragePaths.folderSize(dir);
        if (size > StoragePaths.SHARED_LIMIT_BYTES) {
            throw new IOException("حجم المجلد المشترك " + (size >> 20) + " MB، والحد الأقصى 480 MB. انقل بعض الملفات منه أو اختر مجلدًا آخر من الإعدادات.");
        }
    }

    private void configureEnv(ProcessBuilder pb) {
        Map<String, String> e = pb.environment();
        e.put("LD_LIBRARY_PATH", env.nativeLibDir().getAbsolutePath());
        e.put("TMPDIR", app.getCacheDir().getAbsolutePath());
        e.put("HOME", app.getFilesDir().getAbsolutePath());
        pb.directory(app.getFilesDir());
    }

    private synchronized int allocateDisplay() {
        for (int d = 1; d < 100; d++) {
            boolean used = false;
            for (RunningVm r : running.values()) {
                if (r.display == d) {
                    used = true;
                    break;
                }
            }
            if (!used) return d;
        }
        throw new IllegalStateException("too many machines");
    }

    private void startPump(RunningVm vm, List<String> cmd) {
        Thread t = new Thread(() -> {
            try (OutputStream log = new FileOutputStream(vm.logFile);
                 BufferedReader r = new BufferedReader(new InputStreamReader(vm.process.getInputStream(), StandardCharsets.UTF_8))) {
                log.write(("$ " + String.join(" ", cmd) + "\n").getBytes(StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    log.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    log.flush();
                    synchronized (logs) {
                        Deque<String> d = logs.get(vm.config.id);
                        if (d != null) {
                            d.addLast(line);
                            while (d.size() > 40) d.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "qemu-log-" + vm.display);
        t.setDaemon(true);
        t.start();
    }

    private void startWaiter(RunningVm vm) {
        Thread t = new Thread(() -> {
            int code;
            try {
                code = vm.process.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            try {
                Thread.sleep(200); // let the log pump drain
            } catch (InterruptedException ignored) {
                // continue
            }
            String tail;
            synchronized (logs) {
                Deque<String> d = logs.remove(vm.config.id);
                tail = d == null ? "" : String.join("\n", d);
            }
            exits.put(vm.config.id, new ExitInfo(code, tail, System.currentTimeMillis() - vm.startedAt));
            running.remove(vm.config.id);
            notifyChanged();
        }, "qemu-wait-" + vm.display);
        t.setDaemon(true);
        t.start();
    }

    /** Asks the guest OS to shut down (like pressing the power button). Blocking. */
    public void requestShutdown(String id) throws IOException {
        RunningVm vm = running.get(id);
        if (vm == null) return;
        QmpClient.execute("127.0.0.1", QemuCommandBuilder.qmpPort(vm.display), "system_powerdown", 3000);
    }

    /**
     * Saves the whole running state into the machine's disk, then stops it. The next start
     * resumes exactly where it was. Can take a few minutes. Blocking.
     */
    public void saveStateAndStop(String id) throws IOException {
        RunningVm vm = running.get(id);
        if (vm == null) return;
        if (!vm.config.canSaveState()) {
            throw new IOException(vm.config.diskPath == null || vm.config.diskPath.isEmpty()
                    ? "حفظ الحالة يحتاج قرصًا صلبًا وهميًا لهذا النظام. أضف قرصًا من «تعديل»."
                    : "حفظ الحالة لا يعمل مع المجلد المشترك القابل للكتابة. أوقف خيار الكتابة من «تعديل».");
        }
        int port = QemuCommandBuilder.qmpPort(vm.display);
        QmpClient.hmp("127.0.0.1", port, "stop", 10000);
        try {
            QmpClient.hmp("127.0.0.1", port, "savevm " + VmConfig.SNAPSHOT_NAME, 15 * 60 * 1000);
        } catch (IOException e) {
            try {
                QmpClient.hmp("127.0.0.1", port, "cont", 10000);
            } catch (IOException ignored) {
                // machine stays paused; the user can still stop it
            }
            throw e;
        }
        VmRepository repo = new VmRepository(app);
        VmConfig saved = repo.get(id);
        if (saved == null) saved = vm.config;
        saved.snapshotSaved = true;
        repo.save(saved);
        forceStop(id);
    }

    /** Stops the machine immediately (like pulling the plug). Blocking. */
    public void forceStop(String id) {
        RunningVm vm = running.get(id);
        if (vm == null) return;
        try {
            QmpClient.execute("127.0.0.1", QemuCommandBuilder.qmpPort(vm.display), "quit", 2000);
        } catch (IOException ignored) {
            // fall through to killing the process
        }
        try {
            if (!vm.process.waitFor(2, TimeUnit.SECONDS)) {
                vm.process.destroy();
                if (!vm.process.waitFor(3, TimeUnit.SECONDS)) vm.process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vm.process.destroyForcibly();
        }
    }

    public void forceStopAll() {
        for (RunningVm vm : allRunning()) forceStop(vm.config.id);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onVmStateChanged();
            VmService.refresh(app);
        });
    }

    /** Intent extra used by screens that show one machine. */
    public static final String EXTRA_VM_ID = "vm_id";

    public static Intent withVm(Intent i, String id) {
        return i.putExtra(EXTRA_VM_ID, id);
    }
}
