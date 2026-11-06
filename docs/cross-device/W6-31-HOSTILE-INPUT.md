# W6-31 传输层恶意/畸形帧的安全回归（headless）

**状态：IMPLEMENTED_UNVERIFIED** —— 代码与 36 个新用例已实现并全绿（windows/tests/MediaHub.Tests 全量 1424 例通过、0 失败），
但本轮**没有** GUI 证据、**没有**与 Android 端的联调证据、**没有**跨机器证据。所有结论都来自同机回环的真实套接字与手搓帧字节。

- 日期：2026-10-24
- 分支/工作树：feature/cross-device-source-sync @ D:/mh-w6（HEAD 1cb94e9，W6-30 之后）
- 前置：W6-16（帧协议）、W6-24（tag 帧与 5 个 auth 码）、W6-26（TLS 1.3 与 4 个 tls 码）、W6-30（送达确认与 1 个 delivery 码）
- 范围：只加测试与文档；产品代码只做 1 处最小加固（第 7 节），**不改线格式、不改既有码语义、不改默认行为**

---

## 1 这些用例要证明的四条性质

每一个用例都必须同时满足下列四条，缺一条就算失败。这是本任务与「随便发点垃圾字节看会不会崩」的区别：

1. **稳定码**：对端被拒绝时返回的 `reason` 必须落在四个**封闭**码集之一的并集里
   （W6-16 十码 / W6-24 五码 / W6-26 四码 / W6-30 一码，共 20 个字符串）。集合外的码、空码、
   或者「抛异常代替返回码」都算契约破坏——调用方（同步页、CLI、测试）靠这些字符串分支。
2. **零写入**：被拒绝的轮次结束后，接收目录里 `sync-sources.json` 与 `sync-baseline.json`
   **两个文件都不存在**。不是「写入后又回滚」，也不是「写了但字段为空」，而是根本没落过盘。
3. **有时限**：单例必须远小于夹具预算（5 s）。恶意输入不得让接收端一直持有连接；实测最慢一例
   1 s（慢速滴流，预算 1.5 s 主动超时）。
4. **不抛异常**：`ReceiveOnceAsync` / `ReceiveAndImportAsync` 必须**返回**结果，不能把异常抛给调用方。
   在 CLI 里 `transfer serve` 没有外层 catch，一个逃出去的异常就是进程非零退出加半截 stderr。

## 2 威胁模型

| 攻击者能力 | 本任务覆盖 | 说明 |
| --- | --- | --- |
| 同机任意进程连 `127.0.0.1:<port>` 投递任意字节 | 是 | 回环端口不认证连接来源，只认证（可选）口令与证书 |
| 自造任意「合法帧」（长度前缀、kind、total/offset） | 是 | 用例逐条构造，不经过 `SendAsync` |
| 连接后立刻关闭 / 半关 / RST / 慢速滴流 | 是 | RST 与滴流是唯一能影响「是否一直等下去」的两类行为 |
| 与好连接混在同一监听上（噪声淹没） | 是 | 8 坏 + 1 好，好连接必须仍然成功 |
| 读取/篡改别人的流量 | 否（W6-26 覆盖） | TLS 1.3 + 指纹钉扎是那一层的事 |
| 冒充已配对设备 | 否（W6-24 覆盖） | HMAC 挑战应答 + nonce 防重放是那一层的事 |
| 跨机器/跨进程长期重放 | 否 | 见第 10 节 |

**被保护的资产**：接收端的两份落盘文件（同步源清单与基线）。攻击者能从这次传输里拿到的最大收益是
「让接收端写进一条自己没批准过的来源」，或者「让服务端卡死/崩溃」。本任务就是要证明这两件事都做不到。

## 3 测试夹具

`windows/tests/MediaHub.Tests/SourceSyncHostileInputTests.cs`，一个类 36 个 `[Fact]`，全部走**真套接字**：

- 每例新建一个临时目录，里面 `FileLocalSourceStore` + `FileSourceSyncBaselineStore` + `SourceSyncInbox`，
  writer 把 `SourceRecord` 转成 `LocalSourceRecord` 落盘并计数（用来断言「写了几条」）。
- `SourceSyncTransferListener.Start(127.0.0.1, 0)` 起监听（**明文**路径，TLS 路径由 W6-26 的用例覆盖），
  然后**先**挂上接收任务，**再**用 `TcpClient` 连过去，由用例直接往 `NetworkStream` 写手搓字节。
- 帧构造助手：`LengthPrefix(int)`（4 字节大端）、`Frame(kind, body)`、`ChunkFrame(total, offset, payload)`、
  `ChallengeFrame(text)`、`Chunked(payload)`（按真实发送端的 8183 字节切块）。
- 断言助手：`AssertRefused(case, 期望码, detail 片段)` 统一检查稳定码、`Writes == 0`、两个文件都不存在、
  耗时 < 5 s。

一条容易写错、也确实错过一次的细节：接收端调用必须把 `options` 传到最后那个位置
（`ReceiveAndImportAsync(..., timeout, cancellationToken, options)`）。漏传会让「认证开启」的用例
悄悄退化成「认证关闭」，于是 `auth-missing` 变成 `envelope-invalid`、`auth-mismatch` 变成成功导入——
现象看着像产品缺陷，实际是用例没接上。

## 4 用例矩阵（输入 → 期望码 → 实测码）

共 36 例，按任务书的十类攻击面组织。全部实测通过；「零写入」一列全部为「是」（断言两个文件都不存在）。

### 4.1 声明长度巨大后断开 / 长度前缀本身非法

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `A_frame_length_of_two_gigabytes_then_disconnect_is_frame_truncated` | 长度前缀 `0x7FFFFFFF` 后断开 | `frame-truncated` |
| `A_zero_length_frame_header_is_frame_truncated` | 长度前缀 `0x00000000` | `frame-truncated` |
| `A_negative_length_frame_header_is_frame_truncated` | 长度前缀 `0xFFFFFFFF`（读成 −1） | `frame-truncated` |

### 4.2 信封总长超过 8 MiB 上限

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `A_chunk_announcing_more_than_the_envelope_ceiling_is_payload_too_large` | total = 8 MiB + 1 | `payload-too-large` |
| `An_envelope_one_byte_over_the_ceiling_is_payload_too_large` | 单块 total = 8388609 | `payload-too-large` |

### 4.3 块结构（total/offset/顺序/重复/越界）

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `A_chunk_announcing_zero_bytes_is_frame_invalid` | total = 0 | `frame-invalid` |
| `A_negative_chunk_total_is_frame_invalid` | total = −5 | `frame-invalid` |
| `A_negative_chunk_offset_is_frame_invalid` | offset = −1 | `frame-invalid` |
| `A_first_chunk_that_does_not_start_at_zero_is_frame_invalid` | 首块 offset = 8 | `frame-invalid` |
| `A_chunk_that_changes_the_announced_total_mid_envelope_is_frame_invalid` | 16 之后说 32 | `frame-invalid` |
| `A_chunk_that_skips_bytes_is_frame_invalid` | 收 4 字节后说 offset = 8 | `frame-invalid` |
| `A_chunk_that_runs_past_the_announced_total_is_frame_invalid` | total 8，第二块写 8 字节 | `frame-invalid` |
| `A_repeated_chunk_is_frame_invalid` | 同一 offset 0 重复 | `frame-invalid` |
| `A_chunk_frame_without_a_payload_is_frame_invalid` | 只有 8 字节块头 | `frame-invalid` |

### 4.4 挑战帧（位置/重复/内容）

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `A_challenge_frame_after_a_data_chunk_is_frame_invalid` | 块之后来挑战帧 | `frame-invalid` |
| `A_second_challenge_frame_is_frame_invalid` | 两个挑战帧 | `frame-invalid` |
| `A_challenge_frame_with_no_text_is_frame_invalid` | 空挑战帧 | `frame-invalid` |
| `A_challenge_that_differs_from_the_invitation_is_challenge_mismatch` | 邀请要求 nonce A，对端给 B | `challenge-mismatch` |
| `A_data_chunk_without_the_announced_challenge_is_challenge_mismatch` | 邀请要求 nonce，对端直接发块 | `challenge-mismatch` |
| `A_challenge_with_nul_and_non_ascii_characters_is_accepted_and_the_payload_is_still_rejected` | 挑战含 NUL 与中文 | `envelope-invalid`（2 帧） |
| `A_challenge_longer_than_the_sending_ceiling_is_accepted_by_the_receiver` | 513 字节挑战 | `envelope-invalid`（见第 9 节裁决项 1） |

### 4.5 未知帧类型

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `An_unknown_frame_kind_is_frame_invalid` | kind `0x7F` | `frame-invalid` |
| `An_unknown_frame_kind_zero_zero_four_is_frame_invalid` | kind `0x04`（高于本版本最高 kind） | `frame-invalid` |
| `A_tag_frame_without_authentication_is_frame_invalid` | 认证关闭时收到 `0x03` | `frame-invalid` |

### 4.6 认证已开时的 tag 帧畸形（W6-24 面）

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `A_missing_tag_frame_with_authentication_on_is_auth_missing` | 收满信封后直接断开 | `auth-missing` |
| `A_tag_frame_one_byte_short_is_frame_invalid` | tag 帧 32 字节（应为 33） | `frame-invalid` |
| `A_tag_frame_one_byte_long_is_frame_invalid` | tag 帧 35 字节 | `frame-invalid` |
| `A_tag_frame_before_the_first_chunk_is_frame_invalid` | tag 帧在首块之前 | `frame-invalid` |
| `A_valid_tag_ends_the_transfer_so_a_trailing_chunk_is_never_read` | 真 tag 之后再塞一块 | `envelope-invalid`（3 帧，尾随块未被读取） |

### 4.7 信封大小边界

| 用例 | 输入 | 实测码 |
| --- | --- | --- |
| `An_envelope_of_exactly_eight_mebibytes_is_buffered_and_then_rejected_as_envelope_invalid` | 恰好 8388608 字节（1026 个块） | `envelope-invalid` |

### 4.8 传输层行为（关闭 / RST / 半关 / 滴流）

| 用例 | 输入 | 实测码 | 备注 |
| --- | --- | --- | --- |
| `A_peer_that_connects_and_closes_without_a_byte_is_frame_truncated` | 连上即关 | `frame-truncated` | |
| `A_peer_that_resets_the_connection_is_read_as_a_close_and_writes_nothing` | 写 2 字节后 SO_LINGER(0) 关闭（RST） | `frame-truncated` | 见第 6 节第 9 条 |
| `A_peer_that_shuts_down_mid_frame_is_frame_truncated` | 完整块帧只带 100 中的 10 字节后 FIN | `frame-truncated` | detail 指明 10 of 100 |
| `A_slow_drip_peer_runs_out_of_the_receive_budget` | 每 150 ms 1 字节 | `transfer-timeout` | 预算 1.5 s，实测 ≥1.2 s 才超时 |

### 4.9 一个监听上的并发噪声

`Eight_hostile_peers_and_one_good_peer_on_one_listener_import_only_the_good_one`：
先挂 9 个接收任务，再并发投递 8 条恶意字节流与 1 条真信封。断言**恰好**一条成功、`Report.Applied == 2`、
`sync-sources.json` 有 2 条、`sync-baseline.json` 存在；8 条拒绝的码全部在四个码集内。实测分布：

    恶意流码：frame-invalid ×3 / frame-truncated ×3 / payload-too-large ×1 / envelope-invalid ×1

（`envelope-invalid` 那条是「两块拼出一个完整信封」——帧层合法、内容不是信封，因此到解码层才被拒；
它说明「帧层通过」不等于「被接受」。）

### 4.10 固定种子随机流

`Thirty_two_fixed_seed_random_streams_are_all_refused_with_a_stable_code`：`new Random(20261024)`，
32 组长度 1..600 的随机字节。实测 32/32 全部以 `frame-truncated` 结束（随机字节的首个 4 字节长度前缀
几乎必然落在 1..8192 之外，于是 `PairingFrame.ReadAsync` 立刻返回 null；这也是为什么随机流不是很好的
模糊测试目标——它的熵几乎全部落在同一个分支）。断言仍要求每条码在四集合并集内且零写入。

## 5 上限与常量的实际值（亲数，不要沿用旧描述）

| 常量 | 值 | 位置 | 用于 |
| --- | --- | --- | --- |
| `SourceSyncTransfer.MaximumEnvelopeBytes` | 8388608（8 MiB） | SourceSyncTransfer.cs:101 | 块头 `total` 与信封总长的上限（含等号） |
| `SourceSyncTransfer.ChunkPayloadBytes` | **8183** = 8192 − 9 | SourceSyncTransfer.cs:113 | 每块载荷；**不是** 8191 |
| `PairingFrame.MaximumPayloadBytes` | 8192 | PairingFrame.cs | 单个帧载荷上限（含 kind 字节） |
| `SourceSyncTransfer.MaximumChallengeBytes` | 512 | SourceSyncTransfer.cs:116 | **只有发送端**检查 |
| `SourceSyncTransferAuthenticator.TagBytes` / `TagFrameBytes` | 32 / 33 | SourceSyncTransferAuthenticator.cs:87,90 | tag 帧长度 |
| 四个码集总数 | 10 + 5 + 4 + 1 = 20 | 四个 Reasons 类 | 稳定码封闭集 |

## 6 实测发现（与任务书或直觉不同之处）

1. **`ChunkPayloadBytes` 是 8183，不是 8191。** 任务书写 8191；实现是 `8192 − 9`（8192 是单帧上限，
   9 是块头）。8183 是每块真正搬运的字节数，8 MiB 因此是 1026 个块。
2. **块头 `total` 超上限返回的是 `payload-too-large`，不是 `frame-invalid`。** 任务书第 2 类写的是
   frame-invalid；实现（也是更准确的语义）是「声明了超过上限的总长，一个字节都不缓冲」。
3. **0 长度 / 负长度 / 巨大长度前缀统一是 `frame-truncated`，不是 `frame-invalid`。** `PairingFrame.ReadAsync`
   对「长度 ≤0 或 >8192」返回 null，与 EOF 同一条路径；帧层没有机会区分「头非法」与「对端关了」。
   这是既有设计（W6-16 起就如此），本轮按 characterization 记录，未改。
4. **认证关闭时，尾随字节不会被读取。** 收满 `total` 就立刻 `return Ok`，所以「收满后再来一块」这条
   （`another chunk after the envelope was complete`）**只有认证开启时可达**（因为要等 tag 帧才会继续读）。
   同理，「认证关闭时收到 `0x03`」只有在首块之前才可观测。
5. **认证关闭时 tag 帧不被忽略，而是 `frame-invalid`（「Unknown frame kind 0x03.」）。** 明确拒绝比静默
   丢弃好：静默丢弃会让一个「以为是认证的连接」看起来成功。
6. **未知帧类型的 detail 用小写十六进制**（`Unknown frame kind 0x7f.`），用例按实现原文断言。
7. **接收端没有 512 字节挑战上限**：`MaximumChallengeBytes` 只在发送侧检查，接收侧只受单帧 8192 限制，
   所以 513 字节挑战会被接受（然后因为载荷不是信封而被拒）。见第 9 节裁决项 1。
8. **恰好 8 MiB 是合法的**：帧层会真的缓冲 8 MiB 并通过（随后解码层拒绝）。也就是说上限是「含等号」，
   内存峰值由单个信封决定，不存在「差一字节就能撑爆」的边界。
9. **回环 RST 与 FIN 在接收端不可区分**：写 2 字节后 SO_LINGER(0) 关闭，接收端读到的是 EOF，
   码是 `frame-truncated` 而不是 `io-failed`。见第 7 节。

## 7 产品代码改动（1 处，最小加固）

`windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs`，`SourceSyncTransferListener.ReceiveOnceAsync`：
在既有的 `SocketException` / `ObjectDisposedException` 之后增加一个 `catch (IOException error)` 分支，
返回 `SourceSyncTransferResult.Failed(SourceSyncTransferReasons.IoFailed, error.Message)`。

**理由（如实说明）**：这是本方法里唯一一处**不在** `SourceSyncTransfer` 自己 catch 列表内的读取——
明文路径上先跑 `SourceSyncTlsProbe.ReadAsync`（探测前 5 字节是否为 TLS 记录），而它内部直接
`stream.ReadAsync(...)`，没有任何 try/catch。`SourceSyncTransfer.ReceiveAsync` 自己捕 `IOException` 并
映射到 `io-failed`，`SourceSyncTlsHandshake.AuthenticateServerAsync` 也捕，只有这个探针是裸的。

**没有复现**：在本机回环上，写 2 字节后 SO_LINGER(0) 的 RST 被 .NET 呈现为「读到 0 字节」（EOF），
于是走 `frame-truncated`；`ReceiveOnceAsync` 抛异常这条路径在 36 个用例里**一次都没被触发**。
所以这几行是**加固**而不是「修复了一个已复现的崩溃」：它保证「某个把 RST 呈现为 I/O 错误的协议栈」
下，`transfer serve` 仍然返回稳定码而不是让异常穿出（CLI 的 `RunTransferServeAsync` 没有外层 catch，
穿出去就是进程崩溃 + 半截 stderr）。若维护者倾向「没有复现就不改产品」，这一处可以独立回退，
其余 36 个用例与它无关。

## 8 零写入是怎么断言的（为什么不是「查字段」）

失败用例一律断言 `File.Exists(Path.Combine(directory, "sync-sources.json")) == false` 与
`sync-baseline.json` 同理。`FileLocalSourceStore` 只有 `Save` 才会 `Directory.CreateDirectory` + 写临时文件 +
`File.Move`，构造时**不建文件**；`FileSourceSyncBaselineStore` 同理。所以「文件不存在」就是「这个轮次根本
没写」，比「写了但值为空」更强，也不会因为日后加字段而失效。成功用例（并发那例）反过来断言两个文件
都存在且源计数为 2。

## 9 需要裁决（本轮按现状记录，未扩大范围）

1. **接收端是否也该拒绝 >512 字节的挑战帧？** 目前发送端拒绝、接收端接受（只受 8192 限制）。
   要收紧就得改「对端可以宣告什么」，属于线格式兼容性决定，不由本任务单方面改。现状：接受，随后由
   解码层拒绝，零写入。
2. **认证关闭时的尾随帧不读**：收满即返回，对端再写的东西留在 socket 里被丢弃（连接随即关闭）。
   这是 W6-16 起的行为，本轮只记录。若希望「多余的帧也算错」，需要先有「收满后继续读到 EOF」的约定，
   那会改变关闭态的读时序，属于行为变更。
3. **RST 无法与正常关闭区分**：接收端看到的都是 EOF。若希望「被重置」可诊断，需要额外的 keepalive/
   超时语义，本轮不做。

## 10 未覆盖

- **跨机器/跨进程长窗口**：所有用例都是同机回环；RST 的时序、MTU、延迟、丢包都没测。
- **TLS 路径上的同类畸形帧**：本文件的 36 例都跑明文监听；TLS 只是把帧包在记录层里，W6-26 的用例
  覆盖了握手/钉扎/明文对 TLS 端口互打，但没有逐个重跑本矩阵。
- **并发规模 >9**：只验证了「8 坏 1 好」。没有压力测试（例如 100 个坏连接）与文件描述符耗尽。
- **模糊测试的广度**：固定 32 组随机流全部落在同一个分支（`frame-truncated`），覆盖率有限；真正有效的
  是 4.1–4.7 的手工构造用例。若要做真 fuzz，应以「合法长度的前缀 + 合法块头 + 随机载荷」为种子。
- **内存压力**：8 MiB 用例证明单封存在上限内可缓冲，但没有测「同时多个 8 MiB 连接」。
- **CLI 层**：本任务只测 `MediaHub.Sync` 的接收面；`transfer serve` 的进程级行为（退出码、stderr 文案）
  由 W6-28 的两进程验收脚本覆盖，本文件不重复。

## 11 给 Android 侧的联调口径

- Android 端**不需要**为这批输入新增任何行为：拒绝顺序与 Windows 完全一致即可——
  长度前缀非法 → `frame-truncated`；块结构不一致 → `frame-invalid`；`total` 超 8 MiB → `payload-too-large`；
  挑战不符 → `challenge-mismatch`；帧层通过但内容不是信封 → `envelope-invalid`。
- **不要**依赖「接收端会拒绝超长挑战帧」这条不存在的规则（Windows 发送端拒绝、接收端接受）。
- 认证开启时，tag 帧必须是最后一帧，且长度恰为 33 字节（1 kind + 32 HMAC）；`0x03` 在未认证的连接上
  必须被当作未知帧类型拒绝，不要静默丢弃。
- 收到 `total` 恰好 8388608 的信封是合法的：按 8183 字节一块切，共 1026 块。

## 12 复跑

    dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --filter "FullyQualifiedName~SourceSyncHostileInputTests"

实测：**36 通过 / 0 失败**，该类耗时约 2 s（最慢一例是慢速滴流，1 s）。
全量：`dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug`
→ **1424 通过 / 0 失败 / 0 跳过**（W6-30 结束时是 1388，本任务 +36）。
