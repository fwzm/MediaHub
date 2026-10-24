# W6-37 反向回执帧（`receipt frame 0x04`）

> **状态：IMPLEMENTED_UNVERIFIED。** 代码已实现；35 个新用例全绿（22 个帧/流级 + 11 个 CLI 级 + 2 个 W6-26 超时分类确定性用例），全套 **1488 例通过 / 0 失败 / 0 跳过**；两进程验收脚本 **14/14 PASS**（原 11 例 + 新增 J/K/L）。本轮没有 GUI 证据、没有与 Android 端联调的证据：Android 侧尚未实现 0x04，因此跨端互认只有在 Android 补齐第 8 节的口径之后才能验证。

- 日期：2026-10-24
- 分支：`feature/cross-device-source-sync`（工作树 `D:\mh-w6`，基线 `ff286c9`）
- 前置：W6-16（帧协议）、W6-24（tag 认证）、W6-26（TLS 1.3 + 指纹钉扎）、W6-28（两进程验收脚本）、W6-30（`--confirm-delivery`）
- 范围：`windows/src/MediaHub.Sync`、`windows/src/MediaHub.SyncCli`、`windows/tests/MediaHub.Tests`、`windows/tools/acceptance/w6`、`docs/cross-device`。未动 `MediaHub.App` / `MediaHub.Domain` / `MediaHub.Providers` / `MediaHub.Storage` / `LICENSE`；未改任何既有稳定码的语义；未改 W6-16 的线格式。

## 1 问题：W6-30 的「对端关闭」只是必要条件

W6-30 让 `transfer send --confirm-delivery` 把「对端关闭连接」当作送达证据。W6-28 的 F2 用例把它的边界钉在了表里：

```text
F2-plaintext-confirm-vs-tls :: send=0 serve=2 frames=1 records=0 envelopes=0 codes=[/tls-handshake-failed] 843 ms
```

监听端在 TLS 握手阶段就拒绝了第一个记录、一个信封字节都没读、接收端零写入，而发送端 `deliveryConfirmed=true`。原因不是实现错了，而是**协议里没有反向信息**：对「拒绝后关闭」和「接收后关闭」，TCP 上不可区分（W6-31 第 6 节也把 RST/FIN 不可区分列为已知边界）。关闭是**必要**证据，不是**充分**证据。

W6-37 补的就是那条缺失的反向信息：接收端在真正解出信封之后，回写一个字节。

## 2 回执的语义边界

- 回执 = **「本端读完了全部帧，并且这些字节解出了一个合法信封」**（`SourceSyncInbox.TryDecode` 通过）。
- 回执**不**表示「已应用」：`--apply` 是否落盘、`report.applied` 是多少、是否被审批门禁 withheld，都不进回执语义。接收端解码成功后即回执，apply 的结论仍只在接收端自己的 JSON 里。
- 回执**不**表示「已登录」：与 W6-8 的免责声明一致，导入来源不代表能用。
- 因此「`receiptConfirmed=true`」的准确读法是：**对端读懂了这个信封**，不是「对端接受了这次同步」。

## 3 线格式

帧类型表（前三种沿用 W6-16 / W6-24，第四种是本轮新增）：

| kind | 方向 | 结构 | 含义 |
| --- | --- | --- | --- |
| `0x01` | 发送端 → 接收端 | `4 字节 BE 长度` + `0x01` + UTF-8 挑战 | W6-16 挑战（也是 W6-24 tag 的 nonce） |
| `0x02` | 发送端 → 接收端 | `4 字节 BE 长度` + `0x02` + total(4) + offset(4) + 载荷 | 信封分块 |
| `0x03` | 发送端 → 接收端 | `4 字节 BE 长度` + `0x03` + 32 字节 HMAC | W6-24 tag 帧 |
| `0x04` | **接收端 → 发送端** | **仅 1 字节 `0x04`** | W6-37 回执 |

三条硬约束：

1. **回执没有长度前缀**，总长就是 1 字节。它是唯一一个由接收端主动写出的帧，发送端只读 1 个字节；套上 `PairingFrame` 的 4 字节头会让「读一个字节」变成「读一个可能永不完整到达的帧头」，在失败形态下分不清 `receipt-missing` 和 `receipt-timeout`。
2. **只在对端解出信封之后写**：接收端的写出点位于 `SourceSyncTransferListener.ReceiveOnceAsync` 中「`SourceSyncTransfer.ReceiveAsync` 已返回成功」之后、socket 关闭之前；CLI 还额外传一个 `receiptGate`（`SourceSyncInbox.TryDecode`），解不出的载荷一律沉默。
3. **发送端的输出字节一个也不变**：回执只影响发送端**读**。W6-16 的 95 字节冻结向量在 `--await-receipt` 下仍然逐字节相等（用例 `Without_the_flag_a_send_reports_no_receipt_and_keeps_the_frozen_w6_16_bytes`、`The_receipt_adds_no_byte_to_the_senders_output` 双重钉住）。

接收端写失败（流只读、对端已 RST 等）不会把「已成功接收」变成失败：结果记录上的 `ReceiptWritten` 记 `false`，其余字段不变。

## 4 开关矩阵

| `serve --send-receipt` | `send --await-receipt` | 线上字节 | 发送端结论 | 接收端结论 |
| --- | --- | --- | --- | --- |
| 关 | 关 | 与 W6-16/W6-24 完全一致 | `receiptConfirmed=null`，`receiptWritten=null` | 同左 |
| 开 | 关 | 接收端多写 1 字节（发送端看不到） | `null`（没问） | `receiptWritten=true` |
| 关 | 开 | 发送端一字节不多写 | `receipt-missing`（exit 2） | 正常 0，可能已应用 |
| 开 | 开 | 接收端回 1 字节 | `receiptConfirmed=true` | `receiptWritten=true` |

矩阵第 3 行是 K 用例刻意钉住的形态：**接收端成功应用了 2 条记录（serve 0），发送端仍然 exit 2 `receipt-missing`**。这不是误报——它说明「对端没配置回执」时，发送端不能声称自己知道对端读懂了；要让两边都绿，接收端必须显式开 `--send-receipt`。

## 5 失败码表（`SourceSyncReceiptReasons`，独立封闭集）

| 码 | 谁报 | 触发条件 |
| --- | --- | --- |
| `receipt-missing` | 发送端 | 等回执时读到 EOF（对端关闭但没写 0x04），或读流时 IOException |
| `receipt-timeout` | 发送端 | 预算（默认 2000 ms）到期仍未读到任何字节 |
| `receipt-invalid` | 发送端 | 读到的第一个字节不是 `0x04`（消息里回显实际字节，如 `0x05`） |
| `receipt-not-enabled` | 任一端 | 本端**无法承载**回执：发送端的流不可读、接收端的流不可写 |

四个码与既有三套集合并列、互不重叠（用例 `The_receipt_codes_are_their_own_closed_set` 逐对比对 10 码 / 5 码 / 4 码 / 1 码）。CLI 侧这四码都归 `input-problem`（exit 2），未改 `CliExitCode` 的分类表。

`receipt-not-enabled` 与 `receipt-missing` 的分工容易混：前者是**本端流的能力问题**（配置错误，重试无意义），后者是**对端行为**（对端没开或对端关闭）。对端没开回执不报 `receipt-not-enabled`。

## 6 与 `--confirm-delivery` 的关系

- 回执是**更强**的证据：`--await-receipt` 与 `--confirm-delivery` 同时给出时，**以回执为准**，发送端不再等关闭（`SourceSyncTransferClient.SendAsync` 内部把投递给 `RunAsync` 的 delivery 置空），因此 `deliveryConfirmed` 为 `null`，`receiptConfirmed` 才是结论。
- 两个字段始终都出现在 JSON 里（`confirmDelivery` / `deliveryConfirmed` / `awaitReceipt` / `receiptConfirmed`），脚本可以据此区分「没问」与「问了没成」。
- 只开 `--confirm-delivery` 的旧行为一字未改（W6-30 的 19 个用例仍全绿）。

## 7 安全边界

- **明文回环下回执可伪造**：任何能连上该端口的本机进程都能在收下字节后回一个 `0x04`，于是发送端会报告「对端读懂了」。回执本身**不带身份、不带 nonce、不做重放防护**（它就是 1 个字节）。
- 回执要成为可信证据，必须叠加 **TLS 钉扎**（W6-26，确认对端是那把证书的持有者）或 **共享口令 tag**（W6-24，确认对端知道口令）。两项都可与本功能同时开启，用例 `Tls_and_the_receipt_work_together`、`Authentication_and_the_receipt_work_together` 覆盖。
- 回执**不泄漏**信封内容：单字节常量，无长度、无摘要、无设备标识。
- 与 W6-30 相同：`--await-receipt` 让发送端多等一个预算（默认 2000 ms），这会改变**时序**但不改变**字节**。

## 8 Android 对齐口径（给 Android 端）

1. 帧类型常量追加 `ReceiptFrameKind = 0x04`，**总长 1 字节，不带 4 字节长度前缀**（不要复用 `PairingFrame` 的写入器）。
2. 接收侧（Android 作接收端）：只有在「全部 chunk 收齐 + 信封解码成功」之后、关闭 socket 之前，写 1 个 `0x04` 并 flush；解码失败、帧非法、超时一律**不写**。
3. 发送侧（Android 作发送端）：写完最后一帧并 flush 之后，读 **1 个字节**：`0x04` → 成功；EOF（读返回 0）→ `receipt-missing`；其它字节 → `receipt-invalid`；预算用尽 → `receipt-timeout`。
4. 四个稳定码**逐字一致**：`receipt-missing` / `receipt-timeout` / `receipt-invalid` / `receipt-not-enabled`。
5. JSON 字段名与 Windows 侧一致：`sendReceipt` / `awaitReceipt`（布尔）与 `receiptConfirmed` / `receiptWritten`（布尔或 null）。
6. 默认关闭：Android 未实现时与今天完全互通（Windows 不开 `--await-receipt` 就不会读回执；Android 不发回执也不会被读到）。

## 9 未覆盖

- **Android 侧未实现**（本仓库不含 Android 代码）：没有跨端 `0x04` 往返证据；第 8 节是口径而不是已验事实。
- **GUI 未接**：SyncPage 的传输卡片（W6-33）没有回执开关，presenter 也没有把 `receipt` 暴露出去；本轮只做 CLI/库层。
- **回执与多接收者无关**：协议是一对一单连接，没有「回执聚合」概念。
- **回执的可信度依赖 TLS/认证**（第 7 节）：单独使用时只能防「对端静默」，防不住「本机恶意进程假冒对端」。
- **重放**：回执字节可被中间人缓存后回放给同一个发送端（同一连接内无意义，跨连接需要攻击者能接管 socket）；未做 nonce 绑定。
- **roundtrip 不交换回执**（`--send-receipt` / `--await-receipt` 都只允许单个 socket 动词）：两方向各自的本机进程内传输，close 已足够。

## 10 实测

验收脚本（两进程，真 `dotnet MediaHub.SyncCli.dll` 子进程）：

```text
cases: 14, passed: 14, failed: 0, total: 21380 ms
residual SynCli processes: 0
RESULT: 14 passed, 0 failed
EXIT=0
```

三个新用例的实测行：

```text
J-receipt-written   send 0 receipt confirmed / serve receiptWritten / 1 envelope   send=0 serve=0 frames=1 records=2 envelopes=1 codes=[/] 1037 ms PASS
K-receipt-missing   send 2 receipt-missing / serve 0 / 2 records                   send=2 serve=0 frames=0 records=2 envelopes=1 codes=[receipt-missing/] 768 ms PASS
L-receipt-timeout   send 2 receipt-timeout / no write                               send=2 code=receipt-timeout no receipt in 2275 ms PASS
```

单元/CLI 测试：全套 `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug` → **1488 通过 / 0 失败 / 0 跳过**（基线 1453 + 22 + 11 + 2）。

## 11 文件与行数

| 文件 | 行数 | 角色 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/SourceSyncReceiptFrames.cs` | 293 | 新：四码 + `SourceSyncReceiptOptions/Result` + `WriteAsync/AwaitAsync` |
| `windows/src/MediaHub.Sync/SourceSyncTransfer.cs` | 565 | 改：结果记录加 `ReceiptConfirmed/ReceiptWritten`；`SendAsync` 等回执 |
| `windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs` | 760 | 改：listener 写回执（含 gate）、client 委托投递、`Remaining()` 助手 |
| `windows/src/MediaHub.Sync/SourceSyncPeerRound.cs` | 574 | 改：`SendAsync/SendPayload/ReceiveOn/ReceiveOnce` 透传 |
| `windows/src/MediaHub.Sync/SourceSyncInbox.cs` | 293 | 改：import 成功后写回执；扩展方法传 gate |
| `windows/src/MediaHub.Sync/SourceSyncTlsTransport.cs` | 726 | 改：握手超时改成独立 deadline（W6-26 flaky 修正） |
| `windows/src/MediaHub.SyncCli/CliInvocation.cs` | 180 | 改：`SendReceipt/AwaitReceipt` |
| `windows/src/MediaHub.SyncCli/CliParser.cs` | 561 | 改：两个 flag + 命令白名单 + help |
| `windows/src/MediaHub.SyncCli/SyncCliRunner.cs` | 2364 | 改：serve/send 接线 + JSON/文本字段 |
| `windows/tests/MediaHub.Tests/SourceSyncReceiptTests.cs` | 708 | 新：22 例 |
| `windows/tests/MediaHub.Tests/SyncCliReceiptTests.cs` | 516 | 新：11 例 |
| `windows/tests/MediaHub.Tests/SourceSyncTlsTransportTests.cs` | 499 | 改：+2 例（超时分类确定性） |
| `windows/tools/acceptance/w6/transfer-tls-auth.ps1` | 990 | 改：+J/K/L 与回执断言 |

## 12 复跑

```powershell
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug
pwsh -NoProfile -File windows/tools/acceptance/w6/transfer-tls-auth.ps1 -OutDir D:/mh-verify/w6-37
```

脚本退出码 0 才表示 14 例全 PASS；`<OutDir>/w6-28-summary.json` 里每例都带 `receiptConfirmed` / `receiptWritten`。

## 13 W6-39 追加：同步页与 roundtrip 的接线

W6-37 只在传输层与 CLI 上开了回执；**同步页（presenter）与 `transfer roundtrip` 当时都还用「等对端关闭」当证据**。W6-39 把回执接到这两处，按 W6-37 的结论（关闭是必要证据、不是充分证据）统一改用真回执。

### 13.1 presenter（`windows/src/MediaHub.Sync/SyncTransferPresenter.cs`）

- 接收：`SyncTransferReceiveForm.WriteReceipt`（默认 `false`）→ `SourceSyncReceiptOptions.Sending()` 传给 `listener.ReceiveOnceAsync(..., receipt, Decodes)`。**gate 就是解码谓词** `SourceSyncInbox.TryDecode`，所以「解不出的载荷绝不回执」在 presenter 层同样成立；`SyncTransferReceiveState.WritesReceipt` 让监听中状态句如实回显。
- 发送：`SyncTransferSendForm.AwaitReceipt`（默认 `false`）→ `SourceSyncReceiptOptions.Awaiting(ReceiptBudget)`，预算 `SyncTransferPresenter.ReceiptBudget` 默认 2000 ms（= `SourceSyncReceipt.DefaultBudget`）。
- 文案：`DescribeSent` 在拿到回执时以「，并收到回执：对端读懂了这份信封（0x04）。」结尾，且**不再**说「对端在 X ms 内关闭了连接」——回执比关闭更强，两者不混着说。`DescribeReason` 从 20 码扩到 24 码，四个 receipt 码逐条中文、句尾仍带原码。
- 默认关闭时：receipt 为 `null` / `Off`，线上字节与等待行为与 W6-33 完全一致，W6-16 的 95 字节冻结向量逐字节相等（有单测钉住）。

### 13.2 roundtrip（`SourceSyncPeerRound.ExchangePayloadAsync` + CLI `transfer roundtrip`）

- `ExchangeAsync(...)` / `ExchangePayloadAsync(...)` 新增 `TimeSpan? receiptBudget = null`：给了预算，接收端就 `Sending()`、发送端就 `Awaiting(budget)`；不给则完全不碰回执（旧行为）。
- **回执失败不把已送达的信封变成失败轮**：发送端返回 `receipt-missing` / `receipt-timeout` / `receipt-invalid` 时，`ExchangePayloadAsync` 只把它记进新字段 `SourceSyncPeerRoundResult.ReceiptReason`，轮结果仍是 `Succeeded`（信封确实到了），并把 `ReceiptConfirmed` 如实带出。判据是 `SourceSyncReceiptReasons.IsReceiptReason(sent.Reason)`。
- `SourceSyncPeerRoundResult` 因此多两个字段：`bool? ReceiptWritten`（接收端是否写出回执，来自 `SourceSyncInboxResult.ReceiptWritten`）与 `string? ReceiptReason`。**既有字段一个都没改名**。
- CLI `transfer roundtrip` 现在默认就交换回执（不再跑 W6-30 的 close 等待）：每轮 JSON 多 `receiptConfirmed` 与 `receiptReason`（只在该轮真的问过回执时出现，避免与 serve/send 的 `WriteReceiptFieldsJson` 撞出重复键），文本每轮多一行 `receipt: received (0x04) - the peer decoded this envelope` 或 `receipt: none (<code>) - the envelope arrived, the peer did not confirm it`。冻结向量不受影响：回执不加帧、不加信封字节（`frames == SourceSyncTransfer.ChunkCount(bytes)` 有单测钉住）。
- `--send-receipt` / `--await-receipt` 在 `transfer roundtrip` 上仍是**用法错**（`option-not-allowed-for-command:...:transfer-roundtrip`）——roundtrip 由内部决定，不接受操作员逐侧开关。

### 13.3 实测（本轮自己跑的）

- `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --no-build` → **1515 通过 / 0 失败 / 0 跳过**（基线 1488 + 27：presenter 16 + roundtrip 4 + CLI roundtrip 6，其中 1 个 Theory 展开 4 例）。
- `pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1 -SkipBuild -OutDir D:/mh-verify/w6-39` → **12/12 用例 PASS**，新增 R8（presenter 回执 + 真 CLI `--await-receipt`）、R9（presenter `AwaitReceipt` 打真 `serve --send-receipt --apply`）、R10（真 serve 不写回执 → presenter `receipt-missing` 且对端零写入）；`residual MediaHub.SyncCli processes: 0`。

### 13.4 GUI 入口（W6-43 追加）

同步页的传输卡片现在有两个勾选框和一行结果，W6-37 的回执字节第一次有了人工入口：接收侧 `sync.transfer.receive.receipt`（「接收后写回执（0x04）」→ `SyncTransferReceiveForm.WriteReceipt`）、发送侧 `sync.transfer.send.receipt`（「等待对端回执（0x04）」→ `SyncTransferSendForm.AwaitReceipt`），发送结果单独一行 `sync.transfer.send.receipt-result`（`TransferReceiptResultText`）。默认都不勾，所以不勾时线上字节与 W6-16 冻结向量仍然逐字节相等。渲染规则见 `W6-33-SYNC-TRANSFER-UI.md` §14；**GUI_UNVERIFIED**：勾选框在真实窗口里的初始态、结果行在 UIA 树中的可见性都没有证据（本轮禁止启动 MediaHub）。
