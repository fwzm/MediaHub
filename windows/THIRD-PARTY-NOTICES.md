# 第三方组件与许可证

本客户端分发时包含以下第三方组件。发布产物必须随附本文件。

| 组件 | 版本 | 许可证 | 用途 | 获取方式 |
| --- | --- | --- | --- | --- |
| .NET Runtime | 10.0 | MIT | 运行时（自包含） | 随发布产物 |
| Windows App SDK (WinUI 3) | 1.8.260921001 | Microsoft Software License / MIT | UI 框架 | NuGet |
| **libmpv (mpv)** | 20261014-git-ec38361c56 | **LGPL-2.1-or-later**（本构建含 GPL 组件，见下） | 播放引擎 | `tools/fetch-libmpv.ps1` |
| FFmpeg (libav*) | 随 mpv 构建 | LGPL-2.1-or-later / GPL（取决于构建配置） | 解复用与解码 | 随 libmpv |
| libplacebo | 随 mpv 构建 | LGPL-2.1-or-later | GPU 渲染 | 随 libmpv |
| libass | 随 mpv 构建 | ISC | 字幕排版 | 随 libmpv |
| xunit | 2.9.3 | Apache-2.0 | 测试 | NuGet（仅测试） |

## libmpv 许可证注意事项（**发布前必须确认**）

使用的是 shinchiro 社区构建（`mpv-dev-x86_64-*.7z`）。这类构建通常**启用 GPL-only 组件**，
从而使整体按 **GPL-2.0-or-later** 分发，而非 LGPL。本仓库自身为 MIT，
二者可以共存，但**发布方必须履行 GPL 义务**（提供对应源码或书面要约）。

因此：

- 若本项目要以宽松许可分发，需自行构建 **LGPL 配置**的 libmpv（`--enable-lgpl` 且不启用 GPL-only 组件）。
- 在该问题解决之前，**不应把当前产物作为正式发行版对外分发**。
- 这是一个**未解决的法律风险**，登记在此，不因"代码能跑"而忽略。

## 未包含

- 不含任何 Emby / Jellyfin / Plex 的商标或私有代码。
- 不含用户的服务器地址、账号或令牌。
