package com.raidenrabit.irisledsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.car.app.CarContext;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Manages the day/night LED mode preference (DAY, NIGHT, AUTO).
 *
 * AUTO resolves by comparing the current local time against two user-configured
 * thresholds (dayStartMinute, nightStartMinute). The check re-runs every minute
 * so transitions happen within 60 seconds of the configured time.
 *
 * Car context (Android Auto) takes priority over the time-based check when an
 * Auto session is active — the car's own day/night signal is more reliable
 * while driving.
 */
public final class DayNightManager {

    private static final String TAG = "DayNightMgr";

    public enum Mode { DAY, NIGHT, AUTO }

    public interface Listener { void onModeChanged(boolean isNight); }

    private static final String PREFS           = "aimp_led";
    private static final String KEY_MODE        = "day_night_mode";
    private static final String KEY_DAY_START   = "auto_day_start_min";
    private static final String KEY_NIGHT_START = "auto_night_start_min";

    static final int DEFAULT_DAY_START   =  8 * 60; // 08:00
    static final int DEFAULT_NIGHT_START = 21 * 60; // 21:00

    private static final long TIME_CHECK_INTERVAL_MS = 60_000L;

    private static volatile DayNightManager instance;

    private final Context           context;
    private final SharedPreferences prefs;
    private       Mode              mode;
    private       boolean           lastIsNight;
    private final List<Listener>    listeners   = new ArrayList<>();
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    private boolean hasCarContext = false;
    private boolean carNightMode  = false;

    private final Runnable timeCheckRunnable = new Runnable() {
        @Override public void run() {
            if (mode == Mode.AUTO && !hasCarContext) checkAndNotify();
            mainHandler.postDelayed(this, TIME_CHECK_INTERVAL_MS);
        }
    };

    private DayNightManager(Context ctx) {
        context     = ctx.getApplicationContext();
        prefs       = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int ord     = prefs.getInt(KEY_MODE, Mode.AUTO.ordinal());
        mode        = Mode.values()[Math.min(ord, Mode.values().length - 1)];
        lastIsNight = resolveIsNight();
        Log.d(TAG, "init: mode=" + mode + " isNight=" + lastIsNight);
        mainHandler.postDelayed(timeCheckRunnable, TIME_CHECK_INTERVAL_MS);
    }

    public static DayNightManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (DayNightManager.class) {
                if (instance == null) instance = new DayNightManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    // ---- public API ----------------------------------------------------------

    public Mode    getMode()       { return mode; }
    public boolean isNightActive() { return lastIsNight; }

    public int getDayStartMinute()   { return prefs.getInt(KEY_DAY_START,   DEFAULT_DAY_START); }
    public int getNightStartMinute() { return prefs.getInt(KEY_NIGHT_START, DEFAULT_NIGHT_START); }

    public void setDayStartMinute(int minuteOfDay) {
        prefs.edit().putInt(KEY_DAY_START, minuteOfDay).apply();
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void setNightStartMinute(int minuteOfDay) {
        prefs.edit().putInt(KEY_NIGHT_START, minuteOfDay).apply();
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void setMode(Mode newMode) {
        mode = newMode;
        prefs.edit().putInt(KEY_MODE, newMode.ordinal()).apply();
        checkAndNotify();
    }

    public void attachCarContext(CarContext carContext) {
        hasCarContext = true;
        carNightMode  = carContext.isDarkMode();
        Log.d(TAG, "attachCarContext: carNightMode=" + carNightMode);
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void detachCarContext() {
        Log.d(TAG, "detachCarContext");
        hasCarContext = false;
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void onCarNightModeChanged(boolean isCarNight) {
        Log.d(TAG, "onCarNightModeChanged: " + isCarNight);
        hasCarContext = true;
        carNightMode  = isCarNight;
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void onSystemConfigurationChanged() {
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void refreshAutoMode() {
        if (mode == Mode.AUTO) checkAndNotify();
    }

    public void addListener(Listener l) {
        synchronized (listeners) { listeners.add(l); }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    // ---- internals -----------------------------------------------------------

    private boolean resolveIsNight() {
        switch (mode) {
            case NIGHT: return true;
            case DAY:   return false;
            default:
                if (hasCarContext) return carNightMode;
                return isTimeBasedNight();
        }
    }

    private boolean isTimeBasedNight() {
        Calendar c       = Calendar.getInstance();
        int nowMin       = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int dayStart     = getDayStartMinute();
        int nightStart   = getNightStartMinute();

        if (dayStart < nightStart) {
            // Normal: day is [dayStart, nightStart), night is outside that window
            return nowMin < dayStart || nowMin >= nightStart;
        }
        // Inverted (rare): night spans midnight, day is [dayStart, nightStart)
        return nowMin >= nightStart && nowMin < dayStart;
    }

    private void checkAndNotify() {
        boolean isNight = resolveIsNight();
        if (isNight != lastIsNight) {
            lastIsNight = isNight;
            Log.i(TAG, "transition → night=" + isNight);
            notifyListeners(isNight);
        }
    }

    private void notifyListeners(boolean isNight) {
        List<Listener> snap;
        synchronized (listeners) { snap = new ArrayList<>(listeners); }
        Runnable r = () -> { for (Listener l : snap) l.onModeChanged(isNight); };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }
}
