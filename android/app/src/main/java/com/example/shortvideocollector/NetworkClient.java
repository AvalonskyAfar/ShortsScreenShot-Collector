package com.example.shortvideocollector;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class NetworkClient {
    private final AppConfig config;
    private String sessionId;

    NetworkClient(AppConfig config) {
        this.config = config;
    }

    void checkHealth() throws IOException {
        HttpURLConnection connection = open("GET", "/api/v1/health", false);
        readResponse(connection, 200);
    }

    void startSession() throws IOException {
        JSONObject response = postJson("/api/v1/session", false);
        sessionId = response.optString("sessionId", "");
        if (sessionId.isEmpty()) throw new IOException("接收端没有返回会话编号");
    }

    int startVideo() throws IOException {
        ensureSession();
        JSONObject response = postJson("/api/v1/video", true);
        int number = response.optInt("video", 0);
        if (number <= 0) throw new IOException("接收端没有返回视频编号");
        return number;
    }

    void uploadFrame(int video, int frame, byte[] png) throws IOException {
        ensureSession();
        String path = "/api/v1/frame?video=" + video + "&frame=" + frame;
        HttpURLConnection connection = open("POST", path, true);
        connection.setRequestProperty("Content-Type", "image/png");
        connection.setFixedLengthStreamingMode(png.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(png);
        }
        readResponse(connection, 200);
    }

    void endSession() {
        if (sessionId == null) return;
        try {
            postJson("/api/v1/session/end", true);
        } catch (IOException ignored) {
        } finally {
            sessionId = null;
        }
    }

    private JSONObject postJson(String path, boolean needsSession) throws IOException {
        HttpURLConnection connection = open("POST", path, needsSession);
        connection.setFixedLengthStreamingMode(0);
        connection.setDoOutput(true);
        connection.getOutputStream().close();
        String response = readResponse(connection, 200, 201);
        try {
            return new JSONObject(response);
        } catch (JSONException error) {
            throw new IOException("接收端响应格式错误", error);
        }
    }

    private HttpURLConnection open(String method, String path, boolean needsSession) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(config.baseUrl() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + config.token);
        connection.setRequestProperty("Connection", "close");
        if (needsSession) {
            ensureSession();
            connection.setRequestProperty("X-Session-Id", sessionId);
        }
        return connection;
    }

    private void ensureSession() throws IOException {
        if (sessionId == null || sessionId.isEmpty()) throw new IOException("采集会话尚未建立");
    }

    private static String readResponse(HttpURLConnection connection, int... accepted) throws IOException {
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = input == null ? "" : readAll(input);
        connection.disconnect();
        for (int expected : accepted) if (code == expected) return body;
        try {
            String message = new JSONObject(body).optString("error", body);
            throw new IOException("接收端返回 " + code + "：" + message);
        } catch (JSONException ignored) {
            throw new IOException("接收端返回 HTTP " + code);
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}

