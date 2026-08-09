# Victron "Instant Readout" BLE advertisements

Victron devices with *Instant readout* enabled broadcast their live measurements inside the
manufacturer-specific data of their BLE advertisements. Reading them needs **no connection**: no
pairing, no GATT, and VictronConnect keeps working in parallel. Advertisements repeat roughly once
per second.

The payload is encrypted with a per-device key that VictronConnect shows under
**device → Settings (gear) → Product info → “Instant readout via Bluetooth” → Encryption data**
(32 hex characters). Without that key the *values* stay opaque, but the header does not — model,
record type, address and signal strength are readable, which is why this app can list devices
before you have entered anything.

## Frame layout

Manufacturer-specific data of company id **`0x02E1`** (Victron Energy BV). Android hands it over
without the two company id bytes via `ScanRecord.getManufacturerSpecificData(0x02E1)`:

| Offset | Size | Field |
|-------:|-----:|-------|
| 0 | 1 | `0x10` — “Product Advertisement”. Anything else must be ignored. |
| 1 | 1 | Length of the extra manufacturer data record |
| 2 | 2 | Model id, little endian (e.g. `0xA053` = SmartSolar MPPT 75/15) |
| 4 | 1 | Record type (see below) |
| 5 | 2 | Nonce / data counter, little endian |
| 7 | 1 | First byte of the advertisement key (key check) |
| 8 | n | Ciphertext |

Implemented by [`VictronAdvertisement`](../protocol/src/main/kotlin/de/universam/victron/protocol/VictronAdvertisement.kt).

The key-check byte is genuinely useful: it lets a receiver decide *which* of several configured
keys belongs to an advertisement without relying on the MAC address.

## Encryption

AES-128 in **CTR** mode:

* key = the 16 byte advertisement key,
* the counter block is the 16 bit nonce written as a **little endian 128 bit integer**
  (`nonce & 0xFF`, `nonce >> 8`, then fourteen zero bytes),
* following blocks increment that counter **little endian**.

That last point matters: the JCE's `AES/CTR/NoPadding` counts big-endian, so it produces the right
keystream only by accident for single-block payloads. This project therefore generates the
keystream with AES-ECB and XORs it itself — see
[`VictronCipher`](../protocol/src/main/kotlin/de/universam/victron/protocol/VictronCipher.kt).

Verified end-to-end test vector (a BlueSolar MPPT 75/15, also used in
`protocol/src/test/kotlin/.../TestVectors.kt`):

```
manufacturer data  100242a0016207ad ceb37b605d7e0ee21b24df5c
advertisement key  adeccb947395801a4dd45a2eaa44bf17
nonce              0x0762
plaintext          04006c050e000300130000fe
→ Absorption, 13.88 V, 1.4 A, 19 W PV, 30 Wh today, load 0.0 A
```

## Payload packing

The decrypted payload is a **bit field, packed least significant bit first**, and fields are not
byte aligned (a solar charger record ends with a 9 bit load current). Reading it is what
[`BitReader`](../protocol/src/main/kotlin/de/universam/victron/protocol/BitReader.kt) does.

Every field has a “not available” sentinel: the largest value of its width
(`0xFF`, `0x7FFF` for signed, `0xFFFF`, `0x1FF`, …). Those become `null`, never `0`.

### Record types

| Code | Device | Decoded here |
|-----:|--------|--------------|
| `0x01` | Solar charger (SmartSolar / BlueSolar MPPT) | ✅ |
| `0x02` | Battery monitor (SmartShunt, BMV-7xx) | header only |
| `0x03` | Inverter | header only |
| `0x04` | DC/DC converter | header only |
| `0x05` | SmartLithium | header only |
| `0x06` | Inverter RS | header only |
| `0x08` | AC charger | header only |
| `0x09` | Smart BatteryProtect | header only |
| `0x0A` | Lynx Smart BMS | header only |
| `0x0B` | Multi RS | header only |
| `0x0C` | VE.Bus | header only |
| `0x0D` | DC energy meter | header only |
| `0x0F` | Orion XS | header only |

“header only” means: decrypted, but surfaced as an `UnknownRecord` with the raw payload — visible
on the app's *Raw data* screen, which is exactly what you need to add a decoder.

### `0x01` Solar charger

| Bits | Field | Scale | N/A |
|-----:|-------|-------|-----|
| 8 | device state | VE.Direct state code | `0xFF` |
| 8 | charger error | VE.Direct error code | `0xFF` |
| 16 | battery voltage (signed) | 0.01 V | `0x7FFF` |
| 16 | battery current (signed) | 0.1 A | `0x7FFF` |
| 16 | yield today | 10 Wh | `0xFFFF` |
| 16 | PV power | 1 W | `0xFFFF` |
| 9 | load current | 0.1 A | `0x1FF` |

### `0x02` Battery monitor — for later

| Bits | Field | Scale | N/A |
|-----:|-------|-------|-----|
| 16 | time to go | 1 min | `0xFFFF` |
| 16 | battery voltage (signed) | 0.01 V | `0x7FFF` |
| 16 | alarm reason | bit mask | – |
| 16 | aux voltage / mid voltage / temperature | see aux mode | – |
| 2 | aux input mode | 0 = starter V, 1 = mid V, 2 = temperature, 3 = none | – |
| 22 | current (signed) | 0.001 A | `0x3FFFFF` |
| 20 | consumed Ah | 0.1 Ah | `0xFFFFF` |
| 10 | state of charge | 0.1 % | `0x3FF` |

### `0x0A` Lynx Smart BMS — for later

| Bits | Field | Scale | N/A |
|-----:|-------|-------|-----|
| 8 | error flags | – | – |
| 16 | time to go | 1 min | `0xFFFF` |
| 16 | battery voltage (signed) | 0.01 V | `0x7FFF` |
| 16 | battery current (signed) | 0.1 A | `0x7FFF` |
| 16 | IO status | bit mask | – |
| 18 | alarm flags | bit mask | – |
| 10 | state of charge | 0.1 % | `0x3FF` |
| 20 | consumed Ah | 0.1 Ah | `0xFFFFF` |
| 7 | battery temperature | °C, offset −40 | `0x7F` |

## Sources

* Victron, *Extra Manufacturer Data* (the official description of the record layouts).
* [keshavdv/victron-ble](https://github.com/keshavdv/victron-ble) — reference implementation the
  layouts above and the test vector were cross-checked against.
* [Victron MPPT error codes](https://www.victronenergy.com/live/mppt-error-codes) for the error
  labels.
