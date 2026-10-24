# W6-24 回环传输的对端身份校验（HMAC-SHA256 挑战应答）

- 状态: **IMPLEMENTED_UNVERIFIED**（代码已实现、45 个新用例全绿、全套 1332 例通过、App 工程 0 警告 0 错误；本轮**没有** GUI 证据，也**没有**与 Android 端的联调证据）
- 日期: 2026-10-24
- 分支: `feature/cross-device-source-sync` / HEAD `55767e4`（本任务改动**未提交**，仍在工作区）
- 前置: W6-16 回环传输分帧、W6-17/18 收件箱与对端回合、W6-22 Android 分块传输向量、W6-12 配对指纹钉扎
- 范围: 只动 `windows/src/MediaHub.Sync`（5 个文件，其中 1 个新增）、`windows/src/MediaHub.SyncCli`（3 个文件）、2 个新测试文件、本文档；未动 `MediaHub.App` / Providers / Domain / Storage，未启动 GUI，零 NuGet

## 1 威胁模型

W6-16 的传输层是**明文回环 TCP**：服务端只监听 `127.0.0.1`（`SourceSyncTransferListener.Start` 用 `SourceSyncTransferAddress.TryResolveLoopback` 拒绝一切非回环地址），客户端只拨回环。这挡住局域网，但**挡不住同机的其它进程**——任何本地进程都能连上 serve 端口，投递一个自造的信封，看起来和真实对端完全一样。W6-12 的配对层有指纹钉扎，可它管的是"对端是谁"，传输层从来没有用过那枚指纹。

本任务补的是一件事：**两端证明自己持有同一条事先约定好的口令**，并且这条证明只对本次挑战有效。

| 攻击者能力 | 本层是否拦住 | 说明 |
| --- | --- | --- |
| 同机进程连接 serve 端口、投递自造信封 | 拦住（开启认证时） | 没有口令就算不出 tag；伪造 tag → `auth-mismatch`，且**不写入任何东西** |
| 同机进程抓走一次合法传输的字节，原样重放 | 拦住 | nonce 记进进程内缓存，第二次出现 → `auth-replayed` |
| 同机进程截断传输（发完数据不发 tag） | 拦住 | `auth-missing`，不当成成功 |
| 同机进程在数据中间塞 tag、改 tag 长度 | 拦住 | `frame-invalid`，因为 tag 必须紧跟最后一块 |
| 抓包读取信封内容（含凭据） | **不拦** | 本层不加机密性，明文照旧；见第 10 节 |
| 拿到口令后冒充对端 | **不拦** | 共享口令本身就是身份，本层不做设备身份绑定 |
| 用不同 nonce 重放旧信封（改 tag 也不匹配） | 拦住 | tag 绑定了 nonce，换 nonce 得重算 HMAC |

## 2 为什么默认关闭

1. **字节级契约在跑**：Android 端 W6-22 已有 62 条逐字节向量，Windows 侧 34 个传输用例也是按帧比对的。给线格式加一个帧类型，等于改契约；默认关闭让"没升级的一端"继续跑老路径，不会因为多出一帧而失败（未开启的一端收到 `0x03` 会报 `frame-invalid` 的 `Unknown frame kind 0x03.`，这是**故意**的：它说明对端擅自加了认证，而不是静默忽略一段本该覆盖信封的证明）。
2. **开启必须两端同时**：认证是"两端都显式打开"才生效的，单端打开只会得到 `auth-missing`。默认关闭避免"更新一台就断一片"。
3. **口令来源受限**：口令只能从环境变量进（`--secret-env NAME`）。没有配置口令的部署不该被迫看到认证错误，所以开关默认 off。

`SourceSyncTransferOptions.Off`（以及不传 options 的 `null`）必须与 W6-16 **逐字节相同**：没有 tag 帧、没有额外 flush、帧数不变（第 5 节给了实测 hex）。

## 3 线格式

帧还是 W6-16 的 `PairingFrame`：4 字节大端长度前缀（不含前缀本身）+ 1 字节 kind + 载荷。

| kind | 名称 | 载荷 | 何时出现 |
| --- | --- | --- | --- |
| `0x01` | 挑战帧 | UTF-8 挑战文本（≤ 512 字节，等于本层的 nonce） | 第一帧，可选；认证开启时**必需** |
| `0x02` | 数据块 | `total(4, BE) ‖ offset(4, BE) ‖ payload(≤ 8183)` | 1..N 帧 |
| `0x03` | **tag 帧（新）** | `HMAC-SHA256(secret, nonce ‖ envelope)` 共 32 字节 | 最后一个数据块之后，仅在两端都开启认证时出现 |

- tag 帧总长恒为 33 字节（1 + 32）；长度不符 → `frame-invalid`，detail 为 `A tag frame is N bytes; an HMAC-SHA256 tag is 32 bytes plus the kind byte.`
- HMAC 输入顺序是 **nonce 在前、信封在后**，nonce 取的是挑战帧里那段文本的 UTF-8 字节（发送端先 `Trim()`，接收端用对端宣布的原文）。
- 信封上限仍是 8 MiB（`MaximumEnvelopeBytes = 8 * 1024 * 1024`），分块上限 `ChunkPayloadBytes = 8183`。
- 接收端在认证开启时**收满也不返回**：`received == total` 只置 `complete = true`，继续读下一帧，必须是 `0x03`；对端在此时关闭连接 → `auth-missing`。

## 4 口令、nonce 与重放窗口

| 项 | 规则 |
| --- | --- |
| 口令来源 | 只从环境变量读（`--secret-env NAME`），**从不**出现在命令行、JSON、stderr、异常文本；`SourceSyncTransferOptions.ToString()` 只打印 `<redacted N bytes>` |
| nonce 生成 | 发起方每次传输**新生成**一个：`SourceSyncTransferAuthenticator.CreateNonce()` = 16 随机字节的 base64url（22 字符，去掉填充）。复用 nonce 正是接收端重放缓存要拒绝的 |
| nonce 传递 | 挑战帧的文本就是 nonce；接收端要求它与邀请一致时才用 `expectedChallenge` 校验 |
| 校验顺序 | 先**常量时间**比 tag（`CryptographicOperations.FixedTimeEquals`），再查重放缓存。顺序刻意如此：伪造 tag 不能"烧掉"真 nonce，否则攻击者发一个垃圾包就能让随后合法传输被当成重放 |
| 重放窗口 | 进程内 `SourceSyncNonceCache`，默认容量 64 条，按 `(DeviceId, nonce)` 分区，FIFO 淘汰最旧；**不落盘**（跨进程重启即清空，见第 10 节） |
| 失败时分 | 认证开启但本进程没有口令 → `auth-secret-unavailable`，**一个字节都不写/不读**；发送端没有 nonce（挑战为空白）→ `auth-required`，同样不写 |

## 5 关闭认证时字节与 W6-16 相同（实证）

同一份输入（64 字节信封 `payload[i] = (i*31+7) & 0xFF`，nonce `nonce-abc-123`）在关闭认证时线上应是 95 字节：

```text
0000000e016e6f6e63652d6162632d313233000000490200000040000000000726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8
```

拆解：挑战帧 18 字节（`0000000e` + `01` + 13 字节 `nonce-abc-123`），数据块帧 77 字节（`00000049` + `02` + `00000040` total + `00000000` offset + 64 字节载荷）。这段 hex 已作为**冻结向量**写进测试 `SourceSyncTransferAuthTests.With_authentication_off_the_bytes_match_the_frozen_w6_16_hex_vector`，它同时断言：

- 不传 options 与传 `SourceSyncTransferOptions.Off` 产出的字节完全相同；
- 发送端 `FrameCount == 2`、`Bytes == 64`（`Bytes` 数的是信封，不是线上字节）、`stream.Length == 95`；
- 这段冻结字节仍能被接收端读回同一个信封。

另外 `With_authentication_off_the_bytes_are_exactly_the_w6_16_frame_stream` 用逐帧拼装（不引用实现）再比一次，`With_authentication_off_a_trailing_tag_is_simply_never_read` 证明旧路径"收满即返回"没有因为认证分支被改成"多读一帧"。

## 6 稳定原因码

新增 5 个码，与 W6-16 的 10 码**并列**（`SourceSyncTransferReasons.All` 保持 10 条不变，既有测试断言它不扩张）：

| 码 | 何时出现 | 是否写入任何数据 |
| --- | --- | --- |
| `auth-required` | 开启认证但本次传输没有挑战（nonce）可绑 | 否 |
| `auth-missing` | 数据收满但对端没发 tag；或发了 tag 却从没宣布挑战 | 否 |
| `auth-mismatch` | tag 与"该信封 + 该 nonce + 该口令"不符（含错口令、篡改信封） | 否，且**不记 nonce** |
| `auth-replayed` | 同一 `(deviceId, nonce)` 第二次被接受 | 否 |
| `auth-secret-unavailable` | 开启认证但本进程没有口令（环境变量未设/为空/名字非法） | 否 |

CLI 侧这 5 个码一律 `exit 2`（`CliExitCode.InputProblem`），与"输入问题"同档；退出码语义仍是 0 成功 / 1 用法错 / 2 输入问题 / 3 需审批 / 4 归档或凭据。

## 7 分层接线

所有既有方法的可选参数都**只在末尾追加** `SourceSyncTransferOptions? options = null`（位置传 `CancellationToken` 的调用点很多，插在中间会破源兼容）：

| 层 | 文件 | 变化 |
| --- | --- | --- |
| 帧读写 | `windows/src/MediaHub.Sync/SourceSyncTransfer.cs` | `SendAsync` 在最后一块之后写 tag 帧；`ReceiveAsync` 收满后继续读 tag 帧、验 tag、查重放 |
| 监听/拨号 | `windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs` | `SourceSyncTransferListener.ReceiveOnceAsync` / `SendOnceAsync`、`SourceSyncTransferClient.SendAsync` / `ReceiveAsync` 透传 options |
| 收件箱 | `windows/src/MediaHub.Sync/SourceSyncInbox.cs` | `ReceiveAsync` / `DialAndImportAsync` / `ReceiveAndImportAsync` 扩展方法透传 |
| 对端回合 | `windows/src/MediaHub.Sync/SourceSyncPeerRound.cs` | `SendAsync` / `SendPayloadAsync` / `ReceiveOnceAsync` / `ReceiveOnAsync` / `ExchangeAsync` / `ExchangePayloadAsync` 透传；**不做隐式 nonce 生成**，nonce 由调用方（CLI）显式给 |
| 认证原语 | `windows/src/MediaHub.Sync/SourceSyncTransferAuthenticator.cs`（新） | 5 个码、`CreateTag` / `VerifyTag`、`CreateNonce`、`SourceSyncNonceCache`、`SourceSyncTransferOptions` |

`SourceSyncTransferOptions` 是 `sealed class` 而**不是** `record`：record 的编译器生成 `ToString` 会把 `Secret` 打印出来，而这些 options 会进诊断文本和异常消息；显式 `ToString` 打印 `<redacted N bytes>`。

## 8 CLI

```text
mediahub-synccli transfer serve    --root DIR --device ID --port N [--authenticate --secret-env NAME] [--apply] [--json]
mediahub-synccli transfer send     --to HOST:PORT --root DIR --device ID [--authenticate --secret-env NAME] [--json]
mediahub-synccli transfer roundtrip --root DIR --device-a A --device-b B [--authenticate --secret-env NAME] [--json]
```

- `--authenticate` 只允许上面三个动词；其它命令 → `option-not-allowed-for-command:--authenticate:<command>`。
- `--authenticate` 缺 `--secret-env` → `missing-required-option:--secret-env`；`--secret-env` 缺 `--authenticate` → `missing-required-option:--authenticate`（两个都是用法错，exit 1）。
- 环境变量名非法（`Environment.GetEnvironmentVariable` 抛 `ArgumentException`）或变量未设/为空 → `auth-secret-unavailable`，exit 2，**消息里只有变量名，没有值**；并且这个检查发生在 bind 端口、打开存储、创建 `a/` `b/` 目录之前，失败时不留监听、不留目录。
- 发送端每次传输自己造 nonce（`CreateNonce()`）并把它放进挑战帧；roundtrip 两个方向各造一个。
- JSON 输出新增两个字段：`authenticate`（bool）与 `secretEnv`（变量名，或 null）。文本模式在开启时多打一行 `authenticated: yes (secret read from <变量名>)`（只有名字）。

## 9 Android 端需跟进的字段清单

Android（W6-22 那套分块传输）要与非 Windows 端互操作，需要按下面这张表补齐；字段名/取值是**契约**，不得改写：

| 字段 | 取值 | 为什么必须一致 |
| --- | --- | --- |
| tag 帧 kind | `0x03` | 与 `0x01`/`0x02` 同处一个帧空间，kind 决定解析分支 |
| tag 帧长度 | 恒 33（1 + 32） | 接收端按 `frame.Length != 33` 直接 `frame-invalid` |
| tag 算法 | `HMAC-SHA256`，密钥 = 口令原始 UTF-8 字节 | 这是唯一的共享口令形态；口令不做任何 KDF/加盐，两端必须同字节 |
| HMAC 输入 | `nonce_utf8 ‖ envelope_bytes`（nonce 在前，无分隔符、无长度前缀） | 顺序换了就永远 mismatch |
| nonce 来源 | `0x01` 挑战帧的文本（发送端 `Trim()` 之后的 UTF-8） | 接收端用"对端宣布的挑战"算 tag，不是用自己期望的 |
| nonce 形态 | 建议 `CreateNonce()` 同款：16 随机字节 base64url 无填充（22 字符） | 接收端不校验形态，但两端一致便于互查日志 |
| tag 位置 | 所有 `0x02` 之后、独占最后一帧 | 数据没发完就来 tag → `frame-invalid`；数据发完不来 tag → `auth-missing` |
| 重放窗口 | 至少 64 条 `(peerDeviceId, nonce)`，FIFO | 同一 nonce 第二次必须判 `auth-replayed` |
| 错误码 | `auth-required` / `auth-missing` / `auth-mismatch` / `auth-replayed` / `auth-secret-unavailable` | 与 Windows 侧字符串逐字一致，便于跨端日志比对 |
| 关闭时行为 | 不发 `0x03`、不读 `0x03`、字节与 W6-22 向量全等 | 默认关闭是互操作底线 |

## 10 未覆盖（已知边界，不要当成已解决）

- **跨进程重放窗口**：重放缓存只在进程内、不落盘、容量 64。攻击者只要等到进程重启，或者把一次合法传输的第 65 个之后 nonce 挤掉，旧信封就能重放成功。要关闭这个口子需要落盘 nonce 账本或单调计数器，本任务没做。
- **密钥轮换**：口令只有一条，没有版本号、没有 `kid`、没有过期时间；换口令必须两端同时改，改的瞬间所有在途传输失败。
- **没有机密性**：不加 TLS、不加 AEAD。信封（含来源凭据）仍旧明文过回环 socket，同机有权限读内存/抓包的进程照样能读到。本层只解决"谁写的"，不解决"谁能看"。
- **没有前向保密**：共享口令泄露 = 历史与未来全部可伪造（历史信封可被重新签名，只要 nonce 没被记过）。
- **没有设备身份绑定**：tag 只证明"持有口令"，不证明"是已配对的那台设备"。要与 W6-12 指纹结合，得把指纹塞进 tag 输入，那会改契约，未做。
- **tag 不覆盖帧边界与元数据**：只覆盖 nonce + 信封字节。帧长度/分块方式被重排不影响校验（分块本身有 offset 校验，所以危害有限，但不是密码学保证）。
- **缓存投毒式 DoS**：容量 64 的窗口可以被有效传输快速填满，从而让某条更早的合法 nonce 被挤掉（这正是第一条的窗口）。没有速率限制。
- **单端开启即失败**：一端 `--authenticate` 而另一端没有，只会得到 `auth-missing`（接收端）或对端 `frame-invalid`（发送端收到 `0x03` 时）；没有任何自动降级——降级本身会毁掉认证的意义。
- **没有与 Android 联调**：本节所有"契约"是靠本仓库测试钉住的，Android 侧尚未实现/未验证。

## 11 验收证据

```text
dotnet build windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug
  已成功生成。  0 个警告  0 个错误

dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --no-build
  已通过! - 失败: 0，通过: 1332，已跳过: 0，总计: 1332，持续时间: 5 s    （基线 1287 + 45）

dotnet build windows/src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:/mh-out-w6/ -t:Build
  MediaHub.App -> D:\mh-out-w6\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.dll
  已成功生成。  0 个警告  0 个错误
```

45 个新用例分布：`SourceSyncTransferAuthTests` 32 条（帧/口令/重放/冻结向量），`SyncCliTransferAuthTests` 13 条（参数校验、环境变量、端到端、泄漏检查）。泄漏检查断言口令明文既不在 stdout 也不在 stderr（JSON 与文本两种模式都查）。

## 12 偏离与备注

- `SourceSyncTransferReasons` 的 10 码**不动**，新码另立 `SourceSyncTransferAuthReasons`：既有测试断言它 `Count == 10` 且无重复，扩它会直接破测。
- 接收端在**认证关闭**时仍保持"收满即返回"，所以对端多发的 `0x03` 不会被读（既有行为不变）；认证开启时才要求 tag。
- 设计上删掉了一个不可达分支（"第二个 tag 帧"）：第一个有效 tag 就 `return Ok`，第二次进不了分支。测试改为覆盖"tag 先于首块"这种可达的错误顺序。
- 本任务只写 `D:/mh-out-w6`；`D:/mh-out`、`D:/mh-out2`、`D:/mh-out3` 未被触碰；未 commit / 未 push。
