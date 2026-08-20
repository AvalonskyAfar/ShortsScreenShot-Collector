package com.example.shortvideocollector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

public class CollectorAccessibilityService extends AccessibilityService {
    private static volatile CollectorAccessibilityService instance;
    private static volatile String foregroundPackage = "";
    private static volatile long lastForegroundEventAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName != null && !packageName.toString().equals(getPackageName())) {
            foregroundPackage = packageName.toString();
            lastForegroundEventAt = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    static boolean isConnectedNow() {
        return instance != null;
    }

    static String currentPackage() {
        return foregroundPackage;
    }

    static long lastEventAt() {
        return lastForegroundEventAt;
    }

    static boolean swipeToNext(int width, int height, Runnable success, Runnable failure) {
        CollectorAccessibilityService service = instance;
        if (service == null) return false;
        Path path = new Path();
        path.moveTo(width * 0.50f, height * 0.78f);
        path.lineTo(width * 0.50f, height * 0.25f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 360))
                .build();
        return service.dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (success != null) success.run();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (failure != null) failure.run();
            }
        }, null);
    }
}

