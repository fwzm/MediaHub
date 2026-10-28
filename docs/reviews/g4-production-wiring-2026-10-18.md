# G4 · M16 观测台账的生产接线（V12 24h 作业）

**run_id**: 20261017T220451-mediahub-v12 | **分支**: feature/g4-observability-production-wiring
**基线 head**: 61876266c933c6576822412dcdb3277e30b9510b

## 1. 问题（真实缺口，非推测）

V12 §05.1 要求：「全仓搜索实际创建路径：如果 ledger 默认仍 null，补 production 注入；仅有 optional 参数不算接线。」

核验结果——两处生产路径都只有可选参数、从未传值：

| 位置 | 现象 | 后果 |
|---|---|---|
| AppModule.providePlayerFactory (AppModule.kt:217) | PlayerFactory(context, cache, ua, serverIdFor) —— 未传 ledger | PlayerFactory.mediaDataSourceFactory 里 ledger?.recordUnobservable(MEDIA) 恒为 null 短路 → Media3 取流永远不会被登记为不可观测 |
| MpvPlaybackEngine 次构造器 | createMpvBridge(httpClientFactory) —— 未传 ledger | mpv 的 recordLoopbackInbound / recordLocalFileRead 恒不触发 → 回环接入与本地读取永远记为 0 |

这正是 AGENTS.md §7.4 警告的「伪造 0」：台账会把真实出网表述成零交换。

## 2. 修复

### 2.1 mpv 链路（player/mpv）

- MpvPlaybackEngine 次构造器新增 ledger: NetworkExchangeLedger? = null，并在桥工厂里真正传下去：createMpvBridge(httpClientFactory, ledger)。
- MpvPlaybackEngineCreator 新增 ledger 参数并转发给引擎。

### 2.2 Media3 链路（app/di/AppModule.kt）

- providePlayerFactory 新增 networkLedger: NetworkExchangeLedger 形参，构造时 PlayerFactory(..., ledger = networkLedger)。
- provideMpvEngineCreator 新增同一形参，转发给 MpvPlaybackEngineCreator。

两处都只有唯一生产构造点（已用全仓 grep 核验：PlayerFactory( 仅 PlayerFactory.kt 定义 + AppModule:221；MpvPlaybackEngineCreator( 仅定义 + AppModule:254），因此注入后就没有旁路。

## 3. 先红后绿证据

测试：app/src/test/kotlin/com/mediahub/app/di/ObservabilityProductionWiringTest.kt（3 例，反射检查真实产物字段）

| 用例 | 断言 |
|---|---|
| production PlayerFactory is constructed with a real ledger | 反射读取 PlayerFactory 的 ledger 字段，必须非 null 且同一实例 |
| AppModule provides PlayerFactory already wired to the injected ledger | 端到端：调用生产的 AppModule.providePlayerFactory(...) 本身，验证产物携带注入的 ledger |
| mpv engine creator accepts a ledger so the bridge can observe | MpvPlaybackEngineCreator 构造签名必须含 NetworkExchangeLedger 参数 |

RED（修复前）：

```
tests=2 failures=1
FAIL: mpv engine creator accepts a ledger so the bridge can observe
  java.lang.AssertionError: MpvPlaybackEngineCreator 必须接受 ledger 参数，否则生产 mpv 路径永远观测不到回环/本地读取
  （现有构造参数：Context, Logger, HttpClientFactory, DeviceHdrEvidenceProvider）
```

注意：另一个用例「PlayerFactory 携带 ledger」在 RED 阶段是通过的 —— 因为 PlayerFactory 的构造器早就接受 ledger，缺的是调用方没传。
这正是为什么必须加端到端 DI 用例：只测构造器会漏掉真正的缺口。

GREEN（修复后）：

```
tests=3 failures=0 errors=0
  - mpv engine creator accepts a ledger so the bridge can observe
  - AppModule provides PlayerFactory already wired to the injected ledger
  - production PlayerFactory is constructed with a real ledger
```

## 4. 回归

:app + :player:mpv + :player:engine + :core:network 加 --rerun-tasks --no-build-cache --continue
→ BUILD SUCCESSFUL（3m38s / 400 tasks），64 类 / 449 tests / 0 failures / 0 errors / 0 skipped。

关键观测 suite 全绿：

| suite | tests | failures |
|---|---|---|
| ObservabilityProductionWiringTest（新增） | 3 | 0 |
| Media3ObservabilityBoundaryTest | 3 | 0 |
| ImageObservabilityBoundaryTest | 3 | 0 |
| BridgeObservationWiringTest | 5 | 0 |
| MpvBridgeObservationInjectionTest | 3 | 0 |

## 5. ancestry 核验（V12 §05.1 第一项）

```
git merge-base --is-ancestor 86ba9e1 HEAD   -> exit 0   # M16-S1 在
git merge-base --is-ancestor 4910065 HEAD   -> exit 0   # G4 在
git merge-base --is-ancestor be5b265 HEAD   -> exit 0   # M16-S0 在
```

即 #44（M16-S0+S1）与 G4 的提交全部在当前联合候选 6187626 内，无血缘缺口。

## 6. 口径确认（V12 §05.2）

| 通道 | 生产上报点 | 语义 |
|---|---|---|
| 回环接入 | MpvHttpBridge.kt:184 recordLoopbackInbound() | mpv 连到本桥 = 一次接入 |
| 本地文件读取 | MpvHttpBridge.kt:254 recordLocalFileRead() | 桥的 local bytes 源 |
| Media3 取流 | PlayerFactory.kt:118 recordUnobservable(MEDIA) | 不可观测（不是零交换） |
| Coil 图片 | MediaHubApp.kt:91 recordUnobservable(IMAGE) | 同上 |
| 桥上游出网 | 经 HttpClientFactory 派生链逐交换记账 | 真实出网 |

HTTP 重定向每一跳由 network interceptor 记账；入口冷却快速失败不产生 exchange；HEAD/206/失败/取消不双计
（既有 NetworkExchangeObservationTest 13 例覆盖，本轮未改动该逻辑）。

## 7. 结论

- 生产接线缺口：真实存在，已修复（EXECUTED_LOCAL，先红后绿，449 tests 全绿）
- ancestry：#44 / G4 全部在 6187626 内
- 隐私：未新增任何 URL/token/账号字段；台账接口未改
- 遗留：本切片未为「生产 DI 真的调用 recordUnobservable」加运行时端到端（需要真实起播并读台账快照），
  属后续切片；当前证据止于「装配产物确实持有同一个 ledger 实例」。