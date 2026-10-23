package com.wind.timetracker;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 手机额度的显示文字，常驻通知和主界面共用。 */
final class BudgetText {
    private BudgetText() { }

    static String title(ScreenBudget.Snapshot b) {
        if (b.remainingMin <= 0) {
            return "额度用完了，已超出 " + min(b.overMin) + " 分钟（只记账）";
        }
        return "手机额度 剩 " + min(b.remainingMin) + " 分钟（只记账）";
    }

    static String line(ScreenBudget.Snapshot b) {
        StringBuilder s = new StringBuilder();
        s.append("可用 ").append(min(b.availableMin)).append(" · 已用 ").append(min(b.usedMin));
        if (b.overMin >= 0.5) s.append(" · 超出 ").append(min(b.overMin));
        if (b.exhaustedAtMs > 0) {
            s.append(" · ").append(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(b.exhaustedAtMs))).append(" 用完");
        }
        return s.toString();
    }

    static String sources(ScreenBudget.Snapshot b) {
        if (b.restDay) return "今天是休息日：固定 " + ScreenBudget.REST_DAY_MIN + " 分钟";
        StringBuilder s = new StringBuilder();
        s.append("底 ").append(min(b.baseMin));
        if (b.carryMin >= 0.5) s.append(" · 结转 ").append(min(b.carryMin));
        s.append(" · 学习 +").append(min(b.studyEarnMin));
        s.append(" · 开始 +").append(min(b.startEarnMin));
        s.append(" · 上课 +").append(min(b.classEarnMin));
        s.append(" · 走路 +").append(min(b.walkEarnMin)).append("（").append(b.offSteps).append(" 步）");
        return s.toString();
    }

    /** 四舍五入到分钟。 */
    static long min(double minutes) {
        return Math.round(minutes);
    }
}
