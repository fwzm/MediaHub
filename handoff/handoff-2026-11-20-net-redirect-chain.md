# 交接账本 · Round 40 · net-redirect-chain-20261120

## 目标
- type:goal 修正 core/network 起播 trace 在重定向链下的首字节与元数据错记，使 mediaRedirects 真实反映 hop 数。

## 任务状态
- type:state 实现 core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt 3412B sha256=3aab6c586b864f75a9f86076177f2688cea4e18a3db9bfa3cbcd5200313a55c8（基线 2618B/918fe652…）。
- type:state 新增测试 core/network/src/test/kotlin/com/mediahub/core/network/StartupNetworkRedirectChainTest.kt 6 tests sha256=fa2be28a3904d2843f6d71e7812f52743a6afabf4bc1c7d422fe82ab895ee0e1。
- type:state RED 10 tests/5 failed/exit1（对基线真跑）；GREEN :core:network 37 suites/239 tests/0 failed/exit0；NC 3/3 各恰好 1 红并逐字节还原。

## 已试方案与失败原因
- type:dead-end 用 response.priorResponse 反推 hop 数 → OkHttp 在 responseHeadersEnd 回调时 priorResponse 恒 null（probe.txt 实测 priorNull=true priorDepth=0），计数恒 0，方案不可用。
- type:dead-end 把 isRedirect 收窄为 code == 302 做负向对照 → 302 是测试唯一重定向码，变异不可观测，作废。
- type:dead-end 测试里断言 Content-Length 精确字节数 → MockWebServer 的 setHeader 与 setBody 叠加会覆盖，改为按实际响应头取值。

## 进度与下一步
- type:progress 全量门禁 EXECUTED_PASS：testDebugUnitTest :core:model:test :provider:api:test :metadata:test assembleDebug --rerun-tasks --no-build-cache --no-daemon --console=plain --no-parallel --max-workers=1 --continue → BUILD SUCCESSFUL in 23m 2s，FULL_EXIT=0，335 suites / 3097 tests / 0 failed / 0 errors / 23 skipped（24 模块；core/network 37 suites/239 tests）。APK app-debug.apk 130579351B sha256=dbf9ebb618e4cd147dcaece2bdd26f84c3a70f5c30f6ccbab8648150ffc49d8e（未安装）。真机 NOT_RUN → PARTIAL。
- type:progress 待办：commit → push feature/net-redirect-chain-20261120 → Draft PR → queue.json 追加 net-redirect-chain-20261120 并回填 #213 ci_state → memory_log/memory_note(handoff)/白板两卡。
- type:progress 下一步第一步：用 40 位 SHA 复查 #213 run 44750706345，再复查本 PR exact-head run。
