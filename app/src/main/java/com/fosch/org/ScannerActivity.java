package com.fosch.org;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
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
        // Follow the phone's rotation sensor only when the user's Auto-rotate setting is enabled.
        boolean autoRotateEnabled = false;
        try {
            autoRotateEnabled = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Exception ignored) { }
        setRequestedOrientation(autoRotateEnabled ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        getWindow().setStatusBarColor(Color.rgb(17, 22, 41));
        getWindow().setNavigationBarColor(Color.rgb(17, 22, 41));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(17, 22, 41));
        barcodeView = new DecoratedBarcodeView(this);
        barcodeView.setStatusText("Point the camera at a product barcode");
        root.addView(barcodeView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(10), dp(16), dp(10));
        toolbar.setBackgroundColor(Color.rgb(17, 22, 41));
        TextView title = new TextView(this);
        title.setText("FOSCH barcode scanner");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, 1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        torchButton = new Button(this);
        torchButton.setText("Flashlight off");
        torchButton.setTextSize(12);
        torchButton.setAllCaps(false);
        torchButton.setTextColor(Color.WHITE);
        torchButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(41, 47, 80)));
        torchButton.setOnClickListener(v -> toggleTorch());
        toolbar.addView(torchButton, new LinearLayout.LayoutParams(dp(112), dp(48)));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setTextColor(Color.WHITE);
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(41, 47, 80)));
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(74), dp(48));
        closeParams.leftMargin = dp(6);
        toolbar.addView(close, closeParams);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(toolbar, topParams);
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
                torchButton.setText("Flashlight off");
            } else {
                barcodeView.setTorchOn();
                torchEnabled = true;
                torchButton.setText("Flashlight on");
            }
        } catch (Exception e) {
            torchEnabled = false;
            try { barcodeView.setTorchOff(); } catch (Exception ignored) { }
            torchButton.setText("Flashlight unavailable");
            android.widget.Toast.makeText(this, "This camera does not provide a usable flashlight.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
