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
| 桌面集成：文件菜单、拖放、窗口状态恢复、文件类型关联（HKCU 登记，设置页可开关） | 提交 `5c232d6`（单测覆盖；GUI 逐项点击未单独验收） |
| **官方 Emby 4.10.x 端到端（W8 S2，43/43）**：库浏览（库根为「每片一个文件夹」）→ 详情 → 600 s 真实播放 → 中断回写 304.33 s → 完全重启后从 306 s 恢复 → 自然结束才 `played=True` → 离线与令牌吊销（401，日志无令牌） | `windows/docs/W8-EVIDENCE.md`（验收途中官方实例自动升级到 4.10.2.0，如实记录） |
| **Jellyfin 12.2 中断续播（W8 S1，29/29）**：中断 → 完全重启 → 引擎从 304 s 继续 → 自然结束才算看完；并更正 W4 的「中断恢复 FAILED」不是客户端缺陷 | 同上（S1.1 源码级确认：判据是片长是否达到 `MinResumeDurationSeconds`=300 s，180 s 夹具被服务端按「已看完且清零」处理） |
| **无窗口真服续播闭环（W8-H，17 + 16×2 断言）**：零窗口即可复跑 —— 探针全程走产品 provider 栈（`IMediaProviderFactory` → `IProviderAuth` → `IProviderPlayback` → `IProviderProgress` 上报/回读），两服各 16 条断言：40% 往返（ticks 完全相等、新客户端实例在续播列表可见、详情同值、`played=false`）、1% 低于下限清零、2% 按服务器取值（Emby 保留 / Jellyfin 清零）、看完置 `Played` 且退出续播列表 | `windows/docs/W8-HEADLESS-RESUME.md`（`windows/tools/acceptance/w8/headless-resume.ps1`：`RESULT: 17 passed, 0 failed`，两服探针各 `16 passed, 0 failed`，收尾 `no-test-server-processes-left running=0`；实质发现 Emby 4.10.2 续播下限是片长 **2%**，Jellyfin 12.2 仍是 5% + 300 s） |
| **WebDAV（Browse-only 来源）Gate A 全部项**：入口 → PROPFIND → 目录 → Range GET → 真实播放（R3）、播放中 Seek 与退出时回环释放（R4）、目录递进 / 返回上级 / 空目录 / 中文空格文件名（206 + `bytes=`）/ **关闭整个应用**释放回环（A3 第一部分 24/24）、子根挂载 / 404 / 403 / 500 / 401 / 慢服务器导航竞态 / 离线 → 重试（故障注入 54/54） | `windows/docs/W5-1-R3-GATE-A.md`、`W5-1-R4-GATE-A-SEEK-RELEASE.md`、`W7-EVIDENCE.md`（真实 WsgiDAV 4.3.3 + UI Automation） |
| **多文件临时播放队列（B2）**：原生多选 → 队列 → 上一项 / 下一项 / 移除 / 清空、EOF 自动跳项、失败跳项与连续失败上限、`AutoPlayNext=false` 负向对照 | `windows/docs/W7-EVIDENCE.md`（`r7-b2-queue.ps1` 63/63、`r7-b2-failure.ps1` 18/18） |
| **跨来源「继续观看」（U1）**：真实进度 → 跨来源列表 → 卡片续播 → 已看完消失 | `windows/docs/W9-EVIDENCE.md`（Emby 39/39、Jellyfin 39/39） |
| **剧集自动连播，含跨季（U2）**：同季跨集、跨季、关闭开关的负向对照 | 同上（35/35 × 2 服；`queue:auto-advance` trace + 服务器回读） |
| **收藏端到端（U3）**：详情页收藏 → 服务器回读 `UserData.IsFavorite` → 重启保持 → 取消 → 「我的收藏」页 | 同上（24/24 × 2 服；写端点是 `FavoriteItems`，`/Favorites/` 在真服不存在） |
| **永久播放列表（U4）**：加入 / 命名 / 落盘 `%LOCALAPPDATA%\MediaHub\playlists.json` / 列表页播放不改写列表 / 移除与删除 | 同上（40/40 × 2 服；与临时队列解耦，共 276 条 GUI 断言全绿） |
| 音频文件（`.aac`）真实播放，以及三条真实文件对话框路径：打开视频 / 打开文件夹 / 加载外挂字幕 | `windows/docs/W5-1-R6-AUDIO-PICKERS.md`（12/12、18/18；`PEAK=0.088583` 对齐 W3 基线，不是「静音也算通过」） |
| GUI 验收：播放控制 / 全屏 / 生命周期（可观测断言 + 证据文件） | `windows/docs/W5-GUI-ACCEPTANCE.md` |
| 便携版构建（331.6 MB，可启动） | `CAPABILITY-MAP` 测试与交付表；MSIX / 代码签名 / 干净机安装仍未做 |
| LGPL libmpv 引擎候选已实际构建（**尚未切换到产品**） | `windows/engine/lgpl/dist/` |

### 已知缺陷与未验证项

- **进度回写的服务端语义**：中断位置写入与「完全重启后恢复」已在 Jellyfin 12.2 与 Emby 4.10.x 实测；
  但多客户端并发、快进后未达阈值等服务端判定细节未测。W4 记录的「中断恢复 FAILED」已更正为
  **夹具片长低于服务端阈值**（`MinResumeDurationSeconds`=300 s），不是客户端缺陷。
  W8-H 的无窗口闭环补上了「低于下限怎么处理」这一半：Jellyfin 12.2 丢弃 5% 以下，Emby 4.10.2 丢弃 **2% 以下**，
  且两者都是**清零并移出续播列表**而不是忽略；阈值是按片长百分比而非绝对秒数（600 s 夹具上 1.99% 丢弃、2.00% 保留）。
- **真服覆盖面是单机单实例**：Emby 与 Jellyfin 各只在隔离实例上验证一次；服务端版本差异会改变行为
  （W8 验收途中 Emby 自行从 4.10.1.0 升级到 4.10.2.0），未做多版本矩阵。
- **搜索**：多源搜索仅有模拟服务器与单测，服务端搜索接口未接入，真服路径未测。
- **体验缺口**：「继续观看」卡片海报未懒加载（行可点、海报位为占位图标，属已知缺口）；
  永久播放列表内拖拽排序仅单测覆盖（`NOT_RUN`）。
- **NOT_RUN / IMPLEMENTED_UNVERIFIED**：HDR 与色彩管理（不声称任何 HDR 能力；杜比视界 `NOT_SUPPORTED`）、
  硬件解码故障回退、全屏切换的截图确认、字幕样式（字体 / 字号 / 颜色 / 描边 / 位置 / 延迟 / ASS 覆盖）、
  季 / 集导航的专项断言（U2 已在真服按季 / 集选中并播出）、图片缓存上限与磁盘 TTL、
  TUN / VPN / PAC 出口一致性与 SOCKS 代理、真实第二 Windows 账户下的凭据库行为、
  Intel / AMD / NVIDIA 硬件兼容矩阵。
- **尚未实现**：音频独占 / HDMI 直通 / 多声道 / 音画同步偏移；系统媒体按键、任务栏状态、多窗口；
  Plex / SMB / 云盘媒体源。
- **W6 跨设备来源同步**（[PR #39](https://github.com/fwzm/MediaHub/pull/39)，stacked 在 `feature/windows-client` 上，仍为 Draft）：
  契约层、配对载荷/状态机/传输层（回环）、双向增量与设备注册表、凭据迁移（DPAPI 重封）、加密离线归档、同步 CLI、
  同步页「预览 → 逐条审批 → 应用 → 推进基线」、基线落盘与重启幂等、已配对设备登记与指纹钉扎、
  已配对设备清单与「忘记」、同步 CLI 的 `baseline` / `pair` 命令组（离线查询与预览，
  不监听端口、写操作默认 dry-run）、回环传输与双向回环编排（导出 → 帧分块传输 → 对端导入轮，
  往返幂等、墓碑需显式确认）、同步页/配对页的可判定逻辑（抽成无 WinUI 依赖的 presenter）、回环传输的可选对端认证
  （W6-24：`transfer … --authenticate --secret-env NAME`，口令只从环境变量进、不进命令行与 JSON；
  开启时在最后一个数据块之后追加一个 `0x03` tag 帧（33 字节 = 1 字节帧类型 + 32 字节 HMAC-SHA256），
  关闭时线上字节与 W6-16 全等，重放按进程内 64 条 nonce 窗口拒绝）、来源同步传输的 TLS 1.3 与公钥指纹钉扎（W6-26：`transfer serve --tls` 在 stderr 打 `certificate fingerprint:`，`transfer send --tls --expect-fingerprint` 只信任钉住的那一枚，四个 `tls-*` 稳定码；默认关闭时线上字节与 W6-16 全等）、可选投递确认（W6-30：`--confirm-delivery` 要等到对端关闭连接才认定送达，否则 `delivery-unconfirmed` 而不是静默成功）、传输层恶意与畸形帧回归（W6-31：36 例、十类攻击面，接收端永不把异常抛给调用方）、同步页的传输卡片（W6-33：本机回环收/发、收到的信封只进预览绝不自动应用、20 个稳定码逐条文案，抽成无 WinUI 依赖的 `SyncTransferPresenter`）、同步页 presenter 与真实 `SyncCli` 子进程的 headless 端到端（W6-35：独立测试工程、不入 sln、9 例全部真起子进程，主套件例数不变）、反向回执帧（W6-37：`transfer serve --send-receipt` 只在信封解码成功后回一个裸 `0x04` 字节，`transfer send --await-receipt` 没等到就报 `receipt-missing`/`receipt-timeout`/`receipt-invalid`/`receipt-not-enabled`，不再把「对端关连接」当送达证据；默认关闭时线上字节仍与 W6-16 全等，同批修掉 W6-26 握手「超时 vs 调用方取消」的分类竞态）、回执帧接进同步页与双向编排（W6-39：接收侧解码成功才回 `0x04`、发送侧可等回执、roundtrip 内部默认交换回执，回执失败只作 advisory、不改轮结果）、同步页上的两个回执控件（W6-43：接收后写回执 / 等待对端回执各一个勾选框与一行回执结论，`receipt-*` 四码原样呈现，`receipt-missing` 不软化成「已发送」；GUI_UNVERIFIED）、TLS × 认证 × 回执的组合矩阵验收（W6-45：验收脚本 14 → 18 例、presenter-e2e 12 → 14 例，全开 / 口令不一致 / 错 pin + 认证 / 明文对 TLS 四种组合各自断言稳定码与接收端零信封写入；口令不一致时发送端只能观察到 `receipt-missing`（信封单向，不能凭缺失猜原因），serve 侧则必须不出现任何 `receipt-*`）、发送端投递确认（W6-46：Android 侧对齐 Windows W6-30，写完最后一帧后读对端直到 FIN 才算送达，`delivery-unconfirmed` 是独立单码封闭集，预算 2000 ms、最多排水 64 KiB、回执优先；默认关闭时不读一个字节、线上字节与 W6-22 向量逐字节相等；界面开关见 W6-48）、跨机（非回环）传输的契约 ADR（W6-47：现状硬边界、信任模型、十条实现约束、错误码归并结论与 DoD 证据表全 NOT_RUN，只在 W6 分支上的文档、不改代码）、同步页补上两个协议开关（W6-48：发送卡片可勾「等对端关闭连接（投递确认）」、监听卡片可勾「收下后回 0x04 回执」，回执优先所以勾着等回执时投递确认勾选框直接禁用；两者接进既有表单字段，8 例 androidTest 全部先滚动到页尾再断言、主流程仍在首屏）、
  发送卡可以在途取消（W6-49：`SourceSyncSendLauncher` 只做 `Job` 记账、不持信封与口令，`sync.send.cancel` 按钮只在发送中可用，取消后状态回 Idle 且不产生失败码、释放单飞后可立刻重发、没在发时是 no-op；JVM 8 例 + androidTest 6 例）、`transfer serve` 的接收预算可调（W6-50：`--budget-ms` 默认仍是 15000 ms、取值 100..600000、只对 serve 生效，非法值报 `invalid-budget-ms:<值>`，`--json` 多一个 `budgetMs` 字段、文本模式多一行 `budget: N ms`；19 例单测 + 验收脚本 S1/S2 分别断言「预算到点报 `transfer-timeout`」与「命令值被原样回显」）、跨机（非回环）的第一个入口——显式单播主机（W6-51：`transfer serve --bind ADDR` 与 `transfer send|serve --allow-non-loopback`，同意后只接受一个单播字面量，通配 / 广播 / 组播 / 保留段 / 主机名仍在建 socket 之前就拒掉、因此不触发防火墙弹窗；复用冻结的 `not-loopback` 码、把诊断写进拒绝句子的取舍，已作为对 W6-47 ADR §4.5 第 2 条的显式偏差记进 `docs/cross-device/W6-51-EXPLICIT-HOST.md`；40 例单测；跨机证据 E1–E6 仍 NOT_RUN）、Android 监听端也可以绑一个显式单播地址（W6-52：监听卡的 `sync.listen.host` 输入框 + `sync.listen.allow-host` 复选框，与 Windows W6-51 同名同义——留空仍是 127.0.0.1，同意后只接受一个单播字面量，通配 / 广播 / 组播 / 保留段与主机名在建 socket 之前就拒，码仍是 `not-loopback` 并由四条 detail 句子承载诊断（与 W6-52 之前的拒绝句逐字相同的那条保留给「没开开关」）；地址判定收在一处 `SourceSyncHostAddress`，界面层只原样转交不 trim 不判；JVM 14 + 5 例、androidTest 3 例；发送端与拨号接收卡的同类开关见 W6-52b）、Android 监听端可以要求对端口令（W6-53：监听卡的 `sync.listen.auth` 开关 + `sync.listen.secret` 口令框（密码遮罩、只进内存、`toString` 打 `secret=<redacted>`），与 Windows `transfer serve` 的 `--authenticate --secret-env` 同名同义——四个线上码 `auth-required` / `auth-mismatch` / `auth-replayed` / `auth-secret-unavailable` 原样透传、界面不新增码；开了认证却没填口令时在**建 socket 之前**用本地码 `listen-secret-missing` 拒掉，工厂的 `open` 一次都不调；JVM 5 例 + androidTest 3 例）、Android 监听端的等待预算可调（W6-54：监听卡的 `sync.listen.budget` 输入框，一个框管两段等待——等第一条连接与连上之后每次读，范围 100..600000 毫秒、留空仍是 15000，与 Windows W6-50 的 `transfer serve --budget-ms` 同值同义；非法值在**建 socket 之前**用本地码 `listen-budget-invalid` 拒掉，工厂的 `open` 一次都不调；预算只决定本地等多久，不进信封、不进 tag 帧、不进回执；JVM 5 例 + androidTest 4 例）、门禁补钉三套件（W6-55：`SourceSyncReceiptTest` 33 例、`SourceSyncHostAddressTest` 14 例、`SourceSyncListenerControllerTest` 43 例进 `--required-suite`，33 → 36 个套件；改完同步跑了 `.github/scripts/tests` 的 54 个 unittest 全绿）、Android 拨号侧也有显式单播地址入口（W6-52b：发送卡的 `sync.send.allow-host` 与「接收」卡的 `sync.receive.allow-host` 两个复选框，把 W6-52 在监听端做过的同一件事补到拨号方向——判定仍只有一处 `SourceSyncHostAddress`，只是加了个 `verb` 参数（监听端说「绑定」、拨号端说「连」），所以「开关关着」时那句拒绝文案与 W6-23 起的原文逐字相同；同意后只接受单播字面量，通配 / 广播 / 组播 / 保留段与主机名在拨号之前就拒，码仍是 `not-loopback`、诊断由四条 detail 句子承载；控制器自己不判地址也不 trim，只把开关原样转交；JVM 3 + 3 例、androidTest 4 例；真实两机仍 NOT_RUN）、门禁补钉第四件（W6-56：`com.mediahub.core.common.sync.SourceSyncTransferClientHostTest` 11 例进 `--required-suite`，36 → 37 个套件；钉的是拨号侧那条地址判定——开关关着时拒绝句必须与 W6-23 起的原文逐字相同、通配 / 广播 / 组播 / 主机名在拨号之前就拒、本机一次都不真拨非回环地址；改完同步跑了 `.github/scripts/tests` 的 54 个 unittest 全绿）、Android 回环四维组合矩阵（W6-57：`SourceSyncTransferMatrixTest` 共 20 格，把 TLS / 认证 / 回执 / 投递四个开关放在同一条真实回环 socket 上逐格跑——每格起一枚真监听端与一枚真客户端，断言外部可观察结果与稳定码；回执开了就压掉投递（`delivery = null`）、口令错的真相只落在监听端（发送端不读回话，只能报成功）、TLS 之后四码与明文逐字相同；每个开认证的格子都必须同时宣布挑战（tag 的 MAC 输入是「挑战 ‖ 信封」，没有挑战就没有 nonce，发送端本地 `auth-required` 且一个字节都不写），JVM 20 例、进 `--required-suite`（37 → 38 个套件）、真实两机仍 NOT_RUN）、接收端也能中途取消（W6-58：接收卡的 `sync.receive.cancel` 按钮只在接收中可用，取消了不是「等超时」——先置位再关掉本地连接，被阻塞的读立刻返回，界面回 Idle 且不留任何失败码；本地码 `transfer-cancelled` 只活在进程内，不进跨机五套码集合（`SourceSyncTransferReasons.ALL` 仍是 10 个）；取消后可立刻再收一次，没在收时是 no-op；JVM 6 + 5 + 8 例、androidTest 4 例；真实两机仍 NOT_RUN）、Android 有了第一个配对入口（W6-59：设置页的「配对设备」按钮进入页内托管的配对页（tag `pairing.entry` / `pairing.screen`），贴 `mediahub://pair?...` 链接后按 `PairingLink.parse` 核对——合法链接只显示设备标识、公钥指纹（16 组 4 字符大写）与剩余秒数，**只核对链接、不连接对端**（第二段已在 W6-59b 实现）；七类失败（`pairing-link-empty` / `not-a-pairing-link` / `pairing-link-too-long` / `not-base64url` / `payload-invalid` / `expired`）全部原样透出 core:common 的契约码，本层不自造 Android 专用码，核对成功也不把挑战或链接原文写进状态与结果行；JVM 13 例、androidTest 7 例、进 `--required-suite`（40 → 41 个套件）、真机仍 DEVICE_UNVERIFIED）、配对入口的第二段「真正拉一次邀请」（W6-59b：`PairingFetchPort` 只有四个参数、没有第五个信任根 —— 链接里那一枚指纹同时用于 TLS 1.3 SPKI 钉扎与载荷指纹校验；`PairingLinkController.verify()` 仍然一个字节都不发，`fetchInvitation()` 才做一次 TLS 连接，且先过三道闸门（没核对过 → `pairing-not-verified` 不拨号；链接里没有端口 → `pairing-fetch-unavailable` 不拨号；上一次还在途 → 返回 false 且只拨一次），两个前置条件码只属于本层、不进 core 的五个码集合；拉回来的邀请按核心码原样透出（`fingerprint-mismatch` / `challenge-mismatch` / `lifetime-exceeded` / `payload-invalid`），状态行与结果行都不写挑战、指纹原文与链接原文；配对页新增「拉取邀请」按钮（`pairing.fetch`，未核对或在途时禁用），并在页尾写明「不写任何凭据、不建立同步」；JVM 由 13 例加到 22 例（W6-59b 增 9 例）、androidTest 由 7 例加到 12 例（W6-59b 增 5 例）、core 侧 `PairingTls` + `PairingTransportTlsTest` 13 例一起进 `--required-suite`（41 → 42 个套件）、真机与真实跨端拉取仍分别是 DEVICE_UNVERIFIED 与 NOT_RUN）、
   配对入口的第三段「本机可被连的地址」（W6-60：把 `PairingAddressCandidates` 接上 Android 网卡枚举 —— 新增 `PairingAddressEnumerator`（`fun interface PairingNetworkInterfaceSource` + `JvmNetworkInterfaceSource` 真读 `java.net.NetworkInterface`）与纯展示的 `PairingAddressSummary`，页面页尾多一块只读的「本机可被连的地址」，**没有任何「开始监听」按钮**、不 bind 不 dial，与 Windows 权威实现 `PairingAddressCandidates.cs` 的 display-only 立场同义；接口分类顺序回环 > 隧道（`virtual` / `pointToPoint` / `tun|tap|ppp|ipsec|pptp|l2tp|wg` 前缀）> 无线（`wlan|wl|wifi|swlan` 前缀）> 有线，地址只收 IPv4 与 Windows 一致；枚举异常一律落既有码 `address-enumeration-failed`、空表落 `no-usable-address`（两者刻意分开），适配层从不抛出、不自造 Android 专用码；地址流与链接状态是两条独立 `StateFlow`，候选层的 9 例单测与跨端黄金向量一字未动；JVM 8 + 5 + 6 例、androidTest 7 例、进 `--required-suite`（42 → 43 个套件），真机网卡组合仍 DEVICE_UNVERIFIED）、
   监听卡的「取消这一次接收」（W6-61：缺口表 #8 的后半条补齐 —— 监听卡原先只有「停止监听」，按下「等一次发送」后没有退路；**取消不能借用 `close()`**，因为那会永久废掉监听端口、并把用户的动作翻译成 `listener-failed`。做法是给 `SourceSyncTransferListener.receiveOnce` 加第五尾参 `cancel: SourceSyncCancellation? = null`，把 `accept()` 切成 `ACCEPT_SLICE_MS = 50` 的短切片轮询（总 deadline 仍是 `acceptTimeoutMs`、到点仍报 `transfer-timeout`），accept 成功即 `attach(socket)`、`finally` 首行 `detach`；协议层自己把「连接被本地关掉」折成 `io-failed` 的那条路径也在 `receive(...)` 的 Failed 分支统一改判（只改写失败结果，已收全的信封不被晚到的取消抹掉）。取消沿用 W6-58 的本地码 `transfer-cancelled`，五集跨机码表一字未动；界面层取消把状态置回 `Listening`（不是 `Idle`）且 `codeOf == null`，监听仍开着、可立刻再等一次；`listener-failed` 从此只剩「真的 close 过」一种来源；新增 `SourceSyncListenLauncher`（与 `SourceSyncReceiveLauncher` 同形，取消不关监听）+ `sync.listen.cancel` 常驻禁用按钮；JVM 7 + 3 + 7 例、androidTest 6 例、进 `--required-suite`（43 → 44 个套件），真实两机取消行为仍 NOT_RUN）；
   离线加密归档的读与写（W6-62：把 Windows ADR-S005 的 `.mharchive` 契约移植到 Android `core/common` —— 缺口表里「离线加密导入导出」在 Android 侧此前是零（`sync` 包三十个生产文件无任何 `Archive*` 对等物），本轮新增 `archive` 包六个纯内存文件：`ArchiveError`（十二个稳定码，与 Windows 同名同序）、`ArchiveKdf`（PBKDF2-HMAC-SHA256、salt 16 B、密钥 32 B、迭代下限 600 000）、`ArchivePassphrase`（最短 12 字符 + 二十四条弱口令表 + 单字符/全数字拒绝）、`ArchiveHeader`（`MHSYNCA1` + 大端 int32 长度 + 明文 header，固定字段序）、`ArchiveReader`（十步判定序，框架错误先于口令错误）、`ArchiveWriter`（口令 → 凭据 → 迭代数 → 载荷上限五步判定序，写侧拒绝而不是静默剥掉凭据）。AAD 是 `magic + BE int32 + headerJson` 原字节，与既有 `.mhbackup`（magic `MHBK`、salt 32 B、AAD 是字符串）是两套不可互换的格式；零新依赖（`javax.crypto` 的 `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding`）。用临时 net10.0 生成器（`D:/mh-verify/archive-vectors`，ProjectReference 指向权威 `MediaHub.Sync.csproj`）冻结黄金向量落 `core/common/src/test/resources/archive/windows-archive-vectors.json`（49950 B）：header 161 B 逐字节相同、archive 1282 B / sha256 `17514e08…ee21` 由 Kotlin 复算逐字节相同、19 条错误向量与 9 条 writerCases 逐条同码、口令策略二十四条逐字同序；另补纯 Kotlin 回环 23 例（篡改三处、双侧凭据拒绝、口令 11 拒 12 收、中文口令、归档字节不含明文）。顺带修掉一个跨端契约真缺陷：`"secrets": null` 在 Kotlin 里是非 null 的 `JsonNull`，第一版会把 Windows 允许写的信封判成 `secrets-not-allowed`，已改为同时排除 `JsonNull`。JVM 15 + 23 例、进 `--required-suite`（44 → 46 个套件）；未接 CLI/界面/数据库、未上真机、真实两机搬运仍 NOT_RUN）、加密归档接进 Android 导入入口（W6-63：把 W6-62 的读端接到既有那条导入链上——选文件 → 预览 → 逐条允许 → 应用，不新造第二条流程。分流落在 `core/database` 的 `SourceSyncDocumentInput`：前 8 字节不是 `MHSYNCA1` 就当明文原样走老路径（宽松 UTF-8 → codec → 网关，`bytes.toString(Charsets.UTF_8)` 逐字节不变），是归档且没口令就报 `passphrase-required` 且网关零调用，有口令则 `ArchiveReader.tryRead`，失败码（结构 7 个、口令策略 2 个、`wrong-passphrase-or-tampered`、`malformed-payload`、`secrets-not-allowed`）原样透出，错口令与篡改同码、不造 oracle。接在 `SourceSyncTransferImportService.importEnvelopeBytes` 前面一处，拨号路与监听路同时受益；传输路没有口令通道，所以线上真收到归档只报 `passphrase-required` 而不是解开它——「口令不过网」是有意的。界面侧选文件改成读字节（原 `readEnvelope` 只读文本会把二进制读坏，已删），`SourceSyncArchiveController` 只在私有字段暂存密文、对外只暴露 `pendingByteCount`，口令只活在口令卡的局部 `remember` 里、用完即弃；`SourceSyncImportCopy` 加两条中文文案且 `blockedText` 返回自身，ArchiveError 原码只出现在错误行。与 Windows `SyncCliRunner.TryResolveEnvelope` 有两处有意差异并写进文档：没有退出码分类（Android 没有退出码，码本身就是分类）、没有 `archive-not-supported-by-plan`（Android 的预览发生在解密之后，这条分支没有调用方）。JVM 12 + 8 例、androidTest 5 例、进 `--required-suite`（46 → 47 个套件）；导出侧零实现、真实两机搬运与设备侧 Conscrypt 耗时仍分别是 NOT_RUN 与 DEVICE_UNVERIFIED）、导出侧的「另存为文件」（W6-64：把 W6-62 的写端接到导出卡上，补的正是 W6-63 留下的「导出侧零实现」——不新造导出管线，复用的还是发送卡那条 `AndroidSourceSyncService.export()`，只是多接一个出口。口令留空 = 明文信封，交出的是发送卡那份 JSON、`plaintextExportKeepsTheExportedJsonWithoutReEncoding` 断言逐字节相等（不重新编码）；填口令 = `ArchiveWriter.write` 包成 `.mharchive`，迭代数 600 000、归档头时间戳取信封自己的 `createdAtEpochMs`（对齐 Windows `SourceSyncSession.Export` 的 now 语义）；口令策略 / 凭据拒绝 / 载荷上限一条都不复制，全由写端判，`ArchiveException` 的码原样透出（`passphrase-too-weak` / `passphrase-empty` / `secrets-not-allowed`）。空白口令在核心层仍报 `passphrase-empty`，绝不悄悄退化成明文导出——界面层的「留空」是显式传 null，两件事分开。状态机 `SourceSyncArchiveExportController`: Idle → AwaitingPassphrase → Ready → Saving → Saved | Failed`，生成物只活在 `Ready` 里、对外只暴露 `pendingByteCount`，写盘复用既有 SAF 设施 `BackupFileStore.write`，失败折成本层两个本地码 `archive-export-stream-unavailable` / `archive-export-write-failed`（五集跨机码表仍是 10 个）。与 Windows 有一处历史遗留：CLI 加密导出默认名是 `.mhsync`、Android 统一 `.mharchive`，同一字节格式、只是默认名不同，已在 §37.8 如实记。JVM 8 + 11 例、androidTest 7 例、进 `--required-suite`（47 → 48 个套件）；真实两机搬运与设备侧耗时仍 NOT_RUN / DEVICE_UNVERIFIED）；
   反向互操作：Android 写出的 `.mharchive` 由 Windows 读（W6-65：§35–§37 三节都是「Windows 权威实现 → Android 逐字节对照」的单向证据，
   本节补反方向——把真实 Kotlin `ArchiveWriter` 产出的 1164 B 归档（sha256 `72365f67…4a15`，头部固定 161 B，信封含中文名「家庭 NAS（客厅）」与全角括号、
   未知 sourceType `acme-nas`、缺省 `accountScope` 与 `secrets`、多端点含非主用、一条墓碑）挂进 Windows 测试工程，新增 `ArchiveAndroidInteropTests` 8 例读它：
   逐字段、未知类型原样、字节钉扎、错口令与篡改同码、框架错误优先于口令、归档不含明文；顺带补上 Android 写端 8 MiB 拒绝的行为证据（此前只有常量断言）。
   生成器跑完即删、资产是唯一事实来源；本机 C# 1585 通过（W6 分支）/ Android core/common 596 例。唯一新发现写在 `docs/cross-device/W6-65-ANDROID-ARCHIVE-INTEROP.md` §5：
   `System.Text.Json` 把 `+` 转义成 `\u002B` 而 kotlinx 原样写，头部「同值但不同字节」，GCM 认证的是磁盘原字节所以无害——
   教训是不要用「重序列化后字节相等」当跨语言契约判据；真实两机搬运与设备侧耗时仍 NOT_RUN / DEVICE_UNVERIFIED）；
   归档扩展名统一（W6-66：W6-64 的导出卡把 Android 的加密归档默认名定成 `sync-envelope.mharchive`，而 Windows CLI 从 ADR-S005 起一直写 `.mhsync` —— 两端是**同一套字节格式**（magic `MHSYNCA1`、PBKDF2 600 000 轮、AES-GCM、AAD = magic + BE int32 + headerJson），差别只在默认名一个字。
   本轮只做命名归一：CLI 默认名改为 `sync-envelope.mharchive`，旧名降级为 `SyncCliRunner.LegacyArchiveFileName` 只读兼容；**不加 CLI 参数、不加读端扩展名白名单**——两端读端本来就按前 8 字节 magic 嗅探、从不按扩展名分支，加白名单反而会破坏「明文信封改名成 `.mharchive` 仍按明文读」这条行为。
   界面侧同步页选择器改为 `PickFileAsync([".json", ".mharchive", ".mhsync"])`、两处说明文案跟着改；Android 侧只改提示文案（`SourceSyncImportCopy.PICK_HINT` 说清「扩展名只是提示，真正的依据是文件内容」），不改任何行为。
   三个新 C# 用例分别钉「规范名落盘且旧名不再写」「旧后缀归档仍能读」「明文信封改名成归档后缀仍按明文读」，后两条是反向对照（只钉默认名变了的话，把读端改成按扩展名分支也能绿）；验收脚本 14 → 16 项、`SyncCliTests` 36 → 39 例。
   本机 C# 1588 通过 / 0 失败（1585 → 1588）、验收 16/16 PASS、Android 三模块 596 + 192 + 354 = 1142 例全绿；§37.8 第 4 条与 §37.9 第 3 条已回标关闭。真实两机搬运与设备侧耗时仍 NOT_RUN / DEVICE_UNVERIFIED）；
   跨语言文本边界契约（W6-67：W6-62/63/64/65 的归档契约此前只建立在「两边都收到 ASCII 与合法 UTF-16」这条默许假设上，本轮把假设写成契约——
   空白统一为 Unicode White_Space 属性的 25 个 BMP 码点（JVM 的 `Character.isWhitespace || isSpaceChar` 多收 U+001C–U+001F 而少收 U+0085，Java 的 `String.trim()` 又只剥 ≤U+0020；两端各多一个 `WireText` 显式码点表，五个消费点全部改用它），
   大小写只折 ASCII（JVM `equalsIgnoreCase` 会把长 s U+017F 折成 s、把开尔文 U+212A 折成 k，.NET `OrdinalIgnoreCase` 两个都不折，所以弱口令表与 kdf 名判定都不再用语言自带的折叠），
   孤立代理项在口令策略、信封读写与归档头解析上面全部拒（此前 Windows 派生 PBKDF2 直接抛未捕获的 `EncoderFallbackException`、Android 用 `PBEKeySpec` 静默换成 `?`，写出一份对端永远读不开的归档；C# 读端 `JsonDocument.Parse` 收下的转义孤立代理项会在 `GetString()` 抛 `InvalidOperationException` 穿透调用方，写端 `Utf8JsonWriter` 会把它静默改写成 U+FFFD）；黄金向量新增 `edges` 段 10 组（25 个空白码点 / 13 条空白候选 / 9 个 kdf 名 / 6 组大小写 / 7 个代理项 / 11 条口令 / 10 个头部变体 / 11 个信封变体 / 4 条写侧拒绝 / 5 条归档口令），老 12 段逐字节不变；
   两端各 11 例新用例逐条消费 `edges`，本机 C# 1599 通过 / 0 失败（1588 → 1599）、Android 三模块 607 + 192 + 354 = 1153 例全绿；`--required-suite` 48 → 49；真实两机搬运与设备侧耗时仍 NOT_RUN / DEVICE_UNVERIFIED）；
   8 MiB 上限附近的真数据穿越 AES-256-GCM（W6-68：归档层从 W6-62 起就有 8 MiB 载荷上限，但两端此前只有「超限就拒」的用例（Kotlin 用 9 000 000 字节、C# 用 `MaxPayloadBytes + 1`），
   从没验证过**恰好落在上限**的载荷能不能走完加密、写盘、读回、GCM 校验这条链路——写端判据是 `payload.size > MAX`、读端是 `cipherLength > MAX`，任何一处写成 `>=` 都会让合法的 8388608 B 载荷被拒，
   而既有用例全是 9 MB 级、永远碰不到这个边界。做法是只存**配方与哈希**、不把 8 MB 归档入库：一个 source 的 ASCII `displayName` 重复到精确长度（`displayNameLength = 8388242`）令载荷恰好 8388608 B，
   用固定 salt（0..15）/nonce（16..27）让两端手工加密出**同一份**归档（两端 `write()` 都自取随机 salt/nonce，所以真实写端只能断言「写→读回语义相等」），向量 `nearLimit` 段只记长度与 sha256；
   Kotlin 与 C# 各 5 例同义用例（载荷 sha256 `f6bc78f3…ba11ae`、归档 8388797 B / sha256 `b47b3cc3…2edc287`、头部 161 B / prefix 173 B、恰好上限可写可读、MAX+1 写端拒、读端在解密前就拒超长密文）；
   本机 C# 1604 通过 / 0 失败（1599 → 1604）、Android 三模块 612 + 192 + 354 = 1158 例全绿；`--required-suite` 49 → 50；真实两机搬运与设备侧耗时仍 NOT_RUN / DEVICE_UNVERIFIED）；
   配对侧文本边界统一到共享 `WireText`（W6-69：W6-67 把归档侧的口令/头部/信封文本边界写成了跨语言契约，但**配对侧**是另一套代码——Kotlin `core/common/src/main/kotlin/com/mediahub/core/common/pairing/` 与 C# `windows/src/MediaHub.Sync/Pairing*.cs` 各自在用语言自带的 `isBlank()` / `trim()` / `ignoreCase` / `uppercase()` / `CASE_INSENSITIVE_ORDER`，而这些语义两端并不一致：同一份 `deviceId` 会一端判空一端不判空、同一份 `ephemeralUrl` 会一端 `ok` 一端 `bad-url`。本轮把配对侧全部对外契约调用点换到共享 `WireText`（两端各扩 4 个助手），判据不是代码风格统一，而是「同一份输入两端必须给出同一个 reason」）；
   修掉的两个真实缺陷：①C# 读端对转义孤立代理项抛**非契约**的 `InvalidOperationException`（`JsonDocument.Parse` 接受、`GetString()` 才炸，穿透 `catch (JsonException)`），而 Kotlin 侧会一路静默接受 ⇒ 两端 `decode` 各加一次**文档级** `containsLoneSurrogate` 检查，命中即 `malformed-json`（未知字段里的孤立代理项也拒，因为重序列化会被静默改写成 U+FFFD）；②Kotlin 的 `validateEphemeralUrl` 先 `trim()` 再 `URI()`、C# 把未 trim 的原串直接交给 `Uri.TryCreate`，实测 .NET 容忍 U+0020/U+0009 前后缀但不容忍 U+0085/U+00A0 ⇒ 同一份 URL 两端结论分叉，现两端都先 `WireText.Trim`、主机判空改用 `WireText.IsBlank`；
   另修掉一处**向量自身的缺陷**：`expired` 那条链接按 `Now + LifetimeMilliseconds` 构造、却用冻结的 `frozenNowEpochMs` 去解析，等于从未真的过期（C# 孪生用例首跑 9/10 就红在这里），修法是让每条 link 向量自带 `nowEpochMs`——凡依赖外部时钟的向量，时钟本身也必须进向量；
   黄金向量新增 `textEdges` 段 10 组（25 个空白码点 / 14 条空白候选 / 8 组大小写折叠 / 9 条大小写变换 / 8 组前缀 / 8 组比较 / 7 个代理项 / 16 条链接 / 18 条载荷 / 7 个含代理项的载荷文档），老 11 段逐字节不变；两端各 10 例新用例逐条消费 `textEdges`，本机 C# **1614** 通过 / 0 失败（1604 → 1614）、Android 三模块 **622 + 192 + 354 = 1168** 例全绿；`--required-suite` 50 → 51；真实两机配对与设备侧行为仍 NOT_RUN / DEVICE_UNVERIFIED）；
  审批门禁的「跳过即不写入」（同一身份 allow 与 skip 并存时 skip 胜）、同步 CLI 的 `--root` 误指已存在文件改为稳定拒绝
  （`root-not-a-directory`，每份 JSON 带 `rootExisted` 区分「新建根」与「路径写错」）与导出路径规范化
  （`../` 解析后再回显，`--out` 指向目录给 `output-is-directory`）均已实现，
  `MediaHub.Tests` **1614 通过 / 0 失败**（其中 W6 新增 1145）；
  W12b 对抗性安全回归 66 例（畸形配对载荷、设备登记表在损坏/容量满/独占/只读时必须拒绝而不是静默清空、
  传输层声明长度不触发分配、`--json` 单文档、异常与 stderr 不含口令）；
  **尚未做**：二维码与扫码、跨机（非回环）配对与真实局域网传输（Windows 端已有显式非回环入口 W6-51，但一次真实跨机传输都没跑过；Android 的监听端（W6-52）与拨号侧两张卡（W6-52b）也都有同形状的地址闸门开关）、设备持久化、同步页/配对页/已配对清单的 GUI 证据
  （三者均为 `IMPLEMENTED_UNVERIFIED`，本机没人观看窗口时无法跑）、Android 端接收页的 GUI 证据与真实跨机（非回环）传输
  （Android 侧的契约/规划/导入导出/配对契约/分块传输协议/「回环传输 → 导入闭环」已就位，
  协议与 Windows 逐字节对齐，见 `docs/android-source-sync-transfer.md`；Windows 端已有可选的传输对端认证
  （Windows W6-24 / Android W6-25，五个稳定码与帧格式两端同名同序）；TLS 1.3 与公钥指纹钉扎两端也都就位（Windows W6-26 / Android W6-29，四个 `tls-*` 码同名同序，Android 用 SPKI 而非整证书算指纹），并有与 Windows 真子进程的双向联调（W6-32，JSSE ↔ SslStream）；仍缺：跨机（非回环）的真实证据——Windows 的地址闸门已由 W6-51 显式放开（`--bind` 加 `--allow-non-loopback`，只接受单播字面量），但一台机器证明不了它，E1–E6 全 NOT_RUN，Android 的监听端也已有同形状的地址入口（W6-52：`sync.listen.host` + `sync.listen.allow-host`，只收单播字面量）；拨号侧（发送卡与「接收」卡）的同形状开关也在 W6-52b 里补齐（`sync.send.allow-host` / `sync.receive.allow-host`）（Android 发送端的投递确认已由 W6-46 补上、界面开关见 W6-48，但默认仍关闭）；Android 侧已有回环监听端（W6-34：`SourceSyncTransferListener` 只绑回环、可连续 accept，`SourceSyncListenerImportService` 把它接进导入闭环，Windows 的 `transfer send` 可以直接推到 Android 的监听端口，界面入口也已就位（W6-36：同步页的「接收（监听）」区块可以开一个回环监听、等一次发送，收到的信封只进预览、写入仍要逐条允许后再应用；今天 App 里没有证书下发通道，所以 TLS 开关如实禁用、只能明文）；Android 端也已对齐回执帧（W6-40：`receipt-*` 四码与 Windows 同名同序，监听端只在信封解码成功后回一个裸 `0x04` 字节、gate 在端点层，拨号端可等回执，默认关时线上字节与 W6-22 的冻结向量逐字节相等；界面还没有开关）；导入页的两张接收卡片按 W6-41 移到页面末尾，首屏保留「选择文件 → 预览 → 应用」主流程（此前它们被插在按钮行之前，把主流程挤出首屏，CI 的 visual-instrumentation 上两次 Android run 就红在这 6 例首屏可见性断言上）；Android 也已是发送端（W6-42：同步页末尾的「发送（Android → Windows）」区块把本机导出的信封推给 Windows 的 `transfer serve`，可选 TLS 指纹钉扎 / HMAC 认证 / 等回执，默认全关时线上字节与 W6-22 冻结向量逐字节相等；本层只补 5 个「还没拨号就拒」的码，其余码原样透传；在途发送可以取消（W6-49：新增 `SourceSyncSendLauncher` 只做 Job 记账、不持信封与口令，`sync.send.cancel` 按钮发送中才可用，取消后状态回到 Idle 且不产生任何失败码，释放单飞后可以立刻重发；真实 Socket 中途取消与真机按钮仍是 `NOT_RUN` / `DEVICE_UNVERIFIED`），Android → Windows 的发送方向也还没和真 CLI 对跑）；三张卡（接收 / 监听 / 发送）另有 47 例 instrumentation 证据（W6-44 的 19 例、W6-48 的 8 例、W6-49 的 6 例、W6-52 的 3 例、W6-53 的 3 例、W6-54 的 4 例与 W6-52b 的 4 例：W6-52 那 3 例钉住地址框与开关在树里可交互、表单逐字回传、监听中两者禁用且端口行写实际地址；这 3 例本机没有设备/模拟器可跑，设备结论待下一次 CI；W6-49 那 6 例钉住「发送中才启用取消按钮、点完回到 Idle、失败行不出现」；端口闸门与「TLS 无证书就禁用」、口令框的 Password 语义、失败行与成功行文案，以及「不滚动也看得见选择文件 / 预览」的首屏不变式；本机无设备，设备结论只来自 CI 的 API 32 / 36 模拟器；W6-48 的两个协议开关同样先 performScrollTo 再断言，关掉时不影响主流程首屏））。
- **发布阻断**：见下节许可证；另有 MSIX / 代码签名 / 干净机安装 `NOT_RUN`（`SIGNING_BLOCKED`）。

### 构建与测试

```powershell
pwsh -File windows/tools/fetch-libmpv.ps1    # 首次：拉取 libmpv（约 115 MB，不入库）
cd windows
dotnet build MediaHub.Windows.sln -c Debug -p:Platform=x64
dotnet test  MediaHub.Windows.sln -c Debug
```

CI 见 [`.github/workflows/windows-ci.yml`](.github/workflows/windows-ci.yml)：仅在 `windows/**` 变更时运行，
与 Android 流水线互相独立（Windows 改动不会拖慢 Android 验证，Android 改动也不消耗 Windows runner）。
截至 `4238edc`：`MediaHub.Tests` **469** + `MediaHub.WebDav.Tests` **104** = **573 通过 / 0 失败 / 0 跳过**
（469 = 438 既有 + 31 例续播下限契约；历史口径 542 = 438 + 104 曾在 PR #33 里写过）。
这不是本地口径而是 CI 实测：`windows-ci` 现在把两个套件**分两步**跑并各自产出 trx，再按套件名断言下限
（`MediaHub.Tests` ≥400、`MediaHub.WebDav.Tests` ≥100，`failed=0` 且 `notExecuted=0`）—— 此前 WebDav 套件虽然被构建、
却从未被 `dotnet test` 指向，104 个用例在 CI 里长期空转（run 40019093231 起已修正，日志可见
`MediaHub.Tests: total=438` / `MediaHub.WebDav.Tests: total=104` / `combined tests=542`；此后 `4238edc` 又给 `MediaHub.Tests`
加了 31 例，CI 的 floor（≥400 / ≥100）因此仍有余量）。
W6 分支上 `MediaHub.Tests` 为 **1614 通过 / 0 失败**（主线 469 = 438 基线 + 31 例续播下限契约，加 W6 新增 1145：
W6-1…W6-14 合计 583（其中 W6-10 的接线 14、W6-13 的清单 UI 17），再加 W6-15 的 CLI 命令组 32、
W6-16 的回环传输 34、W6-17 的双向编排 22，另加 W12b 的对抗性回归 66、W6-18 的 transfer 命令组 19、
W6-19 的 presenter 35、W6-21 的 skip 语义 9、W6-20 的 `--root`/导出路径 18、W6-24 的传输对端认证 45、W6-26 的 TLS 与指纹钉扎 37、W6-30 的投递确认 19、W6-31 的恶意帧回归 36、W6-33 的传输卡片 presenter 29、W6-37 的回执帧与握手超时分类修正 35、W6-39 的同步页回执接线 27、W6-43 的回执控件与结果行 3、W6-50 的 `transfer serve` 预算 19、W6-51 的显式主机入口 40、W6-66 的归档默认名 3、W6-67 的跨语言文本边界 11、W6-68 的 8 MiB 上限 5、W6-69 的配对文本边界 10
—— 583+32+34+22+66+19+35+9+18+45+37+19+36+29+35+27+3+19+40+8+3+11+5+10 = 1145，即 1306 + 45 + 121 + 35 + 27 + 3 + 40 + 8 + 3 + 11 + 5 + 10），
`MediaHub.WebDav.Tests` 的 104 例同样适用于该分支（见 [PR #39](https://github.com/fwzm/MediaHub/pull/39) 正文的验证表）。

Android 侧（[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)）的单元门禁同样按**名字**断言，
不是「打印了 PASSED 就算过」：`verify-unit-tests.py` 除要求每个有测试源码的模块都真跑过之外，还用
`--required-suite` 钉住 **51** 个套件（含跨端契约的 `PairingVectorsTest` / `PairingFrameTest` /
`WindowsGoldenInteropTest` / `SourceSyncPlannerTest`、`AndroidSourceSyncServiceTest`、`Migration4to5Test`、
`UpNextResolverTest` / `PlayerAutoplayTest`，W6-G 的 `PairingTransportClientTest` / `PairingTransportEndToEndTest`，
W6-F/W6-H 的 `SourceSyncImportLogicTest` / `SourceSyncImportSessionTest` / `SourceVerificationCoordinatorTest`，
以及 W6-J 的 `PairingMaliciousInputTest` / `PairingTransportSecurityTest` / `PairingCredentialLeakTest` /
`AndroidSourceSyncSecurityTest`，和 W6-22 的 `SourceSyncTransferVectorsTest` / `SourceSyncTransferRoundTripTest` /
`SourceSyncTransferLoopbackTest`，W6-25 的 `SourceSyncTransferAuthTest`、W6-29 的 `SourceSyncTlsTest`，W6-34 的 `SourceSyncTransferListenerTest`，W6-46 的 `SourceSyncDeliveryTest`，W6-49 的 `SourceSyncSendLauncherTest`，和 W6-55 补钉的 `SourceSyncReceiptTest` / `SourceSyncHostAddressTest` / `SourceSyncListenerControllerTest`，W6-58 的 `SourceSyncTransferCancelTest` / `SourceSyncReceiveLauncherTest`，以及 W6-59 的 `PairingLinkControllerTest`，和 W6-59b 补钉的 `PairingTransportTlsTest`，和 W6-60 补钉的 `PairingAddressEnumeratorTest`，以及 W6-61 的 `SourceSyncListenerCancelTest`，和 W6-62 的 `ArchiveVectorsTest` / `ArchiveRoundTripTest`，以及 W6-63 的 `SourceSyncArchiveImportTest`，和 W6-64 的 `SourceSyncArchiveExportTest`，以及 W6-67 的 `ArchiveEdgeContractTest`，和 W6-68 的 `ArchiveNearLimitTest`，以及 W6-69 的 `PairingEdgeContractTest`），并用 `--require-complete-suites` 从源码里解析每个 `@Test`
方法、要求逐个都真的执行过 —— 套件被删、改名，或只跑单个方法（`--tests Suite.oneMethod`）都会失败。
W6-23 新增的 `core/database` 套件 `SourceSyncTransferImportTest`（19 例）不在 51 个钉住名单里，
但由 `--require-complete-suites` 与其他套件一起逐个方法核对（`core/database` 该模块 192 例、`core/common` 622 例（含 W6-40 的 `SourceSyncReceiptTest` 33 例、W6-46 的 `SourceSyncDeliveryTest` 20 例、W6-52 的 `SourceSyncHostAddressTest` 14 例、W6-52b 的 `SourceSyncTransferClientHostTest` 11 例、W6-57 的 `SourceSyncTransferMatrixTest` 20 例与 W6-58 的 `SourceSyncTransferCancelTest` 6 例、W6-61 的 `SourceSyncListenerCancelTest` 7 例、W6-62 的 `ArchiveVectorsTest` 15 例与 `ArchiveRoundTripTest` 24 例、W6-67 的 `ArchiveEdgeContractTest` 11 例、W6-68 的 `ArchiveNearLimitTest` 5 例、W6-69 的 `PairingEdgeContractTest` 10 例）、`feature/settings` **354** 例（W6-36 的 `SourceSyncListenerControllerTest` 43 例（W6-52 增 5 例、W6-53 增 5 例、W6-54 增 5 例）、W6-42 的 `SourceSyncSendControllerTest` 35 例（W6-52b 增 3 例）、W6-49 的 `SourceSyncSendLauncherTest` 8 例、W6-58 的 `SourceSyncReceiveLauncherTest` 8 例、W6-61 的 `SourceSyncListenLauncherTest` 7 例、W6-59 的 `PairingLinkControllerTest` 22 例（W6-59b 增 9 例），另有 W6-52b 增 3 例与 W6-58 增 5 例的 `SourceSyncReceiveControllerTest` 33 例），门禁脚本本地口径合计 2293 个唯一用例（= 上一 CI 基线 2283 + W6-69 的 10 例配对文本边界用例；W6-52 的 19 例、W6-53 的 5 例、W6-54 的 5 例、W6-52b 的 17 例、W6-57 的 20 例、W6-58 的 19 例、W6-59 的 13 例、W6-59b 的 9 例 + 补钉的 13 例与 W6-60 的 19 例与 W6-61 的 17 例与 W6-62 的 38 例与 W6-63 的 20 例与 W6-64 的 19 例与 W6-65 的 1 例与 W6-66 的 1 例与 W6-67 的 11 例与 W6-68 的 5 例与 W6-69 的 10 例已计入；上述 2293 已由本轮 CI `uniqueTests` 实测确认（W6 分支 run 40416988070 / head c9266158，主树 run 40416913420 / head 69fa6633 同口径））；以上只是 JVM 单测，同步页三张卡的 Compose 断言另有 88 例 `androidTest`（W6-44 的 19 例、W6-48 的 8 例、W6-49 的 6 例、W6-52 的 3 例、W6-53 的 3 例、W6-54 的 4 例、W6-52b 的 4 例、W6-58 的 4 例、W6-59 的 7 例、W6-59b 的 5 例与 W6-60 的 7 例与 W6-61 的 6 例与 W6-63 的 5 例与 W6-64 的 7 例），走 CI 的 visual-instrumentation（API 32 / 36）而不计入这些数字）。
唯一允许跳过的是两个环境门控套件：`SourceSyncTlsInteropTest`（core/common，W6-32 的客户端方向）与 `SourceSyncListenerInteropTest`（core/database，W6-38 的反向方向），两者都要真实 Windows `SyncCli` 的 DLL 才能跑，CI runner 上按 JUnit5 assumption 跳过：门禁脚本用 `ENVIRONMENT_GATED_SUITES` 逐个类点名放行，同时还要求这类套件必须出现在报告里（凭空消失同样报错），其他任何被跳过的用例仍然是硬失败。
本地负向验证过这两条：把整套 XML 移走 → 22 条问题（1 条套件 + 21 条方法）；只删一个 `<testcase` →
恰好 1 条 `PairingVectorsTest#rejected_addresses_match_windows`。

> 历史记录：head `2757285` 时记录的 477（373 + 104）与当时 Android CI 的一处不稳定用例失败
> （`core/network` 的 `MpvHttpBridgeLifecycleTest.stop cancels target upstream while response body cannot finish`）
> 均已过时；`f5e4eb7` 的 Android CI 为 success，该用例是否彻底稳定未做专项反复复现。

真服 GUI 验收脚本入库在 `windows/tools/acceptance/`（`r7-*`、`u1/`–`u4/`），可用 `pwsh` 7 重放；
证据目录被 `.gitignore` 排除，可在本机重跑复现。

### 引擎许可证（影响可发行性）

- 生产基线 `windows/native/libmpv/libmpv-2.dll` 为 **GPL-3.0-or-later**（构建未传 `-Dgpl=false`）。
- LGPL 候选已构建于 `windows/engine/lgpl/dist/libmpv-2.dll`（3,327,274 B，sha256 `20035902…3C0A`），
  但**尚未切换到产品**。
- 因此**在完成引擎切换与合规判定前，Windows 客户端不具备分发条件**。
  详见 [windows/THIRD-PARTY-NOTICES.md](windows/THIRD-PARTY-NOTICES.md) 与 `windows/docs/licensing/`。

### 最近更新（自动同步）

<!-- WINDOWS-SYNC:START -->
<!-- 由 scripts/sync-github-daily.ps1 自动生成：windows/ 最近 7 天提交。请勿手工编辑块内内容。 -->
- 1deccca2 2026-10-24 ci(windows): 许可证面门禁接进流水线（先按现状生成报告，再校验 notices/SBOM/项目漂移）
- ef5344d2 2026-10-24 docs(licensing): W11b 许可证清单与 SBOM 按现状重跑（13 csproj / 49 行 / 29 包 + LGPL 33 DLL 许可族），新增 check-licence-surface.ps1 门禁
- 4238edcc 2026-10-24 test(w8): 把两服续播下限语义固化成离屏契约（mock 按 2%/5% 落实下限，31 例）
- a4684a8b 2026-10-24 feat(w8): 无窗口真服续播闭环（ResumeProbe 走产品 provider 栈 + 双服驱动，两服各 16 条断言全绿）
- 214e048e 2026-10-24 docs(engine): W13c Release/publish 两 flavour 自包含实测（10.1-10.7）
- 97a0f8e5 2026-10-24 feat(engine): W13b LGPL 引擎自包含打包与可选切换开关（默认仍 GPL 基线）
- 1a38b06a 2026-10-24 docs(windows): W10 登记第 6 次被打断并停用自动重试，GUI 证据改为人工授予窗口
- dd0ff317 2026-10-24 feat(tools): W13 LGPL 引擎无头探针——按绝对路径加载两份 libmpv 实测解码等价性
<!-- WINDOWS-SYNC:END -->

## 安全约定（强制）

- 禁止源码硬编码 Token / Cookie / 密码。
- Token 走 `core:security`（Android Keystore AES/GCM 加密存储）。
- 临时播放 URL 绝不落库（ADR-003），播放时由 Provider 动态解析。
- 日志统一经 `Redactor` 脱敏（Authorization / Cookie / Token / 密码）。

## 多 AI 协作

开始任务前先读：README / ARCHITECTURE / ROADMAP / TASKS / DECISIONS / HANDOFF。
完成任务后更新：TASKS / CHANGELOG / HANDOFF（重要决策同步 DECISIONS）。