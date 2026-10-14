# Windows 客户端能力对照表

图例：**IMPLEMENTED**（代码完成且经 EXECUTED 验证）/ **IMPLEMENTED_UNVERIFIED**（代码完成，未经真机/真服验证）/
**NOT_IMPLEMENTED** / **NOT_STARTED**。

## 领域契约（W2）

| 契约 | 状态 | 位置 |
| --- | --- | --- |
| SourceRegistry | IMPLEMENTED | `MediaHub.Providers/ProviderContracts.cs`、`MediaHub.App/Services/SourceRegistry.cs` |
| ProviderCapability（声明 vs 运行时） | IMPLEMENTED | 同上，`RuntimeCapabilities` 由接口存在性推导 |
| MediaCatalog | IMPLEMENTED | `MediaHub.Domain/MediaModels.cs` |
| PlaybackPlan | IMPLEMENTED | `MediaHub.Domain/PlaybackModels.cs` |
| PlaybackController | IMPLEMENTED | `MediaHub.Player/IPlaybackEngine` + `MpvPlayer` |
| TrackController | IMPLEMENTED | `ITrackController` + `MpvPlayer` |
| ProgressSync | PARTIAL — 本地快照 IMPLEMENTED；远端上报实现但**未经真服验证** | `ProgressSyncPolicy`、`MediaBrowserProvider.ReportAsync` |
| NetworkPolicy | IMPLEMENTED | `MediaHub.Net/NetworkPolicy.cs`、`OriginScopedCredentialHandler.cs` |
| SettingsContract | IMPLEMENTED | `MediaHub.App/Services/SettingsStore.cs` |
| UnifiedSearch | PARTIAL — 单源 Provider 已实现；跨源聚合 NOT_STARTED | `IProviderSearch` |

## 媒体来源

| 来源 | 浏览 | 播放 | 进度 | 说明 |
| --- | --- | --- | --- | --- |
| 本地 / 映射盘 / UNC | IMPLEMENTED | IMPLEMENTED | 本地快照 IMPLEMENTED | 含同目录外挂字幕发现 |
| Emby | IMPLEMENTED_UNVERIFIED | IMPLEMENTED_UNVERIFIED | IMPLEMENTED_UNVERIFIED | 共享 MediaBrowser 实现；**无真服验证** |
| Jellyfin | IMPLEMENTED_UNVERIFIED | IMPLEMENTED_UNVERIFIED | IMPLEMENTED_UNVERIFIED | 同上 |
| WebDAV | NOT_IMPLEMENTED | NOT_IMPLEMENTED | NOT_IMPLEMENTED | 不得标记为支持 |
| Plex | NOT_IMPLEMENTED | — | — | 注册表中不出现 |
| SMB | NOT_STARTED | — | — | 映射盘走 Local 路径 |
| 云盘 / STRM | NOT_STARTED | — | — | — |

## 播放

| 能力 | 状态 |
| --- | --- |
| libmpv 嵌入（`--wid`） | IMPLEMENTED（EXECUTED_PASS） |
| D3D11 硬件解码 | IMPLEMENTED（实测 `hwdec=d3d11va`） |
| 软件解码回退 | IMPLEMENTED（`hwdec=auto-safe`，**未经故障注入验证**） |
| 播放 / 暂停 | IMPLEMENTED（引擎测试断言） |
| 拖动进度 | IMPLEMENTED（引擎测试断言 seek 落点） |
| 音量 / 静音 | IMPLEMENTED |
| 倍速 | IMPLEMENTED |
| 音轨切换 | IMPLEMENTED |
| 内封字幕 | IMPLEMENTED（引擎轨道枚举已验证） |
| 外挂字幕（SRT/ASS/SSA/VTT） | IMPLEMENTED |
| 字幕样式（字体/字号/颜色/描边/位置/延迟/ASS 覆盖） | IMPLEMENTED |
| 全屏切换 | IMPLEMENTED_UNVERIFIED（未截图确认） |
| 自动连播下一集 | NOT_IMPLEMENTED |
| 播放列表 | NOT_IMPLEMENTED |
| 硬件解码故障回退实测 | NOT_RUN |
| HDR / 色彩管理 | NOT_RUN |
| 杜比视界 | NOT_SUPPORTED（**不得用 HDR10 能力代替声明**） |
| RTX VSR / 超分 / 插帧 | NOT_STARTED |
| 音频独占 / HDMI 直通 / 多声道 | NOT_IMPLEMENTED |
| 音画同步偏移 | NOT_IMPLEMENTED |

## 桌面集成

| 能力 | 状态 |
| --- | --- |
| 命令行打开文件（关联入口） | IMPLEMENTED |
| 双击启动播放 | PARTIAL — 入口已具备，注册表关联 NOT_IMPLEMENTED |
| 文件夹扫描 | IMPLEMENTED |
| 拖放打开 | NOT_IMPLEMENTED |
| 多窗口 | NOT_IMPLEMENTED |
| Windows 媒体按键 | NOT_IMPLEMENTED |
| 系统音量 / 音频设备切换响应 | NOT_IMPLEMENTED |
| 任务栏 / 窗口状态恢复 | NOT_IMPLEMENTED |
| 最小化后台音频 | NOT_IMPLEMENTED |
| 休眠与屏幕唤醒管理 | NOT_IMPLEMENTED（已设 `stop-screensaver=yes`） |

## UI

| 能力 | 状态 |
| --- | --- |
| 首页（继续观看） | IMPLEMENTED |
| 本地媒体页（添加文件夹 / 扫描 / 打开文件） | IMPLEMENTED |
| 媒体源页 | PARTIAL — 列出已配置源与运行时能力；**添加/编辑对话框 NOT_IMPLEMENTED** |
| 搜索页 | NOT_IMPLEMENTED |
| 收藏 | NOT_IMPLEMENTED |
| 设置页（播放/字幕/界面/引擎/诊断） | IMPLEMENTED |
| 深色 / 浅色主题 | IMPLEMENTED（跟随系统） |
| 多 DPI 自适应 | IMPLEMENTED（PerMonitorV2） |
| 窗口尺寸自适应 | IMPLEMENTED |
| 海报墙 / 详情页 / 季集导航 | NOT_IMPLEMENTED |
| 简洁 / 详细两种信息模式 | IMPLEMENTED（详细模式读取引擎真值，未知即"未知"） |
| 加载 / 空 / 错误状态 | PARTIAL |

## 测试与交付

| 项 | 状态 |
| --- | --- |
| 领域与协议单元测试 | IMPLEMENTED（99 tests） |
| Provider 契约测试 | IMPLEMENTED |
| HTTP / 代理 / 重定向测试 | IMPLEMENTED（真实回环监听器） |
| 播放状态机测试 | PARTIAL（引擎集成测试覆盖播放/暂停/拖动） |
| 并发与取消测试 | NOT_IMPLEMENTED |
| Windows UI 自动化测试 | NOT_IMPLEMENTED |
| 播放引擎集成测试 | IMPLEMENTED（`MpvProbe`，EXECUTED_PASS） |
| 端到端（模拟服务器） | NOT_IMPLEMENTED |
| Windows 真机实测 | **PARTIAL**（本机单设备） |
| Windows CI | 已配置（`.github/workflows/windows-ci.yml`），首次运行结果见 PR |
| 安装包 / 便携版 | PARTIAL — 自包含产物已可运行；未打包、未签名 |
| 第三方许可证清单 | IMPLEMENTED（`windows/THIRD-PARTY-NOTICES.md`） |
