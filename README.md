# GearHub X68HE for Android

<img src="docs/icon.png" width="112" alt="GearHub X68HE app icon">

Unofficial, fullscreen Android app that runs the official
[GearHub V5 / qmk.top](https://qmk.top/) web configurator for the
**Attack Shark X68HE** keyboard over USB-C OTG.

Android's WebView does not implement WebHID, so this app injects a small
WebHID polyfill backed by the Android USB Host API. The result is the real
PC configurator — lighting, key remapping, layers, rapid trigger, DKS/MT/TGL,
macros, profiles — running on a phone.

## Features

- Everything the official web driver exposes (the app wraps the live site).
- **Fullscreen**: no status bar, navigation bar, or app chrome.
- **No Connect or Log buttons**: the keyboard is detected and connected
  automatically on launch, plug-in, and replug.
- Hidden diagnostics: three-finger tap anywhere to open the log (with Copy).
- Presents the keyboard's real USB identity so the official site selects the
  correct device profile.

## Requirements

- Android 7.0+ (API 24)
- USB-C OTG adapter
- Attack Shark X68HE, wired
- Internet access (the configurator is loaded from qmk.top)

## How it works

1. `MainActivity` loads `https://qmk.top/` in a WebView with a desktop
   user agent (the site only enables WebHID mode on desktop).
2. The main document is fetched by the app and `assets/webhid_shim.js` is
   injected into `<head>`.
3. The shim implements `navigator.hid` (`requestDevice`, `getDevices`,
   `open`, `sendReport`, `sendFeatureReport`, `receiveFeatureReport`,
   connect/disconnect events) on top of the `AndroidHID` JavaScript
   interface.
4. `WebHidBridge.java` talks to the keyboard with `UsbManager` /
   `UsbDeviceConnection` control transfers.
5. A watchdog checks for the keyboard every 1.5 s while the app is in the
   foreground and keeps the page attached.

The X68HE exposes its configuration endpoint on USB interface 2 as vendor
collection `usagePage 0xFFFF / usage 2`, with 64-byte feature reports and
report ID 0. See [WORKING.md](WORKING.md) for the protocol details and the
important `14763:36883` profile trap.

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
and signs with `debug.keystore`. The keystore is generated on first build
if missing and is git-ignored — use your own keystore for releases.

`build.sh` honours `ANDROID_JAR` if you keep `android.jar` elsewhere:

```sh
ANDROID_JAR=/path/to/android.jar ./build.sh
```

## Install

```sh
termux-open --view --content-type application/vnd.android.package-archive GearHubX68.apk
```

Android will ask for USB host permission the first time the keyboard is
connected. Tap **Allow**.

## Project layout

```
AndroidManifest.xml                     app manifest (package com.attackshark.gearhub)
src/com/attackshark/gearhub/
  MainActivity.java                     WebView shell, fullscreen, auto-connect
  WebHidBridge.java                     USB Host <-> WebHID bridge
  HidDescriptor.java                    HID report-descriptor parser
assets/webhid_shim.js                    navigator.hid polyfill
res/                                    launcher icon (adaptive + legacy PNGs)
tools/make_icon.py                      regenerates res/ with Pillow
tools/shark_mark.png                    alpha cut-out of the Attack Shark mark
build.sh                                Termux build + sign script
WORKING.md                              protocol notes / known-good build
```

## Notes and caveats

- This is an unofficial project and is not affiliated with Attack Shark or
  GearHub.
- Firmware flashing is the riskiest feature. The app does not alter the
  flashing flow, but the phone + OTG path is not what the vendor tested.
  Flash only if you need to.
- The site is designed for a desktop screen. It runs in landscape with a
  1280 px layout; some drag and colour-picker controls may feel cramped.

## Credits

- The WebHID shim is adapted from the MIT-licensed
  [WebHidBridge-Android-Truthear-Keyx](https://github.com/Halil156/WebHidBridge-Android-Truthear-Keyx)
  by Halil156.
- Device protocol reverse-engineered from the public qmk.top bundle.
- The Attack Shark name and logo are trademarks of their respective owner.
  The launcher icon is fan-made for identification of the keyboard and is not
  an official Attack Shark product asset.

## License

MIT — see [LICENSE](LICENSE).
BOOMBAYAH I DROPED A AN ATOMIC BOMB ON MY KEYBOARD
