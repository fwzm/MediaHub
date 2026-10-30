# ADR-AUTO-001：Android CI 的 concurrency 取消正在摧毁 exact-SHA 证据

- 状态：**待维护者裁决**（本文只做分析与提案，未改动任何工作流）
- 提出：2026-10-30，W6-113 round 2
- 证据级别：`EXECUTED`（run id 与 conclusion 均来自 GitHub API）+ `SOURCE_REVIEWED`（工作流配置）

## 1. 现象

同一分支 `feature/cross-device-source-sync` 上有两个执行者并行提交。当天 Android CI 陆续出现：

| run | head | 结局 | 取消者 |
| --- | --- | --- | --- |
| 40993937937 | `055c85c3` | cancelled | 我随后 push 的 `d6331139` |
| 41003347784 | `e7779b7f` | cancelled | 对方 12:51 的 `0ae7757f` |
| 41008280281 | `74548803` | cancelled | 对方 13:48 的 `2ef01647` |
| 40991293954 | `6c28866f` | cancelled | 我 10:49 的 `055c85c3` |

同期 windows-ci 在同一批 head 上**全部 success**（`41003347790` @ `e7779b7f`：1890 + 104；`41000771112` @ `00a6dfa8`；`41005235521` @ `0ae7757f`）。

## 2. 根因（源码定位）

`.github/workflows/android-ci.yml`：

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

`.github/workflows/windows-ci.yml`：**没有 concurrency 块**（全文只有 `on` / `permissions` / `env` / `jobs`）。

⇒ 两个工作流在「同一分支连续 push」这件事上行为相反：

- windows-ci：每次 push 各起一条 run，互不干扰，所以每条提交都能拿到自己的 exact-SHA 结论；
- android-ci：`group` 只按 `github.ref` 分组，**任何新 push 都会取消同分支上仍在跑的那条**，于是「谁最后 push 谁有证据，前面的人全部丢失」。

这不是执行者的纪律问题。本轮我两次按规程「等对方 run 跑完再 push」，仍然被对方的**下一次**push 取消 —— 因为 Android 一次约 28–30 分钟，而对方每 20–60 分钟就有一次提交。只要两个执行者并行，这个窗口几乎必然重叠。

## 3. 代价

- **证据不可得**：Android 侧没有 exact-SHA 结论，只能引用「更早某个 commit 的绿」或「树哈希未变」来间接论证——这是推理，不是证据。
- **状态词失真**：`cancelled` 既不是 pass 也不是 fail。任何把它记成 PASS 的账本都是错的；反过来记成 FAILED 也冤枉代码。
- **返工**：每次想拿证据都要重新跑 28 分钟，且可能再被取消。

## 4. 三个候选方案

### 方案 A（推荐）：`group` 加 SHA，让每条提交互不取消

```yaml
concurrency:
  group: ci-${{ github.ref }}-${{ github.sha }}
  cancel-in-progress: true
```

- 效果：同一提交被重复触发（例如重跑）仍会取消旧的；**不同提交之间不再互相取消**，每条都能拿到自己的 exact-SHA 结论。
- 代价：旧提交的 run 会继续跑完。公开仓库 Actions 分钟免费，代价只是 runner 排队时间。
- 风险：若短期内推送密集，可能同时有 2–3 条 30 分钟的 Android run 排队，挤占并发额度。

### 方案 B：`cancel-in-progress: false`

- 效果：所有 run 排队，任何一条都不被取消。
- 代价：连「同一提交的重复触发」也要排满 30 分钟，队列压力比 A 更大。

### 方案 C：不改工作流，改协作纪律

- 效果：零配置变更。
- 代价：需要两个执行者严格串行（谁持锁谁推），而它们的排程互不可见 —— 本轮实测已证明不可靠。

## 5. 为什么本文没有直接改

改 concurrency 会改变**整个仓库所有人的验证节奏**：对方此刻正以 20–60 分钟一次的频率推送，切换成「不取消」后其 run 会开始排队，可能从「立即可见绿/红」变成「半小时后才可见」。这属于影响他人的共享基础设施变更，按 Goal 规则应提交决策材料而非单方面实施。

**建议**：由维护者在方案 A 上确认后，由任一方实施；实施时应同时把 `windows-ci` 的对齐说明写进两个工作流头部注释，避免下一个人再问「为什么两个 CI 不一样」。

## 6. 当前可行的过渡做法（无需改配置）

1. 账本中 `cancelled` 单列，不并入 pass/fail（已在 `TEST_MATRIX.md` §2 与 §4 写入）。
2. 需要 Android exact-SHA 证据时，**在推送后等待那条 run 完成再推下一个提交**，并把该 run id 立刻记进账本；下一轮若发现对方又推，接受「本条已被取消」的事实并注明。
3. 用「树哈希未变」作辅助论证时必须标明它是推理（`git rev-parse <sha>:core` 相同 + `git diff --name-only <a> <b> -- core feature app` 为空），不得写成「Android 已验证通过」。
