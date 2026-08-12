# 任务看板（TASKS）

> 状态：TODO / IN PROGRESS / DONE / BLOCKED。由完成任务的 AI 更新。

## Phase 0 —— 骨架（本次交付）✅ DONE

- [x] DONE 工程骨架：settings/build/version catalog/wrapper
- [x] DONE 模块划分（23 模块）与依赖图
- [x] DONE core:model 统一领域模型
- [x] DONE provider:api MediaProvider 能力接口 + ProviderException
- [x] DONE provider:base BaseMediaServerProvider + DefaultProviderRegistry（Hilt @IntoSet）
- [x] DONE provider:local 本地文件浏览/播放（真实实现）
- [x] DONE core:network ApiClient/MediaHttpClient/PlaybackError/脱敏拦截
- [x] DONE core:database Room（servers/accounts/playback_progress）+ DataStore + Repository
- [x] DONE core:security Keystore SecretStorage + TokenStore
- [x] DONE core:logging Logger/Redactor/LogBuffer + 单测
- [x] DONE player:engine Media3 封装（引擎/缓存/请求头注入/轨道选择/结构化错误）
- [x] DONE player:compatibility 设备能力 + 评估器 + 单测
- [x] DONE feature:server 添加媒体库（类型选择/测试连接/保存）
- [x] DONE feature:home 媒体源卡片 + 继续观看
- [x] DONE feature:library 本地文件树浏览
- [x] DONE feature:player 播放器页（PlayerView + 自定义控制）
- [x] DONE feature:settings 播放偏好（DataStore）
- [x] DONE 文档体系 + git 初始提交
- [x] DONE assembleDebug / testDebugUnitTest / lintDebug 通过

## Phase 0.5 —— 架构加固（本次交付）✅ DONE

- [x] DONE MediaProvider 能力组合（Interface Segregation + ProviderHandle，ADR-014）
- [x] DONE ProviderDescriptor 动态注册（UI 从 Registry 读取，ADR-015）
- [x] DONE LocalProvider 去除假实现（仅 BROWSE/DETAIL/PLAYBACK）
- [x] DONE CredentialVault（core:security，ADR-016）
- [x] DONE 进度同步三档节流（ProgressSyncCoordinator，ADR-017）
- [x] DONE 播放请求头 session-scoped（per-engine holder，ADR-018）
- [x] DONE 协议级连接测试（Emby/Jellyfin System Info、WebDAV OPTIONS，ADR-019）
- [x] DONE CI（.github/workflows/android-ci.yml）
- [x] DONE 测试补充（Registry/能力组合/Credential/进度节流/Headers 隔离/Mapper，共 40 用例）
- [x] DONE assembleDebug / testDebugUnitTest / lintDebug 通过

## V0.1 —— MVP（下一步） 

### IN PROGRESS（无，等待开工）

### TODO（新增于 Phase 0.5）
- [ ] SAF 目录选择器（ACTION_OPEN_DOCUMENT_TREE + 持久化授权 + DocumentFile 树导航，Phase 0.6）
- [ ] AddServer 认证流接入 CredentialVault（密码按 Provider 策略加密保存/销毁）
- [ ] Emby/Jellyfin/WebDAV 的 `remoteReportIntervalMs` 按官方协议确认节流值

### TODO
- [ ] Emby Provider 完整实现（登录→媒体库→浏览→搜索→详情→播放源→进度上报）
- [ ] Jellyfin Provider 完整实现（同上，独立 Connector）
- [ ] WebDAV Provider（PROPFIND 文件树 + Basic 认证 + 播放）
- [ ] 播放前接入 PlaybackCompatibilityEvaluator（resolve 流程输出三态决策）
- [ ] 播放器：URL 过期自动重解析、外挂字幕、字幕/音频延迟、HDR/媒体信息显示
- [ ] 详情页完整化（海报/简介/演职员/多版本）
- [ ] 全局搜索（跨 Provider 聚合）
- [ ] cleartext 改 networkSecurityConfig（按域名放行）
- [ ] 诊断页 + 脱敏报告导出
- [ ] Coil 图片管线接入（海报/背景图）

### BLOCKED
- （无）

## V0.2 / V0.3 / V1.0

见 ROADMAP.md（SMB、云盘、聚合媒体库等）。
