# Victron MPPT SmartWatch Monitor

Wear OS app + tile (and a phone app) that read a Victron SmartSolar/BlueSolar MPPT from its
connectionless BLE *Instant Readout* advertisements. Kotlin, Gradle, builds headless in Docker.

## Build & test

```sh
./build.sh test        # :protocol unit tests — works without an Android SDK
./build.sh apk         # :wear + :mobile debug APKs, inside docker/Dockerfile
./build.sh install-wear
VICTRON_NATIVE=1 ./build.sh apk   # use a local JDK 17+/Android SDK instead of Docker
```

**Always use the container** (`./build.sh`) to compile and verify locally — even when a native JDK is
available. The container is the source of truth; if it doesn't build there, it doesn't ship.

**Always update screenshot tests** after any UI change. The project has Compose Preview Screenshot
tests (`screenshotTest` source sets) that act as visual regression goldens:

```sh
./build.sh screenshots       # regenerate reference PNGs
```

This regenerates the reference PNGs under `*/screenshotTestDebug/reference/`. Commit the updated
images alongside the code change. The previews in `WearPreviews.kt` and `DashboardPreviews.kt` must
reflect the current spec — if the UI changes, update the preview composables first, then regenerate.
After regenerating, **read the PNGs with the Read tool** and verify them visually against the specs
below before committing.

Versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml). CI
([.github/workflows/build.yml](.github/workflows/build.yml)) runs the protocol tests without an
Android SDK, then the `:data` unit tests and both APKs.

Releases are tag-driven: `git push origin v1.2.3` makes
[.github/workflows/release.yml](.github/workflows/release.yml) test, build and publish both release
APKs. `versionName` comes from the tag and `versionCode` from its numbers (`v1.2.3` → `10203`) via the
`VICTRON_VERSION_NAME`/`VICTRON_VERSION_CODE` env vars the app modules read — don't hardcode versions
in the build files. Signing uses `release.keystore` plus `SIGNING_*` env vars when present and falls
back to the debug key, so `assembleRelease` always works locally.

## Architecture

`protocol` (pure Kotlin/JVM) → `data` (Android library) → `wear` / `mobile` apps.
See [docs/architecture.md](docs/architecture.md); the wire format is in
[docs/victron-ble-protocol.md](docs/victron-ble-protocol.md).

| Module | Responsibility |
|---|---|
| `protocol` | `VictronAdvertisement` (header), `VictronCipher` (AES-CTR), `BitReader`, `records/*`, `VictronModels` |
| `data` | `VictronScanner`, `VictronRepository`, `VictronViewModel`, `ScanWorker`/`ScanScheduler`, DataStore, `Formatting` |
| `wear` | Compose for Wear OS: scrollable gauge + detail list, tile, navigation (Hero → Overview → Devices) |
| `mobile` | Compose Material 3 screens |

## Rules that matter (do not break these)

- **`protocol` stays Android-free.** No `android.*` imports, no Context. It is the only module with
  unit tests and it must keep building without an SDK — that is what makes the decoder verifiable.
- **The root `build.gradle.kts` must not declare the Android plugins** (not even `apply false`), and
  `org.gradle.configureondemand=true` must stay in `gradle.properties`. Both are what let
  `:protocol:test` run on a machine with no Google Maven access.
- **AES-CTR counter is little endian**, initial value = the 16 bit nonce as a little endian 128 bit
  integer. Do not "simplify" this to `AES/CTR/NoPadding` — the JCE counts big-endian and multi-block
  payloads would decrypt to garbage. The public test vector in `TestVectors` guards this.
- **Payload bit fields are LSB-first and unaligned**; always read them through `BitReader`.
- **"Not available" sentinels become `null`, never `0`** (`0xFF`, `0x7FFF`, `0xFFFF`, `0x1FF`, …).
  Formatting turns `null` into `–`, so a missing value never looks like a real zero.
- **Never scan continuously.** Scans are bounded: while a screen is visible, or a short expedited
  window from the tile/worker. Anything else drains a watch in hours.
- **`ScanAggressiveness` is the only real power knob, and the screen-long scan stays `Balanced`.**
  The duty cycle is fixed in `startScan`; dropping advertisements later saves a decode, not a
  milliamp. `LowLatency` means a 100 % duty cycle receiver and belongs only to short bounded windows
  (`scanOnce` from the tile/worker) where time-to-first-packet is the point. Don't raise
  `collectAdvertisements`/`startLiveScan` back to `LowLatency` to make the gauge feel livelier —
  the values change about once a second no matter how hard you listen.
- **Repeat advertisements are filtered by reading, not by time.** A Victron repeats each reading on
  three channels and the stack reports every reception, so the same reading arrives several times a
  second. `collectAdvertisements` skips a `(recordTypeCode, nonce)` it just handled — the nonce is
  the device's data counter, so this drops only genuine repeats and never delays a new reading. The
  filter is per scan session: a restart must re-process what is on the air. A time-based rate limit
  here would be wrong, because it cannot tell a repeat from a new reading.
- **The UI observes snapshots and history through `throttleLatest` (2 Hz), the repository does not.**
  Every emission redraws the gradient arc gauges and sparklines, so the cap is about redraw cost;
  the repository still records every reading, which is what keeps the history buffer complete. Use
  `throttleLatest`, not `sample` — `sample` runs a ticker for as long as the flow is collected, and
  these flows live for the whole screen-on time, so an idle 2 Hz timer would cost more than it saves.
- **The tile must not do BLE work in `onTileRequest`.** It renders the DataStore snapshot and asks
  `ScanScheduler` for a scan; the scan then calls `VictronTileService.requestUpdate`.
- **Every surface shows the age of its data** (`Formatting.age`), and stale values are visibly
  dimmed rather than hidden.
- **Only `BLUETOOTH_SCAN` (with `neverForLocation`).** No `BLUETOOTH_CONNECT`, no location, no
  foreground service — we never connect, and expedited work covers background scans.
- **Unknown record types must survive**: decrypt, keep the payload hex, show it on the Raw data
  screen. Don't throw it away and don't guess a layout.
- Keys are matched by address first, then by the plaintext key-check byte (first key byte). Keep the
  fallback — it is what makes a changed BLE address harmless.
- Formatting lives in `data/Formatting.kt` only, and colours in `data/VictronPalette.kt` only (ARGB
  ints, because the tile knows nothing about Compose). App and tile must never diverge.
- **Both apps keep `applicationId = de.universam.victron`** and the same signing key. The Wear OS
  Data Layer namespaces data items per package + signature — change one id and the key sync stops
  working silently. Module `namespace`s stay distinct (`.wear` / `.mobile`). For the same reason the
  signing block in `wear/build.gradle.kts` and `mobile/build.gradle.kts` is duplicated verbatim:
  if you change one, change the other.
- **Config sync is a per-device last-write-wins union** (`AppConfig.mergeDevices`). Do not "fix" it
  into a wholesale replace: that lets one device wipe the other's keys. Removals are intentionally
  not propagated; `backgroundScanEnabled`/scan window stay local per device.
- **The gauge scales are derived from the model, not configured.** A charger's name carries its
  rating (`MPPT 100/20` → 20 A), so `VictronModels.maxChargeCurrentA` is the current arc's full
  scale and `rating × battery voltage` is `pvScaleMaxW` — the most power that charger can put into
  the battery. Only an unknown model falls back to the observed peak rounded up (floor 50 W / 5 A),
  and `carryOver` must keep `observedPvPeakW`/`observedCurrentPeakA` across advertisements. There is
  deliberately no user setting for either scale: the device already knows.
- **The hero/dashboard surfaces render without a device.** `HeroContent` and `DeviceDashboard` take a
  nullable snapshot: no device means `–` everywhere and arcs at zero, never a fake `0 W`, and every
  navigation affordance stays reachable — that is what lets the UI be checked without a Victron in
  range. Do not put them back behind an "empty state" that replaces the screen.
- **The phone dashboard is laid out by window shape, not orientation** (`BoxWithConstraints`,
  `maxWidth > maxHeight`): one scrolling column when tall, two columns with a height-sized gauge
  (`PvArcGauge(matchHeightFirst = true)`) when wide, where the header also puts the model name
  beside the device name rather than under it (`DashboardHeader(singleLine = true)`). Both
  arrangements call the *same* `DashboardHeader`/`ValueTiles`/`PvArcGauge` — don't fork them into two
  layouts that drift. Deriving the square gauge from the width in landscape is the bug this replaced.
- **`PvArcGauge` reserves 0.8 of its diameter in height, not a full square** (`ARC_HEIGHT_FRACTION`):
  the 240° arc's tips sit half a radius below the centre, so a square box always ended in an empty
  band, and that band pushed the bottom tiles off a portrait screen. The circle hangs from the top of
  the box — which is why the sweep gradient and the rotation pivot use the computed `arcCenter` and
  not the DrawScope `center`, and why the value text is offset down into it. **Never give the gauge a
  fixed height** (`fillMaxHeight`/`fillMaxSize` inside a bounded box): that leaves its aspect ratio no
  way to also honour the width, and it overflows its column instead of shrinking.
- **The phone app is edge-to-edge**: gradients may run behind the system bars, content must be
  inset (`safeDrawingPadding`), or a landscape navigation bar and display cutouts clip it.
- **The hero gauge item is sized with `Modifier.fillParentMaxSize()`** (the `ScalingLazyListItemScope`
  one). A lazy list measures items with an unbounded height, so `fillMaxSize()` there collapses to the
  content height, the bezel arcs shrink to a sliver, and auto-centering pushes the gauge below the top
  of the screen.

## Conventions

- Kotlin explicit API mode in `protocol`; `public` modifiers kept in `data` for the same reason.
- UI strings go through `strings.xml` with a German `values-de` translation. No hardcoded user text.
- Wear UI sticks to a conservative Compose-for-Wear-OS subset (`ScalingLazyColumn`, `Button`,
  `ListHeader`, `Text`, Material Icons Extended) — HeroScreen is a `ScalingLazyColumn` whose first
  item is the fullscreen gauge and following items are detail buttons. No separate detail route, and
  no `HorizontalPager`: it fights the system swipe-to-dismiss gesture and leaves a blank page behind
  when navigation returns. Leaving the hero screen is a `Button` at the end of the list.
- No dependency injection framework: `VictronData` is the whole graph.

## Visual specs

Keep this section updated when the UI changes. Screenshots are verified against these definitions.

### Arc gauges — heat gradient

Both arc gauges paint a **sweep gradient along their length** (not a flat color):

| Arc | Geometry | Gradient (start → tip) |
|---|---|---|
| PV power (wear + mobile) | 240°, starts 150° | `HEAT_LOW` yellow → `HEAT_MID` orange → `HEAT_HIGH` fire-red |
| Battery current (wear) | 104°, starts 38° | `CURRENT_LOW` green → `CURRENT_MID` yellow-green → `CURRENT_HIGH` orange |

Implementation: `Brush.sweepGradient` inside a `rotate(startAngle)` transform, then `drawArc` from
0° so the gradient aligns with the arc start. The glow layer uses the tip color at reduced alpha.

When **stale**, both arcs fall back to flat `TEXT_DIM` (no gradient).

### Wear hero detail buttons

The battery row is a **single merged DetailButton** showing all three values:
`"13.2 V  1.8 A  24 W"` — voltage, current, charging power — in `BATTERY` blue.
Icon: `BatteryChargingFull`. Label: `label_battery` ("Battery" / "Batterie").

The old separate "Solar" button (`label_pv`) that showed `batteryPowerW` is removed — that value was
mislabeled (it is battery V × A, not PV power).
