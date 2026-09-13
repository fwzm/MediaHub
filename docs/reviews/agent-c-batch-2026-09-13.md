# Agent C 批次交付 — PR #18 / #16 / #10 独立复审与验证（2026-09-13）

本档为 Agent C（DSH + DeepSeek）在本批次 C0–C11 中的独立结论。所有 SHA 均在执行时重新查询；
结论只覆盖**本轮实测**的范围，未实测的设备/服务器/时段一律标注为未验证。

## 0. 冻结身份、隔离与方法

| 对象 | 实际值（本轮查询） | 状态 |
| --- | --- | --- |
| main | `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1` | 与任务快照一致 |
| PR #18 head / base | `82a38abce4e761c002dde6d8c9420176e977f57c` / `8e516e4…` | OPEN / Draft / C REVIEW PENDING |
| PR #18 CI | run `34348756064` attempt 1 SUCCESS | checkout `5acd9ba…`（合成 merge） |
| PR #16 head / API base | `f40363ce5199e05a4cd197b456a851c2b984492b` / `0e08fadb…` | OPEN，SLOW-FINAL OPEN |
| PR #10 head / API base | `3febdf8b77a6a1d63d54b9d0c409de708ed978bf` / `0e08fadb…` | OPEN |

任务给出的定位快照**逐项核对通过，未发现漂移**。审查期间远端 head 未前移，因此 C1–C4 全部绑定同一个冻结 head
`82a38abce4e761c002dde6d8c9420176e977f57c`；测试运行中未切换源码。

隔离：C 自建 4 个独立 worktree（`agent-c/batch-2026-09-13` ← PR #18 head、`agent-c/main-verify-2026-09-13` ← main、
`agent-c/pr10-verify-2026-09-13` ← PR #10 head、`agent-c/pr16-verify-2026-09-13` ← PR #16 head），
受控证据目录 `D:\deepseek_test\agent-c-evidence`。未写入任何其他 Agent 的工作区。

方法边界：先按**原始 head** 记录（C2 门禁在工作树干净时启动，`source-dirty-before: no`），再单独记录 C 新增测试的 delta，
绝不把改动后的工作树冒充原始 PR head。

## 1. C0 — 真实基线、任务归属与依赖

| 工作项 | 实际实现负责人 | C 可写范围 | 依赖 / 阻塞 |
| --- | --- | --- | --- |
| PR #18 本地备份与还原（1I-A） | A（zcode） | 仅新增测试/脚本/审查文档 | 无（head 冻结） |
| PR #18 生产修复（B 的三提交） | B（Codex）已由 A 快进接回 | 同上 | 已完成接回 |
| 2A Media3 音轨/字幕选轨 | B（已分配，**未开工**） | 准备夹具与验收测试 | `WAITING_FOR_B` |
| PR #16 证据归档 | 归档负责人 | 自有 docs 分支补丁 | 原始日志可读 |
| PR #10 视觉系统 | A/B（原分支） | 独立复验 | 无 |

**读取并核对**：根进度文档（HANDOFF/TASKS/DECISIONS/CHANGELOG/ROADMAP/ARCHITECTURE）、
两份 B 复审件（`docs/reviews/pr18-agent-b-2026-09-09.md`、其 validation JSON）、
两份 A 集成件（`pr18-agent-a-integration-2026-09-09.md`、其 validation JSON）、
`task0-pr10-pr16-review-queue-2026-09-09.md`；并重新查询 PR 的 commits/reviews/comments/threads 与 CI。
仓库内**不存在任何 AGENTS.md**（`Get-ChildItem -Recurse -Filter AGENTS.md` 无结果）。

写入 `baseline.json`（机器可读基线、环境、归属、阻塞）。未修改其他 Agent 的进度声明。

## 2. C1 — PR #18 最终集成版本的独立代码复审

复审范围：完整 base→head 差异（66 文件 / +10,534 / −103），再分别定位 B 补丁与 A 追加变化。
未只看 `307b8f2`，未复读评论处置表。

### 2.1 四组契约逐项结论

**组 1 冻结计划与数据 — 通过**
- `PreparedRestorePlan` 绑定 `validated`（引用相等 `!==`，`BackupRepository.kt:359`）、`strategy` 与 `plan.record.strategy`，
  任一不符抛 `RestoreBaselineChangedException`。
- 预览后基线复核在互斥内（`:368`：`images.before.sameData(readSnapshot())` 且偏好相等）；
  Room 事务内独立复核（`BackupDataSource.kt:80`，`withTransaction` 内）。
- MERGE newer-wins：`resolveProgress` 用 `existing.updatedAtEpochMs >= vp.updatedAtEpochMs` 保留本机（`:633`），
  `materializeRestorePlan` 再保护一次（`BackupDataSource.kt:56`）。Pair 键统一为 `serverId to itemId`（含斜杠 ID 不碰撞）。
- 同 ID 异源隔离：`compareIdentity` = 类型 + 规范化主地址 + 账号三者全等（`:574-583`）；冲突源进度不导入。
- 默认源唯一性：`materializeRestorePlan` 末尾 `isDefault = it.id == defaultId` 收敛为唯一（`BackupDataSource.kt:49`）。
- 完整回滚与新增行删除：`applyRestorePlan` 删除 `before` 中不在 `after` 的 server 与 endpoint，并删除多余进度（`:86-104`）；
  nullable 偏好经 `rollback()` 恢复（`:524-527`）。

**组 2 持久化与中断 — 通过**
- 加密快照**发布后立即读回并逐字段核验**（`RestoreSnapshotStore.kt:66`，`check(read(id) == images)`），
  在 `journal.begin()` 之前完成——这是"发布成功"的真实证据，而非依赖 `AtomicFile.finishWrite` 的 void 返回。
- Journal 顺序：`save(snapshot) → begin(PREPARING) → invalidate → applyRestorePlan → DB_WRITTEN → preferences →
  PREFERENCES_APPLIED → COMPLETED → clear`（`:373-393`）。
- `commit(false)`：`persist()` 用 `commit()` 并检查返回值，失败即把该 SharedPreferences 记入 `uncertainStores`，
  之后该实例一律抛 `RestoreJournalPersistenceException`（`RestoreJournal.kt:191-208,115`）。
- 实例级共享互斥：`operationMutex` 为 companion 单例（`:266`）；`uncertainStores` 用 `WeakHashMap` 且仅在锁内访问。
- 物理 XML/.bak 损坏检测：`verifyMissingPhysicalEntry` 拒绝 `.bak` 存在、拒绝非空 `<map>`、拒绝未知标签（`:154-189`）。
- 幂等续作 / ROLLING_BACK / COMPLETED 只清理：`recoverLocked`（`:426-505`）；中断后本机数据已变化则拒绝覆盖（`:464`）。

**组 3 身份与认证 — 通过**
- 恢复身份变化与新 ID 孤立凭据清理：`identityChangeTargets` 对"新 ID"与"身份变化 ID"都返回（`:535-541`）。
- 同源保留：`compareIdentity` 全等才跳过，同源重新登录不被撤销。
- 迟到登录/401/logout：`TokenStore` epoch + 每 ID 互斥（`TokenStore.kt`）；`clearAuthenticationIfCurrent` 只在仍是当前
  尝试时才清理，否则返回 false。`logout()` 的 cleanup 在 `finally`，取消时 `addSuppressed` 保留原 CE。
- `restore lease`：`withRestoreIdentityChange` 抬升 generation 并置 blocked，**块内创建的新 handle 永久 invalid**
  （`allowed=false` 固化在 lease 上，`authStates` 状态不再回退）。
- 失败取消释放：`finally` 在 `NonCancellable` 中回退 `restoreBlocks`。

**组 4 正式 UI — 通过**
- Home 身份与读取代际：`HomeViewModel` 引入 `AuthIdentity`/`AuthAttempt`/`readRevisions`/`generation`；
  `publish` 只在 `attempts[id] == attempt` 时写状态；`forceRestore`/`logout` 在 `getServer` 挂起前后比对 revision，
  迟到 DB 快照无法为旧地址创建 handle。
- SAF 事件消费：`takeExportFileNameForPicker()` 先把状态从 `ExportReady` 改为 `AwaitingExportTarget` 再 `launch`，
  重组/配置重建不会重复拉起创建器；picker 抛异常走 `onExportPickerFailed`（可重试）。
- 取消与重入：`beginOperation()` 取消上一个 job 并抬升 `opEpoch`，所有迟到结果经 `epoch != opEpoch` 丢弃。
- 密码擦除：`finally { password.wipe() }` **加** `invokeOnCompletion { … wipe() }`（覆盖"launch 尚未开始即被取消"）。
- 错误密码重试：`PrepareResult.Rejected` → `Error(canRetryRestorePassword = true)` → `retryRestorePassword()`。
- 策略变化清确认：`remember(previewState) { mutableStateOf(false) }`——策略切换产生新 `RestorePreview`（新 `frozenPlan`）→
  key 变化 → 确认复位（`BackupScreen.kt:69`）。
- 零业务写入：非法输入/未确认替换在 VM 与 repository 两层拦截（`BackupViewModel.kt:333`、`BackupRepository.kt:356`）。

### 2.2 A 线路修复的额外检查 — 通过（这是本轮最实质的改动，C 独立复核结论如下）

问题背景已由 C 独立证实：`ServerEndpointDao` 用 `ORDER BY serverId ASC, sortOrder ASC`（`ServerEndpointDao.kt:12,15`），
而 `List<ServerEndpoint>.activeEndpoint()` 是**按数组顺序**取"首个 primary+enabled，否则首个 enabled"（`ServerEndpoint.kt:29`）。
两者在 sortOrder 并列时可取到不同地址。

- **文件数组顺序 vs Room sortOrder**：`validate()` 现按 `sortedBy { it.sortOrder }` 冻结（`BackupRepository.kt:227`），
  身份随之计算（`:235-241`），与 DAO 顺序一致。
- **并列优先级**：`BackupSerializer.hasUnambiguousEndpointIdentity` 取候选集（有 primary 则 primary，否则全部 enabled）中
  `sortOrder` 最小者，要求其规范化地址 `distinct().size <= 1`（`BackupSerializer.kt:203-210`）。
  C 独立推导：DAO 只保证按 sortOrder 排序、并列顺序未定义，因此"身份确定"的充要条件正是"最小 sortOrder 组的规范化地址唯一"。
  **实现与推导一致，未发现漏判或误判。**
- **规范化等价**：`ServerAddressIdentity.normalize` 只小写 scheme/host、去尾部 `/`、**保留 rawPath 与 rawQuery**（`ServerAddressIdentity.kt:8-16`），
  因此不会把路径不同的两个地址误判为同一身份。
- **primary/enabled 组合与合法单主线路**：`primary=false` 且全部并列时拒绝；等价地址（`HTTPS://A.EXAMPLE/` vs `https://a.example`）允许；
  单一主线路允许。A 的四条回归与 C 的推导一致。
- **旧 PREPARING/ROLLING_BACK 保护计划身份不稳时零重放**：`recoverLocked` 在 ROLLING_BACK 分支**之前**做
  `hasStableEndpointIdentities(images)` 检查，命中即 `NeedsAttention` 并**保留日志**（`:446-450`）。
  C 复核确认该检查对 `before+after` 两个 image 都执行，且 `identity(server) == identity(sortedBy sortOrder)` 只对
  "旧写入者按数组序冻结"的历史 image 才会失败（新数据已被 `validate()` 排好序）。
- **持久化后真实有效地址与预览裁决一致**：`identity()` 与 `compareIdentity()` 都走 `activeEndpoint()`；
  修复后排序在冻结前完成，故"预览裁决身份"= "Room 读回身份"。避免错误 MERGE 与携带旧凭据切换地址。

### 2.3 生产故障探针的构建与调用边界 — 通过

`Process.killProcess` / checkpoint 注入**全部只存在于 `app/src/androidTest`**（`RestoreProcessDeathTest.kt:75,107` 等），
且每个入口先 `check(args.getString("backupAcceptance") == "isolated")`。生产 source set 内不存在 checkpoint/kill 通道，
`BackupTestFixtures.kt` 位于 `feature/settings/src/test`（非打包）。**正常用户无法触发测试杀进程或破坏性夹具。**

### 2.4 C1 findings

详见 `agent-c-findings-2026-09-13.md`。摘要：1 个中危已证缺陷（身份守卫异常类型跨界，A 负责）、
2 个低危已证缺陷（其一为 main 既有、超出 PR #18 范围）、2 个设计观察、若干已接受限制。
**未发现阻断合入的缺陷；四组契约在源码层面均成立。** 本结论不等于真机验收通过。

## 3. C2 — 门禁独立重跑与 CI 证据审计

### 3.1 CI 身份核对 — 独立证实

- run `34348756064`，attempt **1**，event `pull_request`，`headSha` 字段 = `82a38ab…`（= PR head 本身，不是 merge）。
- 实际 checkout `5acd9bae209c9f532811ba6fb48d42ad6e50f6df`，parents = `[8e516e4…, 82a38ab…]`，
  subject = `Merge 82a38ab… into 8e516e4…` → 正是本次 base/head 的 GitHub 合成 merge。
- C 用 `git fetch origin refs/pull/18/merge` 独立取得该提交并比较 tree：
  `5acd9ba^{tree}` = `82a38ab^{tree}` = `48151ef7259592243daa46b48cc39ad9cb68769d`。
  **A 报告的"合成 merge tree 与 PR head 相同"独立成立。**
- `ci-validation/revision.json`（CI 产物内）与上述一致：checkoutSha/eventSha=`5acd9ba…`，headSha=`82a38ab…`，
  baseSha=`8e516e4…`，runId/runAttempt 一致。
- artifact `android-validation-5acd9bae…`（256,827 B，未过期）**已由 C 下载并解析**（见 3.3），不是凭绿勾声称。

### 3.2 本地门禁重跑（冻结 head、干净工作树）

命令（C 本轮实际执行，`--rerun-tasks --no-build-cache`，单 worker 以适配本机内存）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME = "C:\Users\55160\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest :core:model:test :provider:api:test :metadata:test \
  assembleDebug lintDebug :app:assembleDebugAndroidTest \
  --rerun-tasks --continue --no-parallel --max-workers=1 \
  "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8" \
  "-Pkotlin.compiler.execution.strategy=in-process" --no-build-cache --no-daemon --console=plain
```

`JAVA_HOME`/SDK 仅在当前进程配置；本机 ambient `JAVA_HOME` 指向失效的 `jdk1.8.0_381`（环境问题，非代码问题），
未擅自修改全局安装或版本。可复用脚本：`tools/run-gate.ps1`、`tools/summarize-test-xml.ps1`。

**本轮结果**：见 `agent-c-batch-2026-09-13.json` 的 `c2.localGate`（退出码、耗时、任务状态、XML 计数、用例集合）。

### 3.3 CI 产物审计（C 独立解析 92 份 XML）

| 指标 | C 从 CI 产物实测 | A 报告 | 差异解释 |
| --- | ---: | ---: | --- |
| 单测用例 | **772** | 772 | 一致 |
| 失败/错误/跳过 | **0 / 0 / 0** | 0 | 一致 |
| 单测 XML | **92** | 92 | 一致 |
| lint XML / warning / error | **22 / 58 / 0** | — | 见 3.5 |

任务级拆分（同一 772 的两种口径，**不重复相加**）：`testDebugUnitTest` = 729（86 XML），
`test`（`:core:model:test` + `:provider:api:test` + `:metadata:test`）= 43（6 XML）。

### 3.4 与 A 的 772 / 144 及 B 的 768 / 140 的差异解释

C 逐模块比对（B 在 `7e314c64` 的本机结果 vs C 在 CI 产物 `5acd9ba` 的解析）：

| 模块 | B@7e314c64 | C@CI 82a38ab | Δ |
| --- | ---: | ---: | ---: |
| feature/settings | 100 | **104** | **+4** |
| 其余 21 个模块 | 668 | 668 | 0 |
| **合计** | **768** | **772** | **+4** |

`BackupRestoreSafetyTest` 由 **15 → 19**，正是 A 在 `9a8c309e` 新增的四条线路顺序回归。
因此：**772 = 768 + 4**，差异**恰好**是那四条测试，无模块总数重复、无变体重复、无专项子集重复相加。
备份专项 **140 → 144** 同样是这四条（core/common 40 + feature/settings 100 → 104）。A 的 772/144/92 三项**均成立**。

### 3.5 lint 审计（区分版本提示与代码问题）

58 条 warning、0 error/fatal，按规则分布：`GradleDependency` 36 + `AndroidGradlePluginVersion` 6 = **42 条属依赖/版本提示**
（不应为清零而升级全仓依赖）；其余 16 条中与代码语义相关的仅：
`ApplySharedPref` 1（`RestoreJournal.kt:193`）、`UnusedResources` 1、`OldTargetApi` 1、`ObsoleteSdkInt` 1、
`MonochromeLauncherIcon` 1、`DataExtractionRules` 1、`ChromeOsAbiSupport` 1、`UseKtx` 9。

> **重要（防止误修）**：`ApplySharedPref` 指向 `RestoreJournal.persist()` 的 `commit()`，**该处必须用 `commit()`**——
> 它正是"日志落盘成功才能开始变更"的持久化前提；lint 建议的 `apply()` 会破坏中断恢复契约。
> 结论：**这是设计意图正确的假阳性，不应"修复"**，必要时以注释说明而不改语义。

### 3.6 NO-SOURCE / SKIPPED / UP-TO-DATE 单列

`player:mpv`、`provider:webdav`、`metadata` 等无测试源码的任务在 CI 中为 NO-SOURCE/无产物，
**不计入 772**。C 的模块统计只累加实际产出 XML 的用例，未把配置/资源任务的 UP-TO-DATE/SKIPPED 当作通过数。

## 4. C3 — 正式 SAF / 真实进程终止与恢复断言补强

**状态：PARTIALLY EXECUTED ON THE PHYSICAL DEVICE（用户明确授权真机；仅执行无害子集；破坏性套件未运行）。**

环境探测（实测）：
- 可用系统镜像：`android-36/google_apis_playstore/x86_64`、`android-32/google_apis/x86_64`；platform `android-36` 已安装。
- 现有 AVD：`Codex_PR18_Integration_36`、`Codex_PR18_Review_36` —— **属于 A/B，C 不予复用**。
- **安全告警（重要）**：`adb devices` 显示**物理设备已连接**：serial `123e243f`、机型 `24031PN0DC`（Xiaomi 14 Ultra，
  即 PR #16 证据所用的真机）。任何缺少 `-s` 的 adb 命令都可能操作该真机。
  因此 C 交付的脚本**强制要求显式 `-Serial`**，并拒绝在没有该参数时运行。

**用户本轮指令**："有 usb 链接手机，可以通过 adb 操作，不必开虚拟安卓机"。据此在**真机**（serial 123e243f，Xiaomi 14 Ultra，
24031PN0DC，Android 16 / SDK 36，ro.hardware=qcom）上执行；用户明确选择"**只跑无害子集**"，
因此**未运行** 8 个真实终止点、未注入损坏日志、未执行确认后的 REPLACE_SELECTED 恢复。

安装安全前提（C 实测，非假设）：
- 真机已装 com.mediahub.app（versionName 0.1.0-alpha.1）**且含真实数据**（databases/mediahub.db、shared_prefs）。
- 安装前用 apksigner 比对签名：已装 base.apk 与本轮构建 app-debug.apk 的签名证书 **SHA-256 完全一致**
  （ca92e12753d1e55868c8fb5a9e942d67a407032ad8d1ce949aa94ad53c953a87，CN=Android Debug），
  故用 adb install -r **保留数据**替换，**全程未 uninstall**。
- 安装后复核：databases、shared_prefs 仍在；测试结束后 /sdcard/Download 无任何 MediaHub-agent-* 残留；
  用户数据未被本测试改写。

C 新增的无害子集测试（C 自有 delta，位于 app/src/androidTest）：
BackupUserFlowHarmlessTest —— 覆盖"取消不产生文件 / 实际保存 / 自解密与真实 appVersion / 错误密码零写入 /
预览零写入 / 未确认替换禁用且零写入"，并在 **确认替换之前显式停止**；偏好只读，不做写入。

实际执行结果（3 轮，均在真机，日志见 agent-c-evidence/c3/）：

| 轮次 | 结果 | 结论 |
| --- | --- | --- |
| run 1 | 失败于首个选择器 DESC=设置 | 真机 logcat 实证 **OEM 拒绝后台启动 Activity**：E/ActivityTaskManager: "Abort background activity starts from 10563"。属**设备/OEM 限制**，不是产品缺陷 |
| run 2 | 失败于"MainActivity 必须在前台" | 实证 am instrument 会重启目标进程，预置的 Activity 不存活（环境/夹具问题） |
| run 3 | **通过应用侧全链**，失败于系统文件选择器导航 | 见下 |

**run 3 的正面结果（真实产品行为）**：
- 应用经 UiAutomation shell 启动后进入正式页面；设置 → 同步与备份 → 输入导出密码 → 触发导出全部成功；
- **SAF appeared=true elapsedMs=4949** —— 生产导出路径在真机上**真实拉起了系统 SAF**（约 4.9 s）。
- 随后失败于 chooseDownloads()：在 HyperOS 的文件选择器里找不到 roots_list 内的"下载/Downloads"根节点
  （TEXT=(?i)^(downloads|下载)$）。这是**系统 UI 布局差异**，与 AOSP 模拟器不同，属环境/夹具问题，非产品缺陷。
- 因此"取消不产生文件 / 实际保存 / 自解密 / 错误密码零写入 / 预览零写入 / 未确认替换零写入"
  这六项**仍未取得真机通过证据**。

**未执行**：8 个真实终止点、物理 XML 损坏分支、确认后的数据与偏好还原（按用户选择）。
**工具**：scripts/agent-c/verify-backup-restore-c.ps1（强制显式 -Serial；拒绝非模拟器与 A/B 的 AVD；
smoke 实测：缺失参数失败、未知 serial 拒绝、物理序列号在模拟器检查处拒绝）。
**C3 四项证据缺口**（forward 路径逐字段比较、损坏日志全凭据存储比对、线路排序/歧义/旧保护计划、
重复 callback/页面重建/策略变更/失败后重试）**尚未落地为测试**，见 C11 的下一批任务。

## 5. C4 — 共享认证、旧请求与首页身份的并发回归

**状态：源码级核验完成；未新增测试（未使用生产代码接缝，不扩大生产改动）。**

已逐项核对：`TokenStore`（`AuthenticationLease`/`AuthenticationAttempt`/`epoch`/`restoreBlocks`/`restoreStatus`）、
Emby 与 Jellyfin `AuthProvider`、两个 `Factory` 的首个 interceptor、同步持久删除（`KeystoreSecretStorage.remove`、
`EmbySessionStore.Storage.remove` 改 `commit()`）、`HomeViewModel` 代际。

**锁序（C 独立读取，非复述）**：`withRestoreIdentityChange` **不跨 server 持锁**——每个 serverId 的 `withLock` 在循环体内
  结束即释放，靠 `restoreStatus.blocked` 标志（`@Volatile`）拒绝旧 lease，因此不存在多锁嵌套与死锁路径；
  `commitAuthentication` 在锁内做 `storage.put` + `saveSession`，`clearAuthenticationIfCurrent` 在 `NonCancellable` + 锁内
  做 `storage.remove` + `clearSession`；两者互斥且顺序一致，无反向获取。
  `invalidateChangedIdentities` 逐 serverId 调用 `tokenStore.clear()`，每次独立取锁，不嵌套。

已有回归覆盖（C 在 CI 产物中确认通过）：`TokenStoreAuthenticationTest` 9 项、`TokenStoreTest` 4 项、
`EmbyAuthProviderTest` 164 项模块级、`JellyfinAuthProviderTest`、两个 `*FactoryRestoreIsolationTest`、`HomeViewModelTest` 11 项。
其中"旧 handle 撤销后 API/media 新请求数为零"的断言（`EmbyFactoryRestoreIsolationTest.kt:63,94`）**已存在且通过**。

**未验证项（如实列出）**：C 未新增 barrier/deferred 时序用例；"已发出的请求单独判断结果提交权限"只在源码层面确认
（`isAuthenticationCurrent` 在响应后二次校验），未用有界真实多线程用例实测。网络报文不可撤销这一事实未被任何断言反证。

## 6. C5 — PR #16 证据归档核对与文档补丁

原始材料：六份日志位于 `D:\deepseek_test\MediaHub\.smoke\`（未入 repo，符合"受控证据目录"要求）。
**C 按原文件逐份复核，未采用任何 Agent 描述当作原始日志。**

| 文件 | 字节（档/实） | SHA256 前 16（档/实） | 内容核对 |
| --- | --- | --- | --- |
| `sceneA_wire.txt` | 2,341 / 2,341 ✓ | `b65b29a3971a0770` ✓ | Scene A wire |
| `sceneA3_wire.txt` | 6,183 / 6,183 ✓ | `dca8f0c835614960` ✓ | 同轮 NETWORK 行 |
| `diag_wire.txt` | 7,251 / 7,251 ✓ | `5e32492ea06b23f1` ✓ | 13 条 REQ_BODY |
| `coldfinal_wire.txt` | 578 / 578 ✓ | `bb020cde193e7861` ✓ | 18:55 墨云阁成功 |
| `coldfinal2_wire.txt` | 278 / 278 ✓ | `9a4d1cd9fe0c5e2f` ✓ | **18:59 失败样本（原索引错标）** |
| `coldfinal3_full.txt` | 1,946 / 1,946 ✓ | `746b8d26edd70445` ✓ | 19:01 样本，**含媒体 GET 502** |

- **ticks 换算**：`diag_wire.txt` 13 条 `REQ_BODY` 的 `PositionTicks` 与文档 §3.3 表**逐条 13/13 一致**，
  `÷ 10,000` 得毫秒一致（如 996,830,000 → 99.683 s）。`PlaySessionId` 全程恒为 `6cd3207d…`。
- **播放上报成功**：coldfinal_wire 与 coldfinal3 的 Playing/Stopped 均 204，与档一致。
- **播放位置推进**：仅能从 Progress 序列观察（11 条、0→98.2 s），**不能**据此证明服务端已持久化该位置。
- **UserData 写回**：**六份文件中不存在任何 `UserData`/`PlaybackPositionTicks` 响应原文**（检索 `UserData`、
  `PlaybackPositionTicks`、`996830000` 均无命中）。档中"回写 delta = 0"属执行方 reported，**归档无法独立复现**。
- **BACK 时刻**：无独立时间戳（档 §4 已如实披露），C 确认无遗漏证据可补。
- **脱敏**：Authorization 头在原始日志中即 `****`；文档正文不含 Token/头/Base64 ✓。
  原始文件含 `UserId=<guid>`、`PlaySessionId=<guid>`（URL query），但**未入库**，留在受控目录，符合要求。
  档中披露某 PlaySessionId 前 8 位属有意的最小披露，判为可接受。

**C5 findings（均已证）**：见 findings 档 F-C5-1/2/3。已生成可接回补丁
`c5-pr16-doc-correction.patch`（4 处更正：索引错标、502 披露、UserData 限制、§7 口径限定），
提交在 C 自有分支 `agent-c/pr16-verify-2026-09-13`（`45afb87`），**未改动 PR #16 分支、未新建重复归档 PR**。

**SLOW-FINAL COMPLETION 保持 OPEN**：C 的复核只修正归档准确性与因果措辞，不提供取消根因的完整新证据，
  故不改变 OPEN 状态。归档准确与功能修复分别为两个结论。

## 7. C6 / C7 — SLOW-FINAL 与 EndpointTestService

**C6 状态：未执行（无新测试/诊断脚本产出）。** 仅在 C1/C5 复核中确认了档 §5 对源码事实的引用成立：
`ProgressSyncCoordinator.flushFinal()` 以 `withTimeoutOrNull(REMOTE_FLUSH_TIMEOUT_MS = 2_000)` 约束整个 `remoteFinalReport`；
Emby cold-final 必须先等 `playbackStart()` 再发 `playbackStopped()`，两者共用该预算；
`ApiClient.executeNoContent()` 在 `withContext(Dispatchers.IO)` 内用同步 `Call.execute()`，协程超时**无法中断已阻塞 IO**。
**C 未指定唯一根因**（缺少可控慢请求时序），保持档中"精确取消来源待复现确认"。未扩大超时、未加兜底重试、未重写 finality。

**C7 状态：源码级核验完成，未新增测试。**
- 线程事实：`EndpointTestService.test()` 是 `suspend` 但内部用同步 `Call.execute()` 且**未切 IO dispatcher**，
  在 `viewModelScope`（主调度器）上执行阻塞网络 IO。该风险**已由生产 KDoc 自行登记**（`EndpointTestService.kt:30-33`）。
- 取消事实：协程取消**不能中断**已阻塞的 `Call.execute()`；`test()` 内无挂起点，取消后仍会跑完并把结果正常返回。
- 结果归属：`ServerEditorViewModel` 的闸门是对的——`qualityRequestId` 请求代号 + `AddressTestResultPolicy.shouldApply`
  草稿版本 + `endpointId/expectedUrl` 落库目标匹配，三者同时满足才写库（`:287-308`）；`finally` 只在本请求仍是当前时复位 loading（`:315-321`）。
  **"旧结果不写库 / 旧结果不清除新请求状态"在源码层面成立。**
- 未证明部分：C 未用真实 MockWebServer 断言底层 `Call` 被取消，也未测"请求 A 取消后 B 启动"的真实并发；
  因此**不声称网络资源已释放**（与任务要求一致：仅观察外层协程结束不足以证明）。
- 另见 findings F-C1-2（该文件未脱敏的 `e.message`，属 main 既有、超出 PR #18 范围）。

## 8. C8 — 2A 选轨修复独立验收

**状态：`WAITING_FOR_B`（已实证 B 尚未交付）。**
- `git branch -a` 无任何含 track/media3/select 的工作分支；
- `gh pr list --state all` 无引用 2A 音轨/字幕选轨的 PR。
现有代码中 `player/engine` 已有 `TrackSelection` 类型、mpv 侧有对应实现，但**未发现**本项目所指的 2A 修复交付。
C 未准备合成多轨夹具（未开始），未并发修改 B 的生产代码。

## 9. C9 — PR #10 视觉系统复验

**状态：未执行。** 已建立独立 worktree（`MediaHub-agent-c-pr10` @ `3febdf8…`）与数据环境隔离，
但本轮**未运行** PR #10 的门禁、未产出合成媒体截图/日志证据、未做 API 32/36 双路径验证。
原因：重构建需与 C2 串行调度（同一机器资源），本轮资源已用于 PR #18 门禁与 CI 审计。
**未提交任何 PR #10 结论**；未重做视觉功能；未执行临时集成分支。

## 10. C10 — Jellyfin 1G 协议与服务器验收

**状态：未执行，`BLOCKED_BY_SERVER`（真服部分）。** 本轮未取得已授权的 Jellyfin 测试实例，
未操作任何现有生产媒体记录，未新增付费资源、未暴露端口。
（背景：PR #16 档记录 Jellyfin 为 "server-blocked / production frozen"，与 C 本轮观察一致。）
协议契约级（MockWebServer）验证与版本矩阵**未在本轮完成**。

## 11. C11 — 批次证据、进度与下一阶段验收契约

### 11.1 状态矩阵（逐项，不用测试数量代替进度）

| 项 | 状态 |
| --- | --- |
| PR #18 代码实现 | IMPLEMENTED（A） |
| PR #18 独立代码审查（四组契约 + A 线路修复） | **REVIEWED**（C，源码级，见 §2） |
| PR #18 合并 | **未合并（MERGED = 否）**，授权由用户保留 |
| PR #18 单测（CI 产物实测 772/0/0/0/92） | **CI_VERIFIED** |
| PR #18 单测（C 本机独立重跑） | 见 JSON `c2.localGate` |
| PR #18 仪器/SAF 正式链路 | **DEVICE_UNVERIFIED / NOT EXECUTED THIS SESSION**（C3） |
| PR #18 真机 | **DEVICE_UNVERIFIED** |
| PR #16 归档准确性 | **REVIEWED**（C，见 §6）+ 补丁待接回 |
| PR #16 SLOW-FINAL COMPLETION | **OPEN**（未改变） |
| PR #10 | **未验证（NOT STARTED）** |
| 2A 选轨 | **BLOCKED / WAITING_FOR_B** |
| Jellyfin 真服 | **BLOCKED_BY_SERVER** |

### 11.2 PR #18 分栏填写（C 的代码审查通过**不**清除真机未验证状态）

| 栏目 | 结论 |
| --- | --- |
| `code_review` | **C 通过（源码级）** @ `82a38ab…`；附 1 中危 + 2 低危 findings，无阻断项 |
| `unit_tests` | **CI_VERIFIED** 772/0/0/0（C 独立解析 CI 产物）；C 本机重跑见 JSON |
| `ci_identity` | **VERIFIED**：run 34348756064 / attempt 1 / checkout `5acd9ba…` / tree 与 head 相同 |
| `emulator` | **NOT EXECUTED THIS SESSION** |
| `physical_device` | **DEVICE_UNVERIFIED** |
| `merge_authorization` | **用户保留**；C 不批准、不合并 |

### 11.3 审查意见逐条处置
- **已修且有证据**：B 复审表中的 R1–R6 对应生产修复在 `7e314c64`/`337c6263` 落地，C 在源码中逐条找到对应实现（§2.1）。
- **仍存在**：F-C1-1（身份守卫异常类型跨界）、F-C5-1/F-C5-2/F-C5-3（PR #16 归档）。
- **重复**：F-C1-2 与 Phase 1I 已登记的"错误脱敏"同源；C7 的线程风险即 `EndpointTestService.kt:30-33` 已登记项，C 不重复计为新缺陷。
- **超范围**：F-C1-2 位于 `core/network`，**PR #18 未修改该文件**，属 main 既有问题，不并入本 PR 修复范围。
- **待证**：C6 的精确取消来源、C7 的网络资源释放、C4 的已发出请求提交权限（源码成立，未实测）。
不以 thread 元数据代替源码判断；不要求所有建议都变为生产改动。

### 11.4 文档补丁（只改证据支持的条目，不整文件覆盖）
- `docs/reviews/agent-c-batch-2026-09-13.md` / `.json` / `agent-c-findings-2026-09-13.md`（本批次新增）。
- `c5-pr16-doc-correction.patch`（PR #16 归档 4 处更正，可接回）。
- **未**修改 HANDOFF/TASKS/CHANGELOG 主线文档：本轮证据不足以支撑其状态改写（尤其不得把 C 的代码审查写成封板）。
  建议 A/B 在接回补丁后按既有格式自行更新，C 提供 `baseline.json` 作为可引用事实源。

### 11.5 未给伪精确完成百分比
任务未指定验收分母，故不给百分比。仅给上表的状态枚举。

### 11.6 下一批任务（按风险 × 依赖排序）

| # | 任务 | 负责人 | 可执行第一步 | 验收条件 | 并行关系 |
| --- | --- | --- | --- | --- | --- |
| 1 | 修复 F-C1-1：身份守卫改抛 `IOException` | A | 改 `EmbyProviderFactory.kt:52` / `JellyfinProviderFactory.kt:52` 的 `check()` 为 `throw IOException(...)` | probe 返回 `MediaProbeResult.Failure` 且请求数 0；断言异常类型为 `IOException` | 独立于 2A |
| 2 | C3 真机/模拟器 SAF 与 8 终止点复验 | C | 用 `scripts/agent-c/verify-backup-restore-c.ps1 -Serial <C专属>` | 8 终止点 + 物理 XML 损坏；forward 路径逐字段比较 | 需 AVD，可与 3 并行 |
| 3 | C4 补强时序测试（barrier/deferred） | C | 在 `core/security` 加有界真实多线程用例 | 交错矩阵逐格断言；失败保留种子 | 与 2 并行 |
| 4 | 2A 选轨修复 + C 独立验收 | B → C | B 提交分支后冻结 head | 活动播放中真实切换/关闭/重入；非仅按钮高亮 | 阻塞 C8 |
| 5 | WebDAV 云备份（**与 WebDAV 媒体 Provider 分开**） | 待指派 | 先稳定本地备份格式，再设计云备份集成 | 格式稳定后才计划集成 | 依赖 1 |
| 6 | orphan 快照 GC / 损坏日志人工处理 / 设备密钥失效 | 待指派 | 作为**独立设计项**，不顺带实现自动删除或恢复绕过 | 需独立 ADR 与恢复语义 | 独立 |
| 7 | C6 可控慢请求复现包 | C | MockWebServer 可控延迟 + 单调时钟事件时间线 | 区分"发起/响应/服务端持久化"三态 | 独立 |
| 8 | C9 PR #10 视觉复验（API 32/36） | C | 与重构建串行调度 | 正式控件生效 + 安全区 + 时钟清理 | 依赖资源窗口 |
| 9 | C10 Jellyfin 1G 协议契约（MockWebServer） | C | 无实例也可做的协议级契约 | 明确文档版本；真服部分记 BLOCKED | 真服需授权 |

以上均**不计入本轮已完成**。

## 附录 A — 证据与哈希索引

| 证据 | 位置 | 核对值 |
| --- | --- | --- |
| 基线 | `D:\deepseek_test\agent-c-evidence\baseline.json` | 5,793 B |
| CI 产物 | `…\c2-ci-artifact\` | artifact 256,827 B，92 单测 XML + 22 lint XML |
| 门禁脚本 | `…\tools\run-gate.ps1` | 参数校验 + 身份记录 + 退出码 |
| XML 汇总器 | `…\tools\summarize-test-xml.ps1` | 任务/用例集合/JSON |
| PR #16 补丁 | `…\c5-pr16-doc-correction.patch` | 4,916 B，分支 `agent-c/pr16-verify-2026-09-13` @ `45afb87` |
| 原始日志 6 份 | `D:\deepseek_test\MediaHub\.smoke\` | 见 §6 哈希表（6/6 匹配） |

## 附录 B — 本轮"独立验证了什么"与"仍未经验证"

**独立验证（C 实测/实查）**：PR #18 完整 base→head 源码复审（四组契约 + A 线路修复的语义正确性推导）；
身份守卫异常类型跨界缺陷（含其测试为何未捕获）；生产无 checkpoint 杀进程通道；CI run/attempt/checkout/parents/tree 身份
（含 `refs/pull/18/merge` 拉取与 tree 相等）；CI 产物 772/0/0/0/92 与逐模块 772 = 768 + 4 的精确归因；lint 58 warning 分类
与 `ApplySharedPref` 假阳性判定；PR #16 六份原始日志的字节/SHA/内容/ticks 13 条逐项核对（含索引错标与 502 未披露）；
`markFailed` 无生产调用点；C3 环境探测（含**真机已连接的安全告警**）。

**仍未经验证**：PR #18 的模拟器/真机 SAF 链路与 8 个真实终止点（C3 未执行）；PR #18 真机（DEVICE_UNVERIFIED）；
C4 的并发交错实测；C6 的慢 final 复现与取消根因；C7 的底层 Call 取消与网络资源释放；C8 的 2A 选轨（B 未交付）；
PR #10 的全部视觉路径、FPS/功耗/HDR 兼容性；Jellyfin 真服与协议矩阵；一切真机性能与掉电持久性结论。

## 附录 C — 本轮未做的事（边界声明）
未直接写 main；未 force-push；未 reset/clean 他人工作区；未自动合并；未改变 Draft/保护规则；
未批量 resolve 审查线程；未替他人批准；未操作物理真机或 A/B 的 AVD；未写入其他 Agent 工作区。
本报告是 Agent C 的独立结论，**不是阶段封板或发布授权**；合并授权由用户保留。
