# PR #18 — Agent A 接回与集成验证（2026-09-09）

本轮身份为 Implementation Owner。审查 Agent B 的补丁后接回既有 `feature/backup-restore`，不代替 Agent C 独立批准，不合并 PR。状态保持 **C REVIEW PENDING / DEVICE UNVERIFIED**。

机器可读的提交、用例、产物哈希与设备证据索引见 [Agent A 验证清单](pr18-agent-a-validation-2026-09-09.json)。最终提交与 CI 的绑定由提交后生成的 PR 正文和外部 C 任务包提供。

## 接回身份与范围

- 本轮重新查询的 main/base：`8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`。
- 接回前本地功能分支、远端功能分支和 PR #18 head 均为 `8798b1381f033533ed2b50b144468b7049789c4e`。
- 已逐个核验父提交：`8798b13 → 7e314c64cd209fdccb7ce211b8864bea767881e4 → 337c626331dcd7552d13d85e76c0c9773883af4d → 307b8f2623ee563227d2502d85e938706ac23373`。使用 fast-forward，原 SHA 与接回 SHA 一一相同，无 cherry-pick、冲突解决或整文件覆盖。
- Agent A 在新的 `MediaHub-pr18-agent-a` worktree / `codex/pr18-integration-validation` 审查与验证。原工作区未跟踪文件保留，接回前核验没有 tracked delta 或传入路径冲突。
- B 补丁共 57 个文件：23 个生产源码、20 个测试文件、8 个文档、6 个构建/验证文件。已核对完整差异与报告；Home、TokenStore、两个 AuthProvider/Factory 及会话持久删除均属于披露的恢复身份隔离范围。未发现报告遗漏的生产功能扩展。
- A 额外提交 `68d502f15cbcf5d01499550622adcc8d763735bd` 仅给现有 CI 追加 checkout/head/base/run 身份清单和测试/lint 产物归档。保留 `build` 名称、synthetic merge checkout、standalone JVM tests、测试/lint 超时、取消策略与 `setup-gradle@v5`。不增加自动重试或绕过断言。
- A 独立确认额外 P1：备份线路数组顺序与 Room `sortOrder` 顺序不同时，MERGE 可能误认同源，REPLACE 可能切换有效地址却不失效凭据。追加两个备份生产文件、四条回归及 ADR-041 更正；这些变更必须进入 C 审查范围。最终 PR head 和实际 CI checkout 绑定在 PR 正文及独立任务包，避免在提交内自引用自己的 SHA。
- A 生产修复提交：`9a8c309ebd5b537c9fd6e6561022b55e49ad200e`，父提交为上述 CI 提交；新增 127 行、删除 1 行（含测试与约束文档）。发现 P1 后将 PR 转为 Draft，保持 OPEN；完成验证仍等待 C，不替 C 将审查状态改为通过。

## 四组审查

| 契约 | 独立源码与断言核对 | 结论及边界 |
| --- | --- | --- |
| A 冻结计划与数据 | `PreparedRestorePlan` 绑定 validated 对象、策略、UUID、scope、Room before/after 与偏好；预览后先检查，Room 事务内再次比较 baseline。原补丁遗漏线路持久化排序；A 按 `sortOrder` 冻结输入，双向拒绝有效候选地址歧义，旧不稳定计划保留现场。MERGE newer-wins、本机默认优先、完整回滚字段和 nullable 偏好保持。 | 已修复本轮确认的 P1，待 C 复审。并发本机写入时保守拒绝自动覆盖，不承诺任意分歧后自动合并回滚。 |
| B 持久化与中断 | 加密 AtomicFile 同步写、发布读回后才登记 PREPARING；Journal 检查 commit 返回值，失败缓存不再可信，空 cache 需核验真实 XML/.bak。继续/回滚分别核对完整 before/after；COMPLETED 清理失败不逆转已完成恢复；再次恢复幂等。 | Room 事务只覆盖 Room。文件发布、Journal、DataStore 与凭据不能描述为跨存储 ACID。真实进程终止与重建对象测试分开；不证明断电持久性。 |
| C 身份与认证 | AppModule 提供 Singleton TokenStore。每 ID mutex 保护 epoch 和提交；网络期间不持锁，restore lease 覆盖身份写入/回滚。成功登录推进 epoch；迟到登录/401/logout 不写回或清除新会话。Factory 在 API/media 请求入口拒绝失效 lease；普通登出后同源再登录、未变来源与 fresh handle 保持可用。恢复取消/失败 finally 释放 block，持久删除失败中止地址替换；回滚不复活凭据。 | 已执行共享 security/Home/Emby/Jellyfin 测试，未仅用 backup 包背书。已进入网络的请求不承诺撤销。需 C 继续独立审查锁序、所有权及消费侧，而不是把 A 的接回视为独立批准。 |
| D 正式 UI 与测试边界 | Home 以类型/规范化地址/原始账号及读取代号防止迟到旧快照和结果回写；registry 每次创建 handle。SAF 事件先消费再启动、取消/重入/错误密码重试、输入擦除与策略变更重置确认。FileStore 测试实际约束读取 max+1、write/close 失败及脱敏；坏文件/预览/未确认替换有零业务写入断言。故障探针全部位于 androidTest，需显式 isolated 参数与模拟器校验。 | 正式 SAF 与 OS kill 结果单列；不把 debug 夹具作为用户功能。无真机独占环境，未操作物理手机或用户媒体。 |

原 17 条 inline、4 个 review、2 条历史 issue comment 已重新拉取分页记录，与 [B 逐条处置表](pr18-agent-b-2026-09-09.md) 对照当前代码。保留已正确修复项；3944594989 的处理按身份变化/新 ID 清理，遵守同源替换保留登录的 ADR，不采用评论中无条件登出的扩大解释。thread 的 resolved/outdated 状态不作为缺陷结论。A 不代为解决或批准审查 threads。

| 本轮问题来源 | 原问题与当前位置 | 处置及回归证据 |
| --- | --- | --- |
| A-P1-ENDPOINT-ORDER，接回完整差异与 Room DAO 交叉核对 | `BackupRepository.validate` 原先按文件数组顺序裁决来源，Room 按 `sortOrder` 读回。两个地址顺序相反时 MERGE 漏报冲突；REPLACE 切换有效地址但 `identityChangeTargets` 判同源，不失效凭据。 | `9a8c309` 排序后冻结；用真实 Room 读回地址及 `cleared` 反向断言约束。红灯 XML 对应 expected conflict 1/actual 0、expected `[a]`/actual `[]`。 |
| 同一问题的校验及重启边界 | `BackupSerializer.validatePayload` 允许同优先级有效候选指向不同地址；`recoverLocked` 自动重放旧的不稳定 image。 | 双向拒绝歧义，预览和重放前校验身份稳定；旧 PREPARING/ROLLING_BACK 均保留现场且零写入。四条红灯对应修复后回归；唯一主线路/同规范化地址为正向兼容断言。既有同源替换保留登录，不扩大失效范围。 |

## 本轮执行证据

针对性命令：

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :core:security:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:home:testDebugUnitTest :provider:emby:testDebugUnitTest :provider:jellyfin:testDebugUnitTest --rerun-tasks --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-daemon --console=plain
```

结果：退出 0，12m01s，187 actionable tasks 全部执行；46 份 XML、451 tests、0 failures/errors/skipped。六个测试 task 均实际执行，模块分别为 common 67 / security 17 / settings 100 / home 15 / Emby 164 / Jellyfin 88。命令在 `307b8f2` 发起，运行期间仅追加了 CI 文件提交 `68d502f`，生产/测试源码无变化；汇总记录的 checkout 是采集时 HEAD，不混作开始时 SHA。

新增 P1 修复后，四条回归首轮全部通过（`green-endpoint-order.xml`，4/0/0/0；5m23s，22 executed、95 up-to-date）。补充导出拒绝、合法并列地址及 ROLLING_BACK 断言后运行 common/settings 全集：

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :feature:settings:testDebugUnitTest --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-daemon --console=plain
```

结果：exit 0，3m22s，122 tasks 中 6 executed、116 up-to-date；两个测试 task 均 **EXECUTED**。本轮 12 份 XML、171 tests、0 failures/errors/skipped（common 67/settings 104），仅采集这两模块到 `targeted-final-results`，未混用其他模块旧 XML。受测文件内容对应 `9a8c309`；该提交在运行期间只记录已开始验证的相同文件内容。

首个全量执行在 `68d502f15cbcf5d01499550622adcc8d763735bd` 开始。发现上述 P1 后，A 核验进程对应本工作区，主动停止自己的 Gradle daemon 以加入失败回归；日志保留为 `full-checks.log`，结束为 daemon disappeared / exit 1，状态为主动中断，不能计作全量通过。随后从无 tracked delta 的 `9a8c309ebd5b537c9fd6e6561022b55e49ad200e` 使用以下命令重新验证：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\55160\AppData\Local\Android\Sdk'
.\gradlew.bat testDebugUnitTest :core:model:test :provider:api:test :metadata:test assembleDebug lintDebug :app:assembleDebugAndroidTest --rerun-tasks --continue --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-daemon --console=plain
git diff --check
```

结果：**BUILD SUCCESSFUL，exit 0，36m05s，1197 actionable tasks / 1197 executed**。从本轮 92 份 XML 得到 **772 tests / 0 failures / 0 errors / 0 skipped**；22 个有源码的测试 task 均 EXECUTED，`:player:mpv:testDebugUnitTest`、`:provider:webdav:testDebugUnitTest`、`:metadata:test` 为 NO-SOURCE，不计通过。`:core:model:test` 为 35，`:provider:api:test` 为 8，均实际执行。Debug 与 androidTest APK 构建成功，`git diff --check` 通过。

与 B ZIP 的 **`evidence/final-unit-xml/`** 92 份 XML 比较，用例标识只新增上述四条，无删除、迁移或减少断言；B 的其他红灯/基线 XML 不能混入这个比较。全量 768 → 772、备份专项 140 → 144 的变化来自新增四条回归，不把历史数字设成验收目标。每模块、每 XML 与用例清单见 `full-final-results/summary.json`、`test-case-delta.json`。

本轮 22 份 lint XML：**0 error/fatal、61 条 warning 记录**。另从 B 的未修改 `307b8f2` 工作区只读采集其实际历史 lint XML（B ZIP 中未包含），确为 19 条；差异是 app 报告内 `GradleDependency` 3 → 39、`AndroidGradlePluginVersion` 0 → 6，其余记录数量不变。这些为依赖/AGP 版本更新提示，版本清单未由 A 修改；不将不同执行时发现的提示集合变化归因为本轮备份生产缺陷，也不据此扩展升级范围。两轮原始 XML 分别保留，CI 仍按自身产物独立统计。

新增缺陷的失败证据：`red-endpoint-order.log` 首先复现三条失败，随后补上旧保护计划并明确凭据反向断言；`red-endpoint-order-2.xml` 为 **4 tests / 4 failures / 0 errors / 0 skipped**（2m32s，117 tasks 中 4 executed、113 up-to-date）。分别断言实际异源冲突、旧凭据必须清理、歧义输入零写入、旧不稳定计划零重放并保留日志。两次红灯日志与 XML 均保留，不用后续绿灯覆盖。

模拟器为本轮新建 `Codex_PR18_Integration_36` / `emulator-5558`，API 36。首次启动长期 offline，未运行任何测试；保留 stdout/stderr 后重启该专用实例并启用内核诊断、改用 swiftshader_indirect，系统最终报告 boot_completed=1。没有单独证据判定渲染配置就是首次未完成的根因，也不把启动中断算测试失败或通过。

安装前已核验 AVD 名称、`ranchu`、boot_completed=1，`pm path com.mediahub.app` 和测试包均为空；记录见 `emulator-preinstall-state.json`。不使用 B 的 emulator-5556，也未对已连接的物理手机输入、安装或清数据。

已设置专用模拟器亮屏并唤醒/解除无密码 keyguard，作为 UI 可交互前提。首轮 `device/ui-saf.log` 为 **1 test / 1 failure，19.842s**：进入备份页前未找到首页“设置”。失败时的 `backup-ui-failure.xml` 显示系统 `Messages isn't responding` 对话框遮挡；随后新层级已回到 Launcher，条件检查拒绝执行旧关闭按钮点击。显式启动应用后 `home-preflight.xml` 确认正式首页与“设置”可见，再以相同代码运行到新的 `device-2`。不修改测试超时、选择器或断言，不隐藏原失败；系统对话框更深层原因未证明，后续成功不能抹去此环境失败记录。

```powershell
.\scripts\verify-backup-restore.ps1 -Serial emulator-5558 -OutputDirectory D:\deepseek_test\pr18-agent-a-evidence\device -IsolatedEmulator
```

最终 `device-2` 完整脚本退出 0：正式 SAF **1/1**（227.202s）+ 八个真实新进程恢复 **8/8** + 损坏磁盘日志阻断 **1/1**（25.242s）。九次 seed 预期进程死亡单列，不计通过。所有 PID 为正且新旧不同，已核对每个 checkpoint；损坏日志 seed/recovery 为 **8341 → 8393**，原始 `corrupt-marker.xml` 与 `runner-logcat.txt` 支持该对应。完整结果、逐文件哈希见 `device-2/summary.json`；首轮失败仍保留为未撤销的环境失败记录。

| 阶段 | 终止 PID → 恢复 PID | 本轮结果 |
| --- | --- | --- |
| SNAPSHOT | 7569 → 7622 | 原业务状态保留；未登记日志无自动恢复 |
| PREPARING | 7663 → 7712 | 前向完成 |
| INVALIDATED | 7756 → 7816 | 前向完成，失效凭据不复活 |
| DB_BEFORE_MARK | 7860 → 7911 | 识别实际已写入数据，完成后续步骤 |
| DB_WRITTEN | 7954 → 8003 | 完成偏好与日志步骤 |
| PREFERENCES_APPLIED | 8046 → 8097 | 完成恢复并清理日志 |
| ROLLING_BACK | 8146 → 8203 | 恢复完整原数据和 nullable 偏好；已失效凭据不复活 |
| COMPLETED | 8249 → 8299 | 保留已完成状态，只清理 |

本轮安装前后 SHA256 一致：生产 APK `54CFDDEE6AEB385D572605A4A9BC15A54115E91EBF2D9D7720D61F7EE64DFDEB`，androidTest APK `2D41557B16CBD91981085FBA2D857880DE2F4D0A950E2F8ABADF1E1436F61DA1`。受测源码为 `9a8c309`，后续提交只整理报告/文档；安装路径、版本与哈希清单在 `installed-app-identity.json`、`installed-test-identity.json`。

另显式执行 `BackupCryptoTimingTest`：1/1，通过 600,000 次生产 KDF（PID 8455）。该进程首次/第二次派生 wall 为 12.0530601s / 12.391308s，thread CPU 为 11.323275187s / 11.115099705s；后台 `backup-crypto-timing-io`，前后主线程 pulse 均 true。原始记录见 `kdf-diagnostic.log`、`kdf-timing.txt`。仅为该次环境诊断，不作为性能 SLA，也未证明同步 JCA 会及时响应取消。

## 历史材料与交接边界

B 的 ZIP 可读取，SHA256 `4DD89D18F412A29C788022F7D15190B369539FB4CC5439949945738550B124CB`，214 条目。直接解析其中 92 份历史 XML 为 768 tests、零失败/错误/跳过，并实际读取全量构建/最终仪器日志与 KDF 记录；bundle 验证通过，要求已有前置 `8798b13`。这些结果仍归 B，不代替 A 新执行。

ZIP 没有 `final-instrument-runner-logcat.txt`，其本机旁置原文件仍可用，A 已另行读取并归档（SHA256 `D23BFC0CFF5C9DDEFE8CC997A78A2BA805267E284775C86C1733F653F090DF00`），确认 B 损坏日志验证 PID 9277 的实际 TestRunner 记录。其余未逐份检查的早期失败日志不能因存在索引而获得新的 A 结论；B 历史失败及其说明保留。

损坏日志的人工处置、设备 Keystore 密钥失效、不可逆凭据失效、没有自动 GC 的 orphan 加密 snapshot 继续登记。无新恢复策略、云备份或清理机制。本轮不关闭 PR #16 的 SLOW-FINAL，也不接入视觉、选轨或 Jellyfin 协议修复。

Agent C 必须重新 fetch 最终 PR head/base，独立读取完整 PR 差异与上述四组契约，在隔离工作区执行关键共享测试、正式 SAF、八个真实终止点及损坏磁盘日志验证。C 任务包应包含完整 SHA、A 额外 delta、原始 XML/日志/哈希、CI revision.json 和下载产物；缺文件明确登记。无实际 C 回报时 **C REVIEW PENDING**，无真机独占验证时 **DEVICE UNVERIFIED**。PR 保持 OPEN，合并必须等待 C 结论与用户授权。
