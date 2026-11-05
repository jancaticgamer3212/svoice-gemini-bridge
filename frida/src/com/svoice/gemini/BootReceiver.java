package com.svoice.gemini;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_START = "com.svoice.gemini.START_WATCHDOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_START.equals(action)) {
            WatchdogService.start(context);
        }
    }
}
