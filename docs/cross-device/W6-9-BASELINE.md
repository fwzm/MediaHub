# W6-9 —— 同步基线持久化与多端点导入策略

- 状态：**已实现**（W6-9 产出：50 例测试钉住；未接 App UI，见「未覆盖」）
- 日期：2026-10-24
- 前置：[ADR-S001](./adr/ADR-S001-source-sync-contract.md) D2/D5、[ADR-S004](./adr/ADR-S004-delta-sync.md)（增量与合并）、[W6-E2E-IMPLEMENTATION.md](./W6-E2E-IMPLEMENTATION.md)（登记了「基线持久化」缺口）
- 实现：`windows/src/MediaHub.Sync/SourceSyncBaselineCodec.cs`、`ISourceSyncBaselineStore.cs`、`FileSourceSyncBaselineStore.cs`、`InMemorySourceSyncBaselineStore.cs`、`SourceSyncEndpointPolicy.cs`；接线 `SourceSyncSession.cs`、`SourceSyncPlanner.cs`、`SourceSyncLocalBridge.cs`、`SourceSyncCandidate.cs`、`SourceSyncSessionResult.cs`
- 测试：`windows/tests/MediaHub.Tests/SourceSyncBaselineCodecTests.cs`、`SourceSyncBaselineStoreTests.cs`、`SourceSyncEndpointPolicyTests.cs`、`SourceSyncSessionBaselineTests.cs`

## 一、问题

W6-4 的 `SourceSyncBaseline` 只活在内存里：调用方每次新建会话都得重新喂一个基线，忘了喂就等于「什么都不记得」。后果不是报错，而是**每轮都退化成全量快照**——对端把所有来源重新提议一遍，用户看到一屏本已同步的条目，界面上「增量同步」形同虚设。

第二个问题在审计 §5.4 就记下了：Android 一个来源可以有多个地址（`endpoints[]`），Windows 只存一个。W6-1 的导入规则是「取主端点，其余静默丢弃」——静默是本轮要修的部分：用户看不到「还有两个地址没导入」，就会以为对端只配了一个地址。

本轮做两件事，都不碰 App 层：

1. 把基线落成 `%LOCALAPPDATA%\MediaHub\sync-baseline.json`，重启后仍在，并且**只记这一轮真正写进去的记录**；
2. 把未导入的端点显式列出来，配稳定 reason 码，出现在计划摘要与导入结果里。

## 二、基线文档格式

一个文档存所有对端，按对端 `deviceId` 隔离——B 的轮次不会让 A 看起来「已同步」。

```json
{
  "version": 1,
  "peers": [
    {
      "deviceId": "device-a",
      "baseline": {
        "version": 1,
        "deviceId": "device-a",
        "snapshotId": "snapshot-device-a",
        "entries": [
          {
            "syncSourceId": "local-385c7c48494fcb81",
            "recordRevision": 7,
            "fieldVersion": 0,
            "contentHash": "0f3c...",
            "fieldHashes": { "accountScope": "9ab1...", "displayName": "1cc0...", "endpoints": "77e2...", "sourceType": "5d91..." }
          }
        ]
      }
    }
  ]
}
```

序列化是**确定性**的：条目按 `syncSourceId` 排序、字段哈希按字段名排序、字段顺序固定。同一份基线永远得到同一串字节，所以「文件是否变化」可以按字节比较，测试也能钉住形状。

未知字段忽略并计数（`TryDeserialize(..., out ignoredFieldCount, ...)`），给未来加字段留路；缺省值中性：`deviceId`/`snapshotId` 为 null、revision 0、缺 `fieldHashes` 视为「未知」而不是「未变化」。

## 三、原子写与损坏隔离

写路径：同目录 `sync-baseline.json.tmp` → `FileStream.Flush(flushToDisk: true)` → `File.Replace(tmp, file, file + ".bak")`（首次写退化为 `File.Move(overwrite: true)`）→ finally 删 tmp。进程被杀只会留下旧文档或新文档，不会留下半截 JSON；上一版留在 `.bak`，坏文件的恢复路径不是「重新同步一切」。

读路径分三类，语义不同：

| 情形 | 行为 | 能不能继续写 |
| --- | --- | --- |
| 文件不存在 | 空基线（首次运行是正常状态） | 能 |
| 解析失败 / 版本不认 / 形状不符 | 改名 `sync-baseline.corrupt-yyyyMMddHHmmss.json`（重名加 `-1`），`LoadError` 写入 reason，空基线 | 能（坏字节已另存） |
| 文件存在但读不出（锁定、无权限） | `LoadError = store-unreadable`，空基线 | **不能**，`Save` 抛 `IOException` |

最后一行的区别是刻意的：读不出不等于坏，覆盖它可能是覆盖唯一一份用户数据；而解析失败时原始字节已经保存在 `.corrupt-*` 里，写新文档不会丢信息。

## 四、revision 单调保护

`Save` 是 merge，不是 replace：存储层已经知道对端上次是什么样，某轮「学到更少」不能把它推回去。逐条规则：

| 来件 | 结果 |
| --- | --- |
| revision 更高 | 替换该条 |
| revision 相同且 contentHash/fieldVersion/fieldHashes 全同 | 不变（`Unchanged`，不写盘） |
| revision 更低 | 拒绝，保留已存，`RejectedSyncSourceIds` 记 id，reason `baseline-stale-revision` |
| revision 相同但内容不同 | 拒绝，reason `baseline-unresolved-conflict`（先解决冲突，再谈基线） |
| 轮次没提到的 peer / 条目 | 保留 |

拒绝不是错误：它表示「两台设备还需要再谈一轮」。任何地方都不比较墙上时间——排序只看 `recordRevision`（D5）。

## 五、会话接线：只记真正写进去的记录

`SourceSyncSession` 新增第 4 个可选构造参数 `ISourceSyncBaselineStore? baselineStore = null`：

1. `Import` / `ImportPlain` 开头：调用方没显式传基线时，`baselineStore.Load(remote.DeviceId)`；空/损坏的存储降级为「不知道」，于是这一轮是全量快照——吵，但不会猜错。
2. 计划、写入（仍然只有 `SourceSyncLocalBridge.Apply` 一条写路径）不变。
3. 成功之后：只把**这一轮 `Applied` 的记录**做成 `SourceSyncDigest`（`record with { Secrets = null }`）→ `SourceSyncBaseline.Advance(...)` → `Save`。

第三步的「只记 Applied」是关键：冲突、未知类型、墓碑都没有写入，若把它们记成「对端已有」，下一轮就会把没写入的变更判成 `up-to-date`——正是评审层存在要防的静默丢失。

基线写失败不把整轮判失败（源数据已经写下去了），只加一条警告 `baseline-write-failed`；被 revision 保护拒掉的条目记 `baseline-rejected:<n>:<reason>`。两条都在 `ImportResult.Warnings` 里。

## 六、多端点：主端点导入，其余显式列出

`SourceSyncEndpointPolicy` 是三处（计划器、桥接层、计划摘要）共用的唯一规则：

- 主端点 = 标了 `isPrimary` 的端点里 `sortOrder` 最小的；没人标就取全体 `sortOrder` 最小的；完全平手保留发送方顺序。
- 除主端点外**每一条**都进 `NotImportedEndpoints`，`reason = secondary-endpoint-not-imported`，带原始 URL、归一化 URL、`sortOrder`、`isPrimary`、`enabled`。
- `SourceSyncPlan.NotImportedEndpointCount` 与 `ImportResult.NotImportedEndpointCount` 把数量摆到摘要上，UI 可以直接说「另有 2 个地址未导入」。

0 个端点、1 个端点、N 个端点三种情形都在测试里钉住；`Summarize` 另外区分「提供多个地址的记录数」与「一个地址都没有的记录数」。

## 七、reason 码表

| 码 | 出处 | 含义 |
| --- | --- | --- |
| `malformed-json` | codec / file store | 不是 JSON，或根不是对象 |
| `baseline-too-large` | codec / file store | 文档超长（codec 8 MiB、store 16 MiB），或条目/peer 数超限 |
| `baseline-too-deep` | codec / file store | 嵌套超过 16 层（解析前拒绝，不赌栈） |
| `missing-field:version` / `invalid-field:<name>` | codec / file store | 版本缺失/类型不对 |
| `unsupported-schema-version:N` | codec / file store | 更新版本的文档，拒绝而不是猜 |
| `baseline-stale-revision` | merge | 来件 revision 更低，保留已存 |
| `baseline-unresolved-conflict` | merge | 同 revision 不同内容，先解决冲突 |
| `store-unreadable` | file store | 文件存在但读不出；只报告，拒绝写入 |
| `baseline-write-failed` | session | 导入成功但基线没写下去；下一轮降级全量 |
| `baseline-rejected:<n>:<reason>` | session | 基线保留更新版本，n 条被拒 |
| `secondary-endpoint-not-imported` | endpoint policy | 非主端点未导入（数量可见） |

## 八、不变量与对应测试

| 不变量 | 实现位置 | 测试 |
| --- | --- | --- |
| 重启后第二轮仍是 NoChange、零写入 | session 读/写基线 | `Restart_KeepsTheSecondRoundIdempotent`（比较基线文件字节、源文件字节、`up-to-date` 原因） |
| 只记真正写入的记录 | `PersistBaseline` + `AppliedDigest` | `Tombstone_IsProposedButNotRecorded`、`UnsupportedType_IsNotRecorded`、`Baseline_RecordsAppliedRecordsOnly` |
| 冲突不写、也不记 | `Conflict_IsNotRecordedAsApplied` | 同上 |
| 每 peer 独立 | 文档 `peers[]` | `BaselineIsKeyedByPeerDevice`、`PeersAreIsolated` |
| 原子写、无 `.tmp` 残留、保留 `.bak` | `Persist` | `Save_LeavesNoTemporaryFileBehind`、`Save_KeepsThePreviousDocumentAsBak`、`ConcurrentSaves_ProduceOneParseableDocument` |
| 损坏隔离不删数据 | `Quarantine` | `CorruptDocument_IsQuarantinedAndTreatedAsEmpty`（逐字节比对原文件） |
| 版本闸门 | codec / store | `Deserialize_RefusesANewerSchema`、`NewerDocumentVersion_IsQuarantined` |
| revision 单调 | `SourceSyncBaselineMerge` | `StaleRevision_IsRejectedWithoutRewritingTheFile`、`SameRevisionDifferentContent_IsRejected`、`InMemoryStore_CountsWritesAndProtectsRevisions` |
| 未变更不写盘 | store `Changed` 判断 | `UnchangedSaveDoesNotRewriteTheFile` |
| 主端点唯一且确定 | `SourceSyncEndpointPolicy.SelectPrimary` | `SelectPrimary_PrefersTheMarkedEndpointEvenWhenItSortsLater`、`SelectPrimary_FallsBackToTheSmallestSortOrder`、`SelectPrimary_TieKeepsTheSendersOrder` |
| 未导入端点显式可见 | policy + plan + import | `NotImported_ListsEveryOtherEndpointWithAReason`、`Plan_SurfacesTheNotImportedCount`、`Import_ReportsNotImportedEndpoints` |

## 九、未覆盖（NOT_IMPLEMENTED）

- **App / UI 接线**：设置页没有「同步基线」视图，审批界面也没有展示 `NotImportedEndpoints`；本层只提供数据结构。
- **跨进程文件锁**：同一目录只允许一个进程写（`File.Replace` 保证单文件原子，但没有跨进程锁）。两个进程同时写同一 `sync-baseline.json` 时后写者可能抛 `IOException`，不会产生半截文件。
- **多端点并行导入**：本轮只记录未导入端点，不提供「改用第二个地址重试」；将来需要多地址存储（`LocalSourceRecord` 单 URL 是既有约束）。
- **Android 侧读取 Windows 基线**：两端都还没交换基线本身（只有增量条目），W6-10 之后才谈「双方各自持有对端基线」的协商。
- **CLI 的基线命令**：`MediaHub.SyncCli` 的 `--baseline` 是把一个信封转成基线用于演练，不是读写 `sync-baseline.json`。
- **每个 peer 独立文件**：当前一个文档存所有 peer（上限 64），peer 极多时需要分文件或分页，未做。

## 十、复现命令

```
dotnet build windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter "FullyQualifiedName~SourceSyncBaselineCodecTests|FullyQualifiedName~SourceSyncBaselineStoreTests|FullyQualifiedName~SourceSyncEndpointPolicyTests|FullyQualifiedName~SourceSyncSessionBaselineTests"
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```

实测（2026-10-24）：构建 `0 个警告 0 个错误`；新测试 `通过: 50`；全量 `通过: 968，失败: 0`（基线 918 + 50）。
