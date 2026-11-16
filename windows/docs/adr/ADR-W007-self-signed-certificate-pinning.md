# ADR-W007 — 自签名证书：按服务器显式信任证书指纹

- 状态：**已接受**（2026-11-11）
- 相关：ADR-W005（每媒体源网络策略）、ADR-W006（播放流本地转发）

## 问题

家庭/自建的 Emby、Jellyfin、WebDAV 服务器普遍使用自签名证书。Windows 的证书链校验会拒绝它，
于是这类服务器在**添加阶段的探测**就失败，且失败原因被折叠成一句"连接失败"。

可选做法只有两种：

1. **全局关闭证书校验**（`ServerCertificateCustomValidationCallback => true`）。
   代价是所有媒体源一起失去保护：任何能劫持 DNS 或 ARP 的设备都可以冒充服务器，拿到登录密码与令牌。
2. **每台服务器显式信任其证书指纹。**

用户拍板采用第 2 种，并附加两条硬约束：

- 不得提供全局关闭校验的开关；
- **账号、密码、令牌不得在未经校验的通道上发送**。

## 决策

### 1. 指纹是每源的 `NetworkPolicy` 字段

`NetworkPolicy.TrustedCertificateSha256`（64 位十六进制 SHA-256，容忍 `:`、空格、`-`、`.` 与大小写）。
只有非空时，`NetworkPolicyHandlerFactory.CreateHandler` 才在该源的 `SocketsHttpHandler.SslOptions`
上安装校验回调；其余源完全不动 `SslOptions`，继续使用平台校验器。

因为 `MediaSourceHttpClientFactory` 的所有客户端（API / 图片 / 字幕 / 播放流 / 心跳）都来自这个工厂，
指纹自动覆盖该源的全部请求；也因为只有一个源持有它，其他源不会因它而被放宽。

### 2. 回调只在平台已经说"不"之后才看指纹

```
errors == None            → 通过（平台信任优先）
叶子证书 SHA-256 == pin   → 通过
其余                      → 拒绝
```

pin 命中即通过意味着**同时跳过链校验与主机名校验**。这是刻意的：自签证书常见 CN/SAN 与局域网地址
不一致，而 pin 已经把信任收敛到唯一一张证书，主机名不再承担区分作用。回调内部不抛异常——从这里抛出的
异常会让握手以一条无法诊断的消息结束。

### 3. 获取指纹的探针不发送任何请求

`CertificateInspector` 用 `TcpClient` + `SslStream` 直连，在握手回调里记录叶子证书与
`SslPolicyErrors`，**始终返回 false**。握手必然失败，因此不会有 HTTP 请求字节写入——这也正是
"第一次添加（无 pin）失败时密码没有泄漏"的依据：失败发生在 TLS 握手，早于任何请求头。

`http://` 地址直接返回 null（没有 TLS 层可查）。

### 4. 添加流程必须由用户确认

`AddAsync` 在探测失败且能读到不受信证书时，返回 `ServerAddResult.UntrustedCertificate`
（主机、Subject、Issuer、有效期、SHA-256、错误位），由 UI 弹出第二个 `ContentDialog`：

- 默认按钮是**取消**；
- 确认后才带 `TrustedCertificateSha256` 重试 `AddAsync`，此时才会发送凭据；
- 取消则不添加服务器、不保存任何 pin。

### 5. 指纹持久化并按源恢复

`PersistedServer.TrustedCertificateSha256` 与 `ServerEntry` 同名字段保存该值（指纹是公开材料，不是秘密）。
`ServerManager.PolicyFor(entry)` 是**唯一**取策略的入口，恢复、重连、复测全部经过它——否则会出现
"添加时能连、重启后就连不上"的分裂。

> 2026-11-12 更正：播放转发此前绕过了这个入口。`PlaybackCoordinator` 给 `LoopbackMediaBridge` 传的是
> `NetworkPolicy.FollowSystem`，于是自签名服务器能浏览、能取字幕，播放却必然在转发取流时握手失败。
> 现在转发策略同样由 `PolicyFor(entry)` 派生（找不到条目才退回系统策略），与本 ADR §1 "播放流由同一个
> 工厂发出" 的表述才真正一致。
>
> 2026-11-12 已在真实自签 TLS 上复验（本地 Kestrel 自签 HTTPS → 进程内 Mock Emby，媒体在 TLS 边缘按
> Range/206 响应，应用走真实 UI 流程；16 项检查全通过）。三条路径都取得运行证据：
>
> - 指纹信任：弹窗显示的自签证书 SHA-256 与代理证书逐字符一致，确认后添加成功并保存 pin，播放出画面
>   （引擎自报 640×360，媒体请求经应用 HTTP 栈携带会话与 `Range` 命中 TLS 边缘）；
> - 该台不校验：`AllowUntrustedCertificate=true` 且无 pin，可连可播；
> - 取消确认：提示"未添加媒体源"，`settings.json` 与凭据库字节哈希未变化，不落 pin、不写令牌。
>
> 证据：`D:\mh-verify\evidence`（`results.md`、截图、代理日志）。这是本地复刻，不等同于真实第三方服务器。

## 已知限制

- 输入是 `http://`、服务器再 302 到自签名 `https://` 的场景不会提示指纹（探针按输入 scheme 判断）。
  此时请求会失败并被报为连接错误，不会静默降级。
- 服务器更换证书（例如重装、Let's Encrypt 轮换到自签）后需要重新确认；这是 pin 的预期行为，
  不是缺陷。

## 后续调整：新增按服务器的“不校验证书”选项

用户后续要求"是否校验证书按每台服务器单独设置"，因此在 pin 之外增加第二种选择：

| 选项 | 行为 | 持久化字段 |
| --- | --- | --- |
| 信任该指纹（推荐） | 平台校验失败时，仅接受 SHA-256 与指纹完全一致的叶子证书 | `TrustedCertificateSha256` |
| 该台不校验 | 跳过该服务器的证书校验（`SslOptions` 回调恒为 true） | `AllowUntrustedCertificate` |

约束没有变：

- 两者都只作用于**该服务器**的 handler，仓库内**不提供任何全局关闭校验**的开关；
- 两者互斥，`NetworkPolicy.Validate` 直接拒绝同时设置，避免行为歧义；
- 只有用户确认后才写入并重试登录；确认对话框里明确写出 "跳过校验" 的后果——任何能截获该连接的人
  都可以冒充服务器，并读到这台服务器的令牌与账号；
- 恢复、重连、复测继续统一走 `PolicyFor(entry)`，两种选择都会随服务器恢复。
