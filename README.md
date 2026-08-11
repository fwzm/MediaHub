# MediaHub —— 多源统一媒体播放器

> 一个播放器，统一管理 Emby / Jellyfin / Plex / NAS / WebDAV / 云盘 / 本地媒体。

当前状态：**Phase 0 骨架**（可编译、可运行、核心闭环已就绪，具体数据源 API 下一阶段接入）。

## 项目简介

不同媒体服务器与云存储通过统一 `MediaProvider` 抽象接入，UI 只消费统一领域模型
（`core:model`），不感知任何数据源协议。播放核心基于 AndroidX Media3 / ExoPlayer。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)，进度见 [ROADMAP.md](ROADMAP.md) / [TASKS.md](TASKS.md)，
重要决策见 [DECISIONS.md](DECISIONS.md)，交接信息见 [HANDOFF.md](HANDOFF.md)。

## 模块结构

```
app/                 应用壳（Application / MainActivity / 导航 / DI）
core/                核心层（无业务）
  common/            通用工具（id、时间、调度器、Nav 编解码）
  model/             统一领域模型（纯 Kotlin，无 Android 依赖）
  network/           ApiClient（API）+ MediaHttpClient（媒体流）+ 结构化错误
  database/          Room（服务器/账号/进度）+ DataStore（偏好）+ Repository
  security/          Android Keystore 加密存储（SecretStorage / TokenStore）
  logging/           统一日志（分类、脱敏、内存缓冲）
player/
  engine/            Media3/ExoPlayer 封装（PlaybackEngine、缓存、请求头注入）
  compatibility/     设备能力 + 播放兼容性评估器（纯逻辑，可单测）
provider/
  api/               MediaProvider 统一接口 + 能力声明 + 异常（纯 Kotlin）
  base/              BaseMediaServerProvider + Provider 注册表
  emby/ jellyfin/ webdav/ local/   具体数据源（local 已实现；其余 Phase 1）
  smb/ aliyun/ baidu/ quark/ china-mobile/ tianyi/   规划占位（README）
metadata/            媒体元数据（刮削）抽象
feature/
  home/ server/ library/ detail/ search/ settings/ player/    UI 模块
docs/                providers / player / api 设计文档
```

## 环境要求

- JDK 17+
- Android SDK（compileSdk 35，minSdk 26，build-tools 35.0.0）
- Gradle 8.9（仓库已带 wrapper）

## 构建

```bash
# 需要 local.properties 或 ANDROID_HOME 指向 SDK
./gradlew assembleDebug          # 编译 + 打包 APK
./gradlew testDebugUnitTest      # 单元测试
./gradlew lintDebug              # Lint 检查
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 当前可用的端到端路径（Phase 0）

1. 首页 → 添加媒体库 → 选择类型（Emby / Jellyfin / WebDAV / 本地存储）→ 测试连接 → 保存。
2. 点击"本地存储"卡片 → 浏览应用外部目录文件树（文件夹可进入/返回上级）。
3. 点击视频文件 → 播放器（PlayerView + 播放/暂停/进度/倍速/音轨/字幕选择）。
4. 播放进度自动写入本地快照 → 首页"继续观看"。
5. 设置页可改默认倍速、字幕大小、硬解/直连偏好等（DataStore 持久化）。

Emby / Jellyfin / WebDAV 的 API 业务（登录、媒体库、搜索、播放源解析、进度上报）为 Phase 1
目标，当前会以明确的中文错误提示"尚未实现"（见 TASKS.md）。

## 安全约定（强制）

- 禁止源码硬编码 Token / Cookie / 密码。
- Token 走 `core:security`（Android Keystore AES/GCM 加密存储）。
- 临时播放 URL 绝不落库（ADR-003），播放时由 Provider 动态解析。
- 日志统一经 `Redactor` 脱敏（Authorization / Cookie / Token / 密码）。

## 多 AI 协作

开始任务前先读：README / ARCHITECTURE / ROADMAP / TASKS / DECISIONS / HANDOFF。
完成任务后更新：TASKS / CHANGELOG / HANDOFF（重要决策同步 DECISIONS）。
