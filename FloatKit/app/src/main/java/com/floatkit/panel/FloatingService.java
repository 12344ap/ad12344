package com.floatkit.panel;

import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.Manifest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 悬浮窗前台服务：
 * - 负责添加/移除悬浮球与菜单；
 * - 执行返回、主页、多任务、锁屏等动作（无障碍服务）；
 * - 调度截图 / 录屏（MediaProjection）；
 * - 前台服务常驻 + 看门狗 + 开机自启，保证长时间在后台存活。
 */
public class FloatingService extends Service implements OverlayUI.Host, CaptureEngine.Listener {

    public static final String ACTION_START = "com.floatkit.panel.action.START";
    public static final String ACTION_STOP = "com.floatkit.panel.action.STOP";
    public static final String ACTION_STOP_RECORDING = "com.floatkit.panel.action.STOP_RECORDING";

    private static final int NOTIFICATION_ID = 0x1001;
    private static final String WAKE_LOCK_TAG = "FloatKit:recording";

    private static volatile FloatingService instance;
    private static volatile boolean running;

    private Handler handler;
    private OverlayUI overlay;
    private CaptureEngine capture;
    private PowerManager.WakeLock wakeLock;
    private boolean foregroundStarted;

    public static FloatingService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 启动（或复用）前台服务。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, FloatingService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Android 12+ 某些后台场景不允许启动前台服务，退化为通知提醒
            Notifier.showRestoreNotification(context);
        }
    }

    /** 停止服务（不会清除“用户已开启”状态，由调用方决定）。 */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, FloatingService.class));
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        Notifier.ensureChannels(this);
        capture = new CaptureEngine(this, this);
        overlay = new OverlayUI(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        goForeground();

        if (ACTION_STOP.equals(action)) {
            stopByUser();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_RECORDING.equals(action)) {
            if (capture != null && capture.isRecording()) {
                capture.stopRecording(true);
            }
            return START_STICKY;
        }

        running = true;

        if (!Settings.canDrawOverlays(this)) {
            Ui.toast(this, "请先开启“悬浮窗权限”");
            Prefs.get(this).setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (overlay != null) {
            overlay.attach();
        }
        Watchdog.schedule(this);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (Prefs.get(this).isEnabled()) {
            Watchdog.scheduleQuickRestart(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        try {
            if (capture != null) {
                capture.release();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        if (overlay != null) {
            overlay.detach();
        }
        overlay = null;
        capture = null;
        releaseWakeLock();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        handler = null;
        if (Prefs.get(this).isEnabled()) {
            Watchdog.scheduleQuickRestart(this);
        } else {
            Watchdog.cancel(this);
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlay != null) {
            overlay.onConfigurationChanged();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private void goForeground() {
        try {
            Notification notification = Notifier.buildServiceNotification(this,
                    capture != null && capture.isRecording());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 显式声明 mediaProjection 类型：Android 10+ 要求 App 有该类型的前台服务
                // 才能调用 MediaProjection.getMediaProjection()。
                try {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    foregroundStarted = true;
                    return;
                } catch (Throwable ignored) {
                    // 退化到 2 参数版本（Android 10+ 会使用 manifest 中声明的类型）
                }
            }
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } catch (Throwable ignored) {
            // 通知被系统限制时也继续运行
        }
    }

    private void stopByUser() {
        Prefs.get(this).setEnabled(false);
        Watchdog.cancel(this);
        if (overlay != null) {
            overlay.detach();
        }
        if (capture != null && capture.isRecording()) {
            capture.stopRecording(true);
        }
        if (foregroundStarted) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {
                // 忽略
            }
            foregroundStarted = false;
        }
        running = false;
        stopSelf();
    }

    // ------------------------------------------------------------------
    // 菜单动作
    // ------------------------------------------------------------------

    @Override
    public void onAction(int action) {
        switch (action) {
            case OverlayUI.ACTION_BACK:
                performGlobal(AutoAccessibilityService.GLOBAL_BACK);
                break;
            case OverlayUI.ACTION_HOME:
                performGlobal(AutoAccessibilityService.GLOBAL_HOME);
                break;
            case OverlayUI.ACTION_RECENTS:
                performGlobal(AutoAccessibilityService.GLOBAL_RECENTS);
                break;
            case OverlayUI.ACTION_LOCK:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    Ui.toast(this, "锁屏需要 Android 9 及以上系统");
                } else {
                    performGlobal(AutoAccessibilityService.GLOBAL_LOCK);
                }
                break;
            case OverlayUI.ACTION_SCREENSHOT:
                requestCapture(ProjectionRequestActivity.ACTION_SCREENSHOT);
                break;
            case OverlayUI.ACTION_RECORD:
                if (capture != null && capture.isRecording()) {
                    capture.stopRecording(true);
                } else {
                    if (!canShowRecordingNotification()) {
                        Ui.toast(this, "请先在“悬浮助手”中授予通知权限，录屏后才能通过通知栏停止");
                        openSettings();
                        break;
                    }
                    requestCapture(ProjectionRequestActivity.ACTION_RECORD);
                }
                break;
            case OverlayUI.ACTION_SETTINGS:
                openSettings();
                break;
            default:
                break;
        }
    }

    private void performGlobal(int globalAction) {
        if (!AutoAccessibilityService.perform(globalAction)) {
            Ui.toast(this, "请先在“悬浮助手”中开启无障碍服务");
        }
    }

    private void requestCapture(String action) {
        if (capture != null && capture.isRecording()) {
            Ui.toast(this, "正在录屏，请先停止录屏");
            return;
        }
        Intent intent = new Intent(this, ProjectionRequestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ProjectionRequestActivity.EXTRA_ACTION, action);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Ui.toast(this, "无法打开屏幕共享授权窗口");
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Ui.toast(this, "无法打开设置页");
        }
    }

    /** 由 ProjectionRequestActivity 回调：拿到系统授权结果后开始截图/录屏。 */
    public void onProjectionResult(final String action, final int resultCode, final Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Ui.toast(this, "已取消屏幕共享授权");
            return;
        }
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (ProjectionRequestActivity.ACTION_SCREENSHOT.equals(action)) {
                    // 先把悬浮球藏起来，避免截进画面
                    if (overlay != null) {
                        overlay.setBubbleVisible(false);
                    }
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (capture != null) {
                                capture.captureScreenshot(resultCode, data);
                            }
                        }
                    }, 220L);
                } else if (capture != null) {
                    if (!canShowRecordingNotification()) {
                        Ui.toast(this, "请先在“悬浮助手”中授予通知权限，录屏后才能通过通知栏停止");
                        openSettings();
                        return;
                    }
                    capture.startRecording(resultCode, data);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 捕获回调
    // ------------------------------------------------------------------

    @Override
    public void onScreenshotFinished(final boolean success, final String message) {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) {
                    overlay.setBubbleVisible(true);
                }
                Ui.toast(FloatingService.this, message);
            }
        });
    }

    @Override
    public void onRecordingStarted() {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                acquireWakeLock();
                if (overlay != null) {
                    overlay.setRecording(true);
                    if (Prefs.get(FloatingService.this).hideBubbleWhileRecording()
                            && canShowRecordingNotification()) {
                        overlay.setBubbleVisible(false);
                    }
                }
                goForeground();
            }
        });
    }

    @Override
    public void onRecordingFinished(final boolean success, final String message) {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                releaseWakeLock();
                if (overlay != null) {
                    overlay.setRecording(false);
                    overlay.setBubbleVisible(true);
                }
                goForeground();
                Ui.toast(FloatingService.this, message);
            }
        });
    }

    /** Android 13+ 未授予通知权限时，录屏将没有可见的“停止”入口，因此先拦截。 */
    private boolean canShowRecordingNotification() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------
    // WakeLock：录屏期间防止 CPU 休眠导致录制中断
    // ------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
