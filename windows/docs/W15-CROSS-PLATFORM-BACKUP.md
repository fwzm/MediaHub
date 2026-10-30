# W15 跨平台备份容器（Windows ↔ Android `.mhbackup` 互通）

> 状态：容器层 **EXECUTED_PASS**（Windows 1793/0、Android core/common 666/0、双向跨语言向量全绿）；
> 还原预览层（恢复计划器，纯函数零写入）**EXECUTED_PASS**（22 例 + 四条负向对照）；
> 导出采集层（本机来源/偏好 → 备份载荷，纯函数）**EXECUTED_PASS**（17 例 + 四条负向对照）；
> 备份命令行（`export` / `import` 预览）**EXECUTED_PASS**（13 例 + 四条负向对照，全量 MediaHub.Tests 1806/0）；
> 还原落库（`import --apply`：来源与偏好写 `settings.json`）**EXECUTED_PASS**（applier 12 例 + CLI 用例 17 例 + 四条负向对照，全量 MediaHub.Tests **1822/0**）；
> WebDAV 上传（`upload`：PUT + `--mkdir`，远端失败独立退出码 9）**EXECUTED_PASS**（上传器 11 例 + CLI 3 例 + 四条负向对照，全量 MediaHub.Tests **1836/0**）；
> 设置页入口、播放进度/收藏落库、WebDAV 下载/删除仍 **NOT_IMPLEMENTED**；端到端「手机备份 → 电脑还原仍可用」尚未接线 **NOT_RUN**。

## 1 动机与范围

用户口径（2026-10-30）：两台机器联调**暂时不做**；同步功能改为**本地备份 / 还原，或 WebDAV 备份 / 还原**，并且
**电脑与手机导出的文件必须互通** —— 手机备份出来的文件还原到电脑仍可正常使用，反之亦然。

本轮只做**容器层**：让 Windows 写出 Android 读得懂、Android 写出 Windows 读得懂的 `.mhbackup`。
备份的**内容层**（还原计划器、来源/进度落库、设置页入口、CLI）与**传输层**（WebDAV 上传）不在本轮。

## 2 格式契约（逐字段对齐 Android 权威实现）

| 项 | 值 | 备注 |
| --- | --- | --- |
| 外层容器 | JSON `Envelope`：`magic` / `formatVersion` / `kdfAlgorithm` / `kdfSaltB64` / `kdfIterations` / `kdfKeyLengthBits` / `nonceB64` / `ciphertextB64` | 字段名与顺序均与 Android 一致 |
| 魔数 / 版本 | `MHBK` / `FORMAT_VERSION=2`、`MINIMUM_READER_VERSION=2` | 版本 > 2 ⇒ `VersionTooNew` |
| 密钥派生 | PBKDF2-HMAC-SHA256，600 000 次，salt 32 B，密钥 256 bit | 迭代数允许 10 000–2 000 000（读取时） |
| 加密 | AES-256-GCM，nonce 12 B，tag 128 bit | **密文 = ciphertext‖tag** |
| AAD | UTF-8 的 `magic│formatVersion│kdfAlgorithm│kdfIterations` | 头部参数被改 ⇒ 解密失败 |
| 明文 | `Payload`：`manifest` + `servers` / `progress` / `favorites` / `preferences` | 白名单，**不含密码 / Token / Cookie / DeviceId / 临时 URL** |
| 身份 | `(type, 规范化主地址, username)` | 地址规范化保留显式写出的默认端口 |
| 上限 | 文件 10 MiB、源 1000、进度 20000、端点 64/源、收藏 5000、收藏标题 512 字符 | 导出与导入两侧都校验 |

两个**必须**留意：

1. Java `Cipher.doFinal` 的输出布局是 `ciphertext‖tag`（tag 追加在末尾）；.NET `AesGcm` 把密文与 tag **分开输出**，
   所以 Windows 侧在 `Encrypt` 里显式缝合、在 `Decrypt` 里按末 16 字节切出 tag —— 这是跨语言能否互解的第一道坎。
2. AAD 只绑**头参数**，不绑明文：所以「改 formatVersion 再解密」必然失败（`AuthenticationFailed`），而不是「用新版本读出旧明文」。

导入失败分类（与 Android 同名）：`NotABackupFile` / `VersionTooNew` / `Corrupted` / `AuthenticationFailed`。
错口令与文件被篡改**同一分类**，不区分 —— 否则容器就成了口令预言机。

## 3 Windows 实现（`windows/src/MediaHub.Backup`，零 NuGet 依赖）

| 文件 | 职责 |
| --- | --- |
| `MhBackupCrypto.cs` | 常量、`DeriveKey`（`Rfc2898DeriveBytes.Pbkdf2`，口令字节在 finally 清零）、`AadFor`、`Encrypt`（缝合 tag）、`Decrypt`（切 tag） |
| `MhBackupEnvelope.cs` | `Envelope` / `Manifest` record 与 JSON 选项（不缩进、`UnsafeRelaxedJsonEscaping` 保证中文不转义） |
| `MhBackupDto.cs` | 白名单 DTO 与默认值逐项对齐 Android（`0xFFFFFFFF`、`1f`、`0.08f`、`0.5f/5f/2f` …）+ `PayloadCodec` |
| `MhBackupUrlGuard.cs` | 凭据 query 键黑名单（token/api_key/… 37 项，键做 URL 解码后小写比较）、userinfo 拒绝、scheme/host/port 校验、身份规范化 |
| `MhBackupSerializer.cs` | `Export` / `Import` 的完整校验顺序与中文原因串（体积 → 魔数 → 外层 → 派生 → 解密 → 明文 → 内层 → 版本相等） |

`MediaHub.Backup` 通过 `ProjectReference` 被 `MediaHub.Tests` 引用，因此 CI 不需要把它加进 `.sln` 也会构建并测试。

## 4 跨语言黄金向量

- 生成器在仓库外：`D:/mh-verify/backup-vectors-gen`（net10.0 console，`ProjectReference` 指向 `MediaHub.Backup`），
  产物 `D:/mh-verify/backup-vectors.json`。
- **两份副本必须同哈希**（漏拷一份会让问 Windows 套件 `KeyNotFound`）：
  - `core/common/src/test/resources/backup/backup-vectors.json`
  - `windows/tests/assets/backup-vectors.json`
  - 当前 sha256 `d581fe287c8c747391db6a75024892296f0ef459c1eeafc421da9e29e9d48cc9`，31 617 B。
- `cryptoCase`：固定 salt=0..31、nonce=0..11、口令 `跨平台备份-测试口令-2026`、600 000 次迭代 —— 两端必须算出**同一条密文**（2 076 字符）。
- `cases`（8 条）：`windows-export-ok` / `fixed-salt-ok` / `wrong-password` / `downgraded-header` / `newer-version` /
  `salt-too-short` / `nonce-not-base64` / `not-a-backup`，每条钉期望分类（必需时钉版本号与原因片段）。
- `androidExport`：由 Kotlin 侧写出的反向容器（Android 编码 + 加密），Windows 侧解出同字段并**重算密文**比对。
  生成器重跑时会把已有 `androidExport` 原样带回，不会丢反向证据。

## 5 验证与证据

复跑：

- Windows：`dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj --no-restore --filter FullyQualifiedName~Backup`
- Android：`gradlew.bat :core:common:testDebugUnitTest --tests "*BackupWindowsContractTest*"`

实测计数：

- `MediaHub.Tests` **1754 / 0**（备份容器 23 例 + 跨语言向量 5 例；App 之外无改动）
- `core:common` **666 / 0**（11 skipped 为环境门控套件），其中 `BackupWindowsContractTest` 6 例

三条负向对照（改坏点 → 红 → 逐字节还原并复核 sha256）：

| 对照 | 改坏点 | 结果 | 还原 sha256 |
| --- | --- | --- | --- |
| A | Windows `AadFor` 多拼一段 | 向量 5 例红（自洽回环仍绿 —— 只改一端时，同端加解密仍一致） | `56a2bf4d…ce2a25` |
| B | Kotlin `aadFor` 多拼一段 | 对照 6 例中 5 红（写入器自检仍 Ok） | `5830efd2…388e14` |
| C | Windows 跳过「外层版本 > 2」检查 | 向量分类 1 例 + 容器版本用例 1 例红 | `789cc4e8…8b1777` |

**B 的教训**：单端自洽（自己写、自己读、自检 Ok）**不能**证明互通；只有跨语言向量能抓出「两端各改一处、各自都自洽」的分叉。

## 6 还原预览（恢复计划器，W16）

容器能解出载荷之后，第二个问题是「里面的来源、进度、收藏落到本机时会发生什么」。这一层必须是**纯函数预览**：
不读注册表、不写 settings.json、不碰凭据库，只吃「备份载荷 + 本机来源 + 本机进度」三份输入，输出每条记录的裁决与中文逐行说明。
UI 与 CLI 都只能拿这份预览去征求确认，写入是下一层的事。

### 6.1 输入与输出

| 类型 | 形状 | 说明 |
| --- | --- | --- |
| `MhBackupLocalSource` | `BackupId` / `Type` / `PrimaryUrl` / `UserName` | 本机已登记来源的最小投影；`BackupId` 是本机主键 |
| `MhBackupLocalProgress` | `(ServerBackupId, ItemId, UpdatedAtEpochMs)` | 只为「本机已有同键进度」的新旧比较服务 |
| `MhBackupRestoreStrategy` | `Merge` / `ReplaceSelected` | 合并 = 保留本机同源；替换 = 只覆盖被选中的 |
| `MhBackupServerVerdict` | `New` / `Identical` / `Conflict` | 逐源 |
| `MhBackupRecordVerdict` | `Apply` / `SkipNewerLocal` / `SkipConflict` / `Orphan` | 逐条进度/收藏 |

`MhBackupRestorePlan` 同时暴露计数（`NewCount` / `IdenticalCount` / `ConflictCount` / `ProgressToWriteCount` / `OrphanProgressCount` /
`FavoritesToWriteCount` / `OrphanFavoriteCount` / `ServersToWriteCount` / `HasWrites`）、`ConflictServerNames`、`PreferencesContained` / `PreferencesWillRestore`、
`IdRemapping`、可回传给 Android 的 `MhBackupRestorePlanRecord ToRecord()`，以及给界面逐行渲染的 `IReadOnlyList<string> SummaryLines`。

### 6.2 身份判据（与 Android `compareIdentity` 同口径）

身份 = `(type, normalizeForIdentity(生效主地址), username)` 三段全等。细节：

- 类型只做 `Trim() + ToUpperInvariant()`（Windows 存 `"Emby"`、线上是 `"EMBY"`），**不做别名猜测** —— 猜错会把两个不同来源合并。
- 地址走 `MhBackupUrlGuard.NormalizeForIdentity`：scheme/host 小写、丢弃 fragment、路径去尾斜杠、**保留 query**，并保留显式写出的默认端口（`http://h:80` 与 `http://h` 不等）。
- 生效主地址 = 启用端点里 `isPrimary` 优先、否则最小 `sortOrder` 的那一条；全部未启用 ⇒ 空串（不会误判成 Identical）。
- `username` 用序数比较：`null` 与 `""` 不同，大小写不同也不同。

### 6.3 裁决顺序

| 情形 | 来源裁决 | 该来源下的进度/收藏 |
| --- | --- | --- |
| 本机没有同 `backupId` | `New`（合并与替换都写） | 按下面规则逐条判 |
| 合并 + 身份全等 | `Identical`（保留本机） | 本机同键且不旧于备份 ⇒ `SkipNewerLocal`；否则写 |
| 合并 + 身份不全等 | `Conflict`（不导入，附原因串） | 一律 `SkipConflict` |
| 替换 | 覆盖已有（`New` 语义） | 不做 newer-wins，直接写 |
| `backupId` 缺失/重复 | 强制 `Conflict`（永不可写） | `SkipConflict` |
| 进度引用了备份里不存在的来源 | — | `Orphan`（计入并披露，不静默丢） |

**与 Android 的显式差异（唯一一处）**：合并模式下冲突来源在 `IdRemapping` 里映射为 `null`（Android 恒等映射为自身 id）。
理由是防止调用方绕过「冲突不导入」的裁决，直接拿映射去写该来源的进度或收藏；KDoc 已写明。

### 6.4 验证

- 用例：`windows/tests/MediaHub.Tests/BackupRestorePlannerTests.cs` 共 **22 例**（19 个 `[Fact]` + 1 个 2 行 `[Theory]`）：空载荷零写入、新源可写、同身份 `Identical`、
  `Emby` vs `EMBY` / URL 大小写 / 尾斜杠不误判、地址·账号·类型三类冲突各带原因串、LOCAL 空端点不误判、旧进度被覆盖、本机不旧于备份 ⇒ `SkipNewerLocal`、
  冲突来源的进度/收藏 ⇒ `SkipConflict`、孤立引用 ⇒ `Orphan` 并在 `SummaryLines` 披露、替换模式不做 newer-wins、`ToRecord()` 字段形状、规划**不改**载荷与本机输入、缺 `backupId` 记冲突、中文预览文案。
- 本机：`dotnet test ... --filter FullyQualifiedName~BackupRestorePlannerTests` ⇒ **22 / 0**（75 ms）；日志 `D:/mh-verify/w16-planner-tests.log`。
- 四条负向对照（改坏点 → 红 → 逐字节还原并复核 sha256 `2cb136e6…f11c1d`）：

| 对照 | 改坏点 | 结果 |
| --- | --- | --- |
| A | 账号比较写成 `if (false)` | 2 红（账号冲突两条） |
| B | 冲突来源映射回自身 id 而非 `null` | 4 红（冲突跳过 + `ToRecord` 不含冲突键） |
| C | 关掉 newer-wins（改比 `localUpdated < 0`） | 2 红（Theory 两行） |
| D | `ToRecord()` 的可覆盖集合只取 `Identical` | 1 红（记录形状） |

### 6.5 仍未做（本层之外）

- 真正落库：把 `MhBackupRestorePlan` 变成 settings.json 的新来源、history.json 的进度、服务端的收藏 —— **NOT_IMPLEMENTED**
- `MediaHub.BackupCli`（`export` / `import` 默认 dry-run、`--json`、退出码）与设置页「备份与还原」入口 —— **NOT_IMPLEMENTED**
- 收藏在 Windows 只能登录后写服务端（本机无收藏表），未登录时的出路尚未定 —— **IMPLEMENTED_UNVERIFIED**
## 7 Windows 导出采集层（W17）

### 7.1 缺口

手机 → 电脑这条路此前只能「解密 + 预览」，电脑 → 手机仍缺最后一层：把本机来源写成 `.mhbackup` 载荷。
没有这一层时，Windows 只能还原别人写的备份，自己导不出对端可用的文件。

### 7.2 输入与输出

- 输入 `MhBackupLocalServer`：与 `settings.json` 的 `PersistedServer` 一一对应（ServerId / Type / DisplayName / Url / UserName / SyncSourceId），**不含任何凭据**。
- 输入 `MhBackupLocalPreferences`：只收三个有明确等价关系的字段（DefaultSpeed / AutoPlayNext / ShowDetailedMediaInfo）。
- 输出 `MhBackupExportCollectResult`：`Payload`（可直接交给 `MhBackupSerializer.Export`）、`SkippedServers`（名称 + 中文原因）、`SkippedHistoryEntries`、`Notes`（中文逐条说明）。
- `MhBackupExportCollector.Collect(servers, preferences, skippedHistoryEntries, nowEpochMs, appVersion)` 是纯函数：不读文件、不碰凭据、不加密。

### 7.3 映射与拒绝规则

- 类型只认已知表：`Emby→EMBY`、`Jellyfin→JELLYFIN`、`Plex→PLEX`、`FnNas / Fn-Nas→FN_NAS`、`WebDAV→WEBDAV`、`SMB→SMB`、`Local / LocalFolder→LOCAL`；未知类型**跳过并给原因**，不猜。
- `backupId` 优先取 `SyncSourceId`；缺失时按 `TYPE|规范化地址|账号` 的 SHA-256 前 8 字节派生 `win-<16 hex>`，同一来源跨次导出一致；重复 `backupId` 只保留第一个。
- 网络来源必须有地址，且必须通过 `MhBackupUrlGuard`：userinfo 或查询参数里的 `token` / `api_key` 等一律**拒绝而不是脱敏**，避免把看起来像凭据的地址写进备份。
- `LOCAL` 来源不带端点（与 Android 一致）；名称为空回退到地址 host，既无名称也无地址则跳过。
- 偏好只导出三个等价字段；字幕字号等因两端单位不同不导出，对端保持自身默认值 —— 填一个数字看起来像映射成功、实际会得到错误字号。
- 本机观看进度**不导出**：`history.json` 以文件路径为键，备份格式以（来源 backupId、条目 id）为键，没有可靠对应关系；宁可报告 `skippedHistoryEntries` 也不猜测。
- 成功来源数超过 1000 时抛 `MhBackupExportException`（不静默截断）。

### 7.4 验证

- `windows/tests/MediaHub.Tests/BackupExportCollectorTests.cs` 17 例：字段映射与默认标记、派生 id 稳定性、凭据地址拒绝、userinfo 拒绝、LOCAL 无端点、未知类型、重复 id、空名称回退 host、空地址、偏好映射、非法倍速回退、无偏好即无 preferences 段、容器往返（导出 → 导入 → 字段一致）、进度只计数不入载荷、超限拒绝、载荷不含 deviceId / password / token / cookie。
- 本机：`dotnet test ... --filter FullyQualifiedName~BackupExportCollectorTests` ⇒ 17 / 0；全量 `MediaHub.Tests` ⇒ **1793 / 0**。
- 四条负向对照（快照 sha256 `d0beb80a…c6d5`）：A 跳过 URL 守卫 2 红 / B 不查重复 id 1 红 / C 不限倍速 1 红（两条断言在同一用例里）/ D 声明未知分区 2 红；每次还原后 sha256 相等。
- **环境陷阱（本轮实际踩到）**：负向对照用 `fs.copyFileSync` 还原时，Windows 会保留快照的旧 mtime；MSBuild 因此认为 DLL 仍最新而跳过重编译，后续测试打的是旧二进制 —— 先出现 2 条假红。还原后必须 `utimesSync` 触碰文件（或清理 `obj/bin`）再跑。

### 7.5 仍未做（本层之外）

- 设置页「备份与还原」入口（`MediaHub.BackupCli` 已在 W18 完成，见 §8）。
- 把采集结果真正写成 `settings.json` 的来源、`history.json` 的进度。
- WebDAV 上传与 Android 反向对照。

## 8 备份命令行（W18）

### 8.1 缺口

容器、采集层、恢复预览都已就位，但用户没有办法**真的**导出一次、也没有办法在不改动的本机的前提下看一遍还原会做什么。
设置页入口属于 GUI 层（需空闲窗口），命令行是没有窗口时唯一可执行的出口。

### 8.2 命令

- `MediaHub.BackupCli export --settings <settings.json> [--history <history.json>] --out <备份文件> [--password-env <NAME>] [--json]`
  - 读 settings.json → `MhBackupLocalData` → `MhBackupExportCollector` → `MhBackupSerializer.Export` → 写文件；
  - 一个来源都导不出时拒绝导出（不是写一个空备份）；
  - 输出含来源数、被跳过的来源与原因、说明行、字节数、SHA-256、包含段，以及「备份不含密码或令牌，还原后需要重新登录」。
- `MediaHub.BackupCli import --file <备份文件> [--settings <settings.json>] [--strategy merge|replace] [--password-env <NAME>] [--json]`
  - 解密 + `MhBackupRestorePlanner.Plan`，只打印「新增 / 已有同源 / 冲突 / 孤立引用 / 设置是否会恢复」，**不写任何文件**；
  - 没有 `--settings` 时按「本机没有任何来源」预览，并在输出里说明这一点，不假装知道本机现状。
- 退出码：`0` 成功、`2` 用法错误、`3` 不是备份文件、`4` 口令错/被篡改、`5` 版本过新、`6` 内容损坏、`7` 导出被拒、`8` 读写失败。

### 8.3 三条硬约束

1. **口令只从环境变量读**（缺省 `MH_BACKUP_PASSWORD`，可用 `--password-env` 换名）。命令行参数会进 shell 历史与进程列表，不接受。
2. **`import` 默认只预览**，这是本版的全部能力；`--apply` 被明确拒绝并报用法错误，而不是接受参数后什么都不做。
3. **JSON 输出不含口令、也不含备份里的明文**；测试用字符串包含关系钉住这一点。

### 8.4 身份 id 一致性（本轮修掉的真缺陷）

导出侧对没有 `SyncSourceId` 的来源派生 `win-<16 hex>`；预览侧最初却用 `SyncSourceId ?? ServerId` 当本机 id。
结果是：同一台机器导出再预览，同一个来源在备份里叫 `win-…`、在本机叫 `srv-1`，被判成「新增」，真还原会重复写入。

修法：把派生逻辑提升为 `MhBackupExportCollector.DeriveBackupId(MhBackupLocalServer)` 单一入口，导出与预览都调它；
预览侧同时对 Url / UserName 做 trim（空账号归 null），与导出侧的规范化一致。

### 8.5 验证

- `windows/tests/MediaHub.Tests/BackupCliTests.cs` 13 例：往返可预览、导出无口令拒绝且不落盘、无来源可导拒绝、JSON 可解析且不含口令、凭据地址跳过、非备份文件退出码 3、口令错退出码 4、篡改容器退出码 6、版本过新退出码 5、`--apply` 拒绝、预览不动 settings/目录、无 `--settings` 全判新增、未知命令与缺参数报用法。
- 本机：`--filter FullyQualifiedName~BackupCliTests` ⇒ **13 / 0**；全量 `MediaHub.Tests` ⇒ **1806 / 0**（W17 的 1793 + 13）。
- 四条负向对照各**恰好 1 红**、恢复后 13/0：
  - A 接受 `--apply` 却不报错 ⇒ `ImportApplyIsExplicitlyUnsupported`；
  - B 去掉 `NotABackupFile` 的退出码 ⇒ `ImportOfAPlainTextFileIsNotABackup`；
  - C 预览退回 `SyncSourceId ?? ServerId` ⇒ `ExportWritesAContainerThatImportCanPreview`（重现重复新增）；
  - D 口令错映射成 Corrupted ⇒ `ImportWithAWrongPasswordFailsWithItsOwnExitCode`。
- 两个新工程已加入 `windows/MediaHub.Windows.sln`（`MediaHub.Backup` 与 `MediaHub.BackupCli`）：不进 sln 的工程 CI 不会还原/编译。
- 复跑环境说明：本会话里 `dotnet restore` 对任何工程都报 `Value cannot be null. (Parameter path1)`，因此本机用 `--no-restore` + 引导好的 `obj/project.assets.json` 构建（`dotnet build MediaHub.Windows.sln -c Debug -p:Platform=x64 --no-restore` 成功、0 错误）；CI 上真 restore 会覆盖这一点。

### 8.6 仍未做（本层之外）

- 真正落库：新来源写进 `settings.json`（还原后需重新登录的 AuthRequired 语义）、进度写进 `history.json` 的映射、收藏登录后写服务端。
- 设置页「备份与还原」入口（口令框 → 预览 → 确认）。
- WebDAV 上传（当前 WebDAV 只读）与 Android 反向对照。

## 9 还原落库（W19）

### 9.1 缺口

W18 的 `import` 只会打印预览：用户看到「新增 3 个来源」，却没有任何命令把它们真正写进本机。
W19 让 `import --apply` 落库，同时**不扩大**能写的东西——只写本机能表达的来源和有明确等价的偏好。

### 9.2 落库边界（写什么、为什么不写其余）

| 备份里的段 | Windows 处理 | 原因 |
| --- | --- | --- |
| `servers` | 写 `settings.json` 的 `Servers`（`New` 追加；`Identical` 合并模式保本机、替换模式原地更新并保留 `ServerId`；`Conflict` 合并模式跳过、替换模式原地更新） | 与 `ServerManager.ApplyRemoteSource` 同口径：`Status = AuthRequired`，写入后需要登录；**备份里没有密码/令牌，也没有 `Status` 字段** |
| `preferences` | 仅替换模式写三对有明确等价的项：`DefaultPlaybackSpeed → DefaultSpeed`、`AutoPlayNextEpisode → AutoPlayNext`、`ShowPlayerInfoOverlay → ShowDetailedMediaInfo` | 字幕单位、手势阈值等两端语义不同，猜测映射比不写更糟 |
| `progress` | **不写**，只在回执里给原因 | Windows 按本地文件路径记账，备份按（来源、条目）记账；缺少条目→文件映射 |
| `favorites` | **不写**，只在回执里给原因 | 收藏是服务端能力，必须登录之后写回服务器 |

本机表达不了的来源逐条跳过并给原因：`PLEX`/`SMB`/`FN_NAS` 这一版没有提供方，`LOCAL` 是那台机器上的路径，未知类型直说「本机无法表达」。
地址缺失或没有可用线路同样跳过，不写一个打不开的来源。

### 9.3 写入安全

- **写前留底**：`settings.json.bak-<yyyyMMdd-HHmmss>`（同一秒冲突追加 `-2`）；留的是写入前的原字节。
- **原子写**：同目录 `settings.json.tmp-<8hex>` → `File.Move(overwrite: true)`，失败清理临时文件，不出现半个文件。
- **逐字节幂等**：同一份备份第二次 `--apply` 输出「没有需要写入的变更：settings.json 逐字节未动」，也不会再生成 `.bak`。
- **不猜结构**：`settings.json` 不是对象、`Servers` 不是数组、JSON 坏了 ⇒ 退出码 8，原文件保持不动。
- **未知键保留**：只改 `Servers` 节点与三对偏好，其余键原样带回。

### 9.4 验证

- `MhBackupRestoreApplier` 12 例：新来源落库、未知键与已有来源保留、id 被占用时让位（`sync:<id>#2`）、合并模式不动同源、合并模式跳过冲突、替换模式原地更新且保留 `ServerId`、PLEX/LOCAL 跳过并给原因、偏好只在替换模式写、无变更逐字节返回、第二次 apply 零写入、坏 `settings.json` 抛 `FormatException`。
- CLI 17 例（W18 13 例 + W19 增删）：`--apply` 缺 `--settings` ⇒ 退出码 2；空 settings 落库并输出「已写入本机设置」「新增 …」且生成 `.bak-*`；第二次 apply 零写入；不带 `--apply` 逐字节不动、不生成 `.bak`；坏 JSON ⇒ 退出码 8；用法文本说明 `--apply` 语义。W18 记录的 `ImportApplyIsExplicitlyUnsupported` 已被 `ImportApplyWithoutSettingsIsAUsageError` 等替代。
- 四条负向对照（快照 → 改坏 → 跑 → 逐字节还原，`sha256` 相等）：A applier 跳过 `New` ⇒ 1 红；B `--apply` 不留底直接写 ⇒ 1 红；C 同源不跳过、重复追加 ⇒ 1 红；D 用法去掉 `--apply` 说明 ⇒ 1 红。
- 全量 `MediaHub.Tests` **1822 / 0**（W18 的 1806 + applier 12 + CLI 净增 4）；日志 `D:/mh-verify/w19-full-tests2.log`、`w19-neg-{A,B,C,D}.log`。

### 9.5 仍未做（本层之外）

- 设置页「备份与还原」入口（口令框 → 预览 → 确认）—— NOT_IMPLEMENTED；目前只能用命令行。
- 播放进度与收藏落库—— NOT_IMPLEMENTED（上面已给原因；进度需要条目→文件映射，收藏需要登录后的服务端写）。
- WebDAV 上传已在 §10 实现（`upload`）；Android 反向对照 —— NOT_RUN。
## 10 WebDAV 上传（W20）

### 10.1 缺口

备份此前只能落在本地盘。仓库里的 WebDAV 客户端（`windows/src/MediaHub.Providers.WebDav/WebDavApi.cs`）是**只读**的：只有 PROPFIND / OPTIONS / range GET，零 PUT / MKCOL / DELETE，也没有任何写入口。
要满足用户口径「本地备份 / 还原，或 WebDAV 备份 / 还原」，至少得能把容器文件放上远端。

### 10.2 上传器（`windows/src/MediaHub.Backup/MhBackupWebDavUploader.cs`）

- 一个动作：`PUT`（`application/octet-stream`，字节原样）。账号口令由调用方传入，有账号就发 `Basic`，没账号就不发认证头（不猜匿名之外的语义）。
- 超时 60 s；失败一律折成稳定原因串，不把异常原文抛给调用方：`401/403` 认证、`409` 目录缺失、`400/415` 不接受、`507` 空间不足、`5xx` 服务端出错、超时、连不上。
- `--mkdir` 语义克制：只在远端返回 `409` 时创建**最后一级**父集合（`MKCOL`，201/200/204/405 都算已存在），然后重试一次 `PUT`；多级路径不做猜测式逐级创建。
- 字节与状态一起回：`Result(Ok, StatusCode, Reason, Location)`；CLI 再用 SHA-256 自己算一遍摘要给用户对账。

### 10.3 命令行（`upload`）

```
MediaHub.BackupCli upload --file <备份文件> --url <WebDAV 地址> [--user <账号>] [--password-env <变量名>] [--mkdir] [--json]
```

- **口令只从环境变量取**：远端口令默认读 `MH_WEBDAV_PASSWORD`，与备份口令的 `MH_BACKUP_PASSWORD` 分开——一个变量传两种凭据会让「谁的口令」变得含糊，命令行参数则会进历史记录。
- 退出码沿用 W18 的编号表，新增 **9（远端失败）**：本地读不出文件仍是 8，不会被误当成远端问题。
- 成功文本给「已上传备份 / 远端地址 / HTTP 状态 / 文件大小 / SHA-256」，并提醒备份不含密码或令牌、从远端取回仍需本机口令。`--json` 给同义的机器可读字段。

### 10.4 验证

- `BackupUploadTests` 14 例（11 上传器 + 3 CLI）：字节与 `Basic` 头精确、无账号不发认证、401/409/507/415 各自的原因、`--mkdir` 的请求顺序是 `PUT,MKCOL,PUT` 且 `MKCOL` 只打最后一级父集合、`MKCOL` 403 只重试失败一次、空内容与非 http 不发送、超时映射、CLI 成功文本与 JSON 字段、CLI 远端 401 退出码 9、用法错误零请求、本地文件缺失退出码 8。
- 四条负向对照（快照 → 改坏 → 跑 → 逐字节还原，`sha256` 相等）：A `PUT` 不发认证头 ⇒ 2 红；B 把 `409` 也算成功 ⇒ 3 红；C 上传路径的本地 IO 失败改报远端 ⇒ 1 红（该行在 `export` 与 `upload` 各有一处，按方法段切片才改对位置）；D 去掉 `--url` 必填校验 ⇒ 1 红。日志 `D:/mh-verify/w20-neg-{A,B,C,D}.log`。
- 全量 `MediaHub.Tests` **1836 / 0**（W19 的 1822 + 14）；日志 `D:/mh-verify/w20-full-tests1.log`。

### 10.5 仍未做（本层之外）

- WebDAV **下载 / 列目录 / 删除**、远端覆盖策略与冲突提示 —— NOT_IMPLEMENTED（本轮只有上传）。
- 用真实 WebDAV 服务器做一次「上传 → 取回 → 本机还原」端到端 —— NOT_RUN（本机无可用远端，也不在无授权时启停外部服务）。
- 设置页「备份与还原」入口里的「传到 WebDAV」按钮 —— NOT_IMPLEMENTED（当前只有命令行）。
## 11 仍未做（如实）

- 备份 CLI 的 `--apply` 落库已实现（§9）；播放进度与收藏仍不落库 —— NOT_IMPLEMENTED
- Windows 设置页「备份与还原」入口（口令框 → 预览 → 确认）—— NOT_IMPLEMENTED
- 本机进度/收藏的写入（来源与偏好已随 `--apply` 落库）—— NOT_IMPLEMENTED
- WebDAV 上传已实现（§10）；下载 / 列目录 / 删除仍 NOT_IMPLEMENTED
- Android 恢复计划器与 Windows 反向对照、真机与真 GUI —— DEVICE_UNVERIFIED / GUI_UNVERIFIED
- 口令强度无最小长度约束（沿用 Android 口径）；容器未做外部安全审计
