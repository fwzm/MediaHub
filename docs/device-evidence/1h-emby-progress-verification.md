# Phase 1H — Emby PROGRESS 真机验证证据档（device evidence）

- **验收对象**：`main = 0e08fadbb398d0d35ab960f0e73ad8ee6ae2bba7`（PR #14 merge，含 PlaySessionId 端到端修复）
- **性质**：Agent A 执行报告的证据归档；原始 logcat/截图在执行工作区留存，本文收录脱敏摘录与哈希对应关系
- **安全**：本文不含 Token、Authorization 头或其 Base64 编码；请求头一律 `****` 脱敏（app 日志原生行为）；账号显示为 user-Y（予初 mulity）/user-M（墨云阁 ink_Choi）

## 1. 构建与安装对应

| 构建 | 源 | SHA256 | 用途 |
|---|---|---|---|
| clean #1 | `0e08fadb` | `20e903ab1485dd9013b35befdf07c28380e0b0335bfc783462d009df6c96e0b5` | Scene A 正常 lifecycle + 177.636s 回写轮 |
| clean #2（最终在装） | `0e08fadb` | `775072588c0dd29fd92e44ec24341624c28fd99f89f155cd88731e172439759e` | cold final 轮（本轮补证） |
| diag（单独标注） | `0e08fadb` + core/network `executeNoContent` 增加 REQ_BODY D 级日志（仅记录请求体，不记录任何头/Token） | `599effe39d174a95fb785f550539ad063569f1d0494975065f4ec92d4ae1b055` | Stopped 请求体（ticks/PSID）取证；诊断结论已与 clean 包交叉核对 |

设备：Xiaomi 14 Ultra（24031PN0DC，HyperOS / Android 16），播放引擎 = mpv（AUTO 自动选择）。

## 2. 服务器

| 服务器 | 地址 | 版本 |
|---|---|---|
| 予初Emby（user-Y） | `https://aaa.yusen6.ccwu.cc` | **4.9.5.0**（GET /emby/System/Info/Public 实测） |
| 墨云阁（user-M） | `https://p3mc4.mobaiemby.site` | 未取到——服务器对 PC 直接查询 /System/Info/Public 回 403（其 remote-access 策略；app 内访问正常） |

## 3. Scene A：正常播放 lifecycle + 服务端回写 + app 反射 — PASS

### 3.1 予初 item 795212（S01E02，clean 包 #1，09-05 16:00–16:01）

```text
-> POST /emby/Sessions/Playing           <- 204
-> POST /emby/Sessions/Playing/Progress  <- 204 ×6（10s 节奏）
-> POST /emby/Sessions/Playing/Stopped   <- 204
```

紧接查询：`UserData.PlaybackPositionTicks = 1_776_360_000`（**177.636 秒 = 2:57.636**，5.76%）。
应用退出详情后重新进入：剧集行显示"51分钟 **进度 6%**"（5.76% 四舍五入），截图 `yuchu_reflection_crop.png`。
说明：操作叙述中的"1:40 + 25 秒"为粗略过程时长，不作为位置精度验收值；位置精度以 3.3 的同 PSID 精确对为准。

### 3.2 墨云阁（S01E02，wire 确认）

```text
-> POST /Sessions/Playing          <- 204 ×1
-> POST /Sessions/Playing/Progress <- 204 ×11
-> POST /Sessions/Playing/Stopped  <- 204 ×1
```

服务端反射未采集（无该服务器查询凭据；预期保留 ~65s，不写入通过证据）。

### 3.3 同 PSID 位置精确对（diag 包，09-05 19:11–19:13，予初 item 374069）

同一 PlaySessionId 全程不变（`6cd3207d…`，已脱敏为前 8 位）：

```text
19:11:16 Playing  {"ItemId":"374069","PlaySessionId":"6cd3207d…","PositionTicks":0,"PlayMethod":"DirectStream"}                       -> 204
19:11:26 Progress {"…","PositionTicks":80500000,…}    -> 204     （80.5s）
19:11:36 Progress {"…","PositionTicks":180600000,…}   -> 204     （180.6s）
…每 10s 递增…
19:12:56 Progress {"…","PositionTicks":982230000,…}   -> 204     （982.2s）
19:12:57 Stopped  {"ItemId":"374069","PlaySessionId":"6cd3207d…","PositionTicks":996830000}                           -> 204
```

查询：`UserData.PlaybackPositionTicks = 996830000`，**与最终 Stopped.PositionTicks delta = 0**；PlayedPercentage = 3.23%（99.683s / 该条目时长，自洽）。

## 4. Cold final：退出前无普通上报 → final 补发 Playing → Stopped — PASS

判定标准（review 裁定）：点击播放前清空 logcat；同一 PSID 首次普通上报之前退出；退出后仅出现补发 `Playing → Stopped`，两者均 204。

### #1 墨云阁 S01E03（clean 包 #2，09-05 18:55，原始摘录）

```text
18:55:35.222  -> POST https://p3mc4.mobaiemby.site/emby/Sessions/Playing           （tap 播放后 ~5s 退出触发）
18:55:35.735  <- 204 …/Sessions/Playing
18:55:35.738  -> POST …/Sessions/Playing/Stopped
18:55:36.032  <- 204 …/Sessions/Playing/Stopped
```

退出前零 Playing/Progress；退出后恰好两条，中间无 Progress——与 Provider cold 分支（`playbackStart → playbackStopped`，无中间 Progress）一致。该服务器 PlaySessionId 修复在 final 路径同样生效。

### #2 予初 S01E01（clean 包 #2，09-05 19:01，原始摘录）

```text
19:01:01.508  -> POST https://aaa.yusen6.ccwu.cc/emby/Sessions/Playing
19:01:01.997  <- 204（488ms）
19:01:02.002  -> POST …/Sessions/Playing/Stopped
19:01:02.667  <- 204（665ms）
```

查询：`UserData.PlaybackPositionTicks = 0`（PlayCount 递增）。归因：7 秒窗口内 mpv 冻结在起点（缓冲未起），Stopped 携带起点位置；curl 对照矩阵已坐实服务器"未推进归零"规则——

| 实验（同 item curl 复现） | PlaybackInfo | ticks 行为 | 服务端结果 |
|---|---|---|---|
| E-1 | 带 StartTimeTicks=41.124s | 冻结在 41.124s | **0** |
| E-2 | 无 resume | 0→100s→Stopped 150s | **150s（精确）** |
| E-3 | 带 StartTimeTicks=41.124s | 41.124s→500s→600s | **600s（精确）** |
| E-4 | 无 resume | 冻结在 0 | **0** |

即：**位置推进 → 精确落库；冻结在起点 → 归 0（PlayCount 照记）**。PlaybackInfo.StartTimeTicks（续播）本身无毒。短播放"无进度文本"与该规则一致，非上报缺陷。

### 过程样本（非通过证据，如实登记）

cold final #2 的首次尝试：Playing 204（响应 3787ms）后 Stopped 缺失——退出时机恰逢慢请求在途，与取证窗口竞争；修正退出窗口（7s，仍 < 10s 首个采样点）并延长取证等待后重试即干净通过。该样本说明退出 final 的完成度受在途慢请求影响，登记为已知行为观察。

## 5. Scene 状态汇总（对齐 review 裁定）

```text
正常播放 lifecycle（予初/墨云阁）        PASS — wire + 服务端回写 + app 反射（予初）
服务端位置写入精度                       PASS — 同 PSID Stopped 996830000 == UserData（delta 0）
cold final 分支                          PASS — 两台服务器各一次：退出前零普通上报 → 补发 Playing → Stopped 全 204
Scene C 短会话三端点请求                 PASS — reported（见下）
Scene B（真机切条目 final）              NOT AVAILABLE ON DEVICE / UNIT CONTRACT COVERED
墨云阁 app 反射                          NOT CAPTURED（不影响第二服务器 wire 确认）
Jellyfin                                 server-blocked / production frozen（不变）
```

Scene C 历史登记更正：此前 7s 窗口出现的 Progress 实为退出操作跨命令间隙越过 10s 首采样点所致（`sample(period)` 首个采样点在周期边界，非"引擎 1s 首 tick 直通"）；本轮冷 final 以 <10s 单命令窗口取得无普通上报形态。

## 6. 范围限定

通过范围限于本次测试的两台服务器、测试条目、mpv 播放路径与 BACK 退出方式；不外推为所有 Emby 版本、所有引擎（Media3）与所有退出方式。Phase 1H 状态由 review 裁定（当前 `CORE DEVICE VERIFIED / FINAL-ONLY SCENE PENDING / NOT SEALED`，本档补齐 FINAL-ONLY 分支后待裁定提升）。
