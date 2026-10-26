# W6-66 归档扩展名统一：Windows CLI 默认名改为 `.mharchive`

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本轮边界：**只改默认文件名与提示文案**，归档字节格式、magic、KDF、AAD、判定序一行不改；旧的 `.mhsync` 文件继续可读。

---

## 1 动机：同一套字节格式，两个默认名

W6-64 把 Android 的加密导出接到界面上时，`SourceSyncArchiveExport` 里写死了
`DEFAULT_FILE_NAME = "sync-envelope.mharchive"`；而 Windows CLI 从 ADR-S005 起，`export --passphrase` 落盘的名字一直是
`sync-envelope.mhsync`。两端写的是**同一套字节格式**：magic `MHSYNCA1`（8 B ASCII，与 `.mhbackup` 的 `MHBK` 不同）、
PBKDF2-HMAC-SHA256 600 000 轮、salt 16 B、key 32 B、AES-GCM（nonce 12 B / tag 16 B）、
AAD = `magic + BE int32(headerLength) + headerJson` 的磁盘原字节。差别只在默认扩展名一个字。

这件事没有功能后果，因为**两端读端都按前 8 字节 magic 嗅探，从不按扩展名分支**；
但它在两个地方持续放血：

1. 文档要把「扩展名差异」当成一条遗留缺口记（`android-source-sync-transfer.md` §37.2 对比表、§37.8 第 4 条、§37.9 第 3 条），每读一次都要先解释「这不是两个格式」；
2. 用户看到 Android 导出 `.mharchive`、Windows 导出 `.mhsync`，最自然的推断就是两种文件需要两种打开方式——而事实恰好相反。

W6-66 因此只做一件事：**把 Windows CLI 的默认名改成 `.mharchive`，把 `.mhsync` 降级为只读兼容名**。
选择「改默认名」而不是「改格式」，是因为格式一侧没有任何理由动——两端的读端已经证明能互吃
（W6-62 的 C# 写 → Kotlin 读、W6-65 的 Kotlin 写 → C# 读）。

---

## 2 方法：默认名与兼容名分开，读端行为一字不改

| 决定 | 取值 | 理由 |
| --- | --- | --- |
| 写端默认名 | `sync-envelope.mharchive` | 与 Android `SourceSyncArchiveExport.DEFAULT_FILE_NAME` 逐字相同 |
| 旧名 | `sync-envelope.mhsync`，保留为 `SyncCliRunner.LegacyArchiveFileName` | 测试与外部调用方需要一个不写字符串字面量的入口；旧文件继续可读 |
| 新增 CLI 参数 | **不加** | 读端按 magic 嗅探，`--in` 传什么名字都能读；加参数只是把命名选择推给用户 |
| 读端扩展名白名单 | **不加** | 加了就等于「按扩展名分支」，会同时破坏「明文信封改名成 `.mharchive` 仍按明文读」这条行为 |
| 界面选择器 | `PickFileAsync([".json", ".mharchive", ".mhsync"])` | `.mharchive` 是规范名，`.mhsync` 只为兼容老文件仍可选 |

刻意**不采用**的两个方案，理由要写下来（否则以后会有人再提）：

1. *在归档头里加一个 `fileExtension` 字段*——头部 JSON 是 GCM 的 AAD 一部分（W6-65 §5），改它等于改格式；
   且扩展名属文件系统元数据，不该进密码学头。
2. *按扩展名决定要不要索要口令*——这正是「明文信封改名成 `.mharchive` 仍按明文读」这条反例要否掉的：
   扩展名只是提示，判据只能是内容。

---

## 3 落点行号表

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `windows/src/MediaHub.SyncCli/SyncCliRunner.cs` | :26-30 | 注释（`every reader sniffs the magic bytes and never the extension`）+ `public const string DefaultArchiveFileName = "sync-envelope.mharchive";` |
| 同上 | :32-33 | `public const string LegacyArchiveFileName = "sync-envelope.mhsync";`（pre-W6-66 后缀，保留只为调用方不必写字符串字面量） |
| 同上 | :2107 | `Path.Combine(invocation.Root, encrypted ? DefaultArchiveFileName : DefaultEnvelopeFileName)` |
| `windows/src/MediaHub.App/Views/SyncPage.xaml` | :33 | 导出按钮 ToolTip：`…写入 sync-envelope.json，默认不含任何密钥；加密导出写入 sync-envelope.mharchive` |
| 同上 | :132 | 应用卡说明：`选择对端导出的信封文件（sync-envelope.json，或加密存档 .mharchive）…` |
| `windows/src/MediaHub.App/Views/SyncPage.xaml.cs` | :649-651 | 注释（`.mharchive` canonical、`.mhsync` 保持可选）+ `path = await FilePickerService.PickFileAsync([".json", ".mharchive", ".mhsync"]);` |
| `windows/tests/MediaHub.Tests/SyncCliTests.cs` | :562 | 新分节注释 `// ---- archive file names (W6-66) ---` |
| 同上 | :566 / :582 / :601 | 三个 `[Fact]`（见 §4） |
| `windows/tools/acceptance/w6/sync-cli-e2e.ps1` | :324 / :339 | 两个新 `Check`（见 §4），插在 #9 `passphrase-archive-round-trips-to-a-fresh-device` 之后 |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportLogic.kt` | :61 | 新增 `SourceSyncImportCopy.PICK_HINT`（含 `.json` / `.mharchive` / 旧 `.mhsync` 三句） |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt` | :369 | 选文件区改用该常量（原内联长句 `.json 或 .mhsync 的明文信封` 已删） |
| `feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportLogicTest.kt` | :280 | 新用例 `thePickHintNamesTheCanonicalArchiveSuffixAndNeverCallsTheLegacySuffixPlaintext` |

Android 侧只改文案、不改行为：选文件仍读字节、仍按 magic 嗅探、仍在口令卡里解密。
改文案的原因和 Windows 一样——旧提示写「`.json` 或 `.mhsync` 的明文信封」，把 `.mhsync` 说成明文，
恰恰与两端读端的实际判据相反。

---

## 4 用例清单

Windows 三个 `[Fact]`（`SyncCliTests.cs`，每个都在临时根目录里跑真 CLI 进程）：

| 用例 | 钉住什么 |
| --- | --- |
| `Run_EncryptedExportUsesTheCanonicalArchiveSuffix` | 加密导出后 `sync-envelope.mharchive` 存在、`sync-envelope.mhsync` **不存在**；同时断言两个常量的值本身 |
| `Run_ArchiveUnderTheLegacySuffixIsStillRead` | 把规范名归档改名成 `.mhsync` 再 `import --passphrase --apply`：exit 0、`appliedCount=1`、stderr 不含 `malformed-payload` |
| `Run_PlainEnvelopeNamedLikeAnArchiveIsStillPlaintext` | 把明文信封改名成 `.mharchive` 再 `import --apply`：exit 0、`appliedCount=1`、stderr 不含 `passphrase-required` |

第二、三是**反向对照**：只断言「默认名变了」的话，把读端改成按扩展名分支也能绿；
这两条把「扩展名不是判据」钉成行为断言。

验收脚本两个新检查（`sync-cli-e2e.ps1`，14 → 16 项）：

| # | 检查 | 钉住的规则 |
| --- | --- | --- |
| 10 | `encrypted-export-uses-the-canonical-archive-suffix` | 规范名落盘、旧名不再写；把规范名复制成 `.mhsync` 后新设备仍能导入（`appliedCount=2`） |
| 11 | `plain-envelope-renamed-to-an-archive-suffix-is-still-plaintext` | 明文信封复制成 `.mharchive` 后仍按明文导入（`appliedCount=2`），不索要口令 |

Kotlin 一例：`thePickHintNamesTheCanonicalArchiveSuffixAndNeverCallsTheLegacySuffixPlaintext`——四条断言
（含 `.mharchive` / `.json` / `.mhsync`，且**不含** `.mhsync 的明文信封`）。这是文案的回归锁。

---

## 5 新发现：改默认名不会破坏兼容，但「扩展名不是判据」必须被钉住

两端读端的实际判据（逐行核对）：

- Windows `SyncCliRunner.TryResolveEnvelope`：先按 `.mharchive` 解密，失败再按 UTF-8 当明文解析——不看路径后缀；
- Kotlin `SourceSyncDocumentInput.resolve(bytes, passphrase)`：`looksLikeArchive` 前 8 字节逐字节比 `MHSYNCA1`，命中才走归档分支。

所以「把 `.mhsync` 归一到 `.mharchive`」在两端都是纯命名变更。风险只有一个：
**如果谁把「按扩展名判断是否加密」写进读端**，那老文件改名后会突然被索要口令、新文件被当明文解析，
而单测只要不覆盖这两个方向就发现不了。本轮的两个反向对照用例与两个验收检查就是钉这件事的。

另一条值得记下来的：`.mhsync` 常量不是「历史遗留所以删掉」。
它被保留为 `LegacyArchiveFileName`，因为测试里要断言「旧名不再被写出」需要一个不写字符串字面量的引用点；
删掉它等于让「旧名」这个词只存在于一处 if 之外的字符串里，下次改名更容易漂。

---

## 6 本机实跑（串行，防 OOM）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| C# 全量 + 验收脚本 | `D:/mh-verify/run-w6-66a-windows.bat`（先 `dotnet build-server shutdown`，再 build / `dotnet test` / `sync-cli-e2e.ps1`） | `BUILD_EXIT=0`；**1588 通过 / 0 失败 / 0 跳过**（1585 → 1588，+3）；`RESULT: 16 passed, 0 failed`、`ACCEPT_EXIT=0` |
| C# 只看新例 | 同上加 `--filter` 精确点三个新 Fact | **3/3 通过**、`EXIT=0`（`D:/mh-verify/w6-66a-filter.log`） |
| 验收 detail 抽样 | 同上日志 | `encrypted-export-uses-the-canonical-archive-suffix :: written=sync-envelope.mharchive legacy-name-still-reads applied=2`；`plain-envelope-renamed-to-an-archive-suffix-is-still-plaintext :: the reader sniffs MHSYNCA1 instead of trusting the extension`；`passphrase-archive-round-trips-to-a-fresh-device :: magic=MHSYNCA1 bytes=1189 applied=2` |
| Android 三模块 | `D:/mh-verify/run-w6-66b-gradle.bat`（低内存模板 + `--rerun-tasks`，确保三模块都真跑而不是 UP-TO-DATE） | `BUILD SUCCESSFUL in 3m 18s`、`167 actionable tasks: 167 executed`、`EXIT=0` |
| 清理 | 跑完 `gradlew --stop` | 仓库根 `hs_err_pid*.log` 计数 0、残留 java / dotnet 进程 0 |

首轮全量 build 撞到一次 `error MSB3021: 无法将文件 …DirectML.dll 复制到 …`（同样 onnxruntime.dll）：
目标文件当时并不存在、目录 ACL 正常、手工 `Copy-Item` 也成功 ⇒ 是**残留 MSBuild / VBCSCompiler 节点句柄**造成的瞬时锁，
`dotnet build-server shutdown` 后单项目 build 成功、整轮即绿。这条已写进验证脚本的头部。

XML 汇总（`<testsuite>` 属性相加，非估算；`--rerun-tasks` 后三模块都是当轮产物）：

| 模块 | 套件 | 用例 | 失败 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- |
| `core/common` | 42 | 596 | 0 | 11 | 不变 |
| `core/database` | 20 | 192 | 0 | 12 | 不变 |
| `feature/settings` | 23 | **354** | 0 | 0 | 353 → 354（+1 = §4 的文案用例） |

三模块合计 **1142** 例（596 + 192 + 354）。本轮**没有**新增套件，`--required-suite` 仍是 48 条；
`androidTest` 仍 88 例（文案改动没有新增 Compose 断言）。

---

## 7 未覆盖（本节交付时必须如实保留）

1. **真实两机搬运**：把 Windows 导出的 `.mharchive` 拷到另一台机器再让 Android 导入的完整流程 NOT_RUN（同 §33.8–§38.9 各节，都需要第二台机器）。
2. **已发布版本的兼容性**：`.mhsync` 只读兼容是**读端行为**，本机用改名文件验过；但「用旧版本 CLI 导出的真实历史文件」没有样本可测（旧版本写出的字节格式与本轮相同，风险仅在此一处）。
3. **其他调用方的默认名**：仓库内除 CLI 与 App 外没有第三处写归档名的代码；仓库外的脚本若硬编码 `.mhsync`，本轮无法感知。
4. **不涉及 GUI 证据**：App 只改提示文案与文件选择器的扩展名列表，没有新增控件、没有改交互顺序；同步页四张卡的 GUI 证据仍按 W6-43 / W6-49 / W6-58 / W6-64 的既有口径记为 GUI_UNVERIFIED。
5. **设备侧**：Android 侧只加一条纯 JVM 文案用例，无设备依赖；真机 `DEVICE_UNVERIFIED` 的既有口径不变。

---

## 8 下一步

- 真实两机搬运：Windows 导出 → 拷到另一台机器 → Android 导入（同时关闭主树 §36.8 第 1 条与 §37.8 第 1 条）；
- 设备侧密码学：PBKDF2 600 000 轮在模拟器 / 低端机上的耗时与峰值内存，写读两侧都要；
- 归档格式若要演进（新 KDF、新字段），先按 W6-47 ADR 的方法写契约，再两端同步加码——不要单边加。
