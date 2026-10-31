# W6-67 跨语言文本边界契约：空白、大小写折叠、孤立代理项

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本轮边界：**只对齐文本判定规则与畸形输入的分诊**；归档字节布局、magic、KDF 参数、AAD、稳定码集合（仍 12 个）一行不改。Windows 侧行为零变化（它的运行时默认本来就是 Unicode White_Space），Android 侧向同一套显式规则收敛。

---

## 1 动机：同一份字节，两端给出不同的稳定码

W6-62 到 W6-66 建立的互操作契约都依赖同一个前提：**同一条坏输入，两端必须报同一个 reason**。前四轮把字节布局、KDF 参数、AAD、判序都钉住了，但还有一层没钉：**文本判定本身**。

这一层默认继承各语言的运行时，而两个运行时并不一致。用探针在本机实测（Java 21.0.9 / .NET 10.0.113），得到四处硬分歧：

| # | 分歧 | JVM（Kotlin 默认） | .NET（C# 默认） | 后果 |
| --- | --- | --- | --- | --- |
| 1 | 空白集 | `Char.isWhitespace \|\| isSpaceChar`：**含** U+001C–U+001F，**不含** U+0085 | `char.IsWhiteSpace` = Unicode White_Space：**含** U+0085，**不含** U+001C–U+001F | 口令 `U+0085+password1234` 在 Windows 是 `passphrase-too-weak`，在 Android 被接受 |
| 2 | `trim` 语义 | `String.trim()` 只剥 `<= U+0020`，与它自己的空白集不一致 | `String.Trim()` 与 White_Space 集一致 | 12×U+001C：Android 判 `passphrase-empty`，Windows 判 `passphrase-too-weak` |
| 3 | 大小写折叠 | `equalsIgnoreCase` 会把 U+017F（长 s）、U+212A（开尔文符号）折成 ASCII | `OrdinalIgnoreCase` 只折 ASCII A–Z | `paſſword1234` 在 Android 命中弱口令表，在 Windows 不命中 |
| 4 | 孤立代理项 | `PBEKeySpec` 静默替换成 `?` 后派生出一个对端永远读不开的密钥 | `Rfc2898DeriveBytes.Pbkdf2` 抛**未捕获**的 `EncoderFallbackException` | 同一口令，一端写出死文件、另一端直接崩 |

前三条是「同一条坏输入两端不同码」，第四条更严重：**两端都没有把错误当成错误**。所以这一轮不是加功能，是把四条边界钉成显式契约。

另有一条比口令更隐蔽的：**JSON 里的孤立代理项**。`JsonDocument.Parse` 接受 `"\ud800"`，失败被推迟到 `GetString()`（抛 `InvalidOperationException`，不是 `JsonException`）；kotlinx.serialization 则一路放行，`primitive.content` 直接返回含孤立代理项的字符串。而 C# 的 `Utf8JsonWriter.WriteString` 遇孤立代理项**不抛**，静默写成 `\uFFFD`（实测 `"a\ud800b"` → `{"deviceId":"a\uFFFDb"}`）—— 等于悄悄改用户数据。

---

## 2 方法：黄金向量由 Windows 权威实现产出，两端逐条对照

沿用 W6-62 以来的方法论，但这一轮的对象不是「字节」，而是「判定结果」。

1. 在 Windows 侧（权威实现）给 `ArchivePassphrase` / `ArchiveKdf` / `ArchiveHeaderCodec` / `SourceSyncCodec` / `EncryptedArchiveReader` / `EncryptedArchiveWriter` 喂一批边界输入，把**答案**（blank / trimmed / accepted / reason / parses）写成 `edges` 段追加进既有黄金向量文件；
2. 同一份 `edges` 段既被 Kotlin 的 `ArchiveEdgeContractTest` 读，也被 C# 的 `ArchiveEdgeContractTests` 读 —— 两份用例逐条比对同一个答案；
3. 生成器的老 12 段（`kdf` / `header` / `envelope` / `archive` / `errors` / `passphrases` / `writerCases` 等）**逐字节不变**，`edges` 只能追加在根对象末尾。

为什么让 Windows 当权威：C# 的 `char.IsWhiteSpace` 恰好就是 Unicode White_Space 属性本身，而 JVM 的 `Char.isWhitespace` 是它的一个有偏差的子集加空间类。把「属性本身」当基准，Android 侧是向标准收敛，Windows 侧行为零变化 —— 这样这一轮不会改动任何一个已在 CI 里跑绿的 Windows 结果。

---

## 3 黄金资产规格（`edges` 段 10 组，逐条可断言）

文件 `core/common/src/test/resources/archive/windows-archive-vectors.json`：老 12 段 + 新增 `edges`，49950 B → **75553 B**（JSON 深度比对：老段全等，只多 `edges`）。C# 侧读的是同一份字节的副本 `windows/tests/assets/windows-archive-vectors.json`（sha256 相同，由 csproj 的 `Content` 挂到输出目录）。

| 组 | 条数 | 钉住什么 |
| --- | --- | --- |
| `whitespaceCodePoints` | 25 | 就是 Unicode White_Space 的 BMP 全集；C# 用例另外逐码点断言它等于 `char.IsWhiteSpace` |
| `blankCandidates` | 13 | blank 与 trimmed 两列；含 U+001C/U+001F/U+200B/U+FEFF/U+00AD 这些「看着像空白但其实不是」的反例 |
| `kdfNameCandidates` | 9 | kdf 名的 ASCII 折叠 + 前导/尾随空白剥离；长 s 与开尔文符号必须**不**被接受 |
| `caseFoldPairs` | 6 | ASCII 折叠的正例与三个非 ASCII 仿冒反例 |
| `loneSurrogateCandidates` | 7 | 按 UTF-16 码元判，不按字符数判；合法代理对必须不算孤立 |
| `passphraseCandidates` | 11 | 口令策略的 12 条判序落点 |
| `headerVariants` | 10 | parses / kdfAccepted / version 三列；含重复键后胜、未知字段忽略、头内孤立代理项 |
| `envelopeVariants` | 11 | 信封解码的 reason；含 `missing-field:deviceId` 与 `malformed-json` |
| `envelopeWriteRejections` | 4 | 写侧拒绝：`invalid-field:deviceId` / `missing-field:deviceId` / `invalid-field:displayName` |
| `archivePassphraseCases` | 5 | 同一候选口令在**读**与**写**两侧各给什么 reason |

几条最值得记住的答案（都是实测值，不是推断）：

- 12 个 U+001C ⇒ **不是空白** ⇒ 口令 `passphrase-too-weak`（单字符重复），不是 `passphrase-empty`；12 个 U+0085 ⇒ **是空白** ⇒ `passphrase-empty`；
- `U+0085 + password1234` ⇒ `passphrase-too-weak`（剥掉 NEL 后命中弱表）；`U+001C + password1234` ⇒ **接受**（U+001C 不是空白，13 个字符，不命中弱表）；
- `paſſword1234`（长 s）⇒ **接受**：ASCII 折叠不碰非 ASCII，所以它不等于 `password1234`；
- 头部 `kdf` 前导 U+001C ⇒ 头**解析成功**但 kdf **不被接受** ⇒ 读端报 `unknown-kdf`；前导 U+0085 则被剥掉 ⇒ 正常；
- 头部 `kdf` 是 `"\ud800"` ⇒ 头**解析失败** ⇒ `corrupt-header`（不是 `unknown-kdf`）；
- 头部重复 `version` 键 ⇒ 后者胜（`version=2`，随后由 version 检查报 `unsupported-version:2`）；
- 信封 `deviceId` 只有 U+0085 ⇒ `missing-field:deviceId`；只有 U+001C ⇒ **接受**（不是空白）。

---

## 4 用例：两端各 11 例，逐条同义对照

| # | Kotlin `ArchiveEdgeContractTest` | C# `ArchiveEdgeContractTests` | 钉住什么 |
| --- | --- | --- | --- |
| 1 | `the_whitespace_table_is_the_unicode_white_space_property_used_by_dotnet` | `TheWhitespaceTableIsTheUnicodeWhiteSpacePropertyOfDotNet` | 25 码点表；C# 侧逐码点对 `char.IsWhiteSpace` 全 BMP 比对 |
| 2 | `is_blank_and_trim_agree_with_the_windows_answers_for_every_candidate` | `IsBlankAndTrimAgreeWithEveryVectorCandidate` | 13 条候选的 blank / trimmed |
| 3 | `ascii_case_folding_never_folds_a_non_ascii_lookalike` | `AsciiCaseFoldingNeverFoldsANonAsciiLookalike` | 6 组折叠对 + 长 s / 开尔文两条硬断言 |
| 4 | `lone_surrogates_are_detected_by_code_unit_not_by_character_count` | `LoneSurrogatesAreDetectedByCodeUnitNotByCharacterCount` | 7 条代理项候选 |
| 5 | `the_passphrase_policy_gives_the_windows_answer_for_every_candidate` | `ThePassphrasePolicyGivesTheVectorAnswerForEveryCandidate` | 11 条口令候选的 reason |
| 6 | `a_lone_surrogate_passphrase_is_rejected_before_pbkdf2_sees_it` | `TheWriterRefusesALoneSurrogatePassphraseBeforePbkdf2SeesIt` | 策略层先拒；写侧抛 `passphrase-too-weak` 而不是编码器异常 |
| 7 | `kdf_names_are_matched_with_ascii_folding_and_the_unicode_whitespace_set` | `KdfNamesAreMatchedWithAsciiFoldingAndTheUnicodeWhitespaceSet` | 9 条 kdf 名 |
| 8 | `header_parsing_and_kdf_acceptance_agree_with_the_windows_answers` | `HeaderParsingAndKdfAcceptanceAgreeWithEveryVector` | 10 条头部变体三列 |
| 9 | `envelope_decoding_reports_the_windows_reason_for_every_variant` | `EnvelopeDecodingReportsTheVectorReasonForEveryVariant` | 11 条信封变体 reason |
| 10 | `envelope_writing_rejects_the_same_documents_the_windows_writer_rejects` | `EnvelopeWritingRejectsTheDocumentsTheWindowsWriterRejects` | 4 条写侧拒绝 |
| 11 | `archive_reading_and_writing_agree_on_every_passphrase_case` | `ArchiveReadingAndWritingAgreeOnEveryPassphraseCase` | 5 条归档口令的读/写两侧 reason |

Kotlin 侧 11 例全部在新文件 `core/common/src/test/kotlin/com/mediahub/core/common/archive/ArchiveEdgeContractTest.kt`（238 行），并已钉进 Android 单元门禁（`--required-suite` 48 → **49**）；C# 侧 11 例在 `windows/tests/MediaHub.Tests/ArchiveEdgeContractTests.cs`（293 行）。

---

## 5 本轮修掉的真实缺陷（四条，全是探针实测）

1. **Windows 写端抛未捕获异常**：`ArchiveKdf.DeriveKey` 对含孤立代理项的口令抛 `System.Text.EncoderFallbackException: Unable to translate Unicode character \uD800 at index 28 to specified code page.` —— 它不是 `ArchiveException`，会穿透 `EncryptedArchiveWriter.Write` 的 `catch (ArchiveException)` 与上层 UI。修法：在口令策略层（`ArchivePassphrase.TryValidate`）就判 `passphrase-too-weak`，派生之前返回。
2. **Android 写端产出对端永远读不开的文件**：`PBEKeySpec` 把孤立代理项静默替换成 `?`（实测 UTF-8 末三字节 `6c653f`，密钥 `a2022a83…b06910`），两端「都成功」但对端永远解密失败。同一处修法一并覆盖。
3. **C# 头解析抛未捕获异常**：`JsonDocument.Parse` 接受 `"\ud800"`，失败推迟到 `GetString()`，抛 `InvalidOperationException`，而 `TryParse` 只 `catch (JsonException)`。修法：`TryReadString` 把 `GetString()` 包进 `catch (InvalidOperationException)` → `corrupt-header`；`TryParse` 另补 `catch (ArgumentException)`（对 `JsonDocument.Parse` 的其他实参异常仍是必要防御）。
4. **C# 信封解码抛未捕获异常**：`SourceSyncCodec.Deserialize` / `TryDeserialize` 对含转义孤立代理项的信封同样抛 `InvalidOperationException`（`TryDeserialize` 只 catch `SyncContractException`）⇒ 跨机输入会穿透成未捕获异常。修法：`Deserialize` 入口用 `WireText.ContainsLoneSurrogate(document.RootElement)` 拦成 `malformed-json`，并把 `ReadEnvelope` 包进 `catch (InvalidOperationException)`。

外加一条**静默改数据**的实证：`Utf8JsonWriter.WriteString` 遇孤立代理项不抛，写成 `\uFFFD`。这是「写端必须自己拒」的依据 —— 若不拒，用户会拿到一份「能读但内容是错的」信封。

---

## 6 落点行号表

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/WireText.cs` | 全文（168 行） | 25 码点表 + `IsWhitespace`/`IsBlank`/`Trim`/`EqualsAsciiIgnoreCase`/`HasLoneSurrogate`/`ContainsLoneSurrogate(JsonElement)` |
| `windows/src/MediaHub.Sync/ArchivePassphrase.cs` | :56-90 | `TryValidate` 判序同 Kotlin（含孤立代理项 → `PassphraseTooWeak`） |
| `windows/src/MediaHub.Sync/ArchiveKdf.cs` | :60-65 | `TryParse` 改用 `WireText.EqualsAsciiIgnoreCase(WireText.Trim(name), …)` |
| `windows/src/MediaHub.Sync/ArchiveHeader.cs` | :112 / :181-186 | `TryParse` 补 `catch (ArgumentException)`；`TryReadString` 把 `GetString()` 包进 `catch (InvalidOperationException)` |
| `windows/src/MediaHub.Sync/SourceSyncCodec.cs` | :71 / :418-446 / :469-480 | `Deserialize` 入口判孤立代理项 → `malformed-json`；`RequireText` 换 `WireText.IsBlank` 并加孤立代理项；新增 `RequireWellFormed` |
| `windows/tests/MediaHub.Tests/ArchiveEdgeContractTests.cs` | 全文（293 行，11 例） | C# 侧逐条消费 `edges` 段 |
| `windows/tests/MediaHub.Tests/MediaHub.Tests.csproj` | Content 第 3 条 | 把向量文件挂到输出目录 `assets/` |
| `windows/tests/assets/windows-archive-vectors.json` | 全文（75553 B） | 与 Android 侧同字节的副本 |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/WireText.kt` | 全文（88 行） | Kotlin 版同源表与助手 |
| `core/common/.../archive/ArchivePassphrase.kt` | :62-68 | `tryValidate` 判序改为 `WireText.*` + 孤立代理项 → `passphrase-too-weak` |
| `core/common/.../archive/ArchiveKdf.kt` | :34-36 | `tryParse` 不再用 `lowercase()`（JVM 会把 U+212A 折成 k） |
| `core/common/.../archive/ArchiveHeader.kt` | :78 | `tryParse` 在转成 `JsonObject` 后 `if (WireText.containsLoneSurrogate(json)) return null` |
| `core/common/.../sync/SourceSyncCodec.kt` | :105-106 / :112-129 / :145-150 / :263 | `validateForWrite` 对可选文本字段调 `requireWellFormed`；`requireText` 换 `WireText.isBlank`；`decode` 入口判文档级孤立代理项；`requiredString` 换 `WireText.isBlank` |
| `core/common/src/test/kotlin/.../archive/ArchiveEdgeContractTest.kt` | 全文（238 行，11 例） | Kotlin 侧逐条消费 `edges` 段 |
| `core/common/src/test/resources/archive/windows-archive-vectors.json` | `edges` 段（+1098 行） | 10 组边界向量，由 Windows 权威实现产出 |
| `.github/workflows/android-ci.yml` | :157-159 / :209 | 门禁注释 + `--required-suite com.mediahub.core.common.archive.ArchiveEdgeContractTest`（48 → 49） |

---

## 7 顺带补的缺口与刻意不动的两处

- **补的**：`WireText` 是两端新增的**共享语义层**，此前这类判定散落在各消费点里各写一遍；现在是「一份表 + 一个助手」，将来备份（`.mhbackup`）与配对（pairing）若要收编，改调用点即可。
- **刻意不动 1**：稳定码表仍 12 个。孤立代理项口令判 `passphrase-too-weak` 而不新增第 13 个码 —— 码表是对外契约（W6-47 结论 C：新增必须两端同名同序且交集为空），而「这个口令不能用」的语义与 too-weak 完全重合。
- **刻意不动 2**：备份与配对两侧的空白判定**没有**改用 `WireText`。它们目前没有跨端分叉的证据，按「先写口径表再决定要不要动」的纪律留到下一轮；这一轮的边界只到归档与信封。
- **刻意不动 3**：`ArchiveKdf` 的 `iterations` 校验、`salt`/`nonce` 长度校验一行未改 —— 它们两端本来就一致，动它们只会扩大回归面。

---

## 8 本机实跑（串行，防 OOM）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 生成器重跑 | `cd D:/mh-verify/archive-vectors && dotnet run -c Release`（生成器不入库） | `wrote …windows-archive-vectors.json (75552 bytes)`、`EXIT=0`；磁盘 **75553 B**（含行尾换行）；老 12 段 JSON 深度全等 |
| C# 全量 build + test | `D:/mh-verify/run-w6-67-windows.bat`（先 `dotnet build-server shutdown`，再 build `MediaHub.Windows.sln`、`dotnet test`、再 filtered 跑新例） | `BUILD_EXIT=0`（0 警告 0 错误）、`TEST_EXIT=0` → **1599 通过 / 0 失败 / 0 跳过**（1588 → 1599，+11）、`EDGE_EXIT=0` → 11/11（578 ms） |
| Android 三模块 | `D:/mh-verify/run-w6-67-gradle.bat`（低内存模板 + `--no-daemon`） | `BUILD SUCCESSFUL in 2m 41s`、`167 actionable tasks: 26 executed, 141 up-to-date`、`EXIT=0`；仓库根 `hs_err_pid*.log` 计数 0 |

XML 汇总（`<testsuite>` 属性相加，非估算）：

| 模块 | 套件 | 用例 | 失败 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- |
| `core/common` | **43** | **607** | 0 | 11 | 42 → 43 套件（+1 = `ArchiveEdgeContractTest`）、596 → 607（+11） |
| `core/database` | 20 | 192 | 0 | 12 | 不变 |
| `feature/settings` | 23 | 354 | 0 | 0 | 不变 |

三模块合计 **1153** 例；`--required-suite` 48 → **49**；`androidTest` 仍 88 例、instrumentation 仍 112 例（本轮无 Compose 断言）。

首跑 `dotnet build windows/MediaHub.sln` 撞 `MSBUILD : error MSB1009: 项目文件不存在` —— 真实文件名是 `MediaHub.Windows.sln`，属验证脚本自身的笔误，不涉及产品代码，改正后 `BUILD_EXIT=0`。

四条 CI 都是 exact-SHA 上的实跑（两个 head、两个工作流），全部 `completed / success`：

| head | 工作流 | run id | 关键计数 |
| --- | --- | --- | --- |
| `c287964d9c7877f114e92f26171f38207680d3d0`（主树） | `windows-ci` | 40372134565 | `MediaHub.Tests` 469 + 104 = 573；licence `projects=13 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | `android-ci` | 40372137888 | `uniqueTests` **2278**（core/common 607 / core/database 192 / feature/settings 354）；`--required-suite` **49** 条；API 32/36 各 `19 unique mandatory` |
| `2a25a919abde3cb5b122133fceba6d39fa298dd2`（本树） | `windows-ci` | 40372214715 | `MediaHub.Tests` **1599** + 104 = **1703**；licence `projects=15 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | `android-ci` | 40372214599 | `uniqueTests` **2278**（同主树口径，本树已 merge 主树提交）；`--required-suite` 49 条；instrumentation 112 例 |

C# 侧由 1588 增至 **1599**（+11）；Android 侧由 2267 增至 **2278**（+11）。两条 Android CI 的 `uniqueTests` 相同，说明本树 merge 后与主树口径一致。

---

## 9 未覆盖（本节交付时必须如实保留）

1. **真实两机搬运**：两端对同一份坏输入的码已在本机逐条对齐，但「一台真机写、另一台真机读」的端到端流程仍 NOT_RUN（同 §33.8–§39.6，都需要第二台机器）。
2. **非 BMP 码点**：空白表只覆盖 BMP（Unicode White_Space 本身只在 BMP）。补充平面的字符在代理层不可能是空白，按码元判已足够；但**非 BMP 的孤立代理项组合**只按码元判定，两端都不做码点级规范化（因此一致）。
3. **其他消费文本规则的模块**：本轮只改口令、kdf 名、信封文本字段、归档头四处；备份（`.mhbackup`）与配对（pairing）两侧仍是各自原状，若将来发现跨端分叉需单独排查。
4. **Unicode 版本差异**：显式表钉住的是**当前**码点集；将来 Unicode 版本若给 White_Space 增减码点，两端必须同时改表（黄金向量会立刻红，这是设计意图）。
5. **不涉及 GUI 与设备**：纯 JVM/C# 逻辑层，无新增控件、无设备依赖；真机 `DEVICE_UNVERIFIED` 与 GUI `GUI_UNVERIFIED` 的既有口径不变。

---

## 10 下一步

- 真实两机搬运：Windows 导出 → 拷到另一台机器 → Android 导入（同时关闭 §36.8 第 1 条、§37.8 第 1 条、§39.6 第 1 条）；
- 备份（`.mhbackup`）与配对（pairing）两侧的文本判定是否也该收进 `WireText`：先按 W6-47 ADR 的方法写一份口径表，再决定要不要动；
- 8 MiB 上限附近的真实数据（口令正确、载荷刚好超限）仍无边测；
- 归档格式若要演进（新 KDF、新字段），先写契约再两端同步加码 —— 不要单边加。
