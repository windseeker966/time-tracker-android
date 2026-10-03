package com.wind.timetracker;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class CollectorService extends Service {
    static final String ACTION_STOP = "com.wind.timetracker.STOP";
    static final String ACTION_POMODORO_START = "com.wind.timetracker.POMODORO_START";
    static final String ACTION_POMODORO_STOP = "com.wind.timetracker.POMODORO_STOP";
    static final String ACTION_TEST_USAGE_REMINDER = "com.wind.timetracker.TEST_USAGE_REMINDER";
    static final String ACTION_USAGE_REMINDER_SETTINGS_CHANGED =
            "com.wind.timetracker.USAGE_REMINDER_SETTINGS_CHANGED";
    static final String ACTION_ALARM_FIRE = "com.wind.timetracker.ALARM_FIRE";
    static final String ACTION_ALARM_STOP_NO_OVERLAY = "com.wind.timetracker.ALARM_STOP_NO_OVERLAY";
    static final String ACTION_ALARM_SETTINGS_CHANGED = "com.wind.timetracker.ALARM_SETTINGS_CHANGED";
    static final String PREFS_POMODORO = "pomodoro";
    static final String KEY_POMODORO_PHASE = "phase";
    static final String KEY_POMODORO_END_MS = "end_ms";
    static final String KEY_POMODORO_CYCLE = "cycle";
    static final String PHASE_IDLE = "idle";
    static final String PHASE_FOCUS = "focus";
    static final String PHASE_BREAK = "break";
    static final long FOCUS_DURATION_MS = 25 * 60_000L;
    static final long BREAK_DURATION_MS = 5 * 60_000L;
    private static final String CHANNEL_ID = "time_tracker_collection";
    private static final String POMODORO_CHANNEL_ID = "pomodoro_alerts";
    private static final String USAGE_REMINDER_CHANNEL_ID = "continuous_usage_alerts";
    private static final int NOTIFICATION_ID = 1001;
    private static final int POMODORO_NOTIFICATION_ID = 1002;
    private static final int USAGE_REMINDER_NOTIFICATION_ID = 1003;
    static final String PREFS_USAGE_REMINDER = "usage_reminder";
    static final String KEY_USAGE_LIMIT_MINUTES = "limit_minutes";
    static final String KEY_USAGE_START_ELAPSED = "session_start_elapsed";
    static final String KEY_USAGE_NEXT_ALERT_ELAPSED = "next_alert_elapsed";
    static final String KEY_USAGE_LAST_CHECK_ELAPSED = "last_check_elapsed";
    static final String KEY_USAGE_INACTIVE_SINCE_ELAPSED = "inactive_since_elapsed";
    static final int DEFAULT_USAGE_LIMIT_MINUTES = 40;
    static final int MIN_USAGE_LIMIT_MINUTES = 1;
    static final int MAX_USAGE_LIMIT_MINUTES = 240;
    static final long CONTINUOUS_USAGE_RESET_GRACE_MS = 5 * 60_000L;
    private static final long MAX_USAGE_CHECK_GAP_MS = 2 * 60_000L;

    // 提醒升级：本轮连续使用中的第几次提醒，决定弹窗需要先等待多久才能选择。
    static final String ACTION_USAGE_ALERT_RESPONSE = "com.wind.timetracker.USAGE_ALERT_RESPONSE";
    static final String EXTRA_USAGE_ALERT_RESPONSE = "response";
    static final String KEY_ALERT_LEVEL = "alert_level";
    static final String KEY_ALERT_PENDING = "alert_pending";
    static final String KEY_LOCK_UNTIL_ELAPSED = "lock_until_elapsed";
    static final String KEY_REST_UNTIL_ELAPSED = "rest_until_elapsed";
    static final String KEY_SNOOZE_USED = "snooze_used";
    static final String KEY_LAST_ALERT_ELAPSED = "last_alert_elapsed";
    /** 旧版本的深夜 15 分钟计时起点，只用于清理。 */
    private static final String KEY_NIGHT_ANCHOR_ELAPSED_LEGACY = "night_anchor_elapsed";
    static final String KEY_NIGHT_MODE_ENABLED = "night_mode_enabled";
    // 深夜时段按台北时间（UTC+8）判断，与手机系统时区无关。
    static final ZoneId NIGHT_ZONE = ZoneId.of("Asia/Taipei");
    static final int NIGHT_END_HOUR = 5;                   // 00:00–05:00
    static final long SNOOZE_MS = 10 * 60_000L;
    static final long NIGHT_SNOOZE_MS = 5 * 60_000L;
    static final long EMERGENCY_MS = 10 * 60_000L;
    static final long REST_MS = 5 * 60_000L;
    /** 提醒后多长时间内出现 5 分钟熄屏，算作“提醒后真正休息”。 */
    static final long BREAK_ATTRIBUTION_MS = 15 * 60_000L;

    static final String EVENT_ALERT = "continuous_use_alert";
    static final String EVENT_NIGHT_ALERT = "continuous_use_night_alert";
    static final String EVENT_ALERT_RESPONSE = "usage_alert_response";
    static final String RESPONSE_REST = "rest";
    static final String RESPONSE_SNOOZE = "snooze";
    static final String RESPONSE_WORK = "work";
    static final String RESPONSE_EMERGENCY = "emergency";
    static final String OUTCOME_REST_COMPLETED = "rest_completed";
    static final String OUTCOME_BREAK = "break_after_alert";

    private DatabaseHelper database;
    private ScheduledExecutorService executor;
    private BroadcastReceiver stateReceiver;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private volatile String locationLabel = "未知";
    private volatile Location latestLocation;
    private volatile boolean locationForegroundActive;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile View usageReminderView;
    // 以下弹窗状态只在主线程读写。
    private boolean overlayTest;
    private boolean overlayResting;
    private long overlayLockUntil;
    private long overlayRestUntil;
    private TextView overlayCountdownView;
    private Button overlayEmergencyButton;
    private final List<Button> overlayChoiceButtons = new ArrayList<>();
    private final Runnable overlayTicker = this::tickUsageReminderOverlay;
    private MathAlarm mathAlarm;  // 只在主线程使用
    /** 计步传感器只在主线程注册/注销；最新累计步数（开机以来），未知为 -1。 */
    private SensorEventListener stepListener;
    private volatile long latestStepCount = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        database = new DatabaseHelper(this);
        createNotificationChannel();
        startAsForeground();
        registerStateReceiver();
        startLocationUpdatesIfAllowed();
        startStepCounterIfAllowed();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> {
            // 运行中才授予计步权限时，在下一次采样补注册。
            if (stepListener == null) mainHandler.post(this::startStepCounterIfAllowed);
            saveSnapshot("sample");
        }, 0, 30, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::checkPomodoroTransition, 0, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::checkContinuousUsage, 0, 5, TimeUnit.SECONDS);
        mathAlarm = new MathAlarm(this, (eventType, fields) -> {
            try {
                executor.execute(() -> saveSnapshot(eventType, null, null, fields));
            } catch (RuntimeException ignored) { }
        });
        // 服务重启（包括手机重启）时，如果闹钟还没答题关闭，会继续响。
        executor.scheduleWithFixedDelay(() -> mainHandler.post(mathAlarm::refresh), 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (AlarmScheduler.isRinging(this)) {
                Toast.makeText(this, "闹钟响铃中，答完题才能停止采集服务", Toast.LENGTH_LONG).show();
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_POMODORO_START.equals(intent.getAction())) {
            startPomodoro();
        } else if (intent != null && ACTION_POMODORO_STOP.equals(intent.getAction())) {
            stopPomodoro();
        } else if (intent != null && ACTION_TEST_USAGE_REMINDER.equals(intent.getAction())) {
            if (executor != null) executor.execute(() -> {
                saveSnapshot("continuous_use_alert_test");
                showContinuousUsageAlert(true);
            });
        } else if (intent != null && ACTION_USAGE_REMINDER_SETTINGS_CHANGED.equals(intent.getAction())) {
            if (executor != null) executor.execute(this::rescheduleContinuousUsageAlert);
        } else if (intent != null && ACTION_ALARM_FIRE.equals(intent.getAction())) {
            long alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, System.currentTimeMillis());
            boolean test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false);
            mainHandler.post(() -> mathAlarm.fire(alarmId, test));
        } else if (intent != null && ACTION_ALARM_STOP_NO_OVERLAY.equals(intent.getAction())) {
            mainHandler.post(mathAlarm::dismissWithoutOverlay);
        } else if (intent != null && ACTION_ALARM_SETTINGS_CHANGED.equals(intent.getAction())) {
            AlarmScheduler.schedule(this);
            DatabaseHelper.AlarmFields fields = new DatabaseHelper.AlarmFields();
            fields.alarmId = AlarmScheduler.nextTriggerMs(this);
            fields.detail = AlarmScheduler.formatTime(this) + (AlarmScheduler.isEnabled(this) ? " on" : " off");
            if (executor != null) executor.execute(() ->
                    saveSnapshot(MathAlarm.EVENT_SETTINGS, null, null, fields));
        } else if (intent != null && ACTION_USAGE_ALERT_RESPONSE.equals(intent.getAction())) {
            String response = intent.getStringExtra(EXTRA_USAGE_ALERT_RESPONSE);
            getSystemService(NotificationManager.class).cancel(USAGE_REMINDER_NOTIFICATION_ID);
            if (executor != null && response != null) {
                executor.execute(() -> handleUsageAlertResponse(response));
            }
        }
        if (!locationForegroundActive && canStartLocationForegroundService()) {
            startAsForeground();
            startLocationUpdatesIfAllowed();
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, CollectorService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stopPending).build())
                .build();

        locationForegroundActive = false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (canStartLocationForegroundService()) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    locationForegroundActive = true;
                }
                startForeground(NOTIFICATION_ID, notification, type);
            } else if (Build.VERSION.SDK_INT >= 29 && canStartLocationForegroundService()) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                locationForegroundActive = true;
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException locationRestriction) {
            locationForegroundActive = false;
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示本地设备状态采集正在运行");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        NotificationChannel pomodoro = new NotificationChannel(POMODORO_CHANNEL_ID,
                "番茄钟提醒", NotificationManager.IMPORTANCE_HIGH);
        pomodoro.setDescription("番茄钟阶段结束时显示静默提醒");
        pomodoro.setSound(null, null);
        pomodoro.enableVibration(false);
        manager.createNotificationChannel(pomodoro);

        NotificationChannel usageReminder = new NotificationChannel(USAGE_REMINDER_CHANNEL_ID,
                getString(R.string.usage_reminder_channel), NotificationManager.IMPORTANCE_HIGH);
        usageReminder.setDescription("连续使用手机达到设定时长时提醒休息");
        usageReminder.enableVibration(true);
        manager.createNotificationChannel(usageReminder);

        // 铃声和振动由 MathAlarm 自己播放，通知本身保持静音。
        NotificationChannel alarm = new NotificationChannel(MathAlarm.CHANNEL_ID,
                "数学闹钟", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("闹钟响铃中，解锁后答题关闭");
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(alarm);
    }

    private void startPomodoro() {
        SharedPreferences prefs = getSharedPreferences(PREFS_POMODORO, MODE_PRIVATE);
        int cycle = prefs.getInt(KEY_POMODORO_CYCLE, 0) + 1;
        prefs.edit()
                .putString(KEY_POMODORO_PHASE, PHASE_FOCUS)
                .putLong(KEY_POMODORO_END_MS, System.currentTimeMillis() + FOCUS_DURATION_MS)
                .putInt(KEY_POMODORO_CYCLE, cycle)
                .apply();
        if (executor != null) executor.execute(() -> saveSnapshot("pomodoro_focus_start"));
    }

    private void stopPomodoro() {
        SharedPreferences prefs = getSharedPreferences(PREFS_POMODORO, MODE_PRIVATE);
        String phase = prefs.getString(KEY_POMODORO_PHASE, PHASE_IDLE);
        if (!PHASE_IDLE.equals(phase)) {
            saveSnapshot("pomodoro_stopped");
        }
        prefs.edit()
                .putString(KEY_POMODORO_PHASE, PHASE_IDLE)
                .remove(KEY_POMODORO_END_MS)
                .apply();
    }

    private void checkPomodoroTransition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_POMODORO, MODE_PRIVATE);
        String phase = prefs.getString(KEY_POMODORO_PHASE, PHASE_IDLE);
        if (PHASE_IDLE.equals(phase)) return;
        long endMs = prefs.getLong(KEY_POMODORO_END_MS, 0);
        if (endMs <= 0 || System.currentTimeMillis() < endMs) return;

        if (PHASE_FOCUS.equals(phase)) {
            saveSnapshot("pomodoro_focus_end");
            long breakEndMs = endMs + BREAK_DURATION_MS;
            prefs.edit()
                    .putString(KEY_POMODORO_PHASE, PHASE_BREAK)
                    .putLong(KEY_POMODORO_END_MS, breakEndMs)
                    .apply();
            saveSnapshot("pomodoro_break_start");
            if (System.currentTimeMillis() >= breakEndMs) {
                saveSnapshot("pomodoro_break_end");
                prefs.edit()
                        .putString(KEY_POMODORO_PHASE, PHASE_IDLE)
                        .remove(KEY_POMODORO_END_MS)
                        .apply();
                showPomodoroAlert("番茄钟完成", "专注和休息均已结束，可以开始下一轮");
            } else {
                showPomodoroAlert("专注结束", "休息 5 分钟");
            }
        } else if (PHASE_BREAK.equals(phase)) {
            saveSnapshot("pomodoro_break_end");
            prefs.edit()
                    .putString(KEY_POMODORO_PHASE, PHASE_IDLE)
                    .remove(KEY_POMODORO_END_MS)
                    .apply();
            showPomodoroAlert("休息结束", "可以开始下一轮番茄钟");
        }
    }

    private void showPomodoroAlert(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, POMODORO_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(POMODORO_NOTIFICATION_ID, notification);
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent == null ? "state_change" : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    markContinuousUsageInactive();
                }
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    mainHandler.post(mathAlarm::refresh);
                }
                if (executor != null) executor.execute(() -> {
                    saveSnapshot(action);
                    if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        checkContinuousUsage(true);
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        checkContinuousUsage();
                    }
                });
            }
        };
        registerReceiver(stateReceiver, filter);
    }

    private void checkContinuousUsage() {
        checkContinuousUsage(false);
    }

    /** @param unlocked 刚解锁手机；深夜时段解锁会立即提醒。 */
    private void checkContinuousUsage(boolean unlocked) {
        long now = SystemClock.elapsedRealtime();
        long usageLimitMs = getUsageLimitMinutes(this) * 60_000L;
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        long start = prefs.getLong(KEY_USAGE_START_ELAPSED, 0);
        long nextAlert = prefs.getLong(KEY_USAGE_NEXT_ALERT_ELAPSED, 0);
        long lastCheck = prefs.getLong(KEY_USAGE_LAST_CHECK_ELAPSED, 0);
        long inactiveSince = prefs.getLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, 0);

        boolean invalidAfterReboot = start > now
                || lastCheck > now
                || inactiveSince > now;
        if (invalidAfterReboot) {
            resetContinuousUsageSession();
            start = 0;
            nextAlert = 0;
            lastCheck = 0;
            inactiveSince = 0;
        }

        boolean active = isDeviceActivelyUsed();
        if (!active) {
            // Keep short screen-off/lock periods in the same continuous-use session.
            // Only an uninterrupted 5-minute break resets the continuous-use timer.
            if (start <= 0 || nextAlert <= 0) return;
            if (inactiveSince <= 0) {
                prefs.edit().putLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, now).apply();
            } else if (now - inactiveSince >= CONTINUOUS_USAGE_RESET_GRACE_MS) {
                finishSessionWithBreak(prefs, inactiveSince);
            }
            return;
        }

        boolean resumedWithinGrace = false;
        if (inactiveSince > 0) {
            if (now - inactiveSince >= CONTINUOUS_USAGE_RESET_GRACE_MS) {
                finishSessionWithBreak(prefs, inactiveSince);
                start = 0;
                nextAlert = 0;
                lastCheck = 0;
            } else {
                // A break shorter than 5 minutes pauses the session. Move both the
                // session origin and alert deadline forward by the paused duration.
                long pausedDuration = now - inactiveSince;
                start += pausedDuration;
                nextAlert += pausedDuration;
                resumedWithinGrace = true;
                SharedPreferences.Editor editor = prefs.edit()
                        .putLong(KEY_USAGE_START_ELAPSED, start)
                        .putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, nextAlert)
                        .putLong(KEY_USAGE_LAST_CHECK_ELAPSED, now)
                        .remove(KEY_USAGE_INACTIVE_SINCE_ELAPSED);
                editor.apply();
            }
        }

        boolean trackingGapTooLarge = !resumedWithinGrace && lastCheck > 0
                && now - lastCheck > MAX_USAGE_CHECK_GAP_MS;
        if (start <= 0 || nextAlert <= 0 || invalidAfterReboot || trackingGapTooLarge) {
            if (trackingGapTooLarge) resetContinuousUsageSession();
            start = now;
            nextAlert = now + usageLimitMs;
            prefs.edit()
                    .putLong(KEY_USAGE_START_ELAPSED, start)
                    .putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, nextAlert)
                    .putLong(KEY_USAGE_LAST_CHECK_ELAPSED, now)
                    .remove(KEY_USAGE_INACTIVE_SINCE_ELAPSED)
                    .apply();
            if (!isNightNow(this)) return;  // 深夜新一轮使用也要立即提醒
        }

        prefs.edit().putLong(KEY_USAGE_LAST_CHECK_ELAPSED, now).apply();

        long restUntil = prefs.getLong(KEY_REST_UNTIL_ELAPSED, 0);
        if (restUntil > 0) {
            if (now >= restUntil) {
                saveSnapshot(EVENT_ALERT_RESPONSE, prefs.getInt(KEY_ALERT_LEVEL, 0), OUTCOME_REST_COMPLETED);
                resetContinuousUsageSession();
            } else {
                // 休息中途亮屏：继续显示休息倒计时。
                showContinuousUsageAlert(false);
            }
            return;
        }
        if (prefs.getBoolean(KEY_ALERT_PENDING, false)) {
            // 弹窗还没有做出选择（例如熄屏又亮屏），重新显示，不能靠熄屏绕过。
            showContinuousUsageAlert(false);
            return;
        }

        long deadline = nextAlert;
        boolean night = isNightNow(this);
        if (night) {
            // 深夜：每次解锁立即提醒；跨过 0 点仍在使用时，本夜还没提醒过也立即提醒。
            long nightStartElapsed = now - millisSinceNightStart();
            if (unlocked || prefs.getLong(KEY_LAST_ALERT_ELAPSED, 0) < nightStartElapsed) {
                deadline = now;
            }
        }
        if (now >= deadline) {
            fireContinuousUsageAlert(prefs, now, usageLimitMs, night);
        }
    }

    private void fireContinuousUsageAlert(SharedPreferences prefs, long now, long usageLimitMs,
                                          boolean night) {
        int level = prefs.getInt(KEY_ALERT_LEVEL, 0) + 1;
        // 没有悬浮窗权限时只能发通知；不能进入“等待选择”状态，否则会卡住后续提醒。
        boolean awaitChoice = Settings.canDrawOverlays(this);
        prefs.edit()
                .putInt(KEY_ALERT_LEVEL, level)
                .putBoolean(KEY_ALERT_PENDING, awaitChoice)
                .putLong(KEY_LOCK_UNTIL_ELAPSED, now + lockSeconds(level, night) * 1000L)
                .putLong(KEY_LAST_ALERT_ELAPSED, now)
                .putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, now + usageLimitMs)
                .apply();
        saveSnapshot(night ? EVENT_NIGHT_ALERT : EVENT_ALERT, level, null);
        showContinuousUsageAlert(false);
    }

    /** 第 1 次提醒可立即选择，第 2 次需等 60 秒，之后每次 180 秒；深夜至少 60 秒。 */
    static int lockSeconds(int level, boolean night) {
        int seconds = level <= 1 ? 0 : level == 2 ? 60 : 180;
        return night ? Math.max(60, seconds) : seconds;
    }

    static boolean isNightModeEnabled(Context context) {
        return context.getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE)
                .getBoolean(KEY_NIGHT_MODE_ENABLED, true);
    }

    static boolean isNightNow(Context context) {
        if (!isNightModeEnabled(context)) return false;
        return LocalTime.now(NIGHT_ZONE).getHour() < NIGHT_END_HOUR;
    }

    /** 深夜时段从台北时间当天 0 点开始，返回距今的毫秒数。 */
    private static long millisSinceNightStart() {
        ZonedDateTime now = ZonedDateTime.now(NIGHT_ZONE);
        return Duration.between(now.toLocalDate().atStartOfDay(NIGHT_ZONE), now).toMillis();
    }

    private void handleUsageAlertResponse(String response) {
        long now = SystemClock.elapsedRealtime();
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        if (prefs.getLong(KEY_USAGE_START_ELAPSED, 0) <= 0) {
            mainHandler.post(this::removeUsageReminderOverlay);
            return;
        }
        boolean night = isNightNow(this);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_ALERT_PENDING, false);
        switch (response) {
            case RESPONSE_REST:
                editor.putLong(KEY_REST_UNTIL_ELAPSED, now + REST_MS);
                break;
            case RESPONSE_SNOOZE:
                if (prefs.getBoolean(KEY_SNOOZE_USED, false)) {
                    mainHandler.post(this::tickUsageReminderOverlay);  // 恢复按钮，避免弹窗卡住
                    return;
                }
                editor.putBoolean(KEY_SNOOZE_USED, true)
                        .putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, now + (night ? NIGHT_SNOOZE_MS : SNOOZE_MS));
                break;
            case RESPONSE_EMERGENCY:
                editor.remove(KEY_REST_UNTIL_ELAPSED)
                        .putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, now + EMERGENCY_MS);
                break;
            case RESPONSE_WORK:
                editor.putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, now + getUsageLimitMinutes(this) * 60_000L);
                break;
            default:
                mainHandler.post(this::tickUsageReminderOverlay);
                return;
        }
        editor.apply();
        saveSnapshot(EVENT_ALERT_RESPONSE, prefs.getInt(KEY_ALERT_LEVEL, 0), response);
        boolean rest = RESPONSE_REST.equals(response);
        mainHandler.post(() -> {
            removeUsageReminderOverlay();
            if (rest) showUsageReminderOverlay(false, null);
        });
    }

    private void finishSessionWithBreak(SharedPreferences prefs, long inactiveSince) {
        long lastAlert = prefs.getLong(KEY_LAST_ALERT_ELAPSED, 0);
        if (lastAlert > 0 && inactiveSince - lastAlert <= BREAK_ATTRIBUTION_MS) {
            saveSnapshot(EVENT_ALERT_RESPONSE, prefs.getInt(KEY_ALERT_LEVEL, 0), OUTCOME_BREAK);
        }
        resetContinuousUsageSession();
    }

    static int getUsageLimitMinutes(Context context) {
        int minutes = context.getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE)
                .getInt(KEY_USAGE_LIMIT_MINUTES, DEFAULT_USAGE_LIMIT_MINUTES);
        return Math.max(MIN_USAGE_LIMIT_MINUTES, Math.min(MAX_USAGE_LIMIT_MINUTES, minutes));
    }

    static long currentContinuousUsageMs(Context context) {
        long now = SystemClock.elapsedRealtime();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        long start = prefs.getLong(KEY_USAGE_START_ELAPSED, 0);
        long inactiveSince = prefs.getLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, 0);
        if (start <= 0 || start > now || inactiveSince > now) return 0;
        if (inactiveSince > 0 && now - inactiveSince >= CONTINUOUS_USAGE_RESET_GRACE_MS) return 0;
        long effectiveNow = inactiveSince > 0 ? inactiveSince : now;
        return Math.max(0, effectiveNow - start);
    }

    private void rescheduleContinuousUsageAlert() {
        long now = SystemClock.elapsedRealtime();
        long usageLimitMs = getUsageLimitMinutes(this) * 60_000L;
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        long start = prefs.getLong(KEY_USAGE_START_ELAPSED, 0);
        if (start <= 0 || start > now) {
            checkContinuousUsage();
            return;
        }
        prefs.edit().putLong(KEY_USAGE_NEXT_ALERT_ELAPSED, start + usageLimitMs).apply();
        checkContinuousUsage();
    }

    private void markContinuousUsageInactive() {
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        if (prefs.getLong(KEY_USAGE_START_ELAPSED, 0) > 0
                && prefs.getLong(KEY_USAGE_NEXT_ALERT_ELAPSED, 0) > 0
                && prefs.getLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, 0) <= 0) {
            prefs.edit()
                    .putLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, SystemClock.elapsedRealtime())
                    .apply();
        }
        if (usageReminderView != null) mainHandler.post(this::removeUsageReminderOverlay);
    }

    private boolean isDeviceActivelyUsed() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!power.isInteractive()) return false;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    private void resetContinuousUsageSession() {
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        String[] sessionKeys = {
                KEY_USAGE_START_ELAPSED, KEY_USAGE_NEXT_ALERT_ELAPSED, KEY_USAGE_LAST_CHECK_ELAPSED,
                KEY_USAGE_INACTIVE_SINCE_ELAPSED, KEY_ALERT_LEVEL, KEY_ALERT_PENDING,
                KEY_LOCK_UNTIL_ELAPSED, KEY_REST_UNTIL_ELAPSED, KEY_SNOOZE_USED,
                KEY_LAST_ALERT_ELAPSED, KEY_NIGHT_ANCHOR_ELAPSED_LEGACY,
        };
        SharedPreferences.Editor editor = null;
        for (String key : sessionKeys) {
            if (!prefs.contains(key)) continue;
            if (editor == null) editor = prefs.edit();
            editor.remove(key);
        }
        if (editor != null) editor.apply();
        if (usageReminderView != null) mainHandler.post(this::removeUsageReminderOverlay);
    }

    private void showContinuousUsageAlert(boolean test) {
        if (!test && usageReminderView != null) return;
        if (!Settings.canDrawOverlays(this)) {
            showUsageReminderNotification(test);
            return;
        }
        String appLabel = foregroundAppLabel();
        mainHandler.post(() -> showUsageReminderOverlay(test, appLabel));
    }

    private String foregroundAppLabel() {
        String packageName = currentForegroundPackage();
        if (packageName == null || packageName.equals(getPackageName())) return null;
        try {
            PackageManager manager = getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return packageName;
        }
    }

    /** 主线程调用。根据当前状态显示“选择”弹窗或“休息中”倒计时。 */
    private void showUsageReminderOverlay(boolean test, String appLabel) {
        if (usageReminderView != null) {
            if (!test) return;
            removeUsageReminderOverlay();
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime();
        long restUntil = test ? 0 : prefs.getLong(KEY_REST_UNTIL_ELAPSED, 0);
        boolean resting = restUntil > now;
        // 状态可能已在其他线程改变（例如已经熄屏满 5 分钟被清零）。
        if (!test && !resting && !prefs.getBoolean(KEY_ALERT_PENDING, false)) return;

        boolean night = isNightNow(this);
        int level = test ? 1 : Math.max(1, prefs.getInt(KEY_ALERT_LEVEL, 1));
        getSystemService(NotificationManager.class).cancel(USAGE_REMINDER_NOTIFICATION_ID);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(48), dp(28), dp(48));
        root.setBackgroundColor(night ? Color.rgb(24, 26, 33) : Color.rgb(250, 250, 250));
        int titleColor = night ? Color.rgb(235, 235, 240) : Color.rgb(35, 35, 35);
        int bodyColor = night ? Color.rgb(190, 192, 200) : Color.rgb(70, 70, 70);

        TextView title = new TextView(this);
        title.setText(test ? "提醒测试" : resting ? "休息中" : night ? "该睡了" : "该休息一下了");
        title.setTextColor(titleColor);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, overlayFullWidth());

        TextView message = new TextView(this);
        message.setText(resting ? "可以直接锁屏。倒计时结束，或熄屏满 5 分钟，连续使用计时都会清零。"
                : usageReminderMessage(test, night, level, appLabel));
        message.setTextColor(bodyColor);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, dp(20));
        root.addView(message, overlayFullWidth());

        TextView countdown = new TextView(this);
        countdown.setTextColor(titleColor);
        countdown.setTextSize(resting ? 48 : 18);
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, 0, 0, dp(20));
        root.addView(countdown, overlayFullWidth());

        overlayChoiceButtons.clear();
        if (!resting) {
            root.addView(choiceButton("休息 5 分钟", RESPONSE_REST, test), overlayFullWidth());
            if (test || !prefs.getBoolean(KEY_SNOOZE_USED, false)) {
                String snoozeLabel = "再用 " + ((night ? NIGHT_SNOOZE_MS : SNOOZE_MS) / 60_000L)
                        + " 分钟（本轮仅一次）";
                root.addView(choiceButton(snoozeLabel, RESPONSE_SNOOZE, test), overlayFullWidth());
            }
            root.addView(choiceButton("在做正事（会被记录）", RESPONSE_WORK, test), overlayFullWidth());
        }

        Button emergency = new Button(this);
        emergency.setText("紧急使用（长按，会被记录）");
        emergency.setTextSize(13);
        emergency.setAllCaps(false);
        emergency.setAlpha(0.7f);
        emergency.setOnClickListener(v -> Toast.makeText(this, "请长按", Toast.LENGTH_SHORT).show());
        emergency.setOnLongClickListener(v -> {
            if (overlayTest) {
                removeUsageReminderOverlay();
            } else if (executor != null) {
                v.setEnabled(false);
                executor.execute(() -> handleUsageAlertResponse(RESPONSE_EMERGENCY));
            }
            return true;
        });
        LinearLayout.LayoutParams emergencyParams = overlayFullWidth();
        emergencyParams.topMargin = dp(28);
        root.addView(emergency, emergencyParams);

        overlayTest = test;
        overlayResting = resting;
        overlayRestUntil = restUntil;
        overlayLockUntil = test ? 0 : prefs.getLong(KEY_LOCK_UNTIL_ELAPSED, 0);
        overlayCountdownView = countdown;
        overlayEmergencyButton = emergency;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        coverSystemBars(params);
        try {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.addView(root, params);
            usageReminderView = root;
            tickUsageReminderOverlay();
        } catch (RuntimeException error) {
            usageReminderView = null;
            showUsageReminderNotification(test);
        }
    }

    private String usageReminderMessage(boolean test, boolean night, int level, String appLabel) {
        long usedMinutes = Math.max(1, currentContinuousUsageMs(this) / 60_000L);
        StringBuilder text = new StringBuilder();
        if (test) {
            text.append("这是连续使用 ").append(getUsageLimitMinutes(this))
                    .append(" 分钟后会出现的提醒。测试时点任意按钮都只会关闭弹窗。");
            return text.toString();
        }
        if (night) {
            LocalTime time = LocalTime.now(NIGHT_ZONE);
            text.append(String.format(Locale.getDefault(), "现在是 %02d:%02d。", time.getHour(), time.getMinute()))
                    .append("\n0:00–5:00 每次解锁都会提醒，放下手机去睡吧。");
            if (appLabel != null) text.append("\n\n正在用：").append(appLabel);
        } else {
            text.append("你已经连续使用手机约 ").append(usedMinutes).append(" 分钟");
            if (appLabel != null) text.append("\n正在用：").append(appLabel);
            text.append("\n\n放下手机、看看远处，离开几分钟。");
        }
        if (level > 1) text.append("\n\n这是本轮第 ").append(level).append(" 次提醒。");
        return text.toString();
    }

    private Button choiceButton(String label, String response, boolean test) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            if (test) {
                removeUsageReminderOverlay();
                return;
            }
            for (Button choice : overlayChoiceButtons) choice.setEnabled(false);
            if (executor != null) executor.execute(() -> handleUsageAlertResponse(response));
        });
        overlayChoiceButtons.add(button);
        return button;
    }

    /** 主线程每秒刷新：选择弹窗的等待倒计时，或休息倒计时。 */
    private void tickUsageReminderOverlay() {
        mainHandler.removeCallbacks(overlayTicker);
        if (usageReminderView == null || overlayCountdownView == null) return;
        long now = SystemClock.elapsedRealtime();
        if (overlayResting) {
            long remaining = Math.max(0, overlayRestUntil - now);
            long seconds = (remaining + 999) / 1000;
            overlayCountdownView.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
            if (remaining <= 0) {
                // 由计时逻辑记录 rest_completed 并清零；它会移除弹窗。
                if (executor != null) executor.execute(this::checkContinuousUsage);
                return;
            }
        } else {
            long remaining = Math.max(0, overlayLockUntil - now);
            boolean locked = remaining > 0;
            for (Button choice : overlayChoiceButtons) choice.setEnabled(!locked);
            overlayCountdownView.setVisibility(locked ? View.VISIBLE : View.GONE);
            overlayEmergencyButton.setVisibility(locked ? View.VISIBLE : View.GONE);
            if (!locked) return;
            overlayCountdownView.setText("先放下手机 " + ((remaining + 999) / 1000) + " 秒，之后才能选择");
        }
        mainHandler.postDelayed(overlayTicker, 1000);
    }

    /** 默认悬浮窗会避开状态栏和导航栏，留出的缝隙可以点到下层应用；让它铺满整个屏幕。 */
    static void coverSystemBars(WindowManager.LayoutParams params) {
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
    }

    private LinearLayout.LayoutParams overlayFullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void removeUsageReminderOverlay() {
        mainHandler.removeCallbacks(overlayTicker);
        View view = usageReminderView;
        usageReminderView = null;
        overlayCountdownView = null;
        overlayEmergencyButton = null;
        overlayChoiceButtons.clear();
        if (view == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ignored) { }
    }

    private void showUsageReminderNotification(boolean test) {
        int limitMinutes = getUsageLimitMinutes(this);
        long usedMinutes = Math.max(1, currentContinuousUsageMs(this) / 60_000L);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 3, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, USAGE_REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker)
                .setContentTitle(test ? "连续使用提醒测试"
                        : (isNightNow(this) ? "该睡了 · " : "") + "已连续使用手机约 " + usedMinutes + " 分钟")
                .setContentText(Settings.canDrawOverlays(this)
                        ? "已达到 " + limitMinutes + " 分钟提醒阈值，建议休息"
                        : "授予悬浮窗权限后可显示带选择的醒目弹窗")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_MAX);
        if (!test) {
            builder.addAction(responseAction("休息 5 分钟", RESPONSE_REST, 10))
                    .addAction(responseAction("在做正事", RESPONSE_WORK, 11));
        }
        getSystemService(NotificationManager.class).notify(USAGE_REMINDER_NOTIFICATION_ID, builder.build());
    }

    private Notification.Action responseAction(String label, String response, int requestCode) {
        Intent intent = new Intent(this, CollectorService.class)
                .setAction(ACTION_USAGE_ALERT_RESPONSE)
                .putExtra(EXTRA_USAGE_ALERT_RESPONSE, response);
        PendingIntent pending = PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, label, pending).build();
    }

    private void startLocationUpdatesIfAllowed() {
        if (!hasLocationPermission() || !locationForegroundActive || locationListener != null) {
            if (!locationForegroundActive && latestLocation == null) locationLabel = "后台定位未授权";
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = location -> updateLocation(location);
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        5 * 60_000L, 25f, locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        5 * 60_000L, 25f, locationListener, Looper.getMainLooper());
            }
            Location last = bestLastLocation();
            if (last != null) updateLocation(last);
        } catch (SecurityException ignored) {
            locationLabel = "权限不足";
        }
    }

    private Location bestLastLocation() {
        if (!hasLocationPermission() || locationManager == null) return null;
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    private String classifyLocation(Location current) {
        SharedPreferences prefs = getSharedPreferences("places", MODE_PRIVATE);
        boolean homeSet = prefs.getBoolean("home_set", false);
        boolean schoolSet = prefs.getBoolean("school_set", false);
        boolean canteenSet = prefs.getBoolean("canteen_set", false);
        boolean librarySet = prefs.getBoolean("library_set", false);
        if (!homeSet && !schoolSet && !canteenSet && !librarySet) return "未配置";
        // 食堂和图书馆通常位于学校范围内，因此用更小半径并优先匹配。
        if (isNearPlace(current, prefs, "canteen", 120f)) return "在食堂";
        if (isNearPlace(current, prefs, "library", 120f)) return "在图书馆";
        if (isNearPlace(current, prefs, "home", 250f)) return "在家";
        if (isNearPlace(current, prefs, "school", 250f)) return "在校";
        return "在外";
    }

    private void updateLocation(Location location) {
        latestLocation = new Location(location);
        locationLabel = classifyLocation(location);
    }

    private boolean isNearPlace(Location current, SharedPreferences prefs, String key, float defaultRadiusM) {
        if (!prefs.getBoolean(key + "_set", false)) return false;
        float[] distance = new float[1];
        Location.distanceBetween(current.getLatitude(), current.getLongitude(),
                Double.longBitsToDouble(prefs.getLong(key + "_lat", 0)),
                Double.longBitsToDouble(prefs.getLong(key + "_lon", 0)), distance);
        return distance[0] <= prefs.getFloat(key + "_radius_m", defaultRadiusM);
    }

    private void saveSnapshot(String eventType) {
        saveSnapshot(eventType, null, null);
    }

    private void saveSnapshot(String eventType, Integer alertLevel, String alertResponse) {
        saveSnapshot(eventType, alertLevel, alertResponse, null);
    }

    private void saveSnapshot(String eventType, Integer alertLevel, String alertResponse,
                              DatabaseHelper.AlarmFields alarm) {
        try {
            Location location = latestLocation == null ? null : new Location(latestLocation);
            PomodoroSnapshot pomodoro = currentPomodoroSnapshot();
            ContinuousUsageSnapshot continuousUsage = currentContinuousUsageSnapshot();
            database.insertSnapshot(eventType, currentForegroundPackage(), isScreenOn(),
                    isCharging(), currentWifiSsid(), locationLabel,
                    location == null ? null : location.getLatitude(),
                    location == null ? null : location.getLongitude(),
                    location == null || !location.hasAccuracy() ? null : location.getAccuracy(),
                    location == null ? null : location.getProvider(),
                    pomodoro.phase, pomodoro.remainingSec, pomodoro.cycle,
                    continuousUsage.elapsedSec, continuousUsage.limitMinutes,
                    continuousUsage.state, alertLevel, alertResponse, alarm,
                    latestStepCount < 0 ? null : latestStepCount);
        } catch (RuntimeException ignored) {
            // The next scheduled sample retries. The service must remain alive.
        }
    }

    private PomodoroSnapshot currentPomodoroSnapshot() {
        SharedPreferences prefs = getSharedPreferences(PREFS_POMODORO, MODE_PRIVATE);
        String phase = prefs.getString(KEY_POMODORO_PHASE, PHASE_IDLE);
        int cycle = prefs.getInt(KEY_POMODORO_CYCLE, 0);
        if (PHASE_IDLE.equals(phase)) return new PomodoroSnapshot(PHASE_IDLE, null, cycle == 0 ? null : cycle);
        long endMs = prefs.getLong(KEY_POMODORO_END_MS, 0);
        int remaining = (int) Math.max(0, (endMs - System.currentTimeMillis() + 999) / 1000);
        return new PomodoroSnapshot(phase, remaining, cycle);
    }

    private static final class PomodoroSnapshot {
        final String phase;
        final Integer remainingSec;
        final Integer cycle;

        PomodoroSnapshot(String phase, Integer remainingSec, Integer cycle) {
            this.phase = phase;
            this.remainingSec = remainingSec;
            this.cycle = cycle;
        }
    }

    private ContinuousUsageSnapshot currentContinuousUsageSnapshot() {
        long now = SystemClock.elapsedRealtime();
        int limitMinutes = getUsageLimitMinutes(this);
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_REMINDER, MODE_PRIVATE);
        long start = prefs.getLong(KEY_USAGE_START_ELAPSED, 0);
        long inactiveSince = prefs.getLong(KEY_USAGE_INACTIVE_SINCE_ELAPSED, 0);
        if (start <= 0 || start > now || inactiveSince > now
                || (inactiveSince > 0 && now - inactiveSince >= CONTINUOUS_USAGE_RESET_GRACE_MS)) {
            return new ContinuousUsageSnapshot(0L, limitMinutes, "idle");
        }
        String state = inactiveSince > 0 ? "inactive_grace" : "active";
        long effectiveNow = inactiveSince > 0 ? inactiveSince : now;
        return new ContinuousUsageSnapshot(
                Math.max(0, effectiveNow - start) / 1000L, limitMinutes, state);
    }

    private static final class ContinuousUsageSnapshot {
        final long elapsedSec;
        final int limitMinutes;
        final String state;

        ContinuousUsageSnapshot(long elapsedSec, int limitMinutes, String state) {
            this.elapsedSec = elapsedSec;
            this.limitMinutes = limitMinutes;
            this.state = state;
        }
    }

    private String currentForegroundPackage() {
        if (!hasUsageAccess()) return "权限不足";
        UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(end - 30 * 60_000L, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String latestPackage = null;
        long latestTime = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    && event.getTimeStamp() >= latestTime) {
                latestTime = event.getTimeStamp();
                latestPackage = event.getPackageName();
            }
        }
        return latestPackage;
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isScreenOn() {
        return ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
    }

    private boolean isCharging() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    @SuppressWarnings("deprecation")
    private String currentWifiSsid() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = manager.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
            return ssid.replaceAll("^\\\"|\\\"$", "");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 系统计步传感器由低功耗协处理器计数，手机睡眠时也不会唤醒 CPU。
     * 值是开机以来的累计步数，重启后归零；分析时用相邻记录的差值。
     */
    private void startStepCounterIfAllowed() {
        if (stepListener != null) return;
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return;
        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor counter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (counter == null) return;
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                latestStepCount = (long) event.values[0];
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        // 允许传感器攒 10 秒再批量上报，减少唤醒；累计值不会因批量而丢步。
        if (sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL,
                10_000_000, mainHandler)) {
            stepListener = listener;
        }
    }

    private void stopStepCounter() {
        if (stepListener == null) return;
        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) sensors.unregisterListener(stepListener);
        stepListener = null;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canStartLocationForegroundService() {
        return hasLocationPermission() && (hasBackgroundLocationPermission() || isAppInForeground());
    }

    private boolean isAppInForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        if (stateReceiver != null) unregisterReceiver(stateReceiver);
        if (locationManager != null && locationListener != null) locationManager.removeUpdates(locationListener);
        stopStepCounter();
        resetContinuousUsageSession();
        removeUsageReminderOverlay();
        if (mathAlarm != null) mathAlarm.release();
        if (database != null) database.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
