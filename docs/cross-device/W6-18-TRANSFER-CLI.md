# W6-18 — transfer 命令行（回环）

状态：**IMPLEMENTED_UNVERIFIED**。进程内 19 例测试与真实命令行均已实测（本机 Windows，2026-10-24）；没有第二台设备，也没有 Android 对端参与。

## 1 这一层补什么

W6-16 给了帧格式与回环端点，W6-17 给了「导出 → 传输 → 预览 → 审批 → 写入 → 推进基线」的编排对象，但两者都只有代码入口。本层把它们露成三条可脚本化的纯文本命令：

| 命令 | 跑在哪一端 | 作用 |
| --- | --- | --- |
| ```transfer serve``` | 接收端（B） | 绑回环端口，接一个连接，解析信封，预览或写入 |
| ```transfer send``` | 发送端（A） | 导出本机来源定义，按帧发给对端 |
| ```transfer roundtrip``` | 单机 | 一个进程里演 A→B 再 B→A，两轮结果一起打印 |

这三条命令只复制**来源定义**（类型、地址、显示名、revision），不复制媒体内容、不复制凭据、不登录对端账号。它们也**不是**常驻服务：serve 收完一个连接就退出，没有后台守护、没有开机项。

## 2 命令参考

### transfer serve

```
transfer serve --root <dir> --device <id> [--port <n>] [--apply] [--once] [--json]
```

- @--root@：接收端的设置目录（来源表 @sync-sources.json@ 与基线 @sync-baseline.json@ 都在这里）。
- @--device@：本机在这个方向上自称的设备 id，写进结果里的 @to@。
- @--port@：0（缺省）交给系统分配；给出后必须落在 0..65535，否则 @invalid-port:<n>@。
- @--apply@：真正写入并推进基线。缺省只预览：四桶计数 + 每条动作 + 是否被门禁扣下，一个字节都不写。
- @--once@：显式声明「只服务一个连接」。这是 serve 唯一的行为，flag 只是让脚本读起来明确；换成 roundtrip/send 会被拒（@option-not-allowed-for-command:--once:<cmd>@）。

绑定地址**不可配置为 LAN 地址**：命令内部固定 @127.0.0.1@，非回环在创建 socket 之前就被拒（见 §6）。

端口在**接收连接之前**写到 stderr：```listening 127.0.0.1:<port>```。因为 @--json@ 的 stdout 要等整轮结束才写单个文档，脚本必须在 stderr 上等端口，否则会死等。

JSON 字段：

| 字段 | 含义 |
| --- | --- |
| @command@ / @subcommand@ | @transfer@ / @serve@ |
| @root@ @address@ @port@ @once@ | 实际绑定的目录与端点（端口是系统分配后的真实值） |
| @applied@ | 本轮是否写入（即是否给了 @--apply@ 且至少一条通过门禁） |
| @from@ @to@ | 对端设备 id / 本机设备 id |
| @bytes@ @frames@ | 信封字节数、帧数（无挑战时 1 个数据块帧 = 1） |
| @noChange@ | 本轮是否零写入 |
| @counts@ | @apply@ @requiresApproval@ @unsupported@ @ignored@ @withheld@ 五桶 |
| @items[]@ | @syncSourceId@ @action@ @reason@ @requiresApproval@ @withheld@ |
| @report@ | **仅当真的写入时才出现**：@applied@ @skipped@ @withheld@ @notApplied@ @baselineUpdated@ @baselineEntries@ @summary@ @baseline@ |
| @error.code@ / @error.message@ | 失败时替代以上全部（@{"ok":false,...}@） |

### transfer send

```
transfer send --to 127.0.0.1:<port> --root <dir> --device <id> [--json]
```

- @--to@ 只接受 @host:port@（也接受 @[::1]:port@）；端口 1..65535，0 与缺失都算 @invalid-target@。
- 非回环主机名/地址一律 @not-loopback@，且**不会发起连接**（防火墙提示不会弹出）。
- 发送端只读：它导出信封后写 socket，不写本机任何文件（内部用只读副本，干跑也不可能落盘）。
- 不接受 @--apply@：发送端没有可应用的东西，给了就是用法错误。

JSON 字段：@command@ @subcommand@ @root@ @deviceId@ @address@ @port@ @snapshotId@ @sourceCount@ @bytes@ @frames@ @idempotency@。幂等由接收端的基线决定，发送端只负责把同一快照送到。

### transfer roundtrip

```
transfer roundtrip --root <dir> --device-a <id> --device-b <id> [--apply] [--json]
```

- 一个进程里造两台设备：A 在 @<root>/a@，B 在 @<root>/b@，各自持有来源表与基线。
- 先 A→B，再 B→A。第二轮用的是第一轮之后的对端状态，所以「已同步过」会被如实算成 @nochange@。
- @--apply@ 才写；不给就是两轮都只预览（此时两端磁盘上只会有本来就存在的文件）。
- 失败会把轮的编号写进 detail：@round 1 (a -> b) failed: ...@ / @round 2 (b -> a) failed: ...@。

JSON 字段：@command@ @subcommand@ @root@ @rootA@ @rootB@ @deviceA@ @deviceB@ @applied@ @secondRoundNoChange@ @rounds[]@，每个 round 元素 = @from@ @to@ @bytes@ @frames@ @noChange@ @counts@ @items[]@（有写入时再加 @report@）。

**单轮元素没有 @applied@ 字段**：写入证据是「这个 round 有没有 @report@ 对象」。这样干跑与实写一眼可分，也不会出现一个恒为 false 的字段被误读成「失败」。

## 3 退出码

| 码 | 含义 | transfer 组何时出现 |
| --- | --- | --- |
| 0 | 成功 | 预览成功、写入成功、空信封成功 |
| 1 | 用法错误 | 缺/未知子命令、缺必填选项、选项对该命令不允许、@--port@ 越界 |
| 2 | 输入/目标问题 | 非回环目标、连接被拒、帧坏/被截断、载荷不是信封、超时、目录不可读 |
| 3 | 需要审批 | **不产生**：transfer 只报告 @requiresApproval@ 计数，不替用户做决定 |
| 4 | 归档/凭据错误 | **不产生**：transfer 不读归档、不解密凭据 |

## 4 稳定错误码

解析层（exit 1，来自 @CliParser@，与 W6-7/W6-15 同一套契约，不另造别名）：

| 码 | 触发 |
| --- | --- |
| @missing-subcommand:transfer@ | @@transfer@@ 后面没有动词 |
| @unknown-subcommand:transfer:<verb>@ | 动词不是 serve/send/roundtrip |
| @missing-required-option:--device@ / @--to@ / @--device-a@ / @--device-b@ | 对应命令缺必填项 |
| @option-not-allowed-for-command:--apply:transfer-send@ | 发送端给 @--apply@ |
| @option-not-allowed-for-command:--once:transfer-roundtrip@ | 把 serve 专属 flag 用到别的动词 |
| @invalid-port:<n>@ | @--port@ 不是 0..65535 的整数 |
| @flag-takes-no-value:@ / @duplicate-option:@ / @unknown-option:@ / @missing-value:@ / @empty-value:@ | 通用选项错误，同 W6-7 |

运行层（exit 2，稳定码来自 @SourceSyncTransferReasons@ 封闭集，共 10 个）：

| 码 | 触发 | 对端是否已写 |
| --- | --- | --- |
| @not-loopback@ | 目标或监听地址不是 127.0.0.1 / ::1 / localhost | 否（socket 都没建） |
| @listener-failed@ | 回环端口绑不上 | 否 |
| @connect-failed@ | 回环目标没人监听（含端口 0） | 否 |
| @frame-invalid@ | 帧 kind 未知、偏移跳字节、总量帧间不一致 | 否 |
| @frame-truncated@ | 对端写完帧头就断开，或帧长不在 1..8192 | 否 |
| @payload-too-large@ | 信封 > 8 MiB，或宣告的总量超上限 | 否（发送侧一字节未写） |
| @challenge-mismatch@ | 挑战串不一致，或缺挑战帧 | 否 |
| @envelope-invalid@ | 字节收到了，但不是合法信封（JSON 坏、schema 版本不支持、缺字段） | 否 |
| @transfer-timeout@ | 对端在预算（缺省 15 s）内没走完 | 否 |
| @io-failed@ | 连接中途被复位等 I/O 错误 | 否（写入器异常时也走这条，但那时报告是 Withheld 而不是失败） |

CLI 自身补充的两个：@invalid-target@（@--to@ 形状/端口不合法）、@store-unreadable@（来源表或基线读不出来，并附隔离文件名，绝不当成空表继续写）。

## 5 @--json@ 单文档约束

- 成功：stdout 恰好**一个** JSON 文档（单行、无缩进），没有任何其他输出。
- 失败：stdout 仍是恰好一个 @{"ok":false,"error":{"code":...,"message":...}}@，同时 stderr 有一行 @error <code>: <message>@。
- 进度、端口、预览提示一律走 stderr；**json 模式下不打印文本版预览句**，所以脚本只需读 stdout。
- 例外只有一处且是刻意的：@transfer serve@ 的 @@listening 127.0.0.1:<port>@@ 一定在 stderr 上先出现——端口是启动即知、结果要等连接后才知道，不能都挤在一个文档里。

## 6 只回环，且是「先拒绝、后建 socket」

@SourceSyncTransferAddress.TryResolveLoopback@ 在创建 @TcpListener@ / @TcpClient@ 之前判断地址；非回环直接返回失败与说明，所以 Windows 防火墙不会因为本 CLI 弹窗。实测原文（@transfer send --to 192.168.1.5:8080@）：

```
error not-loopback: Only 127.0.0.1, ::1 and localhost are accepted, so '192.168.1.5' was refused before any socket was created: binding or dialling a non-loopback address would make Windows ask the user to open the firewall.
```

同一函数在监听侧同样生效（@transfer serve@ 不接受 LAN 参数；W6-17 的探针曾对 @Listener.Start("192.168.1.5")@ 得到 @Started=False Reason=not-loopback Listener=null@）。

## 7 真实输出摘录（本机实测，2026-10-24）

serve + send 两次进程跑通（serve 在后台，端口从 stderr 读）：

```
serve announced port = 3327
sent 1 frames, 679 bytes to 127.0.0.1:3327
device: dev-a  snapshot: 7c6090abb74f417ea2043a091be30e37  sources: 2
the receiver decides idempotency: this snapshot applies once; delivering it again is a no-change round.
send exit = 0
serve stdout:
{"command":"transfer","subcommand":"serve",...,"port":3327,"once":true,"applied":true,"from":"dev-a","to":"dev-b","bytes":679,"frames":1,"noChange":false,"counts":{"apply":2,"requiresApproval":0,"unsupported":0,"ignored":0,"withheld":0},"items":[{"syncSourceId":"src-1","action":"Create","reason":"new-source","requiresApproval":false,"withheld":false},{"syncSourceId":"src-2","action":"Create","reason":"new-source","requiresApproval":false,"withheld":false}],"report":{"applied":2,"skipped":0,"withheld":0,"notApplied":0,"baselineUpdated":true,"baselineEntries":2,"summary":"已写入本地 2 条 / 跳过 0 条 / 保留未应用 0 条","baseline":"本机基线已更新（共 2 条；再次导入同一信封会全部显示为已是最新）。"}}
serve stderr:
listening 127.0.0.1:3327
serve exit = 0
```

首轮 roundtrip @--apply@（A 两条来源 → B），第二轮立即变成全 nochange：

```
round 1 (dev-a -> dev-b)
dev-a -> dev-b: 1 frames, 679 bytes
preview: 2 to apply, 0 requiring approval, 0 unsupported, 0 ignored, 0 withheld
  src-1  create  new-source
  src-2  create  new-source
已写入本地 2 条 / 跳过 0 条 / 保留未应用 0 条
本机基线已更新（共 2 条；再次导入同一信封会全部显示为已是最新）。
round 2 (dev-b -> dev-a)
dev-b -> dev-a: 1 frames, 679 bytes
preview: 0 to apply, 0 requiring approval, 0 unsupported, 2 ignored, 0 withheld
  src-1  nochange  up-to-date
  src-2  nochange  up-to-date
已写入本地 0 条 / 跳过 2 条 / 保留未应用 2 条
本机基线未变化（共 0 条）。
second round noChange: true
exit = 0
```

同一对目录再跑一次（幂等，两边都 nochange）：

```
round 1 (dev-a -> dev-b)
preview: 0 to apply, 0 requiring approval, 0 unsupported, 2 ignored, 0 withheld
  src-1  nochange  up-to-date
  src-2  nochange  up-to-date
已写入本地 0 条 / 跳过 2 条 / 保留未应用 2 条
本机基线未变化（共 2 条）。
```

全新一对目录的干跑（@--json@，没有 @report@ 键 = 没写；@<root>/b@ 下 0 个文件）：

```
{"command":"transfer","subcommand":"roundtrip",...,"applied":false,"secondRoundNoChange":false,"rounds":[{"from":"dev-a","to":"dev-b","bytes":679,"frames":1,"noChange":false,"counts":{"apply":2,...},"items":[{"syncSourceId":"src-1","action":"Create","reason":"new-source",...}]},{"from":"dev-b","to":"dev-a","bytes":229,"frames":1,"noChange":false,"counts":{"apply":0,...},"items":[]}]}
exit = 0
files under dry/b: 0
```

用法错误与非回环目标（exit 1 / exit 2）：

```
error missing-subcommand:transfer: run with --help for usage
transfer (no verb) exit = 1
error invalid-port:70000: run with --help for usage
port 70000 exit = 1
error not-loopback: ... was refused before any socket was created ...
lan target exit = 2
error unknown-subcommand:transfer:dance: run with --help for usage
unknown verb exit = 1
```

## 8 未覆盖（NOT_COVERED）

1. **没有第二台设备**：所有实测都在同一台机器上，两个端口/两个目录。回环之外的真实链路（防火墙、路由、DNS）未验证。
2. **没有 Android 对端**：Android 侧仍走 W6-15 的信封文件搬运；transfer 组目前只有 Windows CLI。
3. **传输层不加密、不认证**：@--json@ 里 @frames@/@bytes@ 都是明文信封。回环之外的保密性与身份验证没有做，本层只证明「回环上能可靠把字节送过去」。
4. **serve 只服务一个连接就退出**：没有并发、没有排队、没有重试；要连续接收就多次启动（每次一个连接）。
5. **没有断点续传**：一帧坏掉即整轮失败，下次从头再来（信封本就不大，上限 8 MiB）。
6. **幂等依赖接收端基线文件**：删掉 @sync-baseline.json@ 后同一信封会被当新来源重放成 @Create@。
7. **没有 UI 入口**：本轮只做 CLI；GUI 侧的传输按钮不在范围内。
8. **不迁移凭据**：信封只带来源定义，@Secrets@ 一律不带（@includeSecrets@ 缺省 false）。

## 9 设计取舍（本层新增的决策）

- **D11 端口先于连接上报**：serve 在 accept 之前把真实端口写到 stderr，否则 @--json@ 的单文档约束会让脚本无端口可等。
- **D12 干跑用只读副本**：@--apply@ 缺省时把来源表包进内存副本，任何一条路径都不可能落盘——「预览不写」是结构保证，不是靠调用方自律。
- **D13 单轮 JSON 不带 @applied@**：写入证据是 @report@ 对象的存在与 @noChange@ 字段，避免出现恒为 false 的字段被误读。
- **D14 错误码沿用 W6-16 的封闭集**：不为 CLI 造 @wrong-scheme@ / @bad-payload@ 之类别名；一个条件只有一个稳定码，文档里的表就是契约。
