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

## 14. 接入导入流程（W6-23）

> 本节由 task-57 追加：把第 9 节的传输客户端接进真实的导入服务，形成「收帧 → 解码 → 落库」闭环。
> 入口放在 `core:database` 而不是 `core:common`，因为要调 `AndroidSourceSyncService`（在 `core:database`），
> 而 `core:common` → `core:database` 的反向依赖是被禁止的。`core:common` 侧本轮一行未改。

### 14.1 入口签名

`core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncTransferImportService.kt`（107 行）：

- `fun interface SourceSyncImportGateway`（:15）——`suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult`。抽成接口是为了让传输层测试不碰 Room 也能数清调用次数。
- `sealed interface SourceSyncTransferImportOutcome`（:29）：`TransferFailed(reason, detail)`（:31）/ `EnvelopeRejected(reason, message)`（:34）/ `Imported(result)`（:37），派生 `isTransferFailure` / `isEnvelopeRejection` / `isImported` 与 `reasonOrNull`（:46；导入成功且未被拒时为空）。
- `class SourceSyncTransferImportService(gateway, client = SourceSyncTransferClient())`（:60）；次构造器 `constructor(service: AndroidSourceSyncService, client = …)`（:65）把服务包成网关。
- `suspend fun receiveAndImport(host, port, expectedChallenge = null)`（:75）：`withContext(Dispatchers.IO) { client.receiveFrom(...) }`，`Failed` → `TransferFailed`，`Ok` → 交给 `importEnvelopeBytes`。
- `suspend fun importEnvelopeBytes(bytes)`（:96）：UTF-8 → `SourceSyncCodec.tryDecode` → `Failed` → `EnvelopeRejected`，`Ok` 才调 `gateway.importEnvelope(json)`。**网关抛出的异常不 catch** —— 传输层没有资格把上层的稳定码换成兜底文案。

### 14.2 失败域划分（三段各自出码）

| 阶段 | 结果类型 | 码来源 | 碰库？ |
| --- | --- | --- | --- |
| 传输（拨号 / 帧层） | `TransferFailed` | `SourceSyncTransferReasons`：`not-loopback`、`connect-failed`、`frame-truncated`、`challenge-mismatch`、`transfer-timeout`、`io-failed` | 否（网关调用数 0） |
| 解码（信封） | `EnvelopeRejected` | `SourceSyncCodec`：`malformed-json`、`missing-field:<名字>`、`unsupported-schema-version:<版本>` | 否（网关调用数 0） |
| 导入（规划 / 写库） | `Imported` | `SourceSyncImportResult.rejectedReason`（如 `duplicate-sync-source-id`）与四桶计数 | 是 |

三段不共用一个兜底码：`frame-truncated` 与 `malformed-json` 在排障时指向完全不同的两件事，合并会丢掉这个区别。

### 14.3 幂等证据

`SourceSyncTransferImportTest.the_same_envelope_a_second_time_writes_nothing`（`SourceSyncTransferImportTest.kt:65`）用同一个 `AndroidSourceSyncService` 实例、同一份 Windows 黄金信封、两条独立连接跑两轮：

| 轮次 | applied | requiresApproval | ignored | createLocalSource | updateLocalSource |
| --- | --- | --- | --- | --- | --- |
| 第一轮 | 2 | 1 | 1 | 2 | 0 |
| 第二轮 | 0 | 1 | 3 | 2（未增加） | 0 |

第二轮的 `ignored` 由 1 涨到 3：emby / jellyfin 两条已在本机且 revision 相同 → `NO_CHANGE`；`quantum-nas` 仍是未知类型（`UNSUPPORTED`，进 requiresApproval）；墓碑仍指向不存在的来源（`IGNORE_STALE`）。
两轮的 `envelopeContentHash` 相等 —— 幂等判据是内容摘要，不是字节（`snapshotId` 每次不同）也不是墙上时间。

### 14.4 本次新增测试（19 例，0 skipped）

`core/database/src/test/kotlin/com/mediahub/core/database/sync/SourceSyncTransferImportTest.kt`（537 行）：

| 组 | 例数 | 代表用例 |
| --- | --- | --- |
| 真实回环闭环 | 3 | `golden_envelope_over_a_loopback_socket_...`（:37，断言 2/1/1 + create 两次）、`the_same_envelope_a_second_time_writes_nothing`（:65）、`bytes_after_a_complete_envelope_do_not_change_the_result` |
| 传输失败零写入 | 6 | 端口已关、非回环 host、挑战不符、帧头 0 长、帧长声明 8193、对端静默；每条都断言网关调用数 0 |
| 载荷非信封零导入 | 5 | `malformed-json`、`missing-field:schemaVersion`、`unsupported-schema-version:9`、非法 UTF-8、空载荷；每条都断言网关调用数 0 |
| 正常路径与边界 | 5 | 网关收到逐字相同的文本、服务内拒绝仍算 `Imported`、网关异常不被吞、三种结果各带唯一稳定码、黄金副本 sha256 |

夹具全部是本文件私有（`core:common` 的测试源码不在 `core:database` 的可见范围内）：`LoopbackPeer`（:425，只绑 `127.0.0.1:0`，`await()` 收尾）、`ImportFakeStore`（:458，记 create / update / list 调用数）、`RecordingGateway` / `ThrowingGateway`。
黄金信封从 `core/database/src/test/resources/sync/windows-envelope-device-a.json` 读（1093 B，sha256 `049688090c5e8dd2f5d79b69f8375cc4c37d54f3ac15abd738861139f42334c8`），并有一条用例专门钉住这个 sha256 —— 复制就可能漂移，所以要有一条测试盯着。

### 14.5 实跑计数

```
gradlew.bat :core:database:testDebugUnitTest --console=plain
```

`BUILD SUCCESSFUL in 16s`，`70 actionable tasks: 1 executed, 69 up-to-date`。JUnit XML（`core/database/build/test-results/testDebugUnitTest`）亲数：**15 suites / 145 tests / 0 failures / 0 errors / 0 skipped**；
本套件单列 `tests="19" skipped="0" failures="0" errors="0"`。扣除本套件后其余为 14 suites / 126 tests，与本轮之前一致（无回归）。

本轮再次踩到 §13 第 1 条：`cmd /c` 里嵌套 `set "JAVA_HOME=…"` 的引号被截断，报 `JAVA_HOME is set to an invalid directory: …jdk1.8.0_381`。
可行做法：用 pwsh 先设置 `$env:JAVA_HOME` 与 `$env:ANDROID_HOME`，再跑 `cmd /c` 把 gradlew 输出重定向到日志；环境变量由子进程继承，不必在 cmd 里再 set。

### 14.6 未覆盖项（如实列出）

1. **instrumentation 未跑**：本轮按任务书只跑 JVM 单测，真机 Room + 真 socket 的组合没有验证。
2. **没有与真实 Windows 端点联调**：对端是 `ServerSocket` 夹具，帧流由本端编码器生成（编码器与 Windows 逐字节相同这一点由第 8 节的向量对照保证，不是本节）。
3. **多块信封 + 挑战帧的组合**：本节 19 例只用了 1093 B 的单块信封（`chunkCount == 1`）；多块路径已由 `SourceSyncTransferRoundTripTest` 覆盖，但没和真实导入服务串起来跑。
4. **取消/中断**：`receiveAndImport` 没有取消通道，`CancellationException` 由 `withContext` 自然传播，未专门测。
5. **UI 接线**：本层仍无界面入口（host / port / challenge 由调用方提供）。
6. **并发**：同一服务实例被并发调用未测；`AndroidSourceSyncService` 的逐条写库没有事务包裹，多线程下的一致性未验证。

## 15. 对端认证（W6-25）

本节把 Windows 的 W6-24 传输认证搬到 Android 侧：认证是可选的一层，默认关闭时第 8 节的字节一个都不变；打开后线格式在原帧流末尾多一帧 0x03 tag。权威契约是 D:/mh-w6/docs/cross-device/W6-24-TRANSFER-AUTH.md（167 行），实现权威是 D:/mh-w6/windows/src/MediaHub.Sync/SourceSyncTransferAuthenticator.cs 与 SourceSyncTransfer.cs —— 本轮只读它们，没有改 Windows 任何文件。

### 15.1 线格式与 tag 计算

帧仍然是 4 字节大端长度 + 载荷，新增的只有帧类型 0x03：

| 帧 | kind | 长度 | 位置 |
| --- | --- | --- | --- |
| 挑战帧 | 0x01 | 1 + 挑战字节 | 可选，第一块数据之前 |
| 数据块 | 0x02 | 9 + payload（≤ 8183） | 1..n 块 |
| tag 帧 | 0x03 | 恒 33 字节（1 + 32） | 必须在最后一块数据之后 |

tag 的算法（`SourceSyncTransferAuth.kt:74` 的 `createTag`、`:97` 的 `verifyTag`）：

- 消息 = nonce 的 UTF-8 字节 ‖ 信封原始字节，两者之间没有分隔符；
- nonce 取自挑战帧文本 `trim()` 后的 UTF-8（与 Windows 的 `challenge.Trim()` 同义）；
- 算法 HMAC-SHA256，密钥是 secret 的原始 UTF-8 字节（不做 base64 解码、不做派生）；
- 比较走 `MessageDigest.isEqual`（常量时间），长度不是 32 或口令为空直接返回 false。

常量与 Windows 同名同值，集中在 `SourceSyncTransferAuth.kt:55-64`：`TAG_FRAME_KIND = 0x03`、`TAG_BYTES = 32`、`TAG_FRAME_BYTES = 33`、`NONCE_ENTROPY_BYTES = 16`。
`createNonce()`（`SourceSyncTransferAuth.kt:123`）用 `Base64.getUrlEncoder().withoutPadding()` 编 16 个随机字节，得到 22 字符 —— 与 Windows 的 nonce 形状一致，但 nonce 的权威来源始终是挑战帧，本端生成只用于测试与将来的发起端。

### 15.2 五个新稳定码

新增独立码表 `SourceSyncTransferAuthReasons`（`SourceSyncTransferAuth.kt:17`），**没有改动第 3 节的十码集，也没有改它们的顺序**：

| 码 | 触发点 | 本端位置 |
| --- | --- | --- |
| `auth-secret-unavailable` | 开了认证但本进程没有 secret | 发送 `SourceSyncTransfer.kt:190`、接收 `:254`（都在读写第一个字节之前） |
| `auth-required` | 开了认证但没有宣布挑战，tag 无从绑定 | `SourceSyncTransfer.kt:197`，零字节写出 |
| `auth-missing` | 收满信封后对端关流（没有 tag），或 tag 到来时从未宣布挑战 | `:272`、`:317` |
| `auth-mismatch` | tag 与本信封/本 nonce/本 secret 不符 | `:330`，**nonce 不记** |
| `auth-replayed` | 该设备已在该 nonce 下接受过信封 | `:338` |

分工刻意明确：**与认证无关的形状错误仍报十码里的旧码**（tag 帧来得太早/太晚/长度不对 → `frame-invalid`；关闭认证时收到 0x03 → `frame-invalid`，文案沿用 Windows 的 `Unknown frame kind 0x03.`）。这样排障时「码」表达的是故障类别，而不是「哪个包管」。

### 15.3 接收判定次序（逐条镜像 Windows）

认证开启时 `receive`（`SourceSyncTransfer.kt:248`）的次序与 Windows 完全一致：

1. 无 secret → `auth-secret-unavailable`，**一个字节都不读**（`:254`，测试用计数 `InputStream` 断言 `reads == 0`）。
2. 读帧失败：已收满 → `auth-missing`，否则 `frame-truncated`（`:271-286`）。这一条让「对端发完信封就关流」不被误判成帧截断。
3. 0x03 帧：认证关闭 → `frame-invalid Unknown frame kind 0x03.`；没有首块 → `The tag frame arrived before the first chunk.`；未收满 → `The tag frame arrived after X of Y bytes; it must follow the last chunk.`；长度不是 33 → `A tag frame is N bytes; an HMAC-SHA256 tag is 32 bytes plus the kind byte.`；从未宣布挑战 → `auth-missing`（`:290-322`）。
4. 验 tag 失败 → `auth-mismatch`（`:330`），**先比 tag 再记 nonce**：伪造信封不能把真 nonce 烧掉，否则合法传输会被当成重放。
5. 记 nonce 失败（窗口里已有）→ `auth-replayed`；否则 `Ok`（`:338-345`）。
6. 已经收满后又来数据块 → `frame-invalid`（`:375-381`），对应 Windows 的 `The peer sent another chunk after the envelope was complete; ...`。

「收满」在认证开启时只置 `complete = true` 并继续读（`:444-450`）；关闭认证时仍照旧立即返回（`:445`）。这一处是本次唯一改动既有控制流的地方，也是「关闭时字节与行为全等」的关键：旧断言（第 8 节向量、`SourceSyncTransferLoopbackTest`）全部原样通过。

### 15.4 关闭认证时与既有向量全等

开关就是参数：`sendTo` / `decode` / `receive` 都新增末尾默认值 `options: SourceSyncTransferOptions? = null`（`SourceSyncTransfer.kt:161/231/248`），`null` 与 `SourceSyncTransferOptions.OFF` 等价。
`SourceSyncTransferAuthTest.authentication_off_produces_exactly_the_bytes_it_produced_before`（`SourceSyncTransferAuthTest.kt:347`）把本端 `encodeFrames` 与第 8 节 `windows-transfer-vectors.json` 里每条 `withChallenge.framesHex` 逐字节比对 —— 49231 字节的黄金向量文件本身一个字节没改。
另一条 `authentication_off_still_ignores_a_trailing_tag_frame`（`:381`）钉住「关闭时收满即返回」：完整信封之后附一个合法 tag 帧，结果仍是 `Ok`，不读尾随帧。

### 15.5 重放窗口

`SourceSyncNonceCache`（`SourceSyncTransferAuth.kt:138`）是进程内内存结构，**不落盘**：

- 容量 `DEFAULT_CAPACITY = 64`，FIFO 逐出最旧一条；
- 键是 `(deviceId ?: "") + U+001F + nonce` —— 用 `U+001F`（单元分隔符）而不是 `:` 或 `|`，避免设备名或 nonce 里的可见字符拼出歧义键；
- 空 nonce、`capacity <= 0` 一律 `accept` 返回 false（宁可拒，不可静默放开）；
- `count` 与 `clear()` 只给测试与将来的调试入口用。

`SourceSyncTransferOptions`（`:200`）是 class 不是 data class，构造时拷贝 secret；`toString()` 与 Windows 逐字同格式（`Secret = <redacted N bytes>`），测试 `options_to_string_redacts_the_secret_and_matches_windows`（`:321`）既比对 4 条 Windows 记录，也断言输出里不含口令明文。

### 15.6 金标向量与生成方式

`core/common/src/test/resources/sync/windows-auth-vectors.json`（66573 字符）由 `D:/mh-verify/auth-vectors` 的一个临时 console 工程直接编译 Windows 的 4 个源文件生成（`Compile Include` 绝对路径，所以不会往 mh-w6 工作树写 obj/bin）。该工程不入库；重生成的命令是 `dotnet run -c Release -- <vectors.json> <windows-envelope-device-a.json>`。

向量分节：`constants`、`authReasons`、`transferReasons`、`secrets`（good / wrong）、`nonces`（first / second / third）、`envelopes`（64 B、9000 B 两块、1093 B 黄金信封）、`success`（5 条 tag）、`streams`（3 条完整帧流 + 每条的 `frameCount` 与 `envelope`）、`receiveCases`（12 条，每条带 `authenticate` / `secret` = good|wrong|absent|off / `deviceId` / `repeats` / `expectedReasons` / `details`）、`sendFailures`（2 条，`bytesWritten` 均为 0）、`optionsToString`（4 条）。

需要 `AllowMissingPrunePackageData=true`，否则 .NET 10 SDK 报 `NETSDK1226`（找不到 .NETCoreApp 10.0 的 prune 包数据）—— 这是本轮唯一的工具链坑。

### 15.7 本次新增测试（24 例，0 skipped）

`core/common/src/test/kotlin/com/mediahub/core/common/sync/SourceSyncTransferAuthTest.kt`（612 行）：

| 组 | 例数 | 代表用例 |
| --- | --- | --- |
| 常量与码表 | 2 | 常量同值（`:108`）、五码与十码集同序且不相交（`:125`） |
| tag 复算与校验 | 2 | 5 条 Windows tag 复算（`:144`）、翻转一个字节必须拒（`:160`） |
| 帧流逐字节 | 2 | 3 条帧流逐字节相等（`:182`）、解码回原信封（`:208`） |
| 判定次序 | 3 | 同 nonce 第二次 `auth-replayed`（`:229`）、12 条坏流逐条比码（`:249`）、2 条发送失败零字节（`:291`） |
| 关闭认证全等 | 2 | 与第 8 节向量逐字节相等（`:347`）、忽略尾随 tag（`:381`） |
| 端到端回环 | 3 | 同口令成功（`:411`）、错口令不烧 nonce（`:427`）、篡改信封 `auth-mismatch` 且第二个 tag 不被读（`:448`） |
| 零写入与打码 | 4 | 无 secret 发送（`:473`）/ 空挑战发送（`:481`）/ 无 secret 接收 `reads == 0`（`:491`）、options 打码逐字（`:321`） |
| 窗口语义 | 3 | FIFO 逐出（`:512`）、按设备分区（`:527`）、空 nonce 与容量 0 拒绝（`:538`） |
| 单帧增量 | 1 | 认证开启只在最后一块后多一帧（`:393`） |

### 15.8 实跑计数

```
gradlew.bat :core:common:testDebugUnitTest --console=plain
```

`BUILD SUCCESSFUL in 16s`，`17 actionable tasks: 3 executed, 14 up-to-date`。JUnit XML（`core/common/build/test-results/testDebugUnitTest`）亲数：**28 suites / 363 tests / 0 failures / 0 errors / 0 skipped**；本套件单列 `tests="24" skipped="0" failures="0" errors="0"`。扣除本套件 = 27 套件 / 339 例，与本轮之前完全一致（无回归）。
本轮首跑 3 红，都是本轮自己写错，没有放宽任何断言：①生成器的重放流记录漏了 `envelope` 键；②诊断文本比对把中文的 `challenge-mismatch` 分支也算成「该逐字对齐」的分支（那条沿用 W6-22 的中文文案，不参与对齐）。修法：生成器补键重生成；测试改成只比对「五码认证分支 + 含 tag frame / `Unknown frame kind` 的 detail」。

### 15.9 未覆盖项（如实列出）

1. **无 TLS / 无机密性**：tag 只证明「同一个 secret 的人发的」，线上仍是明文帧；WireGuard 之外没有加密层。
2. **指纹钉扎未与认证结合**：第 10、11 节的对端指纹校验发生在配对阶段，传输阶段的 tag 不覆盖「对端是谁」，也不覆盖帧边界。
3. **跨进程 / 重启的重放窗口**：窗口在内存里，进程重启即清空；两个进程各开一个窗口时同一个 nonce 可以被各接受一次。
4. **无密钥轮换、无设备身份绑定**：secret 由调用方给，轮换要停机；tag 不绑定 deviceId（deviceId 只用于分区重放键）。
5. **缓存投毒 DoS 无速率限制**：伪造 tag 不会被记，但可以反复试探；本轮未做限速或退避。
6. **单端开启即失败，无降级**：任何一端打开认证而另一端没开，传输都会失败（这是刻意选择，避免静默降级成明文）。
7. **instrumentation 未跑**（按任务书）；**未与真实 Windows 端点联调**，对端是 `ServerSocket` 夹具与 Windows 源文件编译出的向量。

## 16. 界面接线（W6-27）

把第 14 节的回环接收接进 Android 的同步导入页：用户填 host / port / challenge → 点「从 Windows 接收」→ 信封落进**与手动选文件完全相同**的预览 → 逐条允许 → 应用流程。只改 Android，Windows 未碰。

### 16.1 入口位置

- 设置 → 数据同步 → 导入页（`SourceSyncImportScreen`）**页面末尾**（结果卡与 `VerifyCredentialDialog` 之后、`Column` 收尾之前）新增一张「从 Windows 接收」卡片：调用点 `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt:389`（`ReceiveSection`），实现从 `:586` 开始；紧随其后的「接收（监听）」（W6-36）卡片调用点 `:390`–`:396`（`ListenSection` 定义 `:690`）。
- **W6-41 的位置修订（2026-10-24）**：两张卡片原本插在正文引导文案之后、文件选择行之前（W6-27 / W6-36 的原始位置），把首屏的「选择文件 / 预览」行与阻塞卡挤出模拟器视口，使 `SourceSyncImportScreenTest` 的 6 例 instrumentation 首屏可见性断言在 CI 上真实变红（API 32 / API 36 两个 job 都红，不是 flaky）。W6-41 因此把两张卡片整体下移到页尾：**首屏保留原「选择文件 → 预览 → 应用」主流程，接收区块只是入口捷径**，往下滚一格就能看到。只改调用次序与注释，不动任何 testTag / 文案 / 状态机 / 签名。
- **W6-41 的验证边界**：本机未跑 instrumentation（未起模拟器），只做到「`androidTest` 编译通过 + 首屏构成回到 W6-27 之前的形状」；那 6 例是否转绿由 CI 的 visual-instrumentation（API 32 / API 36）证明。逐例复核结论：6 例失败除「被挤出首屏」外**无第二因** —— `SourceSyncImportScreenTest` 全文不出现任何 `sync.receive.*` / `sync.listen.*` tag，也没有对新区块顺序或存在性的断言（其余用例都自带 `performScrollTo()`，因此不受影响）。
- 三个输入框 + 一个按钮 + 一行结果：host（默认 `127.0.0.1`，testTag `sync.receive.host`，`:619`）、port（数字键盘，默认空，`:630`）、挑战（可留空，`:640`）、按钮（`:647`）、结果行（`:674`）。testTag 常量集中在 `SourceSyncImportTags` 的 W6-27 五枚（`:95`–`:106`）与 W6-36 九枚（`:111`–`:130`），均只下移未改名。
- Route 在 `:141` 收集 `viewModel.receiveState`（`:142` 收集 `listenState`），`:163`–`:169` 传进 `Screen`；`Screen` 的接收/监听共 7 个新参数都带默认值（`:194`–`:204`），既有调用点与 androidTest 不需要改。
- ViewModel：`feature/settings/.../sync/SourceSyncImportViewModel.kt:57` 构造控制器（生产端口 `SourceSyncTransferReceivePort`），`:63` 暴露 `receiveState`，`:110` 的 `receive(form)` 把协程挂到 `viewModelScope`。

### 16.2 状态机

`SourceSyncReceiveState`（`feature/settings/.../sync/SourceSyncReceiveController.kt:16`）：`Idle` → `Receiving` → `Received` / `Rejected` / `Failed`，`clear()`（`:177`）退回 `Idle`。

| 状态 | 界面表现 | 含义 |
| --- | --- | --- |
| `Idle` | 无结果行 | 还没接收过 |
| `Receiving` | 按钮禁用 + 转圈 + `接收中…` | 正在收帧；同一时刻只允许一次（`AtomicBoolean` 单飞，`:150`） |
| `Received(deviceId, fileName)` | 主色结果行「已收下信封（设备：…）」 | 已交给 `session.loadEnvelope(json, "windows-transfer.envelope.json")`，状态是 `READY_TO_PREVIEW` |
| `Rejected(reason, message)` | 红色结果行「信封被拒：码（诊断）」 | 帧层成功但 codec 不认 |
| `Failed(reason, detail)` | 红色结果行「接收失败：码（诊断）」 | 帧层失败，或本地失败（`receive-failed` + `missing-envelope`） |

手动选文件（`SourceSyncImportViewModel.kt:80`）与 `reset()`（`:112`）都会先 `clear()`，所以旧回执不会挂在新文件旁边。取消（ViewModel scope 被取消）会回到 `Idle` 并释放单飞闸门。

### 16.3 零写入保证

三层叠加，任一层都足以让「接收」不写库：

1. **网关只解码**：界面跑的是 `SourceSyncImportGateways.acceptOnly()`（`core/database/.../sync/SourceSyncTransferImportService.kt:126`），它只调 `SourceSyncCodec.tryDecode` 并回显 deviceId / snapshotId / 内容指纹，不碰任何 store。生产链路上刻意**不**接 `AndroidSourceSyncService`——那会在用户审批之前直接写库，逐条允许就形同虚设（`SourceSyncTransferImportService.kt:119-125` 的 KDoc 写明了这一点）。
2. **失败只改控制器状态**：`TransferFailed` / `EnvelopeRejected` / `result.rejectedReason` 非空 / `envelopeJson` 为空这四条路径都不调 `onEnvelope`（`SourceSyncReceiveController.kt:181` 起的 `apply`），因此 `session.state` 一个字段都不动：不覆盖已加载信封、不清空已批准条目、不触发应用。
3. **成功也只到「待预览」**：`onEnvelope` 只调 `session.loadEnvelope`，写入仍只由用户点「应用」触发（`session.apply()`）。

证据：`SourceSyncReceiveControllerTest` 的 `closedPortKeepsSessionUntouched`（`:160`）、`notLoopbackKeepsSessionUntouched`（`:178`）、`authMismatchKeepsSessionUntouched`（`:195`）、`envelopeRejectedKeepsSessionUntouched`（`:211`）、`rejectedImportResultWritesNothing`（`:228`）、`importedWithoutEnvelopeJsonIsALocalFailure`（`:246`）、`portExceptionBecomesALocalCodeAndWritesNothing`（`:348`）七例都先种一个「已加载 + 已预览 + 已允许一条」的会话，接收后用 `assertEquals(before, session.state.value)` 做**整份 data class 相等**断言，并核 `preview.calls` 未变、`apply.calls == 0`。

### 16.4 错误码直通

- 不新造码、不折成布尔：`SourceSyncReceiveCopy.lineOf`（`:57`）与 `codeOf`（`:77`）把 `TransferFailed.reason` / `EnvelopeRejected.reason` / `result.rejectedReason` 原样渲染，括号里附原文诊断，例如 `接收失败：connect-failed（Connection refused）`、`接收失败：not-loopback`、`信封被拒：malformed-json`。
- 码的来源仍是既有三层集合（十码传输码 / codec 码 / 导入层），界面只加两个本地码：`receive-failed`（`:216`，端口实现抛异常，`detail` 是异常类名）与 `missing-envelope`（`:218`，`Imported` 却没带信封文本）。两者都不出现在跨端契约里。
- 非回环 host **不在界面判断**：`SourceSyncReceiveForm`（`:92`）只把空白 host 兜底成 `127.0.0.1`，是否回环由传输客户端判（`not-loopback`），界面照原样显示，避免把稳定码改写成另一种说法。
- 端口是唯一在界面拦的输入：`1..65535` 的整数才允许提交（`:150` 的分支），空白/0/65536/非数字都不发请求、不进入 `Receiving`。

### 16.5 刻意不自动预览、不自动应用

接收成功后停在 `READY_TO_PREVIEW`，用户必须自己点「预览」、再逐条允许、最后点「应用」。理由：信封是对端推来的、未经本机确认的数据，自动预览会掩盖「这份东西是从网线来的」，自动应用则直接废掉审批。`importedEnvelopeEntersSessionWithoutPreviewOrApply`（`:113`）断言：`status == READY_TO_PREVIEW`、`envelopeJson` 逐字相等、`preview == null`、`applied == null`、`preview.calls` 仍为种子里那一次、`apply.calls == 0`。

### 16.6 本次新增测试（30 例，0 skipped）

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `feature/settings/src/test/.../sync/SourceSyncReceiveControllerTest.kt`（`SourceSyncReceiveControllerTest.kt`，424 行） | 25 | 表单默认值与端口边界（7 例）、成功只进会话不预览不应用（3 例）、失败零写入与码直通（9 例）、单飞/取消/异常/clear（4 例）、结果行文案（2 例） |
| `core/database/src/test/.../sync/SourceSyncTransferImportServiceTest.kt`（5 例） | 5 | `Imported` 回传信封原文、解码失败不调网关、未来 schemaVersion 不调网关、`acceptOnly` 回显身份与 64 位内容指纹且 `hasWrites == false`、坏 JSON 给 `malformed-json` 且桶全空 |

`feature/settings` 的 25 例全部是 JVM 单测 + fake 端口，不拨号；真 socket 路径仍由 `core/database/.../SourceSyncTransferImportTest`（第 14.4 节，19 例）覆盖。

### 16.7 实跑计数

命令（pwsh 里先设 `JAVA_HOME`/`ANDROID_HOME`，再用 `cmd /c` 把输出重定向到日志，避免 PowerShell 管道吞掉 `.bat` 的执行）：

```
gradlew.bat :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest \
  --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

原始输出尾行（`--rerun-tasks`，两个模块全部重跑）：

```
BUILD SUCCESSFUL in 3m
162 actionable tasks: 162 executed
```

JUnit XML 亲数（跑完立刻重读 `build/test-results/testDebugUnitTest/*.xml`，时间戳 2026-10-24T00:52:59Z / 00:53:43Z，晚于最后一次修测试）：

| 模块 | suites | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| `core/database` | 16 | 150 | 0 | 0 | 0 |
| `feature/settings` | 13 | 189 | 0 | 0 | 0 |

两个新套件单列：`SourceSyncTransferImportServiceTest` tests=5 / 0 skipped / time 0.034s；`SourceSyncReceiveControllerTest` tests=25 / 0 skipped / time 0.081s。扣除本轮新增的 30 例，两模块基线分别是 145（core:database，含第 14 节的 19 例）与 164（feature/settings）。

修红过程（如实记录，避免下次重踩）：

1. 首跑挂在 `:core:common:bundleLibCompileToJarDebug`，报 `java.nio.file.FileSystemException: ...classes.jar: 另一个程序正在使用此文件`——残留的 Gradle daemon 与 Kotlin compile daemon 占着 jar。处理：`gradlew.bat --stop` → 杀残留 `java.exe` → 删 `core/common` 与 `feature/settings` 的 `build/intermediates/compile_library_classes_jar` → 重跑加 `-Pkotlin.compiler.execution.strategy=in-process`。
2. 第二轮编译红：`Unresolved reference MALFORMED_JSON`。码常量在 `SourceSyncContractException`（`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncContractException.kt:18`）而不是 `SourceSyncCodec`，测试改 import 后通过。
3. 第三轮 1 红：`cancelledReceiveGoesBackToIdle` 抛 `UncompletedCoroutinesError`。原因是测试自身写错——取消之后又用同一个仍挂着未完成 `CompletableDeferred` 的 fake 端口发了一次，永久挂起；修法是先 `gate.complete(Unit)` 再发第二次。生产代码未因此改动。
4. **未跑 instrumentation**，也未在真机/真网卡上验证。

### 16.8 未覆盖项（如实列出）

1. **界面没在真机上点过**：instrumentation 未跑；本节的界面证据是 JVM 单测，Compose 侧只有编译通过与既有 androidTest 的签名兼容（新参数带默认值）。
2. **没在真实网卡上跨机器试过**：回环真 socket 由 core:database 的测试覆盖，界面层一律 fake 端口。
3. **无 TLS / 无对端指纹钉扎**（同第 15 节）：接收端不校验对端是谁。
4. **界面没有 secret / 认证输入**：本轮只透传 challenge；认证开启的传输需要调用方先注入 `SourceSyncTransferOptions`（第 15 节），接线留给后续任务。
5. **没有取消按钮**：只能靠离开页面（ViewModel 销毁）取消。
6. **挑战字符串不做格式校验**：只 trim，留空即「对端不要求挑战」，与 W6-22 一致。

## 17. TLS 1.3 与公钥指纹钉扎（W6-29）

Windows 侧 W6-26（`D:/mh-w6/windows/src/MediaHub.Sync/SourceSyncTlsTransport.cs`，719 行）把 4 字节长度帧包进 TLS 1.3；本节是 Android 侧的对齐实现 `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTls.kt`（581 行新建）+ `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClient.kt`（101 → 140 行）。**帧格式一个字节都没动**：`0x01` 挑战帧 / `0x02` 数据块 / `0x03` tag 帧的语义、十码传输码与五码认证码全部沿用第 14、15 节，TLS 只是记录层外面多包一层。

### 17.1 契约

- **只协商 TLS 1.3**：客户端 `enabledProtocols = arrayOf("TLSv1.3")`，且握手成功后**还要复核** `session.session.protocol == "TLSv1.3"` —— 服务端可以无视客户端列表协商出 1.2，只靠设协议数组不算数，不符即 `tls-handshake-failed`。
- **链不验、只钉扎**：自定义 `X509TrustManager` 三个方法全部不抛异常（`AcceptAnyCertificate`），对端身份完全由 pin 决定；不做信任首次（TOFU）、不做明文回退、pin 不符立即断。
- **pin 由操作员提供**：Windows `serve` 在 stderr 打 `certificate fingerprint: <64 hex>`；证书是临时自签、每次进程不同，因此 pin 也不持久化、不轮换。
- **TLS 与对端认证正交**：第 15 节的 HMAC tag 仍在应用层，TLS 开不开都能开认证；两者叠加时 TLS 只负责机密性与服务端身份（`tlsAndAuthenticationCompose` 用同一条 TLS 连接跑「挑战帧 + 数据块 + tag 帧」）。
- **默认关闭**：`SourceSyncTransferClient.receiveFrom/sendTo` 新增的 `tls` 参数默认 `SourceSyncTlsOptions.Off`；不传时走的就是今天这条明文路径，收尾仍用原来的 `closeQuietly`。
- **只连回环**：沿用 `LoopbackOnly.addressOrNull`（`core/common/src/main/kotlin/com/mediahub/core/common/pairing/PairingTransportSupport.kt:77`），TLS 没有放宽这条。
- **不发客户端证书**；**握手预算与业务读取超时分开报**：`SourceSyncTransferClient(handshakeTimeoutMs = SourceSyncTls.DEFAULT_HANDSHAKE_TIMEOUT_MS)`（默认 10 000 ms），超时给 `tls-handshake-failed` + 带预算数字的诊断，业务读超时仍是第 14 节的 `transfer-timeout`。

### 17.2 pin 算法与四个失败码

pin = **SHA-256(SubjectPublicKeyInfo)** 的小写 64 位 hex，即对 `certificate.publicKey.encoded` 做摘要：

```
MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
```

**不是** `certificate.encoded`（整张证书 DER）。Windows 用 `PairingCertificate.ComputeFingerprint` 算同一个值，测试 `fingerprintCoversThePublicKeyNotTheWholeCertificate` 同时钉住「等于 Windows 算出的 pin」与「不等于整证书摘要」。比较走 `PairingFingerprint.matches`（`MessageDigest.isEqual`，常量时间），不用字符串 `==`。

| 码 | 何时出现 | 有没有读写应用字节 |
| --- | --- | --- |
| `tls-handshake-failed` | 握手抛异常 / 超时 / 协商出的协议不是 TLS 1.3 / 服务端握手无证书 / 对端是明文端点 | 无 |
| `tls-fingerprint-mismatch` | 握手完成，但 `chain[0]` 的 SPKI 摘要与 pin 不符 | 无（诊断写明「一个信封字节读写之前」） |
| `tls-certificate-unavailable` | 说要开 TLS 却没有可用 pin（空 / 非 64 hex）；或服务端侧没有证书身份 | 无，**连拨号都不做**（`Socket().isConnected == false`） |
| `tls-not-enabled` | 接收端是明文监听，对端却以 TLS 握手记录开场 | 无（读 5 字节嗅探后立刻拒） |

四个码与十码传输码（`SourceSyncTransferReasons.ALL`）、五码认证码（`SourceSyncTransferAuthReasons.ALL`）**两两不相交**，且一律 `tls-` 前缀；`SourceSyncTlsReasons.ALL` 的顺序与 Windows `SourceSyncTlsReasons.All` 同序，由 `reasonsAreTheFrozenFourInOrder` / `tlsCodesDoNotOverlapTransferOrAuthCodes` 钉住。

### 17.3 接收端 5 字节嗅探与 graceful close

- **嗅探只在接收端做**（发送端不读入站数据）：`SourceSyncTls.probePlaintext(input)` 最多读 5 字节，`0x16 0x03 0x00..0x04` 判为 TLS 握手记录 → `Refused(tls-not-enabled)`；否则把这 5 字节**原样**用 `SourceSyncTlsPrefixedInputStream` 回放给帧解码器（`read` 先吐前缀再转内层、`close` 不关内层、`available()` 含前缀）。
- **graceful close 不是洁癖**：TLS 1.3 服务端握手完成后会立刻发 `NewSessionTicket`；带未读入站记录直接 `close()` 属 abortive close，对端吃 RST，Windows 侧会报 `io-failed`。TLS 路径收尾因此是 `shutdownOutput()` → 读到 EOF（预算 `DEFAULT_CLOSE_BUDGET_MS = 2_000`，drain 预算 250 ms）→ `close()`；明文路径保持原来的 `closeQuietly`，以保证「默认关闭时行为与今天一致」。`gracefulCloseLetsThePeerReachEofWithoutReset` 跑 5 轮，服务端 `read` 必须返回 `-1`（干净 EOF）而不是异常。
- 部分 Android 版本的 `SSLSocket` 不支持半关（`UnsupportedOperationException`），所以 `shutdownOutput()` 与 drain 里的 catch 是 `Exception` 而非 `IOException`：收尾尽力而为，不把已成功的结果翻成失败。

### 17.4 夹具与生成方式

Android 侧**不自造临时证书**（那要么引入 BouncyCastle、要么手搓 `CertificateRequest`，都会把依赖面撑大）。所以：

- **夹具**：`core/common/src/test/resources/tls/mediahub-sync-test.p12`（2 454 B）与 `core/common/src/test/resources/tls/mediahub-sync-other.p12`（2 462 B），PKCS#12，口令 `mediahub-tls-test`，RSA-2048，`CN=mediahub-sync-test` / `CN=mediahub-sync-other`，SAN 含 `localhost` 与 `127.0.0.1`，有效期固定 **2026-01-01 .. 2046-01-01**（防夹具腐烂；Windows 生产是「5 分钟前 .. 2 小时后」）。
- **元数据**：`core/common/src/test/resources/tls/tls-vectors.json` 记 `spkiSha256Hex`、`displayFingerprint`、`pkcs12Sha256Hex`、`notBeforeUtc` / `notAfterUtc`、`keyBits`、`pkcs12Password`。
- **谁算的指纹**：生成器 `D:/mh-verify/tls-vectors`（临时 console 工程，不入库、不写 `D:/mh-w6` 工作树）用 `<Compile Include=绝对路径>` 链接 Windows 的 `PairingCertificate.cs` / `PairingFingerprint.cs` / `PairingPayload.cs` / `PairingChallenge.cs` / `SyncContractException.cs`，调 **Windows 权威实现** 算 SPKI 指纹，命令：

```
dotnet run --project D:/mh-verify/tls-vectors/tls-vectors.csproj -- <outDir>
```

- 生产身份的入口是 `SourceSyncTlsIdentity.fromKeyStore(keyStore, password)`：任何失败返回 `SourceSyncTlsIdentity.NONE`（不抛），成功则暴露 `certificateFingerprint`（可直接展示给对端操作员）。
- **默认关闭时冻结向量一字未改**（`offKeepsTheFrozenVectorFilesByteIdentical`，比对前做 LF 归一，因为 Windows 检出是 CRLF）：

| 文件 | 原始 | LF 归一后 sha256 |
| --- | --- | --- |
| `core/common/src/test/resources/sync/windows-transfer-vectors.json` | 49 231 B / 261 处 CRLF | `bd7faa644dcc14a5a7bb4f9590299868cb536cd6b9f4316f91ca0f1ee9946849`（48 970 B） |
| `core/common/src/test/resources/sync/windows-auth-vectors.json` | 66 574 B / 342 处 CRLF | `2031a83fc23d1e7c54698d0a86179bee7baf64258bcdc099657d9a09c4d12b57`（66 232 B） |

### 17.5 用例表（`SourceSyncTlsTest.kt`，743 行 / 23 例）

| 分组 | 例数 | 用例 |
| --- | --- | --- |
| 码表与互斥 | 2 | `reasonsAreTheFrozenFourInOrder`、`tlsCodesDoNotOverlapTransferOrAuthCodes` |
| 选项与打码 | 3 | `defaultOptionsAreOffAndSaySo`、`pinnedOptionsNeverPrintTheFingerprint`、`pinnedKeepsTheRawTextAndReportsWhetherItParses` |
| 指纹与嗅探 | 4 | `fingerprintCoversThePublicKeyNotTheWholeCertificate`、`looksLikeTlsHandshakeOnlyAcceptsRecordHeaders`、`probeRefusesTlsRecordsWithNotEnabled`、`probeReplaysPlaintextBytesExactly` |
| 前缀流与身份 | 2 | `prefixedStreamIsReadOnlyAndLeavesTheInnerStreamOpen`、`identityFromKeyStoreExposesTheWindowsComputedFingerprint` |
| 默认关闭不变 | 2 | `offKeepsTheFrozenVectorFilesByteIdentical`、`offKeepsThePlaintextPathIdentical`（真 socket + 明文帧，服务端读到 EOF） |
| 真 TLS 握手（成功/失败） | 6 | `pinnedHandshakeReceivesTheGoldenEnvelopeOverRealTls13`、`wrongPinFailsWithFingerprintMismatchAndNoApplicationByte`、`unusablePinFailsWithCertificateUnavailableBeforeDialing`、`tlsClientAgainstPlaintextEndpointIsRefusedWithNotEnabled`、`plaintextClientAgainstTlsEndpointFailsWithHandshakeFailed`、`tls12OnlyEndpointIsRejectedAsHandshakeFailed` |
| 超时 / 重试 / 收尾 / 与认证叠加 | 4 | `handshakeTimeoutIsReportedSeparatelyFromTheReadTimeout`、`wrongPinIsNotTrustedSoTheNextAttemptCanSucceed`、`gracefulCloseLetsThePeerReachEofWithoutReset`、`tlsAndAuthenticationCompose` |

全部是 JVM 单测，且**不是 mock**：夹具用 `SSLContext.getInstance("TLSv1.3")` + PKCS#12 起真 `SSLServerSocket`（绑 `InetAddress.getLoopbackAddress()`，端口 0），客户端走生产代码 `SourceSyncTransferClient.receiveFrom(..., tls = Pinned(pin))`；「服务端只开 1.2」用 `enabledProtocols = arrayOf("TLSv1.2")` 的真监听；「TLS 打明文端点」用真 `SSLSocket` 打 `ServerSocket` 让接收端跑 `probePlaintext`。

实跑计数（`--rerun-tasks`，`core:common` 全部重跑）：

```
gradlew.bat :core:common:testDebugUnitTest --console=plain --rerun-tasks
BUILD SUCCESSFUL in 25s
17 actionable tasks: 17 executed
```

JUnit XML 亲数（`core/common/build/test-results/testDebugUnitTest`，最新 XML 时间戳 2026-10-24T09:15:31）：**29 suites / 386 tests / 0 failures / 0 errors / 0 skipped**。基线是 28 套件 / 363 例（第 15 节），本轮 +1 套件 +23 例；`SourceSyncTlsTest` 单列 tests=23 / 0 skipped / time 1.803s。

顺带复核下游（`SourceSyncTransferClient` 的签名改动对它必须源兼容）：`:core:database:testDebugUnitTest` → `BUILD SUCCESSFUL in 40s` / `70 actionable tasks: 9 executed, 61 up-to-date`，16 suites / 150 tests / 0 failures，与 W6-27 后的基线一致（无回归）。

修红两条（如实记录）：

1. 首跑编译红：`Unresolved reference 'detail'`（两处）。`SourceSyncTransferResult.Failed` 的字段名是 `message`（`SourceSyncTransferClient.withConnection` 把 `SourceSyncTlsResult.Failed.detail` 装进 `message`），测试按 TLS 结果对象去取 `detail` 取错了。
2. 第二次 1 红：`prefixedStreamIsReadOnlyAndLeavesTheInnerStreamOpen` 期望 `read(ByteArray(4))` 一次吐 4 字节，实际吐 3。前缀流**一次只吐剩下的前缀、不跨到内层**（`InputStream` 允许短读；`SourceSyncTransfer.receive` 是循环读满，所以不影响正确性）。是测试期望写错，改断言而非改实现。

**未跑 instrumentation**，也没在真机/真网卡上验证。

### 17.6 未覆盖项（如实列出）

1. **未与真实 Windows 端点握手**：本机用 JVM `SSLServerSocket` 做真 TLS 1.3 握手（不是 mock），但 JSSE 与 .NET `SslStream` 的差异（NewSessionTicket 时序、close_notify 行为）没有被一次跨端联调证明。
2. **未跑 instrumentation**：TLS 路径在真机上没有点过（同第 16 节，本轮界面层无改动）。
3. **无客户端证书认证**：服务端不校验客户端身份（Windows 同），pin 只钉服务端。
4. **证书不持久化、不轮换、无吊销/OCSP/CT**：有效期与 pin 的流转全靠人工（Windows 侧同样是临时证书）。
5. **无 ALPN、无会话恢复 / 0-RTT、不提供 TLS 1.2 回退**（刻意：只认 1.3）。
6. **只允许回环**；且探针只在接收端做，Android 侧没有实现「发送端也要先探测对端是否明文」的对称逻辑（与 Windows 现状一致）。

## 18. 跨端联调（W6-32）

### 18.1 为什么单独做一次跨端联调

第 16、17 节里「对端」都是本机 JVM：`SourceSyncTlsTest` 的 TLS 对端是 `SSLServerSocket`，`SourceSyncTransferImportTest` 的对端是同进程 `ServerSocket`。这些用例能证明「Android 与自己一致」，17.6 也把「未与真实 Windows 端点联调」如实列为未覆盖项。W6-32 把对端换成**真 Windows 进程**（`MediaHub.SyncCli.dll` 的 `transfer serve`），两端各跑自己的生产代码，特意跨过 JSSE ↔ .NET `SslStream` 这条边界：记录层分片、NewSessionTicket 时序、close_notify 与 abortive close 只在跨端时才暴露。

用例仍是 JUnit4 JVM 单测（`SourceSyncTlsInteropTest.kt`，10 例），但**没有 mock**：端口、证书指纹、认证口令、`sync-sources.json` 里的实体都是真的；断言同时看子进程退出码、stdout 的那一份 JSON 与落盘文件，不只看退出码。

### 18.2 运行方式与环境变量

| 环境变量 | 必需 | 含义 |
| --- | --- | --- |
| `MH_W632_SYNC_DLL` | 是 | `MediaHub.SyncCli.dll` 的绝对路径；缺失则整套 skipped |
| `MH_W632_DOTNET` | 否 | dotnet 可执行文件，默认 `dotnet` |

前置：Windows 侧仓库已构建出 `windows/src/MediaHub.SyncCli/bin/Debug/net10.0/MediaHub.SyncCli.dll`（本机 dotnet 10.0.113）。先设好 `JAVA_HOME` / `ANDROID_HOME`，再：

```
gradlew.bat :core:common:testDebugUnitTest --console=plain --rerun-tasks --no-daemon
```

**必须 `--no-daemon`**：Gradle daemon 的环境变量是它启动时的快照，新设的 `MH_W632_SYNC_DLL` 不会被测试 worker 继承，结果是 BUILD SUCCESSFUL 但整套 skipped（看起来绿，其实一例没跑）。

CI 上这套件**刻意不在** `.github/workflows/android-ci.yml` 的 `--required-suite` 名单里（现 30 条）：没有 `MH_W632_SYNC_DLL` 时 `@Before` 的 `assumeTrue` 抛 `AssumptionViolatedException`，JUnit4 记为 skipped 而非 failed。实测：同一套件去掉该环境变量后 `tests=10 skipped=10 failures=0 errors=0`，skip message 是「MH_W632_SYNC_DLL 没有指向 MediaHub.SyncCli.dll，跳过跨端联调（CI 上必须跳过）」。所以它不会污染 CI 门禁，本地设变量即可真跑。

### 18.3 用例表（`SourceSyncTlsInteropTest.kt`，661 行 / 10 例）

每个用例都通过 `ProcessBuilder` 起一次性子进程 `dotnet <dll> transfer serve --root <临时目录> --device win-a --json --apply [--tls] [--authenticate --secret-env MH_W632_SECRET]`，从 stderr 抓 `listening 127.0.0.1:<port>`（`--tls` 时更早还有一行 `certificate fingerprint: <64hex>`），`finally` 里 `destroy()` → `destroyForcibly()` → 递归删临时目录。

| # | 用例（行号） | 场景 | 断言要点 |
| --- | --- | --- | --- |
| 1 | `plaintextKotlinEnvelopeIsAppliedByWindows`（:67） | 明文，Kotlin 发 → Windows serve 收 | `counts.apply=2`、`report.applied=2`、`sync-sources.json` 恰好两条且 id 就是 `local-w632-emby` / `local-w632-jellyfin`、`frames == chunkCount(信封字节数)`（明文没有挑战帧，Windows 的 `frames` 只数它读到的帧）、`from=android-a`、`to=win-a` |
| 2 | `tlsPinnedHandshakeDeliversTheEnvelope`（:91） | TLS + 从 stderr 抓 pin | 客户端 `Ok`、`json.certificateFingerprint` 等于 stderr 那个 pin、`tls=true`、`counts.apply=2` |
| 3 | `wrongPinIsRefusedBeforeAnyEnvelopeByteIsSent`（:122） | pin 改一位 | 客户端 `Failed(tls-fingerprint-mismatch)`、serve 退出码非 0、`sync-sources.json` 不存在（零写入） |
| 4 | `tlsClientAgainstAPlaintextServeFailsTheHandshake`（:153） | TLS 客户端打明文 serve | 客户端 `Failed(tls-handshake-failed)`、serve 退出码非 0、零写入 |
| 5 | `plaintextClientAgainstATlsServeIsRejectedOnTheWindowsSide`（:179） | 明文客户端打 TLS serve | Windows 侧 `tls-handshake-failed`（stderr 含该码）、退出码非 0、零写入；客户端侧因无投递确认仍是 `Ok(frameCount=1)`（见 18.5 第 1 条） |
| 6 | `authenticatedKotlinTagIsVerifiedByWindows`（:208） | 挑战文本当 nonce，Kotlin 带 tag | `authenticate=true`、`secretEnv=MH_W632_SECRET`、`frames == chunkCount + 2`（挑战帧 + 数据块 + tag 帧）、`counts.apply=2`、落盘两条 |
| 7 | `wrongSecretIsRefusedAsAuthMismatch`（:239） | 两端口令不一致 | serve stderr 含 `auth-mismatch`、退出码非 0、零写入；客户端仍 `Ok(frameCount=3)`（同一原因） |
| 8 | `tlsAndAuthenticationCompose`（:268） | TLS + 认证同开 | `tls=true` 且 `authenticate=true`、pin 与 stderr 一致、`counts.apply=2` |
| 9 | `theSameNonceIsAcceptedByTwoSeparateServeProcesses`（:297） | 同 nonce 打两个新进程 | 两个独立进程都接受（nonce 缓存是**进程级**）；进程内重放由 `SourceSyncTransferAuthTest` 覆盖 |
| 10 | `windowsSendPinsTheKotlinTlsListener`（:325） | 反向：Windows `transfer send --tls --expect-fingerprint` 打 Kotlin `SSLServerSocket` | Kotlin 侧 `handshakeServer` + `SourceSyncTransfer.receive` 收到信封、解码后 `deviceId=win-b`、Windows 退出码 0、Windows 不写自己的仓库；Kotlin 侧收完必须 `SourceSyncTls.gracefulClose`（TLS 1.3 服务端不半关会让对端 RST） |

### 18.4 实测（原始输出与计数）

```
pwsh 里设 JAVA_HOME=C:\Program Files\Android\Android Studio\jbr、ANDROID_HOME、MH_W632_SYNC_DLL 后：
cmd /c "gradlew.bat :core:common:testDebugUnitTest --console=plain --rerun-tasks --no-daemon > D:\mh-verify\w6-32-interop.log 2>&1"
BUILD SUCCESSFUL in 1m 6s
17 actionable tasks: 17 executed
EXIT=0
```

JUnit XML 亲数（`core/common/build/test-results/testDebugUnitTest`）：**30 suites / 396 tests / 0 failures / 0 errors / 0 skipped**。基线是 29 套件 / 386 例（第 17 节），本轮 +1 套件 +10 例；`SourceSyncTlsInteropTest` 单列 tests=10 / time=4.807s / skipped=0。

| 用例 | 耗时 | 结论 |
| --- | --- | --- |
| `plaintextKotlinEnvelopeIsAppliedByWindows` | 0.380s | 通过 |
| `tlsPinnedHandshakeDeliversTheEnvelope` | 0.543s | 通过 |
| `wrongPinIsRefusedBeforeAnyEnvelopeByteIsSent` | 0.755s | 通过 |
| `tlsClientAgainstAPlaintextServeFailsTheHandshake` | 0.281s | 通过 |
| `plaintextClientAgainstATlsServeIsRejectedOnTheWindowsSide` | 0.453s | 通过 |
| `authenticatedKotlinTagIsVerifiedByWindows` | 0.378s | 通过 |
| `wrongSecretIsRefusedAsAuthMismatch` | 0.277s | 通过 |
| `tlsAndAuthenticationCompose` | 0.545s | 通过 |
| `theSameNonceIsAcceptedByTwoSeparateServeProcesses` | 0.735s | 通过 |
| `windowsSendPinsTheKotlinTlsListener` | 0.456s | 通过 |

修红三条（如实记）：

1. 编译红 `Unresolved reference: readString`：`Files.readString` 是 Java 11 API，Android 的 `android.jar` 里没有；改用 `String(Files.readAllBytes(file), StandardCharsets.UTF_8)`。
2. `frames` 语义写错：最初按「数据块 + 挑战帧」断言，实测明文时 Windows 的 `frames` 只数它读到的帧（明文发送端不送挑战帧，故 `frames == chunkCount`），认证时才多出挑战帧与 tag 帧（`chunkCount + 2`）。改断言，不改实现。
3. 两例把「发送端返回 Ok」当成「对端接受」：协议单向、无投递确认（见 18.5 第 1 条），改成如实断言客户端 `Ok` 并核对帧数，拒绝只从 Windows 侧观察。

### 18.5 跨端事实与未覆盖项

1. **协议是单向的，发送端没有投递确认**：`SourceSyncTransfer.sendTo` 返回的 `Ok` 只表示帧写进了 socket。第 5、7 例实测：Windows 报 `tls-handshake-failed` / `auth-mismatch` 且零写入，Kotlin 发送端照样 `Ok`（`frameCount` 分别为 1 和 3）。Windows CLI 的 `transfer send` 有 `--confirm-delivery`（JSON 里有 `deliveryConfirmed`），Android 侧**没有**对应物；若以后要显示「对端已接受」，需两端一起定一个回执语义，不在本轮范围。
2. **nonce 重放窗口是进程级**：CLI 的 `transfer serve` 每个进程只服务一条连接就退出，跨端用例无法构造「进程内二次投递」，第 9 例只证明两个新进程各自接受同一 nonce；进程内 `auth-replayed` 仍由 `SourceSyncTransferAuthTest`（第 15 节）覆盖。
3. **明文客户端打 TLS 端点时，客户端侧拿到的码取决于 `SslStream` 断连方式**（可能 `io-failed`、也可能 `frame-truncated`），本轮不锁定客户端码，只锁定 Windows 侧与零写入。
4. **未覆盖**：真机与 instrumentation（未跑）；非回环（全部 127.0.0.1）、跨机、跨网段；Android 作为服务端只在第 10 例的本机回环用过；Windows 侧 GUI 与 Android 侧 UI 都没接发送流程；Windows 的 `transfer send` 只作对端使用，没有做「Windows 发 → Android 收」的全链路。
5. **Windows 文件一个都没改**（本轮只读）；Android 侧唯一的生产代码改动是 `SourceSyncTransferClient.receiveFrom/sendTo` 各加 `auth: SourceSyncTransferOptions? = null` 并透传（默认 `null`，默认路径字节与既有 386 例完全一致）——原客户端没有 auth 参数，Android 发送端无法带 tag 帧，跨端认证用例根本无从写起。

## 19. 回环监听端（W6-34）

第 18 节证明的是「Android 主动拨号」这一半。本节补另一半：**Android 当接收端**，在回环上监听，
让 Windows 的 `transfer send` 把信封推进来，收完直接走同一套预览/审批/应用流程。

### 19.1 范围

- 只绑回环（`127.0.0.1` / `::1` / `localhost` 字面量，不做 DNS）。非回环地址在创建 socket 之前就被拒。
- 端口 `0` 表示让系统分配，实际端口从 `listener.port` 取——两个进程同机时不需要事先约定端口。
- 单连接串行：每次 `receiveOnce` 只接一条连接，处理完（或失败）返回，之后可以继续 `receiveOnce`，
  直到 `close()`。这与 Windows `SourceSyncTransferListener`（一次 accept 一个连接）语义一致，
  区别在于 Windows 的 CLI `serve` 进程收完一条就退出，而 Android 是长驻对象。
- 解帧、挑战、认证、TLS 全部复用既有实现，**没有第二套帧逻辑、也没有新造稳定码**：
  帧层走 `SourceSyncTransfer.receive`，TLS 走 `SourceSyncTls.handshakeServer` / `probePlaintext` / `gracefulClose`，
  认证走 `SourceSyncTransferOptions` 的 tag 校验与非重放缓存（W6-25 的五码）。

### 19.2 API

`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferListener.kt`

| 成员 | 说明 |
| --- | --- |
| `SourceSyncTransferListener.open(host, port, tls, identity, acceptTimeoutMs, readTimeoutMs, handshakeTimeoutMs, closeBudgetMs)` | 打开监听。返回 `SourceSyncListenerStart.Started(listener)` 或 `.Failed(code, detail)`；判定次序是地址 → 端口 → 超时 → 证书 → bind，所以 `listener-failed` 一定是 bind 这一层（通常是端口被占用） |
| `listener.address` / `port` | 实际绑定值（host 传 null 时就是 `127.0.0.1`） |
| `listener.tlsEnabled` / `certificateFingerprint` | TLS 开着时给出本端公钥指纹（小写 64 hex），明文时为 null |
| `listener.receiveOnce(expectedChallenge = null, auth = null)` | 阻塞式收一封信封，返回 `SourceSyncListenerTransfer.Received(envelope, envelopeJson, frames, bytes, challenge)` 或 `.Failed(code, detail)`；方法整体 `synchronized`，不会有两个线程同时 accept |
| `listener.close()` | 幂等关监听并释放端口 |

`core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncListenerImportService.kt`

| 成员 | 说明 |
| --- | --- |
| `SourceSyncListenerImportService.start(gateway, host, port, tls, identity, 超时…)` | 打开监听并接上导入网关；生产用 `AndroidSourceSyncService` 包成 `SourceSyncImportGateway`，只想解码不写库用 `SourceSyncImportGateways.acceptOnly()` |
| `service.receiveAndImport(expectedChallenge = null, auth = null)` | `suspend`；在 `Dispatchers.IO` 上收，然后 `importEnvelopeBytes`。返回与 W6-23 同一个三段结果：`TransferFailed` / `EnvelopeRejected` / `Imported` |
| `service.port` / `address` / `tlsEnabled` / `certificateFingerprint` / `isClosed` | 透传监听的属性，UI 用来显示「告诉对端什么」 |

三段失败域没有合并（与第 14 节同一条理由）：传输层码、解码码、导入层四桶各自有出处，界面可以原样显示。

### 19.3 与 Windows `transfer send` 对接的命令

Android 侧先把端口与（TLS 时的）指纹显示出来，然后 Windows 侧执行：

```
# 明文
dotnet MediaHub.SyncCli.dll transfer send --to 127.0.0.1:<android port> --root <windows dir> --device win-b --json

# TLS：--expect-fingerprint 必须等于 Android 打印的 certificateFingerprint
dotnet MediaHub.SyncCli.dll transfer send --to 127.0.0.1:<android port> --root <windows dir> --device win-b --json --tls --expect-fingerprint <pin>

# 认证：Windows 侧读环境变量里的口令，nonce 由它自己随机生成并写进挑战帧
dotnet MediaHub.SyncCli.dll transfer send --to 127.0.0.1:<android port> --root <windows dir> --device win-b --json --authenticate --secret-env MH_W634_SECRET
```

`expectedChallenge` 传 null（默认）表示接受对端宣布的任何挑战——这正是 Windows `serve` 的口径；
要收紧就传具体文本，对端宣布的不一样会得到 `challenge-mismatch`。

### 19.4 码表（哪些码一定没写库）

监听端不新增码，全部来自既有三张表：传输十码、认证五码、TLS 四码。

| 情形 | 码 | 是否写过库 |
| --- | --- | --- |
| 绑定地址不是回环 | `not-loopback` | 否（socket 都没建） |
| 端口被占用 / bind 失败 / 端口越界 / 超时非正数 | `listener-failed` | 否 |
| TLS 开着但没有可用证书 | `tls-certificate-unavailable` | 否（在 socket 之前） |
| 等不到连接（accept 预算耗尽） | `transfer-timeout` | 否 |
| 帧头长度非法 / 体不足 / 空流 | `frame-truncated` | 否 |
| 帧种类非法 / 挑战帧不在首位 | `frame-invalid` | 否 |
| 声明长度超过 8 MiB | `payload-too-large` | 否 |
| 挑战不匹配 | `challenge-mismatch` | 否 |
| 含信封但 JSON 不合契约 | `envelope-invalid` | 否 |
| 对端认证失败 | `auth-required` / `auth-missing` / `auth-mismatch` / `auth-replayed` / `auth-secret-unavailable` | 否 |
| TLS 握手 / 指纹问题 | `tls-handshake-failed` / `tls-fingerprint-mismatch` / `tls-not-enabled` | 否 |
| 读连接时 IO 异常 | `io-failed` | 否 |

零写入是结构性的：`receiveAndImport` 先拿传输结果，`Failed` 直接转 `TransferFailed` 返回，
只有 `Received` 才把字节交给 `importEnvelopeBytes`；而 `importEnvelopeBytes` 解码失败给 `EnvelopeRejected`，
根本不调网关。测试里用「网关调用数 = 0」与「`createLocalSource` 调用数 = 0」两个计数器钉住。

### 19.5 与 Windows 监听端的差异

1. **生命周期**：Windows `SourceSyncTransferListener` 每次 `ReceiveOnceAsync` 只 accept 一条连接、CLI `serve` 收完即退出；
   Android 的 `receiveOnce` 可在同一个监听上连续调用，直到 `close()`。两者都不并发处理多条连接。
2. **端口越界的归类**：Windows 的 `TryResolveLoopback` 把 `port < 0 || > 65535` 归进地址解析失败（`not-loopback`），
   Android 归进 `listener-failed`（地址合法才轮到端口）。语义上都是「没起来」，码不同，跨端日志对比时要注意。
3. **TLS 身份来源**：Windows 每次启动用 `PairingCertificate.CreateEphemeral` 现造一张两小时有效期的临时证书；
   Android 不自造证书（不引 BouncyCastle），身份由调用方以 `KeyStore` 传入，`SourceSyncTlsIdentity.NONE` 时开 TLS 直接 `tls-certificate-unavailable`。
   所以 Android 的指纹在证书换之前是稳定的，Windows 的每次都变——操作员要按当次打印的值输入。
4. **超时**：Windows 一个 `DefaultTimeout = 15 s` 预算贯穿 accept 与读；Android 把 accept、读、握手、关闭四个预算分开，
   默认 accept 与读各 15 s、握手 10 s、关闭 2 s。
5. **诊断文本**：码逐字相同，detail 文案是各自语言写的（Android 中文、Windows 英文），跨端只对比码。

### 19.6 测试

`core/common/src/test/kotlin/com/mediahub/core/common/sync/SourceSyncTransferListenerTest.kt`（22 例，全部真回环 socket）

| 分组 | 用例 |
| --- | --- |
| 形态与默认值（2） | `defaultsBindLoopbackAndLeaveThePortToTheOs`、`closedListenerServesNothingAndLeavesNoThreadBehind` |
| 明文收发（3） | `plaintextClientDeliversTheGoldenEnvelope`、`secondConnectionOnTheSameListenerIsAccepted`、`challengeIsEchoedAndMustMatchWhenExpectationIsGiven`（含 `challenge-mismatch` 负例） |
| 打开失败（3） | `nonLoopbackHostIsRefusedBeforeAnySocketExists`、`tlsWithoutAnIdentityIsRefusedWithCertificateUnavailable`、`busyPortIsReportedAsListenerFailed` |
| TLS（3） | `pinnedTlsClientDeliversOverRealTls13`、`tlsClientAgainstAPlaintextListenerIsNamedNotEnabled`、`plaintextClientAgainstATlsListenerIsNamedHandshakeFailed` |
| 认证（5） | `authenticatedClientIsVerifiedAndCountsTheTagFrame`（frames = 块数 + 2）、`wrongSecretIsRefusedAsAuthMismatch`、`replayedNonceIsRefusedAsAuthReplayed`（共享 `SourceSyncNonceCache`，count 仍为 1）、`clientWithoutATagFrameIsRefusedAsAuthMissing`、`tlsAndAuthenticationComposeOnTheSameConnection` |
| 生命周期（2） | `closedListenerReleasesThePortForTheNextOne`、`receiveAfterCloseIsReportedAsListenerFailed` |
| 坏帧与预算（4） | `acceptTimeoutIsReportedAsTransferTimeout`（200 ms 预算）、`oversizedDeclaredFrameLengthIsFrameTruncated`、`zeroLengthFrameIsFrameTruncated`、`halfAFrameIsFrameTruncated` |

`core/database/src/test/kotlin/com/mediahub/core/database/sync/SourceSyncListenerImportTest.kt`（8 例，全链路：socket → 解帧 → 解码 → `AndroidSourceSyncService` 写库）

| 用例 | 断言 |
| --- | --- |
| `plaintextEnvelopeIsImportedAndAppliesTwoSources` | applied=2 / requiresApproval=1 / ignored=1、`createLocalSource` 调 2 次、库里 2 条、`deviceId=device-a` |
| `secondRunOfTheSameEnvelopeWritesNothingNew` | 第二轮 applied=0 / requiresApproval=1 / ignored=3，`createCalls` 不变、`updateCalls=0` |
| `rejectedEnvelopeIsNotHandedToTheGateway` | 载荷 `null`（合法 JSON 非信封）→ `EnvelopeRejected(malformed-json)`、网关 0 次、库里 0 条 |
| `truncatedStreamIsReportedAsTransferFailureWithoutWrites` | 4 个零字节 → `TransferFailed(frame-truncated)`、网关 0 次 |
| `nonLoopbackHostIsRefusedBeforeAnySocketExists` | `start(host = 10.1.2.3)` → `not-loopback`、网关 0 次 |
| `busyPortIsReportedAsListenerFailed` | 第二监听同端口 → `listener-failed` |
| `wrongSecretIsRefusedAsAuthMismatchWithoutWrites` | 两端口令不同 → `TransferFailed(auth-mismatch)`、网关 0 次、库里 0 条 |
| `startExposesTheBoundPortAndCloseReleasesIt` | 端口在 1..65535、address 127.0.0.1、close 后同端口可立刻重绑 |

### 19.7 实测

```
**命令**（`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`、`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk` 先在 pwsh 里设好，再 `cmd /c` 重定向日志）：

```
gradlew.bat :core:common:testDebugUnitTest :core:database:testDebugUnitTest \
    --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

**原始尾行**：

```
BUILD SUCCESSFUL in 1m 50s
75 actionable tasks: 75 executed
EXIT=0
```

**JUnit XML 亲数**（`build/test-results/testDebugUnitTest/*.xml` 逐文件累加）：

| 模块 | SUITES | TESTS | FAILURES | ERRORS | SKIPPED |
| --- | --- | --- | --- | --- | --- |
| core:common | 31 | 418 | 0 | 0 | 10 |
| core:database | 17 | 158 | 0 | 0 | 0 |

两个新套件单列：`SourceSyncTransferListenerTest` tests=22 / time=0.838 s / skipped=0；
`SourceSyncListenerImportTest` tests=8 / time=0.112 s / failures=0。

扣除本轮新增的 22 + 8 = 30 例，两模块分别是 388 与 150 例 —— 与基线一致，无回归。

> 10 个 skipped 不是本轮引入的：`SourceSyncTlsInteropTest` 在 `MH_W632_SYNC_DLL` 未设置时整类 `assumeTrue` 跳过（第 18 节已实证），
> 这里刻意没有设该变量，所以跨端联调 10 例按设计 skip。

**修红过程**（三条，全部是测试/编译层面的真修，没有放宽断言）：

1. 编译红：`SourceSyncTransferListener.kt:306` / `:312` 里 `LoopbackOnly.closeQuietly(opened)` 报
   `actual type is ServerSocket?, but Socket? was expected` —— `open()` 失败路径关的是 `ServerSocket`。
   改成 `opened?.let { server -> runCatching { server.close() } }`（生产代码唯一一处修改）。
2. 测试红：`defaultsBindLoopbackAndLeaveThePortToTheOs` 断言 `DEFAULT_ACCEPT_TIMEOUT_MS`（15 000）却用 helper 起的监听（helper 传 10 000）。
   改成该例直接调 `SourceSyncTransferListener.open()`（绕过 helper），断言不动。
3. 编译红（core:database）：测试文件多写了 `import com.mediahub.core.common.sync.SourceSyncImportResult`（该类在 core:database 同包）、
   `com.mediahub.core.model.MediaServer`（实际包 `com.mediahub.model`）、漏 `AndroidSourceTypeMap` 的 import，
   且 `describeStart` 只接受 `SourceSyncListenerStart`。修法：删错 import、补对 import、为 `SourceSyncListenerImportStart` 加一个同形重载。
```

### 19.8 未覆盖

1. 未跑 instrumentation、未在真机上点过监听端（全部 JVM 单测）。
2. 只在本机回环上验证；非回环、跨机、跨网段、防火墙/NAT 都没测（按设计本来就不允许非回环）。
3. Android 作为服务端只与「本仓自己的客户端」「Windows CLI `transfer send`（第 18 节第 10 例）」对过，
   没有和 Windows 的**监听端**同时出现在同一条链路上的编排测试。
4. 没做并发压测：`receiveOnce` 是串行的（`synchronized`），两个线程同时调用时后者等前者结束；没有测过长时间连续接收。
5. Windows 的 `--confirm-delivery` 在 Android 侧仍无对应物（第 18.5 节第 1 条）：`sendTo` 的 `Ok` 只表示帧写进了 socket。
6. CI 只把 `SourceSyncTransferListenerTest` 钉进 required-suite（31 条）；`SourceSyncListenerImportTest` 在 core:database 里照常执行但不单独钉。


## 20. 界面接线：手机当接收端（W6-36）

> 与第 19 节的分工：第 19 节交付的是 `core:common` / `core:database` 里的回环监听端本身（能用、有 30 例回归）；
> 本节交付的是「用户怎么点得到它」——一个纯 Kotlin 状态机、一段 Compose 区块，以及三条不肯让步的规矩。

### 20.1 入口位置

| 层 | 文件 | 位置 |
| --- | --- | --- |
| 状态机 | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerController.kt` | 类 `:262`；六态 `:29`；文案与三行 `lineOf` / `codeOf` / `portLineOf` `:69` / `:106` / `:118` / `:134`；表单 `:165`（回执字段 `:174`，默认关）；两个接口 `:218` / `:234`；生产实现 `SourceSyncListenerImportFactory` `:463`。以上行号按 W6-40 之后的当前文件（522 行）核对 —— W6-40 往状态与表单里加了回执字段，旧值整体下移 |
| ViewModel | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportViewModel.kt` | 控制器 `:72`、`listenState` `:78`、`listenTls` `:81`、`startListening` `:126`、`stopListening` `:131`、`awaitListenOnce` `:136`、`onCleared` 关监听 `:160` |
| 界面 | `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt` | 调用点 `:390`（`ListenSection`）、定义 `:690`、九枚 testTag `:111`–`:130`、Route 收集 `:142`、新参数带默认值 `:197`（W6-41 把两张接收卡下移到页尾，行号已按当前文件更新，见 §16.1） |

- 区块在导入页里紧跟在 W6-27 的「从 Windows 接收」之后，两者都在**整页末尾**（结果卡与 verify 区之后；W6-41 为此把它们从顶部下移，理由见 §16.1）：**按钮位置一样、方向相反**——拨号接收要填 host/port 主动去连对端；
  监听接收是自己先占一个端口，让 Windows 用 `transfer send` 拨进来。
- 九枚 testTag：`sync.listen` / `sync.listen.port` / `sync.listen.tls` / `sync.listen.start` / `sync.listen.stop` / `sync.listen.receive` / `sync.listen.status` / `sync.listen.port-line` / `sync.listen.info`。
- `SourceSyncImportScreen` 的新参数全部带默认值（`:197`），所以既有 UI 测试的调用点一行都不用改。

### 20.2 状态机（六态）

| 状态 | 字段 | 怎么来的 |
| --- | --- | --- |
| `Idle` | — | 初始；`stop()` 之后；`clearResult()` 且没在监听 |
| `Listening` | `port` / `tls` / `certificateFingerprint` | `start()` 成功，值全部取自实际绑定的会话（端口留空时就是系统分配的那个） |
| `Receiving` | `port` | `awaitOnce()` 开始等 |
| `Received` | `port` / `deviceId` / `fileName` | 收到信封并已交给 `loadEnvelope` |
| `Rejected` | `port` / `reason` / `message` | 帧层成功但载荷不是合法信封，或网关整封拒绝（如信封内重复 `syncSourceId`） |
| `Failed` | `port?` / `code` / `detail` | 开监听失败时 `port` 为 null；接收中途失败带上当时的端口 |

三条入口的返回值语义：

- `start(form): Boolean` —— 端口留空 = 让系统分配（`0`）；端口非数字或越界 → `Failed(null, port-invalid)`，**工厂一次都不调用**；
  TLS 但没有可用身份 → `Failed(null, tls-certificate-unavailable)`，**连 socket 都不开**；已经在监听 → 直接 `false` 且状态不变。
- `awaitOnce(): Boolean` —— 没开监听 → `false`；已有一条在飞 → `false`（`AtomicBoolean` 单飞，连点只发一次）；
  成功、失败、拒收都返回 `true`（"这次真的等过并拿到了一个结果"）。
- `stop()` —— 幂等：只 `close()` 一次，第二次什么都不做；`clearResult()` 只清回执，监听开着就回到 `Listening`，不关端口。

超过约 5 秒的等待不是界面自己的超时：accept 与读帧的超时由监听端给（`acceptTimeoutMs` / `readTimeoutMs`，默认各 15 000 ms），
超时以 `SourceSyncTransferReasons.TRANSFER_TIMEOUT`（`transfer-timeout`）回到状态行，界面只显示码。

### 20.3 零写入保证（三层，缺一层都不算）

1. **网关固定 `acceptOnly`**：`SourceSyncListenerImportFactory` 的默认网关是 `SourceSyncImportGateways.acceptOnly()`（`SourceSyncTransferImportService.kt:126`），
   它只解码、回显 `deviceId` / `snapshotId` / 内容指纹，**一个字节都不写库**。监听阶段若接 `AndroidSourceSyncService`，等于绕过预览与逐条允许直接写库。
2. **控制器只在一条路径上回调**：`Imported` 且 `result.rejectedReason` 为空、且 `envelopeJson` 非空，才调用 `onEnvelope`；
   `TransferFailed` / `EnvelopeRejected` / 整封拒绝 / 缺信封原文 都只改本区块状态，`session` 一个字不动。
3. **`onEnvelope` 只走 `loadEnvelope`**：等价于用户手选了文件，**不调 `preview()`、不调 `apply()`**；预览与逐条允许仍然要用户自己点。

证据（`SourceSyncListenerControllerTest`，7 例）：失败/拒收路径逐例先种一个「已加载信封 + 已预览 + 已允许一条」的 session，
再断言 `assertEquals(before, session.state.value)`（整份 data class 相等）、`preview.calls` 仍为 1、`apply.calls` 为 0。

### 20.4 TLS 身份前提（今天的诚实边界）

- Android 不自造临时证书（那要在 App 里带 BouncyCastle），身份只能由调用方以 `KeyStore` 形式注入；
  今天 App 里**没有证书下发通道**，所以生产实现 `SourceSyncListenerImportFactory()` 用默认的 `SourceSyncTlsIdentity.NONE`，
  `tlsAvailability` 如实返回「不可用」，旁注原文就是 `SourceSyncListenCopy.TLS_UNAVAILABLE`（"这不是设置问题：证书下发还没做，界面不会假装支持。"）。
- 这种情况下点 TLS：控制器给 `tls-certificate-unavailable`，**socket 都不开**（不是先开再失败），TLS 开关在界面上是 disabled。
- 有身份时：监听端用 `SourceSyncTlsOptions.pinned(identity.certificateFingerprint)` 自钉扎，端口行追加「证书指纹：<64 hex>」供用户抄给 Windows 的 `--expect-fingerprint`。

### 20.5 与 Windows `transfer send` 的联调口径

```text
# 1) Android：点「开始监听」（端口留空或填一个），读端口行 → 127.0.0.1:<port>
# 2) Windows：
dotnet MediaHub.SyncCli.dll transfer send --to 127.0.0.1:<port> --root <dir> --device win-b --json

# TLS（Android 侧必须真的有身份证书，否则该开关点不动）
dotnet MediaHub.SyncCli.dll transfer send --to 127.0.0.1:<port> --root <dir> --device win-b \
    --tls --expect-fingerprint <Android 端口行上的 64 hex> --json

# 认证（Android 侧的 auth 参数今天固定 null，见 20.6 未覆盖第 2 条）
```

- 顺序固定：**Android 先绑端口，Windows 后拨**；Windows 的 `transfer send` 每次只发一封，发完即退。
- Android 侧开着监听可以连续接第二次（`awaitOnce()` 可重复调用），不必重新开端口。

### 20.6 用例表（25 例，全部 JVM 单测）

`feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerControllerTest.kt`（678 行）。

| 分组 | 用例 | 断言要点 |
| --- | --- | --- |
| 默认值与表单（4） | `defaultsAreIdleAndSayNothing` / `blankPortAsksTheOsForOne` / `typedPortIsUsedVerbatim` / `invalidPortsNeverReachTheFactory` | Idle 三行皆空；空端口传 0 给工厂；`8899` 原样传；`0/65536/-1/abc/9000.5/" 0x10"` 一律 `port-invalid` 且工厂 0 次 |
| 监听状态（3） | `listeningReportsTheBoundPortTlsFlagAndFingerprint` / `plaintextPortLineSaysPlaintext` / `stopIsIdempotentAndClosesOnce` | 端口/TLS/指纹取自会话；明文端口行是「（明文）」；`stop()` 两次只 `close` 一次 |
| 收一封进预览（3） | `receivingOnceDeliversTheEnvelopeIntoTheSession` / `importedEnvelopeCarriesTheListenFileName` / `twoReceivesInARowBothReachThePreviewPath` | 真 `SourceSyncImportSession`：`READY_TO_PREVIEW`、信封原样、`preview`/`applied` 为 null、`preview.calls` 0、`apply.calls` 0；文件名 `windows-listener.envelope.json`；连续两次都能换掉信封 |
| 失败零写入（4） | `transferFailureKeepsTheSessionUntouched` / `rejectedEnvelopeKeepsTheSessionUntouched` / `rejectedImportResultWritesNothing` / `importedWithoutEnvelopeJsonIsALocalFailure` | 每例先种 session，再断言整份 state 相等 + `preview.calls` 1 + `apply.calls` 0；`Rejected` 码原样（`duplicate-sync-source-id`）；缺信封原文 → `Failed(receive-failed, missing-envelope)` 且不回调 |
| TLS 前提（3） | `tlsWithoutAnIdentityIsRefusedBeforeOpening` / `tlsWithAnAvailableIdentityOpensAPinnedListener` / `listenerOpenFailureIsReportedAsItsCode` | 无证书：工厂 0 次 + 码 `tls-certificate-unavailable`；有证书：`lastTls=true`、指纹进状态；开失败：`listener-failed` + 端口进状态行 |
| 并发与重入（4） | `duplicateReceiveIsRejectedWhileOneIsInFlight` / `startWhileListeningIsRejected` / `awaitOnceWithoutListeningDoesNothing` / `cancelledReceiveGoesBackToListening` | 同刻第二次接收返回 false 且 `receiveCalls` 仍 1；已监听再开返回 false 且工厂 1 次；未监听 `awaitOnce` false；取消后回到 `Listening` 而不是留在 `Receiving` |
| 结果行与清空（4） | `acceptTimeoutIsReportedAsATimeoutCode` / `successAfterFailureCarriesNoStaleCode` / `clearResultDropsTheReceiptButKeepsListening` / `resultLineShowsTheCodeAndTheDiagnosticVerbatim` | 超时码 `transfer-timeout`；失败→成功后不留旧码；`clearResult()` 回 `Listening` 且端口还在；状态行逐字（`失败：码（detail）` / `信封被拒：码（msg）` / 含 `RECEIVED_HINT`） |

> 为什么这里不写 Compose UI 测试：区块里的逻辑全在状态机里（可 JVM 直接断言），真 socket 路径由 `core:common` 的
> `SourceSyncTransferListenerTest`（22 例）与 `core:database` 的 `SourceSyncListenerImportTest`（8 例）钉住；
> 在 UI 测试里再造一遍 fake 只会重复。

### 20.7 实测

本轮实跑（三模块全量重跑，非增量）：

```
pwsh> $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
pwsh> $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
pwsh> cmd /c "cd /d D:\deepseek_test\MediaHub_Win && gradlew.bat 
        :feature:settings:testDebugUnitTest :core:common:testDebugUnitTest 
        :core:database:testDebugUnitTest --console=plain --rerun-tasks 
        --no-daemon -Pkotlin.compiler.execution.strategy=in-process 
        > D:\mh-verify\w6-36-listen.log 2>&1"
EXIT=0
```

原始输出末尾两行：

```
BUILD SUCCESSFUL in 3m 35s
167 actionable tasks: 167 executed
```

JUnit XML 亲数（`build/test-results/testDebugUnitTest/*.xml` 逐文件累加）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 与基线对比 |
| --- | --- | --- | --- | --- | --- | --- |
| `core:common` | 31 | 418 | 0 | 0 | 10 | 与基线 31 / 418 相同（10 skipped 仍是第 18 节的 `SourceSyncTlsInteropTest`：没有 `MH_W632_SYNC_DLL` 时按设计整类跳过） |
| `core:database` | 17 | 158 | 0 | 0 | 0 | 与基线 17 / 158 相同 |
| `feature:settings` | 14 | 214 | 0 | 0 | 0 | 基线 13 / 189 → **+1 套件 / +25 例**；扣掉新增 25 例仍为 189，无回归 |

`feature:settings` 逐套件（本轮新增的加粗）：

| 套件 | 用例 | 耗时(s) |
| --- | --- | --- |
| `backup.BackupRepositoryTest` | 20 | 0 |
| `backup.BackupRestoreSafetyTest` | 19 | 0 |
| `backup.BackupViewModelTest` | 16 | 0 |
| `backup.ProductionBackupDataSourceTest` | 10 | 0 |
| `backup.ProductionBackupFileStoreTest` | 12 | 0 |
| `backup.ProductionCredentialRemovalTest` | 6 | 0 |
| `backup.RestoreSnapshotStoreTest` | 10 | 0 |
| `backup.SharedPrefsRestoreJournalTest` | 19 | 0 |
| `SettingsViewModelTest` | 3 | 0 |
| `sync.SourceSyncImportLogicTest` | 16 | 0 |
| `sync.SourceSyncImportSessionTest` | 11 | 0 |
| **`sync.SourceSyncListenerControllerTest`** | **25** | 0 |
| `sync.SourceSyncReceiveControllerTest` | 25 | 0 |
| `sync.SourceVerificationCoordinatorTest` | 22 | 0 |

> 耗时列全为 0：Gradle 在聚合报告里只给了秒级精度，而本模块最慢的单例也在 1 s 以内（25 例合计 0.0x s）。
> 这不影响结论——断言数（25）与失败数（0）才是判据。

本轮**首跑即绿**：没有出现编译红，也没有放宽任何断言或改产品语义（对比 §16.7、§19.7 都各自修过红）。
编译期只有仓库既有告警（`SettingsScreen.kt:92` 的 `Icons.Filled.ArrowBack` deprecated、`BackupRepository.kt:707` 恒真条件等），
本任务新增/修改的文件没有引入新告警。

> CI 口径：本套件只跑 JVM 单测、不依赖 Windows CLI，故**不入** `.github/workflows/android-ci.yml` 的 required-suite（仍 31 条）。

### 20.8 未覆盖

1. 未跑 instrumentation、未在真机上点过监听区块：本节全部证据是 JVM 单测 + 假工厂/假会话。
2. **认证开关没进界面**：`receiveAndImport(expectedChallenge, auth)` 的 `auth` 今天由界面固定传 `null`（即"对端宣布什么 nonce 都接受"的旧口径），
   认证能力本身已在 `core:common` 由 24 例钉住、并在第 18 节与 Windows 联调过，但用户还没有开关。
3. 证书下发通道不存在：今天只能明文；TLS 分支的端到端只在 `core:common` / `core:database` 单测与第 18 节跨端联调里验证过（Android 作服务端只在该节第 10 例用过）。
4. 只在本机回环上验证；非回环、跨机、防火墙/NAT、手机与 PC 不同网段都没测（按设计也不允许非回环）。
5. 没有 Compose UI 测试：九枚 testTag 已就位，只验证到「`SourceSyncImportScreen` 新参数有默认值、既有调用点不改」这一层。
6. 连续接收只测到两轮，也没有长时间挂监听的资源压测（accept 超时循环、反复开关端口）。

## 21. 反向跨端联调（W6-38）

第 18 节验的是「Android 发、Windows 收」；本节把方向反过来：**Windows 的 `transfer send` 作为发送方，Android 的监听端（第 19 节）作为接收方**。两个方向都跑过一遍，跨端才不是单向自证。

### 21.1 范围与边界

- 被测对象是 `SourceSyncListenerImportService`（第 19 节）+ 真 Windows CLI 子进程。**不是假对端**：每一例都用 `ProcessBuilder` 起一个新进程 `dotnet <MediaHub.SyncCli.dll> transfer send ...`，读完 stdout 的单份 JSON 与 stderr，再比对 Android 侧**真实的** Room `servers` 表行。
- Android 没有 Windows 的 `sync-sources.json` / `sync-baseline.json`；本端的等价外部证据是 Room `servers` 表行（`syncSourceId` / `syncRecordRevision` / `type` / `syncSourceTypeRaw` / 端点行），「基线」就是 `syncRecordRevision` 列。
- 断言只认三类外部证据：①子进程退出码与 `--json` 字段（`sourceCount` / `bytes` / `frames` / `tls` / `certificateFingerprint` / `secretEnv` / `deliveryConfirmed`）；②Room 真实行；③监听端返回的稳定码。**任何一例都不只看退出码**——`transfer send` 的 exit 0 只代表字节写进了 socket（第 18 节已实测：对端读都不读直接 close，它照样 exit 0）。
- 文件：`core/database/src/test/kotlin/com/mediahub/core/database/sync/SourceSyncListenerInteropTest.kt`（9 例；JUnit **4** + Robolectric `@Config(sdk=[34])`，与 W6-32 一样用 `org.junit.Assume` 门控——本仓 `core:database` 的测试依赖里没有 JUnit 5）。
- TLS 夹具 `core/database/src/test/resources/tls/mediahub-sync-test.p12` 是从 `core/common/src/test/resources/tls/` **同源复制**的（2454 B，sha256 `8b7329f862f550a8cd33edb38e5d8b9740825a3ec898984a974581941af14bc9`，口令 `mediahub-tls-test`，SPKI pin `2bb12d139178ee3b0f219e409a89c99b51be165914e21189eeb039b2e4dd37ce`）：`core:database` 看不到别的模块的 test resources，故复制而非引用，**不自造证书**。

### 21.2 运行方式与环境变量

```text
pwsh> $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
pwsh> $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
pwsh> $env:MH_W638_SYNC_DLL="D:\mh-w6\windows\src\MediaHub.SyncCli\bin\Debug\net10.0\MediaHub.SyncCli.dll"
pwsh> cmd /c "cd /d D:\deepseek_test\MediaHub_Win && gradlew.bat :core:database:testDebugUnitTest --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process > D:\mh-verify\w6-38-full.log 2>&1"
```

| 变量 | 必需 | 含义 |
| --- | --- | --- |
| `MH_W638_SYNC_DLL` | 是 | `MediaHub.SyncCli.dll` 的绝对路径；没有或不是普通文件 → 整类 9 例 `skipped`（见 21.4 第 3 次实跑） |
| `MH_W638_DOTNET` | 否 | dotnet 可执行文件，缺省 `dotnet` |
| `MH_W638_SECRET` | 认证例用 | 口令**只从子进程环境读**，不进命令行（与 CLI 的 `--secret-env` 同口径） |

必须 `--no-daemon`：Gradle daemon 的环境是它启动那一刻的快照，测试 worker 继承不到新设的 `MH_*` 变量，会整套 skipped 而「绿」。

### 21.3 九例

| # | 用例 | Windows 对端命令 | 断言要点 |
| --- | --- | --- | --- |
| 1 | `plaintextSendIsImportedAndWritesTwoRows` | `transfer send --to 127.0.0.1:<port> --root <tmp> --device win-c --json` | exit 0；`sourceCount=2`、`frames=1`、`bytes` == 收到的信封 UTF-8 长度、`frames == chunkCount(bytes)`；Room 两行（emby：`EMBY` / raw `emby` / rev 3 / `alice` / 端点 `http://127.0.0.1:8097` 为 primary；jellyfin：rev 2）；`appliedCount=2` |
| 2 | `tlsPinnedSendIsAcceptedByTheListener` | 加 `--tls --expect-fingerprint <pin>` | exit 0；JSON `tls=true`、`certificateFingerprint == expectFingerprint ==` 身份指纹；`service.tlsEnabled`；`appliedCount=2` |
| 3 | `wrongPinIsRefusedBeforeAnyEnvelopeByteIsWritten` | `--tls --expect-fingerprint <首字符改一位>` | Windows 侧 exit≠0 且 stderr 含 `tls-fingerprint-mismatch`；Room 0 行；**监听端拿到的是帧层码**（`frame-truncated` 或 `io-failed`）——钉扎在客户端，服务端握手成功后才看到对端关流（21.5 第 1 条） |
| 4 | `authenticatedSendIsImportedOnlyAfterTheTagFrameIsVerified` | `--authenticate --secret-env MH_W638_SECRET` | exit 0；`authenticate=true`、`secretEnv=MH_W638_SECRET`、`frames == chunkCount + 2`（挑战帧 + tag 帧）；`appliedCount=2` |
| 5 | `wrongSecretIsRejectedByTheListenerAndNothingIsWritten` | 同上，口令 `interop-secret-bravo`（监听端持 `alpha`） | 监听端 `TransferFailed(auth-mismatch)`；Room 0 行；`deliveryConfirmed` 为 null；**Windows 的 stderr 里没有 `auth-mismatch`**（发送端没有回执通道，看不到自己被拒） |
| 6 | `replayedFramesAreRefusedAsAuthReplayed` | 先经 `RecordingRelay` 代理端口把 Windows→本端的 782 B 原样录下，再把同一串字节裸写给同一个监听端 | 录制长度 == `envelope + 4 + 9 + 27 + 37`；第二次 `TransferFailed(auth-replayed)`；Room 仍 2 行且逐行 `syncSourceId + syncRecordRevision` 不变。CLI 没有固定 nonce 开关（`CreateNonce()` 每次随机），重放只能录真实帧 |
| 7 | `plaintextSendAgainstATlsListenerIsRejectedOnTheAndroidSide` | 监听端 TLS，Windows **明文**发 | 监听端给 `tls-` 前缀码（实测 `tls-handshake-failed`）；Room 0 行；`deliveryConfirmed` 为 null |
| 8 | `tlsAndAuthenticationComposeInOneConnection` | `--tls --expect-fingerprint <pin> --authenticate --secret-env ...` | exit 0；`tls=true` 且 `authenticate=true`；`appliedCount=2` |
| 9 | `theSecondSendOfTheSameSourcesWritesNothingNew` | 同一 `--root` 连发两次 | 第二次 `appliedCount=0`、`ignoredCount=2`、Room 仍 2 行（第二封规划成 `NO_CHANGE`） |

### 21.4 实测

三次实跑全部 `--rerun-tasks`（非增量），跑前 `Get-Process java` 为 0：

1. **定向单类**（`--tests com.mediahub.core.database.sync.SourceSyncListenerInteropTest`）：`BUILD SUCCESSFUL in 1m 39s` / `70 actionable tasks: 70 executed` / EXIT=0；XML `tests=9 skipped=0 failures=0 errors=0 time=6.816s`。
2. **全模块带 DLL**：`BUILD SUCCESSFUL in 1m 50s` / `70 actionable tasks: 70 executed` / EXIT=0；**18 套件 / 167 例 / 0 失败 / 0 错误 / 0 skipped**（基线 17 / 158 → **+1 套件 / +9 例**，扣掉新增 9 例仍 158，无回归）。
3. **全模块不带 `MH_W638_SYNC_DLL`**：`BUILD SUCCESSFUL in 1m 55s` / EXIT=0；18 套件 / 167 例 / 0 失败 / 0 错误 / **9 skipped**，跳过原因原文：`MH_W638_SYNC_DLL 没有指向 MediaHub.SyncCli.dll，跳过反向跨端联调（CI 上必须跳过）`。

逐例耗时（带 DLL，取自 JUnit XML；整类 6.816 s）：

| 用例 | 耗时(s) |
| --- | --- |
| `plaintextSendIsImportedAndWritesTwoRows` | 0.283 |
| `tlsPinnedSendIsAcceptedByTheListener` | 3.760 |
| `wrongPinIsRefusedBeforeAnyEnvelopeByteIsWritten` | 0.379 |
| `authenticatedSendIsImportedOnlyAfterTheTagFrameIsVerified` | 0.362 |
| `wrongSecretIsRejectedByTheListenerAndNothingIsWritten` | 0.363 |
| `replayedFramesAreRefusedAsAuthReplayed` | 0.301 |
| `plaintextSendAgainstATlsListenerIsRejectedOnTheAndroidSide` | 0.334 |
| `tlsAndAuthenticationComposeInOneConnection` | 0.442 |
| `theSecondSendOfTheSameSourcesWritesNothingNew` | 0.591 |

（第一例 TLS 慢到 3.76 s 是本机 JSSE 首次建 SSLContext/KeyStore + 真子进程冷启动的固定成本，不是重试。）

修红一条（**断言错、实现对**）：第 1 轮定向跑 `BUILD FAILED in 1m 45s`，9 例 8 绿 1 红。`wrongPinIsRefusedBeforeAnyEnvelopeByteIsWritten` 原先断言「监听端给 `tls-` 前缀码」，实测 `frame-truncated`：指纹钉扎在客户端（Windows 的 SslStream 握手成功后才比对证书，随即关流），服务端握手本身成功、接着读帧读到 EOF。改为断言帧层码（`frame-truncated` / `io-failed`）并加注释；Windows 侧证据（exit≠0 + stderr 里的 `tls-fingerprint-mismatch`）与「Room 0 行」**未放宽**。

### 21.5 跨端事实与未覆盖

实测出来的跨端事实（写下来免得下次重新踩）：

1. **指纹钉扎只在客户端**：pin 失配时服务端看不到 `tls-fingerprint-mismatch`，只看到「TLS 之上对端直接关流」→ `frame-truncated`。两端日志对不上是正常的，判据要分侧看。
2. `transfer send` 的 exit 0 **不代表对端接受**：没有 `--confirm-delivery` / `--await-receipt` 时没有任何回执证据；加了 `--confirm-delivery` 也只是等对端 close（探针里对端只读不收、照样 `deliveryConfirmed=true`）。
3. **发送端看不到失败原因**：错口令例里 Windows 侧只报它自己的错误，stderr 里没有 `auth-mismatch`；「本端拒收」的证据只能从监听端码 + Room 行数取。
4. 线长可预算：明文 = `4 + 9 + bytes`；认证再 `+ 27`（挑战帧 = 4 + 1 + 22 字符 nonce）`+ 37`（tag 帧 = 4 + 1 + 32）。`frames`：明文 = `chunkCount(bytes)`、认证 = `chunkCount + 2`。
5. CLI **没有固定 nonce 开关**（`SourceSyncTransferAuthenticator.CreateNonce()` 每次随机），所以进程级重放只能靠录制真实帧再原样重放。
6. 明文客户端打 TLS 监听端时，**服务端**能给出 `tls-handshake-failed`——与第 1 条不同：那条是「握手成功后对端关流」，这条是「握手本身就失败」。
7. 反向没有与 Windows 的 `transfer serve` 做对称测试（那是第 18 节的方向）；本节的 Windows 侧只用 `transfer send`。

未覆盖：

1. 未跑 instrumentation、未在真机上点过监听区块：本节证据是 JVM（Robolectric）单测 + 真子进程，不含设备侧行为。
2. 只在本机回环 `127.0.0.1` 上跑：非回环、跨机、防火墙/NAT、手机与 PC 不同网段都没测（按设计也不允许非回环）。
3. 只验证了「Windows 发一封、Android 收一封」：多封连发、长信封（多数据块）、`--await-receipt` / `--send-receipt`（0x04 回执）都没测。
4. 认证开关今天没进界面（20.8 第 2 条）：本节认证例是直接构造 `SourceSyncTransferOptions.withSecret(...)` 传进监听端，不是用户点出来的。
5. 本套件**不入** `.github/workflows/android-ci.yml` 的 required-suite（仍 31 条）：CI 上没有 Windows CLI 与 dotnet，整类按设计 skipped。

## 22. 回执帧（W6-40）

W6-37 在 Windows 侧加了「接收端解码成功后回一个裸 `0x04` 字节」，本节是 Android 侧对齐它的实现与证据。

### 22.1 为什么需要它（不是可选装饰）

传输层原本只有一种「对端收到了」的证据：传输结束时的 close。但**拒收之后关流与接受之后关流在 TCP 上长得一模一样**：

- W6-28 case F（明文）与 W6-35 case R6a（TLS）实测：发送端报成功（`exit 0`），而监听端在第一处校验就把信封拒了，一个信封字节都没入库；
- 加 `--confirm-delivery` 也只等到 close，对端只读不收、照样 `deliveryConfirmed=true`（第 21.5 节第 2 条已实测）。

所以只有「接收端解码后主动写出的一个字节」能区分「解码通过」与「拒收后关流」。这个字节**不参与帧长度/kind 体系**：

```
0x04            <- 一个字节，裸的：没有 4 字节大端长度前缀，也没有 kind 字节
```

对比其余帧（第 2 节）：挑战帧 `4 + 1 + n`、数据块 `4 + 9 + payload`、tag 帧 `4 + 1 + 32`。回执帧是唯一一个**不带长度前缀**的帧，读端只读一个字节、多余字节不读。

写入时机（两端同位置：端点层，不是帧解码器）：信封**解码成功之后**才写；解码失败、半帧、校验失败、认证失败、TLS 失败一律**沉默**——沉默不是错误，是「我拒了」的既定表达。

### 22.2 四码表（独立封闭集）

与 Windows `SourceSyncReceiptReasons` 同名同序，**不并入**传输十码 / 认证五码 / TLS 四码 / 投递一码。理由：「对端回了错字节」既不是帧失败、也不是认证失败、也不是 TLS 失败。

| 码 | 触发 | 帧是否已写出 | 备注 |
| --- | --- | --- | --- |
| `receipt-not-enabled` | 本端流不可读/不可写（读回执但流只写、写回执但流只读），或发送端 `input == null`，或消费方给不出超时设置器 | 否 | 是**本端能力**问题，不是对端问题 |
| `receipt-missing` | 对端关闭了连接却没有写任何字节 | 是（帧全写出去了） | 最容易被误读成成功：它正好是「拒收后关流」的形状 |
| `receipt-timeout` | 预算内既没字节也没关闭 | 是 | 与 `transfer-timeout`（读业务字节超时）刻意分开 |
| `receipt-invalid` | 读到别的字节（如 `0x02`） | 是 | detail 里带实际字节的十六进制 |

Android 侧 `object SourceSyncReceiptReasons`（`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncReceipt.kt`）四个常量 + `ALL`（同序），测试钉住：

- 四码顺序与 Windows 逐字一致；
- 四码与 `SourceSyncTransferReasons.ALL`（10）、`SourceSyncTransferAuthReasons.ALL`（5）、`SourceSyncTlsReasons.ALL`（4）两两不相交，且全部以 `receipt-` 前缀（`receiptCodesDoNotOverlapTheOtherThreeTables`）。

### 22.3 API 与 gate 语义

| 位置 | 成员 | 作用 |
| --- | --- | --- |
| `SourceSyncReceipt` | `RECEIPT_FRAME_KIND = 0x04` / `RECEIPT_FRAME_BYTES = 1` | 全仓唯一能写 `0x04` 的地方 |
| `SourceSyncReceipt` | `write(output: OutputStream?)` | 写一字节 + flush；不可写 → `receipt-not-enabled` |
| `SourceSyncReceipt` | `await(input: InputStream?, budgetMs = 2000)` | 读一字节；EOF → missing、别的字节 → invalid、超时 → timeout |
| `SourceSyncReceipt` | `acknowledge(output, envelope, options, gate)` | 端点层用：回执关着 → 沉默；gate 拒 → 沉默；否则 write |
| `SourceSyncReceiptOptions` | `Off` / `writing()` / `awaiting(budgetMs)` / `writingOrOff(bool)` / `awaitingOrOff(bool, budgetMs)` | 接收端角色 / 发送端角色；`awaiting` 非正或 null → 2000 |
| `SourceSyncTransfer` | `sendAndAwaitReceipt(output, input, envelope, challenge, options, receipt, readTimeoutSetter)` | 写完全部帧后等回执；**不确认时帧已写出**（`Ok` 变 `Failed(码)`，字节与帧数仍如实记） |
| `SourceSyncTransferListener` | `receiveOnce(..., receipt, receiptGate)` + `Received.receiptWritten` | 监听端的回执开关与证据位 |
| `SourceSyncTransferClient` | `receiveFrom(..., receipt, receiptGate)` / `sendTo(..., receipt)` | 拨号端的同一对能力 |
| `SourceSyncListenerImportService` | `receiveAndImport(..., receipt)` + `Imported.receiptWritten` | 导入层；gate = 信封能被 `SourceSyncCodec.tryDecode` 解出来 |
| `SourceSyncListenerController` | `SourceSyncListenForm.receipt` / `Received.receiptWritten` / `SourceSyncListenCopy.RECEIPT_WRITTEN_HINT` | 界面层；默认关，本轮**不加控件** |

gate 就是「解码是否成功」，与 Windows CLI 同形（`payload => SourceSyncInbox.TryDecode(...)`）：CLI 里解码做两次（gate 一次、之后正式导入再一次），Android 也做两次——gate 在 `SourceSyncListenerImportService` 里，正式导入在 `AndroidSourceSyncService.importEnvelope` 里。这样做的好处是：**回执只在「这封信封会被接受」时写出，而不是在「字节收全了」时写出**。

gate 拒绝时**不把一次好接收变成失败**：`acknowledge` 返回 `isSilent == true`（`confirmed=false, reason=null`），监听端照常返回 `Received(receiptWritten=false)`，导入层照常返回 `EnvelopeRejected(码)`。

### 22.4 与 Windows 对齐表

| 语义 | Windows（`D:/mh-w6/windows/src/MediaHub.Sync`） | Android（本节实现） |
| --- | --- | --- |
| 回执字节 | `SourceSyncReceipt.ReceiptFrameKind = 0x04`、`ReceiptFrameBytes = 1` | `SourceSyncReceipt.RECEIPT_FRAME_KIND = 0x04`、`RECEIPT_FRAME_BYTES = 1` |
| 四码 | `SourceSyncReceiptReasons`（missing / timeout / invalid / not-enabled，`All` 同序） | `SourceSyncReceiptReasons`（同名同序 + `ALL`） |
| 选项 | `SourceSyncReceiptOptions`：`Off` / `Sending()` / `Awaiting(budget)`、`DefaultBudgetMilliseconds = 2000` | `Off` / `writing()` / `awaiting(budgetMs)`、`DEFAULT_BUDGET_MS = 2000` |
| 写回执 | `SourceSyncReceipt.WriteAsync` | `SourceSyncReceipt.write` |
| 等回执 | `SourceSyncReceipt.AwaitAsync`（只读一个字节） | `SourceSyncReceipt.await`（只读一个字节） |
| 端点层闸门 | `ReceiveOnceAsync(..., receipt, receiptGate)` → `WriteReceiptAsync` | `receiveOnce(..., receipt, receiptGate)` → `SourceSyncReceipt.acknowledge` |
| 发送端裁决 | `SendAsync(..., receipt)`：不确认时 `Failed(... ) with { FrameCount, Bytes }` | `sendAndAwaitReceipt`：不确认时 `Failed(码, detail)`，`writeFrames` 已计数 |
| 明文路径写回执用哪个流 | 写到 **socket 本身**（probe 包装只读、拒绝写） | 同样：`writeStream ?: socket.getOutputStream()` |
| 结果位 | `ReceiptWritten` / `ReceiptConfirmed` | `Received.receiptWritten` / `Ok`（确认即 `Ok`，不确认即 `Failed`） |

### 22.5 关闭时字节完全不变

回执默认关（`SourceSyncReceiptOptions.Off`）：

- 发送端不读入站任何字节（只写帧 + flush，与 W6-22 一模一样）；
- 接收端不写任何字节，`Received.receiptWritten` 恒 `false`；
- 两个冻结向量文件 `core/common/src/test/resources/sync/windows-transfer-vectors.json` 与 `windows-auth-vectors.json` **本轮一个字节都没改**；`sendWithReceiptsOffWritesTheFrozenBytes` 断言 `sendAndAwaitReceipt(..., Off)` 写出的字节与 `SourceSyncTransfer.encode(envelope)` 逐字节相等。

### 22.6 用例表

| 套件 | 例数 | 覆盖 |
| --- | --- | --- |
| `core/common` `SourceSyncReceiptTest`（新建） | 33 | 码表同序与不相交；Off/writing/awaiting 选项与预算；Result 三工厂与 `isSilent`；`write` 一字节 / 不可写 / 坏流；`await` 确认 / EOF / 非法字节 / 超时 / IO；`acknowledge` 三态；`sendAndAwaitReceipt` 九例（Off 字节全等、确认、missing 时帧已写出、input null、timeout、invalid、setter 抛异常、预算落到 socket 超时、空信封仍先拒） |
| `core/common` `SourceSyncTransferListenerTest`（追加 6） | +6 | 监听端开回执写一字节、gate 拒收沉默、默认关不写、客户端等回执成功、客户端等回执 missing、TLS 上回执 |
| `core/database` `SourceSyncListenerImportTest`（追加 2） | +2 | 解码通过才回执（`Imported.receiptWritten == true`）；解不出的载荷沉默 + `EnvelopeRejected` + 网关 0 次调用 |
| `core/database` `SourceSyncListenerInteropTest`（追加 3，门控 `MH_W638_SYNC_DLL`） | +3 | 真 `transfer send --await-receipt` → `receiptConfirmed=true` + Room 2 行；Kotlin 等回执打真 `transfer serve --send-receipt` → `receiptWritten=true`；Windows 不带 `--send-receipt` → Kotlin `receipt-missing` |
| `feature/settings` `SourceSyncListenerControllerTest`（追加 3） | +3 | `form.receipt` 原样传到工厂；`Received.receiptWritten` 与结果行提示；默认关不声称回执 |

### 22.7 实跑计数

三次模块一起重跑（`--rerun-tasks`，跑前 `Get-Process java` = 0）：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:MH_W638_SYNC_DLL='D:\mh-verify\w6-38-cli\MediaHub.SyncCli.dll'   # 只有跨端三例用它
cmd /c "gradlew.bat :core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process > D:\mh-verify\w6-40-receipt.log 2>&1"
```

原始尾行：`BUILD SUCCESSFUL in 3m 30s` / `167 actionable tasks: 167 executed` / `EXIT=0`。

JUnit XML 亲数（逐文件累加 `<testsuite>` 属性）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| `core:common` | 32 | 457 | 0 | 0 | 10 |
| `core:database` | 18 | 172 | 0 | 0 | 0 |
| `feature:settings` | 14 | 217 | 0 | 0 | 0 |

本次新增/追加的套件单列：

| 套件 | 用例句数 | 套件耗时 | 备注 |
| --- | --- | --- | --- |
| `core.common.sync.SourceSyncReceiptTest` | 33 | 0.034 s | 新套件 |
| `core.common.sync.SourceSyncTransferListenerTest` | 28 | 0.443 s | 基线 22 → +6 |
| `core.database.sync.SourceSyncListenerImportTest` | 10 | — | 基线 8 → +2 |
| `core.database.sync.SourceSyncListenerInteropTest` | 12 | 2.778 s | 基线 9 → +3，**本次带 DLL 真跑**（没跳过） |
| `feature.settings.sync.SourceSyncListenerControllerTest` | 28 | 0.067 s | 基线 25 → +3 |

无回归算式：`core:common` 418 + 33 + 6 = 457；`core:database` 167 + 2 + 3 = 172；`feature:settings` 214 + 3 = 217。三个模块都是 0 失败 0 错误；唯一的 10 个 skipped 是 `SourceSyncTlsInteropTest` 在无 `MH_W632_SYNC_DLL` 时按设计整类跳过（第 18 节），本节没有新增跳过的套件。

跨端三例是**真子进程**跑的（`MH_W638_SYNC_DLL` 指向 `D:/mh-verify/w6-38-cli/MediaHub.SyncCli.dll`，不重新编译 `windows/`），逐例耗时：

| 用例 | 耗时(s) | 结论 |
| --- | --- | --- |
| `windowsAwaitingAReceiptGetsConfirmedByTheKotlinListener` | 0.406 | Windows `send --await-receipt` 拿到 `receiptConfirmed=true`；Android `Imported.receiptWritten=true`，Room 2 行 |
| `kotlinAwaitingAReceiptConfirmsAgainstWindowsServe` | 0.419 | Kotlin 等回执打 `serve --send-receipt` → `Ok`；serve JSON `sendReceipt=true`、`receiptWritten=true`，Windows 落 2 条 |
| `kotlinAwaitingAReceiptWithoutAWindowsReceiptIsMissing` | 0.423 | 对端没开回执 → Kotlin `receipt-missing`；serve 仍 exit 0（发送端看不到拒收原因，与 21.5 第 3 条一致） |

CI 跳过行为实测（去掉 `MH_W638_SYNC_DLL` 单跑该套件）：`BUILD SUCCESSFUL in 1m 37s` / `70 actionable tasks: 70 executed` / EXIT=0，XML 为 `tests="12" skipped="12" failures="0" errors="0"`，跳过原因原文：`MH_W638_SYNC_DLL 没有指向 MediaHub.SyncCli.dll，跳过反向跨端联调（CI 上必须跳过）`。

修红：**0 条**——本轮三模块首跑即全绿（编译 0 错，47 例新用例全过）。这点如实写明：没有放宽任何断言，也没有为了让测试变绿改实现语义。

### 22.8 未覆盖

1. **界面没有回执控件**：`SourceSyncListenForm.receipt`（`feature/settings/.../sync/SourceSyncListenerController.kt:174`）默认 `false`，只有测试与代码调用能打开 —— `SourceSyncImportScreen.kt` 全文没有 `receipt` 字样，界面上既无开关也无位置可言（W6-41 挪动两张接收卡片同样不涉及它）；要给用户开关是另一轮的事（还要先定文案：开了会让对端多等一个字节的往返）。
2. 未跑 instrumentation、未在真机上验证回执：全是 JVM 单测（Robolectric）与真子进程。
3. 回执只在本机回环上验证：非回环、跨机、手机 ↔ PC 不同网段都没测（按设计也不允许非回环）。
4. 未做「同一连接多封」：回执是一封一次（监听端一次 `receiveOnce` 只回一个字节），没有流水线/批量语义。
5. 未测对端写半个回执或多写字节的行为：读端只读一个字节，多余字节留给后续（或就此丢弃），没有专门用例钉住「多写字节会破坏下一封」这件事。
6. 跨端三例仍**不入** `.github/workflows/android-ci.yml` 的 required-suite（仍 31 条）：CI 上没有 dotnet 与 Windows CLI，整类按设计 skipped。
