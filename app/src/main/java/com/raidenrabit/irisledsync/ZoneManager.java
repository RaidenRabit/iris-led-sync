package com.raidenrabit.irisledsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Singleton — owns zone state, persistence, BLE dispatch, and the lerp animation.
 *
 * Day and night each have their own prefs namespace ("zone_i_day_*" / "zone_i_night_*").
 * Switching modes reloads all zones from the new namespace and redispatches to BLE.
 * The single transformColor() path is shared between both modes — only the loaded
 * values differ (brightness/saturation defaults and the saved color).
 *
 * Migration: legacy "zone_i_*" keys are treated as day settings when no day-specific
 * keys have been written yet.
 */
public final class ZoneManager implements DayNightManager.Listener {

    private static final String TAG   = "ZoneManager";
    private static final String PREFS = "aimp_led";
    public  static final int ZONE_COUNT = 2;

    private static final int LERP_STEPS    = 30;
    private static final int LERP_INTERVAL = 33; // ms

    // ---- singleton -----------------------------------------------------------

    private static volatile ZoneManager instance;

    public static ZoneManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (ZoneManager.class) {
                if (instance == null) instance = new ZoneManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    // ---- state ---------------------------------------------------------------

    private final SharedPreferences prefs;
    private final DayNightManager   dayNightManager;
    private final LedZone[]         zones = new LedZone[ZONE_COUNT];

    private final int[] lerpFrom        = new int[ZONE_COUNT];
    private Map<ColorMode, Integer> lastAlbumColors;
    private final int[] effectiveColors = new int[ZONE_COUNT];

    private final List<Listener>      listeners      = new ArrayList<>();
    private final List<ColorListener> colorListeners = new ArrayList<>();
    private volatile CarLedBleManager bleManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable lerpRunnable;

    // ---- construction --------------------------------------------------------

    private ZoneManager(Context ctx) {
        prefs           = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dayNightManager = DayNightManager.getInstance(ctx);
        dayNightManager.addListener(this);
        boolean isNight = dayNightManager.isNightActive();
        for (int i = 0; i < ZONE_COUNT; i++) {
            zones[i]    = load(isNight, i);
            lerpFrom[i] = zones[i].color;
        }
        dispatchBle();
        Log.i(TAG, "Loaded " + ZONE_COUNT + " zones (night=" + isNight + ")");
    }

    // ---- DayNightManager.Listener -------------------------------------------

    @Override
    public void onModeChanged(boolean isNight) {
        if (lerpRunnable != null) {
            mainHandler.removeCallbacks(lerpRunnable);
            lerpRunnable = null;
        }
        for (int i = 0; i < ZONE_COUNT; i++) {
            zones[i]    = load(isNight, i);
            lerpFrom[i] = zones[i].color;
        }
        dispatchBle();
        if (lastAlbumColors != null) reapplyAlbumColors();
        notifyListeners();
        Log.i(TAG, "Mode changed: night=" + isNight);
    }

    // ---- BLE wiring ----------------------------------------------------------

    public void attachBleManager(CarLedBleManager ble) {
        bleManager = ble;
        ble.setOnReadyCallback(this::dispatchCurrentColors);
        ble.setOnDisconnectCallback(this::notifyListeners);
    }

    public void dispatchCurrentColors() {
        dispatchBle();
        notifyListeners();
    }

    public boolean isBleConnected() { return bleManager != null && bleManager.isReady(); }

    // ---- zone access ---------------------------------------------------------

    public LedZone getZone(int index) { return zones[index]; }

    public void updateZone(LedZone zone) {
        zones[zone.index] = zone;
        lerpFrom[zone.index] = zone.color;
        save(zone);
        dispatchBle();
        notifyListeners();
    }

    public void reapplyAlbumColors() {
        if (lastAlbumColors != null) onAlbumColors(lastAlbumColors);
    }

    public void onAlbumColors(Map<ColorMode, Integer> colors) {
        // Re-evaluate day/night on every track change so the mode stays current even
        // when no system or car configuration event was delivered (e.g. Waze suppressing
        // onCarConfigurationChanged while it is the foreground car app).
        dayNightManager.refreshAutoMode();
        lastAlbumColors = colors;
        Log.i(TAG, "Album colors received");
        int[] targets   = new int[ZONE_COUNT];
        boolean anyChange = false;
        for (int i = 0; i < ZONE_COUNT; i++) {
            LedZone z = zones[i];
            if (!z.autoColor || !z.enabled) continue;
            Integer t = colors.get(z.colorMode);
            if (t == null) t = colors.get(ColorMode.VIBRANT);
            targets[i]  = t != null ? t : 0xFF_FFFFFF;
            anyChange   = true;
        }
        if (!anyChange) return;

        for (int i = 0; i < ZONE_COUNT; i++) lerpFrom[i] = zones[i].color;
        startLerp(targets.clone(), colors);
    }

    // ---- lerp ----------------------------------------------------------------

    private void startLerp(int[] targets, Map<ColorMode, Integer> allColors) {
        if (lerpRunnable != null) mainHandler.removeCallbacks(lerpRunnable);
        final int[] step = {0};
        lerpRunnable = new Runnable() {
            @Override public void run() {
                step[0]++;
                float t = Math.min(1f, (float) step[0] / LERP_STEPS);
                for (int i = 0; i < ZONE_COUNT; i++) {
                    LedZone z = zones[i];
                    if (!z.autoColor || !z.enabled) continue;
                    zones[i] = z.withColor(lerpColor(lerpFrom[i], targets[i], t));
                }
                dispatchBle();
                if (step[0] < LERP_STEPS) {
                    mainHandler.postDelayed(this, LERP_INTERVAL);
                } else {
                    for (int i = 0; i < ZONE_COUNT; i++) lerpFrom[i] = zones[i].color;
                    lerpRunnable = null;
                    notifyListeners();
                }
            }
        };
        mainHandler.post(lerpRunnable);
    }

    private static int lerpColor(int a, int b, float t) {
        int r  = (int) (Color.red(a)   + t * (Color.red(b)   - Color.red(a)));
        int g  = (int) (Color.green(a) + t * (Color.green(b) - Color.green(a)));
        int bl = (int) (Color.blue(a)  + t * (Color.blue(b)  - Color.blue(a)));
        return Color.rgb(r, g, bl);
    }

    // ---- listeners -----------------------------------------------------------

    public interface Listener      { void onZonesChanged(); }
    public interface ColorListener { void onEffectiveColors(int[] colors); }

    public void addListener(Listener l)              { synchronized (listeners)      { listeners.add(l); } }
    public void removeListener(Listener l)           { synchronized (listeners)      { listeners.remove(l); } }
    public void addColorListener(ColorListener l)    { synchronized (colorListeners) { colorListeners.add(l); } }
    public void removeColorListener(ColorListener l) { synchronized (colorListeners) { colorListeners.remove(l); } }

    public int getEffectiveColor(int index) { return effectiveColors[index]; }

    private void notifyListeners() {
        List<Listener> snap;
        synchronized (listeners) { snap = new ArrayList<>(listeners); }
        Runnable r = () -> { for (Listener l : snap) l.onZonesChanged(); };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    private void notifyColorListeners() {
        int[] snap = effectiveColors.clone();
        mainHandler.post(() -> {
            synchronized (colorListeners) { for (ColorListener l : colorListeners) l.onEffectiveColors(snap); }
        });
    }

    // ---- BLE dispatch --------------------------------------------------------

    private void dispatchBle() {
        LedZone z0    = zones[0], z1 = zones[1];
        boolean anyOn = z0.enabled || z1.enabled;

        // Preview shows hue+saturation effect only — brightness=100 means 1× (natural) in the formula.
        int d0 = z0.enabled ? CarLedProtocol.transformColor(z0.color, z0.hueOffset, z0.saturation, 100) : Color.BLACK;
        int d1 = z1.enabled ? CarLedProtocol.transformColor(z1.color, z1.hueOffset, z1.saturation, 100) : Color.BLACK;
        effectiveColors[0] = z0.enabled ? d0 : Color.BLACK;
        effectiveColors[1] = z1.enabled ? d1 : Color.BLACK;
        notifyColorListeners();

        CarLedBleManager ble = bleManager;
        if (ble == null || !ble.isReady()) return;

        // BLE output applies the full transform including brightness amplification.
        int c0 = z0.enabled ? CarLedProtocol.transformColor(z0.color, z0.hueOffset, z0.saturation, z0.brightness) : Color.BLACK;
        int c1 = z1.enabled ? CarLedProtocol.transformColor(z1.color, z1.hueOffset, z1.saturation, z1.brightness) : Color.BLACK;

        ble.enqueuePacket(CarLedProtocol.buildColorPacket(
            Color.red(c0), Color.green(c0), Color.blue(c0),
            Color.red(c1), Color.green(c1), Color.blue(c1)));
        // b1/b2 bytes don't map to per-zone PWM on this controller; keep at 0xFF.
        ble.enqueuePacket(CarLedProtocol.buildBrightnessPacket(anyOn, 0xFF, 0xFF));
    }

    // ---- persistence ---------------------------------------------------------

    private LedZone load(boolean isNight, int i) {
        String mp       = "zone_" + i + "_" + (isNight ? "night" : "day") + "_";
        String lp       = "zone_" + i + "_"; // legacy keys (migration fallback for day)
        boolean useLegacy = !isNight;         // night has its own defaults; day falls back to legacy

        // Brightness: 300% = 3× amplification (vivid for day); 100% = natural (calm for night).
        // Saturation: 250% boosts muted Palette colors to vivid; 150% is softer for night.
        int defaultBrightness = 100; // same natural starting point for both modes
        int defaultSaturation = isNight ? 150 : 250;

        // Night mode first use: inherit the saved day color so hue/sat sliders work immediately.
        // Without this, night defaults to white and all transforms become no-ops (HSV S=0).
        int colorDefault;
        if (isNight && !prefs.contains(mp + "color")) {
            String dp = "zone_" + i + "_day_";
            colorDefault = prefs.contains(dp + "color") ? prefs.getInt(dp + "color", 0xFF_FFFFFF)
                         : prefs.getInt(lp + "color", 0xFF_FFFFFF);
        } else {
            colorDefault = 0xFF_FFFFFF;
        }

        int modeOrd = getInt(mp, lp, useLegacy, "color_mode", ColorMode.VIBRANT.ordinal());
        ColorMode mode = ColorMode.values()[Math.min(modeOrd, ColorMode.values().length - 1)];

        return new LedZone(
            i,
            getStr( mp, lp, useLegacy, "name",        i == 0 ? "Zone 1" : "Zone 2"),
            getBool(mp, lp, useLegacy, "enabled",      true),
            getInt( mp, lp, useLegacy, "brightness",   defaultBrightness),
            getBool(mp, lp, useLegacy, "auto_color",   true),
            mode,
            getInt( mp, lp, useLegacy, "color",        colorDefault),
            getInt( mp, lp, useLegacy, "hue_offset",   0),
            getInt( mp, lp, useLegacy, "saturation",   defaultSaturation)
        );
    }

    private void save(LedZone z) {
        boolean isNight = dayNightManager.isNightActive();
        String p = "zone_" + z.index + "_" + (isNight ? "night" : "day") + "_";
        prefs.edit()
            .putString( p + "name",       z.name)
            .putBoolean(p + "enabled",    z.enabled)
            .putInt(    p + "brightness", z.brightness)
            .putBoolean(p + "auto_color", z.autoColor)
            .putInt(    p + "color_mode", z.colorMode.ordinal())
            .putInt(    p + "color",      z.color)
            .putInt(    p + "hue_offset", z.hueOffset)
            .putInt(    p + "saturation", z.saturation)
            .apply();
    }

    // ---- prefs helpers (mode-specific key, optional legacy fallback) ----------

    private int getInt(String p, String lp, boolean useLegacy, String key, int def) {
        if (prefs.contains(p + key))               return prefs.getInt(p + key, def);
        if (useLegacy && prefs.contains(lp + key)) return prefs.getInt(lp + key, def);
        return def;
    }

    private boolean getBool(String p, String lp, boolean useLegacy, String key, boolean def) {
        if (prefs.contains(p + key))               return prefs.getBoolean(p + key, def);
        if (useLegacy && prefs.contains(lp + key)) return prefs.getBoolean(lp + key, def);
        return def;
    }

    private String getStr(String p, String lp, boolean useLegacy, String key, String def) {
        if (prefs.contains(p + key))               return prefs.getString(p + key, def);
        if (useLegacy && prefs.contains(lp + key)) return prefs.getString(lp + key, def);
        return def;
    }
}
