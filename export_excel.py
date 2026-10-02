#!/usr/bin/env python3
"""Export an Android Time Tracker range over ADB to a formatted XLSX workbook."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
from html import escape
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_OUTPUT_DIR = Path("/Users/wind/vscode/时间记录分析/Android")
TEMP_ROOT = Path("/Users/wind/temp")
DEFAULT_SERIAL = "auto"
KNOWN_SERIALS = (
    "192.168.80.148:5555",  # 当前室内 Wi-Fi
    "10.149.84.166:5555",   # 手机热点
)
SERIALS_ENV = "TIME_TRACKER_ADB_SERIALS"
AUTHORITY = "content://com.wind.timetracker.export"
REMOTE_DIR = "/sdcard/Download/TimeTracker"
UTC_PLUS_8 = timezone(timedelta(hours=8), name="UTC+8")

PERIOD_LABELS = {
    "day": "一天（今天）",
    "yesterday": "一天（昨天）",
    "week": "一周（本周）",
    "month": "一月（本月）",
    "all": "所有记录",
}

COLUMNS = [
    "local_time",
    "timestamp_ms",
    "event_type",
    "app_package",
    "screen_on",
    "charging",
    "wifi_ssid",
    "location_label",
    "latitude",
    "longitude",
    "accuracy_m",
    "location_provider",
    "pomodoro_phase",
    "pomodoro_remaining_sec",
    "pomodoro_cycle",
    "continuous_usage_sec",
    "usage_reminder_limit_min",
    "continuous_usage_state",
    "usage_alert_level",
    "usage_alert_response",
]

WIDTHS = {
    "local_time": 1.55,
    "timestamp_ms": 1.45,
    "event_type": 1.15,
    "app_package": 2.25,
    "screen_on": 1.05,
    "charging": 1.05,
    "wifi_ssid": 1.55,
    "location_label": 1.35,
    "latitude": 1.25,
    "longitude": 1.25,
    "accuracy_m": 1.15,
    "location_provider": 1.35,
    "pomodoro_phase": 1.35,
    "pomodoro_remaining_sec": 1.65,
    "pomodoro_cycle": 1.25,
    "continuous_usage_sec": 1.65,
    "usage_reminder_limit_min": 1.85,
    "continuous_usage_state": 1.65,
    "usage_alert_level": 1.35,
    "usage_alert_response": 1.65,
}


def parse_datetime(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"无效时间 {value!r}；请使用 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS"
        ) from exc
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC_PLUS_8)
    return parsed.astimezone(UTC_PLUS_8)


def default_output_path() -> Path:
    stamp = datetime.now(UTC_PLUS_8).strftime("%Y%m%d_%H%M%S")
    return DEFAULT_OUTPUT_DIR / f"time_tracking_android_{stamp}.xlsx"


def period_bounds(period: str, now: datetime | None = None) -> tuple[datetime | None, datetime | None]:
    current = (now or datetime.now(UTC_PLUS_8)).astimezone(UTC_PLUS_8)
    today = current.replace(hour=0, minute=0, second=0, microsecond=0)
    if period == "day":
        return today, today + timedelta(days=1)
    if period == "yesterday":
        return today - timedelta(days=1), today
    if period == "week":
        start = today - timedelta(days=today.weekday())
        return start, start + timedelta(days=7)
    if period == "month":
        start = today.replace(day=1)
        if start.month == 12:
            return start, start.replace(year=start.year + 1, month=1)
        return start, start.replace(month=start.month + 1)
    if period == "all":
        return None, None
    raise ValueError(f"未知导出范围：{period}")


def prompt_period() -> str:
    print("请选择导出范围（时间边界均为 UTC+8 的 00:00）：")
    print("  1. 一天（今天，默认）")
    print("  2. 一天（昨天）")
    print("  3. 一周（本周一至下周一）")
    print("  4. 一月（本月 1 日至下月 1 日）")
    print("  5. 所有记录")
    choices = {"": "day", "1": "day", "2": "yesterday", "3": "week", "4": "month", "5": "all"}
    while True:
        try:
            choice = input("请输入 1-5，直接回车选择一天：").strip()
        except EOFError:
            choice = ""
        if choice in choices:
            return choices[choice]
        print("无效选项，请输入 1、2、3、4 或 5。")


def describe_period(period: str, start: datetime | None, end: datetime | None) -> str:
    if period == "all":
        return "所有记录"
    return f"{PERIOD_LABELS[period]}：{start:%Y-%m-%d %H:%M:%S}（含）至 {end:%Y-%m-%d %H:%M:%S}（不含），UTC+8"


def find_adb() -> str:
    candidates = [shutil.which("adb"), "/opt/homebrew/bin/adb"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise SystemExit("找不到 adb；请先通过 Homebrew 安装 android-platform-tools")


def run(command: list[str], *, capture: bool = True,
        timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command, check=True, text=True, capture_output=capture, timeout=timeout
        )
    except subprocess.TimeoutExpired as error:
        raise SystemExit(f"命令执行超时：{' '.join(command)}") from error
    except subprocess.CalledProcessError as error:
        details = (error.stderr or error.stdout or str(error)).strip()
        raise SystemExit(f"命令执行失败：{' '.join(command)}\n{details}") from error


def try_run(command: list[str], timeout: float = 5) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            command, check=False, text=True, capture_output=True, timeout=timeout
        )
    except subprocess.TimeoutExpired:
        return None


def add_candidate(candidates: list[str], serial: str) -> None:
    serial = serial.strip()
    if serial and serial not in candidates:
        candidates.append(serial)


def connected_devices(adb: str) -> list[str]:
    result = try_run([adb, "devices", "-l"])
    if result is None or result.returncode != 0:
        return []
    devices = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            add_candidate(devices, fields[0])
    return devices


def mdns_devices(adb: str) -> list[str]:
    """发现 Android 11+「无线调试」广播的 ADB 设备。

    `adb mdns services` 输出形如：
        List of discovered mdns services
            Pixel 7._adb-tls-connect._tcp   Pixel 7   local
            endpoint: 192.168.1.23:38741
    设备名和 IP:端口 通常不在同一行，需解析每个 _adb 服务名
    之后跟随的 endpoint 行，才能拿到动态分配的端口。
    """
    devices = []
    # adb server 刚启动时 mDNS 发现需要约 1-2 秒才有结果，冷启动时重试几次
    stdout = ""
    for attempt in range(4):
        if attempt:
            time.sleep(1.5)
        result = try_run([adb, "mdns", "services"])
        if result is None or result.returncode != 0:
            break
        stdout = result.stdout
        if any("_adb" in line for line in stdout.splitlines()):
            break
    endpoint_pattern = re.compile(r"(?:\d{1,3}\.){3}\d{1,3}:\d+")
    adb_service_seen = False
    for line in stdout.splitlines():
        text = line.strip()
        if "_adb-tls-pairing" in text:
            # 配对端口是一次性的，不能当作可连接的设备
            adb_service_seen = False
            continue
        if "_adb" in text:
            adb_service_seen = True
            match = endpoint_pattern.search(text)
            if match:
                add_candidate(devices, match.group(0))
        elif adb_service_seen and (
            "endpoint" in text.lower() or endpoint_pattern.search(text)
        ):
            match = endpoint_pattern.search(text)
            if match:
                add_candidate(devices, match.group(0))
            adb_service_seen = False
        elif text and not text.lower().startswith("list"):
            # 非 _adb 服务的新条目，结束当前 _adb 服务块
            adb_service_seen = False
    return devices


LAST_SERIAL_FILE = Path.home() / ".cache" / "time-tracker" / "last_adb_serial"


def remember_serial(serial: str) -> None:
    """记录最近几次成功连接的设备（多行，新的在前），供下次 auto 优先尝试。"""
    try:
        known = last_known_serials()
        known = [serial] + [item for item in known if item != serial]
        LAST_SERIAL_FILE.parent.mkdir(parents=True, exist_ok=True)
        LAST_SERIAL_FILE.write_text("\n".join(known[:5]) + "\n", encoding="utf-8")
    except OSError:
        pass


def last_known_serials() -> list[str]:
    try:
        content = LAST_SERIAL_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return []
    return [line.strip() for line in content.splitlines() if line.strip()]


def candidate_devices(adb: str) -> list[str]:
    candidates: list[str] = []
    for serial in connected_devices(adb):
        add_candidate(candidates, serial)
    for serial in last_known_serials():
        add_candidate(candidates, serial)
    for serial in mdns_devices(adb):
        add_candidate(candidates, serial)
    for serial in re.split(r"[,;\s]+", os.environ.get(SERIALS_ENV, "")):
        add_candidate(candidates, serial)
    for serial in KNOWN_SERIALS:
        add_candidate(candidates, serial)
    return candidates


def device_has_app(adb: str, serial: str) -> bool:
    if ":" in serial:
        connected = try_run([adb, "connect", serial], timeout=4)
        if connected is None or connected.returncode != 0:
            return False
    state = try_run([adb, "-s", serial, "get-state"], timeout=3)
    if state is None or state.returncode != 0 or state.stdout.strip() != "device":
        return False
    package = try_run(
        [adb, "-s", serial, "shell", "pm", "path", "com.wind.timetracker"], timeout=5
    )
    return bool(package and package.returncode == 0 and "package:" in package.stdout)


def ensure_device(adb: str, requested_serial: str) -> str:
    automatic = requested_serial.strip().lower() in {"", "auto"}
    candidates = candidate_devices(adb) if automatic else [requested_serial]
    for serial in candidates:
        if device_has_app(adb, serial):
            print(f"使用 ADB 设备：{serial}")
            remember_serial(serial)
            return serial
    if automatic:
        tried = "、".join(candidates) or "无可用候选地址"
        raise SystemExit(
            "未找到安装了时间采集器的 ADB 设备。\n"
            f"已尝试：{tried}\n"
            "请确认：\n"
            "  1. 手机已开启「开发者选项 → 无线调试」（auto 模式会通过 mDNS 自动发现新 IP 和动态端口）；\n"
            "  2. 或先用 USB 连接一次，再运行 adb tcpip 5555 开启固定端口调试；\n"
            "  3. 或用 --serial IP:端口 直接指定，或设置 " + SERIALS_ENV + "。"
        )
    raise SystemExit(f"ADB 设备不可用或未安装时间采集器：{requested_serial}")


def epoch_ms(value: datetime | None, default: int) -> int:
    return default if value is None else int(value.timestamp() * 1000)


def request_jsonl(adb: str, serial: str, start: datetime | None, end: datetime | None,
                  destination: Path) -> int:
    start_ms = epoch_ms(start, 0)
    end_ms = epoch_ms(end, 9_223_372_036_854_775_807)
    label = "adb-" + datetime.now(UTC_PLUS_8).strftime("%Y%m%d-%H%M%S")
    result = run([
        adb, "-s", serial, "shell", "content", "call",
        "--uri", AUTHORITY,
        "--method", "export",
        "--arg", label,
        "--extra", f"start_ms:l:{start_ms}",
        "--extra", f"end_ms:l:{end_ms}",
    ])
    match = re.search(r"(time-tracker-[A-Za-z0-9_-]+-\d{8}-\d{6}\.jsonl)", result.stdout)
    if not match:
        raise SystemExit(f"手机未返回导出文件名：\n{result.stdout.strip()}")
    remote_path = f"{REMOTE_DIR}/{match.group(1)}"
    run([adb, "-s", serial, "pull", remote_path, str(destination)], capture=False)
    return sum(1 for line in destination.open("r", encoding="utf-8") if line.strip())


def xml_text(value: object) -> str:
    return escape(str(value), quote=True)


def cell_string(value: object, style: str = "Body") -> str:
    if value is None:
        return "<table:table-cell/>"
    text = xml_text(value)
    return f'<table:table-cell table:style-name="{style}" office:value-type="string"><text:p>{text}</text:p></table:table-cell>'


def cell_number(value: object, style: str) -> str:
    if value is None:
        return "<table:table-cell/>"
    return f'<table:table-cell table:style-name="{style}" office:value-type="float" office:value="{value}"><text:p>{value}</text:p></table:table-cell>'


def cell_boolean(value: object) -> str:
    if value is None:
        return "<table:table-cell/>"
    boolean = bool(value)
    word = "TRUE" if boolean else "FALSE"
    raw = "true" if boolean else "false"
    return f'<table:table-cell table:style-name="Boolean" office:value-type="boolean" office:boolean-value="{raw}"><text:p>{word}</text:p></table:table-cell>'


def cell_date(timestamp_ms: int) -> str:
    local = datetime.fromtimestamp(timestamp_ms / 1000, UTC_PLUS_8)
    iso = local.replace(tzinfo=None).isoformat(timespec="seconds")
    display = local.strftime("%Y-%m-%d %H:%M:%S")
    return f'<table:table-cell table:style-name="DateCell" office:value-type="date" office:date-value="{iso}"><text:p>{display}</text:p></table:table-cell>'


def write_fods(jsonl_path: Path, fods_path: Path, expected_rows: int) -> int:
    column_styles = "".join(
        f'<style:style style:name="Col{index}" style:family="table-column"><style:table-column-properties style:column-width="{WIDTHS[name]}in"/></style:style>'
        for index, name in enumerate(COLUMNS, start=1)
    )
    with fods_path.open("w", encoding="utf-8") as output:
        output.write(f'''<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" office:mimetype="application/vnd.oasis.opendocument.spreadsheet" office:version="1.3">
<office:styles/>
<office:automatic-styles>
  <number:date-style style:name="DateFormat"><number:year number:style="long"/><number:text>-</number:text><number:month number:style="long"/><number:text>-</number:text><number:day number:style="long"/><number:text> </number:text><number:hours number:style="long"/><number:text>:</number:text><number:minutes number:style="long"/><number:text>:</number:text><number:seconds number:style="long"/></number:date-style>
  <number:number-style style:name="IntegerFormat"><number:number number:decimal-places="0" number:min-integer-digits="1" number:grouping="false"/></number:number-style>
  <number:number-style style:name="CoordinateFormat"><number:number number:decimal-places="6" number:min-decimal-places="6" number:min-integer-digits="1"/></number:number-style>
  <number:number-style style:name="AccuracyFormat"><number:number number:decimal-places="1" number:min-decimal-places="1" number:min-integer-digits="1"/></number:number-style>
  {column_styles}
  <style:style style:name="Header" style:family="table-cell"><style:table-cell-properties fo:background-color="#1F4E78" fo:padding="0.06in"/><style:text-properties fo:color="#FFFFFF" fo:font-weight="bold"/></style:style>
  <style:style style:name="Body" style:family="table-cell"><style:table-cell-properties fo:padding="0.04in"/><style:text-properties fo:font-size="10pt"/></style:style>
  <style:style style:name="DateCell" style:family="table-cell" style:data-style-name="DateFormat"><style:table-cell-properties fo:padding="0.04in"/></style:style>
  <style:style style:name="Integer" style:family="table-cell" style:data-style-name="IntegerFormat"><style:table-cell-properties fo:padding="0.04in"/></style:style>
  <style:style style:name="Coordinate" style:family="table-cell" style:data-style-name="CoordinateFormat"><style:table-cell-properties fo:padding="0.04in"/></style:style>
  <style:style style:name="Accuracy" style:family="table-cell" style:data-style-name="AccuracyFormat"><style:table-cell-properties fo:padding="0.04in"/></style:style>
  <style:style style:name="Boolean" style:family="table-cell"><style:table-cell-properties fo:padding="0.04in"/></style:style>
  <style:style style:name="TableStyle" style:family="table" style:master-page-name="PageStyle"><style:table-properties table:display="true" style:writing-mode="lr-tb"/></style:style>
  <style:page-layout style:name="PageLayout"><style:page-layout-properties fo:page-width="11.69in" fo:page-height="8.27in" style:print-orientation="landscape" fo:margin-top="0.35in" fo:margin-bottom="0.35in" fo:margin-left="0.35in" fo:margin-right="0.35in" style:print-page-order="ttb" style:scale-to-X="1" style:scale-to-Y="0"/></style:page-layout>
</office:automatic-styles>
<office:master-styles><style:master-page style:name="PageStyle" style:page-layout-name="PageLayout"/></office:master-styles>
<office:body><office:spreadsheet><table:table table:name="raw_events" table:style-name="TableStyle">
''')
        for index in range(1, len(COLUMNS) + 1):
            output.write(f'<table:table-column table:style-name="Col{index}"/>')
        output.write("<table:table-header-rows><table:table-row>")
        for name in COLUMNS:
            output.write(cell_string(name, "Header"))
        output.write("</table:table-row></table:table-header-rows>\n")

        rows = 0
        with jsonl_path.open("r", encoding="utf-8") as source:
            for line_number, line in enumerate(source, start=1):
                if not line.strip():
                    continue
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as error:
                    raise SystemExit(f"JSONL 第 {line_number} 行无效：{error}") from error
                timestamp_ms = int(item["timestamp_ms"])
                output.write("<table:table-row>")
                output.write(cell_date(timestamp_ms))
                output.write(cell_string(str(timestamp_ms)))
                output.write(cell_string(item.get("event_type")))
                output.write(cell_string(item.get("app_package")))
                output.write(cell_boolean(item.get("screen_on")))
                output.write(cell_boolean(item.get("charging")))
                output.write(cell_string(item.get("wifi_ssid")))
                output.write(cell_string(item.get("location_label")))
                output.write(cell_number(item.get("latitude"), "Coordinate"))
                output.write(cell_number(item.get("longitude"), "Coordinate"))
                output.write(cell_number(item.get("accuracy_m"), "Accuracy"))
                output.write(cell_string(item.get("location_provider")))
                output.write(cell_string(item.get("pomodoro_phase")))
                output.write(cell_number(item.get("pomodoro_remaining_sec"), "Integer"))
                output.write(cell_number(item.get("pomodoro_cycle"), "Integer"))
                output.write(cell_number(item.get("continuous_usage_sec"), "Integer"))
                output.write(cell_number(item.get("usage_reminder_limit_min"), "Integer"))
                output.write(cell_string(item.get("continuous_usage_state")))
                output.write(cell_number(item.get("usage_alert_level"), "Integer"))
                output.write(cell_string(item.get("usage_alert_response")))
                output.write("</table:table-row>\n")
                rows += 1

        last_row = rows + 1
        last_column = chr(ord("A") + len(COLUMNS) - 1)
        output.write(f'''</table:table>
<table:database-ranges><table:database-range table:name="__Anonymous_Sheet_DB__0" table:target-range-address="raw_events.A1:raw_events.{last_column}{last_row}" table:display-filter-buttons="true"/></table:database-ranges>
</office:spreadsheet></office:body></office:document>''')
    if rows != expected_rows:
        raise SystemExit(f"记录数校验失败：ADB={expected_rows}，工作簿={rows}")
    return rows


def trash(path: Path) -> None:
    quoted = json.dumps(str(path), ensure_ascii=False)
    script = f'tell application "Finder" to delete POSIX file {quoted}'
    run(["osascript", "-e", script])


def patch_xlsx(source: Path, destination: Path) -> None:
    namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    ET.register_namespace("", namespace)
    with zipfile.ZipFile(source, "r") as source_zip, zipfile.ZipFile(
        destination, "w", compression=zipfile.ZIP_DEFLATED
    ) as destination_zip:
        for item in source_zip.infolist():
            data = source_zip.read(item.filename)
            if item.filename == "xl/worksheets/sheet1.xml":
                root = ET.fromstring(data)
                sheet_view = root.find(f"{{{namespace}}}sheetViews/{{{namespace}}}sheetView")
                if sheet_view is None:
                    raise SystemExit("XLSX 缺少工作表视图")
                for existing in list(sheet_view.findall(f"{{{namespace}}}pane")):
                    sheet_view.remove(existing)
                pane = ET.Element(f"{{{namespace}}}pane", {
                    "ySplit": "1",
                    "topLeftCell": "A2",
                    "activePane": "bottomLeft",
                    "state": "frozen",
                })
                sheet_view.insert(0, pane)

                dimension = root.find(f"{{{namespace}}}dimension")
                table_range = dimension.get("ref") if dimension is not None else None
                if not table_range:
                    raise SystemExit("XLSX 缺少有效数据范围")
                auto_filter = root.find(f"{{{namespace}}}autoFilter")
                if auto_filter is None:
                    sheet_data = root.find(f"{{{namespace}}}sheetData")
                    if sheet_data is None:
                        raise SystemExit("XLSX 缺少工作表数据")
                    auto_filter = ET.Element(f"{{{namespace}}}autoFilter")
                    root.insert(list(root).index(sheet_data) + 1, auto_filter)
                auto_filter.set("ref", table_range)
                data = ET.tostring(root, encoding="utf-8", xml_declaration=True)
            destination_zip.writestr(item, data)


def convert_with_libreoffice(fods_path: Path, output: Path) -> None:
    intermediate = fods_path.parent / f"{fods_path.stem}.xlsx"
    run([
        "/usr/bin/open", "-W", "-n", "-a", "LibreOffice", "--args",
        "--headless", "--convert-to", "xlsx", "--outdir", str(fods_path.parent), str(fods_path),
    ], capture=False)
    if not intermediate.is_file():
        raise SystemExit(f"LibreOffice 未生成预期文件：{intermediate}")
    patched = fods_path.parent / f"{fods_path.stem}-final.xlsx"
    patch_xlsx(intermediate, patched)
    shutil.move(str(patched), str(output))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="通过 ADB 将 Android 时间记录导出为 Excel 工作簿")
    parser.add_argument(
        "--serial", default=DEFAULT_SERIAL,
        help="ADB 设备序列号或 IP:端口；默认 auto，自动兼容 Wi-Fi 和手机热点",
    )
    parser.add_argument("--output", type=Path, default=None, help="输出 .xlsx 路径")
    parser.add_argument("--force", action="store_true", help="将已有输出移到废纸篓后重新生成")
    parser.add_argument("--period", choices=tuple(PERIOD_LABELS), help="导出 day、yesterday、week、month 或 all")
    parser.add_argument("--from", dest="start", type=parse_datetime, help="包含此时间及之后的数据")
    parser.add_argument("--to", dest="end", type=parse_datetime, help="不包含此时间及之后的数据")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.period and (args.start is not None or args.end is not None):
        raise SystemExit("--period 不能与 --from/--to 同时使用")

    if args.start is None and args.end is None:
        selected = args.period or prompt_period()
        args.start, args.end = period_bounds(selected)
        print(f"导出范围：{describe_period(selected, args.start, args.end)}")
    else:
        print("导出范围：使用 --from/--to 指定的自定义范围，UTC+8")
    if args.start and args.end and args.start >= args.end:
        raise SystemExit("--from 必须早于 --to")

    output = (args.output or default_output_path()).expanduser().resolve()
    if output.suffix.lower() != ".xlsx":
        raise SystemExit("输出文件必须使用 .xlsx 扩展名")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        if not args.force:
            raise SystemExit(f"输出文件已存在：{output}\n如需覆盖，请添加 --force")
        trash(output)

    TEMP_ROOT.mkdir(parents=True, exist_ok=True)
    work_dir = Path(tempfile.mkdtemp(prefix="android-time-export-", dir=TEMP_ROOT))
    jsonl_path = work_dir / "source.jsonl"
    fods_path = work_dir / f"{output.stem}.fods"

    try:
        adb = find_adb()
        serial = ensure_device(adb, args.serial)
        pulled_rows = request_jsonl(adb, serial, args.start, args.end, jsonl_path)
        rows = write_fods(jsonl_path, fods_path, pulled_rows)
        convert_with_libreoffice(fods_path, output)
    finally:
        if work_dir.exists():
            try:
                trash(work_dir)
            except SystemExit as error:
                print(f"警告：临时目录未能移到废纸篓：{error}")

    print(f"raw_events: {rows:,} 行，1 个工作表")
    print(f"已导出：{output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
