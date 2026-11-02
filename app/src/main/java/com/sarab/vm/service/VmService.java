package com.sarab.vm.service;

import com.sarab.vm.core.Lang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.sarab.vm.R;
import com.sarab.vm.ui.MainActivity;

/**
 * Foreground service that keeps the app process (and therefore QEMU) alive
 * while machines run, with a notification to return to the app or stop all.
 */
public final class VmService extends Service {

    private static final String CHANNEL = "running_vms";
    private static final int NOTIFICATION_ID = 7;
    private static final String ACTION_STOP_ALL = "com.sarab.vm.STOP_ALL";

    private static VmService current;

    public static void ensureStarted(Context context) {
        Intent i = new Intent(context, VmService.class);
        context.startForegroundService(i);
    }

    /** Updates the notification, or stops the service when nothing is running. */
    static void refresh(Context context) {
        VmService s = current;
        if (s == null) return;
        if (VmManager.get(context).runningCount() == 0) {
            s.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            s.stopSelf();
        } else {
            NotificationManager nm = s.getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, s.buildNotification());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        Lang.setEnglish("en".equals(getSharedPreferences("ui", MODE_PRIVATE).getString("lang", "ar")));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Lang.t("الأنظمة العاملة"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(Lang.t("يبقي الأنظمة الوهمية تعمل في الخلفية"));
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            Thread t = new Thread(() -> VmManager.get(this).forceStopAll(), "stop-all");
            t.start();
        } else if (VmManager.get(this).runningCount() == 0) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        int count = VmManager.get(this).runningCount();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VmService.class).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = count <= 1 ? Lang.t("نظام واحد يعمل") : count + Lang.t(" أنظمة تعمل");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Lang.t("سراب"))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, Lang.t("إيقاف الكل"), stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
