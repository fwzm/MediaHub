# Cold URL scope / UA readiness - 2026-10-25

ServerUrlIndex previously launched its first database collection asynchronously but the production resolver immediately read the initially empty list. The first request could therefore send default UA despite a configured source override. The nullable resolver also treated an ambiguous current index as a miss and could borrow an older, unique image-auth mapping.

The index now publishes explicit first-snapshot readiness and immutable lookup status. Request threads may wait at most300ms for that collector signal; no repository I/O runs on the request thread. Completed/error/cancelled collection releases waiting callers. Timeout/unavailable state returns no source identity, and interrupt status is preserved. Ready ambiguity remains a match with no unique owner, so the composition root cannot bypass it via a stale fallback. A ready unmatched URL retains the provider-derived auth-scope fallback. Existing credential generation/stripping is unchanged.

## Executed validation

- RED: actual local HTTP OPTIONS and cold same/foreign-origin redirects through the real composition-root resolver and production network interceptors, plus stale-auth ambiguity. Nine tests /3 assertion failures /0 errors/skips, exit1;368 tasks forced,17m12s. First failure logs and XML retained privately.
- GREEN: app cold/warm scope suites and core network UA policy/interceptor suites,29 tests /0 failures/errors/skips, exit0;373 tasks executed, --rerun-tasks --no-build-cache,20m41s. JDK21,1536m heap/four active processors, one Gradle worker, no parallelism/daemon, in-process Kotlin.
- Covers first OPTIONS after a delayed initial snapshot, custom UA retained for the scoped redirect hop, default UA and removed Authorization on the foreign hop, ambiguity against a stale unique auth snapshot, bounded timeout with later recovery, failed primary collection, preserved caller interrupt and no direct repository reads.
- The300ms deadline is deliberate. If data is still unavailable, default UA is used; no unbounded startup wait or guessed source mapping is introduced. Real Android cold-start timing remains a separate acceptance requirement, deferred while the human uses the device.
- Mandatory CI pins include both cold-start and warm ServerUrlIndex suites. New-head CI is pending; full-project readiness is not inferred from these targeted passes.

## Parent gates and remaining work

PR60 at20966d05 failed both emulator APIs at the new first system-Pause assertion. Each API artifact has90 passing cases,1 failure,1 expected capability skip. Its2415 unique JVM cases have2392 pass +23 gated peer skips; build/unit/lint stages passed, final visual enforcement correctly failed. An AudioTrack-based routing fixture repair is prepared privately and must be verified on a later candidate without dropping any media-key assertion. Do not relabel this parent as green.

Native surface DraftPR61 at5a9f018d remains physically PARTIAL because another operation force-stopped the app and the human deferred testing. No phone UI/install is allowed until the human says available. Legacy t0001 repairs are content-superseded with25 actual unchanged-input CI cases; t0003 contains a real missing per-track Media3/snapshot-selection feature and requires a separate content port. G7 DO_NOT_MERGE.
