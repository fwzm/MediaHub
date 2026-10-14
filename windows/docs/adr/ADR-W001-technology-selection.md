# ADR-W001 — Windows 客户端技术栈选型

- 状态：**已接受**（2026-10-14）
- 决策者：MediaHub Windows 负责人
- 相关：ADR-W002（引擎嵌入）、ADR-W004（打包）

## 背景

Android 客户端已用 Kotlin + Media3/mpv 完成 Emby/Jellyfin/本地/WebDAV 的端到端闭环。
Windows 端需要同一产品语义的原生桌面客户端，且必须**真正能播放**，不是原型壳。

候选：

- **方案 A**：C#/.NET + WinUI 3 + libmpv
- **方案 B**：Qt 6 + libmpv
- 其他：Electron/Tauri（网页技术栈）、原生 Win32 + FFmpeg 自研渲染、WPF + libmpv

## 决策

**采用方案 A：C#/.NET 10 + WinUI 3（Windows App SDK 1.8）+ libmpv。**

## 实测依据（本机实际执行，非推断）

| 事实 | 证据 |
| --- | --- |
| .NET SDK 10.0.113 + VS 2022 + Windows SDK 10.0.26100 已就绪 | `dotnet --info`、目录列举 |
| 不需要任何 VS workload，WinUI 3 可纯 NuGet 构建 | `dotnet build` 成功（0 错误） |
| 自包含 WinUI 3 应用可运行并渲染 | 探针窗口截图（EXECUTED_PASS） |
| Windows App Runtime 默认未安装 | 首次运行报 "Required components of the Windows App Runtime are missing" |
| `WindowsAppSDKSelfContained=true` 后免安装运行时 | 同一 exe 直接启动并显示窗口 |
| libmpv 可用且可直接嵌入 | `mpv` 窗口成为 MediaHub 子窗口（见 ADR-W002） |
| 硬件解码在宿主内生效 | `hwdec=d3d11va`、`vo=gpu-next`、`gpu-context=d3d11` |

## 拒绝方案的理由

**方案 B（Qt 6 + libmpv）**
- 本机无 Qt 工具链，需额外安装数 GB；方案 A 零新增工具链即可构建与运行。
- Qt 的 `QWidget` 叠加在 mpv 子窗口之上**同样**受 airspace 限制，并不比 WinUI 3 更优；
  真正的优势传统上来自 `QWindow` + 原生窗口叠加，那正是本项目要避免的窗口句柄拼接。
- LGPL/商业授权需要额外合规评估；.NET/WinUI 3 为 MIT/微软授权，与仓库现有 MIT 许可一致。

**Electron / Tauri**
- 引入 Chromium/WebView 运行时（>100 MB）与第二套 UI 技术栈；与 Android 共享语义的收益为零。
- 视频走 `<video>` 无法达到本项目要求的解码/渲染/HDR 控制粒度。

**原生 Win32 + FFmpeg 自研渲染**
- 需要自行实现解码调度、色彩管理、字幕排版（libass）、音画同步。
- 与"优先利用成熟 mpv/libplacebo，避免从零开发解码器"的既定要求直接冲突。

**WPF + libmpv**
- `HwndHost` 的确是托管子 HWND 的一等 API，工程上完全可行；
- 但 WPF 是上一代 UI 栈，Fluent/DPI/输入现代化成本更高，且用户明确把 WinUI 3 列为首选。
- **保留为回退路径**：若 WinUI 3 在 W4 的设备矩阵中出现不可绕过的渲染问题，可在不改动
  `MediaHub.Domain`/`MediaHub.Net`/`MediaHub.Providers`/`MediaHub.Player` 的前提下替换表示层。

## 后果

- 正向：零新增工具链；自包含部署；与 Fluent/Windows 11 一致；领域层与平台层严格分离。
- 负向：WinUI 3 的 airspace 约束使视频表面必须与 XAML 控制层**分区**而非叠加（ADR-W002）；
  自包含部署使产物约 200 MB（含 libmpv 115 MB），见 ADR-W004。
- 需要关注：Windows App SDK 版本演进可能带来 API 变化，故版本在
  `Directory.Packages.props` 中集中固定。
