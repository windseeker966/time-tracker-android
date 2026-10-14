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
 * 应用屏蔽，两条规则（台北时间）：
 * <ul>
 *   <li>周一 0:00 到周六 0:00，屏蔽列表里的应用；</li>
 *   <li>每天在饭后和睡前的时段屏蔽哔哩哔哩各客户端，遮挡页给出这个时段可以做的事。</li>
 * </ul>
 * 打开被屏蔽的应用会被送回桌面，遮挡留到点“知道了”或熄屏。
 * {@link #check()} 在服务的后台线程每秒调用；遮挡界面只在主线程创建和移除。
 */
final class AppBlocker {
    static final String PREFS = "app_block";
    static final String KEY_PACKAGES = "packages";
    static final String KEY_EMERGENCY_UNTIL_MS = "emergency_until_ms";
    /** 首次使用时的屏蔽列表：Edge。 */
    static final Set<String> DEFAULT_PACKAGES = new HashSet<>(Arrays.asList("com.microsoft.emmx"));
    static final long EMERGENCY_MS = 5 * 60_000L;
    /** 哔哩哔哩官方版、概念版、HD 版：全天屏蔽，B 站只用 PiliPlus（有应用内的使用记录和稍后再看模式）。 */
    static final Set<String> BILIBILI_OFFICIAL_PACKAGES = new HashSet<>(Arrays.asList(
            "com.bilibili.app.in", "com.bilibili.app.blue", "tv.danmaku.bili", "tv.danmaku.bilibilihd"));
    static final String PILIPLUS_PACKAGE = "com.example.piliplus";
    /** 按时段屏蔽的哔哩哔哩客户端：官方客户端加 PiliPlus。 */
    static final Set<String> BILIBILI_PACKAGES = new HashSet<>(Arrays.asList(
            "com.bilibili.app.in", "com.bilibili.app.blue", "tv.danmaku.bili", "tv.danmaku.bilibilihd",
            PILIPLUS_PACKAGE));
    /** 哔哩哔哩每天的屏蔽时段：开始小时（含）、结束小时（不含）、名称、这个时段的建议。 */
    static final Slot[] BILIBILI_SLOTS = {
            new Slot(12, 14, "午饭后", "睡 20 分钟。先定好闹钟，手机放远一点。"),
            new Slot(19, 22, "晚饭后", "出门散步 20 分钟，或者洗个澡、听听播客和音乐，不看屏幕。"),
            new Slot(23, 5, "睡前", "看几页书，或者写几句日记，然后去睡觉。"),
    };
    private static final long GO_HOME_RETRY_MS = 3_000L;

    static final String EVENT_BLOCKED = "app_blocked";
    static final String EVENT_EMERGENCY = "app_block_emergency";
    static final String EVENT_SETTINGS = "app_block_settings";

    static final class Slot {
        final int startHour;
        final int endHour;
        final String name;
        final String suggestion;

        Slot(int startHour, int endHour, String name, String suggestion) {
            this.startHour = startHour;
            this.endHour = endHour;
            this.name = name;
            this.suggestion = suggestion;
        }

        boolean contains(int hour) {
            return startHour < endHour ? hour >= startHour && hour < endHour
                    : hour >= startHour || hour < endHour;  // 跨过 0 点
        }

        String range() {
            return startHour + ":00–" + endHour + ":00";
        }
    }

    /** 遮挡页的内容。 */
    private static final class Block {
        final String message;
        final String suggestion;

        Block(String message, String suggestion) {
            this.message = message;
            this.suggestion = suggestion;
        }
    }

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

    /** 当前所在的哔哩哔哩屏蔽时段，不在时段内返回 null。 */
    static Slot currentBilibiliSlot() {
        int hour = ZonedDateTime.now(CollectorService.NIGHT_ZONE).getHour();
        for (Slot slot : BILIBILI_SLOTS) {
            if (slot.contains(hour)) return slot;
        }
        return null;
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
     * 被屏蔽的应用在前台时显示遮挡并定期重试回桌面；回到桌面后遮挡保留，等点“知道了”或熄屏。
     *
     * @return 当前前台应用包名（未知为 null），供连续使用提醒判断白名单。
     */
    String check() {
        String pkg = foregroundPackage();
        Block block = blockFor(pkg);
        if (block == null) {
            // 已经离开被屏蔽的应用。遮挡不跟着移除，否则一回到桌面就消失，来不及看。
            blockingPackage = null;
            return pkg;
        }
        long now = System.currentTimeMillis();
        if (!pkg.equals(blockingPackage) || overlayView == null) {
            // 新拦截，或遮挡被熄屏移除、上次没能显示。遮挡显示后会立即回桌面。
            if (!pkg.equals(blockingPackage)) recorder.record(EVENT_BLOCKED, pkg);
            blockingPackage = pkg;
            lastGoHomeMs = now;
            String label = appLabel(context, pkg);
            mainHandler.post(() -> showOverlay(pkg, label, block));
        } else if (now - lastGoHomeMs >= GO_HOME_RETRY_MS || now < lastGoHomeMs) {
            lastGoHomeMs = now;
            goHome();
        }
        return pkg;
    }

    private Block blockFor(String pkg) {
        if (pkg == null || emergencyRemainingMs(context) > 0) return null;
        if (BILIBILI_OFFICIAL_PACKAGES.contains(pkg)) {
            return new Block("B 站请用 PiliPlus：官方客户端全天屏蔽。",
                    "打开 PiliPlus，先看稍后再看里已经挑好的视频。");
        }
        if (BILIBILI_PACKAGES.contains(pkg)) {
            Slot slot = currentBilibiliSlot();
            if (slot != null) {
                return new Block("现在是" + slot.name + "（" + slot.range() + "），"
                        + slot.endHour + ":00 解禁。", slot.suggestion);
            }
        }
        if (isBlockPeriod() && blockedPackages(context).contains(pkg)) {
            return new Block("周一到周五（台北时间）不能使用这个应用，周六 0:00 解禁。", null);
        }
        return null;
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

    private void showOverlay(String pkg, String label, Block block) {
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
        message.setText(block.message);
        message.setTextColor(Color.rgb(70, 70, 70));
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, dp(20));
        root.addView(message, fullWidth());

        if (block.suggestion != null) {
            TextView suggestion = new TextView(context);
            suggestion.setText("现在可以做的事：\n" + block.suggestion);
            suggestion.setTextColor(Color.rgb(35, 35, 35));
            suggestion.setTextSize(20);
            suggestion.setGravity(Gravity.CENTER);
            suggestion.setPadding(dp(16), dp(16), dp(16), dp(16));
            suggestion.setBackgroundColor(Color.rgb(232, 238, 248));
            LinearLayout.LayoutParams suggestionParams = fullWidth();
            suggestionParams.bottomMargin = dp(20);
            root.addView(suggestion, suggestionParams);
        }

        Button close = new Button(context);
        close.setText("知道了");
        close.setTextSize(18);
        close.setAllCaps(false);
        close.setOnClickListener(v -> {
            goHome();  // 先回桌面再移除：HyperOS 只在本应用有可见窗口时允许启动桌面
            removeOverlay();
        });
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
