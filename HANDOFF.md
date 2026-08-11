# 交接文档（HANDOFF）—— 每个 AI 必读

> 最后更新：2026-08-12（Phase 0 骨架交付）。本文件是协作第一手资料。

## 1. 当前项目状态

- **Phase 0 骨架已完成**：`assembleDebug`、`testDebugUnitTest`（20 用例）、`lintDebug` 全部通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk`（22MB）。
- 端到端可用路径：添加媒体库（本地存储）→ 文件树浏览 → 播放（本地文件）→ 继续观看。
- Emby / Jellyfin / WebDAV 的 API 业务**未实现**（Phase 1 目标，见 TASKS.md），
  调用时抛出 `ProviderException.NotYetImplemented`（唯一的"未实现"通道，带明确中文提示）。

## 2. 本次完成了什么

- 23 模块工程 + 版本目录 + wrapper；依赖方向见 ARCHITECTURE.md。
- 领域模型、Provider 抽象、网络层、Room/DataStore/Keystore、Media3 引擎、
  兼容性评估器、Server 管理 UI、首页/媒体库/播放器/设置页面。
- 文档体系 + git 初始提交。

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

1. **V0.1 第一优先**：Emby Provider 登录 → 媒体库 → 浏览 → 播放源解析 → 进度上报
   （endpoint 必须查官方文档，禁止凭记忆虚构；见 docs/providers/README.md 的待确认清单）。
2. 播放前把 `PlaybackCompatibilityEvaluator` 接入 `resolvePlayback` 决策流程。
3. `usesCleartextTraffic=true` 是临时的，接入 provider 后换 networkSecurityConfig。
4. 详情页/全局搜索/诊断页为占位（TASKS.md 有清单）。
5. 图片管线（Coil）待媒体库数据接入后引入。

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
