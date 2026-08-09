# Architecture

## Modules

```
protocol/   pure Kotlin/JVM — header parsing, AES-CTR, bit unpacking, model ids
   ▲        no Android dependency, unit tested against a real advertisement
data/       Android library — BLE scanning, decoding, persistence, ViewModel, WorkManager
   ▲
   ├── wear/    Wear OS app: Compose for Wear OS (Material 3) + the tile
   └── mobile/  phone app: Compose Material 3
```

Why the split:

* **`protocol` knows nothing about Android.** The tricky part of this project is bit-exact
  decoding, and that is exactly the part you want to test on a laptop with no SDK, no emulator and
  no watch: `./build.sh test`.
* **`data` is shared by both apps**, including `VictronViewModel`. The phone app is not a
  companion — it is the same app on a bigger screen, and both scan on their own.
* The watch app is **standalone** (`com.google.android.wearable.standalone = true`): the watch has
  its own Bluetooth radio, so it reads the MPPT directly, with no phone in the loop.

## Data flow

```
BLE advertisement (every ~1 s, manufacturer 0x02E1)
        │  ScanFilter in the Bluetooth controller: first payload byte must be 0x10
        ▼
VictronScanner ── callbackFlow ──▶ VictronRepository
                                      │ parse header (plaintext)
                                      │ pick key: by address, else by key-check byte
                                      │ AES-CTR + bit unpack
                                      ▼
                               DeviceSnapshot per address
                                 ├── StateFlow  → app UI (live while a screen is visible)
                                 └── DataStore  → the tile, and the next cold start
```

## Scanning policy (battery)

A continuous BLE scan is the fastest way to ruin a watch's battery, so nothing scans
unconditionally:

| Trigger | What happens |
|---|---|
| A screen of the app is visible | `SCAN_MODE_LOW_LATENCY` while visible, stopped in `onDispose` |
| Tile comes into view (`onTileEnterEvent`) | one expedited ~12 s scan, then a tile update |
| Tile is stale when requested | same, so looking at it twice always refreshes |
| Background scan enabled (opt-in) | `PeriodicWorkRequest`, 15 min, `SCAN_MODE_BALANCED` |

Every surface shows **how old** its values are, because "old but honest" beats "fresh looking but
wrong" — and because the platform may delay background work while the watch is idle.

## The tile

`VictronTileService` never scans by itself. Tile services must return quickly and cannot start
long-running work, so the tile:

1. reads the cached snapshot from DataStore (a few milliseconds),
2. renders solar power, battery voltage/current, charger state, yield and age,
3. asks `ScanScheduler` for a short scan when it was entered or when the data is stale,
4. gets re-requested when that scan finishes (`TileService.getUpdater().requestUpdate()`), which
   the app wires up via `VictronData.onScanFinished` in `VictronApplication`.

The layout is built with the low-level `androidx.wear.protolayout` builders — no images, so
`onTileResourcesRequest` stays empty and resource versioning is a non-issue.

`setFreshnessIntervalMillis(60_000)` lets the renderer re-request the tile once a minute; the age
string is rendered as text rather than as a dynamic expression, which keeps the layout simple.

## Permissions

Only one runtime permission: `BLUETOOTH_SCAN`, declared with
`android:usesPermissionFlags="neverForLocation"`. Because the app never connects to a device,
`BLUETOOTH_CONNECT` is not needed, and because scanning is not used for positioning, no location
permission is required either. `WorkManager`'s expedited jobs handle the "scan from the background"
case, so there is no foreground service and no notification.

## Configuration

`AppConfig` (JSON via DataStore) holds the device list with their advertisement keys, the
background-scan toggle and the scan window. Keys can be entered

* on the phone: a normal text field (paste from a password manager),
* on the watch: a hex keypad — 32 characters, one time, without depending on an IME.

Keys are matched to advertisements by address first and by key-check byte second, so a device that
shows up under a different address still decodes.
