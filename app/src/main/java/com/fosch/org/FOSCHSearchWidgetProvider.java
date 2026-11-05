package com.fosch.org;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** FOSCH 2x1 home-screen quick scan and app search widget. */
public final class FOSCHSearchWidgetProvider extends AppWidgetProvider {
    static final String PREFERENCES = "fosch-search-widget";
    static final String KEY_LAST_QUERY = "last_query";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateWidget(context, manager, id);
    }

    static String savedQuery(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_LAST_QUERY, "").trim();
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, FOSCHSearchWidgetProvider.class));
        for (int id : ids) updateWidget(context, manager, id);
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.fosch_search_widget);

        Intent scan = new Intent(context, MainActivity.class);
        scan.putExtra(FOSCHScanTileService.EXTRA_SCAN_FROM_TILE, true);
        scan.setAction("com.fosch.org.widget.SCAN." + id);
        scan.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent scanPending = PendingIntent.getActivity(context, id * 10 + 1, scan,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_scan_button, scanPending);

        Intent openSearch = new Intent(context, MainActivity.class);
        openSearch.setAction("com.fosch.org.widget.OPEN_SEARCH." + id);
        openSearch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent searchPending = PendingIntent.getActivity(context, id * 10 + 2, openSearch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_search_field, searchPending);

        manager.updateAppWidget(id, views);
    }
}
