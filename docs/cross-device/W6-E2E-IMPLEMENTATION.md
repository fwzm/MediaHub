# W6-E2E 实现说明 —— 离线同步编排器与双向往返

- 状态：**已实现并测试**（W6-E2E，28 例编排层测试）
- 日期：2026-10-24
- 前置：[ADR-S001](./adr/ADR-S001-source-sync-contract.md)（契约与 D5 冲突规则）、[ADR-S004](./adr/ADR-S004-delta-sync.md)（增量与合并）、[ADR-S005](./adr/ADR-S005-archive-encryption.md)（归档加密）
- 实现：`windows/src/MediaHub.Sync/SourceSyncSession.cs`、`windows/src/MediaHub.Sync/SourceSyncSessionResult.cs`
- 测试：`windows/tests/MediaHub.Tests/SourceSyncSessionTests.cs`

## 一、编排位置

这一层不新增同步语义，只把已有的三层按真实使用顺序串起来：身份与契约形状来自 `SourceSyncLocalBridge`，写不写来自 `SourceSyncPlanner`，冲突判断来自 `SyncRoundMerger`，字节加密来自 `EncryptedArchiveWriter`/`EncryptedArchiveReader`。

```
设备 A                                            设备 B
+--------------------------+                    +--------------------------+
| FileLocalSourceStore     |                    | FileLocalSourceStore     |
|         |                |                    |         ^                |
|         v                |   archive 字节      |         |                |
| SourceSyncSession.Export | -----------------> | SourceSyncSession.Import |
|   EnsureSourceIds        |  (U 盘 / 邮件 /    |   EncryptedArchiveReader |
|   BuildEnvelope          |   手动拷贝；本层   |   SourceSyncLocalBridge  |
|   EncryptedArchiveWriter |   不碰文件系统)     |     -> Planner 决策      |
|   -> byte[] archive      |                    |   -> Apply 写入          |
+--------------------------+                    +--------------------------+
```

同一条往返再走一次就成了双向：B 改完自己的来源后再 `Export`，A 用 `ImportPlain`（或 `Import` 解密后）接收；两侧都改过的条目按 `SourceSyncBaseline` 交给 `SyncRoundMerger` 判，字段级冲突停在审批桶里。

## 二、公开 API

| 成员 | 输入 | 输出 | 关键行为 |
| --- | --- | --- | --- |
| `SourceSyncSession(store, deviceId, clock)` | 本地 store、设备 id、可注入时钟 | — | `deviceId` 空白抛 `ArgumentException`；时钟决定 `createdAtEpochMs` |
| `Export(bool includeSecrets = false, string? passphrase = null, IReadOnlyList<Tombstone>? deletions = null)` | — | `ExportSession` | 无口令给 `EnvelopeJson`；有口令给 `Archive`；`includeSecrets:true` 抛 `SyncContractException(secrets-not-supported)` |
| `Import(byte[] archive, string? passphrase, SourceSyncBaseline? baseline = null)` | 归档字节 | `ImportResult` | 解密失败→`Succeeded=false` + 稳定 `FailureReason`，零写入 |
| `ImportPlain(SourceSyncEnvelope remote, SourceSyncBaseline? baseline = null)` | 明文信封 | `ImportResult` | 三个互斥桶：`Applied` / `Skipped` / `RequiresApproval` |
| `PlanRound(SourceSyncEnvelope remote, SourceSyncBaseline baseline)` | 明文信封 + 基线 | `SyncRoundResult` | 只读预演；不写来源字段（仍缺身份的记录会被补 id，与任何导出相同） |

`ExportSession`：`Envelope`、`EnvelopeJson`（明文时）、`Archive`（加密时）、`Warnings`（`source-skipped:<serverId>:<reason>`、`empty-envelope`）、`IsEncrypted`、`IsEmpty`。

`ImportResult`：`Succeeded`、`FailureReason`、`Envelope`、`Applied`、`Skipped`、`RequiresApproval`、`AppliedCount`、`HasWork`、`IsEmpty`、`Find(id)`、`CountOf(reason)`。每个 `SourceSyncImportEntry` 带 `SyncSourceId`、`Action`、`Reason`（计划器/合并器的码原样上报）、`ServerId`、`DisplayName`、`RequiresUserApproval`。

## 三、不变量与钉住它们的测试

| 不变量 | 实现依据 | 测试 |
| --- | --- | --- |
| 导出永不包含凭据 | `Export` 只调用 `BuildEnvelope(includeSecrets:false)`；`includeSecrets:true` 直接抛 | `Export_PlainEnvelopeCarriesNoSecrets`、`Export_AskingForSecretsIsRefusedLoudly` |
| 冲突、歧义、不支持一律零写入 | 计划器只对 `Update` 与无审批的 `Create` 落盘；其余只在候选里报 | `Import_SameRevisionDifferentContentNeedsApprovalAndWritesNothing`、`Import_AmbiguousLegacyIdentityNeedsApprovalAndWritesNothing`、`Import_KnownButUnsupportedTypeIsReportedNotWritten` |
| 重复导入幂等 | 第二轮计划器判 `up-to-date`，不产生写操作 | `Import_SecondRoundIsAnIdempotentNoOp` |
| 同一份基线下的字段冲突被拦下 | `ImportPlain` 先用 `PlanRound` 取 `RequiresApproval` 的键，从入站信封剔除后再交给计划器 | `Import_WithBaselineWithholdsAFieldConflict` |
| 墓碑只提议不删除 | 计划器 `Delete` 候选恒带 `RequiresUserApproval` | `Import_TombstoneIsAProposalAndNeverADelete` |
| 未知来源类型原样保留 | 导出保留 `SourceType.Raw`；对端标 `unknown-source-type` 且不写 | `Import_UnknownSourceTypeIsKeptOnExportAndRefusedOnImport` |
| 不同账号不合并 | 历史身份匹配要求 `sourceType + 归一化地址 + accountScope` 三者一致 | `Import_ADifferentAccountIsANewSourceNotAMerge` |
| 归档只在内存里 | 本层没有任何文件 API；测试断言目录里只有 `sync-sources.json` 且不含 `MHSYNCA1` | `Import_LeavesNoArchiveOnDisk` |
| 远端 deviceId 与时间戳不落库 | `LocalSourceRecord` 没有这两个字段；测试读文件断言不出现 | `Import_DoesNotWriteTheRemoteDeviceIdOrTimestamp` |
| 时钟只用于盖章，不参与决策 | `Export` 用注入时钟写 `createdAtEpochMs`；合并只比 `recordRevision` | `Export_StampsTheInjectedClockAndADistinctSnapshotId` |

## 四、写入路径只有一条

`ImportPlain` 里所有落盘都经过 `SourceSyncLocalBridge.Apply`。带基线时 `SyncRoundMerger` 只回答「这个键能不能写」，不能写的键从入站信封里剔除，计划器根本看不到它；因此不存在第二套写逻辑，也不会出现「合并器说冲突、计划器却写了」的分叉。

`Skipped` 与 `RequiresApproval` 的区别是「要不要用户看」：`up-to-date`、`stale-revision`、`unknown-source-type`、`not-supported-on-windows` 进 `Skipped`；`same-revision-different-content`、`ambiguous-legacy-identity`、`legacy-identity-match`、`tombstone`、`field-conflict` 进 `RequiresApproval`。

## 五、未覆盖（NOT_IMPLEMENTED）

- 真实网络传输与 QR 配对：本任务的「文件」是一段字节，W6-3b 的 TLS 会话与 ADR-S002 的配对包没有接进来。
- 凭据迁移：`includeSecrets` 一律拒绝；DPAPI 重封与逐源登录验证（ADR-S001 D6/D7）仍是 W6-6。
- App 接线与 UI：导入预览、审批对话框、归档文件的选择与落盘路径、权限，全部归 App 层（W6-2b 之后）。
- 审批后的写回闭环：用户在 UI 上确认冲突后如何重放该决定，尚未设计。
- 基线持久化：`SourceSyncBaseline` 的序列化与落盘未做，目前由调用方在内存里持有。
- Android 侧实现与真机往返验收。
- 大文件/多来源性能：归档载荷上限 8 MiB（ADR-S005），超出直接 `payload-too-large`，没有分片。

## 六、复现命令

```
dotnet build windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter FullyQualifiedName~SourceSyncSessionTests
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```

结果为 `0 个警告 0 个错误`、28/28、全量 `720 / 0 failed`（基线 692 + 28）。
