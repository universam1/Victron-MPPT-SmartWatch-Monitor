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
| `data` | `VictronScanner`, `VictronRepository`, `VictronViewModel`, `ScanWorker`/`ScanScheduler`, DataStore, `Formatting`, `update/*` (self update from GitHub releases) |
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
- **The trend buffer spans the runtime, bounded by decimation, and each bucket keeps its extreme.**
  `MetricSeries` is not a sliding window: it starts at the first reading and grows, and when it
  fills up adjacent buckets merge and `readingsPerBucket` doubles. A merged bucket keeps the sample
  furthest from zero **with its sign** — never the average. That is what makes `peak` exact rather
  than approximate, and it is the only reason the arc marker can be trusted; it also keeps a
  discharge spike, which a plain `max` would erase from the one signed metric. `points` appends the
  bucket still filling so the curve advances on every reading, and the sparkline x axis stays
  **reading-index based** on purpose: scans are bounded to a visible screen, so wall-clock data is
  bursty and a time axis would draw a day as mostly empty gaps. The span label supplies the missing
  wall-clock context.
- **The day purge lives in `ReadingHistory`, not in `MetricSeries`.** One day check per reading
  clears *every* series. Checking per metric would leave yesterday's points in any curve whose field
  is absent from the first reading after midnight — a charger with no load output, or a "not
  available" sentinel — and blend them into today's. `yieldTodayWh` additionally restarts when the
  counter *drops*, because the charger resets it on its own clock: without that, one `0` after
  3.2 kWh pins the rest of the day's sparkline to the top of its box.
- **`observedPvPeakW`/`observedCurrentPeakA` are a fallback *scale*, the marker is today's peak.**
  The observed peaks are all-time and persisted, and must stay that way — they are what scales an
  unknown model's gauge. Using them for the tick would pin it at last week's noon. Keep the
  asymmetry.
- **`CurrentArcGauge` is text-only; its arc lives inside `PvArcGauge`.** The 90° current arc shares
  the PV arc's circle (same centre, same radius, thinner stroke), drawn in the gap at the bottom.
  `CurrentArcGauge` provides only the label, value, and sparkline row beneath the combined gauge.
- **The UI observes snapshots and history through `throttleLatest` (2 Hz), the repository does not.**
  Every emission redraws the gradient arc gauges and sparklines, so the cap is about redraw cost;
  the repository still records every reading, which is what keeps the history buffer complete. Use
  `throttleLatest`, not `sample` — `sample` runs a ticker for as long as the flow is collected, and
  these flows live for the whole screen-on time, so an idle 2 Hz timer would cost more than it saves.
- **The tile must not do BLE work in `onTileRequest`.** It renders the DataStore snapshot and asks
  `ScanScheduler` for a scan; the scan then calls `VictronTileService.requestUpdate`.
- **Every surface shows the age of its data** (`Formatting.age`), and stale values are visibly
  dimmed rather than hidden.
- **Only `BLUETOOTH_SCAN` (with `neverForLocation`) for scanning, plus `INTERNET` and
  `REQUEST_INSTALL_PACKAGES` for the self updater.** No `BLUETOOTH_CONNECT`, no location, no
  foreground service — we never connect, and expedited work covers background scans. No
  `POST_NOTIFICATIONS` either: the updater stages silently and reports in the settings screen, so
  it needs no runtime prompt. Don't add one to "tell the user about an update".
- **The self updater is the distribution channel** (`data/update/`, [docs/updates.md](docs/updates.md)).
  The app is in no store, so it polls this repository's releases every 6 h, downloads the APK whose
  name carries its own variant (`-wear-` / `-phone-`), checksums it against `SHA256SUMS.txt` and
  commits a `PackageInstaller` session. Three things must not be "simplified":
  - **`ReleaseCatalog.versionCode` mirrors `release.yml`** (`v1.2.3` → `10203`). Change one and
    devices stop recognising releases as newer; `ReleaseCatalogTest` is what pins them together.
  - **`ReleaseCatalog` stays Android-free and `data`'s only unit-tested logic in `update/`.** The
    network layer takes its `HttpURLConnection` through a constructor parameter for the same
    reason — both are testable without an SDK.
  - **The staged APK in `cacheDir/updates` is the only bookkeeping.** Its file name carries the
    version, so a cleared cache cannot leave a phantom "update ready" flag in DataStore. Do not
    add a DataStore field for it.
- **Staging and installing are separate, and below API 31 the worker must not install.** A
  confirmation dialog cannot be started from the background, so the worker only downloads and
  verifies; the install happens on the next foreground moment (`VictronViewModel.init`). Only
  API 31+ may install from the worker, where a same-signature self update can run unattended
  (`SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`) — that is what makes a test
  fleet update itself, so don't drop that flag.
- **Prereleases are never offered as updates.** `release.yml` gives `v1.1.0-beta1` the `versionCode`
  of `v1.1.0`, so a prerelease can only ever tie or lose — offering one would be a no-op or a
  downgrade attempt.
- **Unknown record types must survive**: decrypt, keep the payload hex, show it on the Raw data
  screen. Don't throw it away and don't guess a layout.
- Keys are matched by address first, then by the plaintext key-check byte (first key byte). Keep the
  fallback — it is what makes a changed BLE address harmless.
- Formatting lives in `data/Formatting.kt` only, and colours in `data/VictronPalette.kt` only (ARGB
  ints, because the tile knows nothing about Compose). App and tile must never diverge. Update
  labels follow the same rule: `data/update/UpdateStatusText.kt` plus `data/src/main/res` — one
  mapping, one translation, so watch and phone describe the same state identically. Referencing
  those strings from an app needs the *library's* R class (`import de.universam.victron.data.R as
  DataR`): `android.nonTransitiveRClass=true` keeps library resources out of the app's own R.
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
  arrangements call the *same* `DashboardHeader`/`ValueTiles`/`PvArcGauge`/`CurrentArcGauge` — only
  `maxArcHeight`/`strokeWidth`/`sparklineHeight` differ between them, never the composable. Don't
  fork them into two layouts that drift. Deriving the square gauge from the width in landscape is the bug this replaced.
- **`PvArcGauge` reserves 0.96 of its diameter in height** (`ARC_HEIGHT_FRACTION`):
  it draws both the 230° PV arc and the 90° battery current arc on the same circle (matching the
  watch's ring layout). The current arc fills the gap at the bottom, so the box is nearly square —
  0.96 leaves just enough margin for the current arc's glow at the nadir. The circle hangs from the
  top of the box — which is why the sweep gradient and the rotation pivot use the computed
  `arcCenter` and not the DrawScope `center`, and why the value text is offset down into it.
  **Never give the gauge a fixed height** (`fillMaxHeight`/`fillMaxSize` inside a bounded box): that
  leaves its aspect ratio no way to also honour the width, and it overflows its column instead of
  shrinking.
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

The arc gauges paint a **sweep gradient along their length** (not a flat color):

| Arc | Geometry | Gradient (start → tip) |
|---|---|---|
| PV power (wear + mobile) | 230°, starts 155° | `HEAT_LOW` white → `HEAT_MID_LOW` SOLAR yellow → `HEAT_MID` dark orange → `HEAT_HIGH` fire-red |
| Battery current (wear) | 90°, starts 45° | `CURRENT_LOW` green → `CURRENT_MID` yellow-green → `CURRENT_HIGH` orange |
| Battery current (mobile) | 90°, starts 45° — same circle as the PV arc inside `PvArcGauge` | same `CURRENT_*` stops when charging; flat `DISCHARGING` orange when the current is negative |

Four stops on the PV arc, not three: `HEAT_LOW` is white, and the SOLAR yellow is `HEAT_MID_LOW`.

Implementation: `Brush.sweepGradient` inside a `rotate(startAngle)` transform, then `drawArc` from
0° so the gradient aligns with the arc start. The glow layer uses a **low** stop at reduced alpha,
never the tip colour — wear the first stop at α 0.25, mobile `HEAT_MID_LOW` (the *second* stop) at
α 0.35, because mobile's first stop is white and a white glow washes the arc out.

The mobile arcs are drawn through one helper, `mobile/.../dashboard/GaugeArc.kt`
(`ArcSpec` + `drawArcTrack`/`drawArcFill`/`drawPeakTick`), and both live in `PvArcGauge`'s Canvas
on the same circle — the power gauge, the current gauge and the peak tick cannot drift apart. Wear
keeps its own `PowerArc` — the modules do not depend on each other, and the numbers that must agree
live in `data`.

When **stale**, all four arcs fall back to flat `TEXT_DIM` (no gradient).

### Peak marker

Every arc marks the **highest value in the trendline window** with a thin line drawn radially
*across* the track:

- position from `DeviceSnapshot.pvPeakFraction` / `batteryCurrentPeakFraction`, which divide by the
  *same* full scale as `pvFraction`/`batteryCurrentFraction`, so tick and fill cannot disagree;
- colour `VictronPalette.PEAK_MARKER` (90 % white), `TEXT_DIM` at α 0.7 when stale — the peak is a
  fact about the recorded window, not about freshness, so it is dimmed rather than hidden;
- thickness = 0.15 × the arc stroke, floor 1.5 dp; it overhangs the stroke by `(glow − stroke) / 2`
  on each side, which is exactly the inset the canvas already has, so it can never be clipped;
- animated with the fill's own tween, so a new high and the value that set it move together;
- omitted when there is no peak, when the peak is 0, and when it would land inside the dot the arc
  always draws at its start.

### Wear hero detail buttons

The battery row is a **single merged DetailButton** showing all three values:
`"13.2 V  1.8 A  24 W"` — voltage, current, charging power — in `BATTERY` blue.
Icon: `BatteryChargingFull`. Label: `label_battery` ("Battery" / "Batterie").

The old separate "Solar" button (`label_pv`) that showed `batteryPowerW` is removed — that value was
mislabeled (it is battery V × A, not PV power).
