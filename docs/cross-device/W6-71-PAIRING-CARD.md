# W6-71：配对页第四段 —— 对端身份卡片与显式登记（Android guest 方向）

本轮在 **W6 树（Windows 侧）** 只做两件事：①给权威 `MediaHub.Sync` 补两个真缺陷的修；②让 C# 侧成为本轮 Kotlin 实现的**字节级对照物**。
主线那边的实现与文档见 `docs/android-pairing.md` §16（主树）。

## 1. 本轮在 Windows 树做了什么

| 文件 | 改动 |
| --- | --- |
| `windows/src/MediaHub.Sync/PairingPeerCard.cs` | 两处读端加固（见 §2） |
| `windows/tests/MediaHub.Tests/PairingPeerCardContractTests.cs` | 新增 7 `[Fact]`，逐条消费黄金向量 `peerCard` 段 |
| `windows/tests/assets/windows-pairing-vectors.json` | 128430 → **152051 B**（sha256 `b30d1f18f0e1e64f70a88637f3034886eb26b4f1daa2b6fa329fca4ec6360cea`），老 12 段逐字节不变，尾部新增 `peerCard` 段 |

生成器是临时工程 `D:/mh-verify/pairing-vectors/`（`net10.0`，`ProjectReference` 指向 `windows/src/MediaHub.Sync/MediaHub.Sync.csproj`），跑完即弃；
向量文件是唯一事实来源，两树逐字节同一份（主树 `core/common/src/test/resources/pairing/windows-pairing-vectors.json`）。

## 2. 修掉的两个 Windows 真缺陷

都是 Kotlin 侧按契约逐字段写用例时，把 C# 读端的**非契约异常**逼出来的。两条都违反该文件「读永不抛」的口径。

### 2.1 孤立代理项：`JsonElement.GetString()` 抛 `InvalidOperationException`

`TryDeserialize` 原先在「根非对象」判定之后直接读字段。若卡片 JSON 里含**转义的孤立代理项**（例如 `"displayName":"\uD800"`），
`GetString()` 抛 `System.InvalidOperationException`（不是 `JsonException`，也不会被 JSON 解析的 catch 接住），
一个手改的卡片就能把读端打崩。

修法（`PairingPeerCard.cs`，在根类型判定之后、`schemaVersion` 之前）：

```csharp
if (WireText.ContainsLoneSurrogate(root))
{
    error = PairingPeerCardReasons.MalformedJson;
    return false;
}
```

与 Kotlin 侧 `WireText.containsLoneSurrogate(root)` → `card-malformed-json` 同名同序，两端口径一致。

### 2.2 `TryGetInt32` 对非 Number 抛异常

原文：`if (!root.TryGetProperty("schemaVersion", out versionElement) || !versionElement.TryGetInt32(out version))`。
`JsonElement.TryGetInt32` 在 `ValueKind != Number` 时**抛**而不是返回 false：

```
System.InvalidOperationException: The requested operation requires an element of type Number, but the target element has type String.
   at ...PairingPeerCard.cs:line 141
```

即一张 `{"schemaVersion":"1"}` 的卡片会让读端崩掉（与 W6-70 修掉的 `FilePairedDeviceStore` 是同一类缺陷）。
修法：加前置 `versionElement.ValueKind != JsonValueKind.Number` → `card-malformed-json`。

## 3. 孪生契约用例覆盖表

`PairingPeerCardContractTests.cs`（7 个 `[Fact]`，与 Kotlin `PairingPeerCardContractTest` 同名同义）：

| 用例 | 钉住什么 |
| --- | --- |
| `ReasonCodesAndLimitsMatchTheWindowsContract` | 6 条 `card-*` 码同名同序、`maximumTextLength` / `maximumLength` / `schemaVersion` / `derivationPrefix` / `rejectsBlankIdentity` |
| `SerializedCardsMatchTheWindowsBytes` | 6 条 `serializationCases` 逐条比 `json` + `utf8ByteLength` + `sha256`（含中文、ASCII 特殊字符、控制字符、128 字符名字） |
| `DecodedCardsMatchTheWindowsContract` | 27 条 `decodeCases`：接受 9 条比五字段，拒绝 18 条比稳定码；并断言 6 码全覆盖 |
| `SelfIdentityCasesMatchTheWindowsDerivation` | 5 条 `selfIdentityCases`：`deriveFingerprint` 逐条比 hex、`trimmedDeviceId` 归一化、`cardJson` 字节 |
| `BlankIdentityIsRejectedLikeTheWindowsContract` | `PairingSelfIdentity.Create` 对空白 id 抛 `ArgumentException("设备标识不能为空。")` |
| `ObservationCaseCarriesTheCallersClock` | `ToObservation(nowEpochMs)` 的五个字段与时钟来源 |
| `DecodeNeverThrowsOnHostileText` | 7 条恶意文本（深层嵌套 / 超长 / 裸控制字符 / 坏 UTF-8 替换 / 非对象根 / 重复键 …）一律折成稳定码、**永不抛** |

## 4. 本机实跑

| 项 | 结果 |
| --- | --- |
| C# 全量 | `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug -p:Platform=x64` → **1635 通过 / 0 失败 / 0 跳过**（1628 → 1635，+7） |
| 命令坑 | 必须带 `-p:Platform=x64`，否则会跑 `bin/Debug/net10.0-windows/win-x64/` 下的陈旧 DLL（W6-68 的教训） |

## 5. 未做项

- **真实两机卡片交换仍 NOT_RUN**：Windows 侧的卡片交换只在既有 `PairingTransportServer` 里，本轮没有跑任何跨机进程；Android 侧只在回环替身里验证过。
- **Windows GUI 未复验**：`SyncPage` 的已配对清单仍是 `GUI_UNVERIFIED`。
- **协议版本未动**：卡片 `schemaVersion` 恒为 1，本轮没有新增字段、没有升版本；老构建对端读不出卡片时按「记不住对端」处理。
