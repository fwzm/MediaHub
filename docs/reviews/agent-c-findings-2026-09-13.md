# Agent C findings — PR #18 / #16 / #10（2026-09-13）

按**负责人**分组。每条含：严重度 / 受影响 SHA / 路径行号 / 触发条件 / 影响 / 复现证据 / 建议修法 / 回归要求 / 分类。
分类取值：**已证缺陷** / **待证风险** / **环境问题** / **已接受限制** / **设计观察**。

---

## A（zcode）— PR #18 生产代码

### F-C1-1 身份守卫抛出的异常类型跨越 IOException-only 边界（已证缺陷，中危）

- **受影响 SHA**：`82a38abce4e761c002dde6d8c9420176e977f57c`（引入提交 `7e314c64cd209fdccb7ce211b8864bea767881e4`）
- **路径/行号**：
  - `provider/emby/src/main/kotlin/com/mediahub/provider/emby/EmbyProviderFactory.kt:52`
  - `provider/jellyfin/src/main/kotlin/com/mediahub/provider/jellyfin/JellyfinProviderFactory.kt:52`
  - 边界：`core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:59`（只 `catch (e: IOException)`）
  - 边界：`core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:162`（同上）
- **触发条件**：恢复后仍被持有的旧 `ProviderHandle` 发起**新的** API 或 media 请求。
  守卫 `check(tokenStore.isAuthenticationLeaseCurrent(lease))` 抛 `IllegalStateException`。
- **影响**：两个 Factory 的守卫是**正确的安全行为**（请求确实不出网，C 认可），但异常类型与下游契约不符：
  `MediaHttpClient.probe()` 只把 `IOException` 映射为结构化的 `MediaProbeResult.Failure(PlaybackError)`；
  `ApiClient.probe()` 同理。`IllegalStateException` **穿透**这两层，播放前探测不再降级为结构化失败；
  同一 OkHttp 实例经 `okHttpClient()` 交给播放器（Media3 OkHttpDataSource 路径）时，运行期异常同样不被
  `IOException` 处理逻辑承接。
- **复现证据**：
  1. 源码级：`MediaHttpClient.kt:38-62` 的 try/catch 只捕获 `IOException`；守卫在 `interceptors().add(0, …)`，
     必然先于任何网络 IO 执行。
  2. 测试为何未发现：仓库自身的隔离测试用 `runCatching { … }` + `assertTrue(result.isFailure)`，
     **接受任意 Throwable**，因此不会断言失败类型：
     `provider/emby/src/test/kotlin/com/mediahub/provider/emby/EmbyFactoryRestoreIsolationTest.kt:90-95`、
     `provider/jellyfin/src/test/kotlin/com/mediahub/provider/jellyfin/JellyfinFactoryRestoreIsolationTest.kt:91-95`。
  3. C 本轮**未新增**该回归测试（受本轮执行预算限制）；需要的断言在下方"回归要求"中给出，属下一批任务。
- **建议修法（最小）**：把守卫改为抛出 `IOException`（例如 `throw IOException("媒体源身份已变化，请重新打开媒体源")`）。
  OkHttp 允许 interceptor 抛 `IOException`，它会被现有 `catch (IOException)` 边界统一转换为结构化失败，
  且不放宽任何安全约束（仍然在发请求前失败）。**不需要**动 TokenStore 或网络层。
- **回归要求**：断言"被撤销的旧 handle 触发 probe 时，异常类型为 `IOException`，且 MockWebServer 请求数为 0"；
  同时把既有 `assertTrue(result.isFailure)` 收紧为对具体失败形态的断言，避免同类回归再次被 `runCatching` 吞掉。
- **备注**：C 未修改生产代码（遵守边界）；本项属"发现缺陷 → 交可复现失败"，修复归 A。

### F-C1-3 `RestoreJournal.markFailed` 无生产调用点（设计观察，低危）

- **受影响 SHA**：`82a38abce4e761c002dde6d8c9420176e977f57c`
- **路径/行号**：声明 `feature/settings/src/main/kotlin/com/mediahub/feature/settings/backup/RestoreJournal.kt:63`；
  实现 `:94`。全仓检索：**仅测试**引用（`SharedPrefsRestoreJournalTest.kt:133,141,160,187`、三个 fake 实现）。
- **触发条件**：任意恢复失败路径。
- **影响**：`lastError` 字段在生产中**永远不会被写入**，因此不会出现在恢复日志里；
  `recoverLocked()` 也只消费 `phase`/`protectiveSnapshotRef`/`planRecordJson`，不读 `lastError`。
  即"记录失败原因"这一接口承诺在生产中未被使用（诊断能力缺失，而非安全或数据正确性问题）。
- **复现证据**：`tools.grep` 对 `markFailed` 的全仓匹配（10 处，其中 6 处为测试/fake）。
- **建议修法（择一）**：① 在 `BackupRepository.restore()`/`recoverLocked()` 的失败分支调用 `markFailed`（注意不要再抛）；
  或 ② 明确把 `lastError` 标注为"仅测试/诊断用"，并删除接口承诺，避免误导后续读者以为失败原因已落盘。
- **回归要求**：若采用 ①，断言失败后 `read()?.lastError` 非空且**不含原始异常文本/路径/凭据**（沿用 `SAFE_FAILURE` 固定文案）。

### F-C1-4 快照已发布但 journal 未登记时可能遗留加密 orphan（已接受限制，已由 B 登记，C 复核确认代码路径存在）

- **路径/行号**：`feature/settings/src/main/kotlin/com/mediahub/feature/settings/backup/BackupRepository.kt:373-374`
  （`snapshotStorage.save(...)` 之后紧接着 `restoreJournal.begin(...)`，`begin` 在下方 try 块**之外**）。
- **触发条件**：`save()` 成功后、`begin()` 抛异常（冲突/持久化失败）。
- **影响**：磁盘上留下一个加密保护快照文件，无任何日志引用它；当前**没有自动 GC**。
  每次调用都会 `runCatching { snapshotStorage.delete(...) }` 的地方不覆盖这条路径。
- **分类**：B 已在复审件中列为明确保留的磁盘清理风险；C 复核确认"无自动 GC"属实，**不作为新缺陷**。
- **建议**：与 orphan 快照 GC 一起作为独立设计项（见批次报告 §11.6 第 6 项），不要顺带加自动删除。

---

## main 既有（超出 PR #18 范围，不并入本 PR）

### F-C1-2 EndpointTestService 把原始异常文本透出到用户可见结果（已证缺陷，低危，PR #18 范围外）

- **受影响 SHA**：`8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`（main）。
  **PR #18 的 base→head 差异未包含 `core/network`**（见 diffstat），故非本 PR 引入。
- **路径/行号**：`core/network/src/main/kotlin/com/mediahub/core/network/EndpointTestService.kt:63`
  （`errorMsg = "API test failed: ${e.message}"`），结果经 `EndpointTestResult.error` 进入 UI/落库路径。
- **触发条件**：线路测试的 API 层抛任意异常（DNS/连接/URL 解析失败等）。
- **影响**：原始异常文本（常含 host:port、路径；若上游未剥离 user-info，则可能含 `scheme://user:pass@host`）
  **未经脱敏**进入用户可见字段，违反本项目自身的脱敏约定（对照 `ApiClient`/`MediaHttpClient` 使用 `Redactor.redact`，
  `BackupFileStore` 使用固定文案）。
- **复现证据**：源码直接可见；同项目内"固定文案 + Redactor"的对照实现见 `ApiClient.kt:80-83,94`、
  `MediaHttpClient.kt:60`、`BackupFileStore.kt:96-97,118`。
- **建议修法（最小）**：`errorMsg` 改为固定文案（如"线路测试失败，请检查地址与网络"），需要细节时只写日志且经 `Redactor.redact`。
- **回归要求**：断言 `EndpointTestResult.error` 不含 host/路径/user-info 片段。
- **归属**：Phase 1I 已登记的"连接测试脱敏边界"同源项；由当前负责人处理，C 不借 C7 重构通用网络层。

---

## PR #16 归档负责人（文档修正）

### F-C5-1 证据索引把慢请求失败样本标注为成功样本（已证缺陷，中危——归档准确性）

- **受影响 SHA**：`f40363ce5199e05a4cd197b456a851c2b984492b`
- **路径/行号**：`docs/device-evidence/1h-emby-progress-verification.md:114`（证据索引表行）
- **触发条件**：不适用（静态归档错误）。
- **影响**：`coldfinal2_wire.txt`（278 B，SHA256 前 16 `9a4d1cd9fe0c5e2f`）被标注为
  "cold final #2（予初）wire 摘录"（即 §4 的成功样本），但其实际内容是 **§5 的慢请求失败样本**：
  `18:59:38.474 -> POST …/Sessions/Playing` / `18:59:42.262 <- 204（3787ms）`，**无 Stopped**。
  §4 #2 的成功样本实际来自 `coldfinal3_full.txt`（19:01:01.508 → 19:01:02.667）。
  后果：读者按索引取"成功证据"会取到失败样本，直接反转结论。这与 B 已登记的待核线索一致，**但尚未修正**。
- **复现证据**：C 逐字节读取 `D:\deepseek_test\MediaHub\.smoke\coldfinal2_wire.txt`（2 行，内容如上），
  并与 `coldfinal3_full.txt` 交叉比对；字节数与 SHA256 前缀均与索引表一致，**证明是标注错误而非文件不符**。
- **建议修法**：更正索引表该行，明确其为 §5 失败样本，并把 §4 #2 的来源指向 `coldfinal3_full.txt`。
- **回归要求**：索引中每个文件必须能唯一映射到一个场景，且文件名/哈希/字节/场景四元组自洽。
- **C 已交付补丁**：`c5-pr16-doc-correction.patch`（分支 `agent-c/pr16-verify-2026-09-13` @ `45afb87`）。

### F-C5-2 §4 #2 成功样本同文件含媒体 GET 502，档中未披露（已证缺陷，中危——因果措辞）

- **受影响 SHA**：`f40363ce5199e05a4cd197b456a851c2b984492b`
- **路径/行号**：`docs/device-evidence/1h-emby-progress-verification.md:97-104`（§4 #2）与 `:149`（§7 状态行）
- **触发条件**：不适用（归档披露缺失）。
- **影响**：`coldfinal3_full.txt` 第 11 行为 `19:01:02.528 <- 502 …/Videos/374078/stream.mkv（2296ms）`。
  即该轮的**媒体流请求失败**。文档只登记 Playing/Stopped 204，读者容易把"三个进度端点 204"读成"播放正常"。
- **复现证据**：C 读取 `coldfinal3_full.txt` 全文 12 行，逐行确认上述 502 行。
- **建议修法**：在 §4 #2 增加该行摘录，并把判定范围限定为"退出前无普通上报 → 退出后补发 Playing→Stopped 均 204"，
  明确**不**证明媒体可播放、**不**证明服务端续播位置已更新。
- **回归要求**：任何"PASS"判定必须写明其证据边界；HTTP 204 不得推定为媒体正常播放。
- **C 已交付补丁**：同上。

### F-C5-3 §3.1/§3.3 的 UserData 回写 delta = 0 无法从归档复现（证据限制，中危——可复现性）

- **受影响 SHA**：`f40363ce5199e05a4cd197b456a851c2b984492b`
- **路径/行号**：`docs/device-evidence/1h-emby-progress-verification.md:44`（§3.1）与 `:78`（§3.3）
- **触发条件**：不适用。
- **影响**：六份归档原始文件中**不存在任何 `UserData` / `PlaybackPositionTicks` 响应原文**
  （C 对 6 个文件检索 `UserData`、`PlaybackPositionTicks`、`996830000` 均无命中），也没有独立 BACK 时刻时间戳。
  因此"服务端已按该位置写回、delta = 0"属执行方 reported，**归档无法独立复现**。
  这不否定真机事实，但必须把可复核部分与不可复核部分分开表述，避免被后续引用为"已独立验证"。
- **复现证据**：C 的检索结果（0 命中）；可机器复核的部分是 §3.3 表的 13 条 `REQ_BODY`，
  C 已逐条比对 **13/13 与文档一致**，`PlaySessionId` 恒为 `6cd3207d…`，`÷ 10,000` 换算一致。
- **建议修法**：在 §3.1/§3.3 增加"证据完整性限制"注记（已含在 C 的补丁中）；后续若需坐实，补采完整 UserData 响应原文。
- **回归要求**：归档中每条"服务端结果"必须能指到一份原始响应；否则显式标注为 reported。

---

## 环境与安全（非代码缺陷）

### ENV-1 ambient `JAVA_HOME` 指向失效 JDK（环境问题）
- 实测：`JAVA_HOME=C:\Program Files\Java\jdk1.8.0_381`（不存在/不适用），而本机可用 JDK 为 `C:\Program Files\Java\jdk-21`。
- 影响：不改配置直接跑 Gradle 会失败，容易被误记为"代码红灯"。C 仅在**当前进程**覆盖为 jdk-21，未改全局安装或版本。
- 建议：无需改仓库；执行者按文档在进程内设置 `JAVA_HOME`（`tools/run-gate.ps1` 已内置校验与明确退出码 92）。

### HAZARD-1 物理真机处于 adb 连接状态（安全告警，重要）
- 实测：`adb devices -l` → `123e243f device product:aurorapro model:24031PN0DC`（Xiaomi 14 Ultra，即 PR #16 证据所用真机）。
- 影响：任何**省略 `-s`** 的 `adb`/`am instrument` 命令都可能操作该真机（含破坏性测试用例）。
- 要求：C 交付的 `scripts/agent-c/verify-backup-restore-c.ps1` **强制**要求显式 `-Serial`，未提供即退出；
  且拒绝 A/B 的既有 AVD 名称（`Codex_PR18_Integration_36`、`Codex_PR18_Review_36`）。C 本轮未操作该真机。

---

## 待证风险（源码成立但未实测）

| ID | 风险 | 当前证据 | 需要的实测 | 负责人 |
| --- | --- | --- | --- | --- |
| R-C4-1 | 已发出的网络报文无法撤销；只保证"结果不被提交" | `isAuthenticationCurrent` 在响应后二次校验（`EmbyAuthProvider.kt`） | 有界真实多线程交错用例 | C（下一批） |
| R-C6-1 | 慢 final 的精确取消来源未定位 | 档 §5 + `flushFinal` 2s 预算 + `Call.execute()` 不可中断 | 可控慢请求 + 单调时钟时间线 | C（下一批） |
| R-C7-1 | 取消协程不释放底层 OkHttp `Call` | `EndpointTestService.test()` 内无挂起点、未切 IO | MockWebServer 断言底层 Call 取消 | C（下一批） |
| R-C7-2 | 线路测试在主调度器上做阻塞 IO（ANR 风险） | 生产 KDoc 自述（`EndpointTestService.kt:30-33`）+ 调用方 `ServerEditorViewModel:271` | 帧/线程实测 | A / 当前负责人 |

---

## 附注：C 测试 delta（与原始 head 严格区分）

- 原始 head `82a38ab…` 的测试结果**单独记录**（C2 本地门禁在工作树干净时启动，日志记录 `source-dirty-before: no`；
  以及 CI 产物 `5acd9ba` 的 772/0/0/0/92）。
- **本轮 C 未新增任何测试文件**，因此不存在测试 delta 需要区分；
  `F-C1-1` 与 C3/C4/C6/C7 的补强测试均列为下一批任务（见批次报告 §11.6），**不计入本轮已完成的验证**。
- C 对本分支的改动仅为文档与一个 C 自有验证脚本，未触碰生产源码与既有测试。

---

## 未发现问题的部分（如实报告，不为凑数制造问题）

PR #18 的四组契约（冻结计划与数据 / 持久化与中断 / 身份与认证 / 正式 UI）在源码层面**逐条成立**；
A 的线路排序修复经 C 独立推导**语义正确且未发现漏判**；生产 source set **无**可被用户触发的 checkpoint 杀进程通道；
CI 产物 772 项**零失败**且模块归因可精确解释；六份原始日志的哈希与字节**全部匹配**。
以上均如实记为通过，未附加问题。
