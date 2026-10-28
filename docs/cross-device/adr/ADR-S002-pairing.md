# ADR-S002 —— 跨设备配对的载荷、状态机与失败语义

- 状态：**已提议**（W6-3a 产出：契约与纯逻辑已实现，63 例测试钉住；传输与证书钉扎在 W6-3b）
- 日期：2026-10-23
- 前置：[ADR-S001](./ADR-S001-source-sync-contract.md) D6（安全边界）、D7（逐源真实认证）
- 实现：`windows/src/MediaHub.Sync/PairingPayload.cs`、`PairingChallenge.cs`、`PairingFingerprint.cs`、`PairingSession.cs`
- 测试：`windows/tests/MediaHub.Tests/PairingContractTests.cs`

## 问题

ADR-S001 D6 定了三条硬规则：二维码只携带**临时地址、一次性挑战、公钥指纹**；配对**短时有效、一次性消耗**；传输用 TLS 1.3 + 指纹钉扎、**不自创加密**。但 D6 没说清落地细节：载荷长什么样、一次性靠什么保证、过期以谁的表为准、失败怎么告诉用户、配对成功之后到底算不算可用。

这些细节若留给 UI 各自决定，第一个实现就会变成事实标准 —— 而且通常会在「顺手多传一个 token 更方便」处走偏。本 ADR 把它们写成契约。

## 决策

### D1：载荷是**六个标量字段**的白名单，不是黑名单

```
PairingPayload {                        // schema v1
  schemaVersion: int                    // 1
  deviceId: string                      // 发送方设备标识（非秘密）
  ephemeralUrl: string                  // 本次配对的临时监听地址
  challenge: string                     // 32 字节随机数，规范 base64（44 字符）
  publicKeyFingerprint: string          // 对端证书公钥的 SHA-256，小写十六进制（64 字符）
  expiresAtEpochMs: long                // 发起方的时钟
}
```

「不含凭据」用**结构**保证，而不是用词表扫描：

- `PairingPayload` 恰好这 6 个公共属性（测试用反射钉住成员集合），没有任何一个能装下密码或 Token；
- 编解码器只写这 6 个 camelCase 字段，**只写标量**（无嵌套对象/数组，没有藏 blob 的位置）；
- 读到未知属性一律忽略，**且绝不回写** —— 半路注入的 `password` 在第一次重新序列化时就消失。

不做词表/关键字扫描：`canSendSecrets` 这种合法字段名会被误杀，而 `k`、`s` 这类短名永远扫不出来。白名单是唯一可判定的写法。

`ephemeralUrl` 的额外规则（同样是防夹带）：必须是绝对 http/https 且带 host，**禁止 user-info、禁止 query、禁止 fragment**。query 是 token 最常见的落脚点，`?token=` 一旦被允许，D6 就等于没有。

### D2：120 秒窗口，一次性消费，窗口以**发起方**的表为准

- `LifetimeSeconds = 120`、`LifetimeMilliseconds = 120_000`，发起时 `expiresAtEpochMs = now + 120000`。
- 过期判定**含边界**：`now >= expiresAtEpochMs` 即 `expired`。不读墙上时间，`now` 始终是入参 —— 120 秒语义因此可以用两个时间戳断言，而不是靠 `Task.Delay` 碰运气。
- 一次性由 `PairingChallengeLedger`（进程内集合，`TryAdd` 原子占用）保证：扫码侧在受理时**先消费再继续**，同一挑战第二次提交得到 `challenge-replayed`。截图、转发、重放都只值一次。
- **只有扫码侧采纳对端 payload 的 `expiresAtEpochMs`**。发起侧（应答路径）保留自己公布的窗口：否则对端只要写一个更长的有效期，就能单方面延长我们的邀请。

### D3：状态机 —— 非法迁移是调用方 bug，用户可见失败才是失败

| 状态 | 含义 | 允许的下一步 |
| --- | --- | --- |
| `Created` | 会话已开，什么都没发生 | `IssueChallenge` / `AcceptPeerPayload`（扫码侧） / `Close` |
| `ChallengeIssued` | 已公布邀请（含载荷、120 s 窗口） | `AcceptPeerPayload`（对端应答） / `Fail` / `Close` |
| `AwaitingPeer` | 双方载荷已交换，等用户确认 | `ComposeResponse`（仅扫码侧）/ `Confirm` / `Fail` / `Close` |
| `Paired` | 两端认了同一身份并完成确认 | `Close` |
| `Closed` | 用户/流程关闭 | —（`Close` 幂等） |
| `Failed(reason)` | 终态，`LastReason` 为稳定码 | `Close` |

规则：

- **非法迁移抛 `InvalidOperationException`** —— 那是代码路径错，不是用户遭遇的失败。混进原因码表会污染 UI 文案与统计。
- **用户可见的拒绝走 `Fail(reason)`**，落在 `Failed` 且 `LastReason` 是稳定字符串。
- **载荷畸形（版本/字段/URL/编码）直接抛 `SyncContractException`，不改状态机** —— 解析失败不该把一个会话判死，用户可以重扫。
- 每次迁移记一条 `PairingTransition(From, To, Reason, AtEpochMs)`，事后能还原「谁在什么时候拒了谁」。

### D4：失败原因码表（稳定字符串，只加不改）

| 码 | 触发条件 | 用户可见含义 |
| --- | --- | --- |
| `unsupported-version` | `schemaVersion` 不是本版本 | 对方版本更新/更旧，需升级 |
| `bad-fingerprint` | 指纹不是 64 位十六进制（或不是 32 字节摘要） | 二维码内容损坏 |
| `bad-challenge` | 挑战不是 32 字节的规范 base64 | 二维码内容损坏 |
| `bad-url` | 地址不是纯 http(s)（含 user-info/query/fragment） | 二维码内容不可信 |
| `expired` | `now >= expiresAtEpochMs` | 二维码已过期，重新生成 |
| `challenge-replayed` | 挑战已在 ledger 中消费过 | 这张码已经用过了 |
| `challenge-mismatch` | 应答的挑战与本机发出的不符 | 对方应答的不是这次配对 |

解码诊断（不是配对结论，不出现在用户文案里）：`malformed-json`、`missing-field:<name>`、`invalid-field:<name>`。

这些码是契约：UI 按码翻译、日志按码分组、本表按码生成。**永不改写已有码**，需要新语义就加新码。

### D5：比较与规范化

- 挑战与指纹的比较一律 `CryptographicOperations.FixedTimeEquals`，且**任一侧为空即不匹配** —— 否则「没设置」会等于「没设置」而被当成通过。
- 挑战只接受**规范** base64：解出 32 字节不够，回写结果必须与输入逐字符相同。否则同一个 nonce 会有两种拼写，ledger 按字符串记账就会漏掉重放。
- 指纹只接受 64 个十六进制字符，大小写归一为小写；**反序列化时即归一化**（`Deserialize` 返回前改写），保证同一密钥只有一种表示、重新序列化的字节与本地写出的完全一致。
- 展示用 `ToDisplayString()`：大写、每 4 字符一组（16 组），供两端人眼比对。

### D6：本层**不做**加密，传输是 W6-3b 的事

W6-3a 只交付纯逻辑：无 socket、无文件、无墙上时间。明确不做的理由：

1. **不自创加密是 D6.2 的原文要求**。自建握手 = 自建漏洞，且很难被 review 出来。
2. 本层能穷举测试，正因为它什么都不碰；一旦引入 I/O 与时间，120 秒与一次性就只能靠 sleep 测。
3. 更重要的：如果这一层「看起来加密过」，就会有人推论「所以二维码里带个 token 也没关系」。**载荷最小化是安全边界本身**，不靠外层补偿。

W6-3b 负责：临时监听（仅用户许可的私有接口、限来源与范围、**首次配对结束即关闭**，D6.8）、TLS 1.3 与**证书公钥指纹钉扎**（钉的就是这里那 64 位 hex）、超时与取消、以及「配对结束即释放端口」。

### D7：配对成功 ≠ 来源可用

`Paired` 只表示两件事：两端认了同一个身份，且用户确认了这次交换。它**不表示任何来源已经登录**。

ADR-S001 D7 的要求不变：导入后必须对每个来源独立发起真实认证，结论是三态之一（**可直接用 / 需要重新登录 / 暂不支持**，见 W6-2b）。UI 禁止把 `Paired` 显示成「同步完成」，也禁止把来源列表里的「已迁移」显示成「已登录」。

## 实现映射

| 契约点 | 位置 |
| --- | --- |
| 载荷、编解码、原因码 | `windows/src/MediaHub.Sync/PairingPayload.cs` |
| 挑战、一次性 ledger | `windows/src/MediaHub.Sync/PairingChallenge.cs` |
| 指纹（固定时间比较、分组展示） | `windows/src/MediaHub.Sync/PairingFingerprint.cs` |
| 状态机与迁移历史 | `windows/src/MediaHub.Sync/PairingSession.cs` |
| 63 例测试（每条拒绝分支一例，含 wire 形状与「无 secrets」结构断言） | `windows/tests/MediaHub.Tests/PairingContractTests.cs` |

## 后果

- 正向：二维码载荷被压到最小且**可判定**（白名单）；拒绝原因可翻译、可统计；120 秒与一次性有测试钉住，不依赖 sleep；纯逻辑在 CI 里毫秒级跑完。
- 代价：ledger 是进程内的，进程重启后旧挑战可能被再次接受（120 秒窗口下通常已过期）。若 W6-3b 需要跨进程持久化，只替换 ledger 实现，契约不动。
- 未决：多网卡时 `ephemeralUrl` 选哪个地址（W6-3b）；对端时钟偏差只影响它自报的过期时间，本机窗口不受影响。
