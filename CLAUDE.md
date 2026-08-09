# Victron MPPT SmartWatch Monitor

Wear OS tile + app (and a phone app) that read a Victron SmartSolar/BlueSolar MPPT from its
connectionless BLE *Instant Readout* advertisements. Kotlin, Gradle, builds headless in Docker.

## Build & test

```sh
./build.sh test        # :protocol unit tests — works without an Android SDK
./build.sh apk         # :wear + :mobile debug APKs, inside docker/Dockerfile
./build.sh install-wear
VICTRON_NATIVE=1 ./build.sh apk   # use a local JDK 17+/Android SDK instead of Docker
```

Versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml). CI
([.github/workflows/build.yml](.github/workflows/build.yml)) runs the protocol tests without an
Android SDK and then builds both APKs.

## Architecture

`protocol` (pure Kotlin/JVM) → `data` (Android library) → `wear` / `mobile` apps.
See [docs/architecture.md](docs/architecture.md); the wire format is in
[docs/victron-ble-protocol.md](docs/victron-ble-protocol.md).

| Module | Responsibility |
|---|---|
| `protocol` | `VictronAdvertisement` (header), `VictronCipher` (AES-CTR), `BitReader`, `records/*`, `VictronModels` |
| `data` | `VictronScanner`, `VictronRepository`, `VictronViewModel`, `ScanWorker`/`ScanScheduler`, DataStore, `Formatting` |
| `wear` | Compose for Wear OS screens + `tile/VictronTileService` |
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
- Formatting lives in `data/Formatting.kt` only, so a value looks the same in app, tile and phone.

## Conventions

- Kotlin explicit API mode in `protocol`; `public` modifiers kept in `data` for the same reason.
- UI strings go through `strings.xml` with a German `values-de` translation. No hardcoded user text.
- Wear UI sticks to a conservative Compose-for-Wear-OS subset (`ScalingLazyColumn`, `Card`,
  `Button`, `ListHeader`, `Text`) — the tiny keypad keys are plain clickable boxes on purpose.
- No dependency injection framework: `VictronData` is the whole graph.
