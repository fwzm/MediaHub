# W8 证据：真实服务端续播与 Emby 闭环

本轮主题：先确认服务端规则，再改协议。所有数字均为本轮实测。

## 开工基线

| 项 | 值 |
| --- | --- |
| 仓库 | `fwzm/MediaHub`，工作树 `D:\deepseek_test\MediaHub_Win` |
| 分支 | `feature/windows-client`，PR **#33**（Draft，base `main`） |
| 开工 HEAD | `f43e0cb32dd9fcfbb3c1dd1af6bf522ee4ea271d`（== origin/feature/windows-client） |
| origin/main | `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`（未动） |
| PR #39 | head `01233cabf1488f3c05fcd44e472fee153fe0b977`，base `feature/windows-client`，未触碰 |
| 被测 exe | `D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe`，289,792 B，2026-10-23 02:22:48 |
| exe SHA256（S1 验收用） | `0E1CEEBFAB83BF5EE157127E6BD2986412D7379D061C67ECEED6428A8FCE7C93`（289,792 B，2026-10-23 02:22:48） |
| exe SHA256（S2 验收用） | `20A41B39E687326E0DA7E259A13CFF4E27616A61B00817A8CC4648BF94E51DA9`（289,792 B，2026-10-23 20:23:18，含 S2 的文件夹导航与「刷新即重测」修复） |
| Jellyfin | 12.2.0，`http://127.0.0.1:8096`，数据 `D:\mh-servers\data\jellyfin`，ServerId `daffc6534af44bbb8f73848209519255` |
| Emby | 4.10.1.0+ac2d75c0，`http://127.0.0.1:8097`，数据 `D:\mh-servers\data\emby` |

## Gate S1：Jellyfin 真实中断续播

### S1.1 服务端规则（源码级确认，不靠默认值猜）

三处事实来源，互相独立：

1. **实例配置实测**（`GET /System/Configuration`）：`MinResumeDurationSeconds = 300`、`MinResumePct = 5`、`MaxResumePct = 90`。
2. **服务端日志**（`evidence/w8-s1/jellyfin-playstate-lines.txt`）：客户端上报的位置被服务端如实记下（`stopped playback … at "32000"ms`），排除"位置没送到"。
3. **二进制反编译**：`ilspycmd -p` 把安装目录里的 `Emby.Server.Implementations.dll` 还原为 C#，判定函数是
   `Emby.Server.Implementations.Library.UserDataManager.UpdatePlayState(BaseItem item, UserItemData data, long? reportedPositionTicks)`：

```csharp
long runTimeTicksForPlayState = item.GetRunTimeTicksForPlayState();
long num = reportedPositionTicks ?? runTimeTicksForPlayState;   // 未上报位置 == 按播完处理
bool flag = runTimeTicksForPlayState > 0;
if (((num > 0) & flag) && !(item is AudioBook) && !(item is Book))
{
    decimal num2 = decimal.Divide(num, runTimeTicksForPlayState) * 100m;
    if (num2 < (decimal)_config.Configuration.MinResumePct)            // < 5%
    {
        num = 0L;                                                       // 清零，但不算已观看
    }
    else if (num2 > (decimal)_config.Configuration.MaxResumePct        // > 90%
             || num >= runTimeTicksForPlayState - 10000000)            // 或距片尾 < 10 s
    {
        num = 0L;
        result = (data.Played = true);                                 // 记为已观看
    }
    else if (TimeSpan.FromTicks(runTimeTicksForPlayState).TotalSeconds
             < (double)_config.Configuration.MinResumeDurationSeconds) // 片长 < 300 s
    {
        num = 0L;
        result = (data.Played = true);                                 // 记为已观看，位置清零
    }
}
```

停止路径的调用点 `SessionManager.OnPlaybackStopped(User user, BaseItem item, long? positionTicks, bool playbackFailed)`：

- `if (playbackFailed) return false;` —— 客户端把停止上报标成失败时，**服务端整段丢弃**，位置与会话都不记；
- 传了 `positionTicks` → 走 `UpdatePlayState`；
- **没传** `positionTicks`（null）→ 直接 `PlayCount++ / Played = true / PlaybackPositionTicks = 0`。

由此得到客户端的硬约束（当前实现 `windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs` 已满足）：

| 规则 | 客户端要求 | 现状 |
| --- | --- | --- |
| 片长 < MinResumeDurationSeconds 时不续播 | 客户端无法改变，只能如实呈现 | 已确认（W4 曾误判为缺陷） |
| 停止时不带 PositionTicks 会被当成播完 | 必须每次都带位置（含 0） | `ReportAsync` 恒写 `PositionTicks`，注释写明原因 |
| `Failed=true` 会丢弃整条停止信息 | 用户正常退出绝不能标成失败 | 停止上报不带 `Failed` 字段 |
| 位置 < 5% 会清零 | 低于 5% 的退出不留续播点 | 服务端行为，客户端不改写 |

### S1.2 三组对照（只改一个变量）

夹具：库内原有最长片 `Resume Test (2024)`（180 s，id `14f6f919de954038da11705e5933cfc4`）；本轮用 ffmpeg 9.0.2 生成 `Long Resume (2026)`（600 s，31,104,953 B，id `b22e31da9faf6ef247e19f7b3774dab2`）。

每组先 `DELETE /Users/{uid}/PlayedItems/{itemId}` 复位并回读 `Played=false / pos=0`，再按真实会话序列 `POST /Sessions/Playing` → `/Sessions/Playing/Progress` → `/Sessions/Playing/Stopped` 上报，最后回读 `UserData` 与 `/UserItems/Resume`。脚本 `windows/tools/acceptance/w8/s1-threshold.ps1`，证据 `windows/tools/acceptance/evidence/w8-s1/case-{A,B,C}.json`。

| 组 | 片长 | 停止位置 | 占片长 | MinResumeDurationSeconds | Played | PositionTicks | Resume 列表 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | 180 s | 32 s | 17.8% | 300 | **True** | **0** | 不含 |
| B | 600 s | 100 s | 16.7% | 300 | **False** | **1,000,000,000**（100 s） | 含 |
| C | 180 s | 32 s | 17.8% | **60** | **False** | **320,000,000**（32 s） | 含 |

结论：判据是**片长**是否达到 `MinResumeDurationSeconds`，不是播放进度百分比——A 与 B 的百分比几乎相同（17.8% vs 16.7%）而结果相反，C 只把阈值改到 60 s 就翻转为未观看。**W4 记录的「中断恢复 FAILED」是夹具片长低于服务端阈值造成的，不是客户端缺陷**；客户端在此路径上发送的位置、会话序列、鉴权头都没有问题。

实验后已把 `MinResumeDurationSeconds` 还原为 300 并重启，`GET /System/Configuration` 实测回到 300/5/90；隔离实例的配置备份（`system.xml.before-case-c`、`system.xml.case-c`）与实验脚本一并留在 evidence 目录，可复现。未改用户真实服务器配置之外的任何东西。

### S1.3 真实 GUI 门禁

脚本 `windows/tools/acceptance/w8/s1-resume-gui.ps1`，证据 `windows/tools/acceptance/evidence/w8-s1/gui/`（`run4.log`、`checks.json`、`p1-midway-paused.png`、`p2-resumed.png`、`p4-ended.png`、`jellyfin-playstate-window.txt`、`startup.log`）。**RESULT：29/29 passed，0 failed**。

夹具是这次唯一的 600 s 片 `Long Resume (2026)`（Jellyfin item id `b22e31da9faf6ef247e19f7b3774dab2`），每一步的服务器位置都由 Jellyfin 官方 API 回读，脚本不写任何 UserData。

| 步骤 | 断言 | 实测值 |
| --- | --- | --- |
| P1.1–P1.5 | 库 → 详情 → 播放页，引擎出画面 | `technical=640×360 · H.264 · hwdec=d3d11va · aac`，duration=600 s |
| P1.6 | Seek 到中点 | 滑块 0.5 → 引擎 `position=300 s` |
| P1.7 | 暂停后时钟冻结 | `p1=304 s`，`p2=304 s` |
| P1.8 | 退出播放页（正常退出，非强杀） | `method=graceful` |
| P1.9 | 服务端记下的中断位置与客户端一致 | 服务端 `304.54 s`，客户端 `304 s`，差 0.5 s（客户端未写脚本值） |
| P1.10 | 未播完时不得记为已观看 | `played=False`、`pos=304.54 s`、`playCount=0` |
| P1.11 | 出现在 Jellyfin 续播列表 | `/UserItems/Resume` 含该项 |
| P2.x | 关闭客户端再重启（正常退出，等待 1.2 s） | P2.3 详情页出现 `detail.resume` |
| P2.5 | 引擎实际起始位置落在服务端记录附近 | 引擎 `304 s` vs 服务端 `304.58 s`，差 0.6 s |
| P3.3 | 第二次停止覆盖第一次的位置 | `before=304.58 s` → `after=187.62 s` |
| P3.4 | 仍未播完 | `played=False` |
| P4.3–P4.5 | Seek 到 591 s 后自然播完 | `played=True`、`pos=0`、`ticks=0`、`playCount=3`，并移出 Resume |

这五条串起来就是任务书要的闭环：**播放 → 中断 → 服务端记录真实位置 → 完全重启客户端 → 引擎从该位置继续 → 再报告仍为未观看 → 自然结束才算看完**。GUI 层的每个结论都由「引擎状态 + 服务端回读」两边对齐，不是只看界面文字。

### S1 结论（回答任务书问题 A、B）

- **A：Jellyfin 180 s 片中断后变「已观看」不是客户端缺陷**，根因是 `MinResumeDurationSeconds=300`（S1.1 源码 + S1.2 三组对照，改一个变量即翻转）。W4 的「中断恢复 FAILED」结论据此更正。
- **B：≥600 s 片已经真实完成「中断 → 完全重启 → 恢复」**（S1.3 的 P1/P2/P3 段）。

## Gate S2：Emby 官方实例的播放与进度闭环

脚本 `windows/tools/acceptance/w8/s2-emby-gui.ps1`，证据 `windows/tools/acceptance/evidence/w8-s2/gui/`（`run6.log`、`checks.json`、`p1-playing.png`、`p2-paused.png`、`p3-resumed.png`、`p4-ended.png`、`p5-offline.png`、`emby-http-window.txt`、`startup.log`）。**RESULT：43/43 passed，0 failed**。

被测实例：官方 Emby 安装（`D:\mh-servers\emby-install\app\system\EmbyServer.exe`，`-programdata D:\mh-servers\data\emby`，端口 8097），账号 `mh`，ServerId `1271d0a1040046db8c0089cc328bbbf1`，媒体 `Long Resume (2026)`（600 s，Emby item id `34`，短数字串）。

### 版本偏离（必须记录）

开工时是 **4.10.1.0**，验收中途官方实例**自行执行了官方自动更新**：服务端日志 `D:\mh-servers\data\emby\logs\embyserver-63928383089.txt` 有 `Exiting to perform application update`，新实例写 `Emby Server 4.10.2.0`。因此本轮最终实测版本是 **4.10.2.0**，脚本断言写 `4.10.*` 并在此如实记录，不按任务书的旧版本号硬编码。已在隔离测试实例的 `config/system.xml` 关闭 `<EnableAutoUpdate>`（备份 `evidence/w8-s2/system.xml.before-autoupdate-off.xml`），避免验收途中再次重启。

### 逐段实测

| 阶段 | 断言 | 实测值 |
| --- | --- | --- |
| P0 | 官方登录 / 版本 / 复位 | `user=mh`、`version=4.10.2.0`、`played=False pos=0 count=0` |
| P1.4 | 引擎时钟真的在走 | `position=6 s` |
| P1.5 | 真实硬解，不是降级 | `640×360 · H.264/AVC · hwdec=d3d11va · bt.1886 · aac · 24fps` |
| P1.6 | 播的是 600 s 那条 | `duration=600 s` |
| P1.7 | 播放期间回环中继已在 http.sys 注册（先证存在） | `ports=50582` |
| P1.8 | 源站真的收到媒体流请求 | Emby 日志 `Response 206 … GET http://127.0.0.1:8097/videos/34/stream` |
| P1.9 | 客户端向源站发出了播放会话上报 | `Sessions/Playing` 命中 4 次 |
| P2.1 | Seek 到中点生效 | `position=300 s` |
| P2.2 | 暂停后时钟冻结 | `p1=304 p2=304` |
| P2.4 | 客户端退出后 Emby 记下了中断位置（脚本不写 UserData） | 服务端 `304.33 s` vs 客户端 `304 s`，差 0.3 s |
| P2.5 | 中断条目未被标成已观看 | `played=False pos=304.33s count=1` |
| P2.6 | 出现在 Emby 的 Resume（`/Users/{uid}/Items/Resume`） | `resumeCount=1 contains=True` |
| P3.1 | 完全重启客户端，正常退出非强杀 | `method=graceful waited=1.1 s` |
| P3.2 | 退出后回环中继注册消失（先证存在于 P1.7） | `ports=`（空） |
| P3.4 | 重启后详情页出现续播入口 | `detail.resume` 存在 |
| P3.6 | 引擎实际起始位置落在服务端记录附近 | 引擎 `306 s` vs 服务端 `304.33 s`，差 1.7 s |
| P4.1–P4.4 | 播到片尾自然结束 | `seek=591 s` → `played=True pos=0 count=2`、`ticks=0`、移出 Resume（`resumeCount=0`） |
| P5.2 | 离线时给出具名失败提示而不是空列表 | `1 个媒体源未能加载 :: emby test server：网络错误，请检查网络连接` |
| P5.4 | 服务端恢复后点「刷新」列表真的回来 | `cardFound=True` |
| P5.7 | 令牌被服务端吊销后提示需要重新登录 | `1 个媒体源未能加载 :: emby test server：登录状态已过期，请重新登录` |
| P5.9 | 客户端日志不含令牌明文 | `tokenLen=32`，日志 428 字符，不含该令牌 |
| P5.10 | 401 是源站给的，不是客户端臆造 | Emby 日志 `Response 401` 命中 1 次 |
| P6.1–P6.3 | 同一份客户端连 Jellyfin 仍可用（跨服务端回归） | 登录成功、`library|jellyfin*` 卡片可达、`position=6` |

### 本轮由真服暴露并修掉的产品缺陷

1. **Emby 库根是「每片一个文件夹」，而库页只会往详情页走**：点开 `Long Resume (2026)`（Folder，id `27`）得到的是一个没有可播内容的详情页，点播放对文件夹发 `POST /Items/27/PlaybackInfo` → **500**。Jellyfin 同一库被扁平化成电影，所以这条路径此前从未被测到。修法：`LibraryNavigation` 增加 `FolderId/FolderTitle`；`LibraryBrowser.Open(..., parentId)` 支持指定父节点；库页遇到文件夹条目再进一层、标题显示「库 / 文件夹」、返回键先回库根；`DetailPage.Render` 对文件夹隐藏播放入口并说明「这是一个文件夹」。
2. **媒体库页的「刷新」不重连离线来源**：旧实现只重绘已知状态，来源一旦离线就永远停在这一页的旧状态，用户只能去「媒体源」页手工重连——刷新按钮看起来像恢复入口却什么也不恢复。修法：`LibrariesPage.LoadAsync` 对非 Online 且非本地来源逐个 `Servers.RetestAsync(serverId)`（先探连通性再恢复会话）；P5.2 → P5.4 就是这条恢复路径的实测。
3. **启动时恢复失败就没有重试入口**（R7 修了一半，本轮补齐）：离线来源现在仍以「文件夹浏览 · 离线」卡片或失败横幅出现，不会从这个页面消失。

### 两个服务端之间的协议差异（回归确认「没修一端坏另一端」）

| 差异点 | Emby 4.10 | Jellyfin 12.2 | 客户端处理 |
| --- | --- | --- | --- |
| 鉴权头 | 接受 `X-Emby-Authorization`，也接受 `MediaBrowser …` | **只接受** `Authorization: MediaBrowser Client=…,Device=…,DeviceId=…,Version=…` | 统一发 `MediaBrowser` 形态（W4 修复，本轮在 Emby 上回归通过） |
| 条目 id | 短数字串（`34`） | GUID | id 全程按不透明字符串处理，不做 Guid 解析 |
| 用户获取 | `GET /Users/{userId}` 200；`GET /Users/Me` **500 Unrecognized Guid format** | `/Users/Me` 200 | 先 `/Users/{userId}` 再回退 `/Users/Me`（W5 修复） |
| PlaySessionId | PlaybackInfo 根节点 | PlaybackInfo 根节点（12.2） | 先读根节点、`MediaSources[0]` 作回退（W4 修复） |
| Resume 列表 | `GET /Users/{uid}/Items/Resume`；`/UserItems/Resume` 404 | `GET /UserItems/Resume` | 按服务端类型选端点，两条路径本轮各自实测通过 |

## 回归门禁

| 项 | 结果 |
| --- | --- |
| 单元测试 `dotnet test windows\tests\MediaHub.Tests` | 310 passed / 0 failed |
| App 构建（`-p:MediaHubBinRoot=D:\mh-out\`） | 0 警告 0 错误 |
| S1 GUI 门禁 | 29/29 |
| S2 GUI 门禁 | 43/43 |
| R7 队列 / WebDAV / 音频 / 选择器脚本 | 未重放（见「仍未完成」） |

## 仍未完成（不得误报为已解决）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| Gate U1 来源隔离的「继续观看」页 | NOT_IMPLEMENTED | 详情页有续播入口，但没有跨来源的继续观看列表 |
| Gate U2 AutoPlayNext 多集实测 | NOT_RUN | 剧集自动连播逻辑在 R7 已实现，本轮未在真服上跑多集 |
| Gate U3 收藏 / 永久播放列表 | NOT_IMPLEMENTED | 未开工 |
| LGPL 引擎切换 / 许可 / 签名 | RELEASE_BLOCKED / SIGNING_BLOCKED | 本轮未动；`libmpv-2.dll` 仍是 GPL 构建，切换 LGPL 构建不在本轮范围 |
| R7 脚本重放 | NOT_RUN | 本轮改了 `LibraryPage`/`LibrariesPage`/`DetailPage`，WebDAV 走 `BrowsePage` 未受影响；下一轮应重放 `r7-a3-webdav.ps1` 与 `r7-b2-queue.ps1` 作为防回归 |

## 计数口径说明

W7 报告里的「Gate C 23/23」与分项「C1 12/12、C2 18/18、C3 20/20」不是同一层数字：前者是 `r7-c-regress.ps1` 自身的断言数，后者是被它重放/内嵌的两套脚本与 C3 段各自的断言数，两者不矛盾，也不可相加。本轮 S1=29、S2=43 均为脚本自身断言数。

## 复现命令

~~~powershell
# Jellyfin 12.2（8096）
pwsh -File windows\tools\setup-test-server.ps1 -Server jellyfin
# Emby 4.10（8097，隔离实例，已关自动更新）
Start-Process 'D:\mh-servers\emby-install\app\system\EmbyServer.exe' -ArgumentList @('-programdata','D:\mh-servers\data\emby','-nointerface')
# 阈值三组对照
pwsh -File windows\tools\acceptance\w8\s1-threshold.ps1
# S1 真实 GUI 续播
pwsh -File windows\tools\acceptance\w8\s1-resume-gui.ps1
# S2 Emby 端到端（正式留证不要带 -OnlyP5）
pwsh -File windows\tools\acceptance\w8\s2-emby-gui.ps1
~~~
