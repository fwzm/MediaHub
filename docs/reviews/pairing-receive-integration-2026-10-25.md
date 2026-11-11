# Pairing / cancellable receive integration - 2026-10-25

Frozen parents: local10c6ff35 and remote89d8ba41 (contains Windowseb4cc7ef). Adopt the later20-cell transfer matrix, local receive cancellation and parse-only pairing entry. The sole merge conflict was the Android required-suite list; both parents' requirements remain. Pairing verifies link facts locally, does not connect to the endpoint, and retains the challenge privately. User copy states the functional limitation without internal milestone IDs.

Review found that explicit Cancel closed a blocked socket, but cancellation of the owning coroutine scope did not. ViewModel lifecycle cleanup only discarded the launcher reference, so blocking IO could remain until timeout. New actual loopback RED accepted a TCP connection, cancelled the scope and asserted peer EOF within1s against a10s read timeout:1 test/1 AssertionError/0 errors/skips, exit1,127 forced tasks10m48s. Original failure preserved.

The launcher now owns a suspended cancellation-cleanup child. If owner cancellation occurs while the IO child is blocked, it closes the registered handle; CancellationException is rethrown. An atomic normal-completion flag prevents a successful receive from being classified as cancelled. Existing explicit Cancel and clear semantics remain, and handle identity cleanup still protects a newer receive.

## Executed validation

- Forced/no-cache affected common/database/settings suites:1020 tests,0 failures/errors/skips, exit0,234 tasks executed,17m49s. The actual blocked-socket regression passed. Normal completion, explicit cancel, fresh handles and clear contracts passed.
- Real Windows CLI/Kotlin peers executed rather than skipped: SourceSyncTlsInteropTest11 and SourceSyncListenerInteropTest12. DLL source matches validated b71 inputs; actual DLL SHA2562d55c85fc63ba56a4be8fb96f3dfdfad49d2c9fcb728683707a5cdf1e035c853 recorded privately. Golden fixtures are separate from these23 real peer cases.
- Python gate61/0; all7 pairing UI and4 receive-cancel UI cases now mandatory on both APIs. Actual new-head runtime remains CI_PENDING. Settings instrumentation APK compiled in the serial validation.
- App build exit0,483 tasks (30 executed),3m6s. JDK21,1536m/four active processors, one worker, no daemon/parallelism. No physical-device UI or install occurred; human device deferral persists.

Parent10c6ff35 has verified grouped-row UI execution: each API92 passes,0 failures,1 expected capability skip, required exact-snapshot address case executed. Full parent unit manifest2450 unique =2427 pass +23 gated peer skips,0 failure. These parent checks do not validate this merged source. Main-facing Draft59 remains0f509 until the new descendants' required checks and outstanding device/legacy gates are reconciled. G7 DO_NOT_MERGE.

Remaining: inspect new-head whole CI and all11 settings UI cases, source-input/peer ledger; finish legacy utility/architecture dispositions and final live-head audit. Physical native surface restoration/cold timing await human availability. Raw logs/UI/server/account/auth data stay outside Git.
