package com.example.oldphonecamera;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    private static final String TAG = "ApiClient";

    private ApiClient() {
    }

    public static LoginResult login(String backendUrl, String email, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("email", email);
        payload.put("password", password);
        JSONObject response = postJson(backendUrl + "/api/auth/login", payload, null);
        String token = response.optString("token", null);
        if (token == null) {
            throw new IllegalStateException("Token ausente na resposta");
        }
        JSONObject user = response.optJSONObject("user");
        String name = user != null ? user.optString("name", "") : "";
        return new LoginResult(token, name);
    }

    public static DeviceRegistration registerDevice(String backendUrl, String authToken, String deviceName) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("name", deviceName);
        JSONObject response = postJson(backendUrl + "/api/devices/register", payload, "Bearer " + authToken);
        String deviceId = response.optString("deviceId", null);
        String deviceKey = response.optString("deviceKey", null);
        if (deviceId == null || deviceKey == null) {
            throw new IllegalStateException("Falha ao registrar dispositivo");
        }
        return new DeviceRegistration(deviceId, deviceKey, response.optString("name", deviceName));
    }

    public static boolean uploadSegment(String backendUrl,
                                        String deviceKey,
                                        String deviceId,
                                        String deviceName,
                                        String base64Segment,
                                        long startedAt,
                                        long finishedAt) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("deviceId", deviceId);
            payload.put("deviceName", deviceName);
            payload.put("segment", base64Segment);
            payload.put("startedAt", startedAt);
            payload.put("finishedAt", finishedAt);
            JSONObject response = postJson(backendUrl + "/api/segments", payload, "Bearer " + deviceKey);
            return response.optBoolean("ok", false);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar segmento", e);
            return false;
        }
    }

    private static JSONObject postJson(String urlString, JSONObject payload, String authorization) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }

        OutputStream os = connection.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        writer.write(payload.toString());
        writer.flush();
        writer.close();
        os.close();

        int responseCode = connection.getResponseCode();
        InputStream responseStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder builder = new StringBuilder();
        if (responseStream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            reader.close();
        }
        connection.disconnect();

        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("Erro HTTP " + responseCode + ": " + builder);
        }

        String responseBody = builder.toString();
        if (responseBody.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(responseBody);
    }

    public static class LoginResult {
        public final String token;
        public final String userName;

        public LoginResult(String token, String userName) {
            this.token = token;
            this.userName = userName;
        }
    }

    public static class DeviceRegistration {
        public final String deviceId;
        public final String deviceKey;
        public final String deviceName;

        public DeviceRegistration(String deviceId, String deviceKey, String deviceName) {
            this.deviceId = deviceId;
            this.deviceKey = deviceKey;
            this.deviceName = deviceName;
        }
    }
}
