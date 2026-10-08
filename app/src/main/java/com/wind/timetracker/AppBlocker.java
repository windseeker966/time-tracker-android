package com.wind.timetracker;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 应用屏蔽：台北时间周一 0:00 到周六 0:00 期间，打开屏蔽列表里的应用会被送回桌面并全屏遮挡。
 * {@link #check()} 在服务的后台线程每秒调用；遮挡界面只在主线程创建和移除。
 */
final class AppBlocker {
    static final String PREFS = "app_block";
    static final String KEY_PACKAGES = "packages";
    static final String KEY_EMERGENCY_UNTIL_MS = "emergency_until_ms";
    /** Edge 和 Telegram（商店版 + 官网直装版）。 */
    static final Set<String> DEFAULT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.microsoft.emmx", "org.telegram.messenger", "org.telegram.messenger.web"));
    static final long EMERGENCY_MS = 5 * 60_000L;
    private static final long GO_HOME_RETRY_MS = 3_000L;

    static final String EVENT_BLOCKED = "app_blocked";
    static final String EVENT_EMERGENCY = "app_block_emergency";
    static final String EVENT_SETTINGS = "app_block_settings";

    /** 由服务把事件写入数据库；pkg 写入 app_package 列。 */
    interface Recorder {
        void record(String eventType, String pkg);
    }

    private final Context context;
    private final Handler mainHandler;
    private final Recorder recorder;
    // 前台应用增量追踪，只在后台线程读写。
    private long lastQueryMs;
    private long latestEventMs;
    private String latestPackage;
    private volatile View overlayView;
    /** 正在遮挡的应用包名；只在后台线程读写。 */
    private String blockingPackage;
    private long lastGoHomeMs;

    AppBlocker(Context context, Handler mainHandler, Recorder recorder) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.recorder = recorder;
    }

    // ---- 设置 ----

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> blockedPackages(Context context) {
        return new TreeSet<>(prefs(context).getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES));
    }

    static void setBlockedPackages(Context context, Set<String> packages) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, new HashSet<>(packages)).apply();
    }

    /** 台北时间周一 0:00 – 周六 0:00 为屏蔽时段。 */
    static boolean isBlockPeriod() {
        DayOfWeek day = ZonedDateTime.now(CollectorService.NIGHT_ZONE).getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** 紧急使用剩余毫秒数；存的是系统时间，超过上限视为改过时间，作废。 */
    static long emergencyRemainingMs(Context context) {
        long remaining = prefs(context).getLong(KEY_EMERGENCY_UNTIL_MS, 0) - System.currentTimeMillis();
        return remaining > 0 && remaining <= EMERGENCY_MS ? remaining : 0;
    }

    static String appLabel(Context context, String pkg) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return pkg;
        }
    }

    // ---- 检测 ----

    /**
     * 后台线程调用。设备正在使用（亮屏且已解锁）时才需要调用。
     * 遮挡跟随前台应用：被屏蔽的应用在前台就一直遮挡并定期重试回桌面，离开后自动移除。
     */
    void check() {
        String pkg = foregroundPackage();
        boolean block = pkg != null && isBlockPeriod() && emergencyRemainingMs(context) <= 0
                && blockedPackages(context).contains(pkg);
        if (!block) {
            if (blockingPackage != null) {
                blockingPackage = null;
                mainHandler.post(this::removeOverlay);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!pkg.equals(blockingPackage) || overlayView == null) {
            // 新拦截，或遮挡被熄屏移除、上次没能显示。遮挡显示后会立即回桌面。
            if (!pkg.equals(blockingPackage)) recorder.record(EVENT_BLOCKED, pkg);
            blockingPackage = pkg;
            lastGoHomeMs = now;
            String label = appLabel(context, pkg);
            mainHandler.post(() -> showOverlay(pkg, label));
        } else if (now - lastGoHomeMs >= GO_HOME_RETRY_MS || now < lastGoHomeMs) {
            lastGoHomeMs = now;
            goHome();
        }
    }

    /** 增量读取使用事件，避免每秒扫描 30 分钟的记录。 */
    private String foregroundPackage() {
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        // 系统时间被往回调时重新扫描。
        if (lastQueryMs <= 0 || now < lastQueryMs) {
            lastQueryMs = now - 30 * 60_000L;
            latestEventMs = 0;
            latestPackage = null;
        }
        UsageEvents events;
        try {
            // 事件写入可能略有延迟，查询窗口往前重叠几秒。
            events = manager.queryEvents(lastQueryMs - 5_000L, now);
        } catch (RuntimeException error) {
            return latestPackage;
        }
        lastQueryMs = now;
        if (events == null) return latestPackage;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    && event.getTimeStamp() >= latestEventMs) {
                latestEventMs = event.getTimeStamp();
                latestPackage = event.getPackageName();
            }
        }
        return latestPackage;
    }

    /**
     * HyperOS 不允许后台服务直接启动桌面（即使有悬浮窗权限），
     * 但遮挡显示出来后本应用就有可见窗口，此时启动桌面会被放行。
     */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(home);
        } catch (RuntimeException ignored) { }
    }

    // ---- 遮挡界面（主线程） ----

    private void showOverlay(String pkg, String label) {
        if (overlayView != null) return;
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "已屏蔽：" + label, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(48), dp(28), dp(48));
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(context);
        title.setText("已屏蔽：" + label);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, fullWidth());

        TextView message = new TextView(context);
        message.setText("周一到周五（台北时间）不能使用这个应用，周六 0:00 解禁。\n\n离开这个应用后遮挡会自动消失。");
        message.setTextColor(Color.rgb(70, 70, 70));
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, dp(20));
        root.addView(message, fullWidth());

        Button close = new Button(context);
        close.setText("回到桌面");
        close.setTextSize(18);
        close.setAllCaps(false);
        close.setOnClickListener(v -> goHome());
        root.addView(close, fullWidth());

        Button emergency = new Button(context);
        emergency.setText("紧急使用 " + EMERGENCY_MS / 60_000L + " 分钟（长按，会被记录）");
        emergency.setTextSize(13);
        emergency.setAllCaps(false);
        emergency.setAlpha(0.7f);
        emergency.setOnClickListener(v -> Toast.makeText(context, "请长按", Toast.LENGTH_SHORT).show());
        emergency.setOnLongClickListener(v -> {
            prefs(context).edit()
                    .putLong(KEY_EMERGENCY_UNTIL_MS, System.currentTimeMillis() + EMERGENCY_MS)
                    .apply();
            recorder.record(EVENT_EMERGENCY, pkg);
            removeOverlay();
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                try {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) { }
            }
            return true;
        });
        LinearLayout.LayoutParams emergencyParams = fullWidth();
        emergencyParams.topMargin = dp(28);
        root.addView(emergency, emergencyParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        CollectorService.coverSystemBars(params);
        try {
            context.getSystemService(WindowManager.class).addView(root, params);
            overlayView = root;
            mainHandler.post(this::goHome);
        } catch (RuntimeException error) {
            overlayView = null;
            Toast.makeText(context, "已屏蔽：" + label, Toast.LENGTH_LONG).show();
        }
    }

    /** 主线程调用；熄屏和服务销毁时也会调用。 */
    void removeOverlay() {
        View view = overlayView;
        overlayView = null;
        if (view == null) return;
        try {
            context.getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) { }
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
