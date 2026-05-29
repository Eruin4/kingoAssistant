package com.example.homeassistantvoice;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class MicWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, false);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (MicService.ACTION_WIDGET_UPDATE.equals(intent.getAction())) {
            boolean recording = intent.getBooleanExtra(MicService.EXTRA_RECORDING, false);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, MicWidgetProvider.class));
            for (int id : ids) {
                updateWidget(context, manager, id, recording);
            }
        }
    }

    private void updateWidget(Context context, AppWidgetManager manager, int widgetId, boolean recording) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.mic_widget_layout);

        // Toggle mic on click
        Intent toggleIntent = new Intent(context, MicService.class);
        toggleIntent.setAction(MicService.ACTION_TOGGLE);
        PendingIntent pendingIntent = PendingIntent.getForegroundService(
                context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        // Update UI based on state
        if (recording) {
            views.setTextViewText(R.id.widget_icon, "\uD83D\uDD34");
            views.setTextViewText(R.id.widget_status, "녹음 중...");
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.mic_widget_background_recording);
        } else {
            views.setTextViewText(R.id.widget_icon, "\uD83C\uDFA4");
            views.setTextViewText(R.id.widget_status, "탭하여 녹음");
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.mic_widget_background);
        }

        manager.updateAppWidget(widgetId, views);
    }
}
