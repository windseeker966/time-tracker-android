package com.wind.timetracker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class ExportProvider extends ContentProvider {
    private static final String METHOD_EXPORT = "export";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_EXPORT.equals(method)) {
            throw new IllegalArgumentException("不支持的方法：" + method);
        }
        if (extras == null || !extras.containsKey("start_ms") || !extras.containsKey("end_ms")) {
            throw new IllegalArgumentException("必须提供 start_ms 和 end_ms");
        }

        long startMs = extras.getLong("start_ms");
        long endMs = extras.getLong("end_ms");
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("时间范围无效");
        }

        String label = arg == null ? "adb" : arg;
        if (!label.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("导出标签只能包含字母、数字、下划线和连字符");
        }

        try {
            android.content.Context context = getRequiredContext();
            DatabaseHelper database = new DatabaseHelper(context);
            String path = database.exportJsonLines(context, startMs, endMs, label);
            Bundle result = new Bundle();
            result.putString("path", path);
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("导出失败", error);
        }
    }

    private android.content.Context getRequiredContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("应用上下文不可用");
        return context;
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
