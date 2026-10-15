# MediaHub —— 多源统一媒体播放器

> 一个播放器，统一管理 Emby / Jellyfin / Plex / NAS / WebDAV / 云盘 / 本地媒体。

当前状态：**Phase 1B-2.5a**（ServerEndpoint 多线路地基 + Player Startup & Immersive UX：TTFF 单调时钟、临时时长回退、快照直传、自动横屏、沉浸式系统栏已实现并过真机；Overlay 两层 UI 与 Server Editor 进行中）。

[![GitHub release](https://img.shields.io/github/v/release/fwzm/MediaHub?include_prereleases)](https://github.com/fwzm/MediaHub/releases)
[![License](https://img.shields.io/github/license/fwzm/MediaHub)](LICENSE)

## 下载测试版

前往 [GitHub Releases](https://github.com/fwzm/MediaHub/releases) 下载最新 APK。

> 当前为 Alpha 阶段，仅提供 arm64-v8a 架构 APK。安装前请在系统设置中允许「安装未知应用」。
> 每次更新使用相同签名，可直接覆盖安装，数据不丢失。

**反馈问题：** [提交 Issue](https://github.com/fwzm/MediaHub/issues)

## 项目简介

不同媒体服务器与云存储通过统一 `MediaProvider` 抽象接入，UI 只消费统一领域模型
（`core:model`），不感知任何数据源协议。播放核心基于 AndroidX Media3 / ExoPlayer。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)，进度见 [ROADMAP.md](ROADMAP.md) / [TASKS.md](TASKS.md)，
重要决策见 [DECISIONS.md](DECISIONS.md)，交接信息见 [HANDOFF.md](HANDOFF.md)。

> **另有 Windows 原生桌面客户端正在开发中**：代码位于 `windows/`，全部工作在未合并分支
> `feature/windows-client`（Draft PR #33），**尚未发布任何安装包**。
> 状态与限制见 [Windows 桌面客户端（开发中，未发布）](#windows-桌面客户端开发中未发布)。

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
  emby/ jellyfin/ webdav/ local/   具体数据源（emby、local 已实现；jellyfin/webdav 骨架）
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

## 当前可用的端到端路径

### Emby（Phase 1A–1B-2.4）
1. 首页 → 添加媒体库 → Emby → 填服务器地址 / 用户名 / 密码 → 登录并添加。
2. 点 Emby 服务器卡片 → 浏览 Views → 电影库 / 剧集库 → 海报墙（电影/剧集 2:3 海报，单集 16:9 剧照）。
3. 点 Movie / Episode → 详情页（backdrop + 海报 + 元信息 + 简介）→ 播放。
4. 播放（无转码 Direct Stream）：Token 只走请求头不进 URL（ADR-026）；
   跨 origin 重定向剥离凭据（ADR-030）；图片加载同源注入鉴权、跨 origin 剥离（ADR-031）。
5. 播放器：音轨/字幕 Bottom Sheet（codec / 声道 / 采样率 / 解码器诊断），
   字幕默认白字透明背景（黑底关闭），字号/颜色/描边/位置可调并持久化；
   全部音轨不被设备支持时显式提示而非静默无声。
6. 播放进度自动写入本地快照 → 首页"继续观看"（缩略图 + 进度条）。

### 本地存储（Phase 0）
1. 首页 → 添加媒体库 → 本地存储 → 保存。
2. 点"本地存储"卡片 → 浏览文件树 → 点视频文件 → 播放。

设置页可改默认倍速、字幕大小等（DataStore 持久化）。

> Jellyfin / WebDAV / 搜索 / 播放进度上报为后续 Phase 候选，尚未实现（见 TASKS.md）。

## Windows 桌面客户端（开发中，未发布）

MediaHub 的 **Windows 原生桌面客户端**：与 Android 版本共享同一产品语义（领域契约、能力声明、
安全红线），但不共享源码，也不互相依赖运行时。

- 技术栈：C# / .NET 10 + WinUI 3（Windows App SDK 1.8）+ libmpv（`--wid` 嵌入，D3D11 硬解），自包含部署
- 代码位置：`windows/`；客户端自身说明见 [windows/README.md](windows/README.md)
- 现状：本章状态取自 `feature/windows-client` 分支（Draft PR #33），**尚未合并进 `main`，未提供任何安装包**；
  其他并行分支上的 Windows 工作不在本章范围内

> **读法**：本章只写有执行证据的结论。完整逐项状态（含 `UNVERIFIED` / `NOT_RUN` 标记）见
> [windows/docs/CAPABILITY-MAP.md](windows/docs/CAPABILITY-MAP.md) 与 `windows/docs/W*-EVIDENCE.md`；
> 这些表格与路线图可能滞后于最新提交，冲突时以代码与证据文档为准。

### 已验证的能力（真实执行并观察通过）

| 能力 | 证据 |
| --- | --- |
| libmpv 嵌入（`--wid`）+ D3D11 硬解播放真实视频 | `windows/docs/W1-EVIDENCE.md`（`hwdec=d3d11va`） |
| 媒体源管理：添加（探测 → 登录 → 保存）/ 测试 / 重新登录 / 删除 | `windows/docs/W2-EVIDENCE.md`；DPAPI 凭据库 79 条测试 |
| 库浏览（列表 / 海报墙 / 详情）、搜索、继续观看 | 同上 |
| 引擎网络边界：改为经本地回环转发取流，**引擎不持有服务器凭据** | ADR-W006、`CAPABILITY-MAP` 网络边界表 |
| 桌面集成：文件菜单、拖放、窗口状态恢复、文件类型关联 | 提交 `5c232d6` |
| **真实 Jellyfin 12.2**：登录 / 浏览 / 播放 / 播完标记 Played | `windows/docs/` W4 记录 |
| **官方 Emby 4.10.1.0** 部署与 API 形状回归 | `windows/engine/emby/`、W5 记录 |
| **WebDAV（Browse-only 来源）**：入口 → PROPFIND → 目录 → Range GET → libmpv 真实播放 | `windows/docs/W5-1-R3-GATE-A.md`（A-入口 / A-PROPFIND / A-媒体 = EXECUTED_PASS） |
| GUI 验收：播放控制 / 全屏 / 生命周期（可观测断言 + 证据文件） | `windows/docs/W5-GUI-ACCEPTANCE.md` |
| LGPL libmpv 引擎候选已实际构建（**尚未切换到产品**） | `windows/engine/lgpl/dist/` |

### 已知缺陷与未验证项

- **中断恢复**：服务端轨迹显示 32 s / 180 s 的播放被记为「已看完」—— 未解决。
- **WebDAV（W5.1 Gate A 部分通过）**：源可添加 / 认证 / 重启恢复；入口 → PROPFIND → 目录 → Range GET →
  真实播放已 EXECUTED_PASS（真实 WsgiDAV 4.3.3 + UIA，证据 `windows/docs/W5-1-R3-GATE-A.md`）；
  但 **A-Seek、A-退出释放、A-边界（目录递进 / 返回上级 / 空目录 / 错误态 / 离线 / 重启 / generation guard）
  全部 NOT_RUN —— Gate A 不得宣告完成**，Gate B1 未开工；「媒体源」页的浏览入口仍未加。
- **真服覆盖面**：Emby 端到端仍为 `SERVER_UNVERIFIED`；远端进度**回写语义**未验证；
  回环中继的发布声明因远端条目无法启动而**未验证**。
- **未执行**：真实第二 Windows 账户下的凭据库行为；系统代理 / PAC / TUN / VPN / SOCKS 出口一致性；
  文件菜单 / 拖放 / 队列区域的 GUI 验收脚本。
- **尚未实现**：收藏与播放列表 GUI；剧集季 / 集在有数据服务器上的验证。

### 构建与测试

```powershell
pwsh -File windows/tools/fetch-libmpv.ps1    # 首次：拉取 libmpv（约 115 MB，不入库）
dotnet build windows/MediaHub.Windows.sln -c Debug -p:Platform=x64
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug -p:Platform=x64
```

CI 见 [`.github/workflows/windows-ci.yml`](.github/workflows/windows-ci.yml)：仅在 `windows/**` 变更时运行，
与 Android 流水线互相独立（Windows 改动不会拖慢 Android 验证，Android 改动也不消耗 Windows runner）。
截至 `f6356e2`：编译 0 错误 0 警告，`MediaHub.Tests` 296 通过 0 失败；该 head 的 `windows-ci` 与 Android CI 均为 success。

### 引擎许可证（影响可发行性）

- 生产基线 `windows/native/libmpv/libmpv-2.dll` 为 **GPL-3.0-or-later**（构建未传 `-Dgpl=false`）。
- LGPL 候选已构建于 `windows/engine/lgpl/dist/libmpv-2.dll`（3,327,274 B，sha256 `20035902…3C0A`），
  但**尚未切换到产品**。
- 因此**在完成引擎切换与合规判定前，Windows 客户端不具备分发条件**。
  详见 [windows/THIRD-PARTY-NOTICES.md](windows/THIRD-PARTY-NOTICES.md) 与 `windows/docs/licensing/`。

### 最近更新（自动同步）

<!-- WINDOWS-SYNC:START -->
<!-- 由 scripts/sync-github-daily.ps1 自动生成：windows/ 最近 7 天提交。请勿手工编辑块内内容。 -->
- 0f6ff8c 2026-10-15 fix(windows): 改用 WinAppSDK 1.8 桌面文件选择器；并修掉音轨面板关不掉与列表闪烁
- 8fa94d1 2026-10-15 fix(windows): 文件菜单可寻址并记录选择器出口；记录 Gate B1 的确证缺陷
- 314b77b 2026-10-15 test(windows): Gate A Seek 与回环释放实测通过，并修掉 SeekSlider 缺失的 AutomationId
- f6356e2 2026-10-14 test(windows): Gate A 媒体路径实测通过 —— WebDAV GET/Range 与 libmpv 真实播放
- 9739cff 2026-10-14 fix(windows): 让来源卡片可被精确寻址，并证明 WebDAV 目录路径真正打通（W5.1 Gate A）
- c9cb20d 2026-10-14 feat(windows): 给 Browse-only 来源一个真正的用户入口（P0-A）
- 3bcd896 2026-10-14 fix(windows): 让 Space 真的能暂停播放，并停止把 29 MB 验收证据提交进仓库
- c3d957b 2026-10-14 feat(windows): LGPL engine built, Emby deployed, and the Emby regression that exposed
<!-- WINDOWS-SYNC:END -->

## 安全约定（强制）

- 禁止源码硬编码 Token / Cookie / 密码。
- Token 走 `core:security`（Android Keystore AES/GCM 加密存储）。
- 临时播放 URL 绝不落库（ADR-003），播放时由 Provider 动态解析。
- 日志统一经 `Redactor` 脱敏（Authorization / Cookie / Token / 密码）。

## 多 AI 协作

开始任务前先读：README / ARCHITECTURE / ROADMAP / TASKS / DECISIONS / HANDOFF。
完成任务后更新：TASKS / CHANGELOG / HANDOFF（重要决策同步 DECISIONS）。
