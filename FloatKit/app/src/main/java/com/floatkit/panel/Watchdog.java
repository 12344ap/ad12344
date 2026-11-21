package com.floatkit.panel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** 后台存活看门狗：定时唤醒检查悬浮窗服务是否还在运行。 */
public final class Watchdog {

    /** 检查间隔：15 分钟（系统省电策略下可能被延后，但能显著提高存活率）。 */
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    private static final int REQUEST_TICK = 21;
    private static final int REQUEST_RESTART = 22;

    private Watchdog() {
    }

    public static void schedule(Context context) {
        AlarmManager am = alarm(context);
        if (am == null) {
            return;
        }
        try {
            PendingIntent tick = pending(context, WatchdogReceiver.ACTION_TICK, REQUEST_TICK);
            am.cancel(tick);
            // 单次“允许空闲也唤醒”的闹钟，由接收器在处理后重新布置下一次，
            // 比 setInexactRepeating 更能对抗 Doze / App Standby。
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, tick);
        } catch (Throwable ignored) {
            // 部分定制 ROM 限制闹钟，忽略
        }
    }

    /** 服务被系统回收后尽快尝试自启一次。 */
    public static void scheduleQuickRestart(Context context) {
        AlarmManager am = alarm(context);
        if (am == null) {
            return;
        }
        try {
            PendingIntent restart = pending(context, WatchdogReceiver.ACTION_RESTART, REQUEST_RESTART);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000L, restart);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = alarm(context);
        if (am == null) {
            return;
        }
        try {
            am.cancel(pending(context, WatchdogReceiver.ACTION_TICK, REQUEST_TICK));
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            am.cancel(pending(context, WatchdogReceiver.ACTION_RESTART, REQUEST_RESTART));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static AlarmManager alarm(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent pending(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, WatchdogReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
