package com.wind.timetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Intent service = new Intent(context, CollectorService.class);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException ignored) {
            // Some vendor builds require the user to enable app auto-start first.
        }
    }
}
