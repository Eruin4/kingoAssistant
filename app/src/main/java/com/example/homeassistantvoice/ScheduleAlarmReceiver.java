package com.example.homeassistantvoice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class ScheduleAlarmReceiver extends BroadcastReceiver {
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TIME = "time";
    static final String EXTRA_MEMO = "memo";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String title = intent.getStringExtra(EXTRA_TITLE);
        String time = intent.getStringExtra(EXTRA_TIME);
        String memo = intent.getStringExtra(EXTRA_MEMO);
        if (title == null || title.trim().isEmpty()) {
            title = "일정 알림";
        }
        String text = (time == null || time.isEmpty() ? "" : time + "  ")
                + (memo == null || memo.isEmpty() ? "곧 시작합니다." : memo);

        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, 2001, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, MainActivity.SCHEDULE_CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), builder.build());

        Intent widgetUpdate = new Intent(MicService.ACTION_WIDGET_UPDATE);
        widgetUpdate.setPackage(context.getPackageName());
        context.sendBroadcast(widgetUpdate);
    }
}
