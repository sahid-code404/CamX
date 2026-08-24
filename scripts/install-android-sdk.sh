#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
readonly sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$sdkmanager" || { echo "sdkmanager missing: $sdkmanager" >&2; exit 1; }

set +e
yes | "$sdkmanager" --licenses >/dev/null
readonly license_statuses=("${PIPESTATUS[@]}")
set -e
if ((license_statuses[1] != 0)); then
  echo "sdkmanager license acceptance failed with exit ${license_statuses[1]}." >&2
  exit "${license_statuses[1]}"
fi
"$sdkmanager" \
  'platform-tools' \
  'platforms;android-37' \
  'build-tools;37.0.0' \
  'ndk;29.0.14206865' \
  'cmake;4.1.2'
