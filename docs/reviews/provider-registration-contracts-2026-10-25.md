# Provider registration contract port

## Candidate and actual failures

Feature `codex/port-provider-registration-contracts-20261025`, parent
`44fa3ea9f76f19bfc716fc9c432c405683e11d61` (Draft #69). Legacy architecture tip
`47eca35054acb3718282344786ef341b990462f0` enforced duplicate-provider rejection
and runtime capabilities being a subset of the descriptor declaration. The current
code documented the latter but did not reject invalid construction; associateBy
silently replaced a duplicate registration.

Four actual negative tests failed before the production change:

- Two factories with the same ServerType and different stable IDs were accepted.
- Two factories with the same stable ID and different ServerTypes were accepted.
- A handle exposed an undeclared capability.
- A valid handle's copy added an undeclared capability.

RED: four expected-exception AssertionErrors, zero errors/skips, exit1; 57 forced
tasks/4m9s. Original XML/logs are retained privately. No network or device was used
to exercise these configuration invariants.

## Correction

Registry construction reads each descriptor once, rejects duplicate types and stable
IDs, and builds its private immutable map from the same registration snapshot.
Valid lookup, unknown-type null results and display-name sorting remain unchanged.
ProviderHandle construction requires runtime capabilities to be declared, including
construction through data-class copy. Additional planned-but-unimplemented declared
capabilities remain allowed; equality is not required. Error text contains enum
capability names or generic registration errors, not server/account metadata.

The current ServerType storage model is retained under ADR-015. This ports the
invariants without importing the old open-providerId schema or PlannedProviderCatalog.
API test fakes for QUERY, IDENTITY_LOOKUP and FAVORITE now declare the capabilities
they actually provide; the production guard is not relaxed for those fixtures.

## Executed evidence

Scoped forced API/base suite: **31 passed, zero failures/errors/skips, exit0**,
57 tasks/4m12s (provider-api25, provider-base6). Both suites are now mandatory in CI
with complete-method enforcement and all prior required suites preserved.

Complete CI-equivalent local unit execution ran **2577 cases, 69 failures, zero
errors/skips, exit1**, 2351.788 seconds. It included standalone JVM tasks and all
Android unit modules; all 23 actual Windows CLI cases executed. Every raw XML and
failure is retained. The failures were in six private fake classes across detail,
library, search and player tests: descriptors omitted non-null capabilities those
fixtures installed. All other 2320 cases passed, including production factory tests.

Only declaredCapabilities initializers in those six test files were corrected.
No feature assertion, callback, loading behavior or production input changed after
the broad run. A complete forced rerun of the four affected modules (**257 cases**)
completed **257 passed, zero failures/errors/skips, exit0**. Reconciliation verified
identical case keys, unchanged production inputs and declaration-only corrections.
Final source-bound composite coverage is **2577 passed, zero failures/errors/skips**.
This is not a single all-green full invocation; the original exit1 remains visible.

The sole instrumentation fake in PlayerRouteVisualEffectsTest also omitted its actual
DETAIL/PLAYBACK/SUBTITLE_DISCOVERY declaration and is now corrected. Its runtime
result is not inferred from unit tests; new-head API32/36 CI remains required.

App plus app/player test APKs and provider-base lint: **exit0**, 628 tasks
(36 executed, two from cache, 590 up-to-date), 4m27s. App APK SHA-256:
`6d26cb5f29f5c2e8e155b50569124ab80cf169c1e586080d1a37eb41f5357464`.

All local unit invocations used JDK21, `--rerun-tasks --no-build-cache --no-daemon
--no-parallel --max-workers=1 --console=plain` and in-process Kotlin:
scoped `:provider:api:test :provider:base:testDebugUnitTest`;
broad `testDebugUnitTest :core:model:test :provider:api:test :metadata:test --continue`;
recheck `:feature:detail:testDebugUnitTest :feature:library:testDebugUnitTest
:feature:player:testDebugUnitTest :feature:search:testDebugUnitTest --continue`.
New-head full CI is **CI_PENDING**. Physical acceptance remains deferred by the
human; this increment does not operate the phone or any real media account.

## Remaining branch disposition

The old architecture also replaces provider identity/storage, authentication
coordination, local-root and Basic-encoding policies. Those are separate contracts;
these four checks do not establish full legacy parity. Retain existing adopted
ADRs and verify each remaining contract before a readiness claim. The new remote
offline encrypted archive changes are not included in this feature increment.
G7 remains **DO_NOT_MERGE**; main is unchanged.
