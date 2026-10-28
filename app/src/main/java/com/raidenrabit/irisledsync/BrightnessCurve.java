package com.raidenrabit.irisledsync;

/**
 * Maps a control position (0..POSITIONS) to a brightness percentage on a squared curve.
 *
 * LEDs look much brighter than their linear value at the low end, so a linear control
 * crams the whole useful night range into the first few degrees. Squaring the position
 * spreads the dim end out: at half travel you get a quarter of the maximum.
 */
final class BrightnessCurve {

    static final int POSITIONS = 1000;

    private BrightnessCurve() {}

    static int toPercent(int position, int maxPercent) {
        float f = position / (float) POSITIONS;
        return Math.round(maxPercent * f * f);
    }

    static int toPosition(int percent, int maxPercent) {
        float f = Math.min(1f, Math.max(0f, percent / (float) maxPercent));
        return Math.round((float) Math.sqrt(f) * POSITIONS);
    }
}
