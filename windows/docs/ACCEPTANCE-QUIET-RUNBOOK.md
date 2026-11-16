# 验收运行的「不打扰」契约（Quiet Acceptance Runbook）

GUI 验收必须占用真实交互桌面：UIA 看不到别的桌面上的窗口，锁屏后没有窗口可找。因此
「不打扰使用者」不可能靠藏窗口实现，只能靠**四层叠加**：

1. **空闲门禁**（`run-quiet.ps1`）：连续 30 分钟无输入才开跑；人一回来立刻暂停、等待、续跑。
2. **后台启动**（`lib/MhQuietUi.psm1`）：`WS_EX_NOACTIVATE` + `SW_SHOWNOACTIVATE`，窗口放到屏幕外，
   不激活前台。
3. **消息级操作**（同前）：Invoke / Select / Toggle / SetValue / RangeValue，不移动光标、不注入键鼠。
4. **后台抓图**（同前）：`PrintWindow(PW_RENDERFULLCONTENT)` 抓指定 HWND，含 mpv 视频子窗口；
   不用 `CopyFromScreen`，窗口在屏幕外也能抓。

只做门禁是不够的：`GetLastInputInfo` 只观测输入活动，看视频、看文档、远程操作都不产生输入，
「没有输入」不等于「没在用电脑」。所以门禁必须与 2、3、4 叠加，不能单独作为不打扰的依据。

自 2026-10-24 起，本仓库所有 GUI 验收**只能**通过 `run-quiet.ps1` 启动，不再允许直接调用
`u1/…`、`u2/…`、`u3/…`、`u4/…` 脚本（那会在任意时刻弹窗抢焦点，实测已干扰正常使用）。
自 2026-11-14 起，三项无干扰手段由 `quiet-ui/verify.ps1` 逐项实测；现有套件的迁移状态见第 6 节。

## 0. 机器可读状态（CI 核对）

下面这段由 `windows/tools/check-licence-surface.ps1` 的 `quiet-run-claims`（第 19 项）逐字核对：
它同时要求 `run-quiet.ps1` 的默认值、`MhQuiet.psm1` 的 QUNS 白名单、`MhQuietUi.psm1` 的轮询与
后台手段、`MhUia.psm1` 的 `Start-MhApp` 守卫都仍与本文一致。有人在脚本里把门禁放宽、把轮询调慢、
或把本文的承诺改小，CI 都会红。

<!-- BEGIN quiet-run-status (checked by check-licence-surface.ps1) -->
```text
default-min-idle-seconds: 1800
wait-for-idle-minutes-default: 0
idle-source: GetLastInputInfo
quns-allowed-states: 5,6
watchdog-poll-milliseconds: 1000
abort-marker: watch-*.abort
suite-reentry-env: MH_ALLOW_ACTIVE
abort-flag-env: MH_QUIET_ABORT_FILE
gate-precedence: abort-marker-then-allow-active-then-idle-and-quns
pause-log: 用户回来了，已暂停
resume-source: checkpoint.json
session-blockers: locked,disconnected,switched-user,sleep
```
<!-- END quiet-run-status -->

本文其余各节是人读版说明，不是第二个真源。

## 1. 一条命令

```powershell
# 等连续 30 分钟无输入才开跑；中途人回来就暂停，下一个 30 分钟窗口续跑。
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites all

# 单个门 / 单个服务器
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u1 -Servers emby

# 只等 10 分钟；等不到就干净退出（不启动任何进程）
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u3 -WaitForIdleMinutes 10
```

参数：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `-Suites` | all | u1/u2/u3/u4，可逗号组合 |
| `-Servers` | emby,jellyfin | 串行执行，两个服务器不会同时开 |
| `-MinIdleSeconds` | 1800 | 判定「人不在」的连续无输入秒数（30 分钟） |
| `-WaitForIdleMinutes` | 0 | 等多久出现空闲窗口；0 = 无限等待 |
| `-DevOverrideIdleGate` | 关 | 开发工装时跳过错开等待与输入中止；该跑不算验收证据，日志会标注 |
| `-Checkpoint` | `<OutRoot>\checkpoint.json` | 暂停/续跑用的完成记录 |
| `-Exe` | D:\mh-out\…\MediaHub.exe | 被测产物 |
| `-OutRoot` | D:\mh-verify\quiet | 日志/汇总目录 |
| `-KeepServers` | 关 | 跑完保留两个测试媒体服务器（默认关掉） |

## 2. 它对使用者承诺什么

1. **不抢屏**：启动前要求键盘/鼠标/触摸连续无输入 1800 秒（`GetLastInputInfo`，屏保同源），
   **并且**外壳没有报告全屏/忙碌状态（`SHQueryUserNotificationState` ∈ {5 可通知, 6 安静时间}；
   2 忙碌 / 3 D3D 全屏 / 4 演示 / 7 应用窗口一律不启动）。第二条是必要的：全屏看视频、打游戏、
   开演示都不产生键鼠输入，光看 idle 会误判成「人不在」。
   不等空闲就不启动，退出码 0，一个窗口都不开。
2. **人回来就停**：运行期后台 job 每 1000 ms 复查一次；一旦检测到输入，立刻：
   写 `watch-*.abort`（记录 idle 值与时间）→ 杀掉本次 run 的套件进程树与它启动的 MediaHub →
   driver 关闭被测应用、停掉本次启动的服务器 → 记一条原文
   `PAUSED 用户回来了，已暂停 …`，然后回到门禁。只杀本 run 追踪的 pid，不按名误杀并发会话。
3. **不可测状态一律等待**：锁屏（`OpenInputDesktop` 打不开输入桌面）、会话断开
   （`WTSQuerySessionInformationW` 的 `WTSConnectState` 不是 Active）、切换到其他登录用户、
   系统休眠/待机（`GetTickCount64` 跳变）都视为不可测试，门禁只等待、不启动任何东西。
4. **暂停可续跑**：完成的 `(server, suite)` 组合写入 `checkpoint.json`，暂停最多损失正在跑的那一个
   组合，已通过的不会重做。续跑仍要重新过一次完整的连续 30 分钟无输入窗口。
5. **不留残骸**：每个组合之间杀本次 run 启动的 MediaHub；测试服务器按需启动、默认跑完关闭；
   电源/磁盘上不会留下常驻进程或开机项。
6. **一次只做一件事**：服务器串行（Emby 8097 / Jellyfin 8096 不同时开），套件串行，避免长时间占 CPU。

## 3. 判定边界：它不承诺什么

- **没有输入 ≠ 没在用电脑**。看视频、看文档、远程桌面操作都不产生输入，门禁会误判为空闲。
  因此门禁只负责「用户没碰键鼠时跑」，真正的低干扰依赖第 2、3、4 层：后台启动、消息级操作、
  后台抓图。三者由 `quiet-ui/verify.ps1` 单独验证。
- **锁屏 / 断连 / 休眠 / 切换用户时无法运行**：UIA 需要未锁定的交互会话。门禁会等待这些状态结束，
  不会把它们当成空闲。所以没有做成计划任务——凌晨锁屏触发只会一直等。
- **现有 u1–u4 仍会短暂抢前台**：它们内部还有 `Set-MhForeground`（截图与个别断言），截图仍用
  `CopyFromScreen`；迁移清单见第 6 节。当前这些套件只能靠门禁保护，不能声称「完全不打扰」。
- **证据的真实性**：如果你在跑动期间回来，那一跑的截图可能包含半途状态；该跑会被暂停并丢弃当前组合，
  不会记成 PASS/FAIL。已完成组合的证据仍是当时抓到的真实画面。

## 4. 暂停、续跑与检查点

- 检查点粒度是 `(server, suite)` 组合。`checkpoint.json` 的 `completed` 只包含
  `exit=0` 且 `RESULT: 0 failed` 的组合；`failed` 记录失败组合，下次窗口会重试。
- 暂停时 driver 的收尾顺序：先写 abort 标记 → 杀套件树与本次启动的 MediaHub → 停本次启动的服务器 →
  写 `PAUSED 用户回来了，已暂停 pausedCount=… killed=… completed=x/y reason=…` → 停看门狗 → 回门禁。
  日志先于看门狗的进程树清扫写出，避免暂停记录被拖慢。
- 门禁日志每 30 秒一行，包含 `idle=…s need=1800s remaining~…s reason=…`；打开时记
  `gate: READY idle=…s waited=…s`；超时跳过时记
  `SKIPPED-USER-ACTIVE … nothing was started, no window was opened.`
- 退出码：0 = 跑完或因门禁超时跳过；1 = 有失败；2 = 未完成（正常暂停后会继续等待，不会走到这里）。

## 5. 三手段实测证据（`quiet-ui/verify.ps1`）

```powershell
# 开发机（有人在用）：显式声明这不是验收证据
pwsh -File windows/tools/acceptance/quiet-ui/verify.ps1 -DevOverrideIdleGate
# 验收：必须等真实的 30 分钟无输入窗口
pwsh -File windows/tools/acceptance/quiet-ui/verify.ps1
```

每次运行的结论在 `<OutRoot>\evidence.json`，逐步日志在 `verify-<时间戳>.log`。2026-11-14 的实测：

| 步骤 | 结论 | 证据 |
| --- | --- | --- |
| s1-launch | 实测可行 | 前台始终是 `Agent`（`foregroundPreserved=True`）；光标 `1123,628 → 1123,628`；窗口 `3200,200 1280x800`、visible、非 iconic、屏幕外；启动后 UIA 导航可用 |
| s2-uia | 实测可行 | 非激活窗口上 Toggle `Off→On`（`settings.json` 回读 True）、SetValue 回读 `D:\mh-quiet-test`、Select 画质 `SELECTED/VERIFIED`、RangeValue 55；`transientFocusSteals=0`；全程 `appForeground=False` |
| s3-capture | 实测可行 | PrintWindow 1280x800，`nonBlack=0.9709`、237 色、`printWindowReturned=True`；窗口在屏幕外 |
| s4-video | 实测可行 | 找到 `MediaHub.MpvVideoSurface` 子窗口，rect `3211,325 1258x521`，`appForeground=False` |
| s5-capture | 实测可行 | 视频子窗 1258x744：`nonBlack=0.9426`、1275 色、`meanLuma=120.9`、字幕后段 `subtitleBandNeutral=0.0078`（白色字幕）；主窗 `nonBlack=0.9257`、1146 色；播放位置 `0 → 0.0167`（0..1 分数）证明在播 |

补充事实：个别 WinUI 模式调用会瞬间把前台切到 `InputSiteWindowClass` 辅助窗口。`MhQuietUi` 的
focus keeper 每 50 ms 检查，一旦前台变成被测进程就用 `SetForegroundWindow` 还给基线窗口。
2026-11-14 的日志里 `nav.settings` 出现过一次 `focusStolen=True restored=True`，随后 s2 的两处
门槛快照 `transientFocusSteals=0`，每步结束后 `foreground` 仍是 `Agent`（`appForeground=False`）。
若某一步无法恢复，verify 会失败并记录，不会把“前台被留在应用上”算通过。

拒收标准：抓图必须是 `nonBlackRatio > 0.5`、`uniqueColors >= 100`、尺寸 >= 100 像素；视频步还要求
字幕带中性亮像素比例 > 0 且播放位置前进，才判「有视频像素与字幕，不是黑块」。

## 6. 现有验收脚本对照

结论分三类：**无干扰可执行**、**部分可行（需替换个别原语）**、**不可行，需要真实输入**。
「单测覆盖」用于本来就不该走 GUI 的判据。

| 现有脚本 / 检查 | 结论 | 说明与改造方式 |
| --- | --- | --- |
| u1 P0.1–P0.9（服务器登录、复位、配置、指纹、封面） | 无干扰可执行 | 全部走 HTTP / 文件，不碰窗口 |
| u1 P1.1–P1.9、P2.0–P2.6、P3.0–P3.5、P4.0–P4.4、P5.x | 部分可行 | 已是 `Invoke-MhElement` / `Invoke-MhElementDeep` 消息级；需把 `Set-MhForeground`（L626 等）改为静默启动，把 `Save-MhScreenshot`（`CopyFromScreen`）改为 `Save-MhQuietWindowCapture` |
| u1/u2/u3/u4 的 P1.0「窗口在前台」 | 不可行，按现语义 | 断言本身就是应用成为前台，无干扰下必然为假。改判「窗口可见 + UIA 可访问」，或把它留作显式前台车道 |
| u2 P1.1–P1.5、P2.0–P2.7、P3.0–P3.6、P4.1–P4.5（连播/切集/开关） | 部分可行 | seek 与 AutoPlayNext 已用 RangeValue / UI 开关；P4.0 在 UI 失败时会退回配置注入，可接受；长等待与 EOF 判定不需要输入 |
| u3 P0.x–P4.2（收藏、重启持久化、页面回读） | 部分可行 | 交互已是 Invoke；只需替换 `Set-MhForeground` 与截图原语 |
| u4 P0.x–P6.2（播放列表、菜单、命名对话框、删除确认） | 部分可行 | `MenuFlyout` 项与 `ContentDialog` 可用 Invoke + SetValue；P3.5 截图改 PrintWindow。若改用系统文件选择器则退化为「需要真实输入」 |
| 02-playback-controls.ps1、03-fullscreen.ps1、05-resource-release.ps1 | 不可行，需要真实输入 | 按坐标 `Click-MhPoint`、拖动时间轴、全屏/窗口边界与最小化还原；真实指针捕获无法用 UIA 模式替代 |
| emby/run.ps1、http/run.ps1、http/seek_range.ps1 中的 `SetCursorPos` / `keybd_event` | 不可行，需要真实输入 | 时间轴拖动与二次点击依赖真实鼠标消息序列；只能在门禁窗口内跑，或改产品加测试钩子（本任务不改产品） |
| gateB1-picker.ps1、gateB1-open-video.ps1 的 `SendKeys('{ENTER}')` | 不可行，需要真实输入 | 系统 OpenFileDialog 不在应用 UIA 树里，必须真实键盘 |
| 01-lifecycle.ps1 的启动/关闭/重启 | 部分可行 | 启动改静默启动；关闭可用 `WM_CLOSE` 消息；「前台」断言按上条处理 |
| 窗口存在、标题、控件树、设置回读、HTTP 回读 | 无干扰可执行 | UIA 与文件/HTTP 即可，`quiet-ui/verify.ps1` 已覆盖同类路径 |
| resume floor、MockMediaServer、settings/playlists 序列化、URL 解析、日志滚动 | 只能单测覆盖 | 不需要 GUI；已有 `MediaHub.Tests` / `MediaHub.WebDav.Tests` |

## 7. 自检（不需要人离开也能验的部分）

```powershell
# 门禁必拒：阈值设成一天，人在场时一定跳过，且不产生任何进程
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u3 -MinIdleSeconds 86400 -WaitForIdleMinutes 1
# 期望：SKIPPED-USER-ACTIVE ... exit=0；Get-Process MediaHub/EmbyServer/jellyfin 均为 0

# 门禁：模拟输入 → 必须等满一个新的安静窗口才打开
pwsh -File windows/tools/acceptance/quiet-ui/gate-selfcheck.ps1

# run-quiet 端到端：模拟输入 → 暂停 → 杀进程 → 续跑且不重做已完成项
pwsh -File windows/tools/acceptance/quiet-ui/run-quiet-selfcheck.ps1

# 三手段（后台启动 / 消息级操作 / 后台抓图 + 视频子窗）
pwsh -File windows/tools/acceptance/quiet-ui/verify.ps1 -DevOverrideIdleGate
```

2026-11-14 的自查结果：

- `gate-selfcheck.ps1`：`RESULT: OK`。注入到看门狗判定 `detect=126ms`，替身进程已被关闭；
  日志含 `PAUSED 用户回来了，已暂停`；续跑跳过 `step-1`，其余 3 步完成。
- `run-quiet-selfcheck.ps1`：`RESULT: OK`。注入后 1 s 内出现暂停日志，u3 进程被杀；u1 只跑 1 次；
  门禁出现 2 次 `READY`；`checkpoint.json` 含 `emby/u1` 与 `emby/u3`。
- 真实默认门禁（1800 s）：55 s idle 时拒绝启动，日志每 30 s 记
  `idle=…s need=1800s remaining~…s reason=user active`；1 分钟上限后
  `SKIPPED-USER-ACTIVE … nothing was started, no window was opened.`

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
5. **暂停日志被拖慢（2026-11-14）**：最初暂停路径先 `WaitForExit(5000)` 再停看门狗（看门狗内部还要 CIM 枚举），
   注入到日志约 7 s。改为 abort 轮询只 `Stop-Process` 根进程 + `WaitForExit(1000)`，root 已死则跳过 CIM，
   且先写暂停日志再停看门狗；实测注入到日志 0.79 s。
6. **并发会话被误杀（2026-11-14）**：run-quiet 早期按名杀 MediaHub，会杀掉另一会话正在跑的验收应用。
   现在只杀本次 run 基线之后新出现的 MediaHub，服务器也只停本次启动的 pid。

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
