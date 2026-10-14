# Phase 1H — Emby PROGRESS 真机验证证据档（device evidence archive）

- **验收对象**：`main = 0e08fadbb398d0d35ab960f0e73ad8ee6ae2bba7`（PR #14 merge，含 PlaySessionId 端到端修复）
- **状态**：`CORE DEVICE VERIFIED / COLD-FINAL HAPPY-PATH DEVICE VERIFIED / SLOW-FINAL COMPLETION ISSUE OPEN / NOT SEALED`（review 裁定；本档为证据归档，**不是 closeout 宣告**）
- **性质**：Agent A 执行报告的证据归档。审查方核对过仓库源码、CI 与本文所引日志摘录，未独立重跑真机、未取得执行工作区的完整原始日志——结论归属于 Agent A reported。
- **安全**：本文不含 Token、Authorization 头或其 Base64 编码；请求头在 app 日志中原生脱敏（`****`）；账号以 user-Y（予初）/user-M（墨云阁）指代。

## 1. 构建与安装对应（历史基线与修复版本分开记录）

**历史基线（修复前，仅作对照，不作为修复版证据）：**

| 构建 | 源 | SHA256 | 用途 |
|---|---|---|---|
| 1H 实现包 | `9be3d28`（PR #13） | `9e234024553fa3056abf164ff73595d88d0bdf18e150675d8eee6942fe42cbea` | 修复前基线：三端点缺 PlaySessionId → Playing 400×36（真机取证，催生 PR #14） |

**修复版本（PR #14 merge 后，`0e08fadb`）：**

| 构建 | 源 | SHA256 | 用途 |
|---|---|---|---|
| clean #0 | `0e08fadb` | `ae28414fc971e2d456a3a16be84152a22ddd7c2d5423ab04b67e60b3b57f4ba2` | Scene A 正常 lifecycle 轮（177.636s 回写） |
| clean #1 | `0e08fadb` | `20e903ab1485dd9013b35befdf07c28380e0b0335bfc783462d009df6c96e0b5` | cold final 轮（#1 墨云阁 / #2 予初） |
| diag（单独标注） | `0e08fadb` + core/network `executeNoContent` 增加 REQ_BODY D 级日志（仅记录请求体；不记录任何头/Token/Base64） | `599effe39d174a95fb785f550539ad063569f1d0494975065f4ec92d4ae1b055` | 同 PSID 请求体取证（PositionTicks/PlaySessionId） |
| clean #2（执行结束回装） | `0e08fadb` | `775072588c0dd29fd92e44ec24341624c28fd99f89f155cd88731e172439759e` | 收尾在装包 |

设备：Xiaomi 14 Ultra（24031PN0DC，HyperOS / Android 16），播放引擎 = mpv（AUTO 自动选择）。

## 2. 服务器

| 服务器 | 地址 | 版本 |
|---|---|---|
| 予初Emby（user-Y） | `https://aaa.yusen6.ccwu.cc` | **4.9.5.0**（GET /emby/System/Info/Public 实测） |
| 墨云阁（user-M） | `https://p3mc4.mobaiemby.site` | **未取得**（PC 请求 /emby/System/Info/Public 返回 403） |

## 3. Scene A：正常播放 lifecycle + 服务端回写 + app 反射 — PASS（reported）

### 3.1 予初 item 795212（S01E02，clean #0 包，09-05 16:00–16:01）

```text
-> POST /emby/Sessions/Playing           <- 204
-> POST /emby/Sessions/Playing/Progress  <- 204 ×6（10s 节奏）
-> POST /emby/Sessions/Playing/Stopped   <- 204
```

紧接查询：`UserData.PlaybackPositionTicks = 1_776_360_000`（**177.636 秒 = 2:57.636**，5.76%）。
应用退出详情后重新进入：剧集行显示"51分钟 **进度 6%**"（截图 `yuchu_reflection_crop.png`）。
操作叙述中的"1:40 + 25 秒"为粗略过程时长，不作为位置精度验收值；位置精度以 §3.3 的同 PSID 精确对为准。

### 3.2 墨云阁（S01E02，wire 确认）

```text
-> POST /Sessions/Playing          <- 204 ×1
-> POST /Sessions/Playing/Progress <- 204 ×11
-> POST /Sessions/Playing/Stopped  <- 204 ×1
```

服务端反射未采集（执行侧无该服务器查询凭据；预期保留 ~65s 仅为预期，不写入通过证据）。

### 3.3 同 PSID 位置精确对（diag 包，09-05 19:11–19:13，予初 item 374069）

同一 PlaySessionId 全程不变（下记前 8 位 `6cd3207d`）。**完整序列（Progress 共 11 条，含起始 0；无省略）**，单位按 `1ms = 10,000 ticks`：

| 时刻 | 请求 | PositionTicks | 秒 |
|---|---|---|---|
| 19:11:16 | Playing | 0 | 0.000 |
| 19:11:16 | Progress | 0 | 0.000 |
| 19:11:26 | Progress | 80,500,000 | 8.050 |
| 19:11:36 | Progress | 180,600,000 | 18.060 |
| 19:11:46 | Progress | 280,700,000 | 28.070 |
| 19:11:56 | Progress | 381,210,000 | 38.121 |
| 19:12:06 | Progress | 481,310,000 | 48.131 |
| 19:12:16 | Progress | 581,410,000 | 58.141 |
| 19:12:26 | Progress | 681,510,000 | 68.151 |
| 19:12:36 | Progress | 781,610,000 | 78.161 |
| 19:12:46 | Progress | 882,130,000 | 88.213 |
| 19:12:56 | Progress | 982,230,000 | 98.223 |
| 19:12:57 | **Stopped** | **996,830,000** | **99.683** |

全部 204。紧接查询：`UserData.PlaybackPositionTicks = 996,830,000`（**99.683 秒**），**与最终 Stopped.PositionTicks delta = 0**；PlayedPercentage = 3.23%（与该条目时长自洽）。

## 4. Cold final：退出前无普通上报 → final 补发 Playing → Stopped — HAPPY PATH PASS（reported）

判定标准（review 裁定）：点击播放前清空 logcat；同一 PSID 首次普通上报之前退出；退出后仅出现补发 `Playing → Stopped`，两者均 204。

时间依据说明：点击播放与 BACK 的精确时刻未落独立时间戳，由单条 adb 命令内的固定 sleep（5s/7s）与 logcat 请求时间轴共同界定；下述 wire 时间戳即最终证据。

### #1 墨云阁 S01E03（clean #1 包，09-05 18:55，5s 窗口，原始摘录）

```text
18:55:35.222  -> POST https://p3mc4.mobaiemby.site/emby/Sessions/Playing
18:55:35.735  <- 204（512ms）
18:55:35.738  -> POST …/Sessions/Playing/Stopped
18:55:36.032  <- 204（293ms）
```

退出前零 Playing/Progress；退出后恰好两条，中间无 Progress——与 Provider cold 分支（`playbackStart → playbackStopped`，无中间 Progress）一致。

### #2 予初 S01E01（clean #1 包，09-05 19:01，7s 窗口，原始摘录）

```text
19:01:01.508  -> POST https://aaa.yusen6.ccwu.cc/emby/Sessions/Playing
19:01:01.997  <- 204（488ms）
19:01:02.002  -> POST …/Sessions/Playing/Stopped
19:01:02.667  <- 204（665ms）
```

### 原始证据索引（执行工作区 `.smoke/`，文件未入 repo）

| 文件 | SHA256（前 16） | 大小 | 对应场景 |
|---|---|---|---|
| `sceneA_wire.txt` | `b65b29a3971a0770` | 2,341B | §3.1 Scene A wire（clean #0） |
| `sceneA3_wire.txt` | `dca8f0c835614960` | 6,183B | §3.1 同轮完整 NETWORK 行 |
| `diag_wire.txt` | `5e32492ea06b23f1` | 7,251B | §3.3 精确对 REQ_BODY 序列（diag 包） |
| `coldfinal_wire.txt` | `bb020cde193e7861` | 578B | cold final #1（墨云阁） |
| `coldfinal2_wire.txt` | `9a4d1cd9fe0c5e2f` | 278B | cold final #2（予初）wire 摘录 |
| `coldfinal3_full.txt` | `746b8d26edd70445` | 1,946B | cold final #2 全 NETWORK/PLAYER/UI 行 |

## 5. SLOW-FINAL COMPLETION — OPEN（失败样本，未结案）

失败样本（clean #1 包，09-05 18:59，予初 S01E01，5s 窗口运行——与 #2 同场景的两次运行，此为第一次）：

```text
18:59:38.474  -> POST /Sessions/Playing
18:59:42.262  <- 204（3787ms）
（Stopped 缺失；取证窗口至 ~18:59:50 仍未见后续请求）
```

**登记措辞（按 review 裁定）**：慢请求下 final 未完成——观察到 Playing 204、Stopped 缺失。当前源码存在 2000ms 远端 final 总预算（`ProgressSyncCoordinator.flushFinal()` 以 `withTimeoutOrNull(REMOTE_FLUSH_TIMEOUT_MS = 2_000)` 约束整个 `remoteFinalReport`），Emby cold-final 必须先等 `playbackStart()` 完成再发 `playbackStopped()`，两者共用该 2 秒预算；本样本 Playing 响应 3787ms 已超预算，超时可能在补发 Playing 期间或等待在途请求期间耗尽。另注意 `ApiClient.executeNoContent()` 在 `withContext(Dispatchers.IO)` 内使用同步 `Call.execute()`——协程超时是协作式取消，不能中断已阻塞的 IO，因此"日志见到 Playing 204"与"上层 final 已超时"可以同时发生。**该次失败的精确取消来源待复现确认**（需完整时序或可控慢请求定位触发来源、final 开始时间与取消位置）；把退出窗口从 5s 调到 7s 只改变请求时序，不修复该预算限制。后续成功重试仅证明另一轮成功路径，不关闭本项。

关联源码事实：`stopAndFlush()` 先 `engine.stop()` → `syncCoordinator.stop()`（停 periodic/critical 管线）→ `flushFinal(finalProgress)`；本地快照保存在远端超时块之前完成。退出窗口 5s→7s 的调整不改变预算。

## 6. 短播放语义对照（curl 矩阵，诊断期取证）

| 实验（同 item curl 复现） | PlaybackInfo | ticks 行为 | 服务端结果 |
|---|---|---|---|
| E-1 | 带 StartTimeTicks=41.124s | 冻结在 41.124s | 0 |
| E-2 | 无 resume | 0→100s→Stopped 150s | 150s（精确） |
| E-3 | 带 StartTimeTicks=41.124s | 41.124s→500s→600s | 600s（精确） |
| E-4 | 无 resume | 冻结在 0 | 0 |

规则：**位置推进 → 精确落库；Stopped 位置等于会话起点 → 归 0（PlayCount 照记）**。PlaybackInfo.StartTimeTicks（续播）本身无毒。

对 cold final #2：紧接查询 `UserData.PlaybackPositionTicks = 0`——**该次查询结果为零**。冻结在起点的归因目前为推断（curl 矩阵提供对照），缺当次引擎位置/最终快照的直接证据；如需坐实，由 1H 慢-final 独立 PR 以可控请求一并复现。

## 7. Scene 状态汇总（含 OPEN 项）

```text
正常播放 lifecycle（予初/墨云阁）        PASS — wire + 服务端回写 + app 反射（予初）
服务端位置写入精度                       PASS — 同 PSID Stopped 996,830,000 == UserData（delta 0，diag 包）
cold final happy path                    PASS — 两台服务器各一次（§4 #1/#2）
SLOW-FINAL COMPLETION                    OPEN — §5（源码已有 2000ms 预算路径；精确取消来源待复现）
Scene C 短会话三端点请求                 PASS — reported（Progress 归因已更正为采样周期边界，见 §8）
Scene B（真机切条目 final）              NOT AVAILABLE ON DEVICE / UNIT CONTRACT COVERED
墨云阁 app 反射                          NOT CAPTURED（不影响第二服务器 wire 确认）
Jellyfin                                 server-blocked / production frozen（不变）
Phase 1H                                 NOT SEALED
```

## 8. Scene C 登记更正（review 裁定采纳）

此前"引擎 1s 首 tick 直通产生 Progress"的解释作废。源码事实：远端上报经 `ProgressSyncCoordinator.progress.sample(remoteIntervalMs)`（首个采样点在周期边界 ~10s）；Pause/Seek/Ended 触发即时 flush，**Stopped 事件仅状态通知**；本地快照与远端上报是两层触发。此前 7s 窗口出现 Progress 的原因：点击播放与退出分处两条 adb 命令，跨命令间隙使实际退出越过 10s 采样点。本轮冷 final 以单条命令（tap → sleep 5/7 → BACK）消除该间隙。

## 9. 范围限定

通过范围限于本次测试的两台服务器、测试条目、mpv 播放路径与 BACK 退出方式；不外推为所有 Emby 版本、所有引擎（Media3）与所有退出方式。1H 慢-final 修复为独立 PR（基于届时 main），补可控复现与回归后重新裁定封存；Jellyfin 生产冻结不变。
