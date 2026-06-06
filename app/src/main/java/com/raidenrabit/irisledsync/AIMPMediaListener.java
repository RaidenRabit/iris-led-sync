package com.raidenrabit.irisledsync;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.palette.graphics.Palette;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monitors AIMP's MediaSession and computes one colour per ColorMode whenever the
 * track changes.
 *
 * Three complementary triggers so no track change is ever missed:
 *  1. MediaController.Callback.onMetadataChanged  — registered on the main-thread
 *     Handler so callbacks are never silently dropped on a Binder thread.
 *  2. NotificationListenerService.onNotificationPosted — fires when AIMP updates its
 *     notification (common but not guaranteed on every track change).
 *  3. BroadcastReceiver for com.aimp.service.action.* — AIMP broadcasts a play/next
 *     intent on track change regardless of notification state.
 */
public class AIMPMediaListener extends NotificationListenerService {

    private static final String TAG = "AIMPMediaListener";
    static final String AIMP_PACKAGE = "com.aimp.player";

    private static AIMPMediaListener instance;
    public static AIMPMediaListener getInstance() { return instance; }

    private MediaSessionManager mediaSessionManager;
    private CarLedService ledService;

    // Strong reference prevents GC from silently killing the registered callback
    private MediaController currentController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Dedup: skip palette if same track title AND same bitmap pixels as last run
    private int lastGenerationId = -1;
    private String lastTitle = null;
    private boolean artRetryScheduled = false;

    public void attachLedService(CarLedService svc) {
        ledService = svc;
        // Apply colors for the currently playing track immediately
        if (currentController != null) processMetadata(currentController.getMetadata());
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);

        // Trigger 3: AIMP broadcast intents (play, next, previous, etc.)
        IntentFilter f = new IntentFilter();
        f.addAction("com.aimp.service.action.play");
        f.addAction("com.aimp.service.action.next");
        f.addAction("com.aimp.service.action.previous");
        f.addAction("com.aimp.service.action.play_pause");
        f.addAction("com.aimp.service.action.stop");
        registerReceiver(aimpBroadcastReceiver, f, Context.RECEIVER_EXPORTED);

        startMonitoring();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(aimpBroadcastReceiver);
        if (currentController != null) {
            currentController.unregisterCallback(sessionCallback);
            currentController = null;
        }
        instance = null;
    }

    // -------------------------------------------------------------------------
    // Trigger 2: notification posted/updated

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (AIMP_PACKAGE.equals(sbn.getPackageName())) {
            Log.d(TAG, "AIMP notification posted — checking session");
            ensureLedServiceRunning();
            checkAIMPSession();
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {}

    // -------------------------------------------------------------------------
    // Trigger 3: AIMP broadcast

    private final BroadcastReceiver aimpBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            Log.d(TAG, "AIMP broadcast: " + intent.getAction());
            // Small delay: AIMP may not have updated MediaSession metadata yet
            mainHandler.postDelayed(() -> checkAIMPSession(), 300);
        }
    };

    // -------------------------------------------------------------------------

    private void ensureLedServiceRunning() {
        if (ledService == null) AutoStartReceiver.startLedService(this);
    }

    private void startMonitoring() {
        if (mediaSessionManager == null) return;
        ComponentName self = new ComponentName(this, AIMPMediaListener.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                controllers -> {
                    Log.d(TAG, "Active sessions changed");
                    checkAIMPSession();
                }, self);
            checkAIMPSession();
        } catch (SecurityException e) {
            Log.w(TAG, "No notification access: " + e.getMessage());
        }
    }

    private void checkAIMPSession() {
        if (mediaSessionManager == null) return;
        ComponentName self = new ComponentName(this, AIMPMediaListener.class);
        try {
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(self);
            boolean found = false;
            for (MediaController mc : controllers) {
                if (!AIMP_PACKAGE.equals(mc.getPackageName())) continue;
                found = true;

                // Re-register only if the session token changed
                if (currentController == null
                        || !currentController.getSessionToken().equals(mc.getSessionToken())) {
                    if (currentController != null)
                        currentController.unregisterCallback(sessionCallback);

                    // *** Register on main-thread Handler — critical fix ***
                    // Without this, callbacks dispatched on a Binder thread are silently
                    // dropped when that thread has no Looper.
                    mc.registerCallback(sessionCallback, mainHandler);
                    currentController = mc;
                    Log.d(TAG, "Registered callback on AIMP session");
                }

                processMetadata(mc.getMetadata());
                break;
            }
            if (!found && currentController != null) {
                Log.d(TAG, "AIMP session gone");
                currentController.unregisterCallback(sessionCallback);
                currentController = null;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Session access denied: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Trigger 1: metadata callback — now dispatched on main thread

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            Log.d(TAG, "onMetadataChanged fired");
            processMetadata(metadata);
        }
        @Override public void onSessionDestroyed() {
            Log.d(TAG, "AIMP session destroyed");
            currentController = null;
            lastGenerationId = -1;
            lastTitle = null;
        }
    };

    // -------------------------------------------------------------------------

    private void processMetadata(MediaMetadata meta) {
        if (meta == null) return;

        Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) {
            // Art not ready yet — schedule one retry so we catch it when it arrives
            Log.i(TAG, "No album art in metadata, retrying in 1.5s");
            if (!artRetryScheduled) {
                artRetryScheduled = true;
                mainHandler.postDelayed(() -> {
                    artRetryScheduled = false;
                    if (currentController != null)
                        processMetadata(currentController.getMetadata());
                }, 1500);
            }
            return;
        }
        artRetryScheduled = false;

        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        int genId = art.getGenerationId();
        // Skip only when BOTH title and bitmap are identical to last processed track
        if (genId == lastGenerationId && Objects.equals(title, lastTitle)) {
            Log.d(TAG, "Same track/art (genId=" + genId + "), skipping");
            return;
        }
        lastGenerationId = genId;
        lastTitle = title;

        Log.i(TAG, "Processing art for: " + title + " (genId=" + genId + ")");

        final Bitmap finalArt = art;
        Palette.from(finalArt).maximumColorCount(24).generate(palette -> {
            if (palette == null) { Log.w(TAG, "Palette returned null"); return; }
            if (ledService == null) {
                Log.w(TAG, "ledService is null — starting it");
                ensureLedServiceRunning(); // attachLedService() will reprocess this track
                return;
            }

            Map<ColorMode, Integer> colors = computeAllModes(palette, finalArt);
            Log.i(TAG, "Album colors: vibrant=#"
                + Integer.toHexString(colors.getOrDefault(ColorMode.VIBRANT, 0))
                + " avg=#" + Integer.toHexString(colors.getOrDefault(ColorMode.PIXEL_AVERAGE, 0)));
            ledService.onAlbumColors(colors);
        });
    }

    // -------------------------------------------------------------------------
    // Color extraction

    static Map<ColorMode, Integer> computeAllModes(Palette palette, Bitmap art) {
        Map<ColorMode, Integer> out = new EnumMap<>(ColorMode.class);

        int vibrant      = swatch(palette.getVibrantSwatch(),      palette);
        int dominant     = swatch(palette.getDominantSwatch(),     palette);
        int lightVibrant = swatch(palette.getLightVibrantSwatch(), palette);
        int darkVibrant  = swatch(palette.getDarkVibrantSwatch(),  palette);
        int muted        = swatch(palette.getMutedSwatch(),        palette);

        out.put(ColorMode.VIBRANT,       vibrant);
        out.put(ColorMode.DOMINANT,      dominant);
        out.put(ColorMode.LIGHT_VIBRANT, lightVibrant != 0 ? lightVibrant : vibrant);
        out.put(ColorMode.DARK_VIBRANT,  darkVibrant  != 0 ? darkVibrant  : vibrant);
        out.put(ColorMode.MUTED,         muted        != 0 ? muted        : dominant);
        out.put(ColorMode.PIXEL_AVERAGE,  pixelAverage(art));
        out.put(ColorMode.PIXEL_COUNT,    pixelMedian(art));
        out.put(ColorMode.PIXEL_DOMINANT, pixelDominant(art));
        out.put(ColorMode.PIXEL_MEDIAN,   pixelChannelMedian(art));

        return out;
    }

    private static int swatch(Palette.Swatch sw, Palette fallback) {
        if (sw != null) return sw.getRgb();
        Palette.Swatch d = fallback.getDominantSwatch();
        return d != null ? d.getRgb() : 0xFF_FFFFFF;
    }

    private static int pixelAverage(Bitmap art) {
        Bitmap tiny = Bitmap.createScaledBitmap(art, 1, 1, true);
        int color = tiny.getPixel(0, 0);
        tiny.recycle();
        return color;
    }

    /**
     * Saturation-weighted mean in HSV space.
     *
     * Weighting by saturation means washed-out near-gray pixels (low S) barely
     * contribute, so the result tracks the genuinely coloured mid-tones rather
     * than the achromatic background wash.  Hue is averaged circularly (sin/cos)
     * to handle images whose hue wraps across the 0°/360° boundary.
     *
     * Falls back to pixelAverage() when the image is entirely achromatic
     * (e.g. a black-and-white photo).
     */
    private static int pixelMedian(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, 24, 24, true);
        float[] hsv = new float[3];
        double wSinH = 0, wCosH = 0;
        float wS = 0, wV = 0, total = 0;

        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                int px = small.getPixel(x, y);
                if (Math.max(Math.max(Color.red(px), Color.green(px)), Color.blue(px)) < 64)
                    continue;
                Color.colorToHSV(px, hsv);
                float w = hsv[1]; // weight = saturation; near-gray → near-zero weight
                if (w < 0.08f) continue;
                double rad = Math.toRadians(hsv[0]);
                wSinH += Math.sin(rad) * w;
                wCosH += Math.cos(rad) * w;
                wS    += hsv[1] * w;
                wV    += hsv[2] * w;
                total += w;
            }
        }
        small.recycle();

        if (total == 0) return pixelAverage(art); // achromatic image — fall back to avg
        float hue = (float) Math.toDegrees(Math.atan2(wSinH, wCosH));
        if (hue < 0) hue += 360;
        hsv[0] = hue;
        hsv[1] = wS / total;
        hsv[2] = wV / total;
        return Color.HSVToColor(hsv);
    }

    private static int pixelChannelMedian(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, 24, 24, true);
        int n = 24 * 24;
        int[] rs = new int[n], gs = new int[n], bs = new int[n];
        int count = 0;
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                int px = small.getPixel(x, y);
                if (Math.max(Math.max(Color.red(px), Color.green(px)), Color.blue(px)) < 64)
                    continue;
                rs[count] = Color.red(px);
                gs[count] = Color.green(px);
                bs[count] = Color.blue(px);
                count++;
            }
        }
        small.recycle();
        if (count == 0) return Color.WHITE;
        Arrays.sort(rs, 0, count);
        Arrays.sort(gs, 0, count);
        Arrays.sort(bs, 0, count);
        return Color.rgb(rs[count / 2], gs[count / 2], bs[count / 2]);
    }

    private static int pixelDominant(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, 24, 24, true);
        java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
        int best = 0, bestCount = 0;
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                int px = small.getPixel(x, y);
                if (Math.max(Math.max(Color.red(px), Color.green(px)), Color.blue(px)) < 40)
                    continue;
                int key = Color.rgb((Color.red(px) >> 4) << 4,
                                    (Color.green(px) >> 4) << 4,
                                    (Color.blue(px) >> 4) << 4);
                int c = counts.getOrDefault(key, 0) + 1;
                counts.put(key, c);
                if (c > bestCount) { bestCount = c; best = key; }
            }
        }
        small.recycle();
        return bestCount > 0 ? best : Color.WHITE;
    }
}
