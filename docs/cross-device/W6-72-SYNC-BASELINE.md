# W6-72 同步基线：Android 侧新增持久化基线 + 多端点策略对齐

> 权威实现：`D:/mh-w6/windows/src/MediaHub.Sync/`（`SourceSyncBaseline` / `SourceSyncBaselineCodec` / `SourceSyncContentHash` /
> `ISourceSyncBaselineStore` / `InMemorySourceSyncBaselineStore` / `FileSourceSyncBaselineStore` / `SourceSyncEndpointPolicy`）。
> 本轮 Android 侧补齐对等实现，并用 Windows 权威实现产出的黄金向量做逐字节对照。

## 1. 为什么做这一层

W6-71 为止 Android 的同步链只有「导入」一条：`AndroidSourceSyncService.apply()` 每次都用 `store.listLocalSources()`
现算，**没有「上一轮对端是什么样」的记忆**。后果有两条：

- 每轮退化成全量快照，`no-change` 只能靠本地修订号偶然命中；
- 次要端点被 `normalizedEndpoints()` 静默保留在库里，却从不告诉用户「这些线路没有参与同步」。

## 2. 契约（与 Windows 逐条相同）

| 契约点 | 规则 |
| --- | --- |
| 内容哈希 | 规范 JSON，字段序 `accountScope`/`displayName`/`endpoints`/`sourceType`；端点按 `(sortOrder,url,isPrimary,enabled)` 排序，每项键序 `enabled`/`isPrimary`/`sortOrder`/`url`；SHA-256 取 64 位小写 hex |
| 空白与小写 | 一律 `WireText`：空白 = 25 个 Unicode White_Space 码点；大小写只折 ASCII A–Z |
| 基线编码 | `{version:1, deviceId, snapshotId, entries:[...]}`；条目不按内容排序，按 `syncSourceId` 的 `(OrdinalIgnoreCase, Ordinal)` 排序 |
| JSON 字节 | 复刻 `Utf8JsonWriter{Indented=true}`：CRLF、2 空格、无尾随换行；8 个 ASCII 特殊字符走 `\uXXXX` 等；其余 ≥0x7F 写大写 `\uXXXX` |
| 读判定序 | 空白 → `malformed-json`；超长 → `baseline-too-large`；**先扫括号深度**（>16 → `baseline-too-deep`）；解析失败 → `malformed-json`；`version` 缺失/非数字/不等 → 三种码；`entries` 非数组等 → `invalid-field:<name>`；重复 id **后者胜**；未知字段计数 |
| 存储语义 | 合并而非替换；拒绝倒退（`baseline-stale-revision`）、拒绝同号不同内容（`baseline-unresolved-conflict`）；被拒条目**绝不改动**已有内容 |
| 文件文档 | `{version:1, peers:[{deviceId, baseline}]}`，peers 按 deviceId 排序，`sync-baseline.json`，最大 64 peers / 16 MiB；坏档隔离成 `sync-baseline.corrupt-<yyyyMMddHHmmss>[-N].json` |
| 端点策略 | `secondary-endpoint-not-imported`；`selectPrimary` 取「标记 primary 且 sortOrder 最小」，否则 sortOrder 最小；只跳过**引用相同**的那一个主端点（重复行仍报） |

## 3. 本轮唯一一处 Windows 行为变更（有意的）

`SourceSyncContentHash.NormalizedScope`：`Trim().ToLowerInvariant()` → `WireText.Trim` + `WireText.LowercaseAscii`；
`NormalizedUrl` 的空白判定也换成 `WireText.IsBlank` / `WireText.Trim`。

原因：**全 Unicode 小写在 .NET 与 JVM 上无法一致**（实测：U+0130 在 .NET 保持不变、JVM 给 `i`+U+0307；希腊 `Σ-A-Σ`
两端词尾 sigma 不同）。基线是落盘格式，两端必须对同一份输入给出同一个哈希 ⇒ 只能统一到 ASCII-only 折叠
（与 W6-67 的「大小写只折 ASCII」口径一致）。

**刻意不改**：`SourceSyncLocalBridge.cs:373` 派生 syncSourceId 的种子串仍是 `ToLowerInvariant()`——
那串一变，已入库来源的 `syncSourceId` 全部漂移，代价远大于这处口径差。

## 4. 另一处两端统一：`baseline-too-deep` 的分类

原先两端都用「JSON 解析器异常消息里有没有 depth」分类。实测 .NET 对平凡截断的 `{` 报
`Expected depth to be zero at the end of the JSON payload.`——消息含 depth，于是 `{` 被误判成 `baseline-too-deep`；
JVM 没有 MaxDepth 选项，只能预扫描，判 `malformed-json`。同一份坏输入两端不同码。

改为**先扫括号深度再解析**：C# `WireText.ExceedsDepth(text, 16)` / Kotlin `WireJson.exceedsDepth(text, 16)`，
解析器 catch 只回 `malformed-json`。改完 `{` 恢复 `malformed-json`，真正 17 层嵌套仍是 `baseline-too-deep`。

## 5. 黄金向量

| 项 | 值 |
| --- | --- |
| 生成器 | `D:/mh-verify/baseline-vectors/`（临时 net10.0 工程，ProjectReference 指向 `MediaHub.Sync.csproj`，不入库） |
| 文件 | 主树 `core/common/src/test/resources/sync/baseline-vectors.json` = W6 树 `windows/tests/assets/baseline-vectors.json`（逐字节同一份） |
| 大小 / sha256 | **143420 B** / `e7354c831c7a38c10a3200515c43d3af03568465861e5531952338aae22d4e0c` |
| 段 | `contentHashCases`(11) / `baselineCases`(9) / `findCases`(7) / `codecWriteCases`(4) / `codecLoadCases`(35) / `mergeCases`(14) / `inMemoryCases`(7) / `fileDocumentCases`(3) / `fileLoadCases`(17) / `endpointPolicyCases`(7) / `endpointSummaryCases`(3) |

向量**连输入一起存**（record / baseline / digest / 待写入内容都在向量里），所以两端用例是从输入复算再比期望，不是抄答案。

## 6. 用例

| 套件 | 例数 | 位置 |
| --- | --- | --- |
| `BaselineContractTests` | 13 `[Fact]` | `D:/mh-w6/windows/tests/MediaHub.Tests/BaselineContractTests.cs`（599 行） |
| `SourceSyncBaselineContractTest` | 12 `@Test` | 主树 `core/common/src/test/kotlin/…/sync/`（480 行） |
| `SourceSyncBaselineWiringTest` | 7 `@Test` | 主树 `core/database/src/test/kotlin/…/sync/`（285 行） |
| `SourceSyncImportSessionEndpointTest` | 3 `@Test` | 主树 `feature/settings/src/test/kotlin/…/sync/`（170 行） |

C# 比 Kotlin 多一条 `BaselineCases`：`advance` 系列依赖 `SourceSyncDigest`，Android 侧没有对等物，由 C# 孪生覆盖。

## 7. 实跑

| 步骤 | 结果 |
| --- | --- |
| Kotlin 定向 | `SourceSyncBaselineWiringTest` 7/7、`SourceSyncBaselineContractTest` 12/12 |
| Android 三模块 | `BUILD SUCCESSFUL in 4m 19s`、`167 actionable tasks: 167 executed`；core/common **655**（48 套件）、core/database **199**（21）、feature/settings **384**（26），0 失败 |
| C# 全量 | `已通过! - 失败: 0，通过: 1648，已跳过: 0，总计: 1648`（1635 → 1648） |
| 门禁脚本自测 | `Ran 54 tests … OK` |

## 8. 未覆盖

1. 真实两机：基线文件跨机搬运仍 NOT_RUN（需第二台机器）。
2. **Android 还没有「用基线算增量」**：本轮只做到「记基线」；`SourceSyncDigest` / `SyncRoundMerger` 等仍只有 Windows 侧有。
3. `export()` 尚未读基线，零写入的一轮不更新 snapshotId。
4. 无新增 Compose 断言（结果卡片两行事实由 JVM 摘要用例覆盖），真机 DEVICE_UNVERIFIED 口径不变。

