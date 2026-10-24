# W6-3C —— 配对 UI 实现说明

- 状态：**已实现**（本机回环内的手工配对；跨机配对、二维码、应用清单均未覆盖，见第 8 节）
- 日期：2026-10-23
- 分支：`feature/cross-device-source-sync`
- 前置：W6-3A 配对契约（`PairingPayload` / `PairingPayloadCodec` / `PairingChallenge` / `PairingFingerprint` / `PairingSession`）、W6-3B 传输层（`PairingTransportServer` / `PairingTransportClient`）、W6-2B 设备同步页
- 范围：`windows/src/MediaHub.Sync/{PairingCode,PairingPresenter}.cs`、`windows/src/MediaHub.App/Views/SyncPage.xaml(.cs)`、`windows/tests/MediaHub.Tests/PairingCodeAndPresenterTests.cs`

## 1. 边界与红线

| 红线 | 落实方式 |
| --- | --- |
| 不做二维码 | 只做**手工配对码**：`base64url`（无 padding）编码的信封 JSON，可复制、可粘贴 |
| 不做 Apply UI | 配对成功后只显示「已收到 N 个来源的清单（**未导入**）」，页面没有任何写入本地来源的按钮 |
| 配对成功 ≠ 已同步 ≠ 已登录 | 成功文案里不出现「已同步」「已登录」「已导入」；来源清单**只计数** |
| challenge 不得外泄 | `PairingCodeResult.ToString()` 只打印设备号；解码失败只透传契约层稳定码，绝不带异常 message（其 message 会引用字段原值） |
| 监听器必须真正关闭 | 成功、失败、超时、用户点「结束配对」、离开页面（`OnUnloaded`）五条路径都走 `CloseListener`；测试用「端口可重新绑定」证明 |
| 只用本机回环 | 服务端绑定 `IPAddress.Loopback:0`，配对码里的地址是 `https://127.0.0.1:<port>/pairing` |

## 2. 交付物

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `windows/src/MediaHub.Sync/PairingCode.cs` | 346 | 配对码编解码：`Encode` / `Normalize` / `Decode` / `TryDecode`，`PairingCodeReasons` 9 个稳定码，`PairingCodeResult` |
| `windows/src/MediaHub.Sync/PairingPresenter.cs` | 713 | 无 UI、无 IO 的配对驱动：`StartHost` / `PollHost` / `StopHost` / `AcceptGuestAsync`；`PairingHostSnapshot`、`PairingGuestResult`、`PairingStepCodes`、`PairingUiText` |
| `windows/src/MediaHub.App/Views/SyncPage.xaml` | 227 | 同步页新增「配对设备（本机）」「加入配对（对端）」两块卡片 |
| `windows/src/MediaHub.App/Views/SyncPage.xaml.cs` | 458 | 按钮/倒计时/剪贴板/InfoBar 接线，`DispatcherTimer` 500 ms 轮询主机状态，`OnUnloaded` 释放两个 presenter |
| `windows/tests/MediaHub.Tests/PairingCodeAndPresenterTests.cs` | 613 | 23 例纯逻辑测试（含 2 例回环真配对） |

驱动层刻意放在 `MediaHub.Sync`（而不是 App）：配对状态机、倒计时、稳定码与文案都可以在没有 WinUI 的情况下测试。

## 3. 交互流程

### 3.1 主机侧（发起方，「配对设备（本机）」卡片）

1. 点**开始配对** → `PairingPresenter.StartHost(now)`
   - 先关掉上一次的监听与状态（重复点击不会留下两个监听器）；
   - `PairingTransportServer.Start` 绑定 `127.0.0.1:0`（系统分配端口），自签 TLS 证书，生成 32 字节 challenge；
   - 载荷有效期 `PairingPayload.LifetimeSeconds = 120` 秒；`Clock` 注入为页面传入的毫秒时钟，所以界面倒计时与载荷过期时间同源。
2. 界面显示三样东西：**配对码**（只读文本框，可选中复制）、**证书指纹**（64 位十六进制、每 4 位一组共 16 组的大写形式）、**状态行**（状态 + 「剩余 N 秒」 + 下一步中文提示）。
3. 启动 500 ms `DispatcherTimer` 轮询 `PollHost(now)`：

| 条件 | 快照状态 | 稳定码 | 界面 |
| --- | --- | --- | --- |
| 仍在监听且未到截止 | `Listening` | `pair-host-listening` | 绿色提示「把配对码发给对方…120 秒内有效…」 |
| `AcceptOneAsync` 已完成且成功 | `Paired` | `pair-host-paired` | 「已与对端建立可信连接（指纹一致）…不会自动导入」+ InfoBar 成功；**主机侧不显示对端设备号**（单向线格式，见 6.9） |
| 截止时间到（`now >= expiresAt`） | `Expired` | `pair-host-expired` | 警告「配对码已过期」 |
| 握手/载荷/指纹校验失败 | `Failed` | `pair-host-failed` | 错误 InfoBar + 失败原因 |
| 用户点**结束配对** | `Stopped` | `pair-host-stopped` | 「已结束配对」 |

4. 任何终态（已配对/已过期/失败/已结束）都会立即停止计时器、关闭监听，并清空配对码与指纹（`RetireInvitation`）——屏幕上不会留下一个复制了也没用的码；此时端口可被重新绑定（测试断言）。

### 3.2 访客侧（加入方，「加入配对（对端）」卡片）

1. 粘贴配对码 → 点**连接** → `AcceptGuestAsync(code, now)`。
2. **先本地校验，不满足就不碰网络**：字符集（`A-Za-z0-9-_`）、长度上限 4096、严格 UTF-8、载荷 JSON、`schemaVersion`、challenge 形状、指纹形状、有效期。
3. 通过后 `PairingTransportClient.ConnectAsync`：TLS 1.3 + 证书指纹 pin（期望值取自载荷）。
4. 成功 → 读取对端来源清单（见 3.3）→ 「已收到 N 个来源的清单（未导入）」；结果区文案固定带括号「未导入」。
5. 失败 → 稳定码 + 中文文案（第 5 节），InfoBar 标题「配对码无法使用」或「连接失败」；标题与文案都不出现「已同步」「已登录」。

### 3.3 来源清单从哪来（本次的实现边界）

W6-3B 的线格式只承载六个字段的载荷（`schemaVersion/deviceId/ephemeralUrl/challenge/publicKeyFingerprint/expiresAtEpochMs`），**没有来源清单通道**。因此：

- `PairingPresenter` 接受一个可选的 `PairingPeerEnvelopeReader` 委托；
- App 层把它接到 `SourceSyncService.TryReadEnvelope`（即 `%LOCALAPPDATA%\MediaHub\sync-envelope.json`）；
- 于是「已收到 N 个来源」在当前构建里等于「读到了对端导出的信封」——同机演示可用，真正的跨机清单传输属于 W6-4 传输层。

驱动层只做计数，**从不**调用 `SourceSyncLocalBridge.Apply`、不写 `sync-sources.json`、不改 `ServerManager`。

## 4. AutomationId 表

| AutomationId | 控件 | 可访问名称（Name） | 备注 |
| --- | --- | --- | --- |
| `sync.pair.host` | Button | 开始配对 | 失败/终态后仍可点，用于重试 |
| `sync.pair.stop` | Button | 结束配对 | 仅在监听中可用 |
| `sync.pair.copy` | Button | 复制配对码 | 任务书未要求，便于把码发到对端（见第 7 节偏离） |
| `sync.pair.code` | TextBox（只读） | 配对码 | 只在监听中填写；终态清空 |
| `sync.pair.fingerprint` | TextBlock | 证书指纹 | 16 组 × 4 位，等宽字体，可选中 |
| `sync.pair.status` | TextBlock | 配对状态 | 「状态 · 剩余 N 秒」+ 换行 + 下一步提示 |
| `sync.pair.input` | TextBox | 对端配对码 | 占位提示「粘贴对端的配对码」 |
| `sync.pair.join` | Button | 连接 | 连接期间禁用 |
| `sync.pair.result` | TextBlock | 配对结果 | 只读；成功时以「（未导入）」结尾 |

同页既有的 W6-2B 标识保持不变：`sync.status`、`sync.sources`、`sync.actions`、`sync.export`、`sync.preview`、`sync.empty`、`sync.refresh`、`sync.verify`、`sync.preview.details`。

## 5. 稳定码 → 中文文案

### 5.1 配对码拒绝（`PairingCodeReasons`，前缀 `code-` 与契约层别名）

| 稳定码 | 含义 | 界面文案（要点） |
| --- | --- | --- |
| `code-empty` | 粘贴为空 | 请先粘贴对端的配对码 |
| `not-base64url` | 含非法字符 / 长度不是合法 base64url | 配对码格式不正确 |
| `code-too-long` | 超过 4096 字符 | 配对码过长，可能粘贴了别的内容 |
| `payload-invalid` | base64 解出的不是合法载荷 | 配对码内容无法解析 |
| `unsupported-version` | `schemaVersion` 不是 1 | 配对码版本过新，请升级本机 |
| `bad-challenge` | challenge 形状非法 | 配对码已损坏 |
| `bad-fingerprint` | 指纹不是 64 位十六进制 | 配对码已损坏 |
| `bad-url` | 地址不是合法 http/https 绝对地址 | 配对码里的地址不可用 |
| `expired` | 超过 120 秒有效期 | 配对码已过期，请让对方重新生成 |

### 5.2 传输失败（`PairingTransportReasons`）

| 稳定码 | 界面文案 |
| --- | --- |
| `listener-failed` | 连接被拒绝：对端没有在等待配对（监听可能已关闭或地址不可达）。 |
| `tls-version` | 连接失败：对端没有使用 TLS 1.3，已中止。 |
| `fingerprint-mismatch` | 指纹不匹配：对端证书与本机记录不一致，已中止。 |
| `payload-invalid` | 对端返回的配对数据不完整，已中止。 |
| `lifetime-exceeded` | 配对码已过期，请让对方重新生成。 |
| `already-paired` | 本次配对已经完成过，请重新生成配对码。 |
| `peer-timeout` | 对端超时没有响应，请确认对方仍停在配对界面后重试。 |

未知码一律退化为「配对失败（稳定码：<code>）。」，稳定码只出现在这句兜底里，便于用户回报问题。

### 5.3 一步一句的下一步提示（`PairingUiText.DescribeHostNextStep`）

| 状态 | 文案 |
| --- | --- |
| 未开始 | 点「开始配对」在本机打开监听并生成一次性配对码。 |
| 监听中 | 把配对码发给对方；配对码 120 秒内有效，且只能成功连接一次。 |
| 已配对 | 已与对端建立可信连接（指纹一致）。来源清单只计数，不会自动导入；… |
| 失败 | 配对未完成：<原因> 可以点「开始配对」重试。 |
| 已过期 | 配对码已过期，请重新生成。 |
| 已结束 | 已结束配对，监听已关闭。 |

## 6. 安全不变量（测试钉死）

1. **配对码只含载荷**：`PairingCode.Encode` 输出严格 base64url（无 `=`/`+`/`/`），长度 ≤ 4096。
2. **challenge 不落文本**：`PairingCodeResult.ToString()` 只打印设备号；坏 challenge 被拒时 `Detail` 也不回显它（否则窗口期内可重放）。
3. **先校验后联网**：非法/过期码在本地被拒，不打开任何 socket。
4. **指纹必须一致**：客户端 TLS pin + 载荷内指纹复核；不一致即 `fingerprint-mismatch`。
5. **一次性**：`AcceptOneAsync` 只接受一个对端，成功后立刻关闭监听；重复连接得到 `listener-failed`。
6. **端口必回**：所有终态后端口可重新绑定（测试 `AssertPortIsReusable`）。
7. **不写本地来源**：`PairingPresenter` 没有任何写 `ILocalSourceStore` 的路径；来源只被计数。
8. **不宣称已同步**：成功文案固定为「已收到 N 个来源的清单（未导入）」。
9. **主机侧不伪造对端身份**：W6-3B 是单向线格式（服务端发邀请、客户端不回传），所以 `PairingPresenter.PeerDeviceId` 在主机侧恒为 `null`；若照抄 `PairingTransportResult.Payload` 就会把**自己的**设备号当成对端的显示出来（测试 `Guest_PairsOverLoopbackAndOnlyCountsThePeersSources` 断言这一点）。
10. **终态清空邀请**：停止/过期/失败/成功都清空 `PairingCodeText` 与 `FingerprintDisplay`，避免用户复制一个已经不可用的码。

## 7. 偏离任务书（及理由）

| 偏离 | 理由 |
| --- | --- |
| 增加 AutomationId `sync.pair.copy`（复制配对码） | 手工配对码要发到对端，纯手工选中复制在多行只读框里很容易漏字符；按钮走 `Clipboard.SetContent`，失败只提示不抛 |
| 把配对驱动放在 `MediaHub.Sync/PairingPresenter.cs` 而不是 App | 任务书要求"UI-free testable driver"，放 Sync 层才能被测试项目直接引用（测试项目不引用 App） |
| 成功路径读取来源清单用 `PairingPeerEnvelopeReader` 委托 | W6-3B 线格式只有六字段载荷，没有清单通道；委托让 App 用本机信封演示「收到 N 项」，同时保持驱动层无 IO |
| 主页面的「本机媒体源 / 合并预览」两列整体下移到第 4 行 | 新增配对区需要独立一行，既有 AutomationId 与绑定未改 |
| 主机侧 `PeerDeviceId` 恒为 `null`（不显示对端设备号） | 传输层是单向邀请：服务端拿到的 `Payload` 是自己发出的邀请，直接采用会把自己的设备号冒充成对端的；界面显示「（未知设备）」 |
| 终态清空配对码与指纹（`RetireInvitation`） | 已停止/已过期的码复制了也无法使用，留在屏幕上只会误导用户 |

## 8. 未覆盖项（明确不做）

1. **二维码**：只做手工配对码，不引入二维码渲染/识别依赖。
2. **跨机配对**：服务端只绑定 `127.0.0.1`，局域网/公网地址与发现属于 W6-4；当前配对码里的 `ephemeralUrl` 只能被同机进程连接。
3. **Apply（导入）UI 与审批流**：冲突「需用户确认」的逐条审批属于后续任务；本次不提供任何写入入口。
4. **真实清单传输**：清单通过本机 `sync-envelope.json` 读取（同机演示），W6-4 上线后改为从对端读取。
5. **密钥迁移/DPAPI 重封装**：信封恒 `canSendSecrets = false`，本次不触碰密钥。
6. **已配对设备列表持久化**：配对结果不落盘，重启后需重新配对。
7. **取消进行中的连接**：访客侧连接有 10 秒超时，但界面没有单独的「取消」按钮。
8. **双向身份交换**：主机侧看不到对端设备号/指纹（单向线格式），需要在 W6-4 扩展线格式或增加回传帧。

## 9. 测试

`windows/tests/MediaHub.Tests/PairingCodeAndPresenterTests.cs`，30 个测试方法（`[Theory]` 展开后 **32 例**，全绿），全部不依赖 App、不依赖墙上时间（时间一律传参）：

| 分组 | 用例 |
| --- | --- |
| 编解码 | 往返全字段、仅 base64url 字符、容忍粘贴空白、空粘贴、非法字符、超长、非 JSON、未知版本、过期边界（截止前 1 ms 仍可用）、坏 challenge 不回显、`ToString` 不含 challenge |
| 指纹 | 64 位十六进制 → 16 组 × 4 位，拼接等于原文大写 |
| 主机状态机 | 空设备号被拒、未开始轮询为 Idle、未开始停止为 Stopped、Dispose 后拒绝工作且端口可复用、StartHost 发布可解码的码与分组指纹、重复 StartHost 替换旧监听、StopHost 关闭监听且状态保持并清空配对码、停止后可重新开始、截止时刻恰好过期 |
| 访客 | 坏码不联网、过期码被拒、不可达对端给出稳定码、回环真配对只计数（并断言主机侧不冒充对端设备号）、无信封时仍写「（未导入）」 |
| 文案 | 稳定码集合固定且互不重复、每个拒绝码都有中文解释且不泄露原码、每个传输失败都有中文句子、每个主机状态都有标签与下一步，成功文案不含「已导入」 |

另含 2 例真实网络往返（`127.0.0.1` TLS 1.3 握手、真帧读写），用来证明"监听器真的会关、配对真的能成"，其余 21 例为纯逻辑。
