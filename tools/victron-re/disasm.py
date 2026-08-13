#!/usr/bin/env python3
"""
Disassemble ARM64 functions out of VictronConnect's native library.

The library ships unstripped, so the C++ symbol names survive and the protocol
constants are readable as plain immediates. `objdump` on a typical x86 host has no
aarch64 backend, hence capstone.

    ./symbols.sh                          # once, to produce work/
    python3 disasm.py sym setPathValues    # disassemble matching functions
    python3 disasm.py sym getDevices getPathList
    python3 disasm.py at 0x61d1820         # resolve a PLT thunk (8 instructions)
    python3 disasm.py at 0x609ab80:64      # ...or a longer run

`sym` matches a substring of the mangled name against work/vesmart_syms.txt first and
falls back to work/allsyms.txt, so `sym VeSmartService` disassembles the whole class.

Output is annotated: branch targets are resolved to demangled-ish symbol names, adrp/add
pairs are resolved to the string they point at, and every small immediate is summarised at
the end of each function — that summary is where the opcodes turn up.
"""
import os
import re
import struct
import sys

try:
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM
except ImportError:
    sys.exit("capstone missing: pip3 install --break-system-packages capstone")

WORK = os.environ.get("VICTRON_RE_WORK") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "work")
SO = os.path.join(WORK, "lib", "libVictronConnect_arm64-v8a.so")

if not os.path.exists(SO):
    sys.exit(f"{SO} not found — run ./symbols.sh first")


def load_segments(path):
    """PT_LOAD headers, so virtual addresses can be mapped back to file offsets."""
    with open(path, "rb") as f:
        hdr = f.read(64)
        if hdr[:4] != b"\x7fELF":
            sys.exit(f"{path} is not an ELF")
        e_phoff = struct.unpack_from("<Q", hdr, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", hdr, 0x36)[0]
        e_phnum = struct.unpack_from("<H", hdr, 0x38)[0]
        f.seek(e_phoff)
        ph = f.read(e_phentsize * e_phnum)
    segs = []
    for i in range(e_phnum):
        o = i * e_phentsize
        if struct.unpack_from("<I", ph, o)[0] != 1:  # PT_LOAD
            continue
        segs.append((
            struct.unpack_from("<Q", ph, o + 0x10)[0],  # vaddr
            struct.unpack_from("<Q", ph, o + 0x08)[0],  # offset
            struct.unpack_from("<Q", ph, o + 0x20)[0],  # filesz
        ))
    return segs


SEGS = load_segments(SO)
FH = open(SO, "rb")


def read(vaddr, size):
    """Bytes at a virtual address, or None when it is not backed by the file."""
    for v, o, sz in SEGS:
        if v <= vaddr < v + sz:
            FH.seek(o + (vaddr - v))
            return FH.read(size)
    return None


def load_symbols():
    syms = {}
    for name in ("allsyms.txt", "vesmart_syms.txt"):
        path = os.path.join(WORK, name)
        if not os.path.exists(path):
            continue
        for line in open(path):
            parts = line.split()
            if len(parts) >= 2:
                syms.setdefault(int(parts[0], 16), parts[-1])
    return syms


SYMS = load_symbols()


def readable(mangled):
    """Last couple of Itanium name components — enough to recognise a call target."""
    if not mangled:
        return None
    parts = re.findall(r"\d+([A-Za-z_][A-Za-z0-9_]*)", mangled)
    return "::".join(parts[-2:]) if parts else mangled


def cstring_at(vaddr, maxlen=160):
    b = read(vaddr, maxlen)
    if not b:
        return None
    end = b.find(b"\x00")
    if end <= 0:
        return None
    try:
        s = b[:end].decode("utf-8")
    except UnicodeDecodeError:
        return None
    return s if all(32 <= ord(c) < 127 or c in "\t\n" for c in s) else None


MD = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
MD.detail = True


def disasm(vaddr, size, show=True):
    code = read(vaddr, size)
    if code is None:
        print(f"!! 0x{vaddr:x} is not mapped")
        return []
    adrp = {}
    out = []
    for insn in MD.disasm(code, vaddr):
        note = ""
        ops = insn.operands
        if insn.mnemonic in ("bl", "b") and ops and ops[0].type == ARM64_OP_IMM:
            target = SYMS.get(ops[0].imm)
            if target:
                note = f"   ; {readable(target)}"
        elif insn.mnemonic == "adrp" and len(ops) == 2:
            adrp[ops[0].reg] = ops[1].imm
        elif insn.mnemonic == "add" and len(ops) == 3:
            if ops[1].reg in adrp and ops[2].type == ARM64_OP_IMM:
                text = cstring_at(adrp[ops[1].reg] + ops[2].imm)
                if text:
                    note = f'   ; "{text}"'
        elif insn.mnemonic == "ldr" and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
            if ops[1].mem.base in adrp:
                raw = read(adrp[ops[1].mem.base] + ops[1].mem.disp, 8)
                if raw:
                    pointed = SYMS.get(struct.unpack("<Q", raw)[0])
                    if pointed:
                        note = f"   ; -> {readable(pointed)}"
        line = f"  {insn.address:#x}:  {insn.mnemonic:<10} {insn.op_str}{note}"
        out.append((insn, line))
        if show:
            print(line)
    return out


def print_immediates(insns):
    """Small immediates, which is where opcodes and enum values live."""
    found = []
    for insn, _ in insns:
        if insn.mnemonic in ("mov", "movz", "movk", "cmp", "orr", "strb", "and"):
            for op in insn.operands:
                if op.type == ARM64_OP_IMM and 0 <= op.imm <= 0x200:
                    found.append((insn.address, insn.mnemonic, op.imm))
    if found:
        print("\n  --- small immediates (opcodes hide here) ---")
        for addr, mnem, imm in found:
            print(f"      {addr:#x}: {mnem} #{imm} (0x{imm:02x})")


def matching_symbols(patterns):
    """(mangled, addr, size) for symbols matching any pattern, sized ones preferred."""
    results = []
    sized = os.path.join(WORK, "vesmart_syms.txt")
    if os.path.exists(sized):
        for line in open(sized):
            parts = line.split()
            if len(parts) >= 3 and any(p in parts[2] for p in patterns):
                results.append((parts[2], int(parts[0], 16), int(parts[1])))
    if results:
        return results
    # fall back to the full table, which has no sizes — disassemble a fixed window
    for addr, name in SYMS.items():
        if any(p in name for p in patterns):
            results.append((name, addr, 512))
    return results


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mode, args = sys.argv[1], sys.argv[2:]

    if mode == "at":
        for arg in args:
            count = 8
            if ":" in arg:
                arg, count = arg.split(":")
                count = int(count)
            addr = int(arg, 16)
            name = SYMS.get(addr)
            print(f"\n--- 0x{addr:x}{' = ' + name if name else ''}")
            disasm(addr, count * 4)
    elif mode == "sym":
        found = matching_symbols(args)
        if not found:
            sys.exit(f"no symbol matched {args}")
        for name, addr, size in found:
            print(f"\n{'=' * 90}\n{name}\n  @ 0x{addr:x}  ({size} bytes)\n{'=' * 90}")
            print_immediates(disasm(addr, size))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
