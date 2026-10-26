# ADR-W003 — 与 Android 共享领域契约的方式

- 状态：**已接受**（2026-10-14）

## 问题

Android 与 Windows 必须是同一产品语义。是否物理复用 Kotlin 代码？是否引入共享运行时？

## 决策

**共享格式、行为契约与协议适配规范；不物理复用源码；不引入跨平台运行时。**

理由：

1. Kotlin Multiplatform / .NET 互操作会同时给两个平台引入新的构建链与运行时依赖，
   违反"不得因增加 Windows 支持而使 Android 主项目依赖 Windows 桌面运行时"以及反向的对称约束。
2. Android 侧的价值集中在**领域语义与协议裁定**，而不在语法。这些语义可以逐条落成
   Windows 端类型与测试，且比跨语言桥接更容易审查。
3. Android 已验证的**红线**必须逐条继承，并以 Windows 端回归测试固定（见下表）。

## 契约映射

| 契约 | Android | Windows | 继承方式 |
| --- | --- | --- | --- |
| SourceRegistry | Hilt `@IntoSet` + `DefaultProviderRegistry` | `ProviderRegistry` | 同为"按类型注册、UI 从描述符渲染" |
| ProviderCapability | `ProviderCapability` 枚举 + 声明/运行时二分（ADR-022） | 同名 + `RuntimeCapabilities` 由接口存在性推导 | 运行时能力不可声明式伪造 |
| MediaCatalog | `core:model` 的 `MediaItem` 等 | `MediaHub.Domain` | 字段级对齐；未知值保持 null |
| UnifiedSearch | 1C-2 查询管道 + 1E 聚合 | `IProviderSearch`（聚合编排在 W3） | 分页语义一致 |
| PlaybackPlan | `PlaybackCompatibilityEvaluator` + `resolvePlayback` | `PlaybackPlan` + `IProviderPlayback` | 三态 `DirectPlay/Stream/Transcode` |
| PlaybackController | `PlaybackEnginePort` | `IPlaybackEngine` | 状态由引擎回读，UI 不做乐观更新 |
| TrackController | `TrackMapper`/`TrackSelection` | `ITrackController` | 轨道身份用**引擎 track id**，不用服务端 index |
| ProgressSync | ADR-017 三档节流 | `ProgressSyncPolicy` + `IProviderProgress` | 5s / 10s / 立即 flush |
| NetworkPolicy | `HttpClientFactory` + `OriginScopedCredentialInterceptor` | `NetworkPolicy` + `OriginScopedCredentialHandler` | 见下 |
| SettingsContract | DataStore 偏好 | `MediaHubSettings`（JSON） | 同一分组语义 |

## 逐条继承的红线（每条都有 Windows 回归测试）

1. **ADR-003**：播放 URL 播放时解析，绝不落库。Windows 端 `PlaybackRequest` 只携带资源标识，
   历史记录只持久化路径 + 位置（`PlaybackHistoryStore`）。
2. **ADR-026**：Token 只走请求头，禁止进 URL。`MediaBrowserApi.DirectStreamUrl` 构造的直链
   不含任何 token；`MediaBrowserDialectTests` 固定该形状。
3. **ADR-030**：跨 origin 重定向剥离凭据。Windows 端**显式跟随重定向**（`AllowAutoRedirect=false`），
   因为把安全属性建立在 BCL 未承诺的行为上不可接受，且 BCL 不覆盖自定义 token 头。
4. **ADR-040**：进度上报 `PositionTicks` 恒发（含 0），`PlaySessionId` 缺失即拒发
   （不做任何伪造回退）。
5. **Blu-ray ISO 跳过**：Emby 把 `.iso` 标成 `Container=mpegts`，必须按路径判定。
6. **季/集浏览复用条目查询**：不实现专用 `getSeasons`/`getEpisodes` 端点。
7. **cause 消毒**：Provider 异常只保留原异常**类型名**，不保留消息（消息常内嵌带凭据的 URL）。

## 有意偏离 Android 的地方

| 项 | Android | Windows | 原因 |
| --- | --- | --- | --- |
| 重定向跟随方 | OkHttp 原生跟随 + network interceptor | 自行跟随 | 需要在每一跳做 origin 判定；BCL 不做自定义头剥离 |
| 指针活动 | Compose 指针事件 | 轮询光标 | 视频区域消息被子窗口吞掉（ADR-W002） |
| 资源生命周期 | ViewModel + Hilt 作用域 | 显式组合根 `AppServices` | 对象图小，显式生命周期更易审查 |

## 后果

- 领域层（`MediaHub.Domain`）无任何 Windows/UI 依赖，可被 Android 语义逐条比对。
- 表示层可替换（含回退到 WPF，见 ADR-W001）而不影响协议与安全语义。
- 代价：两端各自维护一份实现。以"每条红线都有对应回归测试"作为一致性保障。
