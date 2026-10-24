# W6-33 Windows 同步页接传输 UI

状态：逻辑 **IMPLEMENTED_UNVERIFIED**（presenter 29 例单测全绿、全套 1453 例通过、App 工程 0 警告 0 错误、未启动 GUI）
GUI 面：**GUI_UNVERIFIED**（本轮纪律禁止启动 MediaHub，界面只经编译与静态检查，没有任何点击证据）
日期：2026-10-24 · 分支：feature/cross-device-source-sync · 工作树：D:/mh-w6
前置：W6-16 帧协议 · W6-24 对端认证 · W6-26 TLS 1.3 + 指纹钉扎 · W6-30 送达确认 · W6-8/W6-10 应用门禁
范围：把已经存在、已被 CLI 与 11 例验收脚本证明过的传输能力接进同步页；**不改传输层语义、不改线格式、不新增稳定码**。

---

## 1 范围与分层

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 逻辑（可单测） | `windows/src/MediaHub.Sync/SyncTransferPresenter.cs`（950 行，新增） | 表单校验、监听生命周期、发送、20 个稳定码 → 中文、口令持有与清零 |
| 视图（不可单测） | `windows/src/MediaHub.App/Views/SyncPage.xaml`（414 → 527 行） | 传输卡片：接收表单 + 发送表单，纯渲染 |
| 视图代码 | `windows/src/MediaHub.App/Views/SyncPage.xaml.cs`（1002 → 1200 行） | 三个 `async void` 按钮处理器 + 渲染，不自己做判断 |
| 测试 | `windows/tests/MediaHub.Tests/SyncTransferPresenterTests.cs`（769 行，29 个 [Fact]，新增） | 真回环 socket，不用 GUI、不启进程 |

presenter 是纯 .NET（`namespace MediaHub.Sync`，**零 WinUI / Windows.UI 引用**），因此能被 `MediaHub.Tests` 直接引用——`MediaHub.Tests` 不引用 `MediaHub.App`，任何想被单测的行为都必须留在 Sync 层。

## 2 为什么传输和导入是两条路（红线）

导出入信封是一个**文件**，接收是一个**连接**，两者唯一的交汇点是应用预览：

```
对端 --帧--> 本机 listener --> SourceSyncTransfer.ReceiveAsync --> 信封字节
                                                                    |
                                              SourceSyncCodec.TryDeserialize（失败 = envelope-invalid）
                                                                    |
                                   outcome.Envelope（presenter 只交出去，不碰 store）
                                                                    |
                  SyncPage: App.Services.Sync.PreviewApply(envelope) --> Presenter.Apply.Load(preview)
                                                                    |
                                          用户逐条允许 + 点『应用』 --> ApplyApprovedAsync（唯一的写入点）
```

据此推出三条被测试钉住的约束：

1. presenter **没有** `ILocalSourceStore` 参数，也没有任何 `Apply` / `Import` / `Save` 方法（`The_presenter_exposes_no_store_so_a_received_envelope_cannot_be_written` 用反射断言）。
2. 接收完只调用 `PreviewApply` + `Presenter.Apply.Load`，**不调用** `ApplyApprovedAsync`；页面上原来的『应用』按钮仍是唯一写入点。
3. 一个真实的 `FileLocalSourceStore` 放在 presenter 旁边，收完一个信封后它的条数不变、`sync-baseline.json` 根本不存在（`A_real_store_next_to_the_presenter_is_never_touched_by_a_receive`）。

## 3 UI 元素与 AutomationId

卡片 `sync.transfer.card`（AutomationProperties.Name = 传输（本机回环）），插在根 Grid 的第 7 行（`Grid.Row="7"`，新增的第 8 条 `RowDefinition Height="Auto"`），是该页**最后一个子元素**。

| x:Name | AutomationId | 可访问名称 | 说明 |
| --- | --- | --- | --- |
| TransferStatusText | `sync.transfer.status` | 传输状态 | 一行状态句（idle / 监听中 / 结果） |
| TransferReceivePortBox | `sync.transfer.receive.port` | 接收端口 | 默认 `0` = 自动分配 |
| TransferReceiveTlsCheck | `sync.transfer.receive.tls` | 接收使用 TLS 1.3 | 勾选后监听即绑临时证书 |
| TransferReceiveSecretBox | `sync.transfer.receive.secret` | 接收共享口令 | PasswordBox，可空 |
| TransferStartReceiveButton | `sync.transfer.receive.start` | 开始接收一个信封 | 打开监听并等待一次连接 |
| TransferStopReceiveButton | `sync.transfer.receive.stop` | 停止接收并释放端口 | 初始 `IsEnabled=False` |
| TransferReceiveBusy | —（ProgressRing） | — | 等待期间的忙碌指示 |
| TransferReceiveInfoText | `sync.transfer.receive.info` | 接收状态与证书指纹 | `IsTextSelectionEnabled`，指纹可复制去钉扎 |
| TransferSendHostBox | `sync.transfer.send.host` | 目标主机（只允许回环地址） | 默认 `127.0.0.1` |
| TransferSendPortBox | `sync.transfer.send.port` | 目标端口 | 空 = 校验失败（不是 0） |
| TransferSendTlsCheck | `sync.transfer.send.tls` | 发送使用 TLS 1.3 | 勾选后必须填指纹 |
| TransferPinBox | `sync.transfer.send.pin` | 对端证书指纹 | Consolas，64 位 hex |
| TransferSendSecretBox | `sync.transfer.send.secret` | 发送共享口令 | PasswordBox，可空 |
| TransferConfirmDeliveryCheck | `sync.transfer.send.confirm` | 要求送达确认 | 对应 `--confirm-delivery` |
| TransferSendButton | `sync.transfer.send` | 发送本机信封 | |
| TransferSendBusy | —（ProgressRing） | — | |
| TransferSendResultText | `sync.transfer.send.result` | 发送结果 | 结果句，失败字样保留稳定码 |

命名约定：**本区块的所有 x:Name 以 `Transfer` 开头**，与页面上既有的 `Pair*` / `Apply*` / `Paired*` / `Sync*` 前缀不冲突；AutomationId 统一走 `sync.transfer.*` 命名空间。

### 3.1 回执（receipt）在 presenter 层的两个开关（W6-39）

W6-39 给 presenter 加了「反向回执帧 0x04」（见 W6-37-RECEIPT.md）。**开关只存在于 presenter 层，页面上还没有对应控件**，所以这一节描述的是已实现、已单测、但**没有 GUI 证据**的能力：

| presenter 成员 | 默认 | 语义 |
| --- | --- | --- |
| `SyncTransferReceiveForm.WriteReceipt` | `false` | 接收侧：信封**解码成功**后回写 1 字节 `0x04`；解码失败或传输失败一字节都不写（gate 就是解码谓词） |
| `SyncTransferSendForm.AwaitReceipt` | `false` | 发送侧：写完全部帧后等回执字节，等不到就是 `receipt-missing` / `receipt-timeout` / `receipt-invalid`，按失败呈现 |
| `SyncTransferPresenter.ReceiptBudget` | 2000 ms | 等回执的预算（`SourceSyncReceipt.DefaultBudget`），页面上不可调 |
| `SyncTransferReceiveState.WritesReceipt` | `false` | 监听中状态回显「本次会不会回执」，页面只在状态句里用到 |
| `SyncTransferOutcome.ReceiptConfirmed` / `ReceiptWritten` | `null` | `null` = 没要求；成功时 `true` 并写进结果句（「并收到回执：对端读懂了这份信封（0x04）。」） |

默认两个开关都关闭时，线上字节与等待行为**与 W6-33 交付时完全一致**（95 字节冻结向量仍逐字节相等）。`DescribeReason` 随之从 20 码扩到 24 码（多出 `receipt-missing` / `receipt-timeout` / `receipt-invalid` / `receipt-not-enabled`），仍然每条带原码、未知码回显。

## 4 稳定码 → 界面文案（W6-39 起 24 码全覆盖）

`SyncTransferCopy.DescribeReason` 逐条翻译，**每条末尾把码本身原样写在括号里**，未知码回显为「未识别的结果码。（码本身）」——presenter 不自造码，也不隐藏码。

| 码集（封闭） | 码 | 界面要点 |
| --- | --- | --- |
| 传输 10 码 | not-loopback | 只允许 127.0.0.1 / ::1 / localhost |
| | listener-failed | 端口被占用或系统拒绝 |
| | connect-failed | 对端没在监听 |
| | frame-invalid | 对端不是本传输端点，或数据被改动 |
| | frame-truncated | 帧没传完对端就关了 |
| | payload-too-large | 超 8 MiB，未缓冲任何内容 |
| | challenge-mismatch | 挑战与预期不符（可能是另一个连接） |
| | envelope-invalid | 帧完整但信封不是合法同步信封 |
| | transfer-timeout | 期限内没等到完成 |
| | io-failed | 操作系统报告读写异常 |
| 认证 5 码（W6-24） | auth-required / auth-missing / auth-mismatch / auth-replayed / auth-secret-unavailable | 无挑战 / 无 tag / tag 不符 / 重放 / 无口令 |
| TLS 4 码（W6-26） | tls-handshake-failed / tls-fingerprint-mismatch / tls-certificate-unavailable / tls-not-enabled | 握手失败 / 指纹不符（零字节读写） / 无证书或无可用指纹 / 对端说 TLS 而本端只收明文 |
| 送达 1 码（W6-30） | delivery-unconfirmed | **「这不算送达」** |

送达确认的措辞是本任务的一条硬要求：`delivery-unconfirmed` 在 presenter 里是 `Succeeded=false`（W6-30 语义），页面按 `InfoBarSeverity.Error` 呈现，**不降级为警告**；`DescribeSent` 里 `confirmed=false` 分支写「但没有等到对端关闭：这次不算送达。」。

## 5 表单校验（9 项，返回结构而不是异常）

`SyncTransferFormIssue`：`None`（不是错误）、`HostEmpty`、`PortNotNumber`、`PortOutOfRange`、`FingerprintNotSixtyFourHex`、`AlreadyListening`、`NotListening`、`NoLocalEnvelope`。

- 端口用 `int.TryParse(..., NumberStyles.None, CultureInfo.InvariantCulture, ...)` + `0..65535`，**与 CLI 的 `--port` 完全同一规则**（所以 `-1` 是 `PortNotNumber` 而不是 `PortOutOfRange`，`70000` 才是 `PortOutOfRange`）。
- 指纹用 `PairingFingerprint.TryParse`（大小写都收，规范化小写）。
- `AlreadyListening` 时**不重复绑定**：回显当前端口并明说「这次没有重复绑定端口」。
- `NoLocalEnvelope`：本机信封工厂抛 `IOException` / `InvalidOperationException` / `ArgumentException` / `NotSupportedException` 时转成这个 issue，绝不把异常抛给 UI。

## 6 口令与秘密的边界

- 口令只以 `byte[]` 形式存在，来自 PasswordBox，用完在 `finally` / `Dispose` 里 `Array.Clear`；presenter 用 `SecretHandle` 持有接收侧口令的副本，`Dispose` 时清零。
- `SyncTransferReceiveForm` / `SyncTransferSendForm` 是 record，但**都重写了 `ToString()` 把 Secret 打成 `<redacted>`**；`SyncTransferOutcome` 的 `ToString` 不打印信封、JSON 或口令。测试 `The_secret_never_reaches_ToString_and_is_cleared_on_stop` 断言这些字符串里不含明文，且 `HoldsSecretInMemory` 在 Stop 之后变回 false。
- 口令从不写盘、不写日志、不进 `InfoBar` 文案；页面上只有 PasswordBox 一个入口，离开页面即清空。
- 页面顶部的 `StatusBar` 只承载 presenter 给的 `Summary` / `Detail`；传输层本身已保证 `Detail` 里不含凭据。

## 7 生命周期：谁建谁释放

- listener 由 `PrepareReceive`（或一步式 `StartReceiveAsync`）建立，由**三处**收尾：成功的接收路径（`EndReceive`）、`StopReceiveAsync`、`Dispose`。
- `StopReceiveAsync` 是唯一让「正在等待」提前结束的路径：它把 listener/CTS 置空、`_session++`、清零口令，然后 `Cancel()` → 等待中的 `ReceiveOnceAsync` 返回 `Cancelled=true` 的结果（`Summary` = 「已取消：没有收到信封，也没有写入任何内容。」）。
- `_session` 是代际号：Stop 之后到达的旧会话收尾只会清 `_receiving`，不会把新会话的状态（或新 listener）一起清掉。
- `SourceSyncTransferListener.DisposeAsync()` **同步完成**（内部只有 `Stop()`），所以 `Dispose()` 里 `GetAwaiter().GetResult()` 在 UI 线程上不会死锁；页面 `OnUnloaded` 直接同步 `Dispose` 传输 presenter 并清空两个 PasswordBox。
- 重复 `Start` 不会绑第二个端口（`AlreadyListening`）；Stop 之后端口可被重新绑定（`Stopping_the_receiver_releases_the_port` 用裸 `TcpListener` 复绑来证明）。

## 8 测试（29 例，真回环 socket）

四组：

1. **接收与预览**（6）：收一个信封并交出 JSON；JSON 能被应用预览的编解码器读回；presenter 没有 store（反射）；旁边的真实 store 一条未动；帧里没有信封 → envelope-invalid；无对端 → 300 ms 预算下的 transfer-timeout。
2. **发送**（7）：送到真 listener；要求确认时报告对端关闭；TLS 钉扎成功；错 pin → tls-fingerprint-mismatch；明文对端打 TLS listener → 接收侧 tls-handshake-failed；TLS 打明文 → 两侧各自的 tls-* 码；不要求确认时 `DeliveryConfirmed is null` 且文案说「是否接收由对端决定」。
3. **认证与确认**（4）：两端不同口令 → auth-mismatch；一端不认证 → 接收侧 auth-missing（发送侧仍成功）；同口令 → 两侧 FrameCount=3（挑战帧 + 块 + tag 帧）；对端永不关闭 → delivery-unconfirmed 且「不算送达」。
4. **表单、生命周期、文案**（12）：空 host、端口越界 / 非数字、指纹非 64 hex、停后释放端口、重复 Start、口令不进 ToString 且 Stop 后清零、失败不留写、无对端预算耗尽、等待中被取消、TLS 接收先公布指纹、Dispose 幂等且释放端口、未知码原样回显、文案不承诺登录或自动应用。

实跑（D:/mh-w6）：

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --filter "FullyQualifiedName~SyncTransferPresenterTests"
已通过! - 失败:     0，通过:    29，已跳过:     0，总计:    29，持续时间: 5 s

dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug
已通过! - 失败:     0，通过:  1453，已跳过:     0，总计:  1453，持续时间: 6 s
```

全套基线 1424（W6-31 结束时）→ 1453 = +29，其它 1424 例一条未改。

## 9 构建

```
dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build
  MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll
已成功生成。
    0 个警告
    0 个错误
```

（任务书写的是 `-o D:/mh-out-w6`；这条工程用仓库既有的 `MediaHubBinRoot` 形式写同一个目录，产物位置相同，见交付报告的偏离说明。）

## 10 未覆盖（明确不声称）

1. **没有任何 GUI 证据**：按钮点击、ContentDialog、PasswordBox 行为、AutomationId 在 UIA 树里是否可见——全部未验证。本轮纪律禁止启动 MediaHub，也不允许 UIA 探测。
2. **没有跨端联调**：TLS 双端互认、口令认证、送达确认的 Android 侧对端依赖 W6-32，本仓库内无证据。
3. **没有真机长跑**：60 s 接收预算、30 s 发送预算只被单测以更短的预算覆盖，未验证真实网络抖动下的行为。
4. **没有多网卡/多用户场景**：传输只允许回环地址（`not-loopback`），跨机传输不在本任务范围。
5. **送达确认的固有盲区**（W6-30 已记载，此处沿用）：「对端拒绝后关闭」与「对端接收后关闭」在 TCP 上不可区分，因此 `delivery-unconfirmed` 可观测，但「确认成功」不等于「对端已写入」。
6. **取消的落点**：页面在 Unloaded 里 Dispose；若进程被杀，临时证书与内存口令随进程消失，但没有「启动时清理残留」的逻辑（无残留可清，未验证）。

## 11 回退方式

三层都可独立回退，互不牵连：

1. **只回退 UI**：删除 `SyncPage.xaml` 中 `sync.transfer.card` 那个 `<Border>`（连同新增的第 8 条 `RowDefinition`），并删除 `SyncPage.xaml.cs` 里 `// ---- transport (W6-33) begin/end` 之间整段与 `_transfer` 三个字段、`OnUnloaded` 里那五行。页面立即回到 W6-31 的状态。
2. **保留 UI 回退逻辑**：同上，但保留 `SyncTransferPresenter.cs`——它不被其它文件引用，留着不影响任何构建。
3. **只回退测试**：删除 `SyncTransferPresenterTests.cs`，全套回到 1424。传输层（W6-16/24/26/30）与 CLI 一条未动，无需回退。

## 12 Android 联调口径（W6-32 用）

- 帧层不变：4 字节大端长度 + kind（`0x01` 挑战 / `0x02` 块 / `0x03` tag）+ 载荷，全部在 TLS 记录层之内。
- TLS：客户端必须**钉扎服务端证书的 SHA-256 公钥指纹**（不是整张证书的指纹），小写 64 hex；服务端指纹由页面 `sync.transfer.receive.info` 或 CLI `certificate fingerprint:` 行公布。
- 认证：`tag = HMAC-SHA256(secret, nonce ‖ envelope)`，nonce 就是挑战帧的正文；接收端**先验 tag 再查重放**，replay 缓存 64 条。
- 送达确认：Windows 侧把「对端关闭连接」当作送达证据；Android 若要做接收端，**收到完整信封后必须关闭连接**，否则 Windows 的 `--confirm-delivery` 会报 `delivery-unconfirmed`。
- 失败码必须逐字对齐（四套共 20 个），界面文案在 Windows 侧由 `SyncTransferCopy.DescribeReason` 决定，Android 侧不要自造码。

## 13 复跑命令

```powershell
cd D:/mh-w6
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --filter "FullyQualifiedName~SyncTransferPresenterTests"
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug
dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build
```

期望：29 / 1453 / 0 警告 0 错误。**不要**在没有空闲门禁的机器上启动 `MediaHub.dll`。
