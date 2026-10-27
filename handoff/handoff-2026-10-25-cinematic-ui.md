# Handoff — Cinematic UI refresh（2026-10-25）

## Status

- Branch: `feature/cinematic-ui` in `D:/deepseek_test/mh-cinematic-ui`.
- Base: unmerged Draft PR #59, `c18f6a61`; this is not merged to `main`.
- Scope: shared dark cinematic theme, shared artwork/status/card components, Home / Library / Search / Detail / Server / Settings / Player visual polish, README / CHANGELOG, original local demo art and four real Android page screenshots.
- Debug-only `CinematicVisualActivity` exists only under `app/src/debug`; Release does not contain it.

## Executed evidence

- Final `:app:assembleDebug`: exit 0, 483 actionable tasks, APK SHA-256 `b22b9ad97cfd8589662d989486db320f6a5095e957fd59dc57ac04b9bd5837ab` at that build.
- Final visual-capture `:app:assembleDebug` after last Detail readability correction: exit 0, 483 tasks; current page-capture APK SHA-256 recorded in RUNLOG.
- Same-sign `adb install -r` on Xiaomi 14 Ultra `123e243f`: Success. No uninstall, data clear, or system-setting change.
- Debug-only four-page capture: four separate cold Activity starts; production Compose components Home / Library / Search / Detail rendered at 1080×2400 using fictional local data. Screenshots committed at `docs/screenshots/`.
- Visual review: Home text readable; Library grid and long-title truncation visible; Search cards and source labels visible; Detail title/meta were initially too dim and were corrected to high-contrast white, then rebuilt/captured.
- New app-source privacy scan passed; screenshots use fictional titles and generated local artwork.

## Blocked / not passed

- `CinematicVisualTest` on the physical device did not complete: instrumentation started 4 tests but the OEM shell placed the test Activity in background and the runner did not return before timeout. This is `BLOCKED_ENV`, not PASS. CI visual-instrumentation remains the authoritative Compose runner.
- Full standard gate was restarted after the first 300 s shell timeout; final result is recorded in RUNLOG / TEST_MATRIX.
- During earlier local builds, Windows JVM native-memory failures occurred when emulator and Gradle overlapped. Original `hs_err_*` / `replay_*` logs were moved to `D:/deepseek_test/mh-cinematic-evidence/build-failures`; no deletion of evidence.

## Rules and next steps

- G7: DO_NOT_MERGE. Create only a feature branch and Draft PR; no main write, merge, close, mark-ready, or force push.
- If CI fails, fix additively on this branch and use exact-head evidence only.
- If the debug visual host is no longer needed, it may be replaced by a normal debug drawer or preview route in a future focused change.
