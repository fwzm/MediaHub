# ADR-S006 —— 凭据迁移：DPAPI 重封、默认不出网、失败即整轮拒绝

- 状态：**已提议**（W6-6 产出：`windows/src/MediaHub.Sync/SecretEnvelope.cs`、`SecretEnvelopeCodec.cs`、`ISecretProtector.cs`、`WindowsDpapiProtector.cs`、`SecretError.cs` 与 `SourceSyncSession` 接线，36 例测试钉住）
- 日期：2026-10-23
- 前置：[ADR-S001](./ADR-S001-source-sync-contract.md) D6（凭据不落明文/日志/二维码）、[ADR-S004](./ADR-S004-delta-sync.md)（`recordRevision` + 显式冲突）、[ADR-S005](./ADR-S005-archive-encryption.md)（归档层拒绝携带凭据的信封）
- 实现：`windows/src/MediaHub.Sync/SecretError.cs`、`SecretEnvelope.cs`、`SecretEnvelopeCodec.cs`、`ISecretProtector.cs`、`WindowsDpapiProtector.cs`，以及 `SourceSyncSession.cs`、`SourceSyncLocalBridge.cs`、`LocalSourceRecord.cs` 的接线
- 测试：`windows/tests/MediaHub.Tests/SecretEnvelopeContractTests.cs`（15 例）、`DpapiSecretProtectorTests.cs`（9 例）、`SourceSyncSessionSecretTests.cs`（12 例）
- 偏离登记：**`WindowsDpapiProtector` 放在 `windows/src/MediaHub.Sync/`，而不是任务书要求的 `windows/src/MediaHub.App/Services/`**，见 D8

## 问题

W6-1 到 W6-5 只解决了「来源行」怎么走：身份、增量、冲突、归档加密。凭据一直是 NOT_IMPLEMENTED——信封里 `secrets` 只允许是不透明块，没人写，也没人读。要做凭据迁移，必须先回答四个问题，否则交付的是一颗哑弹：

1. **明文只要落过一次盘就等于永久泄露。** 用户在多台设备、多个服务复用同一个口令，一次 `settings.json` 备份、一次诊断日志、一次崩溃转储都会把它带出去，而这类泄露不会让任何功能测试变红。
2. **DPAPI 密文不是可以搬运的凭据。** `CryptProtectData` 的 blob 绑定「同一台机器 + 同一个 Windows 账户 + 同一段 entropy」。设备 A 的 blob 在设备 B 上 `CryptUnprotectData` 必然失败。如果把对端的 blob 原样存进本地库，UI 会显示「已配置 / 已登录」，用户点播放时才拿到 401——这是最难排查的失败形态：数据看起来齐全。
3. **半成功的导入比拒绝更坏。** 来源行写进去了、凭据没落，或者反过来，都会留下一个「配置完整但登不上」的条目。用户没有理由怀疑是同步造成的。
4. **报错本身会成为信息通道。** 如果「口令错」能通过异常文本区分出「blob 结构」或明文长度，攻击者就拿到了判断依据；如果明文被临时数组或 `ToString()` 带进日志，加密就退化成混淆。

留白——「先用 DPAPI 包一下」「导入时把 blob 存下来」「出错了跳过这一条」——会同时踩中以上四条，且都不会在 happy path 上暴露。

## 决策

### D1 唯一可传输形状：`SecretEnvelope`（sealed blob + 认领信息），没有明文构造器

凭据在契约里只有一种形状：

```json
{ "scheme": "dpapi-user", "scope": "alice", "payloadBase64": "<DPAPI blob>", "protectedAtEpochMs": 1760000000000 }
```

`scheme` 只允许 `dpapi-user`、`dpapi-machine`、`none` 三个值；`none` 是显式的「此处没有凭据」，必须不带 payload，也不能用它夹带明文。校验在 `Validate()` 与 `SecretEnvelopeCodec` 两处都做，两者对同一种违规给同一个稳定码。

关键设计是**没有明文入口**：唯一的合法产生方式是 `ISecretProtector.Protect`，唯一回程是同一台机器上的 `Unprotect`。`SecretEnvelope.FromPlaintext` 存在但恒抛 `plaintext-secret-rejected`——它是个陷阱，专门用来让「以为可以塞明文」的调用点立刻失败，而不是悄悄存下一个口令。

`Validate()` 里那条 `MinSealedBytes = 128` 的规则值得单说：真 DPAPI blob 有数百字节，而把 `password1234` 直接 base64 编码出来的串恰好也长得像 payload。长度下限把这条路堵住，报 `secret-payload-too-short`。这是纵深防御的最后一道，不是主防线（主防线是 D3 的重封）。

### D2 导出默认不带凭据；要带必须凑齐三件套

`SourceSyncSession.Export` 的默认参数是 `includeSecrets: false`，此时信封的 `capabilities.canSendSecrets` 为 false，任何来源行的 `secrets` 都是 `null`——这一点由既有导出测试继续钉住，默认行为一行没变。

用户显式要求迁移凭据时，必须同时给出 `includeSecrets: true`、一个 `ISecretProtector`、以及明文（按 `syncSourceId` 索引）。缺一样都抛稳定码，**绝不静默降级**：

| 缺什么 | 稳定码 | 为什么必须报错 |
| --- | --- | --- |
| 没有 protector | `secrets-require-protector` | 没有它就只能发明文，或假装发了 |
| 没有明文 | `secrets-missing` | 用户勾了「迁移凭据」却什么都没给，静默跳过会让他以为已导出 |
| 未要求却给了明文 | `secrets-not-requested` | 调用方搞错了意图；吞下去等于擅自导出凭据 |
| 同时给了 passphrase | `secrets-not-supported` | 归档层拒绝携带凭据的信封（D5） |

### D3 导入必重封：`Unprotect` → `Protect`，明文只存在于一次调用内

导入侧收到的永远是别人机器上 sealed 的 blob。流程是：

```text
inbound block --parse/validate--> SecretEnvelope
              --Unprotect（本机 protector）--> plaintext（仅内存）
              --Protect（本机 protector）--> 新 blob
              --Store.Save（与来源行同一次写入）--> sync-sources.json
```

重封后的 blob 与入站 blob 一定不同（DPAPI 每次加密带随机盐），测试直接断言这一点。明文数组在 `TryResealSecrets` 的 `finally` 里 `CryptographicOperations.ZeroMemory`，包括每一条失败路径。

凭据与来源行是**同一次** `Store.Save` 落盘的（`LocalSourceRecord.Secret` 就在记录上），所以不存在「地址写了、凭据没写」的中间态。

### D4 跨机 blob 必须整轮失败，禁止逐条降级

如果 `Unprotect` 抛 `dpapi-unprotect-failed`（外来 entropy、外来账户、被篡改），导入直接 `ImportResult.Failed(dpapi-unprotect-failed)` 并**零写入**：不建来源、不改地址、不动已有的 `Secret`。

逐条跳过看起来更「健壮」，实际制造的是 D3 里描述的最坏形态：一部分来源配好了，另一部分看起来也配好了但没有可用凭据。用户唯一的线索是播放时的 401，而那时早就离开了同步界面。整轮失败至少把问题定位在「这次导入没成功」。

同理，导入时如果信封带 `secrets` 而调用方没给 protector，也整轮失败（`secrets-require-protector`），而不是把来源行导进来再把凭据丢掉。

### D5 归档层永不承载凭据，这条不变

`EncryptedArchiveWriter`（W6-5）仍然拒绝 `canSendSecrets: true` 或任何携带 `secrets` 块的信封（`secrets-not-allowed`），读端同样拒绝一次。理由没有变：`.mharchive` 是可以在聊天窗口、网盘、U 盘之间复制的文件，一旦它成为凭据载体，「一个文件泄露 = 一批凭据泄露」，而口令强度只能靠用户自觉。

因此凭据只走明文契约通道（同一台机器、或已通过 ADR-S003 配对并互相验证过的两台机器）。用口令二次加密凭据再塞进归档，是**明确不做的**（见 D8）——那等于自建一套「口令保护凭据」的加密方案，而 DPAPI 才是这台机器上唯一有意义的密钥来源。

### D6 失败码语义（对外稳定，测试钉住）

| 码 | 含义 | 出现位置 |
| --- | --- | --- |
| `secret-scheme-unknown` | scheme 不是本版本认识的三个值之一 | 校验 / 编解码 / 导入 |
| `secret-scope-empty` | sealed 块没有声明作用域 | 校验 |
| `secret-payload-empty` | sealed 块没有 payload | 校验 |
| `secret-payload-not-empty` | `scheme=none` 却带了 payload | 校验 |
| `secret-payload-not-base64` | payload 不是合法 base64 | 校验 |
| `secret-payload-too-short` | 解码后不足 128 字节，形似明文套 base64 | 校验 / 导入 |
| `plaintext-secret-rejected` | 试图把明文变成 `SecretEnvelope` | 校验 |
| `secrets-require-protector` | 需要凭据但没有 `ISecretProtector` | 导出 / 导入 |
| `secrets-not-requested` | 给了明文但没要求导出凭据 | 导出 |
| `secrets-missing` | 要求导出凭据但没给任何明文 | 导出 |
| `secret-envelope-not-sealed` | 对空块调用 `Unprotect` | 保护器 |
| `secret-malformed-json` | 凭据块不是 JSON 对象 | 编解码 / 导入 |
| `dpapi-protect-failed` | `CryptProtectData` 失败（附 Win32 错误码，不含明文） | 保护器 |
| `dpapi-unprotect-failed` | `CryptUnprotectData` 失败：外来机器、外来账户、外来 entropy 或篡改 | 保护器 / 导入 |
| `dpapi-blob-empty` | 待封或解出的字节为空 | 保护器 |
| `secrets-not-supported` | 归档层拒绝携带凭据的信封 | 导出（passphrase 组合）/ 归档读写 |

异常文本只描述失败类别与 Win32 错误码，永不包含明文、明文长度或 blob 内容。

### D7 明文生命周期与外泄面

- 明文只出现在调用方传入的 `byte[]` 与本层临时数组里；`Protect`/`Unprotect` 结束时立刻 `ZeroMemory` 中间缓冲。
- `SecretEnvelope.ToString()` 只输出 scheme / scope / 时间戳 / payload **长度**，测试断言完整 payload 字符串不出现在 `ToString()` 里。
- 不写日志、不写诊断文件、不进 `ImportResult` 的任何字段。`ImportResult` 只报稳定码。
- `SecretEnvelope` 与来源内容哈希、`recordRevision` 无关：换口令不是「来源内容变更」，不会自动触发一次同步。要主动推送轮换后的凭据，需要调用方发起一轮新的、携带凭据的导出（这是 W6-6 之后 App 侧要做的决定，不在本层）。

### D8 位置偏离与升级路径

**偏离：`WindowsDpapiProtector` 落在 `windows/src/MediaHub.Sync/`，而不是任务书写的 `windows/src/MediaHub.App/Services/`。** 原因是可测性：`MediaHub.Tests` 不引用 `MediaHub.App`（App 是 `UseWinUI=true` 的 WinUI 3 项目，测试项目引用它会把 UI 依赖拖进单元测试），protector 放 App 里，本轮 9 例真实 DPAPI 用例就一条都跑不了，只能靠 stub 自证。依赖方向是 App → Sync 单向，所以放在 Sync 里 App 侧直接可用，不需要额外适配；任务书要求的「App/Services」意图（App 能用、不引第三方包）保持不变。

**升级路径**：新增加密方案（例如将来的 `dpapi-user-v2`，或 Android 侧等价的 Keystore 方案）时，只需要在 `SecretEnvelope.Schemes` 与 `ISecretProtector` 的实现里增加分派——信封的 `scheme` 字段已经是显式的算法标识，未知值一律拒绝而不是猜测。契约版本号（`schemaVersion`）不必因为增加一个 scheme 而提升：旧读端看到未知 scheme 会给 `secret-scheme-unknown`，这是「明确拒绝」，不是「静默忽略」。

## 不能做什么（明确边界）

- **不做跨机密文搬运**：B 设备绝不存储 A 设备的 blob，只存自己重封后的结果。
- **不做口令二次加密凭据**：不引入「用 passphrase 加密 secrets 再进归档」的旁路（D5）。
- **不把凭据写进磁盘、日志、UI 诊断、异常消息、`ToString()`**。
- **不把凭据算进内容哈希或 revision**：口令轮换不等于来源内容变更。
- **不自动重试、不自动降级**：解不开就整轮失败，由调用方决定下一步。
- **不在二维码/配对码里放凭据**（ADR-S001 D6 不变）：配对只交换临时地址、一次性挑战和公钥指纹。

## 未覆盖（NOT_IMPLEMENTED）

- App 层 UI 接线：哪个页面勾选「同时迁移登录凭据」、失败后如何提示，属于后续任务；本层只提供强制语义与稳定码。
- Android 侧：Android 没有 DPAPI，需要的等价方案（Keystore 支持的 AES-GCM key）与 wire 上的新 scheme 尚未设计。
- 凭据轮换的主动推送策略（何时发起携带凭据的一轮）。
- 机器作用域（`dpapi-machine`）的产品策略：本层支持并测试了显式选择，但默认恒为用户作用域，App 侧是否暴露给用户待定。

## 复现

```powershell
dotnet build D:/mh-w6/windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter "FullyQualifiedName~SecretEnvelope|FullyQualifiedName~DpapiSecretProtector|FullyQualifiedName~SourceSyncSessionSecretTests"
dotnet test D:/mh-w6/windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```

最后一条在 W6-6 完成时为 788 例全绿（基线 752 + 新增 36）。
