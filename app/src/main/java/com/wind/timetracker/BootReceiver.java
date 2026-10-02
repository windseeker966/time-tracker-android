package com.wind.timetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        // 重启、更新应用后系统闹钟会被清除；改时间或时区后要按新的本地时间重新计算。
        if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            AlarmScheduler.schedule(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        AlarmScheduler.schedule(context);
        Intent service = new Intent(context, CollectorService.class);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException ignored) {
            // Some vendor builds require the user to enable app auto-start first.
        }
    }
}
