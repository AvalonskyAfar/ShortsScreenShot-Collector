package com.example.shortvideocollector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;
    private EditText hostInput;
    private EditText portInput;
    private EditText tokenInput;
    private EditText intervalInput;
    private EditText maxFramesInput;
    private EditText maxDurationInput;
    private TextView permissionStatus;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadValues();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(30));
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(root);

        TextView title = text("短视频图片数据采集器", 24, true);
        root.addView(title);
        TextView subtitle = text("Android 控制端 · 原始图片采集", 14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, margins(0, 4, 0, 20));

        root.addView(text("Windows 接收端", 18, true));
        hostInput = input("电脑地址：无线填 IP，数据线填 127.0.0.1", false);
        portInput = input("端口", true);
        tokenInput = input("配对码", false);
        root.addView(hostInput, margins(0, 8, 0, 4));
        root.addView(portInput, margins(0, 4, 0, 4));
        root.addView(tokenInput, margins(0, 4, 0, 8));
        Button test = button("测试连接");
        test.setOnClickListener(view -> testConnection());
        root.addView(test);

        root.addView(text("采集节奏", 18, true), margins(0, 22, 0, 0));
        intervalInput = input("采样间隔（毫秒，400–5000）", true);
        maxFramesInput = input("每视频最多保存帧数（2–100）", true);
        maxDurationInput = input("每视频最长停留秒数（5–120）", true);
        root.addView(intervalInput, margins(0, 8, 0, 4));
        root.addView(maxFramesInput, margins(0, 4, 0, 4));
        root.addView(maxDurationInput, margins(0, 4, 0, 8));

        root.addView(text("系统权限", 18, true), margins(0, 22, 0, 8));
        permissionStatus = text("", 14, false);
        root.addView(permissionStatus);
        Button overlay = button("1. 授予悬浮窗权限");
        overlay.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(overlay, margins(0, 8, 0, 4));
        Button accessibility = button("2. 开启自动翻页无障碍服务");
        accessibility.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, margins(0, 4, 0, 4));
        Button capture = button("3. 授权截屏并显示悬浮控制器");
        capture.setOnClickListener(view -> requestCapture());
        root.addView(capture, margins(0, 4, 0, 12));

        TextView help = text("准备好后切换到短视频应用，在要采集的第一个视频上点击悬浮窗“开始”。无线模式填写电脑局域网 IP；USB 数据线模式先运行 enable_usb_debug.ps1，再填写 127.0.0.1。开始时会锁定当前前台应用；离开该应用、锁屏或断网都会安全暂停。", 14, false);
        help.setTextColor(Color.DKGRAY);
        root.addView(help);
        return scroll;
    }

    private void loadValues() {
        AppConfig config = AppConfig.load(this);
        hostInput.setText(config.host);
        portInput.setText(String.valueOf(config.port));
        tokenInput.setText(config.token);
        intervalInput.setText(String.valueOf(config.intervalMs));
        maxFramesInput.setText(String.valueOf(config.maxFrames));
        maxDurationInput.setText(String.valueOf(config.maxDurationMs / 1000));
    }

    private boolean saveValues() {
        try {
            String host = hostInput.getText().toString().trim();
            String token = tokenInput.getText().toString().trim();
            if (host.isEmpty()) throw new IllegalArgumentException("请填写电脑 IP");
            if (token.length() < 4) throw new IllegalArgumentException("配对码至少 4 位");
            AppConfig.save(this, host,
                    Integer.parseInt(portInput.getText().toString()), token,
                    Integer.parseInt(intervalInput.getText().toString()),
                    Integer.parseInt(maxFramesInput.getText().toString()),
                    Integer.parseInt(maxDurationInput.getText().toString()));
            return true;
        } catch (RuntimeException error) {
            Toast.makeText(this, error.getMessage() == null ? "配置格式不正确" : error.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void testConnection() {
        if (!saveValues()) return;
        Toast.makeText(this, "正在连接…", Toast.LENGTH_SHORT).show();
        networkExecutor.execute(() -> {
            String result = "连接成功";
            try {
                NetworkClient client = new NetworkClient(AppConfig.load(this));
                client.checkHealth();
                client.startSession();
                client.endSession();
            } catch (Exception error) {
                result = "连接失败：" + error.getMessage();
            }
            String message = result;
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        });
    }

    private void requestCapture() {
        if (!saveValues()) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未授予截屏权限", Toast.LENGTH_LONG).show();
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_INITIALIZE);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        Toast.makeText(this, "悬浮控制器已启动，请切换到短视频应用", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void updatePermissionStatus() {
        if (permissionStatus == null) return;
        permissionStatus.setText("悬浮窗：" + (Settings.canDrawOverlays(this) ? "已授权" : "未授权")
                + "    自动翻页：" + (CollectorAccessibilityService.isConnectedNow() ? "已开启" : "未开启"));
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(32, 33, 36));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText input(String hint, boolean number) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(16);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
