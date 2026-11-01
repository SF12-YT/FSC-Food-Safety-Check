package com.fosch.org;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

/** Dedicated live camera scanner shown inside FOSCH instead of launching the stock camera app. */
public class ScannerActivity extends Activity {
    private DecoratedBarcodeView barcodeView;
    private boolean resultDelivered = false;
    private boolean torchEnabled = false;
    private Button torchButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean autoRotateEnabled = false;
        try {
            autoRotateEnabled = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Exception ignored) { }
        setRequestedOrientation(autoRotateEnabled ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().setStatusBarColor(Color.rgb(13, 19, 34));
        getWindow().setNavigationBarColor(Color.rgb(13, 19, 34));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 12, 23));
        barcodeView = new DecoratedBarcodeView(this);
        barcodeView.setStatusText("Align the barcode inside the frame");
        root.addView(barcodeView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new ScannerGuideOverlay(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), dp(9), dp(10), dp(9));
        toolbar.setBackground(background(Color.argb(220, 13, 19, 34), 20));
        toolbar.setElevation(dp(8));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Scan a product");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, 1);
        TextView subtitle = new TextView(this);
        subtitle.setText("Barcode scanning starts automatically");
        subtitle.setTextColor(Color.rgb(196, 208, 226));
        subtitle.setTextSize(11);
        heading.addView(title);
        heading.addView(subtitle);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));

        torchButton = button("Flash off", Color.rgb(43, 57, 83));
        torchButton.setOnClickListener(v -> toggleTorch());
        toolbar.addView(torchButton, new LinearLayout.LayoutParams(dp(94), dp(44)));
        Button close = button("Close", Color.rgb(43, 57, 83));
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(68), dp(44));
        closeParams.leftMargin = dp(7);
        toolbar.addView(close, closeParams);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topParams.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(toolbar, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(16), dp(11), dp(16), dp(12));
        bottom.setBackground(background(Color.argb(225, 13, 19, 34), 20));
        bottom.setElevation(dp(8));
        TextView instruction = new TextView(this);
        instruction.setText("Move closer or farther until the barcode is sharp and centred.");
        instruction.setTextColor(Color.rgb(218, 226, 238));
        instruction.setTextSize(12);
        instruction.setGravity(Gravity.CENTER);
        bottom.addView(instruction, new LinearLayout.LayoutParams(-1, dp(34)));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(14));
        root.addView(bottom, bottomParams);
        setContentView(root);

        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult barcodeResult) {
                if (resultDelivered || barcodeResult == null || barcodeResult.getText() == null) return;
                resultDelivered = true;
                barcodeView.pause();
                Intent result = new Intent();
                result.putExtra("SCAN_RESULT", barcodeResult.getText());
                setResult(Activity.RESULT_OK, result);
                finish();
            }
            @Override public void possibleResultPoints(List<com.google.zxing.ResultPoint> points) { }
        });
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
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(background(color, 15));
        b.setElevation(dp(2));
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        if (barcodeView != null && !resultDelivered) barcodeView.resume();
    }

    @Override protected void onPause() {
        if (barcodeView != null) {
            if (torchEnabled) {
                try { barcodeView.setTorchOff(); } catch (Exception ignored) { }
            }
            barcodeView.pause();
        }
        super.onPause();
    }

    private void toggleTorch() {
        if (barcodeView == null || torchButton == null) return;
        try {
            if (torchEnabled) {
                barcodeView.setTorchOff();
                torchEnabled = false;
                torchButton.setText("Flash off");
            } else {
                barcodeView.setTorchOn();
                torchEnabled = true;
                torchButton.setText("Flash on");
            }
        } catch (Exception e) {
            torchEnabled = false;
            try { barcodeView.setTorchOff(); } catch (Exception ignored) { }
            torchButton.setText("Unavailable");
            android.widget.Toast.makeText(this, "This camera does not provide a usable flashlight.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private class ScannerGuideOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScannerGuideOverlay() { super(ScannerActivity.this); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = getWidth() * 0.12f;
            float right = getWidth() * 0.88f;
            float top = getHeight() * 0.39f;
            float bottom = getHeight() * 0.59f;
            float length = dp(30);
            paint.setColor(Color.argb(190, 255, 255, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            RectF frame = new RectF(left, top, right, bottom);
            Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
            faint.setColor(Color.argb(28, 255, 255, 255));
            faint.setStyle(Paint.Style.STROKE);
            faint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(frame, dp(12), dp(12), faint);
            canvas.drawLine(left, top + length, left, top, paint);
            canvas.drawLine(left, top, left + length, top, paint);
            canvas.drawLine(right - length, top, right, top, paint);
            canvas.drawLine(right, top, right, top + length, paint);
            canvas.drawLine(left, bottom - length, left, bottom, paint);
            canvas.drawLine(left, bottom, left + length, bottom, paint);
            canvas.drawLine(right - length, bottom, right, bottom, paint);
            canvas.drawLine(right, bottom - length, right, bottom, paint);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}