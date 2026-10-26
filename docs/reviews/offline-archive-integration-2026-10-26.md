# Offline encrypted archive integration

## Frozen candidate and merge

Feature `codex/integrate-offline-archive-20261025`, additive parents
`cd7ceeeca28ea26f0fe53f7405bebfc2770ca817` (Draft70) and
`697d40a1aaa9664c126979a926dff761388292ac` (archive delta, Windows tip7e89b80a).
Workflow conflict retains both parents' required suites and adds the concurrency
suite. README retains the current scoped evidence explanation and new archive
feature description, without restoring stale branch-wide count claims.

The incoming feature is an in-memory format API: no Android CLI/UI/file/database
production wiring is added by this increment. It ports Windows archive framing,
PBKDF2/AES-GCM, exact AAD bytes, bounded header/payload sizes, passphrase policy,
stable failure ordering and golden vectors. This is not a completed cross-device
archive transport or a physical-device acceptance claim.

## Actual defects found before publication

1. Reader helpers communicated through a singleton volatile lastReason. Four actual
   concurrent workers returned null reasons for magic/header errors. One real
   AssertionError, exit1,17forced tasks/3m17s; original XML retained.
2. Both Kotlin and Windows readers accepted a valid encrypted payload with actual
   non-null secrets while canSendSecrets was false. Kotlin expected secrets-not-allowed
   but returned success; Windows Assert.False observed true. Separate real negative
   tests failed on both platforms. Writer-only rejection was insufficient.
3. Invalid UTF8 bytes in a known header string escaped the structure boundary.
   Kotlin replaced the byte and reported unknown-kdf; Windows TryRead threw from
   lazy JsonElement.GetString decoding. Separate real negative tests failed before
   strict decoding was added; original logs/XML/TRX are preserved.

## Corrections

Kotlin helper failures carry operation-local ArchiveException reasons. Public Pair
results never consult diagnostic state; the compatibility lastReason getter is now
thread-local and cleared after success. Operations remain concurrent, with no global
reader lock. The reader and writer share a pure credential-block predicate: actual
non-null blocks are rejected even when the flag lies; explicit JSON null is allowed.
Windows applies the same read-side rejection rule. Both header codecs validate UTF8
strictly before JSON field extraction, returning corrupt-header before passphrase
validation. Serialization, raw AAD bytes, error vocabulary and cipher layout remain
unchanged.

The original17-branch replacement helper stopped on an expected-count preparation
assertion before writing source (actual17 vs assumed15); corrected that count. A
Windows compile attempt also lacked System.Text imports; retained its compiler log,
fixed the imports and reran the Windows suite. These are preparation/compile failures,
not extra product REDs or successful validations.

## Executed evidence

Windows corrected full unit suite: **1580 passed, zero failed/skipped, exit0**;
WebDAV suite: **104 passed, zero failed/skipped, exit0**. Archive cases include the
new hidden-secret, explicit-null and malformed-UTF8 scenarios. TRX is private.
Earlier1579-pass output predates the malformed-header case and is not the final count.

Kotlin common/database forced no-cache regression: **785 passed, zero failures,
errors or skips, exit0**, 75 tasks executed, 4m23s (common599 +database186). This
includes archive vectors15, roundtrip25, concurrency2 and all23 actual Windows CLI
sync interoperability cases. The corrected CLI and all dependency DLL hashes were
captured and rechecked; source inputs were unchanged throughout. Oldb71 binaries
are not reused as proof. Raw XML and the input/bundle manifest are private.

Windows solution Debug/x64 build: **exit0, zero warnings/errors**, 16.76s.
This is compilation evidence, not Windows GUI playback acceptance.

Android app, app test APK and feature-player test APK plus core/common lint:
**exit0**, 568 tasks (52 executed, 516 up-to-date), 10m. Lint has zero errors and
one warning. App APK SHA-256:
`9c80f77056d3921af5477af897fc3c389c19a76ccb2b8c5641089158c5938990`.
After the interrupted observation was resumed, the same process was confirmed
successfully completed; no build was restarted. All17 recorded source inputs and
all three peer dependency DLL hashes still match the executed validation.
New literal-head Android/Windows CI: **CI_PENDING**. No real server/account, device
operation, data reset, schedule or main write was performed. Phone acceptance remains
deferred by the human, and G7 remains **DO_NOT_MERGE**.

## Limits

Golden vectors and local peer tests have distinct scopes; neither is a physical
two-device archive transfer. The format retains its existing minimum iteration
policy; no new universal CPU budget for attacker-selected high iteration counts is
claimed. This increment does not declare the remaining legacy architecture review
complete and does not import incompatible provider/storage/SAF designs wholesale.
