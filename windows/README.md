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
