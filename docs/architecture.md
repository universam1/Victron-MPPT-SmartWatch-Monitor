# Architecture

## Modules

```
protocol/   pure Kotlin/JVM — header parsing, AES-CTR, bit unpacking, model ids
   ▲        no Android dependency, unit tested against a real advertisement
data/       Android library — BLE scanning, decoding, persistence, ViewModel, WorkManager
   ▲
   ├── wear/    Wear OS app: Compose for Wear OS (Material 3) + tile
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
device list — addresses, labels, keys — travels over the **Wear OS Data Layer**.

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

HeroScreen is a `ScalingLazyColumn`: the first item is a fullscreen gauge (240° arc along the bezel
for PV power, watts/amps in the centre), and the following items are Wear M3 Buttons with Material
Icons showing battery voltage, solar power, charger state, yield, load, signal, model, and age.
Scrolling the gauge reveals the detail list — there is no separate detail route. Navigation is
Hero → Overview → Devices, via the "Devices" button at the end of the detail list.

Two details are load-bearing:

* The gauge item is sized with `Modifier.fillParentMaxSize()`, the `ScalingLazyListItemScope`
  variant. A lazy list measures its items with an unbounded height, so a plain `fillMaxSize()`
  collapses to the content height and the bezel arcs shrink to a sliver around the numbers. A first
  item exactly one viewport tall is also what makes the column's auto-centering compute a zero top
  spacer, so the gauge can be scrolled all the way to the top instead of resting below it.
* Everything renders **without a device**: with nothing decoded, the gauge shows `–` and arcs at
  zero, the detail rows show `–`, and a status row below the gauge says why (scanning, no device,
  permission missing) and acts on a tap. So the screen and its navigation can be exercised without
  a Victron in range — the same is true of the phone dashboard.

Colours come from one place, `data/VictronPalette.kt`, as ARGB ints, because the tile knows nothing
about Compose: yellow = solar, blue = battery, green = current going in, orange = current going out,
red = charger error, dim grey = "no value" or stale.

The arc's full scale comes from the model name: every charger is called `MPPT <volts>/<amps>`, so
a *SmartSolar MPPT 100/20* is a 20 A unit and can push at most 20 A × the battery voltage into the
battery. That product is the watts scale, the rating itself is the current arc's scale, and nothing
has to be configured. For a model id that is not in the table there is still the old fallback: the
highest value ever seen from that device, rounded up to a step a human would draw — with a 50 W
floor, so 3 W on a dark morning does not look like a full array.

## Phone UI

The dashboard follows the *shape of the window*, not the orientation sensor: `DeviceDashboard`
measures itself with `BoxWithConstraints` and switches arrangement when `maxWidth > maxHeight`.

* **Taller than wide** — one scrolling column: arc gauge at full width, current bar, 2×2 tiles.
* **Wider than tall** — two columns: the header spans the top, the arc gauge sits left sized to the
  *height* (`PvArcGauge(matchHeightFirst = true)`), the current bar and the tiles sit right in a
  compact variant. That is the difference between a landscape layout and a magnified portrait one:
  deriving the square gauge from the abundant width pushes everything else off screen.

Header, tiles and gauge are single composables shared by both arrangements (`DashboardHeader`,
`ValueTiles`, `PvArcGauge`), so the two paths cannot drift apart. `PvArcGauge` scales its own type
to the resolved diameter and always draws a circle centred in the box it is given.

The app is edge-to-edge (`enableEdgeToEdge`); the dashboard gradient runs behind the system bars
while the content is inset with `safeDrawingPadding`, which is what keeps the gauge and the setup
button clear of a landscape navigation bar or a display cutout. The setup form is capped at 560 dp
and centred — a 32 character key field stretched across a landscape phone is unreadable. Screen
choice and typed input use `rememberSaveable`, so a rotation does not throw you back to setup or
wipe a half-entered key.

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
