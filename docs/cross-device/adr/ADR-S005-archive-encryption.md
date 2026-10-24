# ADR-S005 —— 离线归档加密：AES-256-GCM + PBKDF2-HMAC-SHA256

- 状态：**已提议**（W6-5 产出：`windows/src/MediaHub.Sync/EncryptedArchiveWriter.cs`、`EncryptedArchiveReader.cs`、`ArchiveEncryptionTests.cs` 钉住）
- 日期：2026-10-23
- 前置：[ADR-S001](./ADR-S001-source-sync-contract.md) D6（凭据不得落明文/日志/二维码）、[ADR-S004](./ADR-S004-delta-sync.md)（`recordRevision` + 显式冲突）
- 实现：`windows/src/MediaHub.Sync/` 下 `EncryptedArchiveWriter.cs`、`EncryptedArchiveReader.cs`、`ArchiveHeader.cs`、`ArchiveKdf.cs`、`ArchivePassphrase.cs`、`ArchiveError.cs`
- 测试：`windows/tests/MediaHub.Tests/ArchiveEncryptionTests.cs`（38 例）
- 偏离登记：**Argon2id → PBKDF2-HMAC-SHA256**，见 D2 与「偏离登记」一节

## 问题

跨设备转移「来源清单」需要一个可离线传递的文件：用户把 `.mharchive` 通过网盘、U 盘或聊天窗口带到另一台机器，导入后两台设备的来源配置一致。这条路径绕开了 TLS 钉扎的配对通道（ADR-S003），因此文件本身必须自证完整性，并且在被复制、被云盘同步、被第三方看到时都不泄露内容。

现实的失败形态有三类：

1. **文件被改**：攻击者把 `displayName` 改成钓鱼地址、把端点换成自己的服务器，或者只改一个字节让导入侧崩溃。没有完整性校验时，导入侧只能相信文件。
2. **口令被猜**：文件离开本机后，攻击者可以无限次离线尝试。口令如果是 `password123`，加密就等于编码。
3. **错误信息本身成为信息**：如果「口令错」与「文件被改」给出不同的报错，攻击者就拿到了判断自己是否猜对文件结构的旁路；如果报错里带上明文片段或明文长度，加密就被降级成混淆。

留白——「先写个 AES 就行」「报错随便给个字符串」「口令长度回头再说」——会同时踩中这三条，而且都不会在功能测试里暴露。

## 决策

### D1 文件布局：明文头 + 密文 + tag，头本身就是 AAD

文件结构固定为：

```text
magic "MHSYNCA1" (8 字节 ASCII)
headerLength    (4 字节，big-endian int32)
headerJson      (UTF-8，无缩进，字段顺序固定)
ciphertext      (AES-256-GCM 密文，长度 == 明文长度)
tag             (16 字节)
```

**AAD = magic + headerLength + headerJson 的原始字节**，读端取文件里的原字节，绝不重新序列化后再算——重新序列化会让「值相同但字节不同」的头悄悄通过校验。

头是明文 JSON，因为它必须回答「这文件是哪个版本、用哪个 kdf、迭代多少」——这些问题在用户输错口令时也要能回答，也是支持对话的第一句话。头里没有任何秘密：盐和 nonce 天生是公开的，迭代次数与版本号本来就是格式的一部分。

把整段头当 AAD，是把「头可改」这条路堵死：攻击者把头里的迭代次数从 600000 改成 60000 再递回来，密文仍然解得开（口令没变），但 tag 会失配，因为迭代次数在 AAD 里。若只把密文纳入校验，这种「降成本攻击」是静默的。

### D2 密钥派生：PBKDF2-HMAC-SHA256，并把与 ADR-S001 D6 的偏离摆到台面上

参数：盐 16 字节（每次加密随机生成，写入头）、迭代次数 ≥ 600000、输出 32 字节密钥（AES-256）。读端按头里的 `kdf` 标识分派，本次只有 `pbkdf2-sha256`。

**Deviation**：ADR-S001 D6 写的是「AES-256-GCM + Argon2id」。AES-256-GCM 照做；Argon2id 需要第三方库（Konscious.Security.Cryptography 或 Isopoh），与本项目「零 NuGet」的硬约束冲突，因此退回 `System.Security.Cryptography` 里最强的可用选择：PBKDF2-HMAC-SHA256 @ 600000 次（当前 OWASP 对该 PRF 的建议值）。

代价必须说清楚，而不是藏在括号里：PBKDF2 是**内存便宜**的 KDF，GPU/ASIC 攻击者每秒能试的次数远高于 Argon2id（后者按设计吃显存）。对同一口令，离线暴力破解的成本因此显著更低。补偿手段是 D3 的口令策略与「归档是短周期转移文件、不是长期备份」的定位；这是**降低风险**，不是**消除**它。

### D3 口令策略是密码学的一部分，不是 UI 装饰

`ArchivePassphrase`：最短 12 字符；拒绝常见弱口令表（`password1234`、`123456789012`、`qwertyuiop123` 这类，大小写不敏感、比对前 trim）；拒绝单个字符重复（`aaaaaaaaaaaa`）与纯数字。写端与读端都校验。

读端也校验，是刻意的：一个 8 字符的口令不可能是本格式产生的合法口令，直接回 `passphrase-too-weak` 比回 `wrong-passphrase-or-tampered` 更有用，而且不泄露任何关于文件的信息。

不做强度评分器：用户学不会的规则等于没有规则，长度 + 黑名单 + 两个退化形态已经覆盖真实输入。

### D4 错误码：能不问密钥判断的才单独报，其余一律合并

| 码 | 触发条件 | 是否需要口令 |
| --- | --- | --- |
| `magic-mismatch` | 前 8 字节不是 `MHSYNCA1` | 否 |
| `corrupt-header` | 头部缺失/截断/不是约定 JSON/盐或 nonce 长度不对 | 否 |
| `unsupported-version:N` | `version != 1` | 否 |
| `unknown-kdf:X` | kdf 标识本版本不认识 | 否 |
| `invalid-iterations` | 迭代次数低于下限 | 否 |
| `passphrase-empty` / `passphrase-too-weak` | 口令为空/太弱 | 否（格式层面） |
| `payload-too-large` | 密文长度（= 明文长度）超过 8 MiB | 否 |
| `corrupt-ciphertext` | 密文段为空或长度不足以容纳 tag | 否 |
| **`wrong-passphrase-or-tampered`** | **GCM tag 校验失败** | 是 |
| `malformed-payload` | 解密后不是可读信封 | 是 |
| `secrets-not-allowed` | 信封声称可携带凭据 | 写端拒绝 / 读端纵深防御 |

核心决定：**AES-GCM 先认证后解密，因此「口令错」与「密文/头/tag 被改」在读端是同一个可观测事件**。给两者不同的错误码，等于给攻击者一个判定「我把文件改对了没有」的 oracle；本实现统一映射为 `wrong-passphrase-or-tampered`，并在 `ArchiveError` 的文档注释里写明这一点（测试 `WrongPassphrase_YieldsTheSameCodeAsTampering` 同时钉住两条路径给出同一个码）。

错误消息不含明文、不含明文片段、不含除「是否超过上限」以外的长度信息（长度上限是公开常量）。

### D5 升级路径：kdf 是分派键，不是注释

头里带 `kdf` 字符串，读端 `ArchiveKdf.TryParse` 分派，未知标识直接拒绝（不猜、不回退到默认算法）。将来引入 Argon2id 时：

1. 新增 `KdfId.Argon2Id` 与 wire 名 `argon2id`（或 `argon2id-v19`），写端改用新 kdf；
2. 读端按标识分派，旧文件继续可读，**不需要提升 `version`**；
3. 需要「重加密」时，由导入流程解出信封后用新 kdf 重新写，而不是原地改文件；
4. 若届时仍不允许 NuGet，则继续保持 PBKDF2 并提高迭代次数——同样不需要改格式，因为迭代次数本来就在头里且被 AAD 保护。

`version` 只用于**布局**变化（比如把头换成定长结构、改动 AAD 组成）。版本与 kdf 是两个独立维度，混在一起会让「换算法」被迫变成「换格式」。

### D6 凭据禁止入归档（写端拒绝 + 读端纵深防御）

写端：信封 `capabilities.canSendSecrets == true`，或任一 `SourceRecord.secrets` 非空 → 抛 `secrets-not-allowed`，不做「静默剥离」。静默剥离会让调用方以为凭据已经导出，而实际没有——这种「看起来成功了」是凭据迁移路径上最糟的失败形态。

读端也检查一次：即使文件是被别的工具构造的，解出来的信封只要声称带凭据同样拒绝。写端一个检查、读端一个检查，任何一侧被绕过都还有另一侧。

本层**不实现**凭据迁移：`secrets` 始终为 null，DPAPI 重封（ADR-S001 D6）在 W6-6 之前不存在。

## 威胁模型

| 攻击者能力 | 在范围内 | 说明 |
| --- | --- | --- |
| 拿到归档文件（云盘/聊天/U 盘泄漏） | 是 | 内容机密性由 AES-256-GCM 保证；文件名、大小、创建时间可见 |
| 篡改文件任意字节 | 是 | tag/AAD 校验失败；无法定向修改内容 |
| 离线暴力破解口令 | 是（成本受 D2/D3 影响） | PBKDF2 600k 次/猜测；口令 < 12 字符或命中弱表在写入时已拒绝 |
| 拿到口令但没拿到文件 | 否（无文件可解） | 口令本身不落盘、不写日志（本层不存储口令） |
| 拿到本机内存/进程 | 否 | 密钥在 `finally` 里 `ZeroMemory`，但托管堆里的明文口令与信封字符串无法擦除；本层不宣称抵抗内存取证 |
| 侧信道（时序） | 有限 | tag 校验失败与成功之外的时序差异未做恒定时间处理；口令比对发生在 KDF 之后，不构成逐字符 oracle |
| 拒绝服务（畸形文件） | 是 | 头部上限 4 KiB、载荷上限 8 MiB、迭代次数有下限，均在解密前完成校验 |
| 未来算法弱点（PBKDF2/ AES-GCM 被破） | — | 升级路径见 D5；归档按短周期设计，导入后即可删除 |

明确不在范围内：防篡改**检测之外的**恢复（没有备份就没有恢复）、口令找回（设计上没有后门）、对本机已被攻陷场景的保护。

## 偏离登记（相对 ADR-S001 D6）

| 决策项 | ADR-S001 D6 要求 | 本实现 | 理由 | 补偿 / 升级 |
| --- | --- | --- | --- | --- |
| 对称加密 | AES-256-GCM | AES-256-GCM | 一致 | — |
| KDF | Argon2id | PBKDF2-HMAC-SHA256 ≥ 600000 次，盐 16 B，密钥 32 B | 零 NuGet 硬约束；Argon2id 无 BCL 实现 | 口令下限 12 字符 + 弱表；kdf 标识分派，将来可加 `argon2id`（D5） |
| 批量加密 | 未涉及 | 每次加密独立盐 + 独立 nonce | nonce 复用会让 GCM 丧失全部保证 | 测试 `WritingTwice_NeverReusesTheNonceOrTheCiphertext` 钉住 |
| 凭据迁移 | 导入后立即 DPAPI 重封 | **NOT_IMPLEMENTED**：归档完全不含凭据 | 凭据迁移属 W6-6，本层只承载来源清单 | 写端/读端双重拒绝 `secrets-not-allowed` |

## 未覆盖（NOT_IMPLEMENTED）

- **文件落盘**：本层只产出/消费字节，谁写文件、写到哪个目录、是否与 `history.json` 同目录、文件权限，均由 App 决定（W6-5b）。
- **口令我输入与确认 UI**、口令强度提示、忘记口令流程（设计上不可恢复）。
- **凭据迁移**：`secrets` 始终为空；DPAPI 重封不存在。
- **导入端的字段合并**：归档只承载原封不动的 `SourceSyncEnvelope`，冲突判定交给 W6-4 的 `SyncRoundMerger`。
- **Android 侧读写**：本文档只定义 Windows 侧格式，Android 实现（Kotlin/JCA 的 PBKDF2 + GCM）未写，但格式无平台假设（UTF-8 JSON + 标准 AES-GCM）。
- **Argon2id**：见 D2/D5。

## 复现

```bash
dotnet build windows/src/MediaHub.Sync/MediaHub.Sync.csproj -c Debug -nologo
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter FullyQualifiedName~ArchiveEncryptionTests
```
