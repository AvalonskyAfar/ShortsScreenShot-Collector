package com.example.shortvideocollector;

import android.content.Context;
import android.content.SharedPreferences;

final class AppConfig {
    final String host;
    final int port;
    final String token;
    final long intervalMs;
    final int maxFrames;
    final long maxDurationMs;

    private AppConfig(String host, int port, String token, long intervalMs, int maxFrames, long maxDurationMs) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.intervalMs = intervalMs;
        this.maxFrames = maxFrames;
        this.maxDurationMs = maxDurationMs;
    }

    static AppConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("collector", Context.MODE_PRIVATE);
        return new AppConfig(
                preferences.getString("host", "192.168.1.2").trim(),
                clamp(preferences.getInt("port", 8765), 1, 65535),
                preferences.getString("token", "").trim(),
                clamp(preferences.getInt("interval", 900), 400, 5000),
                clamp(preferences.getInt("maxFrames", 12), 2, 100),
                clamp(preferences.getInt("maxDuration", 15), 5, 120) * 1000L
        );
    }

    static void save(Context context, String host, int port, String token, int interval, int maxFrames, int maxDurationSeconds) {
        context.getSharedPreferences("collector", Context.MODE_PRIVATE).edit()
                .putString("host", host.trim())
                .putInt("port", clamp(port, 1, 65535))
                .putString("token", token.trim())
                .putInt("interval", clamp(interval, 400, 5000))
                .putInt("maxFrames", clamp(maxFrames, 2, 100))
                .putInt("maxDuration", clamp(maxDurationSeconds, 5, 120))
                .apply();
    }

    String baseUrl() {
        String clean = host.replace("http://", "").replace("https://", "");
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return "http://" + clean + ":" + port;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

