# 变更记录（CHANGELOG）

## [0.4.2-reconcile] — 2026-08-12（PR #1 × main 合流，以 main 为主线")

### 合流决策（经用户确认，ADR-029）
- 以 **main 为主线**：不采用 PR #1 的 CredentialVault/AuthenticationCoordinator 双轨重构，
  保留 main 已绿灯的 Phase 1A（TokenStore + EmbySessionStore）。
- PR #1 中有价值的**实质性 review 修复**cherry-pick 到 main：
  - SAF 嵌套浏览基础设施：`SafTreeNavigator` + `SafUri`（tree-backed，纯 Kotlin 可测，+5 测试）；
    作为 Phase 0.6 SAF 演进基础（main 的 LocalProvider 仍为 File 型，无 PR#1 的 fromSingleUri bug）
  - 播放关键事件 Channel：`_events` 改 `Channel(UNLIMITED)`（Pause/Seek/Resume 不 conflate/drop）
  - 系统 Back：PlayerScreen 加 `BackHandler`（awaited stopAndFlush，不走 toolbar 绕过）
  - browse-only MediaType：`MediaTypeGuesser`（core:model）+ PlayerViewModel browse-only fallback
- WebDAV 骨架保留（其 PROPFIND 认证属 Phase 1 完整实现；main 的 testConnection 已含 401/403 AUTH_REQUIRED）

### 验证
- assembleDebug / testDebugUnitTest（74）/ lintDebug 全部通过

## [0.4.1-phase1a-finalization] — 2026-08-12（Phase 1A 端到端收尾，ADR-028）

### 功能补齐（评审 10 项）
- MediaAuthProvider 通用 `restoreSession(): AuthSessionState` 契约（Jellyfin/WebDAV 占位）
- Home 启动/服务器变化自动恢复登录（HomeViewModel.restore）；ServerCard 展示登录态 + 退出入口
- **防 Token 串服**：恢复/登出前无 Token 校验 /System/Info/Public 的 remoteServerId，
  不一致 → SERVER_MISMATCH 绝不发送 Token（含测试：SERVER_B 从未收到 X-Emby-Token）
- **失效策略修正**：仅 401 清会话；403/malformed/5xx/网络保留（含测试）
- **官方协议对齐**：EmbyEndpointResolver（/emby root）+ X-Emby-Authorization 官方 schema
  `Emby UserId=..., Client=...`（不再 MediaBrowser 前缀）
- 密码输入框遮罩 + 文案"仅用于本次登录，不会保存在设备中"

### 测试
- EmbyAuthProviderTest 扩至 16 用例（+403 preserve、malformed preserve、SERVER_MISMATCH、
  logout mismatch）；全项目 69 用例

### 验证
- assembleDebug / testDebugUnitTest（69）/ lintDebug 全部通过

## [0.3.0-phase1a] — 2026-08-12（Emby 认证与会话）

### 新增
- **Emby 认证闭环**（ADR-026）：登录（/Users/AuthenticateByName）→ 严格校验
  AccessToken/ServerId/User.Id → TokenStore（localServerId 键）+ EmbySessionStore（会话元数据）。
- **会话恢复验证**：Token+Session 双份齐全 + GET /Users/Me 真实验证；
  401/403 清会话（SessionExpired），Timeout/DNS/5xx 保留会话（网络问题 ≠ token 失效）。
- **Logout**：POST /Sessions/Logout best-effort + 本地清理权威。
- **EmbyAuthState 状态机**（Unknown/SignedOut/Restoring/Authenticating/Authenticated/Error）。
- **ClientIdentity**（core:common，ADR-025）：DeviceId 首次生成持久化；Emby/Jellyfin 共用。
- **Emby 内部模块拆分**（ADR-027）：api/auth/session/mapper；EmbyProvider 瘦身为身份/探测。
- **添加服务器 UI**：测试连接（协议嗅探）→ 登录并添加（事务语义：认证成功但保存失败 → 回滚会话）。
- ApiClient.postNoContent（HTTP 200 + 空 body 正规处理）。

### 修复
- OkHttp "POST must have a request body"：buildBody 对 POST 兜底空 body。

### 测试
- EmbyAuthProviderTest 13 用例（MockWebServer：登录/401/malformed/关键字段缺失/恢复/
  超时保留/500 保留/X-Emby-Token/登出成功/登出网络失败/密码不落库）；
  EmbyProviderFactoryTest（Handle 只开放 AUTH）；全项目 66 用例。

### 验证
- assembleDebug / testDebugUnitTest（66）/ lintDebug 全部通过。

## [0.2.2-exit-flush-fix] — 2026-08-12（退出重复上报 patch）

### 修复
- 退出 final flush 唯一权威路径（ADR-023）：`PlaybackEvent.Stopped` 仅作状态通知，
  **不再由 ProgressSyncCoordinator 自动 flush**；退出上报只走
  `engine.stop() → flush(finalProgress) → stop() → release()` 一条链，
  消除"Stopped 自动 flush(latest) + 显式 flush(finalProgress)"导致的
  本地两次写入 + 远端两次上报。
- 修正代码注释与文档中 final flush 的 ADR 引用（ADR-022 → ADR-023）。

### 测试
- 完整退出链测试：流中 20s 不得作为退出 final 上报；25s 本地/远端各恰好一次；
  Stopped 事件不触发自动 flush。

### 验证
- assembleDebug / testDebugUnitTest（51 用例）/ lintDebug 全部通过。

## [0.2.1-hardening-fix] — 2026-08-12（Phase 0.5.1 边界修复）

### 修复
- **ProviderHandle 运行时语义**（ADR-022）：Handle 只暴露已实现能力；Emby/Jellyfin/WebDAV
  Handle 置空（Phase 1 逐项填充）；`declaredCapabilities`（计划）/`runtimeCapabilities`（运行时）
  命名分离；ProviderCapability 补 PLAYBACK/DETAIL。
- **退出 final flush 状态机**（ADR-023）：`PlayerViewModel.stopAndFlush()` 显式流程
  （stop → 最终进度 → local save + remote report(2s 超时) → release）；Stopped 事件触发 flush；
  onCleared 兜底；返回按钮同步走 stopAndFlush，DisposableEffect 仅兜底。
- **CredentialVault 接入 Hilt DI**（AppModule @Provides；不保存 Emby/Jellyfin 原始密码）。
- **连接测试协议签名校验**（ADR-024）：Emby/Jellyfin SystemInfo 必须 Id/Version 非空；
  DTO 键名 @SerialName("Id"…) 对齐真实协议。

### 测试
- 新增 11 用例（共 51）：ProviderHandle 一致性、Emby 连接（6）、Jellyfin 连接（3）、
  Stopped/flush override（2）；MockWebServer 覆盖正确/错误JSON/404/401/403/malformed。

### 验证
- assembleDebug / testDebugUnitTest（51 用例）/ lintDebug 全部通过。

## [0.2.0-hardening] — 2026-08-12（Phase 0.5 架构加固）

### 重构
- **MediaProvider 能力组合**（ADR-014）：Fat Interface 拆为公共契约 + 7 个可选能力接口，
  新增 `ProviderHandle`（类型安全组合）。LocalProvider 去除伪造认证/空 Season/NotYetImplemented 堆；
  Emby/Jellyfin/WebDAV 按类型声明能力。
- **ProviderDescriptor**（ADR-015）：Factory 自报描述；"添加媒体库"从 Registry 动态渲染，
  删除 UI 硬编码 ServerTypeOptions。
- **播放请求头 session-scoped**（ADR-018）：PlaybackHeadersHolder 改为 per-engine，
  废弃全局 Singleton mutable holder。
- **进度同步三档节流**（ADR-017）：新增 ProgressSyncCoordinator（本地 5s 采样 / 远端按
  Provider 间隔 / 关键事件立即 flush / 退出 final flush）；PlaybackEngine 提供 progress/events 流。
- **协议级连接测试**（ADR-019）：Emby/Jellyfin 嗅探 /System/Info/Public，WebDAV OPTIONS+DAV 头；
  AddServerViewModel 不再用通用 HTTP probe。
- **CredentialVault**（ADR-016，机制先行）：长期凭据加密存取；TokenStore 改 java.util.Base64（可 JVM 测试）。
- SAF 预留（ADR-020）：LocalRootProvider.contentRoots() 接口（Phase 0.6 接入文档树）。

### 新增
- GitHub Actions CI（.github/workflows/android-ci.yml）：assembleDebug + testDebugUnitTest + lintDebug。
- 测试 40 个（新增 20）：Registry、能力组合、CredentialVault、TokenStore、进度节流、
  Headers 隔离、ServerEntityMappers。

### 验证
- assembleDebug / testDebugUnitTest（40 用例）/ lintDebug 全部通过。

## [0.1.0-skeleton] — 2026-08-12（Phase 0）

### 新增
- 工程骨架：Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + 版本目录（libs.versions.toml）+ wrapper。
- 23 个 Gradle 模块：core(common/model/network/database/security/logging)、
  player(engine/compatibility)、provider(api/base/emby/jellyfin/webdav/local)、
  metadata、feature(home/server/library/detail/search/settings/player)、app。
- core:model：统一领域模型（MediaType/MediaItem/PlaybackSource/PlaybackProgress/分页等）。
- core:network：ApiClient（OkHttp+kotlinx.serialization）、MediaHttpClient（媒体探测）、
  PlaybackError 结构化错误与映射、脱敏日志拦截、X-Request-Id。
- core:database：Room（servers/accounts/playback_progress）、DataStore 偏好、Server/Progress 仓库。
- core:security：Keystore AES/GCM SecretStorage + TokenStore。
- core:logging：Logger（分类）、Redactor（脱敏）、LogBuffer（诊断缓冲）；3 组规则 + 7 用例测试。
- player:engine：PlaybackEngine（Media3/ExoPlayer）、SimpleCache 播放缓存、
  HeaderAwareDataSource 请求头注入、音轨/字幕选择、进度循环、结构化错误映射。
- player:compatibility：DeviceCapabilities 采集、PlaybackCompatibilityEvaluator（9 用例测试）。
- provider:api/base：MediaProvider 能力接口、ProviderException、@IntoSet 注册表、
  BaseMediaServerProvider（异常收敛/Token 会话/连通性探测）。
- provider:local：本地文件树浏览 + file:// 播放（真实实现）。
- provider:emby/jellyfin/webdav：骨架（构造函数/注册就绪，API 业务待 V0.1）。
- metadata：刮削抽象（接口 + 注册表）。
- feature:*：首页媒体源卡片与继续观看、添加媒体库流程（类型选择/测试连接/保存）、
  本地文件树、播放器页（自定义控制/音轨/字幕/倍速）、设置（DataStore 偏好）。
- 文档：README/ARCHITECTURE/ROADMAP/TASKS/DECISIONS/HANDOFF + docs/{providers,player,api}。

### 修复（构建期）
- Hilt KSP `@IntoMap` NonExistentClass → 改用 `@IntoSet`（ADR-005）。
- Media3 1.5.1 API 对齐（无 MediaItem.headers → DataSource 包装层；SelectionOverride 位置等）。
- 跨模块 okhttp/kotlinx-serialization 可见性 → core:network 以 api 暴露。
- Redactor 正则：Bearer 令牌含空格与查询串无引号场景。
- lint：UnsafeOptInUsageError（类级 OptIn + 模块关闭）、NewApi/InlinedApi（API 29 守卫）、
  StateFlowValueCalledInComposition（改为 collectAsStateWithLifecycle）。

### 已知限制
- 沙箱（Linux aarch64）无法原生跑 aapt2，验证时启用 qemu 包装器（见 HANDOFF.md）。
