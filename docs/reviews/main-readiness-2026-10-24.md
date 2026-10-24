# Main readiness ledger - 2026-10-24

Scope: reconcile all active PR work into candidates that can be reviewed for main. Existing merged or superseded implementation must be preserved or explicitly reconciled, rather than revived blindly. This ledger does not authorize PR merge/close/mark-ready.

## Source revisions

- main: `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`
- current main-facing candidate #45: `1a9879ae31768e8b36d12ec52b9542a5d9e3c1ae`
- Inventory was refreshed from GitHub open PR metadata and local ancestry after git fetch origin.
- Main readiness, physical-device acceptance and release/distribution readiness are separate evidence claims. A green branch CI does not prove an integrated candidate.

## Open PR coverage

| PR | Head | Contained in #45 | Next action |
| --- | --- | --- | --- |
| #6 | `7809faca` | no | Old hardening branch conflicts; compare every invariant with current production and migrate remaining behavior/tests additively. |
| #16 | `f40363ce` | no | Historical evidence-only document; preserve its original scope and append a dated current-state note before integration. |
| #20 | `1f3e6ace` | no | Historical W0/W4 documents; retain snapshot dates and distinguish superseded findings from current issues. |
| #28 | `f55db267` | yes | Already contained by ancestry; do not merge it again. |
| #29 | `1637b9e9` | yes | Already contained by ancestry; do not merge it again. |
| #30 | `faa06fdb` | yes | Already contained by ancestry; do not merge it again. |
| #31 | `a1ee9bca` | yes | Already contained by ancestry; do not merge it again. |
| #32 | `208203e0` | yes | Already contained by ancestry; do not merge it again. |
| #33 | `96b6a9ee` | no | Windows + Android favorites/sync/autoplay; verify joint compatibility and current Windows/Android workflow evidence. Release/signing gates stay separately recorded. |
| #34 | `7616f14f` | yes | Already contained by ancestry; do not merge it again. |
| #35 | `91852d17` | yes | Already contained by ancestry; do not merge it again. |
| #36 | `ef9f6e0a` | yes | Already contained by ancestry; do not merge it again. |
| #37 | `916e2fb4` | yes | Already contained by ancestry; do not merge it again. |
| #38 | `acfdedf2` | yes | Already contained by ancestry; do not merge it again. |
| #39 | `b7890aaa` | no | Contains current #33 tip; verify incremental synchronization integration and Windows/Android contract evidence. |
| #40 | `b9ec54e5` | yes | Already contained by ancestry; do not merge it again. |
| #42 | `83329106` | yes | Already contained by ancestry; do not merge it again. |
| #43 | `afe74050` | yes | Already contained by ancestry; do not merge it again. |
| #44 | `86ba9e1e` | yes | Already contained by ancestry; do not merge it again. |
| #45 | `1a9879ae` | yes | Unified candidate; retain Draft until all required integration evidence is reconciled. |
| #46 | `1858d944` | no | Reconcile original 5236f22 and replayed 1858d94 using additive merge. Fresh RED/GREEN and exact-head gates required. |
| #53 | `970df7fc` | no | Migrate copied Slider tests to production PlayerSeekBar; preserve production testTag and real touch contracts from #54. No rebase/force push. |

## Reconciliation guardrails

- #46: preserve both original and replayed heads. Never use another forced push.
- #53: the integrated PLAYER_PROGRESS tag and production PlayerSeekBar already exist via #54. The old duplicated Slider test is insufficient production evidence.
- #33 and #39: Android production deltas are present as well as windows/. Validate overlap against #45 before claiming main readiness.
- Real device `123e243f`: adb devices -l returned no devices on this round; physical tests BLOCKED_ENV. No installation or device-data action was attempted.
- Cold-start UA scope readiness, redirect matrix, audio boundary cases and media key routing retain their pending evidence; do not infer their completion from the ancestry table.
- Historical documents in #16/#20 retain their dates and original failures; a later correction must be append-only.

## Local evidence location

Raw command logs, XML reports, original PR metadata and CI downloads are kept outside the repository at `D:\deepseek_test\mh-main-readiness-evidence-20261024`. Only sanitized counts and revision bindings belong in this repository.

## Merge simulation against current #45

- #39: git merge-tree exit 1; 1 conflicted files.
  - `core/network/src/test/kotlin/com/mediahub/core/network/MpvHttpBridgeLifecycleTest.kt`
- #33: git merge-tree exit 1; 1 conflicted files.
  - `core/network/src/test/kotlin/com/mediahub/core/network/MpvHttpBridgeLifecycleTest.kt`
- #20: git merge-tree exit 0; 0 conflicted files.
- #16: git merge-tree exit 0; 0 conflicted files.
- #6: git merge-tree exit 1; 13 conflicted files.
  - `core/logging/src/main/kotlin/com/mediahub/core/logging/LogBuffer.kt`
  - `core/logging/src/main/kotlin/com/mediahub/core/logging/LogcatLogger.kt`
  - `core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt`
  - `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt`
  - `core/network/src/main/kotlin/com/mediahub/core/network/ApiException.kt`
  - `feature/library/src/main/kotlin/com/mediahub/feature/library/LibraryViewModel.kt`
  - `feature/search/src/main/kotlin/com/mediahub/feature/search/SearchScreen.kt`
  - `feature/search/src/main/kotlin/com/mediahub/feature/search/engine/GlobalSearchEngine.kt`
  - `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt`
  - `provider/emby/src/main/kotlin/com/mediahub/provider/emby/api/EmbyApiClient.kt`
  - `provider/emby/src/main/kotlin/com/mediahub/provider/emby/library/EmbyLibraryProvider.kt`
  - `provider/emby/src/main/kotlin/com/mediahub/provider/emby/search/EmbySearchProvider.kt`
  - `provider/emby/src/test/kotlin/com/mediahub/provider/emby/EmbySearchProviderTest.kt`

These simulations do not run tests or establish behavior compatibility. #33/#39 both conflict only in MpvHttpBridgeLifecycleTest.kt; retain the flake hardening and verify the combined suite.

## PR46 reconciliation evidence

- Original head: `5236f22c761962e1d89c69fd9bdb42d1048834dc`.
- Replayed head: `1858d944b30e2735657f1c6d2860a578cc441036`.
- Additive ancestry merge: `edf9602e05bf5448f4c8f0b8eaadce250913eff0`; both heads are parents, and its tree is byte-identical to the replayed head. The earlier forced update remains recorded as a rule violation; this merge preserves the original history without another rewrite.
- RED: current #45 production control files (`1a9879ae`) with reconciled PR46 tests: 4 Media3 + 26 mpv tests, exactly 2 assertion failures, exit 1. Failures: ended Media3 toggle emitted pause, and unavailable native mpv pause emitted a fabricated success.
- GREEN: same 30 tests, 0 failures / 0 errors / 0 skipped, exit 0. Both commands used `--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`, JDK21, in-process Kotlin, JVM 2048 MB.
- Python CI evidence gate tests: 52 passed, exit 0. No timeout or assertion was relaxed.
- Historical exact-head CI 40088109717 verified at `1858d944`: checkout/head matched; downloaded reports contain 200 suites / 1561 unique tests / 0 failures / 0 errors / 0 skipped. This historical run does not certify a later commit.
- Current physical native verification: BLOCKED_ENV (no ADB devices). UI/emulator and JVM evidence do not replace physical native acceptance.
- No production behavior was altered by the ancestry merge; the following cleanup only corrects the mpv missing-property comment and removes an unnecessary local alias.
