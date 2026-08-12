# Victron BLE GATT Protocol — Reverse-Engineered from VictronConnect

This document captures the BLE protocol used by VictronConnect to read/write registers
(including load output control) on Victron SmartSolar MPPTs. Extracted by decompiling
the VictronConnect Android APK.

## Architecture (from APK disassembly)

VictronConnect uses a **two-layer** BLE architecture:

```
VeSmartDevice (high-level device model)
  └── VeSmartService (CBOR-framed path-based protocol over BLE)
        ├── keepAliveTimer (periodic heartbeat)
        ├── writeCbor() → 68c10002 characteristic
        └── notifications ← 68c10003 characteristic
  └── VeService (pin code, device info, DFU trigger)
  └── VeRegsHelper (maps vreg codes ↔ item paths)
        ├── getItemPathFromVeRegCode(0xEDAB, deviceId) → path string
        └── getVeRegCodeFromItemPath(path, deviceId) → 0xEDAB
```

### Key finding: NOT raw VE.Direct HEX over BLE

The BLE protocol is **not** the VE.Direct HEX protocol (`":7ABED00..."`) tunneled over a
characteristic. That protocol is only used over serial/USB (VE.Direct port).

Over BLE, VictronConnect uses a **higher-level path-based protocol** with CBOR encoding,
chunk-based flow control, and opcode framing on a control characteristic.

## BLE Service & Characteristics

| UUID | Role |
|------|------|
| `68c10001-b17f-4d3a-a290-34ad6499937c` | Primary service |
| `68c10002-b17f-4d3a-a290-34ad6499937c` | Write (TX to device) |
| `68c10003-b17f-4d3a-a290-34ad6499937c` | Notify (RX from device) |

The `6597xxxx-4bda-4c1e-af4b-551c4cf74769` UUID pattern (from community docs about
SmartShunts) is **NOT** used by SmartSolar MPPTs in VictronConnect.

## Connection Flow (from symbol analysis)

1. **Discover** service `68c10001-...`
2. **Subscribe** to notifications on `68c10003-...`
3. **Authenticate** — write PIN code via `VeService::PinCodeUuid` characteristic
   - Default PIN: `000000` (six zeros)
   - PUK code recovery exists for locked devices
4. **Negotiate chunk size** — `VeSmartService::writeCborChunkSize(maxChunk, mtu)`
5. **Keep-alive** — `VeSmartService::sendKeepAlive()` on a timer
6. **Exchange data** — CBOR-encoded path-value operations:
   - `setPathValue(deviceId, pathIndex, value)` — write a register
   - `getPathValue(deviceId, pathIndex)` — read a register
   - Flow control: `writeReadyToReceive()` / `freeChunkTimerTimeout()`

## Register Mapping

VictronConnect does NOT write raw register IDs over BLE. It uses a **path index** system:

```
VeRegsHelper::getItemPathFromVeRegCode(0xEDAB, deviceId)
  → returns a path string like "/Settings/LoadOutputControl"
  → mapped to a numeric pathIndex by getPathList()
```

The device reports its available paths via `VeSmartService::getPathList(deviceId)`.
Each path has an integer index. Reads and writes use this index, not the raw vreg hex.

## Load Output Control

From the product definition XML embedded in the binary:

```xml
<vreg label="load_control" has_load="1" get="0xEDAB"/>
```

The QML UI code:
```javascript
// Toggle switch in VictronConnect:
Switch {
    id: loadOutputSwitch
    checked: items.settings.mode.value === 3
}

// Setting load output mode:
items.settings.loadOperationMode.setValue(4)  // Always ON
items.settings.loadOperationMode.setValue(1)  // Normal/Auto

// The "mode" register determines on/off:
items.settings.mode.setValue(3)  // ON (value 0x03)
items.settings.mode.setValue(4)  // OFF
```

**Important**: VictronConnect uses **two registers together**:
- `loadOperationMode` (vreg `0xEDAB`) — the load algorithm (1=auto, 4=always on)
- `mode` (device mode register) — also plays a role in the on/off state

Setting `loadOperationMode = 4` means "always on" (overrides mode).
Setting `loadOperationMode = 1` + `mode = 3` means "on, normal operation".

## Implications for Our Implementation

The current `VictronGatt.kt` assumes:
1. ❌ Service UUID `65970000-...` → actually `68c10001-...`
2. ❌ Direct register-UUID writes → actually CBOR path-based protocol
3. ❌ Simple `08 00 19` framing → actually chunk-based CBOR with flow control
4. ✅ PIN code `000000` is correct
5. ✅ Register `0xEDAB` is the right register for load output control
6. ✅ Keep-alive is needed

### What needs to change

The GATT layer needs to implement the VeSmartService protocol:
1. Connect to `68c10001-...` service
2. Write PIN to authenticate
3. Negotiate CBOR chunk size based on MTU
4. Request path list from device (maps vreg → pathIndex)
5. Use `setPathValue(deviceId, pathIndex, value)` to write the register
6. Properly encode as CBOR chunks with flow control

This is significantly more complex than a simple characteristic write. The recommended
path is to **sniff the exact byte sequence** with an HCI log (see below) and replay it,
rather than implementing the full VeSmartService protocol stack.

---

## How to Sniff with nRF Connect / HCI Log

### Method 1: Bluetooth HCI snoop log (recommended)

This captures **all** BLE traffic at the HCI level — the exact bytes VictronConnect sends.

#### Enable HCI snoop log

1. **Settings → Developer Options → Enable Bluetooth HCI snoop log** (toggle ON)
2. Toggle Bluetooth off/on to start a fresh log
3. Open VictronConnect, connect to your MPPT, toggle the load output
4. Retrieve the log:
   ```sh
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
   ```
   or on newer Android:
   ```sh
   adb bugreport victron-sniff.zip
   # Extract btsnoop_hci.log from the zip
   ```

#### Analyze in Wireshark

1. Open `btsnoop_hci.log` in Wireshark
2. Filter: `btatt.opcode == 0x12 || btatt.opcode == 0x1b` (Write Request + Notifications)
3. Look for writes to the handle backing `68c10002-...`
4. The payload bytes are the exact CBOR frames

#### What to capture

Do this sequence in VictronConnect while the HCI log is running:
1. Connect to your MPPT (captures auth + path list negotiation)
2. Go to Settings → Load output → toggle it ON
3. Toggle it OFF
4. Disconnect

This gives you the complete byte sequence for both operations. You can then
hard-code these exact frames in `VictronGatt.kt` rather than implementing the
full protocol — simpler and guaranteed to work for your specific device.

### Method 2: nRF Connect (interactive exploration)

1. Open **nRF Connect** → Scanner → find your device (name like `SmartSolar HQ...`)
2. Tap **Connect** → enter PIN `000000` when prompted
3. Look for service `68c10001-b17f-4d3a-a290-34ad6499937c`
4. Enable notifications on `68c10003-...`
5. Write bytes to `68c10002-...` and observe responses

This is useful for interactive experimentation once you have the byte sequences
from the HCI capture.

## Key Register Reference

| Register | Name | Access | Values |
|----------|------|--------|--------|
| `0xEDAB` | Load Output Control | R/W | 0=OFF, 1=AUTO, 4=ALWAYS_ON, 5=ALWAYS_ON(alt) |
| `0xEDA8` | Load Output State | R | 0=OFF, 1=ON |
| `0xED9D` | Load switch high (V) | R/W | un16, ×0.01 V |
| `0xED9C` | Load switch low (V) | R/W | un16, ×0.01 V |

## Product IDs with Load Output

From the VictronConnect product definition, these product IDs have `has_load` capability:
```
0xA04C, 0xA054, 0xA042, 0xA053, 0xA043, 0xA055, 0xA066, 0xA05F,
0xA067, 0xA060, 0xA07B, 0xA079, 0xA07C, 0xA074, 0xA07A, 0xA07D,
0xA07F, 0xA075
```

The capability is reported via register `0x0140` (firmware ≥ 1.16), bit `0x0001`.

## Next Steps

1. **Capture HCI log** of VictronConnect toggling load output on your specific MPPT
2. **Extract the exact byte frames** from Wireshark (Write Request payloads to 68c10002)
3. **Update `VictronGatt.kt`** to replay those frames:
   - Fix service UUID to `68c10001-...`
   - Implement the auth + keepalive handshake
   - Hard-code the set-path-value CBOR frames for load output
4. **Test** on hardware
