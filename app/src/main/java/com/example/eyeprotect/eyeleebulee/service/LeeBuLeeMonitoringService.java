package com.example.eyeprotect.eyeleebulee.service;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import com.example.eyeprotect.R;

public class LeeBuLeeMonitoringService extends Service implements SensorEventListener {
    private static final int NOTIFICATION_ID = 2401;
    private static final String CHANNEL_ID = "leebulee_monitoring";

    private WindowManager windowManager;
    private View bubbleView, overlayView;
    private WindowManager.LayoutParams bubbleParams, overlayParams;
    private TextureView textureView;
    private Camera mCamera;

    private SensorManager sensorManager;
    private PowerManager powerManager;

    private boolean isFullScreen = false;
    private boolean isWalking = false;
    private boolean isManualMode = false; // 用於手動強制開啟

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private double lastAngle = 0;

    private long lastUiUpdateTime = 0;
    private long manualCloseTime = 0; // 記錄手動關閉的時間點
    private static final int UI_STABILITY_DELAY = 500;
    private static final int MANUAL_CLOSE_COOLDOWN = 3000; // 手動關閉後，3秒內自動觸發不准打擾

    private final Handler walkingHandler = new Handler(Looper.getMainLooper());
    private double lastMagnitude = 0;
    private static final double SENSITIVITY_THRESHOLD = 0.15;

    private final Runnable stopWalkingRunnable = () -> {
        isWalking = false;
        checkAutoTrigger();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForegroundService();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        initViews();
        startMonitoring();
    }

    private void startAsForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "走路安全護衛",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setSmallIcon(R.drawable.ic_nav_leebulee)
                .setContentTitle("走路安全護衛執行中")
                .setContentText("偵測走路低頭時會顯示透明相機浮層")
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void initViews() {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.layout_leebu_camera_overlay, null);
        textureView = overlayView.findViewById(R.id.camera_preview);
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.alpha = 0.3f;
        overlayView.setVisibility(View.GONE);
        windowManager.addView(overlayView, overlayParams);

        bubbleView = inflater.inflate(R.layout.layout_leebu_floating_bubble, null);
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
        bubbleParams.x = 0; bubbleParams.y = 100;

        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = bubbleParams.x; initialY = bubbleParams.y;
                    initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bubbleParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                    bubbleParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - initialTouchX);
                    float dy = Math.abs(event.getRawY() - initialTouchY);
                    if (dx < 15 && dy < 15) {
                        handleStarClick(); // 處理點擊
                    } else {
                        checkExitOrSnap(event);
                    }
                    return true;
            }
            return false;
        });
        windowManager.addView(bubbleView, bubbleParams);
    }

    // --- 修改點：點擊星星的邏輯 ---
    private void handleStarClick() {
        if (isFullScreen) {
            // 如果現在鏡頭是開著的 -> 執行暫停（關閉）
            isManualMode = false; // 取消強制開啟模式
            manualCloseTime = System.currentTimeMillis(); // 記錄手動關閉時間
            updateUI(false);
        } else {
            // 如果現在鏡頭是關著的 -> 強制開啟
            isManualMode = true;
            updateUI(true);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!powerManager.isInteractive()) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            lastAngle = Math.toDegrees(Math.atan2(y, z));
            double magnitude = Math.sqrt(x * x + y * y + z * z);
            if (Math.abs(magnitude - lastMagnitude) > SENSITIVITY_THRESHOLD) {
                triggerWalking();
            }
            lastMagnitude = magnitude;
            checkAutoTrigger();
        }
        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            triggerWalking();
            checkAutoTrigger();
        }
    }

    private void triggerWalking() {
        isWalking = true;
        walkingHandler.removeCallbacks(stopWalkingRunnable);
        walkingHandler.postDelayed(stopWalkingRunnable, 3000);
    }

    private void checkAutoTrigger() {
        long currentTime = System.currentTimeMillis();

        // 1. 如果是手動模式，優先維持開啟
        if (isManualMode) {
            if (!isFullScreen) updateUI(true);
            return;
        }

        // 2. 自動觸發判定：走路中 且 角度達標
        boolean shouldBeOn = isWalking && (lastAngle >= 20 && lastAngle <= 90);

        // 3. 處理「手動關閉後」的邏輯
        // 如果使用者剛剛才手動按星星關掉鏡頭，在 3 秒內我們不讓自動觸發重新開啟鏡頭
        // 否則你一按關閉，因為你還在走路，鏡頭會秒開，那按暫停就沒意義了
        if (currentTime - manualCloseTime < MANUAL_CLOSE_COOLDOWN) {
            return;
        }

        if (currentTime - lastUiUpdateTime < UI_STABILITY_DELAY) return;

        if (shouldBeOn && !isFullScreen) {
            updateUI(true);
            lastUiUpdateTime = currentTime;
        } else if (!shouldBeOn && isFullScreen) {
            updateUI(false);
            lastUiUpdateTime = currentTime;
        }
    }

    private void updateUI(boolean shouldExpand) {
        if (shouldExpand && !isFullScreen) {
            overlayView.setVisibility(View.VISIBLE);
            if (textureView.isAvailable()) openCamera(textureView.getSurfaceTexture());
            isFullScreen = true;
        } else if (!shouldExpand && isFullScreen) {
            overlayView.setVisibility(View.GONE);
            closeCamera();
            isFullScreen = false;
        }
    }

    // --- 其餘相機與監控代碼不變 ---
    private void checkExitOrSnap(MotionEvent event) {
        int h = getResources().getDisplayMetrics().heightPixels;
        int w = getResources().getDisplayMetrics().widthPixels;
        if (event.getRawY() > (h * 0.85)) stopSelf();
        else {
            bubbleParams.x = (event.getRawX() < w / 2) ? 0 : w;
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        }
    }

    private void openCamera(SurfaceTexture surface) {
        try { if (mCamera == null) { mCamera = Camera.open(); mCamera.setDisplayOrientation(90); mCamera.setPreviewTexture(surface); mCamera.startPreview(); } } catch (Exception e) { e.printStackTrace(); }
    }

    private void closeCamera() {
        if (mCamera != null) { mCamera.stopPreview(); mCamera.release(); mCamera = null; }
    }

    private void startMonitoring() {
        Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor step = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (step != null) sensorManager.registerListener(this, step, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopMonitoring() {
        sensorManager.unregisterListener(this);
        walkingHandler.removeCallbacks(stopWalkingRunnable);
        isWalking = false;
        updateUI(false);
    }

    @Override public void onDestroy() { super.onDestroy(); stopMonitoring(); if (bubbleView != null) windowManager.removeView(bubbleView); if (overlayView != null) windowManager.removeView(overlayView); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
