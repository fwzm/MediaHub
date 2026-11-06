# W6-47 跨机（非回环）传输契约 ADR

| 字段 | 值 |
| --- | --- |
| 文档状态 | **EXECUTED_PASS（文档）** —— 本文件 2026-10-24 落盘，行号已逐条核对 |
| 实现状态 | **NOT_RUN** —— 仓库里没有任何跨机实现，本文件也不含实现 |
| 工作树 | D:/mh-w6，分支 feature/cross-device-source-sync，HEAD e137973 |
| 权威实现 | Windows：windows/src/MediaHub.Sync/**；Android：core/common/src/main/kotlin/com/mediahub/core/common/sync/** |
| 相关文档 | W6-24-TRANSFER-AUTH.md、W6-26-TLS-TRANSPORT.md（§16 三开关矩阵）、W6-30-DELIVERY-CONFIRM.md、W6-31-HOSTILE-INPUT.md、W6-37-RECEIPT.md（§14） |
| 变更范围 | 只新增本文件；未改代码、测试、脚本、CI、README |

本文件回答一个问题：**如果将来要允许非回环（跨机）传输，已经定死的契约里哪些可以直接复用、哪些必须重新决策、以及「跨机可用」需要什么证据才算数。** 它是一份决策记录（ADR），不是实现报告。

---

## 0 口径与禁止措辞

1. **允许的措辞**：契约可复用（结论）、复用现有码、需新开集合、NOT_RUN、回环内 PASS。
2. **禁止的措辞**：「已支持跨机」「已实现跨机」「跨机可用」「已验证跨机」。截至本文件落盘，跨机能力是 NOT_RUN；任何更积极的表述都是错的。
3. **每条断言必须可核**：要么给出 `文件:行号`，要么显式写 NOT_RUN。§7 记录了三处逐字抽查（抽查方法与结果）。
4. 「回环内」与「跨机」是两个不同的事实层：回环内的传输能力有实测证据；跨机没有。本文件不把前者当作后者的证据。

---

## 1 现状与硬边界

### 1.1 唯一的地址闸门

所有传输路径（监听、拨号、CLI、App 页面）都要经过同一个判定函数
`SourceSyncTransferAddress.TryResolveLoopback`（windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs:48）。它的接受集合是封闭的：

| 输入 | 判定 | 证据 |
| --- | --- | --- |
| null / 空白 | 归一为 `127.0.0.1`（`DefaultAddress`，:31） | SourceSyncTransferEndpoint.cs:53-57 |
| 长度 > 256 | 拒绝（detail 说明未被拨号） | :59-63 |
| `localhost`（大小写不敏感） | 归一为 `IPAddress.Loopback`，**不查 DNS** | :66-69 |
| `::1` | 归一为 `IPAddress.IPv6Loopback` | :70-73 |
| IPv4 / IPv6 字面量 | 必须满足 `IPAddress.IsLoopback`，否则拒绝 | :74-85（判定在 :76） |
| 其它（域名、主机名、网段） | 拒绝：本构建不为传输解析主机名 | :86-91 |
| 端口 | 0..65535 | :93-97 |

拒绝码是 `not-loopback`（SourceSyncTransferReasons.cs:27），理由是写在码旁边的：**绑定通配地址会让 Windows 弹出防火墙提示，而一个用户没要求的弹窗比一次被拒绝的传输更糟**（SourceSyncTransferReasons.cs:24-25；同样的理由在地址判定处重复了一次，SourceSyncTransferEndpoint.cs:78-80）。

### 1.2 监听侧与拨号侧的次序

- **监听**：先闸门（SourceSyncTransferEndpoint.cs:187-191，失败即 `not-loopback`），再创建证书（:193-199，失败即 `tls-certificate-unavailable`），最后才 `new TcpListener(...).Start()`（:201-207，`SocketException` 归 `listener-failed`，:209-213）。这个次序让 `listener-failed` 一定不是地址问题。
- **拨号**：`RunAsync`（:628）先闸门（:638-643），端口 0 单独拒绝（:645-650，`connect-failed`），然后才 connect。
- **TLS 层不重复闸门**：`SourceSyncTlsHandshake` 只负责握手与 pin（SourceSyncTlsTransport.cs:270、:386-393），不做任何地址判断。地址纪律只有一处，这是有意的——跨机改造必须改这一处、而不是在旁边新增一处。

### 1.3 UI 层根本没有「选地址」的入口

这一点比闸门更硬：

- 接收表单没有 host 字段：`PrepareReceive` 把 `SourceSyncTransferAddress.DefaultAddress` 写死传给 `SourceSyncTransferListener.Start`（windows/src/MediaHub.Sync/SyncTransferPresenter.cs:537-540）。
- 发送表单的 `Host` 默认值也是 `DefaultAddress`（:252），页面只让用户填端口（:255）。
- 唯一的回环提示文案是给 `not-loopback` 用的中文（:108）。

所以跨机的第一步不是「放开一个检查」，而是**产品形态变更**：得先有地方让用户/配对流程给出对端地址，才谈得上放开判定。

### 1.4 Android 侧的同构实现

| 位置 | 事实 | 证据 |
| --- | --- | --- |
| 地址闸门 | `LoopbackOnly.addressOrNull`：只解析字面量、不做 DNS、`isLoopbackAddress` 判定 | pairing/PairingTransportSupport.kt:74-97（判定在 :96） |
| 客户端拨号 | `withConnection` 先闸门，拒绝即 `not-loopback` | sync/SourceSyncTransferClient.kt:139、:145-149 |
| 监听 | `open` 先闸门，拒绝即 `not-loopback`（在创建 socket 之前） | sync/SourceSyncTransferListener.kt:289-293 |

两侧语义一致、码一致、连注释里给的拒绝理由都同源。跨平台契约的对称性目前是完整的——这也是第 4 节「归并」结论成立的前提。

### 1.5 因此：跨机在哪个字节之前失败

跨机地址会在**第一个 socket 调用之前**以 `not-loopback` 失败：零字节、零文件写入、不触发防火墙提示。这是既有设计的结果，不是缺陷；本文件不把它记为 bug。

### 1.6 能力状态表

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 回环内传输（明文 / TLS 1.3 + pin / HMAC 认证 / 回执 / 投递确认） | **PASS（回环内）** | windows/tools/acceptance/w6/transfer-tls-auth.ps1 18 例全 PASS；presenter-e2e 14 例；MediaHub.Tests 最近一次记录 1518 通过（W6-43 时点） |
| 跨机传输（任何非回环地址） | **NOT_RUN** | 闸门位置：SourceSyncTransferEndpoint.cs:74-91、:638-643；本次未改任何代码 |
| 跨机的 UI 入口 | **NOT_RUN** | SyncTransferPresenter.cs:252、:537-540 |
| 跨机的 pin 持久化（Android） | **NOT_RUN** | Android 只有 `SourceSyncTlsOptions.Pinned`（sync/SourceSyncTls.kt:81-86），没有注册表 |
| 跨机的防火墙交互记录 | **NOT_RUN** | 仓库内无任何非回环绑定的实测记录 |

---

## 2 信任模型

### 2.1 现有三类凭据

| 凭据 | 保护什么 | 从哪来 | 证据 |
| --- | --- | --- | --- |
| TLS 证书指纹（SPKI 的 SHA-256） | 对端是「我配对过的那一台」，不是中间人 | 带外（配对期核对） | PairingCertificate.cs:76；校验 SourceSyncTlsTransport.cs:439-446 |
| HMAC-SHA256 共享口令 | 信封未被篡改、未被重放 | 带外（环境变量） | SourceSyncTransferAuthenticator.cs:58-61、:136 |
| 回执字节 0x04 | 对端确实**解码**了这份信封 | 协议内 | SourceSyncReceiptFrames.cs:29-43；写入点 SourceSyncTransferEndpoint.cs:448-471 |

三类凭据都不依赖网络位置。这是「契约可复用」这个结论的全部依据。

### 2.2 证书是每次监听现生成的

- 每次监听生成一张自签 RSA-2048，有效期 2 小时，不落盘（PairingCertificate.cs:45、:30、:52、:69-71；注释写明 ephemeral by design、never stored，SourceSyncTlsTransport.cs:58）。
- 指纹算的是 **SubjectPublicKeyInfo**（PairingCertificate.cs:76），不是整张证书——换签同一把钥匙不会让 pin 失效。这一点是跨机 pin 能长期稳定的前提。
- 链校验被**显式放弃**：客户端回调恒 true（SourceSyncTlsTransport.cs:377），Android 的 `AcceptAnyCertificate` 把 `checkServerTrusted` 写成空实现（sync/SourceSyncTls.kt:573-576）。这不是疏忽——回环上没有可信 CA，pin 取代链。
- 结论：跨机**不需要**引入 CA/PKI。需要的是「pin 从哪来、存在哪、什么时候报警」的流程。

### 2.3 第一次见到对端意味着什么

- Windows 有持久信任锚：`PairedDeviceRegistry`（PairedDeviceRegistry.cs:155），`Verify` 三态 MATCH / NEW / MISMATCH（:218）；**MISMATCH 绝不覆盖已钉的指纹**（:287-290），容量满了拒绝而不是淘汰。
- 首次使用即 TOFU：NEW 会把当下看到的指纹钉住。风险点是首次配对时的中间人，缓解只能靠带外核对（今天 CLI 恒把指纹打到 stderr：windows/src/MediaHub.SyncCli/SyncCliRunner.cs:1362-1377，字面量在 :1369，非 JSON 时 stdout 也打一份 :1372-1376）。
- 跨机的要求因此是：**pin 必须来自持久信任锚**（注册表或同等物），不得来自「这次连接里看到的证书」——否则 pin 退化成自证。

### 2.4 口令的边界

- 结构：HMAC-SHA256 over `nonce ‖ envelope`（SourceSyncTransferAuthenticator.cs:58-61），tag 帧 kind 0x03、32 字节（:84、:87），常量时间比对（:136），nonce 缓存默认容量 64（:192）。
- 作者自己在注释里划了界：**没有密钥协商、没有轮换、同一 nonce 跨进程不防重放**（:70-73）。
- 口令来源只能是环境变量（SyncCliRunner.cs:1168-1190，读取在 :1185）；`--authenticate` 必须配 `--secret-env`（CliParser.cs:443-452）。
- 跨机影响：信道从「本机内核」变成「网线」，口令的强度与分发方式从可选卫生变成硬要求。

### 2.5 回执是唯一的正向证据

- 回执是**一个裸字节 0x04**（Windows：SourceSyncReceiptFrames.cs 的 `SourceSyncReceipt` 类；Android：sync/SourceSyncReceipt.kt:149、:152），只在信封解码成功之后写（SourceSyncTransferEndpoint.cs:448-471，解码闸门 :231-233、:460-465）。
- 回执**压过**「等对端关闭连接」：两者都开时以回执为准（SourceSyncTransferEndpoint.cs:513-516）。W6-30 已经记录过：对端拒绝与对端接受在本层看起来一样，close 是必要但不充分的证据。
- 跨机影响：NAT/防火墙更容易造出「半开」连接，close 语义在公网上更不可靠 → 跨机场景下回执必须默认开。

### 2.6 结论与缺口

**结论**：回环闸门不是信任来源，它只是「本机」这个事实的廉价代理。去掉它之后仍然成立的信任只有 2.1 的三类凭据，它们都不依赖网络位置，所以契约可以复用；但**「pin 的持久化」在 Android 上是缺口**（只有内存里的 pin 字符串，sync/SourceSyncTls.kt:81-86），因此跨机在 Android 侧整体 NOT_RUN。

---

## 3 十条实现约束（C1–C10）

以下十条是「如果将来做跨机，必须满足什么」。每条都是**可检查的**：给规则、给理由、给证据行号、给跨机时的处置。

### C1 地址判定只有一处

**规则**：任何新代码不得自己解析地址，只许调 `SourceSyncTransferAddress.TryResolveLoopback`（SourceSyncTransferEndpoint.cs:48）。
**理由**：闸门存在两处就意味着两套判断可以漂移；跨机后「哪一处被绕过了」会成为安全事件。
**跨机处置**：放宽时改这一处，并同时保证监听（:187）与拨号（:638）两条路径都还在它后面。

### C2 次序不可换：地址 → 证书 → bind

**规则**：监听必须先在 socket 之前拒绝地址（SourceSyncTransferEndpoint.cs:182-207），证书缺失也必须在 bind 之前失败（:193-199）。
**理由**：这个次序保证了 `listener-failed` 永远只表示「端口这一层」。反过来，若先 bind 再判地址，防火墙提示就已经弹出来了——正是 C1 想避免的事。

### C3 只认字面量，不引入 DNS

**规则**：地址必须是 IP 字面量；主机名不解析（SourceSyncTransferEndpoint.cs:86-91；Android 同判据 PairingTransportSupport.kt:88-90）。
**理由**：DNS 会引入一个不受 pin 保护的名字解析层，等于把信任前移到解析器。
**跨机处置**：跨机的对端地址由用户/配对流程给出 IP 字面量；「支持域名」必须作为独立决策（见 §6）。

### C4 协议版本钉死 TLS 1.3

**规则**：只协商 TLS 1.3，握手后复核（SourceSyncTlsTransport.cs:256、:319-325、:413-419；Android sync/SourceSyncTls.kt:247、:345、:514-519）。
**理由**：降级到 1.2 会让「pin 有效」这件事建立在更弱的握手上。
**跨机处置**：不改。允许降级即视为违约。

### C5 pin 必须来自持久信任锚

**规则**：pin 来自 `PairedDeviceRegistry`（PairedDeviceRegistry.cs:155、:218），MISMATCH 绝不覆盖（:287-290）；首次使用即 TOFU，必须在可读界面上让用户核对指纹。
**理由**：pin 的全部价值在于「它来自上一次人工确认，而不是这一次连接」。
**跨机处置**：Android 需要补一个持久信任锚（NOT_RUN，见 §2.6）。

### C6 认证默认开

**规则**：跨机场景不得默认 auth-off；开关的语义仍是「字节流不变」（SourceSyncTransferAuthenticator.cs:15-19 记录了为什么不把码并进传输集）。
**理由**：回环信道由内核保护；网线不做这个保证。
**跨机处置**：默认值从 Off 改为 On（今天是 Off）。

### C7 口令只从环境变量 / 凭据库读

**规则**：口令不进 argv、不进 JSON、不进 stderr（SyncCliRunner.cs:1168-1190；CliParser.cs:443-452）。
**理由**：命令行与日志会离开本机（进程列表、shell 历史、CI 日志）。
**跨机处置**：不改。若引入配置文件，必须复用同一「不可回显」纪律。

### C8 关闭必须先排水

**规则**：TLS 关闭要 close_notify + 排水，预算 2 秒 + 250 毫秒（SourceSyncTlsTransport.cs:260、:262；实现 :451-463）。
**理由**：TLS 1.3 服务端在握手后立刻发 session ticket；带着未读入站记录关 socket 是 abortive close，对端看到 RST，已排队的记录被丢弃——W6-26 的 windows-ci 红就是这么来的。
**跨机处置**：不改。真实网络的延迟只会让这个窗口更大。

### C9 预算分层

| 预算 | 值 | 证据 |
| --- | --- | --- |
| 业务收发 | 15 秒 | SourceSyncTransferEndpoint.cs:144 |
| TLS 握手 | 10 秒（Android）/ 独立 deadline（Windows） | sync/SourceSyncTls.kt:256；W6-26 §7 |
| 回执等待 | 2 秒 | SourceSyncReceiptFrames.cs:107 |
| 投递确认等待 | 2 秒 | SourceSyncDeliveryConfirm.cs:85 |
| TLS 关闭 + 排水 | 2 秒 + 250 毫秒 | SourceSyncTlsTransport.cs:260、:262 |

**规则**：不得用业务超时顶替握手超时（W6-26 记录过这类误判）。
**跨机处置**：数值可调，分层不可合并。

### C10 诊断不得回显敏感物

**规则**：指纹可以打印；口令、信封原文、tag 不行（TLS 选项的 ToString 已脱敏：SourceSyncTlsTransport.cs:189-191）。
**理由**：回环内日志不外流；跨机后日志是会被贴进 issue 的东西。
**跨机处置**：这条从「卫生」升级为「契约」，需要在评审清单里固定下来。

---

## 4 错误码归并结论

### 4.1 现有五个闭合集合

| 集合 | 码数 | Windows（行号） | Android（行号） |
| --- | --- | --- | --- |
| 传输 | 10 | SourceSyncTransferReasons.cs:27 not-loopback、:30 listener-failed、:33 connect-failed、:36 frame-invalid、:39 frame-truncated、:42 payload-too-large、:45 challenge-mismatch、:48 envelope-invalid、:51 transfer-timeout、:54 io-failed | sync/SourceSyncTransfer.kt:21-30（同序同名） |
| TLS | 4 | SourceSyncTlsTransport.cs:32 tls-handshake-failed、:35 tls-fingerprint-mismatch、:38 tls-certificate-unavailable、:41 tls-not-enabled | sync/SourceSyncTls.kt:31、:34、:37、:40 |
| 认证 | 5 | SourceSyncTransferAuthenticator.cs:28 auth-required、:31 auth-missing、:34 auth-mismatch、:37 auth-replayed、:40 auth-secret-unavailable | sync/SourceSyncTransferAuth.kt:19、:22、:25、:28、:31 |
| 回执 | 4 | SourceSyncReceiptFrames.cs:29 receipt-missing、:32 receipt-timeout、:35 receipt-invalid、:43 receipt-not-enabled | sync/SourceSyncReceipt.kt:18、:21、:24、:27 |
| 投递确认 | 1 | SourceSyncDeliveryConfirm.cs:31 delivery-unconfirmed | sync/SourceSyncDelivery.kt:20 |

### 4.2 同名同序与交集为空（证据）

- **同名同序**：上表每行两列逐条对齐，顺序一致；Android 常量名是 Windows 常量名的大写变形（NOT_LOOPBACK ↔ NotLoopback）。
- **交集为空**：五集合并无重名。前缀 `tls-` / `auth-` / `receipt-` / `delivery-` 只出现在各自集合里，传输集是无前缀的十个词，两者不重叠。
- **已冻结的证据**：SourceSyncTransferAuthenticator.cs:15-19 明确记录——扩那个十码集合会「silently widen a set other platforms already switch over」，所以认证码另开了一个集合。跨机必须沿用同一条纪律。

### 4.3 结论

**结论 A —— 跨机不新开第 6 个码集合。** 每一种跨机失败都能落进现有五集：地址不被接受 → `not-loopback`；连不上/被拒绝 → `connect-failed`；端口被占 → `listener-failed`；对端不说话 → `transfer-timeout`；连接中途断 → `io-failed`；握手谈不成 → `tls-handshake-failed`；pin 不符 → `tls-fingerprint-mismatch`；口令不符/缺失/重放 → `auth-*`；对端没回执 → `receipt-*`；对端没关连接 → `delivery-unconfirmed`。

**结论 B —— `not-loopback` 保留原义，不得改用来表示「连不上」。** 闸门放宽后，这个码只剩一个含义：**调用方给的地址不被本构建接受**（不是字面量，或不在允许集合内）。「连不上」是 `connect-failed`（SourceSyncTransferReasons.cs:33）。用 `not-loopback` 表示网络不可达会让两端的 switch 语义漂移，也会让既有的用例失效。

**结论 C —— 若将来确实需要新码（例如 firewall-blocked、interface-unavailable），必须新开集合，不得追加到十码集合。** 且必须同时满足三条：① Windows 与 Android 同名同序；② 与现有五集交集为空；③ 有跨两端的用例（Windows 脚本 + Android 单测各一）。

### 4.4 新开集合的验收清单（模板）

1. 集合名与职责一句话写清（「这个集合回答什么问题」）。
2. 码表：码 / 何时产生 / 谁写 / 谁读 / 是否终态。
3. 与既有五集逐一比对：无重名（同名同序检查表）。
4. 两端常量表并列贴出，行号引用。
5. 用例：产生该码的最小复现 + 断言「没有别的码同时出现」。
6. 文档落点：本文件 §4 追加一行，并在对应传输文档里写明「新增集合，不是扩集」。

### 4.5 明确不采用的三种做法

1. **扩十码集合**：违反 SourceSyncTransferAuthenticator.cs:15-19 记录的冻结纪律，且会让 Android 端 switch 静默走 fallback。
2. **白名单开关 + 复用 `not-loopback`**：把「地址被策略拒绝」和「地址不是回环」压成同一个码，等于删掉了诊断信息。
3. **只在 Windows 加新码**：破坏 §4.2 的同名同序，跨平台契约当场失效。

---

## 5 DoD 证据清单（全部 NOT_RUN）

「跨机可用」不能靠推理成立。下表是把它变成事实所需要的证据；**今天一条都没有**。

| # | 需要的证据 | 今天 | 备注 |
| --- | --- | --- | --- |
| E1 | 非回环地址上的一次成功传输（两台真机或两进程 + 真实网卡），两端 exit 0、frames 与 records 符合预期 | **无（NOT_RUN）** | 现有 18 例脚本全部是 127.0.0.1 |
| E2 | 防火墙交互实测记录：首次绑定是否弹窗、用户允许/拒绝后各得到什么码 | **无（NOT_RUN）** | 代码注释只是设计意图，不是实测 |
| E3 | pin 三态在跨机路径上的实测：首次 NEW、再次 MATCH、换指纹 MISMATCH 且旧 pin 未被覆盖 | **无（NOT_RUN）** | 回环内已有单测；跨机路径没有 |
| E4 | TLS × 认证 × 回执 三开关矩阵在非回环地址上重跑，结果与回环矩阵一致，或差异有逐条解释 | **无（NOT_RUN）** | 回环矩阵 18/18（W6-45） |
| E5 | 无敏感物泄漏：secret 不出现在 stdout/stderr/JSON/日志 | **无（NOT_RUN）** | 回环有断言；跨机未测 |
| E6 | 真实网络下关闭语义稳定性：重复 N 次，0 次因 RST 丢帧 | **无（NOT_RUN）** | W6-26 修过同类 flaky |
| E7 | 回退证据：改动后回环矩阵仍 18/18、presenter-e2e 仍 14/14 | **回环侧基线存在**；跨机不适用 | 这是「没把回环搞坏」的证明 |
| E8 | 文档一致性：本文件的行号在改动后仍可核（改了就更新） | **无（NOT_RUN）** | 见 §7 的方法 |

---

## 6 明确不决定项

以下问题本文件**不**作答，留给后续决策（列出来是为了防止它们被当成「已经定了」）：

1. **对端地址从哪来**：手工输入、配对二维码、还是 mDNS 发现。`PairingAddressCandidates` 目前是 display-only（PairingAddressCandidates.cs:105），不做任何绑定。
2. **是否支持 IPv6 非回环**：今天 `::1` 之外的 IPv6 一律拒绝（SourceSyncTransferEndpoint.cs:74-85）。
3. **证书是否长期化**：现在是每次监听 2 小时的临时自签（PairingCertificate.cs:30）；长期证书会改变 pin 的生命周期语义。
4. **UI 形态**：是在同步页加地址框，还是只允许经由配对流程产生地址（今天是后者，因为表单里根本没有 host）。
5. **公网穿透 / 中继**：本文件只讨论「同一局域网内的非回环」，不涉及 NAT 穿透、中继、打洞。
6. **Android 端信任锚的存储方案**：DataStore / SharedPreferences / Keystore 各有取舍，未定。
7. **跨机是否允许「明文 + 口令」**：协议今天允许，但 §2.4 说明它不提供机密性；这是产品决策，不是实现细节。
8. **什么时候动手、怎么评审**：本文件只给契约，不排期。

---

## 7 引用抽查记录

**核对方法**：逐个文件按行号读取同一行（`read` 精确取该行文本），与本文引用逐一比对；随后用脚本批量复核了全部引用行，初检发现两处偏差并已按实际行号修正（TLS 的 pin 失配分支实际在 SourceSyncTlsTransport.cs:439-446；CLI 指纹方法实际在 SyncCliRunner.cs:1362-1377）。

**逐字抽查三处**：

1. windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs:76（§1.1）
   `if (!IPAddress.IsLoopback(parsed))`
2. windows/src/MediaHub.Sync/SourceSyncTlsTransport.cs:444-445（§2.2）
   `The certificate the peer presented does not match the pinned fingerprint, so the connection was`
   `closed before a single envelope byte was read or written.`
3. core/common/src/main/kotlin/com/mediahub/core/common/sync/SourceSyncTransfer.kt:21（§4.1）
   `const val NOT_LOOPBACK: String = "not-loopback"`

---

## 附录 A 如果真要动手：改动点速查（全部 NOT_RUN）

| 改动点 | 位置 | 说明 |
| --- | --- | --- |
| 地址闸门 | SourceSyncTransferEndpoint.cs:48-99 | 唯一放宽点；监听与拨号都走它 |
| 监听次序 | SourceSyncTransferEndpoint.cs:182-213 | 保持「地址 → 证书 → bind」 |
| 拨号闸门 | SourceSyncTransferEndpoint.cs:628-650 | 端口 0 的单独拒绝要保留 |
| 发送表单 | SyncTransferPresenter.cs:252、:773-777 | `Host` 目前默认 127.0.0.1；跨机需要新的默认与校验策略 |
| 接收表单 | SyncTransferPresenter.cs:537-540 | 目前写死 `DefaultAddress`，没有 host 字段 |
| 信任锚 | PairedDeviceRegistry.cs:155、:218、:287-290 | pin 的唯一合法来源 |
| Android 闸门 | pair/PairingTransportSupport.kt:74-97；sync/SourceSyncTransferClient.kt:145-149；sync/SourceSyncTransferListener.kt:289-293 | 三处必须同步放宽 |
| Android 信任锚 | （缺） | 目前只有 sync/SourceSyncTls.kt:81-86 的内存 pin |
| CLI | SyncCliRunner.cs:1362-1377；CliParser.cs:443-452 | 指纹打印与口令来源 |
