# W6-65 反向互操作：Android 写的 `.mharchive` 由 Windows 读

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本轮边界：**只补反向证据**（Kotlin 写 → C# 读），不动任何一端的生产实现。Windows 侧只新增一个测试文件与一份测试资产；Android 侧只新增一个测试方法。

---

## 1 动机：前四轮都只有一个方向

W6-62 / W6-63 / W6-64 的方法论是「Windows 权威实现 → Android 逐字节对照」：
先把 Windows 的 `EncryptedArchiveReader` / `EncryptedArchiveWriter` 当权威，
用临时 net10.0 生成器产出黄金向量（`windows-archive-vectors.json`，49950 B），再让 Kotlin 逐条复算。
这套办法能证明「Android 复现了 Windows」，但证明不了**反方向**：

- Kotlin 的 `ArchiveWriter` 省略可选字段（`accountScope` / `secrets` 为 null 时整段不写）、
  未知 `sourceType` 原样保留、JSON 由 kotlinx.serialization 产出 —— 这三件事在 C# 侧都是「读端能不能吃下」的问题，
  只跑同语言回环永远测不到。
- Windows 的读端将来要接 Android 导出的文件（W6-64 的导出卡已经能产出 `.mharchive`），
  如果读端对「省略字段」「转义差异」「未知枚举」中任何一条挑剔，用户拿到手的就是「解密失败」或更糟的静默截断。

所以 W6-65 反过来做一次：**字节由 Android 的 Kotlin 写端产出，Windows 读端必须原样读懂**。

---

## 2 方法：真实写端产出的黄金文件，不在 C# 侧重造

归档的 salt（16 B）与 nonce（12 B）每次写都随机（W6-62），因此**不可能**在 C# 测试里「重新生成同一个文件」再比对
—— 能钉住的只有那份真实文件本身。做法：

1. 在 Android `core/common` 的测试源集里写一个**临时** JUnit 用例（`AndroidArchiveGeneratorForWindows`），
   用生产 `ArchiveWriter.write(envelope, passphrase, ArchiveKdf.DEFAULT_ITERATIONS, createdAtEpochMs)` 写出一份归档；
2. 把它落到 W6 树的 `windows/tests/assets/android-written-archive.mharchive` 并入库；
3. **立刻删掉生成器**（它只存在于本轮工作区，不进任何提交）——
   资产是唯一事实来源，测试套件里不允许出现「能重写资产」的代码，否则 golden 会自我实现。

生成时用 Node 独立解了一遍（`crypto.pbkdf2Sync` + `aes-256-gcm`，AAD = 前 12+161 字节）验证：
解出的载荷正是下面 §3 的信封，说明这份文件确实是 Android 生产写端产出，而不是手工拼的字节。

---

## 3 黄金资产规格（逐字节可断言）

| 项 | 值 |
| --- | --- |
| 文件 | `windows/tests/assets/android-written-archive.mharchive` |
| 长度 | **1164 B** |
| sha256 | `72365f67b240b10aa20c3c5f867f53600199ac5eb9a834e0ebe672d8d6794a15` |
| magic | `MHSYNCA1`（8 B ASCII） |
| 头部长度（大端 int32 @8） | **161** |
| 头部 JSON | `{"version":1,"kdf":"pbkdf2-sha256","iterations":600000,"saltBase64":"rk4gfF2NEFUQih+xagtPDQ==","nonceBase64":"Jnfcf+l0U161FUBq","createdAtEpochMs":1700000000000}` |
| trailer（magic+长度+头部） | 173 B |
| 密文长度 | 975 B |
| 明文（信封 JSON）长度 | **975 B** |
| 明文 sha256 | `68957f0d6658058139b861d86c868b64364cf7011adb7189851d5ebd40e16b79` |
| 口令 | `correct horse battery staple` |

信封内容（C# 用例里的期望值就是这张表）：

| 字段 | 值 |
| --- | --- |
| `schemaVersion` / `minimumReaderVersion` | 1 / 1 |
| `deviceId` | `device-android-pixel` |
| `snapshotId` | `0123456789abcdef0123456789abcdef` |
| `createdAtEpochMs` | 1 700 000 000 000 |
| `capabilities` | `canSendSecrets=false`、`sourceTypes=["emby","jellyfin","webdav"]` |
| 源 1 | `0f9a1c2e-1111-4a2b-9c3d-000000000001` / `emby` / **家庭 NAS（客厅）** / **张三** / rev 42 / fv 3 |
| 源 1 端点 | `http://192.168.1.20:8096/emby`（primary=true, enabled=true, order=0）与 `https://nas.example.invalid/emby`（false / **false** / 2） |
| 源 2 | `0f9a1c2e-2222-4a2b-9c3d-000000000002` / **`acme-nas`（未知类型）** / `Backup shelf` / accountScope **缺省** / rev 7 / fv 1 |
| 源 2 端点 | `acme://shelf-one`（true / true / 0） |
| 墓碑 1 | `0f9a1c2e-3333-4a2b-9c3d-000000000003` / 1 760 000 000 000 / 5 |

刻意选了四类「容易在跨语言时塌掉」的输入：**中文与全角括号**、**未知 sourceType**、**可选字段缺省**（源 2 的 `accountScope`、两条源的 `secrets`）、**多端点且非主用**。

---

## 4 Windows 侧：8 个用例

新文件 `windows/tests/MediaHub.Tests/ArchiveAndroidInteropTests.cs`（290 行），全部读同一份资产：

| # | 用例 | 钉住什么 |
| --- | --- | --- |
| 1 | `The_android_written_archive_is_read_field_by_field` | 逐字段：envelope 六个标量 + capabilities + 两条源的全部属性 + 端点数组 + 墓碑 |
| 2 | `An_unknown_source_type_is_carried_through_untouched` | `acme-nas` 的 `Raw` / `Wire` 都原样、`IsKnown` 与 `SyncSourceTypes.IsKnown` 都是 false |
| 3 | `The_android_written_archive_bytes_are_pinned` | 长度、sha256、magic、头部长度四个硬值 |
| 4 | `The_android_header_matches_the_canonical_shape` | 头部六字段值 + 头部 JSON 逐字相等 + 重编码后**同值**（见 §5） |
| 5 | `The_windows_serializer_reproduces_the_kotlin_document_semantically` | 先自己解密取明文（sha256 钉住），再 `JsonNode.DeepEquals` 比对 Windows 重序列化的文档；并验证 canonical form 是不动点 |
| 6 | `A_wrong_passphrase_is_indistinguishable_from_tampering` | 错口令 / 改头部时间戳 / 翻密文一位，三者同码 `wrong-passphrase-or-tampered`、同一句文案 |
| 7 | `A_layout_error_is_reported_before_the_passphrase_is_examined` | 只剩头部 ⇒ `corrupt-ciphertext`（框架错误优先于空口令）；null ⇒ `passphrase-empty`；`"short"` ⇒ `passphrase-too-weak` |
| 8 | `The_android_written_archive_carries_no_plaintext` | 七个明文 token 在整份归档字节里一个都搜不到 |

测试工程只加了一条 Content（`windows/tests/MediaHub.Tests/MediaHub.Tests.csproj:28-29`），
照既有 `sample-av.mp4` 的形状 `CopyToOutputDirectory="PreserveNewest"`，测试里用 `Path.Combine(AppContext.BaseDirectory, "assets", …)` 取；不引入任何新依赖。

---

## 5 本轮唯一的新发现：头部「同值但不同字节」

第一版用例按「重序列化必须逐字节相等」写，跑出来红了，位置在 salt 的 base64 里：

```
expected ... 105,104,43,120,97   (i h + x a)
actual   ... 105,104,92,117,48  (i h \ u 0)
```

原因：**System.Text.Json 会把 `+` 转义成 `\u002B`**，kotlinx.serialization 原样写 `+`。
本文件的 saltBase64 是 `rk4gfF2NEFUQih+xagtPDQ==`，C# 重编码后变成 `rk4gfF2NEFUQih\u002BxagtPDQ==`，
一个字符换六个，所以两份头部 JSON 在字节上**不可能**相等。

这是**无害**的，理由要写下来（否则以后有人会「修」它）：

1. GCM 认证的是**磁盘上的头部原字节**：两端都把 `magic + BE int32 + headerJson` 的原字节当 AAD，
   从来不认证「重编码结果」。只要写端与读端读的是同一份字节，转义差异不影响任何一步。
2. 两端读端都能解析另一种形式：`ArchiveHeaderCodec.TryParse`（C# `JsonDocument` / Kotlin `Json.parseToJsonElement`）都是按 JSON 语义取值，
   `\u002B` 与 `+` 解析出同一个字符串。
3. 契约因此定成「**同值**」而不是「同字节」：用例改成 `TryParse(Serialize(header))` 后 `Assert.Equal(header, reparsed)`；
   注释里写明了为什么不断言字节相等。

教训（跨语言格式通用）：**不要用「重序列化后字节相等」当跨语言契约判据**——每种 JSON 库的转义策略都不同，
这个判据会稳定地红，而红的原因与正确性无关。要在字节层面钉，就钉**写端写在盘上的那份字节**（本文件 §3 的 sha256）。

---

## 6 落点行号表

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `windows/tests/assets/android-written-archive.mharchive` | — | 新文件，1164 B，Kotlin `ArchiveWriter` 真实产出 |
| `windows/tests/MediaHub.Tests/MediaHub.Tests.csproj` | :28-29 | 新增一条 Content 挂该资产 |
| `windows/tests/MediaHub.Tests/ArchiveAndroidInteropTests.cs` | :27 | `public sealed class ArchiveAndroidInteropTests`（新文件，290 行） |
| 同上 | :29-54 | 全部硬值常量：`Passphrase` / `OtherPassphrase` / `Magic` / `ArchiveLength` / `ArchiveSha256` / `HeaderLength` / `TrailerLength` / `PayloadSha256` / `PayloadLength` / `HeaderJson` |
| 同上 | :56-57 | `ArchivePath` → `AppContext.BaseDirectory/assets/android-written-archive.mharchive` |
| 同上 | :59 / :113 / :124 / :135 / :162 / :189 / :215 / :234 | 八个 `[Fact]` |
| 同上 | :256 / :258 / :275 | 三个辅助：`ReadArchive` / `DecryptPayload`（自有 AesGcm，AAD=前 173 B）/ `WithHeaderText`（等长替换） |
| `core/common/src/test/kotlin/com/mediahub/core/common/archive/ArchiveRoundTripTest.kt` | :360-371 | 新增 `writing_refuses_a_payload_that_is_larger_than_the_archive_limit`（见 §7） |

---

## 7 顺带补的 Android 侧缺口：写端 8 MiB 真的走了一遍

W6-62 的 `ArchiveWriter` 有「载荷 > 8 MiB ⇒ `PAYLOAD_TOO_LARGE`」这一步，但 Android 侧只断言过常量
（`MAX_PAYLOAD_BYTES` 等于多少），**没有一例真的让写端走那条分支**；而 Windows 侧一直有
`OversizedPayload_IsRejectedWhenWriting`（9 MB 载荷）。也就是说这条限在两端是「一边有行为证据、一边只有常量」——
上限被改坏时 Android 那组用例一个都不会响。本轮补上：

| 输入 | 期望 |
| --- | --- |
| 9 000 000 字符名的一条源（载荷 > 8 MiB） | `ArchiveError.PAYLOAD_TOO_LARGE` |
| 4 000 000 字符名的一条源（明显更小） | 写成功、reason 为 null |

第二行是反向对照：如果只断言「大的一定拒」，把上限改小或把别的错误当拒绝理由，用例照样绿。

---

## 8 本机实跑（串行，防 OOM）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| C# 全量 | `cd D:/mh-w6 && dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug` | **1585 通过 / 0 失败 / 0 跳过**（1577 → 1585，+8） |
| C# 只看新例 | 同上加 `--filter FullyQualifiedName~ArchiveAndroidInteropTests` | **8/8 通过**，首次跑红了 1 例（§5），修断言后全绿 |
| Android 三模块 | `D:/mh-verify/run-w6-65-gradle.bat`（低内存模板，`--max-workers=1`） | `BUILD SUCCESSFUL in 1m 6s`、`EXIT=0` |
| 清理 | 跑完 `gradlew --stop` | 仓库根 `hs_err_pid*.log` 计数 0、残留 java 进程 0 |

XML 汇总（`<testsuite>` 属性相加）：

| 模块 | 套件 | 用例 | 失败 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- |
| `core/common` | 42 | **596** | 0 | 11 | 595 → 596（+1 = §7 那例） |
| `core/database` | 20 | 192 | 0 | 12 | 不变 |
| `feature/settings` | 23 | 353 | 0 | 0 | 不变 |

`ArchiveRoundTripTest` 单套件由 23 例增至 **24 例**、0 失败。
本轮**没有**新增套件，`--required-suite` 仍是 48 条；门禁用例数随 core/common +1。

---

## 9 未覆盖（本节交付时必须如实保留）

1. **反向的设备侧**：C# 用例全部在 Windows 本机跑；Android 侧没有「读 Windows 写的归档」以外的设备证据，
   也没有「Android 写的归档在真机上产出」这回事（写端是纯 JVM 逻辑，但 PBKDF2 600 000 轮的设备耗时仍未测）⇒ DEVICE_UNVERIFIED。
2. **真实两机搬运**：把 Android 导出的 `.mharchive` 拷到另一台 Windows 机器再导入的完整流程 NOT_RUN（同 §33.8 / §34.8 / §35.8 / §36.8 / §37.8，都需要第二台机器）。
3. **迭代数其他取值**：本资产只有 600 000 一档。Kotlin 侧接受 `>= MIN_ITERATIONS`、C# 读端同理，但「Android 用 700 000 写、Windows 读」这种跨端异值从未跑过。
4. **未知 JSON 字段**：两端读端都忽略未知属性，但没有用例把「Android 将来新增一个字段」写进归档再让旧 Windows 读——这属于版本演进，需要 `minimumReaderVersion` 的独立评审。
5. **载荷上限的反向**：§7 补的是写端拒绝；「塞一份接近 8 MiB 的真归档让 C# 读」仍只有既有 `OversizedCiphertext_IsRejectedBeforeDecryption` 的拒绝侧证据，正向的大文件读取未验。
6. **转义差异的其他形态**：§5 只覆盖了 `+`。U+2028 / 控制字符 / 代理对在两端 JSON 库里的行为不同，未做矩阵。
7. **不涉及 GUI**：两端都不改界面，因此没有 GUI 证据需求；同步页四张卡的 GUI 证据仍按 W6-43 / W6-49 / W6-58 / W6-64 的既有口径记为 GUI_UNVERIFIED。

---

## 10 下一步

- **W6-66 候选**：把 §3 的资产再加一份「异迭代数（如 700 000）」与一份「旧版本 `minimumReaderVersion`=2」的，做成跨端演进用例；
- **真实两机搬运**：Windows 导出 → 拷到另一台机器 → Android 导入（同时关闭 §36.8 第 1 条与 §37.8 第 1 条）；
- **设备侧密码学**：PBKDF2 600 000 轮在模拟器 / 低端机上的耗时与峰值内存，写读两侧都要（§36.8 第 2 条、§37.8 第 2 条）；
- 归档格式若要演进（新 KDF、新字段），先按 W6-47 ADR 的方法写契约，再两端同步加码 —— 不要单边加。
