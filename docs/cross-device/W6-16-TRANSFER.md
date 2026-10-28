# W6-16 — 回环传输：配对后直接传信封，不再靠人搬

状态：**IMPLEMENTED · 测试验证（仅回环）**。已在单机回环 socket 上跑通「发送信封 → 接收 → 预览 → 审批门 → 写入 → 推进基线」全链路，
并验证同一快照重复传输零写入。**没有**在两台物理机之间验证过，也**没有**接入 GUI。

## 1. 能力边界（先读这段，再读代码）

| 维度 | 现状 | 原因 |
| --- | --- | --- |
| 网络范围 | **只允许回环**：127.0.0.1 / ::1 / localhost | 绑定或拨号任何非回环地址都会让 Windows 弹防火墙授权框；实现里在创建 socket **之前**就拒绝（见第 5 节） |
| 传输加密 | **无**。TCP 上直接是 UTF-8 信封字节 | 本轮目标是「不用 U 盘」，不是「不安全网络可用」。跨机传输前必须先上 TLS/密钥协商 |
| 对端身份 | **不在传输层验证**。挑战串由调用方带外约定（expectedChallenge） | 回环上没有「对端」；跨机时才有意义，留待后续 |
| 接收落盘 | 走 SourceSyncImportRound：预览 → 审批门 → 写入 → 推进基线 | 与手工导入是**同一套**代码路径，不另起炉灶 |
| 幂等 | 同一信封传两次，第二次 Applied=0（NoChange），两个 JSON 文件字节不变 | 基线落盘（sync-baseline.json）驱动，非内存状态 |
| 帧长上限 | 单帧载荷 ≤ 8192（PairingFrame 契约），信封上限 8 MiB，超过即显式拒绝 | 绝不静默截断：见第 4 节校验 |

## 2. 文件

| 文件 | 作用 |
| --- | --- |
| windows/src/MediaHub.Sync/SourceSyncTransferReasons.cs | 10 个稳定失败码（封闭集合，测试断言） |
| windows/src/MediaHub.Sync/SourceSyncTransfer.cs | 分块帧协议 + SendAsync / ReceiveAsync（只做字节搬运，不懂信封） |
| windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs | 回环门禁 + Listener / Client 的 socket 包装 |
| windows/src/MediaHub.Sync/SourceSyncInbox.cs | 收件箱：把收到的字节解成信封，交给 SourceSyncImportRound 并回填报告 |
| windows/tests/MediaHub.Tests/SourceSyncTransferTests.cs | 34 例，含两端真实回环 socket 的 e2e 与流路径帧计数用例 |

## 3. 帧格式

每一帧仍是既有的 PairingFrame：**4 字节大端长度 + 载荷**（载荷 1..8192 字节）。本层只在载荷里加 1 字节类型：

| 帧类型 | 载荷布局 | 说明 |
| --- | --- | --- |
| 0x01 挑战帧 | 0x01 + UTF-8 挑战串（≤512 字节） | 必须是第 1 帧；不带挑战串时不会出现 |
| 0x02 数据块帧 | 0x02 + 4 字节大端总字节数 + 4 字节大端偏移 + 数据（≤8183） | 偏移必须严格等于已收字节数 |

派生常量：ChunkPayloadBytes = 8183（= 8192 − 9），ChunkHeaderBytes = 9，MaximumEnvelopeBytes = 8 MiB，MaximumChallengeBytes = 512。
块数 = ceil(总字节数 / 8183)；`ChunkCount(0)` = 0，因此空信封在发送端就被 payload-too-large 拒掉。

为什么分块而不是提高单帧上限：PairingFrame.MaximumPayloadBytes=8192 是 W6-3b 已提交的线上契约，其它用例（配对）依赖它；
改它会同时改两个功能的风险面。分块只在传输层内部可见，配对协议不动。

为什么限制 8 MiB：信封是「本机媒体源清单」，实测单条记录约 300 字节；8 MiB 相当于 2.7 万条来源，够用且给出确定的拒绝点，
避免一个手滑的文件把内存吃光。

## 4. 接收端校验（不静默截断）

按到达顺序逐条校验，任一不满足即停止并给稳定码：

1. 挑战帧出现在第 1 帧之外 → frame-invalid；expectedChallenge 非空但对端没发挑战帧 → challenge-mismatch；内容不等 → challenge-mismatch。
2. 未知帧类型（例如 0x03）→ frame-invalid，detail 带上看到的字节。
3. 首块偏移不为 0、偏移不等于已收字节数、各帧声明的总字节数不一致、数据越过声明总数 → frame-invalid。
4. 声明总字节数 > 8 MiB → payload-too-large（Envelope 返回空）。
5. 帧头长度越界或对端提前关闭（ReadAsync 返回 null）→ frame-truncated，detail 给出「已收 N / 共 M 字节」。
6. IO 异常 → io-failed。**接收端任何路径都不向外抛异常。**

## 5. 回环门禁：先拒绝，再考虑建 socket

`SourceSyncTransferAddress.TryResolveLoopback(address, port, out endpoint, out detail)` 是唯一入口：

- 空白 → 127.0.0.1；localhost → IPAddress.Loopback（**不做 DNS**）；::1 → IPv6Loopback；其它必须是可解析且 IsLoopback 的 IP 字面量。
- 长度 > 256、端口不在 0..65535、非 IP 字面量的主机名 → 各自给可读 detail。
- `SourceSyncTransferListener.Start` 先过门禁，失败直接返回 `Failed(not-loopback, detail)`，**TcpListener 根本不会被 new**（测试断言 Listener 为 null）。
- `SourceSyncTransferClient` 同样先过门禁，失败**不会拨号**（port==0 另给 connect-failed "A dial needs a real port"）。

本机实测证据（临时探针跑出来的原文，探针已删除）：

    TryResolveLoopback(192.168.1.5:8080) => False | Only 127.0.0.1, ::1 and localhost are accepted, so '192.168.1.5' was refused before any socket was created: binding or dialling a non-loopback address would make Windows ask the user to open the firewall.
    TryResolveLoopback(0.0.0.0:8080) => False | Only 127.0.0.1, ::1 and localhost are accepted, so '0.0.0.0' was refused before any socket was created: binding or dialling a non-loopback address would make Windows ask the user to open the firewall.
    TryResolveLoopback(:)) => False | ':)' is not an IP literal and this build does not resolve host names for transfers; use 127.0.0.1, ::1 or localhost.
    Listener.Start(192.168.1.5) => Started=False Reason=not-loopback Listener=null
    Listener.Start(0.0.0.0) => Started=False Reason=not-loopback
    Listener.Start() default => Started=True Address=127.0.0.1 Port=6616

注意最后一行：默认参数绑上的是 127.0.0.1 的真端口——回环门禁不是把功能关死，而是把「绑定」限制在回环。

## 6. 失败码表（SourceSyncTransferReasons，封闭 10 条）

| 码 | 何时出现 | 是否会写盘 |
| --- | --- | --- |
| not-loopback | 地址不是 127.0.0.1 / ::1 / localhost（含 0.0.0.0、::、192.168.*、example.com、:)) | 否，socket 未创建 |
| listener-failed | 回环端口绑不上（被占用等 SocketException） | 否 |
| connect-failed | 拨号失败；或 port==0 无法拨号 | 否 |
| frame-invalid | 未知帧类型、块乱序、偏移不符、总量帧间不一致、越过声明总量 | 否 |
| frame-truncated | 对端提前关闭，或帧头长度越界 | 否 |
| payload-too-large | 空信封、信封 > 8 MiB、挑战 > 512 字节、对端声明总量 > 8 MiB | 否（发送端一字节不写） |
| challenge-mismatch | 挑战不等于 expectedChallenge，或期望挑战但对端没发 | 否 |
| envelope-invalid | 字节不是合法信封（非 UTF-8 为 not-utf8、空为 empty-payload，其余为 SourceSyncCodec 的 reason，如 missing-field:snapshotId、unsupported-schema-version:2） | 否 |
| transfer-timeout | 对端在超时内没完成（默认 15 秒） | 否 |
| io-failed | 写入器抛 IOException/UnauthorizedAccessException/InvalidOperationException/ArgumentException | 可能已写部分条目 |

envelope-invalid 的 detail 里带的是**上游 reason**（SyncContractException.Reason 或 "not-utf8"/"empty-payload"），本层不另造同义词——
一个条件只能有一个稳定码，这是 W6-15 定下的 D10 规则，这里继续遵守。

## 7. 用法（调用方视角）

主机侧（被连的一方）：

    SourceSyncTransferStartResult start = SourceSyncTransferListener.Start("127.0.0.1", 0);
    if (!start.Started) { /* start.Reason, start.Detail */ }
    await using SourceSyncTransferListener listener = start.Listener!;
    SourceSyncInbox inbox = new(localSources, baselineStore);
    SourceSyncInboxResult result = await listener.ReceiveAndImportAsync(inbox, writer, expectedChallenge);

客机侧（主动连的一方）：

    SourceSyncInbox inbox = new(localSources, baselineStore);
    SourceSyncInboxResult result = await inbox.DialAndImportAsync("127.0.0.1", port, writer, expectedChallenge);

两侧都拿到 SourceSyncInboxResult：Succeeded / Reason / Detail / Envelope / Preview / Report / FrameCount / Bytes，以及 IsNoChange。
挑战串（预期值）必须由调用方带外准备；本层不生成、不交换挑战——那是配对协议（W6-3b）的职责。

## 8. 幂等与「真的落盘了」的证明方式

测试 LoopbackTransfer_RepeatingTheSameSnapshotWritesNothing 的做法（这也是验收口径）：

1. 第一次传输：Applied=1、BaselineUpdated=true、baseline 文件存在。
2. 记下 sync-sources.json 与 sync-baseline.json 的字节。
3. **新建** SourceSyncInbox 与新的 FileSourceSyncBaselineStore（同目录）——模拟重启，彻底丢掉内存状态。
4. 再传同一信封：IsNoChange=true、Applied=0、BaselineUpdated=false、两个文件字节与第 2 步完全一致。

第 3 步是关键：如果基线只活在内存里，换实例后第二次传输会重复写入，测试就会红。

## 9. 实测结果

- 传输层定向测试：已通过! - 失败: 0，通过: 34，已跳过: 0，总计: 34，持续时间: 2 s - MediaHub.Tests.dll (net10.0)
- 全量测试：已通过! - 失败: 0，通过: 1087，已跳过: 0，总计: 1087，持续时间: 6 s（基线 1053 + 本轮 34）
- 复核补的一例：SourceSyncInbox.ReceiveAsync（流路径）原先不回填 FrameCount/Bytes（只有 DialAndImportAsync 与扩展方法回填），已修并加 StreamReceive_ReportsTheFramesTheTransferUsed 钉住。
- App 构建：MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll / 已成功生成 / 0 个警告 / 0 个错误

## 10. NOT_COVERED（明确没做的事）

1. **没有跨机验证**：所有 e2e 都在 127.0.0.1 上；没有在两台机器之间跑过，也没有跨机防火墙/网卡/IPv6 场景。
2. **没有加密、没有认证**：TCP 上是明文信封；挑战串只做了「本次传输与调用方预期一致」的比对，不构成身份认证。
3. **没有接入 GUI**：SyncPage 未出现「接收/发送」按钮，本轮只交付库与测试。
4. **没有与配对会话联调**：SourceSyncTransfer 只吃 Stream；把 PairingTransport 的流接进来需要配对侧先提供已建立的流，本轮未做。
5. **没有重传/断点续传**：中途失败即整轮失败，调用方重来；分块偏移只用于校验顺序，不支持续传。
6. **没有大信封性能测试**：只测了 2 块的 9 KB 量级；8 MiB 上限从未在真实数据上跑过。
7. **挑战串不防重放**：没有 nonce 缓存，同一挑战可以重放（回环场景下无实际影响）。
8. **超时只覆盖整体**：15 秒是整轮预算（CancelAfter），不是逐帧空闲超时。
