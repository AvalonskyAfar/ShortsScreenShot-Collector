package com.example.shortvideocollector;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    static final String ACTION_INITIALIZE = "collector.INITIALIZE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    private static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "collector_capture";
    private static final float SAVE_DIFFERENCE = 0.025f;
    private static final float STABLE_DIFFERENCE = 0.012f;
    private static final float SWITCH_DIFFERENCE = 0.09f;
    private static final long MIN_VIDEO_MS = 6000L;
    private static final long STABLE_FINISH_MS = 3200L;
    private static final long FORCE_SAVE_MS = 3000L;
    private static final long SWITCH_TIMEOUT_MS = 7500L;

    private enum State { READY, CONNECTING, RUNNING, SWITCHING, PAUSED, ERROR }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView titleView;
    private TextView stateView;
    private TextView countView;
    private Button primaryButton;
    private LinearLayout expandedContent;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int densityDpi;
    private final AtomicBoolean waitingForImage = new AtomicBoolean(false);

    private volatile State state = State.READY;
    private volatile String detail = "等待开始";
    private AppConfig config;
    private NetworkClient network;
    private String targetPackage = "";
    private int videoNumber;
    private int frameNumber;
    private long videoStartedAt;
    private long lastSavedAt;
    private long stableSince;
    private long switchStartedAt;
    private int switchConfirmations;
    private byte[] lastObservedSignature;
    private byte[] lastSavedSignature;
    private byte[] beforeSwitchSignature;
    private int generation;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        workerThread = new HandlerThread("collector-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_INITIALIZE.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, notification("等待开始"));
            initializeProjection(intent);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void initializeProjection(Intent intent) {
        if (projection != null) releaseProjection();
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            setError("截屏授权数据无效，请回到主界面重新授权");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            setError("无法启动系统截屏");
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                worker.post(() -> setError("系统已结束截屏授权，请重新授权"));
            }
        }, main);
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, worker);
        virtualDisplay = projection.createVirtualDisplay(
                "ShortVideoCollector", screenWidth, screenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, worker);
        showOverlay();
        setState(State.READY, "等待开始");
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;
        main.post(() -> {
            if (overlay != null) return;
            float density = getResources().getDisplayMetrics().density;
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(10), dp(7), dp(10), dp(8));
            panel.setBackgroundColor(Color.argb(218, 24, 28, 36));

            titleView = overlayText("采集器 －", 14, true);
            titleView.setPadding(0, 0, 0, dp(3));
            panel.addView(titleView, new LinearLayout.LayoutParams(dp(205), dp(32)));
            expandedContent = new LinearLayout(this);
            expandedContent.setOrientation(LinearLayout.VERTICAL);
            stateView = overlayText("等待开始", 12, false);
            countView = overlayText("视频：0   图片：0", 12, false);
            expandedContent.addView(stateView);
            expandedContent.addView(countView);

            LinearLayout buttons = new LinearLayout(this);
            primaryButton = smallButton("开始");
            Button stop = smallButton("停止");
            Button close = smallButton("关闭");
            buttons.addView(primaryButton, new LinearLayout.LayoutParams(0, dp(38), 1));
            buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(38), 1));
            buttons.addView(close, new LinearLayout.LayoutParams(0, dp(38), 1));
            expandedContent.addView(buttons);
            panel.addView(expandedContent);

            overlay = panel;
            overlayParams = new WindowManager.LayoutParams(
                    dp(225), WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            overlayParams.x = dp(8);
            overlayParams.y = dp(100);
            windowManager.addView(overlay, overlayParams);

            final float[] touch = new float[4];
            final boolean[] dragged = new boolean[1];
            titleView.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    touch[0] = event.getRawX();
                    touch[1] = event.getRawY();
                    touch[2] = overlayParams.x;
                    touch[3] = overlayParams.y;
                    dragged[0] = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - touch[0];
                    float dy = event.getRawY() - touch[1];
                    if (Math.abs(dx) + Math.abs(dy) > density * 8) dragged[0] = true;
                    overlayParams.x = Math.max(0, Math.min(screenWidth - overlay.getWidth(), (int) (touch[2] + dx)));
                    overlayParams.y = Math.max(0, Math.min(screenHeight - dp(50), (int) (touch[3] + dy)));
                    windowManager.updateViewLayout(overlay, overlayParams);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP && !dragged[0]) {
                    boolean expanded = expandedContent.getVisibility() == View.VISIBLE;
                    expandedContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
                    titleView.setText(expanded ? "采集器 ＋" : "采集器 －");
                    overlayParams.width = expanded ? dp(118) : dp(225);
                    windowManager.updateViewLayout(overlay, overlayParams);
                    return true;
                }
                return false;
            });
            primaryButton.setOnClickListener(view -> onPrimaryAction());
            stop.setOnClickListener(view -> worker.post(this::stopCollection));
            close.setOnClickListener(view -> {
                worker.post(this::stopCollection);
                stopSelf();
            });
            refreshOverlay();
        });
    }

    private void onPrimaryAction() {
        worker.post(() -> {
            if (state == State.RUNNING || state == State.SWITCHING || state == State.CONNECTING) {
                pauseCollection("用户暂停");
            } else if (state == State.PAUSED || state == State.ERROR) {
                resumeCollection();
            } else {
                startCollection();
            }
        });
    }

    private void startCollection() {
        generation++;
        if (!checkEnvironment(false)) return;
        String foreground = CollectorAccessibilityService.currentPackage();
        if (foreground.isEmpty() || foreground.equals(getPackageName()) || foreground.equals("com.android.systemui")) {
            setError("无法确认短视频应用，请先打开目标视频再开始");
            return;
        }
        targetPackage = foreground;
        config = AppConfig.load(this);
        network = new NetworkClient(config);
        setState(State.CONNECTING, "正在连接 Windows…");
        try {
            network.checkHealth();
            network.startSession();
            beginNewVideo();
            setState(State.RUNNING, "采集中 · 已锁定 " + shortPackage(targetPackage));
            scheduleCapture(250);
        } catch (IOException error) {
            setError("连接失败：" + error.getMessage());
        }
    }

    private void resumeCollection() {
        generation++;
        if (network == null || targetPackage.isEmpty()) {
            startCollection();
            return;
        }
        if (!checkEnvironment(true)) return;
        setState(State.CONNECTING, "正在安全恢复…");
        try {
            // A resumed task always starts a fresh folder. This may split one video,
            // but prevents a user-changed video from contaminating an old folder.
            beginNewVideo();
            setState(State.RUNNING, "已恢复（新目录）");
            scheduleCapture(250);
        } catch (IOException error) {
            setError("恢复失败：" + error.getMessage());
        }
    }

    private void pauseCollection(String reason) {
        generation++;
        waitingForImage.set(false);
        setState(State.PAUSED, reason);
    }

    private void stopCollection() {
        generation++;
        waitingForImage.set(false);
        if (network != null) network.endSession();
        network = null;
        targetPackage = "";
        videoNumber = 0;
        frameNumber = 0;
        clearVideoState();
        setState(State.READY, "已停止，数据已保存");
    }

    private void beginNewVideo() throws IOException {
        videoNumber = network.startVideo();
        frameNumber = 0;
        videoStartedAt = SystemClock.elapsedRealtime();
        lastSavedAt = 0;
        stableSince = 0;
        lastObservedSignature = null;
        lastSavedSignature = null;
        beforeSwitchSignature = null;
        switchConfirmations = 0;
    }

    private boolean checkEnvironment(boolean requireTarget) {
        if (projection == null || imageReader == null) {
            setError("截屏授权已失效，请回到主界面重新授权");
            return false;
        }
        if (!CollectorAccessibilityService.isConnectedNow()) {
            setError("自动翻页服务未开启或已失效");
            return false;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) {
            setError("屏幕已关闭或锁定");
            return false;
        }
        if (requireTarget && !targetPackage.equals(CollectorAccessibilityService.currentPackage())) {
            setError("目标应用不在前台，请返回原应用后继续");
            return false;
        }
        return true;
    }

    private void scheduleCapture(long delayMs) {
        int expectedGeneration = generation;
        worker.postDelayed(() -> {
            if (generation != expectedGeneration) return;
            if (state != State.RUNNING && state != State.SWITCHING) return;
            if (!checkEnvironment(true)) return;
            requestImage(expectedGeneration);
        }, delayMs);
    }

    private void requestImage(int expectedGeneration) {
        if (!waitingForImage.compareAndSet(false, true)) return;
        worker.postDelayed(() -> {
            if (generation == expectedGeneration && waitingForImage.compareAndSet(true, false)) {
                setError("截屏超时，请重新授权或检查目标应用是否禁止截屏");
            }
        }, 2500);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !waitingForImage.compareAndSet(true, false)) return;
            Bitmap bitmap = imageToBitmap(image);
            if (state == State.RUNNING) processRunningFrame(bitmap);
            else if (state == State.SWITCHING) processSwitchFrame(bitmap);
            bitmap.recycle();
        } catch (Throwable error) {
            setError("处理截图失败：" + error.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;
        Bitmap padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
        padded.recycle();
        return cropped;
    }

    private void processRunningFrame(Bitmap bitmap) throws IOException {
        long now = SystemClock.elapsedRealtime();
        byte[] signature = ImageAnalyzer.signature(bitmap);
        float movement = ImageAnalyzer.difference(lastObservedSignature, signature);
        if (lastObservedSignature != null && movement < STABLE_DIFFERENCE) {
            if (stableSince == 0) stableSince = now;
        } else {
            stableSince = 0;
        }
        boolean shouldSave = frameNumber == 0
                || ImageAnalyzer.difference(lastSavedSignature, signature) >= SAVE_DIFFERENCE
                || now - lastSavedAt >= FORCE_SAVE_MS;
        lastObservedSignature = signature;

        if (shouldSave) {
            int nextFrame = frameNumber + 1;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG 编码失败");
            network.uploadFrame(videoNumber, nextFrame, output.toByteArray());
            frameNumber = nextFrame;
            lastSavedSignature = signature;
            lastSavedAt = now;
            refreshOverlay();
        }

        long elapsed = now - videoStartedAt;
        boolean stableEnough = elapsed >= MIN_VIDEO_MS && stableSince != 0 && now - stableSince >= STABLE_FINISH_MS;
        if (frameNumber >= config.maxFrames || elapsed >= config.maxDurationMs || stableEnough) {
            beginSwitch(signature);
        } else {
            scheduleCapture(config.intervalMs);
        }
    }

    private void beginSwitch(byte[] signature) {
        if (!checkEnvironment(true)) return;
        beforeSwitchSignature = signature;
        switchStartedAt = SystemClock.elapsedRealtime();
        switchConfirmations = 0;
        setState(State.SWITCHING, "正在切换并确认新视频…");
        boolean dispatched = CollectorAccessibilityService.swipeToNext(screenWidth, screenHeight,
                () -> worker.post(() -> scheduleCapture(850)),
                () -> worker.post(() -> setError("系统取消了上滑手势")));
        if (!dispatched) setError("无法执行上滑手势，请检查无障碍服务");
    }

    private void processSwitchFrame(Bitmap bitmap) throws IOException {
        long now = SystemClock.elapsedRealtime();
        byte[] current = ImageAnalyzer.signature(bitmap);
        float difference = ImageAnalyzer.difference(beforeSwitchSignature, current);
        if (difference >= SWITCH_DIFFERENCE) switchConfirmations++; else switchConfirmations = 0;

        if (switchConfirmations >= 2) {
            beginNewVideo();
            setState(State.RUNNING, "已确认新视频，继续采集");
            scheduleCapture(350);
            return;
        }
        if (now - switchStartedAt >= SWITCH_TIMEOUT_MS) {
            setError("未能确认画面已切换；已安全暂停，请人工检查后继续");
            return;
        }
        scheduleCapture(450);
    }

    private void setError(String message) {
        generation++;
        waitingForImage.set(false);
        setState(State.ERROR, message == null ? "未知错误" : message);
    }

    private void clearVideoState() {
        lastObservedSignature = null;
        lastSavedSignature = null;
        beforeSwitchSignature = null;
        stableSince = 0;
        switchConfirmations = 0;
    }

    private void setState(State newState, String message) {
        state = newState;
        detail = message;
        refreshOverlay();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(message));
    }

    private void refreshOverlay() {
        main.post(() -> {
            if (stateView == null) return;
            stateView.setText(detail);
            countView.setText(String.format(Locale.CHINA, "视频：%06d   图片：%d", videoNumber, frameNumber));
            if (state == State.RUNNING || state == State.SWITCHING || state == State.CONNECTING) {
                primaryButton.setText("暂停");
            } else if (state == State.PAUSED || state == State.ERROR) {
                primaryButton.setText("继续");
            } else {
                primaryButton.setText("开始");
            }
        });
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("短视频图片采集器")
                .setContentText(message)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "采集状态", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示截屏采集前台服务状态");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private TextView overlayText(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.argb(120, 70, 100, 150));
        button.setPadding(0, 0, 0, 0);
        button.setAllCaps(false);
        return button;
    }

    private String shortPackage(String packageName) {
        if (packageName.length() <= 24) return packageName;
        return "…" + packageName.substring(packageName.length() - 23);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releaseProjection() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        generation++;
        if (worker != null) worker.post(() -> {
            if (network != null) network.endSession();
        });
        if (overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException ignored) {
            }
            overlay = null;
        }
        releaseProjection();
        if (workerThread != null) workerThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
