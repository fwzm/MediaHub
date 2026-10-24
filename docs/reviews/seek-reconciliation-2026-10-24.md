# PR53 seek reconciliation - 2026-10-24

The original PR53 test duplicated a Compose Slider and could remain green while the production control was broken. It also called SetProgress (which can finish a gesture) and did not actually change state in its recomposition case. Those historical results do not prove the production preview/commit contract.

This revision merges current joint 1a9879ae additively into original PR53 970df7fc (merge 6522099), preserving the production PlayerSeekBar and PLAYER_PROGRESS tag introduced via PR54. The rewritten suite composes PlayerSeekBar directly, checks the held-pointer preview before release, verifies a single release commit equal to the observed preview, and changes actual position state to prove recomposition without a seek.

Executed evidence (JDK21, serial Gradle, no build cache):

| Phase | Cases | Assertion failures | Exit |
| --- | --- | --- | --- |
| Production baseline, rerun-tasks | 8 | 0 | 0 |
| MUT1: production onValueChange also commits | 8 | 4 | 1 |
| MUT2: production onValueChangeFinished omits commit | 8 | 4 | 1 |
| Restored production, rerun-tasks | 8 | 0 | 0 |

The two suites are PlayerSeekBarContractTest (4) and PlayerSeekContractRobolectricTest (4). Each mutation fails two cases in each suite. Both mutations restore the original production source in finally; the final source matches current joint. No skipped cases or test errors were observed.

Command: gradlew.bat "-Dorg.gradle.jvmargs=-Xmx2048m -XX:ActiveProcessorCount=4" "-Pkotlin.compiler.execution.strategy=in-process" :feature:player:testDebugUnitTest --tests "*PlayerSeek*Test" --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain. Baseline/final additionally use --rerun-tasks; mutations rerun naturally after changing production source.

CI now requires both complete suites. New exact-head CI is still required after this commit. This validates production component behavior on JVM/Robolectric, not physical ROM/native decoding. Device 123e243f was offline this round.

Raw XML, phase commands/exit codes and mutation source snapshots remain locally in D:/deepseek_test/mh-main-readiness-evidence-20261024.
