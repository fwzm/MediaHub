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

### 16:0x–16:3x — lead（W6-113 round 3）— 修掉 windows-ci 套件门禁的失效漏洞

- **只读侦察**：W6 树被另一执行者的 W27 占用中（15:58 仍有文件变动），故先做只读核验；确认 `android-ci.yml` 的 concurrency 未被采纳改动（ADR-AUTO-001 仍待裁决），也确认我的导出契约修复 `RollingDirectoryPrefix` 与 16 条 `DiagnosticsReportTests` 仍完好。
- **发现真缺陷**：`windows-ci` 的「Fail if a suite ran nothing or lost tests」自述意图是挡住「丢一半用例仍打印 Passed」，但下限硬编码 `Floor = 400` / `100`（源自 2026-10-24 的 438 / 104）。到 10-30 实际执行已达 **1920** 例 ⇒ 删掉 **1520 例（79%）** 仍判绿。
- **修法**：新增 `windows/tools/test-suite-baseline.json`（提交基线）。`actual < baseline` 判红、`actual > baseline` 判绿 + `::warning`。不把 400 改成 1920 —— 那只是把同一漂移问题推给下一个人。
- **验证（实跑，非纸面）**：把 CI 里那段 PowerShell 原样抽出、用真实 TRX 夹具跑六条对照 —— NC0 GREEN、NC1 少 1 例 RED、**NC2 丢 1520 例 RED（旧 Floor=400 在此会绿）**、NC3 涨 1 例 GREEN+warning、NC4 failed RED、NC5 基线缺失 RED、NC6 还原 GREEN。
- **真实 CI 验证**：`ffe5a9fb` 的 windows-ci run `41025538206` success，日志含 `MediaHub.Tests: total=1920 baseline=1920`、`MediaHub.WebDav.Tests: total=104 baseline=104`、`combined tests=2024`。
- **踩到的陷阱**：基线最初含中文注释，PowerShell 5.1 的 `Get-Content -Raw` 把无 BOM UTF-8 当 ANSI 解码 ⇒ 中文损坏、字符串截断、`ConvertFrom-Json` 报 `Invalid array passed in`（文件本身合法 UTF-8，node `JSON.parse` 通过）。⇒ 基线刻意保持纯 ASCII。
- **如实记录一次自己的失误**：推送 `ffe5a9fb` 时 `in_flight=1`，取消了另一执行者 `4cd51deb` 的 Android CI —— 这正是我上一轮写进 ADR 的那个坑，本轮又犯。`4cd51deb` 的 windows-ci 已 success，Android 侧有更早 `6cff25d3` 的绿。纪律上应等 in-flight 归零再推。

### 19:2x–19:5x — lead（W6-113 round 7）— 基线只比总数仍可被「等量替换」绕过

- **侦察**：W6 树 head `52cda428`（并行执行者 W29/W30 已入）、锁 held=0、工作树干净；PR #39 Draft / MERGEABLE / UNSTABLE、body 166,859 字符；windows-ci `41048006881` success；Android CI `41048006754` in_progress。
- **缺口（P0，CI 真回归）**：`ffe5a9fb` 落的基线只比 **total**。删 N 例再新增 N 个新例 ⇒ total 不变 ⇒ 门禁判绿，而这正是该门禁自述要挡的那类事（“a suite that quietly loses half its cases”）。
- **修法三步**：
  1. 闸门从 `windows-ci.yml` 内联脚本抽成 `windows/tools/check-test-suite-baseline.ps1` —— 内联脚本无法被负向对照直接调用，只能复制粘贴，而副本迟早与 CI 真实行为漂移；
  2. 基线新增 per-suite `classes` 表（MediaHub.Tests 112 类 / WebDav 4 类）。类归属取自 TRX `TestDefinitions` 的 `TestMethod.className`，**不取结果名**：xUnit 会把 Theory 的数据参数拼进结果名，参数里可能含点号（如 `(1.5, 2026.10.30)`），按结果名切类会切错；
  3. 新增 `windows/tools/update-test-suite-baseline.ps1`：按真实 TRX 一条命令重算总数与类表；**无 `-AcceptRemoval` 时拒绝下调任何计数** —— 堵住「用刷新基线的方式把红改绿」。
- **负向对照 18/18（NC0–NC17）**，pwsh 7.6.9 与 Windows PowerShell 5.1 逐例判定一致：NC0 真实匹配 GREEN；NC1 总数少 1 RED；**NC2 总数 5==5 但类分布不同 RED**；NC3 整类消失 RED；NC4 新类 GREEN+warning；NC5 类增大 GREEN+warning；NC6 failed RED；NC7 notExecuted RED；NC8 缺 `classes` 表 RED；NC9 空 `classes` 表 RED；NC10 `expected=0` RED；NC11 负数 RED；NC12 类计数 0 RED；NC13 刷新脚本拒绝下调且文件逐字节未变；NC14 `-AcceptRemoval` 后写入 total=5/AlphaTests=2；NC15 **由真实 CI TRX 重生成基线与提交版本逐字节一致**；NC16 **同一输入在 HEAD 旧 total-only 门禁下 GREEN**（证明 NC2 的红是本次收口带来的，不是巧合）；NC17 真实 1943+104 → PASS。
- **数据来源**：CI run `41048006881` 产物（139,659,367 B）里的 `test-results/*.trx`（windows 2,706,486 B / webdav 145,266 B），不是本机重跑 —— 保证基线钉住的是**同一 commit 的 CI 行为**。
- **CI 验收（EXECUTED，head `0cef5d79`，push `52cda428..0cef5d79`）**：windows-ci run `41051026115` success，四作业全 success。台阶日志：`MediaHub.Tests: total=1943 baseline=1943 failed=0 notExecuted=0`、`MediaHub.Tests: classes=112 baseline=class table 112 entries`、`MediaHub.WebDav.Tests: total=104 baseline=104`、`WebDav classes=4 baseline=class table 4 entries`；套件实跑 `Passed: 1943` / `Passed: 104`。日志留存 `D:/mh-verify/r7-nc/ci-0cef5d79-windows.log`（109,235 B）。Android CI run `41051026121`（同 head）入账时 in_progress → 本轮**不作结论**（本轮零 Android 代码改动）。
- **第三方再次采纳（EXECUTED，第二个非本次作者）**：并行写者 W31（`06b6890d`，作者 墨崔）新增 9 例并同步把基线 1943 → **1952**、类表 112 → **113**（`MhBackupHandleLocatorTests: 9`）；同 head 的 windows-ci run `41053179818` success，台阶日志 `MediaHub.Tests: total=1952 baseline=1952` / `classes=113 baseline=class table 113 entries`。与 W28 那次不同，这次连类表也一起改了 —— 说明 per-class 口径已被第二个使用者按设计跟上，而不是只把总数刷到绿。
- **note**：CI run `41053031684`（head `5f253c50`）在跑时被 W31 的 push 取代；Android CI `41053032168`（同 head）因 `cancel-in-progress` 被取消 —— 与 ADR-AUTO-001 描述一致，不作失败判定。
## 2026-10-30 20:2x — lead（W6-113 round 8）— PR #39 正文治理：21 节历史明细归档，正文回到 69 KB 余量

- **触发**：round 7 结束时正文 **259,740 B**，硬上限 262,144 B ⇒ 只剩 **2,404 B**，新章节追加会被 GitHub 静默截断（不是报错）。
- **动作**：把 2026-10-28 ~ 10-29 的 **21 个小节**（W6-86 … W6-109，合计 **69,288 B**）整体移出正文，**原文逐字**存入 `docs/pr-archive/PR39-W6-86-to-W6-109-20261030.md`（70,177 B，含归档说明头）；正文原位换成一节索引（21 行表：小节 id + 主题）。
- **结果**：正文 **259,740 → 192,381 B**，余量 **69,763 B**（未来约 20 节追加不用再折）。折叠区段以远端当次回读为唯一输入，边界用 `^## ` 标题精确匹配（起点 `W6-86`、终点 `W6-111` 前一节），折叠前后都做 `Buffer.byteLength` 守卫与回读 `trimEnd` 比对。
- **口径与证据级别**：折叠 = 原文搬家，不是删除（EXECUTED：归档文件字节数 70,177 B、正文 192,381 B 均由脚本实测；正文回读校验见下轮推送记录）。
- **并发风险（round 7 已实测的同类问题）**：PR 正文是多个执行者共享的可变产物 —— 8 分钟内我方小节曾被对方用本地快照整体重写掉。本轮折叠同样以「读远端 → 变换 → PATCH → 回读 equal」四步执行，且权威证据仍先落仓库 docs。

## 2026-10-30 22:2x — lead（W6-113 round 9）— 基线从 per-class 推进到 per-method：堵住「同类内换方法」

- **侦察**：head `48d5feb7`、工作树干净、锁 held=0（round 8 的锁已 stale）；PR #39 Draft / MERGEABLE / UNSTABLE、body 122,381 字符（余量 69,763 B）；windows-ci `41055690826` success、Android CI `41055690984` success（同 head `48d5feb7`）。
- **缺口（P0，同一族再下一层）**：round 7 的 per-class 基线只看「每类用例数」。**同一个类里删掉一个方法、再加一个新方法**，total 与每个类计数都不变 ⇒ 判绿 —— 正是该门禁自述 *equal totals do not prove the same cases ran* 的再下一层。
- **修法**：基线每套件新增 `methods` 表（类名 → 方法名）。方法名取 TRX `TestDefinitions` 的 `TestMethod.className` + `name`，剥掉第一个 `(` 起的 Theory 数据参数后去重（数据行仍由 `classes` 的用例数管）；缺失方法 RED 并点名，新增方法 GREEN + `::warning`，配置缺失/空/重名一律 throw。刷新脚本同步重写 `methods` 表，且 **`_comment` 改由脚本拥有**（每次刷新归一化为脚本内置文本）—— 这一点在 NC24 里显式验证，避免「刷新脚本偷丢人写的注释」或反之被误判成数据变化。
- **数据源**：CI run `41055690826`（head `48d5feb7`）产物里的 `test-results/*.trx`（windows **2,719,155 B** / webdav **145,266 B**），不是本机重跑 —— 基线钉住的是同一 commit 的 CI 行为。解析实测：Windows `total=1952 / classes=113 / methods=1637`，WebDav `104 / 4 / 95`；TRX 的类集合与已提交 `classes` 表**完全一致**（无多出、无缺失）。方法数是把 Theory 数据行折叠成方法名后的**去重**结果，因此普遍小于用例数。
- **基线规模取舍**：加入完整方法名后基线 **7,659 B → 122,204 B**，`file` / `expected` / `classes` 三栏与 HEAD 逐字段比对**全部不变**，只有新增的 `methods`。用 100+ KB 换「点名缺失方法」的可诊断性与 review 里逐行可见的 diff。
- **负向对照 28/28（NC0–NC27）**，pwsh 7 与 Windows PowerShell 5.1 逐例判定一致：NC0 匹配 PASS；NC1 总数少 1 RED；NC2 总数相同类分布不同 RED；NC3 整类消失 RED；NC4 新类 GREEN+warning；NC5 类变大 GREEN+warning；NC6 failed RED；NC7 notExecuted RED；NC8–NC12 配置类 RED（缺/空 `classes`、`expected=0`、负数、类计数 0）；**NC13 同类内换方法（total 与每类计数全不变）RED**；**NC14 同一输入在 round 7 per-class 门禁下 GREEN**（证明 NC13 的红是本次收口所得）；NC15 方法跨类搬移 RED；NC16 只改 Theory 数据 PASS（明示残留限制）；NC17–NC20 新增配置校验 RED；NC21 失败消息点名 `MediaHub.Tests.AlphaTests.A_one`；NC22 刷新脚本拒绝下调且文件未变；NC23 `-AcceptRemoval` 后写入新方法集；NC24 刷新脚本二次运行逐字节稳定、`suites` 数据不变、`_comment` 归一化为脚本内置文本；NC25 由真实 CI TRX 重生成与提交版本逐字节一致；NC26 真实 1952+104 对已提交基线 PASS 且日志含 `methods=` 行与 `combined tests=2056`；NC27 默认相对路径冒烟 PASS。
- **三次跑出「假红」并逐条定性（如实记录）**：前两轮跑出的 2 处红都是**台账自身写错**，不是被测行为不对 —— ① NC14 的期望值写成 `GREEN` 而断言器只认 `PASS`，把「旧门禁确实放行」误报成失败；② NC24 断言「刷新前 = 刷新后逐字节」，而刷新脚本按设计会归一化 `_comment`，必然不等。修正断言后才 28/28。教训：**不要把「台账/工具的期望写错」和「被测行为不对」混为一类**，否则为了消红会去改本来正确的被测代码。
- **CI 触发的一个异常（如实记录）**：`48d5feb7..f045d34c` 于 22:26:58 推送成功后，`gh run list --commit f045d34c…` 连续约 6 分钟查不到任何 run，期间仓库整体 Actions 正常（另一写者 `13fad6b8` 的 push 事件 run 已在跑）、githubstatus 亦为 All Systems Operational。最终 `41072599036`（windows-ci）与 `41072599413`（Android CI）在 **14:33:25Z** 才以 `pull_request` 事件出现 ⇒ 判定为**事件投递延迟**，不是路径过滤把本次改动挡掉（改动的三个文件都在 `windows/**`，命中 `windows-ci.yml` 的 `paths`）。
- **CI 验收（EXECUTED_PASS）**：head `f045d34c` 的 windows-ci run `41072599036` completed/success（`pull_request` 事件，14:33:25Z 创建、14:35:57Z 台阶跑完）。日志逐行命中：`MediaHub.Tests: total=1952 baseline=1952 failed=0 notExecuted=0` / `classes=113 baseline=class table 113 entries` / **`methods=1637 baseline=method table 1637 names`** / `MediaHub.WebDav.Tests: total=104 ... classes=4 ... 4 entries ... methods=95 baseline=method table 95 names` / `combined tests=2056`；用例自身 `Passed! - Failed: 0, Passed: 1952` 与 `Passed: 104`。
- **迁移验证（EXECUTED，第 29 例）**：head `48d5feb7` 的旧格式基线（无 `methods` 表）跑新闸门 ⇒ pwsh 7 与 Windows PowerShell 5.1 **均 exit 1**，抛 `entry for MediaHub.Tests has no methods table - the per-method check would be vacuous` ⇒ 旧基线不会静默变绿，而是当场失败并指明缺哪张表。
- **本轮 Android CI 状态口径**：run `41072599413`（同 head `f045d34c`）入账时仍 `in_progress` ⇒ 按 precedent 记为「未作结论」，不写成 success。
- **更正（同上，实测推翻本小节初稿的一句话）**：初稿写「docs 提交不会再触发新 run」是**错的**。实测 docs-only 提交 `388cde7d` 触发了 windows-ci `41073668366` 与 Android CI `41073668337`（皆 `pull_request` 事件）。原因：`windows-ci.yml` 的 `paths` 过滤写在 `pull_request` 下（`paths: ["windows/**", ".github/workflows/windows-ci.yml"]`），GitHub 对 `pull_request` 的 path 过滤按**整个 PR 的改动集**（base…head）判定，而不是只看最后一次 push 的文件 ⇒ 只要 PR 里有 `windows/**` 改动，后续任何 push（哪怕只动 docs）都会再触发一次 windows-ci。教训：**触发面的事实必须实测，不能凭对 push 语义的类推断言。**
- **docs 提交的 CI 结果（EXECUTED，含跨组发现）**：run `41073668366`（head `388cde7d`）= **completed/failure**，唯一失败步骤是 **`Licence surface guard (notices / SBOM / project drift)`**；同一 run 里本轮的台阶行全绿（`total=1952 baseline=1952` / `classes=113 ... 113 entries` / `methods=1637 baseline=method table 1637 names` / WebDav `104 / 4 / 95` / `combined tests=2056`，用例 `Passed: 1952` 与 `104`）。
- **归因（不是本轮改动）**：base 分支 `feature/windows-client` 的 head `13fad6b8`（`feat(licensing): W32 SBOM 库存一致性门禁（组件/许可/来源提交三检）`，2026-10-30T22:32:57+08:00）**自己就红**：push 事件 run `41072548134` 与 pull_request 事件 run `41072562787` 都以同一步骤失败，日志同为 `check-licence-surface  checks=24 failed=2`。而本轮代码提交的 run `41072599036`（head `f045d34c`，14:33–14:36）跑的是 W32 之前的 base，licence 行是 `checks=21 failed=0 warnings=1` ⇒ **21→24 正是 W32 新加的三检**，两条 FAIL 由该提交引入，与本轮 docs 改动无因果关系。
- **两条 FAIL 的根因（EXECUTED + SOURCE_REVIEWED）**：① `sbom-inventory` FAIL —— SBOM `windows/docs/sbom-windows.cdx.json` 第 301 行仍记 `pkg:nuget/System.Reflection.Metadata@1.6.0`，但当前树里已没有 `.csproj` 再解析它（`git grep System.Reflection.Metadata origin/feature/windows-client` 只命中 `THIRD-PARTY-NOTICES.md` 与 SBOM 自身）⇒ 需按守卫提示重生成：`pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom`。② `sbom-source-revision` FAIL —— 守卫用 `git merge-base --is-ancestor 1c30471 HEAD`（`windows/tools/check-licence-surface.ps1` L294）判定 SBOM 记录的 `feature/windows-client@1c30471` 是否在线上；本机实测 `1c30471` **是** `388cde7d` 与 `origin/feature/windows-client` 的祖先，但 CI 的 `actions/checkout@v4` 未设 `fetch-depth`（默认 1、浅克隆），merge commit `76d807dfa8` 之外的祖先对象缺失 ⇒ 祖先无法证明而 FAIL。修法二选一：checkout 取全历史（`fetch-depth: 0`），或把「不可证」降级为 WARN 而不是 FAIL。
- **本轮不做该修复**：这是另一位写者刚落地（22:32）的新守卫面，且其修复会改 SBOM/NOTICES 大文件与其自有校验；按并行写者边界不越界改动，改为入账 `W6-113m`（P0 / BLOCKED）并写进 PR #39 正文与 handoff 账本，交回 W32 作者。
- **锁与工作树**：本轮锁 `w6-r9-h7q2m4` 已在收尾释放；工作树提交后干净。
