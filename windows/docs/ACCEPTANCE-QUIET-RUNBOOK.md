# 验收运行的「不打扰」契约（Quiet Acceptance Runbook）

GUI 验收必须占用真实交互桌面：UIA 看不到别的桌面上的窗口，锁屏后没有窗口可找。因此
「不打扰使用者」不可能靠藏窗口实现，只能靠**契约**：不在人在场时启动、人一回来就停、
跑完不留任何东西。

自 2026-10-24 起，本仓库所有 GUI 验收**只能**通过 `run-quiet.ps1` 启动，不再允许直接调用
`u1/…`、`u2/…`、`u3/…`、`u4/…` 脚本（那会在任意时刻弹窗抢焦点，实测已干扰正常使用）。

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

1. **不抢屏**：启动前要求键盘/鼠标/触摸连续无输入 180 秒（`GetLastInputInfo`，屏保同源）。
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
- `<OutRoot>un-<suite>-<server>-<时间戳>.log`：该跑的完整断言流水（与单独跑脚本时同格式）。
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

## 与历史证据的关系

- W9 四门证据（U1/U2/U3/U4 × Emby/Jellyfin，276 断言）是在直接运行的旧方式下取得的，仍然有效；
  二进制 `MediaHub.dll A15F6E4F…`。
- 之后修复了继续观看卡片封面（`ImageCache` 记录 `image:loaded`、`ContinueWatchingPage` 懒加载海报）并补了
  U1 的 P0.9/P2.6 断言，在当前二进制 `MediaHub.dll A8EDD448…` 上 U1 两服各 41/41 通过。
- 其余六跑（U2/U3/U4 × 两服）在**同一二进制**上的完整复跑，改为用本 runbook 的空闲窗口执行，
  结果写入 `D:mh-verifyquietsummary-*.txt` 并据此更新 W10 证据文档。
