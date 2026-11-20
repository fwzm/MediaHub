# 2026-11-20 Round 32 — core:logging 诊断缓冲字符预算

## 目标
type:goal
- 审计切片：`core:logging` 的 `LogBuffer` 只有条数上界（capacity=512），单条长度不受限；`Redactor.MAX_OUTPUT_LENGTH = 20_000` 允许单条 20000 字符，512 × 20000 ≈ 10 MB，诊断导出会撑爆内存。
- 目标：给缓冲加字符预算 + 逐条截断，形成「条数 + 字符数」双重上界，且不改变既有行为（预算内原样保留）。

## 任务状态
type:state
- worktree `D:/deepseek_test/mh-logbuffer-budget-20261120`，分支 `feature/log-buffer-budget-20261120`，base `origin/main = 77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- 实现 `core/logging/src/main/kotlin/com/mediahub/core/logging/LogBuffer.kt` 最终 sha256 = `338ac167143ef22aade59ea6d97574ad1f5a24b4a2502c396490fd9d2a0a8e7a`（2832 B）。
- 测试 `core/logging/src/test/kotlin/com/mediahub/core/logging/LogBufferBudgetTest.kt` sha256 = `e209f708b71115a2641f06ec9c00222774d51f302a917257f22c7e1f6b1c24bf`（5 tests）。
- RED `EXECUTED_FAIL`：44 tests / 3 failed / exit 1；聚焦 GREEN `EXECUTED_PASS`：44 tests / 0 failed / exit 0（40s）；NC 3/3 各恰好 1 红且逐字节还原复验 MATCH。
- 真机 `NOT_RUN`（Room 6↔5 迁移阻塞，禁清库）→ 本轮总体 `PARTIAL`。

## 已试方案与失败原因
type:dead-end
- 先写测试直接跑 RED：拿到的是**编译红**（`No parameter with name maxChars found`），不是行为红。失败原因：构造器还没有 `maxChars` 参数。改法：先补「只加签名不实现」的 stub 再要行为红。
- NC-B 首版用 `"A".repeat(500)` 断言 `startsWith("AAAA")`：`takeLast` 变异体**照样通过**（全是 A，首尾不可区分）→ 该场是无效对照。改法：把夹具换成 `HEAD-MARKER-` + `A×480` + `-TAIL-MARKER`，并补 `assertFalse(contains("TAIL-MARKER"))`；之后 NC-B 才恰好 1 红。
- 用 `ops_write_file` 还原实现时漏掉末尾换行（2831 B ≠ 2832 B），sha 对不上快照；补 `\n` 后恢复 MATCH。教训：还原必须比对字节数与 sha256，不能只看 diff 为空。

## 进度与下一步
type:progress
- 已 push 分支并开 Draft PR（G7 = `DO_NOT_MERGE`，不合并、不标 ready）。
- 全量门禁 `testDebugUnitTest assembleDebug --rerun-tasks --no-build-cache --no-parallel --max-workers=1` 已在本机运行，结果写入 `D:/deepseek_test/mh-logbuffer-budget-evidence-20261120/full-20261120.log`。
- 下一步：用 40 位完整 SHA 查该 PR 的 exact-head CI run 并回填 `D:/deepseek_test/mh-dsh-longrun-20261014/queue.json`；随后开 Round 33 下一垂直切片（候选：`core:logging` 的 `Logger`/`LogTag`、`player/compatibility` 视频侧、`#114` Coil 内存缓存）。
