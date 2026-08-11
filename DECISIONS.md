# 技术决策记录（DECISIONS / ADR）

> 新增/修改决策必须追加并注明日期与理由。以代码为准。

## ADR-001 播放器基于 AndroidX Media3 / ExoPlayer
- 状态：已采纳（2026-08-12）
- 决策：播放核心使用 Media3 + ExoPlayer，扩展层（FFmpeg/mpv）留接口不预建。
- 理由：官方维护、Android 原生性能、Compose 集成成熟。
- 影响：player:engine 为唯一播放入口；禁止在 MVP 阶段重写为 MPV。

## ADR-002 Provider 统一 Domain Model，UI 不感知数据源
- 状态：已采纳（2026-08-12）
- 决策：Remote Model → Mapper → Domain Model（core:model）；UI 只依赖 MediaProvider 接口。
- 影响：禁止 UI 出现 `if (type == EMBY)`；能力差异用 `ProviderCapability` 表达。

## ADR-003 云盘/媒体播放 URL 播放时动态解析，绝不落库
- 状态：已采纳（2026-08-12）
- 决策：持久化资源标识（itemId/path）；播放时 `resolvePlayback()` 生成临时
  `PlaybackSource`（含过期时间），过期自动重解析。
- 影响：Room 中不存在媒体 URL；播放 URL 仅内存/会话内。

## ADR-004 播放模式三态与兼容性评估器
- 状态：已采纳（2026-08-12）
- 决策：`PlaybackMode`（DIRECT_PLAY/DIRECT_STREAM/TRANSCODE/UNSUPPORTED）统一语义；
  `PlaybackCompatibilityEvaluator`（纯函数）输出决策与原因，禁止散落 `if codec == xx` 链。
- 影响：评估器可单测（已覆盖 9 用例）；服务端转码只在必要时请求。

## ADR-005 Provider 注册采用 Hilt @IntoSet（非 @IntoMap）
- 状态：已采纳（2026-08-12），**修订记录见下**
- 决策：各 Provider 模块 `@Binds @IntoSet` 自注册；`DefaultProviderRegistry` 内部
  按 `ServerType` 建索引（`associateBy { it.serverType }`）。
- 背景：Hilt 2.52 + KSP（2.0.21-1.0.25/1.0.28，KSP1/KSP2）下 `@IntoMap` + 自定义
  `@MapKey` 报 `error.NonExistentClass`（无法解析注解），改用 @IntoSet 后通过。
- 影响：新增数据源 = 新增 Factory + @IntoSet 绑定；键类型仍为 ServerType（类型安全）。

## ADR-006 敏感信息走 Android Keystore，日志强制脱敏
- 状态：已采纳（2026-08-12）
- 决策：SecretStorage（AES/GCM, AndroidKeyStore）封装 Token/Cookie；Logger 实现统一 Redactor。
- 影响：任何业务不得绕过 SecretStorage 明文落盘；日志禁止出现 Token/Cookie/密码。

## ADR-007 Gradle 版本目录 + 多模块（core/player/provider/metadata/feature）
- 状态：已采纳（2026-08-12）
- 决策：libs.versions.toml 统一版本；模块按依赖方向分层。
- 影响：版本升级只改一处；模块边界即架构边界。

## ADR-008 基线：minSdk 26 / targetSdk 35 / Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.9
- 状态：已采纳（2026-08-12）
- 理由：minSdk 26 覆盖主流设备且不拖累架构；版本组合为已验证稳定矩阵。

## ADR-009 缓存分离：Room(元数据) / Coil(图片) / SimpleCache(播放) / DownloadManager(离线)
- 状态：已采纳（2026-08-12）
- 决策：各缓存独立生命周期与清理策略，禁止混用。
- 影响：播放缓存 512MB LRU（player:engine MediaCacheProvider）。

## ADR-010 错误分类：ApiException / ProviderException / PlaybackError
- 状态：已采纳（2026-08-12）
- 决策：分层结构化错误；UI 显示用户可读文案，日志保留诊断（脱敏）。
- 影响：播放错误含 URL_EXPIRED/HTTP_403/DECODER_ERROR 等可编程 code，便于重试策略。

## ADR-011 元数据（刮削）与存储解耦
- 状态：已采纳（2026-08-12）
- 决策："文件在哪"（StorageProvider）与"这是什么"（MetadataProvider）分离；
  metadata 模块仅接口，实现（TMDB 等）后续接入。

## ADR-012 网络分层：ApiClient 与 MediaHttpClient 分离
- 状态：已采纳（2026-08-12）
- 决策：API 请求（短超时/幂等重试）与媒体流（Range/302/Cookie/长连接、无读超时）不同客户端。
- 影响：媒体播放不被 API 超时策略误伤；连接池复用。

## ADR-013 Media3 请求头注入采用 DataSource 包装层
- 状态：已采纳（2026-08-12）
- 决策：Media3 1.5.1 的 MediaItem 无 headers 字段；使用
  `HeaderAwareDataSource`（open() 时 `DataSpec.withAdditionalHeaders`）+ `PlaybackHeadersHolder`。
- 影响：每次播放的鉴权头/Cookie 仅内存注入，不落库不进日志。

## 技术备忘（非决策）
- androidx lint `UnsafeOptInUsageError` 只识别"使用点"级 @OptIn，比编译器严格；
  player:engine 在类级 @OptIn（编译器强制）基础上于该模块 lint 配置中关闭此检查。
- 沙箱（Linux aarch64）无 aapt2 arm64 二进制（AGP 官方不支持），用 qemu 包装器验证；
  gradle.properties 中该配置保持注释（见 HANDOFF.md）。
