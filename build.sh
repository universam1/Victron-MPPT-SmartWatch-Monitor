#!/usr/bin/env bash
# Build the apps without installing an IDE or the Android SDK.
#
#   ./build.sh test           protocol unit tests (no Android SDK needed)
#   ./build.sh apk            debug APKs for watch and phone
#   ./build.sh apk-wear       watch APK only
#   ./build.sh apk-mobile     phone APK only
#   ./build.sh apk-release    release APKs (see .github/workflows/release.yml for signing)
#   ./build.sh lint           Android lint on both apps
#   ./build.sh install-wear   install the watch APK on a connected/paired watch (adb)
#   ./build.sh install-mobile install the phone APK
#   ./build.sh screenshots     update screenshot test reference images
#   ./build.sh shell          interactive shell inside the build container
#   ./build.sh gradle <args>  run gradle with your own arguments
#
# Everything except `install-*` runs inside the container from docker/Dockerfile.
# Set VICTRON_NATIVE=1 to use a locally installed JDK/Android SDK instead of Docker.
set -euo pipefail

cd "$(dirname "$0")"

# Source signing env vars from .env if present (not checked into git).
# shellcheck disable=SC1091
[[ -f .env ]] && set -a && source .env && set +a

# Derive version from the latest git tag when not already set (CI sets these from the tag).
if [[ -z "${VICTRON_VERSION_NAME:-}" ]]; then
    tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0")
    export VICTRON_VERSION_NAME="${tag#v}"
    IFS='.' read -r major minor patch <<< "${VICTRON_VERSION_NAME%%-*}"
    export VICTRON_VERSION_CODE=$(( ${major:-0} * 10000 + ${minor:-0} * 100 + ${patch:-0} ))
fi

COMPOSE_FILE="docker/docker-compose.yml"

run_gradle() {
    if [[ "${VICTRON_NATIVE:-0}" == "1" ]]; then
        ./gradlew "$@"
    else
        # Pass signing env vars through to the container when set.
        local env_args=()
        for var in SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD \
                   VICTRON_VERSION_NAME VICTRON_VERSION_CODE; do
            [[ -n "${!var:-}" ]] && env_args+=(-e "$var=${!var}")
        done
        docker compose -f "$COMPOSE_FILE" run --rm "${env_args[@]}" build ./gradlew "$@"
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
    apk-release)
        # Signed with the release keystore when release.keystore + SIGNING_* env vars are present,
        # with the debug key otherwise. CI does the same, see .github/workflows/release.yml.
        # --no-build-cache: the signing config is not part of the task cache key, so a prior
        # debug-signed cache entry would be served otherwise.
        run_gradle --no-build-cache :wear:assembleRelease :mobile:assembleRelease
        echo "APKs:"; apk_paths
        ;;
    lint)
        run_gradle :wear:lintDebug :mobile:lintDebug
        ;;
    check)
        run_gradle :protocol:test :data:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug
        ;;
    screenshots)
        run_gradle :wear:updateDebugScreenshotTest :mobile:updateDebugScreenshotTest
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
