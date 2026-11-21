package com.floatkit.panel;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 轻量配置存储（SharedPreferences）。
 * 记录：悬浮窗开关、开机自启、贴边、录屏隐藏悬浮球、悬浮球坐标等。
 */
public final class Prefs {

    private static final String FILE = "floatkit_settings";

    private static final String K_ENABLED = "enabled";
    private static final String K_AUTO_START = "auto_start";
    private static final String K_SNAP_EDGE = "snap_edge";
    private static final String K_HIDE_BUBBLE_RECORDING = "hide_bubble_recording";
    private static final String K_BUBBLE_X = "bubble_x";
    private static final String K_BUBBLE_Y = "bubble_y";
    private static final String K_COLLAPSE_SECONDS = "collapse_seconds";

    private static volatile Prefs instance;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 用户是否希望悬浮窗处于开启状态（停止后为 false，看门狗不会自动拉起）。 */
    public boolean isEnabled() {
        return sp.getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean value) {
        sp.edit().putBoolean(K_ENABLED, value).apply();
    }

    public boolean autoStart() {
        return sp.getBoolean(K_AUTO_START, true);
    }

    public void setAutoStart(boolean value) {
        sp.edit().putBoolean(K_AUTO_START, value).apply();
    }

    public boolean snapEdge() {
        return sp.getBoolean(K_SNAP_EDGE, true);
    }

    public void setSnapEdge(boolean value) {
        sp.edit().putBoolean(K_SNAP_EDGE, value).apply();
    }

    public boolean hideBubbleWhileRecording() {
        return sp.getBoolean(K_HIDE_BUBBLE_RECORDING, true);
    }

    public void setHideBubbleWhileRecording(boolean value) {
        sp.edit().putBoolean(K_HIDE_BUBBLE_RECORDING, value).apply();
    }

    public int bubbleX() {
        return sp.getInt(K_BUBBLE_X, -1);
    }

    public int bubbleY() {
        return sp.getInt(K_BUBBLE_Y, -1);
    }

    public void saveBubblePosition(int x, int y) {
        sp.edit().putInt(K_BUBBLE_X, x).putInt(K_BUBBLE_Y, y).apply();
    }

    /** 菜单展开后自动收起的时间（秒），<=0 表示不自动收起。 */
    public int collapseSeconds() {
        return sp.getInt(K_COLLAPSE_SECONDS, 6);
    }
}
