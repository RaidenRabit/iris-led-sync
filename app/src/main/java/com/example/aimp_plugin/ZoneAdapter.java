package com.example.aimp_plugin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;
import java.util.Locale;

/**
 * Adapter for zone cards.
 *
 * Implements two separate listener interfaces:
 *  - ZoneManager.Listener   → full rebind (enable/mode/name changes)
 *  - ZoneManager.ColorListener → partial bind (color preview only, fired at 30 fps during lerp)
 *
 * The partial bind path uses a String payload so RecyclerView never redraws the
 * entire card during a colour transition — only the preview strip updates.
 */
public class ZoneAdapter extends RecyclerView.Adapter<ZoneAdapter.ViewHolder>
        implements ZoneManager.Listener, ZoneManager.ColorListener {

    private static final String PAYLOAD_COLOR = "color";

    private final Context context;
    private final ZoneManager zm;

    public ZoneAdapter(Context context) {
        this.context = context;
        zm = ZoneManager.getInstance(context);
    }

    public void register() {
        zm.addListener(this);
        zm.addColorListener(this);
        notifyDataSetChanged(); // paint headers with current effectiveColors immediately
    }

    public void unregister() {
        zm.removeListener(this);
        zm.removeColorListener(this);
    }

    // Full rebind on structural changes
    @Override public void onZonesChanged() { notifyDataSetChanged(); }

    // Partial rebind on colour changes (30 fps during lerp)
    @Override public void onEffectiveColors(int[] colors) {
        for (int i = 0; i < ZoneManager.ZONE_COUNT; i++) {
            notifyItemChanged(i, PAYLOAD_COLOR);
        }
    }

    // -------------------------------------------------------------------------

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_zone_card, parent, false);
        return new ViewHolder(v);
    }

    /** Partial bind — only updates the colour preview strip. */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_COLOR)) {
            h.updatePreview(zm.getEffectiveColor(pos));
        } else {
            onBindViewHolder(h, pos); // full bind
        }
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        h.bind(zm.getZone(pos), zm.getEffectiveColor(pos));
    }

    @Override public int getItemCount() { return ZoneManager.ZONE_COUNT; }

    // -------------------------------------------------------------------------

    class ViewHolder extends RecyclerView.ViewHolder {

        // Preview bar views
        private final View         colorPreview;
        private final TextView     tvColorHex;

        // Zone controls
        private final TextView       tvName;
        private final SwitchMaterial switchEnabled;
        private final View           rowBrightness, rowHue, rowSaturation;
        private final ChipGroup      chipGroupColor;
        private final ChipGroup      chipGroupMode;
        private final View           colorSwatch;
        private final com.google.android.material.button.MaterialButton btnReset;

        private boolean binding = false;

        ViewHolder(View v) {
            super(v);
            colorPreview   = v.findViewById(R.id.color_preview);
            tvColorHex     = v.findViewById(R.id.tv_color_hex);
            tvName         = v.findViewById(R.id.tv_zone_name);
            switchEnabled  = v.findViewById(R.id.switch_enabled);
            rowBrightness  = v.findViewById(R.id.row_brightness);
            rowHue         = v.findViewById(R.id.row_hue);
            rowSaturation  = v.findViewById(R.id.row_saturation);
            chipGroupColor = v.findViewById(R.id.chip_group_color);
            chipGroupMode  = v.findViewById(R.id.chip_group_mode);
            colorSwatch    = v.findViewById(R.id.color_swatch);
            btnReset       = v.findViewById(R.id.btn_reset);
        }

        /** Full bind — called on structural changes. */
        void bind(LedZone zone, int effectiveColor) {
            binding = true;

            updatePreview(effectiveColor);
            tvName.setText(zone.name);
            switchEnabled.setChecked(zone.enabled);

            // Sliders — onChange always reads the LIVE zone to avoid stale overwrite
            bindSlider(rowBrightness, "Brightness", 0, 255, 1, zone.brightness,
                v -> Math.round(v / 2.55f) + "%",
                v -> zm.updateZone(zm.getZone(zone.index).withBrightness(v)));

            bindSlider(rowHue, "Hue shift", 0, 360, 1, zone.hueOffset + 180,
                v -> (v - 180 >= 0 ? "+" : "") + (v - 180) + "°",
                v -> zm.updateZone(zm.getZone(zone.index).withHueOffset(v - 180)));

            bindSlider(rowSaturation, "Intensity", 0, 200, 1, zone.saturation,
                v -> v + "%",
                v -> zm.updateZone(zm.getZone(zone.index).withSaturation(v)));

            // Auto vs Manual
            chipGroupColor.check(zone.autoColor ? R.id.chip_auto : R.id.chip_manual);
            chipGroupMode.setVisibility(zone.autoColor ? View.VISIBLE : View.GONE);
            updateSwatch(zone);

            chipGroupColor.setOnCheckedStateChangeListener((g, ids) -> {
                if (binding || ids.isEmpty()) return;
                boolean auto = ids.get(0) == R.id.chip_auto;
                chipGroupMode.setVisibility(auto ? View.VISIBLE : View.GONE);
                zm.updateZone(zm.getZone(zone.index).withAutoColor(auto));
            });

            colorSwatch.setOnClickListener(v -> showColorPicker(zone));

            // Color mode chips
            buildModeChips(zone);

            btnReset.setOnClickListener(v -> zm.updateZone(
                zm.getZone(zone.index).withBrightness(255).withHueOffset(0).withSaturation(130)));

            tvName.setOnLongClickListener(v -> { showRenameDialog(zone); return true; });
            switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                if (!binding) zm.updateZone(zm.getZone(zone.index).withEnabled(checked));
            });

            binding = false;
        }

        /** Partial bind — only colour strip. Called at every lerp step. */
        void updatePreview(int effectiveColor) {
            colorPreview.setBackgroundColor(effectiveColor);
            tvColorHex.setText(String.format(Locale.ROOT, "#%06X", effectiveColor & 0xFFFFFF));
        }

        // ---- helpers ---------------------------------------------------------

        private void bindSlider(View row, String label, int min, int max, int step,
                                int value, LabelFn labelFn, IntConsumer onChange) {
            ((TextView) row.findViewById(R.id.tv_label)).setText(label);
            Slider   slider  = row.findViewById(R.id.slider);
            TextView tvValue = row.findViewById(R.id.tv_value);

            slider.setValueFrom(min);
            slider.setValueTo(max);
            slider.setStepSize(step);
            slider.setValue(Math.max(min, Math.min(max, value)));
            tvValue.setText(labelFn.label(value));

            slider.clearOnSliderTouchListeners();
            slider.clearOnChangeListeners();

            slider.addOnChangeListener((s, v, fromUser) -> {
                if (fromUser) tvValue.setText(labelFn.label((int) v));
            });
            slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override public void onStartTrackingTouch(@NonNull Slider s) {}
                @Override public void onStopTrackingTouch(@NonNull Slider s) {
                    onChange.accept((int) s.getValue());
                }
            });
        }

        private void buildModeChips(LedZone zone) {
            chipGroupMode.removeAllViews();
            for (ColorMode mode : ColorMode.values()) {
                Chip chip = new Chip(context);
                chip.setCheckable(true);
                chip.setText(mode.label);
                chip.setChecked(zone.colorMode == mode);
                chip.setOnClickListener(v -> {
                    zm.updateZone(zm.getZone(zone.index).withColorMode(mode));
                    zm.reapplyAlbumColors();
                });
                chipGroupMode.addView(chip);
            }
        }

        private void updateSwatch(LedZone zone) {
            boolean manual = !zone.autoColor;
            colorSwatch.setVisibility(manual ? View.VISIBLE : View.GONE);
            if (manual) {
                ((GradientDrawable) colorSwatch.getBackground().mutate())
                    .setColor(zone.color | 0xFF000000);
            }
        }

        private void showRenameDialog(LedZone zone) {
            android.widget.EditText et = new android.widget.EditText(context);
            et.setText(zone.name);
            et.selectAll();
            new AlertDialog.Builder(context)
                .setTitle("Rename zone")
                .setView(et)
                .setPositiveButton("Rename", (d, w) ->
                    zm.updateZone(zm.getZone(zone.index).withName(et.getText().toString())))
                .setNegativeButton("Cancel", null)
                .show();
        }

        private void showColorPicker(LedZone zone) {
            int[] presets = {
                0xFF_FF0000, 0xFF_FF6600, 0xFF_FFFF00, 0xFF_00FF00,
                0xFF_00FFFF, 0xFF_0066FF, 0xFF_8800FF, 0xFF_FF00AA,
                0xFF_FFFFFF, 0xFF_888888, 0xFF_FF3333, 0xFF_33FF99
            };
            String[] names = {"Red","Orange","Yellow","Green","Cyan","Blue","Purple","Pink",
                              "White","Grey","Coral","Mint"};
            new AlertDialog.Builder(context)
                .setTitle("Pick color for " + zone.name)
                .setItems(names, (d, which) ->
                    zm.updateZone(zm.getZone(zone.index).withColor(presets[which]).withAutoColor(false)))
                .show();
        }
    }

    interface LabelFn    { String label(int v); }
    interface IntConsumer { void accept(int v); }
}
