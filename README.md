# 本地时间采集 Android 服务

这是 `time-tracking-full-plan.md` 中 Android 采集端的本地优先实现。

## 当前功能

- 每 30 秒记录一次前台应用包名、屏幕状态、充电状态、Wi-Fi SSID 和位置标签。
- 屏幕亮灭及充电连接/断开时额外记录事件。
- 定位会保存原始经纬度、系统报告的精度（米）、定位来源，以及“在家 / 在校 / 在食堂 / 在图书馆 / 在外 / 未配置”标签。
- SQLite 默认位于应用私有目录 `databases/time_tracker.db`。
- 可从应用界面按今天、本周、本月或全部导出 JSONL 到手机 `下载/TimeTracker/`。
- 支持家（250 米）、学校（250 米）、食堂（120 米）和图书馆（120 米）位置标签；食堂和图书馆优先于学校匹配。
- 前台服务、开机广播、Android 16 权限模型。
- 连续使用提醒默认 40 分钟，可在应用内调整为 1–240 分钟；主界面实时显示本次连续使用时长。熄屏或锁屏不足 5 分钟时暂停，恢复后继续；满 5 分钟则清零并在下次使用时重新计时。
- 提醒弹窗需要做出选择：休息 5 分钟（倒计时遮挡）、再用 10 分钟（每轮一次）、在做正事，或长按“紧急使用”；熄屏再亮屏不会绕过未选择的弹窗。同一轮继续使用时逐级升级：第 2 次提醒需先等待 60 秒才能选择，之后每次 180 秒。
- 提醒白名单：时间记录、闹钟（`com.android.deskclock`）、Google Keep、相册（Google 相册、小米相册）在前台时不弹提醒、不显示休息倒计时，深夜解锁也不立即提醒；连续使用照常计时，离开这些应用后约 1 秒内恢复弹窗或补上推迟的深夜提醒。提醒弹窗上有这几个应用的快捷入口。
- 深夜模式（默认开启，台北时间 0:00–5:00）：每次解锁手机立即提醒；0 点时仍在使用也会立即提醒一次。之后继续使用按所选项计时（“再用”缩短为 5 分钟）。每次提醒至少等待 60 秒，文案改为“该睡了”。
- 每次提醒、选择和结果都会写入数据库；主界面显示今天和近 7 天的提醒次数、各选项次数，以及提醒后 15 分钟内真正休息（熄屏满 5 分钟或完成休息倒计时）的次数。
- 数学闹钟：每天定时响铃（闹钟音量，静音模式也会响）。解锁后全屏遮挡，必须连续答对 10 道随机算术题（两位数加减、乘法口诀）才会停止响铃；答错一道换一组新题从第 1 题重来。长按“紧急使用”直接关闭闹钟，并记录为紧急关闭。响铃状态持久保存，杀进程或重启手机后会继续响；响铃期间不能停止采集服务。可用“测试闹钟（1 分钟后响）”验证。
- 闹钟事件（`alarm_fire`、`alarm_wrong`、`alarm_dismissed`、`alarm_settings`）写入数据库，附带 `alarm_id`（计划响铃时间）、响铃秒数、当前进度、累计答错次数、`alarm_emergency_count`（紧急关闭为 1）和 `alarm_detail`（关闭方式 `solved` / `emergency` / `no_overlay`，测试闹钟带 `test`；不记录具体题目）。
- 应用屏蔽：台北时间周一 0:00 至周六 0:00，打开屏蔽列表里的应用会被立即送回桌面并全屏遮挡（亮屏解锁时每秒检测，最多约 1 秒延迟）；周末不限制。默认屏蔽 Edge（`com.microsoft.emmx`），可在“编辑屏蔽列表”中增删，但工作日只能添加不能移除。哔哩哔哩官方客户端（官方版、概念版、HD 版）全天屏蔽，B 站只用 PiliPlus；PiliPlus 每天按时段屏蔽：13–15 点（午饭后）、19–22 点（晚饭后）、23–5 点（睡前），遮挡页显示这个时段的建议。这两条不受工作日和列表限制；手机浏览器里的 bilibili.com 检测不到。遮挡在送回桌面后保留，点“知道了”或熄屏才关闭。长按“紧急使用”放行 5 分钟（两条规则都放行）。事件 `app_blocked`、`app_block_emergency` 的 `app_package` 为被屏蔽的应用；`app_block_settings` 的 `app_package` 为修改后的完整列表（逗号分隔）。
- 计步：每条记录附带 `step_count`（系统计步传感器的开机以来累计步数，重启后归零；没有计步权限或传感器时为空）。用相邻记录相减即可得到任意时段的步数，例如闹钟响铃到关闭之间走了多少步。计步由低功耗协处理器完成，传感器批量上报，基本不额外耗电。
- 手机额度（只记账、不拦截，见 `ScreenBudget.java`）：除白名单外的应用都要用额度，额度用学习、上课、走路换。可用 = 结转（最多 60）+ 底额 45 + 学习分钟（每天最多算 150）+ 开始次数 × 10（最多 4 次）+ 上课分钟 × 0.25 + 屏幕关着时走的步数 / 1000 × 10（每天最多算 6000 步）；每天最多用 240 分钟；一天从早上 5 点算起。每周可以提前一天把第二天设为休息日：固定 150 分钟，不挣也不结转。学习分钟和开始次数来自 iPad LearningPack 的学习码（工具栏「状态」›「手机学习码」：分钟、次数和 6 位校验码），在主界面「输入学习码」填入，可以重复输入，取最大值；校验码是 HMAC-SHA256(配对密钥, "日期|分钟|次数") 前 4 字节去掉最高位模 1,000,000，和 LearningPack 的 `StudyCode` 一致，配对密钥在 iPad「设置」›「手机额度」。上课时长在「录入今天上课」手动填。白名单（`ScreenBudget.FREE_APPS`）：系统应用（`com.android.`、`com.miui.` 等前缀，Chrome、小米浏览器、小米视频、小米音乐除外）、地图、邮件、微信和 QQ、生活支付和银行、出行、学校办公和学习工具（飞书、WPS、Anki、Google 文档等）、LearningPack 手机复习（在上面学的时间同步到 iPad，算进学习码）、subword / Readest / epub 阅读器、YouTube（目前只用来学英语）、Apple Music、文件选择器和输入法等系统组件、终端 / SSH / 传文件 / 代理等工具、时间记录本身；其余应用（Chrome、PiliPlus、Nekogram、游戏、ChatGPT……）都计费。余额用完后继续用的时间记为超出。常驻通知和主界面最上面显示余额、来源和今天各计费应用的用时（列前 4 个，其余合成「其他」，各项加起来等于已用）。后来加进白名单的应用，当天已经记的用时在 App 启动时退回来（事件 `budget_refund`）。原有的屏蔽、连续使用提醒、深夜模式照旧。
- 后台恢复需要“始终允许”定位；若权限缺失，服务会安全降级并继续采集非定位信号，不会崩溃。
- Manifest 未申请 `INTERNET` 权限，当前版本不能上传数据，但导出的 JSONL 包含完整位置历史，应妥善保管。

## 从 Mac 导出 Excel

双击 `export.command`，或在终端运行：

```bash
cd /Users/wind/vscode/time-tracker-android
./export.command
```

脚本通过无线 ADB 请求应用安全查询 SQLite，再使用 LibreOffice 生成格式化的 Excel 工作簿。默认输出目录为 `/Users/wind/vscode/时间记录分析/Android`。启动后可选择今天、本周、本月或所有记录；直接回车默认导出今天。

ADB 设备默认使用 `auto`：依次复用已连接设备、查询 ADB mDNS 服务，再尝试室内 Wi-Fi 与手机热点的已知地址。切换网络后通常不需要修改命令。失效地址使用短超时，不会等待约 30 秒。

非交互调用：

```bash
./export.command --period day
./export.command --period week
./export.command --period month
./export.command --period all
./export.command --from "2026-08-27 18:00:00" --to "2026-08-28 08:00:00"
```

如自动发现失败，可显式指定地址，或提供多个候选地址：

```bash
./export.command --serial 192.168.80.148:5555 --period day
TIME_TRACKER_ADB_SERIALS="192.168.80.148:5555,10.149.84.166:5555" ./export.command --period day
```

开始时间包含在结果中，结束时间不包含；所有未带时区的输入均按 UTC+8 解释。可用 `--output` 指定文件，已有文件默认不会覆盖，添加 `--force` 后旧文件会移到 macOS 废纸篓。

## 手机端首次设置

1. 授予通知、精确定位和“身体活动”（计步）权限。
2. 打开后台定位设置，将位置信息设为“始终允许”。
3. 在“使用情况访问”中允许“本地时间采集”。
4. 点击“启动采集服务”。
5. 可选：在家和学校分别点击对应的当前位置按钮。
6. Xiaomi/HyperOS 建议将应用电池策略设为“不限制”，并允许自启动。

## 构建

```shell
./gradlew assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 事件表

`raw_events` 字段：

- `timestamp_ms`
- `event_type`
- `app_package`
- `screen_on`
- `charging`
- `wifi_ssid`
- `location_label`
- `latitude`
- `longitude`
- `accuracy_m`
- `location_provider`
- `pomodoro_phase`
- `pomodoro_remaining_sec`
- `pomodoro_cycle`
- `continuous_usage_sec`：该条事件产生时，本轮已连续使用的秒数。
- `usage_reminder_limit_min`：该条事件产生时配置的提醒阈值（分钟）。
- `continuous_usage_state`：`active`、`inactive_grace` 或 `idle`。
- `usage_alert_level`：提醒相关事件所在的本轮提醒级别（第几次提醒）。
- `usage_alert_response`：`event_type=usage_alert_response` 时的选择或结果：`rest`、`snooze`、`work`、`emergency`、`rest_completed`、`break_after_alert`。

提醒事件类型：`continuous_use_alert`（普通）、`continuous_use_night_alert`（深夜模式）、`usage_alert_response`（选择与结果）。

## 应用内行为表（PiliPlus）

自编译的 PiliPlus（`/Users/wind/vscode/PiliPlus`，见其中 `lib/services/usage_log.dart`）通过 `content://com.wind.timetracker.events` 上报应用内行为，存入 `app_events` 表：`timestamp_ms`、`source_package`、`event_type`、`payload`（JSON）。接口只接受包名 `com.example.piliplus` 的调用。

主要事件：`page_enter` / `page_leave`（页面与参数，如 `bvid`、搜索词）、`nav_tab` / `home_tab`、`list_refresh` / `list_load_more`（列表类名与页码）、`video_info`（标题、UP 主、时长）、`player_playing` / `player_paused` / `player_completed`（播放位置）、`video_end`（每个视频的打开时长 `opened_sec`、实际播放 `played_sec`、看到的最远位置 `max_position_sec`，以及结束原因 `leave` / `switch`）、`app_foreground` / `app_background`。

导出（payload 字段展开到每行）：

```bash
adb shell content call --uri content://com.wind.timetracker.export --method export_app_events \
  --arg day --extra start_ms:l:<开始毫秒> --extra end_ms:l:<结束毫秒>
```

文件在手机 `下载/TimeTracker/time-tracker-app-events-*.jsonl`。

手机额度的事件也写在 `app_events`，`source_package` 为 `budget`：`budget_code`（输入学习码：`minutes`、`starts`、`valid`）、`budget_class`（上课分钟）、`budget_rest_day`（设或取消休息日）、`budget_secret`、`budget_exhausted`（当天第一次用完，附当时的汇总）、`budget_over`（一段超出：应用、秒数）、`budget_day`（换天时前一天的汇总：可用、已用、超出、各来源、各应用分钟）。

下一阶段可在 Mac 端读取导出的 JSONL，执行分类、时间块合并和每日回顾。
