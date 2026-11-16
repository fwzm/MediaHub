# MediaHub Windows 客户端

MediaHub 多源统一媒体播放器的 **Windows 原生桌面客户端**。与 Android 版本共享同一产品语义
（领域契约、能力声明、安全红线），不共享源码，也不互相依赖运行时。

- 技术栈：C# / .NET 10 + WinUI 3（Windows App SDK 1.8）+ libmpv
- 选型依据：[ADR-W001](docs/adr/ADR-W001-technology-selection.md)
- 引擎嵌入方式：[ADR-W002](docs/adr/ADR-W002-mpv-embedding.md)
- 与 Android 的契约映射：[ADR-W003](docs/adr/ADR-W003-shared-domain-contracts.md)
- 发行形式：[ADR-W004](docs/adr/ADR-W004-packaging-and-distribution.md)
- 网络与凭据策略：[ADR-W005](docs/adr/ADR-W005-network-policy.md)
- **当前已实现 / 未实现清单**：[docs/CAPABILITY-MAP.md](docs/CAPABILITY-MAP.md)
- **执行证据（含未验证项）**：[docs/W1-EVIDENCE.md](docs/W1-EVIDENCE.md)

> 本项目不夸大能力：凡未经真机或真服验证的结论，一律在 CAPABILITY-MAP 与
> W1-EVIDENCE 中标记为 UNVERIFIED / NOT_RUN。

## 环境要求

| 项 | 要求 | 说明 |
| --- | --- | --- |
| OS | Windows 10 1809（17763）及以上 | `TargetPlatformMinVersion` |
| .NET SDK | 10.0 | 仅构建需要；**运行不需要**（产物自包含） |
| Windows SDK | 10.0.26100（NuGet 自动引入） | 无需 Visual Studio workload |
| Visual Studio | **不需要** | WinUI 3 通过 NuGet 引用，`dotnet build` 即可 |
| Windows App Runtime | **不需要** | 采用自包含部署，见 ADR-W004 |

## 快速开始

```powershell
# 1. 拉取 libmpv（约 115 MB，不入库）
pwsh -File windows/tools/fetch-libmpv.ps1

# 2. 构建
cd windows
dotnet build src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64

# 3. 运行
./src/MediaHub.App/bin/x64/Debug/net10.0-windows10.0.19041.0/win-x64/MediaHub.exe
```

也可以直接打开一个视频文件：

```powershell
MediaHub.exe "D:\videos\movie.mkv"
```

## 构建与测试

```powershell
cd windows

# 全量构建
dotnet build MediaHub.Windows.sln -c Debug -p:Platform=x64

# 单元测试与契约测试
dotnet test tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug -p:Platform=x64

# 播放引擎集成测试（需要真实媒体文件；否则会被跳过）
dotnet build tools/MpvProbe/MpvProbe.csproj -c Debug -p:Platform=x64
./tools/MpvProbe/bin/x64/Debug/net10.0-windows/win-x64/MpvProbe.exe <video-file> 5

# 便携版发布
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o ./artifacts/portable
```

集成测试在找不到视频样本时应被**跳过**而不是记作通过。当前 CI 不提供媒体样本，
因此该测试只在本地执行（见 `docs/W1-EVIDENCE.md` 的实际运行记录）。

## 播放器：缓存、画质与媒体徽章

设置页「播放」与「媒体徽章」控制这一组功能，均为本地状态，不涉及服务器配置。

### 磁盘缓存

- 目录固定为 `%LOCALAPPDATA%\MediaHub\cache\mpv`，设置页显示当前占用，可开关、选容量
  （512 MB / 1 GB / 2 GB / 4 GB / 8 GB）并「立即清理」。
- 播放结束策略：开启「播放结束清理」时目录会被清空；关闭时按容量裁剪，删除最旧的文件。
- 引擎侧使用 mpv 0.41 的真实选项名 `demuxer-cache-dir`。默认的
  `demuxer-cache-unlink-files=immediate` 会在缓存文件创建后立刻删除，使占用恒为 0，
  因此客户端显式设置为 `whendone`（结束清理）或 `no`（保留给手动/容量清理）。
- 只有网络串流才会产生磁盘缓存；本地文件与 HLS/转码流不写盘，这是引擎行为，不是缺陷。

### 画质优先级与视频轨道

- 画质优先级（自动 / 优先最高 / 优先最低）只在服务器本身可直连或可直接播放的版本中选择，
  不会把请求切到转码，避免改变既有 HLS/转码行为。
- 视频轨道记忆按「服务器 + 条目」保存引擎的 `vid`，下一集加载后自动应用；轨道列表与选择
  位于播放器顶部「视频轨」面板。

### 播放信息与徽章

- 播放信息面板新增缓冲时长、缓冲速度、丢帧、错位帧、音轨与视频轨，并显示当前解码方式。
- 媒体徽章可在设置中选择分辨率 / HDR / 视频编码 / 音频 / 容器五项，卡片最多显示三项；
  服务器未上报的技术字段保持为空，不猜测。

### 验证状态（2026-11-14）

| 项目 | 验证方式 | 结果 |
| --- | --- | --- |
| 磁盘缓存落盘与结束清理 | 无头引擎测试 `MpvPlaylistAndCacheTests` | 已实测：缓存文件写入指定目录，关闭引擎后目录归零 |
| 缓存容量裁剪 / 手动清理 | 单元测试 `PlayerCacheStoreTests` | 通过 |
| 下一集预加载 | 无头引擎测试（append → `playlist-count=2` → `playlist-pos=1`） | 通过 |
| 画质优先级选择 | mock 服务器测试 `PlaybackSourceSelectionTests` | 通过（含“不会切到转码”双向边界） |
| 多版本条目映射与技术字段 | `ProviderContractTests` + mock 详情请求断言 | 通过（请求含 `MediaSources,MediaStreams`） |
| 开始/进度上报时序与暂停/跳转强制上报 | `PlaybackProgressReportingTests` + mock 上报顺序 | 通过 |
| Emby 多版本条目 | 请求补 `Fields=MediaSources,MediaStreams`；mock 覆盖选择规则 | 未在真实 Emby 服务器验证（缺外部服务器条件） |
| HDR / 硬解 / 字幕 / 转码 | 既有测试与引擎行为未改动 | 未回归（沿用原有用例） |

## 安装与更新

### 构建安装包

```powershell
# 版本号会写进 MediaHub.exe；同时产出 zip 与可直接作为更新源的 update.json
pwsh -File windows/tools/make-install-kit.ps1 -Version 1.1.0 -OutputDir windows/artifacts/update-drill/kits
```

安装仍然是每用户、未打包形态：`install.ps1` 把 `app/` 复制到
`%LOCALAPPDATA%\Programs\MediaHub`，不需要管理员，也不安装 MSIX。

### 更新源

设置页「软件更新」填写更新源，支持两种形态：

| 形态 | 示例 | 说明 |
| --- | --- | --- |
| 本地目录 | `D:\MediaHub-Update` | 目录内放 `update.json` 与它指向的 zip |
| HTTP 静态目录 | `https://example.com/mediahub/` | 同一目录以普通静态文件提供，无鉴权、无专用协议 |

`update.json` 的字段：

```json
{
  "version": "1.1.0",
  "package": "MediaHub-Setup-win-x64-1.1.0.zip",
  "sha256": "<64 位小写十六进制>",
  "size": 101187584,
  "notes": "可选的更新说明",
  "published": "2026-11-14T06:00:00.0000000Z"
}
```

点击「检查更新」后只读取清单；点击「立即更新」才会下载。下载先写 `.part`，校验 zip 的
SHA-256 与大小通过后才改名，然后启动 `update.ps1` 并让应用走正常关闭流程。

### 更新代理与回滚

`update.ps1` 在应用退出后执行，依次：

1. 等待启动它的进程退出（默认 30 秒），超时则强制结束；再结束安装目录下仍持有文件的进程；
2. 再次校验 zip 的 SHA-256，解压到 `%LOCALAPPDATA%\MediaHub\update\work`，检查
   `MediaHub.exe`、`MediaHub.dll`、`libmpv-2.dll` 与版本号；
3. 把旧安装目录整体改名为备份，再把新目录移入原位，随后对三个必需文件复算摘要；
4. 修复开始菜单、桌面（仅当原来存在）与任务栏已固定快捷方式的 target/workdir/icon；
5. 需要时重新启动 MediaHub。

任何在“旧目录已移走”之后发生的失败都会删除未完成的新目录并恢复备份，退出码 5；
恢复本身失败时退出码 6，备份路径与日志会保留。校验失败、参数错误等发生在换文件之前的
失败退出码为 2/3/4，安装目录保持原样。

- 日志与状态：`%LOCALAPPDATA%\MediaHub\update\logs\`、`%LOCALAPPDATA%\MediaHub\update\state.json`
- 备份：`%LOCALAPPDATA%\MediaHub\update\backups\`（保留最近两份）
- 一键更新只在安装目录包含 `uninstall.ps1` 时可用；开发目录与便携目录会拒绝，避免替换构建输出。

### 本地升级 / 回滚演练

```powershell
pwsh -NoProfile -File windows/tools/verify-update-chain.ps1 `
  -OldKit windows/artifacts/setup/MediaHub-Setup-win-x64-1.0.0 `
  -NewKit windows/artifacts/update-drill/kits/MediaHub-Setup-win-x64-1.1.0
```

演练在 `windows/artifacts/update-drill/run-*` 下创建隔离的安装目录、状态目录和快捷方式目录，
不触碰当前用户的桌面、开始菜单或任务栏，也不启动 MediaHub 窗口；结束后自动删除临时安装树，
只把 `state.json` 与更新日志归档到 `windows/artifacts/update-drill/last-drill`（需要保留现场时加
`-KeepWorkDir`）。它覆盖：

1. 1.0.0 → 1.1.0 升级，并确认一个占用 `MediaHub.dll` 的进程被等待/结束时释放；
2. 换文件后注入失败，确认退出码 5、旧版本完整恢复且没有留下新版本独有文件；
3. 错误摘要，确认退出码 3、安装目录字节级不变。

### HTTP 静态目录演练

```powershell
pwsh -NoProfile -File windows/tools/verify-update-http.ps1
pwsh -NoProfile -File windows/tools/verify-update-http.ps1 -IdleWaitSeconds 3600   # 等用户离开后自动开跑
```

这条演练走产品自己的 `MediaHub.Update`（演练时现编译），对 `http://127.0.0.1:<随机端口>/`
上的静态目录完成“清单检查 → 下载 → 大小/SHA-256 校验 → 代理换文件 → 重启”，HTTP 服务用
`TcpListener` 写在脚本里（仅回环、无需管理员或 URL ACL），每个请求的 method/path/status/字节数
都会记录。它还覆盖两条失败路径：响应在 1 MiB 处被截断、清单版本非法；两者都必须失败且不改动
安装目录。重启步骤使用带版本资源的无窗口探针 exe 代替 `MediaHub.exe`，因此不会弹出 MediaHub
窗口；真实二进制替换由上面的本地演练覆盖。

两条演练都会先过空闲门禁：距用户最后一次键鼠/触摸输入不足 30 分钟直接拒绝（退出码 10），
运行中一旦检测到输入就终止探针并中止剩余步骤（退出码 11）。演练不上屏、不置顶、不注入输入。

### 未签名与 SmartScreen（如实说明）

安装包、更新包与 `MediaHub.exe` 都没有代码签名。应用内更新会校验 SHA-256，但摘要只能证明
“下载到的字节与清单一致”，不能替代 Authenticode，也不会让 Windows 信任发布者。通过浏览器
下载的 zip 可能带有 Mark-of-the-Web，首次运行仍可能被 SmartScreen 拦截；本实现不会移除该标记、
不会绕过 SmartScreen，也不会把未签名产物描述成已签名。要消除提示需要购买代码签名证书。

本轮验证范围：本地目录源的升级、换文件后回滚、错误摘要拒绝均已实际跑通；HTTP 静态目录的
URL 解析、清单读取与下载逻辑由单元测试覆盖，**尚未用真实 HTTP 服务器做完整界面演练**。

## 项目结构

```
windows/
├── src/
│   ├── MediaHub.Domain/        纯领域模型与契约（无平台依赖）
│   ├── MediaHub.Net/           网络策略、重定向凭据作用域、脱敏、HTTP 客户端
│   ├── MediaHub.Providers/     Local / Emby / Jellyfin（能力组合）
│   ├── MediaHub.Player/        与引擎无关的播放契约
│   ├── MediaHub.Player.Mpv/    libmpv 互操作与视频表面
│   └── MediaHub.App/           WinUI 3 应用
├── tests/MediaHub.Tests/       单元与契约测试
├── tools/
│   ├── MpvProbe/               播放引擎集成测试（可执行）
│   └── fetch-libmpv.ps1        libmpv 获取脚本
├── native/libmpv/              libmpv 运行时（.gitignore 排除）
└── docs/                       ADR、审计、证据、能力表、路线图
```

依赖方向严格单向：`MediaHub.Domain` 不引用任何其他项目，也不引用 Windows API；
`MediaHub.App` 是唯一接触 WinUI 的项目。因此表示层可替换（见 ADR-W001 的回退路径）。

## 与 Android 的关系

- 不修改 Android 模块；Windows 项目独立于 Gradle 构建图（`settings.gradle.kts` 未改动）。
- 领域语义逐条对齐，安全红线逐条继承并各有 Windows 回归测试（见 ADR-W003）。
- Android 端任何时刻都不依赖 Windows 桌面运行时。

## 安全约定（强制）

- 禁止源码硬编码 Token / Cookie / 密码。
- Token 只走请求头，**禁止进入 URL**（继承 Android ADR-026）。
- 播放 URL 播放时解析，**绝不落库**（继承 Android ADR-003）。
- 跨 origin 重定向必须剥离凭据（继承 Android ADR-030，Windows 显式实现）。
- 日志与诊断导出强制脱敏；Provider 异常只保留异常类型名，不保留可内嵌 URL 的消息。
- 设置文件**刻意不保存密码与 Token**；加密凭据存储见 ROADMAP-WINDOWS W2。
