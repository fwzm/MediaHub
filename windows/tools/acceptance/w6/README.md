# W6 设备同步页门禁（sync-page-gui.ps1）

针对分支 `feature/cross-device-source-sync` 的「设备同步」页（`windows/src/MediaHub.App/Views/SyncPage.xaml`）写的端到端 GUI 验收脚本。
它在真实 Emby / Jellyfin 服务器上跑真实客户端窗口，用 UIA 驱动页面，用文件字节比对判定「预览没有落盘」。

## 它验什么（42 条断言，P0→P5）

| 段 | 条数 | 判据 |
| --- | --- | --- |
| P0 准备 | 7 | 无残留进程；真服登录（`/Users/AuthenticateByName`）；`/System/Info` 的 ServerId 与版本；SeedSource 播种且 client 配置只剩被测来源；记录 `MediaHub.dll` SHA256；清历史信封；记录 AutoPlayNext |
| P1 页面身份 | 6 | 导航「设备同步」→ `sync.actions` / `sync.sources` 出现；三按钮齐备且都在 `sync.actions` 子树内；来源行 >= 1 且未落空态（全部只认 AutomationId） |
| P2 导出 | 8 | 状态栏「已导出同步信封」；信封落盘非空；`schemaVersion=1`；`sources[0].syncSourceId` 匹配 `^local-[0-9a-f]{16}$`；**逐键**扫描无 secret/password/token/apiKey；`canSendSecrets=false` 且无 `sources[*].secrets`；全文不含真实 token/密码；文案含免责说明 |
| P3 预览 | 6 | 造对端信封（无变化 / 新建 / 冲突三态）→ 状态栏「已生成合并预览」；明细行 >= 3 且含「无变化」「需用户确认」；**`sync-sources.json` 与 `sync-envelope.json` 的 SHA256 + 长度 + mtime 前后完全一致** |
| P4 逐源验证 | 5 | 行内 `sync.verify` 存在；验证前是「未验证」；点击后行标签离开「未验证」进入已判定三态（已验证/认证失败）；服务器可达时得「已验证」+「认证通过」；反向断言全树不出现把结果说成「已同步」的标签 |
| P5 收尾 | 6 | 无残留进程；dll hash 起止一致；AutoPlayNext 未被改；同步源与信封仍等于预览后基线；来源数仍为 1 |

## 前置

- Windows 客户端已构建并存在：默认 `D:mh-out-w6dDebug
et10.0-windows10.0.19041.0win-x64MediaHub.exe`（可用 `-Exe` 覆盖）。脚本**不会**构建任何东西。
- 服务器在跑：Emby `http://127.0.0.1:8097`（mh / mh-test-pass）、Jellyfin `http://127.0.0.1:8096`（mediahub-test / mediahub-test-password）。
- `SeedSource.exe`：先找工作树里的 `windows	oolsSeedSourcein...SeedSource.exe`，没有则退回 `D:deepseek_testMediaHub_Winwindows	oolsSeedSourcein...SeedSource.exe`。两者都不在时 P0.3 判失败（不会静默跳过播种）。
- 无人值守请保证机器空闲：`Start-MhApp` 自带「有人正在用这台机器就拒绝启动」的空闲门禁（除非设置 `MH_ALLOW_ACTIVE=1`）。脚本不绕过它，P1.0 会把拒绝原因写进 detail。

## 怎么跑

直接跑一个来源：

    pwsh -NoProfile -File windows/tools/acceptance/w6/sync-page-gui.ps1 -Server emby
    pwsh -NoProfile -File windows/tools/acceptance/w6/sync-page-gui.ps1 -Server jellyfin

走空闲门禁（推荐无人值守，等机器空闲后再启动，并带看门狗）：

    pwsh -NoProfile -File windows/tools/acceptance/run-quiet.ps1 -Suites w6 -WaitForIdleMinutes 30 `
      -Exe 'D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'

注意 `run-quiet.ps1` 的 `-Exe` 默认指向**主仓**的 `D:mh-out...`，跑 W6 时必须显式传 W6 的 exe，否则测的是旧二进制。
`-Suites all` 仍然只展开 u1..u4（未改动），w6 需要显式指定。

退出码：全绿 0，有失败 1。最后一行固定打印 `RESULT: N passed, M failed`。

## 它会对机器做什么（跑前必读）

- **改写 `%LOCALAPPDATA%MediaHubsettings.json` 的来源列表**：P0 用 SeedSource 播种后只保留被测来源（`<server>:*`）。这是有意的隔离手段，别在放置个人来源的机器上跑。
- **改写 `%LOCALAPPDATA%MediaHubsync-envelope.json`**：P2 由客户端导出；P3 把它复制一份到证据目录，然后**覆盖成对端信封**（无变化 + 新建 + 冲突三态），用来驱动真正的合并预览。跑完这个文件留着的是对端信封，不是本机导出。
- 不动 `sync-sources.json`（P3.4/P5.3 就是在证明这一点）；不动 AutoPlayNext（P5.2 证明）。
- 收尾（含异常路径的 `finally`）都会 `Stop-MhAppForced`，不留 MediaHub 进程。

## 证据产物（`windows/tools/acceptance/evidence/w6-sync/<server>/`）

- `checks.json` — 机器可读的断言结果（name/pass/detail）
- `results.md` — 人读报告：服务器版本、被测 exe、MediaHub.dll SHA256、逐条 PASS/FAIL + detail
- `p1-sync-page.png` / `p2-export.png` / `p3-preview.png` / `p4-verified.png` — 各阶段截图
- `p1-sync-page-tree.txt` / `p3-preview-tree.txt` / `p4-verify-tree.txt` — 页面全树（`ControlType | id= | name= | offscreen=`），用于事后区分「按预期消失」与「整页没加载」
- `exported-envelope.json` — 本机导出信封的副本（P3 覆盖前保存）

## 几个刻意的设计（改脚本时别退回去）

- **页面身份只认 AutomationId**。同步页里同名的 TextBlock 很多（行标签、预览明细、状态栏 message），按文案找会打偏；WinUI 的 StackPanel 多数没有 automation peer，所以 `sync.empty` 这类 id 不一定在树里，空态判断要用「行数」而不是「空态控件在不在」。
- **`canSendSecrets` 是协议字段，名字里就有 secret**，逐键扫描必须白名单它，同时断言它等于 `false`；不然要嘛误报要嘛放过真泄漏。
- **P4.4 的免责句**：导出成功文案写着「导出成功也不代表任何来源已同步。」，含有「已同步」三个字。反向断言必须排除这句，只抓「整段 Name 等于 已同步 / 以 已同步: 开头」的标签，并在 detail 里标 `disclaimerExcluded=True`。
- **预览不落盘是字节级断言**（SHA256 + 长度 + mtime），只看 UI 等于没测。
- 等待全部是「轮询到条件成立或超时」，没有固定长 sleep 顶替断言。

## 编码与约束

- UTF-8 **无 BOM**，幂等可重跑（重跑前杀进程、清历史信封）。
- 本脚本只读被测代码，不写 `src/` / `tests/`，不构建、不 commit。
