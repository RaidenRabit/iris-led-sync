package com.raidenrabit.irisledsync;

/** Immutable snapshot of one LED zone's configuration. */
public final class LedZone {

    public final int       index;
    public final String    name;
    public final boolean   enabled;
    public final int       brightness;   // 0-400; multiplier = brightness/100 (100 = natural, 300 = 3× boost)
    public final boolean   autoColor;    // follow album art
    public final ColorMode colorMode;    // which extraction algorithm to use
    public final int       color;        // ARGB — current raw color before hue/sat transform
    public final int       hueOffset;    // -180..+180 degrees
    public final int       saturation;   // 0-400; multiplier = saturation/100 (100 = natural, 250 = vivid boost)

    public LedZone(int index, String name, boolean enabled,
                   int brightness, boolean autoColor, ColorMode colorMode,
                   int color, int hueOffset, int saturation) {
        this.index      = index;
        this.name       = name;
        this.enabled    = enabled;
        this.brightness = clamp(brightness, 0, 400);
        this.autoColor  = autoColor;
        this.colorMode  = colorMode;
        this.color      = color;
        this.hueOffset  = clamp(hueOffset, -180, 180);
        this.saturation = clamp(saturation, 0, 400);
    }

    public LedZone withEnabled(boolean v)    { return new LedZone(index,name,v,brightness,autoColor,colorMode,color,hueOffset,saturation); }
    public LedZone withBrightness(int v)     { return new LedZone(index,name,enabled,v,autoColor,colorMode,color,hueOffset,saturation); }
    public LedZone withAutoColor(boolean v)  { return new LedZone(index,name,enabled,brightness,v,colorMode,color,hueOffset,saturation); }
    public LedZone withColorMode(ColorMode v){ return new LedZone(index,name,enabled,brightness,autoColor,v,color,hueOffset,saturation); }
    public LedZone withColor(int v)          { return new LedZone(index,name,enabled,brightness,autoColor,colorMode,v,hueOffset,saturation); }
    public LedZone withHueOffset(int v)      { return new LedZone(index,name,enabled,brightness,autoColor,colorMode,color,v,saturation); }
    public LedZone withSaturation(int v)     { return new LedZone(index,name,enabled,brightness,autoColor,colorMode,color,hueOffset,v); }
    public LedZone withName(String v) {
        String n = v.trim(); return new LedZone(index,n.isEmpty()?name:n,enabled,brightness,autoColor,colorMode,color,hueOffset,saturation);
    }

    public String brightnessPercent() { return brightness + "%"; }
    public String hueOffsetLabel()    { return (hueOffset >= 0 ? "+" : "") + hueOffset + "°"; }
    public String saturationLabel()   { return saturation + "%"; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
