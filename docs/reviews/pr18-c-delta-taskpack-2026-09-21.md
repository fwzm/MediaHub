# PR #18 — C Delta 审查包（最终 head 待 C 独立复核）

生成：2026-09-21（Agent A / zcode 手动批次）。状态词沿用项目词表：**C REVIEW PENDING / DEVICE UNVERIFIED** 保持不变。

## 1. 定位（全部 SHA 已在本地核实）

- 仓库：fwzm/MediaHub；PR #18（Draft / OPEN）
- PR 分支：`feature/backup-restore` 最终 head：`21231eefcb847f84b8d1092d9c2146438f472ddc`
- base：`main` = `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`
- **Agent C 的审查基线：`82a38ab`**（= `agent-c/batch-2026-09-13` 与 PR 分支的 merge-base，本地 `git merge-base` 核实）。
  C 在自己的分支上以 6 个提交（`6f222db`→`feea1ac`）交付了独立 review、CI 审计、非破坏性真机 C2 gate 子集（`c75ecd0`）与 R1 红测试（`6b1f1de`）。
- 说明：`1949804`（早期 round-2 交付 head）也是最终 head 的祖先，但**不是** C 的复核基线；C 的分支基于 `82a38ab`。

## 2. 自 C 基线（82a38ab）以来的 PR 分支 delta = 2 个提交

| 提交 | 性质 | 内容 |
| --- | --- | --- |
| `aa33b28` | test | 接入 C R1 的 F-C1-1 分层失败回归（修复前红：3 失败）；收紧 `runCatching.isFailure` 弱断言为异常类型断言 |
| `21231ee` | fix | Emby/Jellyfin 两 Factory 的 identityGuard 由 `check()`（ISE）改为 `throw IOException`；首位 interceptor、lease 校验、失效身份不出网行为不变；未改 TokenStore/网络层；另按 Mimosa 要求将 3 处历史夹具字面量密码改运行时生成 |

完整生产 diff 面（`git diff --stat 82a38ab..21231ee`，已核实）：
`EmbyProviderFactory.kt`（7 行）、`JellyfinProviderFactory.kt`（7 行）、
新增 `EmbyIdentityGuardFailureLayerTest.kt` / `JellyfinIdentityGuardFailureLayerTest.kt`（各 192 行）、
`Emby/JellyfinFactoryRestoreIsolationTest.kt` 断言收紧（各 26 行）、
`BackupValidationSafetyTest.kt` 夹具（3 行）。
**`git diff 82a38ab..21231ee -- core/security` 为空（已核实）：TokenStore mutex/epoch/lease 契约与 KeystoreSecretStorage 的 durable `commit()` 移除均在此前提交（`7e314c6`，属 C 已复核范围），delta 未触碰。**

## 3. 修复证据链（A 侧）

- 修复前红 / 修复后绿：R1 8/8 + 隔离 6/6（`f-c1-1-red/`、`f-c1-1-green/`，21231ee 提交说明内登记）
- 六共享模块 `--rerun-tasks`：463/0/0（187 tasks 全 executed，`f-c1-1-six-modules.log`）
- 2026-09-21 复跑（本机，JDK 21）：`:feature:settings`、`:core:common:test`、`:provider:emby`、`:provider:jellyfin` BUILD SUCCESSFUL，0 failures / 0 errors
  （含 BackupRepository 20、BackupRestoreSafety 19、BackupViewModel 16、ProductionBackupDataSource 5、ProductionBackupFileStore 12、RestoreSnapshotStore 7、SharedPrefsRestoreJournal 19、BackupCrypto 8、BackupSerializer 22、BackupValidationSafety 10、Emby/Jellyfin RestoreIsolation 各 3；debug/release 变体均绿）

## 4. C 复核要点建议（delta 范围）

1. `EmbyProviderFactory.kt:51-58` / `JellyfinProviderFactory.kt` 同构处：identityGuard 抛 IOException 的边界是否满足 OkHttp/Media3 data source 失败契约，且 probe 层映射（MediaProbeResult/ServerProbeResult.Failure）不吞取消。
2. `Emby/JellyfinIdentityGuardFailureLayerTest`（新增）与两 Factory RestoreIsolationTest 收紧后的断言，是否与 C R1 要求一致（异常类型而非 isFailure）。
3. `BackupValidationSafetyTest` 夹具密码改运行时生成：确认不削弱原测试语义。

## 5. 未验证项（如实登记）

- 真机端到端复验（SAF、八个进程终止点、损坏 journal 阻断、物理设备）：本轮无设备授权，**未执行**；C 分支 `c75ecd0` 的真机子集记录属于 C 自己的证据，A 不借用。
- CI：本轮未推送新 SHA；`21231ee` 的既有 CI 结论以 PR 页面为准，A 未重新触发。
- 正式入口「设置→同步与备份」的密码重试/预览零写入/MERGE/REPLACE_SELECTED/回滚等行为回归在本地套件内全绿，但按词表这属于 **local_tests**，不等于设备验收。
