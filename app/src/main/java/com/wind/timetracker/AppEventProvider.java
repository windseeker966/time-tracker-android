package com.wind.timetracker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 接收其他应用上报的应用内行为（目前只有自己编译的 PiliPlus）。
 * 调用方式：call(method="record", extras.events = JSON 数组字符串)，
 * 数组元素为 {"timestamp_ms": 毫秒, "event_type": "...", 其余字段原样存入 payload}。
 */
public final class AppEventProvider extends ContentProvider {
    private static final String METHOD_RECORD = "record";
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.example.piliplus"));
    private static final int MAX_EVENTS_PER_CALL = 500;
    private static final int MAX_PAYLOAD_CHARS = 4000;

    private DatabaseHelper database;

    @Override
    public boolean onCreate() {
        return true;
    }

    private synchronized DatabaseHelper database() {
        if (database == null) database = new DatabaseHelper(getContext());
        return database;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_RECORD.equals(method)) {
            throw new IllegalArgumentException("不支持的方法：" + method);
        }
        String caller = getCallingPackage();
        if (caller == null || !ALLOWED_PACKAGES.contains(caller)) {
            throw new SecurityException("不允许的调用方：" + caller);
        }
        String events = extras == null ? null : extras.getString("events");
        if (events == null) throw new IllegalArgumentException("必须提供 events");

        int saved = 0;
        try {
            JSONArray array = new JSONArray(events);
            int count = Math.min(array.length(), MAX_EVENTS_PER_CALL);
            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                JSONObject event = array.optJSONObject(i);
                if (event == null) continue;
                String type = event.optString("event_type", "");
                if (type.isEmpty() || type.length() > 64) continue;
                long timestamp = event.optLong("timestamp_ms", now);
                // 拒绝明显错误的时间，避免污染按时间范围的导出。
                if (timestamp <= 0 || timestamp > now + 60_000L) timestamp = now;
                event.remove("event_type");
                event.remove("timestamp_ms");
                String payload = event.length() == 0 ? null : event.toString();
                if (payload != null && payload.length() > MAX_PAYLOAD_CHARS) {
                    payload = payload.substring(0, MAX_PAYLOAD_CHARS);
                }
                database().insertAppEvent(timestamp, caller, type, payload);
                saved++;
            }
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("events 不是有效的 JSON 数组", error);
        }
        Bundle result = new Bundle();
        result.putInt("saved", saved);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
