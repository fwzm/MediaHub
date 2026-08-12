# 交接文档（HANDOFF）—— 每个 AI 必读

> 最后更新：2026-08-12（PR #1 × main 合流，ADR-029）。本文件是协作第一手资料。

## 0. 合流结论（最重要）

- **以 main 为主线**（用户确认）。PR #1（agent/phase-0-5-architecture-hardening）标记为
  not-merge，其 CredentialVault/AuthenticationCoordinator 双轨认证架构不再与 main 并行开发。
- 从 PR #1 吸收了非破坏性 review 修复：播放关键事件 Channel(UNLIMITED)、系统 Back BackHandler、
  browse-only MediaTypeGuesser、SAF tree-backed 导航基础设施（SafTreeNavigator/SafUri，供 Phase 0.6）。
- main 的认证主线 = TokenStore + EmbySessionStore + 通用 restoreSession(AuthSessionState)。
- **后续新功能一律 base 在 main，不再往 PR #1 分支开发。**

## 0. Phase 1A finalization（最新）

- 会话恢复已接入 App：Home 启动自动 restoreSession（通用 MediaAuthProvider 契约），
  ServerCard 显示登录态 + 退出入口；密码框遮罩。
- 防串服：恢复/登出前无 Token 校验 remoteServerId（SERVER_MISMATCH 不发 Token）。
- 失效策略：仅 401 清会话；403/malformed/5xx/网络保留。
- 官方协议：/emby API root（EmbyEndpointResolver）+ X-Emby-Authorization `Emby UserId=...` schema。
- **待真机验收**：杀 App → 恢复 → 撤销 Token → 失效 → 重新登录 → Logout（步骤见交付报告）。

## 1. 当前项目状态

- **Phase 0（骨架）+ 0.5（加固）+ 0.5.1（边界）+ 1A（Emby 认证）已完成**：`assembleDebug`、
  `testDebugUnitTest`（66 用例）、`lintDebug` 全部通过；CI（GitHub Actions）已就绪。
- 能力语义（ADR-022/026）：Handle 只暴露已实现能力——**Emby 当前仅 AUTH**（runtimeCapabilities={AUTH}）；
  Local 有 BROWSE/DETAIL/PLAYBACK；Jellyfin/WebDAV 为空。
- **Emby 认证闭环已实现**（ADR-026）：登录/恢复/验证/登出；Token 入 TokenStore（localServerId 键）、
  会话元数据入 EmbySessionStore；localServerId ≠ remoteServerId；X-Emby-Token 集中注入；
  401=token 失效清会话，网络问题保留；密码绝不持久化。
- 退出流程（ADR-023）：返回按钮走 `stopAndFlush()` 显式状态机；
  **Stopped 事件仅状态通知、不触发自动 flush**——退出上报唯一权威路径是
  `engine.stop() → flush(finalProgress) → coordinator.stop() → engine.release()`，
  禁止让 Coordinator 再对 Stopped 自动 flush（会造成两次本地写入 + 两次远端上报）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 端到端可用路径：添加媒体库（本地存储）→ 文件树浏览 → 播放（本地文件）→ 继续观看。
- Emby / Jellyfin / WebDAV 的 API 业务**未实现**（Phase 1 目标，见 TASKS.md），
  能力组合已就绪，方法以 `ProviderException.NotYetImplemented` 占位；
  连接测试已是协议级（System Info / OPTIONS 嗅探）。

## 2. 本次完成了什么

- Phase 0：23 模块工程、领域模型、Provider 抽象、网络层、存储、Media3 引擎、
  兼容性评估器、Server 管理 UI、首页/媒体库/播放器/设置页面、文档体系、git 初始提交。
- Phase 0.5：MediaProvider 能力组合 + ProviderHandle；ProviderDescriptor 动态注册；
  CredentialVault；进度三档节流（ProgressSyncCoordinator）；播放请求头 per-engine；
  协议级连接测试；CI workflow；测试扩至 40 用例。

## 3. 修改了哪些文件（关键）

```
根：settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/libs.versions.toml
core/model/*（领域模型） core/network/*（网络） core/database/*（Room/DataStore）
core/security/*（Keystore） core/logging/*（日志脱敏）
player/engine/*（Media3 封装） player/compatibility/*（评估器）
provider/api/* provider/base/* provider/local/* provider/{emby,jellyfin,webdav}/*
feature/{home,server,library,detail,search,settings,player}/*
app/*（DI/导航/主题/资源）
docs/* 与 7 份根文档
```

## 4. 为什么这样设计（要点）

- 所有数据源实现 `MediaProvider`（provider:api），UI 不感知来源（ADR-002）。
- 播放 URL 永不落库，播放时 resolve（ADR-003）。
- Provider 自注册用 Hilt `@IntoSet`（@IntoMap 在该工具链有 KSP bug，ADR-005）。
- 播放决策纯函数化（ADR-004），评估器已就绪但**尚未接入 resolve 流程**（下一步做）。
- 缓存四类分离（ADR-009）；网络 API/媒体分离（ADR-012）。

## 5. 遗留问题 / 下一步建议

1. **V0.1 下一里程碑 Phase 1B：Emby 媒体库**（LibraryProvider）：/Users/{userId}/Views、
   /Users/{userId}/Items（分页）、详情 /Items/{itemId}；实现后在 EmbyProviderFactory 填
   library + detail 字段（ADR-022/027）。endpoint 必须查官方文档。
2. 待接 UI：首页服务器卡片显示 Emby 登录态（EmbyAuthState 驱动），提供"登录/退出"入口。
3. Jellyfin 复用 ClientIdentity/会话模式（独立 Connector）。
2. 播放前把 `PlaybackCompatibilityEvaluator` 接入 `resolvePlayback` 决策流程。
3. `usesCleartextTraffic=true` 是临时的，接入 provider 后换 networkSecurityConfig。
4. AddServer 认证流接入 CredentialVault（密码按 Provider 策略加密保存/销毁，ADR-016）。
5. SAF 目录选择器（Phase 0.6：ACTION_OPEN_DOCUMENT_TREE + DocumentFile 树导航）。
6. 详情页/全局搜索/诊断页为占位（TASKS.md 有清单）；图片管线（Coil）待媒体库数据接入后引入。

## 6. 已知 Bug / 注意事项

- `LibraryViewModel.goToParent()` 与 `openFolder()` 的文件夹栈：当前实现正确；
  若 Provider 支持非树形浏览（如 Emby 无 folder 概念），此逻辑需按 ProviderCapability 分支。
- `PlaybackEngine` 进度循环 1s 间隔；服务端上报为尽力而为，失败只记日志（不打断播放）。
- 播放器音轨/字幕选择基于"每组取第一轨"（TrackMapper 简化），多轨同组场景待完善。
- 设置页"默认倍速"当前仅持久化，播放器起播未读取（V0.1 接入）。
- Room schema 导出在 `core/database/schemas`（迁移用，勿删）。

## 7. 禁止随意修改

- `core:model` 领域模型：变更需先更新 ADR 并同步全部 Provider（禁止未经说明改 Schema/模型）。
- `PlaybackCompatibilityEvaluator` 的决策语义（DIRECT_STREAM=视频不转码）。
- `Redactor` 脱敏规则：只能加强，不能放松。
- 网络分层（ApiClient/MediaHttpClient 分离）与缓存分离原则。
- 播放引擎封装（player:engine）对外 API（PlaybackEngine/PlaybackSession/PlaybackUiState）。
- Provider 能力组合：**禁止把新能力塞回 MediaProvider Fat Interface**；
  新增能力 = 独立能力接口 + ProviderHandle 字段 + Factory 装配。
- 播放请求头：禁止恢复全局 Singleton holder（ADR-018）。
- 进度上报：禁止回到"每秒写库+上报"（ADR-017）。
- ProviderHandle：禁止把未实现/占位能力塞进 Handle（ADR-022）——feature 层不得用异常发现"未实现"。
- Emby 认证：禁止密码持久化/日志；禁止 AccessToken 放 URL query；禁止各端点手拼 X-Emby-Token
  （统一 EmbyApiClient）；禁止把 localServerId 与 remoteServerId 混用（ADR-026）。
- 退出流程：禁止"先 stop 协调器再 emit Stopped"（ADR-023）；必须走 stopAndFlush。
- 连接测试：禁止仅凭 HTTP <500 或 200+可解析JSON 判定协议有效（ADR-024）。

## 8. 沙箱专用说明（仅本环境）

- 本沙箱为 Linux **aarch64**：AGP 无 arm64 版 aapt2，验证 APK 需启用 qemu 包装器：
  `gradle.properties` 中 `android.aapt2FromMavenOverride=/opt/aapt2-bin/aapt2`（默认已注释）。
  `/opt/aapt2-bin/aapt2` 是 aarch64 原生 ELF 包装器，内部 exec `qemu-x86_64-static -L /usr/x86_64-linux-gnu` 运行 x86_64 aapt2。
  **正常开发机（x86_64）不需要也不应启用该配置。**
- 构建命令（本沙箱）：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew assembleDebug`
- 单测命令：`./gradlew testDebugUnitTest`（Redactor/PlaybackErrorMapper/PlaybackCompatibilityEvaluator）。

## 9. 环境信息

- JDK 17（OpenJDK arm64）/ Gradle 8.9 / Android SDK 35（platform 35 + build-tools 35.0.0 + platform-tools）
- SDK 路径：`/opt/android-sdk`（local.properties 已配置 sdk.dir，该文件不入库）
