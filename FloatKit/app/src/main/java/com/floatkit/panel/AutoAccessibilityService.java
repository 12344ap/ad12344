package com.floatkit.panel;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：只用来执行系统全局动作（返回 / 主页 / 多任务 / 锁屏），
 * 不读取窗口内容，也不做任何数据采集。
 */
public class AutoAccessibilityService extends AccessibilityService {

    public static final int GLOBAL_BACK = 1;
    public static final int GLOBAL_HOME = 2;
    public static final int GLOBAL_RECENTS = 3;
    /** API 28 (Android 9) 起支持锁屏。 */
    public static final int GLOBAL_LOCK = 8;

    private static volatile AutoAccessibilityService instance;

    /** 服务是否已连接（已开启无障碍且在运行）。 */
    public static boolean isConnected() {
        return instance != null;
    }

    /** 判断无障碍服务是否已在系统设置中开启。 */
    public static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) {
                return false;
            }
            ComponentName target = new ComponentName(context, AutoAccessibilityService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                String entry = splitter.next();
                if (TextUtils.isEmpty(entry)) {
                    continue;
                }
                ComponentName component = ComponentName.unflattenFromString(entry);
                if (component != null
                        && target.getPackageName().equals(component.getPackageName())
                        && target.getClassName().equals(component.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 执行全局动作；服务未就绪时返回 false。 */
    public static boolean perform(int globalAction) {
        AutoAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        try {
            return service.performGlobalAction(globalAction);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理任何事件
    }

    @Override
    public void onInterrupt() {
        // 不需要处理
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
