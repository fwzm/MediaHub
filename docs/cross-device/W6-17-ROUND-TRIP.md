# W6-17 — 一次「导出 → 回环传输 → 对端导入轮」的可断言对象

状态：**IMPLEMENTED_UNVERIFIED**（单元测试全绿；未跑 GUI，未在第二台真机上跑，未与 Android 互操作）

## 1. 这一层补的是什么

W6-16 把「信封的字节怎么过回环 socket」做完了（`SourceSyncTransfer`），W6-8/W6-10 把「收到信封之后怎么预览、审批、写入、推进基线」做完了（`SourceSyncImportRound`）。缺的是把两段接成**一个可断言的东西**：调用方说「把这台机器上的来源发给那台机器」，然后拿到一份报告——写了几条、跳过了什么、基线有没有前进、失败时是哪个稳定码。

新增：`windows/src/MediaHub.Sync/SourceSyncPeerRound.cs`

```csharp
var sender   = new SourceSyncPeerRound("dev-a", senderStore);                 // 本机
var receiver = new SourceSyncPeerRound("dev-b", receiverStore, baselines);    // 对端

SourceSyncPeerRoundResult result = await SourceSyncPeerRound.ExchangeAsync(sender, receiver, Write);

// result.Succeeded / result.Reason / result.Applied / result.Skipped / result.Items
// result.IsNoChange / result.Report.BaselineUpdated / result.SenderDeviceId / result.ReceiverDeviceId
```

报告对象 `SourceSyncPeerRoundResult`：`Succeeded`、`Reason`、`Detail`、`SenderDeviceId`、`ReceiverDeviceId`、`Envelope`、`Preview`、`Report`、`FrameCount`、`Bytes`、`Applied`、`Skipped`、`Items`、`NeedsApproval`、`FindItem(syncSourceId)`、`IsNoChange`。

方法：`Export`、`Preview`、`ApplyAsync`、`SendAsync`（只发不导）、`SendPayloadAsync`、`ReceiveOnceAsync`（起监听收一次并导入）、`ReceiveOnAsync`（用已起的监听器）、静态 `ExchangeAsync`、`ExchangePayloadAsync`（字节已就绪时用它，测试篡改字节就是走这条）。

## 2. 帧格式没有新造

完全复用 W6-16：每帧仍是 `PairingFrame`（4 字节大端长度 + ≤8192 字节载荷）。载荷首字节区分种类：`0x01` 挑战帧（可选，最多 512 字节 UTF-8），`0x02` 数据块帧（1 + 4 字节总量 + 4 字节偏移 + 数据，单块 8183 字节）。信封上限 8 MiB，超限显式 `payload-too-large`，绝不静默截断。本层只做编排，不碰字节布局。

## 3. 测试证明的四件事

测试文件 `windows/tests/MediaHub.Tests/SourceSyncPeerRoundTests.cs`（22 例）。两台设备 = 两个临时目录，各自 `sync-sources.json` + `sync-baseline.json`；socket 全部由产品代码建在 127.0.0.1 上。

| 要求 | 用例 | 断言方式 |
| --- | --- | --- |
| A→B 写入条数与 A 的来源数一致，B 的基线落盘 | `Exchange_AppliesEverySourceAndAdvancesTheReceiversBaseline` | `result.Applied == senderStore.GetAll().Count == 2`；`receiverBaselines.FilePath` 存在且 `Load("dev-a").Entries.Count == 2` |
| B→A 立刻回传 = NoChange，且 A 的基线字节不变 | `ReturnTrip_IsNoChangeAndLeavesTheSendersBaselineBytesUntouched` | `IsNoChange`、`Applied == 0`、`senderStore.WriteCount == 2`、`File.ReadAllBytes(baseline)` 与回传前逐字节相等 |
| 改一条来源 → 一次 UPDATE，同一信封再来一次 NoChange | `Update_PropagatesOnceAndTheSameEnvelopeIsThenNoChange` | 第一次 `Applied == 1`、`Action == Update`、`Reason == higher-revision`、B 的 `RecordRevision == 2`；第二次 `IsNoChange`、`Action == NoChange`、两份文件字节不变 |
| 墓碑不得静默删除，B 的来源仍在 | `Tombstone_IsProposedNotAppliedAndTheSourceSurvives`、`Tombstone_WithAnExplicitAllowRemovesTheSource` | 默认 `Action == Delete`、`Reason == tombstone`、`RequiresApproval == true`、`Applied == 0`、B 仍 2 条；显式 `allowedIds: ["src-2"]` 时才 `Applied == 1` |
| 失败面：非回环被拒 / 篡改 1 字节被拒，对端零写入 | `NonLoopbackTarget_…`、`NonLoopbackListener_…`、`TamperedEnvelope_WithBrokenJson_…`、`TamperedEnvelope_WithAnUnknownSchemaVersion_…` | `Reason == not-loopback` / `envelope-invalid`；`GetAll().Count == 0`；存储文件不存在 |
| 幂等还要跨「进程重启」成立 | `BaselineOnDiskMakesASecondRoundANoChangeForAFreshRound`、`Exchange_TwiceKeepsTheReceiversFilesByteIdentical` | 同一目录上新建 store 对象 + 全新快照 id 再传一次：`IsNoChange`、`BaselineUpdated == false`、`sync-sources.json` 字节不变 |
| 只预览不写盘 | `PreviewWithoutTransfer_PlansTheSameRowsTheRoundWouldWrite` | `preview.Items` 的行与随后真实轮的行数一致，预览后 store 仍 0 条 |
| 写入器失败不外抛 | `WriterThatThrows_IsReportedInsteadOfEscapingToTheCaller` | 报告 `Applied == 0`、`Withheld == 1`、基线文件不存在，调用方不接异常 |

另有：挑战帧校验（`MatchingChallenge_…` / `ChallengeMismatch_…`）、对端沉默超时（`SilentPeer_TimesOutWithTheStableCode`，300 ms）、发送方自己的库不被写（`SendingDoesNotWriteToTheSendersOwnStore`）、墓碑指向对端没有的来源（`Tombstone_ForASourceThePeerNeverHad_IsANoChange`，`no-local-source`）、陈旧墓碑（`StaleTombstone_IsIgnoredRatherThanApplied`，`IgnoreStale` / `stale-revision`）、空来源集（`Exchange_WithNoSourcesSucceedsAndWritesNothing`）。

## 4. 失败码

都来自 `SourceSyncTransferReasons`（封闭集，10 个），报告里原样放进 `Reason`：

| 码 | 什么时候 | 对端有没有写 |
| --- | --- | --- |
| `not-loopback` | 目标地址不是 127.0.0.1 / ::1 / localhost。**先拒绝再建 socket**（见 §6） | 没有，连 socket 都没有 |
| `listener-failed` | 回环地址合法但端口起不来（被占用等） | 没有 |
| `connect-failed` | 拨号失败 / 端口为 0 | 没有 |
| `frame-invalid` | 首字节种类未知、偏移跳字节、总量帧间不一致、块越过总量 | 没有，解码前就停 |
| `frame-truncated` | 对端提前关闭，或帧长度不在 1..8192 | 没有 |
| `payload-too-large` | 信封 > 8 MiB，或挑战 > 512 字节（发送侧连一个字节都不写） | 没有 |
| `challenge-mismatch` | 挑战帧内容与接收侧期望不符，或接收侧期望挑战而对端没发 | 没有 |
| `envelope-invalid` | 字节拿到了但不是可读信封；`Detail` 带契约原因，如 `malformed-json: …`、`unsupported-schema-version:9: …`、`not-utf8`、`empty-payload` | 没有 |
| `transfer-timeout` | 对端没在预算内完成（默认 15 s，可传 `timeout`） | 没有 |
| `io-failed` | 传输层 I/O 异常 | 可能已部分写，报告 `Succeeded == false` |

写入器**拒绝**某一条（`SourceSyncWriteOutcome.Refused`，或内部抛 `IOException`/`UnauthorizedAccessException`/`InvalidOperationException`）不是失败：它进 `Report.Withheld`，整轮仍 `Succeeded`，基线不前进。

## 5. 语义边界

- **一次轮 = 复制来源定义，不是同步内容。** 传过去的是 server 的地址、类型、名字、账号域、revision；媒体库不进这条通道，凭据默认也不进（`includeSecrets` 默认 false，带密码的记录会变成 `secrets-require-approval` 且永远需要人工批准）。
- **配对 ≠ 同步 ≠ 登录。** 配对只钉指纹（W6-12）；一次成功的轮只说明「对端拿到了这些来源的描述」；每个设备仍要自己点「验证」登录。导入文案沿用 W6-8：导入不代表已登录。
- **墓碑是提案，不是命令。** 对端的副本可能是最后一份、凭据会随之消失，所以默认 `Skipped`，只有调用方显式 `allowedIds` 才删。
- **主被动的方向由调用方决定。** `SenderDeviceId` / `ReceiverDeviceId` 只用于展示，不携带权限含义。

## 6. 只回环，而且先拒绝

`SourceSyncTransferAddress.TryResolveLoopback` 在**创建任何 socket 之前**判定地址；不允许的地址直接返回 `not-loopback`，不会 bind 后才发现失败——绑 LAN 地址会让 Windows 弹出防火墙授权框。本机实测（探针已删，仅引用输出）：

```
Listener.Start(192.168.1.5:8080) => Started=False Reason=not-loopback Listener=null
  detail: Only 127.0.0.1, ::1 and localhost are accepted, so '192.168.1.5' was refused before any socket was created: binding or dialling a non-loopback address would make Windows ask the user to open the firewall.
Listener.Start()             => Started=True Address=127.0.0.1 Port=13841
PeerRound.SendAsync(192.168.1.5:8080) => Succeeded=False Reason=not-loopback
  detail: Only 127.0.0.1, ::1 and localhost are accepted, so '192.168.1.5' was refused before any socket was created: binding or dialling a non-loopback address would make Windows ask the user to open the firewall.
```

因此这一层今天能证明的是「同一台机器上两个进程/两个数据目录之间的一次轮」，跨机器的字节搬运仍走 W6-7 CLI 导出的信封文件。

## 7. 实测

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter "FullyQualifiedName~SourceSyncPeerRoundTests"
已通过! - 失败:     0，通过:    22，已跳过:     0，总计:    22，持续时间: 770 ms - MediaHub.Tests.dll (net10.0)

dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
已通过! - 失败:     0，通过:  1175，已跳过:     0，总计:  1175，持续时间: 5 s - MediaHub.Tests.dll (net10.0)

dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build
  MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll
已成功生成。
    0 个警告
    0 个错误
```

基线：本工作树在本轮之前是 1153 例（`--filter "FullyQualifiedName!~SourceSyncPeerRoundTests"` 实测 1153 通过），加本轮 22 例 = 1175。任务书写的基线是 1087，那是更早的快照；本工作树 HEAD 已到 `d05ee40`。

## 8. NOT_COVERED

1. **没有加密、没有身份认证。** 传输层是明文 socket；挑战串只是防止「接错会话」，读得到帧的人可以原样重放。跨机器上线前必须先定传输安全（TLS 或 Noise），这不在 W6-17 范围。
2. **没有跨机实测。** 只在回环上验证；两台真机、防火墙、NAT、休眠重连都没测。
3. **没有 Android 对端。** 帧格式与信封契约是 Windows 侧自洽的；Android 是否按同一帧/同一契约实现，未验证。
4. **没有断点续传/重试。** 一轮要么完整走完要么失败；失败后从头再传，没有按块续。
5. **没有并发控制。** 同一个对端同时两轮没有锁；两个进程同时对同一目录写入的后果由 store 的原子写兜底，但语义未定义。
6. **没有 UI。** 本层没有接到任何页面；SyncPage 今天仍是「导出/导入文件」的路径，把它接到这个对象属于后续 UI 任务。
7. **没有凭据迁移。** `includeSecrets` 默认为 false，即使传 true，落地仍是 `secrets-require-approval` 的人工门；真正的凭据迁移未实现。
