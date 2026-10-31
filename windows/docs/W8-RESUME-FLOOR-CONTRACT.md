# W8-I 续播下限契约（离屏回归，无需真服）

> 状态：mock 已复刻 Emby 与 Jellyfin 对「上报播放位置」的处理规则，**31 个离屏用例**把它钉住。
> 本文件描述的是 **mock 的保真度契约**，不替代真服证据：真服上的原始实测仍以
> `windows/docs/W8-HEADLESS-RESUME.md` 与 `windows/docs/W8-EVIDENCE.md` 为准。
> 未改动任何产品代码（本轮 `windows/src/**` 零改动）。

## 0. 机器可读状态（CI 核对）

下面这段由 `windows/tools/check-licence-surface.ps1` 的 `resume-floor-claims` 检查逐行核对：它把这段里的阈值与用例数、`MockMediaServer.cs` 里的常量、以及 `ResumeFloorContractTests.cs` 实际展开出的用例数放在一起比。放宽下限、删掉判定分支、加减用例而不动这段，CI 就会红。§1 起是人读版，不是第二个真源。

<!-- BEGIN resume-floor-status (checked by check-licence-surface.ps1) -->
```text
emby-min-percent: 2
jellyfin-min-percent: 5
minimum-runtime-seconds: 300
maximum-percent-default: 90
trailing-window-ticks: 10000000
floor-comparison: percent-of-runtime
threshold-source: measured-constants-not-server-config
contract-case-count: 31
```
<!-- END resume-floor-status -->

## 1. 目的与边界

W8-H 用两台真服（Emby 4.10.2 :8097、Jellyfin 12.2 :8096）证明了三件事：位置能原样往返、
**低于服务器下限的上报会把已存位置清零并移出续播列表**、看完会清除并置 `Played=true`。
那套证据需要真服、真媒体、几十秒启动时间，进不了每次 CI。本轮把同一套判定函数写进
`windows/tests/MediaHub.TestSupport/MockMediaServer.cs`，让 CI 在没有真服时也能挡住
「有人把下限逻辑删掉/放宽」这类回归。

边界（刻意不覆盖，见 §6）：
- 不证明窗口内的「继续观看」提示呈现，也不证明播放引擎的 seek 精度 —— 那两条仍是 GUI 验收脚本的职责。
- 不下结论「真服永远是这个行为」：阈值一旦升级，断言会**变红**，这正是设计意图（§4.3）。
- 不证明并发、多用户、HTTPS、自定义端口下的行为。

## 2. 实测结果

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
已通过! - 失败:     0，通过:   469，已跳过:     0，总计:   469，持续时间: 4 s - MediaHub.Tests.dll (net10.0)
```

| 项目 | 数字 |
| --- | --- |
| 改动前该工程用例数（本轮在主检出实跑） | 438 |
| 改动后 | 469（该轮单工程实跑；当前权威计数以 CI 打印为准） |
| 本轮新增 | **31**（17 个测试方法：3 个 `[Fact]` + 14 个 `[Theory]`，共 28 条 `[InlineData]`） |
| 失败 | 0 |

| 测试类 | 方法数 | 展开用例 | 文件行 |
| --- | --- | --- | --- |
| `ResumeFloorMockFidelityTests`（mock 行为 = 真服行为） | 13 | 23 | `windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs:32` |
| `ResumeFloorClientViewTests`（产品 provider 视角） | 4 | 8 | `windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs:348` |

> 口径更正：任务书写「基线 1194 通过」，本轮在主检出实测的是 **438**（`windows/tests/MediaHub.Tests` 单工程）。
> 1194 应是包含 `MediaHub.WebDav.Tests` 或其它分支的口径；本文件所有数字都按**单工程实跑**计。

## 3. mock ↔ 真服 逐条对应表

每一行都是「真服上测到的事实 → mock 里对应的实现 → 钉住它的用例」。原则：**mock 不得比真服宽松** ——
凡是真服会丢掉的位置，mock 也必须丢掉。

| # | 语义（真服实测） | 真服证据 | mock 落点 | 用例 |
| --- | --- | --- | --- | --- |
| 1 | 位置 ≥ 下限时原值保存（Emby 2%、Jellyfin 5%） | `W8-HEADLESS-RESUME.md:50`、`:51` | `MockMediaServer.cs:1091-1114` 百分比分支 | `A_position_at_the_floor_is_stored_exactly` |
| 2 | 低于下限 → 位置清零、**不**置 Played、移出续播列表 | `W8-HEADLESS-RESUME.md:65-67` | `MockMediaServer.cs:1094-1099` 只清 position | `A_position_below_the_floor_clears_the_position_without_marking_it_watched` |
| 3 | Emby 边界是 1.99% 丢 / 2.00% 留（百分比而非绝对秒数） | `W8-HEADLESS-RESUME.md:90-101`（11.94 s vs 12.00 s） | `MockResumeFloor.Emby = new(2, 300)`（`MockMediaServer.cs:96`） | `Emby_discards_one_point_nine_nine_percent_and_keeps_exactly_two` |
| 4 | 2% 在两台服上结果相反（Emby 留 / Jellyfin 清） | `W8-HEADLESS-RESUME.md:51`、`:111` | `ForServerType`（`MockMediaServer.cs:102-106`） | `Two_percent_is_kept_by_Emby_and_dropped_by_Jellyfin` |
| 5 | 低于下限的上报**覆盖**已存位置 = 清零（不保留较大值） | `W8-HEADLESS-RESUME.md:105` | `MockMediaServer.cs:1088-1114` 每次上报覆盖 `_reported` | `A_below_floor_report_erases_a_position_the_server_already_held` |
| 6 | 最终位置 = 片长 → 位置 0、`Played=true`、移出列表 | `W8-EVIDENCE.md:42-47` | `MockMediaServer.cs:1100-1105`（>90% 或距片尾 <10 s） | `Finishing_a_title_clears_the_position_and_marks_it_watched` |
| 7 | 距片尾 < 10 s（1e7 ticks）也算看完 | `W8-EVIDENCE.md:43` | `MockMediaServer.cs:166` `TrailingWindowTicks`、`:1100-1101` | `A_position_within_ten_seconds_of_the_end_counts_as_finished` |
| 8 | 停止时**不带** PositionTicks → 视作播完（Played=true、位置 0） | `W8-EVIDENCE.md:61` | `MockMediaServer.cs:1082-1086` | `A_stop_without_a_position_is_read_as_watched_to_the_end` |
| 9 | 片长 < MinResumeDurationSeconds(300 s) → 清零且 Played=true | `W8-EVIDENCE.md:49`、`:67`、`:78-84` | `MockMediaServer.cs:1106-1111` | `A_title_shorter_than_the_minimum_duration_never_keeps_a_resume_point` |
| 10 | 规则只作用于**上报**，不改服务器既有状态（夹具 seed） | 读了就回去：读到的永远是服务器存的 | `MockMediaServer.cs:802` 先查 `_reported` 再落回 seed；`:326` `UserDataOf` | `The_floor_applies_to_reports_only_not_to_the_state_a_test_seeds` |
| 11 | 夹具复位：`DELETE /Users/{uid}/PlayedItems/{id}` → 位置 0、Played=false | W8-H 驱动脚本用它复位真服（`headless-resume.ps1` 的 `Reset-Fixture`） | `MockMediaServer.cs:1125-1135`、路由 `:538` | `The_played_items_reset_route_puts_a_title_back_to_never_played` |
| 12 | 开始播放（Start）不改位置 —— 否则开播会擦掉正要用的续播点 | 反向推理：真服从不在开播时改位置，否则续播永远无法续 | `MockMediaServer.cs:1029-1062`（`starting` 直接 204） | `Starting_playback_does_not_erase_the_resume_point_it_is_about_to_use` |
| 13 | 客户端只呈现服务器认可的位置（两层过滤） | `MediaBrowserProvider.cs:409` 服务端过滤 + `:414-418` 回读 | 真实 provider 走 mock 全链路（无桩） | `ResumeFloorClientViewTests` 的 4 个方法 |

## 4. 阈值常量的来源

### 4.1 Emby 的 2%（行为二分，不是文档）

`MockResumeFloor.Emby = new(MinimumPercent: 2, MinimumRuntimeSeconds: 300)`，依据是本轮 W8-H 在
600 s 真夹具（item 34 "Long Resume"）上的二分（`W8-HEADLESS-RESUME.md:90-101`，每次先
`DELETE /Users/{uid}/PlayedItems/34` 复位再 Start→Progress→Stopped）：

| 上报位置 | 占片长 | 服务端回读 |
| --- | --- | --- |
| 11.40 s / 114 000 000 ticks | 1.90% | 0 |
| 11.94 s / 119 400 000 ticks | 1.99% | 0 |
| **12.00 s / 120 000 000 ticks** | **2.00%** | **120 000 000** |
| 12.06 s / 120 600 000 ticks | 2.01% | 120 600 000 |

边界正好落在 2.00%，且 1.99% 与 2.00% 只差 0.06 s 却结果相反 ⇒ 判据是**百分比**（与片长相关），
所以 mock 里用 `position * 100 / RunTimeTicks` 比较，而不是绝对秒数。

### 4.2 Jellyfin 的 5% + 300 s + 90%

`MockResumeFloor.Jellyfin = new(5, 300)`，`MaximumPercent` 默认 90（两台服同值）。
依据：`W8-EVIDENCE.md:26`（实例 `GET /System/Configuration` 实测
`MinResumeDurationSeconds=300`、`MinResumePct=5`、`MaxResumePct=90`）与
`W8-EVIDENCE.md:38-53` 反编译的 `UserDataManager.UpdatePlayState`（两台服共用同一判定函数）。

### 4.3 为什么把阈值写成常量而不是读服务端配置

Emby 4.10.2 的 `GET /Users/{uid}` 对非管理员用户**不暴露** `MinResumePct` /
`MinResumeDurationSeconds`（policy 键里没有 resume 相关项，见 `W8-HEADLESS-RESUME.md:107`）。
所以 mock 只能把实测值写成常量。代价是：**服务端升级阈值时断言会变红**，而不是静默跟随 ——
这是有意的，因为「静默跟随」意味着一个把下限删成 0% 的实现也能绿着通过。

## 5. 客户端视角（第二条不变量）

服务端规则只是一半。`MediaBrowserProvider` 在读到答案后还会再过滤一次：
`GetContinueWatchingAsync`（`windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:402-412`）
只保留 `UserDataPlaybackPositionTicks is > 0 && UserDataPlayed != true`（`:409`），
`GetResumePositionAsync`（`:414-418`）读 `UserData.PlaybackPositionTicks`。

`ResumeFloorClientViewTests` 用**真实 provider 栈**（工厂 → 认证 → `ResolvePlaybackAsync` 拿
`SessionId` → `ReportPlaybackStartedAsync` → `ReportProgressAsync`/`ReportFinalProgressAsync`），
再用**新开的 handle 重新登录**读回（等价于重启应用），断言：

| 场景 | 期望 |
| --- | --- |
| 停在 40% | `GetResumePositionAsync` 与 `GetItemDetailAsync` 的位置都等于上报值，`Played=false`，续播列表恰 1 条且 ticks 相同 |
| 停在 1%（低于两服下限） | 两侧位置 0、`Played=false`、续播列表为空 |
| 停在片长（看完） | 位置 0、`Played=true`、续播列表为空 |
| 从未上报（对照） | 位置 0、`Played=false`、续播列表为空 —— 防止「永远返回空」也能通过的假绿 |

注意 `ReportAsync` 是 best-effort 的（`MediaBrowserProvider.cs:389-399` 吞掉
AuthExpired/Http/Network/Connection/Parse），所以**上报失败只能靠回读发现**。这几条用例正是靠回读断言，
不是靠上报不抛异常。

## 6. 未覆盖（NOT_RUN）

1. **并发与多设备**：同一 item 被两个会话同时上报（真服的「最后写入者赢」顺序）只靠单线程顺序覆盖。
2. **多用户**：mock 固定 `user-1`；真服上不同用户的续播互不可见，未测。
3. **HTTPS 与自定义端口**：mock 只起 loopback http 随机端口；`BlockHttpsDowngrade` 对续播路由的影响未测。
4. **AudioBook / Book 例外**：`UpdatePlayState` 对 AudioBook/Book 跳过百分比判定（`W8-EVIDENCE.md:32-41`），
   mock 只建 `Type=Movie`，该分支未覆盖。
5. **自定义 `MaxResumePct`**：mock 支持 `MockResumeFloor` 覆盖，但没有用例把 90 改成别的值。
6. **真服回归**：本轮**没有**再跑一次真服（Emby/Jellyfin 未启动）。真服证据仍是 W8-H 那一轮，任何真服行为变化
   只能靠 `windows/tools/acceptance/w8/headless-resume.ps1` 手动跑出来。
7. **App 本地历史**：`PlaybackHistoryStore` / 首页「继续观看」行的本地快照不被测试工程引用（App 依赖 WinUI），
   只能靠 GUI 验收。
8. **片长恰为 300 s 的边界**：只测了 240 s（低于）与 600 s（高于），未测恰好 300 s 的 `<` 语义。

## 7. 复现

```
# 全工程（本轮实测 469 通过 / 0 失败（该轮实跑；当前计数以 CI 打印为准））
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo

# 只跑本契约（31 用例）
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter "FullyQualifiedName~ResumeFloor"
```

改动文件（均在 `windows/tests/**`）：
- `windows/tests/MediaHub.TestSupport/MockMediaServer.cs` —— 新增续播状态机（`MockResumeFloor`、`_reported`、
  `ServePlaybackReport`/`ApplyReport`、`ServePlayedItemsReset`、`UserDataOf`、`RunTimeTicks` 覆盖项）。
- `windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs` —— 新增，17 个测试方法 / 31 个用例。
