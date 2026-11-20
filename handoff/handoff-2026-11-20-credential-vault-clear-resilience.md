# 交接账本 · 2026-11-20 · CredentialVault.clear 失败韧性

## 目标
- type:goal 让 `CredentialVault.clear(serverId)` 在单个凭据种类删除失败时仍清理其余种类，并把失败点名到种类，避免"删了源但密码/Cookie 残留"。
- type:goal 不改变调用方看到的顶层异常类型与消息（W6-100 日志契约、RemoveServerFailedException.cause 保持原样）。

## 任务状态
- type:state 实现完成：`core/security/src/main/kotlin/com/mediahub/core/security/CredentialVault.kt` 3566B sha256=114641814c18174650a90dcf938257006a8b37397359f865519c8abdf0b1c3af。
- type:state 测试完成：`core/security/src/test/kotlin/com/mediahub/core/security/CredentialVaultClearResilienceTest.kt` 7598B sha256=631f189bfb6b7cc25171cd8e38e8c780f907b3cf59e1b74062efe5c337dbe09b，6 tests。
- type:state RED 行为 stub（sha256=6dd3c34d7efeb56be4d3a52aef90952d08c50d170a85684306f28bf2eb89f50d）→ 23 tests / 3 failed / exit 1；聚焦 GREEN 23 tests / 0 failed / exit 0。
- type:state 负向对照 3/3 各恰好 1 红（nc-a2 消息去 kind 名、nc-b 报最后一个失败、nc-c 包装取消），每场后逐字节还原复验 sha256 MATCH。
- type:state 全量门禁 EXECUTED_PASS：`testDebugUnitTest assembleDebug` BUILD SUCCESSFUL in 20m 14s，882 tasks executed，日志 D:/deepseek_test/mh-credential-clear-evidence-20261120/full2-20261120.log；汇总 320 suites / 2997 tests / 0 fail / 0 err / 27 skipped，feature:server 9 suites / 66 tests 全绿（首版包装异常时这里的 2 红已消除）；APK app/build/outputs/apk/debug/app-debug.apk 130579351B sha256=b9e17f432ff66f2dce981b84a8f1dd0e48dd540153c9fab3f6dac860527b19be（未安装）。

## 已试方案与失败原因
- type:dead-end 首版把失败包装成新异常 `CleanupFailure` 抛出 → 全量门禁 `:feature:server:testDebugUnitTest` 2 红（RemoveServerUseCaseStepsTest.kt:107 与 :132）：MemoryLogger 日志行格式是 `format(...) + " | " + throwable.javaClass.simpleName + ": " + redact(throwable.message)`，包装后 `| CleanupFailure: …` 取代了 `| IOException: keyring unavailable`，W6-100 契约被破坏。
- type:dead-end 首版负向对照 nc-a「去掉 addSuppressed」同时红 2 条断言，不满足恰好 1 红 → 改为 nc-a2（只把 kind 名从消息里去掉）。
- type:dead-end 直接对基线跑新测试得到的是编译红（`No parameter with name ...`/未定义类），不是行为红 → 先写"新类存在但 clear 仍是基线语义"的 stub 才能拿到行为 RED。
- type:dead-end feature/server 侧测试被 #182/#175/#161/#137/#128/#126/#124/#106 等多个开放 PR 占用，不可改其测试 → 只能让 core/security 侧保持原始异常类型。

## 进度与下一步
- type:progress 待办：取全量结果（exit code / 测试总数 / APK sha256）→ commit → push（若 schannel 握手失败，先 curl 探测代理三次 200 再重试）→ Draft PR → queue.json 追加条目并回填 #205 CI_SUCCESS → memory_log / 白板两卡。
- type:progress 第一步：读 D:/deepseek_test/mh-credential-clear-evidence-20261120/full2-20261120.log 尾部确认 `BUILD SUCCESSFUL` 与 exit code，再用 agg 脚本汇总 XML。
- type:progress 真机 NOT_RUN（Room 6↔5 迁移阻塞，禁清库）；G7 = DO_NOT_MERGE。