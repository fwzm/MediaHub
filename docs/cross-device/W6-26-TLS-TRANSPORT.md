# W6-26 回环传输的 TLS 1.3 包装与证书指纹钉扎

- 状态: **IMPLEMENTED_UNVERIFIED**（代码已实现、37 个新用例全绿、全套 1369 例通过、App 工程 0 警告 0 错误；本轮**没有** GUI 证据，也**没有**与 Android 端的联调证据，**不声称** RELEASE READY）
- 日期: 2026-10-24
- 分支: `feature/cross-device-source-sync`（本任务改动已入库：提交 `924c555`，合并 `3b3ad6a` 后为 `a6b13d4`）
- 前置: W6-16 回环传输分帧、W6-17/18 收件箱与对端回合、W6-3b 配对证书与公钥指纹原语（`PairingCertificate` / `PairingFingerprint`）、W6-24 传输认证
- 范围: 只动 `windows/src/MediaHub.Sync`（4 个文件，其中 1 个新增）、`windows/src/MediaHub.SyncCli`（3 个文件）、2 个新测试文件、本文档；未动 `MediaHub.App` / Providers / Domain / Storage，未启动 GUI，零 NuGet

## 1 威胁模型

W6-16 的传输层是**明文回环 TCP**：服务端只监听 `127.0.0.1`（`SourceSyncTransferListener.Start` 用 `SourceSyncTransferAddress.TryResolveLoopback` 拒绝一切非回环地址），客户端只拨回环。W6-24 给它加了**对端持有口令**的证明（HMAC-SHA256 挑战应答），但**没有机密性**：信封里的来源记录带 URL，凭据型来源的 URL 里就含口令，明文 TCP 上任何能读本地回环流量的进程都能拿走。

本层补的是另外两件事：

1. **机密性**：把整段帧流包进 TLS 1.3 记录层，抓到的字节不再是可读 JSON。
2. **服务端身份**：服务端出示一张自签证书，客户端**钉扎它的公钥指纹**——不是"信任任何证书"，也不是"信任系统根"，而是"这一次必须是我手上那枚指纹对应的那把公钥"。

TLS 与 W6-24 是**叠加**关系，不是替代：TLS 保证"传的东西没被偷看/篡改 + 对端不是我钉扎的那把公钥就断"，口令证明保证"对端是知道口令的那个进程"。两者同时开启有专门用例（`Tls_and_the_tag_frame_work_together`、`Tls_with_the_wrong_secret_is_an_auth_mismatch`）。

| 攻击者能力 | 本层是否拦住 | 说明 |
| --- | --- | --- |
| 同机进程抓包读信封（含凭据 URL） | 拦住 | TLS 1.3 记录层加密，只有 AEAD 密文 |
| 同机进程连 serve 端口、冒充服务端给客户端喂信封 | 拦住 | 客户端钉扎公钥指纹，对不上 → `tls-fingerprint-mismatch`，一个信封字节都不读不写 |
| 中间人改写帧流（改长度/改 kind/改载荷） | 拦住 | TLS 记录层 AEAD 完整性校验失败，握手后任意改写都会断连 |
| 降级到 TLS 1.2 或明文 | 拦住 | `EnabledSslProtocols = Tls13`，握手后再查一次 `SslProtocol`；明文客户端打到 TLS 端点 → `tls-handshake-failed`，TLS 客户端打到明文端点 → `tls-not-enabled` |
| 拿到口令冒充对端 | **不拦** | 口令是 W6-24 的身份，本层不动它；本层只证明"对面那把公钥" |
| 本机进程直接读环境变量拿口令 | **不拦** | 见 W6-24 第 10 节，同机同用户进程之间没有隔离 |
| 服务端证书被换掉但攻击者能改你命令行里的 pin | **不拦** | pin 来自操作员/调用方，本层不校验 pin 的出处 |
| 跨机器窃听 | 不适用 | 传输仍只走回环，本层不绑非回环地址 |

## 2 为什么默认关闭

1. **字节级契约在跑**：W6-16 有一枚 95 字节冻结向量（挑战帧 18 + 数据块帧 77），Android 端 W6-22 有 62 条逐字节向量。TLS 一旦默认开启，线上字节就变成 TLS 记录，所有向量全部作废。默认关闭让"没升级的一端"继续跑老路径。
2. **开启必须两端同时**：TLS 是双端协商，单端开启只能得到握手失败。默认关闭避免"更新一台就断一片"。
3. **pin 必须由操作员提供**：`transfer send` 必须显式给 `--expect-fingerprint`。没有默认值可依（临时证书每次指纹都不同），所以不能"默认开"。

关闭时的实证不是口头承诺，而是断言：`With_tls_off_the_bytes_match_the_frozen_w6_16_vector` 直接比对那 95 字节 hex，并断言 `Stream.Length == 95`、`FrameCount == 2`、`Bytes == 64`（注意 `SourceSyncTransferResult.Bytes` 数的是**信封**长度，不是线上字节）。

## 3 线格式

**帧协议完全不变**：还是 `PairingFrame` 的 4 字节大端长度前缀（不含前缀本身）+ 1 字节 kind + 载荷，只是这些字节现在写在 TLS 记录层里面。

| kind | 名称 | 载荷 | 何时出现 |
| --- | --- | --- | --- |
| `0x01` | 挑战帧 | UTF-8 挑战文本（≤ 512 字节；W6-24 开启时它是 nonce） | 第一帧，可选 |
| `0x02` | 数据块 | `total(4, BE) ‖ offset(4, BE) ‖ payload(≤ 8183)` | 1..N 帧 |
| `0x03` | tag 帧（W6-24） | 32 字节 HMAC-SHA256 | 最后一个数据块之后，仅 W6-24 两端开启时 |

本层不新增任何 kind、不改任何长度、不改任何顺序。**"TLS 开或关"与"认证开或关"正交**，四种组合都有用例覆盖（关/关 = W6-16、认证/关、TLS/关认证、TLS+认证）。

## 4 身份：临时自签证书与公钥指纹

- 证书由 W6-3b 的 `PairingCertificate.CreateEphemeral("mediahub-sync")` 现造：RSA-2048、`CertificateRequest("CN=mediahub-sync", key, SHA256, Pkcs1)`、SAN 含 DNS `localhost` 与 `IPAddress.Loopback`、`BasicConstraints(false,false,0,critical:true)` + `KeyUsage(DigitalSignature|KeyEncipherment)` + EKU `serverAuth`、有效期 `now-5min` 到 `now+2h`。
- 私钥只存在于进程内存：`CreateEphemeral` 必须 `Export(X509ContentType.Pfx)` 再 `X509CertificateLoader.LoadPkcs12(...)`，否则 Windows SChannel 会报 "the credentials supplied to the package were not recognized"。**私钥从不落盘**。
- 指纹 = **SHA-256(SubjectPublicKeyInfo)** 的小写 hex，64 字符 —— 不是整张证书的摘要（证书里有有效期、序列号、签名，会变；公钥不会）。这个定义直接复用 `PairingCertificate.ComputeFingerprint` / `PairingFingerprint`，不另写一套密码学。有一条用例专门证明"是公钥不是证书"：手工 `ExportSubjectPublicKeyInfo()` 求 SHA-256 与 `CertificateFingerprint` 相等，且与证书 `RawData` 的摘要不同。
- 比较是**常量时间**：`PairingFingerprint.Matches` 内部 `CryptographicOperations.FixedTimeEquals`，两边其一为空一律 false（空 pin 永远不会"碰巧匹配"）。
- 展示形态：`SourceSyncTlsIdentity.DisplayFingerprint` = 大写、每 4 字符一个空格（79 字符），供人眼核对；机器接口一律用小写 64 hex。

## 5 指纹怎么到达拨号方

| 动词 | 谁钉谁 | 规则 |
| --- | --- | --- |
| `transfer serve` | 被钉的一方 | 自己打印指纹：`certificate fingerprint: <64 hex>`。**stderr 永远打印**（JSON 模式下 stdout 只能有一份文档，而调用方必须先拿到 pin 才能拨号）；文本模式同时写 stdout。显式传 `--expect-fingerprint` 是用法错（`option-not-allowed-for-command:--expect-fingerprint:transfer-serve`） |
| `transfer send` | 钉的一方 | **必须**给 `--expect-fingerprint`，缺失 → `missing-required-option:--expect-fingerprint`（exit 1）。给的 pin 会回显在 JSON 的 `expectFingerprint` 与 `certificateFingerprint` 里（规范化成小写） |
| `transfer roundtrip` | 自钉 | 两个方向的 listener 都由本进程刚建好，sender 直接用 `SourceSyncTlsOptions.Pinned(listener.CertificateFingerprint)`，**不需要操作员在两条命令之间复制指纹**；因此拒绝显式 pin（`option-not-allowed-for-command:--expect-fingerprint:transfer-roundtrip`），也**不打印**指纹 |

库层对应物：`SourceSyncTlsOptions.Pinned(hex)` / `SourceSyncTlsOptions.Off`；`SourceSyncTlsIdentity.Fingerprint` 与 `SourceSyncTransferListener.CertificateFingerprint` 给出服务端自身的指纹（`TlsEnabled == false` 时为 null）。

## 6 四个稳定失败码

新码**独立成集**（`SourceSyncTlsReasons`），与 `SourceSyncTransferReasons` 的 10 码封闭集、`SourceSyncTransferAuthReasons` 的 5 码封闭集**并列**，既有的码一个都没改；有用例断言三集两两不重叠。

| 码 | 含义 | 典型触发 |
| --- | --- | --- |
| `tls-handshake-failed` | 握手没完成（对端不是 TLS 1.3、协议被截断、超时、对端静默） | 明文客户端打 TLS 端点；静默 TCP 对端；600–700 ms 预算内没握完 |
| `tls-fingerprint-mismatch` | 对端出示的公钥指纹不等于钉扎值 | 错 pin；服务端声明的指纹与自己的证书不符（服务端自证） |
| `tls-certificate-unavailable` | 本机造不出证书，或 pin 不可解析（不是 64 hex），或对端证书取不到公钥 | pin=`abc`（detail 说明 "a fingerprint is 64 hex characters"）；证书创建失败 |
| `tls-not-enabled` | 明文端点收到 TLS 握手记录 | TLS 客户端打未开 `--tls` 的 serve |

CLI 侧四个码都归 `input-problem`（**exit 2**），退出码语义仍是 0/1/2，与 W6-24 的 `auth-*` 一致。

## 7 明文端点遇到 TLS 客户端：5 字节探针

明文监听端在接受连接后先读最多 5 字节（`SourceSyncTlsProbe.ProbeBytes`）：

- `LooksLikeTlsHandshake`：`len >= 3 && probe[0] == 0x16 && probe[1] == 0x03 && probe[2] <= 0x04`（TLS 记录类型 handshake + 主版本 3 + 次版本 0..4，含 TLS 1.3 的 0x03）。
- 判定为 TLS → `tls-not-enabled`，detail 明说 "The peer opened the connection with a TLS handshake record, but this transfer endpoint is plaintext"。
- 否则把这几个字节**原样退回**给上层：`SourceSyncTlsPrefixedStream` 先吐前缀再转内层流。`PairingFrame.ReadExactlyAsync` 只调用 `stream.ReadAsync(Memory<byte>, ct)`，所以这个包装流实现 `ReadAsync(Memory)` 就够了；它有专门的逐字节重放用例（`CanSeek`/`CanWrite` 为 false，`Write` 抛 `NotSupportedException`）。

探针只在**接收端**做（`Client.ReceiveAsync` 传 `probePlaintext: true`，`SendAsync` 传 false）：拨号方发完就等回音，不读入站数据，加探针只会白等。

## 8 关闭阶段：TLS 1.3 的 session ticket 与 RST（本轮实测发现的坑）

这是本轮唯一一个"全量并行才复现"的真实缺陷，值得单列：

- TLS 1.3 服务端在**握手完成后立刻**发 `NewSessionTicket`。发送端此时 socket 里还有**未读的入站记录**；`SslStream` 带未读记录直接 Dispose 是 **abortive close**（RST），会丢弃对端尚未读走的字节。接收端表现为 `io-failed`：`Unable to read data from the transport connection: 远程主机强迫关闭了一个现有的连接。`
- 症状：`Tls_and_the_tag_frame_work_together` 单跑必绿，**全套并行时大约每 2–3 次挂 1 次**（serve exit 2 + `io-failed`，而同一次 run 里 send 侧 exit 0、frames 3）。
- 修复两处：
  1. `SourceSyncTlsHandshake.CloseQuietlyAsync(stream, transport, timeout)`：用 `await stream.ShutdownAsync().WaitAsync(budget)`（`SslStream.ShutdownAsync` 没有 `CancellationToken` 重载，只能用 `WaitAsync` 设上限）等 close_notify，预算 **2000 ms**；超时或异常则先 `DrainQuietlyAsync(transport)`（250 ms 内把裸流读空）再 Dispose。取消/失败一律吞掉（关闭阶段不能掩盖业务结果）。
  2. 拨号方在 body 成功返回后先 `WaitForPeerCloseAsync(handshake.Stream)`（读到 0 或 1500 ms 预算）——**让接收端先关**，发送端等它的 close_notify，这样入站记录全被 `SslStream` 消费，close 变成 graceful。
- 修复后连续 6 次全套全绿（修复前同样条件 6 次里挂 2 次）；本文件最后更新时另有 6 次后台复跑。

## 9 CLI 用法

```text
# 服务端：打印自己的指纹（stderr 永远有，文本模式 stdout 也有）
mediahub-sync transfer serve --root D:\sync --device win-a --tls

# 拨号方：必须钉住服务端打印的那枚指纹
mediahub-sync transfer send --to 127.0.0.1:52341 --root D:\sync --device win-b ^
  --tls --expect-fingerprint 3f2a...（64 hex，大小写都收）

# 双向端到端：自钉扎，不需要 --expect-fingerprint
mediahub-sync transfer roundtrip --root D:\two-way --device-a a --device-b b --tls --json
```

- `--tls` 只允许 `transfer serve` / `transfer send` / `transfer roundtrip`，其它命令 → `option-not-allowed-for-command:--tls:<command>`（exit 1）；`--tls=1` → `flag-takes-no-value:--tls`；pin 格式不合法 → `invalid-fingerprint:<原名>`。
- `--expect-fingerprint` 缺 `--tls` → `missing-required-option:--tls`。
- JSON 新增三个字段：`tls`(bool)、`expectFingerprint`(string|null)、`certificateFingerprint`(string|null)。文本模式加一行 `tls: yes (TLS 1.3, ephemeral certificate)` 或 `tls: yes (TLS 1.3, certificate pinned to <pin>)`。
- **日志/JSON 里不出现私钥，也不出现 W6-24 的口令**：本层只打印公钥指纹；`--secret-env` 仍只记环境变量名。

## 10 测试

- `windows/tests/MediaHub.Tests/SourceSyncTlsTransportTests.cs`：**21** 个用例（`380` 行）
- `windows/tests/MediaHub.Tests/SyncCliTlsTests.cs`：**16** 个用例（`451` 行）
- 全套：`失败: 0，通过: 1369，已跳过: 0，总计: 1369`（基线 1332 + 37）

覆盖要点：关闭时 95 字节冻结向量逐字节相等；`TlsEnabled`/`CertificateFingerprint` 语义；"指纹是公钥不是证书"；两张临时证书指纹不同且 `Matches` 只对同值成立；`DisplayFingerprint` 形态；TLS 往返（信封等价、`FrameCount == 1`）；错 pin → `tls-fingerprint-mismatch`；pin=`abc` → `tls-certificate-unavailable`；明文客户端打 TLS 端点 / TLS 客户端打明文端点；静默对端 → 握手超时（`did not finish within`）；服务端广播指纹与自身不符 → `tls-fingerprint-mismatch` 且一个字节都没写；无证书 → `tls-certificate-unavailable`；TLS+tag 成功（两端 `FrameCount == 3`、nonce 记 1 条）；TLS+错口令 → `auth-mismatch` 且 nonce 未记；探针逐字节重放；非回环目标仍 `NotLoopback`；三集码表两两不重叠。CLI 侧：三条参数校验规则、`--tls=1`、`invalid-fingerprint`、help 文案、JSON 全链路（serve 指纹 64 hex + send 回显小写 pin）、文本模式指纹行、错 pin 的 exit 2 且不落 store、明文客户端打 TLS serve 的 exit 2、TLS+认证全链路（口令不出现在 stdout/stderr）、roundtrip 自钉扎且不打印指纹。

## 11 未覆盖（明确不做 / 已知边界）

1. **TLS 层不做客户端认证**：服务端不要求客户端证书，客户端身份仍由 W6-24 的共享口令承担。想让 TLS 自己认客户端，需要另一套证书分发，本轮不做。
2. **证书不持久化、不轮换**：每进程现造一张，因此每次 `serve` 的指纹都不同，pin 不能跨命令复用缓存。从"已配对设备注册表"取指纹也**没有**接线——任务允许的做法是"量不到就要求显式给"，当前实现就停在"必须显式给"。
3. **无吊销/OCSP/CT**：自签证书没有信任链，`CertificateRevocationCheckMode.NoCheck` 是显式选择，不是遗漏。
4. **不提供 TLS 1.2 回退**：只认 1.3（握手后再校验一次 `SslProtocol`），老系统上会直接 `tls-handshake-failed`。
5. **无 ALPN、无会话恢复策略、无 0-RTT**：单用途私有协议，这些能力都关掉。
6. **仍然只走回环**：不绑非回环 socket（避免触发 Windows 防火墙提示），所以跨机器场景本层不参与。
7. **没有性能/吞吐测量**：只测了正确性与稳定性，没有做吞吐对比。
8. **没有 GUI 证据，没有与 Android 联调证据**：本轮全程 headless，代码状态是 IMPLEMENTED_UNVERIFIED。
9. **关闭阶段仍有 2 s 上限**：极端慢的对端可能被 `DrainQuietlyAsync` 兜底后 RST，此时对端看到的是 `io-failed` 而不是干净 EOF。测试未覆盖"对端在读数据时假死"这种组合。

## 12 给 Android 侧（W6-22 之后）的联调口径

1. **协议版本**：只协商 TLS 1.3。Android 侧 `SSLSocket` 要显式 `setEnabledProtocols(arrayOf("TLSv1.3"))`；不要依赖默认值，否则某些 ROM 会协商到 1.2，Windows 端会直接断开（`tls-handshake-failed`）。
2. **信任模型的写法**：服务端是**自签证书**，系统信任库永远不认。必须实现自定义 `X509TrustManager`：在 `checkServerTrusted` 里不要抛异常（交给下一步校验），拿 `chain[0].publicKey.encoded`（= SubjectPublicKeyInfo）算 **SHA-256**，转小写 hex，与 pin **常量时间**比较，不匹配就抛 `CertificateException`。**不要用 `certificate.encoded`（整张证书）**，那是另一种指纹，和 Windows 端不一致。
3. **pin 从哪来**：Windows `transfer serve` 的 stderr 第一段就是 `certificate fingerprint: <64 hex>`。Android 侧把它当配置项存起来（本轮 Windows 端不提供自动分发）。
4. **帧格式不变**：TLS 里跑的还是 4 字节大端长度 + 1 字节 kind + 载荷（`0x01`/ `0x02`，W6-24 时多一个 `0x03`）。Android 现有 62 条向量在 **TLS 关闭**时仍然适用；TLS 开启时只换传输层，不解帧。
5. **关闭要 graceful**：发完最后一帧后先 `shutdownOutput()` 并在有预算内读到 EOF，再 `close()`。直接 close 会 RST，Windows 端表现为 `io-failed`（第 8 节就是被这个坑过）。
6. **错误码映射**：`tls-handshake-failed` / `tls-fingerprint-mismatch` / `tls-certificate-unavailable` / `tls-not-enabled` 四个码语义见第 6 节；Android 侧对 pin 不匹配必须**当作硬失败**，不要"信任首次"降级。
7. **超时要给**：Windows 端握手预算默认跟传输预算一致（15 s）；Android 侧也应设 5–15 s 并把手握超时与业务超时分开报。
8. **不发送客户端证书**：Windows 服务端不要求，也不校验；客户端身份仍靠 W6-24 口令（若 Android 也实现 W6-24，则 `0x03` tag 帧在 TLS 内照常发送）。
9. **不要绑非回环**：两端的传输层都只允许回环地址，跨设备转发/中继不在本层范围内。

## 13 变更清单（行数实测）

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/SourceSyncTlsTransport.cs` | 628（新增） | 4 个 `tls-*` 码、`SourceSyncTlsIdentity`、`SourceSyncTlsOptions`、`SourceSyncTlsHandshake`、`SourceSyncTlsProbe`、前缀流 |
| `windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs` | 372 → 524 | `Start(tls:)`、`TlsEnabled`/`CertificateFingerprint`、双端握手接线、明文探针、graceful close |
| `windows/src/MediaHub.Sync/SourceSyncPeerRound.cs` | 448 → 460 | 六个方法末尾追加 `SourceSyncTlsOptions? tls` 并透传 |
| `windows/src/MediaHub.Sync/SourceSyncInbox.cs` | 241 → 242 | `DialAndImportAsync` 透传 tls |
| `windows/src/MediaHub.SyncCli/CliInvocation.cs` | 105 → 116 | `Tls`、`ExpectFingerprint` |
| `windows/src/MediaHub.SyncCli/CliParser.cs` | 383 → 435 | `--tls`、`--expect-fingerprint`、三条校验、`invalid-fingerprint` |
| `windows/src/MediaHub.SyncCli/SyncCliRunner.cs` | 1906 → 1995 | serve 打印指纹、send 钉扎、roundtrip 自钉扎、JSON/text 新字段 |
| `windows/tests/MediaHub.Tests/SourceSyncTlsTransportTests.cs` | 380（新增） | 21 个用例 |
| `windows/tests/MediaHub.Tests/SyncCliTlsTests.cs` | 451（新增） | 16 个用例 |

## 14 复跑验收脚本（W6-28，真实两进程）

单元测试跑在同一个进程里，看不到"两个终端各自一条命令"时的握手/关闭行为。W6-28 补的就是这一段：

```powershell
pwsh -NoProfile -File windows/tools/acceptance/w6/transfer-tls-auth.ps1
# 参数：-OutDir（默认 D:/mh-verify/w6-28）、-SkipBuild、-KeepArtifacts
```

脚本串行跑 8 个组合，每个组合都真起 `transfer serve` 子进程（从 stderr 抓端口与 `certificate fingerprint:`）再跑 `transfer send` 子进程，超时即 kill 并记 FAIL：

| 用例 | 组合 | 断言 |
| --- | --- | --- |
| A | 明文 + 无认证 | send 0 / serve 0、frames=1、接收端恰好落 1 个信封 2 条来源 |
| B | 明文 + tag | send 0 / serve 0、frames=3、`authenticate`=true |
| C | TLS + 无认证 | send 0 / serve 0、`certificateFingerprint` 等于小写 pin |
| D | TLS + tag | send 0 / serve 0、frames=3、认证与指纹同时成立 |
| E | 错 pin（64 个 0） | send 2 `tls-fingerprint-mismatch`、接收端 `sync-sources.json` 与 `sync-baseline.json` **都不存在** |
| F | 明文 client → TLS listener | serve 2 `tls-handshake-failed`、零写入 |
| G | TLS client → 明文 listener | send 2 `tls-handshake-failed` / serve 2 `tls-not-enabled`、零写入 |
| H | TLS + 两端口令不同 | serve 2 `auth-mismatch`、零写入 |

实测（2026-10-24，W6 分支 `a6b13d4` + 本脚本）：**8/8 PASS，9.6 s**，退出 0，收尾断言本机无残留 `MediaHub.SyncCli` 进程；产物 `w6-28-summary.json` 记录每个用例的 exit code、稳定码、帧数、信封数与日志路径。

**验收脚本暴露的一个真实缺陷（尚未修）**：F 用例里 `transfer send` **exit 0 且不带任何稳定码**——它只写不读，明文帧进了 socket 缓冲区就算"成功"，而 TLS 监听端其实一个字节都没接收（`tls-handshake-failed`）。也就是说"发送端报成功"目前**不是**送达证据。E/G/H 同理（send 侧 0，只有 serve 侧报错）。候选修法见 W6-30。**已由 W6-30 修（默认关闭，开 `--confirm-delivery`）**：`transfer send` 写完后等待对端关闭连接——收到关闭则 `deliveryConfirmed=true`，预算（2000 ms）内没关或读取失败则 exit 2 + `delivery-unconfirmed`；不开此开关时线上字节与本文件描述完全一致。**已知残留盲区**：对端「拒绝后关闭」与「接收后关闭」在 TCP 层不可区分，因此本文件 F 用例重跑仍是 send 0（W6-30 文档第 6 节把这一点写成了可复跑的 F2 用例，而不是假装修好了）。

未动：`MediaHub.App` / `MediaHub.Providers` / `MediaHub.Domain` / `MediaHub.Storage` / `docs` 其它文件 / `LICENSE`；未 commit、未 push。

## 15 追加（W6-37 同期）：握手超时分类的 flaky 修正

**现象**：windows-ci 在 `ff286c9` 上唯一失败的是既有用例 `SourceSyncTlsTransportTests.The_handshake_gives_up_when_the_peer_never_speaks_tls`（`Failed 1 / Passed 1452 / Total 1453`，该例 810 ms）：断言等的是 `did not finish within`，实际 detail 是 `The caller cancelled the TLS handshake.`。

**根因**：`SourceSyncTransferListener.ReceiveOnceAsync` 先 `cts.CancelAfter(预算)`（accept 之前），再把**同一个 token** 当作握手的取消令牌传给 `AuthenticateServerAsync`；而握手内部从握手开始才计自己的 clock。accept 慢（CI 冷启动约 100 ms）时，握手自己的 deadline 还没到，caller 的预算 token 已经取消，于是真实超时被分类成「caller 取消」。这是竞态，不是断言写错。

**改法（确定性，不靠容差）**：`SourceSyncTlsTransport.AuthenticateServerAsync` / `AuthenticateClientAsync` 各自用 `CancellationTokenSource(deadlineAfter)` 建**独立 deadline**，再与 caller token `CreateLinkedTokenSource`；catch 时只看 `deadline.IsCancellationRequested` 判断是谁触发的——deadline 到点即 `did not finish within X s`（并列时归超时文案），否则才是 `The caller cancelled the TLS handshake.`。三个调用点（`ReceiveOnceAsync` / `SendOnceAsync` / client `RunAsync`）统一改成：只把**操作员 token** 传下去，预算余额用新助手 `SourceSyncTransferListener.Remaining(budget, elapsed)` 算成握手的 deadline。

**新用例（都在 `SourceSyncTlsTransportTests`，连跑 10 次全绿）**：

- `A_timeout_is_reported_as_a_timeout_even_when_the_accept_was_slow`：先起 900 ms 预算的接收，400 ms 后才让客户端连接 → 确定性复现 CI 的慢 accept 竞态，断言 detail 含 `did not finish within`。
- `A_caller_that_cancels_before_the_deadline_is_reported_as_a_cancellation`：客户端连上后 250 ms 取消 caller token（预算 10 s）→ 断言 detail 含 `caller cancelled` 且不含 `did not finish within`。
