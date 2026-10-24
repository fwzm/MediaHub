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
  关闭时线上字节与 W6-16 全等，重放按进程内 64 条 nonce 窗口拒绝）、来源同步传输的 TLS 1.3 与公钥指纹钉扎（W6-26：`transfer serve --tls` 在 stderr 打 `certificate fingerprint:`，`transfer send --tls --expect-fingerprint` 只信任钉住的那一枚，四个 `tls-*` 稳定码；默认关闭时线上字节与 W6-16 全等）、可选投递确认（W6-30：`--confirm-delivery` 要等到对端关闭连接才认定送达，否则 `delivery-unconfirmed` 而不是静默成功）、传输层恶意与畸形帧回归（W6-31：36 例、十类攻击面，接收端永不把异常抛给调用方）、同步页的传输卡片（W6-33：本机回环收/发、收到的信封只进预览绝不自动应用、20 个稳定码逐条文案，抽成无 WinUI 依赖的 `SyncTransferPresenter`）、同步页 presenter 与真实 `SyncCli` 子进程的 headless 端到端（W6-35：独立测试工程、不入 sln、9 例全部真起子进程，主套件例数不变）、反向回执帧（W6-37：`transfer serve --send-receipt` 只在信封解码成功后回一个裸 `0x04` 字节，`transfer send --await-receipt` 没等到就报 `receipt-missing`/`receipt-timeout`/`receipt-invalid`/`receipt-not-enabled`，不再把「对端关连接」当送达证据；默认关闭时线上字节仍与 W6-16 全等，同批修掉 W6-26 握手「超时 vs 调用方取消」的分类竞态）、回执帧接进同步页与双向编排（W6-39：接收侧解码成功才回 `0x04`、发送侧可等回执、roundtrip 内部默认交换回执，回执失败只作 advisory、不改轮结果）、同步页上的两个回执控件（W6-43：接收后写回执 / 等待对端回执各一个勾选框与一行回执结论，`receipt-*` 四码原样呈现，`receipt-missing` 不软化成「已发送」；GUI_UNVERIFIED）、TLS × 认证 × 回执的组合矩阵验收（W6-45：验收脚本 14 → 18 例、presenter-e2e 12 → 14 例，全开 / 口令不一致 / 错 pin + 认证 / 明文对 TLS 四种组合各自断言稳定码与接收端零信封写入；口令不一致时发送端只能观察到 `receipt-missing`（信封单向，不能凭缺失猜原因），serve 侧则必须不出现任何 `receipt-*`）、
  审批门禁的「跳过即不写入」（同一身份 allow 与 skip 并存时 skip 胜）、同步 CLI 的 `--root` 误指已存在文件改为稳定拒绝
  （`root-not-a-directory`，每份 JSON 带 `rootExisted` 区分「新建根」与「路径写错」）与导出路径规范化
  （`../` 解析后再回显，`--out` 指向目录给 `output-is-directory`）均已实现，
  `MediaHub.Tests` **1518 通过 / 0 失败**（其中 W6 新增 1049）；
  W12b 对抗性安全回归 66 例（畸形配对载荷、设备登记表在损坏/容量满/独占/只读时必须拒绝而不是静默清空、
  传输层声明长度不触发分配、`--json` 单文档、异常与 stderr 不含口令）；
  **尚未做**：二维码与扫码、跨机（非回环）配对、真实局域网传输与设备持久化、同步页/配对页/已配对清单的 GUI 证据
  （三者均为 `IMPLEMENTED_UNVERIFIED`，本机没人观看窗口时无法跑）、Android 端接收页的 GUI 证据与真实跨机（非回环）传输
  （Android 侧的契约/规划/导入导出/配对契约/分块传输协议/「回环传输 → 导入闭环」已就位，
  协议与 Windows 逐字节对齐，见 `docs/android-source-sync-transfer.md`；Windows 端已有可选的传输对端认证
  （Windows W6-24 / Android W6-25，五个稳定码与帧格式两端同名同序）；TLS 1.3 与公钥指纹钉扎两端也都就位（Windows W6-26 / Android W6-29，四个 `tls-*` 码同名同序，Android 用 SPKI 而非整证书算指纹），并有与 Windows 真子进程的双向联调（W6-32，JSSE ↔ SslStream）；仍缺：Android 发送端没有投递确认（`Ok` 只代表帧写进 socket）、两端都没有跨机（非回环）路径与真机/真网卡证据；Android 侧已有回环监听端（W6-34：`SourceSyncTransferListener` 只绑回环、可连续 accept，`SourceSyncListenerImportService` 把它接进导入闭环，Windows 的 `transfer send` 可以直接推到 Android 的监听端口，界面入口也已就位（W6-36：同步页的「接收（监听）」区块可以开一个回环监听、等一次发送，收到的信封只进预览、写入仍要逐条允许后再应用；今天 App 里没有证书下发通道，所以 TLS 开关如实禁用、只能明文）；Android 端也已对齐回执帧（W6-40：`receipt-*` 四码与 Windows 同名同序，监听端只在信封解码成功后回一个裸 `0x04` 字节、gate 在端点层，拨号端可等回执，默认关时线上字节与 W6-22 的冻结向量逐字节相等；界面还没有开关）；导入页的两张接收卡片按 W6-41 移到页面末尾，首屏保留「选择文件 → 预览 → 应用」主流程（此前它们被插在按钮行之前，把主流程挤出首屏，CI 的 visual-instrumentation 上两次 Android run 就红在这 6 例首屏可见性断言上）；Android 也已是发送端（W6-42：同步页末尾的「发送（Android → Windows）」区块把本机导出的信封推给 Windows 的 `transfer serve`，可选 TLS 指纹钉扎 / HMAC 认证 / 等回执，默认全关时线上字节与 W6-22 冻结向量逐字节相等；本层只补 5 个「还没拨号就拒」的码，其余码原样透传；界面还没有取消按钮，Android → Windows 的发送方向也还没和真 CLI 对跑）；三张卡（接收 / 监听 / 发送）另有 19 例 instrumentation 证据（W6-44：端口闸门与「TLS 无证书就禁用」、口令框的 Password 语义、失败行与成功行文案，以及「不滚动也看得见选择文件 / 预览」的首屏不变式；本机无设备，设备结论只来自 CI 的 API 32 / 36 模拟器））。
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
W6 分支上 `MediaHub.Tests` 为 **1518 通过 / 0 失败**（主线 469 = 438 基线 + 31 例续播下限契约，加 W6 新增 1049：
W6-1…W6-14 合计 583（其中 W6-10 的接线 14、W6-13 的清单 UI 17），再加 W6-15 的 CLI 命令组 32、
W6-16 的回环传输 34、W6-17 的双向编排 22，另加 W12b 的对抗性回归 66、W6-18 的 transfer 命令组 19、
W6-19 的 presenter 35、W6-21 的 skip 语义 9、W6-20 的 `--root`/导出路径 18、W6-24 的传输对端认证 45、W6-26 的 TLS 与指纹钉扎 37、W6-30 的投递确认 19、W6-31 的恶意帧回归 36、W6-33 的传输卡片 presenter 29、W6-37 的回执帧与握手超时分类修正 35、W6-39 的同步页回执接线 27、W6-43 的回执控件与结果行 3
—— 583+32+34+22+66+19+35+9+18+45+37+19+36+29+35+27+3 = 1049，即 1287 + 45 + 121 + 35 + 27 + 3），
`MediaHub.WebDav.Tests` 的 104 例同样适用于该分支（见 [PR #39](https://github.com/fwzm/MediaHub/pull/39) 正文的验证表）。

Android 侧（[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)）的单元门禁同样按**名字**断言，
不是「打印了 PASSED 就算过」：`verify-unit-tests.py` 除要求每个有测试源码的模块都真跑过之外，还用
`--required-suite` 钉住 **31** 个套件（含跨端契约的 `PairingVectorsTest` / `PairingFrameTest` /
`WindowsGoldenInteropTest` / `SourceSyncPlannerTest`、`AndroidSourceSyncServiceTest`、`Migration4to5Test`、
`UpNextResolverTest` / `PlayerAutoplayTest`，W6-G 的 `PairingTransportClientTest` / `PairingTransportEndToEndTest`，
W6-F/W6-H 的 `SourceSyncImportLogicTest` / `SourceSyncImportSessionTest` / `SourceVerificationCoordinatorTest`，
以及 W6-J 的 `PairingMaliciousInputTest` / `PairingTransportSecurityTest` / `PairingCredentialLeakTest` /
`AndroidSourceSyncSecurityTest`，和 W6-22 的 `SourceSyncTransferVectorsTest` / `SourceSyncTransferRoundTripTest` /
`SourceSyncTransferLoopbackTest`，W6-25 的 `SourceSyncTransferAuthTest`、W6-29 的 `SourceSyncTlsTest`，和 W6-34 的 `SourceSyncTransferListenerTest`），并用 `--require-complete-suites` 从源码里解析每个 `@Test`
方法、要求逐个都真的执行过 —— 套件被删、改名，或只跑单个方法（`--tests Suite.oneMethod`）都会失败。
W6-23 新增的 `core/database` 套件 `SourceSyncTransferImportTest`（19 例）不在 31 个钉住名单里，
但由 `--require-complete-suites` 与其他套件一起逐个方法核对（`core/database` 该模块 172 例、`core/common` 457 例（含 W6-40 的 `SourceSyncReceiptTest` 33 例）、`feature/settings` 243 例（W6-36 的 `SourceSyncListenerControllerTest` 28 例、W6-42 的 `SourceSyncSendControllerTest` 26 例），门禁脚本本地口径合计 1997 个唯一用例；以上只是 JVM 单测，同步页三张卡的 Compose 断言另有 19 例 `androidTest`（W6-44），走 CI 的 visual-instrumentation（API 32 / 36）而不计入这些数字）。
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
- 2757285 2026-10-23 docs(windows): W9 证据（两服四门 276 断言全绿）+ 能力图/路线图状态更新
- 99585f4 2026-10-23 test(windows): W9 四门真服 GUI 验收脚本（继续观看/自动连播/收藏/永久播放列表）
- c832fe8 2026-10-23 fix(windows): 继续观看改用递归 + IsResumable 查询（Emby 非递归只回库文件夹）
- 883e9e9 2026-10-23 fix(windows): 首页服务器历史卡片可点击 + 继续观看/首页容器补 AutomationId
- adab0e7 2026-10-23 feat(windows): U4 永久播放列表接入 UI（播放列表页/列表详情页/详情页加入按钮）
- af4aa4e 2026-10-23 test(windows): U2 跨季自动连播真实 GUI 验收脚本（Emby 32/32、Jellyfin 32/32）
- 529ba93 2026-10-23 feat(windows): U4 永久播放列表存储层（与临时队列解耦）
- b154475 2026-10-23 feat(windows): 新增「我的收藏」页面并扩展收藏验收脚本
<!-- WINDOWS-SYNC:END -->

## 安全约定（强制）

- 禁止源码硬编码 Token / Cookie / 密码。
- Token 走 `core:security`（Android Keystore AES/GCM 加密存储）。
- 临时播放 URL 绝不落库（ADR-003），播放时由 Provider 动态解析。
- 日志统一经 `Redactor` 脱敏（Authorization / Cookie / Token / 密码）。

## 多 AI 协作

开始任务前先读：README / ARCHITECTURE / ROADMAP / TASKS / DECISIONS / HANDOFF。
完成任务后更新：TASKS / CHANGELOG / HANDOFF（重要决策同步 DECISIONS）。