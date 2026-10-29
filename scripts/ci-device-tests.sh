#!/usr/bin/env bash
# Runs the instrumented tests on a booted emulator and pulls the screenshot tour.
# Used by .github/workflows/android.yml; also works against any connected device.
set -uo pipefail
pkg=com.vytros.projects.sharpr
mkdir -p build/screens
: > build/instrument.txt

echo "== device: $(adb shell getprop ro.product.model) API $(adb shell getprop ro.build.version.sdk) $(date +%T)"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb logcat -c || true

status=0
for cls in PipelinesTest ScreenshotTour; do
  echo "== $cls $(date +%T)"
  # A hard cap per class, so a hang still leaves logs behind.
  timeout 1500 adb shell am instrument -w -r -e class "$pkg.$cls" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee -a build/instrument.txt | grep --line-buffered -E "INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE|OK \(|FAILURES|Tests run|INSTRUMENTATION_RESULT"
  rc=${PIPESTATUS[0]}
  echo "== $cls finished rc=$rc $(date +%T)"
  [ "$rc" -eq 124 ] && echo "TIMEOUT in $cls" >> build/instrument.txt && status=1
done

adb logcat -d -s SharprNative:* AndroidRuntime:E TestRunner:* > build/logcat.txt 2>&1 || true
adb pull "/sdcard/Android/data/$pkg/files/screens/." build/screens/ || true
ls build/screens | head -50

grep -q "FAILURES!!!" build/instrument.txt && status=1
[ "$(grep -c '^OK (' build/instrument.txt)" -ge 2 ] || status=1
exit $status
