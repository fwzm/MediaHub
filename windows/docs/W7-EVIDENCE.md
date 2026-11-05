# W7 交付证据：多文件临时队列（B2）· WebDAV 目录与错误边界（A3）· R6 回归（C）

本文件只记录本轮实际执行过并留下证据的结论。状态词限定为
EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / NOT_RUN /
NOT_IMPLEMENTED / FAILED / BLOCKED / RELEASE_BLOCKED / SIGNING_BLOCKED。
「动作被调用」与「应用状态被改变」分开举证：每个点击之后都断言界面状态或服务器侧抓包。

## 一、轮次边界与开工基线

- 分支 `feature/windows-client`，PR **#33**（Draft，base `main`）。本轮未创建新 PR、未合并、未 force push、未改 PR #39、未混入 W6 内容。
- 开工基线核验（不是引用历史报告）：HEAD `54dd961adc6bef2d02c5f207e4e78bd29e84f771` == `origin/feature/windows-client`；
  `git diff --stat 2021893 54dd961` 只有 `README.md | 4 +---`（每日同步脚本 2026-10-23 00:00 的摘要提交），代码与 R6 交付一致；
  `origin/main` = `1246313c7a66d492258b5b4b20b51cbdcf2da9cc` 未变。
- 提交：`585336e`（Gate B2 代码与脚本，15 文件 +1310/−200）；A3/C 的缺陷修复、验收脚本与本文档在同一批提交里，head 以 PR #33 为准。

## 二、Gate B2：多文件临时播放队列

### 2.1 状态

| 交付面 | 状态 | 证据 |
| --- | --- | --- |
| 原生多选 → 临时队列 → 逐项播放 | EXECUTED_PASS | `r7-b2-queue.ps1` 63/63 |
| 队列 UI（当前/下一项、上一项/下一项、可见列表、移除、清空） | EXECUTED_PASS | 同上（`panel.queue`、`player.queuecount`） |
| EOF 自动跳项、失败跳项、连续失败上限、AutoPlayNext=false | EXECUTED_PASS | `r7-b2-failure.ps1` 18/18 |
| 队列顺序/去重/取消/替换契约 | EXECUTED_PASS（单测 + GUI） | `PlaybackQueueTests` 14 条 + GUI |
| 未知长度/损坏文件跳过 | EXECUTED_PASS | F1/F2 |
| 持久播放列表 | NOT_IMPLEMENTED（本轮明确不做，队列只存在于本次会话） | 侧栏文案「队列只存在于本次会话，不会被保存成播放列表。」 |

### 2.2 队列契约（实现即事实，均可从源码复核）

- 顺序 = 用户多选顺序（`FilePickerService.PickFilesAsync` 的返回顺序）；拖放取 drop 里的顺序；CLI 取参数顺序。
- 去重按 identity：本地 `local:<大写完整路径>`、远端 `item:<serverId>/<itemId>`；同一 identity 只保留首次出现。
- `Replace`：清空后重建，当前项置 0。`Append`：保序追加，不改变当前项；空队列时取首项。
- 播放中追加只刷新队列视图，不打断当前播放；播放中替换切到新队列首项（旧会话按 `replaced` 释放）。
- 上一项/下一项不环绕；到两端再点无动作（trace `queue:switch` 不出现）。
- 移除当前项 → 提升下一项；无下一项回退上一项；移除最后一项 → 停止播放并退出播放页。
- 清空队列不停止当前播放（当前项仍在播，只是没有前后项）。
- 空摄入（选择器取消、全被路径校验过滤、全部重复）不改动正在播放的内容。
- 本地文件不产生任何服务端进度请求：`OnProgressTick` 的本地分支只写本地历史后返回。
- CLI、拖放、文件关联、菜单多选共用同一入口 `MainWindow.OpenPaths(paths, mode)`。

### 2.3 本轮由 GUI 实测暴露并修掉的产品缺陷

1. **切项后新视频冻结在 0:00**。`MpvPlayer.LoadAsync` 从不重置 `pause`，而 mpv 的 pause 属性跨 `loadfile` 保留；
   切项前调用的 `Player.Pause()` 于是传染给下一项。修法：`LoadAsync` 内 `SetProperty("pause","no")`，把「加载即开始播放」写成不变量。
2. **本地文件永不触发自动跳项**。`PlayerPage.OnProgressTick` 在本地分支写完历史后直接 `return`，
   后面的 `EofReached/Ended` 判断永不执行 —— 纯本地队列因此永远不会自动跳到下一项。修法：先算 `finished`，本地分支内也按它触发。
3. **`MpvPlayer.DecodeEndFile` 不闩锁 EOF**。`eof-reached` 观测属性只在文件仍加载时为真，`keep-open=no` 下 mpv 立刻卸载并复位，
   仅靠属性轮询会丢掉每一次播放结束。修法：`end.Reason == MPV_END_FILE_REASON_EOF` 时置 latch，下次 `LoadAsync` 复位。

附带修复：`QueueRow` 重写 `ToString()` —— ListViewItem 的可访问名取 `item.ToString()`，
否则屏幕阅读器读到 `QueueRow { Index = 0, ... }`。

### 2.4 实测数据（evidence/r7-b2-queue、evidence/r7-b2-failure）

- `RESULT 63/63 passed, 0 failed`。关键 trace：`queue:intake mode=Replace requested=3 added=3 duplicates=0 count=3`、
  `queue:auto-advance title=queue-c`、`queue:switch reason=completed`、追加后计数「队列 2/3」、替换后「队列 1/1」、
  `queue:remove index=2 remaining=2`、`queue:clear`。
- `RESULT 18/18 passed, 0 failed`：单项失败跳项（`queue:item-failed` → `queue:switch reason=replaced`）、
  连续 3 次失败 `queue:skip-capped`、`AutoPlayNext=false` 时 `queue:auto-advance-disabled` 且手动「下一项」仍可用、
  CLI 非法输入逐条拒绝（`cli:not-a-file` / `cli:unsupported-extension`）。

## 三、Gate A3：WebDAV 目录导航与错误边界

### 3.1 第一部分（真实 WsgiDAV 4.3.3 + 真实 UIA）：24/24 PASS

P1 根目录列举 → P2 逐级进入 `Season 01` → P3 返回上级（根目录时「返回上级」禁用）→ P4 空目录文案
「这个目录是空的。」→ P5 中文空格文件名 `Chinese 中文 视频 空格.mp4` 播放并验证服务器收到 206 + `bytes=1,10444707`
→ P6 **关闭整个应用**释放回环：播放中 `netsh http show servicestate` 中有 127.0.0.1 注册，关闭后进程 0、注册为空。

### 3.2 第二部分（故障注入服务器）：54/54 PASS

| 阶段 | 场景 | 判据要点 | 状态 |
| --- | --- | --- | --- |
| P1 | 子根挂载（服务器根 = 子目录） | 只列子根内容，服务器收到的路径不含子目录 | EXECUTED_PASS |
| P2 | 404 | 文案「未找到：gone」+ 重试入口 + 列表清空 + 两次 PROPFIND 都到服务器 | EXECUTED_PASS |
| P3 | 403 无权限 | 失败态而非空目录 | EXECUTED_PASS |
| P4 | 500 服务器错误 | 同上 | EXECUTED_PASS |
| P5 | 401 认证 | 文案「登录状态已过期，请重新登录」+ 服务器侧 401 | EXECUTED_PASS |
| P6 | 导航竞态（慢服务器连续两次导航） | 面包屑最后一段与列表内容一致，mismatch=0/samples=7 | EXECUTED_PASS |
| P7 | 离线来源（端口无监听）→ 起服务器 → 重试 | 卡片仍可见 + 失败文案 + 重试真的重新请求并列出内容 | EXECUTED_PASS |

失败路径的判据纪律：每条都必须同时核对 ①文案（且不得是「这个目录是空的」）②重试入口可见 ③列表清空 ④服务器确实收到该请求；
「重试」另需证明第二次请求真的到达服务器（抓包 jsonl 计数）。

### 3.3 本轮修掉的产品缺陷

1. **面包屑显示未解码的百分号 URL，且根目录「返回上级」可用**。旧实现靠裁 URL 尾段求父目录，根 URL 被当成普通文件夹，
   于是永远差一级到不了根。改为访问轨迹栈（`_trail`，索引 0 = 根，Title 固定「根目录」），面包屑由轨迹拼接，`UpButton.IsEnabled = _trail.Count > 1`。
2. **加载失败时页头不更新，用户被困在报错目录**。`Fail()` 原先不更新标题/面包屑/返回上级状态，从根目录点进一个列举失败的子目录后，
   页头仍写「根目录」且「返回上级」仍禁用，唯一可用的控件是重试 —— 而重试修不了不存在的目录。
   实测症状是对禁用的 `browse.up` 调 InvokePattern 抛 `MethodInvocationException: 无法识别的错误`。
   修法：`Fail(message, title, breadcrumb, canGoUp)` 接收**请求前快照**（与成功路径同源）。
3. **启动时不可达的来源在媒体库页彻底消失**。旧 `LibrariesPage` 只遍历 `OnlineHandles`，
   离线来源与「已被删除」无法区分，于是「离线 → 重试」在 UI 上不可达。改为遍历全部来源，非 Online 时只要有 Browse 能力就出「文件夹浏览」卡片并标状态后缀。
4. **慢服务器上第二次导航被静默丢弃**。旧实现「忙则丢弃」。改为 `_pendingReload` 延迟重跑，并在**请求前快照**渲染所需状态
   （面包屑、标题、可否返回上级），避免把 slow1 的内容配上 slow2 的面包屑。
5. **失败态没有重试入口**。`EmptyPanel` 增加 `browse.retry`，失败与空目录文案分离。

### 3.4 自动化基础设施（本轮新增，都在仓库里）

- `windows/tools/acceptance/lib/MhUia.psm1` 新增 `Invoke-MhElementDeep`：自元素向上最多 5 层找第一个有 Invoke/SelectionItem/Toggle 的祖先。
  起因：媒体库卡片的 AutomationId 绑在 `Border`（ControlType=Group，无 pattern），合成鼠标点击**返回成功却不导航**，而 UIA 对 `ListItem` 祖先调 Invoke 立刻导航。
- `windows/tools/acceptance/lib/MhPicker.psm1`：纯 Win32 驱动 shell 文件对话框（`#32770` 类名 + 同进程 pid 双重识别，
  `WM_SETTEXT` 设路径、`BM_CLICK` 确认）。不能用 UIA 驱动：对 shell 视图调 ValuePattern 一律超时，且 Descendants 里第一个 Edit 是壳层属性元素。
- `windows/tools/acceptance/webdav/fault_server.py`：故障注入 WebDAV（`/gone` 404、`/denied` 403、`/broken` 500、`/secure` 401、`/slow1` `/slow2` 各延时 3s），
  抓包按端口分文件 `fault-requests-<port>.jsonl`（并行跑两个实例时不互相截断），已加入 .gitignore。
- `Stop-FaultServer` 改为按 CIM 命令行匹配（`Get-NetTCPConnection` 对 python 监听套接字返回空集，不可信），P7 用 `Find-FreePort` 动态选端口并断言「确实没有监听者」。

## 四、Gate C：对 R6 已修缺陷的回归

| 部分 | 内容 | 结果 |
| --- | --- | --- |
| C1 | 原样重放 `r6-audio.ps1`（音轨面板 toggle / 字幕列表不重建 / .aac 可播） | 12/12 PASS |
| C2 | 原样重放 `r6-pickers.ps1`（三条真实选择器路径 + 一次性提示不被快照覆盖） | 18/18 PASS |
| C3 | R6 未覆盖、但同一批代码路径上的相互作用 | 20/20 PASS |

C1 走的是主路径：日志中有 `PEAK=0.088583`（真实音频会话峰值，与 W3 基线 0.0886 一致），不是「静音也算通过」。
C3 覆盖：面板互斥与 toggle（音轨→字幕→队列依次互斥、同按钮再点关闭）、Escape 两层（先关面板、无面板才退出播放页）、
进入播放页后焦点在视频区（`focused=Pane`，pid 属于本应用）且 Space 暂停使时间冻结（delta=0）、再按恢复（delta=4）、
连续加载两份外挂字幕时列表跟随引擎且各出现一次、一次性提示 8 秒后消退为基线「共 3 条字幕轨道。」。

### 4.1 本轮由 C3 暴露并修掉的产品缺陷

**一次性提示到期后不消退**。`RefreshTrackLists()` 是提示文本唯一的刷新点，而它只由「轨道列表内容变化」触发；
提示到期本身不是列表变化，于是「已加载：xxx」在列表不再变化后永久留在屏幕上（实测 9 秒后仍在）。
修法：`ApplySnapshot` 末尾调 `ExpireSubtitleNotice(count)`，只在有效期已过时清空并重绘基线文案（不引入每帧写入）。

### 4.2 本轮由 C3 暴露的判据缺陷（记录在案，避免再犯）

1. **Space 打到了别的窗口**。`Start-MhApp` 用 `Win32_Process.Create` 启动，进程拿不到前台；不显式前置时合成键盘事件落到控制台
   （实测 `focused` 元素属于 `python.exe`）。于是「暂停」根本没发生，而「恢复播放」因为时间一直在走反而假通过。修法：发送前 `Set-MhForeground`，
   且焦点判据要求 `focused.pid -eq <app pid>`。
2. **每行两个 TextBlock 把一个条目数成两次**。`Get-TrackRows` 会读出同一行的多个文本块，判「出现几次」之前必须 `Select-Object -Unique`。
3. **相邻字幕文件的行名不是文件名**。当字幕是媒体自身的同名字幕（mpv 自动加载）时，`track-list/N/title` 是文件名去掉媒体名后的剩余部分
   （`playback-180s.mp4` + `playback-180s.srt` → 标题 `srt`）。这是 mpv 的自动加载语义，不是客户端缺陷；
   判据改用不与媒体同名的 `subtitle-alpha.srt` / `subtitle-beta.srt` 作为夹具。

## 五、门禁

- 构建：`dotnet build windows\src\MediaHub.App\MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:\mh-out\` → 0 警告 0 错误。
- 单测：`dotnet test windows\tests\MediaHub.Tests\MediaHub.Tests.csproj -c Debug -p:Platform=x64` → **310 passed / 0 failed**（4 s）。
- 回环与安全单测未回归（`MediaStreamBoundaryTests`、`LoopbackBridgeSecurityTests` 在 310 条内）。

## 六、仍未解决（必须留在 Backlog，不得当作已解决）

- **Jellyfin 中断恢复**：180s 视频播到 32s 中断，客户端上报的 stop 位置正确（服务器日志 `stopped at "32000"ms`），
  但服务端仍记 `Played=True`、`PlaybackPositionTicks=0` 并移出 Resume。已隔离到服务端 playstate 判定，客户端侧未找到可修的缺陷 → FAILED（未解决）。
- **Emby 真实播放**：仅完成部署（4.10.1.0，端口 8097）与协议探测；未做完整播放与进度回写验收 → SERVER_UNVERIFIED。
- **发布阻断**：默认引擎为 GPL-3.0-or-later 构建 → RELEASE_BLOCKED；可执行文件未签名 → SIGNING_BLOCKED。
  LGPL 兼容构建已产出（`windows/engine/lgpl/dist/libmpv-2.dll`）但**尚未切换**，因此不改变上面的阻断状态。
- 收藏与播客列表的写入闭环、剧集自动连播的 GUI 验收、Emby/Jellyfin 的服务端搜索等仍在 Backlog。

## 七、复现

```powershell
cd D:\deepseek_test\MediaHub_Win
# 构建（App 必须带 MediaHubBinRoot：有个旧进程锁住了 src\MediaHub.App\bin）
dotnet build windows\src\MediaHub.App\MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:\mh-out\
# 单测
dotnet test windows\tests\MediaHub.Tests\MediaHub.Tests.csproj -c Debug -p:Platform=x64
# GUI 验收（各自会启动/关闭真实 MediaHub.exe，证据落在被忽略的 windows\tools\acceptance\evidence\）
pwsh -NoProfile -File windows\tools\acceptance\r7-b2-queue.ps1
pwsh -NoProfile -File windows\tools\acceptance\r7-b2-failure.ps1
pwsh -NoProfile -File windows\tools\acceptance\r7-a3-webdav.ps1
pwsh -NoProfile -File windows\tools\acceptance\r7-a3b-webdav.ps1
pwsh -NoProfile -File windows\tools\acceptance\r7-c-regress.ps1
```

依赖：WsgiDAV 4.3.3 / Cheroot 11.1.2（`windows\tools\acceptance\webdav\start-server.ps1` 会自行启动）、
Python 3.13（故障服务器用 stdlib，无额外依赖）、夹具 `D:\mh-media`（`windows\tools\make-test-media.ps1` 可重建）。
