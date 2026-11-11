# G5 · 429 记录能力接入（record-only）与 ADR-046（V12 24h 作业）

**run_id**: 20261017T220451-mediahub-v12 | **分支**: feature/g5-429-record-only
**基线 head**: ff1be5307dd009acf8c12d36a3535ca503640b06

## 1. 本轮做了什么（V12 §06 要求：带测试的分路径接入，不得填全覆盖假象）

**只接入记录能力，不接入抑制能力**。

| 路径 | 接入物 | 抑制？ |
|---|---|---|
| Media3 ExoPlayer 取流（`PlayerFactory.mediaDataSourceFactory`） | `RateLimitRecordOnlyInterceptor`（network 层） | **否** |
| Coil 图片（`MediaHubApp.imageHttpClient`） | 同上 | **否** |
| API / media 链（`HttpClientFactory` 派生） | 原有 `RateLimitCooldownInterceptor` 双实例 | 是（既有，未改） |

`RateLimitRecordOnlyInterceptor` 的全部行为：拿到响应，若 `code == 429` 就
`ledger.recordStatus(429)`，然后原样 return。**不 throw、不 sleep、不重试、不改响应**。

## 2. 为什么不复用冷却器（关键取舍，已写进 ADR-046）

冷却器的语义是「窗口内**抛异常快速失败**」。

- 对 API 链正确：幂等、可重试、失败可提示用户；
- 对**播放**是错的：把可恢复的限流变成**播放硬中断**，用户看到「播放器坏了」；
- 对**图片**也是错的：列表页并发拉封面，快速失败会让**整屏封面集体消失**。

两者都比「多打几次被限流」更糟。所以本轮只解决**可见性**（未知 → 已知），退避留给上层策略（G5-b）。

## 3. 契约测试（4 例，全绿）

`core/network/src/test/kotlin/com/mediahub/core/network/RateLimitRecordOnlyInterceptorTest.kt`

| 用例 | 断言 |
|---|---|
| `a 429 is returned verbatim and recorded, never retried` | 429 原样返回；耗时 <3s（无 Retry-After 退避）；`server.requestCount == 1`（不重试）；`statusCounts[429] == 1` |
| `after a 429 the same origin is NOT suppressed — requests still egress` | 429 之后同源请求仍得 206；`requestCount == 2`；429 计数不清零 |
| `without a ledger the client behaves exactly as before` | 未注入台账 = 不插拦截器，逐字节等价 |
| `non 429 responses are not recorded as 429` | 200 不被误记为 429 |

第 2 例是与冷却器的**关键区别**：record-only 绝不抑制后续请求。

## 4. 回归

`:core:network` + `:player:engine` + `:app` `--rerun-tasks --no-build-cache --continue`
→ **BUILD SUCCESSFUL**（3m24s / 395 tasks），**54 类 / 369 tests / 0 failures / 0 errors / 0 skipped**。

| suite | tests | failures | 说明 |
|---|---|---|---|
| `RateLimitRecordOnlyInterceptorTest`（新增） | 4 | 0 | 本切片契约 |
| `MediaPath429EvidenceTest` | 2 | 0 | 上一轮固定的「无冷却」事实（**仍然成立**：现在有记录，但依然不抑制） |
| `ImagePath429EvidenceTest` | 2 | 0 | 同上，含对照组 |
| `RateLimitEgressBoundaryTest` | 3 | 0 | API 链冷却未被破坏 |

> 注意：`MediaPath429EvidenceTest` 断言「429 后仍全部出网」在接线后**依然是绿的** ——
> 因为 record-only 本就**不抑制**。这恰好证明接线没有改变行为。

## 5. ADR-046（已写入 DECISIONS.md）

标题：播放与图片路径的 429 只记录不抑制（record-only）。
含：背景（G5 证据）、决策、理由（三类路径的取舍）、契约、影响、已知局限、驳回方案。

## 6. 明确未做（不隐瞒）

- **未实现退避**：本切片不解决重试风暴，只让它可见。
  Media3 的 `LoadErrorHandlingPolicy` 按 `Retry-After` 退避、Coil 的 retry/backoff 配置，
  均属 G5-b 后续切片。
- **未接冷却器**：这是经过论证的**有意决定**，不是遗漏（见 ADR-046「驳回的方案」）。
- **未做真机验证**：本切片是 JVM 契约层，服务端限流行为真机不改变结论。

## 7. 结论

- 429 记录能力：**已接入两条生产路径**，`EXECUTED_LOCAL`（4 例契约 + 369 tests 全绿）
- 抑制能力：**有意不接入**，理由与取舍见 ADR-046
- 行为不变性：证据测试（「429 后全出网」）在接线后依然绿，证明无行为变化