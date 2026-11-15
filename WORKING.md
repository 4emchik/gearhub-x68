# GearHub X68HE — known-good build

## Working build

- Version: 1.1, versionCode 2
- APK: `/storage/emulated/0/Download/GearHubX68-v1.1-working.apk`
- SHA-256: `238b13c17b845ec61ff75eefb93cc4ef8a8d3c3d30829ef0a5835414d2e33c11`
- Verified on: OnePlus CPH2689, Android 16 (API 36), X68HE via USB-C OTG

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

## Critical gotcha

Do **not** force the identity to `14763:36883` (`0x39AB:0x9013`). That VID/PID
matches qmk.top's `xhc416` profile, which waits for a 64-byte interrupt-IN
report. The real X68HE never sends that reply, so the handshake hangs forever
even though USB opens fine.

Keep the real `12625:20525` identity in `assets/webhid_shim.js`.

## Permission note

The prompt shown by the app is Android's USB host permission dialog, not a
browser permission. Tap **Allow**. The log line `USB permission granted`
confirms it worked.

## Build

```sh
cd ~/gearhub-x68
./build.sh
```

Signing uses `debug.keystore` (password `android`). Keep the same keystore to
install updates over the working app.
