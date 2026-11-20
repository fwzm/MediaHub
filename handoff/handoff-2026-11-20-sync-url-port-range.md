# Round 39 — SyncUrlNormalizer 端口范围闸门

- type:goal 让 core/common 同步地址规范化把「显式越界端口」当成不可拨号地址拒绝，而不是把写错的端口写进身份串。

## 目标

- type:goal 修复 core/common/src/main/kotlin/com/mediahub/core/common/sync/SyncUrlNormalizer.kt 的端口闸门：基线只判 `port <= 0`（http(s) 分支）与 `uri.port > 0`（smb/file 分支），**没有上限**。
- type:goal 语义对齐既有 core/common/src/main/kotlin/com/mediahub/core/common/ServerAddressNormalizer.kt:150 的 `if (n !in 1..65535) return Result.Invalid(Error.InvalidPort(it))`，不新造返回类型：越界一律 normalize 返回 null。

## 任务状态

- type:state 实现已落盘：core/common/src/main/kotlin/com/mediahub/core/common/sync/SyncUrlNormalizer.kt 4504B sha256=cdae6063e04799070ccd6f5622235eb38c105d1b1ce52101795b211854fd24f6（基线 4054B sha256=7ec2f4d4efce36d08699b8024cad4cf8394573023adf57ab5646a557fd41b534）。
- type:state 改动 3 处：新增 `private const val MAX_PORT: Int = 65535`；在 authority 之前取出 `val rawPort = uri.port` 并加 `if (rawPort != -1 && rawPort !in 1..MAX_PORT) return null`；when 分支内改用 rawPort，并删掉 http(s) 分支里已冗余的 `if (port <= 0) return null`。
- type:state 新增测试 core/common/src/test/kotlin/com/mediahub/core/common/sync/SyncUrlNormalizerPortRangeTest.kt 2737B sha256=55feff9fa768342ac01a773af4483a269d0312f59d5b5915424e78754fd06b73，6 tests。
- type:state RED EXECUTED_FAIL 6 tests / 4 failed / exit 1 / 1m16s（对基线实现真跑）。GREEN EXECUTED_PASS :core:common:testDebugUnitTest 整模块 53 suites / 688 tests / 0 failed / 0 errors / 11 skipped / exit 0。

## 已试方案与失败原因

- type:dead-end 首版 ops_edit_file 返回 status=error 未写入：oldText 里带了文件不存在的一行注释，且把 `if (port <= 0) return null` 一起包进了替换块 → 逐段小改（4 处独立 edits）才成功。
- type:dead-end NC-B 第一版用 `MAX_PORT 65535 -> 65536` 变异 → 6 tests / **2 failed**（65536 与 70000 两条断言同时红），不满足「恰好 1 红」→ 作废，改为 `rawPort !in 1..MAX_PORT` → `rawPort !in 1 until MAX_PORT`（上界少 1），恰好红 `the last legal port is still accepted`。
- type:dead-end 用 ops_write_file 从快照还原实现会**丢掉文件末尾换行**（4503B vs 4504B，sha 02b5f26c… ≠ cdae6063…）→ 还原必须 `cp good impl` 再用 sha256sum/cmp 复验。

## 进度与下一步

- type:progress NC 3/3 各恰好 1 红、每场后 cp 还原并 sha 复验一致：nc-a-no-lower-bound（丢下界，红 port zero）、nc-b-upper-bound-exclusive（上界少 1，红 last legal port）、nc-c-ipv6-not-guarded（IPv6 绕过，红 ipv6 out of range）。
- type:progress 下一步：跑全量门禁 `testDebugUnitTest :core:model:test :provider:api:test :metadata:test assembleDebug`（日志 D:/deepseek_test/mh-sync-url-port-evidence-20261120/full-20261120.log）→ commit → push → Draft PR → queue.json 追加条目并回填 #212。
