#!/usr/bin/env bash
# Check local prerequisites without downloading or changing anything.
set -euo pipefail

status=0
ok() { printf 'ok:    %s\n' "$*"; }
error() { printf 'error: %s\n' "$*" >&2; status=1; }

if command -v java >/dev/null 2>&1; then
    java_version="$(java -version 2>&1 | sed -nE 's/.*version "([^"]+).*/\1/p' | head -n 1)"
    java_major="${java_version%%.*}"
    if [[ "$java_major" == "21" ]]; then
        ok "JDK $java_version"
    else
        error "JDK 21 is required; active version is ${java_version:-unknown}"
    fi
else
    error 'JDK 21 is not on PATH'
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" && -f local.properties ]]; then
    sdk_root="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)"
    sdk_root="${sdk_root//\\\\/\\}"
fi

if [[ -z "$sdk_root" ]]; then
    error 'Android SDK is not configured (set ANDROID_SDK_ROOT or run scripts/setup-android-sdk.sh)'
elif [[ -f "$sdk_root/platforms/android-37/android.jar" ]]; then
    ok "Android SDK API 37 ($sdk_root)"
elif [[ -f "$sdk_root/platforms/android-37.0/android.jar" ]]; then
    error "Platform is android-37.0; run scripts/setup-android-sdk.sh to rename to android-37 for AGP"
else
    error "Android API 37 is missing from $sdk_root"
fi

if [[ -n "$sdk_root" && -x "$sdk_root/build-tools/37.0.0/aapt2" ]]; then
    ok 'Android Build Tools 37.0.0'
else
    error 'Android Build Tools 37.0.0 is missing'
fi

if [[ -x ./gradlew ]]; then
    ok 'Gradle wrapper'
else
    error 'gradlew is not executable (run chmod +x gradlew)'
fi

exit "$status"
