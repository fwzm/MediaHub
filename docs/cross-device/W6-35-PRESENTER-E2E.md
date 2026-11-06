# W6-35 传输 presenter 的 headless 端到端证据

状态：**IMPLEMENTED_UNVERIFIED**（9 个用例全部真实起子进程并通过，脚本连跑 3 次全绿；本轮没有 GUI 证据，没有与 Android 端联调证据）。
日期：2026-10-24 · 分支：feature/cross-device-source-sync · 工作树：D:/mh-w6
前置：W6-33（SyncTransferPresenter + SyncPage 传输卡片）、W6-24（对端认证）、W6-26（TLS 1.3 + 指纹钉扎）、W6-30（送达确认）
范围：只新增验收脚本与一个不入 sln 的测试工程；**不动** windows/src、windows/tests、README、CI。

## 1 为什么不在主套件里

W6-33 的传输 presenter 在主套件里有 29 个用例，但它们全部用**进程内**的对端（真 TcpListener 或真 SourceSyncTransferListener + 本进程的 helper）。那证明 presenter 自己的逻辑，不证明它说的是**已发布命令行**的线协议：帧格式、`--json` 字段、`certificate fingerprint:` 与 `listening 127.0.0.1:` 的 stderr 契约，都可能悄悄偏移而主套件全绿。

同时 README 钉住了主套件的口径「1453 passed / 0 skipped」。会拉起 `dotnet` 子进程、需要真实端口与 temp 目录的用例放进主套件，就该被 skip（环境不具备时）或偶发（端口竞争时）——两种都会破坏那个口径。因此这里的用例放在**独立工程** `windows/tools/acceptance/w6/presenter-e2e/PresenterE2E.csproj`：不入 `windows/MediaHub.Windows.sln`、不被 `windows/tests/MediaHub.Tests` 引用，由脚本按需运行（与 EngineProbe / ResumeProbe 同等处理）。

## 2 证据原则：断言不得自证

每个用例的断言只接受 presenter 之外的东西：

| 证据来源 | 用在哪 |
| --- | --- |
| 子进程退出码 | R1（send=0）、R4（serve≠0）、R5/R6a（serve=0） |
| 子进程 `--json` stdout 字段 | R2 `"tls":true`、R3 `"authenticate":true` 与 `"secretEnv"`、R5 `"applied":2` |
| 对端 root 目录里的 store 文件 | R5/R6a `new FileLocalSourceStore(peerRoot).GetAll().Count == 2`、R4 两份文件都不存在 |
| 端口可重新绑定 | R7a 用裸 `TcpListener` 复绑同一端口 |
| 发送端文件时间戳 + witness 空目录 | R1 store 文件 `LastWriteTimeUtc` 未变、witness 目录无文件 |
| 口令明文不出现 | R3 断言 presenter 的 ToString / ReceiveState / LastReceive 与子进程 stdout+stderr 都不含明文 |

「零写入」在这些用例里靠**看目录**，不看 presenter 的返回值——presenter 根本不持有 store（W6-33 的 29 例里有一条反射用例专门钉住这点）。

## 3 被测对象与运行方式

- 被测：`windows/src/MediaHub.Sync/SyncTransferPresenter.cs`（页面用的同一个对象，构造函数只收 deviceId 与本地信封工厂）。
- 对端：`dotnet windows/src/MediaHub.SyncCli/bin/Debug/net10.0/MediaHub.SyncCli.dll transfer serve|send ...`，参数与运维手输的一致。
- DLL 路径经环境变量 `MH_PRESENTER_E2E_CLI` 传入（测试工程不引用 SyncCli，编译期就不可能与它耦合）。未设置时用例直接抛 `InvalidOperationException` 指明要用脚本跑。
- 工程只引用 `windows/src/MediaHub.Sync`；TFM `net10.0-windows`、x64、`win-x64`，包版本走 `windows/Directory.Packages.props`（Microsoft.NET.Test.Sdk 17.13.0 / xunit 2.9.3 / xunit.runner.visualstudio 3.0.2），零新增 NuGet。

```
pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1
pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1 -SkipBuild -KeepArtifacts -OutDir D:/mh-verify/w6-35-keep
```

脚本做五件事：清掉上一轮残留的 CLI 子进程 → 构建 SyncCli（-v:m，记 build.log）→ 设 `MH_PRESENTER_E2E_CLI` 跑 `dotnet test`（记 presenter-e2e.log）→ 逐例解析「已通过/失败」并检查日志里没有口令明文、清 temp 根 → 打印残余 `MediaHub.SyncCli` 进程数、写 `presenter-e2e-summary.json`，全绿才 `exit 0`。

## 4 用例表（9 例）

| 用例 | 对端 | 关键断言 | 实测耗时 |
| --- | --- | --- | --- |
| R1 `plaintext_receive_accepts_a_real_cli_send_and_writes_nothing` | CLI `transfer send`（明文） | send=0；`Succeeded`；`Bytes==UTF8.GetByteCount(EnvelopeJson)`；`Frames==ChunkCount(Bytes)`；信封 `DeviceId==win-b`；发送端 store 时间戳未变；witness 为空；`ReceiveState.Listening==false` | 231 ms |
| R2 `tls_receive_hands_its_fingerprint_to_a_real_pinned_send` | CLI `transfer send --tls --expect-fingerprint` | `ReceiveState.CertificateFingerprint` 匹配 `^[0-9a-f]{64}$`；send=0；stdout 含 `"tls":true` | 513 ms |
| R3 `authenticated_receive_takes_the_secret_from_the_child_environment_only` | CLI `transfer send --authenticate --secret-env MH_W635_SECRET` | `Frames==ChunkCount+2`（挑战帧+块+tag）；stdout 含 `"authenticate":true`、`"secretEnv":"MH_W635_SECRET"`；口令明文不出现在 stdout/stderr/各处 ToString | 279 ms |
| R4 `a_wrong_pin_is_refused_and_the_peer_writes_nothing` | CLI `transfer serve --tls --apply`，presenter 用 64 个 `a` 当 pin | `Reason==tls-fingerprint-mismatch`；serve≠0；对端 root 无 sync-sources.json 与 sync-baseline.json | 397 ms |
| R5 `a_send_to_a_real_serve_applies_both_sources` | CLI `transfer serve --apply` | `Succeeded`、`DeliveryConfirmed==null`；serve=0；`FileLocalSourceStore(peerRoot).GetAll().Count==2` 且含两条 DisplayName；baseline 存在；stdout 含 `"applied":2` | 395 ms |
| R6a `confirm_delivery_against_a_real_serve_reports_the_close` | 同上 + `ConfirmDelivery=true` | `DeliveryConfirmed is true`；serve=0；对端 2 条记录 | 315 ms |
| R6b `confirm_delivery_against_a_silent_peer_is_delivery_unconfirmed` | 裸 `TcpListener`：accept 后既不读也不关 | `Succeeded==false`；`Reason==delivery-unconfirmed`；`DeliveryConfirmed != true`；Summary 含「这不算送达」 | 2 s |
| R7a `stopping_the_receiver_releases_the_port_for_a_rebind` | 无 | `StopReceiveAsync` 后状态回 Idle；裸 `TcpListener` 能复绑同端口；二次 Stop 仍 `Succeeded` | 4 ms |
| R7b `cancelling_a_waiting_receive_reports_cancelled_and_writes_nothing` | 无（取消等待） | `Cancelled`；`Reason==null`；Bytes/Frames=0；`EnvelopeJson==null`；Summary 含「已取消」；witness 为空 | 221 ms |

任务书写的是 7 个用例（R1..R7）；R6 与 R7 各自拆成 a/b 两个断言方向，所以是 9 个 `[Fact]`。

## 5 实跑输出（原文尾行）

```
已通过 MediaHub.PresenterE2E.SyncTransferPresenterEndToEndTests.R5_a_send_to_a_real_serve_applies_both_sources [497 ms]
...
测试运行成功。
测试总数: 9
     通过数: 9
总时间: 6.5323 秒

tests:       9 passed, 0 failed, 0 skipped, 9 total
cases:       9/9 passed
secret leak: False
residual:    0
RESULT: 9 passed, 0 failed (9 cases)
```

三次整跑：14257 ms / 12043 ms（`-SkipBuild`）/ 17353 ms，全部 `9 passed, 0 failed`、`exit 0`、`residual MediaHub.SyncCli processes: 0`、temp 根清理后 left=0。同轮复查主套件仍 `已通过! - 失败: 0，通过: 1453，已跳过: 0，总计: 1453`——本任务没有往主套件加任何用例。

## 6 脚本自身踩过的坑（写在这里省下一次返工）

- vstest 的合计行有**两种形状**：「失败: 0，通过: 9，已跳过: 0，总计: 9」（有失败时）与「测试总数: 9 / 通过数: 9」（全绿时）。首版只认前者，于是**全绿也报 0/0 并 exit 1**。现在两种都解析，并以「测试运行成功」作为额外门槛。
- 解析逐例结果的正则必须带 `\S*` 前缀吃掉全限定类名，否则 `R1_...` 只会匹配到行尾。
- `$KeepArtifacts` 是 `SwitchParameter`，直接塞进 `ConvertTo-Json` 会变成 `{"IsPresent":false}`；要写 `[bool]$KeepArtifacts`。

## 7 未覆盖

1. **GUI 面**：没有点过按钮，没有截屏、没有 UIA。传输卡片在真实窗口里的布局/焦点/键盘可达性仍是 GUI_UNVERIFIED。
2. **Android 端**：全部对端都是本机 Windows CLI 子进程；跨端 TLS/认证联调依赖 W6-32 的 Android 实现。
3. **端口竞争**：R2/R4/R5/R6a 依赖 CLI 侧 25 ms 轮询拿到 serve 的端口；在极端负载下轮询可能超预算（30 s），本轮未制造该压力。
4. **长连接与多连接**：presenter 设计为「接收一次连接」，不测连续多次连接、不测并发多连接。
5. **TLS 证书持久化/撤销**：沿用 W6-26 的临时自签证书，不测证书轮换与吊销。
6. **口令内存清零**：R3 只证明口令不出现于可见输出与 ToString；`Array.Clear` 是否真的发生在 GC 之前无法从进程外断言（W6-33 的 29 例里用 `HoldsSecretInMemory` 覆盖了状态层面）。

## 8 Android 联调口径

- 明文：4 字节大端长度前缀 + kind(0x01 挑战 / 0x02 块 / 0x03 tag) + 载荷；单块载荷 ≤ 8183 字节；信封 ≤ 8 MiB。
- 认证（W6-24）：tag = HMAC-SHA256(secret, UTF-8(nonce) ‖ envelope)，nonce 就是挑战帧里的文本，tag 帧必须在最后一块之后。
- TLS（W6-26）：TLS 1.3 + 服务端临时自签证书，客户端钉扎 **SHA-256(SubjectPublicKeyInfo)** 的 64 位小写 hex；服务端在 stderr 打印 `certificate fingerprint: <hex>` 供拨号侧取用。
- 送达确认（W6-30）：发送端写完即等对端关闭，预算 2000 ms；对端**不关**才可能得到 `delivery-unconfirmed`——对端「拒绝后立刻关闭」在 TCP 上与「收下后关闭」不可区分（W6-30 已记为已知盲区）。
- CLI 侧命令与 JSON 字段（`authenticate`/`secretEnv`/`tls`/`expectFingerprint`/`certificateFingerprint`/`confirmDelivery`/`deliveryConfirmed`）即本仓库契约，Android 端应逐字段对照。

## 9 回退方式

三个文件都是新增，删掉即回退：`windows/tools/acceptance/w6/presenter-e2e.ps1`、`windows/tools/acceptance/w6/presenter-e2e/`（csproj + 测试文件）、`docs/cross-device/W6-35-PRESENTER-E2E.md`。没有任何产品代码或主套件用例因本任务被修改。
