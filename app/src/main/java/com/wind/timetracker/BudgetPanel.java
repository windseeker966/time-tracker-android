package com.wind.timetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** 主界面最上面的手机额度卡片：余额、来源、计费应用用时，输入学习码、上课时长、休息日、配对密钥。 */
final class BudgetPanel {
    private final Activity activity;
    private final ScreenBudget budget;
    private TextView titleView;
    private TextView detailView;
    private Button restButton;

    BudgetPanel(Activity activity) {
        this.activity = activity;
        this.budget = ScreenBudget.get(activity);
    }

    View build() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.rgb(232, 240, 250));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        titleView = new TextView(activity);
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.rgb(25, 55, 110));
        box.addView(titleView);

        detailView = new TextView(activity);
        detailView.setTextSize(15);
        detailView.setPadding(0, dp(6), 0, dp(6));
        box.addView(detailView);

        LinearLayout row1 = row();
        row1.addView(button("输入学习码", v -> enterCode()), weight());
        row1.addView(button("录入今天上课", v -> enterClass()), weight());
        box.addView(row1);
        LinearLayout row2 = row();
        restButton = button("", v -> toggleRest());
        row2.addView(restButton, weight());
        row2.addView(button("配对密钥", v -> editSecret()), weight());
        box.addView(row2);
        refresh();
        return box;
    }

    void refresh() {
        if (titleView == null) return;
        ScreenBudget.Snapshot b = budget.snapshot();
        titleView.setText(BudgetText.title(b));
        StringBuilder s = new StringBuilder();
        s.append(BudgetText.line(b)).append('\n').append(BudgetText.sources(b));
        s.append("\n学习码：").append(b.studyMin).append(" 分钟、开始 ").append(b.studyStarts).append(" 次");
        s.append(" · 上课 ").append(b.classMin).append(" 分钟");
        s.append("\n今天计费应用：").append(topApps(b.topApps));
        s.append("\n\n规则：底 ").append(ScreenBudget.BASE_MIN).append(" 分钟；学习 1 分钟换 1 分钟（每天最多算 ")
                .append(ScreenBudget.STUDY_CAP_MIN).append("）；每段学习满 10 分钟 +").append(ScreenBudget.START_BONUS_MIN)
                .append("（最多 ").append(ScreenBudget.MAX_STARTS).append(" 次）；上课每分钟 0.25；关屏走 1000 步 +")
                .append(ScreenBudget.STEP_UNIT_MIN).append("（最多 ").append(ScreenBudget.STEP_CAP).append(" 步）；结转最多 ")
                .append(ScreenBudget.CARRY_CAP_MIN).append("；每天最多用 ").append(ScreenBudget.DAILY_MAX_MIN)
                .append("。一天从 5 点算起。目前只记账，不拦截。");
        if (budget.secret().isEmpty()) s.append("\n还没填配对密钥：iPad「设置」›「手机额度」里有。");
        detailView.setText(s);
        restButton.setText(budget.isRestTomorrow() ? "取消明天的休息日" : "明天设为休息日");
    }

    private String topApps(List<Map.Entry<String, Long>> apps) {
        if (apps.isEmpty()) return "还没用";
        StringBuilder s = new StringBuilder();
        int n = 0;
        long otherMs = 0;
        for (Map.Entry<String, Long> e : apps) {
            if (n++ >= 4) {
                otherMs += e.getValue();
                continue;
            }
            if (s.length() > 0) s.append(" · ");
            s.append(label(e.getKey())).append(' ').append(Math.round(e.getValue() / 60000.0));
        }
        if (n > 4) s.append(" · 其他 ").append(n - 4).append(" 个 ").append(Math.round(otherMs / 60000.0));
        return s.toString();
    }

    private String label(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void enterCode() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText minutes = number(form, "学习（分钟）");
        EditText starts = number(form, "开始（次）");
        EditText code = number(form, "校验码（6 位）");
        new AlertDialog.Builder(activity)
                .setTitle("输入 iPad 上的学习码")
                .setMessage("iPad 工具栏「状态」›「手机学习码」")
                .setView(form)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        ScreenBudget.CodeResult r = budget.enterCode(
                                Integer.parseInt(minutes.getText().toString().trim()),
                                Integer.parseInt(starts.getText().toString().trim()),
                                code.getText().toString().trim());
                        toast(r.message);
                    } catch (NumberFormatException e) {
                        toast("三个数都要填");
                    }
                    changed();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void enterClass() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText minutes = number(form, "今天上课一共多少分钟");
        ScreenBudget.Snapshot b = budget.snapshot();
        if (b.classMin > 0) minutes.setText(String.valueOf(b.classMin));
        new AlertDialog.Builder(activity)
                .setTitle("录入今天上课时长")
                .setMessage("填今天一共上了多少分钟课（覆盖之前填的），每分钟换 0.25 分钟额度。")
                .setView(form)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int m = Integer.parseInt(minutes.getText().toString().trim());
                        if (m < 0 || m > 720) {
                            toast("填 0–720 分钟");
                            return;
                        }
                        budget.setClassMinutes(m);
                        toast("已记上：上课 " + m + " 分钟");
                    } catch (NumberFormatException e) {
                        toast("填一个整数");
                    }
                    changed();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleRest() {
        toast(budget.toggleRestTomorrow());
        changed();
    }

    private void editSecret() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(budget.secret());
        LinearLayout form = new LinearLayout(activity);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("配对密钥")
                .setMessage("和 iPad「设置」›「手机额度」里的密钥一致（10 个字符）")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    budget.setSecret(input.getText().toString());
                    toast("已保存");
                    changed();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void changed() {
        refresh();
        try {
            activity.startService(new Intent(activity, CollectorService.class)
                    .setAction(CollectorService.ACTION_BUDGET_CHANGED));
        } catch (RuntimeException ignored) {
            // 服务没在运行：通知下次采样时再更新。
        }
    }

    private EditText number(LinearLayout form, String hint) {
        EditText e = new EditText(activity);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        form.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
