# W6-13 已配对设备 UI（SyncPage）

- 状态: **IMPLEMENTED_UNVERIFIED**（代码已实现、17 个新测试全绿、全套 1021 例通过、App 工程 0 警告 0 错误；本轮**没有** GUI 证据、**没有**真实双机配对证据）
- 日期: 2026-10-24
- 分支/HEAD: `feature/cross-device-source-sync` / `a75139b`（工作区有 1 增 1 新测试 + 4 改，**未 commit**）
- 前置: W6-12 登记层（`PairedDeviceRegistry` / `FilePairedDeviceStore` / `PairedDevice`）、W6-3c 配对 UI、W6-8 审批门禁、W6-10 基线落盘
- 范围: 只动 `windows/src/MediaHub.Sync`（1 个新文件）、`windows/src/MediaHub.App`（3 个文件）、`windows/tests/MediaHub.Tests`（1 个新文件）、本文档；未动 Providers / Domain / Storage，未 commit / 未 push，未启动 GUI

## 1 语义边界（本区块最容易被误读的地方）

| 说法 | 事实 |
| --- | --- |
| 配对成功 | 只表示两台设备在**这一次**通信里互换了邀请码与名片；不等于已同步、不等于已登录任何媒体服务 |
| 出现在「已配对设备」里 | 只表示**本机**登记过这台设备的设备号和指纹；不代表对端当前在线、可连、或已授权 |
| 忘记 | 只删掉 `paired-devices.json` 里的**一行本机登记**；**不吊销对端、不取消同步、不删任何媒体来源、不动任何凭据**；下次遇到同一台设备按**首次配对**重新处理 |
| 指纹 | TOFU（首次使用即信任）：首次配对时**无法验证对方身份**，指纹只用来识别「上次那台是不是同一台」。对方换个指纹就会在 `Mismatch` 状态下被**拒绝**，且**绝不覆盖**已钉指纹 |

界面上这三句话必须能被用户读到：卡片底部的 TOFU 行（`sync.paired.tofu`）、忘记确认框正文、忘记成功后的 InfoBar 文案。

## 2 区块结构

`SyncPage.xaml` 的行布局由 5×Auto + 1×* 变为 **6×Auto + 1×***：新卡片插在「应用远端变更」卡片之后、底部「本机媒体源 / 合并预览」双列 Grid 之前（后者从 `Grid.Row="5"` 挪到 `Grid.Row="6"`，其内部 id 全部不变）。

```
Border  sync.paired.card        （CardBackgroundFillColorSecondaryBrush，Padding 16，CornerRadius 8）
└ StackPanel Spacing=8
  ├ Grid  标题「已配对设备」 + TextBlock sync.paired.count（MediaHubCaption，右对齐）
  ├ TextBlock PairedCapacityText（无 id；仅容量满时可见，前景 SystemFillColorCautionBrush）
  ├ ListView sync.paired.list（SelectionMode=None，MaxHeight=248，空时 Collapsed）
  │ └ DataTemplate → Border sync.paired.entry（Name 绑定 SummaryLine）
  │   ├ TextBlock DisplayName（SemiBold，单行省略）
  │   ├ TextBlock DetailLine（MediaHubCaption，换行）
  │   └ Button「忘记」sync.paired.forget（Name 绑定 ForgetLabel，ToolTip 说明只删本机登记）
  ├ StackPanel PairedEmptyPanel（FontIcon E8D7 + TextBlock sync.paired.empty）
  └ TextBlock sync.paired.tofu（TOFU 说明，常显）
```

所有可见文案（行文案、空态、计数、容量提示、TOFU）都来自 `PairedDeviceListPresenter`，XAML 里只绑定；页面只决定可见性。这样断言落在测试里而不是截图上，也便于将来抽本地化资源。

## 3 数据流与刷新点

- `SourceSyncService` 惰性建 **一个** `PairedDeviceRegistry`：`new PairedDeviceRegistry(new FilePairedDeviceStore(store.DirectoryPath))` → `%LOCALAPPDATA%\MediaHub\paired-devices.json`，与 `sync-sources.json` / `sync-baseline.json` 同目录；通过 `App.Services.Sync.PairedDevices` 暴露。
- 同一个实例经 `PairingPresenter(deviceId, reader, registry)` 注入主机/客机两个 presenter（`HostPresenter` / `GuestPresenter`）。**两个 registry 会各钉一次指纹**，所以这里是惰性单例而不是每处 `new`。
- `ReloadPairedDevices()` 的调用点：`OnLoaded`、主机配对轮询进入终态（Paired/Failed/Expired/Stopped）之后、加入配对结束后、忘记之后。
- 读取失败（`IOException` / `UnauthorizedAccessException` / `InvalidOperationException`）→ StatusBar InfoBar 报错，**不吞**；`OnLoaded` 还额外检查 `PairedDevicesError`，在来源清单本身没报错时报 Warning「已配对设备清单不可用」。

## 4 AutomationId 全表

| AutomationId | 元素 | 可访问名称 |
| --- | --- | --- |
| `sync.paired.card` | 区块容器 Border | 已配对设备 |
| `sync.paired.count` | 计数 TextBlock | 已配对设备数量（文本「已配对 N 台」） |
| `sync.paired.list` | 行列表 ListView | 已配对设备 |
| `sync.paired.entry` | 单行容器 Border | 绑定 `SummaryLine`（名字 · 指纹尾 · 首次配对 · 最后相见 · N 个来源） |
| `sync.paired.forget` | 行内「忘记」按钮 | 绑定 `ForgetLabel`（「忘记 <名字>」） |
| `sync.paired.empty` | 空态文本 | 文本「还没有配对过的设备。」 |
| `sync.paired.tofu` | TOFU 说明文本 | 文本即说明 |
| `sync.paired.forget.dialog` | 确认框内容面板 | —（ContentDialog 自身的按钮无法设 id，故 id 落在面板与自建按钮上） |
| `sync.paired.forget.confirm` | 确认框「忘记」按钮 | 确认忘记该设备 |
| `sync.paired.forget.cancel` | 确认框「取消」按钮 | 取消忘记该设备 |

## 5 忘记的流程

1. 行内「忘记」→ `OnForgetPairedDeviceClick`（`Tag` 是 `PairedDeviceRow`）。
2. `ConfirmForgetAsync(row)` 弹 `ContentDialog`：标题「忘记这台设备？」，正文含显示名、指纹尾，并写明「只删除本机的配对登记，来源、凭据和对端设备都不会被改动；下次配对会重新按首次配对处理」。**故意不设 Primary/Close 按钮**，取消/忘记都是自建按钮（才能带 id），各自 `dialog.Hide()`。
3. 确认 → `App.Services.Sync.PairedDevices.Forget(row.DeviceId)`（内部落盘，原子写）→ `ReloadPairedDevices()`。
4. 结果：成功 Informational「已忘记该设备」+ 上述边界说明；返回 `false`（本机没有这条登记）Warning + `DescribeReason(PairedDeviceReasons.UnknownDevice)`；异常 Error「无法忘记该设备」。

## 6 文件清单

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/PairedDeviceListView.cs` | 247 | 新增：`PairedDeviceRow` / `PairedDeviceListView` / `PairedDeviceListPresenter`（全部文案 + 指纹只取末 8 位 + 本地时间格式化） |
| `windows/src/MediaHub.App/Services/SourceSyncService.cs` | 544 | 改：惰性 `PairedDevices` / `PairedDevicesPath` / `PairedDevicesError` |
| `windows/src/MediaHub.App/Views/SyncPage.xaml` | 414 | 改：新卡片 + 行数 + 原底部 Grid 下移 |
| `windows/src/MediaHub.App/Views/SyncPage.xaml.cs` | 1123 | 改：registry 注入两个 presenter、`ReloadPairedDevices`、忘记与确认框、三个刷新点 |
| `windows/tests/MediaHub.Tests/PairedDeviceListViewTests.cs` | 300 | 新增：17 例 |

## 7 验证证据（本轮原始输出）

App 构建（`-p:MediaHubBinRoot=D:/mh-out-w6/`）：

```
  MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll

已成功生成。
    0 个警告
    0 个错误
```

测试：`已通过! - 失败:     0，通过:  1021，已跳过:     0，总计:  1021，持续时间: 4 s - MediaHub.Tests.dll (net10.0)`（基线 1004 + 本文件 17）

测试覆盖：指纹只显示末 8 位且整串不出现在任何可见文案、空名回退设备号前 8 位、短设备号原样、两个时间戳按本地时区格式化、时间戳非法时写「未知」、来源数、摘要行分隔符与副行不含名字、空态、缺省列表当空、计数、顺序保留、容量满才提示、默认容量与 registry 一致、TOFU 措辞（且不含「已登录」「已信任」）、忘记后行消失且 `WriteCount` 增加、未知设备返回 false 且不写盘、`Mismatch` 后仍显示旧指纹。

## 8 未覆盖项

- **没有 GUI 实测**：没有启动 MediaHub，卡片布局、行高、`MaxHeight=248` 的滚动、确认框在 150% DPI 下的呈现都未截图验证；自动化 id 是否被 UIA 逐层暴露（尤其 `DataTemplate` 内 `Border` 的 id 是否被读屏器枚举）未验证。
- **没有真实双机配对**：登记项来自测试里构造的 `PairedDevice`；真机配对后指纹与显示名是否如预期显示未验证。
- **没有本地化资源**：文案仍是 C# 字面量（集中在一个类里便于将来抽取），没有 .resw。
- **忘记的并发/损坏态**：`paired-devices.json` 被外部删除或损坏时的界面表现（`LoadError` → InfoBar）只有单元级推理，没有实机演练。
- **容量满的界面**：满 32 台时的黄色提示行与「新的设备不会被登记」的实际观感未验证。
- 未做「本机指纹/分享链接」在本区块的展示（属于 W6-12 的 `PairingSelfIdentity` / `PairingLink`，配对卡片已覆盖）。
