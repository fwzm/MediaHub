# ADR-W002 — libmpv 嵌入 WinUI 3 的方式（视频表面与控制层）

- 状态：**已接受**（2026-10-14）
- 相关：ADR-W001、ADR-W004

## 问题

需要把 mpv 的视频输出放进 WinUI 3 窗口，并且要让 MediaHub 自己的控制层可用。
两者在 Windows 上受同一个约束支配：**外部子窗口（foreign child HWND）永远绘制在 XAML 岛之上**。

## 核对结果：libmpv 到底提供什么

对钉住的 Windows 版 libmpv（`mpv-dev-x86_64-20261014-git-ec38361c56`）随包发布的头文件
`include/mpv/render.h` 逐条核对：

```c
#define MPV_RENDER_API_TYPE_OPENGL "opengl"
#define MPV_RENDER_API_TYPE_SW     "sw"
```

参数枚举中与渲染目标相关的只有 `MPV_RENDER_PARAM_OPENGL_*`、`MPV_RENDER_PARAM_SW_*`
（SW_SIZE / SW_FORMAT / SW_STRIDE / SW_POINTER）与 `MPV_RENDER_PARAM_DRM_*`。

> **结论：libmpv 没有 D3D11 render API。**
> "mpv 的 D3D11 输出能力"（`--gpu-context=d3d11`）与"libmpv Render API 提供 D3D11 渲染接口"
> 是两件不同的事。前者存在，后者不存在。

因此 **无法**用 `SwapChainPanel` 接收 mpv 的渲染结果——那需要 Render API 暴露 D3D11 目标，
或自行搭建 OpenGL↔D3D11 互操作层（`WGL_NV_DX_interop`），属于本项目明确排除的私有机械。

## 决策

采用 mpv 官方文档记载的 `--wid` 嵌入契约：

> "The player will create its own window and set the wid window as parent."
> —— mpv 官方手册 `--wid`

具体结构：

```
WinUI 3 顶层窗口 (WinUIDesktopWin32WindowClass)
├── Microsoft.UI.Content.DesktopChildSiteBridge   ← XAML 内容（浏览页、控制栏、面板）
└── MediaHub.MpvVideoSurface  (自建 WS_CHILD 窗口，仅覆盖视频矩形)
    └── mpv                  (由 libmpv 创建，class="mpv")
```

- 视频矩形由 XAML 布局决定，每 250 ms 与布局事件同步一次，按 `GetDpiForWindow` 换算为物理像素。
- mpv 自己驱动 D3D11 交换链，硬件解码与 Present 路径完整保留。
- 不使用：分层透明覆盖窗口、`SetWindowSubclass` 钩子、DWM/direct composition 私有接口、任何 mpv 补丁。

## 控制层如何绕过 airspace

**不绕过，而是分区。** 播放页布局为：

```
┌──────────────────────────────┐
│ 顶部信息栏（XAML）            │  ← 返回、标题、详细信息技术开关、全屏
├──────────────────────────────┤
│ 视频矩形（mpv 子窗口）        │  ← 字幕/OSD 由 mpv+libass 在矩形内绘制
├──────────────────────────────┤
│ 底部控制栏（XAML）            │  ← 进度、播放/暂停、快进快退、音量、倍速、音轨、字幕
└──────────────────────────────┘
```

- 播放中 3 秒无指针活动 → 上/下栏 `Collapsed`，视频矩形扩展为整个窗口（沉浸）。
- 侧边信息面板（媒体信息/音轨/字幕）占用**独立列**，视频矩形相应收窄，因此从不被遮挡。
- 不使用 `Flyout`/`Popup` 覆盖视频区域——它们在 WinUI 3 中仍属于同一 XAML 岛，会被子窗口盖住。

## 指针活动检测

视频区域内的指针消息不会到达 XAML（被子窗口吞掉）。可选方案与取舍：

| 方案 | 结论 |
| --- | --- |
| 全局鼠标钩子 `WH_MOUSE_LL` | 拒绝：全局副作用、易被杀软拦截 |
| `SetWindowSubclass` mpv 子窗口 | 拒绝：依赖 mpv 内部窗口生命周期，跨版本脆弱 |
| **轮询 `GetCursorPos` + `GetForegroundWindow`（≈7 Hz）** | **采用**：只读、无注入、无副作用 |

轮询**只**用于显示控制栏，从不合成输入。点击/滚轮/键盘仍由 mpv 自己处理
（`Assets/input.conf`），UI 通过属性观察回读状态，因此两条输入路径不会互相打架。

> `input.conf` 中**故意不绑定** `cycle fullscreen`：该命令会把 mpv 自己的子窗口全屏，
> 而不是 MediaHub 窗口。全屏由 XAML 侧通过 `AppWindow.SetPresenter` 实现。

## 已验证

- mpv 窗口确实成为 MediaHub 的子窗口：`class='mpv' text='sintel1080.mp4 - mpv'`。
- 视频在窗口内正确合成并随时间推进（截图 + 引擎时钟）。
- DPI 150% 下几何正确（见 W1 证据中关于 DPI 的排查）。
- 暂停、拖动、全屏切换、控制栏自动隐藏均在真实运行中观察到。

## 未验证（如实登记）

- HDR 元数据直通、多显示器 DPI 迁移、设备丢失恢复。
- 与 Intel / AMD / NVIDIA 三家的兼容矩阵（本机仅覆盖当前一台设备的 GPU）。
