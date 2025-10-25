package com.example.oldphonecamera;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LoginActivity extends Activity {
    private EditText backendUrlInput;
    private EditText emailInput;
    private EditText passwordInput;
    private EditText deviceNameInput;
    private ProgressBar progressBar;
    private TextView errorView;
    private Button loginButton;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        backendUrlInput = findViewById(R.id.backendUrlInput);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        deviceNameInput = findViewById(R.id.deviceNameInput);
        progressBar = findViewById(R.id.loginProgress);
        errorView = findViewById(R.id.loginError);
        loginButton = findViewById(R.id.loginButton);

        sessionManager = new SessionManager(this);
        SessionManager.Session existingSession = sessionManager.loadSession();
        backendUrlInput.setText(existingSession.backendUrl);
        if (!TextUtils.isEmpty(existingSession.deviceName)) {
            deviceNameInput.setText(existingSession.deviceName);
        }

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });
    }

    private void attemptLogin() {
        final String backendUrl = backendUrlInput.getText().toString().trim();
        final String email = emailInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        final String deviceName = deviceNameInput.getText().toString().trim();

        errorView.setVisibility(View.GONE);

        if (TextUtils.isEmpty(backendUrl) || TextUtils.isEmpty(email) || TextUtils.isEmpty(password) || TextUtils.isEmpty(deviceName)) {
            errorView.setText(R.string.login_error_generic);
            errorView.setVisibility(View.VISIBLE);
            return;
        }

        showLoading(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiClient.LoginResult loginResult = ApiClient.login(backendUrl, email, password);
                    ApiClient.DeviceRegistration registration = ApiClient.registerDevice(backendUrl, loginResult.token, deviceName);
                    SessionManager.Session session = new SessionManager.Session(
                            backendUrl,
                            loginResult.token,
                            registration.deviceId,
                            registration.deviceKey,
                            registration.deviceName
                    );
                    sessionManager.saveSession(session);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            errorView.setText(getString(R.string.login_error_generic) + "\n" + e.getMessage());
                            errorView.setVisibility(View.VISIBLE);
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLoading(false);
                        }
                    });
                }
            }
        }).start();
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!loading);
    }
}
