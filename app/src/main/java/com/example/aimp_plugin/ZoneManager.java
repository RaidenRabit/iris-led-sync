package com.example.aimp_plugin;

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
 * Key design points:
 *  - updateZone() is the single write path; always reads the LIVE zone from zones[],
 *    so stale ViewHolder captures are safe to apply via withX() mutations.
 *  - onAlbumColors() starts a 30-step lerp (≈1 s) from each zone's current raw color
 *    to the new target color, sending BLE packets at each step.
 *  - dispatchBle() applies hue/saturation/brightness transforms and sends both the
 *    color and power-state packets.
 */
public final class ZoneManager {

    private static final String TAG  = "ZoneManager";
    private static final String PREFS = "aimp_led";
    public  static final int ZONE_COUNT = 2;

    // Lerp: 30 steps × 33 ms ≈ 1 s
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
    private final LedZone[] zones = new LedZone[ZONE_COUNT];

    // Lerp state: raw colors at the start of the current animation
    private final int[] lerpFrom = new int[ZONE_COUNT];

    private Map<ColorMode, Integer> lastAlbumColors;

    // Last computed post-transform colors (what the LEDs actually show)
    private final int[] effectiveColors = new int[ZONE_COUNT];

    private final List<Listener>      listeners      = new ArrayList<>();
    private final List<ColorListener> colorListeners = new ArrayList<>();
    private volatile CarLedBleManager bleManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable lerpRunnable; // non-null while a lerp is in progress

    // ---- construction --------------------------------------------------------

    private ZoneManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int i = 0; i < ZONE_COUNT; i++) {
            zones[i] = load(i);
            lerpFrom[i] = zones[i].color;
        }
        // Seed effectiveColors so UI headers show the saved zone state immediately,
        // not black, before any BLE connection or album-art event fires.
        dispatchBle();
        Log.i(TAG, "Loaded " + ZONE_COUNT + " zones");
    }

    // ---- BLE wiring ----------------------------------------------------------

    public void attachBleManager(CarLedBleManager ble) { bleManager = ble; }

    /** Re-send current zone colors to BLE — call this when BLE reconnects. */
    public void dispatchCurrentColors() { dispatchBle(); }

    // ---- zone access ---------------------------------------------------------

    public LedZone getZone(int index) { return zones[index]; }

    /**
     * Persist and immediately apply an updated zone.
     *
     * Callers (sliders, toggles, car app) should pass a mutation of the LIVE
     * zone — e.g. getZone(i).withBrightness(v) — to avoid stale-capture bugs.
     * Safe to call from any thread.
     */
    public void updateZone(LedZone zone) {
        zones[zone.index] = zone;
        lerpFrom[zone.index] = zone.color; // reset lerp anchor so next lerp starts clean
        save(zone);
        dispatchBle();
        notifyListeners();
    }

    /**
     * Apply new album-art colors (one per ColorMode) to all auto-color zones,
     * animating from the current raw color to the new target.
     */
    public void reapplyAlbumColors() {
        if (lastAlbumColors != null) onAlbumColors(lastAlbumColors);
    }

    public void onAlbumColors(Map<ColorMode, Integer> colors) {
        lastAlbumColors = colors;
        Log.i(TAG, "Album colors received");
        // Compute each zone's target from its chosen ColorMode
        int[] targets = new int[ZONE_COUNT];
        boolean anyChange = false;
        for (int i = 0; i < ZONE_COUNT; i++) {
            LedZone z = zones[i];
            if (!z.autoColor || !z.enabled) continue;
            Integer t = colors.get(z.colorMode);
            if (t == null) t = colors.get(ColorMode.VIBRANT);
            targets[i] = t != null ? t : 0xFF_FFFFFF;
            anyChange = true;
        }
        if (!anyChange) return;

        // Start lerp: capture current raw colors as start points
        for (int i = 0; i < ZONE_COUNT; i++) lerpFrom[i] = zones[i].color;

        final int[] lerpTargets = targets.clone();
        startLerp(lerpTargets, colors);
    }

    // ---- lerp ----------------------------------------------------------------

    private void startLerp(int[] targets, Map<ColorMode, Integer> allColors) {
        // Cancel any in-progress animation
        if (lerpRunnable != null) mainHandler.removeCallbacks(lerpRunnable);

        final int[] step = {0};

        lerpRunnable = new Runnable() {
            @Override public void run() {
                step[0]++;
                float t = Math.min(1f, (float) step[0] / LERP_STEPS);

                for (int i = 0; i < ZONE_COUNT; i++) {
                    LedZone z = zones[i];
                    if (!z.autoColor || !z.enabled) continue;
                    int interpolated = lerpColor(lerpFrom[i], targets[i], t);
                    zones[i] = z.withColor(interpolated);
                }
                dispatchBle();

                if (step[0] < LERP_STEPS) {
                    mainHandler.postDelayed(this, LERP_INTERVAL);
                } else {
                    // Lerp complete — update lerpFrom anchors and notify UI
                    for (int i = 0; i < ZONE_COUNT; i++) lerpFrom[i] = zones[i].color;
                    lerpRunnable = null;
                    notifyListeners(); // refresh UI sliders / color preview
                }
            }
        };
        mainHandler.post(lerpRunnable);
    }

    private static int lerpColor(int a, int b, float t) {
        int r = (int) (Color.red(a)   + t * (Color.red(b)   - Color.red(a)));
        int g = (int) (Color.green(a) + t * (Color.green(b) - Color.green(a)));
        int bl= (int) (Color.blue(a)  + t * (Color.blue(b)  - Color.blue(a)));
        return Color.rgb(r, g, bl);
    }

    // ---- listeners -----------------------------------------------------------

    /** Fires on structural zone changes (enable/disable, name, mode, etc.). */
    public interface Listener { void onZonesChanged(); }

    /**
     * Fires at every lerp step AND after every updateZone() with the
     * post-transform color currently shown by each zone. Use this for the
     * live color preview — it avoids a full RecyclerView rebind at 30 fps.
     */
    public interface ColorListener { void onEffectiveColors(int[] colors); }

    public void addListener(Listener l)           { synchronized (listeners)      { listeners.add(l); } }
    public void removeListener(Listener l)        { synchronized (listeners)      { listeners.remove(l); } }
    public void addColorListener(ColorListener l) { synchronized (colorListeners) { colorListeners.add(l); } }
    public void removeColorListener(ColorListener l) { synchronized (colorListeners) { colorListeners.remove(l); } }

    /** Last post-transform color for a zone — safe to read on any thread. */
    public int getEffectiveColor(int index) { return effectiveColors[index]; }

    private void notifyListeners() {
        mainHandler.post(() -> {
            synchronized (listeners) { for (Listener l : listeners) l.onZonesChanged(); }
        });
    }

    private void notifyColorListeners() {
        int[] snap = effectiveColors.clone();
        mainHandler.post(() -> {
            synchronized (colorListeners) { for (ColorListener l : colorListeners) l.onEffectiveColors(snap); }
        });
    }

    // ---- BLE dispatch --------------------------------------------------------

    private void dispatchBle() {
        LedZone z0 = zones[0], z1 = zones[1];
        boolean anyOn = z0.enabled || z1.enabled;

        int c0 = z0.enabled
            ? CarLedProtocol.transformColor(z0.color, z0.hueOffset, z0.saturation, z0.brightness)
            : Color.BLACK;
        int c1 = z1.enabled
            ? CarLedProtocol.transformColor(z1.color, z1.hueOffset, z1.saturation, z1.brightness)
            : Color.BLACK;

        // Cache for UI preview — always updated even when BLE isn't connected
        effectiveColors[0] = c0;
        effectiveColors[1] = c1;
        notifyColorListeners();

        CarLedBleManager ble = bleManager;
        if (ble == null || !ble.isReady()) return;

        ble.enqueuePacket(CarLedProtocol.buildColorPacket(
            Color.red(c0), Color.green(c0), Color.blue(c0),
            Color.red(c1), Color.green(c1), Color.blue(c1)));
        ble.enqueuePacket(CarLedProtocol.buildBrightnessPacket(anyOn, 0xFF, 0xFF));
    }

    // ---- persistence ---------------------------------------------------------

    private LedZone load(int i) {
        String p = "zone_" + i + "_";
        int modeOrdinal = prefs.getInt(p + "color_mode", ColorMode.VIBRANT.ordinal());
        ColorMode mode = ColorMode.values()[Math.min(modeOrdinal, ColorMode.values().length - 1)];
        return new LedZone(
            i,
            prefs.getString(p + "name",         i == 0 ? "Zone 1" : "Zone 2"),
            prefs.getBoolean(p + "enabled",      true),
            prefs.getInt(p + "brightness",       255),
            prefs.getBoolean(p + "auto_color",   true),
            mode,
            prefs.getInt(p + "color",            0xFF_FFFFFF),
            prefs.getInt(p + "hue_offset",       0),
            prefs.getInt(p + "saturation",       130)
        );
    }

    private void save(LedZone z) {
        String p = "zone_" + z.index + "_";
        prefs.edit()
            .putString(p + "name",       z.name)
            .putBoolean(p + "enabled",   z.enabled)
            .putInt(p + "brightness",    z.brightness)
            .putBoolean(p + "auto_color", z.autoColor)
            .putInt(p + "color_mode",    z.colorMode.ordinal())
            .putInt(p + "color",         z.color)
            .putInt(p + "hue_offset",    z.hueOffset)
            .putInt(p + "saturation",    z.saturation)
            .apply();
    }
}
