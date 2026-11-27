# 交接账本 · 2026-11-19 · 播放完成自动退出（PR #130）

## 任务状态（type:state）
- 分支 `feature/player-completion-exit-20261119`，代码提交 `4fadd1b8`，base `origin/main@77ef1f2f`；PR **#130 Draft/OPEN**（base=main）。
- 本机门禁 **EXECUTED_PASS**：`:feature:player:testDebugUnitTest`（两组测试）`--rerun-tasks --no-build-cache --no-parallel --max-workers=1` → BUILD SUCCESSFUL 1m57s，19 tests / 0 failures（16+3）。
- RED **EXECUTED_FAIL**：撤掉退出发射后 19 tests / 3 failed，仅三条退出断言红；XML 与日志见证据目录。
- 真机 **NOT_RUN**（本轮未安装 APK、未触碰设备）；G7 恒 **DO_NOT_MERGE**，未合并/未 mark-ready。

## 目标（type:goal）
- 审计行 46：电影、末集、关闭连播的剧集播完后停在末帧 → 无下一集可播时宽限 1.5s 自动退出播放页，仍走 `exitPlayer -> stopAndFlush`（ADR-023）。
- 只做最小垂直切片：不改 DataStore 偏好、不改播放引擎、不改路由；退出策略集中在 `PlaybackCompletionPolicy`。

## 已试方案与失败原因（type:dead-end）
- 首轮 RED 因 `PlayerViewModel.kt:590 Unresolved reference 'SharedFlow'` 编译失败（缺 import），该次不计 RED 证据；补 `import kotlinx.coroutines.flow.SharedFlow` 后重跑。
- 未加 UserPreferences 开关：会扩散到 DataStore/backup/restore 快照链，超出本轮范围；已在 PR 正文标注“有意延后”，未静默省略。
- 未采用“末集停在末帧+提示”方案：审计要求是自动退出，提示方案需新增 UI 状态，成本更高。

## 进度与下一步（type:progress）
- 已完成：策略对象 + VM `AdvanceOutcome` 状态机 + UI 收集器 + 5 例结束行为测试 + 3 例策略测试；证据 `D:\deepseek_test\mh-completion-exit-evidence-20261119\`（green-20261119.log、red-test-results-20261119/、pr-body.md）。
- 下一步（任选其一）：①真机验收（装本分支 APK，播放至 EOF，验证电影/末集退出且连播不受影响；夹具需中段起播且每轮换新 itemId）；②转审计下一条 READY 行（建议 #80 轨道选择失败静默或 #43 编辑媒体库对已开页面不生效）。
- 待办（未做，勿当已完成）：宽限期是否可配置的产品决策；连播 resolve 失败时是否也应退出（当前 FAILED 不发信号）。
