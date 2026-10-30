# W15 跨平台备份容器（Windows ↔ Android `.mhbackup` 互通）

> 状态：容器层 **EXECUTED_PASS**（Windows 1793/0、Android core/common 666/0、双向跨语言向量全绿）；
> 还原预览层（恢复计划器，纯函数零写入）**EXECUTED_PASS**（22 例 + 四条负向对照）；
> 导出采集层（本机来源/偏好 → 备份载荷，纯函数）**EXECUTED_PASS**（17 例 + 四条负向对照）；
> 备份命令行（`export` / `import` 预览）**EXECUTED_PASS**（13 例 + 四条负向对照，全量 MediaHub.Tests 1806/0）；
> 还原落库（`import --apply`：来源与偏好写 `settings.json`）**EXECUTED_PASS**（applier 12 例 + CLI 用例 17 例 + 四条负向对照，全量 MediaHub.Tests **1822/0**）；
> WebDAV 上传（`upload`：PUT + `--mkdir`，远端失败独立退出码 9）**EXECUTED_PASS**（上传器 11 例 + CLI 3 例 + 四条负向对照，全量 MediaHub.Tests **1836/0**）；
> WebDAV 下载（`download`：一次 GET + 声明长度预检 + 流式上限 + 原子落盘，远端失败同样退出码 9）**EXECUTED_PASS**（下载器 12 例 + CLI 3 例 + 四条负向对照，全量 MediaHub.Tests **1851/0**）；
> WebDAV 列目录（`list`：一次 PROPFIND Depth: 1、只读只列一层、默认只列 `.mhbackup`）**EXECUTED_PASS**（14 例 + 四条负向对照各恰好 1 红，全量 MediaHub.Tests **1872/0**）；
> WebDAV 删除（`delete`：默认只预览、零请求；`--confirm` 才先 HEAD / PROPFIND 确认存在再 DELETE；只删 `.mhbackup`）**EXECUTED_PASS**（18 例 + 四条负向对照各恰好 1 红，全量 MediaHub.Tests **1890/0**）；
> WebDAV 上传覆盖保护（`upload --no-overwrite`：`If-None-Match: *` 条件写，412 两种口径分开）**EXECUTED_PASS**（`BackupUploadTests` 21 例 = 原 14 + 7，四条负向对照各恰好 1 红，全量 MediaHub.Tests **1897/0**）；
> 跨语言实文件（电脑 `export` 真写出的 `.mhbackup` 由 Android 读、同一份字节由 Windows 再读；顺带修掉命令行 stdout 用系统代码页 936 的缺陷）**EXECUTED_PASS**（Android 6 例 / core:common **672** 例 0 失败 11 跳过；Windows 7 例 / MediaHub.Tests **1904/0**；1 条负向对照恰好 1 红）；
> 设置页入口、播放进度/收藏落库仍 **NOT_IMPLEMENTED**；端到端「手机备份 → 电脑还原仍可用」尚未接线 **NOT_RUN**。

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

- WebDAV **列目录 / 删除**、远端覆盖策略与冲突提示 —— NOT_IMPLEMENTED（下载已在 §11 实现，仍不做覆盖与删除）。
- 用真实 WebDAV 服务器做一次「上传 → 取回 → 本机还原」端到端 —— NOT_RUN（本机无可用远端，也不在无授权时启停外部服务）。
- 设置页「备份与还原」入口里的「传到 WebDAV」按钮 —— NOT_IMPLEMENTED（当前只有命令行）。
## 11 WebDAV 下载（W21）

### 11.1 缺口

§10 只解决了「把备份放到远端」。取回来仍要靠用户手动把文件从 WebDAV 客户端拖回本机，再 `import --file`；服务器上的文件名、大小与摘要对不对，命令行也给不出证据。同时 `windows/src/MediaHub.Providers.WebDav/WebDavApi.cs` 仍是**只读播放器客户端**（只发 `PROPFIND` / `OPTIONS` / range `GET`），没有可复用的「取整个文件」能力，也不该把播放器的凭据库与认证弹窗拖进命令行。

### 11.2 下载器（`windows/src/MediaHub.Backup/MhBackupWebDavDownloader.cs`）

- `DownloadAsync(source, userName, password, maxBytes = 0, handler = null, cancellationToken = default)`；`maxBytes == 0` 时用容器上限（`MhBackupSerializer.MaxFileBytes`，10 MiB）。
- **先判地址再联网**：非 http(s) ⇒ `NotHttp`；路径为空 ⇒ `MissingPath`；路径以 `/` 结尾 ⇒ `DirectoryTarget`（这三种都零请求，测试直接断言桩 handler 没被调用）。
- `GET` + `HttpCompletionOption.ResponseHeadersRead`：不把远端内容先读进内存再判大小。
- **声明长度预检**：`Content-Length > maxBytes` 立即 `TooLarge`，不读正文（`Content` 为 null，`DeclaredLength` 仍回原始声明值，便于用户知道差多少）。
- **读中计数**：按 81920 字节分块累计，累计超过 `maxBytes` 立刻 `TooLarge`（防「无 Content-Length 的流式响应」把磁盘/内存撑爆）。
- 读到 0 字节 ⇒ `Empty`；实际总长与声明不等 ⇒ `Truncated`（不静默接受半截文件）。
- 状态映射 `ReasonFor`：401/403 ⇒ `AuthenticationFailed`、404 ⇒ `NotFound`、413 ⇒ `TooLarge`、≥500 ⇒ `ServerError`、其余「远端拒绝这次下载（HTTP n）」。401/403 的文案是「账号或口令不对」，与 §10 上传侧同口径。
- 认证只在给了账号时发（`Basic base64(user:password)`）；不给账号就不带 `Authorization`，避免把匿名请求伪装成已登录。

### 11.3 命令行（`download`）

```
download --url <WebDAV 地址> --out <备份文件> [--user <账号>] [--password-env <变量名>] [--max-bytes <字节>] [--json]
```

- 口令只从环境变量读（缺省 `MH_WEBDAV_PASSWORD`，与备份容器口令 `MH_BACKUP_PASSWORD` 分开，一个变量只承载一种凭据）。
- 只做一次 `GET` 并写成 `--out` 指定的文件：**不列目录、不删远端文件**，取回后仍要 `import` 才会落地。
- 落盘是**原子写**：同目录 `*.tmp-<8 hex>` + `File.Move(..., overwrite: true)`，失败时不留半截文件（测试用 401 与超限两条路径断言目录里零文件）。
- 退出码沿用 §8：成功 `0`、用法 `2`、本地 IO `8`、**远端失败 `9`**；`--json` 给 `{command, ok, status, url, path, bytes, sha256, declaredBytes}`，失败时也给机器可读的 `status` 与 `reason`。
- 成功文本给「已下载备份 / 远端地址 / HTTP 状态 / 文件大小 / SHA-256」，并提醒这是加密容器、导入需要同一口令。

### 11.4 验证

- `BackupDownloadTests` **15 例**（下载器 12 + CLI 3）：带账号发 `Basic` 且回原字节、无账号不发认证、401/404/503 各自的原因、声明长度超限**在读取前**被拒、无声明长度的流式超限在读取中被截断、声明 100 实收 20 ⇒ `Truncated`、空 body ⇒ `Empty`、目录地址与非 http 地址零请求、CLI 写文件并回 `bytes`/`sha256`/`path`、CLI 401 ⇒ 退出码 9 且零文件、三种用法错误 ⇒ 2 且零请求、CLI `--max-bytes 1024` 对 4096 声明 ⇒ 9 且无半截文件。
- 四条负向对照（快照 `D:/mh-verify/w21-snap/`，每次从快照还原并 `sha256` 复核，日志 `D:/mh-verify/w21-neg-*.log`）：A 去掉声明长度预检 ⇒ **2 红**（下载器与被 CLI 路径共用的那条上限用例都退化成 `Truncated`）；B 把 `total != promised` 放宽成 `total > promised` ⇒ **1 红**；C 去掉目录地址预检 ⇒ **1 红**（会真的发请求）；D CLI 忽略 `--max-bytes` ⇒ **1 红**（超限用例变成成功下载）。
- 全量 `MediaHub.Tests`：**1851 / 0**（W20 的 1836 + 15），日志 `D:/mh-verify/w21-full-tests1.log`。

### 11.5 仍未做（本层之外）

- WebDAV **列目录 / 删除 / 覆盖策略** —— NOT_IMPLEMENTED（本轮只有一次 GET）。
- 用真实 WebDAV 服务器做「上传 → 取回 → 本机还原」端到端 —— NOT_RUN（本机无可用远端，也不在无授权时启停外部服务）。
- 设置页「备份与还原」入口里的「从 WebDAV 取回」按钮 —— NOT_IMPLEMENTED（当前只有命令行）。

## 12 WebDAV 列目录（W22）

### 12.1 缺口

上传（§10）与下载（§11）都要求调用方已经知道远端文件的完整地址；现实中备份文件是「手机传上去的、名字带时间戳」，
让人记住路径不现实。缺一个只读浏览：把目录里的文件列出来，再挑一个下载。

### 12.2 列目录器（`windows/src/MediaHub.Backup/MhBackupWebDavLister.cs`，零 NuGet 依赖）

- 一次 `PROPFIND`，`Depth: 1`，请求体只要四个属性：`displayname` / `resourcetype` / `getcontentlength` / `getlastmodified`。
- 只接受 `207 Multi-Status`（也容忍 `200`）；**正文根元素必须是 `multistatus`** —— 有些服务端出错时仍回 207 但正文是 HTML 或登录页，
  当成「空目录」会把服务端错误伪装成「远端没有备份」，所以根元素不对一律报「不是 WebDAV 列目录响应」。
- 名称优先 `displayname`，没有就取 `href` 最后一段并做百分号解码（中文文件名在多数服务端是编码过的）。
- 目录自己（`href` 等于请求的集合路径）不返回；返回项带 `IsCollection` / `Size`（缺失即 null）/ `LastModifiedUtc`。
- 星期写错的 `getlastmodified`（例如把周四写成 `Wed`）严格 RFC 1123 解析会整条失败，这里丢掉星期再试一次，日期仍可信。
- 失败原因固定：`NotHttp` / `AuthenticationFailed`（401、403）/ `NotFound`（404）/ `NotSupported`（405、501）/ `Corrupted` / `ServerError` / `Timeout` / `Network`。
- 只读、不递归、不删除、不改远端状态；超时 60 秒。

### 12.3 命令行（`list`）

- `list --url <WebDAV 目录地址> [--user <账号>] [--password-env <变量名>] [--all] [--json]`。
- 默认**只列 `.mhbackup`**（大小写不敏感），并给一行提示「加 `--all` 显示全部条目」；`--all` 连目录与其它文件一起列。
- 文本回执：`远端备份目录：…` + 每项 `名称 · 大小 · 修改时间`（目录标 `[目录]`）+ `共 N 项（其中 M 个 .mhbackup 备份文件）`。
- JSON：`{command:"list", ok, status, url, directories, files, backupFiles, entries:[{name,url,isCollection,bytes,lastModified}]}`。
- 远端失败 exit 9（与 upload / download 同码），参数错误 2；口令仍只从 `MH_WEBDAV_PASSWORD` 取。

### 12.4 验证

| 对照 | 改动 | 红 |
| --- | --- | --- |
| A | 删掉 `Depth: 1` 请求头 | 1（`ItSendsOnePropfindWithDepthOneAndBasicAuth`） |
| B | 删掉「根元素必须是 multistatus」守卫 | 1（`AnHtmlErrorPageIsReportedAsCorruptedInsteadOfAnEmptyDirectory`） |
| C | 删掉星期容错回退 | 1（`AWeekdayTypoInTheRemoteDateDoesNotLoseTheDate`） |
| D | 删掉默认列目录的 `--all` 提示行 | 1（`TheCommandLineListsOnlyBackupFilesByDefault`） |

- `BackupListTests` 14 例；全量 `MediaHub.Tests` **1872/0**（基线 1858 已含并行写者的 W6-113 +7，本轮 +14）。
- 四条对照每次从实现态快照（`D:/mh-verify/w22-snap/`）逐字节还原并复验：lister sha256 `1a6c48dd9e43e74668ea0c979ab09a04c45b926ee167ac9183a06323d9975fbd`、
  cli sha256 `7aae6020641f03d892c7adcaa07f9451c28038fa5682137f6a1e13389656dbbe`。
- 全部用例走桩 Handler（`ListStubHandler`）零联网：只钉请求形状、解析、状态映射与回执，不证明真实服务端可列目录。

### 12.5 仍未做（本层之外）

- 删除远端文件（不可逆，需要单独的策略与确认，不在本层做）。
- 递归列子目录、按时间排序、分页（大目录一次全收，服务端自行截断时无法察觉）。
- 真实 WebDAV 服务端联调 —— 本地只有桩 Handler，`SERVER_UNVERIFIED`。
- 设置页「备份与还原」入口（列表 + 下载 + 还原串起来）—— 属 GUI，`GUI_UNVERIFIED`。

## 13 WebDAV 删除（W23）

### 13.1 缺口

上传（§10）、下载（§11）、列目录（§12）之后，远端备份会越攒越多；而删除是**不可逆**动作，缺一个「默认什么都不删」的入口。
本轮不加批量、不加递归，只做「指名一个文件、默认先预览」的删除。

### 13.2 删除器（`windows/src/MediaHub.Backup/MhBackupWebDavDeleter.cs`，零 NuGet 依赖）

- **预览是默认**：`confirm=false` 时直接返回 `ConfirmRequired`，**一个请求都不发**（用例断言桩 handler 零调用）。
- **只删备份文件**：非 `.mhbackup` 需要显式 `allowNonBackupFile`；集合地址（路径以 `/` 结尾）、空路径、非 http(s) 在联网前就拒。
- **先确认再删**：先 `HEAD`；服务端不支持 HEAD（405/501）时退回 `PROPFIND` + `Depth: 0`（207/200 视为存在）；确认存在才发 `DELETE`。
  确认阶段 404 一律报缺失，不把「没删到」说成删除成功。
- **状态映射稳定**：401/403 ⇒ 认证、404 ⇒ 缺失、405/501 ⇒ 远端不支持删除、≥500 ⇒ 服务端出错、其余「远端拒绝这次删除（HTTP n）」；超时 60 秒，超时与连不上各有独立原因。

### 13.3 命令行（`delete`）

- `delete --url <WebDAV 文件地址> [--confirm] [--any] [--user <账号>] [--password-env <变量名>] [--json]`。
- 默认预览：文本「预览：不会删除任何东西 / 将删除：<url> / （只删 `.mhbackup`；其它文件需要 `--any`）/ 加 `--confirm` 才会先确认文件存在、再发 DELETE。」，退出码 **0**；JSON `mode:"preview"`、`deleted:false`。
- 只有 `--confirm` 才真的发请求；成功后文本「已删除远端备份 / HTTP 状态 / 提醒：远端删除不可逆；本机备份文件与备份容器口令不受影响。」，JSON 带 `checkedExistence`。
- 本地用法错误（缺 `--url`、非 http(s)、目录地址、非 `.mhbackup` 且未给 `--any`）由 `PreflightDeleteReason` 在联网前拦下，退出码 **2**，不落到远端失败码；远端失败仍是 **9**；口令同样只从 `MH_WEBDAV_PASSWORD` 读。

### 13.4 验证

| 对照 | 改动 | 红 |
| --- | --- | --- |
| A | 删掉「只删 `.mhbackup`」守卫 | 1（`OnlyBackupFilesAreDeletedWithoutTheAnyFlag`） |
| B | 把确认阶段的 404 当作存在、继续 DELETE | 1（`AMissingFileIsNotReportedAsDeleted`） |
| C | DELETE 返回 405 不再映射成「远端不支持删除」 | 1（`AServerThatRefusesDeleteIsReportedAsNotSupported`） |
| D | 删掉命令行预览里的「加 `--confirm` …」提示行 | 1（`TheCommandLinePreviewDoesNotTouchTheRemote`） |

- `BackupDeleteTests` 18 例（含 `Theory` 两行：目录地址、`file://` 地址）；全量 `MediaHub.Tests` **1890/0**（1872 + 18）。
- 四条对照每次从实现态快照（`D:/mh-verify/w23-snap/`）逐字节还原并复验：deleter sha256 `9be7daa1debfe5f08e96b5e248001f7471254d1393555ee219d2d11d8517c3d1`（7912 B）、
  cli sha256 `4690480b4f4721fac0e5c1646c8ef88568663358e5383c727f182dba24b8dfc3`（44014 B）；还原后复跑 18/0。
- 全部用例走桩 handler（`DeleteStubHandler`）零联网：只钉请求方法序列、`Depth`、鉴权头、状态映射与回执，**不证明真实服务端允许删除**。
- 已知未覆盖：真实 WebDAV 上的删除（`SERVER_UNVERIFIED`）、并发双端同删的竞态（只测了「确认后、DELETE 前被别人删掉 ⇒ 报缺失」这一种）。

### 13.5 仍未做（本层之外）

- 真实 WebDAV 端到端（上传 → 列目录 → 下载 → 删除）—— SERVER_UNVERIFIED
- 批量 / 递归删除、保留策略（N 份 / N 天）—— 不做：不可逆动作不做自动清理
- 设置页里的删除按钮与二次确认 —— GUI_UNVERIFIED

## 14 WebDAV 上传覆盖保护（W24）

### 14.1 缺口

上传（§10）成功即写远端：同名文件默认被覆盖，而命令行的目标文件名常被复用（带时间戳也可能撞），一旦传错口令或错载荷，就把上一份好备份盖掉了。删除不可逆，覆盖也近似不可逆。
本轮只加一个**显式**刹车，不改默认行为：不加 `--no-overwrite` 时仍然同名覆盖（用户常常就是要更新同一份备份）。

### 14.2 `If-None-Match: *` 与 412 语义

- **不用「先 PROPFIND 再 PUT」**：两次往返之间目标可能被别人创建（TOCTOU），改用条件写。
- `noOverwrite=true` 时请求带 `If-None-Match: *`（RFC 7232：仅在目标不存在时写入）；远端已有同名文件回 **412**，本端映射为 `Reasons.AlreadyExists`「远端已有同名文件，这次上传被拒绝（412）」，**不改远端**。
- 未开保护时收到 412（例如中间层强加前置条件）映射为 `Reasons.PreconditionFailed`；两种口径分开，不把「被保护住」说成「前置条件无缘无故失败」。
- 412 在 `ReasonFor` 之外单独分支；其余状态映射（认证 / 目录缺失 / 不接受 / 配额 / 服务端 / 超时 / 连不上）与 60 秒超时完全不变。
- `--mkdir` 建目录后的**重试 PUT 同样带条件头**，避免「第一次 409、建完目录第二次就无条件覆盖」。

### 14.3 命令行（`upload … [--no-overwrite]`）

- 默认仍是同名覆盖；`--no-overwrite` 才开启保护。
- 成功文本在 SHA-256 行后加「覆盖保护：已开启（远端已有同名文件时本次上传会被拒绝，不改远端）。」；`--json` 的成功与失败回执都带 `noOverwrite` 布尔。
- 失败退出码不变：远端拒绝 **9**（`ExitRemote`）、本地读文件失败 8、本地用法错误 2；口令仍只从 `MH_WEBDAV_PASSWORD` 读，不接受命令行口令。

### 14.4 验证

| 对照 | 改动 | 红 |
| --- | --- | --- |
| A | 用法文本与说明行同时去掉 `--no-overwrite`（首版只删用法行**无效**：说明行里还有同一 token） | 1（`UsageTextMentionsTheNoOverwriteSwitch`） |
| B | 成功文案改字（「覆盖保护：开启。」） | 1（`CommandLineNoOverwriteSaysSoOnSuccessAndInJson`） |
| C | 命令行把 `noOverwrite` 写成常量 `false` | 1（`CommandLineNoOverwriteSendsTheHeaderAndReportsTheExistingFile`） |
| D | 无条件发送 `If-None-Match: *` | 1（`APlainUploadSendsNoConditionalHeader`） |

- `BackupUploadTests` 21 例（原 14 + 7：条件头有无、412 两种口径、命令行头部与回执、用法文本）；全量 `MediaHub.Tests` **1897/0**（1890 + 7）。
- 四条对照每次从实现态快照（`D:/mh-verify/w24-snap/`）逐字节还原并复验：uploader sha256 `8820cbba0fc93a5b5f5c9af2bd9cf1aab5466b82b434d8291119032191617755`（8318 B）、
  cli sha256 `dfe02d51e5b5c7e5a7c77ebd0ded29cf25fdbc7ab2beca77083a30c1522867d9`（44502 B）；还原后复跑 21/0。
- 全部走桩 handler（`StubHandler`）零联网：只钉「带/不带条件头」「412 的两种说法」「回执与退出码」，**不证明真实服务端支持条件写**。
- 另一条测试自身的坑：`upload --help` 不是帮助路径（`Run` 只把第一个参数当命令），要断言用法文本得用顶层 `--help`，否则拿到的是「缺 `--file`」的退出码 2。

### 14.5 仍未做（本层之外）

- 真实 WebDAV 服务端是否支持条件写、412 回包是否规范 —— SERVER_UNVERIFIED（有的实现忽略条件头或换码，未经实测不假设）
- 「远端已有则改名另存」与「保留最近 N 份」策略 —— 不做：命名与保留交给调用方
- 上传前的预览与确认（大小 / 来源数 / SHA-256 / 覆盖提示）—— GUI_UNVERIFIED


## 15 跨语言实文件：电脑导出 → 手机导入（W25）

### 15.1 缺口

§4 的向量是仓库外生成器用固定 salt/nonce 拼出来的容器，证明的是「同样的输入产生同样的字节」；
用户要的是**电脑真导出的那一份文件，手机打开就能用**。两者差在：生成器不走 CLI、不读真实的 `settings.json`、不经过采集层的类型/身份/偏好映射。
本轮用命令行真导出一份文件，把这两端都钉住。

### 15.2 被测文件的来历（不是测试自己拼的）

输入 `settings.json`（仓库外 `D:/mh-verify/w25/settings.json`）四行来源：

| # | Type | 显示名 | 地址 | 账号 | SyncSourceId |
| --- | --- | --- | --- | --- | --- |
| 1 | `Emby` | Home Emby | `https://media.example:8920/emby/` | `user-A` | `sync-abc` |
| 2 | `Jellyfin` | Jelly | `http://jelly.example:8096` | （空串） | — |
| 3 | `WebDav` | Team DAV | `https://dav.example/dav/films` | （null） | — |
| 4 | `Local` | 我的本地文件夹 | `C:\Media` | （null） | — |

另给 `history.json` 两条（一条有进度、一条位置 0），命令行因此报 `skippedHistoryEntries: 1`。
导出命令（口令只走环境变量）：

```powershell
$env:MH_BACKUP_PASSWORD = "跨平台备份-测试口令-2026"
dotnet windows/src/MediaHub.BackupCli/bin/Debug/net10.0/MediaHub.BackupCli.dll export `
  --settings D:/mh-verify/w25/settings.json --history D:/mh-verify/w25/history.json `
  --out D:/mh-verify/w25/windows-export.mhbackup --json
```

产物：`2743` B，sha256 `0225ab7c794e7f5f74714d2adb3ca38d0fc297b59c3dbcee8eb6824206c62666`，
`sections=["servers","preferences"]`、`servers=4`、`skipped=[]`。**同一份字节**落到两处（逐字节相同）：

- Android 读取：`core/common/src/test/resources/backup/windows-cli-export.mhbackup`
- Windows 复读：`windows/tests/assets/windows-cli-export.mhbackup`

### 15.3 Android 侧断言（`BackupWindowsCliFileTest`，6 例）

| 用例 | 钉住什么 |
| --- | --- |
| `theWindowsCliExportOpensOnAndroidWithItsManifestIntact` | 用口令解开 Windows 产物 ⇒ `Ok`；`appVersion=windows-0.1.0`、`minimumReaderVersion=2`、`includedSections=[servers,preferences]`、`recordCounts={servers:4,preferences:1}`、`createdAtEpochMs>0` |
| `theServersKeepTheirIdentitiesAndTheBlankAccountStaysNoAccount` | 四行来源按类型取到；`sync-abc` 原样保留、空白账号导出为 **null**（Android 身份比较里 null ≠ 空串）、无 `SyncSourceId` 的两行是 `win-` + 16 位十六进制（20 字符）、WebDAV 端点 URL 原样 |
| `theLocalFolderSourceCarriesNoEndpointAndNoAccount` | `LOCAL` 来源端点为空（本地路径在别的设备上没有意义） |
| `thePreferencesKeepOnlyTheFieldsWithRealEquivalents` | 偏好只带三对有等价物的字段：`1.5f` / `autoPlayNextEpisode=false` / `showPlayerInfoOverlay=true`，`subtitleSizeSp` 保持格式默认 18 |
| `theComputerExportDoesNotSmugglePlaybackProgressOrFavorites` | `progress` 与 `favorites` 都为空 —— Windows 按文件路径记账、备份按「来源+条目」记账，没有可靠映射就不导出 |
| `theSameFileWithAnotherPasswordDoesNotOpen` | 同一份文件换口令 ⇒ `AuthenticationFailed`（不是「解析失败」） |

跑法：`gradlew.bat :core:common:testDebugUnitTest`；本轮 core:common **672 例 / 0 失败 / 11 跳过**。

### 15.4 Windows 侧断言（`BackupWindowsCliArtifactTests`，7 例）

前 6 例用 `MhBackupSerializer.Import` 读同一份文件，字段期望与 §15.3 完全一致（两棵树各读一遍，任一侧语义漂移都会红）；
第 7 例是**进程级**断言：`dotnet <MediaHub.BackupCli.dll> --help` 把 stdout 重定向后按 UTF-8 解码，必须没有替换字符 `U+FFFD`，且含「用法：」「export --settings」。

跑法：`dotnet test windows/tests/MediaHub.Tests --no-restore`；本轮 **1904/0**（上一轮 1897 + 7）。

### 15.5 本轮修掉的真缺陷：命令行 stdout 不是 UTF-8

被重定向时（脚本 / CI / 管道），`MediaHub.BackupCli --json` 的中文与 JSON 都按**系统代码页 936** 输出：
原样字节开头是 `MediaHub ±¸·Ý¹¤¾ß£¨Windows ¶Ë£©`，按 UTF-8 解出来整段是替换字符 —— 任何解析 `--json` 的脚本都会失败。
修法：`windows/src/MediaHub.BackupCli/Program.cs` 在调用命令层之前设 `Console.OutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)`（无 BOM），
设置编码会同步替换 `Console.Out`，命令层仍用注入的 `TextWriter`，单元测试不受影响。

**负向对照 A**：把这一行删掉再跑同一套件 ⇒ **恰好 1 红** `The_command_line_writes_utf8_when_its_output_is_redirected`（6 通过 / 1 失败）；
从实现态快照 `D:/mh-verify/w25-snap/Program.cs` 逐字节还原（sha256 `0ec4c000c66023d40c22884d618171b0f4da35f8cf25588d8484796cd46ec477`，比较相等）后复跑 **7/7**。

### 15.6 仍未做（如实）

- Android 的**还原**只到「容器 + 载荷能读」，没有走 `BackupRepository.prepareRestore/buildPreview/restore` 落库 —— 本层之外 **NOT_RUN**；
- 设置页「备份与还原」入口 **NOT_IMPLEMENTED**（需要 GUI 窗口）；
- 播放进度与收藏仍不落库（原因见 §9.2 / §15.3）；
- 真实 WebDAV 服务端只做过桩断言，**SERVER_UNVERIFIED**。

### 15.7 重做这份证据的步骤

1. 改 Windows 采集层或命令行后，按 §15.2 的命令重新导出（口令自行设定，不写进仓库）；
2. 把新产物同时覆盖到 §15.2 的两个路径，确认两处 sha256 相同；
3. 更新本节的字节数/sha256/`sections`/`counts` 与两侧期望值；
4. 跑 §15.3 与 §15.4 的测试；两份文件是**被读的权威字节**，测试不会重新生成它们。

## 16 播放进度与收藏的写回去向（W26）

`import` 早先只给一句「播放进度与收藏没有落库」，用户无法判断每条记录是**没人能写**、**需要登录**、还是**根本没被选中**。本轮先把这层判断做出来并可核对，而不是直接声称「已经写回」。

### 16.1 为什么不能「直接写本机」

- Windows 的观看记录是 `PlaybackHistoryStore`，**按本机文件路径**记账；备份里的进度是**（来源, 条目）**键（`MhBackupProgressDto.serverBackupId + itemId`）。两者没有可靠映射，猜一个路径等于写错行。
- `MhBackupExportCollector` 因此**不导出**播放进度（只把 `SkippedHistoryEntries` 计数）；电脑导出的备份里 `Progress` 恒空。
- 服务端进度的权威在服务端：Emby / Jellyfin 的恢复位置要登录后经 provider API 写（`POST /Users/{uid}/Items/{id}/UserData`），本命令**不执行**任何网络写回。

### 16.2 五类去向与稳定码

`windows/src/MediaHub.Backup/MhBackupWriteOutlook.cs`（纯函数、零 IO、零网络）：

| `KindCode` | 含义 | 判据顺序 |
| --- | --- | --- |
| `orphan` | 记录指向的来源不在备份里 | 计划裁决 `Orphan`（先于其它） |
| `not-selected` | 来源冲突 / 本机更新等，计划已跳过 | 裁决不是 `Apply`，原因沿用计划器的原话 |
| `local-path-key` | 本地文件夹条目：条目 id 不是本机文件路径 | 进度且类型 `LOCAL` |
| `server-api` | 该类型有服务端接口，需登录后写回 | `EMBY` / `JELLYFIN` |
| `unsupported-type` | 该类型没有写回接口 | 其余（含类型未知） |

来源类型先 `Trim()` 再 `ToUpperInvariant()`；类型表取自 `payload.Servers` 的 `BackupId → Type`，查不到就是「（类型未知）」。收藏没有 `local-path-key`：`LOCAL` 收藏同样归 `unsupported-type`（收藏从来不在路径上记账）。

### 16.3 命令行输出

`import` 的预览与 `--apply` 回执都多出两行（只在对应总数 > 0 时出现，分隔符固定为 ` · `）：

```
播放记录 N 条写回方式：服务端接口 a 条（需登录）· 本机路径键无法映射 b 条 · 该类型没有接口 c 条 · 未选中 d 条 · 孤立引用 e 条
收藏 N 条写回方式：服务端接口 a 条（需登录）· 该类型没有接口 c 条 · 未选中 d 条 · 孤立引用 e 条
```

`--json` 的两条回执都带 `writeOutlook`（`progressTotal` / `progressServerApi` / `progressLocalPathKey` / `progressUnsupportedType` / `favoriteTotal` / `favoriteServerApi` / `favoriteUnsupportedType` / `notSelected` / `orphan`）与 `writeOutlookLines`。末句从「播放进度与收藏没有落库」改为「播放进度与收藏本命令不写（上面的方式说明写清了各自去向）」——**不把「没写」说成「已写」**。

### 16.4 验证

- 新 `MhBackupWriteOutlookTests` 9 例：类型映射（含小写 `jellyfin`）、`LOCAL` 进度、五种无接口类型、收藏两种口径、冲突沿用计划原因、本机更新跳过、缺失来源为孤立、两行文案逐字、空载荷不出行。
- `BackupCliTests` +1 例 `ImportPreviewReportsWherePlaybackAndFavoritesWouldGo`：手工构造带 manifest 的载荷（`MhBackupSerializer.Export` 要求 `manifest` 与 `includedSections` / `recordCounts` 一致）导出真实容器，再跑 `import --json` 与文本两条路径。
- 全量：`MediaHub.Tests` **1914 / 0**（基线 1904 + 10）；`dotnet build windows/MediaHub.Windows.sln -p:Platform=x64 --no-restore` **0 错误**（21 条警告均为既有测试工程告警）。
- 负向对照（实现态快照 `D:/mh-verify/w26-impl-outlook.cs`，sha256 `0d0430852c9592fad4c0afac67453a6b71a07a9c10767e3090b8bee874784bfa`，逐字节还原后相等）：A `LOCAL` 进度改判 `server-api` ⇒ **2 红**（该单例 + 文案行）；B `WEBDAV` 也当 `server-api` ⇒ **3 红**（两类无接口单例 + 文案行）；C 去掉孤立分支 ⇒ **2 红**（孤立单例 + 文案行）。三处都同时打到逐条与汇总两层。

### 16.5 仍未做（如实）

- **真正的写回还没做**：`POST /Users/{uid}/Items/{id}/UserData` 与 `SetFavoriteAsync` 的登录后调用 **NOT_IMPLEMENTED**，所以 `server-api` 只是「知道该走哪条路」，不是「已经写成功」；`IMPLEMENTED_UNVERIFIED` 仅指这一层分类与文案。
- 进度 / 收藏的**本机**落库仍不做（无可靠键，见 §16.1）。
- 设置页「备份与还原」入口仍是 GUI_UNVERIFIED。

## 17 服务端播放位置写回接口（W27）

§16 已把每条记录的去向说清楚，但「知道该走 `POST /Users/{uid}/Items/{id}/UserData`」与「能写」是两件事。本轮在 provider 层补上写口，为还原层接线做准备；**备份命令仍不执行写回**。

### 17.1 为什么单开一个接口

写入没有塞进 `IProviderProgress`，而是新增 `IProviderProgressWrite`（`windows/src/MediaHub.Providers/ProviderContracts.cs`）：

- `IProviderProgress` 表示「播放中上报 + 读续播」，能读续播的 provider 不一定能保存**外部带来的**位置；
- `ProviderHandle.ProgressWrite` 为 `null` 即不宣称可写，调用方据此判断，而不是看 `RuntimeCapabilities.Progress`；
- `IProviderFavorites.SetFavoriteAsync` 早已存在（`ProviderContracts.cs`），收藏写回不需要新接口。

### 17.2 路由与请求体

`MediaBrowserApi.UpdateUserDataAsync`：`POST /Users/{userId}/Items/{itemId}/UserData`，JSON 体只有 `PlaybackPositionTicks`（long，100 ns 单位），沿用 `NewRequest` 的方言身份与 token 头，`SendJsonAsync` 负责 401 → `AuthExpired`、429 → `RateLimited` 等映射。Emby 与 Jellyfin 共用该路由（Jellyfin 的根是 `/`，Emby 是 `/emby`，由方言前缀处理）。

### 17.3 返回的是服务端随后报告的位置

`IProviderProgressWrite.SetPlaybackPositionAsync` 返回 `Task<long>`，语义是**服务端接受后存下的位置**，不是请求值：

- 响应带 `PlaybackPositionTicks` ⇒ 原样返回；
- 响应省略该字段 ⇒ 回读 `GET /Users/{uid}/Items/{id}` 的 `UserData.PlaybackPositionTicks`；
- 回读仍无 ⇒ `0`（服务端无存储位置）。

理由与收藏同口径：HTTP 200 只证明请求被接受；「接受后丢弃」若回成请求值，还原层会虚报已保存。

### 17.4 mock 刻意不过续播下限

`MockMediaServer` 新增 `Users/{uid}/Items/{id}/UserData` 的 POST 分支（未知 id → 404、坏 JSON → 400、正常时把位置写进该条目的 `UserData` 并回 `UserDataJson`），并有 `SetUserDataWritesIgnored(true)` 模拟「收下但丢弃」、`OmitPlaybackPositionFromUserDataResponse` 模拟「回执省略字段」。这条保存口**不套 `MockResumeFloor`**：服务端存什么就是什么，套上客户端续播规则会比真服更严，反而掩盖「接受后丢弃」的语义。

### 17.5 验证

- `ProviderPlaybackWriteTests` 6 例：路由与请求体（POST `/emby/Users/user-1/Items/item-003/UserData`、`PlaybackPositionTicks` 精确、无 token/secret、不发 `FavoriteItems`）；「服务端丢弃」时回旧值（写 999 回 300000000），放开后写 222 回 222；回执省略字段时回读条目（写 42 回 42，POST 后必有 GET）；未知条目 → `ProviderErrorCode.NotFound`；过期 token → `AuthExpired`；Jellyfin 同路由（根为 `/`）。
- `dotnet build windows/MediaHub.Windows.sln -p:Platform=x64 --no-restore`：0 错误、21 警告（均为既有测试工程告警）。全量 `MediaHub.Tests` **1920 / 0**（基线 1914 + 6）。
- 负向对照（实现态快照 `D:/mh-verify/w27-impl-prov.cs` sha256 `3fd1a61f…`、`w27-impl-api.cs` sha256 `1bf87636…`，逐字节还原后相等）：A 回执改回请求值（纯回声）⇒ **恰好 1 红**「服务端丢弃时回旧值」；B 回读分支改成恒 `0` ⇒ **恰好 1 红**「回执省略字段时回读」；C 路由去掉 `/UserData` ⇒ **4 红**（路由是四条用例的共同前提，另两条 404 / 401 仍绿）。

### 17.6 仍未做（如实）

- **还原层未接线**：`MhBackupRestoreApplier` 仍未调用 `ProgressWrite` / `SetFavoriteAsync`，所以 `import --apply` 依然不写进度与收藏；
- **真服未验证**：以上全部是 loopback mock 证据，真实 Emby / Jellyfin 写回 —— SERVER_UNVERIFIED；
- 登录态、令牌过期重登、服务端拒绝写的用户提示均未接。

## 18 跨语言实文件：手机导出 → 电脑与手机都读得懂（W28）

### 18.1 为什么补这条

§15（W25）只钉了「电脑导出 → 手机导入」。反方向没有任何真文件证据：Android 的 `prepareRestore` / `restore` 虽然早就实现了落库，但此前只用内存里的假数据源测过，「生产导出 → 真实 Room 清空 → 生产恢复 → 从库里读回」这条链没有一条端到端用例。另外确认过收藏两端都不导出（Windows 的 `MhBackupExportCollector.cs:229` 写空列表，Android 的 `buildPayload` 里 `favoriteDtos = emptyList()`），所以可携数据 = 来源 + 偏好（Android 另带播放进度）。

### 18.2 样本怎么来、落在哪

`feature/settings/src/test/kotlin/com/mediahub/feature/settings/backup/BackupAndroidExportRoundTripTest.kt`（Robolectric `@Config(sdk=[34])`，真 `AppDatabase` in-memory + 真 `ProductionBackupDataSource` + 真 `BackupRepository`）先种 4 个来源、2 条播放进度、一套偏好，再调生产 `exportBackup`，把字节写到 `feature/settings/build/cross-platform/android-export.mhbackup`：**3271 B**，sha256 **d6612bc201466cd1ec5df408eaec2d5838e87d45053291f85c690a55264c68b7**。

同一份字节落三处：模块产物目录、`core/common/src/test/resources/backup/android-export.mhbackup`、`windows/tests/assets/android-export.mhbackup`（`MediaHub.Tests.csproj` 用 `Content` + `CopyToOutputDirectory=PreserveNewest` 拷到输出）。三处逐字节相同；任一漏拷在 CI 上是找不到资产（KeyNotFound），不是静默跳过。

### 18.3 两侧各读一遍

Android 6 例 `core/common/src/test/kotlin/com/mediahub/core/common/backup/BackupAndroidExportFileTest.kt`：manifest 的格式/读取版本、`appVersion = android-0.1.0`、sections = servers/progress/preferences、counts 4/2/1、created>0；4 个来源身份（EMBY 通用账号 + 默认标记 + 端点 `https://media.example:8920/emby/`；JELLYFIN 的 `username` 为 null、sortOrder 1；WEBDAV；LOCAL 端点空）；2 条进度（item-001 540000/1440000、暂停、标题「第一集」、EPISODE）；偏好（MEDIA3、1.5f、22sp、自动连播关、播放器信息浮层开、字幕颜色 0xFF00FF00）；`favorites` 空且 sections 无 favorites；换口令得到 `AuthenticationFailed`。

Windows 7 例 `windows/tests/MediaHub.Tests/BackupAndroidExportArtifactTests.cs`：同样断言 manifest、4 个来源、2 条进度、偏好，另加两层语义 —— `MhBackupRestorePlanner.Plan(payload, 空本机来源, ReplaceSelected)` 给出 4 个 New、2 条进度 Apply、orphan 0、`IdRemapping` 恒等；`MhBackupWriteOutlooks.Classify` 给出 2 条进度全 `server-api`（理由含「需要登录后调用服务端接口」）、`local-path-key` 为 0、`FavoriteTotal` 为 0。

真 Room 往返 2 例（同一个 `BackupAndroidExportRoundTripTest.kt`）：导出 → `db.clearAllTables()` + 偏好 reset 回默认 → `prepareRestore`（能解开、2 条进度、无孤立）→ `buildPreview(REPLACE_SELECTED)`（4 个新来源、2 条进度待写、偏好将还原）→ `restore(replaceConfirmed = true)` → `readSnapshot()` 断言 4 个来源（别名/账号/note「客厅」/默认标记/sortOrder/端点）、2 条进度、偏好逐项等于导出值，并断言白名单外两段（`playerVisualEffects`、`professionalInfo`）保留本机默认（依据 `BackupRepository.kt:330-333`：还原时只 copy 这两段本机值）。第二例从同一个真库再导出一次，并断言能重新解开（4 sources / 2 progress / 偏好非空、sections 三键）。

### 18.4 结论（planner 与 writeOutlook 侧）

Android 导出的进度在 Windows 计划里全部 Apply、且全部归 `server-api`：备份里只有服务器类型（EMBY/JELLYFIN）的进度，没有 LOCAL 进度的可携样本，所以 `local-path-key` 为 0。这条断言同时钉住「Windows 不会把服务端进度假写成自己的本机路径键」。收藏两侧都空，`FavoriteTotal` 为 0。

### 18.5 验证与证据

- `:feature:settings:testDebugUnitTest` **427 / 0**（原 425 + 2）；
- `:core:common:testDebugUnitTest` **678 / 0 / 11 skipped**（672 + 6）；
- `MediaHub.Tests` **1927 / 0**（1920 + 7）。
- 本轮**零生产代码改动**（只加测试、样本与 csproj 资产行），故不适用「每处行为改动配恰好 1 红」；新用例钉住的是 W19/W25 既有行为。要让它们变红必须改生产导出/恢复/容器代码，那属于另一轮。
- 真机（on-device DB）与真 GUI 未跑：DEVICE_UNVERIFIED / GUI_UNVERIFIED。

### 18.6 仍未做（如实）

- 收藏跨端仍不可携（两端导出都写空列表）；
- 真 Room 往返用的是 in-memory DB，设备上的磁盘库与真实账户切换未跑 —— DEVICE_UNVERIFIED；
- 进度/收藏的真正服务端写回仍要 GUI 入口 + 登录态（§17 的写口已就绪，还原层未接线）。

## 19 仍未做（如实）

- 备份 CLI 的 `--apply` 落库已实现（§9）；播放进度与收藏的**去向**已逐条说明（§16），provider 层写口已实现（§17），但**还原层仍未接线** —— IMPLEMENTED_UNVERIFIED（真服 SERVER_UNVERIFIED）
- Windows 设置页「备份与还原」入口（口令框 → 预览 → 确认）—— NOT_IMPLEMENTED
- 本机进度/收藏的写入（来源与偏好已随 `--apply` 落库）—— NOT_IMPLEMENTED
- WebDAV 上传（§10，含覆盖保护 §14）、下载（§11）、列目录（§12）、删除（§13）均已实现；**真实服务端联调未做** —— SERVER_UNVERIFIED
- Android 恢复计划器与 Windows 反向对照已由 §18（W28）补上：同一份手机导出字节，Android 真 Room 往返 + Windows 计划器各读一遍；真机与真 GUI 仍未跑 —— DEVICE_UNVERIFIED / GUI_UNVERIFIED
- 口令强度无最小长度约束（沿用 Android 口径）；容器未做外部安全审计
