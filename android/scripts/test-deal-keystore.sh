#!/bin/sh
# Runs the already-installed debug instrumentation for Armor Deal Keystore.
# Does not install, uninstall, clear data, or reboot.
# Refuses to start unless the owner has set DEAL_KEYSTORE_APPROVED=1.
set -eu
if [ "${DEAL_KEYSTORE_APPROVED:-}" != "1" ]; then
    echo "Refusing to run. Instrumentation needs owner approval." >&2
    exit 2
fi
app=com.codeyantra.armorchat.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
class=com.codeyantra.armorchat.deal.DealKeystoreDeviceTest
run() {
    adb -s "${ANDROID_SERIAL:?set ANDROID_SERIAL}" shell am instrument -w -r -e class "$class#$1" "$runner"
}
output=$(run prepareRestart)
printf '%s\n' "$output"
printf '%s\n' "$output" | grep -q 'OK (1 test)'
adb -s "$ANDROID_SERIAL" shell am force-stop "$app"
output=$(run resumeAfterRestart)
printf '%s\n' "$output"
printf '%s\n' "$output" | grep -q 'OK (1 test)'
