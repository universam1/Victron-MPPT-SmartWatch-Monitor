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

## Phone ↔ watch sync (companion mode)

Typing a 32 character key on a watch is a punishment, so the phone app is a real companion: the
device list — addresses, labels, keys, array size — travels over the **Wear OS Data Layer**.

```
phone app ──DataClient.putDataItem("/victron/devices")──▶ Data Layer ──▶ ConfigSyncListenerService
   ▲                                                                              │
   └──────────────── same service on the phone ◀── watch pushes its own list ──────┘
```

* Both sides may write; on receipt the lists are **merged per device, newer wins**
  (`DeviceConfig.updatedAtEpochMillis`). If the merge result is newer than what the counterpart
  published, the merged list is pushed straight back, so both ends converge.
* Data items **persist** in the Data Layer: a watch that was switched off still picks up a key that
  was entered on the phone hours earlier. Each app also pulls once on start (`syncNow()`).
* **Removals are not synced.** A union merge cannot express "this is gone" without tombstones, and
  the failure mode of guessing wrong (silently deleting a key on the other device) is worse than
  deleting a device twice.
* `backgroundScanEnabled` and the scan window stay **local** — what a phone can afford, a watch
  cannot.

Hard requirement: both APKs share `applicationId = de.universam.victron` and must be signed with
the same key. The Data Layer namespaces data items per package + signature, so different ids mean
the sync silently does nothing. Debug builds from the same machine (or the same CI run) satisfy
this automatically.

## Watch UI

The main screen is a gauge, not a list: a 240° arc along the bezel for PV power, the watts in the
middle, and the battery values as colour-coded chips underneath — the reading order VictronConnect
trained everyone on. The tile draws the same gauge with two overlaid ProtoLayout `Arc`s so tile and
app cannot drift apart.

Colours come from one place, `data/VictronPalette.kt`, as ARGB ints, because the tile knows nothing
about Compose: yellow = solar, blue = battery, green = current going in, orange = current going out,
red = charger error, dim grey = "no value" or stale.

The arc's full scale is the array size if you configured one (phone app), otherwise the highest
power ever seen from that device, rounded up to a step a human would draw — with a 50 W floor, so
3 W on a dark morning does not look like a full array.

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
