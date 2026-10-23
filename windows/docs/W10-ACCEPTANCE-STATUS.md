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

## 三次尝试总账（同一被测二进制 `A8EDD448`）

| # | 起跑 | 结果 | 关键事实 |
| --- | --- | --- | --- |
| 1 | 00:41（quiet-w10b） | ABORTED-BY-USER 00:44:55 | 门禁过、Emby 起、使用者回车即 abort；`Start-MhApp` 每次被 idle 守卫拒绝 → **零窗口** |
| 2 | 01:27（quiet-w10d，计划任务） | ABORTED-BY-USER 01:45:56 | 该跑暴露「套件内重启被守卫连拒 12 次」的自伤缺陷（u2 23 passed / 12 failed，全部是守卫拒绝，不是产品失败）→ 已由 `934e17f` 修：runner 给套件进程放行 `MH_ALLOW_ACTIVE=1`，但仍把 abort 标记传下去 |
| 3 | 02:29（quiet-w10e，计划任务） | ABORTED-BY-USER 02:35:27 | 门禁 02:35:19 通过 → Emby 起 → 02:35:27 使用者输入即 abort；u2 的 P0 6/6 PASS 后 `P1.0` 因 abort 守卫拒绝开窗而 FAIL（`pid=164048` 无窗口）。**这一跑反证了 guard 链有效：用户在场时一个窗口都没开** |

三次都是「使用者就在机器前」导致的中断，不是产品失败，也不是门禁失效。机器被使用者连续使用期间不可能拿到 40 分钟连续空闲窗口，因此改为**长窗口布防**：计划任务 `MH-W10-Quiet` 挂着 `-WaitForIdleMinutes 480`，谁先满足 300 秒空闲 + QUNS 放行就自动开跑，使用者一碰键鼠立刻 abort。

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
# 只在真正空闲时执行；人一回来它会在 1 秒内杀掉被测应用并记 ABORTED-BY-USER
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u2,u3,u4 -Servers emby,jellyfin `
  -MinIdleSeconds 300 -WaitForIdleMinutes 480 -OutRoot D:\mh-verify\quiet-w10f
```

跑完把 `D:\mh-verify\quiet-w10f\summary-*.txt` 的每行 `exit=` / `RESULT:` 抄进 W10 证据，并逐套件核对
`windows/tools/acceptance/evidence/u{2,3,4}/<server>/` 的截图与 checks.json。

## 明确不声称的东西

- 不声称 U2/U3/U4 在 `A8EDD448` 上新二进制上通过。U3/U4 证据来自 `A15F6E4F`；U2 的 Emby 侧目录产物在 01:53 被 W10 中断跑覆盖（同目录原始 `run-console.log` 仍记 `RESULT: 35 passed, 0 failed`），Jellyfin 侧仍是 W9 原始产物。
- 不声称「已复跑」——本次是被人打断的尝试，不是结果。
- 不声称任何 HDR / 签名 / LGPL 相关能力（仍是 `RELEASE_BLOCKED` / `SIGNING_BLOCKED`）。
