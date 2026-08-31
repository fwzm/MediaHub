#!/usr/bin/env bash
# Complete first-boot HOME initialization before any ActivityScenario can lose focus to it.
# The emulator action waits for sys.boot_completed, which does not imply Launcher readiness.
set -euo pipefail

if [[ ! "${ANDROID_SERIAL:-}" =~ ^emulator-[0-9]+$ ]]; then
  echo "Expected the CI emulator's explicit ANDROID_SERIAL" >&2
  exit 1
fi

mkdir -p ci-evidence
# Google APIs images may keep enqueueing unrelated background broadcasts indefinitely.
# Wait only for the HOME launch that can cover our test Activity, not global system idleness.
timeout 30s adb -s "$ANDROID_SERIAL" shell am start -W \
  -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tee ci-evidence/emulator-home.txt
# `am start` can exit zero even when its output reports a launch error or timeout.
grep -q '^Status: ok' ci-evidence/emulator-home.txt

timeout 30s adb -s "$ANDROID_SERIAL" shell dumpsys activity activities \
  > ci-evidence/emulator-activities-before-tests.txt
timeout 30s adb -s "$ANDROID_SERIAL" shell dumpsys window windows \
  > ci-evidence/emulator-windows-before-tests.txt
home_component=$(sed -n 's/^Activity: //p' ci-evidence/emulator-home.txt | tr -d '\r')
test -n "$home_component"
# Fail before tests if setup is still covering HOME. Never bring a failed test back to the
# foreground or retry it: all original pixel, lifecycle and product-path assertions still run.
grep -E '(topResumedActivity|mResumedActivity)[:=]' ci-evidence/emulator-activities-before-tests.txt \
  | grep -F -- "$home_component"
