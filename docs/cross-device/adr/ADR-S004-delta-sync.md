# ADR-S004 —— 双向增量同步：内容哈希基线、字段级冲突才需确认

- 状态：**已提议**（W6-4 产出：增量摘要、双向合并、设备注册表已实现，57 例测试钉住）
- 日期：2026-10-23
- 前置：[ADR-S001](./ADR-S001-source-sync-contract.md) D2（信封与记录字段）、D3（wire 类型词汇表）、D5（禁止跨设备墙上时间 LWW）、D6（安全边界）；[ADR-S003](./ADR-S003-transport.md)（传输层）
- 实现：`windows/src/MediaHub.Sync/SourceSyncFields.cs`、`SourceSyncContentHash.cs`、`SourceSyncBaseline.cs`、`SourceSyncDigest.cs`、`SyncRoundEntry.cs`、`SyncRoundResult.cs`、`SyncRoundMerger.cs`、`SyncDeviceRecord.cs`、`SyncDeviceRegistry.cs`、`SyncDeviceRegistryCodec.cs`
- 测试：`windows/tests/MediaHub.Tests/SourceSyncDeltaTests.cs`（36 例）、`windows/tests/MediaHub.Tests/SyncDeviceRegistryTests.cs`（21 例）

## 问题

W6-1 定下信封与记录字段，W6-2 定下本地桥接，W6-3 定下配对传输，但「两台设备各自改过之后，下一次同步该传什么、谁的修改算数」没有写死。

留白时最省事的实现一定是：每次传全量、用 `createdAtEpochMs` 比新旧、后写覆盖。这条路线的坏结果很具体 ——

- 两台机器时钟差三分钟，慢的那台永远输，而且是静默的：用户看到的是「我刚改的地址又变回去了」。
- 一台离线改过显示名、另一台改过地址，整条记录被判成冲突，用户被迫在「全部保留 A」和「全部保留 B」之间二选一，尽管两个编辑根本不冲突。
- 一次误删在回环里变成两端都消失，凭据随之蒸发。
- 设备注册表按显示名合并（两台设备都叫「我的手机」是常见情况），「已同步到哪一版」被记到错的设备上，下一次同步的判断基准就是错的。

本 ADR 把这四件事写成契约：增量怎么算、冲突怎么定、删除怎么处理、设备怎么认。

## 决策

### D1：基线是内容哈希，不是时间戳

- 基线条目 `SourceSyncBaselineEntry` 存四样：`RecordRevision`、`FieldVersion`、`ContentHash`（整条记录的规范 JSON 的 SHA-256，64 位小写 hex）、`FieldHashes`（逐字段哈希：`accountScope` / `displayName` / `endpoints` / `sourceType`）。
- 规范 JSON 由 `SourceSyncContentHash.Canonicalize` 生成，规则全部固定：键按字母序、`endpoints` 按 `(sortOrder, url, isPrimary, enabled)` 排序、URL 过 `SyncUrlNormalizer.Normalize`、`accountScope` 先 `Trim` 再转小写、`sourceType` 用 wire 串而不用枚举名。**哈希的稳定性完全依赖这些规则**：端点数组换个顺序就换哈希，于是全量重传。
- **禁止 `GetHashCode`**：`string.GetHashCode` 在 .NET 里进程内随机化，重启后基线永远失配；而且它不保证跨端一致。本层只用 SHA-256。
- 为什么是哈希而不是时间：ADR-S001 D5 已经禁止用不同设备的本地墙上时间做 LWW。哈希只回答「这两份内容是否相同」，不回答「谁更晚」，后者交给 `RecordRevision`（同一条来源的编辑序号），前者是唯一能跨端、跨重启都成立的判断。

### D2：增量摘要只发变化，但「版本动了内容没动」也要发

- `SourceSyncDigest.Build(records, baseline, deletions)` 的规则：基线为空 → 全量（每条 `Added`，`ChangedFields` = 全部四个字段）；与基线 `RecordRevision` + `FieldVersion` + `ContentHash` 全同 → **不出条目**；否则出 `Updated`。
- `Updated` 的 `ChangedFields` 由逐字段哈希求差得到；若只是 `RecordRevision` 变了、四个字段哈希全同，则 `ChangedFields` 为空、`ContentChanged = false`。这种「空更新」是**故意**发出的：对端靠它推进自己的基线，否则会一直以为本地落后一版。
- 本地 `RecordRevision` 低于基线时不出条目（本地这份是旧的，等对端把它补齐，不许倒着推）。
- 每条 `Record` 必须有 `syncSourceId` 与已知 wire 类型；缺失、重复 id、tombstone 撞上仍然存在的记录，一律进 `Issues` 而不是静默丢弃 —— 摘要的条目数对不上时，宁可让上层看见问题。
- 闭环条件写进测试：`SourceSyncBaseline.Advance(baseline, digest)` 之后再对同一批记录 `Build`，结果必须为空（`IsEmpty`）。这条不成立就说明 digest 与基线口径不一致，同步会永远重发同一批记录。

### D3：字段级冲突才需要用户确认，其余按 revision 定序

- 两侧都改且内容不同时，先算 `ConflictingFields` = 两侧 `ChangedFields` 的交集（Ordinal 排序，结果稳定）。
- **只有交集非空、且两侧 `FieldVersion` 相同**，才判 `field-conflict` 并要求审批，`ConflictingFields` 随条目一起上报（UI 必须把它显示成「这两个字段被两边同时改过」，而不是一条笼统的冲突）。
- 交集为空 = 两边改的是不同字段（A 改地址、B 改显示名）→ 不是冲突，按 `RecordRevision` 高者胜（`remote-newer` / `local-newer`）。真正的字段拼合（A 的地址 + B 的名字）留给应用层做，本层只负责不把可合并的编辑误报成冲突。
- 交集非空但两侧 `FieldVersion` 不同 → 仍按 revision 高者胜：字段版本不同意味着两边基于不同的字段语义写，较新的一侧已经覆盖了旧一侧的字段集合，此时再逐字段比交集只会把已经解决的差异重新报警。
- `RecordRevision` 相等而内容不同 → `same-revision-different-content`，必须审批：revision 已经不能定序，任何自动选择都是猜。
- 两侧内容哈希相同（各自独立改成了同一份）→ 不判冲突，按 revision 高者补一次 `remote-newer` / `local-newer`；revision 也相同则 `already-applied` 忽略。

### D4：删除与修改相遇必须审批，tombstone 永不自动删

- 本地删 + 对端改 → `delete-vs-update`（审批）；对端删 + 本地改 → `update-vs-delete`（审批）；两侧都删 → `both-deleted`（忽略，结果一致）。
- 对端 tombstone 单独到达、本地仍有记录 → `tombstone-requires-approval`（审批）。tombstone 早于基线（`revision` 更低）→ `stale-remote`；本地 tombstones 早于基线 → `stale-local`；基线里根本没有这条来源 → `tombstone-unknown`。早到的删除不推翻更新的记录，也不给对端「补删一次」的机会。
- 为什么删除单独一条规则：删除是**破坏性且不可恢复**的（来源记录里的凭据一起没了），自动执行意味着一次误删会顺着同步链条传播到所有设备，而用户只在最开始那台设备上误操作过一次。桥接层 `SourceSyncLocalBridge.Apply` 里 `Delete` 候选从不落盘，理由相同：审批必须发生在真的写入之前。

### D5：未知类型、不支持类型、携带 secrets 的入站记录一律审批

- 判定顺序固定：`unknown-source-type`（wire 不在词汇表，原串保真）> `not-supported-on-windows`（已知但 Windows 侧无实现，如 `quark-drive`）> `secrets-require-approval`（`Secrets` 非 null）> 之后才轮到 revision 规则。
- 顺序是硬约束：先定性再定序。若先按 revision 判定，「对端 revision 更高」会变成一个绕过类型检查与凭据审批的通道 —— 恰好是 ADR-S001 D6 要堵的静默覆盖凭据。
- 本层从不合并、解密、重写 `Secrets`：它只是 `JsonElement`，随记录透传；任何需要落盘或覆盖凭据的动作都是审批后的应用层行为。

### D6：设备注册表以 `deviceId` 为主键，禁止按显示名合并

- `SyncDeviceRegistry` 纯内存（`{ "version": 1, "devices": [...] }` 由 `SyncDeviceRegistryCodec` 负责，无 IO），`lock` 保护，`GetAll()` 保持首次注册顺序并返回副本。
- `Register` 幂等：已存在则保留首次写入的 `DeviceId` 拼写、空白 `DisplayName` 不覆盖旧值、`LastSeenEpochMs` 取 `Math.Max`（时间只前进）、`LastAppliedSnapshotId` 只接受非空新值；记录完全相等时 `Changed = false` 且 `WriteCount` 不动。
- 为什么不用显示名做键：`DisplayName` 是用户随时可改的展示字符串，两台设备同名是常态；按名字合并会把「已同步到哪个快照」记到另一台设备上，于是下一次同步的基线就是错的 —— 这类错误没有任何可见症状，直到数据对不上。设备名相同但 `deviceId` 不同，就是两台设备。
- `MarkSnapshotApplied` 返回 `SyncDeviceRegistration`（未知设备返回 null，同一快照第二次返回 `Changed = false`）；`Remove` 只清本地视图，不影响对端。
- codec 对未知版本 `version != 1` 直接拒绝（`SyncContractException` 的 `unsupported-schema-version:N`），不猜字段语义；未知字段容忍；文档内重复 `deviceId` 后者覆盖、保留首次位置。

## 后果

- 收益：传输量与变更集成正比；冲突只在真的需要判断的地方出现；删除和凭据永远走审批；设备列表可以放心序列化。
- 代价：哈希口径一旦改动，所有设备会认为「内容变了」并全量重传一次 —— 所以 `SourceSyncFields.All` 与 `SourceSyncContentHash.Canonicalize` 是**契约**，改它们等于改协议版本。
- 代价：`ConflictingFields` 只做识别，不做自动拼合；拼合后写回需要应用层按字段调用 `ILocalSourceStore.Save`，并自己保证 revision 递增。
- 未覆盖（留给后续）：字段级拼合写回、传输层重传/分页/断点、`secrets` 加密通道（ADR-S001 D6 的 TLS 通道之外的落盘加密）、App 侧审批 UI、Android 端实现本契约。

## 附：合并 reason 码表

| 码 | 落桶 | 含义 |
| --- | --- | --- |
| `push-local-change` | Apply | 仅本地变，推给对端 |
| `push-local-delete` | Apply | 仅本地删，推 tombstone |
| `apply-remote-change` | Apply | 仅对端变，应用到本地 |
| `remote-newer` | Apply | 两侧同内容不同版本，对端较新 |
| `local-newer` | Apply | 两侧同内容不同版本，本地较新 |
| `already-applied` | Ignore | 对端版本不高于基线 |
| `both-deleted` | Ignore | 两侧都删 |
| `stale-remote` | Ignore | 对端 tombstone 早于基线 |
| `stale-local` | Ignore | 本地 tombstone 早于基线 |
| `tombstone-unknown` | Ignore | 基线里没有这条来源的删除 |
| `delete-vs-update` | 审批 | 本地删、对端改 |
| `update-vs-delete` | 审批 | 对端删、本地改 |
| `tombstone-requires-approval` | 审批 | 删除待用户确认 |
| `field-conflict` | 审批 | 同字段被两侧同时改，带 `ConflictingFields` |
| `same-revision-different-content` | 审批 | revision 相同、内容不同 |
| `unknown-source-type` | 审批 | wire 类型不在词汇表，原串保真 |
| `not-supported-on-windows` | 审批 | 已知但 Windows 侧不支持 |
| `secrets-require-approval` | 审批 | 入站记录携带凭据 |

同一条目不得同时出现在两个桶：`SyncRoundResult.Create` 对重复 key 抛 `InvalidOperationException`，这是构造期断言，不是运行时分支。
