# W6-12 已配对设备登记、配对分享链接与地址候选

- 状态: **IMPLEMENTED_UNVERIFIED**（代码已实现、22 个新测试全绿、全套 1004 例通过、App 工程 0 警告 0 错误；本轮**没有** GUI 证据）
- 日期: 2026-10-24
- 分支/HEAD: `feature/cross-device-source-sync` / `a75139b`（实现提交 `67b8446`，已 push；已 merge `feature/windows-client` `388200d`）
- 前置: W6-3b 配对传输（回环 TLS + 邀请码）、W6-3c 配对 UI、W6-8 审批门禁、W6-10 基线落盘
- 范围: 只动 `windows/src/MediaHub.Sync`（4 个已有文件 + 9 个新文件）、1 个新测试文件、本文档；未动 `MediaHub.App` / Providers / Domain / Storage，未启动 GUI

## 1 语义边界：配对成功 ≠ 已同步 ≠ 已登录

| 说法 | 实际含义 | 不表示 |
| --- | --- | --- |
| 配对成功（`PairingHostState.Paired` / `PairingGuestResult.Paired`） | 两台设备走完一次回环 TLS 握手，互相出示了一张**名片**，握手在 60 秒内结束 | 不表示两台设备此后可信，不表示任何数据已交换 |
| 已登记（`PairedDeviceRegistry.Register`） | 本机在 `paired-devices.json` 里记住了对端 `DeviceId` + 指纹 + 首见/末见时间 | 不表示对端在线、不表示对端同意同步、不表示已交换来源或密钥 |
| 已同步 / 已登录 | 本任务**不产生**这两个状态 | 配对既不搬运来源，也不搬运凭据；来源导入仍只在 W6-8/W6-10 的显式审批路径里发生，登录仍要用户逐个来源点「验证」 |

因此本层所有面向用户的文案都不出现「已同步」「已登录」；登记结果只描述「已记住对端：<名字>」。

## 2 交付物

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/PairedDevice.cs` | 94 | 不可变登记项：`DeviceId` / `Fingerprint` / `DisplayName` / `PairedAtEpochMs` / `LastSeenEpochMs` / `SourceCount` + `Create` / `DisplayNameOrId` |
| `windows/src/MediaHub.Sync/IPairedDeviceStore.cs` | 53 | 存储抽象：`GetAll()` / `Save(PairedDevice)` / `Forget(string)` / `LoadError` + `PairedDeviceStoreReasons`（稳定原因码 + `All`） |
| `windows/src/MediaHub.Sync/InMemoryPairedDeviceStore.cs` | 112 | 测试与无盘环境用；`WriteCount` 记录写次数，`Capacity` 可注 |
| `windows/src/MediaHub.Sync/FilePairedDeviceStore.cs` | 439 | `%LOCALAPPDATA%\MediaHub\paired-devices.json`，`version`+ `devices` 文档；临时文件 + `Flush(true)` + `File.Replace`（保留 `.bak`，目标不存在时回落 `File.Move(overwrite: true)`）、损坏文件**隔离且永不覆盖**；`LoadError` / `LastQuarantinedPath` / `WriteCount` |
| `windows/src/MediaHub.Sync/PairedDeviceRegistry.cs` | 415 | 指纹钉扎核心：`Verify`（三态）/ `Register`（Added / Updated / Unchanged / Rejected）/ `Forget` / `Describe` / `DescribeReason`；容量默认 32，满了**拒绝**而不是静默挤掉旧设备 |
| `windows/src/MediaHub.Sync/PairingLink.cs` | 115 | `mediahub://pair?code=<base64url>` 分享链接；`Create` / `ExtractCode` / `Parse`，最长 8192，复用 `PairingCode` 的编解码与过期判定 |
| `windows/src/MediaHub.Sync/PairingAddressCandidates.cs` | 324 | 把网卡枚举/注入的适配器排成候选地址（有线 > 无线 > 其他），逐条给原因码；**只用于显示**，不监听任何非回环地址 |
| `windows/src/MediaHub.Sync/PairingPeerCard.cs` | 215 | 名片：`DeviceId` / `DeviceFingerprint` / `DisplayName` / `SourceCount` + `PairingPeerCardCodec`（长度上限、字段校验、`TryDeserialize` 绝不抛） |
| `windows/src/MediaHub.Sync/PairingSelfIdentity.cs` | 65 | 本机身份：`DeriveFingerprint(deviceId) = SHA256("mediahub-device:" + deviceId)`、`ToCard()`、`Fingerprint.ToDisplayString()` |
| `windows/src/MediaHub.Sync/PairingTransportResult.cs` | 82 | 加 `PeerPayload`（对端名片，可空）+ `Success(payload, protocol, peerPayload)` |
| `windows/src/MediaHub.Sync/PairingTransportServer.cs` | 558 | 新增可选项 `PeerReplyTimeout` / `SelfCard`：写完邀请帧后**才**读一张对端名片并写回自己的；超时/坏帧只当「没有名片」，绝不把已送达的邀请变成失败 |
| `windows/src/MediaHub.Sync/PairingTransportClient.cs` | 204 | 新增可选项 `Reply` / `ReplyTimeout`（默认 5 秒）：收到邀请后写自己的名片并读回 ack |
| `windows/src/MediaHub.Sync/PairingPresenter.cs` | 804 | 可选 `PairedDeviceRegistry`：主机在 Paired 时登记对端名片，客机只登记主机名片；指纹不一致 → 配对失败（`device-fingerprint-mismatch`） |
| `windows/tests/MediaHub.Tests/PairedDeviceRegistryTests.cs` | 491 | 22 个 [Fact]：存储/登记/链接/候选/两端真回环配对 |

## 3 指纹钉扎规则

`PairedDeviceRegistry.Verify(deviceId, fingerprint)` 三态：

| 情形 | `State` | `Reason` |
| --- | --- | --- |
| `deviceId` 为空 | `New` | `missing-device-id` |
| 未登记过 | `New` | `fingerprint-new`（指纹本身坏则 `missing-fingerprint`） |
| 已登记且指纹一致 | `Match` | `fingerprint-match` |
| 已登记但指纹不同（含指纹无法解析） | `Mismatch` | `fingerprint-mismatch`（无法解析则 `missing-fingerprint`），并带出 `PinnedFingerprint` 供 UI 展示 |

`Register` 的关键不变量：

1. **MISMATCH 永不覆盖已钉扎的指纹**——返回 `Rejected(Mismatch, ...)`，`paired-devices.json` 一个字节都不写，旧名字/旧指纹原样保留。这条是钉扎的全部意义：发现变化只报警，不自动接受。
2. **重复观测不写盘**：与既有登记完全一致时返回 `Unchanged`（`Accepted=true, Added=false`，`WriteCount` 不变），只有 `LastSeenEpochMs` 真的前进才写。
3. **首见时间只写一次**：`PairedAtEpochMs` 沿用旧值，`LastSeenEpochMs` 取两者较大值。
4. **空白名字不覆盖旧名字**（第一次给的空名字不会把后来的「客厅」冲掉）。
5. **容量默认 32，满了拒绝**（`limit-reached`），不静默淘汰——被淘汰的设备下次会被当成新设备，等于无声降级安全等级。
6. 存储写失败（`IOException` / `UnauthorizedAccessException`）转成 `Rejected(..., store-unreadable)`，**不让异常穿过 UI**。

原因码固定为 `fingerprint-new` / `fingerprint-match` / `fingerprint-mismatch` / `missing-fingerprint` / `missing-device-id` / `limit-reached` / `store-unreadable` / `write-failed` / `unchanged` / `updated`，且 `DescribeReason` 为每条给出中文说明（测试断言每条都非空、且不回显裸 reason 码）。

## 4 存储与容错

- 位置：`%LOCALAPPDATA%\MediaHub\paired-devices.json`（与 `sync-sources.json` / `sync-baseline.json` 同目录，便于整体备份）。
- 文档形状：`{ "version": 1, "devices": [...] }`；未知版本按 `unsupported-version` 记 `LoadError` 并当空集合处理，**不覆盖**原文件。
- 原子写：同目录临时文件 + `File.Move(overwrite: true)`，并保留一份 `.bak`；测试断言写完不留 `*.tmp`。
- 损坏隔离：解析失败时把原文件逐字节移到 `paired-devices.corrupt-<时间戳>`（`LastQuarantinedPath` 可查），随后才允许新建；被隔离的文件**之后也不会被覆盖**。这样用户能事后取回证据，而不是被一个空文件抹掉。
- 读失败不抛：`GetAll()` 永远返回（可能为空的）集合 + `LoadError`，由调用方决定怎么提示。

## 5 传输层：可选的第二段名片交换

W6-3b/W6-3c 的线是**单向**的：服务端只写一帧邀请，写完就认为配对结束，因此主机永远不知道对端是谁（`PairingHostSnapshot.PeerDeviceId` 一直是 `null`）。W6-12 在不破坏旧行为的前提下加了一段**可选**交换：

- 服务端 `PairingTransportServerOptions.PeerReplyTimeout`（默认 `null` = 旧单向行为）与 `SelfCard`；设置后写完邀请帧再读一张对端名片、写回自己的名片。
- 客户端 `PairingTransportClientOptions.Reply`（默认 `null`）与 `ReplyTimeout`（默认 5 秒）。
- 失败语义：名片读不到、超时、帧损坏、序列化失败一律**只当没有名片**（`PeerPayload = null`），邀请已经送达这件事不变——不因为一段附加信息把主流程判死。
- `PairingPresenter` 仅在注入了 `PairedDeviceRegistry` 时才启用这段交换，所以既有的单向测试与单向使用路径完全不受影响（无 registry 时主机侧仍得到 `PeerDeviceId = null`，有专门测试钉住这一点）。

视觉上唯一的代价：主机现在能看到对端的 `DeviceId` 与指纹，这比「主机完全不知道对端是谁」信息更多，但**前提是对端愿意出示名片**；对端不出示时退回单向语义。

## 6 TOFU：名片是自述的，钉扎只防变化不防首遇

`PairingSelfIdentity.DeriveFingerprint` 是 `SHA256("mediahub-device:" + deviceId)`——**从设备自报的名字算出来的**，不是密钥，也不是证书指纹。含义必须说清楚：

- 它对「同一台设备」稳定，所以能用来发现**第二次配对换了个设备冒名**（MISMATCH 报警），这正是本轮钉扎要解决的问题。
- 它对**首次配对（Trust On First Use）**没有任何保护：任何设备都可以自称 `dev-abc` 并算出对应指纹，第一次配对时本机无从分辨。要真正防首遇冒充需要身份密钥对与签名，那超出本轮范围（见 §9）。
- **邀请里那个指纹不能钉**：它是每次配对新生成的临时证书指纹，登记它必然导致下次配对假 MISMATCH。所以客机只在收到主机**名片**时登记，否则不登记——这条是 W6-12 期间最容易被写错的地方。

## 7 配对分享链接

- 形状：`mediahub://pair?code=<base64url>`，`code` 就是 W6-3c 的邀请码（`PairingCode`），所以链接**继承了同一套过期与格式校验**，没有第二套令牌。
- 解析失败只有四个原因：`pairing-link-empty` / `not-a-pairing-link` / `pairing-link-too-long`（≥ 8192 字符）/ 邀请本身的原因码（`expired` 等，直接透传 `PairingCode` 的结论）。
- 链接与邀请码一样是**敏感信息**：`Parse` 的失败结果里不带原始 challenge/链接内容（`ToString()` 只描述原因），避免它被贴进日志或错误提示。
- 链接不改变信任模型：点开链接仍然只是一次配对，不传递任何账号或来源数据。

## 8 地址候选：只显示，不监听

`PairingAddressCandidates.Describe` 是**纯函数**，输入适配器即可算出候选顺序，因此测试不需要真实网卡；无参的 `Describe()` 才去问系统，且过滤掉回环/隧道接口。

- 排序：有线（Ethernet / Gigabit / FastEthernet）> 无线（Wireless80211）> 其他；同优先级按地址字节、再按接口名，保证输出确定。
- 拒绝原因（逐条保留，不静默丢弃）：`loopback-address`、`apipa-address`（169.254/16）、`interface-down`、`not-ipv4`（IPv6 或无法解析的条目）；隧道接口记为 `other`。全部候选都被拒时 `Report.Reason = no-usable-address`，UI 才有话可说而不是干瘪的空列表。
- **为什么不允许绑定非回环地址**：配对服务端的设计前提是「本机回环 + 邀请码」，一旦监听 `0.0.0.0`/局域网地址，Windows Defender 防火墙会当场弹「是否允许 MediaHub 通过防火墙」，而无人值守的配对流程既不该弹窗也无权替用户放行；此外长期监听局域网端口等于把一个认证面暴露在共享网络上。因此候选地址只用于**显示**（告诉用户「对方可以连这个地址」，例如手工转发或自建隧道），本进程绝不 bind 它们。

## 9 未覆盖 / 留待后续

| 项 | 现状 | 为什么不在本轮 |
| --- | --- | --- |
| 已配对设备清单的 UI | 未做：`PairedDeviceRegistry` 目前只在 `PairingPresenter` 内部被调用，SyncPage 还没有「已配对设备」卡片，用户看不到、也无法「忘记」某台设备 | 需要新的 UI 面（列表 + 忘记按钮 + 指纹对比文案），与本轮「存储 + 钉扎 + 链接 + 候选」的边界不同；忘记接口 `Forget` 已就绪 |
| 真实第二台设备 / 真实局域网 | 未验证：全部测试都是同一进程内回环 + 合成适配器 | 配对验收要求不弹防火墙、不干扰使用者，跨机验证需单独的验收窗口 |
| 首遇冒充 | 未解决（TOFU） | 需要设备身份密钥对与签名交换，属协议层改动 |
| 指纹不一致后的恢复路径 | 只有 `Forget`（代码级），没有 UI 也没有「我认为这是我换过机器」的显式确认流程 | 与 §9 第一条的 UI 一起做 |
| 容量 32 | 硬编码默认值，超过即拒绝 | 单机配对设备数量现实中很小；给 UI 前不引入配置项 |
| 链接的撤销/失效 | 沿用邀请码过期；无法主动吊销已发出的链接 | 邀请本身是短时令牌，主动吊销需要服务端状态，当前无状态设计 |

## 10 测试

`windows/tests/MediaHub.Tests/PairedDeviceRegistryTests.cs`（22 个 [Fact]，无网络依赖）：

- 存储：原子写不留临时文件、`.bak` 存在、文档 version=1；损坏文件逐字节隔离且原路径被移除、隔离后仍可新建、隔离副本不被覆盖；缺文件=空存储非错误；输出顺序确定；容量满拒绝。
- 登记：三态 Verify、拒绝覆盖钉扎指纹、只前进 `LastSeen` 保留 `PairedAt`、重复观测不写盘、`Forget` 后回到 New、空 id/坏指纹拒绝、写失败转 `store-unreadable`、原因码集合稳定且中文说明不回显裸码。
- 链接：往返、拒绝非链接/超长/空、过期不泄露 challenge。
- 候选：有线优先于无线、回环/APIPA/接口 down/非 IPv4 各自原因、无可用地址时给 `no-usable-address`。
- 集成：注入 registry 后**真回环配对**会给两侧各登记一行；同两台设备第二次配对仍只有一行且指纹匹配；主机侧预置不同指纹时配对失败且旧记录不被覆盖；不注入 registry 时保持单向（`PeerDeviceId` 为 null、不落任何文件）。

结果：筛选运行 22/22 通过；全套 `MediaHub.Tests` **1004 通过 / 0 失败**（基线 982 + 本轮 22）。App 工程构建 0 警告 0 错误（本任务未改 App，构建只作为不回归的证据）。
