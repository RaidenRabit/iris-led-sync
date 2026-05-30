package com.example.aimp_plugin;

import android.graphics.Color;

/**
 * BLE protocol for the car LED controller (com.szraise.carled, Ls8/* classes).
 *
 * Packet:  [0x2E][cmd][len][data…][checksum]
 * checksum = (cmd + len + sum(data)) XOR 0xFF
 *
 * ColorControlCmd  (Ls8/c): cmd=0x8D, data=[0x01, 0x08, R1,G1,B1, R2,G2,B2]
 * BrightnessCmd    (Ls8/b): cmd=0x8D, data=[0x00, type, b1, b2]
 *   type 0x48 = switch ON  (0x40) + solid mode (0x08)
 *   type 0x08 = switch OFF (0x00) + solid mode (0x08)
 */
public final class CarLedProtocol {

    static final String SERVICE_UUID     = "0000AE30-0000-1000-8000-00805f9b34fb";
    static final String WRITE_CHAR_UUID  = "0000AE01-0000-1000-8000-00805f9b34fb";
    static final String NOTIFY_CHAR_UUID = "0000AE02-0000-1000-8000-00805f9b34fb";
    static final String CCCD_UUID        = "00002902-0000-1000-8000-00805f9b34fb";

    private static final byte HEADER    = 0x2E;
    private static final byte CMD_COLOR = (byte) 0x8D;

    private CarLedProtocol() {}

    // ---- color ---------------------------------------------------------------

    /** Build a color packet with independent RGB for each zone slot. */
    static byte[] buildColorPacket(int r1, int g1, int b1, int r2, int g2, int b2) {
        byte[] data = {
            0x01, 0x08,
            (byte)(r1 & 0xFF), (byte)(g1 & 0xFF), (byte)(b1 & 0xFF),
            (byte)(r2 & 0xFF), (byte)(g2 & 0xFF), (byte)(b2 & 0xFF)
        };
        return buildPacket(CMD_COLOR, data);
    }

    // ---- brightness (power switch) -------------------------------------------

    /**
     * Build a brightness/power packet.
     * @param on     true = lights on (0x48), false = lights off (0x08)
     * @param b1     Zone 1 brightness 0-255
     * @param b2     Zone 2 brightness 0-255
     */
    static byte[] buildBrightnessPacket(boolean on, int b1, int b2) {
        byte type = on ? (byte) 0x48 : (byte) 0x08;
        byte[] data = { 0x00, type, (byte)(b1 & 0xFF), (byte)(b2 & 0xFF) };
        return buildPacket(CMD_COLOR, data);
    }

    // ---- color transform (hue offset + saturation + brightness scale) --------

    /**
     * Apply a zone's hue offset, saturation boost, and brightness scaling to a raw color.
     * Brightness is applied here as an RGB scale so the hardware brightness command is
     * not needed for perceived-brightness control (hardware PWM range is too narrow).
     */
    static int transformColor(int argb, int hueOffset, int saturationPct, int brightness) {
        if (argb == 0) return 0;

        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);

        // Hue offset (wrap 0-360)
        hsv[0] = ((hsv[0] + hueOffset) % 360 + 360) % 360;

        // Saturation boost (0-200% → 0.0-2.0, clamped to 0-1)
        hsv[1] = Math.min(1.0f, hsv[1] * saturationPct / 100.0f);

        // Brightness scale via HSV V channel
        hsv[2] = hsv[2] * (brightness / 255.0f);

        return Color.HSVToColor(hsv);
    }

    // ---- framing -------------------------------------------------------------

    private static byte[] buildPacket(byte cmd, byte[] data) {
        int len = data.length;
        byte[] pkt = new byte[len + 4];
        pkt[0] = HEADER;
        pkt[1] = cmd;
        pkt[2] = (byte) len;
        System.arraycopy(data, 0, pkt, 3, len);
        int sum = 0;
        for (int i = 1; i < len + 3; i++) sum += (pkt[i] & 0xFF);
        pkt[len + 3] = (byte) ((sum ^ 0xFF) & 0xFF);
        return pkt;
    }
}
