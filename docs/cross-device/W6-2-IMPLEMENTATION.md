# W6-2 跨设备来源同步：本地镜像 + 同步页（Windows）

状态：**IMPLEMENTED_UNVERIFIED**（编译与纯逻辑测试通过；真实 GUI 与真实对端传输未跑——W6-3 才做网络与配对）。

## 1. 这一层做了什么

W6-1 交付了契约层（`MediaHub.Sync`：类型、编解码、规划器）。W6-2 把它接到 Windows 客户端上，只做三件事：

| 能力 | 入口 | 写入什么 | 不做什么 |
| --- | --- | --- | --- |
| 导出本机信封 | `同步页 → 导出同步信封` | `%LOCALAPPDATA%\MediaHub\sync-envelope.json` | 不含密码/令牌；不修改任何来源 |
| 预览对端信封 | `同步页 → 预览合并` | 什么都不写 | 没有 Apply 通道，冲突只标记「需用户确认」 |
| 逐源验证 | 每行「验证 <来源名>」 | 只写日志 | 不把「导出成功」说成「已同步」，也不把「有令牌」说成「已登录」 |

红线：导出成功 ≠ 已同步；预览 ≠ 已合并；有令牌 ≠ 可登录。三条都写在页面文案里。

## 2. 新增/修改的文件

### 契约层新增（`windows/src/MediaHub.Sync/`）

- `ILocalSourceStore.cs` / `InMemoryLocalSourceStore.cs`：本机来源记录的存储抽象（测试用内存实现，带 `WriteCount`）。
- `SourceIdAssignment.cs` / `SourceSyncApplyOutcome.cs` / `SourceSyncLocalBridge.cs`：`syncSourceId` 派生（`local-` + SHA256(wire + 归一化地址 + accountScope)[0..8] hex，幂等）、信封构建（默认 `CanSendSecrets=false`）、入站应用（只写 Update 与无审批 Create）。
- `LocalSourceMapping.cs`：App 侧来源 → `LocalSourceRecord` 的映射与问题分类（缺失类型、粘贴凭据、本机路径等）。
- `SourceSyncPreviewSummary.cs`：计划 → 计数汇总（新建/更新/冲突/不支持/删除/无变化/接管/需确认）。

### App 层

- `Services/FileLocalSourceStore.cs`：`sync-sources.json`；原子写（tmp + replace）；文件不可解析时**只报告、拒绝写入**，绝不覆盖已有身份（身份无法重建）。
- `Services/SyncIdentity.cs`：`dev-` + 12 位十六进制的稳定设备标识，首次生成写进 `settings.json`。
- `Services/SourceSyncService.cs`：导出 / 读对端信封 / 预览 / 逐源验证；验证先 `Auth.RestoreSessionAsync` 再真的 `Library.GetLibrariesAsync`，只有两步都过才叫「已验证」。
- `Views/SyncPage.xaml(.cs)` + `MainWindow` 导航项「设备同步」（`Tag=sync`）。
- `Services/ServerManager.cs`：`ServerEntry.SyncSourceId` 持久化、`DescribeSyncSources()`、`EnsureSyncSourceIds()`。

## 3. 自动化标识（GUI 验收用）

`sync.actions` / `sync.export` / `sync.preview` / `sync.refresh` / `sync.status`（InfoBar）/ `sync.sources`（ListView）/ `sync.verify`（行内验证按钮）/ `sync.empty` / `sync.preview.details`。
导航项文案「设备同步」。

## 4. 测试与构建

- `dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build -nologo` → 0 警告 0 错误；产物 `D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll`（569344 B，SHA256 `F15E09E165E7C1B7A15A74F1EA2AEF2B6DA11FC9CE7491A3D8A36698782CCFDB`）。
- `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo` → **496 通过 / 0 失败**（W6-1 83 + W6-2a 25 + W6-2b 15 + 之前基线 373）。

## 5. 已验证 / 未验证

已验证：

- 契约与纯逻辑：`syncSourceId` 幂等与稳定性、信封不含 secrets、未知类型逐字保真、规划器的 Conflict/Stale/Ambiguous/Tombstone 语义、预览计数。
- 编译面与 UI 结构（AutomationId 存在）。

未验证（明确留给 W6-3 或人工）：

- 真实 GUI 交互（点击导出/预览/验证）——本轮无 GUI 证据。
- 真实对端的 `VerifyAsync` 网络路径（需要真实服务器 + GUI）。
- `FileLocalSourceStore` 损坏降级只有代码级保证：`MediaHub.Tests` 不引用 `MediaHub.App`，故无自动化测试。
- 配对二维码、局域网发现、TLS 1.3 + 钉扎、导入侧 DPAPI 重封装、批准后应用（Apply UI）、Android 端。

## 6. 已知取舍

- `App.csproj` 新增对 `MediaHub.Sync` 的引用（App 原本没有）。
- `ServerEntry` 没有「原始类型字符串」，所以「本机存过但本版不认识的类型」在页面上标注而不是导出猜测值；对端发来的未知类型仍按原文保留并标「不支持」。
- 「本地存储」源地址是本机路径，对端拨不到，因此只展示、不导出。
