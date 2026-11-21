package com.floatkit.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机 / 应用升级后自动恢复悬浮窗（需用户开启“开机自动启动”）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean upgraded = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !upgraded) {
            return;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.isEnabled() || !prefs.autoStart()) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        try {
            FloatingService.start(context);
            Watchdog.schedule(context);
        } catch (Throwable t) {
            Notifier.showRestoreNotification(context);
        }
    }
}
