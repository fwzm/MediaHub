# PR #39 正文归档（2026-10-30 折叠）

PR #39 正文有 262,144 B 硬上限。随着进度追加节数增长，最旧的几节被折叠成索引行以腾出空间。
**被折叠的原文不是删除，全文保存在本文件中**；折叠后正文对应位置只留一行指向本节。

- 归档时间：2026-10-30 10:5x（W6-113 轮）
- 折叠前正文：257,915 B（余 4,229 B）—— 已落盘证据 `D:/mh-goal/pr39-body-before-w6113.md`
- 折叠对象：W6-68 / W6-69 / W6-70 / W6-71 四节（2026-10-27 的配对与文本边界工作）
- 折叠规则：标题、结论、证据路径一律保留在索引行里；只有正文细节移入本文件

---
## 2026-10-27 03:55 进度追加（W6-68：8 MiB 上限附近的真数据穿越 GCM）

### 缺的是什么

归档层从 W6-62 起就有 8 MiB（8388608 B）载荷上限，但两端此前只有「超限就拒」的用例：

| 侧 | 既有用例 | 输入 | 断言 |
| --- | --- | --- | --- |
| Kotlin | `writing_refuses_a_payload_that_is_larger_than_the_archive_limit` | 9 000 000 字节的 displayName | 写端 `payload-too-large` |
| C# | `OversizedCiphertext_IsRejectedBeforeDecryption` | `MaxPayloadBytes + 1` 字节密文 | 读端 `payload-too-large` |
| C# | `OversizedPayload_IsRejectedWhenWriting` | 9 000 000 字节 displayName | 写端 `payload-too-large` |

三条合起来只证明「超限会被拒」。真正会撞上的是相反的：一份大型媒体库导出的信封，载荷**恰好落在上限附近**时，
加密 → 写盘 → 读回 → GCM 校验这条链路还成不成立。三重风险：

1. **差一错误**：写端判据 `payload.size > MAX`、读端 `cipherLength > MAX`，任一处写成 `>= ` 就会拒掉合法的 8388608 B，而既有用例全是 9 MB 级、永远碰不到边界。
2. **长度假设**：GCM 密文长度等于明文长度，读端正是**在解密前**按 `cipherLength` 判上限；这条等价关系将来被压缩/分块破坏，读端就会先花 600 000 轮 PBKDF2 再失败。
3. **跨语言一致性**：载荷逼近上限时，两端编码器的任何细微差异都会让同一份信封得到不同字节长度——恰好在这个尺度上决定一份归档能不能被对端打开。

### 做法：只存配方与哈希，不把 8 MB 归档入库（已折叠）

判据：上限附近的归档**不入库**——只钉「可计算的载荷配方 + 长度/sha256」（`payloadByteLength`/`payloadSha256`/`archiveByteLength`/`archiveSha256`/`headerBase64`/`prefixByteLength`），8 MiB 二进制进仓库会永久膨胀且无法 review 来源；真实写端的 salt/nonce 随机 ⇒ 只断言「写 → 读回的信封 encode 结果相等」，不把随机值可复现当断言；读端超限用 `prefix + 8388609 字节零密文 + 16 字节 tag` 单独构造，证明它在解密前就返回 `payload-too-large`，否则会得到 `wrong-passphrase-or-tampered`。完整决定表与理由见 docs `docs/android-source-sync-transfer.md` 的 W6-68 小节。
### 落点

| 树 | 文件 | 内容 |
| --- | --- | --- |
| 主树 | `core/common/src/test/kotlin/…/archive/ArchiveNearLimitTest.kt`（新，244 行） | Kotlin 侧 5 例；`craft()` 用向量固定 salt/nonce 手工 GCM |
| 主树 | `core/common/src/test/resources/archive/windows-archive-vectors.json` | 74164 → **77034 B**，新增根键 `nearLimit`（老 13 段逐字节不变） |
| 主树 | `.github/workflows/android-ci.yml` | `--required-suite` 49 → **50**（新钉 `ArchiveNearLimitTest`）+ 3 行注释 |
| 主树 | `docs/android-source-sync-transfer.md` | 新增 §41（376882 B） |
| 主树 | `README.md` | 里程碑段 + 1604/1135 + 算式加 5 + 50 套件 + 612 例 + 2283 唯一用例 |
| W6 | `windows/tests/MediaHub.Tests/ArchiveNearLimitTests.cs`（新，196 行） | C# 侧 5 例，消费同一份向量 |
| W6 | `windows/tests/assets/windows-archive-vectors.json` | 与主树同字节（77034 B，sha256 `8ffdbdc3…15394b`） |
| W6 | `docs/cross-device/W6-68-ARCHIVE-NEAR-LIMIT.md`（新，14255 B） | 九节：动机 / 方法 / 黄金规格 / 用例 / 两个坑 / 落点 / 实跑 / 未覆盖 / 下一步 |
| W6 | `README.md` | 与主树同内容（normalize 后逐字相等） |

本轮**生产代码零改动**：`ArchiveWriter`/`ArchiveReader`/`EncryptedArchiveWriter`/`EncryptedArchiveReader`/`ArchiveHeader` 一行未动，8 MiB 常量本身也没动。这是取证，不是改行为。

### 用例清单（两端各 5 例，同义对照）

| # | Kotlin `ArchiveNearLimitTest` | C# `ArchiveNearLimitTests` | 钉住什么 |
| --- | --- | --- | --- |
| 1 | the_recipe_reproduces_the_windows_payload_byte_for_byte | TheRecipeReproducesTheWindowsPayloadByteForByte | 同一配方 encode 出 8388608 B，sha256 `f6bc78f3…ba11ae` 相同 |
| 2 | the_header_from_the_recipe_is_the_canonical_aad_windows_used | TheHeaderFromTheRecipeIsTheCanonicalAadWindowsUsed | 头部就是 Windows 用作 AAD 的字节；prefix 173 B |
| 3 | a_payload_of_exactly_the_ceiling_encrypts_to_the_windows_archive_hash_and_reads_back | APayloadOfExactlyTheCeilingEncryptsToTheWindowsArchiveHashAndReadsBack | 固定 salt/nonce 加密出 8388797 B、sha256 `b47b3cc3…2edc287`；读回逐字段相等 |
| 4 | the_writer_accepts_exactly_the_ceiling_and_refuses_one_byte_more | TheWriterAcceptsExactlyTheCeilingAndRefusesOneByteMore | 恰好上限可写可读；MAX+1（8388609 B）写端 `payload-too-large` |
| 5 | the_reader_refuses_an_oversized_ciphertext_before_decryption | TheReaderRefusesAnOversizedCiphertextBeforeDecryption | 8388609 B 密文在解密前判 `payload-too-large`，不返回信封 |

### 本机实跑（串行，防 OOM）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 生成器重跑 | `D:/mh-verify/archive-vectors`（临时工程不入库） | 首跑失败（见下），修复后 `wrote …(77033 bytes)`、`EXIT=0`；磁盘 77034 B；老 13 段 JSON 深度全等 |
| C# 全量 | `dotnet build windows/MediaHub.Windows.sln -c Debug` + `dotnet test -p:Platform=x64` | `BUILD_EXIT=0`（0 警告 0 错误）、**1604 通过 / 0 失败**（1599 → 1604，+5）、filtered 5/5 |
| Android 三模块 | `D:/mh-verify/run-w6-68-gradle.bat`（低内存 + `--no-daemon`） | `BUILD SUCCESSFUL in 2m 1s`、167 tasks (17 executed)、三模块 612 + 192 + 354 = **1158** 例全绿 |

两个真实坑：

1. **`dotnet test` 读了 x86 输出目录**。首版脚本 build 完直接 `--no-build`，加载的是 `bin\Debug\net10.0-windows\win-x64\MediaHub.Tests.dll`（无 x64 段、陈旧副本），
   而新代码编进 `bin\x64\Debug\…`（csproj 的 `Platforms=x64`）⇒ 首跑 1599 例（旧数）且 filtered 报 “No test matches”。
   用 `strings` 能在两份 DLL 里搜到新类名，证明编译没问题、是跑错了目录。修法：显式 `-p:Platform=x64`。
2. **Kotlin 的 `JsonElement.getValue` 不存在**。首轮 gradle 报 5 处 `Unresolved reference 'getValue'`：
   链式取值第二层必须 `.jsonObject.getValue(...)`。
3. 生成器首跑还撞过 `near-limit written archive length 8388812 != 8388797`：
   `EncryptedArchiveWriter.Write` 的随机 salt base64 含 `+` 时 `System.Text.Json` 会转义成 `\u002B`（W6-65 已记录），头部长度在 161/166 间浮动 ⇒ **写入归档的总长度不可作为断言**；改成写后回读比 `Serialize` 结果。

### exact-SHA CI 证据（里程碑 head）

四条 CI 都是 exact-SHA 上的实跑（两个 head、两个工作流），全部 `completed / success`：

| head | 工作流 | run id | 关键计数 |
| --- | --- | --- | --- |
| `7d0f0556`（主树） | `windows-ci` | 40389203079 | `MediaHub.Tests` 469 + 104 = 573；licence `projects=13 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | Android CI | 40389203196 | `uniqueTests` **2283**；`--required-suite` **50** 条；两个 VI job 各 `19 unique mandatory` 命中 `7d0f0556…` |
| `b08186be`（W6） | `windows-ci` | 40389300435 | `MediaHub.Tests` 1604 + 104 = **1708**；licence `projects=15 rows=47 packages=28`、`RESULT: PASS` |
| 同上 | Android CI | 40389300211 | `uniqueTests` **2283**（与主树同口径）；`--required-suite` 50 条；两个 VI job 各 `19 unique mandatory` |

### exact-SHA CI 证据（最终 head）

证据提交（只改 README 与文档）之后再跑一轮，四条同样全绿：

| head | 工作流 | run id | 关键计数 |
| --- | --- | --- | --- |
| `0379dd84`（主树，最终 head） | `windows-ci` | 40393545747 | `Passed: 469` + `Passed: 104` = **573**；licence `projects=13 rows=47 packages=28`、`RESULT: PASS (no policy violations)`；`checks=21 failed=0 warnings=1 skipped=0` |
| 同上 | Android CI | 40393545819 | `uniqueTests` **2283**；`--required-suite` **50** 条；`VISUAL_RESULT: success`；两个 VI job 各 `API 32/36: 19 unique mandatory visual/parser tests passed at 0379dd84…` |
| `e8522fa0`（W6，最终 head） | `windows-ci` | 40393682616 | `Passed: 1604` + 104 = **1708**；licence `projects=15 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | Android CI | 40393682572 | `uniqueTests` **2283**（与主树同口径，说明 merge 生效）；`--required-suite` 50 条；两个 VI job 各 `API 32/36: 19 unique mandatory visual/parser tests passed at e8522fa0…` |
### 未做（如实留着）

- 真实两机搬运：8 MiB 载荷的端到端搬运（Windows 导出 → 另一台机器 → Android 导入）仍 **NOT_RUN**，需要第二台机器；
- 恰好上限只覆盖一种形状（单 source、纯 ASCII displayName）；多 source / 中文 / 带 secrets 的同长度载荷未单独测；
- PBKDF2 耗时约 1 s 未做性能断言，也没在低端设备上测；
- 传输层的同值 8 MiB 常量（`SourceSyncTransferReasons.PayloadTooLarge`）是另一层，未动；
- 无 GUI、无设备依赖；真机 `DEVICE_UNVERIFIED` 与 GUI `GUI_UNVERIFIED` 口径不变。

### 状态与证据

| 项 | 状态 | 证据 |
| --- | --- | --- |
| Kotlin 5 例 | EXECUTED_PASS | 三模块 612 + 192 + 354 = 1158 例 / 0 失败；`ArchiveNearLimitTest` XML tests=5 failures=0 |
| C# 5 例 | EXECUTED_PASS | `MediaHub.Tests` 1604 通过 / 0 失败（1599 → 1604） |
| 黄金向量 `nearLimit` 段 | EXECUTED_PASS | 77034 B；老 13 段 JSON 深度全等；三份副本 sha256 相同 `8ffdbdc3…15394b` |
| Android 单元门禁 49 → 50 | EXECUTED_PASS | `--required-suite` 实计 50 条，新条目在 `--require-complete-suites` 之前 |
| 真实两机搬运 | NOT_RUN | 需要第二台机器；与 §33–§41 同一口径 |
| 真机 / GUI | DEVICE_UNVERIFIED / GUI_UNVERIFIED | 本轮纯逻辑层，无设备与控件 |

---

## 2026-10-27 07:2x 进度追加（W6-69：配对侧文本边界统一到共享 WireText —— 两个真缺陷 + 一段新向量）

### 缺的是什么

W6-67 给归档层立了共享文本契约（两端各一份 `WireText`），把「空白 / 大小写 / 孤立代理项」从语言原语换成显式码点表。
但**配对层**没跟上：Kotlin 11 个 `core/common/.../pairing/` 生产文件 vs Windows 16 个 `windows/src/MediaHub.Sync/Pairing*.cs`，
仍在用 JVM 的 `Char.isWhitespace||isSpaceChar` / `String.trim()` / `equalsIgnoreCase` 对位 .NET 的 `char.IsWhiteSpace` / `string.Trim()` / `OrdinalIgnoreCase`。其中三对语义确实不同：

| 判定 | JVM | .NET | 实测后果 |
| --- | --- | --- | --- |
| 空白集合 | `isWhitespace||isSpaceChar` 含 U+001C–U+001F、**不含** U+0085 | `char.IsWhiteSpace` = Unicode White_Space（25 个 BMP 码点，**含** U+0085） | 字段/口令判空两端相反 |
| 大小写折叠 | `equalsIgnoreCase` 会把 U+017F、U+212A、U+0131 折成 ASCII | `OrdinalIgnoreCase` 只折 ASCII | 前缀比较、接口名排序两端不同 |
| trim | `String.trim()` 只剥 ≤U+0020 | `string.Trim()` 剥 25 码点 | 与第一行叠加后差异放大 |

配对是真跨端契约：A 端把 `mediahub://pair?code=…` 粘进来、B 端解析同一个串。两端口径不一致时，同一份输入得到不同 reason，而 reason 是对外契约（UI 文案、诊断、门禁断言都按它选）。判据只有一条：**同一份输入，两端必须给出同一个 reason**。

### 口径三条（两端共用，本轮定稿）

1. **空白 = 显式 25 码点表**（Unicode White_Space 全集）：U+0009–U+000D、U+0020、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。JVM 多出的 U+001C–U+001F、零宽空格 U+200B、BOM U+FEFF、软连字符 U+00AD **都不是空白**。
2. **大小写只折 ASCII**（A–Z ↔ a–z）。U+017F / U+212A / U+0131 原样保留——这是刻意与 JVM `equalsIgnoreCase` 背道而驰的一处。
3. **未配对代理项**在 JSON 文档层一律 `malformed-json`；在配对码/指纹这类「先判空再逐字符校验」的位置上本来就会落 `not-base64url` / `bad-fingerprint`，不额外加码。**不新增任何原因码**。

### W6-69 的两个真缺陷与用例清单（已折叠为结论）

- **孤立代理项**：.NET `JsonDocument.Parse` 接受 `"\ud800"` 这类转义孤立代理项，但随后 `GetString()` 抛
  `InvalidOperationException`（**不是** `JsonException`），`catch (JsonException)` 兜不住、异常穿出契约层；
  Kotlin 静默接受、再序列化时 `Utf8JsonWriter` 把它静默写成 U+FFFD。修法是两端 `PairingPayloadCodec` 在读字段
  **之前**先做文档级检查（`WireText.ContainsLoneSurrogate` / `containsLoneSurrogate`，覆盖未知字段），命中即
  `malformed-json`。
- **ephemeralUrl 的 trim 不对称**：Kotlin 先 `trim()` 再 `URI()`，C# 把未 trim 原串交给 `Uri.TryCreate` ——
  .NET 容忍 U+0020/U+0009 前后缀却不容忍 U+0085/U+00A0/U+3000/U+200B，同一 URL 在 Kotlin 是 ok、在 C# 是
  `bad-url`。修法是 C# 也先 `WireText.Trim`，host 判空改 `WireText.IsBlank`。
- **用例清单**：两端各 10 例同名同义对照（空白表、isBlank/trim、ASCII 大小写折叠、前缀与全序、孤立代理项、
  URL trim 对称性等），逐条钉在 `PairingEdgeContractTest` / `PairingEdgeContractTests` 上。
### 黄金资产：`textEdges` 段（10 组）（已折叠）

判据：`textEdges` 十段共 **120 条候选**（`whitespaceCodePoints` 25 / `blankCandidates` 14 / `caseFoldPairs` 8 / `asciiCaseTransforms` 9 / `prefixPairs` 8 / `comparePairs` 8 / `loneSurrogateCandidates` 7 / `linkCandidates` 16 / `payloadCandidates` 18 / `loneSurrogateDocuments` 7），两端逐条对照；关键口径是 U+001C/U+001F **不是空白**、blank 判定覆盖零宽/BOM/软连字符、大小写折叠按 ASCII 处理 long-s 与 dotless-i、孤立代理项成对判真，`fingerprint-uppercase` 读回后必须是小写 hex。逐条清单与 reason 映射见 docs `docs/android-source-sync-transfer.md` 的 W6-69 小节。
### 用例清单（两端各 10 例，同名同义对照）

| # | Kotlin `PairingEdgeContractTest` | C# `PairingEdgeContractTests` |
| --- | --- | --- |
| 1 | whitespace_table_matches_the_vectors_and_excludes_the_jvm_extras | TheWhitespaceTableMatchesTheVectorsAndExcludesTheRuntimeExtras |
| 2 | is_blank_and_trim_agree_with_every_candidate | IsBlankAndTrimAgreeWithEveryVectorCandidate |
| 3 | ascii_case_folding_never_folds_a_non_ascii_lookalike | AsciiCaseFoldingNeverFoldsANonAsciiLookalike |
| 4 | ascii_case_transforms_only_touch_ascii_letters | AsciiCaseTransformsOnlyTouchAsciiLetters |
| 5 | prefix_test_folds_ascii_only | PrefixTestFoldsAsciiOnly |
| 6 | comparison_is_a_total_order_that_folds_ascii_only | ComparisonIsATotalOrderThatFoldsAsciiOnly |
| 7 | lone_surrogate_detection_matches_every_candidate | LoneSurrogateDetectionMatchesEveryCandidate |
| 8 | link_parsing_reports_the_windows_reason_for_every_candidate | LinkParsingReportsTheVectorReasonForEveryCandidate |
| 9 | payload_decoding_reports_the_windows_reason_and_reads_the_same_value | PayloadDecodingReportsTheVectorReasonAndReadsTheSameValue |
| 10 | documents_with_a_lone_surrogate_are_refused_as_malformed_json | DocumentsWithALoneSurrogateAreRefusedAsMalformedJson |

两端都从向量读「期望 reason」，用例只负责把输入喂给生产代码、把 reason 与向量比。孤立代理项只能按 UTF-16 码元构造（Kotlin `String(charArrayOf(…))`、C# `new string(char[])`），不能用字符串字面量。

### 本机实跑（串行，防 OOM）

| 步骤 | 结果 |
| --- | --- |
| gradle 三模块 + `assembleDebugAndroidTest` | `BUILD SUCCESSFUL in 2m 29s`、234 tasks；core/common **45 套件 622 例 0 失败**（11 跳过）、core/database **20 套件 192 例**（12 跳过）、feature/settings **23 套件 354 例**；APK 20 785 281 B |
| C# 全量 | `MediaHub.Tests` **1614 通过 / 0 失败 / 0 跳过**（1604 → 1614，+10），必须带 `-p:Platform=x64` |
| 门禁脚本自测 | `python -m unittest discover -s .github/scripts/tests` → Ran 54 tests OK |
| 仓库根 hs_err/replay 日志 | 计数 0 |

一个真实坑：gradle 首跑**红**，`documents_with_a_lone_surrogate_are_refused_as_malformed_json` 在 `device-id-valid-pair` 上断言「所有条目都必须是 malformed-json」，但向量里那一条期望是 **ok**（合法代理对）——是**测试自己的缺陷**（C# 孪生用例本来就写成了条件分支）。修成 if/else 后重跑绿。

### exact-SHA CI 证据（最终 head）

四条 run 全部 completed/success，head 与本地提交逐一对应：

| head | 工作流 | run id | 关键计数 |
| --- | --- | --- | --- |
| `69fa6633`（主树，最终 head） | `windows-ci` | 40416913335 | `Passed: 469` + `Passed: 104` = **573**；licence `projects=13 rows=47 packages=28`、`RESULT: PASS (no policy violations)`；`checks=21 failed=0 warnings=1` |
| 同上 | Android CI | 40416913420 | `uniqueTests` **2293**（core/common **622** / core/database **192** / feature/settings **354**）；`--required-suite` **51** 条；两个 VI job 各 `API 32/36: 19 unique mandatory visual/parser tests passed at 69fa6633`；`BUILD SUCCESSFUL`、`lintDebug` 绿 |
| `c9266158`（W6，merge 后最终 head） | `windows-ci` | 40416988037 | `Passed: 1614` + 104 = **1718**；licence `projects=15 rows=47 packages=28`、`RESULT: PASS`；`checks=21 failed=0 warnings=1` |
| 同上 | Android CI | 40416988070 | `uniqueTests` **2293**（与主树同口径，说明 merge 生效）；`--required-suite` **51** 条；两个 VI job 各 `API 32/36: 19 unique mandatory visual/parser tests passed at c9266158`；instrumentation **112 例 / 0 失败**（32 与 36 两个 job 各 112，`0 skipped` `0 failed`） |

取证方式：`gh api repos/fwzm/MediaHub/actions/runs/<runId>/jobs --jq ".jobs[].databaseId"` 取 job id → `gh api repos/fwzm/MediaHub/actions/jobs/<id>/logs` 下载完整日志 → Node 过滤（`gh run view --log` 对大 run 会漏 job、`--job --log` 会截断）。required-suite 条数在日志里直接数 `--required-suite` 行，**不靠 YAML 目数**。

### 未做（如实留着）

- 真实两机跨机配对：仍是**两端 NOT_RUN**（需要第二台机器 + 用户同意）；本轮只做文本边界，未碰网络路径；
- 真机 instrumentation **DEVICE_UNVERIFIED**（只有 API 32/36 emulator 的 `EXECUTED_PASS(emulator)`）；
- 配对侧未覆盖与文本无关的边界（时间字段跨时区/闰秒、`expiresAtEpochMs` 溢出）；
- `PairingCode.Normalize` 的空白集合仍是 4 类（刻意不改，理由见落点节）；若将来配对码格式变成非 base64url，这条决定要重审；
- Windows 同步页 UIA 与 W10 U2/U3/U4 的 GUI 证据仍需用户授权窗口。

### 状态与证据

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 两端 `WireText` 各扩 4 助手 | EXECUTED_PASS | Kotlin 6172 B / C# 8278 B；两端 whitespace 表 25 码点逐点相等（用例 1 断言） |
| Kotlin 配对 8 文件改走 WireText | EXECUTED_PASS | `PairingEdgeContractTest` 10/10；core/common 45 套件 622 例 / 0 失败 |
| C# 配对 5 文件改走 WireText | EXECUTED_PASS | `PairingEdgeContractTests` 10/10；`MediaHub.Tests` 1614 通过 / 0 失败 |
| 两个真缺陷修复 | EXECUTED_PASS | 孤立代理项文档→`malformed-json`（两端同码）；`ephemeralUrl` 两端都先 trim（向量 18 条 payloadCandidates 逐条对照） |
| 黄金向量 `textEdges` 段 | EXECUTED_PASS | 11260 → 128430 B，sha256 `b9134675…0642fa`，**三份副本逐字节相同**；老 11 段逐字节不变 |
| Android 单元门禁 50 → 51 | EXECUTED_PASS | CI 日志 `--required-suite` 实计 **51** 行；`uniqueTests` **2293** |
| 真实两机配对 | NOT_RUN | 需要第二台机器；与 §33–§42 同一口径 |
| 真机 / GUI | DEVICE_UNVERIFIED / GUI_UNVERIFIED | 本轮纯逻辑层，无设备与控件依赖 |

### 最终 head 的 CI（docs-only 收尾提交）

README 回填两处 CI 实测计数后产生两个 docs-only 提交：主树 `87530d29`（PR #33 head）、W6 `c1bbdd67`（merge 主树后，PR #39 head）。四条 run 见下表，与上表同口径：

| head | 工作流 | run id | 结果 |
| --- | --- | --- | --- |
| `87530d29` | windows-ci | 40420612218 | success（`Passed: 469` + `104` = 573） |
| `87530d29` | Android CI | 40420612154 | success（`uniqueTests` 2293、`--required-suite` 51） |
| `c1bbdd67` | windows-ci | 40420641497 | success（`Passed: 1614` + `104` = 1718） |
| `c1bbdd67` | Android CI | 40420641414 | success（`uniqueTests` 2293、`--required-suite` 51） |

---

## 2026-10-27 11:5x 进度追加（W6-70：Android 配对设备登记与解绑 + 一处时区耦合缺陷）

### 缺的是什么

Android 侧**完全没有**「记住配对过的设备」这个概念：在 `core/`、`feature/` 生产代码里 grep `PairedDevice|pairedDevice|PairingStore` 零命中。也就是说，配对页每次都要重新粘链接、重新核对指纹，用户看不到「上次那台是不是同一台」，也没有「忘记这台设备」的出口。Windows 侧早已完整实现（`windows/src/MediaHub.Sync/PairedDevice.cs` / `PairedDeviceRegistry.cs` / `IPairedDeviceStore.cs` / `FilePairedDeviceStore.cs` / `InMemoryPairedDeviceStore.cs` / `PairedDeviceListView.cs` 六个文件），所以这是一个**有权威实现可对齐**的缺口，不是新设计。

### 做法

按既有跨端契约方法（Windows 权威实现 → 黄金向量 → Kotlin 逐字节对照）补 Android 侧：

1. **契约层（`core/common/src/main/kotlin/com/mediahub/core/common/pairing/`，7 个新文件）**：`PairedDevice.kt`（六字段 data class + `fingerprintDisplay` / `displayNameOrId` / `describe` / `formatEpoch`）、`PairedDeviceStore.kt`（接口 + `PairedDeviceStoreException` + 5 条存储码）、`InMemoryPairedDeviceStore.kt`、`PairedDeviceDocument.kt`（内部对象，复刻 `Utf8JsonWriter{Indented=true}` 的字节：CRLF、2 空格缩进、无尾随换行、8 个 ASCII 转义、其余控制字符与 >=0x7F 走大写 \uXXXX）、`FilePairedDeviceStore.kt`（tmp → 复制 `.bak` → `Files.move(REPLACE_EXISTING)` 原子写，坏文档隔离成 `paired-devices.corrupt-<yyyyMMddHHmmss>.json`）、`PairedDeviceRegistry.kt`（12 条原因码 + verify/register/forget/describe）、`PairedDeviceListPresenter.kt`（行文案与会话）。
2. **UI 层（`feature/settings/src/main/kotlin/com/mediahub/feature/settings/paired/`，2 个新文件）**：`PairedDeviceController.kt`（`PairedDeviceStorage` 出口 + 四态 `Loading/Ready/Unavailable` + 「忘记」两段式 `askForget → confirmForget/cancelForget` + 回执）、`PairedDeviceScreen.kt`（`PairedDeviceTags` 14 个 tag + 无状态 Screen + AlertDialog）。
3. **接线**：`SettingsScreen.kt` 加 `showPairedDevices` 页内托管 + `paired.entry` 按钮（排在 `pairing.entry` 之后）；页尾追加，不插主流程（W6-41 首屏纪律）。
4. **反向证据**：Kotlin 写出的 `paired-devices.json` 由 C# `FilePairedDeviceStore` 读回（`documentCases` 段），两端字节级同构。

### 落点

| 侧 | 文件 | 状态 |
| --- | --- | --- |
| Android 契约 | `core/common/src/main/kotlin/com/mediahub/core/common/pairing/{PairedDevice,PairedDeviceStore,InMemoryPairedDeviceStore,PairedDeviceDocument,FilePairedDeviceStore,PairedDeviceRegistry,PairedDeviceListPresenter}.kt` | 新增（7 个） |
| Android UI | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/paired/{PairedDeviceController,PairedDeviceScreen}.kt` | 新增（2 个） |
| 接线 | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/SettingsScreen.kt` | 改（4 处） |
| 向量 | `core/common/src/test/resources/pairing/paired-device-vectors.json` | 新增（36592 B / 17 根键） |
| Kotlin 用例 | `core/common/src/test/kotlin/.../pairing/PairedDeviceContractTest.kt`（14）、`feature/settings/src/test/kotlin/.../paired/PairedDeviceControllerTest.kt`（13）、`feature/settings/src/androidTest/kotlin/.../paired/PairedDeviceScreenTest.kt`（9） | 新增 |
| Windows 修 | `windows/src/MediaHub.Sync/FilePairedDeviceStore.cs` | 改（1 处真缺陷） |
| Windows 用例 | `windows/tests/MediaHub.Tests/PairedDeviceContractTests.cs`（14）+ `windows/tests/assets/paired-device-vectors.json` | 新增 |
| 文档 | 主树 `docs/android-pairing.md` §15；W6 树 `docs/cross-device/W6-70-PAIRED-DEVICES.md` | 新增 |

### 修掉的一个 Windows 真缺陷

`FilePairedDeviceStore.LoadLocked()` 里 `JsonElement.TryGetInt32` 在 `ValueKind` **不是 Number** 时会抛 `InvalidOperationException`（Try- 前缀只表示「值不合范围」，不表示「类型不对」），而 `LoadLocked` 的契约是**读永不抛**（坏文档隔离 + 降级空清单）⇒ 手写一个 `{"version":"1","devices":[]}` 的文档会让 Windows 同步页直接抛异常到 UI 层。修法：先判 `ValueKind != JsonValueKind.Number` 再 `Quarantine(MalformedJson)`，文件 14244 B，由 `loadCases` 的 `version-not-number` 条钉住（两端同码 `paired-malformed-json`）。

### 用例清单（两端同名同义）

| 套件 | 例数 | 覆盖 |
| --- | --- | --- |
| `PairedDeviceContractTest.kt` / `PairedDeviceContractTests.cs` | 各 14 | 12 原因码 + 5 存储码逐字、`describeState` 三文案、`create` 归一化（trim / 截断 128 / 指纹小写 / 负来源归 0 / pairedAt 缺省）、verify 三态、register 六分支（新增/更新/不变/满容量/存储不可读/指纹不一致拒绝且**不覆盖已钉指纹**）、forget 三分支、`documentCases` 字节级（length/sha256/base64）、`loadCases` 六个坏文档、`writeRejects` |
| `PairedDeviceControllerTest.kt` | 13 | 无出口→`paired-device-storage-unavailable`；两设备 Ready 且规范序 device-a 在前；`loadError` 透传 + 警告含「隔离」；存储抛 IOException→Unavailable + 原文；`askForget` 对 null/空白/未知 id 返回 false；prompt 文案逐字且**不写盘**；`cancelForget` 是回执；`confirmForget` 四分支（无 pending / 成功 / store false→`paired-device-unknown` / 抛异常不穿透）；dismiss/clear；生产路径 TemporaryFolder 真写盘 + 重开读回 |
| `PairedDeviceScreenTest.kt` | 9 | 空清单/一行/满容量/loadError 警告/Unavailable/忘记对话框两按钮/forgetting 时按钮禁用/回执 + DISMISS/返回按钮 |

### 一条真实缺陷：时区耦合（本地绿、CI 红）

W6-70 首轮提交（主树 `74dce9a9` / W6 `09641c8d`）在**本地全绿**，四条 CI 里三条红：windows-ci `40438832196` 红 2 例（`PairedDeviceContractTests` CS:322 / CS:391），Android CI `40438831881` 红 2 例（`PairedDeviceContractTest` KT:296 / KT:356）。

根因不是实现 bug：`PairedDevice.formatEpoch`（两端）都用**本地时区**渲染 `yyyy-MM-dd HH:mm`（产品正确语义——用户看自己的钟），而黄金向量把生成机 Asia/Shanghai 渲染出的 `2026-10-24 08:00` 写死了；CI runner 是 UTC，同一个 epoch 1792800000000 渲染成 `2026-10-24 00:00` ⇒ 断言必红。本地绿只是因为本地时区恰好等于生成时区，典型的**时区耦合**。

修法：**不改产品语义**，改向量与用例——向量里所有本地时间串换成 `{pairedAt}` / `{lastSeen}` 占位符（13 处），由两端用例在**自己进程的时区**里用同一个 `FormatEpoch` 展开后比较。这样契约钉的是「标签 + 平台自己的本地渲染」，而不是某台机器的时间串。

复验方式（关键：证明真的与时区无关）：`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` + `--rerun-tasks`（否则 gradle 报 `17 up-to-date` 假绿）→ 同一份 XML `tests=14 skipped=0 failures=0 errors=0`。

### 本机实跑

- `MediaHub.Tests`（W6 树）：**1628 通过 / 0 失败 / 0 跳过**（1614 → 1628，必须带 `-p:Platform=x64`）。
- Android 三模块：**core/common 46 套件 636 例 / core/database 20 套件 192 例 / feature/settings 24 套件 367 例**，failures=0 errors=0（W6-69 基线 622/192/354）。
- 门禁脚本自测：`python -m unittest discover -s .github/scripts/tests` → **Ran 54 tests / OK**。
- `--required-suite` **51 → 53**（新增 `PairedDeviceContractTest` 与 `PairedDeviceControllerTest`）。

### 未做（如实留着）

- 真实两机跨机配对仍是**两端 NOT_RUN**（需要第二台机器 + 用户同意）；本轮只做本机登记簿，不碰网络握手。
- 真机 instrumentation **DEVICE_UNVERIFIED**（只有 API 32/36 emulator 的 `EXECUTED_PASS(emulator)`）。
- **不做「登记新设备」**：那需要真配对握手（把 `verify/register` 接进 `PairingLinkController` 的核对流程），属后续里程碑；本轮只做「只读清单 + 忘记一台」。
- Android 存储目录是 `filesDir/paired_devices/paired-devices.json`（文件名逐字同 Windows），Windows 是 `%LOCALAPPDATA%/MediaHub/paired-devices.json`——**目录位置不同是平台惯例，文件名与文档格式逐字节相同**。

### 本阶段最终 head 的 CI（W6-70 + 时区修复）

| head | 工作流 | run id | 结果 |
| --- | --- | --- | --- |
| `98062a05` | windows-ci | 40443558630 | success（`Passed: 469` + `104` = 573；licence `projects=13 rows=47 packages=28` PASS；`checks=21 failed=0 warnings=1`） |
| `98062a05` | Android CI | 40443558576 | success（`uniqueTests` 2320；`--required-suite` 53；VI 每 job 19 条 mandatory + 139 例 instrumentation，0 失败） |
| `805cc2ed` | windows-ci | 40443586139 | success（`Passed: 1628` + `104` = 1732；licence `projects=15` PASS；`checks=21 failed=0 warnings=1`） |
| `805cc2ed` | Android CI | 40443586001 | success（`uniqueTests` 2320；`--required-suite` 53；VI 每 job 139 例 0 失败） |

---

## 2026-10-27 14:2x 进度追加（W6-71：配对页第四段 —— 对端身份卡片 + 显式登记）

### 缺的是什么

Android 配对页此前只有两段：W6-59b 的「拉一次邀请」和 W6-70 的「只读登记簿 + 忘记一台」。两段之间缺的正是**配对结果本身**——拉完邀请后没有任何一步去问「对端是谁、要不要把它记下来」，于是 W6-70 的已配对页在真机上永远是空的。Windows 侧从 ADR-S005 起就把这一步放在配对握手里：

- 握手时双方各交换一张**身份卡片**（`PairingPeerCard`），卡片里是**稳定指纹**；
- guest 侧拿到 host 卡片后 `PairedDeviceRegistry.Register(...)`，指纹不符就中止配对；
- 邀请里的 `publicKeyFingerprint` 是**一次性证书指纹**，拿它登记必然在下次配对时误判 mismatch（Windows 源码注释原文：`the invitation's fingerprint is the ephemeral certificate's and changes every pairing, so registering it would guarantee a false mismatch next time`）。

### 做法

照既有跨端契约方法：Windows 权威实现 → 黄金向量 → Kotlin 逐字节对照。

1. **卡片线格式**（`core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingPeerCard.kt`）：5 字段 `schemaVersion/deviceId/deviceFingerprint/displayName/sourceCount`，紧凑 JSON、固定键序、`MAXIMUM_LENGTH = 2048`；`serialize` 复用 W6-70 的 `PairedDeviceDocument.quote`，与 .NET `Utf8JsonWriter` 的转义规则逐字节相同。
2. **6 条 `card-*` 稳定码**（独立一集，不并入载荷 7 码、链接 3 码、传输 7 码、客户端 1 码）：`card-malformed-json` / `card-too-long` / `card-unsupported-version` / `card-missing-device-id` / `card-missing-fingerprint` / `card-bad-fingerprint`。
3. **交换时序**（`PairingTransportClient.fetchInvitationWithCard`）：先读邀请、逐项校验（载荷 → 时效 → challenge → 指纹）通过之后**才自报身份**，再尽力读对端卡片。卡片写失败 / 读超时 / 老构建没有卡片，都只让 `peerCard = null`，**绝不把已校验通过的邀请变成失败**。既有 `exchange` / `fetchInvitation` / `validate` 逐字未动（避免波及 8 处既有引用点）。
4. **本机身份**（`PairingSelfIdentity.kt`）：`deriveFingerprint(deviceId) = SHA-256(UTF8("mediahub-device:" + trim(deviceId)))`，与 Windows `PairingSelfIdentity.DeriveFingerprint` 同值。
5. **三条业务边界**（与 Windows 逐条对齐）：①登记的必须是卡片里的稳定指纹；②指纹不一致时按钮恒不可点、`registerPeer()` 直接拒绝，**绝不覆盖已钉指纹**；③不做自动登记，必须用户显式点「登记这台设备」。

### 落点

| 侧 | 文件 | 状态 |
| --- | --- | --- |
| Android 契约 | `core/common/src/main/kotlin/com/mediahub/core/common/pairing/{PairingPeerCard,PairingSelfIdentity}.kt` | 新增（2 个） |
| Android 传输 | `core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportClient.kt` | 改（+155 行，新增 `fetchInvitationWithCard` + `exchangeCards`，既有方法逐字未动） |
| Android UI | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/pairing/{PairingFetchPort,PairingLinkController,PairingLinkScreen}.kt` | 改（+43 / +226 / +98） |
| 向量 | `core/common/src/test/resources/pairing/windows-pairing-vectors.json` | 改（128430 → 152051 B，尾部新增 `peerCard` 段，老 12 段逐字节不变） |
| Kotlin 用例 | `PairingPeerCardContractTest.kt`（7）、`PairingLinkPeerTest.kt`（14）、androidTest `PairingLinkPeerScreenTest.kt`（7） | 新增 |
| Windows 修 | `windows/src/MediaHub.Sync/PairingPeerCard.cs` | 改（2 处真缺陷） |
| Windows 用例 | `windows/tests/MediaHub.Tests/PairingPeerCardContractTests.cs`（7） | 新增 |
| 文档 | 主树 `docs/android-pairing.md` §16；W6 树 `docs/cross-device/W6-71-PAIRING-CARD.md` | 新增 |

### 修掉的两个 Windows 真缺陷

`PairingPeerCardCodec.TryDeserialize` 的契约是「读永不抛、只给 6 个稳定码」，但原实现有两处会抛非契约异常：

1. **转义孤立代理项**：`displayName` 里含 `\uD800` 时 .NET `GetString()` 抛 `InvalidOperationException`（不是 `JsonException`）⇒ 加 `WireText.ContainsLoneSurrogate(root)` 前置判断，统一折叠成 `card-malformed-json`（与 W6-67/W6-69 的文本边界口径一致）。
2. **`schemaVersion` 类型不对**：`JsonElement.TryGetInt32` 在 `ValueKind` 不是 Number 时**抛异常而不是返回 false**（Try- 前缀只表示「值不合范围」）⇒ 一张 `{"schemaVersion":"1"}` 的卡片会把 Windows 读端打崩。加 `ValueKind != JsonValueKind.Number` 前置判断后走 `card-malformed-json`。

### 用例清单（两端同名同义）

| 套件 | 例数 | 覆盖 |
| --- | --- | --- |
| `PairingPeerCardContractTest.kt` / `PairingPeerCardContractTests.cs` | 各 7 | 6 码逐字 + 两个上限常量；6 条序列化逐字节（json/utf8 字节数/sha256）；27 条解码（接受 9 / 拒绝 18，6 码全覆盖）；5 条自身身份派生（含 trim 与 Unicode）；空身份拒绝；`ToObservation` 带调用方时钟；7 条恶意文本不抛 |
| `PairingLinkPeerTest.kt` | 14 | 卡片只读比对**不写盘**；显式登记写的是卡片稳定指纹而非邀请临时指纹；同指纹 MATCH → `paired-device-updated`；异指纹 MISMATCH → 拒绝且已钉指纹不变；无卡片 → `NoCard`；无登记簿 → `Unavailable` 且**不自报身份**；老接口替身 → `NoCard`；拉取失败/换链接作废上一次结论；challenge 与原始邀请链接不外泄 |
| `PairingLinkPeerScreenTest.kt`（androidTest） | 7 | `pairing.peer` / `.state` / `.hint` / `.register` 四 tag；NotChecked 无结果行且按钮禁用；Reviewed(NEW) 显示设备名 +「新设备」且可点；MISMATCH 显示「指纹不一致」+ 不覆盖提示且禁用；Recorded 接受/拒绝两回执；NoCard / Unavailable 文案；Fetching 时即使 Reviewed 也禁用 |

### 本机实跑

- `MediaHub.Tests`（W6 树）：**1635 通过 / 0 失败 / 0 跳过**（1628 → 1635，必须带 `-p:Platform=x64`）。
- Android 三模块：**core/common 47 套件 643 例 / core/database 20 套件 192 例 / feature/settings 25 套件 381 例**，failures=0 errors=0（W6-70 基线 636/192/367）。
- `assembleDebugAndroidTest` BUILD SUCCESSFUL（+7 例 Compose 断言）；门禁脚本自测 `Ran 54 tests / OK`。
- `--required-suite` **53 → 54**（新增 `PairingLinkPeerTest`）。

### 未做（如实留着）

- 真实两机跨机配对仍是**两端 NOT_RUN**（需要第二台机器 + 用户同意开防火墙）；本轮只在回环上做卡片交换与登记，非回环路径未验证。
- 真机 instrumentation **DEVICE_UNVERIFIED**（只有 API 32/36 emulator 的 `EXECUTED_PASS(emulator)`）。
- 配对页仍**没有「忘记」入口**（W6-70 的忘记在设置页的已配对设备页）；本轮只做核对 + 登记。
- 卡片交换的超时沿用邀请读取的读超时；Windows 侧是独立的 5 s `PeerReplyTimeout`，**数值不同但语义一致**（都没把卡片失败变成配对失败）——这一差异已记录在文档 §16.5。

**最终 head 的 CI**：主树 head `6ccf5a92`（PR #33）、stacked W6 分支 head `de6427e3`（本 PR）。四条 run 全部 success；其中 W6 侧 windows-ci 记 `Passed: 1635` + `104` = **1739**，W6 侧 Android CI 记 `uniqueTests` 2341、`--required-suite` 54、VI 每 job **19 条** mandatory visual/parser + **148 例** instrumentation（module 7+3+129+8+1，1 例 SKIPPED）。
