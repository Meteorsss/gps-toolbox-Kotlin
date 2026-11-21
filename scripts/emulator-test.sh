#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
adb install -r "$(find apks -name app-debug.apk -print -quit)"
adb install -r "$(find apks -name app-debug-androidTest.apk -print -quit)"
adb logcat -c
adb shell am instrument -w -r com.meteorsss.startrace.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/instrumentation.txt
adb logcat -d > device-results/logcat.txt
adb pull /sdcard/Android/data/com.meteorsss.startrace/files/screenshots device-results/ || true
adb pull /sdcard/Android/data/com.meteorsss.startrace/files/export-fixtures device-results/ || true
grep -q 'OK (23 tests)' device-results/instrumentation.txt
! grep -q 'FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed' device-results/instrumentation.txt