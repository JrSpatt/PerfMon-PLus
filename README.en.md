# PerfMon+

[Türkçe](README.md)

PerfMon+ is a floating performance monitor for Android. It shows frame rate, CPU and GPU readings, memory usage, temperatures and battery readings while you play a game or use another app. Available metrics depend on the device.

## Installation

1. Download `PerfMon-Plus.apk` and install it on a device running Android 7.0 or later. [Latest release](https://github.com/JrSpatt/PerfMon-PLus/releases/latest)
2. Open PerfMon+, tap **Monitörü başlat** (Start monitor) and allow the app to display over other apps when prompted.

To measure the foreground app’s frame rate, start Shizuku and grant PerfMon+ access. [Shizuku](https://shizuku.rikka.app/)

On devices that support it, you can also grant the `DUMP` permission over ADB for the same measurement.

```sh
adb shell pm grant com.spatt.perfmon android.permission.DUMP
```

## Frame rate

| Source | Label | Reading |
| --- | --- | --- |
| Shizuku or DUMP permission | `FPS` | Frame rate calculated from the foreground app’s frame presentation times |
| Display panel driver | `FPS` | Panel frame rate reported by the driver |
| Display | `Hz` | Display refresh rate when neither app nor panel measurements are available |

If FPS data drops out for one update, the overlay shows `FPS --`. If the gap continues, it switches to `Hz`. For an app measurement, `0 FPS` means the visible content is idle. A reading of `<1` means fewer than one frame per second.

Open **Cihaz uyumluluğu → FPS kaynağı** (Device compatibility → FPS source) to see the current source, why the monitor switched to panel FPS or display Hz, and the last successful app measurement.

## Overlay settings

- Under **İzleme** (Monitoring), choose which metrics appear. You can group CPU cores by cluster and toggle battery current (**Güç**, mA) and battery power (**Güç (W)**) independently.
- Under **Pencere** (Window), adjust text size, opacity and the update interval for all rows.
- Under **Konum** (Position), save separate positions for portrait and landscape. Tap the pin on the overlay to lock it and let touches pass through to the app underneath.
- Stop the monitor from the app, the notification or the Quick Settings tile.

## Build

Use JDK 17, Android SDK Platform 34 and Build Tools 34.0.0. Run the command for your platform from the project directory.

| Platform | Command |
| --- | --- |
| Windows (CMD) | `gradlew.bat :app:assembleDebug` |
| Linux / macOS | `./gradlew :app:assembleDebug` |

The APK is written to `app/build/outputs/apk/debug/`.

## License

Based on PerfMon+ by LibXZR. [Upstream project](https://github.com/libxzr/PerfMon-Plus) · [BSD 3-Clause license](LICENSE)
