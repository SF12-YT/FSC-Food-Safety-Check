package com.fosch.org;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** FOSCH's in-app product camera with continuous autofocus, tap-to-focus and flashlight control. */
@SuppressWarnings("deprecation")
public class ProductCameraActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private AspectRatioSurfaceView surface;
    private int cameraDisplayOrientation = 0;
    private Button torchButton;
    private Button captureButton;
    private boolean torchEnabled = false;
    private boolean capturing = false;
    private String outputPath = "";
    private String focusMode = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        outputPath = getIntent().getStringExtra("PHOTO_PATH");
        if (outputPath == null || outputPath.trim().isEmpty()) { setResult(RESULT_CANCELED); finish(); return; }

        boolean autoRotate = false;
        try { autoRotate = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1; } catch (Exception ignored) { }
        setRequestedOrientation(autoRotate ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().setStatusBarColor(Color.rgb(13, 19, 34));
        getWindow().setNavigationBarColor(Color.rgb(13, 19, 34));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new AspectRatioSurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setOnClickListener(v -> requestTapFocus());
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(surface, previewParams);
        root.addView(new ViewfinderOverlay(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(9), dp(10), dp(9));
        top.setBackground(background(Color.argb(218, 13, 19, 34), 20));
        top.setElevation(dp(8));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Capture product");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, 1);
        TextView subtitle = new TextView(this);
        subtitle.setText("Keep the label inside the frame");
        subtitle.setTextColor(Color.rgb(196, 208, 226));
        subtitle.setTextSize(11);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title);
        heading.addView(subtitle);
        heading.setMinimumHeight(dp(48));
        top.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        torchButton = button("Flash off", Color.rgb(43, 57, 83));
        torchButton.setOnClickListener(v -> toggleTorch());
        top.addView(torchButton, new LinearLayout.LayoutParams(dp(94), dp(44)));
        Button close = button("Close", Color.rgb(43, 57, 83));
        close.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(68), dp(44));
        closeParams.leftMargin = dp(7);
        top.addView(close, closeParams);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topParams.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(13), dp(16), dp(14));
        bottom.setBackground(background(Color.argb(230, 13, 19, 34), 22));
        bottom.setElevation(dp(10));
        TextView hint = new TextView(this);
        hint.setText("Tap the preview to focus. Hold steady for a clear photo.");
        hint.setTextColor(Color.rgb(210, 219, 233));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(2);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hint.setMinHeight(dp(30));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.bottomMargin = dp(8);
        bottom.addView(hint, hintParams);

        captureButton = button("Capture photo", Color.rgb(35, 101, 190));
        captureButton.setTextSize(16);
        captureButton.setTypeface(null, 1);
        bottom.addView(captureButton, new LinearLayout.LayoutParams(-1, dp(58)));
        captureButton.setOnClickListener(v -> takePhoto());

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(14));
        root.addView(bottom, bottomParams);
        setContentView(root);
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(background(color, 15));
        b.setElevation(dp(2));
        return b;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void surfaceCreated(SurfaceHolder holder) { openCamera(holder); }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera == null || capturing) return;
        try {
            camera.stopPreview();
            configureCamera();
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            requestInitialFocus();
        } catch (Exception e) { showCameraError(); }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }

    private void openCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            configureCamera();
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            requestInitialFocus();
        } catch (Exception e) {
            releaseCamera();
            showCameraError();
        }
    }

    private void configureCamera() throws IOException {
        if (camera == null) return;
        Camera.Parameters p = camera.getParameters();

        List<String> focusModes = p.getSupportedFocusModes();
        focusMode = "";
        if (focusModes != null) {
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                focusMode = Camera.Parameters.FOCUS_MODE_AUTO;
            }
        }
        if (!focusMode.isEmpty()) p.setFocusMode(focusMode);

        List<String> modes = p.getSupportedFlashModes();
        if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_TORCH) && torchEnabled) {
            p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        } else if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
            p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        }
        camera.setParameters(p);

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == android.view.Surface.ROTATION_90 ? 90 : rotation == android.view.Surface.ROTATION_180 ? 180 : rotation == android.view.Surface.ROTATION_270 ? 270 : 0;
        cameraDisplayOrientation = (info.orientation - degrees + 360) % 360;
        camera.setDisplayOrientation(cameraDisplayOrientation);
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        if (surface != null && previewSize != null && previewSize.width > 0 && previewSize.height > 0) {
            boolean rotated = cameraDisplayOrientation == 90 || cameraDisplayOrientation == 270;
            float previewAspect = rotated ? (float) previewSize.height / (float) previewSize.width : (float) previewSize.width / (float) previewSize.height;
            surface.setAspectRatio(previewAspect);
        }
    }

    private void requestInitialFocus() {
        if (camera == null || capturing) return;
        if (Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)) {
            try { camera.autoFocus((success, activeCamera) -> { }); } catch (Exception ignored) { }
        }
    }

    private void requestTapFocus() {
        if (camera == null || capturing) return;
        try {
            camera.cancelAutoFocus();
            camera.autoFocus((success, activeCamera) -> { });
        } catch (Exception ignored) { }
    }

    private void toggleTorch() {
        if (camera == null || torchButton == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                torchButton.setText("Unavailable");
                Toast.makeText(this, "This camera does not support a controllable flashlight.", Toast.LENGTH_SHORT).show();
                return;
            }
            torchEnabled = !torchEnabled;
            p.setFlashMode(torchEnabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            torchButton.setText(torchEnabled ? "Flash on" : "Flash off");
        } catch (Exception e) {
            torchEnabled = false;
            torchButton.setText("Unavailable");
            Toast.makeText(this, "The flashlight could not be changed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        if (camera == null || capturing) return;
        capturing = true;
        captureButton.setEnabled(false);
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == android.view.Surface.ROTATION_90 ? 90 : rotation == android.view.Surface.ROTATION_180 ? 180 : rotation == android.view.Surface.ROTATION_270 ? 270 : 0;
            p.setRotation((info.orientation + degrees) % 360);
            camera.setParameters(p);

            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)) {
                camera.autoFocus((success, activeCamera) -> runOnUiThread(this::captureFocusedFrame));
            } else {
                captureFocusedFrame();
            }
        } catch (Exception e) {
            captureFocusedFrame();
        }
    }

    private void captureFocusedFrame() {
        if (camera == null || isFinishing()) return;
        try {
            camera.takePicture(null, null, (data, cam) -> {
                try (FileOutputStream out = new FileOutputStream(outputPath)) {
                    out.write(data);
                    out.flush();
                    setResult(RESULT_OK, new Intent().putExtra("PHOTO_PATH", outputPath));
                    finish();
                } catch (IOException e) {
                    setResult(RESULT_CANCELED);
                    Toast.makeText(this, "Could not save this photo. Please try again.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        } catch (Exception e) {
            capturing = false;
            if (captureButton != null) captureButton.setEnabled(true);
            Toast.makeText(this, "Could not capture photo. Please try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void showCameraError() {
        Toast.makeText(this, "FOSCH could not open the in-app camera. Check camera permission and close other apps using the camera.", Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.cancelAutoFocus(); } catch (Exception ignored) { }
            try { camera.stopPreview(); } catch (Exception ignored) { }
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
        torchEnabled = false;
    }

    /** Keeps the camera preview at its native aspect ratio instead of stretching it. */
    private static class AspectRatioSurfaceView extends SurfaceView {
        private float aspectRatio = 9f / 16f;
        AspectRatioSurfaceView(Activity context) { super(context); }
        void setAspectRatio(float ratio) {
            if (ratio > 0f && !Float.isNaN(ratio) && !Float.isInfinite(ratio) && Math.abs(aspectRatio-ratio)>0.001f) {
                aspectRatio=ratio;
                requestLayout();
            }
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth=MeasureSpec.getSize(widthMeasureSpec),maxHeight=MeasureSpec.getSize(heightMeasureSpec);
            if(maxWidth<=0||maxHeight<=0||aspectRatio<=0f){super.onMeasure(widthMeasureSpec,heightMeasureSpec);return;}
            int fittedWidth,fittedHeight;
            if((float)maxWidth/(float)maxHeight>aspectRatio){fittedHeight=maxHeight;fittedWidth=Math.round(fittedHeight*aspectRatio);}
            else{fittedWidth=maxWidth;fittedHeight=Math.round(fittedWidth/aspectRatio);}
            setMeasuredDimension(fittedWidth,fittedHeight);
        }
    }

    private class ViewfinderOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewfinderOverlay() { super(ProductCameraActivity.this); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = getWidth() * 0.10f;
            float right = getWidth() * 0.90f;
            float top = getHeight() * 0.27f;
            float bottom = getHeight() * 0.68f;
            float length = dp(34);
            paint.setColor(Color.argb(175, 255, 255, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            RectF frame = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(frame, dp(14), dp(14), faintPaint());
            canvas.drawLine(left, top + length, left, top, paint);
            canvas.drawLine(left, top, left + length, top, paint);
            canvas.drawLine(right - length, top, right, top, paint);
            canvas.drawLine(right, top, right, top + length, paint);
            canvas.drawLine(left, bottom - length, left, bottom, paint);
            canvas.drawLine(left, bottom, left + length, bottom, paint);
            canvas.drawLine(right - length, bottom, right, bottom, paint);
            canvas.drawLine(right, bottom - length, right, bottom, paint);
        }
        private Paint faintPaint() {
            Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
            faint.setColor(Color.argb(35, 255, 255, 255));
            faint.setStyle(Paint.Style.STROKE);
            faint.setStrokeWidth(dp(1));
            return faint;
        }
    }

    @Override protected void onPause() { releaseCamera(); super.onPause(); }
}