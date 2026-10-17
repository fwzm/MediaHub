# G5 · Media3 / Coil 的 429 策略：证据与结论

日期：2026-10-18 ｜ 基线：feature/m16-media3-coil-observability
测试：player/engine/.../Media3RateLimitStrategyTest.kt（3/3 PASS，Robolectric + MockWebServer，真实生产链）

## 0. 问题

M15-S4-A 已确认：Media3 取流与 Coil 取图两条生产出网路径没有 429 冷却。
直接给它们装上 RateLimitCooldownInterceptor 看似「补齐覆盖」，但风险未评估：
长视频流若被一刀切冷却，会打断正在播放的会话。

## 1. 实测事实（三条，全部来自真实生产链）

### 事实 1：当前 429 不会阻止后续 Range 出网

对同一 URL 连续两次请求，服务端均返回 429：

| 轮次 | server.requestCount | 结果 |
|---|---|---|
| 第一次 | 1 | 失败（429） |
| 第二次 | 2 | 失败（429） |

→ 当前 Media3 路径完全没有限流保护，429 只是一次普通失败，下一次照常出网。

### 事实 2：重复读取同一 URL 命中 CacheDataSource，不产生新网络请求

第一次成功读取后，对同一 URL 再读一次：server.requestCount 保持不变，
读回的字节数与首次一致。

→ 播放器侧已有一层缓存保护（CacheDataSource + mediaCacheProvider）。
这是评估「是否要加冷却」时不能忽略的既有防线：并非每个 Range 请求都会打到服务器。

### 事实 3（反例）：朴素接线会让合法的后续 Range 被直接拒绝且零出网

用与生产同构的 OkHttp 组装（应用层 + network 层双冷却实例）复现：

```
先一个 429 -> 建立冷却 -> 随后的合法 Range(bytes=100-200) 被快速拒绝
server.requestCount 停在 1（连恢复的机会都没有）
```

→ 证明「直接把冷却拦器装到 Media3 链上」会打断长流。这不是理论风险，是实测结果。

## 2. 结论：不接线（本期）

理由：

1. 朴素接线已实测会打断合法 Range（事实 3）；
2. 播放器已有 CacheDataSource 层保护（事实 2），全量冷却的边际收益低于其回归风险；
3. V11 04-B/06 明确要求「若接入会显著增加播放回归风险，提供带确定性测试的延期 ADR，而不是使用不可靠的默认拦截器」。

因此本项状态：NOT_COVERED + 已评估 + 明确延期，不是遗漏。

## 3. 若将来要接线，必须先满足的最小契约

| 要求 | 为什么 |
|---|---|
| 新请求快速失败；已建立的长连接不被追杀 | 否则长视频被后台冷却打断 |
| 不放大并发重试 | 否则 429 会变成请求风暴 |
| 取消不得触发重试 | 否则用户 seek 会放大成多倍请求 |
| 按真实 origin / serverId 归属 | 避免同 host 多服务器互相误伤 |
| 有确定性测试证明上述各条 | 不接受「看起来没问题」 |

建议实现形态：在 Media3 的 DataSource 层（而非 OkHttp 拦截器层）做请求发起前的判断，
并对已 open() 的流不做任何冷却干预。

## 4. Coil 侧

Coil 与 Media3 同属「不在 HttpClientFactory 观测/冷却链上」（已由 M15-S4-A 确认）。
图片 429 的合理策略是独立预算 + 轻量快失败 + 只观测，
不得把缩略图 429 升级为播放禁令。本轮未实现（同属延期范围）。

## 5. 证据可复现

```
cd D:\deepseek_test\mh-g4-obs
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat :player:engine:testDebugUnitTest --tests com.mediahub.player.engine.Media3RateLimitStrategyTest --rerun-tasks --no-build-cache --no-daemon
# 期望：3 tests / 0 failures
```