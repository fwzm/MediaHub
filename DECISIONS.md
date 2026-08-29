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

## ADR-025 客户端身份（ClientIdentity，跨协议复用）
- 状态：已采纳（2026-08-12，Phase 1A）
- 决策：`core:common` 提供 `ClientIdentity`（client/device/deviceId/version）与
  `ClientIdentityProvider`（DeviceId 首次生成 UUID 持久化，后续启动一致，不用硬件序列号）。
  Emby/Jellyfin 等协议共用，禁止在各 Provider 复制。
- 影响：Emby authorization header 由 `EmbyAuthorizationHeaderBuilder` 统一构建。

## ADR-026 Emby 认证与会话（Phase 1A）
- 状态：已采纳（2026-08-12，Phase 1A）
- 决策：
  - 登录 POST /Users/AuthenticateByName（JSON Username/Pw + X-Emby-Authorization 客户端身份头）；
    成功响应严格校验 AccessToken/ServerId/User.Id 非空，缺失不保存半成品。
  - **ServerId 区分**：localServerId（MediaHub MediaServer.id，TokenStore 键）≠ remoteServerId
    （Emby 返回），会话同时记录两者，恢复时校验服务器身份。
  - 已认证请求统一由 EmbyApiClient 加 `X-Emby-Token`（禁 URL query、禁各端点手拼 Header）。
  - 密码：仅存在于登录请求内存，绝不持久化/日志（沿用 ADR-016）。
  - 会话恢复：本地 Token+Session 双份齐全才发真实验证（GET /Users/Me）；
    401/403 → 清会话；Timeout/DNS/5xx → 保留会话（网络问题 ≠ token 失效）。
  - Logout：POST /Sessions/Logout 为 best-effort，本地清理为权威。
  - 状态机 EmbyAuthState（Unknown/SignedOut/Restoring/Authenticating/Authenticated/Error），
    禁止 Boolean isLoggedIn 承载全部状态。
- 影响：Emby Handle 只开放 AUTH（runtimeCapabilities={AUTH}，ADR-022）。

## ADR-027 Emby Provider 内部模块拆分（防巨型类）
- 状态：已采纳（2026-08-12，Phase 1A）
- 决策：provider:emby 按能力拆分包：api（EmbyApiClient/DTO/HeaderBuilder）、
  auth（EmbyAuthProvider/EmbyAuthState）、session（EmbySession/EmbySessionStore）、
  mapper（EmbyUserMapper）。EmbyProvider 只承载身份/descriptor/testConnection。
  后续 Library/Playback/Progress 各自独立类，Factory 每完成一项填一个 Handle 字段。

## ADR-028 Phase 1A finalization（会话恢复契约 / 串服防护 / 官方协议对齐）
- 状态：已采纳（2026-08-12）
- 决策：
  - `MediaAuthProvider` 新增通用 `restoreSession(): AuthSessionState` 契约（Unknown/Restoring/
    SignedOut/Authenticated/Error），App 启动/服务器列表变化时由 Home 自动恢复登录；
    UI 展示登录态并提供 Logout 入口（AuthSessionState 驱动）。
  - **防 Token 串服**：恢复/登出前先**无 Token** GET /System/Info/Public，校验
    session.remoteServerId 与当前服务器一致才发送 X-Emby-Token；不一致 → SERVER_MISMATCH
    （绝不向错误服务器发 Token）。
  - **失效策略**：仅 401 清会话（Token 已撤销）；403（FORBIDDEN）、5xx、超时/DNS、
    malformed 响应（INVALID_RESPONSE）一律保留本地会话。
  - **官方协议对齐**：API root 统一 `/emby` 前缀（EmbyEndpointResolver，用户已输 /emby 不重复）；
    X-Emby-Authorization 改为官方 schema `Emby UserId="...", Client="...", Device="...", DeviceId="...", Version="..."`
    （Token 始终走 X-Emby-Token）。
  - 密码输入框遮罩（PasswordVisualTransformation），文案"仅用于本次登录，不保存在设备中"。
- 影响：EmbyAuthProvider 的 EmbyAuthState 由通用 AuthSessionState 取代（删除）；测试覆盖
  403/malformed preserve、SERVER_MISMATCH 不发 Token、logout 身份校验。

## ADR-029 PR #1 × main 合流决策（2026-08-12）
- 状态：已采纳（经用户确认）
- 决策：**以 main 为主线**，不采用 PR #1 的 CredentialVault/AuthenticationCoordinator 作为
  认证主线（保留 main 已绿灯的 TokenStore + EmbySessionStore + 通用 restoreSession）。
  从 PR #1 汲取非破坏性的实质性 review 修复（Channel 事件、Back、MediaTypeGuesser、
  SAF tree-backed 导航基础设施）。
- 理由：PR #1 与 main 分叉（merge base 3a8d530，各自有独立提交），其认证基础设施是
  与 main 并行的一套；以 main 为主线不回归已验收的 Phase 1A。SAF 完整落地留待 Phase 0.6。
- 影响：PR #1 标记为 not-merge（架构分叉不再继续开发）；后续新功能base在 main。

## ADR-030 播放重定向凭据隔离（跨 origin 剥离鉴权头）
- 状态：已采纳（2026-08-22，Phase 1B-2.2）
- 背景：Emby Direct Stream 真实链路为 `HTTPS(Emby) → 307 → HTTP 直链 IP → 对象存储`，
  0.6.2 为跟随跨协议重定向启用了 DefaultHttpDataSource 的手动 redirect 循环。
  审查发现（并用双 MockWebServer 回归测试确定性复现）：media3 1.5.1 会把
  `DataSpec.httpRequestHeaders`（含 X-Emby-Token / X-Emby-Authorization）原样
  重复发送给**每一跳** redirect 目标，无任何按 origin 的剥离——长期凭据跨 origin、
  且经明文 HTTP 泄漏给直链/对象存储主机（P1）。
- 决策：
  - 播放 HTTP 栈从 DefaultHttpDataSource 切换为 media3 `OkHttpDataSource`（redirect 由
    OkHttp 原生跟随，跨协议不受限，仍受平台 cleartext 策略约束，业务行为与 0.6.2 等价）；
  - 新增 `OriginScopedCredentialInterceptor`（OkHttp **network** interceptor，每跳生效）：
    请求 origin（scheme+host+port）与本次播放首个请求的 origin 不同时，剥离
    X-Emby-Token / X-MediaBrowser-Token / X-Emby-Authorization / X-MediaBrowser-Authorization /
    Authorization / Proxy-Authorization / Cookie；origin 基准取 `chain.call().request()`
    （ExoPlayer 每次起播/seek 都以原始 Direct Stream URL 发起新 call）；
  - Range / User-Agent 等安全媒体头继续透传；RequiredHttpHeaders 是服务端显式指定给
    目标媒体源的请求头，按原样透传（更细的作用域设计留待有真实样本后处理）。
- 红线不变：Token 不进 URL（ADR-026）；本 ADR 补齐"Token 也不出 Emby origin"。
- 测试：RedirectCredentialIsolationTest（Robolectric + 双/三 MockWebServer）：
  跨 origin 单跳剥离 + 安全头透传、多跳（307+302）每跳剥离、同 origin 重定向保留凭据、
  无重定向直连携带凭据。

## ADR-031 Artwork Pipeline 图片鉴权与作用域（Phase 1B-2.3）
- 状态：已采纳（2026-08-22）
- 决策：
  - 图片 URL 由 Provider 层生成（EmbyImageMapper → /emby/Items/{id}/Images/{type}?tag&maxWidth&quality），
    **URL 永远不含 Token**（ADR-026 延续；tag 是内容哈希，仅缓存键用途）；
  - 鉴权由全局 Coil ImageLoader 的 app 层 application interceptor（EmbyImageAuthInterceptor）注入：
    origin（scheme+host+port）命中已知 Emby 服务器才加 X-Emby-Token/X-Emby-Authorization，
    未命中原样放行；Token/UserId 按请求惰性读（re-login 立即生效，不缓存）；
  - 跨 origin 重定向由 core/network 的 OriginScopedCredentialInterceptor（ADR-030）统一剥离，
    播放与图片共用同一套红线实现（interceptor 因此从 player:engine 迁至 core:network）；
  - 图片磁盘缓存独立于播放缓存（cacheDir/image_cache，256MB LRU，respectCacheHeaders(false)）。
- 类型策略：Movie/Series/Season → Primary(400)+Backdrop(1280)；Episode/Video → Thumb??Primary(400)；
  Folder/Audio 不生成 URL（UI 占位图标）；RequiredHttpHeaders 类的服务端指定图片头当前无真实样本，未引入。

## ADR-032 播放器轨道选择与字幕样式（Phase 1B-2.4）
- 状态：已采纳（2026-08-22）
- 决策：
  - **轨道 index 语义统一为同类型内序号（per-type ordinal）**：AudioTrack/SubtitleTrack.index、
    selected TrackSelection 与引擎 MappedTrackInfo.getTrackGroups(type) 的 per-renderer 组序号
    一一对应；禁止任何层再使用 Tracks.groups 全局序号（存在视频组时错位）。
  - **音频诊断内置**：每音轨携带 isSupported（Tracks.Group.isTrackSupported）与
    decoderName（MediaCodecUtil.getDecoderInfo，宽容 null）；无声提示基于当前 Audio Pipeline
    观测状态（audioFormatMime 非空才视为有声信号），isSupported 仅用于轨道能力提示、
    不作为是否有声音的唯一判据——为未来 mpv fallback 收集数据。
  - **字幕样式默认白字 + 全透明背景 + 黑描边**（彻底去 CC 黑底），样式持久化于
    UserPreferences.subtitleStyle（DataStore），由 PlayerScreen 应用到 SubtitleView
    （CaptionStyleCompat + setFractionalTextSize + setBottomPaddingFraction +
    setApplyEmbeddedStyles）；设置页 18sp 档位与播放器内缩放叠加。
## ADR-033 Server Editor：图标语义 / 删除级联 / 默认媒体源原子不变式
- 状态：已采纳（2026-08-22，Phase 1B-2.5）
- 决策：
  - **服务器图标语义**：MediaServer.icon 为 String?，约定 null = 默认（Provider 首字母徽标）、
    builtin://&lt;type&gt; = 内置 Provider 图标、file://&lt;abs-path&gt; = 自定义图片（应用私有目录
    files/server_icons/{serverId}.webp）。统一 core:ui ServerIcon 组件渲染，禁止各处混用 Text(icon)/AsyncImage(icon)。
  - **图标落盘**：Photo Picker 选中 → 缩放/中心裁剪方形 → WebP 复制到应用私有目录，不长期保存
    SAF content:// URI（权限过期/原图删除即失效）。
  - **删除级联**：RemoveServerUseCase 按 server(+endpoints) → account → token → credential →
    progress → 自定义图标文件 → provider 会话（SessionStoreCleaner）顺序清理，禁止 UI 直接 delete servers row。
  - **默认媒体源不变式**：最多一个 isDefault==true。设为默认用单条 SQL（UPDATE servers SET
    isDefault = CASE WHEN id=:id THEN 1 ELSE 0 END）原子清旧设新；删除默认媒体源时重选首条为默认。
- 影响：ServerEditor 只编辑主线路 URL（草稿→测试→保存一次 updateServer）；多线路增删排序留待 Endpoint Management。

## ADR-034 双内核架构与 AUTO 引擎选择（Universal Playback U1-U3）
- 状态：已采纳（2026-08-23）
- 背景：Media3 fast path 对主流格式体验最好，但 DTS-HD/TrueHD 静音、部分 container/codec 组合
  source/decoder 失败需要 mpv 兜底；用户手动选内核负担高。
- 决策：
  - **PlaybackEnginePort 为唯一引擎契约**：Media3（PlaybackEngine）与 mpv（MpvPlaybackEngine）
    同端口实现；PlayerViewModel 只见 fun interface PlaybackEngineCreator，双工厂经
    @Media3EngineCreator/@MpvEngineCreator 注入。
  - **SwitchablePlaybackEngine 门面**：对外转发活跃引擎的 uiState/progress/events/cues/speed；
    模式为 AUTO/MEDIA3/MPV（UserPreferences.playbackEngineMode，默认 AUTO）。
  - **AUTO 决策链（PlaybackEngineSelector）**：显式指定 > 历史失败指纹（签名粒度
    container|videoCodec|audioCodec，EnginePreferenceHistory 接口在 player:engine、
    DataStore 实现在 app）> DTS/TrueHD 音频集直接走 mpv > 默认 Media3。
  - **运行时降级**：AUTO 下 Media3 报 decoder/source 错误或静音（audioFormatMime 为 null
    且过宽限期）→ 保存当前位置 → mpv 同位置重播 → 签名写入历史；用户只看到
    "正在切换兼容播放模式…"。网络类错误不降级（换内核无意义）。
  - mpv 的 HTTP 流经 MpvHttpBridge（core:network，IPv4 loopback 绑定）本地代理，
    沿用 ADR-030 跨 origin 凭据剥离红线。
- 已知缺口（留在矩阵，非回归）：Blu-ray .iso 在 resolve 阶段 skip；MPEG-TS 原始流。

## ADR-035 播放器手势层与 SeekMode 语义（U3-B）
- 状态：已采纳（2026-08-23）
- 决策：
  - **三层分工**：PlayerGestureLayer（Compose awaitEachGesture 自定义识别器，判定
    tap/double-tap/hold/drag/long-press 矩阵）→ PlayerGestureController（纯状态机，无
    Android 依赖，位置/时长/倍速经注入 lambda 读取，动作走 Actions 接口）→ 播放页接
    PlaybackEnginePort/Overlay。禁止 UI 直接解析指针事件做业务决策。
  - **手势层位置**：渲染 Surface/字幕层之上、控制层之下；上层可点元素消费事件后不落入
    手势层（down.isConsumed 早退）。
  - **SeekMode.PREVIEW/COMMIT**：seekTo(positionMs, mode)——PREVIEW 只移位置不发
    PlaybackEvent.Seeked（不触发 ProgressSyncCoordinator 远端即时 flush），COMMIT 才发。
    scrub 与连续快退期间用 PREVIEW，松手 COMMIT；进度条拖动同语义。
  - **连续快退用节流 seek 而非负倍速**：负倍速内核普遍不支持；每 tick（333ms）退 1s，
    净退速约 3×。
  - **长按倍速恢复永久倍速**：松开恢复长按前的永久倍速（而非固定 1.0×）；
    setSpeed 统一 clamp 0.1-5.0。
  - **双击消歧**：自实现 tap 等待（双击窗口内不出单击），Overlay 不闪烁。
  - **手势偏好独立于 defaultPlaybackSpeed**：PlayerGestures 9 项（DataStore），
    scrub 灵敏度 = clamp(时长×10%, 60s, 10min) 固定算法不做偏好。

## ADR-036 Phase 1C 查询管道与聚合搜索语义（1C-1/1C-2）
- 状态：已采纳（2026-08-29）
- 决策：
  - **MediaListQuery 独立于 PageRequest**：PageRequest 只表达 offset/limit；
    排序属于 MediaSort(field, direction) + MediaListQuery(page, sort)，
    经 MediaQueryLibraryProvider.getItems(libraryId, query) 下沉服务器，
    在分页之前执行；禁止 UI/ViewModel 对已加载页做本地 sortedBy。
    Provider 经 sortCapabilities 自述可兑现的排序字段，UI 只渲染能力内选项，
    禁止按 ServerType 硬编码；无 Query 能力的 Provider 回退 getItems(page) 且隐藏排序入口。
  - **RANDOM 是单次快照而非分页**：Emby SortBy=Random 跨页各自随机、不重不漏无保证；
    RANDOM 只承诺 offset=0 的单页（无论 TotalRecordCount 多大都终止 hasMore/nextOffset），
    offset>0 直接空页；无方向语义（SortOrder 省略）。SERVER_DEFAULT 同样无方向语义。
  - **聚合搜索首版不做跨源语义合并**：不同服务器上的同名影片是两个真实播放源，
    全部保留并标注来源（UnifiedSearchHit.serverName）；UI identity 用 (serverId, itemId)；
    按 title/title+year 合并需等 externalIds/canonical identity，禁止草率同题合并。
  - **Emby capability 只声明已证实的 SortBy 字段**：官方 SortBy 枚举包含的九类
    （含 CRITIC/RATING 等）；OFFICIAL_RATING/BITRATE/SIZE 未见于该枚举
    （OfficialRatings 是过滤参数，Size/Bitrate 只是响应属性），capability 隐藏，
    wire 映射保留待 per-server probe；恢复须协议证据，不做客户端 fallback sort。
  - **用户主动排序后 Provider 顺序为权威**：sort != SERVER_DEFAULT 时 Library UI 按
    Provider 返回顺序渲染（folder 行与媒体格交错，允许全宽行造成视觉空位），
    不得隐式按目录优先重排；SERVER_DEFAULT 保留历史目录优先展示策略。
    导航控件（返回上级）固定顶部，不参与排序语义。
