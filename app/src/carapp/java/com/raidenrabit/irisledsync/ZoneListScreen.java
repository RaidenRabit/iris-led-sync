package com.raidenrabit.irisledsync;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Android Auto main screen: shows all LED zones with name, brightness %, and a power toggle.
 * Tapping a zone navigates to {@link ZoneDetailScreen}.
 * The ActionStrip has a persistent mode button that cycles Day → Night → Auto.
 */
public class ZoneListScreen extends Screen implements ZoneManager.Listener, DayNightManager.Listener {

    private final ZoneManager     zm;
    private final DayNightManager dnm;

    public ZoneListScreen(@NonNull CarContext carContext) {
        super(carContext);
        this.zm  = ZoneManager.getInstance(carContext);
        this.dnm = DayNightManager.getInstance(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                zm.addListener(ZoneListScreen.this);
                dnm.addListener(ZoneListScreen.this);
            }
            @Override public void onStop(@NonNull LifecycleOwner owner) {
                zm.removeListener(ZoneListScreen.this);
                dnm.removeListener(ZoneListScreen.this);
            }
        });
    }

    @Override public void onZonesChanged()         { invalidate(); }
    @Override public void onModeChanged(boolean n) { invalidate(); }

    @NonNull @Override
    public Template onGetTemplate() {
        ItemList.Builder listBuilder = new ItemList.Builder();

        for (int i = 0; i < ZoneManager.ZONE_COUNT; i++) {
            final int idx = i;
            LedZone zone = zm.getZone(i);

            String subtitle = zone.brightnessPercent()
                + " · " + (zone.autoColor ? "Auto color" : "Manual color")
                + (zone.enabled ? "" : " · OFF");

            Row row = new Row.Builder()
                .setTitle(zone.name)
                .addText(subtitle)
                .setOnClickListener(() ->
                    getScreenManager().push(new ZoneDetailScreen(getCarContext(), idx)))
                .setToggle(new Toggle.Builder(checked -> {
                    zm.updateZone(zone.withEnabled(checked));
                    invalidate();
                }).setChecked(zone.enabled).build())
                .build();

            listBuilder.addItem(row);
        }

        ActionStrip actionStrip = new ActionStrip.Builder()
            .addAction(new Action.Builder()
                .setTitle(modeLabel())
                .setOnClickListener(this::cycleMode)
                .build())
            .build();

        return new ListTemplate.Builder()
            .setTitle("AIMP Car LED")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .setSingleList(listBuilder.build())
            .build();
    }

    private String modeLabel() {
        switch (dnm.getMode()) {
            case DAY:   return "Day";
            case NIGHT: return "Night";
            default:    return dnm.isNightActive() ? "Auto:Night" : "Auto:Day";
        }
    }

    private void cycleMode() {
        DayNightManager.Mode next;
        switch (dnm.getMode()) {
            case DAY:   next = DayNightManager.Mode.NIGHT; break;
            case NIGHT: next = DayNightManager.Mode.AUTO;  break;
            default:    next = DayNightManager.Mode.DAY;   break;
        }
        dnm.setMode(next);
    }
}
