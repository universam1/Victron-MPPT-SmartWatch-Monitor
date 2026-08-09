# Victron MPPT SmartWatch Monitor

Read a Victron **SmartSolar / BlueSolar MPPT** straight from your wrist — connectionless, over the
device's BLE *Instant Readout* advertisements. A Wear OS **tile** shows solar power, battery
voltage/current, charger state and today's yield at a glance; the full watch app adds details,
device discovery and key entry; the same code also ships as a phone app.

* No connection to the charger: VictronConnect keeps working while you watch.
* No phone required — the watch app is standalone and scans with the watch's own radio.
* No IDE required — everything builds in Docker with one script.

```
        ╭─────────────────────╮        arc = PV power against your array size
      ╱   ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁    ╲
     │    SmartSolar 100/30  ›  │      tap the name to switch device
     │                          │
     │        142 W             │      ← solar yellow
     │        Absorption        │
     │    13.88 V    1.4 A      │      ← battery blue · charging green
     │      0.42 kWh · 8s       │      ← yield green · age of the reading
      ╲            ⚙           ╱
        ╰─────────────────────╯
```

The tile draws the same gauge, so tile and app never look like two different apps.

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

### 3. Install both apps

```sh
./build.sh install-mobile        # phone: plug in via USB
adb connect <watch-ip>:5555      # watch: enable Wi-Fi debugging first
./build.sh install-wear
```

Easiest path: **enter the key once in the phone app** — paste it, done — and it syncs to the watch
over the Wear OS Data Layer, together with the device label and array size. Grant Bluetooth
scanning on both, then add the tile on the watch via *press and hold the watch face → Tiles → +*.

The watch also works entirely on its own: it lists every Victron device in range even without a key
(model and address travel unencrypted), and has a hex keypad if you want to type the key there.

> Both APKs must come from the same build — they share an `applicationId` and signing key, which is
> what allows the Data Layer to connect them. `./build.sh apk` builds both, so this is automatic.

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
| Gauge scale | your array size in W, or automatically the highest power seen so far |

Other Victron devices (SmartShunt, Lynx Smart BMS, BatteryProtect, …) are **discovered and
decrypted**, but their record types are not decoded into values yet; the *Raw data* screen shows
their decrypted payload. Adding one is a self-contained change — the layouts are documented in
[docs/victron-ble-protocol.md](docs/victron-ble-protocol.md).

## Releases

Tag a commit and CI publishes both APKs to the
[Releases page](https://github.com/universam1/Victron-MPPT-SmartWatch-Monitor-/releases):

```sh
git tag v1.1.0
git push origin v1.1.0
```

Or, without touching git: run the **release** workflow from the *Actions* tab and enter the tag
(e.g. `v1.1.0`) — the release is created together with the tag on the selected branch. Leaving the
input empty makes it a dry run that builds everything and attaches the APKs as workflow artifacts
without publishing.

The [release workflow](.github/workflows/release.yml) runs the tests, derives `versionName` from the
tag and `versionCode` from its numbers (`v1.2.3` → `10203`), builds `victron-monitor-wear-<version>.apk`
and `victron-monitor-phone-<version>.apk`, adds `SHA256SUMS.txt`, and writes install instructions plus
GitHub's generated changelog into the release notes. Always install **both** APKs from the same
release — they share an application id and signing key, which is what makes the key sync work.

No tag needed for a test build: every push runs [build.yml](.github/workflows/build.yml), which
attaches debug APKs as workflow artifacts.

### Signing (optional, but do it before the first real release)

Without a keystore the APKs are signed with a throwaway debug key: installable, but a later
differently signed build cannot replace it. To sign properly, create a keystore once

```sh
keytool -genkeypair -v -keystore release.keystore -alias victron \
        -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore                 # paste this into the secret below
```

and add four repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | output of the `base64 -w0` command |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `victron` (the alias above) |
| `SIGNING_KEY_PASSWORD` | key password, if different from the keystore password |

Keep the keystore file — losing it means every future release needs an uninstall/reinstall. The same
variables work locally: put `release.keystore` in the repository root, export the three `SIGNING_*`
variables and run `./build.sh apk-release`.

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
