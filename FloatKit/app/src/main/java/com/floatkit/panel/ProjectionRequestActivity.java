package com.floatkit.panel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * 透明中转页：弹出系统的“屏幕共享 / 录制”授权框，把结果回传给 FloatingService。
 * MediaProjection 授权必须由 Activity 发起，服务本身无法直接申请。
 */
public class ProjectionRequestActivity extends Activity {

    public static final String EXTRA_ACTION = "extra_action";
    public static final String ACTION_SCREENSHOT = "screenshot";
    public static final String ACTION_RECORD = "record";

    private static final int REQUEST_CODE = 0x501;

    private String pendingAction;
    private boolean requested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingAction = getIntent() != null ? getIntent().getStringExtra(EXTRA_ACTION) : ACTION_RECORD;
        // 即使 Activity 被系统重建（如“不保留活动”开启），也重新发起授权请求。
        requestProjection();
    }

    private void requestProjection() {
        if (requested) {
            return;
        }
        requested = true;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                finishWith(null, RESULT_CANCELED, null);
                return;
            }
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE);
        } catch (Throwable t) {
            Ui.toast(this, "无法申请屏幕录制授权：" + t.getClass().getSimpleName());
            finishWith(null, RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            finishWith(pendingAction, resultCode, data);
        }
    }

    private void finishWith(String action, int resultCode, Intent data) {
        FloatingService service = FloatingService.getInstance();
        if (service != null && action != null) {
            try {
                service.onProjectionResult(action, resultCode, data);
            } catch (Throwable ignored) {
                // 忽略
            }
        } else if (resultCode == RESULT_OK) {
            // 服务已销毁，授权结果作废
            Ui.toast(this, "悬浮窗服务未运行，请重新启动后再试");
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        finishWith(null, RESULT_CANCELED, null);
        super.onBackPressed();
    }
}
