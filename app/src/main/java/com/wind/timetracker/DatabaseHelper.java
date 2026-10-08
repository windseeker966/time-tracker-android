package com.wind.timetracker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "time_tracker.db";
    private static final int DB_VERSION = 7;

    DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE raw_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp_ms INTEGER NOT NULL," +
                "event_type TEXT NOT NULL," +
                "app_package TEXT," +
                "screen_on INTEGER NOT NULL," +
                "charging INTEGER NOT NULL," +
                "wifi_ssid TEXT," +
                "location_label TEXT," +
                "latitude REAL," +
                "longitude REAL," +
                "accuracy_m REAL," +
                "location_provider TEXT," +
                "pomodoro_phase TEXT," +
                "pomodoro_remaining_sec INTEGER," +
                "pomodoro_cycle INTEGER," +
                "continuous_usage_sec INTEGER," +
                "usage_reminder_limit_min INTEGER," +
                "continuous_usage_state TEXT," +
                "usage_alert_level INTEGER," +
                "usage_alert_response TEXT," +
                "alarm_id INTEGER," +
                "alarm_ring_sec INTEGER," +
                "alarm_progress INTEGER," +
                "alarm_wrong_count INTEGER," +
                "alarm_emergency_count INTEGER," +
                "alarm_detail TEXT," +
                "step_count INTEGER)");
        db.execSQL("CREATE INDEX idx_raw_events_timestamp ON raw_events(timestamp_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN latitude REAL");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN longitude REAL");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN accuracy_m REAL");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN location_provider TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN pomodoro_phase TEXT");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN pomodoro_remaining_sec INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN pomodoro_cycle INTEGER");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN continuous_usage_sec INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN usage_reminder_limit_min INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN continuous_usage_state TEXT");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN usage_alert_level INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN usage_alert_response TEXT");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_id INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_ring_sec INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_progress INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_wrong_count INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_emergency_count INTEGER");
            db.execSQL("ALTER TABLE raw_events ADD COLUMN alarm_detail TEXT");
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE raw_events ADD COLUMN step_count INTEGER");
        }
    }

    long insertSnapshot(String eventType, String appPackage, boolean screenOn,
                        boolean charging, String wifiSsid, String locationLabel,
                        Double latitude, Double longitude, Float accuracyM,
                        String locationProvider, String pomodoroPhase,
                        Integer pomodoroRemainingSec, Integer pomodoroCycle,
                        Long continuousUsageSec, Integer usageReminderLimitMin,
                        String continuousUsageState, Integer usageAlertLevel,
                        String usageAlertResponse, AlarmFields alarm, Long stepCount) {
        ContentValues values = new ContentValues();
        values.put("timestamp_ms", System.currentTimeMillis());
        values.put("event_type", eventType);
        values.put("app_package", appPackage);
        values.put("screen_on", screenOn ? 1 : 0);
        values.put("charging", charging ? 1 : 0);
        values.put("wifi_ssid", wifiSsid);
        values.put("location_label", locationLabel);
        values.put("latitude", latitude);
        values.put("longitude", longitude);
        values.put("accuracy_m", accuracyM);
        values.put("location_provider", locationProvider);
        values.put("pomodoro_phase", pomodoroPhase);
        values.put("pomodoro_remaining_sec", pomodoroRemainingSec);
        values.put("pomodoro_cycle", pomodoroCycle);
        values.put("continuous_usage_sec", continuousUsageSec);
        values.put("usage_reminder_limit_min", usageReminderLimitMin);
        values.put("continuous_usage_state", continuousUsageState);
        values.put("usage_alert_level", usageAlertLevel);
        values.put("usage_alert_response", usageAlertResponse);
        values.put("step_count", stepCount);
        if (alarm != null) {
            values.put("alarm_id", alarm.alarmId);
            values.put("alarm_ring_sec", alarm.ringSec);
            values.put("alarm_progress", alarm.progress);
            values.put("alarm_wrong_count", alarm.wrongCount);
            values.put("alarm_emergency_count", alarm.emergencyCount);
            values.put("alarm_detail", alarm.detail);
        }
        return getWritableDatabase().insertOrThrow("raw_events", null, values);
    }

    long countEvents() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM raw_events", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    String recentEvents(int limit) {
        StringBuilder result = new StringBuilder();
        String sql = "SELECT timestamp_ms,event_type,app_package,screen_on,charging,wifi_ssid,location_label," +
                "latitude,longitude,accuracy_m,location_provider,pomodoro_phase,pomodoro_remaining_sec,pomodoro_cycle," +
                "continuous_usage_sec,usage_reminder_limit_min,continuous_usage_state," +
                "usage_alert_level,usage_alert_response," +
                "alarm_id,alarm_ring_sec,alarm_progress,alarm_wrong_count,alarm_emergency_count,alarm_detail,step_count " +
                "FROM raw_events ORDER BY timestamp_ms DESC LIMIT " + Math.max(1, Math.min(limit, 100));
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.append(format.format(new Date(cursor.getLong(0))))
                        .append("  ").append(cursor.getString(1))
                        .append("\n  app=").append(value(cursor, 2))
                        .append("  screen=").append(cursor.getInt(3) == 1 ? "on" : "off")
                        .append("  charging=").append(cursor.getInt(4) == 1)
                        .append("\n  wifi=").append(value(cursor, 5))
                        .append("  location=").append(value(cursor, 6))
                        .append("\n  coordinates=").append(coordinates(cursor, 7, 8))
                        .append("  accuracy=").append(accuracy(cursor, 9))
                        .append("  provider=").append(value(cursor, 10))
                        .append("\n  pomodoro=").append(value(cursor, 11))
                        .append("  remaining=").append(cursor.isNull(12) ? "-" : cursor.getInt(12) + "s")
                        .append("  cycle=").append(cursor.isNull(13) ? "-" : cursor.getInt(13))
                        .append("\n  continuous_usage=").append(cursor.isNull(14) ? "-" : cursor.getLong(14) + "s")
                        .append("  limit=").append(cursor.isNull(15) ? "-" : cursor.getInt(15) + "m")
                        .append("  usage_state=").append(value(cursor, 16))
                        .append("  steps=").append(value(cursor, 25));
                if (!cursor.isNull(17) || !cursor.isNull(18)) {
                    result.append("\n  alert_level=").append(value(cursor, 17))
                            .append("  alert_response=").append(value(cursor, 18));
                }
                if (!cursor.isNull(19)) {
                    result.append("\n  alarm_ring=").append(value(cursor, 20)).append("s")
                            .append("  progress=").append(value(cursor, 21))
                            .append("  wrong=").append(value(cursor, 22))
                            .append("  emergency=").append(value(cursor, 23))
                            .append("  detail=").append(value(cursor, 24));
                }
                result.append("\n\n");
            }
        }
        return result.length() == 0 ? "尚无采集记录" : result.toString();
    }

    String exportJsonLines(Context context) throws Exception {
        return exportJsonLines(context, 0, Long.MAX_VALUE, "all");
    }

    String exportJsonLines(Context context, long startMs, long endMs, String rangeLabel) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String displayName = "time-tracker-" + rangeLabel + "-" + stamp + ".jsonl";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TimeTracker");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法创建导出文件");

        try (OutputStream output = resolver.openOutputStream(uri);
             Cursor cursor = getReadableDatabase().rawQuery(
                     "SELECT timestamp_ms,event_type,app_package,screen_on,charging,wifi_ssid,location_label," +
                             "latitude,longitude,accuracy_m,location_provider,pomodoro_phase,pomodoro_remaining_sec,pomodoro_cycle," +
                             "continuous_usage_sec,usage_reminder_limit_min,continuous_usage_state," +
                             "usage_alert_level,usage_alert_response," +
                             "alarm_id,alarm_ring_sec,alarm_progress,alarm_wrong_count,alarm_emergency_count,alarm_detail,step_count " +
                             "FROM raw_events WHERE timestamp_ms >= ? AND timestamp_ms < ? " +
                             "ORDER BY timestamp_ms",
                     new String[] { Long.toString(startMs), Long.toString(endMs) })) {
            if (output == null) throw new IllegalStateException("无法打开导出文件");
            while (cursor.moveToNext()) {
                JSONObject object = new JSONObject();
                object.put("timestamp_ms", cursor.getLong(0));
                object.put("event_type", cursor.getString(1));
                object.put("app_package", nullable(cursor, 2));
                object.put("screen_on", cursor.getInt(3) == 1);
                object.put("charging", cursor.getInt(4) == 1);
                object.put("wifi_ssid", nullable(cursor, 5));
                object.put("location_label", nullable(cursor, 6));
                object.put("latitude", nullableNumber(cursor, 7));
                object.put("longitude", nullableNumber(cursor, 8));
                object.put("accuracy_m", nullableNumber(cursor, 9));
                object.put("location_provider", nullable(cursor, 10));
                object.put("pomodoro_phase", nullable(cursor, 11));
                object.put("pomodoro_remaining_sec", nullableNumber(cursor, 12));
                object.put("pomodoro_cycle", nullableNumber(cursor, 13));
                object.put("continuous_usage_sec", nullableNumber(cursor, 14));
                object.put("usage_reminder_limit_min", nullableNumber(cursor, 15));
                object.put("continuous_usage_state", nullable(cursor, 16));
                object.put("usage_alert_level", nullableNumber(cursor, 17));
                object.put("usage_alert_response", nullable(cursor, 18));
                object.put("alarm_id", nullableNumber(cursor, 19));
                object.put("alarm_ring_sec", nullableNumber(cursor, 20));
                object.put("alarm_progress", nullableNumber(cursor, 21));
                object.put("alarm_wrong_count", nullableNumber(cursor, 22));
                object.put("alarm_emergency_count", nullableNumber(cursor, 23));
                object.put("alarm_detail", nullable(cursor, 24));
                object.put("step_count", nullableNumber(cursor, 25));
                output.write((object + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            resolver.delete(uri, null, null);
            throw error;
        }

        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        return "下载/TimeTracker/" + displayName;
    }

    /** 统计 sinceMs 之后的连续使用提醒次数、用户选择和提醒后的真实休息次数。 */
    AlertStats alertStats(long sinceMs) {
        AlertStats stats = new AlertStats();
        String sql = "SELECT event_type,usage_alert_response,COUNT(*) FROM raw_events " +
                "WHERE timestamp_ms >= ? AND event_type IN (?,?,?) GROUP BY 1,2";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[] {
                Long.toString(sinceMs), CollectorService.EVENT_ALERT,
                CollectorService.EVENT_NIGHT_ALERT, CollectorService.EVENT_ALERT_RESPONSE })) {
            while (cursor.moveToNext()) {
                String type = cursor.getString(0);
                String response = cursor.isNull(1) ? "" : cursor.getString(1);
                int count = cursor.getInt(2);
                if (CollectorService.EVENT_ALERT.equals(type)) {
                    stats.alerts += count;
                } else if (CollectorService.EVENT_NIGHT_ALERT.equals(type)) {
                    stats.alerts += count;
                    stats.nightAlerts += count;
                } else if (CollectorService.RESPONSE_REST.equals(response)) {
                    stats.rest += count;
                } else if (CollectorService.RESPONSE_SNOOZE.equals(response)) {
                    stats.snooze += count;
                } else if (CollectorService.RESPONSE_WORK.equals(response)) {
                    stats.work += count;
                } else if (CollectorService.RESPONSE_EMERGENCY.equals(response)) {
                    stats.emergency += count;
                } else if (CollectorService.OUTCOME_REST_COMPLETED.equals(response)
                        || CollectorService.OUTCOME_BREAK.equals(response)) {
                    stats.breaks += count;
                }
            }
        }
        return stats;
    }

    /** sinceMs 之后指定类型的事件数。 */
    int countEvents(String eventType, long sinceMs) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM raw_events WHERE event_type = ? AND timestamp_ms >= ?",
                new String[] { eventType, Long.toString(sinceMs) })) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** 最近一次闹钟：响铃时间、关闭时间与用时、答错次数和关闭方式。 */
    String lastAlarmSummary() {
        long alarmId;
        long firedMs;
        String firedDetail;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT alarm_id,timestamp_ms,alarm_detail FROM raw_events WHERE event_type = ? " +
                        "ORDER BY timestamp_ms DESC LIMIT 1",
                new String[] { MathAlarm.EVENT_FIRE })) {
            if (!cursor.moveToFirst()) return "还没有响过";
            alarmId = cursor.getLong(0);
            firedMs = cursor.getLong(1);
            firedDetail = cursor.isNull(2) ? null : cursor.getString(2);
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        StringBuilder text = new StringBuilder("最近一次")
                .append("test".equals(firedDetail) ? "（测试）" : "")
                .append("：").append(format.format(new Date(firedMs))).append(" 响");
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms,alarm_ring_sec,alarm_wrong_count,alarm_emergency_count FROM raw_events " +
                        "WHERE event_type = ? AND alarm_id = ? AND timestamp_ms >= ? " +
                        "ORDER BY timestamp_ms LIMIT 1",
                new String[] { MathAlarm.EVENT_DISMISSED, Long.toString(alarmId), Long.toString(firedMs) })) {
            if (!cursor.moveToFirst()) return text.append("，尚未关闭").toString();
            long ringSec = cursor.getLong(1);
            text.append("，").append(new SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(new Date(cursor.getLong(0)))).append(" 关闭")
                    .append(String.format(Locale.getDefault(), "\n  用时 %d 分 %02d 秒", ringSec / 60, ringSec % 60))
                    .append(" · 答错 ").append(cursor.getInt(2)).append(" 次")
                    .append(cursor.getInt(3) > 0 ? " · 紧急使用关闭" : " · 答题关闭");
        }
        return text.toString();
    }

    /** 闹钟相关事件的附加字段，非闹钟事件为 null。 */
    static final class AlarmFields {
        Long alarmId;
        Long ringSec;
        /** 当前这组题已连续答对的数量。 */
        Integer progress;
        Integer wrongCount;
        Integer emergencyCount;
        String detail;
    }

    static final class AlertStats {
        int alerts;
        int nightAlerts;
        int rest;
        int snooze;
        int work;
        int emergency;
        /** 提醒后 15 分钟内熄屏满 5 分钟，或完成 5 分钟休息倒计时。 */
        int breaks;
    }

    private static Object nullable(Cursor cursor, int index) {
        return cursor.isNull(index) ? JSONObject.NULL : cursor.getString(index);
    }

    private static Object nullableNumber(Cursor cursor, int index) {
        return cursor.isNull(index) ? JSONObject.NULL : cursor.getDouble(index);
    }

    private static String coordinates(Cursor cursor, int latitudeIndex, int longitudeIndex) {
        if (cursor.isNull(latitudeIndex) || cursor.isNull(longitudeIndex)) return "-";
        return String.format(Locale.US, "%.6f,%.6f", cursor.getDouble(latitudeIndex),
                cursor.getDouble(longitudeIndex));
    }

    private static String accuracy(Cursor cursor, int index) {
        if (cursor.isNull(index)) return "-";
        return String.format(Locale.US, "±%.1fm", cursor.getDouble(index));
    }

    private static String value(Cursor cursor, int index) {
        return cursor.isNull(index) ? "-" : cursor.getString(index);
    }
}
