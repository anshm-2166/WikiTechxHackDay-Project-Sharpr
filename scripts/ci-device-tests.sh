#!/usr/bin/env bash
# Runs the instrumented tests on a booted emulator and pulls the screenshot tour.
# Used by .github/workflows/android.yml; also works against any connected device.
set -uo pipefail
pkg=com.vytros.projects.sharpr
mkdir -p build/screens

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb logcat -c || true

adb shell am instrument -w -r "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee build/instrument.txt
adb logcat -d -s SharprNative:* AndroidRuntime:E TestRunner:* > build/logcat.txt 2>&1 || true

adb pull "/sdcard/Android/data/$pkg/files/screens/." build/screens/ || true
ls -la build/screens || true

grep -q "FAILURES!!!" build/instrument.txt && exit 1
grep -q "^OK (" build/instrument.txt || exit 1
