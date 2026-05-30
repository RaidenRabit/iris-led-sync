package com.example.aimp_plugin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that glues AIMPMediaListener and CarLedBleManager together.
 *
 * The notification shows the current brightness for both zones and has six actions
 * for in-car brightness control without needing the Android Auto launcher:
 *   Z1 −  |  Z1 +  |  Z2 −  |  Z2 +  |  All OFF  |  Stop
 *
 * These actions show up in Android Auto's notification shade immediately.
 */
public class CarLedService extends Service implements ZoneManager.Listener {

    private static final String TAG = "CarLedService";

    static final String CHANNEL_ID   = "car_led_channel";
    static final int    NOTIF_ID     = 1;

    static final String ACTION_START    = "com.example.aimp_plugin.START";
    static final String ACTION_STOP     = "com.example.aimp_plugin.STOP";
    static final String ACTION_Z1_DOWN  = "com.example.aimp_plugin.Z1_DOWN";
    static final String ACTION_Z1_UP    = "com.example.aimp_plugin.Z1_UP";
    static final String ACTION_Z2_DOWN  = "com.example.aimp_plugin.Z2_DOWN";
    static final String ACTION_Z2_UP    = "com.example.aimp_plugin.Z2_UP";
    static final String ACTION_ALL_OFF  = "com.example.aimp_plugin.ALL_OFF";

    static final String PREF_BLE_ADDR = "ble_address";

    private static final int BRIGHT_STEP = 26; // ~10%

    private CarLedBleManager bleManager;

    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        bleManager = new CarLedBleManager(this);
        ZoneManager zm = ZoneManager.getInstance(this);
        zm.attachBleManager(bleManager);
        zm.addListener(this);
        bleManager.setOnReadyCallback(zm::dispatchCurrentColors);
        Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) action = ACTION_START;

        ZoneManager zm = ZoneManager.getInstance(this);

        switch (action) {
            case ACTION_STOP:
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_Z1_DOWN:
                zm.updateZone(zm.getZone(0).withBrightness(zm.getZone(0).brightness - BRIGHT_STEP));
                break;
            case ACTION_Z1_UP:
                zm.updateZone(zm.getZone(0).withBrightness(zm.getZone(0).brightness + BRIGHT_STEP));
                break;
            case ACTION_Z2_DOWN:
                zm.updateZone(zm.getZone(1).withBrightness(zm.getZone(1).brightness - BRIGHT_STEP));
                break;
            case ACTION_Z2_UP:
                zm.updateZone(zm.getZone(1).withBrightness(zm.getZone(1).brightness + BRIGHT_STEP));
                break;
            case ACTION_ALL_OFF:
                zm.updateZone(zm.getZone(0).withEnabled(false));
                zm.updateZone(zm.getZone(1).withEnabled(false));
                break;
            default: // ACTION_START or first-run
                startForeground(NOTIF_ID, buildNotification());
                SharedPreferences prefs = getSharedPreferences("aimp_led", MODE_PRIVATE);
                String address = prefs.getString(PREF_BLE_ADDR, null);
                if (address != null) bleManager.connect(address);
                else Log.w(TAG, "No BLE address — open app to scan");
                if (AIMPMediaListener.getInstance() != null)
                    AIMPMediaListener.getInstance().attachLedService(this);
                return START_STICKY;
        }

        // For brightness actions: update notification immediately (even without LED)
        refreshNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ZoneManager.getInstance(this).removeListener(this);
        if (bleManager != null) bleManager.disconnect();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---- ZoneManager.Listener -----------------------------------------------

    @Override
    public void onZonesChanged() { refreshNotification(); }

    // ---- called by AIMPMediaListener ----------------------------------------

    void onAlbumColors(java.util.Map<ColorMode, Integer> colors) {
        ZoneManager.getInstance(this).onAlbumColors(colors);
    }

    // ---- notification -------------------------------------------------------

    private void refreshNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        ZoneManager zm = ZoneManager.getInstance(this);
        LedZone z0 = zm.getZone(0), z1 = zm.getZone(1);

        String text = z0.name + " " + z0.brightnessPercent()
                    + "  ·  " + z1.name + " " + z1.brightnessPercent();

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Iris LED Sync")
            .setContentText(text)
            .setContentIntent(pi(ACTION_START, 0))
            .addAction(android.R.drawable.ic_media_previous, "Z1−", pi(ACTION_Z1_DOWN, 1))
            .addAction(android.R.drawable.ic_media_next,     "Z1+", pi(ACTION_Z1_UP,   2))
            .addAction(android.R.drawable.ic_media_previous, "Z2−", pi(ACTION_Z2_DOWN, 3))
            .addAction(android.R.drawable.ic_media_next,     "Z2+", pi(ACTION_Z2_UP,   4))
            .addAction(android.R.drawable.ic_lock_power_off, "Off", pi(ACTION_ALL_OFF, 5))
            .setOngoing(true)
            .build();
    }

    private PendingIntent pi(String action, int requestCode) {
        Intent i = new Intent(this, CarLedService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Iris LED Sync", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Syncs album art colors to car LED strips");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
