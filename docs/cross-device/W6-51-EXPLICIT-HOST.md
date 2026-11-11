# W6-51 显式主机入口（跨机的第一步，只解地址闸门）

> 状态词按本项目口径：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / GUI_UNVERIFIED / SERVER_UNVERIFIED / DEVICE_UNVERIFIED / NOT_RUN / BLOCKED / FAILED / RELEASE_BLOCKED / SIGNING_BLOCKED。
> 本文件描述的是 **Windows 端 CLI 与 Sync 库** 的一层改动。它让「非回环地址」第一次能通过地址闸门，但**没有任何一次真实跨机传输被执行**：今天全部用例仍是 127.0.0.1 / ::1，跨机的 E1–E8 一律 **NOT_RUN**（§8）。

---

## 1 动机：闸门是唯一挡住跨机的东西，先只拆它

W6-47 ADR §1.1 记录：地址判定只有一处（`SourceSyncTransferAddress`），只要不是 `127.0.0.1` / `::1` / `localhost`，在**创建任何 socket 之前**就以 `not-loopback` 拒绝。§1.2 记录次序是「地址 → 证书 → bind」。因此跨机在第一个字节之前就失败了，后面的 TLS 1.3、SPKI pin、认证、回执、投递确认**全都从未在非回环地址上跑过**。

W6-51 只做一件事：给调用方一个**显式、逐次、可审计**的入口，让它自己点名一个本机网卡上的单播地址。不做的事同样要写清：

- 不引入服务发现、不扫描网段、不读 ARP/路由表、**不做 DNS**（C3）。
- 不预设「局域网」概念，不自动挑网卡，不给 `0.0.0.0` 之类「监听所有接口」的便利。
- 不新增错误码集合（ADR §4.3 结论 A）。
- 不改 UI：同步页今天没有任何地址输入框（ADR §1.3），本轮也不加。

---

## 2 开关语义（CLI 面，逐条可断言）

| 命令 | 选项 | 缺省 | 语义 | 拒绝时的码 |
| --- | --- | --- | --- | --- |
| `transfer serve` | `--bind ADDR` | `SourceSyncTransferAddress.DefaultAddress`（`127.0.0.1`） | 本端监听的地址 | `not-loopback` + detail |
| `transfer serve` | `--allow-non-loopback` | 关 | 允许 `--bind` 是**一个单播字面量** | `option-not-allowed-for-command:--allow-non-loopback:<cmd>`（非 serve/send）／`flag-takes-no-value:--allow-non-loopback` |
| `transfer send` | `--allow-non-loopback` | 关 | 允许 `--to` 是**一个单播字面量** | 同上 |
| `transfer send` | `--bind` | — | **不提供**（发送端由 `--to` 决定目标，源地址不选） | `option-not-allowed-for-command:--bind:transfer-send` |
| `transfer roundtrip` | 两者都不给 | — | 本命令自己发明两端的地址（回环），调用方无权指定 | `option-not-allowed-for-command:<选项>:transfer-roundtrip` |
| 其它所有命令 | 两者都不给 | — | 由各自的 allowed 列表拒绝 | `option-not-allowed-for-command:<选项>:<cmd>` |

「同意」只放宽一件事：**允许一个单播字面量通过**。它不放宽下面任何一种（同意后仍被拒，且拒绝句子点名原因）：

| 输入类 | 例子 | 为什么仍拒 |
| --- | --- | --- |
| 通配 | `0.0.0.0`、`::`、`0.1.2.3` | 监听「所有接口」，等于把闸门整段删掉 |
| 广播 | `255.255.255.255` | 面向一整条链路，不是一台机器 |
| 组播 | `239.1.2.3`、`224.0.0.1`、`ff02::1` | 面向一组订阅者 |
| 保留段 | `240.0.0.1`、`255.255.255.254` | 不指向任何主机 |
| 主机名 | `mediahub-lan.local`、`nas`、`example.com` | C3：传输路径不做 DNS，没有解析器可依赖 |
| 超长 | 257 字符 | 先于一切判断拒绝，避免把输入直写进诊断 |

要点：**所有拒绝都发生在创建 socket 之前**，因此「拒绝」永远不会引发 Windows 防火墙弹窗——这正是用户 GUI 纪律要求的形状。

---

## 3 实现落点（文件与行号）

| 位置 | 内容 |
| --- | --- |
| `windows/src/MediaHub.Sync/SourceSyncTransferEndpoint.cs:67` | `public static bool TryResolve(string? address, int port, bool allowExplicitHost, out IPEndPoint? endpoint, out string? detail)` —— 唯一的地址判定 |
| 同上 `:48-49` | `TryResolveLoopback` 变成 `=> TryResolve(address, port, allowExplicitHost: false, …)`，旧调用方（Android 同构实现、旧测试）语义逐字节不变 |
| 同上 `:99-105` | 无同意 + 非回环 → 原文拒绝句（W6-51 之前逐字相同的句子） |
| 同上 `:106-115` | 有同意 + 非单播 → 新增拒绝句（wildcard / broadcast / multicast / reserved，举例 `192.168.1.20`） |
| 同上 `:121-126` | 非 IP 字面量（主机名）→ 拒绝句（点名「不做 DNS」） |
| 同上 `:78-82` | >256 字符 → `An address is at most 256 characters; this one was not dialled.` |
| 同上 `:144-164` | `private static bool IsUnicastLiteral(IPAddress)`：IPv6 排除 `IPv6Any`、`IsIPv6Multicast`；IPv4 排除 `0.0.0.0/8`、`255.255.255.255`、`>=224` |
| 同上 `:248-288` | `SourceSyncTransferListener.Start(…, bool allowExplicitHost = false)`；次序 **地址 `:254` → 证书 `:262` → bind `:271-272`**，失败码依次 `not-loopback` / `tls-certificate-unavailable` / `listener-failed` |
| 同上 `:579`、`:614` | `SourceSyncTransferClient.SendAsync` / `ReceiveAsync` 末位加 `bool allowExplicitHost = false`，转发给 RunAsync |
| 同上 `:709-711` | `RunAsync` 用 `TryResolve`；拨号侧同样「先判地址，再建 socket」 |
| `windows/src/MediaHub.Sync/SourceSyncPeerRound.cs:294`、`:354`、`:392` | SendAsync / SendPayloadAsync / ReceiveOnceAsync 末位各加 `bool allowExplicitHost = false` 并逐层透传（`:323`、`:357`、`:400`） |
| `windows/src/MediaHub.SyncCli/CliInvocation.cs:168`、`:176` | `public string? BindAddress { get; init; }`、`public bool AllowExplicitHost { get; init; }` |
| `windows/src/MediaHub.SyncCli/CliParser.cs:77-88` | usage 两段（`--bind ADDR` / `--allow-non-loopback`），含 `W6-51` 字样 |
| 同上 `:103` | `TransferServeValueOptions` 加 `"--bind"`（只有 serve 的值选项表里有它，于是其它命令天然得到 `option-not-allowed-for-command`） |
| 同上 `:367` | flag 分支仿 `--await-receipt`：`--allow-non-loopback` 只对 `TransferServe`/`TransferSend` 打开 |
| 同上 `:550-553`、`:584-585` | `string? bindAddress = values.GetValueOrDefault("--bind")`；invocation 填 `BindAddress`、`AllowExplicitHost` |
| 同上 `:610` | 「未知值选项」提示句补进 `--bind`/`--allow-non-loopback` |
| `windows/src/MediaHub.SyncCli/SyncCliRunner.cs:857-860` | `SourceSyncTransferListener.Start(invocation.BindAddress ?? SourceSyncTransferAddress.DefaultAddress, invocation.Port ?? 0, invocation.Tls, invocation.AllowExplicitHost)` |
| 同上 `:990` | `SendAsync(…, receipt, invocation.AllowExplicitHost)` |

未改动：`:864` 的 `start.Detail ?? "the loopback listener could not be bound"` 兜底句保留原样——它是「detail 缺失时」的兜底，真实拒绝句一定先到。

---

## 4 ADR 十条约束的落点（C1–C10）

| # | 约束 | 本轮落点 |
| --- | --- | --- |
| C1 | 地址判定只有一处 | `TryResolve` 是唯一实现；`TryResolveLoopback` 是它的 `allowExplicitHost:false` 包装（`:49`）；Listener / Client / PeerRound 全部转发，没有第二份白名单 |
| C2 | 次序不可换：地址 → 证书 → bind | 保持：`:254` → `:262` → `:271`；顺序由 `Serve_RefusesAWildcardBindWithConsentBeforeAnnouncingAPort`（:319）与既有 20 例脚本共同钉住 |
| C3 | 只认字面量，不引入 DNS | `IPAddress.TryParse` 失败即拒（`:121-126`）；用例 `WithConsent_AHostNameIsStillRefusedBecauseNothingResolvesIt`（:172） |
| C4 | 协议版本钉死 TLS 1.3 | 未触碰 |
| C5 | pin 来自持久信任锚 | 未触碰 |
| C6 | 认证默认开 | 未触碰 |
| C7 | 口令只从环境变量/凭据库读 | 未触碰；`--bind` 不携带任何凭据 |
| C8 | 关闭必须先排水 | 未触碰（W6-26 的 `shutdownOutput`→排水→close 路径不变） |
| C9 | 预算分层 | 未触碰：serve 15 s（`--budget-ms` 可调，W6-50）、send 用 `TransferBudget`；本轮新选项不改变任何预算 |
| C10 | 诊断不得回显敏感物 | 新增 detail 只回显**调用方自己提供的地址**（≤256 字符先判长度），不含 secret / 口令 / 证书/ 指纹；`Serve_RefusesAWildcardBindWithConsentBeforeAnnouncingAPort` 断言 stderr 不含 `listening`、JSON 只有 `code`+`detail` |

---

## 5 与 W6-47 ADR §4.5 第 2 条的偏差（必须记录）

ADR §4.5 明确列了**不采用**的三种做法，第 2 条是：

> **白名单开关 + 复用 `not-loopback`**：把「地址被策略拒绝」和「地址不是回环」压成同一个码，等于删掉了诊断信息。

W6-51 采用的正是这个形状（一个开关 + 复用 `not-loopback`）。必须给出为什么这不是同一条被批评的做法，以及仍然付出的代价。

**为什么不是同一条：**

1. **放宽后「不是回环」不再是一个拒绝理由**，因此也就不会再产生任何码——被压掉的第二语义在实现里根本不存在。剩下的拒绝全部落在 ADR §4.3 结论 B 的原义上：「调用方给的地址不被本构建接受」（不是字面量 / 不是单播 / 超长 / 端口越界）。
2. **诊断信息没消失，只是从码搬到 detail**：五类拒绝各有独立句子（`:80`、`:101`、`:111`、`:123`、`:130`），句子点名原因（wildcard / broadcast / multicast / reserved / host name / 长度），并且被逐句断言（见 §6 的 `:114-192`、`:333`）。
3. ADR §4.5 担心的是「同一个码承担两种语义」，而这里一个码只承担一种语义：地址不被接受。

**仍然付出的代价（不得淡化）：**

- **机器不可辨**：只看 `code` 的消费者无法 switch 出「为什么」，必须读 `detail`（人写的英文句子）。任何依赖码做分支的下游都得接受这一点。
- 如果将来确实需要机器可辨（例如 UI 要按原因给不同建议），必须回到 ADR §4.3 **结论 C**：新开集合、两端同名同序、与现有五集交集为空、两端各有用例。**不得**扩十码集合（§4.5 第 1 条）。今天 Android 端没有任何跨机入口，不存在「两端」，结论 C 的第①③条无法满足——这正是本轮不新开集合的现实理由，而不是省事。

---

## 6 用例清单（40 例，全部 EXECUTED_PASS）

文件：`windows/tests/MediaHub.Tests/SourceSyncExplicitHostTests.cs`（498 行、19638 B、`public sealed class SourceSyncExplicitHostTests : IDisposable`）。

| 组 | 用例（行号） | 断言要点 |
| --- | --- | --- |
| 同意不改回环行为 | `Consent_DoesNotChangeAnyLoopbackSpelling`（:67，8 行数据：`127.0.0.1`、`::1`、`localhost`、`LOCALHOST`、` 127.0.0.1 `、`127.0.0.2`、`""`、`null`） | 同意下全部照旧被接受，端点文本与不带同意时相同 |
| 原文拒绝句 | `WithoutConsent_ALanLiteralGetsThePreW651SentenceWordForWord`（:79） | `192.168.1.5` 无同意：`TryResolve` 与 `TryResolveLoopback` 的 detail **逐字相等** |
| 同意后接受单播 | `WithConsent_OneUnicastLiteralIsAccepted`（:102，`192.168.1.5`/`10.0.0.7`/`172.16.30.4`/`fd00::1`） | 端点地址正确、端口保留 |
| 通配 | `WithConsent_AWildcardIsStillRefusedAndTheSentenceSaysSo`（:118，`0.0.0.0`/`::`/`0.1.2.3`） | detail 含 wildcard / broadcast / multicast / reserved 词 |
| 广播 | `WithConsent_BroadcastIsStillRefused`（:131） | 同上 |
| 组播 | `WithConsent_MulticastIsStillRefused`（:145，`239.1.2.3`/`224.0.0.1`/`ff02::1`） | 同上 |
| 保留段 | `WithConsent_TheReservedRangesAreStillRefused`（:158，`240.0.0.1`/`255.255.255.254`） | 同上 |
| 主机名 | `WithConsent_AHostNameIsStillRefusedBecauseNothingResolvesIt`（:172） | detail 含 `does not resolve host names` |
| 超长 | `WithConsent_AnOverlongAddressIsStillRefused`（:183，257 字符） | `at most 256` |
| 无 socket | `Listener_RefusesAWildcardBindWhetherOrNotConsentWasGiven`（:194）、`Client_RefusesABroadcastTargetWithConsentAndOpensNoSocket`（:210） | 返回 `not-loopback`，且**没有 socket 被创建**（因此不触防火墙） |
| CLI 解析 | `Parse_ServeTakesBothOptions`（:223）、`Parse_ServeWithoutTheOptionsIsStillThePreW651Invocation`（:235）、`Parse_SendTakesConsentButRefusesBind`（:245）、`Parse_RoundtripRefusesConsentBecauseItInventsBothAddresses`（:260）、`Parse_ConsentTakesNoValue`（:270）、`Parse_BindNeedsAValue`（:280）、`Parse_BindIsRefusedOutsideServe`（:292） | invocation 字段、`option-not-allowed-for-command:<opt>:<cmd>`、`flag-takes-no-value`、缺值/空值 |
| usage | `Usage_NamesTheTwoOptionsAndStillPromisesLoopbackByDefault`（:304） | `--bind ADDR`、`--allow-non-loopback`、`loopback only`、`W6-51` 同时出现 |
| CLI 运行 | `Serve_RefusesAWildcardBindWithConsentBeforeAnnouncingAPort`（:319）、`Serve_RefusesALanBindWithoutConsentWithTheOriginalSentence`（:333） | exit 2、stderr 无 `listening`、JSON `code=not-loopback` |
| CLI 运行 | `Send_RefusesABroadcastTargetEvenWithConsent`（:346） | 目标 `255.255.255.255:9000` 带同意仍 `not-loopback` |
| 不回退 | `Serve_WithConsentStillServesLoopbackExactlyAsBefore`（:362） | 带同意时 serve 仍正常接收回环传输，`counts.apply=1` |

**测试类里的硬约束（remarks 原文要点）**：本类**不 bind、不 dial 任何非回环地址**。理由是与用户 GUI 纪律直接冲突——bind 一个真实网卡会让 Windows 弹防火墙授权框、抢焦点。因此这 40 例证明的是「闸门与解析行为正确」，**不是**「跨机能通」；后者见 §8。

---

## 7 实跑证据（本机，2026-10-24）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 新测试类 | `dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --filter "FullyQualifiedName~SourceSyncExplicitHostTests"` | `失败: 0，通过: 40，已跳过: 0，总计: 40`，749 ms，EXIT=0（EXECUTED_PASS） |
| 全量单测 | 同上不带 filter（工作树 `D:/mh-w6`） | `失败: 0，通过: 1577，已跳过: 0，总计: 1577`，12 s，EXIT=0 —— 基线 1537 + 40（EXECUTED_PASS，日志 `D:/mh-verify/w6-51-full-test.log`） |
| 回环验收矩阵 | `pwsh -NoProfile -File windows/tools/acceptance/w6/transfer-tls-auth.ps1 -SkipBuild -OutDir D:/mh-verify/w6-51-script` | `cases: 20, passed: 20, failed: 0`，47833 ms，`residual SynCli processes: 0`，`RESULT: 20 passed, 0 failed`（EXECUTED_PASS，日志 `D:/mh-verify/w6-51-script.log`） |
| presenter 端到端（E7 回退） | `pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1 -OutDir D:/mh-verify/w6-51-presenter-e2e` | `tests: 14 passed, 0 failed, 0 skipped`、`cases: 14/14 passed`、`secret leak: False`、`residual MediaHub.SyncCli processes: 0`、16299 ms、`RESULT: 14 passed, 0 failed`（EXECUTED_PASS）。**注意**：直接 `dotnet test` 该工程会因缺 `MH_PRESENTER_E2E_CLI` 全红——必须走包装脚本，它会先 build SyncCli 再把 DLL 路径灌进环境变量 |

验收矩阵里与预算相关的两例本轮复跑数字：`S1-budget-timeout`（serve 2，`transfer-timeout` 1527 ms，stderr 有 budget 行，无写入）、`S2-budget-in-json`（JSON `budgetMs=30000`，records=2）。这两例同时充当「加了 `--bind` 之后 serve 的预算路径没被挤掉」的证据。

---

## 8 DoD：E1–E6 仍 NOT_RUN，E7/E8 已取证（与 W6-47 §5 逐条对齐）

| # | 需要的证据 | 今天 | 为什么没有 |
| --- | --- | --- | --- |
| E1 | 非回环地址上一次成功传输（两台真机或两进程 + 真实网卡），两端 exit 0、frames/records 符合预期 | **NOT_RUN** | 需要真实第二台机器/第二网卡；本机自连会触防火墙弹窗，违背 GUI 纪律 |
| E2 | 防火墙交互实测：首次绑定是否弹窗、允许/拒绝各得到什么码 | **NOT_RUN** | 同上；这也是本实现「拒绝发生在 socket 之前」的原因——它把最常见的一次弹窗从路径上去掉了，但仍然没有测过「用户同意后」的那次 |
| E3 | pin 三态在跨机路径上实测（NEW/MATCH/MISMATCH 且旧 pin 不被覆盖） | **NOT_RUN** | 回环内有单测，跨机路径没有 |
| E4 | TLS × 认证 × 回执 三开关矩阵在非回环地址上重跑 | **NOT_RUN** | 回环矩阵 20/20（本轮）；跨机未跑 |
| E5 | 无敏感物泄漏（secret 不出现在 stdout/stderr/JSON/日志）跨机版 | **NOT_RUN** | 回环有断言（C10）；跨机未测 |
| E6 | 真实网络下关闭语义稳定性（重复 N 次 0 次 RST 丢帧） | **NOT_RUN** | 需要真实链路抖动条件 |
| E7 | 回退证据：改动后回环矩阵仍全绿、presenter-e2e 仍全绿 | **EXECUTED_PASS** | 回环矩阵 20/20（§7 实测）；presenter-e2e **14/14 passed, 0 failed**（16299 ms、secret leak False、residual 0、临时根 left:0；日志 `D:/mh-verify/w6-51-presenter-e2e-wrapper.log`，摘要 `D:/mh-verify/w6-51-presenter-e2e/presenter-e2e-summary.json`） |
| E8 | 文档一致性：ADR 行号在改动后仍可核 | **EXECUTED_PASS（本文件）** | §3 行号是本轮改完后的实测值；ADR 本身未被本轮改动影响（它引用的 `SourceSyncTransferReasons.cs` 行号未变） |

W6-51 的自我定位：**IMPLEMENTED_UNVERIFIED**。单测与回环矩阵不是「跨机可用」的证据；它们只证明「闸门放宽后，回环路径逐字节没变，且非回环地址的拒绝理由仍是可读的」。

---

## 9 回退

- **行为回退**：不给 `--bind` / `--allow-non-loopback` 时，调用方看到的一切与 W6-51 之前逐字节相同——`Parse_ServeWithoutTheOptionsIsStillThePreW651Invocation`（:235）钉住 invocation 字段（`BindAddress=null`、`AllowExplicitHost=false`），`Consent_DoesNotChangeAnyLoopbackSpelling`（:67）钉住端点文本，验收矩阵 20/20 钉住进程级行为。
- **代码回退**：删除 `CliParser` 的两个解析分支（flag 分支 + `TransferServeValueOptions` 的 `--bind` 项）与 `SyncCliRunner` 两处透传即可；`SourceSync` 库里的 `allowExplicitHost` 参数全部有 `= false` 缺省，删不删都不影响既有调用方。
- 不涉及数据迁移、不涉及持久状态：没有任何 pin / 配置被写到磁盘。

---

## 10 下一步（按依赖顺序）

1. Android 端对齐（另立里程碑）：Android 今天连「选地址」的入口都没有，跨机在 Android 侧仍是 0；如果要做，必须先定「地址从哪来」，并且此时才可能满足 ADR 结论 C 的「两端同名同序」。
2. 真实跨机证据（E1–E6）需要用户提供第二台机器与一次防火墙授权窗口；在此之前任何「跨机可用」的说法都不成立。
3. UI 入口（ADR §1.3 的缺口）保持不动。
