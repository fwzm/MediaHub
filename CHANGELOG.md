# 变更记录（CHANGELOG）

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
