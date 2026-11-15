# GearHub for Android — Attack Shark X68 HE

<img src="docs/icon.png" width="112" alt="GearHub app icon">

Unofficial, fullscreen Android app that runs the official
[GearHub V5 / qmk.top](https://qmk.top/) configurator for the
**Attack Shark X68 HE** keyboard over USB-C OTG.

The app also recognizes the **Attack Shark X6** receiver and requests USB host
access for it, so both devices are authorized when the app starts. qmk.top's
device list currently exposes the X68 HE only, so this build does not open the
separate X6 configurator. See [X6](#x6) below.

Android's WebView does not implement WebHID, so this app injects a small
WebHID polyfill backed by the Android USB Host API. The result is the real PC
configurator — lighting, key remapping, layers, rapid trigger, DKS/MT/TGL,
macros, profiles — running on a phone.

## Features

- **No chooser.** The app opens qmk.top directly, with or without hardware.
- **Automatic keyboard detection.** The X68 HE is detected and connected on
  launch, plug-in, and replug.
- **Both devices authorized.** A connected X6 receiver/cable gets its Android
  USB permission prompt too, so both devices are ready.
- **Fullscreen**: no status bar, navigation bar, or app chrome.
- **No Connect or Log buttons.**
- **Black-on-white Attack Shark icon**, adaptive and legacy (square + round).
- Hidden diagnostics: three-finger tap anywhere to open the log (with Copy).
- Presents the keyboard's real USB identity so qmk.top selects the correct
  profile.

## Requirements

- Android 7.0+ (API 24)
- USB-C OTG adapter
- Attack Shark X68 HE, wired
- Internet access (the configurator is loaded from qmk.top)
- Optional: Attack Shark X6 receiver/cable, recognized for USB authorization

## How it works

1. `MainActivity` is the launcher and loads `https://qmk.top/` in a WebView
   with a desktop user agent (the site only enables WebHID mode on desktop).
2. The main document is fetched by the app and `assets/webhid_shim.js` is
   injected into `<head>`.
3. The shim implements `navigator.hid` (`requestDevice`, `getDevices`,
   `open`, `sendReport`, `sendFeatureReport`, `receiveFeatureReport`,
   connect/disconnect events) on top of the `AndroidHID` JavaScript
   interface.
4. `WebHidBridge.java` talks to the keyboard with `UsbManager` /
   `UsbDeviceConnection` control transfers.
5. `UsbPermissionRequester.java` requests access for an attached X6; the
   keyboard permission is requested by `WebHidBridge` when qmk.top opens it.
6. A watchdog checks for the keyboard every 1.5 s while the app is in the
   foreground and keeps the page attached.

The X68 HE exposes its configuration endpoint on USB interface 2 as vendor
collection `usagePage 0xFFFF / usage 2`, with 64-byte feature reports and
report ID 0. See [WORKING.md](WORKING.md) for the protocol details and the
important `14763:36883` profile trap.

### X6

- USB ID: `1D57:FA60` (2.4 GHz receiver) or `1D57:FA61` (cable).
- GearHub requests Android USB permission for the receiver, but qmk.top's
  device filters contain no `1D57` entries, so the mouse is not listed by the
  configurator.
- The separate X6 web-driver bridge (`MouseActivity`, `X6Bridge`,
  `assets/x6shim.js`) is retained in the source but has no launcher entry in
  1.6. The previous **1.5** release still exposes it behind its chooser.

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

Download `GearHub-1.6.apk` from the
[Releases](https://github.com/4emchik/gearhub-x68/releases) page, or build it
yourself. The package name (`com.attackshark.gearhub`) and signing key are
unchanged, so 1.6 installs over 1.4/1.5 as an in-place update.

Android will ask for USB host permission when a device is connected. Tap
**Allow**. With both devices attached, expect one prompt for the keyboard and
one for the X6.

## Project layout

```
AndroidManifest.xml                     app manifest (package com.attackshark.gearhub)
src/com/attackshark/gearhub/
  MainActivity.java                     launcher, qmk.top WebView, fullscreen, auto-connect
  WebHidBridge.java                     keyboard USB Host <-> WebHID bridge
  HidDescriptor.java                    HID report-descriptor parser
  UsbPermissionRequester.java           Android USB permission for the X6
  MouseActivity.java                    X6 WebView shell (retained, not routed in 1.6)
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
- qmk.top does not list the X6, so this build has no mouse settings screen.
  Use the 1.5 release if you need the X6 configurator.
- Firmware flashing is the riskiest feature. The app does not alter the
  flashing flow, but the phone + OTG path is not what the vendor tested.
  Flash only if you need to.
- The site is designed for a desktop screen. It runs in landscape with a
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
