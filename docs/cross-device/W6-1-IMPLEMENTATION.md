# W6-1 —— 跨设备来源同步契约层实现说明

- 状态：**已实现**（纯逻辑契约层；尚未接线，见第 5 节）
- 日期：2026-10-23
- 分支：`feature/cross-device-source-sync`（基线 HEAD `83e6876`）
- 前置：[ADR-S001](adr/ADR-S001-source-sync-contract.md)、[W6-0-AUDIT.md](W6-0-AUDIT.md)
- 范围：新增 `windows/src/MediaHub.Sync/**`；测试 `windows/tests/MediaHub.Tests/SourceSyncContractTests.cs`；`MediaHub.Windows.sln` 与测试项目引用

## 1. 边界

本轮只做**能离线验证的纯逻辑**：词汇表、地址规范化、信封编解码、逐条同步计划。
不涉及网络、GUI、凭据读取或写入。App 层（`windows/src/MediaHub.App/**`）**零改动**——接线属 W6-2。

契约层存在的理由是审计发现的三处现实冲突，它们不能靠"调用方小心"解决，必须由代码钉死：

| 审计条目 | 冲突 | 本轮对策 |
| --- | --- | --- |
| §5.1 | Android 12 个 `sourceType`、Windows 只有 7 个 `ServerType`，交集 6 个且大小写不同 | 同步层自有小写线格式 + 显式双向映射表（第 3.1 节） |
| §5.2 | Android 显式写默认端口、Windows 省略，同一台 `http://host:80/` 会变成两个来源 | 同步层自定义 `syncNormalize`，端口恒显式（第 3.2 节） |
| §5.3 | Windows `ServerEntry.ServerId` 是本地派生值（`type:remoteIdOrHost`），随地址变化 | 引入独立的 `syncSourceId`（UUID），与本地 `ServerId` 解耦 |
| §5.4 | Windows 端点单 URL、Android 多端点 | 只取主端点参与计划，其余端点不静默丢弃（候选里保留完整列表） |

## 2. 交付物清单

| 文件（`windows/src/MediaHub.Sync/`） | 职责 |
| --- | --- |
| `MediaHub.Sync.csproj` | `net10.0`，仅 `ProjectReference` → `MediaHub.Domain`，**零 NuGet** |
| `SyncSourceTypes.cs` | 12 个线格式常量、`All`、`IsKnown(string?)`、`Parse` |
| `SyncSourceTypeValue.cs` | 保真值对象：`Raw` / `Wire` / `IsKnown` |
| `WindowsSourceTypeMap.cs` | 线格式 ↔ `ServerType` 显式双向映射，`Resolve` 返回 `IsKnown`/`IsSupported` |
| `SyncUrlNormalizer.cs` | `Normalize(string?)` / `TryNormalize`，幂等 |
| `SourceSyncEnvelope.cs` | 信封 + `SyncCapabilities` |
| `SourceRecord.cs` | 来源记录 + `SourceEndpoint`（`secrets` 保持不透明 `JsonElement`） |
| `Tombstone.cs` | 墓碑 |
| `SyncContractException.cs` | 稳定 `Reason` 码的契约异常 |
| `SourceSyncCodec.cs` | `Serialize` / `Deserialize` / `TryDeserialize`，camelCase JSON |
| `LocalSourceRecord.cs` | 设备侧来源（含可为空的 `SyncSourceId`，兼容 W6 前历史记录） |
| `SourceSyncAction.cs` | 动作枚举 + `SourceSyncReasons` 13 个原因常量 |
| `SourceSyncCandidate.cs` | 单条计划结果 + `SourceSyncPlan` 汇总 |
| `SourceSyncPlanner.cs` | `Plan(envelope, locals)`：纯静态、无 IO、无墙上时间 |

测试：`windows/tests/MediaHub.Tests/SourceSyncContractTests.cs`（44 个测试方法，`[Theory]` 展开后 **83 例**）。

## 3. 契约要点

### 3.1 类型词汇表与映射（D3）

线格式固定 12 个小写串，**绝不传枚举名**：
`emby` `jellyfin` `plex` `webdav` `smb` `local` `fn-nas` `aliyun-drive` `baidu-drive` `quark-drive` `china-mobile-cloud` `tianyi-cloud`。

Windows 侧只映射 6 个：`emby`/`jellyfin`/`plex`/`webdav`/`smb`/`local` → 同名 `ServerType`。
`WindowsSourceTypeMap.Resolve` 区分三态：

- `IsKnown = false`（协议外的值）→ 计划器给 `Unsupported` + `unknown-source-type`，**原串随候选保留**；
- `IsKnown = true, IsSupported = false`（`fn-nas` 与 Android 独有 5 个云盘）→ `Unsupported` + `not-supported-on-windows`；
- 两者皆真 → 正常参与计划。

`SyncSourceTypeValue` 同时保存 `Raw`（逐字）与 `Wire`（trim + 小写），所以"已保存但暂不支持"的来源在往返后仍然是用户当初那个串，不会被改写成 `unknown`。`WindowsSourceTypeMap.ToWire(ServerType.Unknown)` 返回 `null` 而不是伪造 `"unknown"`。

### 3.2 地址规范化（D4）

`SyncUrlNormalizer.Normalize` 规则：

- 只接受 `http` / `https` / `smb` / `file`；其余 scheme → `null`（不是抛异常，调用方按"无可用地址"处理）；
- 无 scheme 时补 `http://`；但输入里含 `@` 时返回 `null`——粘贴进来的"账号:密码@主机"不能被当作主机名，宁可拒绝；
- `//host` → `http://host`；
- `http`/`https` **端口恒显式**：缺省补 `80`/`443`，`port <= 0` 拒；非 http scheme 只在显式写了端口时保留；
- scheme + host 小写；path 去尾斜杠（`/` → 空）；丢弃 query 与 fragment；剥掉 userinfo；
- 幂等：`Normalize(Normalize(x)) == Normalize(x)`。

这是**同步层自己的**口径，与 Windows 既有的 `MediaHub.Net.ServerUrlNormalizer`（省略默认端口、拒绝 query）以及 Android `ServerAddressIdentity`（保留 query）都不同——跨端身份不能复用任何一端。

### 3.3 信封编解码（D2）

`SourceSyncCodec.Serialize` 先 `Validate` 再写 JSON：camelCase、缩进、`sourceType` 写 `Raw`、`secrets` 原样透传（`JsonElement.WriteTo`），可选字段写全默认值以便对端解析。

`Deserialize` 的容错边界：

- 未知字段忽略；未知 `sourceType` 保留；
- 缺 `schemaVersion` → `missing-field:schemaVersion`；`≠ 1` → `unsupported-schema-version:N`；
- `minimumReaderVersion` 缺失 → `missing-field`；`< 1` → `invalid-field`；`> 1` → `unsupported-minimum-reader-version:N`（本端读不动的语义，拒绝比误读安全）；
- 必填字段（`deviceId`、`snapshotId`、`createdAtEpochMs`、`sources[].syncSourceId`、`sourceType`、`recordRevision`、`endpoints[].url`、`deletions[].syncSourceId`）缺失或空 → `missing-field:<name>`；
- 坏 JSON → `malformed-json`；
- 可选默认：`fieldVersion = 0`、`enabled = true`、`isPrimary = false`、`sortOrder = 0`、`accountScope = null`、`displayName = ""`；
- `TryDeserialize` 不抛，返回 `false` + 原因串。

### 3.4 计划器（D5）

`SourceSyncPlanner.Plan` 是纯函数：相同输入永远相同输出，不读时钟、不读磁盘。逐条来源按**固定优先级**判定：

| 条件 | 动作 | 原因 | 需用户确认 |
| --- | --- | --- | --- |
| `sourceType` 不在 12 个词汇表内 | `Unsupported` | `unknown-source-type` | 否（保留原串） |
| 已知但 Windows 不支持 | `Unsupported` | `not-supported-on-windows` | 否 |
| 同一 `syncSourceId` 命中多条本地记录 | `Conflict` | `duplicate-local-sync-id` | 是 |
| 远端 `recordRevision` > 本地 | `Update` | `higher-revision` | 否 |
| 相等且内容相同 | `NoChange` | `up-to-date` | 否 |
| 相等但内容不同 | `Conflict` | `same-revision-different-content` | 是 |
| 远端 < 本地 | `IgnoreStale` | `stale-revision` | 否 |
| 无 `syncSourceId` 命中，但 (类型, 归一化主端点, `accountScope`) 唯一匹配一条历史记录 | `Adopt` | `legacy-identity-match` | 是 |
| 同上但匹配到多条 | `Conflict` | `ambiguous-legacy-identity` | 是（附候选 `ServerId`） |
| 无匹配、无可用主端点 | `Create` | `no-usable-endpoint` | 是 |
| 无匹配、有主端点 | `Create` | `new-source` | 否 |

墓碑（`deletions[]`）：无本地匹配 → `NoChange` + `no-local-source`；多条匹配 → `Conflict` + `duplicate-local-sync-id`；`tombstone.recordRevision` < 本地 → `IgnoreStale`；否则 `Delete` + `tombstone`，且 `RequiresUserApproval = true`。

"内容相同"的定义：线格式类型 + `displayName` + `accountScope`（trim、空串视同 `null`、忽略大小写）+ 归一化后的主端点地址。主端点 = `isPrimary` 中 `sortOrder` 最小者；无 `isPrimary` 则全体 `sortOrder` 最小者。

三条硬约束：

1. **不比较墙上时间**。`createdAtEpochMs` / `deletedAtEpochMs` 只在传输层存在，计划器从不读取——不同设备的本地时钟不可比，LWW 会静默丢数据；
2. **不静默覆盖**。任何可能丢数据或丢凭据的条目（`Delete`、`Adopt`、`Conflict`、`no-usable-endpoint`）都置 `RequiresUserApproval`；
3. **不静默合并**。跨账号（`accountScope` 不同）或跨类型永不匹配；历史记录候选必须唯一，否则交用户裁决。

## 4. 实现决策与相对任务书的偏差

| # | 事项 | 决定 | 理由 |
| --- | --- | --- | --- |
| 1 | 墓碑 revision 过时 | `IgnoreStale`，**不**提议删除 | 任务书只写了"tombstone → Delete 候选"；但远端删除之后本地又被编辑时，按墓碑删本地会毁掉更新的数据。删除永远可重发，误删不可恢复 |
| 2 | 新建来源但无可用地址 | `Create` + `no-usable-endpoint`，并要求确认 | 默认进库会产生一条用户只能手动删除的空条目 |
| 3 | 同一 `syncSourceId` 命中多条本地记录 | `Conflict` + 审批 | 静默取第一条等于随机丢一条 |
| 4 | 无 scheme 且含 `@` 的地址 | `Normalize` 返回 `null` | 避免把粘贴的"用户:密码@主机"当成主机名存进来源身份 |
| 5 | `SourceSyncException` 之外 | 只抛 `SyncContractException`，`Reason` 为稳定码 | 传输层与 UI 可据此分支，不依赖异常消息文本 |
| 6 | `secrets` 类型 | 不透明 `JsonElement?`，编解码原样透传 | 凭据结构属 Android ADR-016 与 DPAPI 层，契约层不解释、不校验、不记录 |

## 5. NOT_IMPLEMENTED

以下全部**未做**，本轮只交付契约层：

- **QR 配对**：二维码生成/扫描、一次性挑战、公钥指纹校验——一行代码都没有；
- **传输层**：LAN 发现、TLS 1.3 + 证书钉扎、分片/重试/断点续传；
- **凭据迁移**：Windows 侧 DPAPI 重封、Android 侧 Keystore 解封；本轮只定义 `capabilities.canSendSecrets` 与不透明 `secrets` 字段；
- **Android 侧实现**：`SourceSyncEnvelope` 的 Kotlin 数据类、`syncNormalize` 的 Kotlin 版本、两端交叉验证；
- **App 接线**（W6-2）：`ServerManager` 增加 `syncSourceId` 持久字段、同步预览 UI、逐源验证状态（可直接用 / 需重新登录 / 暂不支持）；
- **多端点导入**：按 §5.4 只取主端点参与计划，其余端点如何在 UI 列出"未导入"尚未实现；
- **持久化**：契约层不带存储，`syncSourceId` 落到 `%LOCALAPPDATA%\MediaHub\` 的文件格式未定。

D7 的"同步成功 ≠ 已登录"是 W6-2 的验收项，本轮无法验证。

## 6. 验证与复现

```powershell
# 契约层单独构建（0 警告 0 错误）
dotnet build D:/mh-w6/windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo

# 全量测试（基线 373 + 新增 83 = 456）
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo

# 只跑契约测试
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter FullyQualifiedName~SourceSyncContractTests
```

实测结果：

- build：`已成功生成。    0 个警告    0 个错误`
- test：`已通过! - 失败:     0，通过:   456，已跳过:     0，总计:   456，持续时间: 4 s - MediaHub.Tests.dll (net10.0)`
- 契约测试过滤：`通过: 83`

环境注意：本仓库是 git worktree，`windows/native/libmpv/`（gitignored）为空，而测试项目间接引用 `MediaHub.Player.Mpv`，其 `VerifyLibMpv` 目标会在缺 DLL 时直接 fail：
`error : libmpv-2.dll not found in '...\native\libmpv'. Run: pwsh -File windows/tools/fetch-libmpv.ps1`。
跑测试前需执行该脚本，或从已有检出复制 `libmpv-2.dll` 到 `windows/native/libmpv/`。这与本轮的 Sync 契约层无关。

## 7. 测试覆盖（`SourceSyncContractTests`）

| 关注点 | 覆盖的规则 |
| --- | --- |
| 词汇表 | 12 值全集与顺序、`IsKnown` 忽略大小写与空白、`Raw` 保真 |
| 映射 | 6 对双向、`Unknown` 不映射、Android 独有 6 值 → `IsKnown && !IsSupported` |
| 规范化 | 端口恒显式、大小写、去尾斜杠、丢 query/fragment/userinfo、`//` 补 scheme、非 http 不凭空造端口、非法输入 `null`、**幂等**、两端样例归一后相等 |
| 编解码 | 字段名 camelCase、未知字段容忍、可选字段默认值、未知 `sourceType` 往返、`schemaVersion`/`minimumReaderVersion` 拒绝、必填缺失、坏 JSON、`secrets` 透传 |
| 计划器 | 全部分支；同 revision 内容不同 → `Conflict`（不静默覆盖）；跨账号不合并；跨类型不合并；历史记录歧义需审批；墓碑需审批；**`createdAtEpochMs`/`deletedAtEpochMs` 变化不影响决策** |
