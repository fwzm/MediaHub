# 交接账本 · Round 33（provider/base 协程取消透传）

## 目标 (type:goal)
- 修复 provider/base/src/main/kotlin/com/mediahub/provider/base/BaseMediaServerProvider.kt 的 apiCall 把 CancellationException 包装成 ProviderException.Unknown 的缺陷。
- 验收：取消异常原实例透传、不写错误日志；真实异常仍按原语义包装；IOException→Network、ProviderException 原样透传不回归。

## 任务状态 (type:state)
- 实现 sha256=480867e4a7a5655d45542c731591c414413c0b1d9e28edeaa431f68ed9334a48（4326B，+16/−1），新增 import kotlin.coroutines.cancellation.CancellationException 与 catch (e: CancellationException) { throw e }（在 ProviderException 之后、ApiException 之前）。
- 新增测试 BaseMediaServerProviderCancellationTest.kt 6 tests；RED 5 tests/3 failed exit1 1m34s；聚焦 GREEN 12 tests/0 failed exit0 1m26s。
- NC 3/3 各恰好 1 红，每场后从 nc/snapshot 还原并复验 sha256 MATCH。
- worktree D:/deepseek_test/mh-provider-cancel-20261120；branch feature/provider-cancellation-propagation-20261120；base 77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。

## 已试方案与失败原因 (type:dead-end)
- NC-B 首版变异体 throw ProviderException.Unknown 仅在 e is kotlinx.coroutines.JobCancellationException 时触发 → 编译失败（JobCancellationException 为 internal），无行为对照。改用 e.javaClass.simpleName == "JobCancellationException" 后恰好 1 红。
- 直接 catch CancellationException 前置于 catch (e: ProviderException) 会遮蔽业务异常语义，故插在 ProviderException 之后、ApiException 之前。

## 进度与下一步 (type:progress)
- 待办：全量 testDebugUnitTest assembleDebug（后台 job bash-39d5f687135b461e，日志 evidence/full-20261120.log）→ commit/push/Draft PR → queue.json 追加 → 复查 #200 run 44703052891。
- 真机 NOT_RUN（Room 6↔5 迁移阻塞，禁清库）→ 本轮结论上限 PARTIAL。
- G7 恒为 DO_NOT_MERGE。
