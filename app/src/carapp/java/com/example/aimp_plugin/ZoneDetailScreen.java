package com.example.aimp_plugin;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Android Auto per-zone detail screen.
 *
 * Rows: Brightness, Hue shift, Intensity, Power, Color mode
 * ActionStrip: context-sensitive +/− for whichever parameter is "active"
 * (cycles: brightness → hue → intensity, toggled by a "Next param" action).
 */
public class ZoneDetailScreen extends Screen implements ZoneManager.Listener {

    private static final int BRIGHT_STEP = 26;   // ~10% of 255
    private static final int HUE_STEP    = 15;   // 15° per tap
    private static final int SAT_STEP    = 20;   // 10% of 200

    private final ZoneManager zm;
    private final int zoneIndex;

    // Cycle: 0=brightness, 1=hue, 2=saturation
    private int activeParam = 0;

    public ZoneDetailScreen(@NonNull CarContext ctx, int zoneIndex) {
        super(ctx);
        this.zm        = ZoneManager.getInstance(ctx);
        this.zoneIndex = zoneIndex;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner o) { zm.addListener(ZoneDetailScreen.this); }
            @Override public void onStop(@NonNull LifecycleOwner o)  { zm.removeListener(ZoneDetailScreen.this); }
        });
    }

    @Override public void onZonesChanged() { invalidate(); }

    @NonNull @Override
    public Template onGetTemplate() {
        LedZone zone = zm.getZone(zoneIndex);
        String[] paramNames = { "Brightness", "Hue shift", "Intensity" };

        Row brightnessRow = new Row.Builder()
            .setTitle("Brightness")
            .addText(zone.brightnessPercent() + (activeParam == 0 ? "  ◀  −10% / +10%" : ""))
            .build();

        Row hueRow = new Row.Builder()
            .setTitle("Hue shift")
            .addText(zone.hueOffsetLabel() + (activeParam == 1 ? "  ◀  −15° / +15°" : ""))
            .build();

        Row satRow = new Row.Builder()
            .setTitle("Intensity")
            .addText(zone.saturationLabel() + (activeParam == 2 ? "  ◀  −10% / +10%" : ""))
            .build();

        Row powerRow = new Row.Builder()
            .setTitle("Power")
            .addText(zone.enabled ? "On" : "Off")
            .setToggle(new Toggle.Builder(checked -> zm.updateZone(zone.withEnabled(checked)))
                .setChecked(zone.enabled).build())
            .build();

        Row colorRow = new Row.Builder()
            .setTitle("Color mode")
            .addText(zone.autoColor ? "Auto — " + zone.colorMode.label : "Manual")
            .setToggle(new Toggle.Builder(checked ->
                zm.updateZone(zm.getZone(zoneIndex).withAutoColor(checked)))
                .setChecked(zone.autoColor).build())
            .build();

        // Cycle through extraction presets (tap the action strip label)
        ColorMode[] modes = ColorMode.values();

        int nextModeIdx = (zone.colorMode.ordinal() + 1) % modes.length;

        ActionStrip strip = new ActionStrip.Builder()
            .addAction(new Action.Builder()
                .setTitle("−")
                .setOnClickListener(() -> adjust(zone, -1))
                .build())
            .addAction(new Action.Builder()
                .setTitle("+")
                .setOnClickListener(() -> adjust(zone, +1))
                .build())
            .addAction(new Action.Builder()
                .setTitle(paramNames[activeParam])
                .setOnClickListener(() -> { activeParam = (activeParam + 1) % 3; invalidate(); })
                .build())
            .addAction(new Action.Builder()
                .setTitle(zone.colorMode.label)
                .setOnClickListener(() ->
                    zm.updateZone(zm.getZone(zoneIndex).withColorMode(modes[nextModeIdx])))
                .build())
            .build();

        Pane pane = new Pane.Builder()
            .addRow(brightnessRow)
            .addRow(hueRow)
            .addRow(satRow)
            .addRow(powerRow)
            .addRow(colorRow)
            .build();

        return new PaneTemplate.Builder(pane)
            .setTitle(zone.name)
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
            .build();
    }

    private void adjust(LedZone zone, int dir) {
        LedZone live = zm.getZone(zoneIndex); // always use live state
        switch (activeParam) {
            case 0: zm.updateZone(live.withBrightness(live.brightness + dir * BRIGHT_STEP)); break;
            case 1: zm.updateZone(live.withHueOffset(live.hueOffset   + dir * HUE_STEP));   break;
            case 2: zm.updateZone(live.withSaturation(live.saturation + dir * SAT_STEP));   break;
        }
    }
}
