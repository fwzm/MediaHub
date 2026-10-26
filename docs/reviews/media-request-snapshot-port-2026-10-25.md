# MediaSource request snapshot content port

## Frozen scope

Feature `codex/port-media-request-snapshots-20261025`, parent
`df4e74ed4f162a28c71bb84c0f0a79fd09dc37d4` (Draft #67).
The legacy architecture tip `47eca35054acb3718282344786ef341b990462f0` introduced
immutable per-MediaSource request context. The current implementation retained a
per-engine holder but read it at delayed DataSource.open time. This is a genuine
contract gap; it does not establish that the entire legacy architecture is needed.

At the frozen parent, 72 of 83 origin tips are ancestors and 11 need content
disposition. The early architecture branch has 119 changed paths against its merge
base. This increment addresses its four header/context paths; the other 115 rows
remain pending contract disposition. Do not describe this as complete branch coverage.

## Actual reproduction

Only fake credentials and local MockWebServer endpoints were used.

- A DataSource factory created for A later created a reader after the holder changed
  to B. The actual A server received `Bearer fixture-b` instead of `Bearer fixture-a`.
- Mutating the caller's original Map rewrote the supposedly published holder state.
- An actual HLS MediaSource created for A was prepared after the next B source was
  created. A's manifest request carried B's fake token. The corrected HLS fixture
  uses Media3 1.11's current prepareSource overload and exercises chained DRM,
  load-policy and subtitle configuration on the same factory.

Three unique real assertion failures were observed across two executions. The
forced 80-task run had two real ComparisonFailures plus an HLS fixture exception
(obsolete prepareSource overload) and cleanup exception. The corrected HLS-only
run produced one real ComparisonFailure, exit 1 (two tasks executed, 78 up-to-date).
Production inputs were unchanged between these RED runs. Earlier invalid generic
getApplication fixture compilation and the obsolete-overload exceptions are retained
privately and are not counted as product failures.

## Correction

PlaybackHeadersHolder publishes a copied, unmodifiable map. PlayerFactory captures
that snapshot for every MediaSource before loading begins, including later HLS/DASH
readers. The media DataSource factory also binds its snapshot before a reader exists.

SnapshotMediaSourceFactory wraps the configured DefaultMediaSourceFactory. Each
source receives a fresh immutable header wrapper. The original cache, connection
pool, speed listener, UA policy, redirect credential stripping, startup listener,
record-only 429 ledger and observability boundary remain shared and intact.
All MediaSource.Factory configuration APIs forward to the delegate and return the
wrapper, so fluent configuration cannot bypass the snapshot boundary. Local 1.11.0
bytecode confirms changing its DataSource factory clears cached format factories;
the real delayed-HLS test verifies that already-created sources retain their context.

## Executed validation

`:player:engine:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon
--no-parallel --max-workers=1 --console=plain`, JDK21, 1536 MiB heap and in-process Kotlin.

**162 tests passed, zero failures/errors/skips, exit 0; 80 tasks executed, 4m12s.**
The three new cases passed with all existing engine tests, including redirect
credential stripping, scoped UA, 429/observability, track selection and external
subtitle reload. Raw XML is archived privately. Five changed runtime/test/gate files
were hashed during configuration before engine compilation and checked unchanged
after execution. The suite is mandatory in CI with complete-method enforcement.

Application/test APK and engine lint: **exit 0**, 597 tasks (42 executed,
555 up-to-date), 7m34s. App APK SHA-256:
`1f3a7435a2865627eb93f9af3027525664455c2b8b7ce70f3b7d0cd0b1dceb01`.
These are compilation and lint results; no APK was installed on the deferred phone.

## Boundaries

New-head full CI is **CI_PENDING**. Physical playback is **NOT_RUN** for this increment;
the user's phone deferral persists. Existing parent device or CI passes cannot be
relabeled as this candidate's evidence. No real media server, account, raw UI dump,
device install, schedule, main write or PR merge/close/mark-ready was used.
G7 remains **DO_NOT_MERGE**. Continue the remaining legacy content audit after the
new candidate's literal-head CI is checked; do not import obsolete enum/provider,
credential-storage or progress-pipeline designs wholesale.
