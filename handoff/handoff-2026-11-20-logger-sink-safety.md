# 交接账本 · Round 34 · 日志汇聚器单点故障隔离

## 目标 (type:goal)
- 修 CompositeLogger 的单点故障放大：任一 sink 抛异常会让后续 sink 丢日志，并把异常抛回业务调用方（播放/网络路径）。
- 同时保持取消透传红线：CancellationException 不得被吞成"日志已写"。
- 切片边界：只改 core/logging/src/main/kotlin/com/mediahub/core/logging/Logger.kt 的 CompositeLogger；接口 Logger 与 StdoutLogger 不动。

## 任务状态 (type:state)
- 分支 feature/logger-sink-safety-20261120，worktree D:/deepseek_test/mh-logger-sink-20261120，base 77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 实现 sha256=a5b89323a48e17183fb33a36e993d5795d1554c11794fcf5293ccb0ceb0157ed（2409B），新增测试 LoggerSinkSafetyTest.kt 6 tests。
- RED EXECUTED_FAIL：45 tests / 4 failed，exit 1，BUILD FAILED 40s。
- 聚焦 GREEN EXECUTED_PASS：core:logging 45 tests / 0 failed（8+4+6+27），exit 0，38s。
- NC 3/3 各恰好 1 红并逐字节还原 MATCH=a5b89323…。

## 已试方案与失败原因 (type:dead-end)
- 首版 NC 设计若只去掉 try/catch，会让「取消透传」与「sink 故障隔离」两个断言同时红 → 不满足"恰好 1 红"；改为三场各自只退化一条防线。
- NC-B 首版写成"任何 sink 失败都冒泡"，同样会打到两处断言；改为仅最后一个 delegate 冒泡，才只红 sink_failures_never_reach_the_caller_on_any_level。

## 进度与下一步 (type:progress)
- 下一步：取全量门禁结果（exit / suites / tests / APK sha256）→ commit → push → Draft PR → queue.json 追加条目 → 复查 exact-head CI。
- 复查 #203 run 44712630444（head 30fd5598e5904008786bc0b8407ffb0bed6aefe0），17:49 仍 in_progress。
- 真机 NOT_RUN（Room 6↔5 迁移阻塞，禁清库）；G7 恒为 DO_NOT_MERGE。
