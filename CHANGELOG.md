# 变更记录（CHANGELOG）

## [0.5.1-phase1a-final5] — 2026-08-12（FINAL PATCH 5：PR 吸收 main + forceRestore 竞态修复 + 测试）

- 把 latest main（208d9a3）真正 merge 进 PR（main 成为祖先，behind 0）。
- P1 forceRestore stale-server race：改 serverStore.getServer(serverId)（DB 最新，非 servers.first() 缓存），
  复用 authenticationCoordinator.restore；HomeViewModel 依赖 ServerStore/ProgressStore 接口。
- 新增 PR 版 HomeViewModelTest 5 例（读 DB 最新 / SessionExpired→Authenticated / SignedOut→Authenticated /
  非认证不写 / 已有状态仍强制 restore）；全项目 88 用例。
- 验证：assembleDebug / testDebugUnitTest(88) / lintDebug 通过。main 已冻结，只在此 PR 施工。

## [0.5.0-pr1-reconcile] — 2026-08-12（PR #1 Final Reconciliation：PR#1 为最终主线）

### 合流（评审 Final Reconciliation 规范）
- 把 latest main（61e5aea）真正 merge 进 PR #1（祖先包含 main，非手工复制）。
- 最终认证架构：CredentialVault + AuthenticationCoordinator + AuthSession 单一 source of truth。
- 恢复 main 的 Auth UI：HomeScreen 登录态 + 退出；Existing-Server Re-login（复用 same id）；
  Local reauthorization（reauthorizeId + ACTION_OPEN_DOCUMENT_TREE）。
- 修 Emby 官方 API：/emby root（EmbyApiRoot.from 追加前缀）+ GET /Users/{userId}
  （去未文档化 /Users/Me）+ Authorization 官方 `Emby UserId="..."` schema。
- 防串服 + 失效策略：remoteServerId 校验不发错 Token；仅 401 清 Vault；403/malformed/5xx/网络保留。

### 验证
- assembleDebug / testDebugUnitTest（65）/ lintDebug 通过；等待 latest head CI + 重新 Codex/Copilot review。

## [Unreleased] — Phase 1A Reconciliation

### 认证架构
- 将 main@499463c 的 Emby 登录/API/DTO/Header/Mapper/ClientIdentity/MockWebServer 资产迁入 PR #1 架构。
- 新增原子 `AuthSession`；`CredentialVault + AuthenticationCoordinator` 成为唯一 Session source of truth，
  不保留 TokenStore/EmbySessionStore。
- 通用 restore 接入 App：首页恢复时先匿名校验 remoteServerId，再发送 Token；仅 401/身份变化清 Session，
  403、malformed、网络与 5xx 保留。
- Emby 使用统一 API root、官方 Authorization Header、X-Emby-Token；Phase 1A Handle 只开放 AUTH。
- 添加页执行认证→保存事务，密码遮罩且明确“不保存”；保存失败撤销并清理会话。

### 评审修复
- SAF 任意层级目录改用 DocumentsContract 查询；旧空 LOCAL 记录支持原地重新授权。
- 关键播放事件改为无丢弃队列，PLAY 状态依据 playWhenReady；系统返回等待 STOP flush；导航保留 MediaType。
- WebDAV 认证改用受保护 PROPFIND，Basic 默认字符集并按 challenge 重试 UTF-8；401/403 探测显示认证提示。

### 验证
- 最新 PR head 的三项 GitHub Actions 门禁与 Codex/Copilot review 待本次 reconciliation commit 发布后执行。

## [Unreleased] — Phase 0.5 Architecture Hardening

### 架构
- 将 Fat `MediaProvider` 重构为最小公共接口；新增 `ProviderHandle` 类型安全地组合可选能力。
- Descriptor 与 Handle 分离计划/运行时语义；未实现的 Emby/Jellyfin/WebDAV API 不向业务层伪装可用。
- 新增 `ProviderDescriptor`，Registry 改用开放的 `providerId:String`；添加媒体库页面动态读取 Factory 元数据。
- 移除领域层 `ServerType`；Room v1 兼容读取旧枚举字符串并写入稳定 providerId。
- 新增 `CredentialVault` / `AuthenticationCoordinator`，区分短期输入与长期会话并通过 Keystore 加密保存。
- 移除 Singleton `PlaybackHeadersHolder`；每个 MediaSource 使用独立不可变 `PlaybackRequestContext`。
- 把播放位置、5s 本地快照、Provider 远端策略和 Play/Pause/Seek/Stop/End 关键事件拆成独立进度路径。
- 关键事件不 conflate，远端上报最多等待 2s，退出不会被无响应 Provider 长时间阻塞。

### 功能与质量
- LocalProvider 改用 SAF 文档树、持久 URI Permission、DocumentFile/ContentResolver 与 `content://` 播放。
- Emby/Jellyfin 连接测试校验公开 System Info；WebDAV 使用 OPTIONS 并要求 DAV Header；Local 校验 URI 授权。
- 新增 GitHub Actions Android CI，执行 assembleDebug、testDebugUnitTest、lintDebug。
- 新增 Registry、能力组合、凭据生命周期、请求头隔离、进度节流、旧数据库映射和协议探测测试。
- 保留完整 Provider API、云盘、Plex、FFmpeg/MPV 与大规模 UI 重做到后续阶段。

### 验证
- GitHub Actions run 31554659794：`assembleDebug`、`testDebugUnitTest`（37 个 `@Test`）、
  `lintDebug` 全部通过；`BUILD SUCCESSFUL`，1005 actionable tasks。
- 合并最新 main 后新增计划/运行时能力、加密 pending 凭据及协议拒绝路径测试，总计 41 个 `@Test`；
  PR #1 最终头继续执行同一套三项门禁。

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
