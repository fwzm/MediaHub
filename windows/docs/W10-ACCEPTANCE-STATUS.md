# W10 复跑状态（继续观看海报修复后的六跑）

状态：**ABORTED-BY-USER，未获得证据**（不是 FAILED，也不是 PASS）。

## 为什么要复跑

W9 的四门证据（U1/U2/U3/U4 × Emby/Jellyfin，276 条断言）是在 `MediaHub.dll A15F6E4F…` 上取得的。此后代码有两次产品面改动：

1. 继续观看卡片海报懒加载（`ContinueWatchingPage.xaml(.cs)` 的 `ContainerContentChanging` → `RequestPoster`）与 `ImageCache` 成功加载后写
   `startup.log` 的 `image:loaded item=… type=… w=…`；
2. `MediaBrowserApi.GetResumableItemsAsync`（`Recursive=true` + `Filters=IsResumable`）——继续观看在 Emby 上的真缺陷修复。

当前验收二进制因此变成 `A8EDD448…`，U1 已在其上两服各 **41/41**（含 P0.9 夹具封面就绪、P2.6 海报真实加载）。
U2/U3/U4 需要在这同一份二进制上复跑，才是「同一被测物、同一批断言」。

## 本次尝试（2026-10-24）

| 项 | 值 |
| --- | --- |
| 二进制 | `MediaHub.dll` 539648 B / 2026-10-24 00:13:17 / SHA256 `A8EDD44842CA44C3A5E3D1A55C51BCEEBAF285F30FB78281340EFA0BDB610470` |
| 驱动器 | `windows/tools/acceptance/run-quiet.ps1`（空闲门禁 + 看门狗 + 串行起停服务器） |
| 参数 | `-Suites u2,u3,u4 -Servers emby,jellyfin -MinIdleSeconds 300 -WaitForIdleMinutes 240 -OutRoot D:\mh-verify\quiet-w10b` |
| 门禁通过 | 00:44:36（idle 300 s，QUNS 允许） |
| 中断 | 00:44:55 使用者回到机器（`watch-20261024-004100.abort`：`user-active idle=0 threshold=300 … dryRun=False`） |
| 结果 | `ABORTED-BY-USER`；**没有打开任何 MediaHub 窗口**（P1.0 记录每次 `Start-MhApp` 都被 idle 守卫拒绝：`refusing to start MediaHub: the user is active`） |
| 残留 | 只启动了不可见的 Emby 进程，已在 00:52 手工停止；8096/8097 无监听、无 MediaHub 进程 |

证据目录：`D:\mh-verify\quiet-w10b\`（summary、`watch-*.abort`、`run-u2-emby-*.log`、`ABORT-NOTE.txt`）。

## 六次尝试总账（同一被测二进制 `A8EDD448`）

| # | 起跑 | 结果 | 关键事实 |
| --- | --- | --- | --- |
| 1 | 00:41（quiet-w10b） | ABORTED-BY-USER 00:44:55 | 门禁过、Emby 起、使用者回车即 abort；`Start-MhApp` 每次被 idle 守卫拒绝 → **零窗口** |
| 2 | 01:27（quiet-w10d，计划任务） | ABORTED-BY-USER 01:45:56 | 该跑暴露「套件内重启被守卫连拒 12 次」的自伤缺陷（u2 23 passed / 12 failed，全部是守卫拒绝，不是产品失败）→ 已由 `934e17f` 修：runner 给套件进程放行 `MH_ALLOW_ACTIVE=1`，但仍把 abort 标记传下去 |
| 3 | 02:29（quiet-w10e，计划任务） | ABORTED-BY-USER 02:35:27 | 门禁 02:35:19 通过 → Emby 起 → 02:35:27 使用者输入即 abort；u2 的 P0 6/6 PASS 后 `P1.0` 因 abort 守卫拒绝开窗而 FAIL（`pid=164048` 无窗口）。**这一跑反证了 guard 链有效：用户在场时一个窗口都没开** |
| 4 | 02:37（quiet-w10f，计划任务） | ABORTED-BY-USER 02:42:57 | idle 曲线 104→172→293→300→0：门禁刚满 300 s 放行、watchdog 与 Emby 刚起来，使用者就碰了键鼠；`watch-20261024-023718.abort` 记 `dryRun=False`。同样**零 MediaHub 窗口** |
| 5 | 03:02（quiet-w10g，计划任务，**首次用可续跑驱动器**） | ABORTED-BY-USER 03:22:58 | 阈值抬到 1200 s；03:22:36 门禁放行（idle 从 3 s 一路涨到 1200 s，说明连续空闲 20 分钟确实拿得到）→ Emby 起（pid 207988）→ u2 套件进程 03:22:45 起 → **22 秒后**使用者输入即 abort（`watch-20261024-030230.abort` 记 `idle=0 threshold=1200 dryRun=False`），`Start-MhApp` 从未被调用 → **零 MediaHub 窗口**、检查点仍为空 |
| 6 | 03:42（quiet-w10g，计划任务，可续跑驱动器） | ABORTED-BY-USER 04:02:58 | 阈值 1200 s；04:02:4x 门禁放行（idle 一路涨到 1200 s）→ Emby 起（pid 202764）→ u2 套件 04:02:47 起 → **16 秒后**使用者输入即 abort（`watch-20261024-034222.abort` 记 `idle=0 threshold=1200 dryRun=False`）。`Start-MhApp` 从未被调用 → **零 MediaHub 窗口**、检查点仍为空。此跑之后**主动停用计划任务**：每次使用者离开就起一次不可见 Emby，本身也是打扰 |

六次都是「使用者就在机器前」导致的中断，不是产品失败，也不是门禁失效；六次合计**零窗口**（每次都在 `Start-MhApp` 之前就 abort）。第 5、6 跑另外证明：可续跑驱动器本身端到端可用（门禁 → 起服务器 → 起套件 → 被打断 → 收尾清理），只是它一格都还没跑完。

**注意一个反复出现的形状**：门禁阈值从 300 s 提到 1200 s，都没有改变结果 —— 使用者确实能连续空闲 20 分钟（idle 实测从 3 s 涨到 1200 s），但**门禁一放行、服务器一起、套件一起**，16–22 秒内就有人碰键鼠。六次全部如此，因此按「等下一个空闲窗口」自动重试的收益为零，代价是每次都在使用者桌面上起一个不可见的媒体服务器进程。

结论：这台机器拿不到约 40 分钟的连续空闲窗口，一次跑完六跑的方案不可行。因此改为**按组合可续跑**：

- `windows/tools/acceptance/run-quiet-resume.ps1` 把「套件 × 服务器」拆成 6 个组合（emby/jellyfin × u2/u3/u4），每个组合单独过门禁、单独起停服务器；
- 每跑完一个组合就把 `RESULT: OK` 记进 `-Checkpoint`（默认 `D:\mh-verify\w10-checkpoint.json`），被打断只损失当前组合，下个窗口从断点继续；
- 开跑前还有**构建清场守卫**：检测 `dotnet/MSBuild/VBCSCompiler/java/javaw` 的 1.5 s CPU 增量（>0.6 s 判忙），有构建在跑就等，避免 GUI 时序断言被抢 CPU 变假红；
- 门禁阈值提高到 **1200 s 空闲**：只有使用者真的离开才开跑，减少「刚满 5 分钟就被碰」的无效尝试。


## 结论：无人值守自动跑在这台机器上不可行（2026-10-24 04:05）

计划任务 `MH-W10-Quiet` 在 04:02:58 第 6 次被打断后**已主动停用**（`schtasks /End`，状态回到 Ready，未删除，需要时可 `/Run` 重启）。理由：继续自动重试只会让「使用者离开 → 起一份不可见 Emby → 使用者回来 → 杀进程」循环反复发生，这本身就是打扰，而六次里没有一次跑到 `Start-MhApp`。

因此 GUI 证据改由**人工授予窗口**：要么使用者自己挑一个不会被打断的时段跑下面这一条命令，要么在允许打扰的前提下用 `MH_ALLOW_ACTIVE=1` 直接跑（会抢焦点，必须明确同意）。在此之前，U2/U3/U4 在 `A8EDD448` 上仍是 **NOT_RUN**，W9 的 276 条断言仍然只对 `A15F6E4F` 有效。
## 这一跑暴露并已修的测试设施缺陷（不是产品缺陷）

1. **看门狗从未真的杀进程**：`Start-Job -ArgumentList …, [bool]$DryRun, …` 把 `[bool]` 当字面量拼成字符串 `[bool]False`，
   job 内 `[bool]` 恒为 `$true`，`if (-not $DryRun)` 永不成立。已改为传 `$DryRun.IsPresent` 并在 job 内按文本判定；
   用 dummy 进程做过对照：真模式 4 秒内清空且 abort 记 `dryRun=False`，dry-run 模式 dummy 存活。
2. **Emby 用错开关**：`EmbyServer.exe -datadir …` 被忽略，Emby 回落到 `app\programdata`、按默认端口绑 **8096**，并真的
   `LaunchWebBrowser` 拉起浏览器。改用 `-programdata D:\mh-servers\data\emby` 后 8097 正常，`ServerId` 仍是 `1271d0a1040046db8c0089cc328bbbf1`。
3. **只有 idle 门禁不够**：全屏视频/游戏/演示不产生键鼠输入，idle 会一路涨到阈值。已加 `SHQueryUserNotificationState` 门禁
   （只放行 5 可通知 / 6 安静时间），runner 的 gate 与 `Start-MhApp` 的守卫都用它。
4. **Emby 自更新会吃掉第一次启动**（安装目录有 `embyserver-win-x64-*.7z` 时进程复制 updater 后退出）：服务器启动现在带重试。

## 怎么把它跑完

```powershell
# 可续跑：每个组合单独过门禁；被打断只丢当前组合，下次从检查点继续
pwsh -File windows/tools/acceptance/run-quiet-resume.ps1 `
  -OutRoot D:\mh-verify\quiet-w10g -Checkpoint D:\mh-verify\w10-checkpoint.json `
  -MinIdleSeconds 1200 -WaitForIdleMinutes 420 -BuildWaitMinutes 30
```

无人值守时由计划任务 `MH-W10-Quiet` 承载（先 `schtasks /Change /TN MH-W10-Quiet /TR <wrapper>` 再 `/Run`），
wrapper 调上面的命令并把 stdout 重定向到 `D:\mh-verify\w10g-console.log`。该任务在 04:02:58 第 6 次被打断后已 `schtasks /End` 停用（Status=Ready，未删除）。

如果要人工跑一次：确认自己接下来 10–15 分钟不碰键鼠，直接在上面那条命令前加 `-Suites u2` 之类的单组合，跑完把 `summary-*.txt` 贴回来即可。

跑完把 `D:\mh-verify\quiet-w10g\<组合>\summary-*.txt` 的每行 `exit=` / `RESULT:` 抄进 W10 证据，并逐套件核对
`windows/tools/acceptance/evidence/u{2,3,4}/<server>/` 的截图与 checks.json。

## 明确不声称的东西

- 不声称 U2/U3/U4 在 `A8EDD448` 上新二进制上通过。U3/U4 证据来自 `A15F6E4F`；U2 的 Emby 侧目录产物在 01:53 被 W10 中断跑覆盖（同目录原始 `run-console.log` 仍记 `RESULT: 35 passed, 0 failed`），Jellyfin 侧仍是 W9 原始产物。
- 不声称「已复跑」——六次尝试都是被人打断的，不是结果（检查点 `D:\mh-verify\w10-checkpoint.json` 至今为空，说明零组合完成）。
- 不声称「没有打扰使用者」以外的任何运行副作用：每次尝试都在门禁打开后**极短时间内**被 abort，实际只起过不可见的测试服务器进程，且已在收尾时停止。
- 不声称任何 HDR / 签名 / LGPL 相关能力（仍是 `RELEASE_BLOCKED` / `SIGNING_BLOCKED`）。
