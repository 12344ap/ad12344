package com.floatkit.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

/** 通知渠道与常驻通知。 */
public final class Notifier {

    public static final String CHANNEL_SERVICE = "floatkit_service";
    public static final String CHANNEL_ALERT = "floatkit_alert";

    private static final int ID_ALERT = 0x1002;

    private Notifier() {
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE, "悬浮窗常驻", NotificationManager.IMPORTANCE_LOW);
            service.setShowBadge(false);
            service.setDescription("保持悬浮球在后台运行");
            manager.createNotificationChannel(service);

            NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "提醒", NotificationManager.IMPORTANCE_DEFAULT);
            alert.setDescription("悬浮窗被系统关闭等提醒");
            manager.createNotificationChannel(alert);
        } catch (Throwable ignored) {
            // 忽略渠道创建失败
        }
    }

    /** 前台服务常驻通知；录屏时附带“停止录屏”按钮。 */
    public static Notification buildServiceNotification(Context context, boolean recording) {
        PendingIntent contentIntent = PendingIntent.getActivity(context, 10,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags());

        Notification.Builder builder = builder(context, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF3B82F6)
                .setContentTitle(recording ? "正在录制屏幕…" : "悬浮助手正在运行")
                .setContentText(recording ? "点按“停止录屏”保存视频" : "悬浮球已就绪，点按查看设置")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (recording) {
            Intent stop = new Intent(context, FloatingService.class).setAction(FloatingService.ACTION_STOP_RECORDING);
            PendingIntent stopIntent = PendingIntent.getService(context, 11, stop, pendingFlags());
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stop), "停止录屏", stopIntent).build());
        }
        return builder.build();
    }

    /** 悬浮窗被系统停止后的“恢复”提醒。 */
    public static void showRestoreNotification(Context context) {
        try {
            ensureChannels(context);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 12,
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    pendingFlags());
            Notification notification = builder(context, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(0xFF3B82F6)
                    .setContentTitle("悬浮助手已停止")
                    .setContentText("点按重新打开并启动悬浮窗")
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build();
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(ID_ALERT, notification);
            }
        } catch (Throwable ignored) {
            // 通知权限被拒绝时静默失败
        }
    }

    private static Notification.Builder builder(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, channelId);
        }
        return new Notification.Builder(context);
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
