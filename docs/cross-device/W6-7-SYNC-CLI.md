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
