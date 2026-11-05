package com.fosch.org;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** FOSCH 2x1 home-screen quick scan and additive search widget. */
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
        String query = savedQuery(context);
        views.setTextViewText(R.id.widget_search_query, query.isEmpty() ? "Search additives" : query);
        views.setTextViewText(R.id.widget_search_hint,
                query.isEmpty() ? "E-number or additive name" : "Tap to edit search");
        views.setContentDescription(R.id.widget_search_field,
                query.isEmpty() ? "Enter an E-number or additive name" : "Edit search: " + query);

        Intent scan = new Intent(context, MainActivity.class);
        scan.putExtra(FOSCHScanTileService.EXTRA_SCAN_FROM_TILE, true);
        scan.setAction("com.fosch.org.widget.SCAN." + id);
        scan.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent scanPending = PendingIntent.getActivity(context, id * 10 + 1, scan,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_scan_button, scanPending);

        Intent edit = new Intent(context, FOSCHWidgetSearchActivity.class);
        edit.putExtra(FOSCHWidgetSearchActivity.EXTRA_INITIAL_QUERY, query);
        edit.setAction("com.fosch.org.widget.EDIT." + id);
        PendingIntent editPending = PendingIntent.getActivity(context, id * 10 + 2, edit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_search_field, editPending);

        Intent search;
        int requestCode;
        if (query.isEmpty()) {
            search = new Intent(context, FOSCHWidgetSearchActivity.class);
            search.putExtra(FOSCHWidgetSearchActivity.EXTRA_INITIAL_QUERY, "");
            requestCode = id * 10 + 3;
        } else {
            search = new Intent(context, MainActivity.class);
            search.putExtra(MainActivity.EXTRA_WIDGET_SEARCH_QUERY, query);
            search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            requestCode = id * 10 + 4;
        }
        search.setAction("com.fosch.org.widget.SEARCH." + id);
        PendingIntent searchPending = PendingIntent.getActivity(context, requestCode, search,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_search_button, searchPending);
        manager.updateAppWidget(id, views);
    }
}
