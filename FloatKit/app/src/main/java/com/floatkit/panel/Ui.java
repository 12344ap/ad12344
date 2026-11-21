package com.floatkit.panel;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 通用小工具：dp 换算、Toast、圆角背景、屏幕尺寸等。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context context, String message) {
        if (context == null || message == null || message.isEmpty()) {
            return;
        }
        try {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 某些 ROM 在后台线程弹 Toast 会抛异常，忽略即可
        }
    }

    public static GradientDrawable roundRect(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setColor(color);
        return drawable;
    }

    public static GradientDrawable circle(Context context, int color, int strokeColor, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (strokeDp > 0f) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return drawable;
    }

    /** 获取屏幕真实像素尺寸（含状态栏 / 导航栏区域）。 */
    public static Point screenSize(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        if (wm == null) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            point.set(dm.widthPixels, dm.heightPixels);
            return point;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                point.set(bounds.width(), bounds.height());
                return point;
            } catch (Throwable ignored) {
                // 落到下面的兼容分支
            }
        }
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            point.set(dm.widthPixels, dm.heightPixels);
        } catch (Throwable t) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            point.set(dm.widthPixels, dm.heightPixels);
        }
        return point;
    }

    public static int screenDpi(Context context) {
        return context.getResources().getDisplayMetrics().densityDpi;
    }

    public static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
