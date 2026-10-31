package com.fosch.org;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** FOSCH's own camera preview for product-label evidence, with an in-app flashlight toggle. */
@SuppressWarnings("deprecation")
public class ProductCameraActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private SurfaceView surface;
    private Button torchButton;
    private Button captureButton;
    private boolean torchEnabled = false;
    private boolean capturing = false;
    private String outputPath = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        outputPath = getIntent().getStringExtra("PHOTO_PATH");
        if (outputPath == null || outputPath.trim().isEmpty()) { setResult(RESULT_CANCELED); finish(); return; }
        boolean autoRotate = false;
        try { autoRotate = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1; } catch (Exception ignored) { }
        setRequestedOrientation(autoRotate ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().setStatusBarColor(Color.rgb(16, 23, 40));
        getWindow().setNavigationBarColor(Color.rgb(16, 23, 40));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        top.setBackgroundColor(Color.rgb(16, 23, 40));
        TextView title = new TextView(this);
        title.setText("Capture Product");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, 1);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        torchButton = button("Flashlight off");
        torchButton.setOnClickListener(v -> toggleTorch());
        top.addView(torchButton, new LinearLayout.LayoutParams(dp(124), dp(46)));
        Button close = button("Close");
        close.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(72), dp(46));
        closeParams.leftMargin = dp(6);
        top.addView(close, closeParams);
        root.addView(top, new FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        captureButton = button("Capture Product");
        captureButton.setTextSize(16);
        captureButton.setOnClickListener(v -> takePhoto());
        FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
        captureParams.setMargins(dp(20), 0, dp(20), dp(20));
        root.addView(captureButton, captureParams);
        setContentView(root);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35, 94, 177)));
        return b;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void surfaceCreated(SurfaceHolder holder) { openCamera(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera == null || capturing) return;
        try { camera.stopPreview(); configureCamera(); camera.setPreviewDisplay(holder); camera.startPreview(); }
        catch (Exception e) { showCameraError(); }
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }

    private void openCamera(SurfaceHolder holder) {
        try { camera = Camera.open(); configureCamera(); camera.setPreviewDisplay(holder); camera.startPreview(); }
        catch (Exception e) { releaseCamera(); showCameraError(); }
    }
    private void configureCamera() throws IOException {
        if (camera == null) return;
        Camera.Parameters p = camera.getParameters();
        List<String> modes = p.getSupportedFlashModes();
        if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_TORCH) && torchEnabled) p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        else if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_OFF)) p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        camera.setParameters(p);
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == android.view.Surface.ROTATION_90 ? 90 : rotation == android.view.Surface.ROTATION_180 ? 180 : rotation == android.view.Surface.ROTATION_270 ? 270 : 0;
        camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
    }
    private void toggleTorch() {
        if (camera == null || torchButton == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                torchButton.setText("Flashlight unavailable");
                Toast.makeText(this, "This camera does not support a controllable flashlight.", Toast.LENGTH_SHORT).show();
                return;
            }
            torchEnabled = !torchEnabled;
            p.setFlashMode(torchEnabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            torchButton.setText(torchEnabled ? "Flashlight on" : "Flashlight off");
        } catch (Exception e) {
            torchEnabled = false;
            torchButton.setText("Flashlight unavailable");
            Toast.makeText(this, "The flashlight could not be changed.", Toast.LENGTH_SHORT).show();
        }
    }
    private void takePhoto() {
        if (camera == null || capturing) return;
        capturing = true; captureButton.setEnabled(false);
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == android.view.Surface.ROTATION_90 ? 90 : rotation == android.view.Surface.ROTATION_180 ? 180 : rotation == android.view.Surface.ROTATION_270 ? 270 : 0;
            p.setRotation((info.orientation + degrees) % 360);
            camera.setParameters(p);
            camera.takePicture(null, null, (data, cam) -> {
                try (FileOutputStream out = new FileOutputStream(outputPath)) {
                    out.write(data); out.flush();
                    setResult(RESULT_OK, new Intent().putExtra("PHOTO_PATH", outputPath));
                    finish();
                } catch (IOException e) {
                    setResult(RESULT_CANCELED);
                    Toast.makeText(this, "Could not save this photo. Please try again.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        } catch (Exception e) {
            capturing = false; captureButton.setEnabled(true);
            Toast.makeText(this, "Could not capture photo. Please try again.", Toast.LENGTH_LONG).show();
        }
    }
    private void showCameraError() {
        Toast.makeText(this, "FOSCH could not open the in-app camera. Check camera permission and close other apps using the camera.", Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED); finish();
    }
    private void releaseCamera() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
        torchEnabled = false;
    }
    @Override protected void onPause() { releaseCamera(); super.onPause(); }
}
