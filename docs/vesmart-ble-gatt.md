# VeSmartService BLE GATT Protocol

Findings from HCI snoop analysis, live BLE debugging, and disassembly of the VictronConnect
ARM64 library (Aug 2026).

**The library is the authoritative source.** Where community documentation and the shipping
app disagree, the app wins — see [Two protocol generations](#two-protocol-generations).

The Victron SmartSolar MPPT exposes **two** relevant GATT services:

## Service 68c10001 — VeService (PIN auth / keepalive)

| UUID | Properties | CCCD | Role |
|------|-----------|------|------|
| 68c10002 | Write + Notify | ✓ | Keepalive, PIN auth at BLE bonding layer |
| 68c10003 | WriteWithoutResponse only | ✗ | Not used for protocol |

## Service 306b0001 — VeSmartService (register-based settings protocol)

| UUID | Properties | CCCD | Role |
|------|-----------|------|------|
| 306b0002 | Read + WnR + Notify | ✓ | Control: session init (fa80ff, f980), credit/ack (f901), errors (f7) |
| 306b0003 | WnR + Notify | ✓ | Commands + responses; also the **last chunk** of a split payload |
| 306b0004 | WnR + Notify | ✓ | Every chunk **except the last** of a split payload |

`getCharacteristics()` stores these at member offsets 0x50 / 0x68 / 0x80 respectively; the
0x50 validity check logs *"Control characteristic is does not have notification or
indication"*, which is what pins 306b0002 to the control role.

306b0004 is **not** a "bulk query" channel — `writeChunkToStack` logs *"Writing to data:"*
for intermediate chunks (→ 306b0004) and *"Writing to lastData:"* for the final one
(→ 306b0003). Our commands are a few bytes against a negotiated 128-byte chunk size, so they
are always single-chunk and go to 306b0003 whole.

## Addressing: register ids, not string paths

Settings are addressed by **16-bit VE.Direct register id**. The library's own log formats say
so: `RegId=%04X Flags=%02X Length=%d`, `Received set for different RegId %04X. Expected
RegId %04X`. The internal C++ class is `PathItem`, but it is keyed by register id — "path"
is an internal abstraction, not a wire string.

The `/Settings/...`, `/Mode`, `/Load/State` strings in the app are built with
`Utils.path(bindPrefix, …)` and belong to its **VenusOS/GX D-Bus/MQTT** transport, used when
talking to a Cerbo. They have nothing to do with the BLE link. An earlier version of this
implementation modelled BLE settings as those string paths and could never resolve one.

## Connection sequence (from VictronConnect HCI trace)

1. Connect (BLE LE, `TRANSPORT_LE`)
2. Discover services
3. Enable notifications (CCCD `0100`) on all three 306b chars
4. Session init: write `fa 80 ff` then `f9 80` to **306b0002** via WriteWithoutResponse
5. Device responds with `f9 01` on 306b0002 = session ready
6. Protocol commands go to **306b0003** via WriteWithoutResponse
7. Responses come back as notifications on **306b0003** (and 306b0002 for keepalives)

## Error codes

| Bytes | Meaning |
|-------|---------|
| `f7 03 00` | Error 3: session not initialized (commands before fa80ff/f980) |
| `f7 02 00` | Error 2: invalid command or not ready |
| `f9 01` | Session ready / keepalive ack |

## Framing: a flat CBOR sequence

A request is a bare concatenation of CBOR items — **no array or map wrapper**:

```
<opcode> <instance> <regId> [<value>] …
```

Each command function inline-builds a `Cbor` object (type tag 0 = unsigned integer, value =
the opcode), serialises it with `Cbor::write(QDataStream&)`, appends the arguments the same
way, and hands the buffer to `writeCbor`. Since every opcode is below 24 it encodes as a
single literal byte. Register ids are emitted as the 2-byte form `19 <hi> <lo>` even when a
shorter CBOR encoding would do.

Evidence — `setPathValues` @0x60a4f0c:

```
mov  w8, #0xc          ; opcode
str  wzr, [sp, #0xa8]  ; Cbor type tag = 0 (unsigned integer)
str  x8,  [sp, #0xb0]  ; Cbor value = 0xc
bl   Cbor::write       ; emits the single byte 0x0c
```

## Opcodes

Requests (client → device):

| Byte | Command | Payload |
|------|---------|---------|
| `01` | GetDevices | — |
| `0a` | GetPathList | instance |
| `0b` | GetPathValues | instance, then register ids |
| `0c` | **SetPathValues** | instance, then (register id, value) pairs |

Responses (device → client):

| Byte | Meaning |
|------|---------|
| `02` | DeviceList |
| `07` | Response (generic ack/status) |
| `08` | Value |
| `09` | ValueResponse |
| `0d` | PathList |
| `0e` | NewPath |
| `0f` | PathValue |

Names come from each handler's own diagnostic strings; the dispatch switch is at 0x609ab80.
Unknown opcodes log *"Received unknown data opcode"*.

Worked examples:

```
0b 03 19 ed ab           read load control on instance 3
0c 03 19 ed ab 04        set load control = 4 (always on) on instance 3
0c 00 19 00 93 42 10 27  keepalive: register 0x0093 = 10000 ms
```

## Values: two encodings

The app has two write paths, and they encode the value differently:

- `setPathValues(instance, QList<pair<int, QVariant>>)` — an integer QVariant serialises as a
  CBOR **unsigned int** (`04`). This is the path the settings UI uses.
- `setValue(instance, regId, QByteArray)` — serialises as a CBOR **byte string** holding the
  register's little-endian bytes (`42 10 27` for 10000). This is what the keepalive uses.

`encodeSetRegister` defaults to the unsigned-int form and can emit the byte-string form; the
GATT layer tries the former and falls back, because we cannot tell from the binary alone
which one this firmware accepts for `0xEDAB`.

## Flow control and keepalive

- `writeCborChunkSize(0x80, 0xff)` → `fa 80 ff`. Chunk size starts at 20 and is raised to 128.
- `writeReadyToReceive(n)` → `f9 <credit>`, but the app **accumulates** credit and only emits
  once ≥ 65 has built up. The device's inbound `f9 <n>` is the reciprocal: `f9 01` = one
  outstanding chunk permitted.
- Keepalive: `setValue(0, 0x0093, {0x10, 0x27})` — 10000 ms as a little-endian u16 — on a
  10 s single-shot timer re-armed each fire (`QTimer::setInterval(0x2710)` @0x60980dc).
  Our sessions are short-lived and finish well inside that window, so we do not run a timer.

All GATT writes use `WriteWithoutResponse`: the write mode passed to
`QLowEnergyService::writeCharacteristic` is computed as `(properties >> 2) & 1`, which is the
WriteNoResponse property bit, and it is set on all three 306b characteristics.

## Two protocol generations

Community documentation (and an earlier revision of this file) describes GetPathList = `03`,
GetPathValue = `05`, bulk = `06`, with `81`/`82` CBOR array wrappers around the arguments.
**The shipping library contains none of that.** Opcodes 3–6 are absent from its request set,
and no array wrapper is emitted. Those bytes are a previous protocol generation, preserved in
old capture files and third-party repos.

`06 00 82 18 93 42 10 27` from that older material is recognisably the *same keepalive* —
register `0x93`, value 10000 — under the legacy opcode and wrapper, which is a useful check
that the two generations describe the same device.

Treat `01` / `0a` / `0b` / `0c` as authoritative.

## Load output

| Register | Label | Access | Notes |
|----------|-------|--------|-------|
| `0xEDAB` | load_control | read/write | 1 = automatic, 4 = always on; **bit 7 = streetlight** |
| `0xEDAC` | load_offset | read/write | load disconnect/reconnect offset |
| `0xED9D` | load_switch_high_level | read/write | un16, 0.01 V |
| `0xED9C` | load_switch_low_level | read/write | un16, 0.01 V |
| `0xED90` | load_aes_timer | read/write | automatic energy selector |
| `0x0140` | capabilities_reg | read | bit `0x0001` = has load output |

From VictronConnect's embedded product XML for model 41055 (`0xA05F`, SmartSolar MPPT 100/20):
`<vreg label="load_control" has_load="1" needs_migration="1" get="0xEDAB"/>`. When a `set=`
attribute is absent the write goes to the same register as `get`; `set="0xFFFE"` is the
"not settable" sentinel.

The mode **shares its register with the streetlight flag** (QML:
`(loadOperationMode.value & (1<<7)) >>> 7`), so a write must carry bit 7 through or it
silently disables streetlight. Read before write.

`0xA05F` appears in the app's `hasLoadOutputConfig` product list, so this MPPT's load output
is configurable.

## Key debugging findings

- The `68c10001` service only has 2 chars, and `68c10003` has NO Notify property and NO CCCD
  → `enableNotifications` on it always fails
- The `306b0001` service requires an **encrypted link** (bonding) — CCCD writes hang without it
- VictronConnect uses `WriteWithoutResponse` (WriteCmd 0x52) for ALL protocol data
- Android GATT cache shows both services when bonded (`bondState=12`)
- The PIN is handled at the BLE bonding layer (`device.setPin`), not in GATT payload
- The load register is **not necessarily on instance 0** — a capture showed it queried on
  instance 3. Enumerate with GetDevices (`01`) and probe, rather than assuming 0.
- Replies can arrive before the caller starts waiting, because writes are fire-and-forget.
  Buffer notifications in a queue; a `CompletableDeferred` created after the write drops them.

## Full GATT service layout (from bleak scan on Linux)

```
Service: 97580001-ddf1-48be-b73e-182664615d8e
  97580006 [read, write, notify] handle=0x001c  CCCD=0x001e
  97580002 [read] handle=0x0015
  97580004 [write] handle=0x001a
  97580003 [write, notify] handle=0x0017  CCCD=0x0019

Service: 00001801 (Generic Attribute Profile)
  00002a05 [indicate] handle=0x000b  CCCD=0x000d

Service: 306b0001-b081-4037-83dc-e59fcc3cdfd0 (VeSmartService)
  306b0004 [write-without-response, notify] handle=0x0026  CCCD=0x0028
  306b0002 [read, write-without-response, notify] handle=0x0020  CCCD=0x0022
  306b0003 [write-without-response, notify] handle=0x0023  CCCD=0x0025

Service: 00001800 (Generic Access Profile)
  00002a01 [read] handle=0x0004
  00002aa6 [read] handle=0x0008
  00002a00 [read] handle=0x0002
  00002a04 [read] handle=0x0006

Service: 68c10001-b17f-4d3a-a290-34ad6499937c (VeService)
  68c10003 [write-without-response] handle=0x0012
  68c10002 [write, notify] handle=0x000f  CCCD=0x0011
```

## HCI handle mapping (from VictronConnect btsnoop)

| Handle | UUID | Role |
|--------|------|------|
| 0x0020/0x0021 | 306b0002 | Smart RX |
| 0x0023/0x0024 | 306b0003 | Smart TX (main protocol channel) |
| 0x0026/0x0027 | 306b0004 | Smart Bulk |
