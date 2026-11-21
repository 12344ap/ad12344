package com.floatkit.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 看门狗广播：服务被系统杀掉时尝试重新拉起。 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.floatkit.panel.action.WATCHDOG_TICK";
    public static final String ACTION_RESTART = "com.floatkit.panel.action.WATCHDOG_RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_TICK.equals(action) && !ACTION_RESTART.equals(action)) {
            return;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.isEnabled()) {
            Watchdog.cancel(context);
            return;
        }
        if (FloatingService.isRunning()) {
            Watchdog.schedule(context);
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            Watchdog.schedule(context);
            return;
        }
        try {
            FloatingService.start(context);
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务可能被拒，退化成通知提醒
            Notifier.showRestoreNotification(context);
        }
        // 无论是否成功，都重新布置下一次检查
        Watchdog.schedule(context);
    }
}
