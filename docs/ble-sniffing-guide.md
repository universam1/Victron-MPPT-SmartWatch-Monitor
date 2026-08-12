# Sniffing Victron BLE GATT Traffic with nRF Connect

This guide explains how to capture the BLE GATT communication between VictronConnect and
your SmartSolar MPPT, so you can verify or adjust the register framing used in the load
output control feature.

## What you need

- Android phone with **nRF Connect** (Nordic Semiconductor, free on Play Store)
- **Wireshark** on a PC (optional, for deeper analysis)
- **Android Developer Options → Enable Bluetooth HCI snoop log** (for full pcap capture)
- Your Victron MPPT with BLE enabled and the PIN code (default: `000000`)

## Method 1: nRF Connect (interactive, quick)

### Step 1: Bond with the device

1. Open **nRF Connect** → Scanner → find your device (name like `SmartSolar HQ...`)
2. Tap **Connect** → the device will request pairing (enter PIN `000000`)
3. Once connected you'll see the GATT service list

### Step 2: Find the Victron service

Look for service UUID:
```
65970000-4bda-4c1e-af4b-551c4cf74769
```

Expand it. You'll see characteristics like:
- `6597ffff-...` — Keep-alive (write periodically to hold connection)
- One or more writable characteristics (the transport)
- Possibly `6597edab-...` — direct Load Output Control register

### Step 3: Write keep-alive

Write `0x01` to `6597FFFF-...` (Write Request, not Write Command). This holds the
connection open for ~60 seconds.

### Step 4: Toggle load output

**Option A — Direct register write** (try this first):
Write to `6597EDAB-...` the value `0x05` (ON) or `0x04` (OFF).

**Option B — VREG framing on transport char**:
If option A doesn't work, find the writable transport characteristic and write:
```
08 00 19 AB ED 41 05   (set load output = ALWAYS_ON)
08 00 19 AB ED 41 04   (set load output = ALWAYS_OFF)
```

### Step 5: Read back state

Read characteristic `6597EDA8-...` — value `0x01` means ON, `0x00` means OFF.

Or write the read command to the transport:
```
08 00 17 A8 ED
```
And watch for a notification response.

### Step 6: Verify with VictronConnect

Open VictronConnect on another phone and check that the load output state matches.

## Method 2: Bluetooth HCI snoop log (full pcap)

This captures **all** BLE traffic at the HCI level, giving you the exact bytes exchanged.

### Enable HCI snoop log

1. **Settings → Developer Options → Enable Bluetooth HCI snoop log** (toggle ON)
2. Toggle Bluetooth off/on to start a fresh log
3. Perform the action in VictronConnect (toggle load output)
4. Retrieve the log:
   ```
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
   ```
   or on newer Android:
   ```
   adb bugreport victron-sniff.zip
   # Extract btsnoop_hci.log from the zip
   ```

### Analyze in Wireshark

1. Open `btsnoop_hci.log` in Wireshark
2. Filter: `btatt` (shows only ATT protocol — GATT reads/writes)
3. Look for Write Request/Command frames to handles on the Victron device
4. The payload bytes show the exact framing

### What to look for

| Operation | Wireshark filter | What you'll see |
|-----------|-----------------|-----------------|
| Service discovery | `btatt.opcode == 0x10` | Read By Group Type responses listing services |
| Write keep-alive | `btatt.opcode == 0x12` | Write Request to handle of `6597FFFF` |
| Write register | `btatt.opcode == 0x12` | Write Request with `08 00 19 AB ED ...` payload |
| Notification | `btatt.opcode == 0x1b` | Handle Value Notification with response frame |

### Decode the response

A response notification looks like:
```
08 00 19 A8 ED 41 01
         ^^^^^ register 0xEDA8 (load state) little-endian
               ^^ CBOR byte string, length 1
                  ^^ value: 0x01 = ON
```

## Key register reference

| Register | Name | Access | Values |
|----------|------|--------|--------|
| `0xEDAB` | Load Output Control | R/W | 0=OFF, 1=AUTO, 4=ALWAYS_OFF, 5=ALWAYS_ON |
| `0xEDA8` | Load Output State | R | 0=OFF, 1=ON |
| `0xED9D` | Load switch high (V) | R/W | un16, ×0.01 V |
| `0xED9C` | Load switch low (V) | R/W | un16, ×0.01 V |

## Troubleshooting

- **Connection drops immediately**: You're not writing keep-alive. Write `0x01` to
  `6597FFFF` within a few seconds of connecting, and again every 30s.
- **Write returns error**: Device may not support direct register-UUID writes. Try the
  VREG framing on the transport characteristic instead.
- **No notification after read request**: Enable notifications on the characteristic first
  (write `0x01 0x00` to the CCCD descriptor, handle = char handle + 1).
- **PIN rejected**: Some devices use `000000` (6 zeros), others use a custom PIN set in
  VictronConnect.

## Updating the app after sniffing

Once you confirm the exact write path, update `VictronGatt.kt`:

- If direct register-UUID writes work: simplify `writeTransport()` to write directly to
  `registerUuid(LOAD_OUTPUT_CONTROL)` instead of searching for a transport characteristic.
- If VREG framing is needed (likely): the current implementation already does this — verify
  the exact bytes match what you captured.
- If there's an init/auth handshake: add it before the register write in `withGatt()`.
