# VictronConnect reverse-engineering tools

Tooling used to work out the VeSmartService BLE GATT protocol that
[docs/vesmart-ble-gatt.md](../../docs/vesmart-ble-gatt.md) documents — the connected
protocol for *writing* settings (the load output toggle), as opposed to the connectionless
Instant Readout advertisements the rest of the app decodes.

VictronConnect is a Qt app: the UI is QML embedded as plain strings in the native library,
and the protocol logic is compiled ARM64. The library ships **unstripped**, so C++ symbol
names survive and the protocol constants are readable as plain immediates. That is what made
this tractable without a decompiler.

## Nothing extracted is committed

`work/` is gitignored. This repository is public, so it holds only our own scripts and the
*protocol facts* we established. It deliberately does not carry the APK, the extracted
library, Victron's product-definition XML, or the firmware blocks embedded in it — that
material is theirs, and publishing it is a different act from analysing a copy you already
own. Pull your own from your own device.

## Requirements

- A phone with VictronConnect installed, on `adb`
- `python3` with capstone: `pip3 install --break-system-packages capstone`
- `binutils` for `readelf`, and `unzip`

`objdump` on an x86 host usually has no aarch64 backend, which is why disassembly goes
through capstone rather than binutils.

## Use

```sh
./pull-apk.sh     # adb pull base.apk + the arm64 split into work/
./symbols.sh      # extract the .so, build the symbol tables
python3 disasm.py sym setPathValues
```

`disasm.py sym <pattern>` disassembles every function whose mangled name contains the
pattern, annotating branch targets with symbol names and `adrp`/`add` pairs with the string
they point at. Each function ends with a summary of its small immediates — **that summary is
where the protocol opcodes are**. For example `setPathValues` opens with `mov w8, #0xc`,
which is the SET opcode, written straight out as a CBOR unsigned integer.

`disasm.py at 0x61d1820[:count]` disassembles at an address, for following PLT thunks to
their real targets.

## Where the findings came from

| Question | Where it was answered |
|---|---|
| Request opcodes | The `mov w8, #N` immediate at the top of `getDevices` / `getPathList` / `getPathValues` / `setPathValues`, stored into a `Cbor` value whose type tag is 0 (unsigned int) and serialised by `Cbor::write` |
| Response opcodes | The dispatch switch at `0x609ab80`, with names taken from each handler's own diagnostic strings |
| Which characteristic each write targets | `getCharacteristics()` stores the three by UUID at member offsets 0x50/0x68/0x80; `writeControl` and `writeChunkToStack` reveal which offset they use, and the log strings *"Writing to control/data/lastData"* name the roles |
| Write mode | The 4th argument to `QLowEnergyService::writeCharacteristic` is computed `(properties >> 2) & 1` — the WriteNoResponse bit |
| Chunking | The `mid(offset, chunkSize)` loop in `writeChunkToStack`, chunk size in the `uint16` member at `+0xe8` |
| Keepalive | `sendKeepAlive` writes `{0x10, 0x27}` (10000 LE) to register `0x0093`; the interval is `QTimer::setInterval(0x2710)` in the constructor |
| Register ids and value semantics | The product-definition XML and QML embedded as strings in the `.so` — `<vreg label="load_control" get="0xEDAB"/>`, and QML calling `loadOperationMode.setValue(4)` |

## Caveats

Addresses are **specific to one build**. `symbols.sh` regenerates the tables for whatever
version you pulled, but any address written down in a commit message or doc — including the
ones in the table above — is only valid for the build it was read from. Re-derive them by
symbol name rather than trusting a literal address.

The protocol also has more than one generation in the wild. Older third-party captures and
repos show opcodes `03`/`05`/`06` with CBOR array wrappers; the shipping library emits none
of that. When they disagree, the library wins — see the *Two protocol generations* section of
the protocol doc.
