# W6-68 8 MiB 上限附近的真数据穿越 AES-256-GCM

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本轮边界：**只加测试与门禁**。归档字节布局、magic、KDF 参数、AAD、稳定码集合（仍 12 个）、8 MiB 常量本身一行不改；两端生产代码零改动。

---

## 1 动机：既有用例只证明「超限会被拒」，没证明「上限本身可用」

归档层从 W6-62 起就有 8 MiB（8388608 B）载荷上限。两端各自都有「超限就拒」的用例：

| 侧 | 既有用例 | 输入 | 断言 |
| --- | --- | --- | --- |
| Kotlin | `ArchiveRoundTripTest.writing_refuses_a_payload_that_is_larger_than_the_archive_limit` | `displayName = "x".repeat(9_000_000)` | 写端 `payload-too-large` |
| C# | `ArchiveEncryptionTests.OversizedCiphertext_IsRejectedBeforeDecryption` | `new byte[MaxPayloadBytes + 1]` 作密文 | 读端 `payload-too-large` |
| C# | `ArchiveEncryptionTests.OversizedPayload_IsRejectedWhenWriting` | `new string('x', 9_000_000)` | 写端 `payload-too-large` |

三条合起来只说明一件事：**超过上限会被拒**。真正会撞上的问题是相反的——一份大型媒体库导出的信封，
载荷恰好落在上限附近时，加密、写盘、读回、GCM 校验这条完整链路还成不成立。这个缺口有三重风险：

1. **差一错误（off-by-one）**。写端判据是 `payload.size > MAX`（大于才拒）、读端是 `cipherLength > MAX`；
   任何一处写成 `>= `，恰好等于 8388608 B 的合法载荷就会被拒——而既有用例全是 9 MB 级，永远碰不到这个边界。
2. **长度假设**。AES-GCM 的密文长度等于明文长度，读端正是**在解密之前**按 `cipherLength` 判上限的；
   这条等价关系一旦被将来的压缩/分块改写破坏，读端就会先花 600 000 轮 PBKDF2 再失败。
3. **跨语言一致性**。两端编码器（kotlinx `buildJsonObject{}.toString()` 与 `Utf8JsonWriter`）字段序相同，
   但一旦载荷逼近上限，任何一端的细微差异都会让同一份信封在两个实现里得到不同的字节长度——恰好在下限附近，
   这就决定了一份归档能不能被对端打开。

所以本轮把「上限本身可用」写成契约：用 Windows 权威实现生成一份**恰好 8 MiB** 的载荷与其归档，
让 Kotlin 端逐字节复现同一份载荷与同一份归档，并且两端都验证「恰好上限可读、MAX+1 被拒」。

---

## 2 方法：只存配方与哈希，不把 8 MB 归档入库

沿用 W6-62 以来的黄金向量方法（Windows 权威实现产出答案、Kotlin 逐条对照），但这一轮的对象是「上限附近的字节」。

直接把 8 MiB 归档当测试资源入库不可接受：单测仓库会永久膨胀 16 MB（Kotlin 与 C# 各一份），
且二进制资源无法 review「它到底是哪个信封」。采用的做法是**只存配方**：

| 决定 | 内容 | 理由 |
| --- | --- | --- |
| 载荷长度可计算 | 一个 source 的 `displayName` 由 ASCII `x` 重复到精确长度（`displayNameLength = 8388242`），其余字段固定 | 载荷字节长度 = 基准 JSON 长度 + displayName 长度，可反解到恰好 8388608 B；纯 ASCII 不触发转义，长度稳定 |
| 固定 salt/nonce | `saltBase64 = "AAECAwQFBgcICQoLDA0ODw=="`（0..15）、`nonceBase64 = "EBESExQVFhcYGRob"`（16..27） | 两端 `write()` 都自取随机 salt/nonce，**不可能**逐字节相同；只有手工按固定 salt/nonce 加密才能让两端得到同一份归档字节 |
| 只存哈希与长度 | `payloadByteLength` / `payloadSha256` / `archiveByteLength` / `archiveSha256` / `headerBase64` / `prefixByteLength` | 8 MB 资源不入库；断言「编码结果的长度与 sha256」等价于逐字节比较，失败时还能立刻定位是载荷还是归档 |
| 真实写端只钉语义 | `ArchiveWriter.write` / `EncryptedArchiveWriter.Write` 各自随机 salt/nonce ⇒ 只断言「写→读回的信封 encode 结果相等」 | 把「随机 salt 也应可复现」当断言只会造出 flaky 用例 |
| 读端超限单独构造 | `prefix + 8388609 字节零密文 + 16 字节 tag` | 读端必须在**解密前**返回 `payload-too-large`；零密文可证明它没有先尝试解密（否则会给 `wrong-passphrase-or-tampered`） |

两个刻意不采用：**把 8 MiB 归档直接入库**（仓库永久多 16 MB 且无法解释来源）、
**在测试里现场构造 8 MiB 再和另一端比**（两端测试无法互调，等于没有跨语言断言——必须一端出答案、另一端去对）。

---

## 3 黄金资产规格（`nearLimit` 段）

文件 `core/common/src/test/resources/archive/windows-archive-vectors.json`：13 段 + 新增 `nearLimit`，
74164 B → **77034 B**（JSON 深度比对：老 13 段全等，只多 `nearLimit`）。C# 侧读同一份字节的副本 `windows/tests/assets/windows-archive-vectors.json`（sha256 相同，由 csproj 的 `Content` 挂到输出目录）。

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `maxPayloadBytes` | 8388608 | 上限本身（不新造常量，直接引用 `ArchiveHeader.MaxPayloadBytes`） |
| `recipe.displayNameLength` | 8388242 | 反解出来的长度；`encode` 后恰好 8388608 B |
| `payloadByteLength` / `payloadSha256` | 8388608 / `f6bc78f30a55b46b3a8cb64c28ad035f04e4fad86ba6542f8be64d02b3fa11ae` | 同一份配方两端 encode 出的字节**完全相同** |
| `headerBase64` → `prefixByteLength` | 161 B 头 → 173 B（magic 8 + BE int32 4 + header 161） | 头部就是 Windows 用作 AAD 的那串字节 |
| `archiveByteLength` / `archiveSha256` | 8388797 / `b47b3cc3c4bd4abe3a317c39632d1f220415dfc703eb21a8c881deacd2edc287` | = 173 + 8388608 + 16；两端手工加密得到同一份归档 |
| `tooLarge` | displayName 8388243 → 载荷 8388609 B → 写端 `payload-too-large` | MAX+1 的写侧答案 |
| `oversizedCiphertext` | 密文 8388609 B → 读端 `payload-too-large` | MAX+1 的读侧答案（解密前） |

配方其余字段（`recipe` 子对象）：`deviceId = "device-near-limit"`、`snapshotId = "0123456789abcdef0123456789abcdef"`、
`createdAtEpochMs = 1700000000000`、`capabilities = { canSendSecrets: false, sourceTypes: ["emby"] }`、
`syncSourceId = "near-limit-1"`、`sourceType = "emby"`、`recordRevision = 1`、`fieldVersion = 1`、`iterations = 600000`、口令 `"correct horse battery staple"`。

---

## 4 用例：两端各 5 例，逐条同义对照

| # | Kotlin `ArchiveNearLimitTest` | C# `ArchiveNearLimitTests` | 钉住什么 |
| --- | --- | --- | --- |
| 1 | `the_recipe_reproduces_the_windows_payload_byte_for_byte` | `TheRecipeReproducesTheWindowsPayloadByteForByte` | 同一配方 encode 出 **8388608 B**，sha256 与 Windows 相同 |
| 2 | `the_header_from_the_recipe_is_the_canonical_aad_windows_used` | `TheHeaderFromTheRecipeIsTheCanonicalAadWindowsUsed` | 头部 JSON 逐字节等于向量 `headerBase64`；`buildPrefix` 长度 173 |
| 3 | `a_payload_of_exactly_the_ceiling_encrypts_to_the_windows_archive_hash_and_reads_back` | `APayloadOfExactlyTheCeilingEncryptsToTheWindowsArchiveHashAndReadsBack` | 固定 salt/nonce 加密出的归档 8388797 B、sha256 相同；读回来逐字段相等 |
| 4 | `the_writer_accepts_exactly_the_ceiling_and_refuses_one_byte_more` | `TheWriterAcceptsExactlyTheCeilingAndRefusesOneByteMore` | 恰好上限：真实 `write`→`read` 往返成功；MAX+1 写端给 `payload-too-large` |
| 5 | `the_reader_refuses_an_oversized_ciphertext_before_decryption` | `TheReaderRefusesAnOversizedCiphertextBeforeDecryption` | 8388609 B 密文在解密前判 `payload-too-large`，且不返回信封 |

Kotlin 侧 5 例在新文件 `core/common/src/test/kotlin/com/mediahub/core/common/archive/ArchiveNearLimitTest.kt`（244 行），
并已钉进 Android 单元门禁（`--required-suite` 49 → **50**）；C# 侧 5 例在 `windows/tests/MediaHub.Tests/ArchiveNearLimitTests.cs`（196 行）。

---

## 5 本轮踩到的两个真实坑

1. **`dotnet test` 用了 x86 输出目录**。首轮脚本先 `dotnet build windows/MediaHub.Windows.sln -c Debug` 再 `dotnet test … --no-build`，
   日志里的可执行路径是 `bin\Debug\net10.0-windows\win-x64\MediaHub.Tests.dll`（无 `x64` 段），
   而 `MediaHub.Tests.csproj` 的 `Platforms=x64`、sln 里项目只映射到 x64 平台。
   结果：新文件已编译进 `bin\x64\…` 那份 DLL（`strings` 能搜到 `ArchiveNearLimitTests`），
   但 `dotnet test` 加载的是旧的 `bin\Debug\…` DLL，跑出 **1599 例**（旧数）、`--filter ArchiveNearLimitTests` 报 `"No test matches the given testcase filter"`。
   修法：**显式 `-p:Platform=x64`** 后再 build + test，1604 例、新例 5/5。
   与 W6-67 那次「`MediaHub.sln` vs `MediaHub.Windows.sln`」同类：**验证脚本自身写错路径，看起来却像产品缺陷**。
2. **Kotlin 里 `JsonElement.getValue` 不存在**。首轮 gradle 编译报 5 处 `Unresolved reference 'getValue'`：
   `record.getValue("tooLarge").getValue("displayNameLength")` —— 第一个 `getValue` 是 `JsonObject` 的、返回 `JsonElement`，
   第二层必须显式转 `JsonObject` 才能再取键，改为 `.jsonObject.getValue(...)`。
   教训：向量文件的嵌套断言写成链式取值时，Kotlin 的类型是 `JsonElement`，不像 `JsonObject` 那样有 `getValue`。

> 生成器首跑还失败过一次：`System.InvalidOperationException: near-limit written archive length 8388812 != 8388797`。
> 根因是 `EncryptedArchiveWriter.Write` 自取随机 salt/nonce，某些 base64 含 `+` 而 `System.Text.Json` 会把它转义成 `\u002B`（W6-65 已记录的结论），
> 头部 JSON 长度因此在 161 / 166 之间浮动 ⇒ **写入归档的总长度不可作为断言**。修法是写后 `EncryptedArchiveReader.Read` 回读并比 `Serialize` 结果，并删掉向量里不可复现的 `writtenArchiveByteLength` 字段。

---

## 6 落点行号表

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `core/common/src/test/kotlin/…/archive/ArchiveNearLimitTest.kt`（新） | 全文 244 行 | Kotlin 侧 5 例；`craft()` 用向量固定 salt/nonce 手工 GCM；`envelopeWithDisplayName(n)` 按配方重建信封 |
| `core/common/src/test/resources/archive/windows-archive-vectors.json` | `nearLimit` 段 | 74164 → 77034 B；老 13 段逐字节不变 |
| `.github/workflows/android-ci.yml` | required-suite 块末位 | 49 → 50 条，新增 `com.mediahub.core.common.archive.ArchiveNearLimitTest`；注释块加 3 行 |
| `windows/tests/MediaHub.Tests/ArchiveNearLimitTests.cs`（新） | 全文 196 行 | C# 侧 5 例；`JsonNode.Parse` 读 `assets/windows-archive-vectors.json` |
| `windows/tests/assets/windows-archive-vectors.json` | 全文 | 与 Android 侧同字节的副本（77034 B） |

本轮**没有改任何生产代码**：`ArchiveWriter`/`ArchiveReader`/`EncryptedArchiveWriter`/`EncryptedArchiveReader`/`ArchiveHeader` 一行未动。

---

## 7 本机实跑（串行，防 OOM）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 生成器重跑 | `cd D:/mh-verify/archive-vectors && dotnet run -c Release`（生成器不入库） | 首次失败（见 §5 末），修复后 `wrote …windows-archive-vectors.json (77033 bytes)`、`EXIT=0`；磁盘 **77034 B**（含行尾换行）；老 13 段 JSON 深度全等 |
| C# 全量 build + test | `D:/mh-verify/run-w6-68-windows2.sh`（build `MediaHub.Windows.sln`、`dotnet test -p:Platform=x64`、再 filtered 跑新例） | `BUILD_EXIT=0`（0 警告 0 错误）、`TEST_EXIT=0` → **1604 通过 / 0 失败 / 0 跳过**（1599 → 1604，+5）、`NEAR_EXIT=0` → 5/5（1 s） |
| Android 三模块 | `D:/mh-verify/run-w6-68-gradle.bat`（低内存模板 + `--no-daemon`） | `BUILD SUCCESSFUL in 2m 1s`、`167 actionable tasks: 17 executed, 150 up-to-date`、`EXIT=0`；仓库根 `hs_err_pid*.log` 计数 0 |

XML 汇总（`<testsuite>` 属性相加，非估算）：

| 模块 | 套件 | 用例 | 失败 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- |
| `core/common` | **44** | **612** | 0 | 11 | 43 → 44 套件（+1 = `ArchiveNearLimitTest`）、607 → 612（+5） |
| `core/database` | 20 | 192 | 0 | 12 | 不变 |
| `feature/settings` | 23 | 354 | 0 | 0 | 不变 |

三模块合计 **1158** 例；`--required-suite` 49 → **50**；`androidTest` 仍 88 例、instrumentation 仍 112 例（本轮无 Compose 断言）。
C# 侧由 1599 增至 **1604**（+5）；Android 侧唯一用例由 2278 增至 **2283**（+5）。

四条 CI 都是 exact-SHA 上的实跑（两个 head、两个工作流），全部 `completed / success`：

| head | 工作流 | run id | 关键计数 |
| --- | --- | --- | --- |
| `7d0f055600ae20e8df1fcfa4892e6799478fa0ab`（主树） | `windows-ci` | 40389203079 | `MediaHub.Tests` 469 + 104 = 573；licence `projects=13 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | `android-ci` | 40389203196 | `uniqueTests` **2283**；`--required-suite` **50** 条；两个 VI job 各 `19 unique mandatory` 命中 `7d0f0556…` |
| `b08186be87c5d983c8628dfee852416fb534fdf9`（本树） | `windows-ci` | 40389300435 | `MediaHub.Tests` **1604** + 104 = **1708**；licence `projects=15 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | `android-ci` | 40389300211 | `uniqueTests` **2283**（与主树同口径，本树已 merge 主树提交）；`--required-suite` 50 条；两个 VI job 各 `19 unique mandatory` 命中 `b08186be…` |

W6 windows 由 1692 增至 **1708**（+5）；Android 侧由 2278 增至 **2283**（+5）。两条 Android CI 的 `uniqueTests` 相同，说明本树 merge 后与主树口径一致。

---

## 8 未覆盖（本节交付时必须如实保留）

1. **真实两机搬运**：8 MiB 载荷的端到端搬运（Windows 导出 → 拷到另一台机器 → Android 导入）仍 NOT_RUN，同 §33.8–§40.7，需要第二台机器。
2. **恰好上限只覆盖一种形状**：一个 source、纯 ASCII 的 displayName。多 source、含中文/转义字符、含 secrets 的载荷在同一字节长度下的行为没有单独测；
   不过 displayName 长度是唯一变量，其余字段形状已由 §40 与既有 `ArchiveRoundTripTest` 覆盖。
3. **PBKDF2 耗时**：8 MiB 载荷的加密/解密在本机约 1 s（600 000 轮 PBKDF2 占绝大部分），没有做性能断言，也没在低端设备上测。
4. **传输层上限**：本轮只碰归档层的 8 MiB；传输层的同值常量（`SourceSyncTransferReasons.PayloadTooLarge`）是另一层，未动。
5. **不涉及 GUI 与设备**：纯 JVM/C# 逻辑层，无新增控件、无设备依赖；真机 `DEVICE_UNVERIFIED` 与 GUI `GUI_UNVERIFIED` 的既有口径不变。

---

## 9 下一步

- 真实两机搬运：Windows 导出 → 拷到另一台机器 → Android 导入（同时关闭 §36.8 第 1 条、§37.8 第 1 条、§39.6 第 1 条、§40.7 第 1 条、§41.7 第 1 条）；
- 备份（`.mhbackup`）与配对（pairing）两侧的文本判定是否也该收进 `WireText`（§40.7 第 3 条的遗留）；
- Windows 同步页真 GUI UIA 证据（W10 U2/U3/U4 需要用户授权窗口）；
- 归档格式若要演进（新 KDF、新字段），先写契约再两端同步加码——不要单边加。
