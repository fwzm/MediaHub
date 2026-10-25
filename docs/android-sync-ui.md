# W6-F —— Android 跨设备来源导入界面

- 状态：**已实现**（JVM 逻辑测试 27 例 = 16 + 11；Compose UI 测试 16 例已随 androidTest 编译通过，执行证据等 CI visual-instrumentation 的 API 32 / API 36 模拟器）
- 日期：2026-10-24
- 前置：[ADR-S001](./adr/ADR-S001-source-sync-contract.md)（线格式与身份）、[ADR-S004](./adr/ADR-S004-delta-sync.md)（冲突与审批）、[ADR-S006](./adr/ADR-S006-credential-resealing.md)（凭据不出网）
- 实现：
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportModels.kt`（视图模型与状态）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportLogic.kt`（四桶分组、可否允许、过滤信封）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportSession.kt`（状态机，端口可替身）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportViewModel.kt`（Hilt 薄壳）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt`（Compose 页面 + 稳定 testTag）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/SettingsScreen.kt`（设置页入口）
- 测试：
  - `feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportLogicTest.kt`（16 例，JVM）
  - `feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportSessionTest.kt`（11 例，JVM）
  - `feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreenTest.kt`（16 例，Compose / instrumentation）

## 问题

W6-A / W6-B / W6-C 已经把「来源怎么过机器」做成了可测试的契约层：Windows 侧能导出信封，Android 侧能预演、能落库。
但**用户没有入口**——契约只有测试在调，真实用户拿不到任何按钮。留白会带来三个具体坏结果：

1. 用户无法把另一台设备的来源搬过来，只能手工重填地址与账号；
2. 若仓促加一个「一键导入」按钮，未知类型与冲突条目会被静默吞掉——**用户以为成功了，实际什么都没导入**；
3. 导入成功后没有任何提示区分「来源记录已建」与「已登录」，用户会认为可以立即播放。

## 决策

### D1：预览与写入彻底分家，预览永不写盘

页面有两个动作：**预览**（走 `SourceSyncPreviewService.preview(json)`，只读接口 `SyncSourceStore` 上没有任何写方法）与 **应用**（走 `AndroidSourceSyncService.importEnvelope(json)`，唯一写库入口）。
UI 里没有「直接导入」，必须先预览出四桶。理由：四个桶的语义差异太大（可真写、需确认、可忽略、本机不支持），不预览就没有逐条决策的机会。

### D2：四桶固定显示，空桶也保留

四桶是 `applied / requiresApproval / ignored / unsupported`（界面标签：可导入 / 需确认 / 已忽略 / 暂不支持）。
即使某个桶为空也保留标题与计数——页面结构不随数据形状抖动，用户每次看到的都是同一张表。
摘要行固定形如「共 N 条：可导入 x · 需确认 y · 已忽略 z · 暂不支持 w」，与 Windows CLI 的 counts 一一对应，便于两端对账。

### D3：`unsupported` 与 `unknown-source-type` 永远不可允许

判据写在 `SourceSyncImportLogic.isAllowable`：只有「动作是 Create/Update」且「规划器没标 requiresUserApproval」的条目才可允许。
未知类型（`unknown-source-type`）与不支持类型（`not-supported-on-windows`）无条件进 unsupported 桶，控件 disabled，并显示固定说明「本机暂不支持该来源类型，无法允许导入。」。
理由：这两类条目在本机根本没有映射到 `ServerType`，允许它等于放行一条永远拨不通的来源。
冲突（Conflict）、删除（Delete）、Adopt 一律进需确认桶并禁用允许按钮：它们要么需要用户先解决冲突，要么等价于删数据。

### D4：失败关闭——没点允许的条目不会导入

`SourceSyncImportPreviewView.skippedKeys` 只包含「可允许但用户没点允许」的键。应用前用 `SourceSyncImportLogic.filterEnvelope` 把这些来源从信封里剔除，再把剩下的交给写库服务。
为什么在信封上过滤而不是给服务加逐条参数：服务的公开契约就是「一份信封 → 一次导入」，逐条决策是 UI 层的事；过滤信封不改服务签名，结果页的计数也如实反映「我批准了什么」。
**没有允许任何条目时应用按钮禁用**，并显示「请至少允许一个条目；未允许的条目不会被导入。」。
不可允许条目**留在信封里**：它们本来就写不进去，剔除反而会让结果页少报一条冲突。

### D5：结果页固定免责句

结果卡片固定显示「导入不代表已登录；请对每个来源点『验证』。」。
契约层只搬来源记录与（可选）已封凭据，**没有**做登录态验证；把「同步成功」说成「已登录」会让用户以为可以立即播放。

### D6：入口不改导航图

设置页「同步与备份」区新增「从文件导入来源」按钮，点开在本页内托管整个导入页（本地 `remember` 状态），返回即回到设置列表。
理由：导入是一次性低频操作，不值得在 app 级导航图里加一条 destination；也避免动到 app 模块。

## testTag 表（UI 测试与文档共用）

| 标签 | 位置 | 含义 |
| --- | --- | --- |
| `sync.import.entry` | 设置页按钮 | 打开导入页（设置页内托管） |
| `sync.import.screen` | 整页 Scaffold | 页面根节点 |
| `sync.import.pick` | 选择文件按钮 | 拉起系统文件选择器（OpenDocument） |
| `sync.import.preview` | 预览按钮 | 未选文件时禁用 |
| `sync.import.passphrase.card` | 口令卡片（W6-63） | 只有选到的是加密归档时出现；明文路径不渲染任何口令控件 |
| `sync.import.passphrase` | 口令输入框（W6-63） | 密码遮罩；口令只活在这张卡片的局部 `remember` 里，不进任何 StateFlow |
| `sync.import.unlock` | 「解密」按钮（W6-63） | 口令为空时禁用（避免用空口令白跑一次 600 000 轮密钥派生） |
| `sync.import.passphrase.error` | 口令错误行（W6-63） | 解密失败时出现，原样带 ArchiveError 稳定码 |
| `sync.entry.state` | 每条目状态行 | 未决定 / 已允许 / 已跳过 / 不可允许原因（点了按钮必须有回执） |
| `sync.entry.allow` | 每条目「允许」 | 仅可允许条目可点；未知/不支持/冲突一律 disabled |
| `sync.entry.skip` | 每条目「跳过」 | 同上 |
| `sync.entry:<key>` | 每条目卡片 | key = syncSourceId（去空白），缺失时 entry-<位置> |
| `sync.apply` | 应用按钮 | 没有任何允许时禁用 |
| `sync.apply.blocked` | 阻断卡片 | 预览失败 / 整份信封被拒 / 一个都没允许 |
| `sync.apply.result` | 结果卡片 | 导入结果计数 + 免责句 |

多条目页面上 allow/skip 会重复出现，UI 测试用 `hasTestTag(ENTRY_ALLOW) and hasAnyAncestor(hasTestTag(entry(key)))` 把按钮限定到某一条，避免「点了第一行却断言第二行」。

## 不变量（每条都有测试钉住）

| 不变量 | 钉住它的测试 |
| --- | --- |
| 预览零写入 | `SourceSyncImportSessionTest.previewWithoutAnEnvelopeIsBlocked`、`SourceSyncPreviewServiceTest`（契约层调用计数） |
| 未知类型永不可允许 | `SourceSyncImportLogicTest.unknownSourceTypeGoesToUnsupportedEvenWhenTheActionLooksImportable`、`SourceSyncImportScreenTest.theUnknownTypeEntryCanNeverBeAllowed` |
| 未允许即不导入 | `SourceSyncImportSessionTest.applyWithoutAllowingAnythingIsBlockedAndNeverReachesTheWriter`、`onlyTheAllowedEntrySurvivesIntoTheAppliedEnvelope` |
| 不可允许条目留在信封里 | `SourceSyncImportLogicTest.filterEnvelopeDropsOnlyTheEntriesTheUserDidNotAllow`（只掉 sync-2） |
| 整份信封被拒要有码 | `SourceSyncImportSessionTest.rejectedEnvelopeBlocksAndNeverCallsApply` |
| 逐条决策有可见回执 | `SourceSyncImportLogicTest.everyEntryCarriesAVisibleStateLineForItsDecision`、`SourceSyncImportScreenTest.everyEntryShowsTheStateItIsIn`、`aSkippedEntrySaysItWillNotBeImported` |
| 结果页带免责句 | `SourceSyncImportScreenTest.theResultCardCarriesTheDisclaimerSentence` |
| 阻断原因可见 | `SourceSyncImportScreenTest.theBlockedCardExplainsWhyNothingWasApplied`、`aRejectedEnvelopeBlockCardShowsTheReasonCode` |

## 测试证据（本机只做编译校验）

本机不跑模拟器（避免打扰使用者、也不在检出里起服务）。实际执行的三组证据：

1. **JVM 逻辑测试**：`SourceSyncImportLogicTest`（16 例）+ `SourceSyncImportSessionTest`（11 例）= 27 例，随 `:feature:settings:testDebugUnitTest` 真跑。
   JUnit XML 原始计数（`feature/settings/build/test-results/testDebugUnitTest/`）：
   `com.mediahub.feature.settings.sync.SourceSyncImportLogicTest :: tests=16 failures=0 errors=0 skipped=0`
   `com.mediahub.feature.settings.sync.SourceSyncImportSessionTest :: tests=11 failures=0 errors=0 skipped=0`
   同目录整个模块 11 个 suite 合计 `142 tests, 0 failures, 0 errors, 0 skipped`（Lead 重跑：`BUILD SUCCESSFUL in 48s`、18 executed / 194 up-to-date；androidTest APK 20,415,936 B）。
2. **androidTest 编译校验**：`:feature:settings:assembleDebugAndroidTest` 通过（见下方原始输出）。
3. **Compose 交互执行证据：等 CI**。`.github/workflows/android-ci.yml` 的 `visual-instrumentation` job 会在 API 32 与 API 36 两个模拟器上跑 `connectedDebugAndroidTest`，模块清单含 `:feature:settings`；`verify-visual-tests.py` 会逐 suite 核对无失败与计数一致。

**本页不得声称「GUI 已验证」**：本机只证明代码能编译、逻辑有 JVM 测试；androidTest 的断言是否真绿要等 CI 的 visual-instrumentation 结果。

## NOT_IMPLEMENTED（明确不做的部分）

1. **凭据导入**：本页面走的是明文信封路径，`includeSecrets` 与 DPAPI/SecretStorage 重封属于 W6-6 之后的独立任务；界面不展示任何凭据字段。
2. **删除审批**：墓碑条目进「需确认」桶但不提供逐条批准控件；真要删来源仍走来源管理页。
3. **冲突解决 UI**：冲突条目只显示原因码，不提供「用哪一边」的选择器。
4. **选择器之外的文件来源**：只支持系统 OpenDocument（SAF）；从剪贴板粘贴 JSON、从分享 Intent 进入都未接。
5. **导入后立即验证登录态**：免责句要求用户自己点「验证」；自动验证需要真实网络往返，属 W6-G。
6. **执行证据**：androidTest 的真跑结果在 CI，本机不跑模拟器。

## 复现命令

```powershell
cd D:\deepseek_test\MediaHub_Win
cmd /c ".\gradlew.bat :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest --console=plain --rerun-tasks > %TEMP%\w6f.log 2>&1"
```

跑之前确认 `Get-Process MediaHub` 计数为 0（不要在用户使用应用时构建）。

---

## W6-H 增补：导入后的凭据补录与逐源验证

- 状态：**已实现**（JVM 单测 22 例；androidTest 6 例随 `assembleDebugAndroidTest` 编译通过，执行证据等 CI）
- 日期：2026-10-24
- 前置：[ADR-S001](./adr/ADR-S001-source-sync-contract.md) D7（导入后必须逐源真实验证）、[ADR-S006](./adr/ADR-S006-credential-resealing.md)（凭据不出网）
- 实现：
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceVerificationModels.kt`（三态、稳定原因码、固定文案、视图状态）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceVerificationCoordinator.kt`（纯逻辑协调器：凭据端口 + 校验端口 + 日志端口）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceVerificationWiring.kt`（生产实现：`CredentialVault` / `MediaAuthProvider.authenticate`）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt`（结果页逐源行 + 补录对话框）
  - `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportViewModel.kt`（接线与刷新）
- 测试：
  - `feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceVerificationCoordinatorTest.kt`（22 例，JVM）
  - `feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/sync/SourceSyncVerificationScreenTest.kt`（6 例，Compose / instrumentation，本机只编译）

### 问题

W6-F 把来源导入落库了，但落库后的来源**没有任何凭据**，也没有任何验证动作：用户看到「已导入」，只能自己跑去来源管理页补录，
补录完也没有回执——界面永远显示导入成功。两种坏结果：①用户以为已登录，点开就播不出；②为了"看起来能用"，有人会把验证做成静默假成功。

### 三态语义（一个布尔值不够用）

| 状态 | 含义 | 是否发请求 | 落盘 |
| --- | --- | --- | --- |
| `NEEDS_CREDENTIALS` | 本机安全存储里没有该来源的密码（导入后的默认状态） | **不发** | 不写 |
| `VERIFIED` | 用本机凭据真实调用了 provider 的 `MediaAuthProvider.authenticate` 并成功 | 发 | 只在认证通过后写 |
| `FAILED` | 调用过但失败（服务器拒绝 / 类型不支持 / 认证服务不可用 / 凭据读不出或存不进） | 可能发过 | **不写** |

判定入口是 `SourceVerificationCoordinator`：`inspect(sources)` 只读探测（有已存凭据才真认证），`verify(source, secret)` 走补录——先认证，成功才 `saveSecret`。
失败、不支持、异常、取消一律不落盘；协调器**没有** store 依赖，入参是只读 `SourceVerificationSource` 快照，所以「验证顺手改了来源身份」在类型层面就不可能。

### AutomationId 表（UI 测试与文档共用）

| 标签 | 位置 | 含义 |
| --- | --- | --- |
| `sync.source.verify` | 每源「补录并验证」按钮 | 打开该来源的补录对话框 |
| `sync.source.verify.row:<serverId>` | 每源一行 | 把状态行与按钮限定到某条来源（多源页面必用 `hasAnyAncestor`） |
| `sync.source.verify.state` | 每源状态行 | 三态文案互不相同，`NEEDS_CREDENTIALS` 也必须有文案 |
| `sync.source.verify.dialog` | 补录对话框 | `AlertDialog` 根节点 |
| `sync.source.verify.input` | 密码输入框 | `OutlinedTextField` + `PasswordVisualTransformation`（语义带 `Password` 标记） |
| `sync.source.verify.confirm` | 「保存并验证」 | 密码为空或该源忙时禁用 |
| `sync.source.verify.cancel` | 「取消」 | 只关对话框，不提交任何凭据 |

### 失败码（封闭集合 `SourceVerificationReasons`）

| 码 | 含义 | 界面文案 |
| --- | --- | --- |
| `no-credentials` | 本机没有该来源密码 | 需补录凭据 |
| `missing-account` | 来源没有账号（username 为空） | 来源缺少账号，无法登录 |
| `auth-failed` | 服务器拒绝了这组凭据（401 / `AuthFailed`） | 账号或密码不被服务器接受 |
| `auth-not-supported` | 该来源类型没有认证能力 | 该来源暂不支持密码登录 |
| `auth-unavailable` | 认证调用抛异常（网络/协议），不是密码错 | 认证服务不可用，请稍后重试 |
| `credentials-unavailable` | 本机安全存储读不出来 | 本机安全存储不可用 |
| `credentials-save-failed` | 认证通过但凭据没写进安全存储 | 认证通过但凭据未能保存，请重试 |
| `verified` | 认证通过 | 已验证 |

校验器返回了集合之外的原因码时归一到 `auth-failed`（测试 `an unknown verdict reason is normalised into auth-failed` 钉住），
所以状态行永远只会出现上表里的码。校验器给的自由文本 `message` 会先脱敏（密码与账号替换成 `***`）再进文案。

### 「密码不进日志」是怎么验证的

两道证据，都是正向断言（不是「日志为空」这种弱断言）：

1. `logs carry the state and reason code but no password`：用 `RecordingLog` 收集真实写入的两行日志（一次 `verify` + 一次 `inspect`），断言「确实记了 2 行」、每行以 `source-verify serverId=` 开头、含 `state=VERIFIED` 与 `reason=verified`，并**逐行**断言不含密码 `s3cret-Pa55word`、不含账号 `alice`。
2. `auth messages are sanitised so the password never reaches the ui`：让校验器把密码与账号嵌进错误消息，断言 `outcome.message` 既不含密码也不含账号，且含 `***`。

日志行格式固定为 `source-verify serverId=<id> state=<STATE> reason=<code> checkedAt=<ms>`——只拼常量与 id，没有任何用户输入。

### 不变量（每条都有测试钉住）

| 不变量 | 钉住它的测试 |
| --- | --- |
| 无凭据时不发请求 | `SourceVerificationCoordinatorTest.a source without a stored secret needs credentials and sends no request`、`inspect only authenticates the sources that have credentials` |
| 只有认证通过才落盘 | `an accepted password is persisted exactly once`、`a rejected password is auth-failed and writes nothing`、`an unsupported source type is reported without persisting anything` |
| 验证失败不改来源身份 | `verification never changes the source identity`（快照值相等 + `outcome.serverId` 不变） |
| 重复验证幂等 | `verifying twice with the same password does not rewrite the credential`（`puts == 1`） |
| 密码/账号不进日志与文案 | 上面「密码不进日志」两条 |
| 原因码来自封闭集合 | `every reported reason stays inside the closed set`、`an unknown verdict reason is normalised into auth-failed` |
| 例外不吞取消 | `cancellation is never swallowed` |
| 三态文案互不相同 | `the three states render three different status lines`、`theThreeVerificationStatesRenderThreeDifferentLines`（Compose） |

### 测试证据（本机只跑 JVM 与编译校验）

1. **JVM 单测真跑**：`cmd /c ".\gradlew.bat :feature:settings:testDebugUnitTest --console=plain > %TEMP%\w6h-2.log 2>&1"` → `BUILD SUCCESSFUL in 50s`（`127 actionable tasks: 10 executed, 117 up-to-date`），EXIT=0。
   JUnit XML（`feature/settings/build/test-results/testDebugUnitTest/`）实测：
   `com.mediahub.feature.settings.sync.SourceVerificationCoordinatorTest :: tests=22 failures=0 errors=0 skipped=0`
   模块合计 **12 suites / 164 tests / 0 failures / 0 errors / 0 skipped**（基线 11 suites / 142 tests + 本轮 1 suite / 22 tests）。
2. **androidTest 编译校验**：`.\gradlew.bat :feature:settings:assembleDebugAndroidTest --console=plain` → `BUILD SUCCESSFUL in 16s`（`155 actionable tasks: 9 executed, 146 up-to-date`），EXIT=0；产物 `feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk`（20,447,820 B）。
3. **Compose 交互执行证据：等 CI**。`.github/workflows/android-ci.yml` 的 `visual-instrumentation` job 在 API 32 / API 36 两个模拟器上跑 `connectedDebugAndroidTest`，模块清单含 `:feature:settings`。

**本页不得声称「GUI 已验证」**：本机只证明代码能编译、纯逻辑有 22 例 JVM 测试真绿；6 例 Compose 断言是否真绿要等 CI。

### 未覆盖项

1. **真机未跑**：本机不启动模拟器（不打扰使用者），androidTest 6 例只做编译校验；执行证据等 CI 的 `visual-instrumentation`（API 32 / API 36）。
2. **跨机未跑**：本轮只做「本机补录 + 本机验证」；从另一台设备导入的凭据信封（W6-6 的 DPAPI 重封）在 Android 侧仍是 `NOT_IMPLEMENTED`，所以这里的凭据只能由用户手输。
3. **不搬凭据**：验证过程绝不写 Room、绝不把密码放进同步信封或日志；Android 侧**没有**任何「带凭据导出」的入口。
4. **不做自动验证**：导入完成后不会自动发起网络请求，必须由用户点「补录并验证」（ADR-S001 D7 要求逐个来源有明确动作）。
5. **不做凭据轮换**：同一来源改密码需要用户再次点「补录并验证」；没有后台刷新、没有过期提醒。
## Compose 断言在真模拟器上必须先滚动（2026-10-24 教训）

`assembleDebugAndroidTest` 通过只说明能编译，**不等于测过**。API 32 模拟器上第一次真跑（Android CI run 40028318723，head `8284f83`）拿到：`feature_settings` 套件 18 例 / 4 失败，四条全落在 `SourceSyncImportScreenTest`：

| 用例 | 报错 | 行 |
| --- | --- | --- |
| `theResultCardCarriesTheDisclaimerSentence` | `Assert failed: The component is not displayed!` | `SourceSyncImportScreenTest.kt:190` |
| `applyingReportsTheClickWhenSomethingIsAllowed` | `expected:<1> but was:<0>` | `SourceSyncImportScreenTest.kt:136` |
| `theScreenKeepsTheWholeFlowOnOnePage` | 未显示 | `SourceSyncImportScreenTest.kt:232` |
| `thePreviewShowsEveryBucketAndTheSummaryLine` | 未显示 | `SourceSyncImportScreenTest.kt:84` |

根因不在 UI：页面根节点是带 `verticalScroll` 的 `Column`（`SourceSyncImportScreen.kt:155-160`），屏幕外的条目/结果卡在语义树里存在，但 `assertIsDisplayed()` 为假、`performClick()` 也落不到。所谓「整页一屏」在可滚动布局下本来就不成立。

修法与现在的规矩：

1. 凡是断言「看得见」或要点击的节点，一律先 `performScrollTo()`（`androidx.compose.ui.test.performScrollTo`）；
2. `theScreenKeepsTheWholeFlowOnOnePage` 改名为 `theWholeFlowLivesOnOneScrollableScreenWithoutASecondPage`，语义变为「选择/预览/条目/应用都在同一个可滚动页面上，滚回去还能看到首屏按钮 ⇒ 没有二级页面」；
3. 同一个标签对应多个节点时不能用 `onNodeWithTag`（会报多匹配）：`VERIFY_STATE` 每个来源一个，改用 `onAllNodesWithTag(...).assertCountEquals(2)`；
4. 对话框是 `AlertDialog` overlay（`SourceSyncImportScreen.kt:457-499`），其内部节点不需要滚动。

本机没有模拟器、也不允许为验收随便起 GUI，所以这些修正的**执行证据仍然只能由 CI 给出**：`visual-instrumentation` 的 API 32 / API 36 全绿才算 GUI 证据到手，此前状态词只能写 `GUI_UNVERIFIED`。

