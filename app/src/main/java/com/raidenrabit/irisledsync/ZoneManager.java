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
 * A per-mode master dimmer (0-100%) scales every zone's brightness on output, so the
 * whole cabin can be dimmed in one go without losing the balance between zones.
 * Night caps zone brightness at 100% (no amplification) and starts dimmer.
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

    // Live preview while a control is being dragged — rate-limited so the BLE queue keeps up
    private static final int PREVIEW_INTERVAL = 66; // ms

    private static final int MAX_BRIGHTNESS_DAY       = 400;
    private static final int MAX_BRIGHTNESS_NIGHT     = 100;
    private static final int DEFAULT_BRIGHTNESS_DAY   = 100;
    private static final int DEFAULT_BRIGHTNESS_NIGHT = 25;

    public static int maxBrightness(boolean isNight)     { return isNight ? MAX_BRIGHTNESS_NIGHT : MAX_BRIGHTNESS_DAY; }
    public static int defaultBrightness(boolean isNight) { return isNight ? DEFAULT_BRIGHTNESS_NIGHT : DEFAULT_BRIGHTNESS_DAY; }
    // 250% boosts muted Palette colors to vivid; 150% is softer for night.
    public static int defaultSaturation(boolean isNight) { return isNight ? 150 : 250; }

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
    private int master; // 0-100, per mode

    private final List<Listener>      listeners      = new ArrayList<>();
    private final List<ColorListener> colorListeners = new ArrayList<>();
    private volatile CarLedBleManager bleManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable lerpRunnable;
    private boolean  previewScheduled;
    private final Runnable previewDispatch = () -> { previewScheduled = false; dispatchBle(); };

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
        master = loadMaster(isNight);
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
        cancelPreview();
        for (int i = 0; i < ZONE_COUNT; i++) {
            zones[i]    = load(isNight, i);
            lerpFrom[i] = zones[i].color;
        }
        master = loadMaster(isNight);
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
        cancelPreview();
        zone = capBrightness(zone);
        zones[zone.index] = zone;
        lerpFrom[zone.index] = zone.color;
        save(zone);
        dispatchBle();
        notifyListeners();
    }

    /** Apply a zone change to the LEDs right away (throttled) without saving it — used while dragging. */
    public void previewZone(LedZone zone) {
        zones[zone.index] = capBrightness(zone);
        schedulePreview();
    }

    public int getMaster() { return master; }

    public void setMaster(int pct) {
        cancelPreview();
        master = clampMaster(pct);
        prefs.edit().putInt(masterKey(dayNightManager.isNightActive()), master).apply();
        dispatchBle();
        notifyListeners();
    }

    public void previewMaster(int pct) {
        master = clampMaster(pct);
        schedulePreview();
    }

    private void schedulePreview() {
        if (previewScheduled) return;
        previewScheduled = true;
        mainHandler.postDelayed(previewDispatch, PREVIEW_INTERVAL);
    }

    private void cancelPreview() {
        mainHandler.removeCallbacks(previewDispatch);
        previewScheduled = false;
    }

    private LedZone capBrightness(LedZone z) {
        int max = maxBrightness(dayNightManager.isNightActive());
        return z.brightness > max ? z.withBrightness(max) : z;
    }

    private static int clampMaster(int pct) { return Math.max(0, Math.min(100, pct)); }

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

        // Full transform including brightness and the master dimmer — the preview shows
        // exactly what the LEDs output, so it dims along with them at night.
        int c0 = outputColor(z0);
        int c1 = outputColor(z1);
        effectiveColors[0] = c0;
        effectiveColors[1] = c1;
        notifyColorListeners();

        CarLedBleManager ble = bleManager;
        if (ble == null || !ble.isReady()) return;

        ble.enqueuePacket(CarLedProtocol.buildColorPacket(
            Color.red(c0), Color.green(c0), Color.blue(c0),
            Color.red(c1), Color.green(c1), Color.blue(c1)));
        // b1/b2 bytes don't map to per-zone PWM on this controller; keep at 0xFF.
        ble.enqueuePacket(CarLedProtocol.buildBrightnessPacket(anyOn, 0xFF, 0xFF));
    }

    private int outputColor(LedZone z) {
        if (!z.enabled) return Color.BLACK;
        int brightness = Math.round(z.brightness * master / 100f);
        return CarLedProtocol.transformColor(z.color, z.hueOffset, z.saturation, brightness);
    }

    // ---- persistence ---------------------------------------------------------

    private static String masterKey(boolean isNight) { return "master_" + (isNight ? "night" : "day"); }

    private int loadMaster(boolean isNight) { return prefs.getInt(masterKey(isNight), 100); }

    private LedZone load(boolean isNight, int i) {
        String mp       = "zone_" + i + "_" + (isNight ? "night" : "day") + "_";
        String lp       = "zone_" + i + "_"; // legacy keys (migration fallback for day)
        boolean useLegacy = !isNight;         // night has its own defaults; day falls back to legacy

        int defaultBrightness = defaultBrightness(isNight);
        int defaultSaturation = defaultSaturation(isNight);

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
            Math.min(maxBrightness(isNight),
                getInt(mp, lp, useLegacy, "brightness", defaultBrightness)),
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
