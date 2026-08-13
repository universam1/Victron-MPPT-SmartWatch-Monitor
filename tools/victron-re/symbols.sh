#!/usr/bin/env bash
# Extract the native library from the pulled APK and build the symbol tables disasm.py needs.
set -euo pipefail

cd "$(dirname "$0")"
WORK=${VICTRON_RE_WORK:-work}
SPLIT="$WORK/split_arm64.apk"
LIB=libVictronConnect_arm64-v8a.so

[ -f "$SPLIT" ] || { echo "$SPLIT missing — run ./pull-apk.sh first" >&2; exit 1; }
command -v readelf >/dev/null || { echo "readelf missing (install binutils)" >&2; exit 1; }

mkdir -p "$WORK/lib"
if [ ! -f "$WORK/lib/$LIB" ]; then
    echo "extracting $LIB (~100 MB, unstripped)"
    unzip -o -q -j "$SPLIT" "lib/arm64-v8a/$LIB" -d "$WORK/lib"
fi

echo "building symbol tables"
# Every defined function: address + mangled name. Used to resolve call targets.
readelf --wide -sW "$WORK/lib/$LIB" \
    | awk '$4=="FUNC" && $2!="0000000000000000" {print $2, $8}' \
    | sort -u > "$WORK/allsyms.txt"

# The protocol classes, with sizes so whole functions can be disassembled exactly.
readelf --wide -sW "$WORK/lib/$LIB" \
    | grep -E "VeSmartService|VeService|BleServiceBase" \
    | awk '$4=="FUNC" {print $2, $3, $8}' \
    | sort -u > "$WORK/vesmart_syms.txt"

printf '  allsyms.txt      %6d functions\n' "$(wc -l < "$WORK/allsyms.txt")"
printf '  vesmart_syms.txt %6d functions\n' "$(wc -l < "$WORK/vesmart_syms.txt")"

cat <<'EOF'

Ready. Try:
  python3 disasm.py sym setPathValues      # the SET opcode (0x0c) lives here
  python3 disasm.py sym writeChunkToStack  # chunk routing across 306b0003/0004
  python3 disasm.py at 0x61d1820           # resolve a PLT thunk

QML and the product-definition XML are plain strings inside the .so:
  strings -td work/lib/libVictronConnect_arm64-v8a.so | grep -n 'load_control'
then slice the file around the reported offset to recover the full block, since
strings truncates at NULs.
EOF
