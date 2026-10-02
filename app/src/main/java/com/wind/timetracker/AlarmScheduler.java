package com.wind.timetracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** 每日数学闹钟的设置与系统闹钟注册。响铃和答题由 {@link MathAlarm} 负责。 */
final class AlarmScheduler {
    static final String PREFS = "math_alarm";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_HOUR = "hour";
    static final String KEY_MINUTE = "minute";
    // 响铃状态：持久化，服务被杀或重启后继续响，不能靠重启绕过。
    static final String KEY_RINGING = "ringing";
    static final String KEY_ALARM_ID = "alarm_id";
    static final String KEY_RING_START_MS = "ring_start_ms";
    static final String KEY_WRONG_TOTAL = "wrong_total";
    static final String KEY_TEST = "test";

    static final int DEFAULT_HOUR = 7;
    static final int DEFAULT_MINUTE = 0;
    static final int QUESTION_COUNT = 10;
    static final long TEST_DELAY_MS = 60_000L;

    static final String ACTION_TRIGGER = "com.wind.timetracker.ALARM_TRIGGER";
    static final String EXTRA_ALARM_ID = "alarm_id";
    static final String EXTRA_TEST = "test";

    private static final int REQUEST_DAILY = 200;
    private static final int REQUEST_TEST = 201;
    private static final int REQUEST_SHOW = 203;

    private AlarmScheduler() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static int hour(Context context) {
        return prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR);
    }

    static int minute(Context context) {
        return prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE);
    }

    static String formatTime(Context context) {
        return String.format(Locale.US, "%02d:%02d", hour(context), minute(context));
    }

    static boolean isRinging(Context context) {
        return prefs(context).getBoolean(KEY_RINGING, false);
    }

    /** 下一次每日闹钟的时间（毫秒），未启用时返回 0。 */
    static long nextTriggerMs(Context context) {
        if (!isEnabled(context)) return 0;
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.toLocalDate().atTime(LocalTime.of(hour(context), minute(context)));
        if (!next.isAfter(now)) next = next.plusDays(1);
        return ZonedDateTime.of(next, zone).toInstant().toEpochMilli();
    }

    /** 根据当前设置重新注册（或取消）每日闹钟。开机、改时间、闹钟触发后都要调用。 */
    static void schedule(Context context) {
        long triggerMs = nextTriggerMs(context);
        if (triggerMs <= 0) {
            alarmManager(context).cancel(triggerIntent(context, REQUEST_DAILY, 0, false));
            return;
        }
        setAlarm(context, triggerMs, triggerIntent(context, REQUEST_DAILY, triggerMs, false));
    }

    static long scheduleTest(Context context) {
        long triggerMs = System.currentTimeMillis() + TEST_DELAY_MS;
        setAlarm(context, triggerMs, triggerIntent(context, REQUEST_TEST, triggerMs, true));
        return triggerMs;
    }

    static boolean canScheduleExact(Context context) {
        return Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms();
    }

    private static void setAlarm(Context context, long triggerMs, PendingIntent operation) {
        AlarmManager manager = alarmManager(context);
        if (canScheduleExact(context)) {
            // setAlarmClock 在 Doze 下也会准时触发，并在状态栏显示闹钟图标。
            PendingIntent show = PendingIntent.getActivity(context, REQUEST_SHOW,
                    new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, show), operation);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, operation);
        }
    }

    private static PendingIntent triggerIntent(Context context, int requestCode, long alarmId, boolean test) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_TRIGGER)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TEST, test);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static AlarmManager alarmManager(Context context) {
        return context.getSystemService(AlarmManager.class);
    }
}
