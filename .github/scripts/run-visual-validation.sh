#!/usr/bin/env bash
# emulator-runner starts each script line in a new shell. Keep state and gates here.
set -euo pipefail
api=${1:?Expected device API}
case "$api" in 32|36) ;; *) echo "Unexpected API: $api" >&2; exit 1 ;; esac
: "${EXPECTED_HEAD:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
bash .github/scripts/prepare-visual-emulator.sh
test_status=0
./gradlew :core:ui:connectedDebugAndroidTest :feature:player:connectedDebugAndroidTest :feature:settings:connectedDebugAndroidTest :provider:webdav:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest --no-daemon --continue --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks --no-build-cache || test_status=$?
backup_status=0
# Production backup and directory-picker flows with owned fixtures on an isolated emulator.
# Neither class clears existing sources to satisfy preflight.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mediahub.app.backup.BackupUserFlowNonOverwritingDeviceTest,com.mediahub.app.player.LocalSafProductionUiFlowTest \
  -Pandroid.testInstrumentationRunnerArguments.backupAcceptance=isolated \
  --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks --no-build-cache || backup_status=$?
# App tests write failure UI into UTP's additionalTestOutputDir while installed;
# UTP pulls it before package cleanup. Post-run run-as cannot read an uninstalled target.
record_status=0
python3 .github/scripts/verify-visual-tests.py --record --api "$api" --device-api "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" --sha "$EXPECTED_HEAD" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" || record_status=$?
printf 'gradleExit=%s\nbackupExit=%s\nrecordExit=%s\n' "$test_status" "$backup_status" "$record_status" > ci-evidence/visual-command-status.txt
if [[ "$record_status" -ne 0 ]]; then exit "$record_status"; fi
if [[ "$backup_status" -ne 0 ]]; then exit "$backup_status"; fi
exit "$test_status"
