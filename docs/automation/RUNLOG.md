# RUNLOG — MediaHub 执行日志（追加式）

**只追加，不改写历史。** 发现旧结论错误时追加更正行并写明归因，不静默改写。

字段口径：`时间 | 执行者 | 动作 | 结果 | 证据`。`证据` 必须是路径、SHA 或 run id；写不出来的行不写。

---

## 2026-10-30

### 04:0x — lead（W6-112）— 收口 W6 树半途合并

- 动作：把 `origin/feature/windows-client`（W14 `b586b001`）合回 `feature/cross-device-source-sync`，冲突仅 `LogService.cs`，按并集解（保留 `Snapshot()` + `DiagnosticsReport` 渲染层 + 补传 `rollingDirectory`）。
- 结果：合并提交 `7c2f96ca`；PR #39 从 `CONFLICTING/DIRTY` 恢复 `MERGEABLE`。
- 证据：`git diff --diff-filter=D 51776182 7c2f96ca` 为空；CI `40945269315`（windows 1726+104）、`40945269145`（Android 2495）。

### 04:34 — lead — §81 文档补记

- 动作：`docs/android-source-sync-transfer.md` 追加 §81（冲突面、并集解法、该行不过脱敏的理由、零删除判据）。
- 结果：提交 `e6e5d534`；exact-SHA CI `40950218318`（windows 1726+104）、`40950218275`（Android 2495，VI 19×2）。
- 说明：`7c2f96ca` 那次 push 取消了同分支仍在跑的 Android run —— 同分支连续 push 会取消进行中 run，这是已知坑。

### 10:39–11:00 — lead（W6-113）— 写出锁 + 诊断导出契约修复

- 动作：仓库外实现跨进程写锁 `D:/mh-goal/lock.mjs`（资源粒度 worktree / branch / ref:origin，O_EXCL 原子创建 + token + 心跳 TTL + 字典序批量取锁失败回滚 + --take-stale 显式闸门），碰撞测试入库 `D:/mh-goal/lock-collision-test.mjs`。
- 结果：碰撞测试 **17/17 PASS**；WIP 保护快照 `head 6c28866f`、`porcelain=0`，他人 6 个 backup 相关文件一个未碰。
- 动作：核验诊断导出契约，发现**真缺陷** —— W6-112 合并丢了 W14 的 `# ` 前缀且位置错位；修复提交 `055c85c3`（新增 `RollingDirectoryPrefix`、渲染顺序 Title → 滚动目录 → 生成时间 → 版本 → 设备 → 条目、该行仍不过 Redact）。
- 结果：`DiagnosticsReportTests` 9 → 16，本机 `MediaHub.Tests` **1858 / 0**；三条负向对照恰好 1 / 1 / 4 红，快照 `sha256 7ad4ed85…` 逐字节还原后复绿。
- 证据：windows-ci `40993937874`（1858 + 104）；其 Android run `40993937937` = **cancelled**（我随后 push 所致，非产品缺陷）。

### 11:00 — lead（W6-113）— PR #39 正文治理

- 动作：W6-68/69/70/71 四节（37,565 B）折叠为 838 B 索引，原文移入 `docs/pr-archive/PR39-BODY-ARCHIVE-20261030.md`（38,250 B，提交 `d6331139`）。
- 结果：正文 257,915 → **224,584 B**（余 37,560 B），远端回读 `trimEnd` 相等。
- 证据：exact-SHA CI `40995411695`（windows 1858+104）、`40995411720`（Android `uniqueTests` 2501、VI 19×2、FAILED 0）。

### 11:1x — 另一执行者（W21/W22，backup 系列）— 与本次并行

- 动作：W21 WebDAV 下载（`6c28866f`）、W22 WebDAV 列目录（`5a3b8ba6`）。
- 归因：`uniqueTests` 2495 → 2501 来自该系列的 W15 Kotlin 用例，**不是** W6-113 的 Windows 侧改动。
- 并发事实：两个执行者同时向同一分支提交；我持锁期间对方未取锁（其提交时间在我锁心跳过期之后）。

### 11:4x — lead（W6-113 round 2）— 建立自动化账本

- 动作：新建 `docs/automation/`（本文件 + `TEST_MATRIX.md` + `queue.json`）；此前仓库只有 `HANDOFF.md`/`TASKS.md` 等叙述性文档，无机器可读队列与门禁矩阵。
- 结果：见提交；`TEST_MATRIX.md` 每条标注 `EXECUTED` / `SOURCE_REVIEWED` / `NOT_RUN`，`queue.json` 记录任务 DAG。
- 边界：本轮不并行改 Windows 生产代码 —— 共享 `MediaHub.Tests` 与 docs 已被另一执行者占用，并行改同文件会互相盖掉。

### 11:4x–12:0x — lead（W6-113 round 2）— 账本落地与自纠

- 动作：新建 `docs/automation/`（`TEST_MATRIX.md` / `RUNLOG.md` / `queue.json`）。此前后两者不存在，按指令「若不存在再建立」执行。
- 动作：把导出契约钉回两棵树各自的文档（`W14-DIAGNOSTIC-LOG-FILES.md` 与 `docs/android-source-sync-transfer.md` §81.3）。
- 结果：提交 `4ff648e1`（5 文件 +207）。
- **自纠**：`TEST_MATRIX` 里我上午写的静态计数 `[Fact]` 1459 / `[Theory]` 99，同一小时复核已是 **1475 / 100**（另一执行者新增 `BackupListTests.cs`）；`--required-suite` 我记成 61 处，实测 **60**。除改正数字外，补了 §5「测量命令」与 `grep -c -- '...'` 的选项解析坑，并把这次自证写进文件本身。
- 结果：提交 `14f7fa39`（1 文件 +27/−2）。
- **WIP 保护实测**：提交时工作树里有另一执行者的 4 个未提交文件（`MhBackupCliCommand.cs`、`MhBackupWebDavLister.cs` 修改 + `MhBackupWebDavDeleter.cs`、`BackupDeleteTests.cs` 新增）。全程只 `git add` 自己的文件，两次提交后那 4 个文件原样留在工作树，未被误提交。
- **推送时的漂移处置**：推送前三验（锁 / 远端 head / 在跑的 run）发现 `HEAD~2 != origin` —— 对方在我之后完成 W23（`00a6dfa8`）并一并推送，我的两个提交已随之进入远端。按纪律停下重新评估，未强推；确认 `4ff648e1`/`14f7fa39` 均在 `origin` 历史、且 diff 未被外部内容污染后释放锁。
- 观察：本轮两次「等对方 CI 跑完再推」都是正确决策 —— W22 的 Android build 跑了约 28 分钟，若我中途推送会像上一轮那样取消它。

### 13:2x–14:3x — lead（W6-113 round 2，续）— 定位并记录 Android CI 证据链的结构性缺陷

- **发现**：`android-ci.yml` L15-17 有 `concurrency: group: ci-${{ github.ref }}` + `cancel-in-progress: true`；`windows-ci.yml` **没有 concurrency 块**。这解释了两者行为相反：同分支连续 push 时 Android 必被取消、Windows 从不被取消。
- **实证**：今天 4 次 Android cancelled（`40993937937` @`055c85c3`、`41003347784` @`e7779b7f`、`41008280281` @`74548803`、`40991293954` @`6c28866f`），同期 windows-ci 在同一批 head 上全部 success。
- **判定**：不是执行者纪律问题。本轮两次按规程「等对方 run 跑完再 push」，仍被对方的下一次 push 取消 —— Android 一次 28–30 分钟，对方每 20–60 分钟一次提交，窗口几乎必然重叠。
- **产出（均未改任何工作流）**：`ADR-AUTO-001-android-ci-concurrency.md`（现象/根因/代价/三方案/为何不直接改/过渡做法）、`PATCH-PROPOSAL-android-ci-concurrency.md`（一行改动 + 四条验收，含负向对照）。
- **提交**：`446b252c`（ADR）、`9e37d9b1`（补丁提案）。
- **过渡纪律**：`cancelled` 在账本中单列，不并入 pass/fail；用树哈希作辅助论证必须标注为推理。
- **正向对照**：`2ef01647`（他执行者 W25）的 Android CI **完整跑完 success** —— 证明只要没有新 push 插入，流程本身是健康的，问题只在 concurrency 策略。
