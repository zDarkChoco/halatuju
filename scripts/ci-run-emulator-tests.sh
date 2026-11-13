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
adb shell "rm -rf /data/local/tmp/halatuju_ci_screenshots && mkdir -p /data/local/tmp/halatuju_ci_screenshots && chmod 777 /data/local/tmp/halatuju_ci_screenshots" || true

TEST_STATUS=0
./gradlew :app:connectedDebugAndroidTest --stacktrace || TEST_STATUS=$?

adb pull /data/local/tmp/halatuju_ci_screenshots/. "${ARTIFACT_DIR}/screenshots/" || true
if [ -f "${ARTIFACT_DIR}/screenshots/dumpsys_active_nav.txt" ]; then
  mv "${ARTIFACT_DIR}/screenshots/dumpsys_active_nav.txt" "${ARTIFACT_DIR}/dumpsys_active_nav.txt"
fi
adb exec-out screencap -p > "${ARTIFACT_DIR}/emulator_screen.png" || true
adb shell dumpsys notification --noredact > "${ARTIFACT_DIR}/dumpsys_notification.txt" || true
adb logcat -d > "${ARTIFACT_DIR}/logcat.txt" || true

ls -la "${ARTIFACT_DIR}" "${ARTIFACT_DIR}/screenshots" || true
echo "Emulator tests (API ${API_LEVEL}) exited with status ${TEST_STATUS}"
exit "${TEST_STATUS}"
