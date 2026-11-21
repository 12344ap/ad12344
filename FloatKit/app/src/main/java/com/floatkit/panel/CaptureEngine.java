package com.floatkit.panel;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 屏幕捕获引擎：
 * - 截图：MediaProjection + ImageReader，保存 PNG 到相册；
 * - 录屏：MediaProjection + MediaRecorder，保存 MP4 到相册。
 * 每次截图/录屏都使用独立的 MediaProjection 会话（兼容 Android 14+ 的一次性授权）。
 */
public class CaptureEngine {

    public interface Listener {
        void onScreenshotFinished(boolean success, String message);

        void onRecordingStarted();

        void onRecordingFinished(boolean success, String message);
    }

    private static final String TAG = "FloatKit";

    private final Context context;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader imageReader;
    private MediaRecorder recorder;
    private File recordFile;
    private boolean recording;
    private boolean screenshotPending;
    private boolean released;
    private Runnable screenshotTimeout;

    public CaptureEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    /** 释放后不再回调宿主，且拒绝再派发任务，避免服务销毁后出现“幽灵悬浮球”或 RejectedExecutionException。 */
    private void notifyScreenshot(boolean success, String message) {
        Listener l = listener;
        if (l != null && !released) {
            l.onScreenshotFinished(success, message);
        }
    }

    private void notifyRecordingStarted() {
        Listener l = listener;
        if (l != null && !released) {
            l.onRecordingStarted();
        }
    }

    private void notifyRecordingFinished(boolean success, String message) {
        Listener l = listener;
        if (l != null && !released) {
            l.onRecordingFinished(success, message);
        }
    }

    private void runIo(Runnable task) {
        if (io.isShutdown()) {
            return;
        }
        try {
            io.execute(task);
        } catch (Throwable ignored) {
            // 已 shutdown 或线程池拒绝时静默丢弃（保存失败不崩溃）
        }
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    public void captureScreenshot(int resultCode, Intent data) {
        if (recording) {
            notifyScreenshot(false, "录屏中无法截图");
            return;
        }
        stopProjection();
        MediaProjection mp;
        try {
            mp = obtain(resultCode, data);
            projection = mp;
        } catch (Throwable t) {
            notifyScreenshot(false, "截图失败：" + describe(t));
            return;
        }
        try {
            Point size = screenPoint();
            int width = size.x;
            int height = size.y;
            ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader ir) {
                    handleImageAvailable();
                }
            }, main);
            imageReader = reader;
            display = mp.createVirtualDisplay("FloatKitShot", width, height, Ui.screenDpi(context),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
            screenshotPending = true;
            screenshotTimeout = new Runnable() {
                @Override
                public void run() {
                    if (imageReader != null) {
                        screenshotPending = false;
                        stopProjection();
                        notifyScreenshot(false, "截图超时，请重试");
                    }
                }
            };
            main.postDelayed(screenshotTimeout, 5000L);
        } catch (Throwable t) {
            stopProjection();
            notifyScreenshot(false, "截图失败：" + describe(t));
        }
    }

    private void handleImageAvailable() {
        ImageReader reader = imageReader;
        if (reader == null) {
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Throwable ignored) {
            // 忽略
        }
        if (image == null) {
            return;
        }
        screenshotPending = false;
        if (screenshotTimeout != null) {
            main.removeCallbacks(screenshotTimeout);
            screenshotTimeout = null;
        }
        Bitmap bitmap = null;
        try {
            bitmap = imageToBitmap(image);
        } catch (Throwable t) {
            Log.w(TAG, "decode screenshot failed", t);
        } finally {
            try {
                image.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        stopProjection();
        if (bitmap == null) {
            notifyScreenshot(false, "截图失败：无法读取画面");
            return;
        }
        final Bitmap result = bitmap;
        runIo(new Runnable() {
            @Override
            public void run() {
                String message = null;
                try {
                    message = saveBitmap(result);
                } catch (Throwable t) {
                    Log.w(TAG, "save screenshot failed", t);
                }
                final String finalMessage = message;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            result.recycle();
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                        notifyScreenshot(finalMessage != null,
                                finalMessage != null ? finalMessage : "保存截图失败");
                    }
                });
            }
        });
    }

    private Bitmap imageToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int bitmapWidth = width + (rowPadding > 0 ? rowPadding / pixelStride : 0);
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (bitmapWidth != width) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    private String saveBitmap(Bitmap bitmap) throws IOException {
        String name = "FloatKit_" + Ui.timestamp() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FloatKit");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建相册条目");
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法写入相册");
                }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            } catch (IOException e) {
                context.getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            return "截图已保存到相册 / Pictures/FloatKit";
        }
        File dir = publicMediaDir(Environment.DIRECTORY_PICTURES);
        if (dir != null) {
            File file = new File(dir, name);
            try (OutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
            return "截图已保存：" + file.getAbsolutePath();
        }
        File fallback = appMediaDir(Environment.DIRECTORY_PICTURES);
        if (fallback == null) {
            throw new IOException("没有可写目录");
        }
        File file = new File(fallback, name);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
        }
        return "截图已保存：" + file.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // 录屏
    // ------------------------------------------------------------------

    public void startRecording(int resultCode, Intent data) {
        if (recording) {
            return;
        }
        stopProjection();
        MediaProjection mp;
        try {
            mp = obtain(resultCode, data);
            projection = mp;
        } catch (Throwable t) {
            notifyRecordingFinished(false, "录屏失败：" + describe(t));
            return;
        }
        MediaRecorder mediaRecorder = null;
        File file = null;
        File dir;
        try {
            Point size = screenPoint();
            int width = size.x;
            int height = size.y;
            if ((width & 1) != 0) {
                width--;
            }
            if ((height & 1) != 0) {
                height--;
            }
            if (width > 1920) {
                float scale = 1920f / width;
                width = ((int) (width * scale)) & ~1;
                height = ((int) (height * scale)) & ~1;
            }
            if (width < 2 || height < 2) {
                throw new IOException("屏幕尺寸异常");
            }
            dir = appMediaDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) {
                throw new IOException("没有可写目录");
            }
            file = new File(dir, "FloatKit_" + Ui.timestamp() + ".mp4");
            boolean withAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            try {
                mediaRecorder = createRecorder(file, width, height, withAudio);
            } catch (Throwable first) {
                if (!withAudio) {
                    throw first;
                }
                // 麦克风被占用时退回无声录制
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "cannot delete failed record file");
                }
                file = new File(dir, "FloatKit_" + Ui.timestamp() + ".mp4");
                mediaRecorder = createRecorder(file, width, height, false);
            }
            VirtualDisplay vd = mp.createVirtualDisplay("FloatKitRec", width, height, Ui.screenDpi(context),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.getSurface(), null, main);
            mediaRecorder.start();
            recorder = mediaRecorder;
            recordFile = file;
            display = vd;
            recording = true;
            notifyRecordingStarted();
        } catch (Throwable t) {
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    mediaRecorder.release();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            if (file != null && file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete failed record file");
            }
            stopProjection();
            notifyRecordingFinished(false, "录屏失败：" + describe(t));
        }
    }

    private MediaRecorder createRecorder(File file, int width, int height, boolean withAudio) throws IOException {
        MediaRecorder mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (withAudio) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoFrameRate(30);
            long bitRate = (long) width * height * 4L;
            mediaRecorder.setVideoEncodingBitRate((int) Math.min(16_000_000L, Math.max(2_000_000L, bitRate)));
            if (withAudio) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(96_000);
                mediaRecorder.setAudioSamplingRate(44_100);
                mediaRecorder.setAudioChannels(1);
            }
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.prepare();
            return mediaRecorder;
        } catch (Throwable t) {
            try {
                mediaRecorder.reset();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                mediaRecorder.release();
            } catch (Throwable ignored) {
                // 忽略
            }
            throw new IOException(describe(t), t);
        }
    }

    public void stopRecording(boolean save) {
        if (!recording) {
            return;
        }
        recording = false;
        File file = recordFile;
        recordFile = null;
        MediaRecorder mediaRecorder = recorder;
        recorder = null;
        boolean stopped = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                stopped = true;
            } catch (Throwable t) {
                Log.w(TAG, "recorder stop failed", t);
            }
            try {
                mediaRecorder.reset();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                mediaRecorder.release();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        stopProjection();
        if (!save) {
            if (file != null && file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete record file");
            }
            notifyRecordingFinished(false, "已取消录屏");
            return;
        }
        if (stopped && file != null && file.length() > 0) {
            final File saved = file;
            runIo(new Runnable() {
                @Override
                public void run() {
                    String message = null;
                    try {
                        message = saveVideo(saved);
                    } catch (Throwable t) {
                        Log.w(TAG, "save video failed", t);
                    }
                    final String finalMessage = message;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyRecordingFinished(finalMessage != null,
                                    finalMessage != null ? finalMessage : "保存录像失败");
                        }
                    });
                }
            });
        } else {
            if (file != null && file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete record file");
            }
            notifyRecordingFinished(false, "录制时间太短，已丢弃");
        }
    }

    private String saveVideo(File source) throws IOException {
        String name = source.getName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FloatKit");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建相册条目");
            }
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法写入相册");
                }
                copy(in, out);
            } catch (IOException e) {
                context.getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            if (source.exists() && !source.delete()) {
                Log.w(TAG, "cannot delete temp video");
            }
            return "录像已保存到相册 / Movies/FloatKit";
        }
        File dir = publicMediaDir(Environment.DIRECTORY_MOVIES);
        if (dir != null) {
            File target = new File(dir, name);
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            if (source.exists() && !source.delete()) {
                Log.w(TAG, "cannot delete temp video");
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{target.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            return "录像已保存：" + target.getAbsolutePath();
        }
        return "录像已保存：" + source.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    // MediaProjection 生命周期
    // ------------------------------------------------------------------

    private MediaProjection obtain(int resultCode, Intent data) {
        MediaProjectionManager manager =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("系统不支持屏幕捕获");
        }
        MediaProjection mp = manager.getMediaProjection(resultCode, data);
        if (mp == null) {
            throw new IllegalStateException("未获得屏幕共享授权");
        }
        try {
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            onProjectionStopped();
                        }
                    });
                }
            }, main);
        } catch (Throwable ignored) {
            // Android 14 之前可以不注册回调
        }
        return mp;
    }

    private void onProjectionStopped() {
        if (!recording) {
            if (screenshotPending) {
                screenshotPending = false;
                notifyScreenshot(false, "屏幕共享已被系统停止");
            }
            stopProjection();
            return;
        }
        // 用户从系统 UI 强制停止了屏幕共享
        recording = false;
        File file = recordFile;
        recordFile = null;
        MediaRecorder mediaRecorder = recorder;
        recorder = null;
        boolean stopped = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                stopped = true;
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                mediaRecorder.reset();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                mediaRecorder.release();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        stopProjection();
        if (stopped && file != null && file.length() > 0) {
            final File saved = file;
            runIo(new Runnable() {
                @Override
                public void run() {
                    String message = null;
                    try {
                        message = saveVideo(saved);
                    } catch (Throwable t) {
                        Log.w(TAG, "save video failed", t);
                    }
                    final String finalMessage = message;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyRecordingFinished(finalMessage != null,
                                    finalMessage != null ? finalMessage : "录像已保存");
                        }
                    });
                }
            });
        } else {
            if (file != null && file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete record file");
            }
            notifyRecordingFinished(false, "屏幕共享已被系统停止");
        }
    }

    private void stopProjection() {
        VirtualDisplay currentDisplay = display;
        display = null;
        if (currentDisplay != null) {
            try {
                currentDisplay.release();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null);
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                reader.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        if (screenshotTimeout != null) {
            main.removeCallbacks(screenshotTimeout);
            screenshotTimeout = null;
        }
        MediaProjection currentProjection = projection;
        projection = null;
        if (currentProjection != null) {
            try {
                currentProjection.stop();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    public void release() {
        released = true;
        listener = null;
        if (recording) {
            stopRecording(true);
        } else {
            stopProjection();
        }
        io.shutdown();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Point screenPoint() {
        Point size = Ui.screenSize(context);
        if (size.x <= 0 || size.y <= 0) {
            size.set(context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels);
        }
        return size;
    }

    private File appMediaDir(String type) {
        File base = context.getExternalFilesDir(type);
        if (base == null) {
            base = new File(context.getFilesDir(), type == null ? "media" : type);
        }
        if (!base.exists() && !base.mkdirs()) {
            return null;
        }
        return base;
    }

    /** Android 9 及以下写入公共相册目录需要存储权限，没有权限时返回 null。 */
    private File publicMediaDir(String type) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(type), "FloatKit");
        if (dir.exists() || dir.mkdirs()) {
            return dir;
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int length;
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
        out.flush();
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        return message;
    }
}
