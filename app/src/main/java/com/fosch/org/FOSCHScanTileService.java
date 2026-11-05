package com.fosch.org;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * One-tap FOSCH barcode scanner entry point in Android Quick Settings.
 * The scanner itself remains owned by MainActivity so permission handling,
 * camera orientation, torch controls and barcode result handling stay unified.
 */
public final class FOSCHScanTileService extends TileService {
    public static final String EXTRA_SCAN_FROM_TILE = "com.fosch.org.EXTRA_SCAN_FROM_TILE";

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setLabel("FOSCH Scan");
            tile.setSubtitle("Open barcode scanner");
            tile.setContentDescription("Open the FOSCH barcode scanner");
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        Intent launch = new Intent(this, MainActivity.class);
        launch.putExtra(EXTRA_SCAN_FROM_TILE, true);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    9057,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(launch);
        }
    }
}
