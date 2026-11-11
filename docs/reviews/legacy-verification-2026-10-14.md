# MediaHub 历史遗留问题核验报告

- 核验对象仓库：`D:\deepseek_test\MediaHub`（GitHub `fwzm/MediaHub`）
- 核验时 main：`1246313c7a66d492258b5b4b20b51cbdcf2da9cc`（Merge PR #27，2026-10-14；本地 main == origin/main 已核对）
- 核验范围：PR #6 / #16 / #20 / #10、T0002（含已关闭 PR #22）、Room 3→4 迁移测试环境、EndpointTestService F04/F06、SLOW-FINAL 真实服务器证据，共 8 项
- 证据级别定义：**EXECUTED** = 本轮实际执行命令/查询并取得输出；**SOURCE_REVIEWED** = 只读源码/文档/PR 元数据审查。本报告不改动任何生产代码，未对任何 PR 发表评论、未 close/merge。
- 敏感信息纪律：PR #16 未脱敏的主机名、账号名、IP、URL 一律以 `<redacted>` 占位，不复制原文。

## 0. 方法与基线事实

| 命令 / 查询 | 输出摘要 | 级别 |
|---|---|---|
| `git rev-parse main origin/main` | `1246313` == `1246313` | EXECUTED |
| `gh run list --branch main --limit 8` | main @ `1246313` Android CI run `38496435020` = **success**（build / visual-instrumentation API 32 / API 36 三 job 全 SUCCESS） | EXECUTED |
| `gh pr list --state all` | 本报告涉及 PR 状态：**#6 OPEN / CONFLICTING**、**#16 OPEN**、**#20 OPEN (Draft)**、**#10 MERGED**、**#22 CLOSED** | EXECUTED |
| `git fetch origin main refs/pull/{6,16,20,22,10}/head` | 已取得全部相关 PR head 到 `refs/remotes/pr/*`（只读 fetch，无写操作） | EXECUTED |

---

## 1. PR #6 —— `codex/post-1d-review-hardening`（31 文件）

**现状判定：已被 main 覆盖（部分为功能性等价覆盖）；剩余仅为测试与文档增量。**

**证据（EXECUTED / SOURCE_REVIEWED）**

- PR 元数据（`gh pr view 6`）：OPEN、非 Draft、head `7809facab2316f1c43f1e30deb2efeacd8c2ca78`、base `main`、`changedFiles=31`、`+919/−118`。
- 祖先关系（本轮按纪律用 `$LASTEXITCODE` 判定）：
  - `git merge-base --is-ancestor refs/remotes/pr/6 main` → `rc=1`（否）
  - `git merge-base --is-ancestor main refs/remotes/pr/6` → `rc=1`（否）
  - `git merge-base pr/6 main` = `71af989c5b47a31c530577210a1c8a18b28666aa`（PR 分支实际分叉点 = PR #4 合并点，即分支落后 main 约 6 周）
  - 结论：**PR #6 的提交不是 main 的祖先，main 也不在其上**；PR 列表元数据同时显示 `mergeable=CONFLICTING`（`mergeStateStatus=DIRTY`，取自 `gh pr list` 同次查询；未做 live `gh pr view` 二次确认）。
- 变动面比对（`git diff --name-only 71af989 pr/6` 对比 `git diff --name-only 71af989 main`）：PR 的 31 个文件里 **20 个在 main 上也被改动过**（重叠候选覆盖），**11 个仅出现在 PR #6**，逐一核对如下：
  - `core/model/.../MediaQuery.kt` + `MediaQueryTest.kt`、`core/network/.../HttpClientFactory.kt`、`feature/library/.../LibraryScreen.kt`、`feature/search/.../GlobalSearchViewModel.kt`、`provider/api/.../ProviderExceptionTest.kt`、`core/network/src/test/.../{ApiClientCancellationTest,ApiClientSecurityTest,ApiExceptionTest}.kt` —— 属等价实现覆盖或测试增量（main 已存在等价安全/取消边界，见第 7 项）。
  - `feature/library/src/test/.../{LibraryFilterViewModelTest,LibrarySortViewModelTest}.kt` —— 主线上同名测试文件已存在且内容更晚（main 版本覆盖 1D/A4 语义）。
- 代码级抽样（`git show main:<path>`，均为 SOURCE_REVIEWED）：
  - `core/network/.../ApiClient.kt`：已用 `suspendCancellableCoroutine + enqueue` 的 `awaitCancellable` 取代阻塞 `execute()`，`probe()` 亦以 `withTimeoutOrNull` 包裹并经 `Call.cancel` 真实打断 → 对应该 PR 的「取消/取消重试/deadline」主旨已覆盖。
  - `core/logging/.../Redactor.kt`、`LogcatLogger.kt`、`LogBuffer.kt`：main 上存在对应的脱敏加固实现与 `RedactorTest`（PR 中为 +90 行的新增用例）→ 已在主线演进链中覆盖。
  - `provider/emby/.../{EmbyLibraryProvider,EmbySearchProvider,EmbyApiClient}.kt`、`feature/search/engine/GlobalSearchEngine.kt`、`LibraryViewModel.kt`、`provider/api/.../ProviderException.kt`：main 上均已远超该 PR 的改动深度（后续 1E/1F/1G/1I/A4 阶段继续演进）。

**建议动作：建议关闭（superseded）。**
关闭说明建议写成：「分支落后 main 约 6 周、与 main 冲突；其 correctness/security/cancellation 主旨已由 main 的实现覆盖，未并入手工保留价值仅为少量测试增量；若需要测试增量应按当前 main 结构重写，不按旧分支 merge。」

**证据级别：EXECUTED（祖先关系、文件集合比对、CI/PR 元数据） + SOURCE_REVIEWED（文件内容抽样）**

---

## 2. PR #16 —— `docs/1h-device-evidence`（1 文件，真机证据归档）

**现状判定：仍存在真实缺陷（脱敏未完成）。**

**证据**

- PR 元数据（`gh pr view 16`）：OPEN、head `f40363ce5199e05a4cd197b456a851c2b984492b`、`changedFiles=1`、`+164/−0`，唯一文件 `docs/device-evidence/1h-emby-progress-verification.md`。最新 CI：run `33967890069` build = success（2026-09-05）。
- 文件是否已被 main 覆盖：`git ls-tree -r --name-only main -- docs/device-evidence docs/workbuddy` **返回空**；`git log main -- docs/device-evidence` 无输出 ⇒ **该归档文件从未进入 main**（main 的 `docs/` 下只有 `api/ backup/ player/ providers/ reviews/`）。EXECUTED
- 脱敏现状（结构性扫描 head 文件，不打印原文；行号为 HEAD 文件行号）EXECUTED：
  - URL/主机出现 **4 次、2 个不同主机**，分别位于：
    - L31–L32（§2 服务器表格，code 内联 `<redacted>://<redacted>`）
    - L89（§4 cold-final #1 wire 摘录 `-> <redacted>://<redacted>/<redacted>`）
    - L100（§4 cold-final #2 wire 摘录，同上形态）
  - IP 形态字面出现 1 次（IPv4 形态、长度 7）。
  - 「真实账号名」：头部安全声明称「账号以代称（予初/墨云阁）指代」，但 **L89/L100 的 wire 摘录仍保留真实账号名**（与别名并存）。
  - 因此：review 的 hostname 诉求（inline `3940426902`/`3940426913`/suppressed-1）**未满足**；账号名诉求（inline `3940426889`）**仅部分满足**（表格处已改，wire 摘录处未改）。
- 该结论与 main 上的既有台账一致：`docs/reviews/task0-pr10-pr16-review-queue-2026-09-09.md`（main）第 3 节明确记「本轮确认实际 head 仍有未脱敏主机名，原问题未满足」「账号名称已移除，主机名未全部脱敏」，`TASKS.md:14` 遗留项 ③ 登记为「PR #16 证据归档 3 条脱敏处置」。

**建议动作：保留（待脱敏补丁）；脱敏完成前不建议合并。**
补丁范围仅限 `docs/device-evidence/1h-emby-progress-verification.md`（L31/L32/L89/L100 的 host 与 L89/L100 的账号名替换为稳定代号；保留请求路径、时序、协议诊断信息），并同步更正 `docs/reviews/task0-pr10-pr16-review-queue-2026-09-09.md` 中「coldfinal2 场景索引」遗留记录。若决定不再合并，则关闭并另建脱敏后的归档分支。

**证据级别：EXECUTED（文件存在性、结构性扫描、CI 元数据）**

---

## 3. PR #20 —— `workbuddy/w0-state`（Draft 文档）

**现状判定：唯一仍成立的实质发现（EndpointTestService 脱敏/取消）已被 main 覆盖；PR 本体未合并，剩余价值为历史归档。**

**证据**

- PR 元数据（`gh pr view 20`）：OPEN、**isDraft=true**、head `1f3e6acef9d0bd3184abb9cfa8cd8851af258cb4`、`changedFiles=5`、`+622/−0`，文件为 `docs/workbuddy/{current-state.md,resume.md,task-state.json,w1-pr18-verification-2026-09-20.md,w4-endpointtestservice-findings.md}`；最新 CI run `35487926952` build = success（2026-09-20）。EXECUTED
- 文件是否被 main 覆盖：`docs/workbuddy/` 在 main 上不存在（`git ls-tree` 空）⇒ 5 个文件均未合并。EXECUTED
- 实质结论核销（SOURCE_REVIEWED）：
  - `w4-endpointtestservice-findings.md` 的两条主线：「`e.message` 直入用户结果（未过 Redactor）」与「`CancellationException` 被折叠为普通失败」，已被 main 的实现取代 —— 见第 7 项（main 用 `EndpointProbeErrorMapper.describe(e)` 安全映射，且每层 `catch (e: CancellationException) { throw e }` + `coroutineContext.ensureActive()`）。
  - 该文档第 3 条「同步 `Call.execute()` 未切 IO dispatcher」在 main 上已改为 `withContext(ioDispatcher)` + `enqueue` 桥。
  - 文档其余部分（W0 状态快照、Provider 能力矩阵、跨分支 lease API 陷阱）为 2026-09-20 的历史快照，其「文档漂移提示」类结论对当前 main 仍有参考价值，但已不构成未闭环缺陷。
- 结构性问题：该 PR 为 Draft 且 5 个文档描述的是 2026-09-20 的基线（`8e516e4`），与当前 main（`1246313`）已差 3 周余、跨 PR #18/#24/#25/#26/#27 多个阶段；直接合并会把过时状态写成 main 事实。

**建议动作：关闭（或以 docs 分支归档后关闭）；不建议原样合并。**
如需保留其中仍有价值的条目（Provider 能力矩阵、文档漂移清单、跨分支 lease 陷阱），应提炼进当前 `TASKS.md` / `HANDOFF.md` 或 `docs/reviews/` 的一份新文档，并重新核对当前基线。

**证据级别：EXECUTED（存在性、PR 元数据） + SOURCE_REVIEWED（文档结论核销）**

---

## 4. PR #10 —— 视觉系统历史审查意见（8 条 inline）

**现状判定：8 条中 6 条已被 main 覆盖、2 条仍存在真实缺陷（均为局部时钟/时间语义问题）。**

PR #10 本体为 **MERGED**（2026-10-14 01:00:27Z 并入 main 链，`changedFiles=89`）。8 条 inline 来自 `chatgpt-codex-connector[bot]`（review `5059373565`，reviewed commit `3644de76c8`）；另两条 Copilot review（`5059361431`/`5064371484`）为「配额耗尽未执行 review」，无缺陷文本。

| # | inline id | 原问题 | main 现状（本轮核对） | 判定 |
|---|---|---|---|---|
| 1 | 3887879282 | `packRgb` 产出零 alpha，破坏不透明调色板约定 | `VisualPalette.kt`：`packRgb/scaleRgb/mixTowards` 全部 `OPAQUE_ALPHA or …`，`OPAQUE_ALPHA = -0x1000000` | 已覆盖 |
| 2 | 3887879284 | `uTreble` 上传但未参与输出 | `FlowGlowShader.kt`：`float trebleEnergy = clamp(uTreble,0,1)` → `dispersionOffset`、`dispersionGain`、`irid * edgeBand * uIridescence * (0.18 + trebleEnergy*0.08)` | 已覆盖 |
| 3 | 3887879286 | 极端宽高比缩略时某维取整为 0 | `ArtworkPalette.kt` `sampleDimensions()`：`maxOf(1, (w*ratio).roundToInt())` / `maxOf(1, …)`；且 `longest <= 64` 时直接返回原尺寸，1×128 场景已改为返回原尺寸 | 已覆盖（当前采样策略下不再触发；建议补边界回归，见第 9 节） |
| 4 | 3887879290 | smoother 固定 `1/fps`，未用实际经过时间（掉帧/帧率不一致时漂移） | `FlowGlowSurface.kt` 仍传 `dtSec = 1f / activeClock.fps`（`RendererSpectrumProcessor.process`）与 `SpectrumBars` 内同一写法；`FlowGlowClock` 只按 vsync 累积 `timeSec` | **仍存在真实缺陷** |
| 5 | 3887879299 | API 26–32 fallback 忽略 `config.opacity` | `drawRect(fallbackBrush, alpha = cfg.opacity)`（两处 fallback 分支一致） | 已覆盖 |
| 6 | 3887879302 | `Config.HARDWARE` 位图未缩放时 `getPixels` 抛异常 | `ArtworkPalette.fromBitmap`：`source.config == HARDWARE` → `requireNotNull(source.copy(ARGB_8888,false))`，且 `finally` 中只回收自建副本 | 已覆盖 |
| 7 | 3887879304 | 每次重置 `lastNanos` 丢失不足一帧余量（60 Hz 下 24 fps → 20 fps，40/50 → 30） | `FlowGlowSurface.kt:70-75`：`if (now - lastNanos >= 1e9/fps) { timeSec += …; lastNanos = now }`，**无余量保留、无 next-deadline** | **仍存在真实缺陷** |
| 8 | 3887879308 | 取色忽略透明度，透明 padding 可主导调色板 | `VisualPalette.kt`：`if ((c ushr 24) < MIN_ARTWORK_ALPHA) continue`，`MIN_ARTWORK_ALPHA = 0x80`，全透明输入返回 `VisualPalette.Fallback` | 已覆盖 |

补充事实：main 已有 `FlowGlowClockTest`（含「fps 边界钳 1..120」「stop 后重启以新帧为基线」两条用例），但**没有**针对 24/40/50 fps 在 60 Hz 下的时间窗帧计数用例，也没有 `SpectrumBars`/`dt` 的真实经过时间断言 —— 这正是第 4、7 条无法由现有测试证伪的原因。

**建议动作：第 4、7 条走补丁（小改 `FlowGlowClock`/`FlowGlowSurface`/`SpectrumBars` + 受控 vsync 序列回归）；其余 6 条可登记为已闭环。**
不建议为此重开 PR #10（已合并）；补丁应作为新的小 PR（例如 `fix(core-ui): clock fractional remainder + elapsed-time smoothing`）。

**证据级别：SOURCE_REVIEWED（8 条逐条源码核对） + EXECUTED（PR/review 元数据、文件存在性）**

---

## 5. T0002 —— Media3 真实选轨测试迁移（相关已关闭 PR #22）

**现状判定：生产修复已被 main 覆盖；剩余为测试迁移缺口（真实缺陷面已收窄为「缺引擎级回归」）。**

**证据**

- PR #22：head `e0980b6307da3cb1705b9211617bff36d818bff2`、`changedFiles=10`、`+554/−24`、CLOSED（2026-10-14T01:01:31Z）、未合并。关闭评论（作者 fwzm，2026-10-14T01:01:30Z）理由为「生产契约已被主线实现取代」，并声明主线「同时写入 `setOverrideForType` 与 `setSelectionOverride`；`C.TRACK_TYPE_*` 未被当作 rendererIndex 传入」。EXECUTED
- main 生产实现（`player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEngine.kt`，SOURCE_REVIEWED）：
  - `selectTrack(trackType, selection)`（约 L473–L515）：`clearOverridesOfType(trackType)` + `setTrackTypeDisabled(trackType, …)`；`mapped == null` 时关闭路径仍安全下发；`rendererIndices = filter { mapped.getRendererType(it) == trackType }`；组序号按目标类型组的顺序跨 renderer 递减展开（`ordinal`）；非法 `groupIndex`/`trackIndex` 直接 `return` 且不改参数；关闭字幕置 `lastSelectedSubtitle = null`。
  - 同时调用 `builder.setOverrideForType(TrackSelectionOverride(groups[groupIndex], selection.trackIndex))`（类型级）与 `builder.setSelectionOverride(renderer, groups, DefaultTrackSelector.SelectionOverride(groupIndex, selection.trackIndex))`（renderer 级，**确实以 renderer 索引调用**）。
  - 由此，关闭评论中「`C.TRACK_TYPE_*` 未被当作 rendererIndex 传入」成立（未直接把 trackType 当索引），但「主线只用类型级 API」的表述不准确 —— `setSelectionOverride(renderer, …)` 是 renderer 索引级 API。该措辞偏差只影响文档准确性，不改变结局，建议在 TASKS/HANDOFF 或 PR #22 关闭说明的后续更正文档中登记。
- 测试迁移缺口（EXECUTED）：
  - PR #22 新增的 `PlaybackEngineTrackSelectionTest.kt`（358 行）依赖 PR 新增的 `tracksProvider` 构造参数（`newSelector(scope, tracks) { … tracksProvider = tracks }`）；main 的 `PlaybackEngine` 已改为 `trackSelector.currentMappedTrackInfo`，注入点不同 ⇒ 不能直接移植。
  - `git ls-tree main player/engine/src/test` 中无 `PlaybackEngineTrackSelectionTest.kt`（main 仅有 `TrackMapperTest.kt` 等 12 个测试文件）。
  - `git log --all -- "**/PlaybackEngineTrackSelectionTest.kt"` 命中两个提交：`e0980b6`（PR #22 head）与 **`4684685`（分支 `loop/t0003-media3`，未合并、非 main 祖先，`rc=1`）**。`loop/t0003-media3` 基线为 `8e516e4`（2026-09-06），是更早的一次尝试，包含 `resolveTrackGroup(trackType, groupIndex)` 的 `tracksProvider()` 实现；**该分支与 PR #22 的测试都未进入 main**，需按 `currentMappedTrackInfo` 改写注入。
  - main 已在 `TASKS.md:14` 遗留项 ② 登记「T0002 引擎级回归测试移植（旧测试依赖 `tracksProvider` 构造参数，需改写注入为 `currentMappedTrackInfo`）」。

**建议动作：保留待补（测试重做），不重开生产修复。**
新增测试应按当前 main 的 `currentMappedTrackInfo` 解析路径构造夹具（video/audioA/textA/audioB/textB + unsupported 前置组），覆盖：类型内组序号映射、非目标类型参数不被污染、关闭/重开字幕、非法索引零副作用。`loop/t0003-media3` 建议标记为 superseded 并删除远端分支（避免与 main 实现混淆）。

**证据级别：EXECUTED（git 祖先/文件集合/PR 元数据） + SOURCE_REVIEWED（实现与测试注入点）**

---

## 6. Room 3→4 迁移测试环境阻塞（MigrationTestHelper 与 Robolectric 不兼容）

**现状判定：仅剩测试环境问题（且为**本地**环境阻塞；设备侧与 SQL 级路径在 main 上已被 CI 实际执行通过）。**

**证据**

- 本地阻塞的原始登记：`core/database/src/test/kotlin/com/mediahub/core/database/Migration3to4Test.kt` 类 KDoc（main，SOURCE_REVIEWED）：「Room 2.8.4 的 MigrationTestHelper 在 Robolectric 下存在 databaseName/绝对路径解析不兼容（`IllegalArgumentException: driver configured … but … was requested`），全链 helper 验证（含 v3 历史结构与 identity hash 校验）在本环境 BLOCKED_ENV」。`libs.versions.toml`：`room = "2.8.4"`、`robolectric = "4.14.1"`。
- main 上已存在的两条替代/补充路径：
  1. **SQL 级 Robolectric 回归（本地可跑）**：`Migration3to4Test` 用 `FrameworkSQLiteOpenHelperFactory` 在内存 v3 库上直接执行 `Migrations.MIGRATION_3_4.migrate(db)`，断言既有数据无损、`subtitle_memory` 表/主键去重/`serverId` 索引。
  2. **设备侧全链 helper 回归（CI 已执行）**：`core/database/src/androidTest/kotlin/com/mediahub/core/database/RoomMigrationDeviceTest.kt` 使用 `MigrationTestHelper` + `runMigrationsAndValidate` + 真实 `Room` 打开（含 identity hash），遍历 v1..v3 历史结构。
- CI 实执证据（EXECUTED，本轮读取 job 日志）：
  - `bash .github/scripts/run-visual-validation.sh` 在 emulator 上执行 `:core:database:connectedDebugAndroidTest`；run `38496435020`（main @ `1246313`）API 32 job 日志：`> Task :core:database:connectedDebugAndroidTest` → `Starting 1 tests on emulator-5554` → `Finished 1 tests` → `BUILD SUCCESSFUL in 3m 25s`；API 36 job 同 run 亦 SUCCESS。
  - run `38496435020` 的 build job 日志：`> Task :core:database:testDebugUnitTest` 执行，Unit tests step `BUILD SUCCESSFUL in 2m 38s`（即 `Migration3to4Test` 已通过）。
- 本地环境事实（EXECUTED，只读检查，未运行构建）：`ANDROID_HOME` 为空但 `local.properties` 存在；`adb` 不在 PATH；Robolectric 运行库 `android-all-instrumented-14/15-robolectric-*-i7.jar` 位于 `%USERPROFILE%\.m2\repository\org\robolectric\` 且**可读**（`.NET File.Open` 成功、magic = `50 4b 03 04`，ACL 对当前用户 FullControl）。⇒ 「Android runtime JAR 读取被拒」的本地条件在当前会话已不成立（或属当时会话沙箱策略），如需本地证据可重跑 `:core:database:testDebugUnitTest` + `:core:database:connectedDebugAndroidTest` 补齐 XML。
- `TASKS.md:14` 遗留项 ⑥ 与 `docs/reviews/a4-joint-handoff-2026-09-24.md:32` 仍以 BLOCKED_ENV 表述登记该组合。

**建议动作：保留但降级表述 —— 归类为「本地 Robolectric 环境限制」，并在关闭该遗留项时引用 CI 证据。**
即：把 `TASKS.md:14` ⑥ 的「Room 3→4 迁移测试环境阻塞」改为「已在设备路径闭环（CI `connectedDebugAndroidTest`）；本地 Robolectric 全链 helper 仍不可用（Room 2.8.4 限制），由 `Migration3to4Test`（SQL 级）+ `RoomMigrationDeviceTest`（设备级）共同覆盖」。不需要生产代码改动。

**证据级别：EXECUTED（CI job 日志、JAR 可读性、环境变量） + SOURCE_REVIEWED（测试与构建脚本）**

---

## 7. EndpointTestService F04/F06 环境阻塞（Robolectric Android JAR / Gradle 配置阶段权限）

**现状判定：已被 main 覆盖（F04/F06 所针对的验证对象已被新实现取代且已在 CI 通过）；仅剩本机环境问题。**

**证据**

- 原始登记（SOURCE_REVIEWED）：`HANDOFF.md` 「2026-09-20 T0001 阻断项修复复核」段：F04「Robolectric 测试未运行：所需 Android runtime JAR 读取被拒绝」；F06「两个模块单测、两个 lint 和 `:app:assembleDebug` 在 Gradle 配置阶段因 `AccessDeniedException` 失败，目标任务未执行」；`TASKS.md:34-35` 同款登记。
- 被阻塞对象是 2026-09-20 的 `EndpointTestService` 快照。main 当前实现（`core/network/src/main/kotlin/com/mediahub/core/network/EndpointTestService.kt`，SOURCE_REVIEWED）已完全改写：
  - `open suspend fun test(...) = withContext(ioDispatcher)`；入口与两阶段之间 `coroutineContext.ensureActive()`；
  - Layer 1/2 均显式 `catch (e: CancellationException) { throw e }`；`IOException` 分支先 `ensureActive()` 再映射；
  - 错误文本经 `EndpointProbeErrorMapper.describe(e)` 安全映射（不再回显 `e.message`）；
  - `Call.awaitCancellable`（`suspendCancellableCoroutine + enqueue + invokeOnCancellation { cancel() }`）替换阻塞 `execute()`，响应 `use` 关闭，1 MiB 有界采样。
  - 对应测试：`EndpointTestServiceTest` / `EndpointTestServiceCancellationTest` / `EndpointTestServiceOwnershipTest` / `EndpointTestServiceRedactionTest`（main 全部存在），另有 `ServerEditorViewModelTest`。
- CI 实执证据（EXECUTED）：main @ `1246313` 的 run `38496435020` build job 日志显示 `> Task :core:network:testDebugUnitTest` 已执行、Unit tests step `BUILD SUCCESSFUL in 2m 38s`，Lint step `BUILD SUCCESSFUL`，`Build debug APK` `BUILD SUCCESSFUL`（三 job 全 SUCCESS，2026-10-14T01:34Z 触发）。⚠ 说明：`HANDOFF.md` 提到的 T0001 当时所用 run `38698454545` 在本次抓取时未出现在 run 列表中（编号/时间与 main 最新 push 不符），故本报告不引用它作为证据，改用本轮可复现的 run `38496435020` 与 CI 配置（`android-ci.yml` 的 `verify-unit-tests.py` 门禁）作为依据。
- 本机环境（EXECUTED，只读）：Robolectric 运行库 jar 可读（同第 6 项）；`adb` 不在 PATH；`ANDROID_HOME` 为空。⇒ 「Gradle 配置阶段 `AccessDeniedException`」在当前会话策略（danger-full-access）下未复现，属当时会话的本地权限/沙箱条件。

**建议动作：关闭 F04/F06 条目（改为「已由主线实现 + CI 覆盖」），不再作为遗留阻塞登记。**
若要补齐本地证据，在本机以 `JAVA_HOME=…jdk-21` + `local.properties: sdk.dir` + `--offline` 重跑 `:core:network:testDebugUnitTest :core:database:testDebugUnitTest`，保留 XML；不通过也不改变 CI 结论。

**证据级别：EXECUTED（CI job 日志、本机 jar 可读性/环境变量） + SOURCE_REVIEWED（实现与测试清单）**

---

## 8. SLOW-FINAL 真实服务器证据缺失

**现状判定：需真实设备或服务器（生产侧修复已在 main 落地；真实服务器端验收证据仍缺失）。**

**证据**

- 生产侧修复已进 main（SOURCE_REVIEWED）：
  - `core/network/.../ApiClient.kt`：`awaitCancellable`（enqueue + 取消桥）已覆盖 `execute`/`executeNoContent`；`probe()` 亦改为 `withTimeoutOrNull(timeoutMs)` + `awaitCancellable`，取消经 `Call.cancel` 真实打断 —— 即 `docs/reviews/a2-joint-handoff-2026-09-21.md:33` 曾登记的「`ApiClient.probe` 仍为阻塞 `execute`」已被后续 A4 修复覆盖（该 handoff 文档未回改，属文档滞后）。
  - 回归测试存在：`provider/emby/src/test/kotlin/com/mediahub/provider/emby/progress/SlowFinalExitRegressionTest.kt`（main），且被 CI 的 `verify-unit-tests.py --required-suite …SlowFinalExitRegressionTest` 列为必需套件（`android-ci.yml`，SOURCE_REVIEWED）。
- 但「真实服务器证据」仍为 NOT_RUN（SOURCE_REVIEWED，来自 main 文档）：
  - `HANDOFF.md`（2026-10-14 收敛段）：「真机端到端、真实服务器（WebDAV/Emby/Jellyfin 慢终报与认证）、真实旧库升级 = **NOT_RUN**」。
  - `docs/reviews/a4-joint-handoff-2026-09-24.md:57`：「真实服务器：NOT_RUN；SLOW-FINAL 真实服务器侧 OPEN；Jellyfin BLOCKED_BY_SERVER」。
  - `TASKS.md:14` 遗留项 ⑤「SLOW-FINAL 真实服务器证据」仍在未完成清单；`TASKS.md:25` 记后续顺序含 2B SLOW-FINAL。
- 边界（来自 PR #16 归档与 review 队列，SOURCE_REVIEWED）：已观察的失败样本（慢 Playing 后无 Stopped）仍 OPEN；`coldfinal2` 场景索引错误未更正；六份原始日志无完整 UserData 响应、无独立点击/BACK 时间戳；精确取消来源与生产因果仍需可控复现。CI 只能提供合成/回归证据（`SlowFinalExitRegressionTest` 证明「退出预算被阻塞 IO 击穿」的缺陷与修复），**不能替代真实服务器退出链证据**。
- 本轮未执行、也无法执行真实服务器验证（无授权 Emby/Jellyfin/WebDAV 实例；用户已明确暂缓真机/真服验收）。

**建议动作：保留待设备/服务器（BLOCKED_BY_SERVER / NEEDS_SERVER）；不要用 CI 绿代替。**
建议先落一份可复用的验收脚本与记录模板（参数化 server 地址/凭据走环境变量，不落库、不打印凭据），把「Playing → Progress → Stopped 的 UserData 前后快照 + 退出预算耗时 + 取消传播」写成可重复步骤，供有服务器时一次跑完；同时更正 `docs/reviews/a2-joint-handoff-2026-09-21.md:33` 的过期陈述。

**证据级别：SOURCE_REVIEWED（代码/文档/CI 配置） + EXECUTED（CI run 列表与 job 日志中的必需套件门禁存在性）**

---

## 9. 可立即闭环的低风险项（建议修复清单，本轮未改仓库）

> 以下均为「不动生产逻辑」或「只改文档/测试」的低风险动作；按收益排序。

1. **更新遗留台账：把第 6、7 项从「环境阻塞」改为「已闭环 + 本地环境限制」** —— 文件：`TASKS.md`（第 14 行遗留项 ⑥、第 34-35 行 F04/F06 段落）、`HANDOFF.md`（2026-09-20 T0001 段）。建议文案要点：Room 3→4 由 `Migration3to4Test`（Robolectric SQL 级）+ `RoomMigrationDeviceTest`（CI 模拟器 API 32/36）双覆盖；EndpointTestService F04/F06 阻塞对象已被主线实现取代，CI 已跑 `:core:network:testDebugUnitTest`。附证据：run `38496435020`（main @ `1246313`，三 job SUCCESS；API 32 日志含 `:core:database:connectedDebugAndroidTest` 1 tests 通过）。
2. **更正 PR #22 关闭说明的措辞偏差** —— 不是重新打开 PR，而是在 `TASKS.md`/`HANDOFF.md` 追加一行更正：main 的实现**同时**使用类型级 `setOverrideForType(TrackSelectionOverride(...))` 与 renderer 级 `setSelectionOverride(renderer, groups, …)`（`PlaybackEngine.kt` 约 L510–L512）；「`C.TRACK_TYPE_*` 未被当作 rendererIndex 传入」成立，但「未使用 renderer 索引级 API」不成立。顺带登记 `loop/t0003-media3`（`4684685`，未合并、非 main 祖先）为 superseded 并删除远端分支，避免后续误引用。
3. **收口 T0002 测试迁移（测试-only，可立即做）** —— 文件：新增 `player/engine/src/test/kotlin/com/mediahub/player/engine/PlaybackEngineTrackSelectionTest.kt`。做法：以 `DefaultTrackSelector` + 受控 `TrackGroup` 夹具（video/audioA/textA/audioB/textB + 前置 unsupported 组）驱动真实 `selectAudioTrack/selectSubtitleTrack`，断言经 `currentMappedTrackInfo` 路径写入的 `overrides`/`disabledTrackTypes` 指向真实 `TrackGroup`；移植 PR #22 的 6 类用例（组序号映射、非法索引零副作用、关闭/重开、跨类型不污染、非零 trackIndex 保真、unsupported 前置组不改变映射）。注意不要沿用 `tracksProvider` 注入。
4. **修正 `FlowGlowClock` 的帧余量与平滑 dt（PR #10 第 7、4 条）** —— 文件：`core/ui/src/main/kotlin/com/mediahub/core/ui/effects/FlowGlowSurface.kt`（`FlowGlowClock.start` 约 L66–L78；`FlowGlowSurface` 内 `spectrumProcessor.process(dtSec = 1f / activeClock.fps…)`；`SpectrumBars` 内同一 `dtSec`）。建议：把「`timeSec += (now-lastNanos)`；`lastNanos = now`」改为保留余量的累计（如 `lastNanos += interval * ((now - lastNanos) / interval)`）或引入独立的 next-frame deadline；并为 smoother 计算真实的「本次采样 − 上次采样」秒差（可让 `FlowGlowClock` 暴露最近一次帧间隔）。配套回归：`core/ui/src/test/kotlin/com/mediahub/core/ui/effects/FlowGlowClockTest.kt` 增加 24/40/50 fps 在 60 Hz vsync 序列下的时间窗帧计数与余量守恒用例；`RendererSpectrumProcessorTest` 增加「掉帧时 attack/release 按真实经过时间收敛」用例。
5. **补 `ArtworkPalette` 边界回归（PR #10 第 3 条）** —— 文件：`core/ui/src/test/kotlin/com/mediahub/core/ui/effects/ArtworkPaletteExtractorTest.kt`（或新增 `ArtworkPaletteSampleDimensionsTest`）。现状已用 `maxOf(1, …)` 且 `longest ≤ 64` 直接返回原尺寸，但缺 1×128 / 128×1 / 64×1 / 1×1 的显式断言；顺手登记「`fromBitmap` 只回收自建副本、不回收调用方 bitmap」的契约断言（第 6 条）。
6. **PR #16 脱敏补丁（可立即做的文档修复）** —— 文件：`docs/device-evidence/1h-emby-progress-verification.md`（L31/L32/L89/L100 的 host 与 L89/L100 的账号名替换为稳定代号，保留请求路径/时序/协议信息与原始日志索引 SHA256），并同步 `docs/reviews/task0-pr10-pr16-review-queue-2026-09-09.md` 的 `coldfinal2` 场景索引更正。补丁后即可进入合并流程，同时消掉 `TASKS.md:14` 遗留项 ③。
7. **处理 PR #6 / #20 的处置记录** —— 文件：`TASKS.md`（遗留项 ⑦「PR #6/#20 的处置评估」）。按第 1、3 项结论写清：PR #6 建议 superseded 关闭（分支落后 6 周 + CONFLICTING）；PR #20 建议提取仍有价值条目后关闭，不原样合并。这两条只改台账，不需要代码改动。
8. **SLOW-FINAL 验收脚本化（为未来服务器窗口做准备）** —— 建议新增 `docs/reviews/` 或 `scripts/` 下的验收记录模板（参数从环境变量读，输出 UserData 前后快照与耗时，不写凭据），并在 `HANDOFF.md` 中把「SLOW-FINAL 真实服务器侧 OPEN」与脚本路径对应起来；同时更正 `docs/reviews/a2-joint-handoff-2026-09-21.md:33` 关于 `ApiClient.probe` 仍为阻塞 execute 的过期陈述。

---

## 10. 汇总表

| # | 项 | 现状判定 | 建议动作 | 证据级别 |
|---|---|---|---|---|
| 1 | PR #6（31 文件 hardening） | 已被 main 覆盖（剩余测试/文档增量） | 关闭（superseded） | EXECUTED + SOURCE_REVIEWED |
| 2 | PR #16（1H 真机证据归档脱敏） | 仍存在真实缺陷（host/账号未全脱敏） | 保留待脱敏补丁；补丁前不合并 | EXECUTED |
| 3 | PR #20（W0 状态 Draft 文档） | 实质发现已被 main 覆盖；本体未合并、已过时 | 关闭或以新文档归档后关闭 | EXECUTED + SOURCE_REVIEWED |
| 4 | PR #10（视觉 8 条 inline） | 6 条已覆盖；第 4、7 条仍存在真实缺陷 | 逐条闭环 + 两条走小补丁 | SOURCE_REVIEWED + EXECUTED |
| 5 | T0002（选轨测试迁移 / PR #22） | 生产修复已被 main 覆盖；测试迁移缺失 | 保留待补（测试重做）；更正关闭说明措辞 | EXECUTED + SOURCE_REVIEWED |
| 6 | Room 3→4 迁移测试阻塞 | 仅剩测试环境问题（本地 Robolectric；设备路径 CI 已过） | 降级表述并引用 CI 证据后闭环 | EXECUTED + SOURCE_REVIEWED |
| 7 | EndpointTestService F04/F06 阻塞 | 已被 main 覆盖；仅剩本机环境条件 | 关闭条目 | EXECUTED + SOURCE_REVIEWED |
| 8 | SLOW-FINAL 真实服务器证据 | 需真实设备或服务器（生产修复已落地，真服证据缺失） | 保留待设备/服务器；先脚本化验收 | SOURCE_REVIEWED + EXECUTED |

**核验边界声明**：本轮未运行 Gradle 构建（只读核验，避免在已有未跟踪改动的本地工作树上产生构建写入），因此「CI 已通过」类结论均取自 GitHub Actions 的 job 日志与本轮 `gh run` 查询（EXECUTED），「本地可跑」类结论标注为环境事实与建议动作；`mergeable/mergeStateStatus` 取自 `gh pr list` 同次查询；PR #16 的敏感原文按纪律一律以 `<redacted>` 占位。
