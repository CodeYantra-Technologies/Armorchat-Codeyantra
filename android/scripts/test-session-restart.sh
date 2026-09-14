#!/bin/sh
# Requires the debug APK and debug androidTest APK already installed.
set -eu
app=com.codeyantra.armorchat.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
class=com.codeyantra.armorchat.crypto.SessionRestartTest
run() {
    adb shell am instrument -w -r -e class "$class#$1" ${2:+-e crashPoint "$2"} "$runner"
}
verify() {
    output=$(run "$@")
    printf '%s\n' "$output"
    printf '%s\n' "$output" | grep -q 'OK (1 test)'
}
verify prepareRestart
adb shell am force-stop "$app"
verify resumeAfterRestart
verify rollbackOnExceptionAndWrongOperationReuse
for point in after_state after_result after_commit; do
    verify prepareRestart
    # This invocation intentionally dies; resume verifies database atomicity.
    run crashAtCommitBoundary "$point" || true
    adb shell am force-stop "$app"
    verify resumeAfterCrash "$point"
done
