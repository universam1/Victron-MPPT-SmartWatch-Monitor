#!/usr/bin/env bash
# Pull the installed VictronConnect APK off a connected phone into work/.
#
# The APK is not committed: it is ~19 MB plus a ~107 MB native split, and it is Victron's
# proprietary build. Pull your own copy from your own device.
set -euo pipefail

cd "$(dirname "$0")"
WORK=${VICTRON_RE_WORK:-work}
PKG=${PKG:-com.victronenergy.victronconnect}

mkdir -p "$WORK"

if ! adb get-state >/dev/null 2>&1; then
    echo "No device on adb. Connect the phone (and authorise USB debugging) first." >&2
    exit 1
fi

mapfile -t PATHS < <(adb shell pm path "$PKG" | tr -d '\r' | sed 's/^package://')
if [ ${#PATHS[@]} -eq 0 ]; then
    echo "$PKG is not installed on the device." >&2
    exit 1
fi

for remote in "${PATHS[@]}"; do
    case "$remote" in
        *base.apk)                 local_name=base.apk ;;
        *split_config.arm64_v8a*)  local_name=split_arm64.apk ;;
        *)                         continue ;;  # other splits (densities, languages) are noise
    esac
    echo "pulling $local_name"
    adb pull "$remote" "$WORK/$local_name"
done

echo
echo "Version on the device:"
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 | sed 's/^/  /'
echo
echo "Next: ./symbols.sh"
