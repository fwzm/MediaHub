# W6-7 —— SyncCli：无桌面环境下的跨设备同步端到端证据

- 状态：**已交付**（W6-7 产出：14 项验收检查 + 36 例进程内测试）
- 日期：2026-10-23
- 前置：[ADR-S001](./adr/ADR-S001-source-sync-contract.md)（信封契约）、[ADR-S004](./adr/ADR-S004-delta-sync.md)（增量与冲突）、[ADR-S005](./adr/ADR-S005-archive-encryption.md)（加密归档）、[ADR-S006](./adr/ADR-S006-credential-resealing.md)（凭据重封）
- 实现：`windows/src/MediaHub.SyncCli/`（`MediaHub.SyncCli.csproj`、`CliParser.cs`、`CliInvocation.cs`、`CliExitCode.cs`、`CliJson.cs`、`SyncCliRunner.cs`、`SyncCliProgram.cs`、`Program.cs`）
- 测试：`windows/tests/MediaHub.Tests/SyncCliTests.cs`（36 例）
- 验收脚本：`windows/tools/acceptance/w6/sync-cli-e2e.ps1`（14 项检查）

## 问题

W6-1 ~ W6-6 把同步能力全部做在 `MediaHub.Sync` 库里，但此前唯一的真实使用路径是 WinUI App：需要桌面会话、需要一台可连的服务器、需要人工点按。于是「两台设备之间到底能不能同步」这个结论，只能靠单元测试的推断——

- 单元测试可以证明 `Plan` 返回了什么，却证明不了「用户真的能从 A 导出、在 B 导入」；
- App 是 WinUI 进程，跑它需要交互桌面，验收成本高且会与构建、播放器争用机器；
- GUI 自动化（`sync-page-gui.ps1`）验的是界面，不是同步语义，两者不能互相替代。

缺口不是功能，是**证据的可获得性**：需要一条能在纯终端里跑、不依赖桌面、不依赖服务器的真实端到端路径。

## 决策

### D1：命令行是同步库的第二个消费者，不引入任何新同步逻辑

`SyncCli` 只做三件事：解析参数、调用 `MediaHub.Sync` 既有类型、把结果渲染成文本或 JSON。导出走 `SourceSyncSession.Export`，导入走 `SourceSyncSession.Import/ImportPlain`，规划走 `SourceSyncSession.PlanRound`，凭据校验走 `WindowsDpapiProtector`——一行同步语义都不复制。

反面后果：如果这里出现了「CLI 自己算冲突」「CLI 自己归一化地址」，那么终端里看到的结论就不再代表 App 的行为，这条证据立刻失效。

### D2：退出码是脚本可判定的契约，不靠解析文本

| 退出码 | 含义 | 典型触发 |
| --- | --- | --- |
| 0 | 成功 | 导出完成；导入应用成功且无待确认项；`secret-check` 匹配 |
| 1 | 用法错误 | 缺参数、未知选项、`--secrets` 与 `--passphrase` 同时出现 |
| 2 | 输入文件问题 | 输入不存在、JSON 解析失败、store 不可读、`plan` 被喂了归档 |
| 3 | 需要确认或存在冲突 | 计划里有 `requiresApproval`（未知类型、字段冲突、墓碑） |
| 4 | 归档或凭据错误 | 口令错误、头部损坏、KDF 未知、DPAPI 失败 |

分类集中在 `CliExitCode.ClassifyReason`：`ArchiveError` 与 `SecretError` 的全部稳定码映射到 4，`unsupported-version:` / `unknown-kdf:` 前缀映射到 4，其余映射到 2。契约由 `SyncCliTests.ClassifyReason_MapsFailuresToDocumentedExitCodes` 钉住（9 组）。

### D3：`--json` 时 stdout 只有一个 JSON 文档，诊断信息只走 stderr

脚本要能 `| ConvertFrom-Json` 直接吃下输出，因此告警（`warning: ...`）与错误（`error <code>: ...`）一律写 stderr；stdout 在 `--json` 下始终是单个文档。这一点由 `Run_JsonModeLeavesExactlyOneDocumentOnStdout` 与 `Run_WarningsGoToStderr` 双重钉住。

### D4：`import` 默认 dry-run

不带 `--apply` 的导入只做计划、不写盘，退出码按是否有待确认项给出。破坏性动作必须显式请求，与 ADR-S004「冲突逐条交用户选择」一致；`Run_ImportWithoutApplyTouchesNothing` 断言 dry-run 连 store 文件都不创建。

### D5：凭据只在内存里出现，且只以 base64 传入命令行

`--secrets` 接受 `{"<syncSourceId>":"<base64 明文>"}`，进程内立即用本机 DPAPI 封成 `SecretEnvelope`，随后只以封块形式进入信封与本地 store。任何输出（stdout/stderr/JSON/store/日志）都不得回显明文或其 base64；`secret-check` 只输出 `scheme` / `scope` / `matched` / `blobSha256`。

反面后果：把明文打进日志或证据文件，等于把凭据复制到一台机器的普通文件里，ADR-S006 的整条防线作废。

### D6：验收脚本不启动 App、不连服务器、不碰发布产物

`sync-cli-e2e.ps1` 唯一启动的进程是 `dotnet <MediaHub.SyncCli.dll>`，两个「设备」是两个独立的临时目录，证据写到被 `.gitignore` 忽略的 `windows/tools/acceptance/evidence/w6-sync-cli/`。这样它可以在任何机器、任何时刻跑，不会与 GUI 验收或构建抢锁。

## 用法

```
dotnet run --project windows/src/MediaHub.SyncCli -- <command> [options]
# 或针对已构建产物：
dotnet windows/src/MediaHub.SyncCli/bin/Debug/net10.0/MediaHub.SyncCli.dll <command> [options]
```

| 命令 | 作用 | 允许的选项 |
| --- | --- | --- |
| `export` | 把本机来源导出为信封（`--passphrase` 时加密为 `.mhsync`） | `--root`、`--out`、`--passphrase`、`--secrets`、`--deletions`、`--device`、`--json` |
| `import` | 读取信封并合并（默认 dry-run，`--apply` 才写盘） | `--root`、`--in`、`--passphrase`、`--baseline`、`--device`、`--apply`、`--json` |
| `plan` | 只算三桶计划，不写盘；拒绝归档输入 | `--root`、`--in`、`--baseline`、`--device`、`--json` |
| `secret-check` | 校验本机能否解开封存凭据（比较明文） | `--root`、`--id`、`--expect`、`--json` |

- `--root`：来源 store 所在目录（默认 `%LOCALAPPDATA%\MediaHub`）。
- `--deletions`：JSON 数组，元素 `{"syncSourceId":"...","recordRevision":N,"deletedAtEpochMs":N}`；墓碑只提议，绝不自动删除。
- `--baseline`：一个信封 JSON 的路径，脚本会从中建立 `SourceSyncBaseline` 做增量规划。
- `--device`：覆盖信封里的 `deviceId`（默认取机器名）。

### JSON 形状

- `export`：`command/root/path/encrypted/deviceId/snapshotId/sourceCount/deletionCount/canSendSecrets/sealedSecretCount/warningCount/warnings[]/bytes`
- `import`：`command/dryRun/applied/succeeded/root/in/appliedCount/requiresApprovalCount/skippedCount/counts{apply,requiresApproval,ignored}/entries[{syncSourceId,action,reason,serverId,displayName,requiresUserApproval}]`
- `plan`：`command/dryRun/applied/root/in/hasWork/counts/apply[]/requiresApproval[]/ignored[]`，条目含 `kind/direction/reason/localRevision/remoteRevision/conflictingFields[]`
- 失败：`{"ok":false,"error":{"code":"<稳定码>","message":"..."}}`

### 示例

```
# 设备 A：导出（明文）
dotnet MediaHub.SyncCli.dll export --root D:\devA --device pc-a --json

# 设备 B：先看计划，再落盘
dotnet MediaHub.SyncCli.dll plan   --root D:\devB --in D:\devA\sync-envelope.json --json
dotnet MediaHub.SyncCli.dll import --root D:\devB --in D:\devA\sync-envelope.json --apply --json

# 加密导出 / 导入
dotnet MediaHub.SyncCli.dll export --root D:\devA --passphrase "correct horse battery staple" --json
dotnet MediaHub.SyncCli.dll import --root D:\devB --in D:\devA\sync-envelope.mhsync --passphrase "correct horse battery staple" --apply --json
```

## 退出码与 `--json` 的组合

退出码永远反映真实结果，JSON 只补充细节；两者不互相替代。脚本判定「是否成功」只看退出码，判定「为什么」再看 `error.code` 或 `counts`。

## 与 App 的关系

- App **不调用** SyncCli：它是同一个库的独立消费者，App 的同步路径仍然走 `SourceSyncService`（W6-2c）。
- 两者共享 `MediaHub.Sync`，所以 CLI 里的行为就是库的行为；差异只可能来自调用参数，不可能来自同步语义。
- CLI 不出现在 App 的运行链路上，删掉它不影响产品功能；它是**证据工具**，不是功能入口。

## 安全边界

| 项 | 约束 |
| --- | --- |
| 明文凭据 | 只在进程内存里存在；入口是 base64 参数，出口只有封块 |
| 日志 | 参数里 `--secrets` / `--passphrase` / `--expect` 的值在脚本 transcript 中一律 `<redacted>` |
| 证据目录 | `windows/tools/acceptance/evidence/` 已被 `.gitignore` 忽略 |
| 归档 | 归档层永不携带凭据：`--secrets` 与 `--passphrase` 组合直接报用法错误 |
| 跨机凭据 | 导入必须用本机 DPAPI 重封；外来 blob 解不开 → 整轮失败且零写入 |

## 验收脚本

`windows/tools/acceptance/w6/sync-cli-e2e.ps1`：两个临时根目录模拟两台设备，14 项检查：

| # | 检查 | 钉住的规则 |
| --- | --- | --- |
| 1 | `cli-binary-is-available` | 产物存在（缺失时按需构建 SyncCli 项目） |
| 2 | `device-a-holds-three-known-sources-and-one-unsupported-type` | A 有 3 个已知来源 + 1 个 Windows 不支持的 wire 类型 |
| 3 | `export-a-emits-three-sources-and-one-tombstone` | 信封 `sourceCount=3`、`deletionCount=1`、`canSendSecrets=false`，派生 id 形如 `local-` + 16 hex |
| 4 | `device-b-plan-splits-apply-approval-and-tombstone` | 计划三桶 `2/1/1`，理由分别是 `apply-remote-change` / `unknown-source-type` / `tombstone-unknown`；plan 不写盘 |
| 5 | `device-b-import-applies-only-what-it-understands` | 落盘 2 条；未知类型 `Unsupported`；墓碑 `NoChange`（绝不自动删） |
| 6 | `device-b-stores-the-peer-identity-verbatim` | B 的 `serverId == syncSourceId == A 的派生 id`，地址已归一化 |
| 7 | `unsupported-source-type-survives-the-wire-untouched` | 未知 wire 值 `quantum-nas` 在信封与 A 的 store 里逐字保留 |
| 8 | `second-round-is-idempotent` | 第二轮 `apply=0`、两条 `already-applied`、未知类型仍待确认、墓碑仍只报告 |
| 9 | `passphrase-archive-round-trips-to-a-fresh-device` | 归档 magic `MHSYNCA1`，新设备导入成功 |
| 10 | `wrong-passphrase-fails-closed-and-writes-nothing` | 退出码 4、`wrong-passphrase-or-tampered`、目标目录无 store |
| 11 | `secrets-are-resealed-with-the-local-dpapi-key` | 信封 blob 与 B 落盘 blob 不同，但 `secret-check` 用同一明文匹配成功 |
| 12 | `no-plaintext-secret-in-any-artefact` | transcript 与全部产物里搜不到明文及其 base64 |
| 13 | `no-gui-or-media-server-was-started` | 没有任何 `MediaHub` GUI 进程 |
| 14 | `device-roots-are-independent` | 五个根目录互相隔离且都在临时沙箱内 |

输出约定：每条一行 `PASS|FAIL <名字> :: <detail>`，收尾 `RESULT: N passed, M failed`，失败时退出码 1。证据（transcript、检查清单、两台设备的 store 与信封）写到 `windows/tools/acceptance/evidence/w6-sync-cli/<时间戳>/`。

## 局限（NOT_IMPLEMENTED）

1. **不是传输层**：信封由用户或脚本自己搬（U 盘、共享目录、聊天工具）。二维码配对与局域网传输属 W6-3，CLI 不承担。
2. **不做后台常驻**：没有服务、没有定时任务，每次都是单次命令。
3. **不做登录**：凭据只能从命令行传入（base64），CLI 不连媒体服务器、不校验凭据是否仍然有效。
4. **不实现审批 UI**：`requiresApproval` 只体现为退出码 3 与 JSON 桶，选择权留给上层（App）或用户重跑。
5. **不动 App**：App 的同步入口仍是 W6-2c 的页面，本工具不参与产品运行时。
6. **没有 Android 侧对应工具**：移动端仍是 Kotlin 实现，契约相同但代码不共享。

## 复现命令

```
dotnet build windows/src/MediaHub.SyncCli/MediaHub.SyncCli.csproj -c Debug -nologo
dotnet test  windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
pwsh -NoProfile -File windows/tools/acceptance/w6/sync-cli-e2e.ps1
```

期望：构建 0 警告 0 错误；`MediaHub.Tests` 全绿（其中 `SyncCliTests` 36 例）；脚本 `RESULT: 14 passed, 0 failed`。
## W6-15 追加 —— `baseline` 与 `pair` 子命令（无 GUI 也能管理基线与已配对设备）

- 状态：**已交付**（6 个新命令 + 32 例进程内测试；全量 `MediaHub.Tests` 1053 通过 / 0 失败）
- 日期：2026-10-23
- 实现：`windows/src/MediaHub.SyncCli/CliParser.cs`（组命令/子命令解析与用法文本）、`windows/src/MediaHub.SyncCli/CliInvocation.cs`（6 个命令枚举值 + `--source`/`--link`）、`windows/src/MediaHub.SyncCli/SyncCliRunner.cs`（`RunBaselineShow` / `RunBaselineForget` / `RunPairLink` / `RunPairVerify` / `RunPairList` / `RunPairForget`）、`windows/src/MediaHub.Sync/FileSourceSyncBaselineStore.cs`（新增 `PeerDeviceIds` / `ForgetPeer` / `ForgetEntry`）
- 测试：`windows/tests/MediaHub.Tests/SyncCliBaselinePairTests.cs`（32 例）

### 问题

W6-9 的基线文档（`sync-baseline.json`）与 W6-12 的配对登记（`paired-devices.json`）此前只能被 App 或测试触碰：一台没有桌面会话的机器既看不到「我从谁那儿导入过什么」，也清不掉一条已经作废的登记。两个文件都落在 `%LOCALAPPDATA%\MediaHub`，让用户手改 JSON 会绕过 merge 语义与原子写——必须给 CLI 一条受控路径。

### 决策（续 D7–D10）

#### D7：删除与枚举由 store 提供，CLI 不自己写 JSON

`FileSourceSyncBaselineStore.Save` 是 merge 语义（revision 不回退、只增不减），永远删不掉一条已作废的基线。如果 CLI 自己反序列化再整份覆盖写，就绕过了两条不变量：同目录 `.tmp` + `File.Replace` 的原子写，以及「文档损坏时拒绝覆盖」（`!_writable`）。因此 `PeerDeviceIds` / `ForgetPeer` / `ForgetEntry` 加在 store 内，CLI 只是调用者。

反面后果：CLI 自己写盘，一次 `--apply` 在断电或并发下就能写坏基线，App 与 CLI 对「我从谁那儿导入过什么」会给出两份不一致的答案。

#### D8：`pair` 组永不监听端口

`pair link` 载荷里的 `ephemeralUrl` 只是占位（`http://127.0.0.1:0/`，端口 0 即「不存在」）；JSON 里 `"listens": false` 显式声明，文本模式最后一行也写明。理由：W6-12 已确定「绑非回环地址会触发 Windows 防火墙弹窗」，CLI 没有理由承担这个副作用——它要做的是生成/校验一个串，不是建立连接。`pair verify` 同样只解析，不发任何 TCP/UDP。

#### D9：破坏性动作默认 dry-run，`--apply` 才写

`baseline forget` / `pair forget` 与既有 `import` 一致：不带 `--apply` 只报告「将要忘掉什么」，退出码 0。dry-run 的证据是 `dryRun:true` / `applied:false` 字段加上文件字节未变（`SyncCliBaselinePairTests` 用字节级断言钉住；json 模式下**不打印** `preview:` 句，见下文）。

#### D10：一个条件只有一个稳定码，不为别名再造一层

错误码直接复用 `PairingLinkReasons` / `PairingCodeReasons` / `SourceSyncBaselineStoreReasons`（映射表见下）。`wrong-scheme` 与 `bad-payload` 在这套实现里是**类别**而不是码：`PairingLink.Parse` 已能把「不是配对链接」与「载荷解不出来」分开（`not-a-pairing-link` vs `payload-invalid` / `not-base64url`），再加一层别名会让脚本对同一次失败判两个码都算对。

### 命令参考

| 命令 | 作用 | 选项 | 默认退出码 |
| --- | --- | --- | --- |
| `baseline show --root <dir>` | 打印基线文件路径、是否存在、文档版本、peers/entries 计数与每条记录的 (peer, syncSourceId, revision, 内容哈希前 12) | `--root`、`--json` | 0 |
| `baseline forget --root <dir> --device <id> [--source <syncSourceId>]` | 忘掉某个 peer（或它下面某一条来源）的基线；默认 dry-run | `--root`、`--device`、`--source`、`--apply`、`--json` | 0 / 2 |
| `pair link [--device <id>]` | 生成本机 `mediahub://pair?...` 链接 + 设备 id + 指纹前 8 位；不监听 | `--device`、`--json` | 0 |
| `pair verify --link <url>` | 离线解析并校验链接（过期、版本、字段形状），打印对端 deviceId 与指纹 | `--link`、`--json` | 0 / 2 |
| `pair list --root <dir>` | 列出本机已登记的配对设备（id、名字、指纹后 8 位、来源数、最后相见）；空表合法 | `--root`、`--json` | 0 / 2 |
| `pair forget --root <dir> --device <id>` | 忘掉一台已登记设备；默认 dry-run | `--root`、`--device`、`--apply`、`--json` | 0 / 2 |

组命令的第二个位置参数是动词：`missing-subcommand:<group>`（缺失或紧跟 `--`）与 `unknown-subcommand:<group>:<verb>`（不认识）都是用法错误（退出码 1）。`--root` 只在需要读写本地文档的命令上必填，`pair link` / `pair verify` 不需要根目录。

### 退出码（沿用 D2，补充新触发）

| 退出码 | 新增触发 |
| --- | --- |
| 0 | 六个新命令的正常结果（含 dry-run、空表、链接校验通过） |
| 1 | 缺子命令、未知子命令、缺 `--root` / `--device` / `--link`、`--apply` 用在不写盘的命令上 |
| 2 | 未知 peer / 未知来源 / 未知设备、链接解析失败（各细分码）、基线或登记文档损坏（已隔离）、写盘失败 |
| 3 / 4 | 新命令不产生（它们不碰信封、不碰凭据、不碰归档） |

### `--json` 单文档约束（补充 D3）

1. `--json` 时 stdout **恰好一个** JSON 文档；失败时该文档是 `{"ok":false,"error":{"code":"...","message":"..."}}`，同时 stderr 多一行 `error <code>: <message>`。
2. 一切进度与预览提示写 stderr；**json 模式不打印 `preview: ...` 句**（与既有 `WriteRound` 一致：json 分支先 return）。dry-run 在 json 下的证据是 `dryRun` / `applied` 字段与文件字节未变，`preview:` 只出现在文本模式（钉住用例：`PairForget_TextModeAnnouncesThePreviewAndWhatIsKeptUntouched`）。
3. 字段顺序与缩进不承诺稳定，脚本按名取字段；文档为紧凑单行（`CliJson` 关闭缩进、放开非 ASCII 转义）。

### 稳定错误码

| 码 | 退出码 | 触发 | 任务书里的类别 |
| --- | --- | --- | --- |
| `not-a-pairing-link` | 2 | 前缀/协议不对（不是 `mediahub://pair?`） | wrong-scheme |
| `pairing-link-empty` / `pairing-link-too-long` | 2 | 空串 / 超过 8192 字符 | bad-payload |
| `code-empty` / `not-base64url` / `code-too-long` | 2 | 链接里的载荷段本身坏了 | bad-payload |
| `payload-invalid` | 2 | 载荷解出的字节不是合法 JSON/UTF-8 | bad-payload |
| `unsupported-version` | 2 | 载荷 `schemaVersion` 不认识 | unsupported-version |
| `expired` | 2 | 链接过期（默认 120 秒） | expired |
| `bad-challenge` / `bad-fingerprint` / `bad-url` | 2 | 载荷字段形状不合法（challenge 不是 32 字节、指纹不是 64 hex、地址不是绝对 http(s)） | bad-payload |
| `baseline-device-unknown` | 2 | 基线里没有这个 peer | input problem |
| `baseline-source-unknown` | 2 | 该 peer 下没有这个 syncSourceId | input problem |
| `baseline-write-failed` | 2 | 原子写失败（IO / 权限） | input problem |
| `baseline-nothing-forgotten` | 2 | store 返回 false（并发下已被别人删掉） | input problem |
| `device-unknown` | 2 | `pair list` 里没有这个 deviceId | input problem |
| `store-write-failed` | 2 | `paired-devices.json` 写失败 | input problem |
| `store-unreadable` / `<隔离原因>`（如 `baseline-too-deep`） | 2 | 文档读不了或结构损坏；损坏件已被改名隔离，消息里带隔离后的完整路径 | input problem |

### PairingLink 的真实线上格式

输出侧的链接是 `mediahub://pair?<base64url>`——**没有 `code=` 参数**（`PairingLink.cs:47` 的 `Prefix`，`:56-59` 的 `Create = Prefix + PairingCode.Encode(payload)`）。输入侧的 `ExtractCode`（`:63-74`）会容忍旧的 `?code=<base64url>` 拼法，所以两种写法都能被 `pair verify` 读。断言请用 `PairingLink.Prefix`，不要硬编码 `?code=`（本任务的测试首轮就因为硬编码而红了 5 例）。

### 实测输出（本机真实运行，2026-10-23，D:\mh-w6）

```
$ dotnet MediaHub.SyncCli.dll pair link --json
{"command":"pair","subcommand":"link","deviceId":"cli-inkchoi","fingerprintPrefix":"27b64d6d","link":"mediahub://pair?eyJzY2hlbWFWZXJzaW9uIjoxLC…（实测 561 字符，此处按段截断）","expiresAtEpochMs":1792790388437,"lifetimeSeconds":120,"listens":false}
```

```
$ dotnet MediaHub.SyncCli.dll pair link
device: cli-inkchoi
fingerprint: 27b64d6d...
expires: 2026-10-24 05:19
link: mediahub://pair?eyJzY2hlbWFWZXJzaW9uIjoxLC…（此处截断）
note: this CLI does not listen; the link identifies this device and is checked offline (pair verify).
```

设备 id 未给 `--device` 时取 `cli-` + 机器名（小写净化）：本机即 `cli-inkchoi`。

```
$ dotnet MediaHub.SyncCli.dll pair verify --link <上一步的 link>
pairing link ok
device: cli-inkchoi
fingerprint: 27b64d6d...
expires: 2026-10-24 05:19 (119s left)
note: the fingerprint is self-declared, so compare it on both devices before trusting the link.

$ dotnet MediaHub.SyncCli.dll pair verify --link mediahub://pair?zzzz   ; echo exit=$?
error payload-invalid: 配对码解出的内容不是 UTF-8 文本，可能复制时缺少一段。
exit=2

$ dotnet MediaHub.SyncCli.dll pair verify --link https://example.com/pair?code=abc ; echo exit=$?
error not-a-pairing-link: 这不是 MediaHub 配对链接。
exit=2
```

```
$ dotnet MediaHub.SyncCli.dll baseline show --root <空目录>
baseline: C:\Users\55160\AppData\Local\Temp\mh-w6-15-real-f308240c\empty\sync-baseline.json
exists: false
version: 1
peers: 0
entries: 0
nothing has been imported from a peer yet

$ dotnet MediaHub.SyncCli.dll baseline forget --root <空目录> --device peer-1 ; echo exit=$?
error baseline-device-unknown: the sync baseline holds nothing for device peer-1
exit=2
```

```
# 损坏文档：写入 {"version":1,"peers":[{"deviceId":"peer-1","baseline":{"entries":[  之后再 show
$ dotnet MediaHub.SyncCli.dll baseline show --root <损坏目录> ; echo exit=$?
error baseline-too-deep: the sync baseline could not be read; the damaged document was kept at C:\Users\55160\AppData\Local\Temp\mh-w6-15-real-f308240c\corrupt\sync-baseline.corrupt-20261023211749.json
exit=2
# 目录里现在只剩隔离件：sync-baseline.corrupt-20261023211749.json
```

```
$ dotnet MediaHub.SyncCli.dll pair list --root <空目录>
paired devices: 0
no devices are paired on this machine

$ dotnet MediaHub.SyncCli.dll pair forget --root <空目录> --device dev-x ; echo exit=$?
error device-unknown: no paired device has id dev-x
exit=2
```

```
$ dotnet MediaHub.SyncCli.dll baseline ; echo exit=$?
error missing-subcommand:baseline: run with --help for usage
MediaHub.SyncCli <command> [options]
...（随后打印完整用法文本）
exit=1

$ dotnet MediaHub.SyncCli.dll pair dance ; echo exit=$?
error unknown-subcommand:pair:dance: run with --help for usage
...（随后打印完整用法文本）
exit=1
```

### 局限（本节的 NOT_COVERED）

1. **`pair verify` 不联网**：它只保证链接自洽、未过期、字段合法，不验证对端此刻可达（CLI 绝不监听也绝不出站连接）。
2. **没有真机 Android 互操作证据**：链接格式与 App 共用同一套 `PairingLink` / `PairingCode`，但跨平台实测仍属 W6-3c / W6-12 的 GUI 侧；本节只钉住「同一串在两处算出的结论一致」。
3. **`baseline forget` 只动基线**：不撤销已落盘的来源、不删凭据、不通知对端；忘记之后下一轮导入会被当作新来源重新逐条确认。
4. **`--json` 不承诺字段顺序**，也不保证跨版本只增不减；脚本按名取字段。
5. **文案硬编码中文**：与 `MediaHub.Sync` 其他 presenter 一致，尚无本地化资源。
6. **未覆盖并发写**：两个 CLI 进程同时对同一个 root 执行 `--apply` 时，靠 store 的原子替换保证不写坏文件，但后写者会覆盖前者的删除意图（`baseline-nothing-forgotten` 能暴露部分情况）。

