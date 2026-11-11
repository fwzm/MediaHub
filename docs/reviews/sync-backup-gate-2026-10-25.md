# Sync and non-overwriting backup gate - 2026-10-25

This increment advances the main-facing Draft from db09bb9c by preserving the frozen new sync parent8b70aae5b85e96e054505f208179820859bd8cd3. It contains Windows branch d4bdad05c104ae6b4f7d78230e0ed12b06f6c736. The actual delta is Android listen-budget control, explicit send/receive unicast-host opt-in, associated tests and suite pins. Three-way merge preserves the prior playback/UA/security work; copying the remote tip's whole tree would discard unrelated validated code and is not the integration method.

## Parent evidence, not new-head acceptance

The db09bb9c baseline finished all five checks. Downloaded revision manifests confirm that exact checkout rather than the event merge SHA. Unit reports:2384 unique cases,2361 passes/23 gated peer skips,0 failures/errors. The separately executed Windows peer job contains exactly those23 skipped case identities, all passes/no skips. Emulator API32 andAPI36 each executed81 passing cases with one API-capability skip; the immutable db09 gate validates all mandatory cases. These results remain bound to db09 until the new candidate's own CI completes.

## Local execution

New sync sources:967 JVM tests/0 failures/errors/skips, forced rerun/no build cache, including23 real same-machine CLI/Kotlin cases. Windows src/tests/tools are byte-identical to the previously validated b71edc2b source, so the locally built CLI DLL is reused with its recorded hash.

The aggregate build exited1 only at app AndroidTest compilation: an older RestoreProcessDeathTest positional BackupPayload call passed preferences into the later-added favorites slot. The failure is retained, not counted as behavioral RED. The fixture now names manifest/servers/progress/preferences explicitly. Subsequent app AndroidTest build: exit0,496 tasks (12 executed/484 up-to-date),3m17s. No physical device targeted.

## Safe backup fixture port

The historical C non-overwriting flow is adapted for a fresh isolated emulator only. The class rejects physical hardware before its own database/page operations; the CI preparation wrapper rejects non-emulator serials before any device setup/install. Existing sources cause a stop, never a data clear.

The fixture owns two LOCAL servers/progress rows and one synthetic encrypted token. One server simulates already-saved protected data. After exporting, its token and row deliberately differ from the backup, preventing an accidental restore from passing by writing equal values. Private fingerprints compare credential values as well as key names; a same-key/different-value guard self-check must reject the deliberate mutation. Raw credentials never enter comparison messages. The real UI/SAF/serializer/crypto path tests cancelled export, successful file save/decode, wrong password, preview and disabled unconfirmed replacement. Confirmed replacement is not executed. Cleanup names only the fixture's exact IDs/files.

CI runs this one production-app test separately from library instrumentation on the same isolated emulator. Its XML is mandatory on both API32/API36. The new listen-budget and explicit send/receive switches also have mandatory production Compose assertions. Final Python gate self-tests:58 passed,0 failures/errors. App test APK compilation is not a runtime pass: emulator acceptance is NOT_RUN at this commit checkpoint.

## Resource and remaining gates

Android CI uses one Gradle worker, no parallel build and2GiB in-process Kotlin compilation. Bounded step/job budgets allow the extra app instrumentation without changing pass criteria. No report cleanup or fresh-emulator guard removes user sources or private restore journals to obtain a pass.

Foreground media-key delivery still requires an active system MediaSession, not only the present Compose key mapper. The URL-to-server index also starts as an asynchronously populated empty list without a readiness barrier; the original cold-UA/readiness/redirect matrix remains open. Do not relabel old device results or claim the full main-readiness goal complete. Keep Draft/G7 DO_NOT_MERGE.
