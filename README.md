# MediaHub —— 多源统一媒体播放器

> 一个播放器，统一管理 Emby / Jellyfin / Plex / NAS / WebDAV / 云盘 / 本地媒体。

当前状态：**Phase 0.5 架构加固**。基础闭环已就绪，真实 Emby/Jellyfin/WebDAV 业务 API 仍属于 V0.1。

## 当前架构

- `MediaProvider` 只保留公共身份和协议探测；认证、媒体库、文件浏览、播放、搜索、字幕、进度是可选能力。
- `ProviderHandle` 对 Descriptor 声明与实际能力做构造期校验，UI/ViewModel 不判断具体 Provider 类型。
- `MediaProviderFactory` 自带开放的 `providerId` 与 `ProviderDescriptor`；添加页从 Registry 动态读取。
- 凭据只经 `CredentialVault` 进入 Android Keystore 加密的 `SecretStorage`，不写 Room/DataStore/日志。
- 每个播放 MediaSource 捕获独立的不可变请求上下文，不共享可变 Header/Cookie。
- 播放进度分成 UI 刷新、本地快照、Provider 远端节流和关键事件即时同步。
- 本地媒体使用 SAF 文档树和持久 URI 授权，播放 `content://`，不依赖 `file://`。

详细约束见 [ARCHITECTURE.md](ARCHITECTURE.md)，进度见 [ROADMAP.md](ROADMAP.md) / [TASKS.md](TASKS.md)，
决策见 [DECISIONS.md](DECISIONS.md)，交接见 [HANDOFF.md](HANDOFF.md)。

## 模块结构

```text
app/                 应用壳、导航与依赖装配
core/
  common/            通用工具与调度器
  model/             统一领域模型（纯 Kotlin）
  network/           API 与媒体流网络客户端、结构化错误
  database/          Room、DataStore、Repository
  security/          Android Keystore 加密 SecretStorage
  logging/           分类日志、脱敏、诊断缓冲
player/
  engine/            Media3 引擎、会话请求上下文、缓存
  compatibility/     设备能力与播放兼容性评估
provider/
  api/               最小 Provider 契约、可选能力、Descriptor/Handle
  base/              Registry、认证协调器、加密 CredentialVault
  emby/ jellyfin/    协议探测与 Phase 1 契约骨架
  webdav/            OPTIONS 探测、Basic 凭据生命周期、业务骨架
  local/             SAF 文件树浏览与 content:// 播放
metadata/            元数据抽象
feature/             home/server/library/detail/search/settings/player
```

## 环境与验证

- JDK 17+
- Android SDK：compileSdk/targetSdk 35，minSdk 26
- Gradle 8.9（仓库包含 Wrapper）

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

同样的三项检查由 [Android CI](.github/workflows/android-ci.yml) 在 PR 与主分支推送时执行。

## 当前可用路径

1. 首页 → 添加媒体库；可选类型由 Provider Registry 动态提供。
2. 本地媒体 → 系统目录选择器 → 持久化文档树授权 → 浏览文件树。
3. 点击媒体 → Media3 播放 `content://` 或 Provider 解析出的临时资源。
4. UI 位置约每 500ms 刷新；本地进度约 5s 快照；远端按 Provider 策略上报；暂停/拖动/停止/结束即时同步。

Emby/Jellyfin 目前只实现服务器身份探测，WebDAV 已实现协议探测与 Basic 凭据保存；媒体库、搜索、播放源、
远端进度等完整业务在 V0.1 实现，当前通过结构化 `NotYetImplemented` 明确失败。

## 安全约定

- 禁止硬编码或明文持久化 Token、Cookie、密码。
- 临时播放 URL 绝不落库；只持久化稳定资源标识。
- 所有日志经 `Redactor` 脱敏。
- Provider 登录成功后只保留长期会话；必须长期使用密码的协议才保存加密密码。

## 多 AI 协作

开始前阅读 README / ARCHITECTURE / ROADMAP / TASKS / DECISIONS / HANDOFF。完成后更新 TASKS / CHANGELOG /
HANDOFF；改变基础契约时同步 DECISIONS。CI 结果优先于文字声明。
