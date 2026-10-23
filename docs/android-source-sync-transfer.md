# Android 侧来源同步：分块信封传输（W6-K）

本文件只讲一件事：Windows 把一份同步信封（`SourceSyncEnvelope`）送进 Android 的那段字节协议，
以及本仓 Kotlin 实现与 Windows 权威实现的互操作证据。

- 权威实现（只读参照，不在本仓）：`D:/mh-w6/windows/src/MediaHub.Sync/SourceSyncTransfer.cs`（347 行）、
  `SourceSyncTransferEndpoint.cs`（395 行）、`PairingFrame.cs`（94 行）、`SourceSyncTransferReasons.cs`。
- 本仓实现：`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransfer.kt`（357 行）、
  `SourceSyncTransferClient.kt`（101 行）；帧读写的唯一实现抽到
  `core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportSupport.kt`（`PairingFrameIo:27`、`FrameRead:15`、`LoopbackOnly:74`）。
- 信封结构与规划语义不在此文件：见 `docs/android-source-sync.md`；配对手势见 `docs/android-pairing.md`。

## 1. 结论先行

1. 帧格式与 Windows 逐字节一致：黄金信封 1093 字节 → 帧流 1139 字节，双向 hex 全等（本地编码器能复现 Windows 的字节流）。
2. 10 个失败码与 Windows 同名同序；19 条 Windows 亲口产出的坏帧流，本端逐条给出同一个码。
3. 判定次序逐条照抄 Windows，包括「收满即返回，尾随帧一个字节都不读」与「声明总长超 8 MiB 先于任何分配就拒绝」。
4. 客户端只连回环地址，且**不做 DNS 解析**（只认 `127.0.0.1` / `::1` / `localhost` 这类字面量），非回环在拨号前就返回 `not-loopback`。
5. 本层不做 TLS、不做指纹钉扎、不解析信封内容（`envelope-invalid` 永远由 `SourceSyncCodec` 产出）——边界见第 10 节。

## 2. 帧格式

每一帧（与配对通道共用同一帧壳，见 `PairingFrame.kt`）：

| 段 | 长度 | 说明 |
| --- | --- | --- |
| 帧长 | 4 字节，大端 | 后面载荷的字节数，合法范围 1..8192 |
| 载荷 | 帧长 | 首字节是帧类型，其余按类型解释 |

载荷按首字节分两类：

| kind | 名称 | 载荷内容 |
| --- | --- | --- |
| `0x01` | 挑战帧 | UTF-8 挑战文本（1..8191 字节），去空白后不得为空 |
| `0x02` | 数据块 | 9 字节头：4 字节大端 `total` + 4 字节大端 `offset` + 1..8183 字节数据 |

一条合法帧流：`挑战帧?` + `数据块 × chunkCount`。挑战帧可选；出现时必须是第一帧。
信封 1093 字节 → 1 块；8183 字节整 → 1 块；8184 字节 → 2 块；8 MiB 整 → 1026 块。

## 3. 常量（两端逐字相等，测试钉住）

| 常量 | 值 | 来源 |
| --- | --- | --- |
| 信封上限 `MAXIMUM_ENVELOPE_BYTES` | 8 × 1024 × 1024 = 8388608 | `SourceSyncTransfer.cs:76` / `SourceSyncTransfer.kt:83` |
| 挑战帧类型 `CHALLENGE_FRAME_KIND` | 0x01 | `SourceSyncTransfer.cs:79` / `SourceSyncTransfer.kt:86` |
| 数据帧类型 `CHUNK_FRAME_KIND` | 0x02 | `SourceSyncTransfer.cs:82` / `SourceSyncTransfer.kt:89` |
| 块头 `CHUNK_HEADER_BYTES` | 9 | `SourceSyncTransfer.cs:85` / `SourceSyncTransfer.kt:92` |
| 块载荷 `CHUNK_PAYLOAD_BYTES` | 8183 = 8192 − 9 | `SourceSyncTransfer.cs:88` / `SourceSyncTransfer.kt:95` |
| 帧上限 | 8192（配对帧壳，同步层不自带） | `PairingFrame.cs:27` / `PairingFrame.kt` |
| 挑战上限 `MAXIMUM_CHALLENGE_BYTES` | 512（UTF-8 字节） | `SourceSyncTransfer.cs:91` / `SourceSyncTransfer.kt:98` |
| 读超时 | 15 秒（对齐 Windows 监听端默认超时） | `SourceSyncTransferEndpoint.cs:142` / `SourceSyncTransferClient.kt:97` |

`chunkCount(n)`：`n <= 0` 返回 0，否则向上取整（`SourceSyncTransfer.cs:94-101` / `SourceSyncTransfer.kt:101`）。

## 4. 失败码（10 个，同名同序）

`SourceSyncTransferReasons.ALL`（`SourceSyncTransfer.kt:33`）与 `SourceSyncTransferReasons.cs` 声明序一致，测试逐项比对：

| # | 码 | 本端何时产出 |
| --- | --- | --- |
| 1 | `not-loopback` | 客户端目标不是回环字面量（拨号前） |
| 2 | `listener-failed` | 本层不产出（Android 没有监听端） |
| 3 | `connect-failed` | 端口越界、连接被拒 |
| 4 | `frame-invalid` | 帧内容不合法（类型、块头、偏移、总长矛盾等） |
| 5 | `frame-truncated` | 对端提前结束/帧长越界，帧壳判定失败 |
| 6 | `payload-too-large` | 信封为空或超 8 MiB、挑战超 512 字节、对端声明总长超上限 |
| 7 | `challenge-mismatch` | 要求的挑战没先声明，或声明的与期望不符 |
| 8 | `envelope-invalid` | 本层不产出（归 `SourceSyncCodec`） |
| 9 | `transfer-timeout` | 读超时（本层把 `SocketTimeoutException` 折成此码，见第 7 节） |
| 10 | `io-failed` | 其它读写失败 |

## 5. 接收判定次序（逐条照抄 Windows `ReceiveAsync`）

1. 读一帧；帧壳失败（头不足 / 长度 ≤ 0 / 长度 > 8192 / 体不足）→ `frame-truncated`。
2. 帧数 +1（挑战帧也计入）。
3. `kind == 0x01`：帧序号不为 1 → `frame-invalid`；挑战去空白后为空 → `frame-invalid`；
   要求了挑战且不等于期望 → `challenge-mismatch`。
4. `kind != 0x02` → `frame-invalid`（未知帧类型）。
5. 首帧就是数据块、但要求了挑战 → `challenge-mismatch`。
6. 帧长 ≤ 9（只有头没有载荷）→ `frame-invalid`。
7. `total <= 0` → `frame-invalid`；`total > 8 MiB` → `payload-too-large`（**先于分配**）；`offset < 0` → `frame-invalid`。
8. 首块：`offset != 0` → `frame-invalid`，然后才按 `total` 分配；后续块：`total` 与首块不一致 → `frame-invalid`。
9. `offset != 已收字节` → `frame-invalid`；`已收 + 本块载荷 > total` → `frame-invalid`。
10. 拷贝数据；`已收 == total` 立即返回成功——**多余帧不再读**（不是疏忽：多读一帧会把「对端多写」变成「本端阻塞」）。

`frame-truncated` 的措辞分两种，便于定位：首帧都没收全时说「对端没有送出完整的一帧」，已收到部分数据时说
「对端在 X / Y 字节处停止」（`SourceSyncTransfer.kt` 内 `receive` 的 `FrameRead.Rejected` 分支）。

## 6. 发送侧

`sendTo(OutputStream, envelope, challenge)`（`SourceSyncTransfer.kt:159`）：

| 情况 | 结果 | 写了多少 |
| --- | --- | --- |
| 信封 0 字节 | `payload-too-large` | 0 字节 |
| 信封 > 8 MiB | `payload-too-large` | 0 字节 |
| 挑战（去空白后 UTF-8）> 512 字节 | `payload-too-large` | 0 字节 |
| 写流抛 IOException | `io-failed` | 已写出的帧保留，消息带「写到第 N 帧时失败」 |
| 成功 | `Ok(envelope, 帧数, 去空白后的挑战)` | 全部 |

空白挑战等价于「不发挑战帧」，成功结果里的 `challenge` 为 `null`；接收侧对空白期望值也按「不要求」处理。

## 7. 与 Windows 的差异（2 处，均有理由）

1. **超时归属**：Windows 在端点层用 15 秒总超时把静默对端折成 `transfer-timeout`；Android 没有端点层，
   因此 `SourceSyncTransfer.receive` 直接把 `SocketTimeoutException` 折成 `transfer-timeout`，其它 IO 失败仍是 `io-failed`。对外码保持一致。
2. **`envelope-invalid` 不在此层产出**：Windows 的 `ReceiveAsync` 也不产出该码（它属于信封解析），本端同样只负责字节重组。

另有能力差异（不是语义差异，来自两端实现边界）：Windows 侧有监听端（`SourceSyncTransferListener`）与 `CancellationToken` 取消通道，
本端只有回环客户端 + 读超时；两端在**传输层都没有 TLS/指纹钉扎**（`SourceSyncTransferEndpoint.cs` 全文无 SslStream/X509，
指纹属于配对层），因此跨设备传输前必须补，见第 10 节。

## 8. 互操作证据（不是手写夹具）

向量由 `D:/mh-verify/transfer-vectors/`（临时 console 工程，`ProjectReference` 到 `MediaHub.Sync.csproj`，**不入库**）
直接调用 Windows 的 `SendAsync/ReceiveAsync` 生成，落盘为 `core/common/src/test/resources/sync/windows-transfer-vectors.json`
（49231 B，sha256 `d9b52eea73db9283af026346d8efcee1eab5109f64d1f4e63da2aceeca76c8ae`）。冻结挑战 `transfer-vector-challenge-01`。

| 向量 | 内容 | 钉住什么 |
| --- | --- | --- |
| `withChallenge` | 真实 device-a 信封（1093 B）+ 挑战帧，共 2 帧 / 1139 B | 解码逐字节相同；本地编码 hex 全等 |
| `withoutChallenge` | 同信封 1 帧 / 1106 B | 无挑战路径与 `challenge = null` |
| `threeChunks` | 16466 B 合成信封（`byte[i] = (i*31+7) and 0xFF`）3 块 + 挑战 = 4 帧 / 16538 B | 多块路径：逐块 `total/offset/payloadLength/frameLength` |
| `goldenWithTrailingFrame` | 黄金帧流后接一帧垃圾 | 收满即返：帧数与干净流相同 |
| `errors` | 19 条坏帧流 | 每条 `reason` 与 Windows 逐字相同 |

19 条错误向量的码分布：`frame-invalid` 10 条、`frame-truncated` 5 条、`payload-too-large` 2 条、`challenge-mismatch` 2 条。
名字：unknown-frame-kind、challenge-required-but-chunk-first、wrong-challenge、challenge-after-chunk、empty-challenge-frame、
offset-overlap、negative-offset、total-inconsistent、total-above-ceiling、total-int-max、total-zero、header-only-chunk、
first-chunk-not-at-zero、chunk-runs-past-total、truncated-header、truncated-body、empty-stream、frame-length-zero、
frame-length-above-frame-ceiling。

串联证据：把解码出的黄金信封交给 `SourceSyncCodec` + `SourceSyncPlanner`（空本地集），得到
`apply=2 / ignored=1 / unsupported=1`，与 `windows-plan-device-b-empty.json` 里 Windows CLI 的 counts 逐桶相等（CLI 的 reason 是轮级码，只能比桶）。

生成器红例（记下来防止复现）：`challenge-after-chunk` 最初写成「收满的块 + 挑战帧」，Windows 判成功——因为收满即返回、
后面的挑战帧根本不读；改成 `total=20 / offset=0 / 10 字节` 的不完整块再跟挑战帧，才得到 `frame-invalid`。

## 9. 回环端到端

`SourceSyncTransferClient`（`SourceSyncTransferClient.kt:19`）：

- `receiveFrom(host, port, expectedChallenge)` / `sendTo(host, port, envelope, challenge)`；
- 回环判定只解析字面量、**不做 DNS**：`localhost` 直接取 `getLoopbackAddress()`，IPv4/IPv6 用正则认字面量后再 `getByName` 并校验 `isLoopbackAddress`；
- 非回环 → `not-loopback`（拨号之前）；端口不在 1..65535 → `connect-failed`（拨号之前）；
- 读超时 → `transfer-timeout`；连接被拒 → `connect-failed`；其它 IO → `io-failed`；
- 无论成功失败都关 socket（`finally`）。

测试里真开 `ServerSocket(0, 8, 127.0.0.1)`：一处回放黄金帧流给客户端收，一处用 `SourceSyncTransfer.receive` 当对端收客户端发的信封；
另有「非回环绝不连到监听端（accept 超时）」「静默对端超时」与「超时后 socket 真的被关（对端读到 EOF）」三条。

## 10. 未覆盖项（如实列出，别当成已做）

1. **TLS 与指纹钉扎**：传输层两端都没有（Windows `SourceSyncTransferEndpoint.cs` 只用明文 TCP + 回环限定）。真机跨设备接入前必须补 `SSLSocket` + 配对层指纹校验。
2. **真机/真网卡**：全部用例都在 JVM 回环上跑，未覆盖 Wi-Fi 下的分片、MTU、半关闭行为。
3. **UI 接线**：本层没有被任何界面调用（导入流程仍走文件/字符串），属于下一步工作。
4. **并发/压力**：没有多客户端并发、没有 >8 MiB 的**真实**信封（只有 16466 B 的合成信封证明多块路径）。
5. **取消与中断**：Windows 侧接受 `CancellationToken`，本端没有取消通道（读超时是唯一出口）。
6. **`envelope-invalid` 联调**：本层不产出该码，需在接入 `SourceSyncCodec` 的上层验证错误传递。

## 11. 本次改动的文件

| 文件 | 变化 |
| --- | --- |
| `core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportSupport.kt` | 新增：`FrameRead`(15) / `PairingFrameIo`(27) / `LoopbackOnly`(74)，帧读写唯一实现 |
| `core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportClient.kt` | 改动：改用上面的共享实现，267 → 189 行（+4 / −82），对外行为与失败码不变 |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransfer.kt` | 新增：常量、码表、`chunkCount`、`encodeFrames`、`encode`、`sendTo`、`receive`、`decode` |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClient.kt` | 新增：只连回环的 socket 客户端 |
| `core/common/src/test/kotlin/.../sync/SourceSyncTransferVectorsTest.kt` | 新增：11 例，Windows 向量对照 |
| `core/common/src/test/kotlin/.../sync/SourceSyncTransferRoundTripTest.kt` | 新增：17 例，边界/往返/坏流 |
| `core/common/src/test/kotlin/.../sync/SourceSyncTransferLoopbackTest.kt` | 新增：10 例，回环端到端 |
| `core/common/src/test/resources/sync/windows-transfer-vectors.json` | 新增：Windows 生成的向量（49231 B） |
| `docs/android-source-sync-transfer.md` | 新增：本文件 |

## 12. 实跑计数

命令（Windows，`cmd` 里跑，标准输出重定向到日志再读）：

```
gradlew.bat :core:common:testDebugUnitTest --rerun-tasks --console=plain
```

`BUILD SUCCESSFUL in 1m 18s`，`17 actionable tasks: 17 executed`；JUnit XML（`core/common/build/test-results/testDebugUnitTest`）实测：

| 指标 | 数值 | 基线（task-55 之前） |
| --- | --- | --- |
| suites | 27 | 24 |
| tests | 339 | 301 |
| failures | 0 | 0 |
| errors | 0 | 0 |
| skipped | 0 | 0 |

三个新套件：`SourceSyncTransferVectorsTest` 11、`SourceSyncTransferRoundTripTest` 17、`SourceSyncTransferLoopbackTest` 10（合计 38）。

## 13. 复现注意

1. `cmd /c "…set \"JAVA_HOME=C:\Program Files\Java\jdk-21\"…"` 这种嵌套引号会被截断成 `C:\Program`，报 `JAVA_HOME is set to an invalid directory`。
   稳定做法：把 `cd /d` / `set "…"` / `call gradlew.bat … > log 2>&1` 写进一个 `.bat`，再 `cmd /c C:\…\run.bat`。
2. `:core:common:bundleLibCompileToJarDebug` 报 `classes.jar: 另一个程序正在使用此文件` → 先杀 `java` 进程再重跑（本轮开工前即按此清理，构建一次通过）。
3. 测试里断言的「消息措辞」必须照实现原文（例：首块偏移错实现说「第一块从 4 开始，而不是 0」，不含「起点」）。
