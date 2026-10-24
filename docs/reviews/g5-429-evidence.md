# G5 · 无冷却出网路径的 429 行为证据（2026-10-18）

## 结论

两条生产出网路径确认没有 429 冷却，且已用可重复执行的测试固定住当前行为：

| 路径 | 生产入口 | 429 后同源后续请求 | 证据级别 |
|---|---|---|---|
| Media3 ExoPlayer 取流（实际播放路径） | PlayerFactory.mediaDataSourceFactory | 全部真实出网（无抑制、无退避） | EXECUTED_PASS（测试绿 = 缺陷行为被固定） |
| Coil 图片加载（列表页并发封面） | MediaHubApp.imageHttpClient | 全部真实出网（无抑制） | EXECUTED_PASS（同上） |
| 对照组：API 链（已具备冷却） | HttpClientFactory.apiClient | 零出网（连接前快速失败） | EXECUTED_PASS（证明测试有效） |

对照组是必要的：没有它，「没有冷却」与「测试根本没生效」无法区分。

## 证据测试

- player/engine/src/test/kotlin/com/mediahub/player/engine/MediaPath429EvidenceTest.kt（2 例）
  - after a 206 range response a same origin 429 surfaces and the next request still egresses
  - every range request after a 429 goes out immediately with no per origin suppression
- app/src/test/kotlin/com/mediahub/app/image/ImagePath429EvidenceTest.kt（2 例）
  - production image client keeps hammering the same origin after a 429
  - control — the cooldown equipped api chain does fast fail after a 429

两处测试都驱动生产链本身（不是替身链）：

- Media3 侧：PlayerFactory.mediaDataSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor())，与 create() 装入 ExoPlayer 的是同一条链；
- Coil 侧：MediaHubApp.imageHttpClient(...)，即 newImageLoader() 中 callFactory 用的同一 client。

两者都显式传入可解析的 serverId（ServerIdResolver 返回 srv-g5），因此「429 后仍出网」不能被解释成「解析不到 serverId 所以没进冷却」。

## 复现命令

    $env:JAVA_HOME='C:\Program Files\Java\jdk-21'
    .\gradlew.bat :player:engine:testDebugUnitTest --tests 'com.mediahub.player.engine.MediaPath429EvidenceTest' --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain
    .\gradlew.bat :app:testDebugUnitTest --tests 'com.mediahub.app.image.ImagePath429EvidenceTest' --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1 --console=plain

实测：:player:engine BUILD SUCCESSFUL（2 tests / 0 failures / 5.4s）；:app BUILD SUCCESSFUL（2 tests / 0 failures / 0.4s）。

## 为什么这是风险

1. 播放路径：长视频靠连续 Range 请求续流。一旦服务器对同源开始 429，当前实现把 429 直接变成播放错误 —— 没有任何退避/重试，用户在播放中途被中断，且客户端不会自我降速，紧接着的下一段仍立即出网，加剧服务端限流。
2. 图片路径：列表页并发拉取大量封面，没有冷却意味着 429 之后每张图继续出网、继续被拒，形成重试风暴的温床（尤其弱网 + 服务器已限流时）。
3. 现有 M15 冷却表只覆盖 HttpClientFactory 派生链（API / 媒体 API），而 Media3 / Coil 各自 new 了 OkHttpClient，天然不在这张表上。

## 策略选项（本轮不强行接线，供决策）

| 选项 | 做法 | 代价 | 风险 |
|---|---|---|---|
| A. 共享同一张冷却表 | 把 RateLimitCooldown 实例注入 Media3 / Coil 的 client，只在 network 层记账，不做应用层快速失败 | 中：两条链各加一个 interceptor + 构造参数 | 播放路径若被快速失败会硬中断播放，故只能记账 + 退避，不能直接抛 |
| B. 只记账不抑制 | Media3/Coil 侧只 record429，把状态暴露给上层决定（切源、提示） | 低 | 不解决重试风暴，只提供可见性 |
| C. 播放专用退避 | 仅 Media3：429 时按 Retry-After 在上层 LoadErrorHandlingPolicy 退避重试 | 中 | 依赖 Media3 的错误处理钩子，需验证 OkHttpDataSource 是否把 429 转成可重试错误 |
| D. 维持现状 + 记录风险 | 不改代码，把风险写进 PR 与 AGENTS.md | 零 | 风险已知但未缓解 |

推荐路径：先做 B（低成本、零播放风险，补上可见性），再评估 C（真正的用户价值在于「播放不被中断」）；A 需谨慎 —— 把快速失败引入播放路径会直接把可恢复的限流变成播放中断。

## 未做（明确声明）

- 未修改任何生产代码；未改动冷却表；未接入上述任何选项。
- 未做真机验证（本切片是 JVM 证据；服务器限流是服务端行为，真机不改变结论）。
- 未评估 mpv 路径（MpvHttpBridge 上游是本地回环，其上游 client 属于 HttpClientFactory 派生链，已覆盖）。
