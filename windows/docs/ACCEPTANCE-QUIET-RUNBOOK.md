# 验收运行的「不打扰」契约（Quiet Acceptance Runbook）

GUI 验收必须占用真实交互桌面：UIA 看不到别的桌面上的窗口，锁屏后没有窗口可找。因此
「不打扰使用者」不可能靠藏窗口实现，只能靠**契约**：不在人在场时启动、人一回来就停、
跑完不留任何东西。

自 2026-10-24 起，本仓库所有 GUI 验收**只能**通过 `run-quiet.ps1` 启动，不再允许直接调用
`u1/…`、`u2/…`、`u3/…`、`u4/…` 脚本（那会在任意时刻弹窗抢焦点，实测已干扰正常使用）。

## 0. 机器可读状态（CI 核对）

下面这段由 `windows/tools/check-licence-surface.ps1` 的 `quiet-run-claims`（W48，第 42 项）逐字核对：它同时要求 `run-quiet.ps1` 的默认值、`MhQuiet.psm1` 的 QUNS 白名单与轮询间隔、`MhUia.psm1` 的 `Start-MhApp` 守卫都仍与本文一致。有人在脚本里把门禁放宽、或把本文的承诺改小，CI 都会红。

<!-- BEGIN quiet-run-status (checked by check-licence-surface.ps1) -->
```text
default-min-idle-seconds: 180
wait-for-idle-minutes-default: 0
idle-source: GetLastInputInfo
quns-allowed-states: 5,6
watchdog-poll-milliseconds: 700
abort-marker: watch-*.abort
suite-reentry-env: MH_ALLOW_ACTIVE
abort-flag-env: MH_QUIET_ABORT_FILE
gate-precedence: abort-marker-then-allow-active-then-idle-and-quns
```
<!-- END quiet-run-status -->

本文其余 起的各节是人读版说明，不是第二个真源。

## 一条命令

```powershell
# 只在电脑空闲 3 分钟后跑；你回来它就停。（推荐日常用法）
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites all -WaitForIdleMinutes 30

# 现在就跑，但如果你正在用电脑则直接放弃（不弹任何窗口）
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites all

# 单个门 / 单个服务器
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u1 -Servers emby
```

参数：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `-Suites` | all | u1/u2/u3/u4，可逗号组合 |
| `-Servers` | emby,jellyfin | 串行执行，两个服务器不会同时开 |
| `-MinIdleSeconds` | 180 | 判定「人不在」的连续无输入秒数 |
| `-WaitForIdleMinutes` | 0 | 等多久出现空闲窗口；0 = 现在不空闲就放弃 |
| `-Exe` | D:\mh-out\…\MediaHub.exe | 被测产物 |
| `-OutRoot` | D:\mh-verify\quiet | 日志/汇总目录 |
| `-KeepServers` | 关 | 跑完保留两个测试媒体服务器（默认关掉） |

## 它对使用者承诺什么

1. **不抢屏**：启动前要求键盘/鼠标/触摸连续无输入 180 秒（`GetLastInputInfo`，屏保同源），**并且**外壳没有报告
   全屏/忙碌状态（`SHQueryUserNotificationState` ∈ {5 可通知, 6 安静时间}；2 忙碌 / 3 D3D 全屏 / 4 演示 / 7 应用窗口一律
   不启动）。第二条是必要的：全屏看视频、打游戏、开演示都不产生键鼠输入，光看 idle 会误判成「人不在」。
   不等空闲就不启动，退出码 0，一个窗口都不开。
2. **人回来就停**：运行期后台 job 每 700 ms 轮询输入；一旦检测到输入，立刻杀掉 MediaHub 并写
   `watch-*.abort`（记录 idle 值与时间），本次运行标记 `ABORTED-BY-USER`。最多闪一下窗口，
   不会与使用者抢焦点、不会继续截屏。
3. **不留残骸**：每个套件之间、以及 finally 块里都会杀 MediaHub；测试服务器按需启动、默认跑完关闭；
   电源/磁盘上不会留下常驻进程或开机项。
4. **一次只做一件事**：服务器串行（Emby 8097 / Jellyfin 8096 不同时开），套件串行，避免长时间占 CPU。

## 它不承诺什么

- **锁屏 / 远程断开时无法运行**（UIA 需要未锁定的交互会话）。所以没有做成计划任务——凌晨锁屏触发只会全红。
- 证据里的截图仍是在**你不在场时**抓的；如果你在跑动期间回来，那一跑的截图可能包含半途状态，
  该跑会被标成 `ABORTED-BY-USER` 而不是 PASS/FAIL。
- 脚本里 `Set-MhForeground` 仍会调用（截图与 UIA 点击需要窗口在屏幕上），它只在空闲窗口期内发生。

## 证据与日志

- `<OutRoot>summary-<时间戳>.txt`：本次总账（每跑一行 `exit=` / `RESULT:` / `elapsed` / 失败行），
  末行 `RESULT: OK` / `RESULT: FAILED …` / `RESULT: ABORTED-BY-USER …`。
- `<OutRoot>` 下的 `run-<suite>-<server>-<时间戳>.log`：该跑的完整断言流水（与单独跑脚本时同格式）。
- `<OutRoot>` 下的 `transcript-<时间戳>.log`：runner 自身 transcript（服务器启动结果、每次 exit code），runner 中途被杀也留得下。
- 各套件自己的证据目录不变：`windows/tools/acceptance/evidence/<suite>/<server>/`。
- 退出码：0 = 跑过或因人不在而跳过；1 = 有失败或被打断。

## 自检（不需要人离开也能验的部分）

```powershell
# 门禁必拒：阈值设成一天，人在场时一定跳过，且不产生任何进程
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u3 -MinIdleSeconds 86400
# 期望：SKIPPED-USER-ACTIVE ... exit=0；Get-Process MediaHub/EmbyServer/jellyfin 均为 0
```

看门狗也可单独验（dry-run 不杀进程）：

```powershell
Import-Module windows/tools/acceptance/lib/MhQuiet.psm1
$w = Start-MhQuietWatch -MinIdleSeconds 86400 -DryRun
Start-Sleep 3; Test-MhQuietAborted $w; Get-MhQuietAbortReason $w
Stop-MhQuietWatch $w
```

## 实战踩坑（2026-10-24 首次无人值守跑暴露并已修）

1. **看门狗没真的杀进程**：`Start-Job -ArgumentList …, [bool]$DryRun, …` 里的 `[bool]$DryRun` 在参数模式被当成字面量
   拼成字符串 `[bool]False`，job 内 `[bool]` 转换恒为 `$true`，于是 `if (-not $DryRun)` 永不成立——abort 标记写着
   `dryRun=True`，进程却一个没杀。改为传 `$DryRun.IsPresent`，job 内再用 `-match 'true$'` 按文本判定。
   对照自检（dummy 进程）：真模式 4 s 内清空且 abort 记 `dryRun=False`；dry-run 模式 dummy 存活。
2. **Emby 用错开关**：`EmbyServer.exe -datadir …` 被 Emby 忽略，它回落到 `app\programdata`、按默认端口绑 **8096**，
   还会 `LaunchWebBrowser` 拉起浏览器（既没监听 8097，又真弹了窗口）。必须用 `-programdata D:\mh-servers\data\emby`；
   实测 8097 起、`ServerId` 仍是 `1271d0a1040046db8c0089cc328bbbf1`。
3. **只有 idle 门禁不够**：全屏视频/游戏/演示不产生输入，idle 会一路涨到阈值。已加 `SHQueryUserNotificationState`
   门禁（只允许 5/6），runner 的 gate 与 `Start-MhApp` 的守卫都用它；实测本机返回 5。
4. **Emby 自更新会吃掉第一次启动**：安装目录存在 `embyserver-win-x64-*.7z` 时进程复制 updater 后退出。
   `Start-QuietServer` 现在记录 launch pid、检测提前退出并在函数内重试一次，端口等待 150 s，等待期间也可中止。

## 拿不到长窗口时：可续跑驱动器（2026-10-24 加）

这台机器的使用者在场时间长且不可预测：四次整跑（quiet-w10b/d/e/f）全部在门禁打开后 1–3 分钟被输入打断，
一次完整的 40 分钟窗口都没拿到。与其反复重跑，不如把验收拆成**可续跑的组合**：

```powershell
pwsh -NoProfile -File windows/tools/acceptance/run-quiet-resume.ps1 `
  -MinIdleSeconds 900 -WaitForIdleMinutes 420 -OutRoot D:\mh-verify\quiet-w10r
```

- 组合 = 6 个「套件 × 服务器」（`u2/u3/u4` × `emby/jellyfin`），**每个组合单独过一次门禁、单独起停服务器**。
- 检查点 `checkpoint.json` 记 `done`/`failed`；`RESULT: OK` 记完成并继续下一个组合，
  `ABORTED-BY-USER` 立即停（下个窗口从断点继续），`FAILED` 记下后继续其余组合。
- 每个组合开跑前还有一道 **build 清场**：`dotnet`/`MSBuild`/`VBCSCompiler`/`MediaHub` 任一在跑就先等最多
  `-BuildWaitMinutes`（默认 20）分钟；等不到就放弃本窗口（写 `BUSY-BUILD`）而不是污染时序断言。
- 默认门禁比单跑更严（`-MinIdleSeconds 900`）：只有人真的离开 15 分钟以上才开跑，避免又一次「刚满 5 分钟就被碰键盘」。
- 收尾总会杀 `MediaHub`/`EmbyServer`/`jellyfin`，并在 `<OutRoot>\resume-summary.txt` 写 `RESULT: COMPLETE` 或 `RESULT: INCOMPLETE stopped=…`。

进度判读只看两处：`resume-summary.txt`（每行一个组合的结果）与各组合目录下的 `summary-*.txt`（自己那一跑的原始结论）。

## 与历史证据的关系

- W9 四门证据（U1/U2/U3/U4 × Emby/Jellyfin，276 断言）是在直接运行的旧方式下取得的，仍然有效；
  二进制 `MediaHub.dll A15F6E4F…`。
- 之后修复了继续观看卡片封面（`ImageCache` 记录 `image:loaded`、`ContinueWatchingPage` 懒加载海报）并补了
  U1 的 P0.9/P2.6 断言，在当前二进制 `MediaHub.dll A8EDD448…` 上 U1 两服各 41/41 通过。
  这一跑是**直接运行套件脚本**取得的，证据在 `windows/tools/acceptance/evidence/u1-continue/{emby,jellyfin}/`
  （`checks.json` 各 41 条全 pass，`results.md` 头记录被测 dll `A8EDD448…` 与本脚本 `991D5BC7…`），不是 quiet runner 的输出；
  `D:\mh-verify\quiet-w10b\` 只有 W10 那次 `ABORTED-BY-USER` 的尝试（summary 里没有 U1/41/41），不要拿它当 U1 证据。

## 套件内重启与看门狗的分工（2026-10-24 修）

- 问题：`Start-MhApp` 的 idle 守卫会拒绝套件内部的重启（u2/u3 为拿干净基线会 stop + start）。
  一次真实的 W10 跑里 3 段各重启 4 次全被拒（`refusing to start MediaHub: the user is active (idle=2s…)`），
  于是 P1/P3 成片 `[FAIL]`，而产品其实一次窗口都没起来 —— 属测试设施误判，不是产品缺陷。
- 现在的分工：`run-quiet.ps1` 的门禁与看门狗才是这个时间窗的「别打扰」契约持有者，
  因此它给套件进程设置 `MH_ALLOW_ACTIVE=1`（允许套件内重启）并导出 `MH_QUIET_ABORT_FILE`（看门狗的 abort 标记）。
- `Start-MhApp` 先查 abort 标记：一旦使用者回来、看门狗落下 abort，任何重启都会直接抛错而不会把窗口再弹出来。
  守卫的优先级因此是：abort 标记 > `MH_ALLOW_ACTIVE` > idle/QUNS。
- 自检：把 `MH_QUIET_ABORT_FILE` 指向一份已存在的 abort 文件后调用 `Start-MhApp`，立即抛
  `refusing to start MediaHub: the quiet run was aborted by user input (…)`，`Get-Process MediaHub` 计数为 0。
