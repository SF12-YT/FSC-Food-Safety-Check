package com.fosch.org;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

/**
 * Android opens this activity when the user long-presses the FOSCH Quick
 * Settings tile. Hand the user directly to FOSCH's standard app-info page.
 */
public final class FOSCHScanTilePreferencesActivity extends Activity {
    private static final String TAG = "FOSCHQuickTile";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent appInfo = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)
            );
            startActivity(appInfo);
        } catch (ActivityNotFoundException error) {
            Log.w(TAG, "Unable to open FOSCH app information settings", error);
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                // There is no alternate Settings screen available on this device.
            }
        }
        finish();
    }
}
