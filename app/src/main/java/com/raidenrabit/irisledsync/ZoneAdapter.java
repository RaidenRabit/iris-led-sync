package com.raidenrabit.irisledsync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;
import java.util.Locale;

/**
 * Adapter for zone cards.
 *
 * Implements two separate listener interfaces:
 *  - ZoneManager.Listener   → full rebind (enable/mode/name changes, mode switch)
 *  - ZoneManager.ColorListener → partial bind (color preview only, fired at 30 fps during lerp)
 *
 * The partial bind path uses a String payload so RecyclerView never redraws the
 * entire card during a colour transition — only the preview strip updates.
 *
 * Knobs send live (throttled, unsaved) previews to the LEDs while dragging and commit
 * the value when the finger lifts. The brightness knob runs on BrightnessCurve and is
 * capped to the current mode's maximum.
 *
 * Knobs always reflect the CURRENT mode's values: when day/night mode switches,
 * ZoneManager reloads zones from the appropriate prefs namespace, notifyListeners()
 * fires, and the full rebind path updates all knob positions automatically.
 */
public class ZoneAdapter extends RecyclerView.Adapter<ZoneAdapter.ViewHolder>
        implements ZoneManager.Listener, ZoneManager.ColorListener {

    private static final String PAYLOAD_COLOR = "color";

    private static final int BRIGHTNESS_KNOB_DP = 120;
    private static final int PREVIEW_HEIGHT_DAY_DP   = 72;
    private static final int PREVIEW_HEIGHT_NIGHT_DP = 48;

    private final Context context;
    private final ZoneManager zm;
    private final boolean[]   advancedOpen = new boolean[ZoneManager.ZONE_COUNT];

    public ZoneAdapter(Context context) {
        this.context = context;
        zm = ZoneManager.getInstance(context);
    }

    public void register() {
        zm.addListener(this);
        zm.addColorListener(this);
        notifyDataSetChanged();
    }

    public void unregister() {
        zm.removeListener(this);
        zm.removeColorListener(this);
    }

    @Override public void onZonesChanged() { notifyDataSetChanged(); }

    @Override public void onEffectiveColors(int[] colors) {
        for (int i = 0; i < ZoneManager.ZONE_COUNT; i++) {
            notifyItemChanged(i, PAYLOAD_COLOR);
        }
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_zone_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_COLOR)) {
            h.updatePreview(zm.getEffectiveColor(pos));
        } else {
            onBindViewHolder(h, pos);
        }
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        h.bind(zm.getZone(pos), zm.getEffectiveColor(pos));
    }

    @Override public int getItemCount() { return ZoneManager.ZONE_COUNT; }

    // -------------------------------------------------------------------------

    class ViewHolder extends RecyclerView.ViewHolder {

        private final View         previewFrame;
        private final View         colorPreview;
        private final TextView     tvColorHex;
        private final TextView       tvName;
        private final View           btnRename;
        private final SwitchMaterial switchEnabled;
        private final View           rowBrightness, rowHue, rowSaturation;
        private final ChipGroup      chipGroupColor;
        private final ChipGroup      chipGroupMode;
        private final View           colorSwatch;
        private final View           layoutAdvanced;
        private final com.google.android.material.button.MaterialButton btnAdvanced;
        private final com.google.android.material.button.MaterialButton btnReset;

        private boolean binding = false;

        ViewHolder(View v) {
            super(v);
            previewFrame   = v.findViewById(R.id.preview_frame);
            colorPreview   = v.findViewById(R.id.color_preview);
            tvColorHex     = v.findViewById(R.id.tv_color_hex);
            tvName         = v.findViewById(R.id.tv_zone_name);
            btnRename      = v.findViewById(R.id.btn_rename);
            switchEnabled  = v.findViewById(R.id.switch_enabled);
            rowBrightness  = v.findViewById(R.id.row_brightness);
            rowHue         = v.findViewById(R.id.row_hue);
            rowSaturation  = v.findViewById(R.id.row_saturation);
            chipGroupColor = v.findViewById(R.id.chip_group_color);
            chipGroupMode  = v.findViewById(R.id.chip_group_mode);
            colorSwatch    = v.findViewById(R.id.color_swatch);
            layoutAdvanced = v.findViewById(R.id.layout_advanced);
            btnAdvanced    = v.findViewById(R.id.btn_advanced);
            btnReset       = v.findViewById(R.id.btn_reset);

            View brightnessKnob = rowBrightness.findViewById(R.id.knob);
            ViewGroup.LayoutParams lp = brightnessKnob.getLayoutParams();
            lp.width = lp.height = dp(BRIGHTNESS_KNOB_DP);
            brightnessKnob.setLayoutParams(lp);
        }

        void bind(LedZone zone, int effectiveColor) {
            binding = true;

            updatePreview(effectiveColor);
            tvName.setText(zone.name);
            switchEnabled.setChecked(zone.enabled);

            boolean isNight = DayNightManager.getInstance(context).isNightActive();
            int maxBrightness = ZoneManager.maxBrightness(isNight);

            // Night: shorter preview strip, no hex code — less glowing area on screen
            ViewGroup.LayoutParams lp = previewFrame.getLayoutParams();
            lp.height = dp(isNight ? PREVIEW_HEIGHT_NIGHT_DP : PREVIEW_HEIGHT_DAY_DP);
            previewFrame.setLayoutParams(lp);
            tvColorHex.setVisibility(isNight ? View.GONE : View.VISIBLE);

            bindKnob(rowBrightness, "Brightness", 0, BrightnessCurve.POSITIONS,
                BrightnessCurve.toPosition(zone.brightness, maxBrightness),
                v -> BrightnessCurve.toPercent(v, maxBrightness) + "%",
                v -> zm.getZone(zone.index).withBrightness(BrightnessCurve.toPercent(v, maxBrightness)));

            bindKnob(rowHue, "Hue shift", 0, 360, zone.hueOffset + 180,
                v -> (v - 180 >= 0 ? "+" : "") + (v - 180) + "\u00b0",
                v -> zm.getZone(zone.index).withHueOffset(v - 180));

            bindKnob(rowSaturation, "Intensity", 0, 400, zone.saturation,
                v -> v + "%",
                v -> zm.getZone(zone.index).withSaturation(v));

            layoutAdvanced.setVisibility(advancedOpen[zone.index] ? View.VISIBLE : View.GONE);
            btnAdvanced.setText(advancedOpen[zone.index] ? "Advanced \u25B4" : "Advanced \u25BE");
            btnAdvanced.setOnClickListener(v -> {
                advancedOpen[zone.index] = !advancedOpen[zone.index];
                notifyItemChanged(zone.index);
            });

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

            buildModeChips(zone);

            // Reset to mode-appropriate defaults (night resets dim, not to full)
            btnReset.setOnClickListener(v -> zm.updateZone(
                zm.getZone(zone.index)
                    .withBrightness(ZoneManager.defaultBrightness(isNight))
                    .withHueOffset(0)
                    .withSaturation(ZoneManager.defaultSaturation(isNight))));

            tvName.setOnClickListener(v -> showRenameDialog(zone));
            btnRename.setOnClickListener(v -> showRenameDialog(zone));
            switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                if (!binding) zm.updateZone(zm.getZone(zone.index).withEnabled(checked));
            });

            binding = false;
        }

        void updatePreview(int effectiveColor) {
            colorPreview.setBackgroundColor(effectiveColor);
            tvColorHex.setText(String.format(Locale.ROOT, "#%06X", effectiveColor & 0xFFFFFF));
        }

        private void bindKnob(View row, String label, int min, int max,
                              int value, LabelFn labelFn, ZoneFn zoneFor) {
            ((TextView) row.findViewById(R.id.tv_label)).setText(label);
            KnobView knob = row.findViewById(R.id.knob);

            knob.setListener(null);
            knob.setRange(min, max);
            knob.setValue(value);
            knob.setValueText(labelFn.label(knob.getValue()));
            knob.setContentDescription(label);

            knob.setListener(new KnobView.Listener() {
                @Override public void onChange(int v) {
                    knob.setValueText(labelFn.label(v));
                    zm.previewZone(zoneFor.zone(v));
                }
                @Override public void onStop(int v) { zm.updateZone(zoneFor.zone(v)); }
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

            GridLayout grid = new GridLayout(context);
            grid.setColumnCount(4);
            grid.setPadding(dp(16), dp(16), dp(16), 0);
            AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Pick color for " + zone.name)
                .setView(grid)
                .setNegativeButton("Cancel", null)
                .create();

            for (int i = 0; i < presets.length; i++) {
                int color = presets[i];
                View swatch = new View(context);
                GradientDrawable bg = (GradientDrawable) context.getDrawable(R.drawable.color_swatch_bg).mutate();
                bg.setColor(color);
                swatch.setBackground(bg);
                swatch.setContentDescription(names[i]);
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                glp.width  = dp(48);
                glp.height = dp(48);
                glp.setMargins(dp(8), dp(8), dp(8), dp(8));
                swatch.setLayoutParams(glp);
                swatch.setOnClickListener(v -> {
                    zm.updateZone(zm.getZone(zone.index).withColor(color).withAutoColor(false));
                    dialog.dismiss();
                });
                grid.addView(swatch);
            }
            dialog.show();
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    interface LabelFn { String label(int v); }
    interface ZoneFn  { LedZone zone(int v); }
}
