# 变更记录（CHANGELOG）

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
