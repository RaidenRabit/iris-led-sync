package com.example.aimp_plugin;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.util.Log;

/**
 * Starts CarLedService automatically on:
 *   - Device boot
 *   - Android Auto / car mode entry (UiModeManager.ACTION_ENTER_CAR_MODE)
 *   - Any AIMP playback broadcast (com.aimp.service.action.*)
 *
 * Only starts if a BLE address has been saved (i.e. user has done initial setup).
 */
public class AutoStartReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoStartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "Received: " + action);

        if (isSystemTrigger(action) || action.startsWith("com.aimp.service.action.")) {
            startLedService(context);
        }
    }

    private static boolean isSystemTrigger(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || UiModeManager.ACTION_ENTER_CAR_MODE.equals(action)
            || "android.app.action.ENTER_CAR_MODE".equals(action);
    }

    static void startLedService(Context context) {
        Intent svc = new Intent(context, CarLedService.class);
        svc.setAction(CarLedService.ACTION_START);
        context.startForegroundService(svc);
    }
}
