#!/usr/bin/env bash
# emulator-runner starts each script line in a new shell. Keep state and gates here.
set -euo pipefail
api=${1:?Expected device API}
case "$api" in 32|36) ;; *) echo "Unexpected API: $api" >&2; exit 1 ;; esac
: "${EXPECTED_HEAD:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
bash .github/scripts/prepare-visual-emulator.sh
test_status=0
./gradlew :core:ui:connectedDebugAndroidTest --rerun :feature:player:connectedDebugAndroidTest --rerun :feature:settings:connectedDebugAndroidTest --rerun :provider:webdav:connectedDebugAndroidTest --rerun :core:database:connectedDebugAndroidTest --rerun --no-daemon --continue --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --no-build-cache || test_status=$?
backup_status=0
# Production app/SAF flow with owned fixtures only. The class rejects physical devices and
# existing sources; it never executes confirmed replacement or clears data to satisfy preflight.
./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mediahub.app.backup.BackupUserFlowNonOverwritingDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.backupAcceptance=isolated \
  --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --no-build-cache || backup_status=$?
mkdir -p ci-evidence/backup-report ci-evidence/cinematic-report
for report in app/build/outputs/androidTest-results/connected/debug/TEST-*.xml; do
  test -f "$report"
  cp -p "$report" "ci-evidence/backup-report/$(basename "$report" .xml)-backup.xml"
done
cinematic_status=0
./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mediahub.app.visual.CinematicVisualTest \
  --no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --no-build-cache || cinematic_status=$?
for report in app/build/outputs/androidTest-results/connected/debug/TEST-*.xml; do
  test -f "$report"
  cp -p "$report" "ci-evidence/cinematic-report/$(basename "$report" .xml)-cinematic.xml"
  mv "$report" "ci-evidence/cinematic-report/$(basename "$report")"
done
cp -p ci-evidence/backup-report/TEST-*-backup.xml app/build/outputs/androidTest-results/connected/debug/
cp -p ci-evidence/cinematic-report/TEST-*-cinematic.xml app/build/outputs/androidTest-results/connected/debug/
record_status=0
python3 .github/scripts/verify-visual-tests.py --record --api "$api" --device-api "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" --sha "$EXPECTED_HEAD" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" || record_status=$?
printf 'gradleExit=%s\nbackupExit=%s\ncinematicExit=%s\nrecordExit=%s\n' "$test_status" "$backup_status" "$cinematic_status" "$record_status" > ci-evidence/visual-command-status.txt
if [[ "$record_status" -ne 0 ]]; then exit "$record_status"; fi
if [[ "$backup_status" -ne 0 ]]; then exit "$backup_status"; fi
if [[ "$cinematic_status" -ne 0 ]]; then exit "$cinematic_status"; fi
exit "$test_status"
