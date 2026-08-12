# 变更记录（CHANGELOG）

## [0.5.0-phase1b1] — 2026-08-12（Phase 1B-1：Emby 媒体库浏览）

### 新增（Phase 1B 第一刀，不碰播放）
- Emby 媒体库真实浏览竖切：/Users/{userId}/Views（顶层库）→ /Users/{userId}/Items?ParentId=...
  （View → Series → Season → Folder 通用进入子级）。
- EmbyLibraryProvider（TokenStore + EmbySessionStore + EmbyApiClient；getLibraries/getItems；
  getSeasons/getEpisodes 占位）。
- EmbyApiClient 新增 getUserViews/getUserItems（HttpUrl.Builder 安全拼 query，禁止手拼）。
- EmbyLibraryDtos（QueryResult/BaseItem/UserData，@SerialName，非关键字段缺失不整页失败）。
- EmbyLibraryMapper / EmbyMediaItemMapper（CollectionType→LibraryType；Type→MediaType）。
- MediaType.isContainer = FOLDER||SERIES||SEASON（Series/Season 可继续进入，不再误送播放器）。
- EmbyProviderFactory 暴露 library 能力（runtimeCapabilities 含 LIBRARY）。
- LibraryViewModel：root→getLibraries（LibraryUiState.Libraries）；非 root→getItems(parentId)。
- LibraryScreen：顶层 Views 列表 + MediaLibrary 导航 + item.type.isContainer 点击。
- 结构化错误映射：无 session→AuthRequired、401→AuthExpired、404→NotFound、5xx→Http、网络/解析分别表达。

### 测试（96 用例）
- EmbyLibraryProviderTest 5（Views/header/ParentId/StartIndex/Limit/类型映射/缺失 session 不发请求/401/403/5xx）。
- LibraryViewModelTest 4（root→getLibraries、非 root→getItems、open container→item.id、goToParent 恢复）。
- EmbyProviderFactoryTest 更新（AUTH+LIBRARY）。

### 验证
- assembleDebug / testDebugUnitTest（96）/ lintDebug 通过

## [0.4.6.1-phase1a-final4.1] — 2026-08-12（Phase 1A FINAL PATCH 4.1：TEST-ONLY 修正)

### 修正（纯测试，不改生产代码）
- `HomeViewModelTest` 的 B/C/D 三个测试名与实际状态迁移不一致，已修正为真实制造前置状态：
  - B：初始 restore = SESSION_EXPIRED → assert SessionExpired → 改 Authenticated → forceRestore → Authenticated
  - C：初始 restore = SignedOut → assert SignedOut → 改 Authenticated → forceRestore → Authenticated
  - D：先 auth-capable 产生 Authenticated → assert 包含 → 改 auth==null → forceRestore → 移除该 id
- 生产代码 forceRestore（serverStore.getServer + restore）已在 0.4.6 通过评审，未改动。

### 验证
- assembleDebug / testDebugUnitTest（87）/ lintDebug 通过

## [0.4.6-phase1a-final4] — 2026-08-12（Phase 1A FINAL PATCH 4：forceRestore 竞态修复 + 测试)

### 修复（评审 FINAL PATCH 4）
- **P1：forceRestore 读旧缓存竞态**：原用 `servers.first()`（observeServers StateFlow 缓存，re-login
  修改 baseUrl 后可能落后于 updateServer 的 DB 写入）。改为 `serverRepository.getServer(serverId)`
  直接读 DB 最新，复用 `restore(server)`（消除重复恢复逻辑）。
- **P2：Local 等非认证 Provider 瞬间显示 Restoring**：forceRestore 复用 restore(server)（先判
  handle.auth != null 才写 Restoring），Local reauthorization 返回 Home 不再闪现 Restoring/SignedOut。
- 可测性：提取 `ServerStore`/`ProgressStore` 接口（ServerRepository/ProgressRepository 实现），
  HomeViewModel 依赖接口；AppModule @Binds 绑定。

### 测试
- 新增 `HomeViewModelTest`（4 例）：forceRestore 读 DB 最新（非缓存）、SessionExpired→Authenticated、
  SignedOut→Authenticated、非认证 Provider 不写 authStates。
- 清理 AuthNavigationPolicyTest 未用 MediaType import。全项目 87 用例。

### 验证
- assembleDebug / testDebugUnitTest（87）/ lintDebug 通过

## [0.4.5-phase1a-final3] — 2026-08-12（Phase 1A FINAL PATCH 3：Home 状态闭环)

### 修复（评审 FINAL PATCH 3）
- **P1：re-login 后 Home 登录状态不刷新**：新增 `HomeViewModel.forceRestore(serverId)`（强制恢复，
  不管 authStates 是否已有记录）；导航用 Navigation result（`auth_changed_server_id`）在
  AddServer re-login 成功后通知 Home 强制刷新 → SessionExpired/SignedOut 后重新登录，
  返回 Home 立即变 Authenticated（不用杀 App）。
- **P2：非认证 Provider 不显示"未登录"**：`restore()` 在 auth==null 时不写入 authStates
  （移除该 id），Local/WebDAV 等不再显示 未登录/Restoring/Logout。
- **needsRelogin 单一 source of truth**：新建 `AuthNavigationPolicy`（feature:home），
  HomeViewModel 实际调用它；删除 ExistingServerEditPolicy 里仅被测试调用的重复 needsRelogin
  + 无用 isProviderLocked/MediaItem import。

### 测试
- 新增 `AuthNavigationPolicyTest`（测生产实际调用的 policy）：SignedOut/SESSION_EXPIRED/
  SERVER_MISMATCH → relogin；FORBIDDEN/网络/5xx/INVALID/UNKNOWN/Authenticated → 不 relogin；
  非认证 Provider → 不 relogin。全项目 83 用例。

### 验证
- assembleDebug / testDebugUnitTest（83）/ lintDebug 通过

## [0.4.4-phase1a-final2] — 2026-08-12（Phase 1A FINAL PATCH 2：Existing Server Re-login 修复)

### 修复（评审 FINAL PATCH 2）
- **descriptor id 映射 P1 bug**：existing-server 加载原本用 `ServerType.EMBY.name = "EMBY"`，
  与 `ProviderDescriptor.id = "emby"` 不匹配 → 表单无 descriptor 选中、URL/name/username 不预填。
  改为 `ExistingServerEditPolicy.descriptorFor`（按 `serverType` 匹配）；找不到 descriptor 显示错误而非静默。
- **Re-login Provider 锁定**：existingServer != null 时 `selectProvider` 拒绝切换（UI 不能点 Jellyfin/WebDAV）。
- **needsRelogin 精确语义**：仅 `SignedOut / SESSION_EXPIRED / SERVER_MISMATCH` 进入重登录；
  FORBIDDEN / NETWORK / 5xx / INVALID_RESPONSE / UNKNOWN 保留 session（不送重登录页）。
- **existing update 完整保留元数据**：`ExistingServerEditPolicy.buildDraft` 保留
  id/type/isDefault/sortOrder/createdAtEpochMs/lastConnectedAtEpochMs/lastError（不再重置为 0/null）。
- 修正 EmbyAuthProvider 注释中 `/Users/Me` → `/Users/{userId}`。

### 新增测试
- `ExistingServerEditPolicyTest`（8 例）：descriptor 按 serverType 匹配（禁 "EMBY"≠"emby"）、
  same id + 全元数据保留、Provider 锁定、needsRelogin 精确分支、新建不受破坏。

### 验证
- assembleDebug / testDebugUnitTest（82）/ lintDebug 通过

## [0.4.3-phase1a-final] — 2026-08-12（Phase 1A FINAL PATCH）

### 修复（评审 Phase 1A FINAL PATCH）
- **Emby 官方 API**：会话验证改用 `GET /emby/Users/{userId}`（废弃未文档化 /Users/Me）；
  authenticated request 的 `X-Emby-Authorization` 必带 `UserId="..."`
  （EmbyApiClient.authenticatedHeaders(token, userId) / getCurrentUser(token, userId)；
  logout(token, userId) 同样带 UserId）。
- **Existing Server Re-login**：ServerCard 点击在 已失效/身份变更/未登录（且为认证 Provider）时
  进入 `server/add?reauthorizeId={serverId}`；复用 SAME localServerId、预填 URL/name/username
  （密码留空）、成功后 `updateServer`（不 addServer，不产生重复卡片）。
  导航 HomeRoute 新增 `onRelogin`；AddServerViewModel 新增 reauthorizeId/existingServer 模式。
- **Playback progress**：`isPaused` 改为 `!player.playWhenReady`（播放意图），
  buffering 不再误报 paused（playWhenReady=true 但 isPlaying=false 时）。

### 验证
- assembleDebug / testDebugUnitTest（74）/ lintDebug 通过

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
