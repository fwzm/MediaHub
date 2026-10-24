# W8 · 无窗口续播闭环（真服实测）

> 状态：两服实测全绿 —— 驱动脚本 `RESULT: 17 passed, 0 failed`，每台服务器上的探针 `RESULT: 16 passed, 0 failed`。
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
| 解析播放会话 | `MediaBrowserProvider.ResolvePlaybackAsync`（`windows/src/MediaHub.Providers/MediaBrowser/MediaBrowserProvider.cs:254-300`，`SessionId` 来自 `PlaybackInfo`） |
| 上报开播/进度/停止 | `IProviderProgress.ReportPlaybackStartedAsync` / `ReportProgressAsync` / `ReportFinalProgressAsync`（`ProviderContracts.cs:117`、`:121`、`:127`；实现在 `MediaBrowserProvider.cs:347-400`，落到 `/Sessions/Playing`、`/Sessions/Playing/Progress`、`/Sessions/Playing/Stopped`） |
| 回读位置 | `IProviderProgress.GetResumePositionAsync`（`ProviderContracts.cs:131`，实现 `MediaBrowserProvider.cs:414-418`） |
| 回读续播列表 | `IProviderProgress.GetContinueWatchingAsync`（`ProviderContracts.cs:130`，实现 `MediaBrowserProvider.cs:402-412`） |
| 回读详情 | `IProviderDetail.GetItemDetailAsync` |

两点实现事实值得记住：`MediaBrowserProvider.cs:362` 要求 `PlaybackProgress.SessionId` 非空，否则直接拒绝；`:393` 的上报是 **best-effort**，网络/HTTP 异常被吞掉 —— 所以「上报成功」不能靠返回值判断，只能靠**回读**判断。本探针的每条断言都建立在回读上。

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
| `PlaySessionId` 位置 | `PlaybackInfo` 响应根（兼容旧版时才在 `MediaSources[0]`） | 同左（产品 `MediaBrowserProvider.cs:273` 先读根再兜底源） |
| 续播下限 | `MinResumePct=5` | **2%**（本轮二分实测，见 §5.1） |
| 最短可续播片长 | `MinResumeDurationSeconds=300` | 未在本实例暴露 policy（见 §5.2） |
| 夹具条目 id | GUID `b22e31da9faf6ef247e19f7b3774dab2` | 短字符串 `34` |

身份头这条差异是硬约束：把 `X-Emby-Authorization` 发给 Jellyfin 12.2 的 `/Users/AuthenticateByName` 会得到 **HTTP 400**，服务端日志报 `ArgumentNullException: Value cannot be null. (Parameter 'request.App')`（`Emby.Server.Implementations.Session.SessionManager.AuthenticateNewSessionInternal`）。产品代码本来就是对的两台服各用各的头（`MediaBrowserDialect.cs:32` / `:39`，身份头生成见 `:64-74`，token 注入见 `:120-132`），驱动脚本只是照抄该规则（`windows/tools/acceptance/w8/headless-resume.ps1:138` `New-IdentityHeaders`）。

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

17 条驱动断言：`probe-binary-exists`、`jellyfin-answers`(12.2.0)、`emby-answers`(4.10.2.0)、`the-two-servers-are-not-the-same-process`、两侧各 `-fixture-login`、`-has-a-long-enough-fixture`、`-probe-passes`、`-probe-wrote-evidence`、`-report-is-all-green`、`-report-used-several-client-instances`，以及 `no-test-server-processes-left`。

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
| 驱动 `0` | 17 条驱动断言全过 |
| 驱动 `1` | 任一驱动断言失败（含残留进程、探针非零退出） |

驱动脚本刻意**不调用** `windows/tools/setup-test-server.ps1`：那个脚本开头会 `Stop-Process` 所有 `jellyfin` / `EmbyServer`（该文件 `:125-127`），会把并行工作的其他人正在用的服务器杀掉。本驱动只杀自己启动的进程，以及收尾时清理同名残留。

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
