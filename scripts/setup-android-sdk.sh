#!/usr/bin/env bash
# Install the Android SDK pieces required to build fcmself.
# JDK 21 must already be installed; this script deliberately does not modify
# the host package manager or shell profile.
set -euo pipefail

readonly COMPILE_SDK=37
# Google currently ships the platform as platforms;android-37.0 (folder android-37.0).
readonly PLATFORM_PACKAGE="platforms;android-37.0"
readonly BUILD_TOOLS_VERSION=37.0.0
readonly CMDLINE_TOOLS_REVISION=13114758

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

java_major_version() {
    java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1
}

command -v java >/dev/null 2>&1 || fail \
    'JDK 21 was not found. Install it and set JAVA_HOME before running this script.'

java_major="$(java_major_version)"
[[ "$java_major" == "21" ]] || fail \
    "JDK 21 is required, but the active Java version is ${java_major:-unknown} (${JAVA_HOME:-JAVA_HOME is unset})."

case "$(uname -s)" in
    Linux)
        host=linux
        default_sdk_root="${HOME}/Android/Sdk"
        ;;
    Darwin)
        host=mac
        default_sdk_root="${HOME}/Library/Android/sdk"
        ;;
    *)
        fail 'This bootstrap script supports Linux and macOS. On Windows, use Android Studio SDK Manager as documented in README.md.'
        ;;
esac

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$default_sdk_root}}"
sdk_root="${sdk_root%/}"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$sdkmanager" ]]; then
    command -v curl >/dev/null 2>&1 || fail 'curl is required to download Android command-line tools.'
    command -v unzip >/dev/null 2>&1 || fail 'unzip is required to unpack Android command-line tools.'

    archive="$(mktemp "${TMPDIR:-/tmp}/android-commandlinetools.XXXXXX.zip")"
    unpack_dir="$(mktemp -d "${TMPDIR:-/tmp}/android-commandlinetools.XXXXXX")"
    trap 'rm -f "$archive"; rm -rf "$unpack_dir"' EXIT

    url="https://dl.google.com/android/repository/commandlinetools-${host}-${CMDLINE_TOOLS_REVISION}_latest.zip"
    printf 'Downloading Android command-line tools from %s\n' "$url"
    curl --fail --location --retry 3 --output "$archive" "$url"
    unzip -q "$archive" -d "$unpack_dir"

    mkdir -p "$sdk_root/cmdline-tools"
    rm -rf "$sdk_root/cmdline-tools/latest"
    mv "$unpack_dir/cmdline-tools" "$sdk_root/cmdline-tools/latest"
fi

export ANDROID_SDK_ROOT="$sdk_root"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

printf 'Accepting Android SDK licenses...\n'
yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null || true

printf 'Installing SDK packages for API %s...\n' "$COMPILE_SDK"
"$sdkmanager" --sdk_root="$sdk_root" \
    'platform-tools' \
    "$PLATFORM_PACKAGE" \
    "build-tools;${BUILD_TOOLS_VERSION}"

# AGP looks for platforms/android-37; the package installs as android-37.0.
if [[ -d "$sdk_root/platforms/android-37.0" && ! -e "$sdk_root/platforms/android-37" ]]; then
    ln -sfn android-37.0 "$sdk_root/platforms/android-37"
fi

sdk_dir_property="${sdk_root//\\/\\\\}"
printf 'sdk.dir=%s\n' "$sdk_dir_property" > local.properties

cat <<EOF

Android build environment is ready.
  JAVA_HOME:       ${JAVA_HOME:-not set (java resolved from PATH)}
  ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT

Build and verify with:
  ./gradlew testDebugUnitTest assembleDebug
  ./gradlew lint assembleRelease
EOF
