# ADR-W005 — 每媒体源网络策略与凭据作用域

- 状态：**已接受**（2026-10-14）

## 背景

Windows 机器上同时存在多层代理配置：WinINet（浏览器与多数桌面应用）、WinHTTP（服务）、
PAC 脚本、自动代理发现、TUN 虚拟网卡、VPN 与分流规则。它们彼此不一致，也**不适用于同一套网络栈**。

两个具体后果：

1. 一个媒体源的 API、海报、字幕、播放流可能走**不同的出口**，表现为"能登录但播不动"。
2. 重定向链上，长期凭据可能被转发给第三方主机。

## 决策

### 1. 策略绑定到"源"，而不是"请求"

`NetworkPolicy` 属于一个媒体源；`MediaSourceHttpClientFactory` 用同一份策略同时创建
API 客户端与媒体客户端。因此同一源的库列表、图片、字幕、播放、进度上报必然同路。

`NetworkPolicy` 提供三种模式：跟随系统代理 / 强制直连 / 指定 HTTP(SOCKS) 代理。

> **不声称的事**：本策略只覆盖 MediaHub 自己发出的 HTTP（媒体源 API、图片、字幕、进度）。
> **播放流由 mpv 用另一套网络栈抓取**，其代理行为需要单独配置与单独验证。
> "共用一个连接池所以出口 IP 相同"是错误推论，本项目不做该推断。

### 2. 重定向由我们自己跟随

`SocketsHttpHandler.AllowAutoRedirect = false`，由 `OriginScopedCredentialHandler` 逐跳处理。

理由：BCL 确实会在某些重定向中移除 `Authorization`，但

- 行为不完整：`X-Emby-Token`、`X-MediaBrowser-Token` 等自定义头不在其列；
- 行为无契约：把它当作安全属性等于把安全建立在实现细节上。

规则（每条有回归测试）：

| 情形 | 行为 |
| --- | --- |
| 同 origin（scheme+host+port 相同） | 凭据原样转发 |
| 跨 origin | 剥离 `Authorization` / `Proxy-Authorization` / `Cookie` / Emby·Jellyfin token 头 |
| `Range` / `User-Agent` 等无身份语义的头 | 保留 |
| HTTPS → HTTP | 直接拒绝（可配置，默认开） |
| 跳数超过上限（默认 10） | 结构化失败，不无限跟随 |

测试用**两个真实回环监听器**验证"不同 origin"是测试的事实而非假设；
同 origin 场景使用单监听器自跳转，确保只比较端口不同造成的差异。

### 3. 日志与诊断强制脱敏

URL（query 中的 `api_key`/`token`/`PlaySessionId`、user-info）、
请求头（Authorization/Cookie 等）、JWT 形态值、长十六进制串在**写入时**脱敏，
导出的是已脱敏副本，因此"导出诊断"这个动作本身是安全的。

Provider 异常只保留原异常**类型名**，不保留消息（消息常内嵌完整请求 URL）。

### 4. 诊断能力

`NetworkPolicy.Describe()` 供设置页展示当前生效策略。
`MediaServerProbe` 给出**协议级**连接判定（Emby/Jellyfin 必须返回可解析的
`/System/Info/Public`，含 `Id`），而不是"HTTP 状态码 < 500 即视为可连接"——
后者会把 NAS 登录页或反向代理错误页当成媒体服务器。

## 未完成项

- SOCKS 代理的端到端实测（`WebProxy` 对 SOCKS 的支持需单独验证）。
- PAC 脚本解析的正确性未验证（依赖系统解析）。
- TUN/VPN 环境下的出口一致性未验证。
- 图片与字幕请求当前复用 API 客户端；鉴权头注入策略在 W3 接入图片管线时复核。
