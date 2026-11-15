# GearHub for Android — Attack Shark X68 HE + X6

<img src="docs/icon.png" width="112" alt="GearHub app icon">

Unofficial, fullscreen Android app that runs the official web configurators for
the **Attack Shark X68 HE** keyboard and **Attack Shark X6** mouse over USB-C
OTG.

Android's WebView does not implement WebHID, so the app injects small bridges
backed by the Android USB Host API. The result is the real PC configurator —
lighting, key remapping, layers, rapid trigger, DKS/MT/TGL, macros, profiles,
DPI, polling rate, button mapping — running on a phone.

## Features

- **One app, two devices.** X68 HE uses the qmk.top configurator; X6 uses the
  official szslxd-tech.com web driver.
- **Automatic detection.** One device attached → its configurator opens
  immediately. Both or neither attached → a minimal chooser is shown.
- **Fullscreen**: no status bar, navigation bar, or app chrome.
- **No Connect or Log buttons**: USB permission is requested automatically and
  the page is attached on launch, plug-in, and replug.
- **Black-on-white Attack Shark icon**, adaptive and legacy (square + round).
- Hidden diagnostics: three-finger tap anywhere to open the log (with Copy).
- Presents each device's real USB identity so the official site selects the
  correct profile.

## Requirements

- Android 7.0+ (API 24)
- USB-C OTG adapter
- Attack Shark X68 HE (wired) or X6 (2.4 GHz receiver or cable)
- Internet access (both configurators are live sites)

## How it works

1. `LauncherActivity` inspects the USB bus and routes to the matching screen.
2. **Keyboard:** `MainActivity` loads `https://qmk.top/` with a desktop user
   agent. The main document is fetched by the app and
   `assets/webhid_shim.js` is injected into `<head>`.
3. The keyboard shim implements `navigator.hid` (`requestDevice`,
   `getDevices`, `open`, `sendReport`, `sendFeatureReport`,
   `receiveFeatureReport`, connect/disconnect events) on top of the
   `AndroidHID` JavaScript interface, which is backed by
   `WebHidBridge.java` and Android's `UsbManager`.
4. **Mouse:** `MouseActivity` loads `https://szslxd-tech.com/#/`. That page
   normally talks to a Windows helper over `ws://localhost:10086`;
   `assets/x6shim.js` replaces that socket and forwards its hex commands to
   `X6Bridge.java`, which sends HID feature reports through the USB Host API.
5. Watchdogs check for the selected device every 1.5 s while the screen is in
   the foreground and keep the page attached.

The X68 HE exposes its configuration endpoint on USB interface 2 as vendor
collection `usagePage 0xFFFF / usage 2`, with 64-byte feature reports and
report ID 0. See [WORKING.md](WORKING.md) for the protocol details and the
important `14763:36883` profile trap.

### X6 notes

- USB ID: `1D57:FA60` (2.4 GHz receiver) or `1D57:FA61` (cable).
- Configuration interface 2, interrupt IN endpoint `0x83`.
- Page commands are prefixed with `[connectionMethod, deviceId]`; the bridge
  strips those two bytes and sends the rest as a HID feature report. Report ID
  4 carries DPI settings, report ID 5 carries lighting.
- The shim reports helper version 3.0, so the site skips the device-read
  commands this bridge does not implement. Presence and battery state are sent
  in the helper's `81 01 00 85 40/41 <status> <level>` format.
- The receiver must be in 2.4 GHz mode (the dongle attached by USB). The wired
  `FA61` identity is also recognized.

## Build (Termux)

```sh
pkg install git aapt2 apksigner d8 openjdk-17 zip unzip
```

Place an Android platform `android.jar` at `sdk/android.jar`, for example
from the platform 36 SDK package:

```sh
mkdir -p sdk
curl -LO https://dl.google.com/android/repository/platform-36_r02.zip
unzip -p platform-36_r02.zip android-36/android.jar > sdk/android.jar
```

Then:

```sh
./build.sh
```

The script compiles with `javac`, dexes with `d8`, packages with `aapt2`,
generates `R.java`, and signs with `debug.keystore`. The keystore is generated
on first build if missing and is git-ignored — use your own keystore for
releases.

`build.sh` honours `ANDROID_JAR` if you keep `android.jar` elsewhere:

```sh
ANDROID_JAR=/path/to/android.jar ./build.sh
```

## Install

Download `GearHub-1.5.apk` from the
[Releases](https://github.com/4emchik/gearhub-x68/releases) page, or build it
yourself. The package name (`com.attackshark.gearhub`) and signing key are
unchanged, so 1.5 installs over 1.4 as an in-place update.

Android will ask for USB host permission the first time each device is
connected. Tap **Allow**.

## Project layout

```
AndroidManifest.xml                     app manifest (package com.attackshark.gearhub)
src/com/attackshark/gearhub/
  LauncherActivity.java                 device detection and chooser
  MainActivity.java                     keyboard WebView shell, fullscreen, auto-connect
  WebHidBridge.java                     keyboard USB Host <-> WebHID bridge
  HidDescriptor.java                    HID report-descriptor parser
  MouseActivity.java                    X6 WebView shell, shim injection
  X6Bridge.java                         X6 USB Host <-> helper-socket bridge
  GearHubApp.java                       crash capture / startup diagnostics
  CrashActivity.java                    crash screen in a separate process
assets/webhid_shim.js                    navigator.hid polyfill for qmk.top
assets/x6shim.js                        fake localhost helper for the X6 page
res/                                    launcher icon (adaptive + legacy PNGs)
tools/make_icon.py                      regenerates res/ with Pillow
tools/shark_mark.png                    alpha cut-out of the Attack Shark mark
build.sh                                Termux build + sign script
WORKING.md                              protocol notes / known-good build
```

## Notes and caveats

- This is an unofficial project and is not affiliated with Attack Shark,
  GearHub, or the web-driver vendors.
- The X68 HE path is hardware-verified on a OnePlus CPH2689 running Android 16.
  X6 support is new: it follows the official web driver's protocol, but it has
  not yet been exercised against a physical X6 at release time. If a setting
  fails to save, open the hidden log (three-finger tap) and check the last
  `SET_FEATURE` line.
- Firmware flashing is the riskiest feature. The app does not alter the
  flashing flow, but the phone + OTG path is not what the vendor tested.
  Flash only if you need to.
- The sites are designed for a desktop screen. They run in landscape with a
  1280 px layout; some drag and colour-picker controls may feel cramped.

## Credits

- The WebHID shim is adapted from the MIT-licensed
  [WebHidBridge-Android-Truthear-Keyx](https://github.com/Halil156/WebHidBridge-Android-Truthear-Keyx)
  by Halil156.
- Device protocols reverse-engineered from the public qmk.top bundle and the
  official X6 web driver.
- The Attack Shark name and logo are trademarks of their respective owner.
  The launcher icon is fan-made for identification of the supported devices and
  is not an official Attack Shark product asset.

## License

MIT — see [LICENSE](LICENSE).
