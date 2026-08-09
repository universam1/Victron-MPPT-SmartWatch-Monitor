#!/usr/bin/env bash
# Build the apps without installing an IDE or the Android SDK.
#
#   ./build.sh test           protocol unit tests (no Android SDK needed)
#   ./build.sh apk            debug APKs for watch and phone
#   ./build.sh apk-wear       watch APK only
#   ./build.sh apk-mobile     phone APK only
#   ./build.sh lint           Android lint on both apps
#   ./build.sh install-wear   install the watch APK on a connected/paired watch (adb)
#   ./build.sh install-mobile install the phone APK
#   ./build.sh shell          interactive shell inside the build container
#   ./build.sh gradle <args>  run gradle with your own arguments
#
# Everything except `install-*` runs inside the container from docker/Dockerfile.
# Set VICTRON_NATIVE=1 to use a locally installed JDK/Android SDK instead of Docker.
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE_FILE="docker/docker-compose.yml"

run_gradle() {
    if [[ "${VICTRON_NATIVE:-0}" == "1" ]]; then
        ./gradlew "$@"
    else
        docker compose -f "$COMPOSE_FILE" run --rm build ./gradlew "$@"
    fi
}

apk_paths() {
    find . -path '*/build/outputs/apk/*' -name '*.apk' -print 2>/dev/null | sort
}

case "${1:-apk}" in
    test)
        # The protocol module is plain Kotlin/JVM: this works even without an Android SDK.
        run_gradle :protocol:test
        ;;
    apk)
        run_gradle :wear:assembleDebug :mobile:assembleDebug
        echo "APKs:"; apk_paths
        ;;
    apk-wear)
        run_gradle :wear:assembleDebug
        echo "APKs:"; apk_paths
        ;;
    apk-mobile)
        run_gradle :mobile:assembleDebug
        echo "APKs:"; apk_paths
        ;;
    lint)
        run_gradle :wear:lintDebug :mobile:lintDebug
        ;;
    check)
        run_gradle :protocol:test :wear:assembleDebug :mobile:assembleDebug
        ;;
    install-wear)
        # adb runs on the host: the watch is paired with your machine, not with the container.
        # Wi-Fi debugging: adb connect <watch-ip>:5555 first.
        adb install -r -t wear/build/outputs/apk/debug/wear-debug.apk
        ;;
    install-mobile)
        adb install -r -t mobile/build/outputs/apk/debug/mobile-debug.apk
        ;;
    shell)
        docker compose -f "$COMPOSE_FILE" run --rm build bash
        ;;
    gradle)
        shift
        run_gradle "$@"
        ;;
    *)
        echo "Unknown command: $1" >&2
        sed -n '2,20p' "$0" >&2
        exit 64
        ;;
esac
