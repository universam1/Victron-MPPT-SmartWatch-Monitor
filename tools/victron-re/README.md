# VictronConnect reverse-engineering tools

Tooling used to work out the VeSmartService BLE GATT protocol that
[docs/vesmart-ble-gatt.md](../../docs/vesmart-ble-gatt.md) documents — the connected
protocol for *writing* settings (the load output toggle), as opposed to the connectionless
Instant Readout advertisements the rest of the app decodes.

VictronConnect is a Qt app: the UI is QML embedded as plain strings in the native library,
and the protocol logic is compiled ARM64. The library ships **unstripped**, so C++ symbol
names survive and the protocol constants are readable as plain immediates. That is what made
this tractable without a decompiler.

## ⚠ Before making this repository public again

`work/` **is committed** while the repo is private, so the analysis inputs travel with it.
Some of that material is Victron's, not ours:

| Artifact | Contains |
|---|---|
| `work/base.apk` | Their app build (DEX + Qt resource bundle) |
| `work/product_defs.xml` | Their product definition for `0xA05F`, **including 616 blocks of encrypted firmware** |
| `work/allsyms.txt`, `work/vesmart_syms.txt` | Verbatim symbol/address dumps from their binary |

Analysing a copy you own is one thing; publishing it is another. **Git history is permanent**,
so flipping the repo public re-publishes every one of these from history even if the files are
deleted in a later commit. Removing them properly means rewriting history:

```sh
git filter-repo --path tools/victron-re/work --invert-paths   # then force-push
```

Do that *before* the repo goes public, not after — once it is public, forks and mirrors have
their own copies. The scripts here are ours and can stay.

## The native library is not committed

`work/lib/` is gitignored. `libVictronConnect_arm64-v8a.so` is 103 MB, over GitHub's 100 MB
per-file hard limit, and it lives in `split_config.arm64_v8a.apk` (107 MB) — which is over the
limit too. `base.apk` does **not** contain it; it has only `classes*.dex` and `assets/`.

So the committed APK is not sufficient to regenerate the symbol tables. To disassemble
anything you need the phone again:

```sh
./pull-apk.sh && ./symbols.sh
```

If you want the library itself under version control, Git LFS is the only practical route.

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

`work/product_defs.xml` is the already-extracted product definition for model `41055`
(`0xA05F`, SmartSolar MPPT 100/20) — the `<vregs>` section is the register map the protocol
doc's load-output table came from. It was sliced out of the `.so` by byte offset, since
`strings` truncates at NULs.

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
