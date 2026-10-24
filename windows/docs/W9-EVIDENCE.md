# W9 证据：跨来源继续观看 / 自动连播 / 收藏 / 永久播放列表（Windows）

本文件只记录**实际执行过**的结论。未执行的写 NOT_RUN / IMPLEMENTED_UNVERIFIED，不写成通过。
状态词表沿用 W7/W8：EXECUTED_PASS · IMPLEMENTED_UNVERIFIED · GUI_UNVERIFIED · SERVER_UNVERIFIED ·
DEVICE_UNVERIFIED · NOT_RUN · NOT_IMPLEMENTED · FAILED · BLOCKED · RELEASE_BLOCKED · SIGNING_BLOCKED。

## 0. 结论摘要（全部在本文件第 1 节那一个二进制上取得）

| 门 | 内容 | Emby 4.10.2.0 :8097 | Jellyfin 12.2.0 :8096 | 状态 |
|---|---|---|---|---|
| U1 | 跨来源「继续观看」：真实进度 → 列表 → 卡片续播 → 已看完消失 | 39 / 39 | 39 / 39 | **EXECUTED_PASS** |
| U2 | 自动连播：同季跨集 / 跨季 / 关闭开关负向 | 35 / 35 | 35 / 35 | **EXECUTED_PASS** |
| U3 | 收藏端到端：详情页收藏 → 服务器回读 → 重启保持 → 取消 → 收藏页 | 24 / 24 | 24 / 24 | **EXECUTED_PASS** |
| U4 | 永久播放列表：加入 → 命名 → 落盘 → 列表页 → 播放不变质 → 移除/删除 | 40 / 40 | 40 / 40 | **EXECUTED_PASS** |

合计 276 条 GUI 断言（138 × 2 服）全部通过，0 失败、0 跳过，四个 run 均 exit 0，跑动期间被测 dll 哈希未变。

> 2026-10-24 复核（[W12-DOC-AUDIT.md](W12-DOC-AUDIT.md)）：U1 已扩为两服各 41/41（`A8EDD448`）；
> U2 Emby 的目录级产物（`checks.json`/`results.md`/截图）被 W10 的中断跑覆盖成「23 通过 / 12 失败」
> （失败项全是守卫拒绝开窗，非产品失败），但那次 W9 跑自身的 `run-console.log`
> （`RESULT: 35 passed, 0 failed`，2026-10-23 23:50）与 `run-exit.txt`（`0`）仍在，故 U2-Emby 结论**仍可复核**，只是不能只看 `checks.json`。

自动化门禁（同一工作树）：`dotnet test windows/MediaHub.Windows.sln -c Debug` → MediaHub.Tests **373 passed**、
MediaHub.WebDav.Tests **104 passed**，合计 **477 / 0 failed / 0 skipped**。

## 1. 被测二进制（唯一口径）

```
dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out/ -t:Rebuild -nologo
```

| 文件 | 字节 | 时间 | SHA256 |
|---|---|---|---|
| `D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll` | 539648 | 2026-10-23 23:32:01 | `A15F6E4F0057D58EB9178A001F57FAF2510C46682EC759DB1806CBAA4CF7F8E5` |
| 同目录 `MediaHub.exe`（apphost） | 289792 | 2026-10-23 23:32:01 | `67FDE4E80CE836922F3CC8FA5384B5217104C4C7117291F33CBD131AFB3A1DC0` |
| 同目录 `MediaHub.Storage.dll` | 18944 | 2026-10-23 23:31:40 | `5EC6B18121540301080B3F27A6C3EFFF8C944D05C2C8E127468D122D4A9A59C4` |

**证据钉法**：代码在 `MediaHub.dll`，`MediaHub.exe` 只是 apphost —— 它的哈希不随代码变化。
所有验收脚本都在开跑前记录 dll 的 SHA256、跑完再比一次（U2 的 start==end 断言即为此）。历史版本的 exe 哈希
曾被误当作证据，W9 起一律钉 dll。

构建路径坑：只传 `-p:MediaHubBinRoot=D:/mh-out/` 而不给 `-p:Platform=x64`，产物会落到
`D:\mh-out\Debug\...`（缺 `x64` 段），脚本用的 exe 却是 `D:\mh-out\x64\Debug\...`，表现为
「构建成功但跑的是旧二进制」。重建前必须先杀掉残留 MediaHub 进程，否则原生依赖被锁会出 MSB3061。

## 2. 真实服务器环境

| 服务器 | 版本 | 地址 | ServerId | 账号 | 库 / 夹具 |
|---|---|---|---|---|---|
| Emby | 4.10.2.0 | http://127.0.0.1:8097（根路径直接服务 API，无 `/emby` 前缀） | `1271d0a1040046db8c0089cc328bbbf1` | `mh` / `mh-test-pass`（uid `a5d8c0447545470fa9e227b942569158`） | MHMovies(id=22) / Long Resume(id=34) 600 s；Example Show(series=11) 四集 10/12/14/16 s |
| Jellyfin | 12.2.0 | http://127.0.0.1:8096 | `daffc6534af44bbb8f73848209519255` | `mediahub-test` / `mediahub-test-password`（uid `c0c242f88d664109a5ed45f8ba43e335`） | Movies / Long Resume (2026)(`b22e31da9faf6ef247e19f7b3774dab2`) 600 s；Example Show 四集 |

启动：`pwsh -File windows\tools\setup-test-server.ps1 -Server jellyfin`；Emby 用
`Start-Process D:\mh-servers\emby-install\app\system\EmbyServer.exe -programdata D:\mh-servers\data\emby -nointerface`。

## 3. U1 跨来源「继续观看」

- 脚本：`windows/tools/acceptance/u1/continue-watching-gui.ps1`（844 行，SHA256 `2E670C38150A3AE30FDA7C03CC4DED16DC7D2FD6304744A2FF94DBBA6CA87C70`）
  —— W10 扩写后当前值：**890 行 / `991D5BC76A78C09EA3CB6A423A8DD6A10024F3D41098B14C2EF70A537C55ABC9`**（见 [W12-DOC-AUDIT.md](W12-DOC-AUDIT.md)）
- 证据：`windows/tools/acceptance/evidence/u1-continue/{emby,jellyfin}/`（checks.json、results.md、截图、页面全树、trace.log）
- 流程：P0 复位/播种 → P1 真播 600 s、seek 到中点、退出（服务器回读 303 s、played=false）→
  P2 继续观看页出现该条目且无警告 → P3 点卡片续播（起点与服务器差 ≤ 15 s 且 > 30 s）→
  P4 服务器置已看后刷新，条目消失且是真空态 → P5 复位清理。
- **本轮修掉的真缺陷**：`MediaBrowserApi.GetItemsAsync` 不带 `Recursive`，`GetContinueWatchingAsync` 用空
  `ParentId` 调它 —— Emby 4.10 的 `/Users/{uid}/Items` 在非递归时只返回库文件夹（实测 total=3，全是 CollectionFolder），
  过滤 `IncludeItemTypes=Movie,Episode` 后恒为空，页面永远是空态且不报错（假「没有内容」）。
  修法：新增 `MediaBrowserApi.GetResumableItemsAsync`（`Recursive=true` + `Filters=IsResumable` +
  `SortBy=DatePlayed` + `SortOrder=Descending` + `ImageTypeLimit=1`），provider 保留客户端兜底过滤
  `position > 0 && played != true`。真服实测：Emby 非递归 total=3 / 递归 total=16 / `Filters=IsResumable` 命中 1；
  Jellyfin 非递归也命中（故旧代码只在 Emby 侧暴露）。
- 判据坑：`/Users/{uid}/Items/Resume` 与 `/UserItems/Resume` 在 Emby 上返回空，**不能**当验收判据；判据必须与
  客户端同源（同一条 `/Items` 查询串 + 客户端同款过滤）。
- 未覆盖：跨来源多源合并排序（本轮只接一个来源）；WebDAV 等无 Progress 的来源不参与（按设计跳过）。

## 4. U2 自动连播（同季 / 跨季 / 关开关）

- 脚本：`windows/tools/acceptance/u2/autoplay-gui.ps1`（605 行，SHA256 `99C4F2D6FB7946201682FCE4D572260BE07D99F889ABD15DECE6B75203756F9D`）
  —— 当前值：**606 行 / `6A686143DBF991FFE8C98797A62DF929E2A791161096837FDCFF997ECA9CB4ED`**
- 证据：`windows/tools/acceptance/evidence/u2-autoplay/{emby,jellyfin}/`
- 断言要点：P2 seek 到 93% 后自动切到 S01E02（trace `queue:auto-advance episode=16`，播放页标题为 S01E02，
  服务器回读上一集 played=True）；P3 从 S01E03 播到结束跨季进 S02E01（`episode=18`）；P4 关闭 AutoPlayNext 后
  同场景 trace 只有 `queue:auto-advance-disabled`、标题停在 S01E02。
- **本轮修掉的真缺陷**：①剧集身份不透传 —— `DetailPage/BrowsePage/ContinueWatchingPage` 组 `PlaybackRequest` 时
  不填 `SeriesId/SeasonNumber/EpisodeNumber`，而 `PlayerPage` 的自动连播要求 `SeriesId` 非空，真实 GUI 路径永不触发；
  ②`MediaBrowserMapper` 把缺失的 `ParentIndexNumber/IndexNumber` 折成 0，导致「未知集号」排在真实集号之前
  —— 排序改为未知值统一排最后（`ThenBy(e => e.SeasonNumber > 0 ? e.SeasonNumber : int.MaxValue)`，集号同理）。
- 未覆盖：播放结束后「不连播、停在结束画面」的偏好矩阵（只测了开关关）；多集连续自动跳三集以上的长链。

## 5. U3 收藏端到端

- 脚本：`windows/tools/acceptance/u3/favorite-gui.ps1`（396 行，SHA256 `E3256CA617BCAD4EC32B9AFC4BFAB984164C25CFA8F1E3D82D027AD50F8C17BF`）
  —— 当前值：**397 行 / `2A1771F0109C1B735561D5C5E6EA241EFD2C034207A9693F8E0879E5F25D784F`**
- 证据：`windows/tools/acceptance/evidence/u3-favorite/{emby,jellyfin}/`
- 断言要点：详情页点 `detail.favorite` → 按钮变「取消收藏」+ 服务器回读 `IsFavorite=True`（不是本地乐观态）→
  完全重启客户端后仍是「取消收藏」→ 再点回「收藏」且服务器 False → 「我的收藏」页列出条目、点行进详情页（非死链接）→
  取消后该页不再列出且显示空态 → 详情页播放路径未被收藏改动破坏（600 s）。
- **协议事实（真机实测，两服一致）**：写端点只有 `POST/DELETE /Users/{uid}/FavoriteItems/{id}`；
  `/Users/{uid}/Favorites/{id}` 在 Emby 4.10.2 与 Jellyfin 12.2 上**都是 404**（Emby openapi.json 433 条 path 里
  没有任何 `/Favorites` 写路由）。写操作返回空 body，必须回读 `UserData.IsFavorite` 才能确认。
  `Filters=IsFavorite` 可用于服务端筛选。测试夹具因此**故意不提供** `/Favorites/{id}` 的 200 —— mock 不得比真服宽松。
- 未覆盖：`Favorite` 在 Android 侧尚未实现（Android 只读筛选，无写入），跨端收藏同步属 W6 范围。
  （2026-10-24 复核：工作树里有**未提交**的 `ProviderCapability.FAVORITE` 改动，未入库、未验证，故本条仍按已入库代码判定。）

## 6. U4 永久播放列表

- 脚本：`windows/tools/acceptance/u4/playlists-gui.ps1`（874 行，SHA256 `5FC1050FBBB7BE6511C52155BEA0B52C9443CD2B8DA56E5DC697185CBB0AF333`）
  —— 当前值：**875 行 / `A07A0B1180AF21257B4E71D5AF4EBD5E57F1B7DCDE859D1BA90DBAEC0DBF15B9`**
- 证据：`windows/tools/acceptance/evidence/u4-playlists/{emby,jellyfin}/`
- 断言要点：详情页「加入播放列表」→ 命名对话框 → `%LOCALAPPDATA%\MediaHub\playlists.json` 落地并满足契约
  （`version:1`、camelCase、`runTimeTicks`）→ 重复加入提示「已在该列表中」且 JSON 不重复 → 播放列表页/详情页可见 →
  点条目播放只发**单条** `PlaybackRequest`（trace 无 `queue:intake`，JSON canonicalEqual=True，播放不改写永久列表）→
  移除条目 → 条目空态 → 删除列表 → 列表空态 → 文件清理。
- **语义红线**：永久播放列表 ≠ `PlaybackQueue`（后者是本次会话的临时队列，不落盘、不自动摄取）。
  存储层 `MediaHub.Storage` 与 Domain 完全解耦，UI 侧点击只发单条播放请求。
- 未覆盖：跨设备同步播放列表（W6）；列表内拖拽排序（`MoveItem` 有单测覆盖，GUI 未验）；超长列表虚拟化性能。

## 7. 自动化门禁

```
dotnet test windows/MediaHub.Windows.sln -c Debug --nologo
MediaHub.Tests         373 passed / 0 failed / 0 skipped
MediaHub.WebDav.Tests  104 passed / 0 failed / 0 skipped
```

新增覆盖（W9）：`ContinueWatchingTests`（11 例：wire 必须带 `Recursive/Filters=IsResumable/SortBy=DatePlayed`、
过滤与排序、`limit<=0` 不发请求、mock 复刻 Emby 非递归语义、库浏览回归）、`FavoriteTests`（17 例）、
`EpisodeAutoAdvanceTests`（16 例：同季/跨季/末集/未知集号排最后/剧集身份映射）、`PlaylistStoreTests`（19 例）。
MockMediaServer 已按真服语义收紧：非递归空 `ParentId` 只回库文件夹、`Filters`/`SortBy` 在 mock 内生效、
收藏写端点只认 `FavoriteItems`。

## 8. 仍未完成 / 未验证（不得当作通过）

| 项 | 状态 | 说明 |
|---|---|---|
| 「继续观看」卡片海报 | EXECUTED_PASS（W10 已修） | 页面已调用 `Card.RequestPoster`（`Views/ContinueWatchingPage.xaml.cs:99`，同款在 `FavoritesPage.xaml.cs:86`、`LibraryPage.xaml.cs:121`；定义 `MediaCardViewModel.cs:73`）；U1 的 P2.6 断言在 `A8EDD448` 上两服各 41/41 验证 |
| U4 列表内拖拽排序 GUI | NOT_RUN | `MoveItem` 仅单测覆盖 |
| U2 Emby 目录级产物 | 部分被覆盖（结论仍可复核） | W9 的 35/35 产物被 W10 中断跑（2026-10-24 01:53，23 通过 / 12 失败，失败项全是「守卫拒绝开窗」）覆盖；同一目录的 W9 `run-console.log`（`RESULT: 35 passed, 0 failed`）+ `run-exit.txt`（`0`）仍在，以此为据。Jellyfin 侧 35/35 产物完整。见 [W12-DOC-AUDIT.md](W12-DOC-AUDIT.md) |
| 收藏/播放列表跨端同步（W6） | BLOCKED | PR #39 停在 01233ca（base=`feature/windows-client`，相对当前分支 21 behind、mergeable=UNKNOWN）；Android 侧无收藏写入、无播放列表模型 |
| Android 侧对应能力 | NOT_IMPLEMENTED | 收藏只读筛选；`autoPlayNextEpisode` 是死开关（9 处引用无消费方）；无播放列表/队列 |
| 引擎 LGPL 替换（libmpv 当前 GPL-3.0-or-later） | RELEASE_BLOCKED | LGPL 构建**已产出**（[W5-LGPL-BUILD.md](licensing/W5-LGPL-BUILD.md)：BUILT AND VERIFIED，3,327,274 B / `20035902…3C0A`），但随包发布的仍是 GPL 构建（`windows/native/libmpv/libmpv-2.dll` 120,989,696 B / `6A48FAEC…63D9`，见 [LGPL-COMPLIANCE.md](LGPL-COMPLIANCE.md) 与 `sbom-windows.cdx.json`）；切换与签名决策未做，故仍阻塞 |
| 代码签名 / MSIX / 干净机安装 | SIGNING_BLOCKED | 与 W7/W8 同结论，未动 |
| HDR / 多 GPU / 音频独占 / 设备兼容矩阵 | NOT_RUN / DEVICE_UNVERIFIED | 单机单卡环境，未执行 |
| PR #33 转正式（取消 Draft） | BLOCKED（需维护者决定） | 分支 CI 全绿，Draft 状态只能由人解除；本项目不自称 RELEASE READY |

## 9. 复现命令

```powershell
# 1) 服务器
pwsh -File windows\tools\setup-test-server.ps1 -Server jellyfin          # :8096
Start-Process D:\mh-servers\emby-install\app\system\EmbyServer.exe -ArgumentList '-programdata','D:\mh-servers\data\emby','-nointerface'   # :8097

# 2) 构建被测二进制（先杀残留 MediaHub 进程）
dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out/ -t:Rebuild -nologo

# 3) 验收（每个 run 独占 GUI，跑前建好证据目录）
New-Item -ItemType Directory -Force windows\tools\acceptance\evidence\u1-continue | Out-Null
pwsh -File windows\tools\acceptance\u1\continue-watching-gui.ps1 -Server emby
pwsh -File windows\tools\acceptance\u2\autoplay-gui.ps1        -Server emby
pwsh -File windows\tools\acceptance\u3\favorite-gui.ps1         -Server emby
pwsh -File windows\tools\acceptance\u4\playlists-gui.ps1        -Server emby
# jellyfin 同参数再跑一遍

# 4) 门禁
dotnet test windows/MediaHub.Windows.sln -c Debug --nologo
```

运行纪律（W9 实测踩过）：脚本是 UTF-8 无 BOM，必须用 **pwsh 7**；Windows PowerShell 5.1 会按 ANSI 解码中文而报语法错。
从工具进程树里直接起脚本会因窗口发现失败而假红，验收要 `Start-Process pwsh -File ... -RedirectStandardOutput` 脱离。
GUI 验收期间禁止任何 build/test —— 会改 dll 使证据作废、也会锁住原生依赖。

## 10. 提交

```
c832fe8 fix(windows): 继续观看改用递归 + IsResumable 查询（Emby 非递归只回库文件夹）
883e9e9 fix(windows): 首页服务器历史卡片可点击 + 继续观看/首页容器补 AutomationId
adab0e7 feat(windows): U4 永久播放列表接入 UI（播放列表页/列表详情页/详情页加入按钮）
529ba93 feat(windows): U4 永久播放列表存储层（与临时队列解耦）
b154475 feat(windows): 新增「我的收藏」页面并扩展收藏验收脚本
11b37a3 fix(windows): 自动连播沿用剧集身份，未知集号不再顶替真实集号
af4aa4e test(windows): U2 跨季自动连播真实 GUI 验收脚本
```
