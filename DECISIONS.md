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
- 状态：已采纳（2026-08-12），Phase 0.5 修订
- 决策：各 Provider 模块 `@Binds @IntoSet` 自注册；`DefaultProviderRegistry` 内部
  按开放的 `ProviderDescriptor.providerId:String` 建索引，重复 ID 启动即失败。
- 背景：Hilt 2.52 + KSP（2.0.21-1.0.25/1.0.28，KSP1/KSP2）下 `@IntoMap` + 自定义
  `@MapKey` 报 `error.NonExistentClass`（无法解析注解），改用 @IntoSet 后通过。
- 影响：新增数据源 = 新增 Factory + Descriptor + @IntoSet 绑定；不再修改封闭类型枚举或添加页。

## ADR-006 敏感信息走 Android Keystore，日志强制脱敏
- 状态：已采纳（2026-08-12），Phase 0.5 修订
- 决策：SecretStorage（AES/GCM, AndroidKeyStore）封装加密字节；Provider 层的 `CredentialVault`
  统一保存 pending credential 与 session credential；Logger 实现统一 Redactor。
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
- 状态：已采纳（2026-08-12），Phase 0.5 修订
- 决策：Media3 1.5.1 的 MediaItem 无 headers 字段；使用
  `HeaderAwareDataSource` 在 open() 时调用 `DataSpec.withAdditionalHeaders`。每个 MediaSource 捕获独立、
  不可变的 `PlaybackRequestContext`；禁止 Singleton mutable holder。
- 影响：鉴权头/Cookie 仅在对应播放资源的内存上下文中，多个播放器/预加载不会串线。

## ADR-014 Provider 使用最小公共接口 + 可选能力组合
- 状态：已采纳（2026-08-12）
- 决策：`MediaProvider` 不再继承所有能力。Factory 返回 `ProviderHandle`，认证/Library/Browse/Playback/
  Search/Subtitle/Progress 以可空、类型化字段组合，并校验 Descriptor 与实现一致。
- 理由：Local/WebDAV/云盘不应伪造自己没有的能力；避免 empty list 与 NotYetImplemented 污染公共契约。
- 影响：业务层按能力编程，不按 Provider 类型编程；新增能力需同时更新 Handle 校验与契约测试。

## ADR-015 持久化稳定 providerId，移除 ServerType
- 状态：已采纳（2026-08-12）
- 决策：领域模型与 Registry 使用受格式约束的 `providerId:String`。Room v1 暂不改列结构，历史 `type`
  列从此存 providerId；读取时 Mapper 将旧枚举名转换为稳定 ID。
- 理由：封闭 enum 会迫使每个新数据源修改中央 model 与 UI，无法实现 Factory 自描述注册。
- 迁移路径：未来首次必要的 Room schema 迁移时把列名正式改为 `provider_id`；在此之前兼容读取旧值。

## ADR-016 CredentialVault 与认证协调器管理凭据生命周期
- 状态：已采纳（2026-08-12）
- 决策：短期输入使用 `Credentials`；登录结果使用 `SessionCredential`；`AuthenticationCoordinator`
  负责调用 Provider、写入加密 Vault、删除原始 pending 凭据与回滚。Room/DataStore 不持有 Secret。
- 影响：Emby/Jellyfin 登录应把密码换 Token 后删除；Basic/OAuth/Refresh/Cookie 可按协议保存长期凭据。
  Phase 0.5 对尚未实现登录的 Provider 仅加密暂存，V0.1 成功登录后必须清除。

## ADR-017 播放 HTTP 上下文按资源/会话隔离
- 状态：已采纳（2026-08-12）
- 决策：Header/Cookie/Referer/Authorization 从 `PlaybackSource` 复制进每个 MediaSource 的不可变
  `PlaybackRequestContext`。播放器工厂可以是 Singleton，但工厂不保存播放请求状态。
- 影响：支持未来双播放器、预加载、字幕和多 CDN，而不会跨源污染请求头。

## ADR-018 播放位置与持久化/远端进度分流
- 状态：已采纳（2026-08-12）
- 决策：UI 位置 500ms 仅内存更新；本地快照默认 5s；远端周期由 Provider policy 决定；
  Play/Pause/Seek/Stop/End 立即同步；release 做 final flush。流使用 conflate/backpressure。
- 影响：消除每秒数据库写入、远端请求与 coroutine 创建；Provider 协议可独立选择节流策略。

## ADR-019 本地媒体统一使用 Android SAF
- 状态：已采纳（2026-08-12）
- 决策：目录授权使用 `ACTION_OPEN_DOCUMENT_TREE` + persistable URI permission，浏览使用 DocumentFile/
  ContentResolver，播放使用 `content://`。
- 影响：不继续扩展 app 私有目录和 `file://`；支持 U 盘及系统 DocumentProvider。数据库只保存 tree URI。

## 技术备忘（非决策）
- androidx lint `UnsafeOptInUsageError` 只识别"使用点"级 @OptIn，比编译器严格；
  player:engine 在类级 @OptIn（编译器强制）基础上于该模块 lint 配置中关闭此检查。
- 沙箱（Linux aarch64）无 aapt2 arm64 二进制（AGP 官方不支持），用 qemu 包装器验证；
  gradle.properties 中该配置保持注释（见 HANDOFF.md）。
