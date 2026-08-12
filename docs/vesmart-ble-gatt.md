# VeSmartService BLE GATT Protocol

Findings from HCI snoop analysis and live BLE debugging (Aug 2026).

The Victron SmartSolar MPPT exposes **two** relevant GATT services:

## Service 68c10001 — VeService (PIN auth / keepalive)

| UUID | Properties | CCCD | Role |
|------|-----------|------|------|
| 68c10002 | Write + Notify | ✓ | Keepalive, PIN auth at BLE bonding layer |
| 68c10003 | WriteWithoutResponse only | ✗ | Not used for protocol |

## Service 306b0001 — VeSmartService (path-based settings protocol)

| UUID | Properties | CCCD | Role |
|------|-----------|------|------|
| 306b0002 | Read + WnR + Notify | ✓ | "RX": session init (fa80ff, f980), keepalive ack (f901) |
| 306b0003 | WnR + Notify | ✓ | "TX": protocol commands + responses |
| 306b0004 | WnR + Notify | ✓ | "Bulk": batched path queries |

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

## Protocol commands (from HCI trace)

| Bytes | Command | Expected response |
|-------|---------|-------------------|
| `01` | GetDevices | `029f000001000301ff` |
| `03 00` | GetPathList (instance 0) | `07000300` + path index list |
| `05 XX 81 19 YYZZ` | GetPathValue(instance, register) | Value response |
| `06 00 82 18 93 42 10 27 ...` | Bulk subscription/query | Multiple responses |

## Key debugging findings

- The `68c10001` service only has 2 chars, and `68c10003` has NO Notify property and NO CCCD
  → `enableNotifications` on it always fails
- The `306b0001` service requires an **encrypted link** (bonding) — CCCD writes hang without it
- VictronConnect uses `WriteWithoutResponse` (WriteCmd 0x52) for ALL protocol data
- Android GATT cache shows both services when bonded (`bondState=12`)
- The PIN is handled at the BLE bonding layer (`device.setPin`), not in GATT payload

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
