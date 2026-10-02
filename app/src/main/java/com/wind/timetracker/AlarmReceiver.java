package com.wind.timetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收系统闹钟，交给采集服务响铃。setAlarmClock 触发的广播允许启动前台服务。 */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmScheduler.ACTION_TRIGGER.equals(intent.getAction())) return;
        boolean test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false);
        // 每日闹钟先注册下一天，避免本次响铃期间出错导致明天不响。
        if (!test) AlarmScheduler.schedule(context);
        Intent service = new Intent(context, CollectorService.class)
                .setAction(CollectorService.ACTION_ALARM_FIRE)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID,
                        intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, System.currentTimeMillis()))
                .putExtra(AlarmScheduler.EXTRA_TEST, test);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException ignored) {
            // 厂商系统可能拦截自启动；需要在系统设置中允许自启动。
        }
    }
}
