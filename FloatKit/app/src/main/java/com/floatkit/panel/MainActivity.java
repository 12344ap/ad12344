package com.floatkit.panel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 设置主界面：权限引导 + 行为设置 + 启动/停止悬浮窗。 */
public class MainActivity extends Activity {

    private static final int REQUEST_RUNTIME_PERMISSIONS = 0x301;

    private TextView tvStatus;
    private TextView tvOverlay;
    private TextView tvA11y;
    private TextView tvRuntime;
    private TextView tvBattery;
    private Button btnOverlay;
    private Button btnA11y;
    private Button btnRuntime;
    private Button btnBattery;
    private Button btnStart;
    private Button btnStop;
    private Switch swBoot;
    private Switch swSnap;
    private Switch swRecordHide;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean binding;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindListeners();
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private void bindViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvOverlay = findViewById(R.id.tv_overlay);
        tvA11y = findViewById(R.id.tv_a11y);
        tvRuntime = findViewById(R.id.tv_runtime);
        tvBattery = findViewById(R.id.tv_battery);
        btnOverlay = findViewById(R.id.btn_overlay);
        btnA11y = findViewById(R.id.btn_a11y);
        btnRuntime = findViewById(R.id.btn_runtime);
        btnBattery = findViewById(R.id.btn_battery);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        swBoot = findViewById(R.id.sw_boot);
        swSnap = findViewById(R.id.sw_snap);
        swRecordHide = findViewById(R.id.sw_record_hide);
    }

    private void bindListeners() {
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnA11y.setOnClickListener(v -> openAccessibilitySettings());
        btnRuntime.setOnClickListener(v -> requestRuntimePermissions());
        btnBattery.setOnClickListener(v -> requestBatteryExemption());
        btnStart.setOnClickListener(v -> startFloating());
        btnStop.setOnClickListener(v -> stopFloating());

        CompoundButton.OnCheckedChangeListener listener = (button, checked) -> {
            if (binding) {
                return;
            }
            Prefs prefs = Prefs.get(this);
            if (button.getId() == R.id.sw_boot) {
                prefs.setAutoStart(checked);
            } else if (button.getId() == R.id.sw_snap) {
                prefs.setSnapEdge(checked);
            } else if (button.getId() == R.id.sw_record_hide) {
                prefs.setHideBubbleWhileRecording(checked);
            }
        };
        swBoot.setOnCheckedChangeListener(listener);
        swSnap.setOnCheckedChangeListener(listener);
        swRecordHide.setOnCheckedChangeListener(listener);
    }

    private void loadSettings() {
        binding = true;
        Prefs prefs = Prefs.get(this);
        swBoot.setChecked(prefs.autoStart());
        swSnap.setChecked(prefs.snapEdge());
        swRecordHide.setChecked(prefs.hideBubbleWhileRecording());
        binding = false;
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private void refresh() {
        Prefs prefs = Prefs.get(this);

        boolean overlay = Settings.canDrawOverlays(this);
        setStatus(tvOverlay, overlay ? "已开启" : "未开启（必需）", overlay);
        btnOverlay.setText(overlay ? "已开启" : "开启");
        btnOverlay.setEnabled(!overlay);

        boolean a11y = AutoAccessibilityService.isEnabled(this);
        setStatus(tvA11y, a11y ? "已开启" : "未开启（返回 / 主页 / 锁屏需要）", a11y);
        btnA11y.setText(a11y ? "已开启" : "开启");
        btnA11y.setEnabled(!a11y);

        boolean notification = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notification = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean storage = true;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            storage = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        boolean allRuntime = notification && mic && storage;
        StringBuilder detail = new StringBuilder();
        detail.append(notification ? "通知 ✓" : "通知 ✗").append("    ");
        detail.append(mic ? "麦克风 ✓" : "麦克风 ✗");
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            detail.append("    ").append(storage ? "存储 ✓" : "存储 ✗");
        }
        tvRuntime.setText(allRuntime ? "已授予（录屏可带声音）" : detail.toString());
        tvRuntime.setTextColor(getColor(allRuntime ? R.color.ok : R.color.warn));
        btnRuntime.setText(allRuntime ? "已授权" : "授权");
        btnRuntime.setEnabled(!allRuntime);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        setStatus(tvBattery, ignoring ? "已加入白名单" : "未加入电池优化白名单", ignoring);
        btnBattery.setText(ignoring ? "已设置" : "设置");
        btnBattery.setEnabled(!ignoring);

        boolean running = FloatingService.isRunning();
        tvStatus.setText(running ? "运行中" : "未运行");
        tvStatus.setTextColor(getColor(running ? R.color.ok : R.color.text_secondary));
        btnStop.setEnabled(running || prefs.isEnabled());
    }

    private void setStatus(TextView view, String text, boolean ok) {
        view.setText(text);
        view.setTextColor(getColor(ok ? R.color.ok : R.color.warn));
    }

    // ------------------------------------------------------------------
    // 权限入口
    // ------------------------------------------------------------------

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable ignored) {
                Ui.toast(this, "无法打开悬浮窗权限设置，请手动前往系统设置");
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            Ui.toast(this, "无法打开无障碍设置，请手动前往系统设置");
        }
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (permissions.isEmpty()) {
            Ui.toast(this, "权限已全部授予");
            refresh();
            return;
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_RUNTIME_PERMISSIONS);
    }

    private void requestBatteryExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                Ui.toast(this, "请手动在系统设置中关闭电池优化");
            }
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                Ui.toast(this, "请手动在系统设置中允许后台运行");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // 启动 / 停止
    // ------------------------------------------------------------------

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Ui.toast(this, "请先开启悬浮窗权限");
            openOverlaySettings();
            return;
        }
        Prefs.get(this).setEnabled(true);
        FloatingService.start(this);
        Ui.toast(this, "悬浮窗已启动，正在返回桌面…");
        refresh();
        handler.postDelayed(refreshRunnable, 700L);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 回到桌面，用户马上能看到悬浮球
                moveTaskToBack(true);
            }
        }, 400L);
    }

    private void stopFloating() {
        Prefs.get(this).setEnabled(false);
        FloatingService.stop(this);
        Ui.toast(this, "已停止悬浮窗");
        handler.postDelayed(refreshRunnable, 500L);
    }
}
