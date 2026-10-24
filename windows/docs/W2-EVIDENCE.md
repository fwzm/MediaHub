# W2 执行证据（2026-10-14）

延续 [W1-EVIDENCE.md](W1-EVIDENCE.md) 的纪律：**已执行**与**未执行**严格分开，
不用"代码完成"或"CI 绿"代替真机/真服验证。

## 本轮要回答的两个问题

### 问题一：用户能否从 Windows 客户端登录 Emby/Jellyfin，并通过正常 UI 浏览、选择与播放远端媒体？

**能（EXECUTED_PASS，针对本地模拟服务器）。**

驱动方式：真实运行 `MediaHub.exe`，用 UI Automation 走真实控件（不是脚本直接调后端），
媒体服务器为 `windows/tools/MockServerHost` 启动的模拟 Emby（真实 socket、真实 HTTP）。

观察到的界面文字，逐步：

| 步骤 | 界面实际输出 |
| --- | --- |
| 启动（凭据已由 DPAPI 凭据库恢复） | `已配置 1 个媒体服务器，其中 1 个当前可用。` |
| 媒体库 | `Movies / GUI Test Server / 电影`、`TV Shows / GUI Test Server / 剧集` |
| 打开 Movies | `已加载 40 / 40 项`，海报网格渲染 40 项（海报经 provider 鉴权路径取回） |
| 点海报 | `Star Wars 00`、`1990 · 90 分钟 · 评分 6.0`、`来源：GUI Test Server · Emby` |
| 详情页播放源 | `视频 h264 · 1920×1080 · bt709`、`音频 aac · 2 声道 · 48000 Hz`、`字幕 1 条` |
| 点播放 | 播放页 `0:03 / 0:04`、`160×120 · H.264 / AVC · hwdec=d3d11va · bt.1886 · aac · 10fps` |

**关键点**：`0:03 / 0:04` 是**引擎上报**的位置与时长，画面为播放中的一帧（见
[evidence/w2-remote-playback.png](evidence/w2-remote-playback.png)）。

### 问题二：播放引擎的实际网络连接，是否与应用层媒体源鉴权和代理策略一致？

**一致 —— 而且现在是结构上的一致，不是配置上的巧合。**

先测量（`windows/tools/MpvNetworkProbe`，对钉住的引擎构建）：

| 观察 | 结果 |
| --- | --- |
| 同 origin 重定向 | `X-Emby-Token` 保留（正确） |
| **跨 origin 重定向** | **`X-Emby-Token` 被原样投递给另一台主机（凭据泄漏）** |
| `--http-proxy` | 选项名正确且生效，但 **`NO_PROXY` 环境变量可使其被绕过** |
| 选项拼写错误 | 引擎**静默忽略**（旧实现未检查返回码，已修复为记录并上报） |

据此改为：媒体流经 `LoopbackMediaBridge` 取回，引擎只拿到不含凭据的回环 URL。
代理与重定向作用域因此**是同一段网络代码**（`MediaSourceHttpClientFactory` +
`OriginScopedCredentialHandler`），不可能漂移。见 [ADR-W006](adr/ADR-W006-media-relay.md)。

**服务器端观察到的证据**（模拟服务器逐请求记录，共 10 条请求）：

```
12:22:28.764  GET /emby/Users/user-1/Items?ParentId=lib-movies&...   session=yes  range=-
12:22:46.672  POST /emby/Items/item-000/PlaybackInfo?UserId=user-1    session=yes  range=-
12:22:46.672  GET /emby/Videos/item-000/stream.mp4?...&static=true    session=yes  range=bytes=0-
```

- 媒体路由上**没有任何一条**请求缺少会话头（统计：0 条 `session=NO`）。
- 引擎从未直接连接媒体服务器：它只连接回环转发端口。

## 自动化测试

| 层级 | 命令 | 结果 |
| --- | --- | --- |
| 单元与契约 | `dotnet test tests/MediaHub.Tests` | **210 passed / 0 failed / 0 skipped** |
| 模拟服务器集成（真实 socket） | 同上（内含 23 条） | 通过 |
| 引擎网络边界（含凭据不泄漏断言） | 同上（内含 9 条） | 通过 |
| GUI 端到端 | UI Automation 驱动真实应用（见上表） | **EXECUTED_PASS** |

测试分层中"模拟服务器"与"真实媒体服务器"被明确区分：

- **模拟服务器（本机真实 HTTP）**：EXECUTED_PASS。覆盖协议、分页、搜索、Range、401/404/429、
  重定向凭据作用域、播放流路径。
- **真实 Emby/Jellyfin 服务器**：**SERVER_UNVERIFIED**。本轮无合法可用的测试服务器与凭据，
  未使用任何私人账号，未上传任何凭据。

## 本轮定位并修复的缺陷

| # | 缺陷 | 性质 |
| --- | --- | --- |
| 1 | 登录密码错误被报成"登录状态已过期" | 死代码分支：`SendJsonAsync` 把 401 映射为 `AuthExpired`，而登录路径捕获的是永远不会抛出的 `ProviderHttpException` |
| 2 | 引擎选项拼写错误被静默忽略 | 网络安全选项写错会静默失效；已改为记录并上报 |
| 3 | `LibraryPage` 未订阅 `Loaded` | 导航成功但页面空白（由追踪发现：`OnNavigatedTo` 到达而 `OnLoaded`从未触发） |
| 4 | 转发层用 `HttpListenerResponse.Headers` 设置 `Content-Length` | 受限集合抛"参数错误"，连接被中止，调用方看到的是 socket reset |
| 5 | 转发层缺少媒体流鉴权头 | 表现为"loading failed"；已按 origin 授权模型补齐 |

## 未执行 / 未验证（本轮结束时的确切状态）

| 项 | 状态 |
| --- | --- |
| 真实 Emby / Jellyfin 服务器端到端 | **SERVER_UNVERIFIED** |
| 凭据加密存储的跨 Windows 账户/跨机器行为 | 模拟验证（熵不匹配、目录复制）。真实第二账户未测 |
| 多进程并发访问凭据库 | 未测（应用假定单实例） |
| 收藏、播放列表、自动连播、拖放打开、系统媒体按键 | **NOT_IMPLEMENTED** |
| 剧集页的季/集浏览 | 已实现，模拟服务器的剧集库为空，**未经有数据验证** |
| 搜索跨源聚合 | 已实现，**单源验证**；多源部分失败路径由单元测试覆盖 |
| HDR / 多 GPU / 音频独占 / 代理一致性（PAC/TUN/VPN） | **NOT_RUN** |
| MSIX / 代码签名 / 干净机安装 | **NOT_RUN** |
| 全屏切换与按钮逐一点击 | **NOT_RUN**（W1 遗留） |
