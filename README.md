# Victron MPPT SmartWatch Monitor

Read a Victron **SmartSolar / BlueSolar MPPT** straight from your wrist — connectionless, over the
device's BLE *Instant Readout* advertisements. A Wear OS **tile** shows solar power, battery
voltage/current, charger state and today's yield at a glance; the full watch app adds details,
device discovery and key entry; the same code also ships as a phone app.

* No connection to the charger: VictronConnect keeps working while you watch.
* No phone required — the watch app is standalone and scans with the watch's own radio.
* No IDE required — everything builds in Docker with one script.

```
┌─────────────────────┐
│ Victron             │   tile
│                     │
│      142 W          │   ← PV power
│   13.88 V  1.4 A    │   ← battery
│   Absorption        │   ← charger state
│   0.42 kWh · 8s     │   ← yield today · age of the reading
└─────────────────────┘
```

## Getting started

### 1. Enable Instant Readout and copy the key

In **VictronConnect**: open the device → gear icon → *Product info* → enable **“Instant readout
via Bluetooth”** → *Encryption data* → note the 32 character key (and the device's Bluetooth
address).

### 2. Build

```sh
./build.sh test        # protocol unit tests, no Android SDK needed
./build.sh apk         # debug APKs for watch and phone (in Docker)
```

The first `apk` run builds the Docker image from [`docker/Dockerfile`](docker/Dockerfile) (JDK 21 +
Android command line tools; Gradle comes from the wrapper). Later runs reuse a cached Gradle home,
so they take seconds. If you do have a local JDK 17+ and Android SDK, `VICTRON_NATIVE=1 ./build.sh
apk` skips Docker entirely.

### 3. Install on the watch

```sh
adb connect <watch-ip>:5555     # enable Wi-Fi debugging on the watch first
./build.sh install-wear
```

Then on the watch: open **Victron Monitor**, allow Bluetooth scanning, and your charger appears in
the list — even before a key is entered, because model and address travel unencrypted. Tap it,
type the key on the hex keypad, save. Add the tile via *press and hold the watch face → Tiles →
+*.

The phone app (`./build.sh install-mobile`) works the same way and lets you paste the key.

## What it shows

| | |
|---|---|
| Solar power | `PV power` in W |
| Battery | voltage, current, and the resulting power |
| Charger state | Off / Bulk / Absorption / Float / … |
| Errors | the VE.Direct error text, e.g. *PV input voltage too high* |
| Yield today | Wh below 1 kWh, kWh above |
| Load output | on models that have one |
| Age | how old the reading is — always visible |

Other Victron devices (SmartShunt, Lynx Smart BMS, BatteryProtect, …) are **discovered and
decrypted**, but their record types are not decoded into values yet; the *Raw data* screen shows
their decrypted payload. Adding one is a self-contained change — the layouts are documented in
[docs/victron-ble-protocol.md](docs/victron-ble-protocol.md).

## Battery use

Nothing scans permanently. The app scans only while a screen is open; the tile triggers a ~12 s
scan when you look at it and refreshes itself when that finishes; an optional background scan runs
about every 15 minutes. Details and the reasoning in
[docs/architecture.md](docs/architecture.md#scanning-policy-battery).

## Repository layout

| Path | Contents |
|---|---|
| [`protocol/`](protocol) | Pure Kotlin: advertisement parsing, AES-CTR, bit unpacking, model ids — unit tested |
| [`data/`](data) | Android library: BLE scanning, snapshot cache (DataStore), shared ViewModel, WorkManager |
| [`wear/`](wear) | Wear OS app (Compose for Wear OS, Material 3) + the tile |
| [`mobile/`](mobile) | Phone app (Compose Material 3) |
| [`docker/`](docker) | Headless build image |
| [`docs/`](docs) | Protocol reference and architecture |

## Requirements

* Wear OS 4 or newer (API 33+) for the watch app, Android 12+ (API 31) for the phone app.
* A Victron device with Bluetooth and *Instant readout* enabled.
* Docker (or a local JDK 17+ and Android SDK) to build.

## Credits

The protocol was cross-checked against [keshavdv/victron-ble](https://github.com/keshavdv/victron-ble),
whose published test vector is used in the unit tests.

## License

[MIT](LICENSE)
