# W6-8 同步页「应用远端信封」与审批门禁

- 状态: **IMPLEMENTED_UNVERIFIED**（代码已实现、纯逻辑测试全绿、App 工程 0 警告 0 错误；本轮**没有** GUI 证据）
- 日期: 2026-10-24
- 分支/HEAD: `feature/cross-device-source-sync` / `fb78480`
- 前置: W6-1 契约层、W6-2 本地来源镜像、W6-3 配对、W6-6 DPAPI 重封、W6-7 CLI e2e
- 范围: 只新增/修改 6 个文件（见 §2），未动 Providers/Domain/Storage/tests 之外的既有语义，未 commit，未启动 GUI

## 1 红线与落实位置

| 红线 | 落实位置 |
| --- | --- |
| 用户点「应用」之前，一个字节都不写本机来源 | 预览路径 `SourceSyncService.PreviewApply` 只读 `_store.GetAll()`；唯一写入路径是 `ApplyApprovedAsync`，只由 `OnApplyClick` 在确认框返回 Primary 后调用 |
| 需要审批的条目默认不写，必须显式「允许」 | `SourceSyncApprovalGate.Decide`：无决策时 `allow = !item.RequiresApproval` |
| 三类原因永远不可允许 | `SourceSyncApprovalGate.NeverAllowableReasons`（`secrets-require-approval` / `unknown-source-type` / `not-supported-on-windows`），先判拒绝再判决策，显式 Allow 也拦 |
| 允许高风险变更必须留下 `acknowledgedRisk` | `SourceSyncApprovalResult.AcknowledgedRisk` / `AppliedWithRisk`；UI 在确认框里单独提示 |
| 什么都不能显示「已同步」「已配对成功」 | 结果行固定拼 `SourceSyncService.ApplyDisclaimer`；grep 全 App 只有否定句含「已同步」 |
| 口令不得进日志/异常/StartupTrace | 口令只在 `LoadAndPreviewAsync` 局部变量里存活，读完立刻置 null；`ReadPeerFile` 的失败文案只带 reason code |
| 卸载/取消时清掉口令与待写计划 | `OnUnloaded` 清 `_applyPreview` / `_applyDecisions` / `_applyPath`；`ClearApplyPreview` 退休计划 |

## 2 交付物

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/SourceSyncApprovalGate.cs` | 409 | 纯逻辑审批门：条目/决策 → applied / skipped / withheld + blockingReasons + acknowledgedRisk |
| `windows/tests/MediaHub.Tests/SourceSyncApprovalGateTests.cs` | 392 | 22 个测试方法（[Theory] 展开后 29 例）钉住规则 |
| `windows/src/MediaHub.App/Services/SettingsStore.cs` | 194 | `PersistedServer.SyncRecordRevision`：记住「上次跨设备写入时的版本」 |
| `windows/src/MediaHub.App/Services/ServerManager.cs` | 946 | `ApplyRemoteSource` / `RemoveRemoteSourceAsync` / `AdoptRemoteSource` + `RevisionOf` |
| `windows/src/MediaHub.App/Services/SourceSyncService.cs` | 553 | `ReadPeerFile`（明文/加密）、`PreviewApply`、`ApplyApprovedAsync`、`SyncApplyResult` |
| `windows/src/MediaHub.App/Views/SyncPage.xaml` | 320 | 「应用远端信封」卡片：选文件 / 预览 / 逐条允许跳过 / 应用 / 结果 |
| `windows/src/MediaHub.App/Views/SyncPage.xaml.cs` | 924 | 上述交互与口令对话框 |

## 3 交互流程

1. **选择信封文件**（`sync.apply`）：`FilePickerService.PickFileAsync([".json", ".mharchive", ".mhsync"])`。取消则什么都不发生。
   - W6-66 后：`.mharchive` 是加密归档的规范后缀（Windows CLI 默认名与 Android 导出默认名由此统一），`.mhsync` 只为兼容旧文件仍可选；读端按 magic `MHSYNCA1` 嗅探，扩展名不参与判定。
2. **读取**：`ReadPeerFile(path, null)`。命中加密存档头 → 需要口令 → 弹 `ContentDialog` + `PasswordBox`（`sync.passphrase.dialog` / `sync.passphrase.input`）；取消则只提示「没有读取任何内容」。口令只作为这一次调用的实参，读完即丢弃。
3. **预览**（`sync.apply.preview`，同一路径自动触发一次）：`SourceSyncPlanner.Plan` → 每条转 `SourceSyncApprovalItem`（信封里带 `secrets` 的条目改写为 `secrets-require-approval` 且要求审批）→ 审批门默认判决。此时**没有任何写入**，页面只显示条目与计数。
4. **逐条决定**：每行 `sync.entry.allow` / `sync.entry.skip`，按 `syncSourceId` 记决策（不看名字、不看行号）。不可允许的条目「允许」按钮禁用（`Allowable=false`），并列出到 `sync.apply.blocked`。
5. **应用**（`sync.apply.confirm`，只有 `CanApply` 时可用）：确认框列出「写入 N / 跳过 M / 保留未应用 K」；含高风险变更时额外一段提示。确认后 `ApplyApprovedAsync` 逐条走 `ServerManager`：
   - 信封里没有该标识的记录 → `RemoveRemoteSourceAsync`（删除；本地源 `local` 拒绝）
   - `Action == Adopt` → `AdoptRemoteSource`（接管已有来源，写上标识与版本）
   - 其余 → `ApplyRemoteSource`（新增/替换条目，状态固定 `AuthRequired`，`SyncRecordRevision` 落盘）
6. **结果**：`sync.apply.result` 显示 `已写入本地 N 条 / 跳过 M 条 / 保留未应用 K 条` + 固定免责句「导入不代表已登录；请对每个来源点『验证』。」；预览退休，来源列表刷新，需要重新「验证」才有认证结论。

## 4 审批门规则表

| reason | 来源 | 可否 Allow | 需 ack 风险 |
| --- | --- | --- | --- |
| `secrets-require-approval` | App（信封带 secrets） | **否，永不** | — （进 `blockingReasons`） |
| `unknown-source-type` | planner / round | **否，永不** | — （进 `blockingReasons`） |
| `not-supported-on-windows` | planner / round | **否，永不** | — （进 `blockingReasons`） |
| `delete-vs-update` | round | 是 | 是 |
| `update-vs-delete` | round | 是 | 是 |
| `field-conflict` | round | 是 | 是 |
| `same-revision-different-content` | planner / round | 是 | 是 |
| `ambiguous-*`（如 `ambiguous-legacy-identity`） | planner / round | 是 | 是 |
| `tombstone-requires-approval` / `legacy-identity-match` / `new-source` / `higher-revision` / `no-usable-endpoint` / `duplicate-local-sync-id` | planner / round | 是 | 否（不标风险） |
| `up-to-date` / `stale-*` / `already-applied` / `both-deleted` / `push-*` / `apply-remote-change` / `remote-newer` / `local-newer` / `no-local-source` / `tombstone-unknown` | planner / round | 是（但动作不写入时一律 skipped） | 否 |

**为什么这三类永远不能 Allow**：`secrets-require-approval` 要求写入一段属于**另一台机器 DPAPI 作用域**的密文，本机解不开也封不回；`unknown-source-type` 与 `not-supported-on-windows` 要求写入一个本构建既连不上、也验不了、也表达不出的来源类型——点「允许」无法创造这些能力，只会写进一行坏数据，所以只能拒绝并给出原因（这三条在门里先于决策判断，传入显式 Allow 也照样进 withheld）。

## 5 AutomationId 表

| AutomationId | 控件 | 可访问名称 |
| --- | --- | --- |
| `sync.status` | InfoBar | —（标题随操作变化） |
| `sync.actions` | 工具栏 StackPanel | — |
| `sync.export` / `sync.preview` / `sync.refresh` | Button | 导出同步信封 / 预览合并 / 刷新 |
| `sync.sources` | ListView | 本机媒体源 |
| `sync.verify` | 行内 Button | 验证 <来源名> |
| `sync.empty` | TextBlock | 还没有可同步的媒体源。 |
| `sync.preview.details` | ListView | 合并预览明细 |
| `sync.pair.host` / `sync.pair.stop` / `sync.pair.copy` | Button | 开始配对 / 结束配对 / 复制配对码 |
| `sync.pair.code` / `sync.pair.fingerprint` / `sync.pair.status` | 只读控件 | 配对码 / 证书指纹 / 配对状态 |
| `sync.pair.input` / `sync.pair.join` / `sync.pair.result` | TextBox / Button / TextBlock | 粘贴对端配对码 / 连接对端 / 配对结果 |
| `sync.apply` | Button | 选择信封文件 |
| `sync.apply.preview` | Button | 预览变更 |
| `sync.apply.confirm` | Button | 应用已允许的变更 |
| `sync.apply.entry` | 行容器 Border | 行内来源名 |
| `sync.entry.allow` | Button | 允许 <来源名> / 不可允许 <来源名> |
| `sync.entry.skip` | Button | 跳过 <来源名> |
| `sync.apply.result` | TextBlock | 应用结果 |
| `sync.apply.blocked` | TextBlock | 不可应用的条目 |
| `sync.passphrase.dialog` | 对话框面板 | — |
| `sync.passphrase.input` | PasswordBox | 信封口令 |

## 6 凭据处理

- 明文信封：`SourceSyncEnvelope.Sources[].Secrets` 只要非空，该条即被标为 `secrets-require-approval` 并进 withheld，**永不写入**（也不会落进本机 store）。
- 加密存档：口令只用于 `EncryptedArchiveReader.TryRead`，是 `LoadAndPreviewAsync` 的局部变量，用完即置 null；不进字段、不进日志、不进 InfoBar 文案、不进异常消息。`PasswordBox` 读后立即清空。
- 结论：本版**不做**跨机凭据迁移。带凭据的条目只会出现在「保留未应用」里，提示用户人工在新设备上重新登录。

## 7 验证记录（原始输出）

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
已通过! - 失败:     0，通过:   918，已跳过:     0，总计:   918，持续时间: 4 s - MediaHub.Tests.dll (net10.0)
```

基线 889 + 本次新增 29 例 = 918，失败 0。

```
dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build -nologo
  MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll

已成功生成。
    0 个警告
    0 个错误
```

- 产物: `D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll`，612864 字节，2026-10-24 03:52:56.645，SHA256 `4091BB66150F74203FEA5B7FDD416C2B8A9FD42E2939C92BD5B648C817F9E86C`
- 未触碰: `D:\mh-out` 1098 文件 / max 2026-10-24 00:13:18.529；`D:\mh-out2` 543 / 2026-10-23 23:09:00.218；`D:\mh-out3` 543 / 2026-10-24 00:01:52.874（与本轮开工前一致）

**未验证声明**：本轮没有启动 GUI（本轮五轮 GUI 验收尝试都拿不到窗口），因此「点开同步页 → 选文件 → 点允许 → 点应用」这条真实路径**没有被人工或 UI 自动化走过**。上面的测试只覆盖纯逻辑部分；App 侧只到「编译通过、XAML 解析通过」为止。状态如实记为 **IMPLEMENTED_UNVERIFIED**。

## 8 未覆盖项

1. 无 GUI 证据（无 UIA 断言、无截图、无 StartupTrace 序列）。
2. 无驻留监听：同步仍是「用户手动选文件」的一次性动作，没有后台接受连接。
3. 无二维码配对（W6-3c 只有手工配对码，且服务端只绑回环）。
4. 无 Android 端。
5. 无设备持久化：已配对设备不入库，下次要重新配对。
6. 无跨机凭据迁移（secrets 一律 withheld）。
7. 无「撤销上次应用」：`SyncRecordRevision` 只用于下次比较，没有回滚入口。
8. 无多信封队列：一次只处理一个文件。
9. `SourceTypeRaw` 无法从 `ServerEntry` 回读，因此本机未知类型只报 `no-wire-type`，不会被导出成对端未知类型。
