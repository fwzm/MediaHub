# W6-69 配对侧文本边界统一到共享 WireText

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本轮边界：**只改文本判定与新增测试/向量**。配对协议字段、帧格式（4 字节大端长度 + JSON）、原因码集合（配对 7 条 + 码 9 条 + 链接 3 条 + 传输 7 条 + 地址 9 条）、TLS 1.3 + SPKI 钉扎、120 s 邀请有效期一行不改；也不新增任何原因码。

---

## 1 动机：W6-67 只统一了归档侧，配对侧仍在用两端语义不同的原生判定

W6-67 为归档层立了共享文本契约（两端各一份 `WireText`），把「空白 / 大小写 / 孤立代理项」三件事从语言原语换成显式码点表。但**配对层**（Kotlin `core/common/.../pairing/` 11 个生产文件 vs Windows `windows/src/MediaHub.Sync/Pairing*.cs` 16 个文件）当时没有跟进：它继续用
JVM 的 `Char.isWhitespace || isSpaceChar` / `String.trim()` / `equalsIgnoreCase` 对位 .NET 的 `char.IsWhiteSpace` / `string.Trim()` / `StringComparison.OrdinalIgnoreCase`，而这四对里有两对**语义确实不同**：

| 判定 | JVM | .NET | 实测后果 |
| --- | --- | --- | --- |
| 空白集合 | `Char.isWhitespace` 含 U+001C–U+001F、**不含** U+0085 | `char.IsWhiteSpace` = Unicode White_Space（25 个 BMP 码点，**含** U+0085） | 口令/字段两端判空结果相反 |
| 大小写折叠 | `equalsIgnoreCase` 会把 U+017F(U+017F→s)、U+212A(开尔文→k)、U+0131(ı→i) 折成 ASCII | `OrdinalIgnoreCase` 只折 ASCII | 前缀/接口名比较两端结果不同 |

配对是**真跨端契约**：A 端把 `mediahub://pair?code=…` 粘贴进来，B 端要解析同一个串；两端口径不一致时，同一份输入会得到不同的 reason——而 reason 是对外契约（UI 文案、诊断、门禁断言都按它选）。所以判据只有一条：**同一份输入，两端必须给出同一个 reason**。

## 2 口径三条（本轮定稿，两端共用）

1. **空白 = 显式 25 码点表**，即 Unicode White_Space 全集：U+0009–U+000D、U+0020、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。JVM 多出来的 U+001C–U+001F、以及零宽空格 U+200B、BOM U+FEFF、软连字符 U+00AD **都不是空白**。
2. **大小写只折 ASCII**（A–Z ↔ a–z）。U+017F / U+212A / U+0131 这类「看起来像 ASCII 字母」的码点一律原样保留，绝不折叠——这是本轮刻意与 JVM 的 `equalsIgnoreCase` 背道而驰的一处。
3. **未配对代理项**（lone surrogate）在 JSON 文档层一律 `malformed-json`；在配对码/指纹这类「先判空再逐字符校验」的位置上，它们本来就会落 `not-base64url` / `bad-fingerprint`，不额外加码。

理由：这三条都是「可穷举、可生成黄金向量、可两端逐条对照」的形状。凡是能用码点表钉死的，就不该留给语言运行时版本去决定。

## 3 两个真实缺陷（不是风格问题）

### 3.1 C# 遇转义孤立代理项抛非契约异常，Kotlin 静默接受

.NET 的 `JsonDocument.Parse` 接受 `"\ud800"` 这种转义孤立代理项（JSON 规范允许转义任意 UTF-16 码元），但随后 `GetString()` 抛 `InvalidOperationException`——**不是** `JsonException`，所以 `Deserialize` 里那条 `catch (JsonException)` 兜不住它，异常会直接穿出契约层。而 Kotlin 的 kotlinx 会静默接受，字段里就留下一个孤立代理项；更糟的是再序列化时 `Utf8JsonWriter` 会把它**静默写成 U+FFFD**，等于悄悄改数据。

生成器实测：7 条 lone-surrogate 文档里有 5 条在 C# 侧报 `unhandled:InvalidOperationException`、Kotlin 侧返回 ok。

修法：两端 `PairingPayloadCodec` 在读任何字段**之前**先做文档级检查——C# `WireText.ContainsLoneSurrogate(root)`、Kotlin `WireText.containsLoneSurrogate(root)`，命中即 `malformed-json`。检查覆盖未知字段（未知字段也会被重新序列化，同样会被 U+FFFD 污染）。

### 3.2 `ephemeralUrl` 的 trim 不对称

Kotlin 的 `validateEphemeralUrl` 先 `trim()` 再交给 `URI()`；C# 直接把**未 trim 的原串**给 `Uri.TryCreate`。实测 .NET `Uri` 自己会容忍 U+0020 / U+0009 前后缀（这两类恰好两端都过），但**不容忍** U+0085 / U+00A0 / U+3000 / U+200B——所以同一个 URL 在 Kotlin 是 ok、在 C# 是 `bad-url`。

修法：C# 也先 `WireText.Trim`，再 `Uri.TryCreate(trimmed, …)`，host 判空改 `WireText.IsBlank`。

### 3.3 向量自身的一个缺陷（`expired` 条永远不触发）

第一版 linkCandidates 里 expired 条用 `Now + LifetimeSeconds` 造过期时间，但两端用例都拿**冻结的 Now** 去解析，结果是 ok 而不是 expired——C# 首跑 9/10 就是这个。修法不是把期望改成 ok，而是**让每条 link 向量自带 `nowEpochMs`**（解析时刻本身是输入的一部分）。这暴露了一个更一般的教训：向量只存「输入」不够，凡是「相对时间」都要连「参照时刻」一起存。

## 4 落点（Kotlin 9 文件 + C# 5 文件）

### 4.1 `WireText` 两端各加四个助手

两端原有 8 个 API（`WHITESPACE_CODE_POINTS` / `isWhitespace` / `isBlank` / `trim` / `equalsAsciiIgnoreCase` / `hasLoneSurrogate` / `containsLoneSurrogate` / private foldAscii）之外新增 4 个：

| API | 语义 |
| --- | --- |
| `startsWithAsciiIgnoreCase(value, prefix)` | prefix 比 value 长 → false；否则逐字 ASCII 折叠比较 |
| `lowercaseAscii(value)` | 只把 A–Z 转小写；先扫一遍，无 A–Z 时返回原实例 |
| `uppercaseAscii(value)` | 只把 a–z 转大写；同上 |
| `compareAsciiIgnoreCase(left, right)` | 逐字折叠后比，长度差兜底；是**全序**（反对称，可作排序末级） |

### 4.2 Kotlin 侧改点（9 文件）

| 文件 | 改动 |
| --- | --- |
| `sync/WireText.kt` | 新增 4 助手 + `foldAsciiUpper` |
| `pairing/PairingLink.kt` | `extractCode` / `parse` 的 trim + 前缀判定 + 参数前缀 |
| `pairing/PairingPayload.kt` | `deviceId` 判空、`ephemeralUrl` 判空/trim/scheme/host、文档判空、`readRequiredString`、文档级孤立代理项 |
| `pairing/PairingFingerprint.kt` | `tryParse` 判空 + 小写化；`displayString` 的大写化 |
| `pairing/PairingChallenge.kt` | `tryParse` 判空 |
| `pairing/PairingTls.kt` | `pinned(pin)` 判空 |
| `pairing/PairingTransportSupport.kt` | `LoopbackOnly.addressOrNull` 的 trim + `localhost` 比较 |
| `pairing/PairingAddressCandidates.kt` | 三处地址 trim + 排序末级 |
| `pairing/PairingAddressEnumerator.kt` | 接口名 trim + 小写化（Android 独有） |

### 4.3 C# 侧改点（5 文件）

| 文件 | 改动 |
| --- | --- |
| `WireText.cs` | 新增 4 助手 + `FoldAsciiUpper` |
| `PairingLink.cs` | `ExtractCode` / `Parse` 全链 |
| `PairingPayload.cs` | `ValidateShape` / `ValidateEphemeralUrl`（**先 trim**）/ `Deserialize` / `ReadRequiredString` / 文档级孤立代理项 |
| `PairingChallenge.cs` | `TryParse` 判空 |
| `PairingFingerprint.cs` | `TryParse` 判空 |
| `PairingAddressCandidates.cs` | 接口名/地址 trim + 排序末级 |

### 4.4 刻意不改的清单

- **Windows 内部校验**（无 Kotlin 对位）：`PairingPresenter`、`PairingSelfIdentity`、`PairingSession`、`PairingPeerCard`、`PairingTransportServer`、`PairingTransportResult`、`PairingCode` 的 reason 参数检查、`PairingCertificate`。改它们只会扩大面，不会让任何跨端断言变强。
- **`PairingCode.Normalize` 仍只剥 空格/Tab/CR/LF 四类**。配对码是 base64url：即使先剥掉别的空白，后续逐字符 `isBase64UrlCharacter` 一样会落 `not-base64url`，最终 reason 不变。为「normalize 集合」本身加一条更宽的表，只会让「什么算配对码」变模糊。
- **不新增原因码**。配对侧 7 + 9 + 3 + 7 + 9 的码表是对外契约，孤立代理项复用 `malformed-json`。

## 5 黄金资产：`textEdges` 段（10 组）

文件 `core/common/src/test/resources/pairing/windows-pairing-vectors.json`：11 段 → **12 段**，11260 B → **128430 B**。老 11 段**逐字节不变**（脚本比对 old keys 全等，新段从偏移 11261 起追加）。C# 侧读同一份字节的副本 `windows/tests/assets/windows-pairing-vectors.json`（sha256 相同，csproj 加一条 `Content` 挂到输出目录）。

sha256 = `b9134675df84cf5380b2a1e0e21c742b9be8b4ba680a19435b0d734f6d0642fa`（三方：生成器产物 / Kotlin 资源 / W6 测试资源逐字节相同）。

| 子段 | 条数 | 内容 |
| --- | --- | --- |
| `whitespaceCodePoints` | 25 | 显式码点表，两端断言逐点相等 |
| `blankCandidates` | 14 | 空串/ASCII 空格/混合空白/下一行/不换行空格/表意空格 → blank；U+001C/U+001F/零宽/BOM/字母/软连字符 → 不 blank |
| `caseFoldPairs` | 8 | ASCII 混合/同/空 → 相等；不同/long-s/开尔文/dotless-i/长度差 → 不等 |
| `asciiCaseTransforms` | 9 | `upperAscii` / `lowerAscii` 对 ASCII 与非 ASCII 看似的处理 |
| `prefixPairs` | 8 | 精确/全大写/混合/空前缀/CODE= → true；非前缀/过短/long-s → false |
| `comparePairs` | 8 | 全序：same/ascii-case=0、顺序、长度兜底、B vs a、long-s |
| `loneSurrogateCandidates` | 7 | 纯/合法对 → false；尾高/首低/内部高/双双高/低后高 → true |
| `linkCandidates` | 16 | 每条 `{name, link:[码元], nowEpochMs, reason}` |
| `payloadCandidates` | 18 | 每条 `{name, field, utf16:[码元], json, reason, afterRead}` |
| `loneSurrogateDocuments` | 7 | 5 条 `malformed-json`、2 条合法代理对仍 ok |

linkCandidates 的 16 条 reason 分布：plain / padded-ascii / padded-next-line / padded-no-break / padded-ideographic / uppercase-prefix / code-parameter / code-parameter-uppercase = **ok**；padded-record-separator / padded-zero-width / record-separator-only / wrong-prefix = **not-a-pairing-link**；whitespace-only / whitespace-only-mixed = **pairing-link-empty**；padding-bearing-code = **not-base64url**；expired = **expired**。

payloadCandidates 的关键几条：device-id-record-separator / next-line-letters / letters-record-separator = **ok**（U+001C 不是空白）；ephemeral-url-ascii-padded / next-line-padded / ideographic-padded / zero-width-padded = **ok**（两端都先 trim 之后）；ephemeral-url-record-separator-padded = **bad-url**；fingerprint-uppercase = ok 且 `afterRead` 为小写 hex。

## 6 用例：两端各 10 例，同名同义一一对照

| Kotlin（`PairingEdgeContractTest.kt`） | C#（`PairingEdgeContractTests.cs`） |
| --- | --- |
| `whitespace_table_matches_the_vectors_and_excludes_the_jvm_extras` | `TheWhitespaceTableMatchesTheVectorsAndExcludesTheRuntimeExtras` |
| `is_blank_and_trim_agree_with_every_candidate` | `IsBlankAndTrimAgreeWithEveryVectorCandidate` |
| `ascii_case_folding_never_folds_a_non_ascii_lookalike` | `AsciiCaseFoldingNeverFoldsANonAsciiLookalike` |
| `ascii_case_transforms_only_touch_ascii_letters` | `AsciiCaseTransformsOnlyTouchAsciiLetters` |
| `prefix_test_folds_ascii_only` | `PrefixTestFoldsAsciiOnly` |
| `comparison_is_a_total_order_that_folds_ascii_only` | `ComparisonIsATotalOrderThatFoldsAsciiOnly` |
| `lone_surrogate_detection_matches_every_candidate` | `LoneSurrogateDetectionMatchesEveryCandidate` |
| `link_parsing_reports_the_windows_reason_for_every_candidate` | `LinkParsingReportsTheVectorReasonForEveryCandidate` |
| `payload_decoding_reports_the_windows_reason_and_reads_the_same_value` | `PayloadDecodingReportsTheVectorReasonAndReadsTheSameValue` |
| `documents_with_a_lone_surrogate_are_refused_as_malformed_json` | `DocumentsWithALoneSurrogateAreRefusedAsMalformedJson` |

两端都从向量读「期望 reason」，用例只负责把输入喂给生产代码、把 reason 与向量比。孤立代理项只能按 UTF-16 码元构造（Kotlin 用 `String(charArrayOf(…))`、C# 用 `new string(char[])`），不能用字符串字面量——字面量里写 `"\ud800"` 会被编译器当场拒或折成 U+FFFD。

## 7 实测记录

- gradle 首跑**红**：core/common 622 例中 1 失败，`documents_with_a_lone_surrogate_are_refused_as_malformed_json` 在 `device-id-valid-pair` 上断言「所有条目都必须是 malformed-json」，但向量里这一条期望是 **ok**（合法代理对）。这是**测试自己的缺陷**（C# 孪生用例本来就写成了条件分支，所以一直是对的）。修成 if/else 分支后重跑绿。
- 修后 gradle `BUILD SUCCESSFUL in 2m 29s`：core/common **45 套件 622 例 0 失败 0 错误 11 跳过**、core/database **20 套件 192 例 0 失败 12 跳过**、feature/settings **23 套件 354 例 0 失败 0 跳过**（11/12 skip 是两个环境门控套件 `SourceSyncTlsInteropTest` / `SourceSyncListenerInteropTest`，正常）。
- C# 全量 `MediaHub.Tests`：**1614 通过 / 0 失败 / 0 跳过**（1604 → 1614，+10）。注意必须带 `-p:Platform=x64`，否则会跑 `bin/Debug/net10.0-windows/win-x64/` 的陈旧输出树。
- `--required-suite` 50 → **51**（新增 `com.mediahub.core.common.pairing.PairingEdgeContractTest`）。门禁脚本自测 `python -m unittest discover -s .github/scripts/tests` → Ran 54 tests OK。
- 仓库根 `hs_err_pid*.log` / `replay_pid*.log` 计数 0；gradle 与 dotnet 全程串行（严禁并发，本机内存不足时 gradle 会报 `os::commit_memory`）。

## 8 未做项与遗留

- **真实两机跨机**：仍两端 NOT_RUN。本轮的「跨端」是**字节级契约对照**，不是网络实连——配对传输的真实 TLS 会话在两端各自的用例里跑（Kotlin 用假 Windows 主机、C# 用 loopback TCP），没有真正把两台机器接起来。
- **真机 instrumentation**：DEVICE_UNVERIFIED。本轮没有新增 androidTest（改动全在 JVM 单测可覆盖的文本判定层）。
- **`PairingAddressEnumerator` 的接口名前缀分类**（`tun/tap/ppp/wg` → TUNNEL、`wlan/wl/wifi/swlan` → WIRELESS）是 Android 独有逻辑，Windows 没有对位实现 ⇒ **不进跨端向量**，只在 Kotlin 侧做内部一致性（排序末级改成 `WireText.compareAsciiIgnoreCase` 后仍是全序）。
- Windows 同步页 UIA、W10 U2/U3/U4 GUI 证据仍需用户授权窗口。
