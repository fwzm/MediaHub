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

## ADR-014 MediaProvider 能力组合（Interface Segregation）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：`MediaProvider` 只保留公共最小契约（serverId/type/displayName/descriptor/testConnection）；
  认证/媒体库/详情/浏览/播放/搜索/字幕/进度拆为**可选能力接口**，通过 `ProviderHandle`
  （可空字段，类型安全）组合暴露。Provider 只实现真实具备的能力。
- 背景：原 Fat Interface 迫使 LocalProvider 伪造认证、返回空 Season/Episode、堆 NotYetImplemented。
- 影响：LocalProvider 仅 BROWSE+DETAIL+PLAYBACK；UI 用 `handle.library != null` 等判断，禁止 `type == EMBY` 分支。

## ADR-015 ProviderDescriptor 动态注册（保留 ServerType 的迁移路径）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：Factory 自报 `ProviderDescriptor(id, serverType, displayName, category, capabilities, authMethod, status)`；
  "添加媒体库"页面从 `MediaProviderRegistry.descriptors()` 动态渲染，新增 Provider 无需改 UI。
- 持久化：**暂保留 ServerType 枚举**（Room 列不变，避免 schema 迁移）。
  迁移路径：未来引入自定义/第三方 Provider 时，`MediaServer` 增加 `providerId: String` 列
  （Room migration 1→2），以 `descriptor.id` 为持久化键。
- 语义约定：descriptor.capabilities = 类型规划能力（展示/路由）；ProviderHandle 字段 = 当前可用能力（运行时权威）。

## ADR-016 CredentialVault 凭据生命周期
- 状态：已采纳（2026-08-12，Phase 0.5，机制先行）
- 决策：`CredentialVault`（core:security，Keystore 加密）按 (serverId, kind) 存取长期凭据
  （密码/API Key/Refresh Token/Cookie/Client Secret）；`TokenStore` 只管会话令牌。
  策略：Emby/Jellyfin 登录后不存密码（Token 足够）；WebDAV/SMB 长期密码入 Vault；
  云盘 OAuth refresh/Cookie 入 Vault。禁止 Room/DataStore 明文、禁止日志。
- 现状：机制与测试就绪；AddServer 表单密码暂不落盘（无认证流），Phase 1 认证时接入 Vault。

## ADR-017 进度同步管线（三档节流）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：`PlaybackEngine` 每秒发 progress 流 + 关键事件流（Pause/Seek/Ended/Stopped）；
  `ProgressSyncCoordinator` 分流：本地快照 sample(5s)、远端上报 sample(Provider 间隔，默认 10s)、
  关键事件立即 flush、退出 final flush。Provider 可覆写 `MediaProgressProvider.remoteReportIntervalMs`。
- 背景：原实现每秒写库+上报（1 小时 ≈ 3600 次 DB 写 + 3600 次请求）。

## ADR-018 播放请求头 session-scoped（废弃 Singleton holder）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：`PlaybackHeadersHolder` 由 `PlaybackEngineFactory.create` 按引擎创建（每会话独立），
  不再全局 Singleton。`PlayerFactory.create(holder)` 构建 HeaderAwareDataSource 链。
- 背景：原 Singleton mutable holder 在多播放器/预加载/跨源切换时会串线（A 播 Emby、B 播夸克互相污染）。

## ADR-019 协议级连接测试（Provider 负责）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：`testConnection()` 由具体 Provider 实现协议嗅探：
  Emby/Jellyfin → `/System/Info/Public`（公开端点）；WebDAV → OPTIONS + DAV 头；Local → 目录检查。
  UI（AddServerViewModel）只调 `provider.testConnection()`，不再通用 GET `<500` 判定。
- 背景：原逻辑把 401/403/404 都算"连接成功"。

## ADR-020 SAF 演进预留
- 状态：已采纳（2026-08-12，Phase 0.5，接口预留）
- 决策：`LocalRootProvider.contentRoots()` 预留 content:// 树根；Phase 0.6 接入
  ACTION_OPEN_DOCUMENT_TREE + takePersistableUriPermission + DocumentFile 树导航，
  长期本地媒体以 content:// 为准，不围绕 file:// 扩展。

## ADR-021 保留 23 模块结构（不合并）
- 状态：已采纳（2026-08-12，Phase 0.5）
- 决策：维持现有模块划分。合并（如 feature:detail/search/metadata 归并）不带来可衡量的
  编译/维护收益，且已有独立语义边界；后续若出现"无独立编译价值"的模块再评估合并。

## ADR-022 ProviderHandle 运行时能力语义（0.5.1 修订）
- 状态：已采纳（2026-08-12，Phase 0.5.1）
- 决策：**Handle 只暴露"当前版本真正实现完成"的能力**；占位/未实现能力一律不放 Handle。
  `ProviderDescriptor.declaredCapabilities`（计划能力，展示用）与
  `ProviderHandle.runtimeCapabilities`（由字段推导，运行时权威）明确分开命名。
  约束：runtimeCapabilities ⊆ declaredCapabilities；feature 层永远不用通过异常发现"其实还没实现"。
- 影响：Phase 0.5 的 Emby/Jellyfin/WebDAV Handle 为空（仅 provider）；
  Local 保留 BROWSE/DETAIL/PLAYBACK。Phase 1 每实现一项能力，在 Factory 对应填一个字段。

## ADR-023 播放退出 final flush 显式状态机（0.5.1 修订）
- 状态：已采纳（2026-08-12，Phase 0.5.1）
- 决策：退出流程为显式状态机 `stopAndFlush()`（幂等）：
  停止 position 读取（engine.stop，emit Stopped）→ 生成最终进度 →
  local save + remote report（远端带 2s 短超时，不阻塞退出）→ stop 协调器 → release 播放器。
  ProgressSyncCoordinator 同时监听 Stopped 事件触发 flush；onCleared 为兜底路径。
- 背景：原实现先 stop 协调器再 emit Stopped（无人接收），且 DisposableEffect +
  viewModelScope cancellation 可能丢最后一次进度（Emby 会显示"已看到 93%"）。

## ADR-024 连接测试协议签名校验（0.5.1 修订）
- 状态：已采纳（2026-08-12，Phase 0.5.1）
- 决策：Emby/Jellyfin 的 /System/Info/Public 必须满足特征字段（Id/Version 非空）才算协议有效；
  仅 HTTP 200 + JSON 可解析不算。DTO 键名按真实协议（@SerialName("Id") 等）。
  分层语义：TCP reachable ≠ HTTP reachable ≠ Emby reachable ≠ Emby authenticated。
- 验证：MockWebServer 覆盖 正确响应 / 200-错误JSON / 404 / 401 / 403 / malformed JSON。
