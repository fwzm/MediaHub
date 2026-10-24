# Main readiness closure candidate - 2026-10-25

This is an integration checkpoint, not a declaration that every historical branch can be merged. Keep G7 DO_NOT_MERGE and all open PRs Draft. No PR merge/close, force push, direct main write, worktree cleanup or real-service scan is part of this increment.

## Frozen inputs

| Input | Revision | Treatment |
|---|---|---|
| Validated Android/Windows integration and PR6 hardening |3c51ce52cf49f011dfcf1e46ce37b2297d5aa359|Starting parent; includes PR57/PR6/PR58, reconciled controls/seek and earlier joint candidates.|
| Latest observed Windows branch |0ec00892ba18c59f78c2e243f4f06c9fe340f14d|Ancestor of the frozen sync input; included without rewriting history.|
| Latest observed cross-device branch |b71edc2b6f5e63e115a4850e76cf8ce26665111b|Merged additively. Adds send cancellation, explicit unicast binding, listener peer-secret controls and CLI transfer budgets.|
| Historical authentication interleaving test |feea1acd8a9c954f2f07f1bb91cf17988c5f458c|Port only the fixed-seed16-round production-provider test, not the old branch history. Clarify it checks one already-started request, not a new login while the restore gate is held.|
| Historical PR16 evidence correction |45afb870415103e4070d3d90188c07885b60fa4d|Port corrections into the sanitized archive: reported delta0 lacks response text; media GET502 does not become playable because progress endpoints returned204; correct the slow/final sample index.|

The only merge conflict was the Android required-suite list. Both sides' requirements remain, with the new host-address and authentication stress suites also required. Five actual sync Compose assertions are mandatory on both API32 and API36; omitted or skipped mandatory cases cannot be replaced by job success.

## Observed local validation

- Frozen Windows source/tests/tools exactly match b71edc2b. Whole solution build: exit0,0 warnings/errors. Main suite1577/1577 and separate WebDAV suite104/104,0 skipped. TRX counters retained privately.
- Real Windows native engine headless probe: exit0; duration90.021s, playback reached3.167s, seek observed23.375s,2 audio/2 subtitle tracks,65 snapshots. Null video/audio outputs do not establish GUI/GPU/acoustic acceptance. A private per-run temporary directory protects unrelated probe logs.
- Affected Android regression:1145 tests/0 failures/errors/skips, exit0;262 tasks executed, forced rerun/no build cache. Includes23 real same-machine CLI/Kotlin interoperability cases with the CLI DLL built from this frozen source, and the16-round authentication case (one JUnit method).
- New sync instrumentation sources compiled successfully; their runtime assertions still need the new candidate's emulator CI execution.
- Evidence-gate self tests:56 passed. The first fixture attempt had API36 fixture-identity errors, retained and corrected in the fixture helper. An in-memory mutation omitting the five sync requirements produced10 expected assertion failures/0 errors, without modifying product source or device reports.
- Request-construction redaction and the full network module:226 tests/0 failures/errors/skips, exit0,27 tasks executed, forced rerun/no build cache. The old fixture's space-containing path was not reliably invalid; the new case uses an out-of-range port and asserts the real call factory is never reached.

## Remaining branch and device audit

The frozen-parent union contains62 of73 observed remote branch tips. The11 other tips must be assessed by content rather than blindly merged:

| Branch group | Evidence / next disposition |
|---|---|
| agent-c batch |Emby/Jellyfin identity guard layer tests are identical to current files. Authentication stress is ported. Request-construction redaction is covered with a deterministic no-network case. The additional non-overwriting backup UI fixture still needs emulator-only adaptation and execution; credential key-name equality alone is insufficient for value stability.|
| PR16 source and C verification |Sanitized archive and corrections are ported. Original private-service history stays out of the integration ancestry. PR16 is now Draft; historical reported evidence never endorses the current head.|
| t0001 repair branches |Review against current cancellation/redaction/ownership implementations and executed contracts before declaring superseded.|
| t0002/t0003 and old visuals integration |Review old renderer/group selection changes against current production selection tests; closed PR22 is not automatically a valid merge input.|
| mislabeled m16/observability tip |Do not restore the old cached-or-native pause policy or treat a cached stream read as proof that an established network stream survives cooldown. Preserve the authoritative pause getter and current record-only decision.|
| old phase0-5 architecture and nightly sync |PR1 and PR41 are closed, unmerged. Assess any remaining useful content separately; do not run maintenance scripts or regress current architecture merely to make ancestry complete.|

Original queue work remains: foreground media-key routing needs real runtime acceptance, the M0 cold/readiness/redirect matrix is partial, and the stated audio-boundary cases need final candidate evidence. Some known residuals are architecture limits rather than passing tests; preserve their labels. A fresh remote-head audit is required before claiming the overall goal complete.

## Evidence ownership

Local logs, TRX/XML, native hashes, frozen-ref coverage and process metadata are in the private20261025 main-readiness evidence directory. App/device acceptance for PR58 remains bound to3c51ce52 and its installed APK; do not relabel it as acceptance of this new union. No raw UI, server domains, account values, tokens or cookies belong in Git/PR. Main-facing exact-head Android/Windows CI and subsequent device gates remain required.
