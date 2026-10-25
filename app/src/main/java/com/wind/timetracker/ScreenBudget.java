package com.wind.timetracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 手机额度（目前只记账、不拦截）：除白名单外的应用都要用额度，额度由学习、上课、走路换来。
 *
 * <p>可用 = 结转 + 底额 45 + 学习分钟（每天最多算 150）+ 开始次数 × 10（最多 4 次）+ 上课分钟 × 0.25
 * + 关屏步数 / 1000 × 10（每天最多算 6000 步）。休息日固定 150，不挣也不结转。每天最多用 240 分钟。
 * 一天从早上 5 点算起。没用完的最多结转 60 分钟到第二天。余额用完后继续用的时间记为「超出」，不拦截。
 *
 * <p>学习分钟和开始次数来自 iPad LearningPack 的学习码（离线），校验算法和 LearningPack 的 StudyCode 一致：
 * HMAC-SHA256(配对密钥, "日期|分钟|次数")，取前 4 字节（大端，去掉最高位）模 1,000,000。
 *
 * <p>各线程（每秒的前台检测、计步传感器回调、界面）都会调用，方法全部同步。状态在内存里累计，
 * 每 30 秒和每次输入时存一次 SharedPreferences；事件写 app_events 表（source_package = budget）。
 */
final class ScreenBudget {
    static final int BASE_MIN = 45;
    static final int STUDY_CAP_MIN = 150;
    static final int START_BONUS_MIN = 10;
    static final int MAX_STARTS = 4;
    static final double CLASS_RATE = 0.25;
    static final int STEP_UNIT = 1000;
    static final int STEP_UNIT_MIN = 10;
    static final int STEP_CAP = 6000;
    static final int CARRY_CAP_MIN = 60;
    static final int DAILY_MAX_MIN = 240;
    static final int REST_DAY_MIN = 150;
    static final int DAY_START_HOUR = 5;
    static final String SOURCE = "budget";

    /** 系统自带的应用按前缀免费，下面这些例外照样计费（浏览器、视频）。 */
    private static final String[] FREE_PREFIXES = {
            "android", "com.android.", "com.miui.", "com.xiaomi.", "com.mi.", "com.lbe.security.miui",
            "com.google.android.inputmethod", "com.google.android.gms", "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller", "com.google.android.dialer", "com.google.android.contacts",
            "com.google.android.apps.messaging", "com.sohu.inputmethod", "com.baidu.input", "com.iflytek.inputmethod",
    };
    private static final Set<String> GATED_SYSTEM = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.android.browser", "com.miui.video", "com.miui.player",
            "com.google.android.googlequicksearchbox", "com.miui.newhome"));
    /** 白名单：地图、邮件、联系、生活支付、出行、学校与办公、英语（YouTube 目前只用来学英语）、音乐、工具。 */
    private static final Set<String> FREE_APPS = new HashSet<>(Arrays.asList(
            // 地图
            "com.autonavi.minimap", "com.baidu.BaiduMap", "com.google.android.apps.maps", "com.tencent.map",
            // 邮件
            "com.microsoft.office.outlook", "com.google.android.gm", "com.netease.mail", "com.tencent.androidqqmail",
            // 联系
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim",
            // 生活、支付、银行
            "com.sankuai.meituan", "com.taobao.taobao", "com.eg.android.AlipayGphone", "com.chinamworld.bocmbci",
            "com.jingdong.app.mall", "com.sdu.didi.psnger", "cmb.pb", "com.csii.csbank", "cn.gov.pbc.dcep",
            "com.mipay.wallet", "com.paypal.android.p2pmobile", "com.gojek.gopay", "com.cainiao.wireless",
            "com.greenpoint.android.mc10086.activity", "com.ct.client", "com.wondersgroup.ybtproduct", "com.zhszcn.zhsmk",
            "com.qiekj.user", "cn.cyberIdentity.certification",
            // 出行
            "com.MobileTicket", "com.mygolbs.mybus", "com.ibike.publicbicycle.activity", "cn.ninebot.ninebot",
            // 学校、办公、学习
            "com.chaoxing.mobile", "com.tencent.wework", "com.xuetangx.ykt", "com.klcxkj.zqxy", "com.larksuite.suite",
            "cn.com.chsi.chsiapp", "cn.unipus.itest.student", "com.netease.edu.ucmooc", "com.ichi2.anki",
            "com.huachenjie.shandong_school", "com.huachenjie.shandong_school_pro", "com.Enlink.sdp",
            "cn.wps.moffice_eng.xiaomi.lite", "com.microsoft.office.word", "com.microsoft.office.excel",
            "com.intsig.camscanner", "com.google.android.apps.docs", "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides",
            "com.google.android.calendar", "com.google.android.apps.tasks", "com.google.android.apps.translate",
            "com.baidu.baidutranslate", "md.obsidian", "notion.id",
            // LearningPack 手机复习：在上面学的时间同步到 iPad，算进学习码，不能再扣额度
            "com.wind.learningpack",
            // 英语、音乐
            "com.wind.subword", "com.bilingify.readest", "epub.reader", "com.google.android.youtube",
            "com.apple.android.music",
            // 系统组件、工具
            "com.wind.timetracker", "com.google.android.apps.authenticator2", "com.nebula.clashmi",
            "com.google.android.keep", "com.google.android.apps.photos", "com.google.android.documentsui",
            "com.google.android.tts", "com.google.android.apps.credentialmanager", "com.touchtype.swiftkey",
            "org.fcitx.fcitx5.android", "com.termux", "com.server.auditor.ssh.client", "org.localsend.localsend_app",
            "com.estmob.android.sendanywhere", "com.v2ray.ang", "com.github.metacubex.clash.meta",
            "moe.shizuku.privileged.api", "bin.mt.plus", "com.aikuai.ecloud", "com.alicloud.databox",
            "com.baidu.netdisk", "com.microsoft.skydrive", "mega.privacy.android.app",
            "com.duokan.phone.remotecontroller"));

    private static ScreenBudget instance;

    private final SharedPreferences prefs;
    private final DatabaseHelper database;

    // 当天状态
    private String day;
    private double carryMin;
    private long usedMs;
    private long overMs;
    private int studyMin;
    private int studyStarts;
    private int classMin;
    private long offSteps;
    private boolean restDay;
    private final Map<String, Long> appMs = new HashMap<>();
    private long exhaustedAtMs;
    // 运行状态（不存）
    private long lastStepCount = -1;
    private long lastTickMs;
    private boolean overNow;
    private long overSinceMs;
    private String overPackage;

    static synchronized ScreenBudget get(Context context) {
        if (instance == null) instance = new ScreenBudget(context.getApplicationContext());
        return instance;
    }

    private ScreenBudget(Context context) {
        prefs = context.getSharedPreferences("screen_budget", Context.MODE_PRIVATE);
        database = new DatabaseHelper(context);
        load();
        refundFreeApps();
        rollover(System.currentTimeMillis());
    }

    /** 后来加进白名单的应用：当天已经记的用时退回来。 */
    private void refundFreeApps() {
        JSONObject refunded = new JSONObject();
        for (Iterator<Map.Entry<String, Long>> it = appMs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (isGated(e.getKey())) continue;
            usedMs -= e.getValue();
            put(refunded, e.getKey(), e.getValue() / 60000.0);
            it.remove();
        }
        if (refunded.length() == 0) return;
        usedMs = Math.max(usedMs, 0);
        overMs = Math.min(overMs, usedMs);
        save();
        log("budget_refund", refunded.toString());
    }

    // ---------- 白名单 ----------

    static boolean isGated(String pkg) {
        if (pkg == null || pkg.isEmpty() || "权限不足".equals(pkg)) return false;
        if (GATED_SYSTEM.contains(pkg)) return true;
        if (FREE_APPS.contains(pkg)) return false;
        for (String prefix : FREE_PREFIXES) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix)) return false;
        }
        return true;
    }

    // ---------- 记账 ----------

    /** 每秒的前台检测调用（屏幕亮着、正在用手机时）。 */
    synchronized void tickUsage(String pkg, long nowMs) {
        rollover(nowMs);
        long dt = lastTickMs == 0 ? 0 : Math.min(Math.max(nowMs - lastTickMs, 0), 2000);
        lastTickMs = nowMs;
        boolean gated = isGated(pkg);
        boolean over = gated && remainingMinNow() <= 0;
        if (gated && dt > 0) {
            usedMs += dt;
            Long prev = appMs.get(pkg);
            appMs.put(pkg, (prev == null ? 0 : prev) + dt);
            if (over) overMs += dt;
        }
        if (over && !overNow) {
            overNow = true;
            overSinceMs = nowMs;
            overPackage = pkg;
            if (exhaustedAtMs == 0) {
                exhaustedAtMs = nowMs;
                log("budget_exhausted", summaryJson());
            }
        } else if (!over && overNow) {
            endOverStretch(nowMs);
        }
    }

    /** 屏幕熄灭或不在用手机时，结束正在记的超出段。 */
    synchronized void idle(long nowMs) {
        lastTickMs = 0;
        if (overNow) endOverStretch(nowMs);
    }

    private void endOverStretch(long nowMs) {
        overNow = false;
        JSONObject j = new JSONObject();
        put(j, "package", overPackage);
        put(j, "seconds", (nowMs - overSinceMs) / 1000);
        put(j, "used_min", usedMs / 60000.0);
        log("budget_over", j.toString());
    }

    /** 计步传感器回调：屏幕关着时走的步数才算。 */
    synchronized void onStepCount(long count, boolean screenOn) {
        rollover(System.currentTimeMillis());
        if (lastStepCount >= 0 && count >= lastStepCount && !screenOn) {
            offSteps += Math.min(count - lastStepCount, 5000);   // 防止重启等异常跳变
        }
        lastStepCount = count;
    }

    // ---------- 输入 ----------

    static final class CodeResult {
        final boolean ok;
        final String message;

        CodeResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    synchronized CodeResult enterCode(int minutes, int starts, String code) {
        rollover(System.currentTimeMillis());
        String secret = secret();
        if (secret.isEmpty()) return new CodeResult(false, "还没填配对密钥");
        String expected = code(secret, day, minutes, starts);
        JSONObject j = new JSONObject();
        put(j, "minutes", minutes);
        put(j, "starts", starts);
        put(j, "valid", expected.equals(code));
        if (!expected.equals(code)) {
            log("budget_code", j.toString());
            return new CodeResult(false, "校验码不对：确认是今天（" + day + "）的码，三个数都照 iPad 上填");
        }
        int oldStudy = studyMin, oldStarts = studyStarts;
        studyMin = Math.max(studyMin, minutes);
        studyStarts = Math.max(studyStarts, starts);
        put(j, "previous_minutes", oldStudy);
        put(j, "previous_starts", oldStarts);
        log("budget_code", j.toString());
        save();
        if (minutes < oldStudy || starts < oldStarts) {
            return new CodeResult(true, "比之前输入的少，保留之前的 " + studyMin + " 分钟、" + studyStarts + " 次");
        }
        return new CodeResult(true, "已记上：学习 " + studyMin + " 分钟、开始 " + studyStarts + " 次");
    }

    synchronized void setClassMinutes(int minutes) {
        rollover(System.currentTimeMillis());
        JSONObject j = new JSONObject();
        put(j, "minutes", minutes);
        put(j, "previous", classMin);
        classMin = Math.max(0, minutes);
        log("budget_class", j.toString());
        save();
    }

    /** 把明天设为休息日（每周一天，只能提前一天定）；返回提示。 */
    synchronized String toggleRestTomorrow() {
        rollover(System.currentTimeMillis());
        LocalDate tomorrow = LocalDate.parse(day).plusDays(1);
        Set<String> rest = new HashSet<>(prefs.getStringSet("rest_days", new HashSet<>()));
        String key = tomorrow.toString();
        JSONObject j = new JSONObject();
        put(j, "day", key);
        if (rest.remove(key)) {
            prefs.edit().putStringSet("rest_days", rest).apply();
            put(j, "set", false);
            log("budget_rest_day", j.toString());
            return "已取消明天的休息日";
        }
        LocalDate monday = tomorrow.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (String d : rest) {
            LocalDate other = LocalDate.parse(d);
            if (!other.isBefore(monday) && other.isBefore(monday.plusDays(7))) {
                return "这周已经有休息日（" + d + "），每周只能一天";
            }
        }
        rest.add(key);
        prefs.edit().putStringSet("rest_days", rest).apply();
        put(j, "set", true);
        log("budget_rest_day", j.toString());
        return "明天（" + key + "）是休息日：固定 " + REST_DAY_MIN + " 分钟，不用挣";
    }

    synchronized boolean isRestTomorrow() {
        return prefs.getStringSet("rest_days", new HashSet<>()).contains(LocalDate.parse(day).plusDays(1).toString());
    }

    synchronized String secret() {
        return prefs.getString("secret", "");
    }

    synchronized void setSecret(String secret) {
        prefs.edit().putString("secret", secret.trim().toLowerCase(Locale.ROOT)).apply();
        log("budget_secret", "{}");
    }

    // ---------- 计算 ----------

    static final class Snapshot {
        String day;
        boolean restDay;
        double carryMin, baseMin, studyEarnMin, startEarnMin, classEarnMin, walkEarnMin;
        int studyMin, studyStarts, classMin;
        long offSteps;
        double availableMin, usedMin, overMin, balanceMin, remainingMin;
        long exhaustedAtMs;
        List<Map.Entry<String, Long>> topApps;
    }

    synchronized Snapshot snapshot() {
        rollover(System.currentTimeMillis());
        Snapshot s = new Snapshot();
        s.day = day;
        s.restDay = restDay;
        s.studyMin = studyMin;
        s.studyStarts = studyStarts;
        s.classMin = classMin;
        s.offSteps = offSteps;
        if (restDay) {
            s.baseMin = REST_DAY_MIN;
        } else {
            s.carryMin = carryMin;
            s.baseMin = BASE_MIN;
            s.studyEarnMin = Math.min(studyMin, STUDY_CAP_MIN);
            s.startEarnMin = Math.min(studyStarts, MAX_STARTS) * START_BONUS_MIN;
            s.classEarnMin = classMin * CLASS_RATE;
            s.walkEarnMin = Math.min(offSteps, STEP_CAP) * (double) STEP_UNIT_MIN / STEP_UNIT;
        }
        s.availableMin = s.carryMin + s.baseMin + s.studyEarnMin + s.startEarnMin + s.classEarnMin + s.walkEarnMin;
        s.usedMin = usedMs / 60000.0;
        s.overMin = overMs / 60000.0;
        s.balanceMin = s.availableMin - s.usedMin;
        s.remainingMin = Math.min(s.balanceMin, DAILY_MAX_MIN - s.usedMin);
        s.exhaustedAtMs = exhaustedAtMs;
        List<Map.Entry<String, Long>> apps = new ArrayList<>(appMs.entrySet());
        apps.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        s.topApps = apps;
        return s;
    }

    /** 当天可用的分钟数（调用方已持有锁）。 */
    private double availableMin() {
        if (restDay) return REST_DAY_MIN;
        return carryMin + BASE_MIN + Math.min(studyMin, STUDY_CAP_MIN)
                + Math.min(studyStarts, MAX_STARTS) * START_BONUS_MIN + classMin * CLASS_RATE
                + Math.min(offSteps, STEP_CAP) * (double) STEP_UNIT_MIN / STEP_UNIT;
    }

    private double remainingMinNow() {
        double used = usedMs / 60000.0;
        return Math.min(availableMin() - used, DAILY_MAX_MIN - used);
    }

    static String code(String secret, String day, int minutes, int starts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((day + "|" + minutes + "|" + starts).getBytes(StandardCharsets.UTF_8));
            long value = ((h[0] & 0x7fL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String dayKey(long ms) {
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
        return t.minusHours(DAY_START_HOUR).toLocalDate().toString();
    }

    // ---------- 换天、存取 ----------

    private void rollover(long nowMs) {
        String today = dayKey(nowMs);
        if (today.equals(day)) return;
        double carry = 0;
        if (day != null) {
            log("budget_day", summaryJson());
            boolean yesterday = LocalDate.parse(day).plusDays(1).toString().equals(today);
            double balance = availableMin() - usedMs / 60000.0;
            if (yesterday && !restDay) carry = Math.min(Math.max(balance, 0), CARRY_CAP_MIN);
            if (overNow) endOverStretch(nowMs);
        }
        day = today;
        carryMin = carry;
        usedMs = 0;
        overMs = 0;
        studyMin = 0;
        studyStarts = 0;
        classMin = 0;
        offSteps = 0;
        appMs.clear();
        exhaustedAtMs = 0;
        restDay = prefs.getStringSet("rest_days", new HashSet<>()).contains(today);
        save();
    }

    private String summaryJson() {
        JSONObject j = new JSONObject();
        put(j, "day", day);
        put(j, "rest_day", restDay);
        put(j, "carry_min", carryMin);
        put(j, "study_min", studyMin);
        put(j, "starts", studyStarts);
        put(j, "class_min", classMin);
        put(j, "off_steps", offSteps);
        put(j, "available_min", availableMin());
        put(j, "used_min", usedMs / 60000.0);
        put(j, "over_min", overMs / 60000.0);
        JSONObject apps = new JSONObject();
        for (Map.Entry<String, Long> e : appMs.entrySet()) put(apps, e.getKey(), e.getValue() / 60000.0);
        put(j, "apps_min", apps);
        return j.toString();
    }

    synchronized void save() {
        JSONObject apps = new JSONObject();
        for (Map.Entry<String, Long> e : appMs.entrySet()) put(apps, e.getKey(), e.getValue());
        prefs.edit()
                .putString("day", day)
                .putFloat("carry_min", (float) carryMin)
                .putLong("used_ms", usedMs)
                .putLong("over_ms", overMs)
                .putInt("study_min", studyMin)
                .putInt("study_starts", studyStarts)
                .putInt("class_min", classMin)
                .putLong("off_steps", offSteps)
                .putLong("exhausted_at", exhaustedAtMs)
                .putString("apps_ms", apps.toString())
                .apply();
    }

    private void load() {
        day = prefs.getString("day", null);
        carryMin = prefs.getFloat("carry_min", 0);
        usedMs = prefs.getLong("used_ms", 0);
        overMs = prefs.getLong("over_ms", 0);
        studyMin = prefs.getInt("study_min", 0);
        studyStarts = prefs.getInt("study_starts", 0);
        classMin = prefs.getInt("class_min", 0);
        offSteps = prefs.getLong("off_steps", 0);
        exhaustedAtMs = prefs.getLong("exhausted_at", 0);
        restDay = day != null && prefs.getStringSet("rest_days", new HashSet<>()).contains(day);
        appMs.clear();
        try {
            JSONObject apps = new JSONObject(prefs.getString("apps_ms", "{}"));
            for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String k = it.next();
                appMs.put(k, apps.getLong(k));
            }
        } catch (JSONException ignored) { }
    }

    private void log(String type, String payload) {
        try {
            database.insertAppEvent(System.currentTimeMillis(), SOURCE, type, payload);
        } catch (RuntimeException ignored) { }
    }

    private static void put(JSONObject j, String key, Object value) {
        try {
            j.put(key, value);
        } catch (JSONException ignored) { }
    }
}
