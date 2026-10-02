package com.wind.timetracker;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;
    private TextView statusView;
    private TextView pomodoroView;
    private TextView usageReminderStatusView;
    private EditText usageLimitInput;
    private Button testUsageReminderButton;
    private Button nightModeButton;
    private TextView alertStatsView;
    private TextView eventsView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable pomodoroTicker = new Runnable() {
        @Override public void run() {
            refreshPomodoroStatus();
            refreshUsageReminderStatus();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private enum ExportPeriod { ALL, TODAY, WEEK, MONTH }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildInterface());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshNightModeButton();
        uiHandler.removeCallbacks(pomodoroTicker);
        uiHandler.post(pomodoroTicker);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(pomodoroTicker);
        super.onPause();
    }

    private View buildInterface() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("本地时间采集");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(30, 80, 35));
        root.addView(title, fullWidth());

        TextView privacy = new TextView(this);
        privacy.setText("数据默认只保存在手机应用私有 SQLite。事件会保存原始经纬度、系统报告的精度和定位来源，便于核对“在家 / 在校 / 在食堂 / 在图书馆 / 在外”判断。");
        privacy.setTextSize(15);
        privacy.setPadding(0, dp(8), 0, dp(12));
        root.addView(privacy, fullWidth());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setBackgroundColor(Color.rgb(238, 245, 238));
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusView, fullWidth());

        root.addView(button("1. 授予通知与定位权限", v -> requestRuntimePermissions()), fullWidth());
        root.addView(button("1b. 授予后台定位（选择“始终允许”）", v -> openBackgroundLocationSettings()), fullWidth());
        root.addView(button("2. 打开“使用情况访问”设置", v -> openUsageAccess()), fullWidth());
        root.addView(button("3. 启动采集服务", v -> startCollector()), fullWidth());
        root.addView(button("停止采集服务", v -> stopCollector()), fullWidth());

        TextView usageReminderTitle = sectionTitle("连续使用提醒");
        root.addView(usageReminderTitle, fullWidth());
        TextView usageReminderDescription = new TextView(this);
        usageReminderDescription.setText("连续使用达到设定时长时弹出提醒，需要选择：休息 5 分钟、再用 10 分钟（每轮一次）或在做正事，选择会被记录。" +
                "同一轮里继续使用，第 2 次提醒要先等 60 秒才能选择，之后每次等 180 秒。熄屏或锁屏不足 5 分钟时暂停计时，满 5 分钟则清零。");
        usageReminderDescription.setTextSize(15);
        usageReminderDescription.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(usageReminderDescription, fullWidth());

        usageReminderStatusView = new TextView(this);
        usageReminderStatusView.setTextSize(18);
        usageReminderStatusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        usageReminderStatusView.setBackgroundColor(Color.rgb(238, 245, 238));
        root.addView(usageReminderStatusView, fullWidth());

        alertStatsView = new TextView(this);
        alertStatsView.setTextSize(15);
        alertStatsView.setPadding(dp(12), dp(10), dp(12), dp(10));
        alertStatsView.setBackgroundColor(Color.rgb(245, 240, 232));
        root.addView(alertStatsView, fullWidth());

        TextView usageLimitLabel = new TextView(this);
        usageLimitLabel.setText(R.string.usage_limit_label);
        usageLimitLabel.setTextSize(15);
        usageLimitLabel.setPadding(dp(12), dp(8), dp(12), 0);
        root.addView(usageLimitLabel, fullWidth());

        usageLimitInput = new EditText(this);
        usageLimitInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        usageLimitInput.setSingleLine(true);
        usageLimitInput.setSelectAllOnFocus(true);
        usageLimitInput.setText(String.valueOf(CollectorService.getUsageLimitMinutes(this)));
        root.addView(usageLimitInput, fullWidth());
        root.addView(button("保存提醒分钟数", v -> saveUsageReminderMinutes()), fullWidth());
        nightModeButton = button("", v -> toggleNightMode());
        root.addView(nightModeButton, fullWidth());
        root.addView(button("授予“显示在其他应用上层”权限", v -> openOverlaySettings()), fullWidth());
        testUsageReminderButton = button("测试提醒弹窗", v -> testUsageReminder());
        root.addView(testUsageReminderButton, fullWidth());

        TextView pomodoroTitle = sectionTitle("番茄钟");
        root.addView(pomodoroTitle, fullWidth());
        pomodoroView = new TextView(this);
        pomodoroView.setTextSize(18);
        pomodoroView.setPadding(dp(12), dp(10), dp(12), dp(10));
        pomodoroView.setBackgroundColor(Color.rgb(248, 244, 235));
        root.addView(pomodoroView, fullWidth());
        root.addView(button("开始 25 + 5 分钟番茄钟", v -> startPomodoro()), fullWidth());
        root.addView(button("停止番茄钟", v -> stopPomodoro()), fullWidth());

        TextView placeTitle = sectionTitle("位置标签（可选）");
        root.addView(placeTitle, fullWidth());
        root.addView(button("将当前位置设为“家”", v -> saveCurrentPlace("home", "家")), fullWidth());
        root.addView(button("将当前位置设为“学校”", v -> saveCurrentPlace("school", "学校")), fullWidth());
        root.addView(button("将当前位置设为“食堂”", v -> saveCurrentPlace("canteen", "食堂")), fullWidth());
        root.addView(button("将当前位置设为“图书馆”", v -> saveCurrentPlace("library", "图书馆")), fullWidth());
        root.addView(button("打开应用电池/后台设置", v -> openAppSettings()), fullWidth());

        TextView dataTitle = sectionTitle("本机数据");
        root.addView(dataTitle, fullWidth());
        root.addView(button("刷新最近记录", v -> refreshStatus()), fullWidth());
        root.addView(button("导出今天 JSONL", v -> exportData(ExportPeriod.TODAY)), fullWidth());
        root.addView(button("导出本周 JSONL", v -> exportData(ExportPeriod.WEEK)), fullWidth());
        root.addView(button("导出本月 JSONL", v -> exportData(ExportPeriod.MONTH)), fullWidth());
        root.addView(button("导出全部 JSONL", v -> exportData(ExportPeriod.ALL)), fullWidth());

        eventsView = new TextView(this);
        eventsView.setTextSize(12);
        eventsView.setTextIsSelectable(true);
        eventsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        eventsView.setPadding(0, dp(10), 0, dp(30));
        root.addView(eventsView, fullWidth());
        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(19);
        view.setTextColor(Color.DKGRAY);
        view.setPadding(0, dp(20), 0, dp(6));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (permissions.isEmpty()) toast("通知与定位权限已经授予");
        else requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void openUsageAccess() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); }
        catch (RuntimeException error) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
    }

    private void openBackgroundLocationSettings() {
        toast("请进入“权限 → 位置信息”，选择“始终允许”");
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("醒目提醒悬浮窗权限已经授予");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void testUsageReminder() {
        Intent intent = new Intent(this, CollectorService.class)
                .setAction(CollectorService.ACTION_TEST_USAGE_REMINDER);
        startForegroundService(intent);
        toast(Settings.canDrawOverlays(this)
                ? "正在显示 " + CollectorService.getUsageLimitMinutes(this) + " 分钟提醒测试"
                : "未授予悬浮窗权限，将使用高优先级通知兜底");
    }

    private void saveUsageReminderMinutes() {
        String value = usageLimitInput.getText().toString().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            toast("请输入 1–240 之间的整数分钟数");
            return;
        }
        if (minutes < CollectorService.MIN_USAGE_LIMIT_MINUTES
                || minutes > CollectorService.MAX_USAGE_LIMIT_MINUTES) {
            toast("提醒时间必须在 1–240 分钟之间");
            return;
        }
        getSharedPreferences(CollectorService.PREFS_USAGE_REMINDER, MODE_PRIVATE)
                .edit().putInt(CollectorService.KEY_USAGE_LIMIT_MINUTES, minutes).apply();
        Intent intent = new Intent(this, CollectorService.class)
                .setAction(CollectorService.ACTION_USAGE_REMINDER_SETTINGS_CHANGED);
        startForegroundService(intent);
        toast("已设为连续使用 " + minutes + " 分钟提醒");
        refreshUsageReminderStatus();
        refreshStatus();
    }

    private void toggleNightMode() {
        boolean enabled = !CollectorService.isNightModeEnabled(this);
        getSharedPreferences(CollectorService.PREFS_USAGE_REMINDER, MODE_PRIVATE)
                .edit().putBoolean(CollectorService.KEY_NIGHT_MODE_ENABLED, enabled).apply();
        toast(enabled ? "深夜模式已开启" : "深夜模式已关闭");
        refreshNightModeButton();
    }

    private void refreshNightModeButton() {
        if (nightModeButton == null) return;
        nightModeButton.setText(CollectorService.isNightModeEnabled(this)
                ? "深夜模式：已开启（00:30–06:00 每 15 分钟提醒，点击关闭）"
                : "深夜模式：已关闭（点击开启）");
    }

    private void refreshAlertStats(DatabaseHelper database) {
        ZonedDateTime today = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneId.systemDefault());
        DatabaseHelper.AlertStats day = database.alertStats(today.toInstant().toEpochMilli());
        DatabaseHelper.AlertStats week = database.alertStats(today.minusDays(6).toInstant().toEpochMilli());
        alertStatsView.setText("今天：" + describeAlertStats(day) + "\n近 7 天：" + describeAlertStats(week));
    }

    private String describeAlertStats(DatabaseHelper.AlertStats stats) {
        if (stats.alerts == 0) return "没有触发提醒";
        return "提醒 " + stats.alerts + " 次" + (stats.nightAlerts > 0 ? "（深夜 " + stats.nightAlerts + "）" : "") +
                "，真正休息 " + stats.breaks + " 次" +
                "\n  选择：休息 " + stats.rest + " · 再用 " + stats.snooze +
                " · 正事 " + stats.work + " · 紧急 " + stats.emergency;
    }

    private void startCollector() {
        Intent intent = new Intent(this, CollectorService.class);
        startForegroundService(intent);
        toast("采集服务已启动");
        statusView.postDelayed(this::refreshStatus, 1200);
    }

    private void stopCollector() {
        stopService(new Intent(this, CollectorService.class));
        toast("采集服务已停止");
    }

    private void startPomodoro() {
        Intent intent = new Intent(this, CollectorService.class).setAction(CollectorService.ACTION_POMODORO_START);
        startForegroundService(intent);
        toast("番茄钟开始：专注 25 分钟");
        refreshPomodoroStatus();
    }

    private void stopPomodoro() {
        Intent intent = new Intent(this, CollectorService.class).setAction(CollectorService.ACTION_POMODORO_STOP);
        startService(intent);
        toast("番茄钟已停止");
        pomodoroView.postDelayed(this::refreshPomodoroStatus, 200);
    }

    private void refreshPomodoroStatus() {
        if (pomodoroView == null) return;
        SharedPreferences prefs = getSharedPreferences(CollectorService.PREFS_POMODORO, MODE_PRIVATE);
        String phase = prefs.getString(CollectorService.KEY_POMODORO_PHASE, CollectorService.PHASE_IDLE);
        int cycle = prefs.getInt(CollectorService.KEY_POMODORO_CYCLE, 0);
        if (CollectorService.PHASE_IDLE.equals(phase)) {
            pomodoroView.setText(cycle == 0 ? "未开始（默认 25 分钟专注 + 5 分钟休息）" : "空闲 · 已进行 " + cycle + " 轮");
            return;
        }
        long endMs = prefs.getLong(CollectorService.KEY_POMODORO_END_MS, 0);
        long remainingSec = Math.max(0, (endMs - System.currentTimeMillis() + 999) / 1000);
        long minutes = remainingSec / 60;
        long seconds = remainingSec % 60;
        String label = CollectorService.PHASE_FOCUS.equals(phase) ? "专注" : "休息";
        pomodoroView.setText(String.format(java.util.Locale.getDefault(),
                "%s · 第 %d 轮 · %02d:%02d", label, cycle, minutes, seconds));
    }

    private void refreshUsageReminderStatus() {
        if (usageReminderStatusView == null) return;
        int limitMinutes = CollectorService.getUsageLimitMinutes(this);
        long elapsedSeconds = CollectorService.currentContinuousUsageMs(this) / 1000L;
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds % 3600) / 60;
        long seconds = elapsedSeconds % 60;
        String elapsed = hours > 0
                ? String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds);
        usageReminderStatusView.setText(getString(
                R.string.continuous_usage_status, elapsed, limitMinutes));
        if (testUsageReminderButton != null) {
            testUsageReminderButton.setText(getString(
                    R.string.test_usage_reminder_button, limitMinutes));
        }
    }

    private void saveCurrentPlace(String key, String displayName) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("请先授予精确定位权限");
            requestRuntimePermissions();
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        toast("正在获取当前位置…");
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Executor main = getMainExecutor();
                manager.getCurrentLocation(provider, new CancellationSignal(), main,
                        location -> persistPlace(key, displayName, location));
            } else {
                LocationListener listener = location -> persistPlace(key, displayName, location);
                manager.requestSingleUpdate(provider, listener, getMainLooper());
            }
        } catch (RuntimeException error) {
            toast("无法获取位置：" + error.getMessage());
        }
    }

    private void persistPlace(String key, String displayName, Location location) {
        if (location == null) {
            toast("暂时无法取得位置，请确认系统定位已开启");
            return;
        }
        getSharedPreferences("places", MODE_PRIVATE).edit()
                .putBoolean(key + "_set", true)
                .putLong(key + "_lat", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong(key + "_lon", Double.doubleToRawLongBits(location.getLongitude()))
                .apply();
        toast("已设置“" + displayName + "”（后续事件会同时保存原始定位）");
        refreshStatus();
    }

    private void exportData(ExportPeriod period) {
        long nowMs = System.currentTimeMillis();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long startMs;
        String rangeLabel;
        switch (period) {
            case TODAY:
                startMs = now.toLocalDate().atStartOfDay(now.getZone()).toInstant().toEpochMilli();
                rangeLabel = "today";
                break;
            case WEEK:
                startMs = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toLocalDate().atStartOfDay(now.getZone()).toInstant().toEpochMilli();
                rangeLabel = "week";
                break;
            case MONTH:
                startMs = now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.getZone())
                        .toInstant().toEpochMilli();
                rangeLabel = "month";
                break;
            default:
                startMs = 0;
                rangeLabel = "all";
        }
        new Thread(() -> {
            try (DatabaseHelper database = new DatabaseHelper(this)) {
                String path = database.exportJsonLines(this, startMs, nowMs + 1, rangeLabel);
                runOnUiThread(() -> toast("已导出到 " + path));
            } catch (Exception error) {
                runOnUiThread(() -> toast("导出失败：" + error.getMessage()));
            }
        }, "time-tracker-export").start();
    }

    private void refreshStatus() {
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean backgroundLocation = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);
        int usageLimitMinutes = CollectorService.getUsageLimitMinutes(this);
        SharedPreferences places = getSharedPreferences("places", MODE_PRIVATE);
        try (DatabaseHelper database = new DatabaseHelper(this)) {
            statusView.setText("使用情况访问：" + yesNo(hasUsageAccess()) +
                    "\n定位权限：" + yesNo(location) +
                    "\n后台定位：" + yesNo(backgroundLocation) +
                    "\n通知权限：" + yesNo(notification) +
                    "\n" + usageLimitMinutes + " 分钟醒目提醒：" + yesNo(overlay) +
                    "\n家位置：" + setUnset(places.getBoolean("home_set", false)) +
                    "  学校位置：" + setUnset(places.getBoolean("school_set", false)) +
                    "  食堂位置：" + setUnset(places.getBoolean("canteen_set", false)) +
                    "  图书馆位置：" + setUnset(places.getBoolean("library_set", false)) +
                    "\n已保存事件：" + database.countEvents());
            eventsView.setText(database.recentEvents(12));
            refreshAlertStats(database);
        }
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private String yesNo(boolean value) { return value ? "已授予" : "未授予"; }
    private String setUnset(boolean value) { return value ? "已设置" : "未设置"; }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
