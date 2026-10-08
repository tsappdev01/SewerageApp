#!/usr/bin/env bash
# Installs the release APK on the running emulator, opens it and fails if it does not stay open.
# Usage: release-smoke-test.sh <apk>
set -euo pipefail
APK="$1"
PKG=ae.dipark.fieldservice
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 20
mkdir -p build/smoke
adb exec-out screencap -p > build/smoke/release-start.png || true
if ! adb shell pidof "$PKG" > /dev/null; then
  echo "The release build closed after starting:"
  adb logcat -d -b crash || true
  adb logcat -d | grep -E "AndroidRuntime|FATAL|$PKG" | tail -n 80 || true
  exit 1
fi
if adb logcat -d -b crash | grep -q "$PKG"; then
  echo "The release build crashed:"
  adb logcat -d -b crash
  exit 1
fi
echo "The release build started and stayed open."
