# Pairing TLS and listener integration

## Frozen candidate

Feature `codex/integrate-pairing-tls-listener-20261025` integrates these parents:

- `bafe859b68991ddfb5b16cfd8c255f6f88060829`: prior Android/player fixes, receive
  lifecycle cancellation and safe manual utility (Draft #66).
- `25c56d450763826320381d77f3513ed473f8c563`: pairing TLS, local address display and
  per-wait listener cancellation; includes Windows documentation tip
  `8e9b35607f4905c9b071478367c56e78bc282b39`.

The only merge conflict was obsolete parse-only pairing copy. Keep the new fetch
states, fingerprint/challenge validation, address reader and explicit boundary that
fetching an invitation does not itself establish source synchronization. Retain prior
receive cancellation and all previously required tests. Main remains unchanged.

## Actual lifecycle defect

The incoming listener launcher only closed its cancellation handle for an explicit
button cancellation. Cancelling its owner while an IO child was blocked in a socket
read could leave the connection open until the read timeout, including after clear
discarded the launcher's fields.

Real loopback RED: one test, one AssertionError, exit 1; 127 forced Gradle tasks,
6m4s. The test observes the actual accepted-socket `probePlaintext` stack before
cancelling, then fails because peer EOF does not arrive within one second against
a ten-second read timeout. Original XML and transcript are preserved privately.

The fix captures the per-operation handle in a suspended child that closes it when
the owner is cancelled. An atomic completion flag keeps normal completion from
being labelled cancellation; CancellationException is rethrown. GREEN also joins
the cancelled owner and sends another envelope through the same listener, checking
its exact bytes. No listener-wide close is used to make the cancellation pass.

## Executed local validation

Forced unit command: `:core:common:testDebugUnitTest :core:database:testDebugUnitTest
:feature:settings:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon
--no-parallel --max-workers=1 --console=plain --continue`, JDK21, 1536 MiB heap,
in-process Kotlin and an isolated daemon registry.

| Scope | Executed tests | Failed / errors / skipped |
|---|---:|---:|
| core/common | 557 | 0 / 0 / 0 |
| core/database | 186 | 0 / 0 / 0 |
| feature/settings | 336 | 0 / 0 / 0 |
| Total | **1079** | **0 / 0 / 0** |

Exit 0, 167 tasks executed, 10m40s. Inputs were hashed before execution and checked
unchanged afterward; fresh XML was archived outside the repository. Subsequent
README edits only correct stale evidence counts and scope.

The total includes all 23 actual Windows SyncCli interoperability cases (11 TLS
client-direction, 12 listener-direction). Peer DLL SHA-256:
`2d55c85fc63ba56a4be8fb96f3dfdfad49d2c9fcb728683707a5cdf1e035c853`.
Windows src/tests/tools are unchanged from `b71edc2b`. Pairing's own 13 TLS tests use
a controlled JSSE peer, including pinned mismatch, malformed pin before dialing,
plaintext/TLS mismatch, expired invitation and graceful close; these do not prove
physical Android-to-Windows pairing.

Gate RED: the visual-verifier test found 18 newly introduced UI methods absent
from mandatory execution on each API (36 subtest failures). Require all 25 actual
pairing/fetch, address and listen-cancel UI methods on both API32 and API36, retaining
all previous mandatory cases. Source-derived omission mutations prove every case
must appear as an actual pass. The new listen lifecycle suite and address/controller
suites are also mandatory unit suites.

Python gates: final **62 tests, zero failures/errors/skips, exit 0**, 39.760s. An
earlier run had one Git Bash resource failure (Win32 1455 / fork unavailable) while
Gradle was active; retained that log, waited for Gradle to terminate, then reran
the complete gate suite successfully.

APK/test-APK compilation: **exit 0**, 546 tasks (38 executed, two from cache,
506 up-to-date), 6m16s. Built app debug, settings instrumentation and app
instrumentation APKs. Local app APK SHA-256:
`df6077022b8cbf7722f32c06cdaab6f4e14833be58cab2f3adf1f5a4e1247ace`.
This is compilation evidence on the recorded integration inputs, not a device run.

## Evidence limits and next gate

This is a feature integration candidate, not a merged PR. New-head CI and lint are
**CI_PENDING**. Prior a5aff emulator results (103 pass + one capability skip per API)
belong to that parent and do not validate this new head's 18 additional UI methods.
The manual utility at bafe separately has a literal-head CI pass for its 13 local-Git
fixtures. Neither result is promoted into this candidate's full CI verdict.

Pairing transport retains its incoming loopback-only policy; enumerating local
addresses only displays candidates and does not open LAN dialing or advertise a
listener. No media account, real server scan, device UI/install or schedule was used.
The user's phone deferral persists. Paused-surface recovery, final cold startup,
real server sidecar subtitles and complete cross-device pairing remain outside this
increment's physical evidence. G7 is **DO_NOT_MERGE**.
