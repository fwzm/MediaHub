# Main readiness integration - 2026-10-24

## State
Local feature codex/main-readiness-integration-20261024 in the reused controls worktree. Merge 005d6cb contains controls a9f5e94, seek 507b9eb, Windows 96b6a9e, cross-device b7890aa and historical WorkBuddy PR20. PR16 is ported as a sanitized historical report without importing its raw origins into new history. Main remains 1246313; main-facing PR45 remains 1a9879ae.

## Goal
All valid active work must become verifiably ready for main. This candidate is incomplete until combined regression and remaining PR6 hardening reconciliation pass. Release signing, GUI/device and source/CI evidence remain distinct.

## Failed attempts
Controls RED: 30 cases / 2 assertion failures before fix, GREEN 30/0. Seek mutations: both incorrect production variants fail 4/8; final restored 8/0. Original failures retained locally. Windows main test attempt1 did not execute because the ignored DLL was absent; attempt2 after pinned-hash dependency copy passed. No test was skipped or relaxed. Incoming branch raw build logs have legacy whitespace issues; they are preserved as evidence rather than rewritten.

## Progress and next action
Controls current-head CI 40094375055 success / 1561 unique tests and API32/API36 pass. Seek CI 40096311477 pending. Windows combined d150b4a: 1518+104 tests pass, WinUI build 0 warnings/errors, real headless native decode probe PASS (time-pos 3s, 0 errors). Windows source remains identical in combined head. Run full combined Android unit tests with rerun/no-cache, standalone JVM modules, then assembleDebug/lintDebug. Raw runner and outputs: D:/deepseek_test/mh-main-readiness-evidence-20261024. Do not run another Gradle in parallel. PR6 conflicts and physical device acceptance remain outstanding; adb reports no devices.
