package com.wind.timetracker;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.Random;

/**
 * 数学闹钟：响铃 + 全屏遮挡，连续答对 {@link AlarmScheduler#QUESTION_COUNT} 道算术题才能关闭，
 * 答错一道就换一组新题从头开始。所有方法都在主线程调用；状态持久化在 SharedPreferences。
 */
final class MathAlarm {
    static final String CHANNEL_ID = "math_alarm";
    static final int NOTIFICATION_ID = 1004;

    static final String EVENT_FIRE = "alarm_fire";
    static final String EVENT_WRONG = "alarm_wrong";
    static final String EVENT_DISMISSED = "alarm_dismissed";
    static final String EVENT_SETTINGS = "alarm_settings";
    static final String DETAIL_SOLVED = "solved";
    static final String DETAIL_EMERGENCY = "emergency";
    static final String DETAIL_NO_OVERLAY = "no_overlay";

    /** 由服务把事件写入数据库（在后台线程执行）。 */
    interface Recorder {
        void record(String eventType, DatabaseHelper.AlarmFields fields);
    }

    private final Context context;
    private final Recorder recorder;
    private final Random random = new Random();

    private MediaPlayer player;
    private Vibrator vibrator;
    private AudioFocusRequest focusRequest;

    private View overlayView;
    private TextView progressView;
    private TextView questionView;
    private TextView answerView;
    private TextView feedbackView;
    private final int[][] questions = new int[AlarmScheduler.QUESTION_COUNT][3];  // a, op, b
    private int questionIndex;
    private final StringBuilder answer = new StringBuilder();

    MathAlarm(Context context, Recorder recorder) {
        this.context = context;
        this.recorder = recorder;
    }

    // ---- 状态切换 ----

    void fire(long alarmId, boolean test) {
        SharedPreferences prefs = AlarmScheduler.prefs(context);
        if (prefs.getBoolean(AlarmScheduler.KEY_RINGING, false)) return;  // 上一次还没关闭
        prefs.edit()
                .putBoolean(AlarmScheduler.KEY_RINGING, true)
                .putLong(AlarmScheduler.KEY_ALARM_ID, alarmId)
                .putLong(AlarmScheduler.KEY_RING_START_MS, System.currentTimeMillis())
                .putInt(AlarmScheduler.KEY_WRONG_TOTAL, 0)
                .putBoolean(AlarmScheduler.KEY_TEST, test)
                .commit();
        record(EVENT_FIRE, null);  // 测试闹钟会自动带上 "test"
        beginRinging();
    }

    /** 周期性调用及亮屏/解锁时调用：保证响铃状态与持久化状态一致。 */
    void refresh() {
        SharedPreferences prefs = AlarmScheduler.prefs(context);
        if (!prefs.getBoolean(AlarmScheduler.KEY_RINGING, false)) {
            if (player != null || overlayView != null) release();
            return;
        }
        if (player == null) startSoundAndVibration();
        if (overlayView == null) {
            showNotification();
            showOverlay();
        }
    }

    /** 没有悬浮窗权限时无法答题，只能从通知停止。 */
    void dismissWithoutOverlay() {
        if (Settings.canDrawOverlays(context)) return;
        if (!AlarmScheduler.isRinging(context)) return;
        dismiss(DETAIL_NO_OVERLAY);
    }

    /** 服务销毁时只停止声音和界面，保留响铃状态，服务重启后继续响。 */
    void release() {
        stopSoundAndVibration();
        removeOverlay();
        if (!AlarmScheduler.isRinging(context)) {
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
    }

    private void beginRinging() {
        startSoundAndVibration();
        showNotification();
        showOverlay();
    }

    private void dismiss(String detail) {
        record(EVENT_DISMISSED, detail);
        AlarmScheduler.prefs(context).edit().putBoolean(AlarmScheduler.KEY_RINGING, false).commit();
        stopSoundAndVibration();
        removeOverlay();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private void record(String eventType, String detail) {
        SharedPreferences prefs = AlarmScheduler.prefs(context);
        DatabaseHelper.AlarmFields fields = new DatabaseHelper.AlarmFields();
        fields.alarmId = prefs.getLong(AlarmScheduler.KEY_ALARM_ID, 0);
        long start = prefs.getLong(AlarmScheduler.KEY_RING_START_MS, 0);
        fields.ringSec = start > 0 ? Math.max(0, (System.currentTimeMillis() - start) / 1000L) : null;
        fields.progress = overlayView == null ? null : questionIndex;
        fields.wrongCount = prefs.getInt(AlarmScheduler.KEY_WRONG_TOTAL, 0);
        fields.emergencyCount = DETAIL_EMERGENCY.equals(detail) ? 1 : 0;
        String test = prefs.getBoolean(AlarmScheduler.KEY_TEST, false) ? "test" : null;
        fields.detail = detail == null ? test : test == null ? detail : test + "; " + detail;
        recorder.record(eventType, fields);
    }

    // ---- 声音、振动、通知 ----

    private void startSoundAndVibration() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (player == null) {
            AudioManager audio = context.getSystemService(AudioManager.class);
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).build();
            audio.requestAudioFocus(focusRequest);
            player = createPlayer(attributes);
        }
        if (vibrator == null) {
            vibrator = Build.VERSION.SDK_INT >= 31
                    ? context.getSystemService(VibratorManager.class).getDefaultVibrator()
                    : context.getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrateWithAttributes(vibrator, attributes);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrateWithAttributes(Vibrator vibrator, AudioAttributes attributes) {
        vibrator.vibrate(VibrationEffect.createWaveform(new long[] { 0, 800, 600 }, 0), attributes);
    }

    private MediaPlayer createPlayer(AudioAttributes attributes) {
        int[] types = { RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE,
                RingtoneManager.TYPE_NOTIFICATION };
        for (int type : types) {
            Uri uri = RingtoneManager.getDefaultUri(type);
            if (uri == null) continue;
            MediaPlayer candidate = new MediaPlayer();
            try {
                candidate.setAudioAttributes(attributes);
                candidate.setDataSource(context, uri);
                candidate.setLooping(true);
                candidate.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
                candidate.prepare();
                candidate.start();
                return candidate;
            } catch (Exception error) {
                candidate.release();
            }
        }
        return null;  // 只剩振动；下次 refresh 会再尝试播放
    }

    private void stopSoundAndVibration() {
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) { }
            player.release();
            player = null;
        }
        if (focusRequest != null) {
            context.getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private void showNotification() {
        String text;
        if (Settings.canDrawOverlays(context)) {
            text = "解锁后连续答对 " + AlarmScheduler.QUESTION_COUNT + " 道算术题才能关闭";
        } else {
            text = "未授予悬浮窗权限，无法答题，可从通知停止";
        }
        PendingIntent open = PendingIntent.getActivity(context, 20,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tracker)
                .setContentTitle("闹钟 " + AlarmScheduler.formatTime(context))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (!Settings.canDrawOverlays(context)) {
            Intent stop = new Intent(context, CollectorService.class)
                    .setAction(CollectorService.ACTION_ALARM_STOP_NO_OVERLAY);
            builder.addAction(new Notification.Action.Builder(null, "停止闹钟",
                    PendingIntent.getService(context, 21, stop,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, builder.build());
    }

    // ---- 答题遮挡层 ----

    private void showOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(context)) return;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(24, 26, 33));
        int light = Color.rgb(235, 235, 240);
        int dim = Color.rgb(170, 172, 182);

        boolean test = AlarmScheduler.prefs(context).getBoolean(AlarmScheduler.KEY_TEST, false);
        TextView title = text(test ? "闹钟测试" : "起床了 · " + AlarmScheduler.formatTime(context), 30, light);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, wrap());
        root.addView(text("连续答对 " + AlarmScheduler.QUESTION_COUNT + " 道题关闭闹钟，答错一道从头再来",
                15, dim), wrap());

        progressView = text("", 18, dim);
        progressView.setPadding(0, dp(20), 0, 0);
        root.addView(progressView, wrap());
        questionView = text("", 44, light);
        questionView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(questionView, wrap());
        answerView = text("", 40, Color.rgb(140, 200, 255));
        answerView.setMinHeight(dp(56));
        root.addView(answerView, wrap());
        feedbackView = text("", 16, Color.rgb(255, 120, 110));
        feedbackView.setMinHeight(dp(24));
        root.addView(feedbackView, wrap());

        GridLayout keypad = new GridLayout(context);
        keypad.setColumnCount(3);
        String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确定" };
        for (String key : keys) {
            Button button = new Button(context);
            button.setText(key);
            button.setTextSize(24);
            button.setAllCaps(false);
            button.setOnClickListener(v -> onKey(key));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = dp(64);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            keypad.addView(button, params);
        }
        LinearLayout.LayoutParams keypadParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keypadParams.topMargin = dp(8);
        root.addView(keypad, keypadParams);

        Button emergency = new Button(context);
        emergency.setText("紧急使用（长按直接关闭闹钟，会被记录）");
        emergency.setTextSize(13);
        emergency.setAllCaps(false);
        emergency.setAlpha(0.7f);
        emergency.setOnClickListener(v -> Toast.makeText(context, "请长按", Toast.LENGTH_SHORT).show());
        emergency.setOnLongClickListener(v -> {
            v.setEnabled(false);
            dismiss(DETAIL_EMERGENCY);
            return true;
        });
        LinearLayout.LayoutParams emergencyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        emergencyParams.topMargin = dp(20);
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
            newRound(null);
        } catch (RuntimeException error) {
            overlayView = null;
        }
    }

    private void removeOverlay() {
        View view = overlayView;
        overlayView = null;
        progressView = null;
        questionView = null;
        answerView = null;
        feedbackView = null;
        if (view == null) return;
        try {
            context.getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) { }
    }

    private void onKey(String key) {
        if (overlayView == null) return;
        switch (key) {
            case "删除":
                if (answer.length() > 0) answer.setLength(answer.length() - 1);
                break;
            case "确定":
                submit();
                return;
            default:
                if (answer.length() < 4) answer.append(key);
        }
        answerView.setText(answer);
    }

    private void submit() {
        if (answer.length() == 0) return;
        int[] q = questions[questionIndex];
        int expected = evaluate(q);
        int given = Integer.parseInt(answer.toString());
        if (given == expected) {
            questionIndex++;
            if (questionIndex >= AlarmScheduler.QUESTION_COUNT) {
                dismiss(DETAIL_SOLVED);
                return;
            }
            feedbackView.setText("");
            showQuestion();
            return;
        }
        SharedPreferences prefs = AlarmScheduler.prefs(context);
        prefs.edit().putInt(AlarmScheduler.KEY_WRONG_TOTAL,
                prefs.getInt(AlarmScheduler.KEY_WRONG_TOTAL, 0) + 1).commit();
        record(EVENT_WRONG, null);
        newRound("答错了：" + describe(q) + " = " + expected + "，换一组新题从第 1 题开始");
    }

    private void newRound(String feedback) {
        for (int[] q : questions) generate(q);
        questionIndex = 0;
        if (feedbackView != null) feedbackView.setText(feedback == null ? "" : feedback);
        showQuestion();
    }

    private void showQuestion() {
        answer.setLength(0);
        progressView.setText("第 " + (questionIndex + 1) + " / " + AlarmScheduler.QUESTION_COUNT + " 题");
        questionView.setText(describe(questions[questionIndex]) + " = ?");
        answerView.setText("");
    }

    /** 两位数加减法或 2–9 × 2–12 的乘法，结果都是非负整数。 */
    private void generate(int[] q) {
        int op = random.nextInt(3);
        int a;
        int b;
        if (op == 0) {
            a = 10 + random.nextInt(80);
            b = 10 + random.nextInt(80);
        } else if (op == 1) {
            a = 20 + random.nextInt(80);
            b = 10 + random.nextInt(a - 9);
        } else {
            a = 2 + random.nextInt(8);
            b = 2 + random.nextInt(11);
        }
        q[0] = a;
        q[1] = op;
        q[2] = b;
    }

    private static int evaluate(int[] q) {
        return q[1] == 0 ? q[0] + q[2] : q[1] == 1 ? q[0] - q[2] : q[0] * q[2];
    }

    private static String describe(int[] q) {
        String op = q[1] == 0 ? "+" : q[1] == 1 ? "−" : "×";
        return q[0] + " " + op + " " + q[2];
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
