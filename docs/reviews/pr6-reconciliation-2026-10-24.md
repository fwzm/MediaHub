# PR6 invariant reconciliation - 2026-10-24

Base: combined candidate5e028a31. Original PR6:7809facab2316f1c43f1e30deb2efeacd8c2ca78. Reconcile additively; never restore an entire old production tree.

| Original work | Reconciliation |
| --- | --- |
| Logger/exception privacy | Keep current safeLogText bound and cancellation/IOException-preserving cause wrappers. Add complete escaped/quoted/truncated and bare-colon redaction; retain existing userinfo-without-password contract. |
| API cancellation/probe | Keep current asynchronous awaitCancellable, credentials, timeout and cooldown behavior. Add missing cancellation checks before retry and after backoff; redact full error text before the500-character log limit. |
| Pagination | Keep shared pageWindow and canonical identity behavior, which already stop empty pages. Preserve old boundary tests and both real-request/interceptor cancellation regressions. |
| Filter contract | Restrict MediaFilter v1 to MOVIE/SERIES/EPISODE/null. Keep shared internal nullable wire mapper for identity lookup; constructor validation prevents silent filter widening. |
| Library query recovery | Capture last working sort/filter until successful load; preserve generation guards and container navigation. |
| Search restoration | SavedStateHandle query is shared by ViewModel and actual SearchRoute. Preserve later multi-source grouping/expansion. |
| ADR/history | Original ADR, changelog and task text preserved; dated clarifications appended. |

RED:167 executed tests /14 assertion failures /0 errors/skips, exit1. Eleven failures prove behavior gaps; three ProviderException exact text/prefix assertions describe an obsolete format and are excluded from product-failure claims. Emby tests did not execute because preserving the original cancellation fixture duplicated one identical empty-page method; compile failure retained separately. Search RED uses the current empty-flow behavior with constructor/getter-only plumbing to compile the restored-query contract; restoration is not wired until GREEN.

GREEN:204 tests /25 suites /0 failures/errors/skips, exit0;177 tasks executed. Count increase is36 Emby cases now executing plus one extra long quoted/truncated-value regression. Compatible exception test wording changed while every privacy assertion remained. Both runs use JDK21,serial Gradle,in-process Kotlin,--rerun-tasks --no-build-cache. Raw commands/XML, interrupted preparation and source hashes are retained locally under mh-main-readiness-evidence-20261024/pr6.

A short early GREEN launch after a failed preparation step was cancelled before tests; it is ABORTED_PREPARATION, not a product result. No timeout or business assertion was relaxed.

Current unit CI intentionally skips23 cross-end cases when Windows CLI is absent. This does not prove interoperability; a Windows execution gate and real CLI run remain required. Physical-device results are not upgraded by these JVM tests.
