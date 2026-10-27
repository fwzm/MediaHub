# W6-70 · Android 配对设备登记与解绑（Windows 侧对照）

日期：2026-10-27　分支：`feature/cross-device-source-sync`　主树对照：`docs/android-pairing.md` §15

## 1. 本轮在 Windows 树做了什么

Android 侧新增了配对设备登记档案（`core/common/src/main/kotlin/com/mediahub/core/common/pairing/` 七个文件）与设置页的「已配对设备」页。
Windows 侧**早已有全部实现**（ADR-S005 起：`PairedDevice.cs` / `PairedDeviceRegistry.cs` / `IPairedDeviceStore.cs` / `FilePairedDeviceStore.cs` / `InMemoryPairedDeviceStore.cs` / `PairedDeviceListView.cs`），本轮 Windows 树只做三件事：

1. **修一个真缺陷**：`FilePairedDeviceStore.LoadLocked()` 违反「读永不抛」；
2. **加孪生契约用例**：`windows/tests/MediaHub.Tests/PairedDeviceContractTests.cs` 14 `[Fact]`，与 Kotlin `PairedDeviceContractTest` 同名同义、消费同一份黄金向量；
3. **挂测试资源**：`windows/tests/assets/paired-device-vectors.json`（36592 B）进 `MediaHub.Tests.csproj` 的 `Content`。

生成器与向量都在仓库外：`D:/mh-verify/paired-device-vectors/`（`Program.cs` 520 行，`ProjectReference` 指向权威 `MediaHub.Sync.csproj`）。
运行方式：`cd D:/mh-verify/paired-device-vectors && dotnet run -c Release`（必须用 `ops_bash`，`run_code` 的 `execFileSync` 会报 `NuGet.targets(780,5) Value cannot be null (Parameter 'path1')`）。
跑完把 `vectors.json` 原样复制到两处：`D:/mh-w6/windows/tests/assets/paired-device-vectors.json` 与主树 `core/common/src/test/resources/pairing/paired-device-vectors.json`（逐字节相同，sha256 `4adc50d7f16b870d3cb9e5936edde11d18dee496709a5684b95dc9670f0e04e3`）。

## 2. 修掉的 Windows 缺陷：读路径会抛 InvalidOperationException

`FilePairedDeviceStore.LoadLocked()` 原写法直接对 `version` 元素调 `JsonElement.TryGetInt32`：

`csharp`
if (!root.TryGetProperty("version", out var versionElement)
    || !versionElement.TryGetInt32(out var version)
    || version != CurrentDocumentVersion)
{
    Quarantine(MalformedJson);
    return;
}

``
问题在 `TryGetInt32` 只对 `ValueKind == Number` 的**数值**安全：当 `version` 是 `"1"`（字符串）或 `null` 时，它抛 `InvalidOperationException`，
而调用链上没有任何 `catch` 会把这个异常折成 `loadError` —— 直接穿透到同步页，整页崩掉。
这违反该文件的既有契约：「读永不抛，坏档一律隔离」。

修法是先判 `ValueKind` 再取值：

`csharp`
if (!root.TryGetProperty("version", out var versionElement)
    || versionElement.ValueKind != JsonValueKind.Number
    || !versionElement.TryGetInt32(out var version)
    || version != CurrentDocumentVersion)
{
    Quarantine(MalformedJson);
    return;
}

``
Kotlin 侧没有这个坑：`PairedDeviceDocument.decode` 第一层就判 `JsonPrimitive.isString` / `intOrNull`，类型不符直接 `Rejected(MALFORMED_JSON)`。
这个缺陷是**写孪生用例时被向量逼出来的**：`loadCases` 里有一条 `version-as-string`，Windows 首跑就抛在这里。

## 3. 孪生契约用例的覆盖

`PairedDeviceContractTests.cs`（23272 B，14 `[Fact]`）逐条消费同一份向量：

| 用例 | 钉住什么 |
| --- | --- |
| `ReasonCodesMatchTheWindowsContract` | 12 条原因码同名同序 |
| `StoreReasonCodesMatchTheWindowsContract` | 5 条存储码同名同序 |
| `DescribeReasonCasesMatchTheWindowsContract` | 12 条中文文案逐字 + 未知码原样 + 空码「未说明原因。」 |
| `StateLabelsMatchTheWindowsContract` | 「新设备 / 指纹一致 / 指纹不一致」 |
| `CreateCasesMatchTheWindowsContract` | trim / lowercase / 128 截断 / 负数归 0 / 缺省时间戳 |
| `VerifyCasesMatchTheWindowsContract` | `verify` 只核对不写盘（写盘次数不变） |
| `RegisterCasesMatchTheWindowsContract` | 登记的全部副作用：新增 / 更新 / 不变 / 指纹不符拒绝 / 容量满 / 写失败 |
| `CapacityCasesMatchTheWindowsContract` | 容量边界与 `isFull` |
| `ForgetCasesMatchTheWindowsContract` | 空白 / 未知 / 成功 / 写失败各自的返回值与写盘次数 |
| `DescribeCasesMatchTheWindowsContract` | 事件描述文案；`describe` 与 `describeSuffix` **分开判** |
| `PresenterCasesMatchTheWindowsContract` | 行文案、空态、豆腐块、容量提示、`forgetLabel` |
| `DocumentCasesAreWrittenByteIdentically` | 写出的字节**长度 / sha256 / base64** 三项全等 |
| `LoadCasesMatchTheWindowsContract` | 畸形 / 版本不符 / 容量满 / 只读的降级路径 |
| `WriteRejectsMatchTheWindowsContract` | 空 `deviceId`、容量满的写入拒绝 |

## 4. 三条被用例逼出来的实现/断言修正

1. **Windows 读路径抛异常**（见 §2）。
2. **`describeCases` 的字段形状**：向量里 `describe` 与 `describeSuffix` **不同时存在**（`peer-clock-ahead` 只有 suffix），
   首版用例把两者当同一字段取 ⇒ `KeyNotFoundException`。修法：两个都改成可空取值、分开断言。
3. **suffix 用 `Contains` 而不是 `EndsWith`**：时钟偏移提示位于尾部「 · 来源 N 个」**之前**，不是整行结尾。
   C# 用 `actual.Contains(suffix, StringComparison.Ordinal)`、Kotlin 用 `actual.contains(suffix)`。

另有一处**排序断言**的坑，不是实现缺陷：`documentCases.devices` 是**写入顺序**（`two-devices` 是 `Zeta` 在前），
而 `FilePairedDeviceStore.GetAll()` / `FilePairedDeviceStore.getAll()` 返回**规范序**（`deviceId` 先 OrdinalIgnoreCase 再 Ordinal，`alpha` 在前）。
重开比较前必须先排序，否则字节断言全过、语义断言红。
C# 写法：`devices.OrderBy(d => d.DeviceId, StringComparer.OrdinalIgnoreCase).ThenBy(d => d.DeviceId, StringComparer.Ordinal)`；
Kotlin 写法要用显式类型参数：`devices.sortedWith(compareBy<PairedDevice, String>(String.CASE_INSENSITIVE_ORDER) { device -> device.deviceId }.thenBy { device -> device.deviceId })`（不显式会报 `Cannot infer type for this parameter`）。

## 5. 时区耦合缺陷：本地绿、CI 红（本轮真修）

首版黄金向量把「首次配对 / 最后相见 / 最近配对」的时间串按**生成机器的本地时区**（Asia/Shanghai, UTC+8）写死，
例如 `describeCases[unknown-times].describe` 含 `2026-10-24 08:00`、`presenterCases[two-devices].rows[0].lastSeenLabel` 是 `最后相见 2026-10-24 08:00`。
而两端实现都是「本地时区渲染」（C# `PairedDevice.FormatEpoch` / `PairedDeviceListView.FormatEpoch` 走 `ToLocalTime()`，Kotlin 走 `ZoneId.systemDefault()`）——
CI runner 是 UTC，同一 epoch `1792800000000` 渲染成 `2026-10-24 00:00`，于是：

| run | head | 红的位置 |
| --- | --- | --- |
| windows-ci `40438832196` | `09641c8d` | `PairedDeviceContractTests.DescribeCasesMatchTheWindowsContract`（CS:322）、`PresenterCasesMatchTheWindowsContract`（CS:391）；Failed 2 / Passed 1626 |
| Android CI `40438831881` | `09641c8d` | `build` job 的 `PairedDeviceContractTest > describe_cases_match_the_windows_contract`（KT:296）、`presenter_cases_match_the_windows_contract`（KT:356） |
| Android CI `40438579408` | `74dce9a9` | 与上一行同根因（同一份向量） |

产品语义保留「本地时区」渲染（用户看自己的钟），所以**修契约、不修实现**：

- 生成器加 `Tokenize(text, device)`，对 `PairedAtEpochMs > 0` 把「首次配对 + FormatEpoch(pairedAt)」换成「首次配对 `{pairedAt}`」，对 `LastSeenEpochMs > 0` 把「最后相见 / 最近配对 + FormatEpoch(lastSeen)」换成带 `{lastSeen}` 的标签串（**锚在标签上**，避免一句里两个相同渲染串互相混淆）；
- 向量 36670 → **36592 B**、sha256 `4afe2032…55db8b` → `4adc50d7f16b870d3cb9e5936edde11d18dee496709a5684b95dc9670f0e04e3`，共 13 处占位符，已无任何 `\d{4}-\d{2}-\d{2} \d{2}:\d{2}` 字面时间；
- 两端用例各加 `ExpandTokens` / `expandTokens`，按**本进程时区**用同一份 `FormatEpoch` / `formatEpoch(device.pairedAtEpochMs)` 展开后再断言；`describeCases` 与 `presenterCases` 的四个字段（`pairedLabel` / `lastSeenLabel` / `summaryLine` / `detailLine`）都过一遍。

契约钉的是「标签 + 平台自己的本地渲染」，不再是某个固定时区的渲染结果。本地复验时额外用 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` + `--rerun-tasks` 复跑一遍 Kotlin 契约用例（14/14 绿，`17 actionable tasks: 17 executed`），
证明修复真的与时区无关 —— 这一步不能省：`--tests` 过滤下任务 `up-to-date` 会直接跳过执行，日志绿但等于没测。

## 6. 本机实跑

| 项 | 命令 | 结果 |
| --- | --- | --- |
| C# 契约用例 | `cd D:/mh-w6 && dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug -p:Platform=x64 --filter "FullyQualifiedName~PairedDeviceContractTests"` | **14/14 通过** |
| C# 全量 | 同上、去掉 `--filter` | **1628 通过 / 0 失败 / 0 跳过**（1614 → 1628） |
| Kotlin 契约用例 | 主树 `:core:common:testDebugUnitTest --tests "com.mediahub.core.common.pairing.PairedDeviceContractTest"` | **14/14 通过** |
| Kotlin 三模块 | 主树 `:core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest` | **636 + 192 + 367 = 1195 例 / 0 失败** |
| Kotlin 契约（UTC 复验） | 同契约用例，`set "JAVA_TOOL_OPTIONS=-Duser.timezone=UTC"` + `--rerun-tasks` | **14/14 通过**（`17 actionable tasks: 17 executed`，非 up-to-date 假绿） |

`-p:Platform=x64` 不能省：`dotnet test --no-build` 不带它时会跑 `bin/Debug/net10.0-windows/win-x64/` 下的陈旧 DLL，新代码在 `bin/x64/…`。

## 7. 未做项

- **登记新设备**：Android 侧本轮只做清单 + 解绑；Windows 侧 `Register` 已有完整实现与用例，但两端都没有「握手完成后自动登记」的接线。
- **跨端联跑**：本轮没有 Android 与 Windows 互相读写同一份 `paired-devices.json` 的实跑（两边各自读同一份字节向量，逐字节对照）。
- **真实两机配对**：`NOT_RUN`，需要第二台机器 + 用户同意开防火墙。
- **Windows GUI**：`SyncPage` 的已配对清单本轮没有新的 GUI 证据，仍是 `GUI_UNVERIFIED`。

