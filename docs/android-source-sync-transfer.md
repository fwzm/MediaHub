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

- 设置 → 数据同步 → 导入页（`SourceSyncImportScreen`）正文第一个引导文案之后、文件选择行之前，新增一张「从 Windows 接收」卡片：调用点是 `feature/settings/src/main/kotlin/com/mediahub/feature/settings/sync/SourceSyncImportScreen.kt:192`（`ReceiveSection`），实现从 `:535` 开始。
- 三个输入框 + 一个按钮 + 一行结果：host（默认 `127.0.0.1`，testTag `sync.receive.host`，`:568`）、port（数字键盘，默认空，`:579`）、挑战（可留空，`:589`）、按钮（`:596`）、结果行（`:623`）。testTag 常量集中在 `SourceSyncImportTags`（`:94`–`:105`）。
- Route 在 `:116` 收集 `viewModel.receiveState`，`:137` 传进 `Screen`；`Screen` 的两个新参数带默认值（`:163`），既有调用点与 androidTest 不需要改。
- ViewModel：`feature/settings/.../sync/SourceSyncImportViewModel.kt:57` 构造控制器（生产端口 `SourceSyncTransferReceivePort`），`:63` 暴露 `receiveState`，`:90` 的 `receive(form)` 把协程挂到 `viewModelScope`。

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
