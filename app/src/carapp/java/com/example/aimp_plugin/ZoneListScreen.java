package com.example.aimp_plugin;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
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
 */
public class ZoneListScreen extends Screen implements ZoneManager.Listener {

    private final ZoneManager zm;

    public ZoneListScreen(@NonNull CarContext carContext) {
        super(carContext);
        this.zm = ZoneManager.getInstance(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) { zm.addListener(ZoneListScreen.this); }
            @Override public void onStop(@NonNull LifecycleOwner owner)  { zm.removeListener(ZoneListScreen.this); }
        });
    }

    @Override public void onZonesChanged() { invalidate(); }

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

        return new ListTemplate.Builder()
            .setTitle("AIMP Car LED")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build();
    }
}
