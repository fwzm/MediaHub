# Windows 客户端能力对照表

图例：**EXECUTED_PASS**（已实际执行并观察通过）/ **IMPLEMENTED_UNVERIFIED**（代码完成，未经该层验证）/
**SERVER_UNVERIFIED**（无真实服务器/凭据）/ **DEVICE_UNVERIFIED** / **NOT_IMPLEMENTED** / **NOT_STARTED**。

> 本表在每轮结束时必须与代码一致。**不得因为 Android 已有某功能就视为 Windows 已实现**。

## 领域契约

| 契约 | 状态 | 位置 |
| --- | --- | --- |
| SourceRegistry | EXECUTED_PASS（含 UI 增删改查） | `MediaHub.Providers/ProviderContracts.cs`、`MediaHub.App/Services/ServerManager.cs` |
| ProviderCapability（声明 vs 运行时） | EXECUTED_PASS | `RuntimeCapabilities` 由接口存在性推导 |
| MediaCatalog | EXECUTED_PASS（经模拟服务器） | `MediaHub.Domain/MediaModels.cs` |
| UnifiedSearch | IMPLEMENTED_UNVERIFIED（多源部分失败路径有单测；真服未测） | `MediaHub.App/Services/SearchService.cs` |
| PlaybackPlan | EXECUTED_PASS（经模拟服务器） | `MediaHub.App/Services/PlaybackCoordinator.cs` |
| PlaybackController | EXECUTED_PASS | `IPlaybackEngine` + `MpvPlayer` |
| TrackController | IMPLEMENTED_UNVERIFIED（引擎侧轨道枚举已验证；UI 切换未逐一点击） | `ITrackController` |
| ProgressSync | IMPLEMENTED_UNVERIFIED（本地快照 EXECUTED；远端上报经模拟服务器发出，**回写语义未验证**） | `PlaybackCoordinator`、`IProviderProgress` |
| NetworkPolicy | EXECUTED_PASS（含引擎边界实测，见 ADR-W006） | `MediaHub.Net/NetworkPolicy.cs` |
| SettingsContract | EXECUTED_PASS | `MediaHub.App/Services/SettingsStore.cs` |

## 媒体源

| 来源 | 浏览 | 播放 | 进度 | 说明 |
| --- | --- | --- | --- | --- |
| 本地 / 映射盘 / UNC | EXECUTED_PASS | EXECUTED_PASS | 本地快照 EXECUTED_PASS | 含同目录外挂字幕发现 |
| Emby | EXECUTED_PASS（模拟服务器） | EXECUTED_PASS（模拟服务器） | IMPLEMENTED_UNVERIFIED | 真实服务器 4.10.1.0 已部署并完成协议探测，**完整播放与进度回写仍 SERVER_UNVERIFIED** |
| Jellyfin | EXECUTED_PASS（真实 12.2） | EXECUTED_PASS（真实 12.2） | EXECUTED_PASS（真实 12.2） | 登录/浏览/5 部影片播放/进度回写均实测；**中断恢复 FAILED**（服务端 playstate 判定，未解决） |
| WebDAV | EXECUTED_PASS（真实 WsgiDAV 4.3.3） | EXECUTED_PASS（真实 WsgiDAV 4.3.3） | 不支持（协议无 playstate） | 子根挂载、404/403/500/401、离线与重试、导航竞态、关闭应用释放回环：见 [W7-EVIDENCE.md](W7-EVIDENCE.md) |
| Plex / SMB / 云盘 | NOT_STARTED | — | — | — |

## 媒体源管理与凭据（W2-A）

| 能力 | 状态 |
| --- | --- |
| 添加（探测 → 登录 → 保存） | EXECUTED_PASS |
| 编辑 / 重新登录 | IMPLEMENTED_UNVERIFIED（重新登录已实现，UI 点击未逐一实测） |
| 删除（同时清除凭据） | IMPLEMENTED_UNVERIFIED |
| 连接检测 / 全部重新检测 | EXECUTED_PASS（启动恢复路径） |
| URL 规范化（端口/HTTP(S)/反代子路径/`/emby` 去重） | EXECUTED_PASS（测试覆盖） |
| DPAPI 凭据库（版本化 / 原子写 / 损坏隔离 / 异账户识别） | EXECUTED_PASS（79 条测试，含变异验证）；真实第二 Windows 账户未测 |
| 多服务器凭据隔离 | EXECUTED_PASS |
| 重启后恢复 | EXECUTED_PASS |
| 密码/令牌不写入普通配置或日志 | EXECUTED_PASS（`ToString` 脱敏 + 测试） |

## 网络与凭据边界（W2-B）

| 能力 | 状态 |
| --- | --- |
| 每媒体源 NetworkPolicy（系统/直连/显式代理） | EXECUTED_PASS |
| 跨 origin 重定向剥离凭据（应用层） | EXECUTED_PASS（真实回环 socket，两个监听器） |
| HTTPS→HTTP 降级拒绝 | EXECUTED_PASS |
| 重定向跳数上限 | EXECUTED_PASS |
| 日志/诊断写入时脱敏 | EXECUTED_PASS |
| **引擎只经本地转发取流，不持有服务器凭据** | EXECUTED_PASS（`Engine_through_the_bridge_never_delivers_the_token_to_a_redirect_target`） |
| 转发层访问控制（令牌/Host/Origin/Referer） | EXECUTED_PASS |
| 引擎代理值与策略一致（可读回验证） | EXECUTED_PASS |
| 401 / 403 / 404 / 429 / 5xx 映射 | EXECUTED_PASS（模拟服务器） |
| Range / 206 / 416 | EXECUTED_PASS |
| TUN / VPN / PAC 出口一致性 | **NOT_RUN** |
| SOCKS 代理 | **NOT_RUN** |

## 播放

| 能力 | 状态 |
| --- | --- |
| 远端媒体播放（经真实 UI 驱动） | **EXECUTED_PASS**（模拟服务器，见 W2-EVIDENCE） |
| libmpv 嵌入（`--wid`）+ D3D11 硬解 | EXECUTED_PASS（`hwdec=d3d11va`） |
| 播放 / 暂停 / 拖动 / 音量 / 倍速 | EXECUTED_PASS（引擎测试断言） |
| 音轨 / 字幕轨道枚举 | EXECUTED_PASS（真实文件 + UIA，轨道列表跟随引擎内容签名） |
| 内封 / 外挂字幕（SRT/ASS/SSA/VTT） | EXECUTED_PASS（外挂字幕经真实文件对话框加载，列表与提示均实测） |
| 字幕样式（字体/字号/颜色/描边/位置/延迟/ASS 覆盖） | IMPLEMENTED_UNVERIFIED |
| 全屏切换 | IMPLEMENTED_UNVERIFIED（未截图确认） |
| 播放进度恢复 | IMPLEMENTED_UNVERIFIED |
| 剧集自动连播下一集 | IMPLEMENTED_UNVERIFIED（播放页内切换已实现，未在真实剧集库上做 GUI 验收） |
| 多文件临时播放队列（上一项/下一项/移除/清空/EOF 与失败跳项） | EXECUTED_PASS（[W7-EVIDENCE.md](W7-EVIDENCE.md)：63/63 + 18/18） |
| 持久播放列表 | NOT_IMPLEMENTED（队列只存在于本次会话） |
| HDR / 色彩管理 | **NOT_RUN** |
| 杜比视界 | **NOT_SUPPORTED**（不得用 HDR10 能力代替声明） |
| 硬件解码故障回退实测 | **NOT_RUN** |
| 音频独占 / HDMI 直通 / 多声道 / 音画同步偏移 | NOT_IMPLEMENTED |

## UI（W2-D）

| 能力 | 状态 |
| --- | --- |
| 左侧导航：首页 / 媒体库 / 搜索 / 继续观看 / 媒体源 / 设置 | EXECUTED_PASS |
| 媒体库列表（多源 + 来源标签） | EXECUTED_PASS |
| 海报墙（虚拟化 / 懒加载 / 占位图 / 分页预取） | EXECUTED_PASS（40 项） |
| 图片缓存（内存上限 + 磁盘 TTL + 容量上限 + 在途去重） | IMPLEMENTED_UNVERIFIED |
| 电影详情页（真实流信息） | EXECUTED_PASS |
| 季 / 集导航 | IMPLEMENTED_UNVERIFIED（模拟服务器剧集库为空，**未在有数据下验证**） |
| 搜索（多源 / 部分失败提示 / 取消过期请求） | IMPLEMENTED_UNVERIFIED |
| 继续观看（服务端 resume） | IMPLEMENTED_UNVERIFIED |
| 收藏 | NOT_IMPLEMENTED |
| 深色 / 浅色主题、多 DPI、窗口自适应 | IMPLEMENTED_UNVERIFIED（150% DPI 下已实测布局） |
| 本地媒体页 | EXECUTED_PASS |

## 桌面集成

| 能力 | 状态 |
| --- | --- |
| 命令行打开文件 | EXECUTED_PASS |
| 文件夹扫描 | EXECUTED_PASS |
| 文件类型关联 / 拖放 / 多窗口 / 系统媒体按键 / 任务栏状态 | NOT_IMPLEMENTED |

## 测试与交付

| 项 | 状态 |
| --- | --- |
| 单元与契约测试 | **EXECUTED_PASS — 210 tests / 0 failed** |
| 模拟服务器集成（真实 socket） | EXECUTED_PASS（23 条） |
| 引擎网络边界与凭据不泄漏 | EXECUTED_PASS（9 条） |
| 凭据库与 URL 规范化 | EXECUTED_PASS（79 条，含变异验证） |
| 播放引擎集成（真实解码） | EXECUTED_PASS（`MpvProbe`） |
| GUI 端到端（UI Automation 驱动真实应用） | **EXECUTED_PASS（本地，脚本可复现）** |
| 真实媒体服务器端到端 | **SERVER_UNVERIFIED** |
| 硬件兼容矩阵（Intel/AMD/NVIDIA） | **NOT_RUN** |
| Windows CI | 已配置（`.github/workflows/windows-ci.yml`），结果见 PR 检查页 |
| 便携版构建 | EXECUTED_PASS（331.6 MB，可启动） |
| MSIX / 代码签名 / 干净机安装 | **NOT_RUN** |
| 第三方许可证与固定哈希 | 见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)；**libmpv 为 GPL，RELEASE BLOCKER** |
