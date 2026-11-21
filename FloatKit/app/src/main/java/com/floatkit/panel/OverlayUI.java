package com.floatkit.panel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮球的窗口管理：
 * - 收起态：一颗可拖动、可点击的圆形悬浮球；
 * - 展开态：悬浮球旁边的功能菜单（返回 / 主页 / 多任务 / 截屏 / 录屏 / 锁屏）。
 */
public class OverlayUI {

    public static final int ACTION_BACK = 1;
    public static final int ACTION_HOME = 2;
    public static final int ACTION_RECENTS = 3;
    public static final int ACTION_SCREENSHOT = 4;
    public static final int ACTION_RECORD = 5;
    public static final int ACTION_LOCK = 6;
    public static final int ACTION_SETTINGS = 7;

    /** 菜单按钮点击后回调给服务执行。 */
    public interface Host {
        void onAction(int action);
    }

    private static final int BUBBLE_SIZE_DP = 56;
    private static final int PANEL_WIDTH_DP = 232;

    private static final int ACCENT = 0xF23B82F6;
    private static final int TEXT_MAIN = 0xFFFFFFFF;
    private static final int TEXT_SUB = 0xFFB9C4D3;

    private final Context context;
    private final Host host;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout bubble;
    private WindowManager.LayoutParams bubbleParams;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private ImageView recordIcon;
    private TextView recordLabel;

    private int screenW;
    private int screenH;
    private int bubbleSize;
    private int panelWidth;
    private int panelHeight;
    private int touchSlop;

    private boolean attached;
    private boolean bubbleAdded;
    private boolean panelVisible;
    private boolean recording;
    private boolean destroyed;

    private final Runnable collapseRunnable = new Runnable() {
        @Override
        public void run() {
            collapse();
        }
    };

    public OverlayUI(Context context, Host host) {
        this.context = context;
        this.host = host;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.bubbleSize = Ui.dp(context, BUBBLE_SIZE_DP);
        this.panelWidth = Ui.dp(context, PANEL_WIDTH_DP);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        refreshMetrics();
    }

    public boolean isAttached() {
        return attached;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void attach() {
        if (destroyed) {
            return;
        }
        if (attached) {
            if (!bubbleAdded) {
                setBubbleVisible(true);
            }
            return;
        }
        if (wm == null) {
            return;
        }
        refreshMetrics();
        if (bubble == null) {
            buildBubble();
        }
        if (bubbleParams == null) {
            bubbleParams = createBubbleParams();
        }
        try {
            wm.addView(bubble, bubbleParams);
            bubbleAdded = true;
            attached = true;
        } catch (Throwable ignored) {
            // 没有悬浮窗权限时会走到这里
            bubbleAdded = false;
            attached = false;
        }
    }

    public void detach() {
        destroyed = true;
        handler.removeCallbacks(collapseRunnable);
        collapse();
        if (bubble != null && bubbleAdded) {
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        bubbleAdded = false;
        attached = false;
    }

    /** 截图 / 录屏时临时隐藏悬浮球，结束后恢复。 */
    public void setBubbleVisible(boolean visible) {
        if (destroyed || wm == null || bubble == null) {
            return;
        }
        if (!visible) {
            collapse();
        }
        if (visible) {
            if (bubbleAdded) {
                return;
            }
            try {
                wm.addView(bubble, bubbleParams);
                bubbleAdded = true;
            } catch (Throwable ignored) {
                bubbleAdded = false;
            }
        } else {
            if (!bubbleAdded) {
                return;
            }
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
                // 忽略
            }
            bubbleAdded = false;
        }
    }

    public void setRecording(boolean value) {
        if (destroyed) {
            return;
        }
        recording = value;
        if (recordIcon != null) {
            recordIcon.setImageResource(value ? R.drawable.ic_stop : R.drawable.ic_record);
            recordIcon.setColorFilter(value ? 0xFFFF5252 : TEXT_MAIN);
        }
        if (recordLabel != null) {
            recordLabel.setText(value ? "停止" : "录屏");
        }
    }

    public void onConfigurationChanged() {
        if (!attached || bubble == null || bubbleParams == null) {
            return;
        }
        refreshMetrics();
        bubbleParams.x = Ui.clamp(bubbleParams.x, 0, screenW - bubbleSize);
        bubbleParams.y = Ui.clamp(bubbleParams.y, topInset(), screenH - bubbleSize - Ui.dp(context, 8));
        try {
            wm.updateViewLayout(bubble, bubbleParams);
        } catch (Throwable ignored) {
            // 忽略
        }
        if (panelVisible) {
            repositionPanel();
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    private void buildBubble() {
        FrameLayout root = new FrameLayout(context);
        GradientDrawable background = Ui.circle(context, ACCENT, 0x66FFFFFF, 1.5f);
        root.setBackground(background);
        root.setContentDescription("悬浮助手，点击展开菜单，拖动可移动");
        root.setAlpha(0.96f);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_bubble);
        icon.setColorFilter(TEXT_MAIN);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(context, 26), Ui.dp(context, 26), Gravity.CENTER));

        root.setOnTouchListener(new DragTouch(root, true));
        bubble = root;
    }

    private WindowManager.LayoutParams createBubbleParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize, overlayType(), windowFlags(), PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int x = Prefs.get(context).bubbleX();
        int y = Prefs.get(context).bubbleY();
        if (x < 0) {
            x = screenW - bubbleSize - Ui.dp(context, 10);
        }
        if (y < 0) {
            y = Math.max(topInset(), (int) (screenH * 0.35f));
        }
        lp.x = Ui.clamp(x, 0, Math.max(0, screenW - bubbleSize));
        lp.y = Ui.clamp(y, topInset(), Math.max(topInset(), screenH - bubbleSize - Ui.dp(context, 8)));
        return lp;
    }

    private void onBubbleMoved() {
        if (bubbleParams == null) {
            return;
        }
        if (Prefs.get(context).snapEdge()) {
            snapToEdge();
        }
        Prefs.get(context).saveBubblePosition(bubbleParams.x, bubbleParams.y);
        if (panelVisible) {
            repositionPanel();
        }
    }

    private void snapToEdge() {
        if (bubbleParams == null || wm == null) {
            return;
        }
        int margin = Ui.dp(context, 6);
        boolean toRight = bubbleParams.x + bubbleSize / 2 > screenW / 2;
        final int targetX = toRight ? screenW - bubbleSize - margin : margin;
        final int startX = bubbleParams.x;
        if (startX == targetX) {
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(startX, targetX);
        animator.setDuration(180L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (bubbleParams == null) {
                    return;
                }
                bubbleParams.x = (int) animation.getAnimatedValue();
                try {
                    wm.updateViewLayout(bubble, bubbleParams);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (bubbleParams != null) {
                    Prefs.get(context).saveBubblePosition(bubbleParams.x, bubbleParams.y);
                }
                if (panelVisible) {
                    repositionPanel();
                }
            }
        });
        animator.start();
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    private void togglePanel() {
        if (panelVisible) {
            collapse();
        } else {
            expand();
        }
    }

    private void expand() {
        if (!attached || wm == null || bubbleParams == null) {
            return;
        }
        refreshMetrics();
        if (panel == null) {
            panel = buildPanel();
        }
        panelWidth = Ui.dp(context, PANEL_WIDTH_DP);
        panel.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panelHeight = panel.getMeasuredHeight();

        boolean bubbleOnRight = bubbleParams.x + bubbleSize / 2 > screenW / 2;
        int gap = Ui.dp(context, 8);
        int x = bubbleOnRight ? bubbleParams.x - panelWidth - gap : bubbleParams.x + bubbleSize + gap;
        x = Ui.clamp(x, Ui.dp(context, 4), Math.max(Ui.dp(context, 4), screenW - panelWidth - Ui.dp(context, 4)));
        int y = bubbleParams.y + bubbleSize / 2 - panelHeight / 2;
        y = Ui.clamp(y, topInset(), Math.max(topInset(), screenH - panelHeight - Ui.dp(context, 8)));

        panelParams = new WindowManager.LayoutParams(
                panelWidth, panelHeight, overlayType(), windowFlags(), PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = x;
        panelParams.y = y;

        try {
            wm.addView(panel, panelParams);
        } catch (Throwable t) {
            panelParams = null;
            return;
        }
        panelVisible = true;
        panel.setPivotX(bubbleOnRight ? panelWidth : 0f);
        panel.setPivotY(Ui.dp(context, 26));
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L).start();
        scheduleCollapse();
    }

    public void collapse() {
        handler.removeCallbacks(collapseRunnable);
        if (panel != null && panelVisible && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        panelVisible = false;
        panelParams = null;
    }

    private void repositionPanel() {
        if (!panelVisible || panelParams == null || panel == null || bubbleParams == null || wm == null) {
            return;
        }
        refreshMetrics();
        boolean bubbleOnRight = bubbleParams.x + bubbleSize / 2 > screenW / 2;
        int gap = Ui.dp(context, 8);
        int x = bubbleOnRight ? bubbleParams.x - panelWidth - gap : bubbleParams.x + bubbleSize + gap;
        x = Ui.clamp(x, Ui.dp(context, 4), Math.max(Ui.dp(context, 4), screenW - panelWidth - Ui.dp(context, 4)));
        int y = Ui.clamp(bubbleParams.y + bubbleSize / 2 - panelHeight / 2,
                topInset(), Math.max(topInset(), screenH - panelHeight - Ui.dp(context, 8)));
        panelParams.x = x;
        panelParams.y = y;
        try {
            wm.updateViewLayout(panel, panelParams);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable);
        int seconds = Prefs.get(context).collapseSeconds();
        if (seconds > 0) {
            handler.postDelayed(collapseRunnable, seconds * 1000L);
        }
    }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = Ui.roundRect(context, 0xF2181B22, 20f);
        background.setStroke(Math.max(1, Ui.dp(context, 1)), 0x33FFFFFF);
        root.setBackground(background);
        int padH = Ui.dp(context, 10);
        root.setPadding(padH, Ui.dp(context, 6), padH, Ui.dp(context, 10));

        // 顶部：拖动条 + 标题 + 设置 + 关闭
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView handle = new ImageView(context);
        handle.setImageResource(R.drawable.ic_drag);
        handle.setColorFilter(0x55FFFFFF);
        header.addView(handle, new LinearLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16)));

        TextView title = new TextView(context);
        title.setText("悬浮菜单");
        title.setTextSize(11f);
        title.setTextColor(0xFF9AA7B8);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = Ui.dp(context, 4);
        header.addView(title, titleParams);

        ImageView settings = iconButton(R.drawable.ic_settings, 0xCCFFFFFF);
        settings.setContentDescription("打开设置");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
                host.onAction(ACTION_SETTINGS);
            }
        });
        header.addView(settings, new LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)));

        ImageView close = iconButton(R.drawable.ic_close, 0xCCFFFFFF);
        close.setContentDescription("收起菜单");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)));

        header.setOnTouchListener(new DragTouch(root, false));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 34)));

        // 功能按钮：3 x 2
        LinearLayout row1 = makeRow();
        row1.addView(makeItem(R.drawable.ic_back, "返回", ACTION_BACK), cellParams());
        row1.addView(makeItem(R.drawable.ic_home, "主页", ACTION_HOME), cellParams());
        row1.addView(makeItem(R.drawable.ic_recents, "多任务", ACTION_RECENTS), cellParams());

        LinearLayout row2 = makeRow();
        row2.addView(makeItem(R.drawable.ic_screenshot, "截屏", ACTION_SCREENSHOT), cellParams());
        row2.addView(makeItem(R.drawable.ic_record, "录屏", ACTION_RECORD), cellParams());
        row2.addView(makeItem(R.drawable.ic_lock, "锁屏", ACTION_LOCK), cellParams());

        root.addView(row1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 手指按住菜单时暂停自动收起
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    scheduleCollapse();
                }
                return false;
            }
        });
        return root;
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams cellParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View makeItem(int iconRes, String label, final int action) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(context, 4);
        cell.setPadding(p, Ui.dp(context, 6), p, Ui.dp(context, 6));
        cell.setContentDescription(label);

        FrameLayout circle = new FrameLayout(context);
        GradientDrawable circleBg = Ui.circle(context, 0x22FFFFFF, 0x33FFFFFF, 1f);
        circle.setBackground(circleBg);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT_MAIN);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        circle.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(context, 22), Ui.dp(context, 22), Gravity.CENTER));
        cell.addView(circle, new LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)));

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(11f);
        text.setTextColor(TEXT_SUB);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = Ui.dp(context, 4);
        cell.addView(text, textParams);

        if (action == ACTION_RECORD) {
            recordIcon = icon;
            recordLabel = text;
            if (recording) {
                icon.setImageResource(R.drawable.ic_stop);
                icon.setColorFilter(0xFFFF5252);
                text.setText("停止");
            }
        }

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
                host.onAction(action);
            }
        });
        return cell;
    }

    private ImageView iconButton(int iconRes, int color) {
        ImageView image = new ImageView(context);
        image.setImageResource(iconRes);
        image.setColorFilter(color);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = Ui.dp(context, 7);
        image.setPadding(padding, padding, padding, padding);
        return image;
    }

    // ------------------------------------------------------------------
    // 触摸拖动
    // ------------------------------------------------------------------

    private final class DragTouch implements View.OnTouchListener {

        private final View windowView;
        private final boolean isBubble;

        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;

        DragTouch(View windowView, boolean isBubble) {
            this.windowView = windowView;
            this.isBubble = isBubble;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WindowManager.LayoutParams lp = isBubble ? bubbleParams : panelParams;
            if (lp == null || windowView == null || wm == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    if (!isBubble) {
                        handler.removeCallbacks(collapseRunnable);
                    }
                    windowView.setAlpha(isBubble ? 0.8f : 0.96f);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        moved = true;
                    }
                    if (moved) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        clampWindow(lp, isBubble);
                        try {
                            wm.updateViewLayout(windowView, lp);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                    windowView.setAlpha(isBubble ? 0.96f : 1f);
                    if (up && !moved && isBubble) {
                        togglePanel();
                    } else if (up && moved && isBubble) {
                        onBubbleMoved();
                    }
                    if (!isBubble) {
                        scheduleCollapse();
                    }
                    return true;
                }

                default:
                    return false;
            }
        }
    }

    private void clampWindow(WindowManager.LayoutParams lp, boolean isBubbleWindow) {
        int w = isBubbleWindow ? bubbleSize : panelWidth;
        int h = isBubbleWindow ? bubbleSize : panelHeight;
        lp.x = Ui.clamp(lp.x, 0, Math.max(0, screenW - w));
        int maxY = Math.max(topInset(), screenH - h - Ui.dp(context, 8));
        lp.y = Ui.clamp(lp.y, topInset(), maxY);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void refreshMetrics() {
        Point size = Ui.screenSize(context);
        screenW = size.x;
        screenH = size.y;
        if (screenW <= 0) {
            screenW = context.getResources().getDisplayMetrics().widthPixels;
        }
        if (screenH <= 0) {
            screenH = context.getResources().getDisplayMetrics().heightPixels;
        }
    }

    private int topInset() {
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBar = resId > 0
                ? context.getResources().getDimensionPixelSize(resId)
                : Ui.dp(context, 28);
        return statusBar + Ui.dp(context, 4);
    }

    private static int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static int windowFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }
}
