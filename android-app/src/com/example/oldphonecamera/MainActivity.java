package com.example.oldphonecamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "OldPhoneCamera";
    private static final int PERMISSION_REQUEST = 1001;
    private static final int LOGIN_REQUEST = 2001;

    private TextureView previewView;
    private Button startButton;
    private Button stopButton;
    private Button logoutButton;
    private TextView statusText;

    private SessionManager sessionManager;
    private SessionManager.Session session;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private MediaRecorder mediaRecorder;
    private File currentSegmentFile;
    private long currentSegmentStart;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private ExecutorService uploadExecutor;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    private final Runnable segmentSplitRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStreaming.get()) {
                stopCurrentSegment(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        startButton = findViewById(R.id.startStreamButton);
        stopButton = findViewById(R.id.stopStreamButton);
        logoutButton = findViewById(R.id.logoutButton);
        statusText = findViewById(R.id.statusText);

        sessionManager = new SessionManager(this);
        session = sessionManager.loadSession();
        uploadExecutor = Executors.newSingleThreadExecutor();

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ensureSession()) {
                    return;
                }
                if (isStreaming.compareAndSet(false, true)) {
                    Toast.makeText(MainActivity.this, R.string.status_streaming, Toast.LENGTH_SHORT).show();
                    setStatus(R.string.status_streaming);
                    startStreaming();
                }
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isStreaming.compareAndSet(true, false)) {
                    Toast.makeText(MainActivity.this, R.string.main_stop, Toast.LENGTH_SHORT).show();
                    stopStreaming();
                }
            }
        });

        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopStreaming();
                sessionManager.clear();
                session = sessionManager.loadSession();
                Toast.makeText(MainActivity.this, "Sessão encerrada", Toast.LENGTH_SHORT).show();
                ensureSession();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
        ensureSession();
    }

    @Override
    protected void onPause() {
        stopStreaming();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (uploadExecutor != null) {
            uploadExecutor.shutdownNow();
            uploadExecutor = null;
        }
        super.onDestroy();
    }

    private boolean ensureSession() {
        session = sessionManager.loadSession();
        if (session.hasValidDevice()) {
            return true;
        }
        Intent intent = new Intent(this, LoginActivity.class);
        startActivityForResult(intent, LOGIN_REQUEST);
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOGIN_REQUEST) {
            session = sessionManager.loadSession();
            if (!session.hasValidDevice()) {
                Toast.makeText(this, "Autenticação necessária para transmitir", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Sessão pronta", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startStreaming() {
        if (cameraDevice == null) {
            Toast.makeText(this, "Câmera indisponível", Toast.LENGTH_SHORT).show();
            isStreaming.set(false);
            return;
        }
        startNewSegment();
    }

    private void stopStreaming() {
        isStreaming.set(false);
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(segmentSplitRunnable);
        }
        stopCurrentSegment(true);
        setStatus(R.string.status_idle);
    }

    private void startNewSegment() {
        if (cameraDevice == null || !previewView.isAvailable()) {
            return;
        }
        try {
            closeCaptureSession();
            setUpMediaRecorder();

            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recorderSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        isRecording.set(true);
                        currentSegmentStart = System.currentTimeMillis();
                        scheduleNextSplit();
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Erro ao iniciar gravação", e);
                        Toast.makeText(MainActivity.this, "Falha ao iniciar captura", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Falha ao configurar sessão de gravação", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao preparar segmento", e);
            Toast.makeText(this, "Erro ao preparar vídeo", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleNextSplit() {
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacks(segmentSplitRunnable);
            backgroundHandler.postDelayed(segmentSplitRunnable, Config.SEGMENT_DURATION_MS);
        }
    }

    private void stopCurrentSegment(final boolean finalSegment) {
        if (!isRecording.get()) {
            if (finalSegment) {
                createCameraPreviewSession();
            }
            return;
        }
        isRecording.set(false);
        final long finishedAt = System.currentTimeMillis();
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (captureSession != null) {
                        captureSession.stopRepeating();
                        captureSession.abortCaptures();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro ao interromper captura", e);
                }

                try {
                    mediaRecorder.stop();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Erro ao finalizar gravação", e);
                    deleteCurrentFile();
                }
                mediaRecorder.reset();

                CameraCaptureSession localSession = captureSession;
                if (localSession != null) {
                    localSession.close();
                    captureSession = null;
                }

                final File segmentFile = currentSegmentFile;
                final long startedAt = currentSegmentStart;
                currentSegmentFile = null;

                if (segmentFile != null && segmentFile.exists()) {
                    setStatus(R.string.status_uploading);
                    uploadSegment(segmentFile, startedAt, finishedAt);
                }

                if (isStreaming.get() && !finalSegment) {
                    startNewSegment();
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(R.string.status_idle);
                        }
                    });
                    createCameraPreviewSession();
                }
            }
        });
    }

    private void uploadSegment(final File file, final long startedAt, final long finishedAt) {
        final SessionManager.Session currentSession = session;
        if (currentSession == null || !currentSession.hasValidDevice()) {
            Log.w(TAG, "Sessão inválida, descartando segmento");
            if (!file.delete()) {
                Log.w(TAG, "Não foi possível apagar segmento temporário");
            }
            return;
        }
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            Log.w(TAG, "Executor de upload indisponível");
            if (!file.delete()) {
                Log.w(TAG, "Não foi possível apagar segmento temporário");
            }
            return;
        }
        uploadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    fis.close();
                    byte[] bytes = baos.toByteArray();
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    ApiClient.uploadSegment(
                            currentSession.backendUrl,
                            currentSession.deviceKey,
                            currentSession.deviceId,
                            currentSession.deviceName,
                            base64,
                            startedAt,
                            finishedAt
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao enviar vídeo", e);
                } finally {
                    if (!file.delete()) {
                        Log.w(TAG, "Não foi possível apagar segmento temporário");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isStreaming.get()) {
                                statusText.setText(R.string.status_streaming);
                            } else {
                                statusText.setText(R.string.status_idle);
                            }
                        }
                    });
                }
            }
        });
    }

    private void setUpMediaRecorder() throws IOException {
        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();
        } else {
            mediaRecorder.reset();
        }

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncodingBitRate(5_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(1280, 720);
        mediaRecorder.setAudioEncodingBitRate(128_000);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setOrientationHint(90);

        File outputDir = getExternalFilesDir(null);
        if (outputDir == null) {
            outputDir = getFilesDir();
        }
        currentSegmentFile = File.createTempFile("segment_", ".mp4", outputDir);
        mediaRecorder.setOutputFile(currentSegmentFile.getAbsolutePath());
        mediaRecorder.prepare();
    }

    private void openCamera() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST);
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = getBackCameraId(manager);
            if (cameraId == null) {
                Toast.makeText(this, "Câmera traseira não encontrada", Toast.LENGTH_LONG).show();
                return;
            }
            if (!previewView.isAvailable()) {
                previewView.setSurfaceTextureListener(surfaceTextureListener);
                return;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erro ao acessar câmera", e);
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            Toast.makeText(MainActivity.this, "Erro na câmera", Toast.LENGTH_SHORT).show();
        }
    };

    private void createCameraPreviewSession() {
        if (cameraDevice == null || !previewView.isAvailable()) {
            return;
        }
        try {
            closeCaptureSession();
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(texture);
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            List<Surface> surfaces = Collections.singletonList(surface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Erro ao iniciar preview", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Falha ao configurar preview", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erro ao criar sessão de preview", e);
        }
    }

    private void closeCaptureSession() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception ignored) {
            }
            captureSession.close();
            captureSession = null;
        }
    }

    private void closeCamera() {
        closeCaptureSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Erro ao parar thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private String getBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return null;
    }

    private void deleteCurrentFile() {
        if (currentSegmentFile != null && currentSegmentFile.exists()) {
            if (!currentSegmentFile.delete()) {
                Log.w(TAG, "Não foi possível apagar arquivo após falha");
            }
        }
        currentSegmentFile = null;
    }

    private void setStatus(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(resId);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults.length > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Permissões de câmera e áudio são necessárias", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
