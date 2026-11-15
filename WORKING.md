# GearHub — build and protocol notes

## Build history

- **1.6 / versionCode 7** — no chooser: `MainActivity` is the launcher and
  opens qmk.top directly. Requests Android USB access for both the keyboard
  (via `WebHidBridge`) and the X6 (via `UsbPermissionRequester`). The separate
  X6 configurator is retained in the source but has no launcher entry.
  - SHA-256: `3df96c81a17a9cbea1f2ab4faee2f05e84e258fd52462c10de40a6a7cdfa819f`
  - Built and signed; keyboard path unchanged from the hardware-verified 1.5.
- **1.5 / versionCode 6** — combined app: one launcher for the X68 HE keyboard
  and the X6 mouse, automatic routing, two-button chooser, X6 helper-socket
  bridge, black-on-white Attack Shark icon.
  - SHA-256: `8f892de19025584d7fc24b5639cb17a31efc51dcd8198b4c8eec33247eac2d0e`
  - Keyboard path verified on OnePlus CPH2689, Android 16 (API 36).
- **1.4 / versionCode 5** — crash capture and startup diagnostics; keyboard
  path accepted on hardware.
  - SHA-256: `476101c891a688ab5821c57b011a73781e08766dda7cbfee90e7a8c04a9bec54`
- **1.3 / versionCode 4** — keyboard-shaped launcher icon with the Attack
  Shark mark (adaptive + legacy, square + round).
- **1.2 / versionCode 3** — immersive fullscreen, no Connect/Log buttons, no
  bottom bar, auto-connect watchdog, hidden three-finger log.
- **1.1 / versionCode 2** — first working build (bottom bar with Connect/Log).
  Rollback APK below.

## Known-good rollback (1.1)

- Version: 1.1, versionCode 2
- SHA-256: `238b13c17b845ec61ff75eefb93cc4ef8a8d3c3d30829ef0a5835414d2e33c11`
- Verified on: OnePlus CPH2689, Android 16 (API 36), X68HE via USB-C OTG

APKs are attached to the GitHub releases. The signing keystore is not in the
repository; keep the same `debug.keystore` locally to install updates over an
existing build.

## Launcher flow (1.6)

`MainActivity` is the `MAIN`/`LAUNCHER` activity. It loads qmk.top immediately;
there is no routing screen and no chooser.

- The keyboard permission is requested by `WebHidBridge` when qmk.top opens
  the X68 HE.
- `UsbPermissionRequester` scans the USB bus and requests Android permission
  for an attached X6 receiver (`1D57:FA60`) or cable (`1D57:FA61`).
- The two requests are intentionally kept separate: the helper does not
  prompt for the keyboard, so it cannot race `WebHidBridge`'s permission flow.
- `MouseActivity` and `X6Bridge` are still built into the APK but have no
  launcher entry. Release 1.5 remains the build with the two-button chooser
  and the X6 configurator.

qmk.top's filters contain no `1D57` entries, so authorizing the X6 does not
make it appear in the qmk device list. Its settings are not available in the
1.6 UI.

## Keyboard identity (important)

- Real USB VID: `12625` (`0x3151`)
- Real USB PID: `20525` (`0x502D`)
- Product string: `X68HE`
- Config interface: interface `2`
- Collection: usagePage `0xFFFF`, usage `2`
- HID report descriptor: 20 bytes, one 64-byte input report, no report IDs
- Feature reports: report ID `0`, 64 bytes (`SET_REPORT` / `GET_REPORT` via control transfers)

## qmk.top profile

The real VID/PID matches this filter in qmk.top:

```js
{ type: "keyboard", vendorId: 12625, productId: 20525,
  usage: 2, usagePage: 65535, interfaceNumber: 2, reportRate: 8000 }
```

qmk.top routes `type: "keyboard"` to its `Ds` protocol class. Its `whoAmI()`:

1. Sends feature report `[0x8F, ...]` (64 bytes).
2. Reads the reply with `GET_REPORT` report ID 0, 64 bytes.
3. Reads the device ID from bytes 1..4 (little-endian).

## Critical keyboard gotcha

Do **not** force the identity to `14763:36883` (`0x39AB:0x9013`). That VID/PID
matches qmk.top's `xhc416` profile, which waits for a 64-byte interrupt-IN
report. The real X68HE never sends that reply, so the handshake hangs forever
even though USB opens fine.

Keep the real `12625:20525` identity in `assets/webhid_shim.js`.

## Keyboard permission note

The prompt shown by the app is Android's USB host permission dialog, not a
browser permission. Tap **Allow**. The log line `USB permission granted`
confirms it worked.

## Keyboard auto-connect

`MainActivity` runs a 1.5 s watchdog while the app is in the foreground. It
looks for VID/PID `12625:20525` (or a product name containing `x68`, `attack`
or `shark`), asks the page to open it, and re-attaches after a page reload.
Android USB permission prompts are rate-limited to one every 20 s so a denial
cannot cause an endless popup loop. Three-finger tap opens the log.

## X6 protocol (retained, not routed in 1.6)

- Wireless receiver: `1D57:FA60` (`7511:64096`)
- Cable: `1D57:FA61` (`7511:64097`)
- Configuration interface: `2`
- Interrupt IN endpoint: `0x83`
- Feature writes: `SET_REPORT` (`0x21/0x09`), `wValue = 0x0300 | reportId`,
  payload without the report ID.

The official page does not use WebHID for the X6. It opens
`ws://localhost:10086` and expects a Windows helper. `assets/x6shim.js`
replaces that WebSocket and forwards hex messages to `AndroidX6`, implemented
by `X6Bridge`:

- Page → helper messages start with `[connectionMethod, deviceId]`
  (`81 85` wireless, `80 85` wired). The bridge strips those two bytes; the
  next byte is the HID report ID (4 = DPI, 60-byte payload; 5 = lighting,
  20-byte payload).
- Helper version is reported as `03 00` (3.0). The page treats ≤ 3.0 as
  "skip reads", which avoids `GET_REPORT` commands the bridge does not
  service. The page's driver-version tip is hidden by the shim.
- Presence: `81 01 00 85 40 <status> <level>` for the initial report and
  `... 41 <status> <level>` for updates; disconnect is `81 00 85`.
- Device → page reports are forwarded as hex. Expected acknowledgements are
  `03 10 50 00 04`; battery is `03 10 40 <stage> <byte4 * 10>%`; a DPI-stage
  event is `03 10 10 <stage> 00`. Padded input packets are trimmed to the
  exact lengths the page dispatches on.

When testing the retained mouse path, watch tags `GearHubX6` and
`SiteConsole`. Expected sequence: permission granted → interface 2 claimed →
presence routed to `/factorySix` → an ACK `03 10 50 00 04` after each
`SET_FEATURE`.

## Build

```sh
cd ~/gearhub-x68
./build.sh
```

`build.sh` generates `R.java` with `aapt2 --java`, compiles all sources,
dexes, packages both shims, and signs. Signing uses `debug.keystore`
(password `android`). Keep the same keystore to install updates over the
working app.
