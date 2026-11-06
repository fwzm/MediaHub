# W8 · 无窗口续播闭环（真服实测）

> 状态：两服实测全绿 —— 驱动脚本 `RESULT: 17 passed, 0 failed`（2026-10-24 首次闭环时的条数；§10 加上两条版本断言后共 **19** 条），每台服务器上的探针 `RESULT: 16 passed, 0 failed`。
>
> 范围：本文只证明**服务端侧的续播记录、新客户端实例可见性、看完清除与下限规则**。它**不**证明窗口内「继续观看」提示的呈现，也**不**证明播放引擎的 seek 精度 —— 那两条仍是 GUI 验收（`windows/tools/acceptance/w8/s1-resume-gui.ps1`、`s2-emby-gui.ps1`）的职责。本任务是「不弹窗口也能证明续播链路成立」，所以刻意不启动任何带窗口的进程。

## 1 结论摘要

| 服务器 | 版本 | 地址 | ServerId | 夹具条目 | 探针结果 |
| --- | --- | --- | --- | --- | --- |
| Jellyfin | 12.2.0 | http://127.0.0.1:8096 | `daffc6534af44bbb8f73848209519255` | `b22e31da9faf6ef247e19f7b3774dab2` "Long Resume (2026)" 600 s | 16 passed, 0 failed |
| Emby | 4.10.2.0 | http://127.0.0.1:8097 | `1271d0a1040046db8c0089cc328bbbf1` | `34` "Long Resume" 600 s | 16 passed, 0 failed |

驱动脚本还要证明两件事：两台服务器确实**同时**在线且是不同进程（比对两个不同 ServerId）；跑完后本地残留的 `jellyfin` / `EmbyServer` 进程数必须是 **0**。两条都通过。

## 2 能力边界

| 本闭环证明的 | 本闭环不证明的 |
| --- | --- |
| 产品 provider 栈（工厂 → `ProviderHandle` → `IProviderProgress`）能把播放位置上报给真服 | 窗口内「继续观看」卡片/续播提示的 UI 呈现 |
| 上报的位置在服务端被原样保存（ticks 完全相等，不是近似） | 播放引擎从续播点开始解码、seek 是否精确 |
| 一个**全新创建的客户端实例**（重新登录，等价于重启应用）能读到该位置，并出现在续播列表里 | 应用重启后恢复播放会话（`SessionRestoreProtocol`）与窗口生命周期 |
| 看完（上报到片尾）后 `Played=true` 且位置清零、退出续播列表 | 多用户并发、跨设备同步、离线与重试策略 |
| 低于服务器下限的上报会按服务器规则被丢弃/清零（两台服下限不同，见 §5） | 服务器版本升级后的新阈值（断言已把差异钉成回归，见 §5.3） |

## 3 被测路径：必须是产品 provider 栈

探针 `windows/src/MediaHub.ResumeProbe/`（**故意不入** `windows/MediaHub.Windows.sln`，也不引用 WinUI/播放器/引擎）只经由产品接口说话。理由写在 `windows/src/MediaHub.ResumeProbe/MediaHub.ResumeProbe.csproj:3-11`：一个自己拼 HTTP 的探针证明不了我们发布的代码。

| 步骤 | 产品入口 |
| --- | --- |
| 建句柄 + 登录 | `IMediaProviderFactory.Create(...)` + `IProviderAuth.AuthenticateAsync(Credentials)`（`windows/src/MediaHub.Providers/ProviderContracts.cs`） |
| 发现可播放条目 | `IProviderLibrary.GetLibrariesAsync` / `GetItemsAsync(libraryId, PageRequest)`（同上） |
| 解析播放会话 | `MediaBrowserProvider.ResolvePlaybackAsync`（`windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:269-315`，`SessionId` 来自 `PlaybackInfo`） |
| 上报开播/进度/停止 | `IProviderProgress.ReportPlaybackStartedAsync` / `ReportProgressAsync` / `ReportFinalProgressAsync`（`ProviderContracts.cs:117`、`ProviderContracts.cs:121`、`ProviderContracts.cs:127`；实现在 `MediaBrowserProvider.cs:362-415`，落到 `/Sessions/Playing`、`/Sessions/Playing/Progress`、`/Sessions/Playing/Stopped`） |
| 回读位置 | `IProviderProgress.GetResumePositionAsync`（`ProviderContracts.cs:131`，实现 `MediaBrowserProvider.cs:429-433`） |
| 回读续播列表 | `IProviderProgress.GetContinueWatchingAsync`（`ProviderContracts.cs:130`，实现 `MediaBrowserProvider.cs:417-427`） |
| 回读详情 | `IProviderDetail.GetItemDetailAsync` |

两点实现事实值得记住：`MediaBrowserProvider.cs:377` 要求 `PlaybackProgress.SessionId` 非空，否则直接拒绝；`MediaBrowserProvider.cs:408` 的上报是 **best-effort**，网络/HTTP 异常被吞掉 —— 所以「上报成功」不能靠返回值判断，只能靠**回读**判断。本探针的每条断言都建立在回读上。

## 4 四个阶段与 16 条断言

每台服务器上只有 **1 个** ≥300 s 的夹具条目，所以四个阶段共用同一条目，顺序本身有意义：先存 40%，再用 1% 覆盖它（必须被丢弃），再用 2%（两台服结论不同），最后看完。每个阶段都**新开句柄并重新登录**（`clientInstances=10`），读数一律来自新实例。

| 阶段 | 上报 | 各服实测回读 |
| --- | --- | --- |
| 前置 | 夹具复位（服务端 API 清 `PlayedItems`） | 两服 `played=false`、位置 0 |
| `phase1-resume` | Start 0 → Progress 40% → Stop 40%（2 400 000 000 ticks） | 两服位置 2 400 000 000、`played=false`、续播列表含该条目 |
| `phase2-below-the-floor` | 1%（60 000 000 ticks） | 两服位置 0、`played=false`、续播列表为空（1% 同时低于 Emby 2% 与 Jellyfin 5%） |
| `phase2b-two-percent` | 2%（120 000 000 ticks） | **Jellyfin**：位置 0、列表空（2% < 5%）；**Emby**：位置 120 000 000、列表含该条目（2% 恰好命中下限） |
| `phase3-finished` | Progress 95% → Stop 全长 6 000 000 000 ticks | 两服位置 0、`played=true`、续播列表为空 |

每服 16 条断言（`windows/src/MediaHub.ResumeProbe/Program.cs` 里 `Check(...)` 产出 `[assert] <id> PASS|FAIL expected=… actual=…`）：

| # | 断言 id | 钉住的行为 |
| --- | --- | --- |
| 1 | `precondition-target-is-unplayed` | 夹具已被复位（前置条件由驱动脚本负责） |
| 2 | `precondition-target-has-no-position` | 同上，位置为空或 0 |
| 3 | `resume-position-roundtrip` | 上报 ticks 与回读 ticks **完全相等**（不是近似） |
| 4 | `resume-position-roundtrip-detail` | 详情接口的 `UserData.PlaybackPositionTicks` 与之一致 |
| 5 | `resume-visible-from-a-new-client` | 全新客户端实例能在续播列表里看到它 |
| 6 | `resume-not-marked-played` | 40% 不被当成看完 |
| 7 | `session-resolved-through-the-product-path` | `SessionId` 来自产品 `ResolvePlaybackAsync`（非空） |
| 8 | `below-floor-clears-the-position` | 1% 覆盖不掉已存的 40%，而是把位置清零 |
| 9 | `below-floor-does-not-mark-played` | 1% 不置 `Played` |
| 10 | `below-floor-stays-out-of-the-resume-list` | 1% 之后续播列表不再包含它 |
| 11 | `two-percent-floor-is-per-server` | 2% 的期望值**按服务器**取值（Emby 保留 120 000 000，Jellyfin 清零） |
| 12 | `two-percent-stop-does-not-mark-played` | 2% 不置 `Played` |
| 13 | `two-percent-resume-list-follows-the-same-floor` | 续播列表的期望同样按服务器取值 |
| 14 | `finished-marks-played` | 上报到片尾后 `Played=true` |
| 15 | `finished-clears-the-position` | 看完后位置清零 |
| 16 | `finished-leaves-the-resume-list` | 看完后退出续播列表 |

## 5 两服的协议与策略差异（本轮实测）

| 维度 | Jellyfin 12.2.0 | Emby 4.10.2.0 |
| --- | --- | --- |
| 默认端口 | 8096 | 8097（`D:\mh-servers\data\emby\config\system.xml`） |
| API 根 | 无前缀 | 无 `/emby` 前缀（根路径直接服务 API） |
| 身份头 | `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…` | `X-Emby-Authorization` 同格式 |
| token 头 | 沿用 `Authorization` 头内 `Token="…"` | `X-Emby-Token` + `X-MediaBrowser-Token` 两个头 |
| `PlaySessionId` 位置 | `PlaybackInfo` 响应根（兼容旧版时才在 `MediaSources[0]`） | 同左（产品 `MediaBrowserProvider.cs:288` 先读根再兜底源） |
| 续播下限 | `MinResumePct=5` | **2%**（本轮二分实测，见 §5.1） |
| 最短可续播片长 | `MinResumeDurationSeconds=300` | 未在本实例暴露 policy（见 §5.2） |
| 夹具条目 id | GUID `b22e31da9faf6ef247e19f7b3774dab2` | 短字符串 `34` |

身份头这条差异是硬约束：把 `X-Emby-Authorization` 发给 Jellyfin 12.2 的 `/Users/AuthenticateByName` 会得到 **HTTP 400**，服务端日志报 `ArgumentNullException: Value cannot be null. (Parameter 'request.App')`（`Emby.Server.Implementations.Session.SessionManager.AuthenticateNewSessionInternal`）。产品代码本来就是对的两台服各用各的头（`MediaBrowserDialect.cs:32` / `MediaBrowserDialect.cs:39`，身份头生成见 `MediaBrowserDialect.cs:64-74`，token 注入见 `MediaBrowserDialect.cs:120-132`），驱动脚本只是照抄该规则（`windows/tools/acceptance/w8/headless-resume.ps1:146` `New-IdentityHeaders`）。

### 5.1 Emby 的续播下限是片长的 2%（不是 5%）

对同一条 600 s 夹具逐点二分（每点都先复位，再 Start(0) → Progress(P) → Stop(P)，然后读 `Items/34`）：

| 上报位置 | 占片长 | 服务端保存 |
| --- | --- | --- |
| 11.40 s / 114 000 000 ticks | 1.90% | 0 |
| 11.94 s / 119 400 000 ticks | 1.99% | 0 |
| **12.00 s / 120 000 000 ticks** | **2.00%** | **120 000 000** |
| 12.06 s / 120 600 000 ticks | 2.01% | 120 600 000 |

边界正好落在 2.00%，且是按**百分比**而非绝对秒数（1.99% = 11.94 s 被丢弃，2.00% = 12.0 s 被保留），因此与片长相关。Jellyfin 12.2 的文档化配置是 `MinResumePct=5` + `MinResumeDurationSeconds=300`（`windows/docs/W8-EVIDENCE.md:26`，本实例 `D:\mh-servers\data\jellyfin\config\system.xml:32/34` 同值）。

### 5.2 覆盖语义与 policy 可见性

Emby 上先存 40%（2 400 000 000）再报 1%，回读是 **0 / `played=false` / 续播列表为空** —— 低于下限的上报不是「被忽略」，而是把已存位置**清零并移出续播列表**。再报 2% 又能重新写入。

`GET /Users/{uid}` 对非管理员用户不暴露 `MinResumePct` / `MinResumeDurationSeconds`（policy 键里没有 resume 相关项），所以 Emby 的 2% 只能靠行为实测得到，不能读配置。测试与文档因此把阈值写进断言期望值，而不是去读服务端配置。

### 5.3 为什么断言里要有「按服务器取值」

`two-percent-floor-is-per-server` 与 `two-percent-resume-list-follows-the-same-floor` 的期望值是 `emby ? 保留 : 清零`。这不是把差异藏起来，而是把它**钉成回归**：如果哪天 Emby 改成 5%、或 Jellyfin 改成 2%，这两条会立刻变红并打印两侧实测值，而不是让一个「恰好都对」的宽松断言继续绿着。

## 6 原始命令与输出

### 6.1 构建探针

```
dotnet build windows/src/MediaHub.ResumeProbe/MediaHub.ResumeProbe.csproj -c Debug -nologo
已成功生成。 0 个警告 0 个错误
```

### 6.2 一键驱动（起双服 → 两遍探针 → 收尾 kill）

```
pwsh -NoProfile -File windows/tools/acceptance/w8/headless-resume.ps1
```

末行与退出码：

```
PASS no-test-server-processes-left :: running=0
summary: D:\deepseek_test\MediaHub_Win\windows\tools\acceptance\evidence\w8\headless-resume-summary.json
RESULT: 17 passed, 0 failed
DRIVER EXIT=0 elapsed=31s
```

当时的 17 条驱动断言：`probe-binary-exists`、`jellyfin-answers`(12.2.0)、`emby-answers`(4.10.2.0)、`the-two-servers-are-not-the-same-process`、两侧各 `-fixture-login`、`-has-a-long-enough-fixture`、`-probe-passes`、`-probe-wrote-evidence`、`-report-is-all-green`、`-report-used-several-client-instances`，以及 `no-test-server-processes-left`。

上面这段是 2026-10-24 那次运行的原文（所以是 17 条）。§10 之后驱动多了两条版本断言（`jellyfin-is-the-expected-version` / `emby-is-the-expected-version`），当前驱动共 **19** 条 = 15 条固定 + 每服 2 条（`-report-is-all-green`、`-report-used-several-client-instances`）；条数由 §12 的契约块与源码逐条对齐。

### 6.3 单服探针（可直接手跑）

```
dotnet windows/src/MediaHub.ResumeProbe/bin/Debug/net10.0/MediaHub.ResumeProbe.dll --server jellyfin --url http://127.0.0.1:8096 --user mediahub-test --password mediahub-test-password --json D:\mh-verify\w8\jellyfin-resume.json --label jellyfin-headless
dotnet windows/src/MediaHub.ResumeProbe/bin/Debug/net10.0/MediaHub.ResumeProbe.dll --server emby --url http://127.0.0.1:8097 --user mh --password mh-test-pass --json D:\mh-verify\w8\emby-resume.json --label emby-headless
```

每服输出尾部（Emby 一侧，Jellyfin 只有第 11、13 条期望不同）：

```
  [assert] resume-position-roundtrip PASS expected=2400000000 actual=2400000000
  [assert] below-floor-clears-the-position PASS expected=0 actual=0
  [assert] two-percent-floor-is-per-server PASS expected=120000000 actual=120000000
  [assert] finished-marks-played PASS expected=true actual=true
  RESULT: 16 passed, 0 failed
```

### 6.4 证据文件

`windows/tools/acceptance/evidence/w8/`（已被 `.gitignore:48` 忽略，不入库）：

| 文件 | 大小 | 内容 |
| --- | --- | --- |
| `jellyfin-headless-resume.json` | 11,346 B | 探针完整报告：服务器、条目、四阶段样本、16 条断言、客户端实例数 |
| `jellyfin-headless-resume.log` | 4,811 B | 探针 stdout |
| `emby-headless-resume.json` | 11,019 B | 同上 |
| `emby-headless-resume.log` | 4,529 B | 同上 |
| `headless-resume-summary.json` | 1,263 B | 驱动汇总：`servers[2]`（版本 + ServerId）、`probes[2]`（`assertions=16`、`failures=0`、`clientInstances=10`）、`passed=17`、`failed=0` |

## 7 复现步骤与退出码

1. 确认 `D:\mh-servers\data\{jellyfin,emby}` 与 `D:\mh-media` 存在（夹具媒体树 + 两台服数据目录）。
2. 运行 §6.1 构建探针（只需一次）。
3. 运行 §6.2 驱动脚本。脚本自己负责：探测 8096/8097 是否已有可用服务器（已答 `/System/Info/Public` 则复用），否则用 WMI 分离启动（`Start-JellyfinHeadless` / `Start-EmbyHeadless`），登录夹具账号、按服务端 API 复位 `PlayedItems`，跑两遍探针，`finally` 里杀掉本轮启动的 PID 与所有 `jellyfin`/`EmbyServer`，最后断言残留 0。

| 退出码 | 含义 |
| --- | --- |
| 探针 `0` | 16 条断言全过 |
| 探针 `1` | 至少一条断言失败（stdout 里 `[assert] … FAIL expected=… actual=…`） |
| 探针 `2` | 用法错误或连不上/登录失败 |
| 驱动 `0` | 19 条驱动断言全过（15 条固定 + 每服 2 条） |
| 驱动 `1` | 任一驱动断言失败（含残留进程、探针非零退出） |

驱动脚本刻意**不调用** `windows/tools/setup-test-server.ps1`：那个脚本开头会 `Stop-Process` 所有 `jellyfin` / `EmbyServer`（`windows/tools/setup-test-server.ps1:158-159`），会把并行工作的其他人正在用的服务器杀掉。本驱动只杀自己启动的进程，以及收尾时清理同名残留。

## 8 未覆盖与残余风险

1. **窗口与引擎仍未验**：本闭环不覆盖「继续观看」卡片渲染、点击后从续播点开始播放、引擎 seek 精度 —— 仍由 `s1-resume-gui.ps1` / `s2-emby-gui.ps1` 负责。
2. **复位只能走服务端 API**：产品 provider 接口只有读续播与写进度，**没有**「标记已看 / 清位置」的写入口（`MediaHub.Providers` 下无 `SetPlayedAsync` 一类 API），所以 `DELETE /Users/{uid}/PlayedItems/{id}` 只作为**夹具准备步骤**出现在驱动脚本里，探针本身 100% 走产品路径。若将来需要「在应用里标记已看」，那是产品缺口而不是测试缺口。
3. **阈值随服务器版本变化**：Emby 的 2% 是行为实测（该实例未暴露 policy），升级后可能变。断言已按服务器取值并在失败时打印两侧实测值。
4. **固定端口 + 单实例**：脚本假定 8096/8097；若被占用会复用已应答的那台（并断言两台 ServerId 不同），但**不**验证多实例或端口自定义。
5. **时间依赖**：每阶段回读前固定等待 750 ms（`SettleMilliseconds`，`--settle-ms` 可调）。网络很慢时可能需要加大，但降低会引入偶发。
6. **未覆盖并发/多用户/HTTPS/代理**：只测单用户、单客户端循环、明文回环。
7. **依赖固定夹具**：每服只有 1 条 ≥300 s 条目（`Long Resume` 600 s）；探针会 BFS 走库（深度上限 3）并要求至少 1 条 ≥300 s，找不到会以退出码 1 明确报错而不是静默跳过。

## 9 相关文档

- `windows/docs/W8-EVIDENCE.md` —— 阈值规则与早期真服证据（Jellyfin `MinResumePct=5`、`MinResumeDurationSeconds=300`）
- `windows/docs/W9-EVIDENCE.md` —— 两台服的 ServerId、账号、夹具条目 id 与片长
- `windows/src/MediaHub.ResumeProbe/Program.cs` —— 探针实现（参数、四阶段、断言、报告）
- `windows/tools/acceptance/w8/headless-resume.ps1` —— 驱动脚本（起服、登录、复位、跑探针、收尾）
- `windows/tools/acceptance/w8/s1-resume-gui.ps1`、`s2-emby-gui.ps1` —— GUI 侧续播验收（本文件的补充，不是替代）

## 10 版本可钉住的复跑（2026-10-29 · 第 43 轮）

> 状态：三次真服闭环全绿 —— 官方 Emby **4.10.1.0**（任务书指定版本）+ Jellyfin 12.2.0 的钉住运行 `RESULT: 19 passed, 0 failed`；默认路径（本机既有 Emby 4.10.2.0）`RESULT: 19 passed, 0 failed`。两条版本断言各比对 `GET /System/Info/Public` 的 `Version` 字符串。

### 10.1 为什么需要这一节

§1 的表格只钉住「跑过的那两台实例」，而 `windows/tools/acceptance/w8/headless-resume.ps1` 原先只有一个 `-InstallRoot`：换一台服务器没有入口，版本只写进报告、不在断言里。任务书要求的是**官方 Emby 4.10.1.0**，所以本轮把 harness 拆成「安装根 / 数据根 / 期望版本」三个可传参数，并把版本做成断言 —— 报告里记的版本号必须由断言复核，否则「这一轮跑的是哪个版本」永远只是回忆。

### 10.2 三次运行（本轮证据）

| 运行 | Jellyfin | Emby | 结果 |
| --- | --- | --- | --- |
| 钉住版本 | 12.2.0 @8096（`D:\mh-servers`） | **4.10.1.0** @8098（`D:\mh-servers410`，官方 4.10.1.0 归档） | `RESULT: 19 passed, 0 failed` |
| 默认路径 | 12.2.0 @8096 | 4.10.2.0 @8097（`D:\mh-servers\emby-install\app\system.old`） | `RESULT: 19 passed, 0 failed` |
| 负向 N1 | 12.2.0 | 4.10.1.0，但期望故意写成 4.10.2.0 | **17 passed, 1 failed** —— `FAIL emby-is-the-expected-version :: expected=4.10.2.0 actual=4.10.1.0` |
| 负向 N2 | 12.2.0 | 安装根故意写成 `D:\mh-servers410-missing` | 具名失败：`FATAL EmbyServer.exe not found under D:\mh-servers410-missing\emby-install or D:\mh-servers410-missing\emby (run get-test-servers.ps1)`，退出码 1 |

钉住运行里的两条版本断言原文：`PASS jellyfin-is-the-expected-version :: expected=12.2.0 actual=12.2.0`、`PASS emby-is-the-expected-version :: expected=4.10.1.0 actual=4.10.1.0`。两服探针各 `RESULT: 16 passed, 0 failed`（`clientInstances=10`），收尾 `PASS no-test-server-processes-left :: running=0`。

与 §5.3 有关的一条**新实测**：官方 Emby 4.10.1.0 在 600 s 夹具上停止于 12.0 s（片长 2%）时位置被**保留**（`PASS two-percent-floor-is-per-server expected=120000000 actual=120000000`，且出现在续播列表里），与 4.10.2.0 结论一致 ⇒「Emby 的下限是片长 2%」不是 4.10.2 独有的行为。同一次运行里 Jellyfin 12.2.0 对同样 12.0 s 的上报仍是清零（5% + 300 s），两服差异在同一次运行内被同时钉住。

### 10.3 复跑命令

```powershell
# 1) 官方 Emby 4.10.1.0 装到隔离根（仓库外），并关掉自动更新
pwsh -NoProfile -File windows/tools/setup-test-server.ps1 -Server emby `
    -InstallRoot D:\mh-servers410 -DataRoot D:\mh-servers410\data\emby -Port 8098 `
    -UserName mh -Password mh-test-pass -DisableAutoUpdate

# 2) 版本钉住的闭环（Jellyfin 仍用 D:\mh-servers 的 12.2.0）
pwsh -NoProfile -File windows/tools/acceptance/w8/headless-resume.ps1 `
    -InstallRoot D:\mh-servers -EmbyInstallRoot D:\mh-servers410 `
    -EmbyDataRoot D:\mh-servers410\data\emby -EmbyPort 8098 `
    -ExpectJellyfinVersion 12.2.0 -ExpectEmbyVersion 4.10.1.0
```

`windows/tools/get-test-servers.ps1` 负责把两个官方归档下到 `<Destination>\_downloads`（解压到 `<Destination>\{jellyfin,emby}`），并把版本与每个归档的 SHA-256 写进 `<Destination>\SERVER-VERSIONS.json`。`setup-test-server.ps1` 仍然会在开头强杀机器上所有 `jellyfin`/`EmbyServer` 进程（`windows/tools/setup-test-server.ps1:158-159`），所以不要与其他人的并行取证同时跑。

### 10.4 本轮修掉的两个工具缺陷

1. **缓存归档只查「存在」不查「可用」**（`windows/tools/get-test-servers.ps1`）：本机 `_downloads\embyserver-win-x64-4.10.1.0.7z` 只有 90,730,496 B，而官方资产是 133,879,144 B —— 一次中断的下载把截断文件留在缓存里，之后每次运行都拿它去解压（`7zr x` 报 `Unexpected end of archive`，`Files: 0`）。现在 `Test-Artifact` 会真的打开容器（`.7z` 走 `7zr t`，zip 走 `[System.IO.Compression.ZipFile]::OpenRead`），打不开就打印 `refetching <名称> (the cached copy cannot be opened)` 并重下。
2. **Emby 的监听端口不随 `-Port`**（`windows/tools/setup-test-server.ps1`）：Emby 4.10 忽略 `-datadir`，端口只来自数据根的 `config\system.xml`（从未运行过的新根默认 8096）—— 本轮实测打印 `configured port is 8096; pinning 8098`。脚本现在先冷启动探到真实端口，停服、写 `HttpServerPortNumber`/`PublicPort`、读回校验（不等即抛）后重启；并新增 `-DisableAutoUpdate`（鉴权后经 `/System/Configuration` 关掉自动更新并回读，实测 `auto update disabled (read back as false)`），避免验收途中服务器自动升级把版本证据改掉。同一次改动把 `Wait-Http` 从「有响应即就绪」收紧为「只认 2xx」，超时消息带最后状态码。

### 10.5 负向对照 N3：缓存校验不是空转

在 scratch 目录 `D:\mh-control` 放两个**小号**真实归档（一个 132 B 的 zip + 一个 183 B 的 7z，7z 内含 `system\EmbyServer.exe` 占位），然后：

| 步骤 | 动作 | 观测 |
| --- | --- | --- |
| A | 把 7z 截断成一半（91 B）后运行 `get-test-servers.ps1 -Destination D:\mh-control` | `cached  jellyfin_12.2-amd64.zip` + `refetching embyserver-win-x64-4.10.1.0.7z (the cached copy cannot be opened)`；并且重下真的发生了（15 s 内文件长到 32,715,492 B，随后由对照自身终止进程） |
| B | 恢复完好的 7z 后再跑一次 | `cached  jellyfin_12.2-amd64.zip` + `cached  embyserver-win-x64-4.10.1.0.7z` → 解压 → `versions recorded: D:\mh-control\SERVER-VERSIONS.json` → 两个 exe 都定位到，**退出码 0** |

### 10.6 本机环境事实（复跑前必读）

**`PATHEXT=.CPL` 会让 `& dotnet` 变成「无法将 'dotnet' 项识别为 cmdlet」**：本会话的 shell 环境里 `$env:PATHEXT` 是 `.CPL`，PowerShell 因此不会把 `dotnet` 补成 `dotnet.exe`。而驱动脚本正是用 `& dotnet $ProbeDll …` 启动探针（`windows/tools/acceptance/w8/headless-resume.ps1:240`），所以任何用自定义环境变量起 harness 的调用方都必须显式带上正常的 `PATHEXT`（`.COM;.EXE;.BAT;.CMD;…`）。本轮为此真实失败了两次：版本断言全部通过、探针却一步没跑。这不是脚本缺陷，但会伪装成「服务器/探针有问题」。

### 10.7 证据文件

| 文件 | 内容 |
| --- | --- |
| `D:\mh-verify\w8-emby410-pinned-run.log` | 钉住运行完整 stdout（`RESULT: 19 passed, 0 failed`） |
| `D:\mh-verify\w8-evidence-emby410\`（4 文件） | 该次运行的 summary + 两服探针 JSON/日志 |
| `D:\mh-verify\w8-default-path-run.log` + `w8-evidence-emby410-default\` | 默认路径复跑（19/0，Emby 4.10.2.0） |
| `D:\mh-verify\w8-neg-N1.log`、`w8-neg-N2.log` | 两条负向对照 |
| `D:\mh-verify\w8-neg-N3-A.log`、`w8-neg-N3-B.log` | 缓存校验 A/B |
| `D:\mh-verify\w8-emby410-setup.log` | 隔离根一次性向导 + 建库 + 扫描 + 关自动更新 |

这些目录都在仓库外（`D:\mh-verify`）或 `.gitignore` 覆盖的 `windows/tools/acceptance/evidence/`，所以只留路径、不入库；报告里的数字都能由上面的日志复算。

### 10.8 残余风险

1. **版本断言只比字符串**：`Version` 来自 `GET /System/Info/Public`，不校验二进制哈希。归档哈希由 `get-test-servers.ps1` 记进 `<Destination>\SERVER-VERSIONS.json`（本轮已实测该文件内容与官方资产一致）。
2. **默认路径跑的是 4.10.2.0**：本机 `D:\mh-servers` 树下唯一的 `EmbyServer.exe` 位于 `emby-install\app\system.old`（FileVersion 4.10.2.0），这是安装事实而非脚本选择；`-ExpectEmbyVersion` 让它至少不会静默漂移。
3. **2% 结论仍只来自两个 Emby 小版本**（4.10.1.0 / 4.10.2.0）：更大范围的版本漂移仍需逐版本重跑。
## 11 源代码指针契约（机器可读）

本文件按行号引用产品与工具源码的地方全部声明在下面这个块里，并由 `windows/tools/check-licence-surface.ps1` 的第 54 项 `resume-source-pointer-claims` 逐条复核（每次 CI 都跑）。判据是四条：这份记录仍然写着每条 `cite`；该文件仍存在、区间仍落在文件内；区间里仍然含着那条 `anchor` 短语；正文里每一处带文件名的源码引用都在块里声明过。行数按 `[System.IO.File]::ReadAllLines` 计（文件以换行结尾时末尾那个空元素不计，与 `wc -l` 同口径）。

为什么要它：2026-10-24 建这份记录时，`headless-resume.ps1` 的 `New-IdentityHeaders` 在 138 行、`setup-test-server.ps1` 的强杀块在 125-127 行；10-29 的第 43 轮往两个脚本前面插了行，两处指针就**静默**指错了位置（现为 146 与 158-159）—— 文档读起来一切正常，只有自动核对能发现。行号是易碎引用，写下来不核对等于没写。

覆盖范围只有 `.cs` / `.csproj` / `.ps1` 三种扩展名：`W8-EVIDENCE.md:26` 属跨文档引用（由该文档自己负责），`D:\mh-servers\data\jellyfin\config\system.xml:32/34` 是仓库外的现场配置路径（不在版本控制里，无法按提交核对）。

<!-- BEGIN resume-source-pointer-claims -->
resume-pointer-owner: check-licence-surface.ps1:resume-source-pointer-claims
resume-pointer-count: 20
resume-pointer-convention: line counts as [System.IO.File]::ReadAllLines; 1 <= from <= to <= line count; the anchor substring must still sit inside the cited range

- cite=windows/src/MediaHub.ResumeProbe/MediaHub.ResumeProbe.csproj:3-11 ; anchor=Deliberately not part of MediaHub.Windows.sln
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:269-315 ; anchor=ResolvePlaybackAsync
- cite=windows/src/MediaHub.Providers/ProviderContracts.cs:117 ; anchor=ReportPlaybackStartedAsync
- cite=windows/src/MediaHub.Providers/ProviderContracts.cs:121 ; anchor=Task ReportProgressAsync
- cite=windows/src/MediaHub.Providers/ProviderContracts.cs:127 ; anchor=ReportFinalProgressAsync
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:362-415 ; anchor=ReportProgressAsync
- cite=windows/src/MediaHub.Providers/ProviderContracts.cs:131 ; anchor=GetResumePositionAsync
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:429-433 ; anchor=GetResumePositionAsync
- cite=windows/src/MediaHub.Providers/ProviderContracts.cs:130 ; anchor=GetContinueWatchingAsync
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:417-427 ; anchor=GetContinueWatchingAsync
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:288 ; anchor=PlaySessionId
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:377 ; anchor=string.IsNullOrWhiteSpace(progress.SessionId)
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:408 ; anchor=catch (ProviderException ex) when
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserDialect.cs:32 ; anchor=X-Emby-Authorization
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserDialect.cs:39 ; anchor=tokenInAuthHeader: true
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserDialect.cs:64-74 ; anchor=BuildAuthorizationValue
- cite=windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserDialect.cs:120-132 ; anchor=ApplyToken
- cite=windows/tools/setup-test-server.ps1:158-159 ; anchor=Get-Process jellyfin, EmbyServer
- cite=windows/tools/acceptance/w8/headless-resume.ps1:146 ; anchor=function New-IdentityHeaders
- cite=windows/tools/acceptance/w8/headless-resume.ps1:240 ; anchor=& dotnet $ProbeDll
<!-- END resume-source-pointer-claims -->

改这份记录时：动正文里的行号，就要同步改块里的 `cite`；动源码导致函数搬家，就先改块、再跑一遍守卫（`RESULT: PASS` 才算真的对上了）。

## 12 断言条数契约（机器可读）

上文 §4 的 16 条探针断言表与 §6.4 / §7 的驱动断言条数都是**计数**：往 `Program.cs` 里加一条 `Check("…")`、或往驱动里加一条 `Assert-That`，文档里的数字就会静默过期（§1 的 17 条就是这么变成过时数字的 —— §10 加了两条版本断言，正文一直没跟上）。下面这个块把三处计数声明出来，由 `windows/tools/check-licence-surface.ps1` 的第 55 项 `resume-record-count-claims` 每轮 CI 复核：

- 探针断言 id 必须是**双向集合相等**：`Program.cs` 里每一处 `Check("<id>")` 都要在 §4 表格里，§4 表格里每一行 id 也都要在代码里（改名 / 漏登 / 多登都算红）。
- 驱动条数按**调用点**算：第一个参数写成 `($pair.Label + …)` 的调用点属于「每服一条」，按 `foreach ($pair in @(@{ Label = … }, …))` 里的服务器数相乘；第一个参数是字面量断言名的 `Assert-That '<name>'` 各算一条（放在循环后面也算固定条，因为它跑一次）。`fixed + per-server × servers` 必须等于声明的总数。
- 条数只在这里声明一次，§7 的退出码表与正文引用都按这里的数写。

<!-- BEGIN resume-record-count-claims -->
resume-count-owner: check-licence-surface.ps1:resume-record-count-claims
resume-probe-assertions: 16
resume-driver-fixed-assertions: 15
resume-driver-per-server-assertions: 2
resume-driver-servers: 2
resume-driver-assertions-total: 19
<!-- END resume-record-count-claims -->

改断言时：先在代码里加/改，再同步 §4 表格或 §6.4 正文，最后改这个块里的数，然后跑一遍守卫（`RESULT: PASS` 才算对上）。

## 13 常量契约（机器可读）

§11 管的是「正文按行号引用源码」，§12 管的是「断言条数」。探针与驱动里还有第三种会静默过期的东西：**散落在正文里的常量** —— 夹具最短片长（300 s）、每阶段回读前的固定等待（750 ms）、走库深度上限（3）、以及 ticks 与百分比换算（`TimeSpan.TicksPerSecond`）。它们不像断言 id 那样能被集合比对抓住，正文里只是一句「750 ms」或一个「2 400 000 000 ticks」。

下面这个块把这些常量声明成唯一事实来源，由 `windows/tools/check-licence-surface.ps1` 的第 56 项 `resume-record-constant-claims` 每轮 CI 复核：

- **A 块自指**：`resume-constant-owner` 必须点名本项；十个键齐全；`resume-constant-count` 等于块里数字键的条数（含一条自身不算）。
- **B 从源码取数**：`Program.cs` 的 `MinimumRuntimeTicks = <n> * TicksPerSecond`、`SettleMilliseconds { get; init; } = <n>`、`const int MaximumDepth = <n>` 三处当场解析，必须与块里声明相等；并确认 ticks 仍然来自 `TimeSpan.TicksPerSecond`（不是随手写的字面量）。改源码不改块 ⇒ 红。
- **C 算术自洽**：夹具片长（`resume-fixture-seconds`，600 s）乘以 `resume-probe-ticks-per-second` 得全长 ticks，再按正文的 40% / 1% / 2% 算出四个数字，必须与块里 `resume-derived-ticks-*` 一致，且这些数字仍以「N 000 000 000 ticks」的分组写法逐字出现在正文里。
- **D 正文残留**：正文仍写着「固定等待 750 ms」「深度上限 3」「≥300 s」三句 —— 代码改了而正文句子还是旧值也算红。
- **E 文件缺失**：记录或 `Program.cs` 读不到 ⇒ 本项 SKIP（退出码不变）。

`resume-fixture-seconds` 是**服务端夹具的属性**（不是仓库源码里的常量），记在这里是为了让 C 的算术有唯一事实来源；换一条夹具，改这个值，C 与正文一起改。

<!-- BEGIN resume-record-constant-claims -->
resume-constant-owner: check-licence-surface.ps1:resume-record-constant-claims
resume-constant-count: 9
resume-probe-min-runtime-seconds: 300
resume-probe-settle-milliseconds: 750
resume-probe-max-depth: 3
resume-probe-ticks-per-second: 10000000
resume-fixture-seconds: 600
resume-derived-ticks-total: 6000000000
resume-derived-ticks-40pct: 2400000000
resume-derived-ticks-1pct: 60000000
resume-derived-ticks-2pct: 120000000
<!-- END resume-record-constant-claims -->

改常量时：先改代码，再改正文里的数字（含分组写法），最后改这个块，然后跑一遍守卫（`RESULT: PASS` 才算对上）。
