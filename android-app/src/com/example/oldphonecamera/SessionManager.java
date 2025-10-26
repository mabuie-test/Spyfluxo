package com.example.oldphonecamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public class SessionManager {
    private static final String PREFS_NAME = "old_phone_camera_session";
    private static final String KEY_BACKEND_URL = "backend_url";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_KEY = "device_key";
    private static final String KEY_DEVICE_NAME = "device_name";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Session loadSession() {
        String backendUrl = prefs.getString(KEY_BACKEND_URL, Config.DEFAULT_BACKEND_URL);
        String authToken = prefs.getString(KEY_AUTH_TOKEN, null);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        String deviceKey = prefs.getString(KEY_DEVICE_KEY, null);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, null);
        return new Session(backendUrl, authToken, deviceId, deviceKey, deviceName);
    }

    public void saveSession(Session session) {
        prefs.edit()
                .putString(KEY_BACKEND_URL, session.backendUrl)
                .putString(KEY_AUTH_TOKEN, session.authToken)
                .putString(KEY_DEVICE_ID, session.deviceId)
                .putString(KEY_DEVICE_KEY, session.deviceKey)
                .putString(KEY_DEVICE_NAME, session.deviceName)
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public static class Session {
        public final String backendUrl;
        public final String authToken;
        public final String deviceId;
        public final String deviceKey;
        public final String deviceName;

        public Session(String backendUrl, String authToken, String deviceId, String deviceKey, String deviceName) {
            this.backendUrl = TextUtils.isEmpty(backendUrl) ? Config.DEFAULT_BACKEND_URL : backendUrl;
            this.authToken = authToken;
            this.deviceId = deviceId;
            this.deviceKey = deviceKey;
            this.deviceName = deviceName;
        }

        public boolean hasValidDevice() {
            return !TextUtils.isEmpty(deviceId) && !TextUtils.isEmpty(deviceKey);
        }

        public boolean hasAuthToken() {
            return !TextUtils.isEmpty(authToken);
        }
    }
}
