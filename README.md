# Iris LED Sync

Android app that syncs your car's LED strip colors to the album art of whatever is playing in **AIMP**. Colors transition smoothly as tracks change, and everything is controllable from the Android Auto dash or the notification shade.

NOTE: This was made and tested with the LED lights from mikstoreph / mikstore. I don't know if it works with other brands.

## How it works

```
AIMP playing → MediaSession metadata → album art → Palette extraction
                                                          ↓
                                              color chosen per zone
                                                          ↓
                                         30-step lerp animation (~1 s)
                                                          ↓
                                         BLE packets → LED controller
```

`AIMPMediaListener` (a `NotificationListenerService`) watches for track changes via three complementary triggers — MediaController callbacks, notification posts, and AIMP's broadcast intents — so no track change is ever missed. On each change it runs [AndroidX Palette](https://developer.android.com/develop/ui/views/graphics/palette-colors) with 24 swatches to extract colors, then `ZoneManager` lerps each zone from its current color to the new target over ~1 second at 30 fps. The final color (after hue/saturation/brightness transforms) is sent to the BLE LED controller as a proprietary packet framed by `CarLedProtocol`.

## Features

- **Album art color extraction** — 7 extraction algorithms per zone: Vibrant, Dominant, Light Vibrant, Dark Vibrant, Muted, Pixel Average, Most Common
- **Per-zone color transforms** — hue offset (±180°), saturation (0–200%), brightness (0–100%)
- **Smooth 1-second transitions** between tracks
- **Two independent LED zones** with separate color modes and brightness
- **Android Auto UI** — zone list and detail screens via the Car App Library
- **Notification actions** — adjust each zone's brightness or kill the LEDs without touching the phone
- **Runs on boot** and auto-starts when Android Auto connects

## Architecture

| Class | Role |
|---|---|
| `AIMPMediaListener` | `NotificationListenerService`; watches AIMP's MediaSession; extracts Palette colors |
| `ZoneManager` | Singleton; owns zone state, lerp animation, BLE dispatch, SharedPreferences persistence |
| `CarLedBleManager` | GATT client; serialized write queue; connects to the LED controller |
| `CarLedProtocol` | Encodes color and brightness packets (`[0x2E][cmd][len][data…][checksum]`) |
| `CarLedService` | Foreground service; glues everything together; handles notification actions |
| `LedCarAppService` / `LedCarSession` | Android Auto entry points |
| `ZoneListScreen` / `ZoneDetailScreen` | Android Auto templates |
| `MainActivity` | BLE scanner + zone config UI |

## Setup

1. Install the APK on an Android phone running **Android 8.0+** (API 26).
2. Open the app and grant **Notification access** (required for MediaSession — tap the status line if it shows ✗).
3. Tap **Scan for LED controller** and select your car's BLE LED module.
4. Tap **Start service**. The foreground notification confirms the service is running.
5. Play something in AIMP — the LEDs should change color within a second or two.

The service starts automatically on boot and when Android Auto connects.

## BLE protocol

The app targets LED controllers running the `com.szraise.carled` firmware family. Packets follow this format:

```
[0x2E] [cmd] [len] [data…] [checksum]
checksum = (cmd + len + sum(data)) XOR 0xFF
```

- **Color packet** — `cmd=0x8D`, data `[0x01, 0x08, R1, G1, B1, R2, G2, B2]`
- **Brightness/power packet** — `cmd=0x8D`, data `[0x00, type, b1, b2]` where `type=0x48` (on) or `0x08` (off)

Service UUID: `0000AE30-0000-1000-8000-00805f9b34fb`

## Requirements

- Android 8.0+ (API 26), targeting API 35
- AIMP for Android
- A BLE LED controller compatible with the `szraise` / CarLED protocol
- Android Auto (optional, for in-car UI)

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | BLE scan and GATT connection |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scan on API < 31 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read AIMP's MediaSession and album art |
| `FOREGROUND_SERVICE` | Keep BLE connection alive in the background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |

## License

See [LICENSE](LICENSE).
