package com.raidenrabit.irisledsync;

/** How a zone derives its color from album art. */
public enum ColorMode {
    VIBRANT        ("Vibrant",   "Saturated mid-tone — most visually striking"),
    DOMINANT       ("Dominant",  "Most visually prominent swatch (Palette weighted)"),
    LIGHT_VIBRANT  ("Light",     "Bright saturated variant of the dominant hue"),
    DARK_VIBRANT   ("Dark",      "Deep saturated variant — dramatic look"),
    MUTED          ("Muted",     "Desaturated — gentle ambient light"),
    PIXEL_AVERAGE  ("Average",   "Arithmetic mean of every pixel — smooth blend"),
    PIXEL_COUNT    ("Tonal",      "Saturation-weighted hue mean — tracks the image's colour character"),
    PIXEL_DOMINANT ("Most Common","Literal most-frequent colour after quantisation"),
    PIXEL_MEDIAN   ("Median",     "Per-channel median across non-dark pixels");

    public final String label;
    public final String description;

    ColorMode(String label, String description) {
        this.label       = label;
        this.description = description;
    }
}
