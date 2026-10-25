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

## 23. 发送端与发送控制器（W6-42）

到 §22 为止，Android 侧验的都是「收」的方向（§19 回环监听端、§20 界面、§21 反向联调）。本节补「Android 当发送端」：`SourceSyncSendController` 把「先校验 → 再导出 → 最后拨号」钉成一条界面可点的路径，界面只负责贴表单。

### 23.1 为什么需要一个控制器（不是界面里直接调客户端）

- **顺序是硬约束**。`AndroidSourceSyncService.export()`（`core/database/src/main/kotlin/com/mediahub/core/database/sync/AndroidSourceSyncService.kt:199`）导出时会为没有 `syncSourceId` 的来源派生身份号并 `store.bindSyncIdentity(...)` **回写数据库**。若先导出、再发现「TLS 开了却没填指纹」，就白写一批身份号。控制器把三项前置校验放在导出之前：`unusableFingerprintIsRejectedBeforeExporting`（`SourceSyncSendControllerTest.kt:307`）与 `tlsWithoutFingerprintIsAlsoRejected`（:321）都用「导出端口调用数为 0」断言一步都没导出。
- **单飞**。导出会写库，重复点按并发两次就是两条并行导出。用 `AtomicBoolean.compareAndSet` 而不是读 `StateFlow`（后者在 `Sending` 落盘前有窗口）。`duplicateTapSendsOnlyOne`（:428）断言第二次调用返回 `false`，且对端只收到一封。
- **口令只在内存**。`SourceSyncSendForm.toString` 把口令打成 `secret=<redacted>`；所有 `Failed` 的 detail 只放稳定码或异常类名。`secretNeverAppearsInToStringOrState`（:410）断言整份 `state` 与表单字符串都不含口令原文。
- **失败码不翻译**。传输十码（§4）、认证五码（§15）、TLS 四码（§17）、回执四码（§22）在 `Failed(result.reason, result.message)` 里原样透传；本层只补 5 个「还没到传输层」的码（23.2）。界面文案 `SourceSyncSendCopy.lineOf` 只把人话写在结果行，不产生新码。

### 23.2 五码表（本层新增，独立封闭集）

五个常量在 `SourceSyncSendController` 的 companion（`feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncSendController.kt:366-378`），全部 `send-` 前缀，与传输十码 / 认证五码 / TLS 四码 / 回执四码**两两不相交**，单独成一集（它们是「本端表单还没填好」或「导出/拨号抛了没码的异常」，不是任何既有层的语义）。

| 码 | 触发 | 拨号 | 导出 |
| --- | --- | --- | --- |
| `send-port-invalid`（:366） | `portNumber == null`：空、0、越界、非数字 | 否 | 否 |
| `send-fingerprint-invalid`（:369） | 开了 TLS 但 `fingerprintOrNull == null`（不是 64 位 hex） | 否 | 否 |
| `send-secret-missing`（:372） | 开了认证但口令为空 | 否 | 否 |
| `send-export-failed`（:375） | 导出抛异常；detail 只有异常类名 | 否 | 失败 |
| `send-failed`（:378） | 传输层抛了非传输码的异常；detail 只有异常类名 | 是 | 是 |

前四项都不拨号（`invalidPortsNeverReachTheTransport`（:77）用「对端零连接」证明端口那一项；`unusableFingerprint...`/`tlsWithoutFingerprint...` 证明指纹两项；`exportFailureIsReportedWithoutDialing`（:444）证明导出项），只有 `send-failed` 是拨号之后。

### 23.3 API 与语义

| 位置（`SourceSyncSendController.kt`） | 成员 | 作用 |
| --- | --- | --- |
| :24 | `sealed interface SourceSyncSendState` | 四态：`Idle`（:27）/ `Sending`（:30）/ `Sent`（:38）/ `Failed`（:47） |
| :38 | `Sent(bytes, frames, receipt, sourceCount, skippedCount)` | `bytes` 是**信封字节数**（不是线上字节，线上字节见 23.5）；`frames` 是传输层写出的帧数 |
| :51-126 | `object SourceSyncSendCopy` | 全部界面文案（标题/标签/三句必填提示/结果行）；`lineOf(state)`（:87）/ `codeOf(state)`（:95） |
| :128 | `data class SourceSyncSendExport(envelopeJson, sourceCount, skippedCount)` | 导出端口的一次返回值 |
| :135 | `fun interface SourceSyncExportPort { suspend fun export() }` | 允许测试塞假导出、生产塞 `AndroidSourceSyncService` |
| :145 | `fun interface SourceSyncSendPort { suspend fun send(host, port, envelope, challenge, tls, auth, receipt) }` | 传输端口；生产实现 `SourceSyncTransferSendPort`（:162）转调 `SourceSyncTransferClient` |
| :190 | `data class SourceSyncSendForm(host, port, challenge, tls, fingerprint, auth, secret, receipt)` | 表单值对象；派生 `hostOrLoopback`（:202）/ `portNumber`（:206）/ `challengeOrNull`（:209）/ `fingerprintOrNull`（:213）/ `isFingerprintAcceptable`（:216）/ `isSecretAcceptable`（:219）/ `canSubmit`（:222） |
| :226 | `toString` | 口令打码为 `secret=<redacted>` |
| :257 / :279 | `class SourceSyncSendController` / `suspend fun send(form): Boolean` | 顺序见 23.1；`false` 表示「这次根本没发」（前置校验失败或单飞被挡） |
| :359 | `fun clearResult()` | 清掉上一次的 `Sent`/`Failed`，回到 `Idle` |

界面接线（`feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt`）：tags 在 `object SourceSyncImportTags` 末尾 `sync.send.*` 十一个（`SEND`/`SEND_HOST`/`SEND_PORT`/`SEND_CHALLENGE`/`SEND_TLS`/`SEND_PIN`/`SEND_AUTH`/`SEND_SECRET`/`SEND_RECEIPT`/`SEND_ACTION`/`SEND_STATE`（:158））；`SendSection`（:871）放在页面 **Column 末尾**（调用点 :432，紧跟 `ListenSection`），遵守 W6-41 的「接收/发送区块放页尾、首屏保留导入主流程」纪律；口令框用 `PasswordVisualTransformation()`（:973）。ViewModel 侧（`SourceSyncImportViewModel.kt`）：`sourceSyncService`（:93）→ `sendController`（:104，用显式 `object : SourceSyncExportPort` 而不是 SAM lambda）→ `sendState`（:119）→ `send(form)`（:197）→ `clearSendResult()`（:202）；`reset()`（:210）与 `onCleared()`（:221）各清一次发送结论。

### 23.4 与 Windows `transfer send` 对齐表

| 语义 | Windows（`D:/mh-w6/windows/src/MediaHub.Sync` + CLI） | Android（本节实现） |
| --- | --- | --- |
| 目标 | `transfer send --to 127.0.0.1:<port>` | `form.host`（默认 `127.0.0.1`，:238）+ `form.portNumber`（必填，1-65535；发送端没有「系统分配端口」） |
| TLS + 钉扎 | `--tls --expect-fingerprint <64hex>` | `tls=true` + `fingerprint` → `SourceSyncTlsOptions.Pinned(hex)`（§17） |
| 认证 | `--authenticate --secret-env <ENV>`（口令只从环境变量读） | `auth=true` + `secret` → `SourceSyncTransferOptions.withSecret(ByteArray)`（§15）；口令只从界面进内存，不进日志 |
| 挑战 / nonce | CLI 不暴露；由 CLI 内部与 `--authenticate` 一起处理 | `form.challenge` 去除首尾空白后原样传给传输层（`challengeIsTrimmedAndForwarded`（:503））；开着认证但挑战为空时由引擎先拒（`authWithoutChallengeIsRefusedByTheEngineBeforeWriting`（:389）） |
| 回执 | `--await-receipt`（对端要开 `--send-receipt`） | `form.receipt` → `SourceSyncReceiptOptions.awaiting()`（§22） |
| 计数 | `--json` 里的 `sourceCount` / `bytes` / `frames` | `Sent.sourceCount` / `Sent.bytes` / `Sent.frames` 同名同义 |
| 投递确认 | `--confirm-delivery` 只等 close，不代表对端接受（§21.5 第 2 条） | 不提供；只认回执（`form.receipt`） |

### 23.5 默认全关时线上字节不变

默认「TLS 关 / 认证关 / 不等回执」时，线上字节与 W6-22 冻结向量、W6-25 认证向量**逐字节等价**，证据是三条断言（不是「测试全绿」这种笼统说法）：

1. `core/common` 的 `sendWithReceiptsOffWritesTheFrozenBytes`（`core/common/src/test/kotlin/com/mediahub/core/common/sync/SourceSyncReceiptTest.kt:238`）：`sendAndAwaitReceipt(..., SourceSyncReceiptOptions.Off)` 写出的字节 == `SourceSyncTransfer.encode(envelope)`。它固定住了「回执关 = 一个字节都不多写」。
2. 本节新增的 `realLoopbackDeliveryWritesTheEnvelopeJson`（`SourceSyncSendControllerTest.kt:115`）：控制器走完 `send` 之后，回环对端**从流里读到的字节数** == `SourceSyncTransfer.encode(payload).size`（:126），且 `recorder.envelope` 与导出信封逐字节相等（:125）。即「控制器这一层没有额外写任何东西」。
3. `plainSendWithoutReceiptSaysSoInTheLine`（:156）：全关时结果行**明确写着没等回执**，不会把 close 当成送达。

两个冻结向量文件 `core/common/src/test/resources/sync/windows-transfer-vectors.json` 与 `windows-auth-vectors.json` 本轮一个字节都没改。

注意 `Sent.bytes` 与「线上字节」不是同一个数：`Sent.bytes` = 信封字节（如 665），线上字节 = 4 字节帧长 + 9 字节块头 + 信封，用例里分别断言（`Recorder.bytes` 的 KDoc 已写明它含帧头）。

### 23.6 用例表（26 例，纯 JVM）

| 组 | 用例（行号在 `SourceSyncSendControllerTest.kt`） | 钉住什么 |
| --- | --- | --- |
| 初态与表单 | `defaultsAreIdleAndSafe`（:59）、`boundaryPortsAreAccepted`（:95）、`invalidPortsNeverReachTheTransport`（:77）、`tlsWithoutFingerprintIsAlsoRejected`（:321）、`unusableFingerprintIsRejectedBeforeExporting`（:307） | 四态初值、端口边界 1/65535、非法端口/缺指纹不拨号也不导出 |
| 明文投递 | `realLoopbackDeliveryWritesTheEnvelopeJson`（:115）、`sentStateCarriesBytesFramesAndSourceCount`（:135）、`plainSendWithoutReceiptSaysSoInTheLine`（:156）、`nonLoopbackHostIsRejectedBeforeDialing`（:106）、`cancelledSendGoesBackToIdle`（:486） | 真回环字节、`Sent` 三计数、全关时结果行、非回环在拨号前被拒、取消回 `Idle` |
| 回执四码 | `receiptConfirmedWhenThePeerAcks`（:172）、`receiptMissingWhenThePeerClosesWithoutAnswering`（:194）、`receiptTimeoutWhenThePeerStaysSilent`（:213）、`receiptInvalidWhenThePeerAnswersAnotherByte`（:235） | 四码全部由真对端行为触发，控制器只透传 |
| TLS | `tlsPinnedDeliveryOverRealTls13`（:257）、`wrongPinIsRefusedBeforeAnyEnvelopeByte`（:283） | 真 `SSLServerSocket`（TLSv1.3）收一封；错 pin 时对端收到 0 字节 |
| 认证 | `authenticatedDeliveryAddsTheTagFrame`（:361）、`authMismatchIsSeenByThePeerNotByTheSender`（:334）、`authWithoutChallengeIsRefusedByTheEngineBeforeWriting`（:389）、`challengeIsTrimmedAndForwarded`（:503） | tag 帧存在、错口令由对端发现（发送端仍 `Ok`，与 §18.5 一致）、空挑战先拒、挑战去空白 |
| 口令不外泄 | `secretNeverAppearsInToStringOrState`（:410） | 表单字符串与整份 `state` 都不含口令 |
| 状态机 | `duplicateTapSendsOnlyOne`（:428）、`exportFailureIsReportedWithoutDialing`（:444）、`successAfterFailureCarriesNoStaleCode`（:459）、`clearResultDropsThePreviousResult`（:476） | 单飞、导出异常码、成功后不残留旧码、清结果 |
| 夹具自证 | `fixturePinsMatchTheFrozenWindowsVectors`（:516） | 抄来的两枚 p12 的 pin 与冻结向量逐字相等（防夹具漂移） |

测试替身：`CountingInput` 统计应用写出的字节；`Peer` 用 `ServerSocket(0, 8, loopback)` 起真回环对端、`await()` 把线程异常带栈抛回测试线程；TLS 对端用 `feature/settings/src/test/resources/tls/` 下的两枚 p12（`mediahub-sync-test.p12` 2454 B、`mediahub-sync-other.p12` 2462 B，与 `core/common` 同一对）。

### 23.7 实跑计数与修红记录

三模块一起重跑（`--rerun-tasks`，跑前 `Get-Process java` = 0）：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:MH_W638_SYNC_DLL='D:\mh-verify\w6-38-cli\MediaHub.SyncCli.dll'
cmd /c "gradlew.bat :feature:settings:testDebugUnitTest :core:common:testDebugUnitTest :core:database:testDebugUnitTest --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process > %TEMP%\w642b.log 2>&1"
```

原始尾行：`BUILD SUCCESSFUL in 3m 32s` / `167 actionable tasks: 167 executed` / `EXIT=0`。

JUnit XML 亲数（逐文件累加 `<testsuite>` 属性）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| `core:common` | 32 | 457 | 0 | 0 | 10 |
| `core:database` | 18 | 172 | 0 | 0 | 0 |
| `feature:settings` | 15 | 243 | 0 | 0 | 0 |

本次新增套件单列：`feature.settings.sync.SourceSyncSendControllerTest` = 26 例、耗时 3.062 s（`tests="26" failures="0" skipped="0"`）。

无回归算式：`feature:settings` 217 + 26 = 243（套件 14 → 15，新增 1 个）；`core:common` 与 `core:database` 数字与 §22 快照完全一致（457 / 172，未改一行生产代码）；10 个 skipped 仍是 `SourceSyncTlsInteropTest` 在无 `MH_W632_SYNC_DLL` 时按设计整类跳过（§18），本节没有新增跳过。

**修红记录（如实写）**：首跑 `243 tests completed, 4 failed`，三例是测试夹具错位——`FakeExportPort` 的默认信封只有 203 B（deviceId/snapshotId 最小信封），断言却拿两来源的 665 B 信封比；另有一条把 `Recorder.bytes`（线上字节，含帧头）当信封字节比。**都是夹具/断言尺度问题**，改法是给 `exportPort()` 助手统一给 665 B 信封、`Recorder.bytes` 比 `SourceSyncTransfer.encode(payload).size`；**没有放宽任何断言**。

剩下的一例是环境问题，不是实现缺陷：`tlsPinnedDeliveryOverRealTls13` 在全模块运行时红、单类运行自己变绿。根因是**本模块的 Robolectric 用例（backup 套件）会把 Conscrypt 插为该 JVM 的默认 TLS provider 且一直存活**，JDK 21 上 Conscrypt 要反射读 `InetAddress` 内部字段，缺 `--add-opens` 时握手抛 `InaccessibleObjectException`（栈：`org.conscrypt.Platform.getOriginalHostNameFromInetAddress(Platform.java:571)`）。处置：`feature/settings/build.gradle.kts:35-41` 给本模块的**测试 JVM** 加 `--add-opens=java.base/java.net=ALL-UNNAMED`（`tasks.withType<Test>().configureEach { jvmArgs(...) }`），**只放宽测试 JVM 的模块访问，不改断言、不改生产语义**；真机上 Conscrypt 是平台组件，不存在这个编译期/运行期限制。这也是「单类绿 ≠ 模块绿」的一个具体例子。

### 23.8 未覆盖

1. **界面没有取消按钮**：控制器支持取消（`cancelledSendGoesBackToIdle`），但 `SendSection` 只把 `Sending` 期间的控件禁用，没有「中止」入口 —— 取消能力目前只有代码/测试可达。
2. **表单不持久化**：主机、端口、挑战、指纹、TLS/认证/回执三个开关都只活在一次组合内（`remember`），离开页面即丢；也没有「上次用过的配置」记忆。
3. **只有一个目标**：发送时只填「主机 + 端口」，没有对接 Windows 的 `--root`/设备号（那些是 Windows 侧参数），也没有多目标列表或重发历史。
4. 未跑 instrumentation、未在真机验证发送流程：全是 JVM 单测（真回环 socket + 真 TLSv1.3 握手），界面层只有编译与 tags 层面的证据。
5. 只覆盖回环：非回环按设计在拨号前拒（`non-loopback-peer`），跨机、手机 ↔ PC 不同网段、非 `localhost` 的 IPv6 字面量都没测。
6. 未测大信封与预算：没有「来源很多 → 信封接近 8192 帧上限」的用例，也没有对端慢读时 `transfer-timeout` 的端到端用例（那是传输层 §4 的范围，本节未重复）。
7. 跨端联调不在本轮：本节的 TLS 对端是**测试里的** `SSLServerSocket`，不是真 Windows `transfer serve`；真 CLI 的跨端证据仍只有 §18（Android 发）/ §21（Windows 发）。
8. 本套件**不入** `.github/workflows/android-ci.yml` 的 required-suite（仍 31 条，按任务书不碰 CI 名单）；它是纯 JVM 单测，CI 会随 `:feature:settings:testDebugUnitTest` 跑到，但没有单独的必跑条目。

## 24. 三张卡（接收 / 监听 / 发送）的界面证据（W6-44）

### 24.1 为什么补这一节

W6-27 / W6-36 / W6-42 把三张卡加到了同步页页尾，但三张卡的行为（端口闸门、TLS 指纹闸门、口令闸门、失败码与结果行）此前只有 JVM 单测与纯 Kotlin 控制器测试钉住；`feature/settings` 的 androidTest 只有导入主流程的 6 例（`SourceSyncImportScreenTest.kt`，324 行），三张卡没有任何 instrumentation 证据。

新增 `feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/sync/SourceSyncSectionsScreenTest.kt`（396 行，19 例）。**没有改动任何生产代码**，也没有改既有导入页那 6 例的任何断言（它们在同一个 androidTest 编译单元里，照样编译通过）。

### 24.2 用例表（19 例）

| 组 | 用例（行号在 `SourceSyncSectionsScreenTest.kt`） | 钉住什么 |
| --- | --- | --- |
| 接收 | `receiveSectionStartsOnTheLoopbackHostAndWaitsForAPort`（:55）、`receivePortGateAcceptsOnlyRealPorts`（:67） | host 默认 `127.0.0.1`；端口没填时 `接收` 禁用且显示 `PORT_REQUIRED`；`0` / `70000` 仍禁用、`8999` 才启用 |
| 接收 | `nonLoopbackReceiveHostIsForwardedAsTyped`（:82）、`aNotLoopbackFailureIsShownWithItsStableCode`（:102） | 非回环地址**原样转交**（表单层不拦，见 `SourceSyncReceiveController.kt:88-90`）；`Failed("not-loopback")` 渲染成 `接收失败：not-loopback` |
| 监听 | `listenStartsWithASystemAssignedPortAndStopsDisabled`（:112）、`anIllegalListenPortBlocksStarting`（:134） | 端口留空 = 系统分配 ⇒ `开始监听` 可用；`0` 禁用、`45555` 启用；空转态没有端口行、`停止` / `等一次发送` 禁用 |
| 监听 | `listenTlsIsDisabledAndTheReasonIsShown`（:124） | 本机无 KeyStore 身份时 TLS 开关禁用，且 `TLS_UNAVAILABLE` 原文显示在 `LISTEN_INFO` 上（不给假开关） |
| 监听 | `listeningShowsThePortLineAndEnablesStopAndReceive`（:146） | `Listening(45678)` 时端口行含 `45678`，`停止` / `等一次发送` 启用，端口框与 `开始监听` 锁住 |
| 发送 | `sendSectionNeedsAPortAndKeepsPinAndSecretDisabled`（:161）、`sendPortGateAcceptsOnlyRealPorts`（:174） | 默认 `发送` 禁用 + `PORT_REQUIRED`；TLS/认证都没开时指纹框与口令框不可编辑；`0` / `70000` 禁用、`8942` 启用 |
| 发送 | `aValidPortHandsTheSendFormOverExactly`（:189） | 点下去交出的表单就是所填：`portNumber=8942`、`hostOrLoopback=127.0.0.1`、TLS/认证/回执三个开关全关 |
| 发送 | `tlsOnWithoutAUsablePinBlocksSending`（:208）、`aPinnedFingerprintLetsTheFormSubmit`（:224） | 开 TLS 后指纹框才可编辑；指纹 `abc` 仍禁用并显示 `PIN_REQUIRED`；64 位 hex（`"a0" * 32`）才启用且 `fingerprintOrNull` 可解析 |
| 发送 | `authOnWithoutASecretBlocksSending`（:246）、`theSecretFieldIsMaskedAndItsValueIsForwarded`（:260） | 开认证后口令框才可编辑；空口令禁用并显示 `SECRET_REQUIRED`；口令框带 `SemanticsProperties.Password`（真打码）、值原样交给表单、表单 `toString` 里是 `secret=<redacted>` |
| 发送 | `aSendFailureShowsItsOwnCode`（:285）、`aSendSuccessSaysWhetherTheReceiptWasAwaited`（:293） | 失败行 = `发送失败：peer-timeout`；关着等回执时成功行必须写「没有等回执」（不能让人以为对端收下了，同 §22/§23 的说法） |
| 首屏不变式 | `theThreeSectionCardsNeverPushTheMainFlowOffTheFirstScreen`（:313）、`theMainFlowStaysOnTheFirstScreenEvenWithAPreviewBelow`（:326） | **不滚动**就断言 `选择文件` / `预览` 仍在首屏；三张卡要滚动才可见；预览展开后主流程仍在首屏（W6-41 的回归闸门） |

### 24.3 滚动纪律

- 页面根节点是 `verticalScroll` 的 `Column`（`SourceSyncImportScreen.kt:253`），三张卡刻意放在页尾（`:419-422` 的注释），真机视口里它们在首屏之外——**凡是要断言「看得见」或要点击的节点，一律先 `performScrollTo()`**（§24.2 里除首屏不变式两组外全部如此）。
- 首屏不变式两组**故意不滚动**：它们要证明的就是「主流程没被顶下去」。这条纪律来自 API 32 上 4 个用例假红（2026-10-24，run 40028318723）与 W6-41 的修复（那两个接收区块移到页尾）。
- 断言只依赖 `SourceSyncImportTags` 的稳定标签与 `SourceSyncReceiveCopy` / `SourceSyncListenCopy` / `SourceSyncSendCopy` 的常量文案，不依赖布局顺序、不依赖像素。

### 24.4 编译证据（本机；DEVICE_UNVERIFIED）

本机 `adb devices` 输出只有表头（无设备、无模拟器），按纪律**不起模拟器**，因此 `connectedDebugAndroidTest` 未运行——本节的设备证据状态是 **DEVICE_UNVERIFIED**，执行证据由 CI 的 visual-instrumentation job（API 32 / API 36）提供。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cmd /c "gradlew.bat :feature:settings:assembleDebugAndroidTest --console=plain --no-daemon -Pkotlin.compiler.execution.strategy=in-process > %TEMP%\w644.log 2>&1"
```

原始尾行：`BUILD SUCCESSFUL in 43s` / `155 actionable tasks: 6 executed, 149 up-to-date` / `EXIT=0`（`packageDebugAndroidTest` 与 `assembleDebugAndroidTest` 均在输出末尾）。

**编译修红记录（如实写）**：首跑 `:feature:settings:compileDebugAndroidTestKotlin FAILED`，唯一错误 `SourceSyncSectionsScreenTest.kt:7:33 Unresolved reference 'assertDoesNotExist'`——`assertDoesNotExist()` 是 `SemanticsNodeInteraction` 的成员函数，不是扩展函数，**不能 import**；删掉那行 import 后第二次编译即绿（`BUILD SUCCESSFUL in 43s`）。除此之外没有改过任何断言。

### 24.5 未覆盖

1. **未在设备上执行**：本机无 adb 设备，19 例只到「编译通过 + 打包出 androidTest APK」，实际执行结果要等 CI（DEVICE_UNVERIFIED）。
2. **未覆盖三张卡的端到端路径**：本节全是纯界面证据（回调参数 + 状态渲染），真 socket / 真 TLS / 真回执由 §18（Android 发）、§21（Windows 发）、§23（发送控制器 JVM 单测）覆盖，本节不重复。
3. **未覆盖 `Sending` 与 `Receiving` 忙碌态**：只断言了 Idle / Sent / Failed / Listening 四个状态的渲染，忙碌态的进度行与控件禁用没有专门用例。
4. **未覆盖超长文案与窄屏换行**：19 例都不改字体缩放、不模拟窄屏，中文长句换行后的布局没有断言（那是渲染层截图对比的活，不在本仓 Compose 测试范围内）。
5. **未覆盖 Hilt 注入路径**：测试直接调 `SourceSyncImportScreen(...)` 组合函数并手传状态，`SourceSyncImportRoute` / `SourceSyncImportViewModel` 的注入与状态收集（`collectAsStateWithLifecycle`）没有 instrumentation 证据。
6. 本套件同样**不入** `.github/workflows/android-ci.yml` 的 required-suite（仍 31 条）；它是 androidTest，CI 会随 visual-instrumentation 的 `connectedDebugAndroidTest` 跑到。

## 25. 投递确认（W6-46，对齐 Windows W6-30 / W6-39）

### 25.1 为什么需要它

单向发送的 `Ok` 只证明「帧写进了 socket」，不证明对端读完或接受。这一点本仓有两条反证：§7 差异之一、§18 用例 5/7（明文客户端打 TLS 端点、口令不一致时，发送端都报 `Ok`，真正拒绝只发生在 Windows 一侧）。Windows 侧早已用「写完最后一帧后读对端直到 FIN，预算 2000 ms」把这一步补上（`D:/mh-w6/windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs:730-732` 收完帧 `TryHalfClose` 半关、`SourceSyncDeliveryConfirm.cs` 发送端读 FIN），Android 侧此前没有对应物。本节补上它，并保持「默认全关、开了才变」的纪律。

### 25.2 一个失败码（独立封闭集）

`SourceSyncDeliveryReasons` 只有一条，与传输十码（§4）、认证五码（§15）、TLS 四码（§17）、回执四码（§22）**没有任何重名**（`deliveryCodeDoesNotOverlapTheOtherFourTables` 钉住）。

| 码 | 触发 | detail 里有什么 |
| --- | --- | --- |
| `delivery-unconfirmed` | 开了确认，但没读到对端关连接 | 已发信封字节数 + 「N 毫秒内没关」/「还在发（已读 M 字节）」/异常类名，不含信封内容 |

`SourceSyncDeliveryResult` 只有两种：`Confirmed(bytesRead, elapsedMs)` 与 `Unconfirmed(reason, detail, elapsedMs)`；`confirm(...)` **永不抛异常**，失败一律折成 `Unconfirmed`。

### 25.3 API 与语义

| 组件 | 形状 | 语义 |
| --- | --- | --- |
| `SourceSyncDeliveryOptions` | `Off` / `Confirming(budgetMs)`；`DEFAULT_BUDGET_MS = 2_000`；`confirming(budgetMs?)`、`confirmingOrOff(enabled, budgetMs?)` | `budgetMs <= 0` 或 `null` 一律回落 2000；`toString` 只印 Confirm 与 BudgetMs |
| `SourceSyncDeliveryConfirmation.confirm(input, sentBytes, budgetMs, setReadTimeout)` | `MAXIMUM_DRAIN_BYTES = 64 * 1024`、256 字节 scratch | 每轮把**剩余预算**（最少 1 ms）设进 soTimeout 再读 |
| `SourceSyncTransferResult.Ok` | 第 4 个参数 `delivery: SourceSyncDeliveryResult? = null` | 只在发送端开了 Confirming 时非 null；读端与既有三参构造零影响 |
| `SourceSyncTransferClient.sendTo(...)` | 末参 `delivery: SourceSyncDeliveryOptions = SourceSyncDeliveryOptions.Off` | 见 25.5 的优先级；`Off` 时不读一个字节、不多等一次 |
| `SourceSyncSendController` | `SourceSyncSendForm.delivery`、`SourceSyncSendState.Sent.deliveryConfirmed`、`SourceSyncSendCopy.DELIVERY_LABEL/DELIVERY_HINT` | `deliveryConfirmed` 语义：`true` = 读到对端关连接；`null` = 没开或被回执取代（读不到 FIN **不会**落到 `Sent`，而是 `Failed("delivery-unconfirmed")`） |

判定次序（`confirm` 内部）：

| 观察 | 结论 |
| --- | --- |
| `read` 返回 `-1`（EOF / FIN） | `Confirmed(drained, elapsed)` |
| 累计读到 `> 64 KiB` | `Unconfirmed`：对端在一封信封之后还在发，不是在确认收到 |
| `SocketTimeoutException` | `Unconfirmed`：对端没有在 `<budgetMs>` 毫秒内关闭连接 |
| `IOException` / 其它异常 | `Unconfirmed`：只带类名，不带消息 |
| `setReadTimeout` 本身抛 | `Unconfirmed`：读超时设不上 |

### 25.4 与 Windows 对齐表

| 项 | Android（本节点） | Windows |
| --- | --- | --- |
| 失败码 | `delivery-unconfirmed` | `SourceSyncDeliveryReasons.DeliveryUnconfirmed = "delivery-unconfirmed"` |
| 默认预算 | `DEFAULT_BUDGET_MS = 2_000` | `SourceSyncDeliveryOptions.DefaultBudgetMilliseconds = 2000` |
| 排空上限 | `MAXIMUM_DRAIN_BYTES = 64 * 1024` | `SourceSyncDeliveryConfirmation.MaximumDrainBytes = 64 * 1024` |
| scratch | 256 字节 | 256 字节 |
| 成功判据 | 读到 EOF | 读到 0 字节（EOF） |
| 优先级 | 回执 > 投递确认 | `SyncCliRunner.cs:964` `ConfirmDelivery && !AwaitReceipt` |
| CLI 开关 | 无（控制器参数 + 端口参数，界面无控件） | `transfer send --confirm-delivery`，JSON 字段 `confirmDelivery`（`SyncCliRunner.cs:1284`） |

### 25.5 优先级：回执优先，两端同口径

回执是应用层的 0x04 ACK（§22），投递确认是传输层的 FIN；两者同时开只会多一次读，没有额外信息。因此**回执优先**在三处各挡一次：

1. Windows CLI：`SourceSyncDeliveryOptions delivery = invocation.ConfirmDelivery && !invocation.AwaitReceipt ? Confirming : Off;`（`SyncCliRunner.cs:964-966`）。
2. Android 客户端：`val effectiveDelivery = if (receipt.enabled) SourceSyncDeliveryOptions.Off else delivery`。
3. Android 控制器：`SourceSyncDeliveryOptions.confirmingOrOff(form.delivery && !form.receipt)`（界面两个开关都开时也不会把确认传下去）。

### 25.6 预算与 64 KiB 上限

- **预算**：默认 2000 ms；`confirming(0)` / 负值回落 2000（`budgetFallsBackToTheDefault`）。超时是用「剩余预算」逐轮重设 `soTimeout` 实现的，所以超时时能报出「对端没有在 2000 毫秒内关闭连接」而不是笼统的 `transfer-timeout`。
- **64 KiB**：对端在信封之后继续说话 → `Unconfirmed`。理由与 Windows 同：回一发信封的对端不是在确认收到。64 KiB 只在**排空量**上生效，正常对端读到 EOF 时 `bytesRead` 通常是 0（真 Windows serve 实测就是 0，见 §25.8 的互操作用例）。

### 25.7 默认全关时行为与线上字节完全不变

- `SourceSyncDeliveryOptions.Off` 时**不调用** `confirm`：不读一个字节、不多等一次、不多设一次读超时（`offDoesNotWaitForThePeerToClose`、`deliveryIsOffByDefaultAndCarriesNoConfirmation`）。
- 冻结向量未改：`core/common/src/test/resources/sync/windows-transfer-vectors.json`、`windows-auth-vectors.json` 本轮零改动；`sendWithReceiptsOffWritesTheFrozenBytes` 仍然断言 `sendAndAwaitReceipt(..., Off)` 写出的字节 == `SourceSyncTransfer.encode(envelope)`。
- 控制器默认文案一字未变：`delivery = false` 时 `lineOf(Sent)` 只是原有的「已发送 N 字节（F 帧，来源 S 条）。…」，新增那句只有 `deliveryConfirmed == true` 时才追加。

### 25.8 用例表（27 条新增，全部纯 JVM）

**core/common `SourceSyncDeliveryTest`（20 例，新建）**

| 用例 | 钉住的行为 |
| --- | --- |
| `reasonsAreTheFrozenSingleCode` | 码表只有 `delivery-unconfirmed` |
| `deliveryCodeDoesNotOverlapTheOtherFourTables` | 与传输/认证/TLS/回执四张码表零重名 |
| `defaultOptionsAreOffAndSaySo` | `Off` 默认、`toString` 如实 |
| `confirmingSpeaksItsBudget` | `Confirming(1500)` 的 `toString` 带 BudgetMs |
| `budgetFallsBackToTheDefault` | 0 / 负数 → 2000 |
| `booleanHelperMapsToOffOrOn` | `confirmingOrOff` 两向映射 |
| `optionsNeverCarryASecret` | options 的字符串里没有口令样式串 |
| `maximumDrainIsSixtyFourKilobytes` | 常量 = 65536 |
| `theReadTimeoutSetterSeesTheRemainingBudget` | 逐轮设的是剩余预算（单调不增） |
| `aFailingReadTimeoutSetterIsReportedNotThrown` | setter 抛异常 → `Unconfirmed` 而不是崩 |
| `peerThatClosesAfterTheEnvelopeCountsAsConfirmed` | 真回环：读完就关 → `Confirmed` |
| `bytesThePeerSentBeforeTheFinAreCounted` | 关前发出的字节被计入 `bytesRead` |
| `aPeerThatKeepsTheConnectionOpenIsNotConfirmed` | 持连接不发 → `Unconfirmed`，detail 含「没有在 N 毫秒内关闭」 |
| `aTinyBudgetStillSaysWhichBudgetItUsed` | 预算 1 ms 也能跑，detail 报出用的预算 |
| `aPeerThatKeepsTalkingIsNotConfirmed` | 信封后继续发 > 64 KiB → `Unconfirmed`「还在发」 |
| `aResetConnectionIsNotConfirmed` | RST → `Unconfirmed` |
| `detailNeverCarriesTheEnvelopeBytes` | detail 不含信封内容 |
| `offDoesNotWaitForThePeerToClose` | `Off` 时零字节读取 |
| `deliveryIsIgnoredWhenTheReceiptIsAwaited` | 回执优先 |
| `aTlsPeerThatClosesAfterTheEnvelopeIsConfirmed` | 真 TLS 1.3（夹具 p12）同样 `Confirmed` |

**feature:settings `SourceSyncSendControllerTest`（+6 例，共 32 例）**

| 用例 | 钉住的行为 |
| --- | --- |
| `deliveryIsOffByDefaultAndCarriesNoConfirmation` | 默认 `Off`、`Sent.deliveryConfirmed == null`、文案不出现确认那句 |
| `deliveryOnIsForwardedWithTheDefaultBudget` | 开了 → 端口收到 `Confirming(2000)`，`Sent.deliveryConfirmed == true`，文案出现「对端读完就关了连接」 |
| `receiptTakesPrecedenceOverDelivery` | 回执 + 确认同开 → 端口收到 `Off`，`deliveryConfirmed == null` |
| `unconfirmedDeliveryIsSurfacedWithItsOwnCode` | 失败行是「发送失败：delivery-unconfirmed（detail）」 |
| `realLoopbackSendIsOnlyConfirmedWhenThePeerCloses` | 真回环 + 真 `SourceSyncTransferSendPort` → `Sent.deliveryConfirmed == true` |
| `formPrintsTheDeliveryFlagButNeverTheSecret` | `toString` 有 `delivery=true`、口令仍然 `<redacted>` |

**core/common `SourceSyncTlsInteropTest`（+1 例，共 11 例；`MH_W632_SYNC_DLL` 门控）**

| 用例 | 钉住的行为 |
| --- | --- |
| `deliveryConfirmationSeesTheRealServeCloseTheConnection` | 真 Windows `transfer serve --json --apply`（w6-46-cli）：`sendTo(delivery = Confirming())` → `Ok` 且 `delivery is Confirmed`、`bytesRead == 0`，serve exit 0、`counts.apply == 2`、`sync-sources.json` 两来源 |

同时修正了既有两例里已过时的注释（「Ok 只表示帧写进 socket，Android 侧没有 `--confirm-delivery` 的对应物」）——**断言一字未改**，因为那两例用的就是默认 `Off`。

### 25.9 实跑计数（两轮，本机，`--no-daemon`）

命令（A 轮带门控变量，B 轮清掉它）：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
# A 轮：$env:MH_W632_SYNC_DLL='D:\mh-verify\w6-46-cli\MediaHub.SyncCli.dll'
cmd /c "gradlew.bat :core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process > %TEMP%\w646.log 2>&1"
```

**A 轮（带 `MH_W632_SYNC_DLL`）** 原始尾行：`BUILD SUCCESSFUL in 3m 42s` / `167 actionable tasks: 167 executed` / `EXIT=0`。

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| core:common | 33 | 478 | 0 | 0 | 0 |
| core:database | 18 | 172 | 0 | 0 | 12 |
| feature:settings | 15 | 249 | 0 | 0 | 0 |

（core:database 那 12 个跳过是 `SourceSyncListenerInteropTest`——它由 `MH_W638_SYNC_DLL` 门控，本轮**故意只设** `MH_W632_SYNC_DLL`；W6-44 记录过设齐两个变量时它是 0 跳过。）

门控与新增套件单列（A 轮 XML）：`SourceSyncTlsInteropTest` tests=11 failures=0 skipped=**0**（真跑了 Windows CLI，含本节点新增的第 11 例）；`SourceSyncDeliveryTest` tests=20 skipped=0；`SourceSyncSendControllerTest` tests=32 skipped=0。

**B 轮（不带任何 `MH_*` 门控变量）** 原始尾行：`BUILD SUCCESSFUL in 3m 1s` / `167 actionable tasks: 167 executed` / `EXIT=0`。

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- | --- |
| core:common | 33 | 478 | 0 | 0 | 11 |
| core:database | 18 | 172 | 0 | 0 | 12 |
| feature:settings | 15 | 249 | 0 | 0 | 0 |

B 轮的门控套件是**跳过而不是消失**：`SourceSyncTlsInteropTest` tests=11 failures=0 errors=0 **skipped=11**、`SourceSyncListenerInteropTest` tests=12 **skipped=12** 两个 `TEST-*.xml` 都在，套件数与用例数与 A 轮完全相同，只有 skipped 从 0 变成满额。

**无回归算式**（基线取自 §22/§23 快照）：core:common 457 + 20（SourceSyncDeliveryTest）+ 1（互操作新增）= **478**，套件 32 → 33；feature:settings 243 + 6 = **249**，套件仍 15；core:database 172 不变（skipped 12 的差异只来自本轮没设 `MH_W638_SYNC_DLL`）。

### 25.10 本次改动的文件

| 文件 | 变化 |
| --- | --- |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncDelivery.kt` | 新建，208 行 |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransfer.kt` | `Ok` 增第 4 个默认参数（564 → 567 行） |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClient.kt` | `sendTo` 增 `delivery` 参数 + `awaitPeerClose` |
| `feature/settings/.../sync/SourceSyncSendController.kt` | `Form.delivery`、`Sent.deliveryConfirmed`、端口第 8 参、文案 |
| `core/common/src/test/.../sync/SourceSyncDeliveryTest.kt` | 新建，556 行 / 20 例 |
| `core/common/src/test/.../sync/SourceSyncTlsInteropTest.kt` | +1 例、两处过时注释修正 |
| `feature/settings/src/test/.../sync/SourceSyncSendControllerTest.kt` | +6 例（共 32 例） |

**`Screen` 未改**：任务书要求本节点只到「控制器 + 端口」，界面上没有投递确认开关（见 25.11 第 2 条）。

### 25.11 未覆盖

1. **TLS 下 close_notify 与 FIN 的差异没有对真 Windows 实测**：Windows 的 TLS 分支是先 `WaitForPeerCloseAsync`（1500 ms）再 `CloseQuietlyAsync`，Android 侧读到的可能是 TLS close_notify 而不是 TCP FIN，且可能接近默认 2000 ms 预算。回环 TLS 单测（`aTlsPeerThatClosesAfterTheEnvelopeIsConfirmed`）能过，但**跨端 TLS + 投递确认没有用例**（§18 的跨端 TLS 用例仍是默认 `Off`）。
2. **界面没有开关**：`SourceSyncSendForm.delivery` 与 `Sent.deliveryConfirmed` 都有，`SendSection` 里没有控件，真机上开不了（任务书明确要求本节点不动 `Screen`）。
3. **未跨机**：全部只连回环，没有跨设备/跨网卡证据。
4. **未测大信封边界**：8 MiB 上限附近的信封 + 慢读对端没有用例；也没有「对端慢慢读、FIN 迟迟不到」的时延分布测量。
5. **未测对端发 1..64 KiB 再关的中间态**：设计上仍判 `Confirmed`（只要没超 64 KiB 且最终 EOF），但没有专门用例逐段覆盖。
6. **未接日志/未跑真机**：没有日志器接入，也没有 instrumentation 或设备证据。
7. **本节点没有新增 required-suite**：`.github/workflows/android-ci.yml` 仍是 31 条；`SourceSyncDeliveryTest` 会随模块的 `testDebugUnitTest` 在 CI 里跑到，但不单独点名。

## 26. 两个协议开关的界面接线（W6-48）

### 26.1 为什么需要这一节

W6-40 把回执（裸 0x04）做进了监听端、W6-46 把投递确认做进了发送端，但两处都只到控制器/协议层：
`SourceSyncListenForm.receipt` 与 `SourceSyncSendForm.delivery` 早就存在，**界面上没有任何控件**，
真机上这两项能力开不了——§25.11 第 2 条当时如实记录了这一点，本节是它的后续（旧条目保留，不追改）。

本节点只补界面与接线：不改协议、不改任何码表、不动线上字节，也不改控制器的判定语义。

### 26.2 两个开关的语义与优先级

| 开关 | 控件 tag | 表单字段 | 打开后发生什么 | 关着时 |
| --- | --- | --- | --- | --- |
| 发送端「投递确认」 | `sync.send.delivery` | `SourceSyncSendForm.delivery` | 写完最后一帧后读对端直到 FIN（预算 2000 ms、上限 64 KiB），读到 FIN 才置 `Sent.deliveryConfirmed = true` | 线上字节与冻结向量逐字节相同（§25.5） |
| 监听端「回 0x04 回执」 | `sync.listen.receipt` | `SourceSyncListenForm.receipt` | 只有信封解码成功才回一个裸 0x04；写库仍要预览与逐条允许 | 一个字节都不多写（§22.5） |

**优先级：回执优先。** 勾着「等回执」（`sync.send.receipt`）时，投递确认复选框**禁用**
（`enabled = !sending && !form.receipt`）；控制器里还有同一道门
（`SourceSyncDeliveryOptions.confirmingOrOff(form.delivery && !form.receipt)`），
`core/common` 的 `SourceSyncTransferClient` 再兜一次（`if (receipt.enabled) Off`）。
三层同口径，与 Windows `SyncCliRunner` 的 `ConfirmDelivery && !AwaitReceipt` 一致。

禁用而不是「勾了也没用」：用户点不动的控件旁边有 `DELIVERY_HINT` 那句「与回执同时开时回执优先」，
不需要去猜为什么。

### 26.3 testTag 表（新增 2 个，既有 tag 一个没改）

| 常量 | 值 | 定义位置 | 落在哪个控件 |
| --- | --- | --- | --- |
| `SourceSyncImportTags.LISTEN_RECEIPT` | `sync.listen.receipt` | `SourceSyncImportScreen.kt:118` | `ListenSection` 里的 `Checkbox`（:804） |
| `SourceSyncImportTags.SEND_DELIVERY` | `sync.send.delivery` | `SourceSyncImportScreen.kt:159` | `SendSection` 里的 `Checkbox`（:1025） |

命名与既有风格一致：`sync.listen.*` / `sync.send.*` 前缀，kebab-case 后缀。

### 26.4 接线方式：只加控件，不加逻辑

| 环节 | 位置 | 本节点做了什么 |
| --- | --- | --- |
| 表单字段 | `SourceSyncListenerController.kt:181`（`val receipt: Boolean = false`）、`SourceSyncSendController.kt:217`（`val delivery: Boolean = false`） | **未改**：W6-40 / W6-46 已有 |
| 文案 | `SourceSyncListenerController.kt:82`（`RECEIPT_LABEL`）/ `:85`（`RECEIPT_HINT`）新增；`SourceSyncSendController.kt:80`（`DELIVERY_LABEL`）/ `:94`（`DELIVERY_HINT`）**复用 W6-46 既有常量** | 监听端两条新常量，发送端零改动 |
| 控件 | `SourceSyncImportScreen.kt:799-815`（监听，`enabled = !listening && !receiving`）、`:1019-1036`（发送，`enabled = !sending && !form.receipt`） | 新增两个勾选框 + 各自一句说明 |
| 控制器透传 | `SourceSyncListenerController.kt:322`（`factory.open(targetPort, form.tls, form.receipt)`）、`SourceSyncSendController.kt:345`（`confirmingOrOff(form.delivery && !form.receipt)`） | **未改**：表单本来就被原样交给控制器 |
| ViewModel | `SourceSyncImportViewModel.kt:163`（`startListening` → `listenerController.start(form)`）、`:197`（`send` → `sendController.send(form)`） | **未改** |

监听回执的文案把边界写清楚：只有解码成功才回，解不出的载荷保持沉默（对端看到 `receipt-missing`）。
发送端的两句分别说明「什么时候算送达」与「与回执的优先级」。

### 26.5 androidTest 用例表（8 例，`SourceSyncProtocolSwitchesScreenTest.kt`）

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `bothProtocolSwitchesStartUnchecked` | :45 | 两个开关默认都是关（`assertIsOff`） |
| 2 | `theDeliverySwitchCanBeToggledAndIsForwarded` | :53 | 能勾上，且提交的表单里 `delivery == true`、`receipt` 仍为假 |
| 3 | `theDeliverySwitchIsDisabledWhileTheReceiptIsAwaited` | :72 | 勾「等回执」→ 投递确认禁用；取消回执 → 又能点 |
| 4 | `aFormSubmittedWithTheReceiptOnNeverCarriesTheDelivery` | :88 | 回执优先：提交表单 `receipt == true && delivery == false` |
| 5 | `theListenReceiptSwitchCanBeToggledBothWays` | :106 | 监听回执能开能关 |
| 6 | `theListenReceiptSwitchIsForwardedWithTheForm` | :118 | 点「开始监听」后表单里 `receipt == true`，端口仍是系统分配（0） |
| 7 | `theListenReceiptSwitchIsFrozenWhileListening` | :136 | 监听已开（`Listening(40404, tls = false)`）时回执开关禁用（`assertIsNotEnabled().assertIsOff()`） |
| 8 | `bothSwitchesSitAtThePageTailAndTheMainFlowStaysOnTheFirstScreen` | :147 | 不滚动的 `PICK`/`PREVIEW` 仍在首屏；两个开关要 `performScrollTo()` 才可见 |

滚动纪律与 W6-44 相同：任何针对页尾节点的断言先 `performScrollTo()`，只有第 8 例的前两句故意不滚动，
它要证明的正是「首屏主流程没被顶下去」。既有 `SourceSyncSectionsScreenTest.kt`（19 例）一行未改。

### 26.6 实跑证据（本机，**DEVICE_UNVERIFIED**）

命令（`--no-daemon`，跑前残留 `java.exe` = 0）：

```
gradlew.bat :feature:settings:assembleDebugAndroidTest :feature:settings:testDebugUnitTest --console=plain --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

| 项 | 结果 |
| --- | --- |
| 尾行 | `BUILD SUCCESSFUL in 3m 6s` / `212 actionable tasks: 212 executed` / `EXIT=0` |
| androidTest 编译产物 | `feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk`（20410526 B）；`build/tmp/kotlin-classes/debugAndroidTest/.../SourceSyncProtocolSwitchesScreenTest.class`（22233 B） |
| JVM 回归（feature:settings） | 15 套件 / 249 例 / 0 失败 / 0 错误 / 0 跳过——与 W6-46 基线**逐项相同**（本节点不加 JVM 用例） |
| 设备执行 | **未执行**：`adb devices` 无设备，按纪律不起模拟器；执行证据由 CI 的 visual-instrumentation（API 32 / 36）提供 |

### 26.7 未覆盖（如实列出）

1. **设备未跑（DEVICE_UNVERIFIED）**：本机无模拟器/真机，本节点只证明编译与语义断言的可编译性，真机执行等 CI。
2. **忙碌态未覆盖**：`Sending`（发送中）与 `Receiving`（接收中）时两个开关的禁用只有代码里的 `!sending` / `!listening && !receiving`，没有对应用例。
3. **未测深色主题 / 字体缩放 / 窄屏换行**：长文案在 200% 字体下的折行没有视觉回归。
4. **未覆盖 Hilt 注入路径**：用例直接 `setContent { SourceSyncImportScreen(...) }`，`SourceSyncImportRoute` + ViewModel 未在本节点被覆盖。
5. **开关不持久化**：两处都是 `remember { mutableStateOf(...) }`，离开页面即复位；没有写进设置项（本节点未要求）。
6. **不入 required-suite**：`.github/workflows/android-ci.yml` 在本节写就时是 32 条 `--required-suite`（同一分支后来随 `SourceSyncSendLauncherTest` 变成 33 条，本套件仍未钉），本套件由 visual-instrumentation job 覆盖。
7. **没有新增网络证据**：回执与投递确认的线上行为仍由 §21 / §22 / §25 的既有用例负责，本节点只补界面。

### 26.8 本次改动的文件

| 文件 | 变化 |
| --- | --- |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt` | 1052 → 1093 行：2 个 tag + 监听/发送各一个勾选框与说明 |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerController.kt` | 521 → 528 行：`RECEIPT_LABEL` / `RECEIPT_HINT` 两条文案 |
| `feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/sync/SourceSyncProtocolSwitchesScreenTest.kt` | 新建，194 行 / 8 例 |
| `docs/android-source-sync-transfer.md` | 本节（§26） |

**未改**：`windows/`、`README`、`.github/`、两个冻结向量、`core/common` 与 `core/database` 任何文件、
既有的 `SourceSyncSectionsScreenTest.kt`（19 例）与既有 testTag/文案。

## 27. 发送取消（W6-49）

### 27.1 为什么需要这一节

W6-42 的发送区块只有一个「发送」按钮：从点下去到结束，界面能显示的只有 `发送中…` 与一个禁用的按钮。
一次发送包含导出（读库、打包信封）与传输（拨号、写帧、等回执或等 FIN）两段，Windows 端 `transfer serve` 的默认等待预算
是 15 s、W6-50 起可配到 600 s —— 这段时间里用户没有任何撤回手段。§23 覆盖的是「发出去会怎样」，本节补的是
「发一半不想发了」。**本节只加界面与一层薄记账：不改协议、不改码表、不动线上字节、不改控制器的判定语义。**

### 27.2 三条外部可观察语义

| # | 语义 | 钉在哪里 |
| --- | --- | --- |
| 1 | **取消后回 Idle，不是失败** | `SourceSyncSendState.Idle`；结果行为空、稳定码为空（`SourceSyncSendCopy.lineOf` / `codeOf` 均为 `null`），界面不出现「发送失败：<码>」 |
| 2 | **取消立刻释放单飞** | 控制器 `inFlight` 在 `finally` 释放（`SourceSyncSendController.kt:374-383`）；取消后可以马上再发一次，不必等超时 |
| 3 | **没在发时取消是 no-op** | 从未发送、或那条 Job 已收尾时，`cancel()` 返回 `null`，不改状态、不写结果、不报错 |

这三条都是用户能直接看见的差别（「点了取消，界面停在发送中/显示失败」都是缺陷），所以必须有确定性证据，而不是靠代码阅读。

### 27.3 实现：一层薄壳 + 两行委托（含偏离说明）

**偏离（如实记录）**：任务书原本要求「在 `SourceSyncImportViewModel` 里加 `private var sendJob: Job?`」。
实际收进新类 `SourceSyncSendLauncher`（`feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncSendLauncher.kt`，56 行，纯 Kotlin）——
因为该 ViewModel 的构造依赖 Room 仓库、Keystore 凭据库与 provider 注册表，纯 JVM 造不出来；而上面三条正是用户可见语义。
薄壳可以被 JVM 用例直接钉住，ViewModel 只留两行委托（`send` → `launcher.launch(form)`、`cancelSend` → `launcher.cancel()`），
因此 JVM 用例钉的就是真路径，不是影子实现。

薄壳的职责只有记账，边界写死在注释里：

| 成员 | 行号 | 行为 |
| --- | --- | --- |
| `class SourceSyncSendLauncher(scope, send)` | :22 | 持有 `CoroutineScope` 与 `suspend (SourceSyncSendForm) -> Boolean`，不持表单以外的任何东西 |
| `val isActive` | :30 | `job?.isActive == true`，界面据此决定取消按钮可不可点 |
| `fun launch(form): Boolean` | :34 | 在飞时返回 `false`（不排队、不重复发）；否则起一条 Job |
| `fun cancel(): Job?` | :45 | 只取消在飞的那条并把它交回调用方去 `join`；没有在飞时返回 `null` |
| `fun clear()` | :53 | 只丢引用，**不取消**（取消必须是用户显式动作） |

**不越界**：不持信封字节、不持口令或 `SecretHandle`（每次发送的数据只活在控制器调用栈与该 Job 的协程里，随 Job 收尾释放）；
不判 `Sending/Sent/Failed/Idle`；不复制单飞——控制器的 `inFlight` 仍是唯一权威，薄壳的 `isActive` 只是界面门。
`onCleared()` 里也只 `clear()`：`viewModelScope` 取消时那条 Job 自己收尾，控制器把状态写回 Idle。

### 27.4 改动的文件与行号

| 文件 | 变化 | 关键行 |
| --- | --- | --- |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncSendLauncher.kt` | 新建 56 行 | :22 类、:30 `isActive`、:34 `launch`、:45 `cancel`、:53 `clear` |
| `.../sync/SourceSyncImportViewModel.kt` | 271 → 294 行 | :124-129 `sendLauncher`、:208 `launch`、:217-219 `cancelSend()`、:230 `reset()` 里 `clear()`、:243 `onCleared()` 里 `clear()` |
| `.../sync/SourceSyncSendController.kt` | 只加一条文案 | :85 `const val CANCEL = "取消发送"`（判定与码表零改动） |
| `.../sync/SourceSyncImportScreen.kt` | 1093 → 1111 行 | :164 tag `SEND_CANCEL`、:210 `onSendCancel = viewModel::cancelSend`、:250 参数、:444 `SendSection(..., onCancel = ...)`、:1053-1063 取消按钮 |
| `feature/settings/src/test/kotlin/.../SourceSyncSendLauncherTest.kt` | 新建 221 行 / 8 例 | 见 §27.5 |
| `feature/settings/src/androidTest/kotlin/.../SourceSyncSendCancelScreenTest.kt` | 新建 128 行 / 6 例 | 见 §27.6 |
| `docs/android-source-sync-transfer.md` | 本节（§27） | — |

按钮纪律与 W6-48 一致：**常驻且禁用，不隐藏**（`enabled = sending`，见 :1057）——
tag 与布局稳定，UI 测试不必为状态切换去分支查找；位置在「发送」按钮正下方、仍属页尾区块（§26 的页尾纪律不变）。

### 27.5 JVM 用例表（8 例，`SourceSyncSendLauncherTest.kt`）

造「在飞」的手法不是 sleep：假导出端 / 假传输端在 `started` 信号之后 `awaitCancellation()`，
于是用例可以在「状态确实是 Sending、单飞标记确实被占着」的那一刻取消，再用 `join()` 等它真的收尾。

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `launchStartsTheSendAndMarksItInFlight` | :36 | 起一条 → `isActive` 为真、控制器状态 `Sending`、`isSending` 为真 |
| 2 | `cancelDuringExportReturnsToIdleWithoutAnyFailure` | :54 | 导出阶段取消 → Idle、单飞释放、**传输端一次都没被调用**（`send.calls == 0`）、结果行与稳定码均为 null |
| 3 | `cancelDuringTransportReturnsToIdleAndFreesTheSingleFlightGuard` | :75 | 传输阶段取消 → Idle、单飞释放，**随即再发一次能成功**（`Sent`，两次拨号） |
| 4 | `cancelWhenNothingWasEverSentIsANoop` | :98 | 从未发送 → `cancel()` 返回 null、状态仍是 Idle、无稳定码 |
| 5 | `cancelAfterTheCancelledSendFinishedIsStillANoop` | :109 | 取消并收尾后再点取消 → no-op，不改 Idle |
| 6 | `aSecondLaunchWhileInFlightIsIgnored` | :124 | 在飞期间第二次 `launch` 返回 false、导出端只被调用一次 |
| 7 | `cancellingTwiceOnlyCancelsTheFirstTime` | :140 | 同一条只能取消一次，第二次返回 null |
| 8 | `clearBetweenSendsIsHarmlessAndCancelAfterItIsANoop` | :155 | `clear()` 不取消、之后 `cancel()` 是 no-op；再起一条仍正常收尾 |

### 27.6 androidTest 用例表（6 例，`SourceSyncSendCancelScreenTest.kt`）

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `theCancelButtonIsThereButDisabledBeforeAnythingIsSent` | :41 | 空闲时按钮在树里（`assertIsDisplayed`）但禁用（`assertIsNotEnabled`） |
| 2 | `theCancelButtonBecomesUsableWhileSending` | :52 | `Sending` 时可点 |
| 3 | `tappingCancelWhileSendingCallsBackExactlyOnce` | :63 | 点一次回调恰好一次 |
| 4 | `aSuccessfulSendLeavesTheCancelButtonDisabled` | :73 | `Sent` 后回到禁用 |
| 5 | `aFailedSendAlsoLeavesTheCancelButtonDisabled` | :86 | `Failed` 后同样禁用（取消不是失败的重试入口） |
| 6 | `theCancelButtonSitsAtThePageTailAndTheMainFlowStaysOnTheFirstScreen` | :95 | 不滚动的首屏仍有 `PICK`/`PREVIEW`；取消按钮要 `performScrollTo()` 才可见 |

滚动纪律与 W6-44 / W6-48 相同：针对页尾节点的断言先 `performScrollTo()`，只有第 6 例前两句故意不滚动。

### 27.7 实跑证据（本机）

命令（串行执行，跑前确认没有其它 gradle/dotnet 大套件在跑）：

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\55160\AppData\Local\Android\Sdk'
$env:GRADLE_OPTS='-Xmx2560m -XX:MaxMetaspaceSize=896m'
gradlew.bat --max-workers=2 --rerun-tasks :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest
```

| 项 | 结果 |
| --- | --- |
| 尾行 | `BUILD SUCCESSFUL in 2m 51s` / `212 actionable tasks: 212 executed` / `EXIT=0` |
| JVM 回归（feature:settings） | **16 套件 / 257 例 / 0 失败 / 0 错误 / 0 跳过**（W6-48 基线 249 例 + 本节 8 例） |
| 新增套件 | `SourceSyncSendLauncherTest` 8 例 / 0 失败 |
| androidTest 编译产物 | `feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk`（20426910 B，21:36:13）；`build/tmp/kotlin-classes/debugAndroidTest/.../SourceSyncSendCancelScreenTest.class`（16863 B，21:34:41） |
| 收尾 | `gradlew --stop` 后残留 `java.exe` = 0 |
| 设备执行 | **未执行**：`adb devices` 无设备，按纪律不起模拟器；执行证据由 CI 的 visual-instrumentation（API 32 / 36）提供 |

### 27.8 未覆盖（如实列出）

1. **设备未跑（DEVICE_UNVERIFIED）**：本机无模拟器/真机，本节点只证明编译与断言的可编译性，真机执行等 CI。
2. **取消只覆盖到控制器边界**：假端口用 `awaitCancellation()` 挂住，没有覆盖真实 Socket 在写帧中途被取消时的收尾（`SourceSyncTransferClient` 的 close 路径）。
3. **取消与回执/投递确认的交互无用例**：取消后不发回执、不等 FIN 的字节级断言仍 NOT_RUN。
4. **未覆盖 Hilt 注入路径与进程重建**：用例直接 `setContent { SourceSyncImportScreen(...) }`，旋转屏幕/进程重建后的取消不在本节点。
5. **取消态不持久化**：状态在内存里，离开页面即复位。
6. **不入 required-suite**：`.github/workflows/android-ci.yml` 仍是 32 条 `--required-suite`（本节点未改 CI），本套件由 visual-instrumentation job 覆盖。
7. **未测无障碍 / 深色主题 / 200% 字体**：取消按钮与新增布局没有视觉回归。

## 28. 监听端显式单播地址（W6-52）

### 28.1 为什么需要这一节

W6-51 在 Windows 侧开了 `transfer serve --bind ADDR` 与 `--allow-non-loopback`，但 Android 侧同一轮留下的缺口表（`D:/mh-verify/android-w6-gap-table.md` 第 1 条）指出：全仓 Kotlin 没有任何 `allowExplicitHost` 命中，
`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferListener.kt` 的地址闸门用 `LoopbackOnly.addressOrNull` 硬只收回环，拒绝句固定是「只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 …」。
于是一台手机要把库推给另一台手机（或 Windows 反向连手机）时，Android 端没有任何办法说「绑这块网卡上的这个地址」。**本节只拆监听端的地址闸门**：不做发现、不做域名解析、不碰协议与码表、不改线上字节；
发送端（拨号出去）与「接收」卡（拨号进来）的同类开关留到 W6-52b，未做事实见 §28.6。

### 28.2 与 Windows W6-51 同名同义

| 语义 | Windows W6-51 | Android W6-52 |
| --- | --- | --- |
| 指定绑定地址 | `transfer serve --bind ADDR` | 监听卡「绑定地址」输入框（tag `sync.listen.host`），留空 = 127.0.0.1 |
| 打开非回环闸门 | `transfer serve --allow-non-loopback` | 监听卡复选框（tag `sync.listen.allow-host`） |
| 缺省行为 | 只绑 127.0.0.1 | 完全不变：留空 + 开关关 = 只绑 127.0.0.1 |
| 接受范围（开关打开后） | 一个单播字面量 | 同一个：一个单播字面量 |
| 一律拒绝（开关打开也一样） | 通配 / 广播 / 组播 / 保留地址、主机名、超过 256 字符 | 同上，逐条同义 |
| 稳定码 | `not-loopback` 不变 | `not-loopback` 不变 |
| 诊断载体 | detail 句子 | detail 句子（四条，见 §28.3） |
| 拒绝时机 | 建 socket 之前 | 建 socket 之前 |

两边都刻意不新开码：W6-47 的 ADR（`docs/cross-device/W6-47-CROSS-MACHINE.md` §4.3 结论 A/B）要求「跨机失败都落进现有五集」，
并把 `not-loopback` 的含义钉成「调用方给的地址不被本构建接受」——放宽之后「非回环」不再是被拒理由，但「这个地址我不收」这层原义仍然成立。
与 W6-51 相同的代价也照抄：**机器不可辨**（同一个 `not-loopback` 既可能是「没开开关」也可能是「写了个广播地址」），
要机器可辨必须两端同名同序新开集合（ADR 结论 C），那是另一件事。

### 28.3 四条拒绝句（逐字，测试按这四句断言）

码一律 `SourceSyncTransferReasons.NOT_LOOPBACK`；detail 由 `SourceSyncHostAddress.kt` 生成：

1. 没开开关（与 W6-52 之前**逐字相同**，既有用例零改动）：

   ```
   只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 <用户输入>
   ```

2. 开关开着，但不是 IP 字面量：

   ```
   「<用户输入>」不是 IP 字面量，本版本传输不做域名解析；请填 127.0.0.1、::1、localhost 或那台机器的单播 IP。
   ```

3. 开关开着，是字面量但不是「一台机器的地址」：

   ```
   「<用户输入>」不是一个具体某台机器的地址（通配 / 广播 / 组播 / 保留地址），建 socket 之前就拒绝了；请填一个单播地址，例如 192.168.1.20。
   ```

4. 超过 256 个字符（先于任何解析）：

   ```
   地址最多 256 个字符，这个没有去绑。
   ```

「单播字面量」的判定写在一处（`SourceSyncHostAddress.isUnicastLiteral`）：IPv4 首字节不能是 0（`0.0.0.0/8`）、不能是 `255.255.255.255`、不能 ≥224（组播 + 保留）；
IPv6 不能是全零、首字节不能是 `0xFF`（组播）。IPv4 自己按点分十进制算四个字节（`decodeIpv4`，带前导零的段直接拒），**永不走 DNS**；
IPv6 只接受 `^[0-9a-fA-F:.]+$` 形状再交给 `InetAddress.getByName`（那个形状不可能触发解析）。

### 28.4 改动的文件与行号

| 文件 | 变化 | 关键行 |
| --- | --- | --- |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncHostAddress.kt` | 新建 148 行 | :21 `MAX_LENGTH`、:24 `EXAMPLE_UNICAST`、:27 `Resolution`、:42 `resolve`、:53 / :57 / :62 三句、:78 `parseLiteral`、:92 `decodeIpv4`、:124 `isUnicastLiteral` |
| `.../sync/SourceSyncTransferListener.kt` | 348 → 350 行 | :275 KDoc、:288 末位 `allowExplicitHost = false`、:290 改用 `SourceSyncHostAddress.resolve`（原两句删除） |
| `core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncListenerImportService.kt` | 162 → 167 行 | :127/:129 KDoc、:140 `allowExplicitHost`、:150 透传 |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerController.kt` | 528 → 589 行 | :40 `Listening.host`、:79 `HOST_LABEL`、:82 `ALLOW_HOST_LABEL`、:85 `ALLOW_HOST_HINT`、:128 `lineOf`、:155 `portLineOf`、:197-202 表单两个新字段、:276/:284 旧工厂方法、:292-298 新 `open(...)` 默认实现、:366 `start` 里透传、:487 `listeningStateOf`、:529-553 生产工厂、:573 `ImportSession.address` |
| `.../sync/SourceSyncImportScreen.kt` | 1145 行 | :116 tag `LISTEN_HOST`、:119 tag `LISTEN_ALLOW_HOST`、:787 地址输入框、:794 复选框 |
| `core/common/src/test/kotlin/.../SourceSyncHostAddressTest.kt` | 新建 189 行 / 14 例 | 见 §28.5 |
| `feature/settings/src/test/kotlin/.../SourceSyncListenerControllerTest.kt` | 740 → 842 行 / +5 例 | :747 / :758 / :769 / :782 / :805 |
| `feature/settings/src/androidTest/kotlin/.../SourceSyncSectionsScreenTest.kt` | 396 → 446 行 / +3 例 | :161 / :177 / :197 |
| `docs/android-source-sync-transfer.md` | 本节（§28） | — |

两条界面纪律与 W6-44 / W6-48 / W6-49 一致：
**常驻不隐藏**（地址框与开关一直在，只在监听中/接收中 `enabled = false`），**地址只原样转交**（界面层不 trim、不判回环、不判字面量，判定只有 `SourceSyncHostAddress` 一处——否则同一个错误会有两种说法，与接收卡 §24 的口径相同）。

### 28.5 JVM 用例表

**core:common 新增 14 例**（`SourceSyncHostAddressTest.kt`）：地址解析器是纯函数，这一层钉「接受什么、拒绝什么、拒绝句说的是哪一类」。

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `blank_and_null_mean_the_default_loopback_address` | :39 | `null` / `""` / 全空白 → 127.0.0.1，text 为 `"127.0.0.1"` |
| 2 | `every_loopback_spelling_is_accepted_with_and_without_the_switch` | :48 | 7 种回环拼写（含 `[::1]`、`LocalHost`、`127.0.0.2`）× 开关两态都接受 |
| 3 | `the_switch_does_not_make_a_blank_host_non_loopback` | :59 | 开着开关留空仍是 127.0.0.1（开关只放宽「显式写的那个地址」） |
| 4 | `a_non_loopback_address_without_the_switch_keeps_the_exact_pre_w6_52_sentence` | :68 | 无开关 `192.168.1.5` → 码 `not-loopback` + 原句逐字相等 |
| 5 | `a_host_name_without_the_switch_is_refused_by_the_same_sentence` | :75 | 无开关 `nas` → 同一句原句 |
| 6 | `one_unicast_ipv4_literal_is_accepted_when_the_switch_is_on` | :84 | 3 个私网地址 + 前后空白，接受且 `text` 已 trim |
| 7 | `one_unicast_ipv6_literal_is_accepted_when_the_switch_is_on` | :93 | `fd00::1` 接受（唯一 IPv6 正例） |
| 8 | `wildcard_addresses_are_refused_and_the_sentence_says_wildcard` | :100 | `0.0.0.0` / `::` / `0.1.2.3` → 第 3 句（含「不是一个具体某台机器的地址」与「通配」） |
| 9 | `broadcast_multicast_and_reserved_addresses_share_the_same_refusal_sentence` | :113 | 广播 / 组播 / 保留共 6 个 → 同一句（含「通配 / 广播 / 组播 / 保留地址」） |
| 10 | `host_names_are_refused_because_the_transfer_path_never_resolves_them` | :126 | `mediahub-lan.local` / `nas` / `example.com` / `192.168.1` → 第 2 句（含「不是 IP 字面量」） |
| 11 | `an_over_long_address_is_refused_by_length_before_anything_is_parsed` | :138 | 257 字符 → 第 4 句（含「最多 256」） |
| 12 | `the_listener_refuses_a_wildcard_even_when_the_switch_is_on` | :147 | 真监听器 `open("0.0.0.0", 0, allowExplicitHost = true)` 返回 `Failed(not-loopback)`，**socket 未创建** |
| 13 | `the_listener_refuses_a_unicast_address_when_the_switch_is_off` | :161 | 同上但没开开关，`Failed(not-loopback)` |
| 14 | `the_switch_is_harmless_when_the_address_is_still_loopback` | :174 | 回环 + 开关 → `Started`，address/port/isClosed 三项正确，finally close |

（第 1–11 条是纯解析器，12–14 条打真监听器；共 14 个 `@Test`。）

**feature:settings 新增 5 例**（`SourceSyncListenerControllerTest.kt`）：这一层钉「界面说了什么，传到工厂的就是什么」。

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `defaultFormLeavesTheHostBlankAndKeepsTheSwitchOff` | :747 | 缺省表单 → 工厂收到 `host = ""`（表单缺省是空串，空白由 core 当缺省回环）、`allowExplicitHost = false` |
| 2 | `aTypedHostWithoutTheSwitchStillTravelsButStaysOff` | :758 | 写了地址没点开关 → 地址原样传、开关仍是 false（判断在 core） |
| 3 | `hostAndSwitchTravelVerbatimAndTrimmingIsLeftToTheCore` | :769 | `"  192.168.1.5  "` 不做 trim；开关 true 原样传 |
| 4 | `notLoopbackFromTheFactoryKeepsTheCodeAndTheSentenceVerbatim` | :782 | 工厂回 `not-loopback` → 状态 `Failed(null, 码, 句子)`、结果行逐字、`isListening` 假、**信封一个都没往导入页送** |
| 5 | `theListeningLinesNameTheBoundHostInsteadOfLoopback` | :805 | 监听状态带 `host = "192.168.1.5"`，`lineOf` / `portLineOf` 都写这个地址、不再出现 127.0.0.1 |

### 28.6 androidTest 用例表（3 例，`SourceSyncSectionsScreenTest.kt`）

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `theListenCardOffersAHostFieldAndAnExplicitHostSwitch` | :161 | 地址框与开关在树里、可点；`HOST_LABEL` 与 `ALLOW_HOST_HINT` 都显示（说明必须写清「只收单播字面量」） |
| 2 | `theListenFormCarriesTheTypedHostAndTheSwitchVerbatim` | :177 | 输入 `192.168.1.5` + 点开关 + 点开始 → 回调收到的表单 `host` / `allowExplicitHost` 逐字正确 |
| 3 | `whileListeningTheHostFieldAndTheSwitchAreLocked` | :197 | 监听中端口行写 `192.168.1.5:45678`，地址框与开关都禁用（避免「再开一个」） |

滚动纪律同 §26/§27：页尾节点一律先 `performScrollTo()`。

### 28.7 实跑证据（本机）

串行执行，跑前 `gradlew --stop` 清 daemon；同一时段机器上没有 dotnet 大套件（同机器并发跑 gradle 与 dotnet 曾在 2026-10-24 触发 JVM OOM）。

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\55160\AppData\Local\Android\Sdk'
$env:GRADLE_OPTS='-Xmx2560m -XX:MaxMetaspaceSize=896m'
gradlew.bat --max-workers=2 --rerun-tasks :core:common:testDebugUnitTest `
  :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest `
  :feature:settings:assembleDebugAndroidTest
```

**第一次全量跑（`--rerun-tasks`）**：`BUILD FAILED in 6m 23s`、`220 actionable tasks: 220 executed`。`:core:common:testDebugUnitTest` 与 `:core:database:testDebugUnitTest` 通过；`:feature:settings:testDebugUnitTest` 报 `262 tests completed, 1 failed`，唯一失败是 `SourceSyncListenerControllerTest` 里**新写的那条期望写错了**（断言表单缺省把 `host = null` 传给工厂，实际传的是空串，`AssertionError: 留空就该传 null… expected null, but was:<>`）。
判定：源码行为正确（`SourceSyncListenForm.host` 缺省是空串，空白由 core 的 `SourceSyncHostAddress` 当缺省回环处理），改测试为断言空串、用例改名为 `defaultFormLeavesTheHostBlankAndKeepsTheSwitchOff`。**失败使 `assembleDebugAndroidTest` 当次未执行**，所以修完重跑。

**第二次（不带 `--rerun-tasks`，只跑 feature:settings 的两个任务，core 两个模块沿用上面的结果）**：`BUILD SUCCESSFUL in 1m 49s`、`212 actionable tasks: 8 executed, 204 up-to-date`。

| 项 | 结果 |
| --- | --- |
| core:common | 34 套件 / **492 例** / 0 失败 / 0 错误 / 11 跳过（基线 478 + 本节 14；`SourceSyncHostAddressTest` 14/14） |
| core:database | 18 套件 / **172 例** / 0 失败 / 0 错误 / 12 跳过（与本节前一致，未受影响） |
| feature:settings | 16 套件 / **262 例** / 0 失败 / 0 错误 / 0 跳过（基线 257 + 本节 5；`SourceSyncListenerControllerTest` 33/33） |
| androidTest 编译 | `assembleDebugAndroidTest` 成功，`feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk` = **20697523 B**（2026-10-25 00:17:44） |
| 既有回环用例 | 未被文案改动打红：`SourceSyncTransferLoopbackTest` 10/10、`SourceSyncTransferListenerTest` 28/28 |
| 收尾 | `gradlew --stop` → `2 Daemons stopped`，残留 java 进程 0；仓库内无 `hs_err_pid*.log` / `replay_pid*.log` |
| 设备执行 | **未执行**：`adb devices` 无设备，按纪律不起模拟器；instrumentation 证据由 CI 的 visual-instrumentation（API 32 / 36）提供 |
### 28.8 未覆盖（如实列出）

1. **发送端与「接收」卡未放宽（W6-52b）**：`SourceSyncTransferClient` 拨号出去仍只认回环，接收卡（拨号进来）也没有地址开关。本节只做了监听端。
2. **真实两机未跑（NOT_RUN）**：本机所有用例都不 bind / 不 dial 任何非回环地址（那会触发 Windows 防火墙确认框，违背「测试不得干扰使用者」的纪律），
   所以「两台机器真的能互传」这件事仍是两端 NOT_RUN，只有「建 socket 之前就拒绝」有确定性证据。CI 的模拟器同样没有第二台机器。
3. **不做 DNS**：写主机名一律拒绝（第 2 句）。要支持名字得先决定「解析失败算哪个码」，那是新码集合的问题（ADR 结论 C）。
4. **未覆盖 Hilt 注入路径与进程重建**：用例直接构造控制器 / `setContent`，旋转屏幕后的表单保留不在本节点。
5. **本套件尚未钉进 required-suite**：`.github/workflows/android-ci.yml` 的 33 条 `--required-suite` 未包含 `SourceSyncHostAddressTest`；
   按缺口表它是 W6-55 的内容（改 `android-ci.yml` 必须同步跑 `.github/scripts/tests` 的 unittest）。
6. **未测无障碍 / 深色主题 / 200% 字体 / 超长地址的实际换行**：新增两个控件没有视觉回归。
## 29. 监听端对端口令（W6-53）

### 29.1 为什么需要这一节

缺口表第 2 条（`D:/mh-verify/android-w6-gap-table.md`）指出：Window 侧 `transfer serve` 早就有 `--authenticate` + `--secret-env`，
Android 当**发送端**时（W6-42）也有 `sync.send.auth` / `sync.send.secret` 两个控件；唯独 Android 当**接收端**时界面没有办法要求对端证明自己持有同一口令。
服务层的本事是现成的：`core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncListenerImportService.kt:82-86` 的
`suspend fun receiveAndImport(expectedChallenge: String? = null, auth: SourceSyncTransferOptions? = null, receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off)`
早就把 `auth` 透传给 `listener.receiveOnce(...)`（W6-25 对齐 Windows W6-24 的 HMAC-SHA256 认证）；
可 `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerController.kt` 里的 `SourceSyncListenerFactory.open(...)` 一路只透传 tls / identity / receipt / host，**从不动 auth**，
所以界面上的开关永远出现不了。本节只补这道接线：协议、tag 帧字节与码表都不动。

### 29.2 与 Windows `transfer serve` 同名同义

| 语义 | Windows（W6-24 / W6-28） | Android W6-53 |
| --- | --- | --- |
| 要求对端认证 | `transfer serve --authenticate`（必须与 `--secret-env NAME` 成对，缺一在解析期就报 `missing-required-option:--secret-env` / `missing-required-option:--authenticate`） | 监听卡复选框（tag `sync.listen.auth`） |
| 口令从哪来 | 环境变量：命令行只出现变量名，口令本身不上命令行 | 口令输入框（tag `sync.listen.secret`），`PasswordVisualTransformation`，只进内存 |
| 缺省行为 | 不认证 | 完全不变：开关关 ⇒ `auth = null`，走 W6-34 原路径 |
| 对端没带认证 | `auth-required` | 同码透传，界面不新增码 |
| 对端口令不对 | `auth-mismatch` | 同上 |
| 重放 | `auth-replayed` | 同上 |
| 本端根本没有口令可用 | `auth-secret-unavailable` | 同上 |
| 本端开了认证却没填口令 | 解析期拒绝（见上） | 控制器在**建 socket 之前**拒：本地码 `listen-secret-missing` |

那四个线上码在 Android 的常量是 `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferAuth.kt:19`（`AUTH_REQUIRED`）、`:25`（`AUTH_MISMATCH`）、
`:28`（`AUTH_REPLAYED`）、`:31`（`AUTH_SECRET_UNAVAILABLE`），与 Windows 同名同序，本节一个都没碰。
`listen-secret-missing` 与发送卡的 `send-secret-missing`（W6-42）同属**界面本地码**：只在「本端配置自相矛盾」时出现，对端永远看不到，因此不需要两端同名同序（ADR 结论 C 只管线上码）。

### 29.3 三条外部可观察语义

1. **开了认证不填口令 ⇒ 一个 socket 都不开**：`start(form)` 立刻返回 `false`，状态是 `Failed(port = null, code = "listen-secret-missing", detail = SECRET_REQUIRED)`，工厂的 `open` 调用计数为 0（没有监听行、没有端口、没有半开的监听）。与 W6-51 的地址判定同一原则：拒绝必须发生在副作用之前。
2. **开了认证且填了口令 ⇒ 选项带口令字节过去**：工厂收到的 `auth` 是 `SourceSyncTransferOptions`，`authenticate = true`、`hasSecret = true`、`secret` 逐字节等于输入内容；状态行、`lineOf`、`portLineOf` 与日志里都没有口令原文。
3. **口令框只在开关点亮时可编辑**：开关关闭时口令框 `enabled = false`；监听中（`listening`）与接收中（`receiving`）「开关 + 口令框」一起锁住；`SourceSyncListenForm.toString()` 打的是 `secret=<redacted>`（防止异常栈或调试日志把口令带出去）。

### 29.4 实现落点

| 文件 | 行号 | 落了什么 |
| --- | --- | --- |
| `feature/settings/.../sync/SourceSyncListenerController.kt` | `:126` | `AUTH_LABEL =「要求对端带口令（HMAC 认证）」` |
| 同上 | `:129` | `SECRET_LABEL =「口令（只留在内存）」` |
| 同上 | `:132` | `AUTH_HINT`：对端没带 tag 判 `auth-required`、tag 对不上判 `auth-mismatch`，口令不进状态行 / 日志 / 导出 |
| 同上 | `:137` | `SECRET_REQUIRED`：开了认证没填口令时对端的 tag 帧一律 `auth-secret-unavailable` |
| 同上 | `:224` / `:226` | 表单新增 `auth: Boolean = false`、`secret: String = ""` |
| 同上 | `:240` / `:242` | `isSecretAcceptable = !auth` 或 `secret.isNotEmpty()`；`canSubmit = isPortTextAcceptable && isSecretAcceptable` |
| 同上 | `:245` | `toString()` 打 `secret=<redacted>` |
| 同上 | `:342` | 工厂接口新增第 6 参重载 `open(port, tls, receipt, host, allowExplicitHost, auth)`，默认转 5 参重载 ⇒ 既有测试替身零改动 |
| 同上 | `:412-419` | 口令闸门（在 TLS 检查之后、`starting` 单飞与 `factory.open` 之前） |
| 同上 | `:426` | `SourceSyncTransferOptions.withSecret(form.secret.toByteArray(Charsets.UTF_8))`，原文只活在这个局部变量里 |
| 同上 | `:577` | `const val SECRET_MISSING = "listen-secret-missing"` |
| 同上 | `:610` / `:619` | 生产工厂：5 参 `open` 委托成 6 参（`auth = null`）；6 参 `open` 承载原 body |
| 同上 | `:646` / `:650` / `:670` | `ImportSession(service, receipt, auth)`；`receiveOnce() = service.receiveAndImport(auth = auth, receipt = receiptOptions)` |
| `feature/settings/.../sync/SourceSyncImportScreen.kt` | `:124` / `:127` | 两个 tag：`sync.listen.auth`、`sync.listen.secret` |
| 同上 | `:847-850` | 认证 `Checkbox`：`enabled = !listening && !receiving` |
| 同上 | `:858-867` | 口令框：`PasswordVisualTransformation()` + `KeyboardType.Password` + `enabled = !listening && !receiving && form.auth` |
| 同上 | `:870` | `AUTH_HINT` 说明文字（把两端的码写清） |

控件次序刻意排成「认证开关 → 口令框 → 说明」，与发送卡（`:1056` / `:1067`）同一顺序，用户在两卡之间来回时不必重新学。

### 29.5 JVM 用例（`SourceSyncListenerControllerTest`，新增 5 例）

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `defaultFormAsksForNoSecretAndPassesNoAuthOptions` | :851 | 缺省表单传到工厂的第 6 参是 `null`，`authCalls` 记账 1 次 |
| 2 | `authOnWithoutASecretIsRefusedBeforeAnySocket` | :863 | 开关开、口令空 ⇒ `Failed(null, listen-secret-missing, SECRET_REQUIRED)`、端口 null、`openCalls == 0`、状态行是 `失败：listen-secret-missing（…）` |
| 3 | `authOnWithASecretPassesOptionsCarryingTheSecretBytes` | :883 | 开关开 + 口令 `s3cret` ⇒ `auth.authenticate` / `auth.hasSecret` 均 true、`auth.secret` 与 `s3cret` 的 UTF-8 字节逐字节相等、状态行不含口令 |
| 4 | `aTypedSecretWithTheSwitchOffIsIgnored` | :899 | 开关关时口令框内容不参与判定（传 `null`），不因为「框里有字」就偷偷认证 |
| 5 | `theSecretNeverAppearsInTheFormToString` | :909 | `toString()` 含 `secret=<redacted>` 且不含口令原文 |

FakeFactory 侧新增 `var lastAuth: SourceSyncTransferOptions?`（`:137`）、`var authCalls`（`:139`）与第 6 参 override（`:175`），只记账不改行为。

### 29.6 androidTest（`SourceSyncSectionsScreenTest`，新增 3 例）

| # | 用例 | 行号 | 钉住的行为 |
| --- | --- | --- | --- |
| 1 | `theListenCardOffersAnAuthSwitchAndASecretField` | :210 | 开关在树里、缺省关闭；口令框在树里但缺省禁用；`AUTH_LABEL` 与 `AUTH_HINT` 都显示 |
| 2 | `theListenFormCarriesTheAuthSwitchAndTheSecretVerbatim` | :226 | 点开关 → 填 `s3cret` → 点开始，回调收到的表单 `auth = true`、`secret = "s3cret"` |
| 3 | `whileListeningTheAuthSwitchAndTheSecretAreLocked` | :246 | 已监听时两控件都禁用（避免「再开一个」） |

滚动纪律同 §26/§27/§28：页尾节点一律先 `performScrollTo()`。

### 29.7 实跑证据（本机）

串行执行，跑前 `gradlew --stop` 清 daemon；同一时段机器上没有 dotnet 大套件。

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="C:\Users\55160\AppData\Local\Android\Sdk"
$env:GRADLE_OPTS="-Xmx2560m -XX:MaxMetaspaceSize=896m"
gradlew.bat --max-workers=2 --rerun-tasks :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest
```

`BUILD SUCCESSFUL in 6m 15s`、`212 actionable tasks: 212 executed`（日志 `D:/mh-verify/w6-53-gradle.log`）。

| 项 | 结果 |
| --- | --- |
| feature:settings | 16 套件 / **267 例** / 0 失败 / 0 错误 / 0 跳过（基线 262 + 本节 5；`SourceSyncListenerControllerTest` 38/38） |
| core:common / core:database | 未改代码，沿用 §28.7 的 492 / 172（本节只动 feature/settings） |
| androidTest 编译 | `assembleDebugAndroidTest` 成功，`settings-debug-androidTest.apk` = **20426910 B**（2026-10-25 00:37:14） |
| APK 内容抽查 | 大小与 W6-49 时巧合相同，故解包核对：3 个新用例的方法名都在 `classes17.dex`（98412 B），`sync.listen.auth` 与 `listen-secret-missing` 在 `classes12.dex`（676188 B），`SourceSyncSectionsScreenTest.class` 34871 B @ 00:35:17 —— 确属本次编译产物 |
| 收尾 | `gradlew --stop`（要带对 `JAVA_HOME`，否则报 `JAVA_HOME is set to an invalid directory`）→ `1 Daemon stopped`，残留 java 进程 0；仓库内无 `hs_err_pid*.log` |
| 设备执行 | **未执行**：`adb devices` 无设备，按纪律不起模拟器；3 例 instrumentation 的实跑证据由 CI 的 visual-instrumentation（API 32 / 36）提供 |

### 29.8 未覆盖（如实列出）

1. **真实两机未跑（NOT_RUN）**：本机用例不 bind / 不 dial 任何非回环地址，也没有第二台设备来对齐口令；「两台机器开着认证真的连上了、密码错了真的被判 `auth-mismatch`」仍是两端 NOT_RUN。
2. **「口令错 / 重放」的控制器级用例没有**：那四条线上码的判定在 core 层（W6-25 / W6-31 已有用例），本节只测「本端配置自相矛盾」与「选项透传」，没有构造 `auth-mismatch` 的 outcome 走到界面状态。
3. **口令在 Kotlin `String` 里不可擦除**：不落盘、不进状态行、不进 `toString`、不进导出，但内存里仍是不可变字符串，dump 可见。要真正擦除得把表单改成 `CharArray` / `ByteArray` 并在 UI 层就不留字符串，那是另一件事。
4. **Hilt 注入路径与进程重建未覆盖**：用例直接构造控制器 / `setContent`，旋转屏幕后口令框内容是否保留未测。
5. **本套件仍不入 required-suite**：`.github/workflows/android-ci.yml` 的 33 条 `--required-suite` 里没有这两个套件（W6-55 的内容；改 `android-ci.yml` 必须同步跑 `.github/scripts/tests` 的 unittest）。
6. **无视觉回归**：无障碍 / 深色主题 / 200% 字体 / 超长口令的换行与遮挡都未测。

### 29.9 下一步

- **W6-52b**：发送端（拨号出去）与「接收」卡（拨号进来）补齐同一套地址开关，语义照 §28.2。
- **W6-54**：把 `acceptTimeoutMs` / `readTimeoutMs` / `closeBudgetMs` 做成界面旋钮（现在只在库层有默认值）。
- **W6-55**：把 `SourceSyncHostAddressTest`、本节的 `SourceSyncListenerControllerTest` 等钉进 `--required-suite`。
- 两端跨机真题仍需两台机器与用户授权窗口，见 §28.8 第 2 条。

## 30. 监听端等待预算旋钮（W6-54）

### 30.1 动机

Android 缺口表第 3 条：等待预算此前只存在于库层 —— `SourceSyncTransferListener.open(..., acceptTimeoutMs = 15_000, readTimeoutMs = 15_000, ...)`（`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferListener.kt:279`）与 `SourceSyncListenerImportService.start(..., acceptTimeoutMs, readTimeoutMs, ...)`（`core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncListenerImportService.kt:131-141`）都支持，但监听卡的 `start(form)` 一律用默认值：用户在大信封或慢网络上没有任何旋钮，只能等 15 秒后看到 `transfer-timeout`。

本轮只加这一个旋钮，**不改任何线上字节**：预算只在本地决定「等多久就放弃」，不进入信封、不进 tag 帧、不进回执。

### 30.2 与 Windows `transfer serve --budget-ms` 的同名同义

| 维度 | Windows（W6-50） | Android（W6-54） |
| --- | --- | --- |
| 名字 | `--budget-ms N` | 界面「等待预算（毫秒，留空=15000）」 |
| 缺省 | 15000 ms | 留空 = 15000 ms |
| 范围 | 100..600000 | 100..600000 |
| 作用点 | `transfer serve` 等连接与读 | `SourceSyncTransferListener` 等连接（accept）与每次读（read） |
| 非法值 | CLI 码 `invalid-budget-ms:<值>`（退出码 2） | 本地码 `listen-budget-invalid`（不开 socket） |
| 线上影响 | 无 | 无 |

**一个框管两段等待**是刻意的：core 的两个超时都是「单次等待的上限」（`SO_TIMEOUT` 落在 accept 与每次 read 上），不是一个整场传输的总预算；把它拆成两个输入框等于让用户去猜到底哪一段在超时。文案把这件事直接写在卡片上（`SourceSyncListenCopy.BUDGET_HINT`），不留在文档里。

### 30.3 三条外部可观察语义

1. **不合法就不开 socket**：预算文本非数字或落在 100..600000 之外时，控制器在 `starting` 单飞之前就返回，状态是 `Failed(port = null, code = "listen-budget-invalid", detail = BUDGET_INVALID_HINT)`，工厂的 `open` 一次都没被调用。
2. **留空等于 15000**：空白文本不当作 0，也不当作错误；`budgetMillis` 直接给 `SourceSyncTransferListener.DEFAULT_ACCEPT_TIMEOUT_MS`，与 W6-53 之前逐字同行为。
3. **数字原样到达 core**：控制器把毫秒数同时填进 `acceptTimeoutMs` 与 `readTimeoutMs`，中间不做单位换算、不做四舍五入；界面只转交文本，解析与范围判定只有一处（`SourceSyncListenForm.budgetMillis`）。

### 30.4 落点（文件 + 行号）

| 位置 | 内容 |
| --- | --- |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncListenerController.kt:141` | `BUDGET_LABEL`（"等待预算（毫秒，留空=15000）"） |
| 同上 `:150` | `BUDGET_HINT`（一个框管两段等待、范围与 Windows 同值同义） |
| 同上 `:156` | `BUDGET_INVALID_HINT`（本端兜底文案） |
| 同上 `:252` | `SourceSyncListenForm.budget: String = ""` |
| 同上 `:269` | `budgetMillis: Int?`（留空→15000，越界/非数字→null） |
| 同上 `:276` / `:278` | `isBudgetTextAcceptable` 进 `canSubmit` |
| 同上 `:298` | `VALID_BUDGET_MILLIS = 100..600_000` |
| 同上 `:404` | 工厂接口第 7 参重载 `open(..., budgetMillis: Int)`（带默认实现，既有替身零改动） |
| 同上 `:477-489` | `start(form)` 的预算闸门（口令闸门之后、`starting` 之前） |
| 同上 `:647` | `const val BUDGET_INVALID = "listen-budget-invalid"` |
| 同上 `:704` | 生产工厂 6 参重载以默认预算转调 7 参 |
| 同上 `:714` / `:729-730` | 生产工厂 7 参把同一个毫秒数同时交给 `acceptTimeoutMs` 与 `readTimeoutMs` |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt:130` | tag `sync.listen.budget` |
| 同上 `:877-887` / `:888-892` | 监听卡的预算输入框与说明 |

### 30.5 JVM 用例（5 例）

| 用例 | 行号 | 钉住什么 |
| --- | --- | --- |
| `defaultFormPassesTheFifteenSecondBudgetAndCanSubmit` | `SourceSyncListenerControllerTest.kt:938` | 留空 = 15000，且按钮可提交 |
| `aTypedBudgetTravelsVerbatimToTheFactory` | `:951` | 30000 原样到达工厂；前后空白不改数字 |
| `theBudgetRangeEndsAreBothAccepted` | `:962` | 100 与 600000 两个端点都被接受 |
| `anOutOfRangeBudgetIsRefusedBeforeAnySocket` | `:973` | 99 越界 → `listen-budget-invalid`、`openCalls == 0`、状态行逐字 |
| `aNonNumericBudgetIsRefusedAndStillShowsInTheFormToString` | `:996` | `12s` 不当作缺省；toString 里预算原文可读、口令仍打码 |

替身新增：`FakeFactory.lastBudget`（`:141`）与 7 参 `open`（`:190`）。

### 30.6 androidTest 用例（4 例）

| 用例 | 行号 | 钉住什么 |
| --- | --- | --- |
| `theListenCardOffersABudgetFieldWithItsRangeHint` | `SourceSyncSectionsScreenTest.kt:257` | 输入框存在、可编辑，标签与范围说明都在界面上 |
| `theListenFormCarriesTheTypedBudgetVerbatim` | `:270` | 填 30000 点开始 → 表单 `budget == "30000"`、`budgetMillis == 30000` |
| `anOutOfRangeBudgetKeepsTheListenButtonDisabled` | `:287` | 99 / 12s 都让按钮禁用，清空后恢复可点 |
| `whileListeningTheBudgetFieldIsLocked` | `:303` | 监听中该框不可编辑 |

### 30.7 实跑证据（本机）

命令（先 `gradlew --stop`；`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`、`ANDROID_HOME=C:\Users\55160\AppData\Local\Android\Sdk`、`GRADLE_OPTS=-Xmx2560m -XX:MaxMetaspaceSize=896m`）：

```
gradlew.bat --max-workers=2 --rerun-tasks :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest
```

- `BUILD SUCCESSFUL in 5m 5s`、`212 actionable tasks: 212 executed`，日志 `D:/mh-verify/w6-54-gradle.log`。
- XML：`feature/settings` **16 套件 / 272 例 / 0 失败 / 0 错误 / 0 跳过**（267 + 5）；`SourceSyncListenerControllerTest` **43/43**（38 + 5，XML 时间 `2026-10-24T16:59:37Z`）。
- APK `feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk` = **20426910 B**（`10/25/2026 01:00:03`）。**大小与 W6-49/W6-53 时相同，已解包确认不是陈旧产物**：`Expand-Archive` 后 20 个 dex，四个新用例名都在 `classes17.dex`（100480 B），`listen-budget-invalid` 与 `sync.listen.budget` 在 `classes12.dex`（681324 B）。
- 收尾：`gradlew --stop` → `1 Daemon stopped`、残留 java 进程 0；仓库里没有 `hs_err_pid*.log` / `replay_pid*.log`。

### 30.8 未覆盖（如实记录）

1. **真机 / 模拟器执行未做**：本机无设备，四个 androidTest 用例只进了 APK，设备结论待 CI 的 visual-instrumentation（API 32 / 36）。
2. **真实超时行为未跑**：没有跑「预算 100 ms + 大信封」去观察 `transfer-timeout` 的实际毫秒数；本机可做的只有解析与透传，跨进程计时需要真实 socket 路径（已由 W6-28 验收脚本在 Windows 侧覆盖）。
3. **发送卡与拨号接收卡仍无预算旋钮**：本轮只做监听端（与 W6-52 同一取舍），发送端 `SourceSyncSendForm` 的等待仍走控制器常量。
4. **Hilt 注入与进程重建未覆盖**：表单状态只存在于 Compose 的 `remember`，进程重建后预算输入会回到空（=15000）。
5. **未进 required-suite**：`SourceSyncListenerControllerTest` 已在 33 条 `--required-suite` 里，androidTest 四个新用例不在任何 required 名单里（android instrumentation 不进那 33 条）。
6. **无视觉回归**：只断言标签、文案与 enabled 状态，没有截图对比。

### 30.9 下一步

W6-55：把 `SourceSyncHostAddressTest`、`SourceSyncReceiptTest` 等已存在但未钉住的套件加进 `.github/workflows/android-ci.yml` 的 `--required-suite`（改门禁脚本必须同步跑 `.github/scripts/tests` 的 unittest）；发送端与拨号接收卡的预算/地址/认证开关合并为 W6-52b。

## 31. W6-52b：拨号侧（发送卡 / 接收卡）的显式单播地址

> 状态口径：本机单元测试与构建 EXECUTED_PASS；androidTest 只编译、未在设备上执行（DEVICE_UNVERIFIED，设备结论由 CI 的 visual-instrumentation 提供）；真实两机跨网证据 NOT_RUN（见 §31.8）。

### 31.1 为什么现在补

缺口表（`D:/mh-verify/android-w6-gap-table.md` 第 1 条）在 W6-52 之后只剩一半：监听端已经能绑一台机器的单播地址，但**拨号方向仍硬只连回环**——`core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClient.kt` 的 `receiveFrom` / `sendTo` 都经 `withConnection`，那里原本只认 `LoopbackOnly`。

于是「Windows 当 serve、Android 当 send」这条主用法在 W6-52 之后成立，而反过来的「Android 开监听、Windows 用 `transfer send` 发过来」只要 Windows 侧绑了网卡（W6-51 的 `--bind <IP>`），Android 仍然连不过去。本节补的就是这个方向：把拨号端也做成「默认只回环，显式同意后只接受一个单播字面量」。

### 31.2 与 Windows W6-51 同名同义

| 项 | Windows（W6-51） | Android（W6-52b） |
| --- | --- | --- |
| 开关名 | `transfer serve --bind ADDR` / `--allow-non-loopback`、`transfer send --allow-non-loopback` | 发送卡「允许发给一个非回环的单播地址（跨机发送）」= tag `sync.send.allow-host`；接收卡「允许连一个非回环的单播地址（跨机接收）」= tag `sync.receive.allow-host` |
| 缺省 | 关：只接受 127.0.0.1 / ::1 / localhost | 同 |
| 打开后接受集合 | 一个单播 IP 字面量（IPv4 / IPv6） | 同（`SourceSyncHostAddress.isUnicastLiteral`） |
| 拒绝时机 | 建 socket 之前 | 同（`withConnection` 在 `ServerSocket`/`Socket` 之前判定） |
| 拒绝码 | `not-loopback`（不新增码集合，W6-47 结论 A/B） | 同，仍是 `SourceSyncTransferReasons.NOT_LOOPBACK` |
| 诊断 | detail 句承载「为什么这个地址不行」 | 同，四条句子见 §31.4 |
| DNS | 不解析主机名 | 同（`decodeIpv4` 自算四字节，IPv6 必须先含 `:` 且只含 hex 与 `.`/`:` 才 `getByName`，永不查 DNS） |

### 31.3 判定只有一处，拒句分「绑定」与「连」

地址判定的唯一实现是 `SourceSyncHostAddress.resolve(host, allowExplicitHost, verb)`（`SourceSyncHostAddress.kt:44`）。W6-52 的监听端用缺省 `verb = "绑定"`；本节让拨号端传 `verb = "连"`（`SourceSyncTransferClient.kt:155`）。

这样做的硬理由：开关关着时，拨号端那句必须与 W6-23 起的原文**逐字相同**（`只允许连回环地址，实际 <地址>`），否则等于在没人要求的情况下改了既有诊断；监听端那句也必须保持 W6-52 的原文（`只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 <地址>`）。两条都由单测钉住。

### 31.4 四条拒绝句（照抄自 `SourceSyncHostAddress.kt`）

1. 开关关着：`只允许<verb>回环地址（127.0.0.1 / ::1 / localhost），实际 <原文>` —— 拨号端 verb=连，监听端 verb=绑定。
2. 开关开着但不是 IP 字面量：`「<原文>」不是 IP 字面量，本版本传输不做域名解析；请填 127.0.0.1、::1、localhost 或那台机器的单播 IP。`
3. 开关开着但是通配 / 广播 / 组播 / 保留地址：`「<原文>」不是一个具体某台机器的地址（通配 / 广播 / 组播 / 保留地址），建 socket 之前就拒绝了；请填一个单播地址，例如 192.168.1.20。`
4. 超过 256 字符：`地址最多 256 个字符，这个没有去绑。`

### 31.5 落点（行号即本节写就时的实测值）

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClient.kt` | :48 / :80 | `receiveFrom` / `sendTo` 末位各加 `allowExplicitHost: Boolean = false` |
| 同上 | :147 | `private fun withConnection(host, port, tls, allowExplicitHost, body)` |
| 同上 | :155 | `SourceSyncHostAddress.resolve(host, allowExplicitHost, verb = "连")` |
| `core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncHostAddress.kt` | :44 | `fun resolve(host: String?, allowExplicitHost: Boolean, verb: String = "绑定"): Resolution` |
| 同上 | :21 / :24 | `MAX_LENGTH = 256` / `EXAMPLE_UNICAST = "192.168.1.20"` |
| `core/database/src/main/kotlin/com/mediahub/core/database/sync/SourceSyncTransferImportService.kt` | :95 / :102 | `receiveAndImport(..., allowExplicitHost)` → `client.receiveFrom(..., allowExplicitHost = …)` |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncSendController.kt` | :67 / :69 | `ALLOW_HOST_LABEL` / `ALLOW_HOST_HINT` |
| 同上 | :259 | `SourceSyncSendForm.allowExplicitHost: Boolean = false` |
| 同上 | :196 / :225 / :235 | `SourceSyncSendPort` 的 8 参默认实现 / 9 参方法 / 8 参转发到 9 参 |
| 同上 | :296 | `toString()` 末尾 `", allowExplicitHost=" + allowExplicitHost` |
| 同上 | :399 | `send(form)` 把开关一起交给端口 |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncReceiveController.kt` | :44 / :46 | 同两条文案（接收卡口径） |
| 同上 | :103 / :140 / :272 / :274 | 表单字段 / 4 参默认实现 / 4 参 override / `service.receiveAndImport(..., allowExplicitHost = …)` |
| 同上 | :181 | `receive(form)` 把开关交给端口 |
| `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt` | :100 / :161 | tag `sync.receive.allow-host` / `sync.send.allow-host` |
| 同上 | :695 / :1058 | 两张卡的复选框（`enabled = !receiving` / `!sending`） |

### 31.6 用例

`core/common/src/test/kotlin/com/mediahub/core/common/sync/SourceSyncTransferClientHostTest.kt`（11 例，全部 EXECUTED_PASS）：

| 行 | 用例 | 钉住什么 |
| --- | --- | --- |
| :44 | `sending_without_the_switch_keeps_the_exact_pre_w6_52b_sentence` | 无开关 `sendTo(192.168.1.5)` → `not-loopback`，句子逐字等于 W6-23 起的原文 |
| :52 | `receiving_without_the_switch_uses_the_same_sentence` | `receiveFrom(10.0.0.7)` 同句 |
| :60 | `the_dialling_side_says_connect_and_never_bind` | 拨号端句子里是「连」不是「绑定」 |
| :68 | `the_listener_side_keeps_its_bind_wording` | `resolve("192.168.1.5", false)` 仍是「绑定」句（守住缺省 verb） |
| :81 | `a_unicast_literal_is_accepted_by_the_resolver_on_the_dialling_side` | 开关开着接受 192.168.1.5 且 `isLoopbackAddress == false` |
| :89 | `wildcard_addresses_are_refused_before_any_socket_with_the_switch_on` | `0.0.0.0` / `::` 开关开也拒，且整段 < 2000 ms（证明没拨号） |
| :107 | `broadcast_multicast_and_reserved_addresses_are_refused_with_the_switch_on` | 255.255.255.255 / 239.1.2.3 / 224.0.0.1 / 240.0.0.1 / ff02::1 |
| :122 | `host_names_are_refused_even_with_the_switch_on` | `mediahub-lan.local` / `nas` / `192.168.1` |
| :137 | `an_over_long_address_is_refused_by_length_before_anything_is_parsed` | 257 字符 |
| :152 | `a_loopback_target_gets_past_the_gate_and_only_then_fails_to_connect` | 回环 + 刚释放端口 → `connect-failed`（不是 `not-loopback`） |
| :170 | `a_blank_host_still_means_loopback_on_the_dialling_side` | 空白地址 = 回环，开关开着也一样 |

`feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncSendControllerTest.kt`（+3 例）：:614 `theCrossMachineSwitchIsOffByDefaultAndPrintedInTheForm`（缺省 false、`toString` 含 `allowExplicitHost=false/true`）、:623 `theCrossMachineSwitchTravelsVerbatimToThePort`（地址与开关原样到端口、状态 Sent）、:633 `aCrossMachineAddressWithoutTheSwitchIsNotRefusedHereButCarriesFalse`（控制器不自己判，只照实传 false）。

`feature/settings/src/test/kotlin/com/mediahub/feature/settings/sync/SourceSyncReceiveControllerTest.kt`（+3 例）：:48 `theCrossMachineSwitchIsOffByDefault`、:55 `theCrossMachineSwitchTravelsVerbatimToThePort`（`receive` 真走完，端口收到 192.168.1.5 + true）、:70 `aCrossMachineAddressWithoutTheSwitchCarriesFalse`。

`feature/settings/src/androidTest/kotlin/com/mediahub/feature/settings/sync/SourceSyncSectionsScreenTest.kt`（+4 例，只编译不执行）：:104 `theReceiveCardOffersACrossMachineSwitch`、:117 `theReceiveFormCarriesTheCrossMachineSwitchVerbatim`、:395 `theSendCardOffersACrossMachineSwitch`、:407 `theSendFormCarriesTheCrossMachineSwitchVerbatim`。

### 31.7 本机实跑

命令（先 `gradlew --stop`；`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`、`ANDROID_HOME=C:\Users\55160\AppData\Local\Android\Sdk`、`GRADLE_OPTS=-Xmx2560m -XX:MaxMetaspaceSize=896m`）：

`gradlew.bat --max-workers=2 --rerun-tasks :core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest` → **`BUILD SUCCESSFUL in 7m 24s`**、`234 actionable tasks: 234 executed`、`EXIT=0`；日志 `D:/mh-verify/w6-52b-gradle.log`，脚本 `D:/mh-verify/w6-52b-gradle.ps1`。

XML 口径（`*/build/test-results/testDebugUnitTest/` 逐文件解析 `<testsuite>` 属性）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 本节带来的变化 |
| --- | --- | --- | --- | --- | --- | --- |
| core/common | 35 | **503** | 0 | 0 | 11 | 492 + `SourceSyncTransferClientHostTest` 11 |
| core/database | 18 | 172 | 0 | 0 | 12 | 不变（只加透传参数，无新增用例） |
| feature/settings | 16 | **278** | 0 | 0 | 0 | 272 + 6（`SourceSyncSendControllerTest` 35 例、`SourceSyncReceiveControllerTest` 28 例） |

androidTest APK `feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk` = **20443294 B**（2026-10-25 01:40:26）。大小与 W6-49/W6-53 那几次相同过，所以做了逐 dex 解包核对：`Expand-Archive` 展开 19 个 dex 后用 `Buffer.includes` 查字节，四个新用例名与两个新 tag 都在 `classes17.dex`（103328 B）里，`sync.receive.allow-host` / `sync.send.allow-host` 另在 `classes12.dex`（691048 B）——这张包确为本次编译产物，不是陈旧复用。

先导证据：`SourceSyncTransferClientHostTest` 在全量跑之前单独跑过一次（`BUILD SUCCESSFUL in 2m 9s`、11/11、`17 actionable tasks: 5 executed`），日志 `D:/mh-verify/w6-52b-core.log`。

收尾：`gradlew --stop` → 残留 java 0；仓库内无 `hs_err_pid*.log` / `replay_pid*.log`。

### 31.8 未覆盖（诚实清单）

1. **真实两机 NOT_RUN**：本机一次都没有 bind / dial 任何非回环地址——那会让 Windows 弹出防火墙授权框并抢焦点，违反「GUI/自动化不得干扰使用者」的纪律。所以「两个单播地址之间真能传完一封信封」这件事，两端都还没有证据（Windows 侧 E1–E6 也仍是 NOT_RUN，见 `docs/cross-device/W6-51-EXPLICIT-HOST.md` §8）。
2. **设备执行未做**：本节新增的 4 个 androidTest 只到「编译进 APK」为止，DEVICE_UNVERIFIED；设备结论要等 CI 的 visual-instrumentation 作业。
3. **单元门禁**：`SourceSyncTransferClientHostTest` 已由 W6-56 钉进 `--required-suite`（36 → 37 个套件，见 §31.10）；`SourceSyncSectionsScreenTest` 属 androidTest，本来就不在单元门禁里（由 visual-instrumentation 作业覆盖，见 §31.7）。
4. **只有开关，没有发现机制**：不扫网段、不解析 mDNS、不记忆上次地址；地址要靠人抄。
5. **没有「地址簿」**：抖动或换网后用户要重新填。
6. **无视觉回归**：复选框位置与说明文字只有断言「存在且可点」，没有截图比对。
7. **Android 侧没有对应的 `--bind` 之外的等价物**：监听端绑非回环靠 W6-52 的地址框 + 本节无关；拨号端没有「从监听到的地址自动回填」这类联动。

### 31.9 下一步

- 真实两机取证（需要一台第二台机器 + 允许一次防火墙授权的窗口）：先在 Windows 用 `transfer serve --bind <IP> --allow-non-loopback` 起监听，Android 发送卡填同一 IP、点亮开关发一次；再反向（Android 监听端填 IP + 开关，Windows `transfer send --allow-non-loopback`）。两侧都要抓 `not-loopback` 之外的码。
- 若将来要把「被策略拒绝」与「地址不是回环」在机器可辨层面分开，必须两端同时新开一个码集合、同名同序、各有用例（W6-47 结论 C），单端做不算。

### 31.10 W6-56：把拨号侧地址判定钉进单元门禁

**为什么钉**：`--required-suite` 的语义是「这个套件必须真的执行过」。没有这一行，套件整体消失（文件被误删、被 `@Ignore`、source set 变更导致整类不参与编译）时 gradle 依然打印 `BUILD SUCCESSFUL` —— 这正是 windows-ci 上 WebDav 套件踩过的坑。两个方向的地址判定此前只有一半受保护：监听端（绑定）的 `SourceSyncHostAddressTest` 已由 W6-55 钉住，拨号端（`sendTo` / `receiveFrom`）的 `SourceSyncTransferClientHostTest` 到本节才钉。

**改动**（`.github/workflows/android-ci.yml`，+3 行）：两行注释 + 一行 `--required-suite com.mediahub.core.common.sync.SourceSyncTransferClientHostTest`，钉住套件数 **36 → 37**；`--require-complete-suites` 与其余 36 行一字未动。

**口径回顾（一个判定、两个视角）**：地址判定仍然只有一处 `SourceSyncHostAddress.resolve(host, allowExplicitHost, verb)`，`verb` 只改「开关关着」那句里的动词（监听端缺省 `绑定`、拨号端传 `连`）。因此：

- 拨号端开关关着 ⇒ 与 W6-23 起的原文逐字相同：`只允许连回环地址（127.0.0.1 / ::1 / localhost），实际 <text>`
- 监听端开关关着 ⇒ 与 W6-52 逐字相同：`只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 <text>`
- 另外三条两端共用同一句、不含动词：
  - `「<text>」不是 IP 字面量，本版本传输不做域名解析；请填 127.0.0.1、::1、localhost 或那台机器的单播 IP。`
  - `「<text>」不是一个具体某台机器的地址（通配 / 广播 / 组播 / 保留地址），建 socket 之前就拒绝了；请填一个单播地址，例如 192.168.1.20。`
  - `地址最多 256 个字符，这个没有去绑。`
- 码始终是 `not-loopback`（W6-47 结论 B）；机器可辨的新码集合本轮不做（结论 C：要两端同时新开、同名同序、各有用例）。

**本机核对**：

1. 只跑该套件：`gradlew.bat --max-workers=2 :core:common:testDebugUnitTest --tests "com.mediahub.core.common.sync.SourceSyncTransferClientHostTest"` → 11 例全绿（W6-52b 的先导证据：`BUILD SUCCESSFUL in 2m 9s`、`17 actionable tasks: 5 executed`、XML `tests=11 failures=0 errors=0`）。
2. 门禁自带单测（改 `.github/workflows/` 或 `.github/scripts/` 后必跑）：`python -m unittest discover -s .github/scripts/tests` → `Ran 54 tests in 104.789s` / `OK`。

**未覆盖**：套件只验证闸门本身（一次都不真拨非回环地址，理由见 §31.8 第 1 条）；设备执行仍 DEVICE_UNVERIFIED；真实两机 NOT_RUN；CI 侧「37 条 `--required-suite` 都真跑过」的证据记在 PR #33 的 exact-SHA 表里，本节写就时该 head 的 run 尚未回执。

## 32. W6-57：回环上的四维组合矩阵（TLS × 认证 × 回执 × 投递）

### 32.1 动机

`D:/mh-verify/android-w6-gap-table.md` 第 5 条：Windows 侧有 `windows/tools/acceptance/w6/transfer-tls-auth.ps1`（20 例，真进程）与 presenter-e2e（14 例），Android 侧只有**单维**套件——`SourceSyncTransferRoundTripTest`（分块/挑战/截断）、`SourceSyncTransferAuthTest`（五码 + tag 向量）、`SourceSyncTlsTest`（握手 + 指纹钉扎）、`SourceSyncReceiptTest`（0x04 四码）、`SourceSyncDeliveryTest`（`delivery-unconfirmed`）。每一维各自成立，但**没有一处把四个开关同时打开放在同一条真实回环 socket 上跑**：谁压掉谁（回执 vs 投递）、失败落在发送端还是监听端、TLS 之后的码是否与明文逐字相同，都没有断言钉住。

W6-57 补的正是这一格：新增 `core/common/src/test/kotlin/com/mediahub/core/common/sync/SourceSyncTransferMatrixTest.kt`，20 个格子，每格起一枚真监听端、一枚真客户端，走真实 TCP（含真实 JSSE 握手），逐格断言外部可观察结果与稳定码。

### 32.2 矩阵（20 格）

| 格 | 开什么 | 发送端结果 | 监听端结果 | 钉住的外部语义 |
| --- | --- | --- | --- | --- |
| 1 | 全关 | `Ok`，`delivery = null` | `Received`，信封逐字节等于 golden | 基线：两个都不开会回任何字节 |
| 2 | 只给挑战 | `Ok` | `Received.challenge` 回显同一挑战 | 挑战只是回显，不是认证 |
| 3 | 两端认证（同口令） | `Ok` | `Received` | 认证单开可通过 |
| 4 | 客户端口令错 | `Ok`（不读回话，看不见失败） | `auth-mismatch` | 失败只落在监听端——想确认必须开回执/投递 |
| 5 | 监听端要认证、客户端不带 tag | `Ok` | `auth-missing` | 「没送 tag」与「没宣布挑战」不是一个码 |
| 6 | 监听端开认证却无口令 | 写完即 `Ok` | `auth-secret-unavailable` | 配置错误在读帧时暴露，不自作主张跳过校验 |
| 7 | 只开回执 | `Ok` | `receiptWritten = true` | 0x04 真写出来了 |
| 8 | 客户端等回执、监听端沉默 | `receipt-missing` | `receiptWritten = false` | 没写回执的监听端不谎称写过 |
| 9 | 只开投递 | `Ok(..., delivery = Confirmed(bytesRead = 0))` | `Received` | 对端只关连接、不回字节 |
| 10 | 对端既不关也不说话（裸 ServerSocket） | `delivery-unconfirmed` | ——（本格刻意不用监听端） | 预算耗光即折码，不抛异常 |
| 11 | 回执 + 投递 | `Ok`，`delivery = null` | `receiptWritten = true` | 回执优先，投递确认被忽略 |
| 12 | 只开 TLS | `Ok` | `Received` | 握手指纹钉对即可通行 |
| 13 | 指纹钉错 | `tls-fingerprint-mismatch` | 握手前就断 | 一个信封字节都不写 |
| 14 | TLS + 认证 | `Ok` | `Received` | tag 帧走加密流 |
| 15 | TLS + 口令错 | `Ok` | `auth-mismatch` | 加密后码与明文逐字相同 |
| 16 | TLS + 回执 | `Ok` | `receiptWritten = true` | 0x04 写在 `TlsStream` 上 |
| 17 | TLS + 投递 | `Ok(..., Confirmed)` | `Received` | graceful close 也能当 FIN |
| 18 | TLS + 认证 + 回执 | `Ok` | `Received` + `receiptWritten` | 三维同时开不互相干扰 |
| 19 | TLS + 认证 + 投递 | `Ok(..., Confirmed)` | `Received` | 认证成功后 graceful close 仍可判投递 |
| 20 | 四维全开 | `Ok` | `Received` + `receiptWritten` | 四码集合互不污染 |

### 32.3 一条协议事实：认证必须同时宣布挑战（首跑 6 红的根因）

tag 的 MAC 输入是「挑战的 UTF-8 字节 ‖ 信封字节」，所以**没有挑战就没有可绑定的 nonce**：发送端在 `sendTo` 里本地就报 `auth-required` 并**一个字节都不写**（detail 原文 `Authentication is on but no challenge was announced; the challenge is the nonce the tag binds to, so nothing was sent.`）。对端只会看到一条空连接，因而报 `frame-truncated`。

W6-57 首跑正是栽在这里：格 3 / 14 / 19 只开了认证没给挑战，于是发送端本地拒绝、监听端读到空流；格 4 / 15 期望 `auth-mismatch` 却拿到 `frame-truncated`（同上原因）；格 5 期望 `auth-required` 实际是 `auth-missing`——`auth-required` 只属于「本端要认证却没挑战」，不属于「对端没送 tag」。修法是每格开认证就同时给挑战，并把格 5 的期望改成 `auth-missing`；类 KDoc 里把这条协议事实写下来，后续任何人加格子都不会再踩。

### 32.4 实现落点

| 位置 | 内容 |
| --- | --- |
| `SourceSyncTransferMatrixTest.kt:374` | `private fun exchange(tls, challenge, listenerAuth, clientAuth, listenerReceipt, clientReceipt, delivery, clientPin): Outcome`——起监听（`port = 0` 由系统分配）→ 后台 `ReceiveCall` → 主线程 `SendCall` → 收两侧结果，`finally` 关监听 |
| `:413` | `private fun silentPeer(server: ServerSocket): Thread`——裸 ServerSocket 读走帧流后挂住不关，只给格 10 用 |
| `:433` / `:466` | `ReceiveCall` / `SendCall` 两个后台线程包装（`join(30_000)`，抛过异常就让测试红，不吞） |
| `:68`–`:341` | 20 个 `cellNN_*` 用例（行号见 32.5） |
| `:551` | `assertSentOk` / `assertReceived` / `failedReasonOf` / `listenerCodeOf` / `describeSent` / `describeListener` |

纪律写在类 KDoc 里：**只碰 `127.0.0.1`**。非回环地址一格都不 bind、不拨——那会让 Windows 弹防火墙授权窗，违背 GUI 纪律；跨机证据仍记 NOT_RUN。

### 32.5 用例清单（20 例，行号）

| 行 | 用例 |
| --- | --- |
| `:68` | `cell01_nothingEnabledIsThePlainBaseline` |
| `:82` | `cell02_challengeAloneIsEchoedVerbatim` |
| `:94` | `cell03_authAloneWithTheSameSecretSucceeds` |
| `:111` | `cell04_aWrongSecretIsMismatchOnTheListenerAndInvisibleToTheSender` |
| `:128` | `cell05_aListenerThatRequiresAuthRefusesAPeerWithoutATagFrame` |
| `:139` | `cell06_aListenerWithAuthButNoSecretSaysSecretUnavailable` |
| `:155` | `cell07_receiptAloneIsWrittenAndAwaited` |
| `:166` | `cell08_awaitingAReceiptFromASilentListenerIsReceiptMissing` |
| `:176` | `cell09_deliveryAloneIsConfirmedByTheListenerClosing` |
| `:194` | `cell10_aPeerThatNeverClosesLeavesDeliveryUnconfirmed` |
| `:209` | `cell11_theReceiptWinsWhenBothAreEnabled` |
| `:224` | `cell12_tlsAloneCarriesTheEnvelope` |
| `:237` | `cell13_aWrongPinStopsBeforeAnyEnvelopeByte` |
| `:257` | `cell14_tlsPlusAuthSucceeds` |
| `:270` | `cell15_tlsPlusAWrongSecretFailsAtTheTagFrame` |
| `:282` | `cell16_tlsPlusReceiptConfirmsOverTheEncryptedStream` |
| `:294` | `cell17_tlsPlusDeliveryIsConfirmedByAGracefulClose` |
| `:306` | `cell18_tlsPlusAuthPlusReceiptSucceeds` |
| `:323` | `cell19_tlsPlusAuthPlusDeliverySucceeds` |
| `:341` | `cell20_allFourDimensionsTogetherStillSucceed` |

### 32.6 本机实跑证据（EXECUTED_PASS）

- 单套件：`gradlew.bat --max-workers=2 :core:common:testDebugUnitTest --tests "com.mediahub.core.common.sync.SourceSyncTransferMatrixTest"` → `BUILD SUCCESSFUL in 1m 38s`；XML `<testsuite ... tests="20" skipped="0" failures="0" errors="0" ... time="2.408">`。日志 `D:/mh-verify/w6-57-matrix.log`。
- 模块全量（`--rerun-tasks :core:common:testDebugUnitTest`）→ `BUILD SUCCESSFUL in 2m 43s`、`17 actionable tasks: 17 executed`；XML 合计 **36 套件 / 523 例 / 0 失败 / 0 错误 / 11 跳过**（503 + 20），矩阵套件在列。日志 `D:/mh-verify/w6-57-core-full.log`。
- 首跑（未修前）：`BUILD FAILED in 1m 39s`，`20 tests completed, 6 failed`（格 3/4/5/14/15/19），原因见 32.3。

### 32.7 门禁（W6-57 的一部分）

`.github/workflows/android-ci.yml` 增加注释两行 + `--required-suite com.mediahub.core.common.sync.SourceSyncTransferMatrixTest`，`--required-suite` **37 → 38** 条；`yaml.safe_load` 自检通过（`jobs = 2`）。改门禁必须同步跑的门禁自带单测：`python -m unittest discover -s .github/scripts/tests` → `Ran 54 tests in 94.641s` / `OK`。日志 `D:/mh-verify/w6-57-gate-unittest.log`。

矩阵只碰回环，CI runner 上不需要任何设备或额外服务，因此可以直接进单元门禁（与 androidTest 无关）。

### 32.8 未覆盖

1. **真实两机**：`cross-machine` 证据两端 NOT_RUN——需要第二台机器，且要让 Windows 弹一次防火墙授权（用户当面要求 GUI/网络类验证走空闲门禁，本轮不做）。
2. **设备执行**：矩阵是 JVM 单元测试（跑在 CI 的 ubuntu runner 的 JVM 上），未在任何 Android 设备上执行过；`DEVICE_UNVERIFIED`。
3. **跨进程重放**：`auth-replayed` 需要共享 `SourceSyncNonceCache` 跨两封信封，本矩阵每格只发一封，重放语义仍只由 `SourceSyncTransferAuthTest` 的流级用例覆盖（`:411` 起）。
4. **客户端身份证书 / mTLS**：`SourceSyncTlsIdentity` 只用于监听端服务端证书，矩阵没有「客户端出示证书」这一维（协议不支持）。
5. **大信封耗时**：矩阵用 `windows-envelope-device-a.json`（单块），分块与 `payload-too-large` 走 `SourceSyncTransferRoundTripTest`，矩阵不重复测吞吐。
6. **视觉/界面**：矩阵不碰 Compose，界面侧的开关联动仍由 `SourceSyncSectionsScreenTest` 的 instrumentation 覆盖。
7. **CI 实证**：本节的 38 条套件与 2098 唯一用例要到下一次 push 后的 Android CI 上才有 exact-SHA 证明（本节写就时只有本机 `unittest` + gradle）。

### 32.9 下一步

1. 监听端取消接收（缺口表 #8 的另一半：`SourceSyncReceiveController` 只有 `receive`/`clear`，没有 cancel，真实 Socket 中途取消 NOT_RUN）。
2. W6-47 跨机契约在 Android 侧的引用点：把 `docs/cross-device/W6-47-CROSS-MACHINE.md` 的结论 A/B/C 写进 `docs/android-source-sync-transfer.md` 的索引，让两端文档互指。
3. 真实两机取证（等用户授权与第二台机器）。

## §33 Android 接收中「取消接收」（W6-58）

> 本节记录拨号接收卡（「从 Windows 接收」）在传输途中可以被用户取消的实现与证据。
> 与前几节一样：只写外部可观察语义、稳定码与可复核的行号，不写推测。

### §33.1 动机：缺口表里唯一没有「退路」的卡

W6-27 起「从 Windows 接收」是手机主动拨号收帧。它是页尾三张卡里**唯一没有停止/取消入口**的一张：
监听卡有「停止监听」，发送卡有「取消发送」（W6-49），而接收卡一旦按下「接收」，用户能做的只有等。
更要紧的是这个「等」不是抽象的：分块收帧是**阻塞** socket 读，缺省读超时 15 000 ms（`SourceSyncTransferListener.DEFAULT_READ_TIMEOUT_MS` / `SourceSyncTransferClient` 的同名常量），
对端半开、路由器丢包、或对端进程卡死时，界面会一直停在「接收中…」。

缺口表（`D:/mh-verify/android-w6-gap-table.md`）第 8 条把「真实 Socket 中途取消」记为 NOT_RUN，
此前只到控制器层的`cancelSend`；本节补齐的正是**拨号接收**这一侧的取消入口与它的库层把手。

### §33.2 设计取舍：取消必须真的关连接，光 cancel 协程不够

第一版想法是「起一个 Job，取消时 `job.cancel()`」。这个做法在本项目**不成立**，原因是：

- 分块收帧的每一次读都是 `input.read(...)`（阻塞 IO）。挂起点（suspend 函数的 return）不在读的中间，
  所以 `job.cancel()` 只会在下一次真正走到挂起点时才生效——而那时已经读完一整帧或者超时了；
- 于是界面会停在「接收中」最多 15 s 才回到 Idle，用户按了取消却看不到任何反应。

结论：取消要么关 socket，要么无意义。实现上用一层很薄的把手把这两件事绑定：

1. `SourceSyncCancellation`（`core/common/.../sync/SourceSyncCancellation.kt`，84 行）持一个 `AtomicBoolean` 与一个 `AtomicReference<Closeable?>` 槽；
2. 客户端在 socket 建好之后 `cancel?.attach(socket)`（`SourceSyncTransferClient.kt:190`）；
3. `cancel()` 先 `compareAndSet(false, true)` 置位，再取走槽里的 `Closeable` 并关掉；
4. 阻塞读立刻抛 `SocketException`，三个 catch（`SocketTimeoutException` / `SocketException` / `IOException`）的**第一行**都先看 `cancel?.isCancelled`，是就返回本地码而不是 IO 码；
5. `finally` 首行 `cancel?.detach(socket)`，把槽清干净，避免把手长期握着一个已关的 socket。

先置位再关的顺序是必须的：如果先关 socket 再置位，catch 里可能还看不到 `isCancelled`，
就会把一次用户取消报成 `io-failed`——那正是「取消被算成失败」的那类事故。

`attach` 里还有一道「二次检查」：设进槽之后再看一眼 `isCancelled`，如果这期间已经被取消，
就自己把刚放进去的 socket 关掉并清槽。这样「取消发生得比 attach 更早」也不会漏掉。

### §33.3 本地码 `transfer-cancelled` 不进跨机词汇表

取消是**本端**的事件，对端看不到任何「取消帧」——它只会看到连接断了。所以：

- 不新增跨机码：五集（传输 10 / TLS 4 / 认证 5 / 回执 4 / 投递 1）一个字都没动，
  这与 W6-47 ADR 结论 A「跨机不新开第 6 个码集合」一致；
- `SourceSyncLocalReasons.CANCELLED = "transfer-cancelled"` 是新加的**本地码**对象，只在 core 与界面之间流转；
- 用例 `theLocalCodeIsNeverPartOfTheCrossMachineVocabulary` 直接钉住 `SourceSyncTransferReasons.ALL.size == 10` 且集合里不含 `transfer-cancelled`；
- 界面层把它咽掉：`SourceSyncReceiveController` 看到把手已取消就把状态置回 `Idle` 并 `return true`，不写失败行、不留码。

### §33.4 三条外部可观察语义

| # | 情形 | 可观察结果 | 由谁钉住 |
| --- | --- | --- | --- |
| 1 | 接收中按「取消接收」 | 状态回 `Idle`；**没有**失败行、**没有**稳定码；单飞标记释放 | `cancelDuringTheTransportClosesTheHandleAndReturnsToIdle`、`aCancelledReceiveGoesBackToIdleWithoutKeepingTheLocalCode` |
| 2 | 取消之后立刻再按「接收」 | 可以再收（新把手是干净的，旧取消不泄漏） | `eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak`、`cancelDuringTransportReturnsToIdleAndFreesTheSingleFlightGuard` |
| 3 | 没在接收时点「取消接收」 | no-op：`cancel()` 返回 null，状态不变，端口一次都不调 | `cancelWhenNothingWasEverReceivedIsANoop`、`clearBetweenReceivesIsHarmlessAndCancelAfterItIsANoop` |

第 1 条的「没有失败行」是刻意的：用户按的取消不是错误。这条与本项目另一处纪律同源——
**码只用于对端/协议层面的失败**，用户自己的动作不该出现在失败列里。

### §33.5 落点行号表

| 文件 | 行号 | 内容 |
| --- | --- | --- |
| `core/common/.../sync/SourceSyncCancellation.kt` | :15 / :18 | `object SourceSyncLocalReasons` / `const val CANCELLED = "transfer-cancelled"` |
| 同上 | :35 / :42 / :50 / :60 / :72 | `class SourceSyncCancellation` / `val isCancelled` / `fun cancel()` / `internal fun attach(closeable)` / `internal fun detach(closeable)` |
| `core/common/.../sync/SourceSyncTransferClient.kt` | :51 | `fun receiveFrom(...)` 末位新增 `cancel: SourceSyncCancellation? = null` |
| 同上 | :128 | `private fun cancelledResult()`（本地码 + 「连接在本地关闭，对端可能只看到断开」） |
| 同上 | :163 | `private fun withConnection(...)` 新增 `cancel` 形参 |
| 同上 | :190 / :233 | `cancel?.attach(opened)` / `finally` 首行 `cancel?.detach(it)` |
| 同上 | :215 / :221 / :227 | 三个 catch 首行 `if (cancel?.isCancelled == true) return cancelledResult()` |
| `core/database/.../sync/SourceSyncTransferImportService.kt` | :98 / :106 | `suspend fun receiveAndImport(...)` 新增 `cancel` / 转调 `client.receiveFrom(...)` |
| `feature/.../sync/SourceSyncReceiveController.kt` | :57 | `SourceSyncReceiveCopy.CANCEL = "取消接收"` |
| 同上 | :140 | `SourceSyncReceivePort` 的第 5 参默认重载（转调 4 参，既有替身零改动） |
| 同上 | :191 | `suspend fun receive(form, cancel = null)` |
| 同上 | :221 | 取消不是失败：置回 `Idle` 并 `return true` |
| 同上 | :306 | 生产端口 5 参 override，把 `cancel` 交给 `service.receiveAndImport(...)` |
| `feature/.../sync/SourceSyncReceiveLauncher.kt` | :27 / :37 / :41 / :62 / :71 | `class` / `val isActive` / `fun launch` / `fun cancel` / `fun clear`（76 行新文件） |
| `feature/.../sync/SourceSyncImportViewModel.kt` | :138 / :168 / :178 / :251 / :265 | `receiveLauncher` / `fun receive` / `fun cancelReceive` / `reset()` 与 `onCleared()` 里的 `clear()` |
| `feature/.../sync/SourceSyncImportScreen.kt` | :112 / :465 / :750 | tag `sync.receive.cancel` / `onCancel = onReceiveCancel` / 取消按钮（`enabled = receiving`） |

### §33.6 用例清单

#### core/common：`SourceSyncTransferCancelTest`（6 例）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :121 | `aBlockedReadEndsAsTheLocalCodeWithinMilliseconds` | 阻塞读在取消后 < 2 s 内结束，码是本地码 |
| :147 | `cancellingBeforeTheDialStillYieldsTheLocalCode` | 还没拨号就取消也返回本地码 |
| :158 | `cancelIsIdempotentAndSticky` | 取消幂等、粘滞 |
| :168 | `aHandleThatWasNeverCancelledDoesNotDisturbANormalReceive` | 把手在场但没取消：正常收帧，逐字节一致 |
| :187 | `aFreshHandleWorksRightAfterACancellation` | 取消之后换新把手立刻可用 |
| :209 | `theLocalCodeIsNeverPartOfTheCrossMachineVocabulary` | 跨机码集合仍是 10 个、不含本地码 |

#### feature/settings：`SourceSyncReceiveControllerTest`（+5 例）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :405 | `withoutAHandleThePortGetsNullAndNothingIsCancellable` | 不传把手时端口收到 null |
| :417 | `theHandleTravelsVerbatimToThePort` | 把手原样到端口（同一实例） |
| :433 | `aCancelledReceiveGoesBackToIdleWithoutKeepingTheLocalCode` | 取消回 Idle、码为 null、之后能再收 |
| :453 | `aFreshHandleDoesNotSwallowARealFailureCode` | 没取消时真失败码原样透出 |
| :468 | `theCancelButtonCopyIsStable` | 按钮文案稳定（取消接收） |

#### feature/settings：`SourceSyncReceiveLauncherTest`（8 例，新文件 220 行）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :36 | `launchStartsTheReceiveAndMarksItInFlight` | 起一条 + 状态 Receiving + isActive |
| :53 | `cancelDuringTheTransportClosesTheHandleAndReturnsToIdle` | **把手真的被置为已取消**（关连接）+ 回 Idle + 无码 |
| :77 | `cancelWhenNothingWasEverReceivedIsANoop` | 没在收：cancel 返回 null |
| :88 | `cancelAfterTheCancelledReceiveFinishedIsStillANoop` | 收尾之后再点取消仍是 no-op |
| :102 | `aSecondLaunchWhileInFlightIsIgnored` | 在飞时第二次 launch 被忽略 |
| :118 | `cancellingTwiceOnlyCancelsTheFirstTime` | 同一条只能取消一次 |
| :133 | `clearBetweenReceivesIsHarmlessAndCancelAfterItIsANoop` | clear 只丢引用不取消 |
| :154 | `eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak` | 每次 launch 新把手、旧取消不泄漏 |

#### androidTest：`SourceSyncSectionsScreenTest`（+4 例）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :149 | `theReceiveCardOffersACancelButtonThatIsDisabledWhenIdle` | 按钮在场、文案固定、空闲时禁用 |
| :162 | `whileReceivingTheCancelButtonCallsBackAndTheReceiveButtonIsLocked` | 接收中可点、回调只来一次、接收按钮与输入框锁住 |
| :178 | `onceTheEnvelopeIsInTheCancelButtonIsLockedAgain` | 收下之后取消又禁用（取消不是清空结果） |
| :188 | `afterAFailureTheCancelButtonIsLockedAgain` | 失败之后同样禁用 |

### §33.7 本机实跑

全部为本机串行执行（同一台机器上不与 dotnet 大套件并发，避免 2026-10-24 那次 JVM OOM 重演）。

| 步骤 | 命令/脚本 | 结果 |
| --- | --- | --- |
| 定向复验（core） | `gradlew.bat --max-workers=2 :core:common:testDebugUnitTest --tests "com.mediahub.core.common.sync.SourceSyncTransferCancelTest"` | 6/6 通过（XML 时间 `2026-10-24T20:41:16Z`） |
| 定向复验（feature） | `--tests "com.mediahub.feature.settings.sync.SourceSyncReceiveLauncherTest"` | 8/8 通过（XML 时间 `2026-10-24T20:53:32Z`） |
| 全量四任务 | `D:/mh-verify/w6-58-gradle5.ps1`（`--rerun-tasks`，先 `gradlew --stop`） | `BUILD SUCCESSFUL in 5m 20s`、`234 actionable tasks: 234 executed`、`EXIT=0` |
| 门禁自带单测 | `python -m unittest discover -s .github/scripts/tests` | `Ran 54 tests in 100.481s` / `OK` / `EXIT=0` |

XML 逐套件汇总（`<testsuite>` 属性相加，非估算）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| `core/common` | 37 | **529** | 0 | 0 | 11 | 523 → 529（+6，`SourceSyncTransferCancelTest`） |
| `core/database` | 18 | **172** | 0 | 0 | 12 | 不变 |
| `feature/settings` | 17 | **291** | 0 | 0 | 0 | 278 → 291（+5 控制器 + +8 launcher） |

androidTest APK：`feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk` = **20443294 B**（`2026-10-25 12:03:05`）。
解包核对（先复制成 `.zip` 再 `Expand-Archive`，共 19 个 dex）：四个新用例名
`theReceiveCardOffersACancelButtonThatIsDisabledWhenIdle` / `whileReceivingTheCancelButtonCallsBackAndTheReceiveButtonIsLocked` /
`onceTheEnvelopeIsInTheCancelButtonIsLockedAgain` / `afterAFailureTheCancelButtonIsLockedAgain` 全在 `classes17.dex`（106216 B），
`sync.receive.cancel` 与「取消接收」在 `classes12.dex`（699116 B）与 `classes17.dex` —— 因此这个 APK 不是陈旧产物
（该文件大小与 W6-49 / W6-53 / W6-54 三次巧合相同，每次都必须解包证伪）。

清理：跑完 `gradlew --stop`；仓库内无 `hs_err_pid*.log` / `replay_pid*.log`。

#### §33.7.1 断点记录：两次首红与真因（本节最值钱的部分）

**红 1（编译期）**：`e: SourceSyncTransferCancelTest.kt:140:32 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'`
——`SourceSyncTransferResult.Failed.message` 是 `String?`，测试里直接 `.contains(...)` 不合法；改为 `failure.message?.contains("本地取消") == true`。

**红 2（运行时，2 例）**：`aBlockedReadEndsAsTheLocalCodeWithinMilliseconds`（`SourceSyncTransferCancelTest.kt:136`）与
`aFreshHandleWorksRightAfterACancellation`（`:195`）报 `org.junit.ComparisonFailure: expected:<[transfer-cancel]led> but was:<[io-fai]led>`。

根因不是把手没关，而是**失败分类发生在把手检查之外**：取消关 socket 后，阻塞读抛的 `SocketException` 先被协议层
`SourceSyncTransfer.receive(...)` 自己 catch 并折成 `SourceSyncTransferResult.Failed(io-failed)` 返回，
`withConnection` 里那三个 catch 压根没被触发，所以「catch 首行先看 `isCancelled`」这一层防御是空的。

修法：在 `withConnection` 里给 `body(...)` 的结果加一道**统一裁决**（`SourceSyncTransferClient.kt:211` 之后）——
`if (cancel?.isCancelled == true && outcome is SourceSyncTransferResult.Failed) cancelledResult() else outcome`。
两条约束写死在这里：①取消在任何失败分类之前胜出；②只改写失败结果，**成功收下的信封不会被晚到的取消抹掉**（否则会出现「收下了却报取消」）。

**红 3（feature/settings 1 例）**：`eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak` 报
`java.lang.AssertionError: 每次 launch 都必须新建把手`（`:170`）。根因是测试自己的时序假设错：`launch` 只把协程排进 testScheduler，
第二次 `launch` 之后立刻读 `port.cancel` 拿到的还是上一枚把手。修法：`FakeReceivePort` 加 `val handles = mutableListOf<SourceSyncCancellation?>()`
记录每一枚，测试里 `advanceUntilIdle()` 之后再断言 `handles.size == 2` 与 `handles[1]`。定向复验 8/8 绿。

### §33.8 未覆盖（本节交付时必须如实保留）

1. **真实两机**：取消的跨机表现（对端此刻收到 RST 还是只看到 FIN）未取证，因为需要第二台机器与用户同意打开防火墙；状态仍是 NOT_RUN。
2. **TLS 握手中取消**：把手在 socket 建好后立刻 attach，握手阶段也被覆盖到，但**没有专门用例**去卡「TLS 1.3 握手进行中」这个窗口。
3. **监听卡的取消**：`SourceSyncListenerController` 目前只有「停止监听」与「收一次」，没有「取消这一次接收」；本节只做拨号接收侧。
4. **Hilt 注入与进程重建**：Launcher 由 ViewModel 持有，进程被杀后的恢复没有用例。
5. **门禁已钉、证据待 CI**：`SourceSyncTransferCancelTest` 与 `SourceSyncReceiveLauncherTest` 本轮已进 `--required-suite`（38 → **40** 个套件），本机 `python -m unittest discover -s .github/scripts/tests` 为 `Ran 54 tests` / `OK`；但「CI 上这两个套件确实被门禁跑过」只能由新 head 的 Android CI 日志证明，本节写就时该 head 尚未 push。
6. **视觉回归**：取消按钮出现在真机首屏之外（页尾第三卡），布局本身没有像素级基线。
7. **设备执行**：本机无连接设备，androidTest 的 4 例只到「APK 里确实编进了这些用例」这一层，设备结论由 CI 的 visual-instrumentation 提供。

### §33.9 下一步

- 监听卡补「取消这一次接收」（同一把手的复用：监听端的阻塞点在建连与读帧两处）；
- ~~把 `SourceSyncTransferCancelTest` 与 `SourceSyncReceiveLauncherTest` 钉进 `--required-suite`（38 → 40）~~ —— 本轮已完成（见 §33.8 第 5 条），待新 head 的 CI 日志确认；
- 真实两机取消取证（需第二台机器 + 防火墙同意），届时把 §33.8 第 1 条改成 EXECUTED_PASS。


## §34 监听端「取消这一次接收」（W6-61）

> 本节记录手机当接收端（监听卡）在等对端连上来、或已经连上但帧没收全时，
> 用户可以**只取消这一次等待**而不关掉监听的实现与证据。
> §33 做的是拨号侧（手机主动连 Windows），本节是对称的另一半：缺口表 #8 的后半条。

### §34.1 动机：缺口表 #8 的后半条，以及 close() 为什么不能当取消用

监听卡原先只有「停止监听」（`SourceSyncListenerController.stop()` → `session.close()`），
没有「取消这一次接收」。用户按下「等一次发送」之后，界面会停在「接收中…」直到
`acceptTimeoutMs`（缺省 15 000 ms）或读超时到点，中间没有任何退路。

一个看似省事的做法是「取消就调 `close()`」。这条路在本项目**不成立**，两条原因都是硬约束：

1. `close()` 关的是 `ServerSocket`，**监听从此作废**——端口没了，用户想再等一次必须重新「开始监听」，
   界面上等于「取消」把整张卡拆了；
2. 更糟的是 `close()` 让等待中的 `accept()` 抛 `SocketException`，被翻译成 `listener-failed`。
   也就是说「用户按了取消」会显示成一行失败、带一个稳定码——把用户的动作算成了故障，
   与 §33.4 那条纪律（码只用于对端/协议层面的失败）直接冲突。

所以 W6-61 的目标定义得很窄：**取消只结束这一次等待，监听仍然开着、端口不变、不留码。**

### §34.2 设计取舍：accept 必须切成小片轮询，否则取消只能等到超时

`ServerSocket.accept()` 是阻塞调用，超时由 `soTimeout` 决定（原先一次设成 `acceptTimeoutMs` = 15 s）。
只把取消把手接进来是不够的：阻塞在 `accept()` 里的线程要等 15 s 才会返回，
用户按了取消界面纹丝不动——这正是 §33.2 在拨号侧记过的那类「取消看似无效」。

实现上把一段 15 s 的等待拆成**以 `ACCEPT_SLICE_MS = 50` 为片长的轮询**
（`core/common/.../sync/SourceSyncTransferListener.kt:348`），且**总 deadline 不变**：

| 环节 | 做法 | 位置 |
| --- | --- | --- |
| deadline | `deadlineNanos = System.nanoTime() + acceptTimeoutMs * NANOS_PER_MILLI` | `SourceSyncTransferListener.kt:190` |
| 每片先看取消 | `if (cancel?.isCancelled == true) return Refused(cancelledResult())` | 同上 :192 |
| 到点 | 剩余 ≤ 0 ⇒ `acceptTimeoutResult()`（码仍是 `transfer-timeout`，文案不变） | 同上 :194 / :217 |
| 片长 | `remainingMillis.coerceAtMost(ACCEPT_SLICE_MS).coerceAtLeast(1)` 写回 `server.soTimeout` | 同上 :195-197 |
| 三处异常 | `SocketTimeoutException` 回循环；`SocketException`/`IOException` **先看取消**再分类 | 同上 :201 / :203 / :206 |

两处刻意保留的语义：

- `transfer-timeout` 的文案与触发条件一字未改——切片只是实现细节，外部看到的还是「在 N 毫秒内没有对端连上来」；
- `listener-failed` 仍然存在，但**只剩「真的 close 过」一种来源**（用例 `closingTheListenerIsStillReportedAsListenerFailedNotAsACancellation` 钉住这一点）。

连上之后的读阶段同理：socket 在 `accept` 成功那一刻就 `cancel?.attach(socket)`（:173），
`receiveOver` 的 `finally` 首行 `cancel?.detach(socket)`（:313）把槽清干净；
阻塞读抛异常时两个 catch（:294 `SocketTimeoutException` / :303 `IOException`）同样「取消优先」。

### §34.3 仍然没有新码：`transfer-cancelled` 是本地码

与 §33.3 完全同一条立场：取消是本端事件，对端只会看到连接断了，看不到任何「取消帧」。

- 五集（传输 10 / TLS 4 / 认证 5 / 回执 4 / 投递 1）一字未动，符合 W6-47 ADR 结论 A；
- 复用的是 W6-58 已存在的 `SourceSyncLocalReasons.CANCELLED = "transfer-cancelled"`，W6-61 没有新增任何码常量；
- 用例 `theLocalCodeIsNeverPartOfTheCrossMachineVocabulary` 继续钉 `SourceSyncTransferReasons.ALL.size == 10` 且不含本地码；
- 界面层咽掉：`SourceSyncListenerController.awaitOnce` 在 `apply(...)` 之前判断把手已取消，
  把状态置回 `Listening` 并 `return true`（`SourceSyncListenerController.kt:583`）——不写失败行、不留码。

一句话区别记在这里：**§33 的取消让状态回 `Idle`（没有在监听）；§34 的取消让状态回 `Listening`（监听还在，只是这次不收了）。**

### §34.4 五条外部可观察语义

| # | 情形 | 可观察结果 | 由谁钉住 |
| --- | --- | --- | --- |
| 1 | 等 accept 期间按「取消这一次接收」 | 毫秒级返回；码 `transfer-cancelled`；`listener.isClosed == false` | `cancellingWhileWaitingForAcceptReturnsTheLocalCodeWithinMilliseconds`（< 2 000 ms） |
| 2 | 已连上、帧还没收全时取消 | 同样本地码（不是 `io-failed`） | `cancellingDuringABlockedReadAlsoYieldsTheLocalCode` |
| 3 | 取消之后同一监听再等一次 | 能正常收到下一封信封（端口与监听都没坏） | `theListenerStillAcceptsAfterACancelledWait` |
| 4 | 真的 `close()` 掉监听 | 仍是 `listener-failed`，不会被误认成取消 | `closingTheListenerIsStillReportedAsListenerFailedNotAsACancellation` |
| 5 | 界面层取消 | 状态回 `Listening`；`codeOf == null`；`lineOf == null`；之后能再等一次 | `aCancelledWaitGoesBackToListeningWithoutAnyCode`、`aCancelledWaitCanBeFollowedByAnotherOneThatSucceeds` |

把手没在场（`cancel == null`）时行为与 W6-61 之前**逐字相同**：`receiveOnce` 的第五参缺省为 null，
既有全部监听用例、导入服务调用点、测试替身都不需要改动。
用例 `aHandleThatWasNeverCancelledDoesNotDisturbANormalReceive` 钉住这一点。

### §34.5 落点行号表

| 文件 | 行号 | 内容 |
| --- | --- | --- |
| `core/common/.../sync/SourceSyncTransferListener.kt` | :127 | `fun receiveOnce(...)` 第五尾参 `cancel: SourceSyncCancellation? = null`（:132） |
| 同上 | :149 | `override fun close()`（KDoc 补一段：取消不走 close，`listener-failed` 只剩真 close） |
| 同上 | :173 | `cancel?.attach(socket)` —— accept 成功即挂把手 |
| 同上 | :189 / :217 / :222 | `private fun acceptSocket` / `acceptTimeoutResult()` / `cancelledResult()` |
| 同上 | :234 / :313 | `private fun receiveOver(..., cancel)` / `finally` 首行 `cancel?.detach(socket)` |
| 同上 | :270 | `SourceSyncTransfer.receive(...)` 的 Failed 分支统一裁决：`if (cancel?.isCancelled) cancelledResult()` |
| 同上 | :345 / :348 / :350 | `CANCELLED_DETAIL` / `ACCEPT_SLICE_MS = 50` / `NANOS_PER_MILLI = 1_000_000L` |
| `core/database/.../sync/SourceSyncListenerImportService.kt` | :90 / :100 | `receiveAndImport(..., cancel = null)` / 透传给 `listener.receiveOnce(..., gate, cancel)` |
| `feature/.../sync/SourceSyncListenerController.kt` | :109 | `SourceSyncListenCopy.CANCEL_RECEIVE = "取消这一次接收"` |
| 同上 | :357 | `SourceSyncListenerSession` 的带缺省实现重载 `suspend fun receiveOnce(cancel)`（既有替身零改动） |
| 同上 | :565 / :571 / :583 | `suspend fun awaitOnce(cancel = null)` / `active.receiveOnce(cancel)` / 取消 ⇒ 回 `Listening` 并 `return true` |
| 同上 | :789 | `ImportSession` 覆盖带 cancel 版，透传给 `service.receiveAndImport` |
| `feature/.../sync/SourceSyncListenLauncher.kt` | :27 / :41 / :62 / :71 | `class` / `fun launch()` / `fun cancel()` / `fun clear()`（新文件，与 `SourceSyncReceiveLauncher` 同形） |
| `feature/.../sync/SourceSyncImportViewModel.kt` | :149 / :215-216 / :226-227 / :275 / :290 | `listenLauncher` / `awaitListenOnce()` / `cancelListening()` / `reset()` 与 `onCleared()` 各 `clear()` |
| `feature/.../sync/SourceSyncImportScreen.kt` | :148 / :236 / :278 / :479 / :1002-1004 | tag `sync.listen.cancel` / Route 接线 / 尾参 / 调用处 / 按钮（`enabled = receiving`，常驻禁用而非隐藏） |

### §34.6 用例清单

#### core/common：`SourceSyncListenerCancelTest`（7 例，新文件）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :96 | `cancellingWhileWaitingForAcceptReturnsTheLocalCodeWithinMilliseconds` | 等 accept 期间取消，< 2 000 ms 返回，码是本地码，`isClosed == false` |
| :122 | `cancellingDuringABlockedReadAlsoYieldsTheLocalCode` | 已连上但帧没发来，取消仍回本地码（不是 `io-failed`） |
| :154 | `cancellingBeforeTheWaitStartsStillYieldsTheLocalCode` | 还没开始等就先取消，也回本地码 |
| :169 | `theListenerStillAcceptsAfterACancelledWait` | 取消之后同一监听还能收下一封（端口没坏） |
| :194 | `aHandleThatWasNeverCancelledDoesNotDisturbANormalReceive` | 把手在场但不取消：正常收帧 |
| :212 | `closingTheListenerIsStillReportedAsListenerFailedNotAsACancellation` | 真 close 仍报 `listener-failed`，两个语义不混 |
| :225 | `theLocalCodeIsNeverPartOfTheCrossMachineVocabulary` | 跨机码集合仍是 10 个、不含 `transfer-cancelled` |

#### feature/settings：`SourceSyncListenerControllerTest`（+3 例）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :692 | `aCancelledWaitGoesBackToListeningWithoutAnyCode` | 把手原样送到会话层（:718）；状态回 `Listening`；`codeOf == null` |
| :723 | `aCancelledWaitCanBeFollowedByAnotherOneThatSucceeds` | 取消后再等一次能成功，单飞标记已释放 |
| :754 | `aRealFailureAfterACancelStillReportsItsCode` | 没取消时真失败码原样透出（取消优先不等于吞掉故障） |

#### feature/settings：`SourceSyncListenLauncherTest`（7 例，新文件）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :55 | `launchStartsTheWaitAndMarksItInFlight` | 起一条 + `isActive` |
| :69 | `aSecondLaunchWhileInFlightIsIgnored` | 在飞时第二次 launch 被忽略 |
| :82 | `cancelClosesTheHandleAndEndsTheJob` | 取消真的把把手置为已取消（等价于关连接） |
| :99 | `cancelWhenNothingWasEverStartedIsANoop` | 没在等：cancel 返回 null |
| :109 | `cancelAfterTheWaitFinishedIsStillNoop` | 收尾之后再点取消仍是 no-op |
| :125 | `eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak` | 每次 launch 新把手，旧取消不泄漏 |
| :146 | `clearBetweenWaitsIsHarmless` | clear 只丢引用、不取消 |

#### androidTest：`SourceSyncListenCancelScreenTest`（6 例，新文件）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :43 | `theCancelButtonIsThereButDisabledWhenNothingIsBeingWaitedFor` | 常驻按钮、文案固定、空闲时禁用 |
| :54 | `theCancelButtonBecomesUsableOnlyWhileReceiving` | 仅 `Receiving` 可点；同时「等一次发送」与「开始监听」被锁 |
| :66 | `tappingCancelWhileReceivingCallsBackExactlyOnce` | 点击回调只来一次 |
| :76 | `cancelStaysDisabledAfterAReceivedEnvelope` | 收下之后又禁用（取消不清结果） |
| :92 | `cancelStaysDisabledAfterAFailedWait` | 失败之后同样禁用 |
| :101 | `theCancelButtonSitsAtThePageTailAndTheMainFlowStaysOnTheFirstScreen` | 按钮在页尾、首屏主流程（选择文件/预览）不被挤走——W6-41 的回归守卫 |

### §34.7 本机实跑（串行，低内存）

| 步骤 | 命令/脚本 | 结果 |
| --- | --- | --- |
| 四任务全量 | `D:/mh-verify/run-w6-61d.bat`（`:core:common:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest`，`--max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" "-Dkotlin.daemon.jvm.options=-Xmx1024m"`） | `BUILD SUCCESSFUL in 3m 21s`、`217 actionable tasks: 10 executed, 207 up-to-date`、`EXIT=0` |
| 清理 | 跑完 `gradlew --stop` | `java-left=0`；仓库根 `hs_err_pid*.log` / `replay_pid*.log` 计数均 0 |

XML 逐套件汇总（`<testsuite>` 属性相加，非估算；日志 `D:/mh-verify/w6-61d.log`）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| `core/common` | 40 | **557** | 0 | 0 | 11 | 550 → 557（+7 `SourceSyncListenerCancelTest`） |
| `feature/settings` | 21 | **334** | 0 | 0 | 0 | 324 → 334（+3 控制器 +7 新 launcher 套件） |

关键套件逐个：`SourceSyncListenerCancelTest` 7/7（1.248 s）、`SourceSyncListenerControllerTest` 46/46（0.324 s，回归未破）、
`SourceSyncListenLauncherTest` 7/7（0.635 s）、`SourceSyncTransferCancelTest` 6/6（0.343 s，§33 的拨号侧未受影响）。
11 个跳过仍是 `SourceSyncTlsInteropTest` + `SourceSyncListenerInteropTest` 两个环境门控套件，与既有口径一致。

androidTest APK：`feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk` = **20758198 B**（`2026-10-25 18:22:49`）。
解包核对（先复制成 `.zip` 再 `Expand-Archive`，共 **20 个 dex**）：`SourceSyncListenCancelScreenTest`、
`theCancelButtonIsThereButDisabledWhenNothingIsBeingWaitedFor`、`tappingCancelWhileReceivingCallsBackExactlyOnce`、
`theCancelButtonSitsAtThePageTailAndTheMainFlowStaysOnTheFirstScreen` 全在 `classes18.dex`（117068 B），
tag `sync.listen.cancel` 同时出现在 `classes12.dex`（706552 B）与 `classes18.dex` —— 因此这个 APK 不是陈旧产物。

#### §34.7.1 断点记录：三次首红与真因（本节最值钱的部分）

**红 1（core，1 例运行时）**：`cancellingDuringABlockedReadAlsoYieldsTheLocalCode` 报
`org.junit.ComparisonFailure: expected:<[transfer-cancel]led> but was:<[io-fai]led>`（`SourceSyncListenerCancelTest.kt:137`）。

根因与 §33.7.1 的「红 2」是**同一个坑，只是换了一侧**：取消关 socket 后阻塞读抛的 `SocketException`
先被协议层 `SourceSyncTransfer.receive(...)` 自己 catch 并折成 `SourceSyncTransferResult.Failed(io-failed)` 返回，
`receiveOver` 的那两个 catch 压根不会被触发，所以「catch 首行先看 `isCancelled`」那层防御对这条路径是空的。

修法：在 `receiveOver` 里对 `receive(...)` 的 `is Failed` 分支加统一裁决
（`SourceSyncTransferListener.kt:265-270`）：
`if (cancel?.isCancelled == true) cancelledResult() else failed(received.reason, received.message)`。
两条约束：①取消在任何失败分类之前胜出；②**只改写失败结果**，已收全的信封不会被晚到的取消抹掉。

**红 2（feature/settings，3 例）**：`SourceSyncListenLauncherTest` 的 `launchStartsTheWaitAndMarksItInFlight` /
`aSecondLaunchWhileInFlightIsIgnored` / `eachLaunchGetsAFreshHandleSoAnOldCancellationDoesNotLeak` 报
`kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, there were active child jobs`。

根因不是产品缺陷，是**测试的作用域挂错**：Launcher 挂在 `this`（测试体作用域）上，
「故意挂着等取消」的 Job 成了测试体的子协程，`runTest` 收尾时判「还有活跃子协程」。
修法：7 处 `SourceSyncListenLauncher(this)` → `SourceSyncListenLauncher(backgroundScope)`
（`backgroundScope` 是 `TestScope` 成员，无需 import）。副作用是 `advanceUntilIdle()` 不再推进那边的活，
所以 `cancelAfterTheWaitFinishedIsStillNoop` 改用 `runCurrent()`。

**红 3（androidTest，编译期 1 例）**：`e: ...SourceSyncListenCancelScreenTest.kt:44:62 No value passed for parameter 'tls'`。
`SourceSyncListenState.Listening(port, tls, ...)` 的 `tls` **没有缺省值**，而 androidTest 里只写了 `port = 45_678`。
修法：`Listening(port = 45_678, tls = false)`。

三次红各自的性质值得记一笔：红 1 是**产品缺陷**（防御层覆盖不到协议层自己折的码），
红 2 是**测试脚手架缺陷**（作用域），红 3 是**编译期契约**（Kotlin 无缺省值参数）。
只有红 1 改了生产代码；后两次都只动测试。

### §34.8 未覆盖（本节交付时必须如实保留）

1. **真实两机**：取消时对端此刻看到 RST 还是 FIN 未取证，需要第二台机器与用户同意开防火墙 ⇒ NOT_RUN。
2. **TLS 握手中取消**：把手在 `accept` 成功那一刻 attach，握手阶段理论上也被覆盖，但**没有专门用例**卡「TLS 1.3 握手进行中」这个窗口。
3. **多连接并发**：`receiveOnce` 由 `acceptLock` 串行化，单次只能等一条连接；并发两路不在本里程碑范围。
4. **Hilt 注入与进程重建**：`SourceSyncListenLauncher` 由 ViewModel 持有，进程被杀后的恢复没有用例。
5. **设备执行**：本机无连接设备，androidTest 的 6 例只到「APK 里确实编进了这些用例」这一层 ⇒ DEVICE_UNVERIFIED；
   设备结论由新 head 的 CI visual-instrumentation 提供。
6. **视觉回归**：按钮在真机首屏之外（页尾第三卡），布局没有像素级基线。
7. **门禁**：`SourceSyncListenerCancelTest` 本轮已进 `--required-suite`（43 → **44** 个套件），
   本机 `python -m unittest discover -s .github/scripts/tests` 为 `Ran 54 tests` / `OK`；
   「CI 上这个套件确实被门禁跑过」只能由新 head 的 Android CI 日志证明，本节写就时该 head 尚未 push。

### §34.9 下一步

- ~~监听卡补「取消这一次接收」~~ —— 本节已完成（W6-61），§33.8 第 3 条与 §33.9 第 1 条同时失效；
- 配对页接二维码（W6-60 之后把「本机地址」直接生成二维码，省掉手抄）——NOT_RUN；
- 真实两机取消取证（§34.8 第 1 条），届时把 §33.8 第 1 条与本处第 1 条一起改成 EXECUTED_PASS。

## §35 Android 侧离线加密归档：读与写（W6-62）

> 本节记录把 Windows 的 `.mharchive`（ADR-S005）契约移植到 Android `core/common` 的实现与证据：
> 同一份归档，Windows 写的 Android 读得出来，Android 写的 Windows 也读得出来，十二个稳定码逐字一致。
> 这是缺口表里「离线加密导入导出」那一项在 Android 侧的从零补齐。

### §35.1 动机：Windows 早就有了，Android 侧一个文件都没有

Windows 侧 ADR-S005 已经落地了一整套离线加密归档（`windows/src/MediaHub.Sync/` 下的
`EncryptedArchiveWriter` / `EncryptedArchiveReader` / `ArchiveHeader` / `ArchiveKdf` /
`ArchivePassphrase` / `ArchiveError` 六个文件），CLI 也已经接上（`transfer export --passphrase`、
`transfer import --passphrase`，归档输入走 `ArchiveOrCredential = 4` 退出码）。

Android 侧此前是**零**：`core/common/src/main/kotlin/com/mediahub/core/common/sync/` 下三十个生产文件里
没有任何 `Archive*` 对等物，`grep -i "archive"` 在 sync 包零命中。
需要说清楚的是，`core/common/.../backup/` 里那套 `.mhbackup`（magic `MHBK`）**不是**这个东西——
它是整机灾难恢复格式，载荷是 manifest 加各 section 记录；而 `.mharchive` 的载荷是**双向同步信封**
（`SourceSyncCodec` 编出来的 `SourceSyncEnvelope`），用途是把一次同步快照加密搬到另一台机器上导入。
两套格式的差异在 §35.4 逐项列，代码里也分在两个包（`archive` / `backup`），不许混用。

W6-62 的目标因此定得很窄：**只做 `core/common` 里的纯内存读与写**，不接 CLI、不接界面、不碰数据库。
这个边界是从 Windows 侧照搬的——`EncryptedArchiveReader` 的类注释写明它是纯内存 API，不碰文件系统。

### §35.2 文件布局与契约表

归档的字节布局（与 `EncryptedArchiveWriter.cs:12` 的 remarks 逐字同构）：

```
magic(8) | headerLength(4, 大端) | headerJson | ciphertext | tag(16)
```

三个刻意的设计点，Android 侧一字不改地继承：

1. **magic 与长度在最前面**：不解密、不解析 header 就能把「这不是本产品的归档」拒掉；
2. **header 是明文 JSON**：回答「哪个版本 / 哪个 kdf / 迭代多少轮」不需要口令，
   支持场景里首先要问的正是这三件事；
3. **header 的精确字节就是 AAD**：想偷偷改迭代数、时间戳或 kdf 名字，tag 立刻对不上。

契约常量（`ArchiveHeader` / `ArchiveKdf` / `ArchivePassphrase` 三个伴生对象）：

| 项 | 值 | 定义位置 |
| --- | --- | --- |
| magic | `MHSYNCA1`（8 字节 ASCII） | `ArchiveHeader.kt:29` |
| 归档版本 | `1` | `ArchiveHeader.kt:30` |
| header 上限 | `4096` 字节 | `ArchiveHeader.kt:39` |
| 载荷上限 | `8 * 1024 * 1024` 字节 | `ArchiveHeader.kt:42` |
| GCM nonce | `12` 字节 | `ArchiveHeader.kt:33` |
| GCM tag | `16` 字节 | `ArchiveHeader.kt:36` |
| KDF 名 | `pbkdf2-sha256`（只认这一个，不回落） | `ArchiveKdf.kt:16` / `:29` |
| salt | `16` 字节 | `ArchiveKdf.kt:18` |
| 派生密钥 | `32` 字节 | `ArchiveKdf.kt:19` |
| 迭代下限 / 缺省 | `600_000` / `600_000` | `ArchiveKdf.kt:22-23` |
| 口令最短 | `12` 字符 | `ArchivePassphrase.kt:16` |
| AAD | `magic + 大端 int32(headerJson.length) + headerJson` 原字节 | `ArchiveWriter.kt:106`（`buildPrefix`） |

header 的 JSON 字段序固定、camelCase、无空白：
`version, kdf, iterations, saltBase64, nonceBase64, createdAtEpochMs`
（`ArchiveHeader.kt:53` 的 `serialize`）。Reader **不解码重排**——它认证的是盘上那些原字节，
所以「Kotlin 先 parse 再 serialize 回来再当 AAD」这种做法是错的，必须取原切片。

口令策略（`ArchivePassphrase.tryValidate`，:50）按序四条：
空或全空白 ⇒ `passphrase-empty`；**原始长度** < 12 ⇒ `passphrase-too-weak`；
trim 后命中 24 条弱口令表（忽略大小写）⇒ `passphrase-too-weak`；
单字符重复或全 ASCII 数字 ⇒ `passphrase-too-weak`。
弱表 24 条与 Windows `ArchivePassphrase.cs` 逐字同序（由用例 `the_passphrase_policy_is_identical_to_the_windows_twenty_four_word_deny_list` 钉住）。

### §35.3 判定次序：框架错误优先于口令错误

读侧的十步判定序（`ArchiveReader.layoutOf`，:84）。**这个顺序就是外部可观察的优先级**，
改顺序会让同一份坏文件报出不同的码：

| # | 条件 | 报的码 |
| --- | --- | --- |
| 1 | 长度 < 8 或前 8 字节不是 magic | `magic-mismatch` |
| 2 | 长度 < 12（magic + 4 字节长度都不够） | `corrupt-header` |
| 3 | headerLength ≤ 0、> 4096、或超出剩余字节 | `corrupt-header` |
| 4 | header JSON 解析失败（含不是对象、字段缺失、类型不符） | `corrupt-header` |
| 5 | 归档版本 ≠ 1 | `unsupported-version:<n>` |
| 6 | kdf 名不认识 | `unknown-kdf:<kdf>` |
| 7 | 迭代数 < 600 000 | `invalid-iterations` |
| 8 | salt / nonce 的 base64 解不出，或解出长度不是 16 / 12 | `corrupt-header` |
| 9 | 密文长度 ≤ 0 ⇒ `corrupt-ciphertext`；> 8 MiB ⇒ `payload-too-large` | 同左 |
| 10 | 走到这里：`aad = archive[0 .. trailer)` 的拷贝，交给解密 | — |

门禁式的两条：**先跑完 layout，再看口令**。Windows 的注释把这层意思写得很直白——
「so "this file is from a newer version" wins over "you typed nothing"」：
一个来自新版本的文件 + 空口令，用户更该看到的是「文件太新」，不是「你没输口令」。
Android 侧 `decrypt`（:166）第一行就是这句注释的同义实现：口令策略在 `deriveKey` 之前。

写侧五步（`ArchiveWriter.write`，:37）：

| # | 条件 | 报的码 | 证据用例 |
| --- | --- | --- | --- |
| 1 | 口令策略不过 | `passphrase-empty` / `passphrase-too-weak` | `empty-passphrase-wins`、`weak-passphrase-wins` |
| 2 | `capabilities.canSendSecrets` 或任一 source 带凭据块 | `secrets-not-allowed` | `secrets-flag-refused`、`secrets-block-refused` |
| 3 | 迭代数 < 600 000 | `invalid-iterations` | `low-iterations-refused` |
| 4 | 载荷序列化后 > 8 MiB | `payload-too-large` | `a_payload_that_is_not_an_envelope_is_malformed_payload`（读侧同源） |
| 5 | 全过：新随机 salt(16) + nonce(12) → header → 加密 → `prefix + 密文 + tag` | — | `happy-path` |

两条优先级是用例直接钉死的：`passphrase-before-secrets`（空口令 + 带凭据 ⇒ 报 `passphrase-empty`）与
`secrets-before-iterations`（带凭据 + 1000 轮 ⇒ 报 `secrets-not-allowed`）。
也就是说口令 → 凭据 → 迭代数这个次序不是实现巧合，是契约。
写侧对凭据的态度是**拒绝而不是静默剥掉**：`secrets-not-allowed` 让用户知道「这份东西本来就不该进归档」，
而不是悄悄改成一份缺凭据的信封让人以为搬成功了。

### §35.4 与 `.mhbackup` 的区别：两套格式不许混

`core/common/.../backup/` 里那套是本项目自己的整机备份格式，与本节的归档长得像但**没有一个字段可以互换**：

| 维度 | `.mharchive`（本节，W6-62） | `.mhbackup`（`core/common/.../backup/`） |
| --- | --- | --- |
| magic | `MHSYNCA1`（二进制前缀） | `MHBK`（JSON 里的 `magic` 字段） |
| 外层形态 | `magic + BE int32 + 明文 header + 密文 + tag` | 整份 JSON 信封（`ciphertextB64` 等字段） |
| salt | 16 字节 | 32 字节 |
| AAD | `magic + BE int32 + headerJson` 原字节 | 字符串 `"$magic|$formatVersion|$kdfAlgorithm|$kdfIterations"` |
| 载荷 | `SourceSyncCodec` 编出的双向同步信封 | manifest + 各 section 记录 |
| 用途 | 双向增量同步的离线搬运 | 整机备份 / 恢复 |
| 迭代下限 | 600 000 | 10 000（`MIN_ITERATIONS`） |

两套都用了 PBKDF2-HMAC-SHA256 + AES-GCM，但**参数与 AAD 形状完全不同**，所以
「拿 `.mhbackup` 的代码改改变成 `.mharchive`」这条路是不通的：
AAD 差一个字节，tag 就永远对不上。本节因此没有复用 `BackupCrypto` 的任何一行，只借了它的结论——
`javax.crypto` 的 `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding` 在 Android 上零依赖可用，不需要引入任何新库。

### §35.5 落点行号表

生产代码六个文件（`core/common/src/main/kotlin/com/mediahub/core/common/archive/`）：

| 文件 | 行 / 字节 | 内容 |
| --- | --- | --- |
| `ArchiveError.kt` | 82 / 3349 | `object ArchiveError` 十二个 const（:16 magic-mismatch、:19 corrupt-header、:22 unsupported-version、:25 unknown-kdf、:28 invalid-iterations、:31 passphrase-empty、:34 passphrase-too-weak、:37 secrets-not-allowed、:40 payload-too-large、:43 corrupt-ciphertext、:46 wrong-passphrase-or-tampered、:49 malformed-payload）、`ALL`（:52，十二项同序）、`unsupportedVersion(Int)`（:67）、`unknownKdf(String?)`（:69）、`ArchiveException(reason, message, cause?)`（:77） |
| `ArchiveKdf.kt` | 49 / 2120 | `PBKDF2_SHA256_NAME`（:16）、`SALT_SIZE`（:18）、`KEY_SIZE`（:19）、`MIN_ITERATIONS`（:22）、`DEFAULT_ITERATIONS`（:23）、`tryParse`（:29，只认一个名字、不回落）、`deriveKey`（:35，`require` 校验 salt 与迭代下限，`PBEKeySpec` + `finally clearPassword()`） |
| `ArchivePassphrase.kt` | 78 / 3159 | `MIN_LENGTH = 12`（:16）、`WEAK_PASSPHRASES` 二十四条（:22）、`tryValidate`（:50）、`validate`（:60）、`messageOf`（:66） |
| `ArchiveHeader.kt` | 114 / 4676 | `data class ArchiveHeader`（:20，六个字段全带缺省值）、六个契约常量（:29-42）、`ArchiveHeaderCodec.serialize`（:53）、`tryParse`（:66）、`parse`（:90）、三个私有取值器（:96 / :102 / :108） |
| `ArchiveReader.kt` | 253 / 11034 | `readHeader`（:31）、`tryReadHeader`（:38）、`read`（:41）、`tryRead`（:56）、`describe`（:64）、`Layout`（:77）、`layoutOf` 十步（:84）、`decrypt`（:166）、`decodePayload`（:203）、`decodeBase64`（:233）、`lastReason`（:249） |
| `ArchiveWriter.kt` | 129 / 5940 | `write`（:37，五步判定序）、`buildPrefix`（:106，`internal`，供用例逐字节对照）、`carriesSecrets`（:123） |

测试与资源：

| 文件 | 规模 | 内容 |
| --- | --- | --- |
| `core/common/src/test/kotlin/.../archive/ArchiveVectorsTest.kt` | 419 行 / 21429 B / **15 例** | 与 Windows 黄金向量逐字节对照：常量、十二码、三条 PBKDF2 密钥、header 161 B、archive 1282 B、19 条错误向量、9 条 writerCases、四条有效口令 + 九条无效口令 |
| `core/common/src/test/kotlin/.../archive/ArchiveRoundTripTest.kt` | 418 行 / 20056 B / **23 例** | 纯 Kotlin 回环与边界：逐字段往返、两次写不重复 salt/nonce/密文、篡改 tag/密文/header、双侧凭据拒绝、8 MiB 上限、口令 11 拒 12 收、中文口令、不泄露明文 |
| `core/common/src/test/resources/archive/windows-archive-vectors.json` | 294 行 / **49950 B** | 由 `D:/mh-verify/archive-vectors`（临时 net10.0 生成器，`ProjectReference` 指向权威 `MediaHub.Sync.csproj`）生成，不入 git 的生成器本身不在仓库里 |

### §35.6 用例清单

#### `ArchiveVectorsTest`（15 例，跨端对照）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :95 | `the_kdf_and_header_constants_match_the_windows_contract` | 六个常量与 kdf 参数逐项等于 Windows |
| :118 | `the_twelve_stable_codes_are_identical_and_in_the_same_order_as_windows` | 十二个码字符串与顺序 |
| :145 | `the_three_frozen_pbkdf2_keys_reproduce_byte_for_byte` | 三条冻结密钥（英文 / 第二 salt / 中文口令）逐字节相同 |
| :166 | `the_kdf_parser_rejects_everything_but_the_one_frozen_name` | `tryParse` 只认 `pbkdf2-sha256`，大小写与空格归一、其它一律 false |
| :177 | `the_header_serializer_reproduces_the_windows_bytes_exactly` | header 161 B 逐字节相同（字段序 + 无空白） |
| :200 | `kotlin_reproduces_the_windows_archive_byte_for_byte` | 用冻结 payload + salt + nonce，Kotlin 算出 1282 B / sha256 `17514e08…ee21` 与 Windows 完全相同 |
| :243 | `the_frozen_archive_decrypts_back_to_the_same_envelope` | 读回的信封逐字段相等 |
| :254 | `the_header_can_be_read_without_any_passphrase` | `readHeader` 不需要口令 |
| :268 | `every_error_vector_reports_the_reason_windows_reports` | 19 条错误向量逐条同码 |
| :291 | `every_error_vector_agrees_on_whether_the_header_parses` | 19 条逐条同 `headerParses` |
| :305 | `the_error_vectors_cover_every_bucket_a_reader_can_report` | 向量覆盖读到得了的每一种码，没有漏桶 |
| :331 | `every_writer_case_reports_the_reason_windows_reports` | 9 条写侧用例逐条同码 |
| :359 | `the_writer_case_order_proves_the_passphrase_then_secrets_then_iterations_precedence` | 口令 → 凭据 → 迭代数 |
| :378 | `the_passphrase_policy_agrees_with_every_frozen_candidate` | 四条有效 / 九条无效逐条判定一致 |
| :395 | `the_passphrase_policy_is_identical_to_the_windows_twenty_four_word_deny_list` | 弱表二十四条逐字同序 |

#### `ArchiveRoundTripTest`（23 例，纯 Kotlin）

| 行号 | 用例 | 钉住什么 |
| --- | --- | --- |
| :140 | `a_written_archive_reads_back_with_every_field_intact` | 逐字段往返（含 endpoint 三属性、accountScope、tombstone） |
| :162 | `an_empty_envelope_also_round_trips` | 空信封也能往返 |
| :168 | `writing_twice_never_repeats_salt_nonce_or_ciphertext` | 每次写新 salt / nonce，密文不同 |
| :180 | `the_reader_is_pure_and_can_be_called_repeatedly` | 读是纯操作，同一份可重复读 |
| :193 | `a_tampered_tag_byte_is_wrong_passphrase_or_tampered` | 改 tag 一个字节 ⇒ 同码 |
| :200 | `a_tampered_ciphertext_byte_is_wrong_passphrase_or_tampered` | 改密文一个字节 ⇒ 同码 |
| :208 | `a_tampered_header_timestamp_still_parses_but_never_decrypts` | 改 header 时间戳：header 仍可读，解密必失败（AAD 覆盖） |
| :224 | `a_tampered_version_reports_unsupported_version_before_any_passphrase_work` | 改版本号 ⇒ 框架错误先于口令 |
| :237 | `a_tampered_kdf_name_reports_unknown_kdf_with_the_name` | 改 kdf ⇒ 码里带原名字 |
| :244 | `a_tampered_iteration_count_is_refused_at_the_structural_layer` | 改迭代数 ⇒ `invalid-iterations` |
| :263 | `a_truncated_archive_never_reports_a_decryption_error` | 截断只报结构码，不冒充「口令错」 |
| :283 | `writing_refuses_an_envelope_that_declares_can_send_secrets` | 写侧拒 `canSendSecrets` |
| :288 | `writing_refuses_an_envelope_that_carries_a_secrets_block_even_when_the_flag_is_off` | 写侧拒凭据块（即使 flag 关） |
| :308 | `a_json_null_secrets_block_is_not_credentials_on_either_side` | `"secrets": null` 不算凭据（见 §35.7.1） |
| :318 | `reading_refuses_a_payload_that_declares_can_send_secrets` | 读侧拒 `canSendSecrets` |
| :326 | `the_passphrase_boundary_is_eleven_refused_and_twelve_accepted` | 11 拒 12 收 |
| :337 | `a_chinese_passphrase_round_trips_and_a_wrong_one_does_not` | 中文口令往返、错口令报同码 |
| :349 | `a_low_iteration_count_is_refused_by_the_writer_and_the_boundary_is_allowed` | 999_999 拒、600_000 收 |
| :363 | `a_wrong_passphrase_and_a_tampered_file_report_the_exact_same_code` | 错口令与篡改同码（不做攻击者 oracle） |
| :373 | `the_describe_texts_never_quote_the_payload_or_the_passphrase` | `describe` 文案不含载荷 / 口令 |
| :390 | `the_archive_bytes_never_contain_the_plaintext` | 归档字节里搜不到明文片段，但能找到 magic |
| :402 | `a_payload_that_is_not_an_envelope_is_malformed_payload` | 载荷不是信封 ⇒ `malformed-payload` |
| :409 | `a_payload_with_an_unknown_schema_version_stays_malformed_payload` | 载荷 schemaVersion 不是 1 ⇒ `malformed-payload` |

### §35.7 本机实跑（串行，低内存）

| 步骤 | 命令 / 脚本 | 结果 |
| --- | --- | --- |
| 单模块全量 | `D:/mh-verify/run-w6-62.bat`（`:core:common:testDebugUnitTest`，`--max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" "-Dkotlin.daemon.jvm.options=-Xmx1024m"`） | `BUILD SUCCESSFUL in 3m 19s`、`17 actionable tasks: 7 executed, 10 up-to-date`、`EXIT=0` |
| 清理 | 跑完 `gradlew --stop` | `java-left=0`；仓库根 `hs_err_pid*.log` / `replay_pid*.log` 计数均 0 |

XML 汇总（`<testsuite>` 属性相加，非估算；日志 `D:/mh-verify/w6-62.log`）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| `core/common` | 42 | **595** | 0 | 0 | 11 | 557 → 595（+2 套件 = 15 + 23） |

关键套件逐个：`ArchiveRoundTripTest` **23/23**（22.89 s）、`ArchiveVectorsTest` **15/15**（6.138 s，
其中 600 000 轮 PBKDF2 每个用例一次，时间主要花在这里）；
11 个跳过仍是 `SourceSyncTlsInteropTest` + `SourceSyncListenerInteropTest` 两个环境门控套件，与既有口径一致。

#### §35.7.1 断点记录：一个跨端契约上的真缺陷（`"secrets": null`）

首跑之前自查出来的，不是跑挂的——但性质比跑挂更严重，因为它只在一条路径上显形：

`ArchiveWriter.carriesSecrets` 第一版写的是 `envelope.sources.any { it.secrets != null }`。
Windows 权威实现的判据是 `source.Secrets is { } secrets && secrets.ValueKind != JsonValueKind.Null`——
也就是说，一个显式的 `"secrets": null` 块 **不算**携带凭据。
可是在 Kotlin 里 `JsonNull` 是个非 null 的对象，`it.secrets != null` 对它是 true。
后果是：同一份信封，Windows 说能写、Android 说 `secrets-not-allowed`——两端对「什么算凭据」判断不一致。

修法（`ArchiveWriter.kt:123`）：

```kotlin
envelope.sources.any { source ->
    val secrets = source.secrets;
    secrets != null && secrets !is kotlinx.serialization.json.JsonNull
}
```

为什么只有一条路径暴露：`SourceSyncCodec.readRecord` 已经把 `element["secrets"]?.takeIf { it !is JsonNull }`
折成了 Kotlin 的 null（`SourceSyncCodec.kt:183`），所以从 JSON 解码进来的信封碰不到这个 bug；
只有「用 Kotlin 对象直接构造信封再写」这条路才会踩到。用例
`a_json_null_secrets_block_is_not_credentials_on_either_side` 现在钉住两端同判。

### §35.8 未覆盖（本节交付时必须如实保留）

1. **没有接 CLI / 界面 / 数据库**：`ArchiveReader` 与 `ArchiveWriter` 目前是 `core/common` 里的纯函数，
   Android 侧还没有任何调用方。把它接到「导入信封」那条链上（`SourceSyncTransferImportService.importEnvelopeBytes`）
   是下一个切片的活，本节不算完成端到端。
2. **没有真实设备**：全部结论来自 JVM 单测；Android 上 `javax.crypto` 的实现（Conscrypt）与 JVM 的 SunJCE
   在 AES-GCM 与 PBKDF2 上理应一致，但**没有在设备上跑过** ⇒ DEVICE_UNVERIFIED。
3. **8 MiB 上限只测了写法没测真数据**：上限判定在两侧都有，但没有构造一份接近 8 MiB 的真实归档跑内存与耗时；
   低内存设备上 600 000 轮 PBKDF2 与 8 MiB 明文的峰值内存没有测过。
4. **跨端双向互操作只到「读 Windows 的向量」**：Kotlin 写出的归档由 C# 读这一半，本轮的证据是
   「Kotlin 复算出的字节与 Windows 冻结字节完全相同」（`kotlin_reproduces_the_windows_archive_byte_for_byte`），
   属于**等价证明**而非真的跑了一次 C# Reader 去读 Kotlin 的输出。生成器里没有做这一半，是因为
   它要往 Windows 工程里加测试入口；等价证明在密码学上没有信息损失，但口径上要记成「互操作 = 逐字节等价」。
5. **真实两机搬运**：把归档拷到另一台机器再导入的完整流程 NOT_RUN（与 §33.8 / §34.8 的跨机项同源，都需要第二台机器）。
6. **口令策略的界外情况**：只有「11 拒 12 收」与中文口令；没有测超长口令（数千字符）与含 NUL / 代理对的字符串。
7. **门禁**：本轮把两个套件都加进 `--required-suite`（44 → **46** 个套件），
   本机 `python -m unittest discover -s .github/scripts/tests` 为 `Ran 54 tests` / `OK`；
   「CI 上这两个套件确实被门禁跑过」只能由新 head 的 Android CI 日志证明，本节写就时该 head 尚未 push。

### §35.9 下一步

- 把 `ArchiveReader` 接到 Android 的导出 / 导入入口：导出侧给「另存为 .mharchive」，
  导入侧在 `SourceSyncTransferImportService` 前面加一层「这是不是归档」的嗅探（`magic == MHSYNCA1`），
  对齐 Windows `SyncCliRunner.TryResolveEnvelope` 的分支语义；
- 设备侧取证：把 15 + 23 例里能在 androidTest 上跑的部分搬上模拟器（PBKDF2 600 000 轮在低端机上耗时是可观察项）；
- 真实两机搬运取证（上面 §35.8 第 5 条），届时与 §33.8 / §34.8 的跨机项一起改成 EXECUTED_PASS；
- 口令策略补超长口令与畸形 Unicode 用例。


## §36 加密归档接进 Android 导入入口（W6-63）

### §36.1 动机：读端交付了，但没有调用方

§35.8 第 1 条如实记着：「`ArchiveReader` 与 `ArchiveWriter` 目前是 `core/common` 里的纯函数，
Android 侧还没有任何调用方。把它接到『导入信封』那条链上是下一个切片的活。」
本节补的就是这条缝：**不新造一条导入流程**，而是让既有那条（选文件 → 预览 → 逐条允许 → 应用）
多认一种输入——离线加密归档 `.mharchive`。

边界仍然照 W6-62 的写法：只做「读进来并交给既有网关」，不做导出侧的「另存为归档」、
不给传输链路口令通道、不碰数据库。

### §36.2 分支语义：对齐 Windows `SyncCliRunner.TryResolveEnvelope`

Windows 的权威语义在 `windows/src/MediaHub.SyncCli/SyncCliRunner.cs` L2159-2217：
先看前 8 字节是不是 `MHSYNCA1`，是归档且没带 `--passphrase` 就报 `passphrase-required`；
带了口令就走 `EncryptedArchiveReader.Read`，`ArchiveException` 的 `Reason` 原样当稳定码输出。
Android 侧的分流是同一个顺序，落在一处纯函数 `SourceSyncDocumentInput.resolve` 里：

| 输入 | 返回 | 外部可观察结果 |
| --- | --- | --- |
| 长度 < 8 或前 8 字节不是 `MHSYNCA1` | `Plaintext` | 走既有明文路径（宽松 UTF-8 → codec → 网关），与 W6-23 逐字节相同 |
| 是归档，调用方没给口令 | `PassphraseRequired` | `passphrase-required`（本地闸，网关零调用） |
| 是归档 + 口令，但结构坏 | `Rejected` | `magic-mismatch` / `corrupt-header` / `unsupported-version:<n>` / `unknown-kdf:<k>` / `invalid-iterations` / `corrupt-ciphertext` / `payload-too-large` |
| 是归档 + 口令，口令本身不合策略 | `Rejected` | `passphrase-empty` / `passphrase-too-weak`（在建密钥之前就拒，白跑不了一次 PBKDF2） |
| 是归档 + 口令，解密或认证失败 | `Rejected` | `wrong-passphrase-or-tampered`（错口令与密文被改同码，不做攻击者 oracle） |
| 是归档 + 口令，解开了但载荷不是合法信封 | `Rejected` | `malformed-payload` |
| 是归档 + 口令，信封宣称携带凭据 | `Rejected` | `secrets-not-allowed`（写侧 W6-62 也拒，两侧同义） |
| 是归档 + 口令，成功 | `Decrypted` | 用 `SourceSyncCodec.encode` 重新编码成明文 JSON，再走同一条 codec → 网关路径 |

两条与 Windows 的**有意差异**，都写在这里免得后人当成漏做：

1. **没有退出码分类**：Windows 把归档失败归成 `CliExitCode.ArchiveOrCredential`（4）、把 `passphrase-required` 归成 `InputProblem`（2），
   因为 CLI 的退出码是给脚本判断「换参数重试还是换文件」用的。Android 没有退出码，码本身就是分类；
   `passphrase-required` 与 `malformed-json` 一样属于「这份输入还缺一步」的本地闸，不是归档结构码。
2. **没有 `archive-not-supported-by-plan`**：Windows 的 `plan` 子命令只读明文信封，所以要专门拒一次归档。
   Android 的导入页只有一条路，预览发生在解密**之后**，这条分支没有调用方，就不造一个没人报的码。

### §36.3 三处落点的分工：为什么嗅探在 core:database、暂存在控制器、口令在卡片里

**嗅探与解密放 `core:database`**（`SourceSyncDocumentInput`），因为两个入口都汇到这里：
拨号路 `SourceSyncTransferImportService.receiveAndImport` 与监听路 `SourceSyncListenerImportService` 最终都调
`importEnvelopeBytes`。加在它前面一处，两条路同时得到归档支持，不必改两遍。

**暂存密文放 ViewModel 的控制器里**（`SourceSyncArchiveController`），不放 `SourceSyncImportState`。理由有三条：
state 是 `StateFlow`，会被 Compose 反复重组、被测试整份比较、也可能被日志打印；
密文留在控制器的私有字段里，对外只暴露 `pendingByteCount` 一个长度。
第三条是生命周期：重选文件、点重置、离开页面都必须把暂存丢掉，控制器正好有 `clear()` 这个点。

**口令只活在卡片分支的局部 `remember` 里**：不写 StateFlow、不落盘、不进日志。
点「解密」时它被原样交给 ViewModel，ViewModel 在 `Dispatchers.Default` 上跑 PBKDF2（600 000 轮），
用完即弃——卡片一消失（解开、重选、离开页面）那份字符串就随组合一起被丢弃。
把关卡做成「常驻但按不动的按钮」而不是隐藏按钮，是为了避免用空口令白跑一次 600 000 轮密钥派生。

### §36.4 三条外部可观察语义

1. **没口令不猜**：归档 + 无口令 ⇒ `passphrase-required`，网关一次都不会被调（零写入的第一道闸）。
   这条与 W6-30 的 `delivery-unconfirmed` 是同一种态度：宁可报「我缺东西」，不假装成功。
2. **错口令与篡改同码**：`wrong-passphrase-or-tampered`。GCM 的 tag 校验天然分不出这两者，
   伪造出区别就等于给攻击者一个 oracle；说法与 W6-62 §35.3 完全一致。
3. **明文路径一个字节都没变**：`plaintextEnvelopeKeepsTheExactTextPath` 断言网关收到的是
   `bytes.toString(Charsets.UTF_8)` 的同一个字符串（不是重新编码的等价物）。
   既有 W6-23 的 19 例包括「0xB1 这类非法 UTF-8 ⇒ malformed-json」，全部仍然绿。

### §36.5 落点行号表

| 文件 | 行 | 内容 |
| --- | --- | --- |
| `core/database/.../sync/SourceSyncDocumentInput.kt` | :19 | `object SourceSyncDocumentInput`（新文件，74 行） |
| 同上 | :22 | `PASSPHRASE_REQUIRED = "passphrase-required"` |
| 同上 | :27 | `looksLikeArchive`：逐字节比 8 字节 magic，短于 8 字节直接 false |
| 同上 | :36 | `resolve(bytes, passphrase)`：四分支 Plaintext / Decrypted / PassphraseRequired / Rejected |
| 同上 | :45 | `ArchiveReader.tryRead`；:47 失败时 `reason ?: MALFORMED_PAYLOAD` |
| 同上 | :50 | 成功时 `SourceSyncCodec.encode(envelope)`，runCatching 兜底 `malformed-payload` |
| 同上 | :61 | `sealed interface SourceSyncDocumentResolution`（四个实现 :64/:67/:70/:73） |
| `core/database/.../sync/SourceSyncTransferImportService.kt` | :130 | 原 `importEnvelopeBytes(bytes)` 保留签名，转调带口令重载（既有 5 处调用点零改动） |
| 同上 | :143 | 新 `importEnvelopeBytes(bytes, passphrase: String?)`：按 resolve 四分支分派 |
| 同上 | :161 | `private suspend fun deliverText(json)`：原来的 tryDecode → EnvelopeRejected / Imported 原样搬进来 |
| `feature/settings/.../sync/SourceSyncArchiveController.kt` | :11 | `SourceSyncArchivePrompt(visible, fileName, error)`（新文件，126 行） |
| 同上 | :18 | `SourceSyncArchiveCopy`：HINT / LABEL / ACTION / `errorOf(reason)` |
| 同上 | :37 | `fun interface SourceSyncArchiveUnlockPort`（单方法，替身测试不跑 PBKDF2） |
| 同上 | :53 | `class SourceSyncArchiveController`；:71 `offer`、:88 `unlock`、:120 `clear` |
| `feature/settings/.../sync/SourceSyncImportViewModel.kt` | :163 | 控制器组装（port 直连 `SourceSyncDocumentInput.resolve`） |
| 同上 | :176 | `val archivePrompt: StateFlow<SourceSyncArchivePrompt>` |
| 同上 | :197 | `fun loadPickedDocument(bytes, fileName)`：非归档就直接当明文走既有 loadEnvelope |
| 同上 | :210 | `fun unlockArchive(passphrase)`：`Dispatchers.Default` 上跑解密 |
| 同上 | :322 / :338 | `reset()` / `onCleared()` 里 `archiveController.clear()` |
| `feature/settings/.../sync/SourceSyncImportSession.kt` | :61 | `fun requirePassphrase(reason, message, fileName)`：整份置 BLOCKED，密文绝不进 envelopeJson |
| `feature/settings/.../sync/SourceSyncImportLogic.kt` | :51 / :54 | `PASSPHRASE_REQUIRED` / `UNLOCK_FAILED` 两条中文文案；:76-77 `blockedText` 返回自身 |
| `feature/settings/.../sync/SourceSyncImportScreen.kt` | :197-206 | 四个新 tag：`sync.import.passphrase.card` / `sync.import.passphrase` / `sync.import.unlock` / `sync.import.passphrase.error` |
| 同上 | :220 / :224 | Route 收集 `archivePrompt`；picker 改调 `readPickedDocument` |
| 同上 | :299-301 | 两个新形参都带缺省值 ⇒ 既有调用点与 11 个 androidTest 零改动 |
| 同上 | :327 | 说明句补「或加密归档（.mharchive，需要导出时设置的口令）。」 |
| 同上 | :368-418 | 口令卡：HINT + 密码遮罩输入框 + 「解密」按钮（空口令禁用）+ 错误行 |
| 同上 | :1338 | `readPickedDocument` 改成 `stream.readBytes()`（原 `readEnvelope` 只读文本，二进制归档会被读坏，已删除） |

### §36.6 用例清单

**A. `SourceSyncArchiveImportTest`（core/database，12 例，进 `--required-suite`）**

| 行 | 用例 | 断言 |
| --- | --- | --- |
| :28 | `looksLikeArchiveOnlyMatchesTheEightByteMagic` | 归档 true；空数组 / `"MHSYNC"` / 明文信封 false |
| :36 | `windowsArchiveIsDecryptedAndHandedToTheGatewayAsPlainEnvelope` | Windows 写的 1282 B 归档解开后网关收到明文；解码结果与明文件**结构化相等**；`canSendSecrets == false`；3 条来源 + 1 条墓碑 |
| :55 | `archiveWithoutAPassphraseIsRejectedWithTheStableCode` | `passphrase-required`；网关 0 调用 |
| :69 | `wrongPassphraseReportsTheArchiveCodeAndNeverCallsTheGateway` | `wrong-passphrase-or-tampered`；0 调用 |
| :81 | `aTamperedCiphertextReportsTheSameCode` | 翻掉最后一字节（tag 区）⇒ 同一个码 |
| :97 | `aPassphraseBelowThePolicyIsRejectedBeforeAnyKeyIsDerived` | `"short"` ⇒ `passphrase-too-weak`；0 调用 |
| :109 | `aTruncatedArchiveReportsItsStructuralCode` | 截到 20 B ⇒ `corrupt-header`（结构错不冒充口令错） |
| :122 | `bytesThatOnlyStartWithTheMagicAreRejectedAsACorruptArchive` | 只有 magic 的 16 B ⇒ `corrupt-header` |
| :136 | `bytesShorterThanTheMagicAreTreatedAsPlaintext` | `"MHSYNC"` 六字节 ⇒ 明文路径 ⇒ `malformed-json` |
| :148 | `plaintextEnvelopeKeepsTheExactTextPath` | 网关收到 == `String(bytes, UTF_8)`，明文路径零变化 |
| :162 | `aLocallyWrittenArchiveRoundTripsThroughTheSamePath` | 用 `ArchiveWriter.write` 现造归档 ⇒ 同一入口解开 ⇒ 信封结构相等 |
| :180 | `acceptOnlyGatewayEchoesTheDecryptedIdentityWithoutWriting` | `acceptOnly` 网关回显 deviceId / snapshotId / 64 字符内容指纹；零写入 |

**B. `SourceSyncArchiveControllerTest`（feature/settings，8 例）**

| 用例 | 断言 |
| --- | --- |
| `plaintextIsNotOfferedAndLeavesNoPrompt` | 非归档 ⇒ `offer` false、卡片不可见、不碰端口 |
| `anArchiveShowsThePromptAndAsksForThePassphrase` | 归档 ⇒ 卡片可见、带文件名、`onBlocked(passphrase-required)` |
| `aCorrectPassphraseHandsBackTheEnvelopeAndDropsTheArchive` | 成功 ⇒ 回调解出信封、卡片收起、暂存清空 |
| `aWrongPassphraseKeepsThePromptAndPutsTheArchiveCodeOnTheErrorLine` | 失败 ⇒ 卡片保持、错误行含 ArchiveError 原码、主文案是 UNLOCK_FAILED |
| `aBlankPassphraseNeverReachesThePort` | 空白口令不调端口（不白跑 PBKDF2） |
| `unlockWithoutAnOfferedArchiveDoesNothing` | 没暂存时 `unlock` 是 no-op |
| `clearDropsTheStashedArchiveAndThePrompt` | `clear()` 同时丢密文与卡片 |
| `pickingAnotherFileClearsThePreviousPrompt` | 重选明文文件 ⇒ 上一份归档的提示被清掉 |

**C. `SourceSyncPassphraseScreenTest`（feature/settings androidTest，5 例）**

| 用例 | 断言 |
| --- | --- |
| `noPassphraseCardIsRenderedWhenThePickerDidNotSeeAnArchive` | 明文路径不出现任何口令控件，首屏 PICK/PREVIEW 仍可见 |
| `anArchiveShowsTheCardWithItsFileNameAndADisabledUnlockButton` | 卡片与输入框在；空口令时「解密」按不动；错误行不存在 |
| `typingAPassphraseEnablesUnlockAndTappingItHandsTheTextBack` | 输字后按钮可用；点击把口令**原样**交回（`listOf("correct horse battery staple")`） |
| `aRejectedPassphraseKeepsTheCardOpenAndShowsTheReasonCode` | 错误行显示 `wrong-passphrase-or-tampered` |
| `thePassphraseCardSitsAboveThePreviewBlockAndKeepsTheMainFlowOnTheFirstScreen` | 不滚动：口令卡与首屏 PICK/PREVIEW 同时可见（W6-41 的首屏纪律没被破坏） |

### §36.7 本机实跑（串行，低内存）

| 步骤 | 命令 / 脚本 | 结果 |
| --- | --- | --- |
| 三模块单测 | `D:/mh-verify/run-w6-63.bat`（`:core:common:testDebugUnitTest :core:database:testDebugUnitTest :feature:settings:testDebugUnitTest`，`--max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" "-Dkotlin.daemon.jvm.options=-Xmx1024m"`） | `BUILD SUCCESSFUL in 6m 53s`、`167 actionable tasks: 26 executed`、`EXIT=0` |
| androidTest 编译 | `assembleDebugAndroidTest`（同一低内存参数） | `BUILD SUCCESSFUL in 3m 26s`、`EXIT=0` |
| 门禁脚本自测 | `python -m unittest discover -s .github/scripts/tests` | `Ran 54 tests` / `OK` |
| 清理 | 跑完 `gradlew --stop` | `java-left=0`；仓库根 `hs_err_pid*.log` / `replay_pid*.log` 计数均 0 |

XML 汇总（`<testsuite>` 属性相加，非估算；日志 `D:/mh-verify/w6-63.log`）：

| 模块 | 套件 | 用例 | 失败 | 错误 | 跳过 | 变化 |
| --- | --- | --- | --- | --- | --- | --- |
| `core/common` | 42 | 595 | 0 | 0 | 11 | 不变（11 个跳过仍是两个环境门控套件） |
| `core/database` | 19 | **184** | 0 | 0 | 12 | 172 → 184（+12 = W6-63 的 `SourceSyncArchiveImportTest`） |
| `feature/settings` | 22 | **342** | 0 | 0 | 0 | 334 → 342（+8 = W6-63 的 `SourceSyncArchiveControllerTest`） |

关键套件逐个：`SourceSyncArchiveImportTest` **12/12**（14.60 s，主要花在 4 次真 PBKDF2 上：
解开归档 2.25 s、错口令 2.91 s、篡改 2.48 s、本地写+读 4.70 s）、
`SourceSyncArchiveControllerTest` **8/8**（0.043 s，端口是替身，不跑 PBKDF2）。

### §36.8 未覆盖（本节交付时必须如实保留）

1. **真实两机搬运**：把归档拷到另一台机器再导入的完整流程 NOT_RUN（同 §33.8 / §34.8 / §35.8 第 5 条，都需要第二台机器）。
2. **设备侧密码学**：全部结论来自 JVM；Android 上 `javax.crypto` 由 Conscrypt 实现，PBKDF2 600 000 轮在低端机上的耗时
   与峰值内存未测 ⇒ DEVICE_UNVERIFIED。
3. **传输路没有口令通道**：`receiveAndImport` 与监听路调的是单参重载（`passphrase = null`），
   所以线上真收到一份归档只会得到 `passphrase-required` 而不是解开它。这是**有意**的：口令不过网。
   要支持「对端发归档」就得在线协议里开口令通道，那是另一个安全评审的活，本轮不做。
   也因此「归档在真实 socket 上跑过」这条证据不存在——归档的端到端只有文件选择器这一条路。
4. **导出侧没有做**：「另存为 .mharchive」零实现，`ArchiveWriter.write` 目前只有测试调用方。
5. **8 MiB 上限**：继承 §35.8 第 3 条，只测了判定写法没测接近上限的真数据。
6. **口令的界外情况**：继承 §35.8 第 6 条（超长口令、NUL、代理对）。界面上也没有「显示口令」开关、
   没有剪贴板粘贴的专项验证。
7. **门禁**：本轮把 `SourceSyncArchiveImportTest` 加进 `--required-suite`（46 → **47** 个套件），
   本机 `python -m unittest discover -s .github/scripts/tests` 为 `Ran 54 tests` / `OK`。
   CI 侧已在 head `6857bdd` 上落实：Android CI run **40215577317** success，日志实计
   `--required-suite com.` **47** 行（末两条 `ArchiveRoundTripTest` → `SourceSyncArchiveImportTest`，
   紧接 `--require-complete-suites`），`uniqueTests 2246` 且 `core/database` 报 **184** 例。
8. **instrumentation**：新增 5 例 Compose 断言本机无设备/模拟器可跑，设备侧证据来自 CI：
   `6857bdd` 的 API 32 / API 36 两个作业里 `:feature:settings:connectedDebugAndroidTest`
   **Starting 105 → Finished 105**、0 FAILED，作业末断言 `API 32/36: 19 unique mandatory visual/parser tests passed`
   命中该 SHA；真机仍 DEVICE_UNVERIFIED。

### §36.9 下一步

- 导出侧：「另存为 .mharchive」（`ArchiveWriter.write` 已有写端，缺的是导出页与文件落盘）；
- 设备侧取证：把 PBKDF2 600 000 轮的耗时搬到模拟器上量一次（低端机是主要风险面）；
- 真实两机搬运取证（§36.8 第 1 条），届时与 §33.8 / §34.8 / §35.8 的跨机项一起改成 EXECUTED_PASS；
- 若产品上要「对端直接发归档」，先写一份安全评审：口令怎么进、超时多久、失败怎么告诉用户不泄露信息。
