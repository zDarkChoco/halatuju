#!/usr/bin/env bash
set -uo pipefail

API_LEVEL="${1:-36}"
ARTIFACT_DIR="ci-emulator-artifacts/api-${API_LEVEL}"
mkdir -p "${ARTIFACT_DIR}/screenshots"

adb wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
sleep 3
adb shell settings put global window_animation_scale 0.0 || true
adb shell settings put global transition_animation_scale 0.0 || true
adb shell settings put global animator_duration_scale 0.0 || true

adb logcat -c || true
adb shell rm -rf /sdcard/Android/data/com.mbk.halatuju/files/ci_screenshots || true

TEST_STATUS=0
./gradlew :app:connectedDebugAndroidTest --stacktrace || TEST_STATUS=$?

adb pull /sdcard/Android/data/com.mbk.halatuju/files/ci_screenshots/. "${ARTIFACT_DIR}/screenshots/" || true
adb exec-out screencap -p > "${ARTIFACT_DIR}/emulator_screen.png" || true
adb shell dumpsys notification --noredact > "${ARTIFACT_DIR}/dumpsys_notification.txt" || true
adb logcat -d > "${ARTIFACT_DIR}/logcat.txt" || true

echo "Emulator tests (API ${API_LEVEL}) exited with status ${TEST_STATUS}"
exit "${TEST_STATUS}"
