package com.pigeoncookie.dsmonitor.data;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.pigeoncookie.dsmonitor.MainActivity;
import com.pigeoncookie.dsmonitor.R;
import com.pigeoncookie.dsmonitor.core.Fmt;

/** Balance-threshold notification. */
public final class Alerts {

    private static final String CHANNEL_ID = "balance_alert";
    private static final int NOTIFICATION_ID = 1001;

    private Alerts() {
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "余额预警",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("余额低于阈值时提醒");
        nm.createNotificationChannel(channel);
    }

    public static void notifyLowBalance(Context c, double balance, double threshold) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannel(c);
        Intent intent = new Intent(c, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(c, 0, intent, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(R.drawable.ic_wallet)
                .setContentTitle("DeepSeek 余额偏低")
                .setContentText("当前余额 ¥" + Fmt.money2(balance) + "，低于阈值 ¥" + Fmt.money2(threshold))
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (RuntimeException ignored) {
            // notifications can be blocked; never crash the refresh path
        }
    }

    public static void cancel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
    }
}
