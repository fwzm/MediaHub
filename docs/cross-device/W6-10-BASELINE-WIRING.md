# W6-10 同步页接线：基线落盘真正生效（应用预览 → 逐条审批 → 写入 → 基线推进）

状态：**IMPLEMENTED_UNVERIFIED** —— 单测与构建全绿，**没有 GUI 证据**（本轮未启动 MediaHub，Get-Process MediaHub = 0）。
W6-9 只交付了基线存储层（`FileSourceSyncBaselineStore` + `SourceSyncEndpointPolicy`），App 侧从未实例化它，
所以「同一信封导入两次是 no-op」在当时只存在于测试里。W6-10 把它接进真实应用路径，并让「未导入端点」
在同步页上可见。

## 一、接线点（谁持有谁）

| 位置 | 变动 |
| --- | --- |
| `windows/src/MediaHub.App/Services/SourceSyncService.cs` | 构造时创建（或注入）`FileSourceSyncBaselineStore`，默认路径 = 镜像源记录目录下的 `sync-baseline.json`（即 `%LOCALAPPDATA%\MediaHub\sync-baseline.json`）；`CreateSession()` 把同一个 store 传给 `SourceSyncSession`；`PreviewApply` / `ApplyApprovedAsync` 全部转为调用 round |
| `windows/src/MediaHub.Sync/SourceSyncImportRound.cs`（新增，411 行） | 唯一的「计划 → 审批 → 写入 → 推进基线」实现：`Preview()` 纯计算（planner + 审批门 + 端点摘要 + 读基线），`ApplyAsync()` 逐条 await 调用方 writer，**只把真正写入的 record** 交给 `SourceSyncBaseline.FromRecords` 落盘 |
| `windows/src/MediaHub.App/Views/SyncPage.xaml(.cs)` | 新增未导入端点展示；应用结果行追加基线行与 warnings；基线损坏时预览走 Warning 而不是普通「已生成预览」 |

为什么把逻辑放 `MediaHub.Sync` 而不是 App：`MediaHub.Tests` 不引用 `MediaHub.App`（WinUI 依赖）。
App 只做装配，round 承担全部可测逻辑——同一个实现被应用与测试共用，不是「测试测了另一份代码」。

## 二、语义

- **预览不写入**：`Preview()` 不写文件、不分配身份、不动基线，只读一次基线用来告诉用户「这是全量还是增量」。
- **应用必须逐条批准**：默认裁决来自 `SourceSyncApprovalGate`；`secrets-require-approval` 一类即使显式 Allow 也不会写入（`SecretsAreWithheldEvenWhenTheUserAllowsThem`）。
- **基线只记真正写入的条目**：writer 返回 `Refused` 或抛 `IOException/UnauthorizedAccessException/InvalidOperationException` 的条目不计入基线（`AWriterThatRefusesLeavesTheBaselineAlone`），下一次导入仍会重新提议它。
- **删除不进基线**：对端声明删除时 writer 收到 `record = null`（`DeletionsReachTheWriterAsARecordlessEntry` 意义上的 `DeletionsReachTheWriterAsANullRecord`），删除没有 record 可存，因此事后重建同名来源仍被视为新建。
- **基线写入失败不崩页面**：`IOException` / `UnauthorizedAccessException` 转成 warning 码 `baseline-write-failed`；revision 单调守卫拒绝时转成 `baseline-rejected:<n>:<reason>`，二者都渲染进结果行的提示段。
- **损坏基线的降级**：store 把坏文件隔离成 `sync-baseline.corrupt-<ts>.json` 并令 `LoadError` 非空；round 据此把预览标成「全量」（`IsFullSnapshot = true`），页面弹 Warning「本机基线不可用 … 本次按全量计划预览」，写入成功后重建基线。

## 三、自动化标识（新增 2 个，全页共 28 个）

| AutomationId | 内容 |
| --- | --- |
| `sync.notimported.count` | 固定句：「N 个来源已导入，M 个次要地址未导入（secondary-endpoint-not-imported）」 |
| `sync.notimported.list` | 每行 `name · address · 顺序 · reason`，无次要端点时 Collapsed |

`sync.apply.result` 现在由三段拼成：三计数摘要（`SourceSyncApplyReport.Summary`）+ 基线行
（`BaselineLabel`：「本机基线已更新（共 N 条…）」/「本机基线未变化（共 N 条）」）+ 固定免责句
（`SourceSyncService.ApplyDisclaimer` =「导入不代表已登录；请对每个来源点『验证』。」）。

## 四、证据（本机实跑）

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
已通过! - 失败: 0，通过: 982，已跳过: 0，总计: 982   （基线 968 + 新增 14）

dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build
已成功生成。 0 个警告 0 个错误     → MediaHub.dll 609280 B / E94E803C12AADE49E09895A04C964CB58D63734F543234DD2DF47FCD1E0CDDFB
```

14 个用例覆盖：空信封无提议、首次应用写全部并推进基线、同信封二次应用零写入、**换新服务实例（模拟重启）仍零写入**、
多端点只写主端点且其余显式列出、坏基线降级为全量计划、未导入摘要含固定 reason、secrets 即使 Allow 也 withhold、
writer 拒绝不动基线、删除以 null record 抵达 writer、无允许身份则零写入、预览幂等不写、计数互斥且等于预览条目数、
结果行解释基线是否移动。

## 五、偏离（均已接受）

1. 可测逻辑落在 `MediaHub.Sync`（`SourceSyncImportRound`），App 只装配——理由见 §1 末尾。
2. 新类型原名 `SourceSyncApplyResult` 与 Sync 层既有类型撞名（CS0101），改名 `SourceSyncApplyReport`；App 里两个本地 record 删除。
3. `deviceId` 沿用 `SyncIdentity.EnsureDeviceId(SettingsStore)`，本层不新造设备身份。
4. 基线保存只含本轮写入的 record，不与旧基线合并；单调性交给 `FileSourceSyncBaselineStore` 的 merge 守卫。

## 六、仍未做

- **GUI 证据**：本轮零窗口运行，只能记 IMPLEMENTED_UNVERIFIED；`sync.notimported.*` 与结果行的基线句都需要人工授予窗口后跑 `windows/tools/acceptance` 下的脚本确认。
- 次要端点只展示不重试（单地址存储是既有约束 `LocalSourceRecord.LocalUrl`）。
- secrets 一律 withheld：跨设备真实 DPAPI 重封装/凭据传递未做。
- 跨设备真实传输、配对后的设备持久化未做；Android 侧仍不读 Windows 基线文件。
- 删除不进基线（见 §2 末）。
