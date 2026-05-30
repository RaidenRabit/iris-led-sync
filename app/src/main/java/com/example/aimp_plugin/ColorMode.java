package com.example.aimp_plugin;

/** How a zone derives its color from album art. */
public enum ColorMode {
    VIBRANT        ("Vibrant",   "Saturated mid-tone — most visually striking"),
    DOMINANT       ("Dominant",  "Most visually prominent swatch (Palette weighted)"),
    LIGHT_VIBRANT  ("Light",     "Bright saturated variant of the dominant hue"),
    DARK_VIBRANT   ("Dark",      "Deep saturated variant — dramatic look"),
    MUTED          ("Muted",     "Desaturated — gentle ambient light"),
    PIXEL_AVERAGE  ("Average",   "Arithmetic mean of every pixel — smooth blend"),
    PIXEL_COUNT    ("Most Common","Literal most-frequent colour after quantisation");

    public final String label;
    public final String description;

    ColorMode(String label, String description) {
        this.label       = label;
        this.description = description;
    }
}
