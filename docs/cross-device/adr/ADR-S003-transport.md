# ADR-S003 —— 配对传输层：临时回环监听、TLS 1.3 与证书钉扎

- 状态：**已提议**（W6-3b 产出：传输层已实现，17 例真实回环测试钉住）
- 日期：2026-10-23
- 前置：[ADR-S001](./ADR-S001-source-sync-contract.md) D6（安全边界）、[ADR-S002](./ADR-S002-pairing.md)（载荷、状态机、原因码）
- 实现：`windows/src/MediaHub.Sync/PairingTransportServer.cs`、`PairingTransportClient.cs`、`PairingCertificate.cs`、`PairingFrame.cs`、`PairingTransportResult.cs`、`PairingTransportReasons.cs`
- 测试：`windows/tests/MediaHub.Tests/PairingTransportTests.cs`

## 问题

ADR-S002 把载荷压到 6 个字段、给了 120 秒窗口与一次性挑战，但"这段 JSON 怎么送过去"没有定：监听绑在哪、端口谁定、用哪种 TLS、证书从哪来、对端凭什么相信它不是中间人、配完之后端口会不会留着、失败怎么归因。

如果这些留给 UI 各自决定，第一个能跑通的实现就会成为事实标准 —— 而"先连上，指纹不一样弹个警告继续"正是最容易顺手写下的版本。本 ADR 把它们写成实现契约。

## 决策

### D1：只绑 `127.0.0.1`，端口由系统分配

- 监听固定为 `TcpListener(IPAddress.Loopback, 0)`，backlog 4。**不绑 `0.0.0.0`、不动防火墙、不常驻**。绑通配地址等于把一个一次性配对入口挂到整个 LAN 上，而且需要用户单独授权防火墙规则；`port 0` 还顺带让测试能并行跑、不依赖固定端口。
- 实际端口写进邀请（`ephemeralUrl = https://127.0.0.1:{port}/pairing`）。这样 ADR-S002 D1 对 URL 的要求（禁止 user-info/query/fragment）由**构造**保证，而不是靠调用方自觉。
- 生命周期是硬边界：成功配对一次、到 120 秒、调用方取消、抛异常，任何一条路径都必须 `StopAccepting()`（`_stopped = 1` + `listener.Stop()`），端口随即可以重新 bind。**不做**"配完了继续听以便重试"。

### D2：TLS 1.3 强制，不自建 PKI，也不自创加密

- 两端都显式 `EnabledSslProtocols = SslProtocols.Tls13`，并且握手后再硬校验 `ssl.SslProtocol == Tls13`。双保险的理由：允许集只是"上限声明"，不同 SChannel 版本的下限行为不完全一致；一次硬校验把"以为限了、其实没限"变成明确的 `tls-version` 失败，而不是静默降级到 1.2。
- 证书是每次 `Start` 现生成的自签名 X509（RSA 2048 / SHA-256，SAN `localhost` + `127.0.0.1`，EKU serverAuth，有效期 ±2 小时），**不申请 CA、不写证书库**。身份来自指纹钉扎（D3），CA 链在这个场景里没有可验证的主体。
- 生成时必须走一趟 PFX 往返（`Export(X509ContentType.Pfx)` → `X509CertificateLoader.LoadPkcs12(pfx, null, Exportable)`）：Windows SChannel 会拒绝从未进入 key container 的私钥，省掉这步握手会直接失败。
- 客户端的 `RemoteCertificateValidationCallback` 恒返回 true，然后**自己比对指纹**。这不是"忽略证书错误"，而是把信任根从系统 CA 换成二维码里的 64 位 hex —— 回调里返回 false 只能得到一句模糊的 TLS 错误，而我们需要可归因的 `fingerprint-mismatch`。
- 不自创加密（ADR-S001 D6.2）：TLS 1.3 提供机密性与前向保密，本层只负责**认人**。

### D3：钉扎发生在**写任何载荷之前**

- 钉的值是 SHA-256(SubjectPublicKeyInfo)，不是证书 DER：同一密钥重签证书，指纹不变。
- **服务端自检**：`Start` 时算自己的指纹，与邀请里公布的 `AdvertisedFingerprint` 用 `PairingFingerprint.Matches`（`FixedTimeEquals`）比较。不一致时（典型场景：二维码是旧证书打印的，或测试注入了一个过期广告值）返回 `fingerprint-mismatch` 且**一个字节都不写** —— 不能让对端拿到"我们的载荷 + 别人的指纹"这种半真半假的组合。
- **客户端**：握手后从 `ssl.RemoteCertificate` 算指纹，与 `ExpectedFingerprint` 固定时间比较；不符立即断开，**不读载荷**。载荷内的 `publicKeyFingerprint` 再比一次，用来覆盖"证书对、载荷是别人的"。
- 没有"警告但继续"的分支。钉扎失败即终止，这是 ADR-S001 D6 的原文要求。

### D4：一次性与生命周期 —— 取消，而不是等

- 一次 `Start` 只服务**一次**成功配对：`AcceptOneAsync` 用 `Interlocked.CompareExchange` 抢占 `_pairing`，第二次调用直接 `already-paired`；配对成功后置 `_paired` 并立刻 `StopAccepting()`，第二台设备连不上（测试用真实 TCP 连接断言"连接被拒"，不是只断言返回值）。
- `Lifetime`（默认 120 秒，ADR-S002 D2）与调用方 `CancellationToken` 用 linked CTS 合并；到点/取消时 `Cancel` + `listener.Stop()` + 关闭活跃 `SslStream`，`AcceptOneAsync` 立即返回，不留后台线程或计时器。
- 区分 `lifetime-exceeded`（我们自己的窗口到点，提示"重新生成二维码"）与 `peer-timeout`（调用方预算或握手超时，提示"没等到对方"）：两者的用户动作完全不同，不能合并成一个"超时"。
- 握手单独设 5 秒上限：只接受连接不算"配对进行中"，挂着不发 ClientHello 的连接必须被主动切断。

### D5：载荷只走 `PairingPayloadCodec`，帧有上限

- 帧 = 4 字节大端长度 + body，`MaximumPayloadBytes = 8192`。用定长前缀而不是"读到 EOF"：对端不发 FIN 时，读到 EOF 会一直等下去。
- 读写都只调用编解码器。传输层不认识字段，因此**不可能**"顺手多带一个 token"。
- 日志与异常消息不含 challenge 原文：`PairingPayload.ToString()` 与 `PairingTransportResult.ToString()` 都改写成 `challenge=<redacted>`。record 的默认 `ToString()` 会把一次性挑战打满整个日志，这是最容易漏的一处泄漏。

### D6：稳定原因码表（只加不改）

| 码 | 触发条件 | 用户可见含义 |
| --- | --- | --- |
| `listener-failed` | 监听建立失败、接受连接失败、已停止的 listener 被再次使用 | 临时通道没建起来，重试 |
| `tls-version` | 握手失败，或协商结果不是 TLS 1.3 | 对方或系统不支持安全连接，需升级 |
| `fingerprint-mismatch` | 证书指纹与钉住值不一致（服务端自检或客户端校验） | 二维码过期或对方不可信 |
| `payload-invalid` | 帧超限/截断、JSON 畸形、缺字段、指纹字段不合规 | 二维码内容损坏 |
| `lifetime-exceeded` | 120 秒窗口到点 | 二维码已过期，重新生成 |
| `already-paired` | 已配对，拒绝新的连接/新的 accept | 已经配对过了 |
| `peer-timeout` | 调用方取消，或握手/读取超时 | 没等到对方 |

与 ADR-S002 D4 的表**分开**：那里是"载荷内容不合法"，这里是"通道没能建立"。UI 按码翻译，日志按码分组。

## 实现映射

| 契约点 | 位置 |
| --- | --- |
| 临时监听、TLS 1.3、服务端自检、一次性 | `windows/src/MediaHub.Sync/PairingTransportServer.cs` |
| 客户端钉扎与载荷读取 | `windows/src/MediaHub.Sync/PairingTransportClient.cs` |
| 临时自签名证书与 SPKI 指纹 | `windows/src/MediaHub.Sync/PairingCertificate.cs` |
| 长度前缀帧（8 KiB 上限） | `windows/src/MediaHub.Sync/PairingFrame.cs` |
| 结果对象与不泄漏的 `ToString()` | `windows/src/MediaHub.Sync/PairingTransportResult.cs` |
| 7 个原因码 | `windows/src/MediaHub.Sync/PairingTransportReasons.cs` |
| 17 例回环测试 | `windows/tests/MediaHub.Tests/PairingTransportTests.cs` |

## 测试

全部是真实回环 socket、`port 0`，每个用例自清理（不依赖固定端口，不留监听）。

- 正向：客户端钉扎通过 → 收到 6 字段载荷 → 两端都记录 TLS 1.3；服务端在写完后关闭监听。
- 拒绝：旧邀请（服务端自检）、客户端 pin 不符、TLS 1.2 客户端、明文 HTTP、坏帧/坏 JSON、已配对后的第二次 accept、并发两连接只放行一个。
- 生命周期：300 ms 取消 → `peer-timeout` 且 2 秒内返回；300 ms lifetime → `lifetime-exceeded` 且端口可重绑；`Dispose` 幂等且端口可重绑；配对后新客户端连接被拒。
- 不泄漏：`result.ToString()`/`payload.ToString()` 不含 challenge；线上序列化恰好 6 个 camelCase 字段且**每个值只能是标量**（结构断言，不用词表）。
- 结论行：`dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo` → 失败 0，通过 597，总计 597（基线 580 + 新增 17），耗时 4 s。

## 后果

- 正向：配对通道短、窄、可判定 —— 回环 + 一次性 + 120 秒 + 指纹钉扎，任一条件不满足就以稳定码失败；端口与线程在配对结束后确定性地消失，测试可以在 CI 里反复跑而不撞端口。
- 代价：每次配对现生成证书（几十毫秒），且没有 CA 链 —— 第三方工具若想校验，只有指纹可用。传输层依赖 Windows SChannel 的 TLS 1.3（Win10 1809+），换平台需同条件。
- 未覆盖（留给后续）：二维码的渲染与扫码、多网卡时 `ephemeralUrl` 选哪个地址、用户确认的 UI 文案、跨进程重启后的挑战 ledger 持久化、IPv6 回环、以及非 .NET 对端的 ALPN 兼容性（本层不发 ALPN，也不要求对端发）。
