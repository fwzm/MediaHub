# W6-6 凭据迁移与 DPAPI 重封 —— 实现说明

- 状态：**已实现**（纯逻辑层 + 真实 DPAPI 测试；App 层 UI 接线为 NOT_IMPLEMENTED）
- 日期：2026-10-23
- 设计依据：[ADR-S006](./adr/ADR-S006-credential-resealing.md)；前置 [ADR-S001](./adr/ADR-S001-source-sync-contract.md) D6、[ADR-S005](./adr/ADR-S005-archive-encryption.md)
- 前置工作：[W6-1-IMPLEMENTATION.md](./W6-1-IMPLEMENTATION.md)、[W6-2-IMPLEMENTATION.md](./W6-2-IMPLEMENTATION.md)、[W6-E2E-IMPLEMENTATION.md](./W6-E2E-IMPLEMENTATION.md)

## 目标与范围

让「来源行的登录凭据能不能跟着同步过去」成为一个**可测试的强制层**，而不是后续某个页面里的 if：

- 默认路径（`includeSecrets: false`）行为完全不变：信封里没有任何凭据，`capabilities.canSendSecrets` 为 false。
- 显式要求迁移时，明文只在内存里存在一次调用，出网/落盘的一律是本机 DPAPI 重封后的 `SecretEnvelope`。
- 跨机器的 blob 解不开就整轮失败、零写入——不允许出现「来源导入了但登不上」的半成品。

不做的事和边界见 ADR-S006「不能做什么」。

## 数据形状

凭据在契约里只有一个形状（`SourceRecord.Secrets` 这个不透明块的内容）：

```json
{
  "scheme": "dpapi-user",
  "scope": "alice",
  "payloadBase64": "<DPAPI blob，base64>",
  "protectedAtEpochMs": 1760000000000
}
```

- `scheme` ∈ `dpapi-user` | `dpapi-machine` | `none`（`none` 必须不带 payload）。
- `scope`：user 作用域是 Windows 用户名，machine 作用域是机器名；不参与任何比较，只用于诊断。
- `protectedAtEpochMs`：只用于诊断，**从不**参与冲突判定（ADR-S004 D5）。
- 解码后不足 128 字节的 payload 一律拒绝：真 DPAPI blob 有数百字节，短 payload 意味着「明文套了层 base64」。

## 组件

| 文件（`windows/src/MediaHub.Sync/`） | 职责 |
| --- | --- |
| `SecretError.cs` | 稳定错误码常量 + `SecretException`（`Reason` 属性） |
| `SecretEnvelope.cs` | 唯一凭据形状、`Validate()`、`Empty()`、`FromPlaintext()` 陷阱 |
| `SecretEnvelopeCodec.cs` | camelCase JSON 编解码、`SerializeToElement` 供 `SourceRecord.Secrets` |
| `ISecretProtector.cs` | `Protect(ReadOnlySpan<byte>)` / `Unprotect(SecretEnvelope)` |
| `WindowsDpapiProtector.cs` | P/Invoke `CryptProtectData`/`CryptUnprotectData`，entropy 固定为 `MediaHub.SourceSync.v1`，默认 user 作用域 |
| `SourceSyncSession.cs`（改） | `Export` 增加 `protector`/`secrets` 可选参数；`Import`/`ImportPlain` 增加 `protector`，导入前统一重封 |
| `SourceSyncLocalBridge.cs`（改） | `Apply` 接受已重封的凭据字典；Update 时只在有匹配凭据时替换，Create 时随行落库 |
| `LocalSourceRecord.cs`（改） | 新增 `SecretEnvelope? Secret`，与来源行同一次 `Store.Save` 落盘 |

## 流程

```text
设备 A（源站）
  Export(includeSecrets: true, protector: A 的 protector, secrets: { syncSourceId -> 明文 })
    -> 明文 Protect() 成 SecretEnvelope（A 的 blob）
    -> 信封 Sources[i].Secrets = 该块（JSON）
    -> 明文缓冲区 ZeroMemory
      |
      |  通道：明文契约（同机 / 已配对验证的两台机器）
      v
设备 B（目标）
  ImportPlain(peer, protector: B 的 protector)
    -> 逐条 SecretEnvelopeCodec.TryParse
    -> B 的 protector.Unprotect(peer 块)  ... 失败 => ImportResult.Failed(dpapi-unprotect-failed)，零写入
    -> B 的 protector.Protect(明文)        ... 得到 B 的 blob（必然与 A 的 blob 不同）
    -> SourceSyncPlanner.Plan + Bridge.Apply(target, 重封字典)
    -> Store.Save（来源行 + Secret 同一次写入）
```

## 不变量（每条都有测试钉住）

| 不变量 | 测试 |
| --- | --- |
| 默认导出不含任何凭据，`canSendSecrets` 为 false | `SourceSyncSessionTests.Export_PlainEnvelopeCarriesNoSecrets` |
| 要求凭据却没给 protector / 没给明文 / 未要求却给明文 / 同时给 passphrase，各自抛稳定码 | `SourceSyncSessionSecretTests.Export_*`（4 例） |
| 导出的明文既不以原文也不以 base64 形式出现在信封 JSON 里 | `Export_SealsTheCredentialAndKeepsPlaintextOffTheWire` |
| 导入侧重封，落盘对象与文件中都读不到明文 | `Import_ReSealsForThisMachineAndNeverStoresPlaintext` |
| 外来 entropy / 外来账户的 blob 使整轮失败且零写入 | `Import_ForeignSealedBlobFailsTheWholeRound`、`DpapiSecretProtectorTests.Unprotect_RefusesABlobSealedWithADifferentEntropy` |
| 明文套 base64 的假块被拒（`secret-payload-too-short`）且零写入 | `Import_APasswordWearingABase64CoatFailsClosed` |
| 有凭据的信封缺 protector 时整轮失败、零写入 | `Import_SealedCredentialsWithoutAProtectorFailClosed` |
| 重复导入同一轮不重写凭据（幂等） | `Import_RepeatingTheSameRoundDoesNotRewriteTheCredential` |
| 没有凭据的信封不需要 protector，行为与 W6-1 一致 | `Import_WithoutCredentialsNeedsNoProtector` |
| `secret` 块校验的每个拒绝码 | `SecretEnvelopeContractTests`（15 例） |
| 真实 DPAPI 往返、篡改拒绝、machine 作用域 | `DpapiSecretProtectorTests`（9 例） |

## 与 W6-5 归档层的关系

`EncryptedArchiveWriter` 仍然拒绝 `canSendSecrets: true` 或任何携带 `secrets` 块的信封（`secrets-not-allowed`），读端同样拒绝。`Export` 里 `includeSecrets + passphrase` 的组合因此在参数校验阶段就抛 `secrets-not-supported`，不会走到归档写入。原因见 ADR-S006 D5：`.mharchive` 是可被复制传播的文件，不能成为凭据载体。

## NOT_IMPLEMENTED

- **App 层 UI 接线**：哪个页面提供「同时迁移登录凭据」、失败后如何提示用户（需要显示稳定码与「请在新设备上重新登录」），属于 W6-2b 之后的 UI 任务。
- **Android 侧等价方案**：Android 没有 DPAPI，需要 Keystore 支持的 AES-GCM key 与新 scheme（例如 `android-keystore-v1`）；wire 上已通过 `scheme` 字段留好分派点。
- **凭据轮换的主动推送**：本层不把凭据算进内容哈希或 `recordRevision`，因此换口令不会自动触发同步；是否/何时发起一轮携带凭据的导出由 App 决定。
- **机器作用域的产品策略**：`WindowsDpapiProtector(machineScope: true)` 已实现并测试，但默认恒为 user 作用域，是否向用户暴露待定。
- **凭据的实际消费方**：本层只负责搬运与重封，谁在播放/连接时 `Unprotect` 并交给 HTTP 客户端（以及如何在内存里持有）不在本轮范围。

## 复现

```powershell
# 1) 库本身
dotnet build D:/mh-w6/windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo

# 2) 本轮新增的 36 例（DPAPI 用例需要 Windows；非 Windows 会直接返回）
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter "FullyQualifiedName~SecretEnvelope|FullyQualifiedName~DpapiSecretProtector|FullyQualifiedName~SourceSyncSessionSecretTests"

# 3) 全量回归（W6-6 完成时 788 例）
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```
