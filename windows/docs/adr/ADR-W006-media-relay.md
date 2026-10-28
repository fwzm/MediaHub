# ADR-W006 — 播放流的本地转发与引擎网络边界

- 状态：**已接受**（2026-10-14）
- 相关：ADR-W005（每媒体源网络策略）

## 问题

Android 端 ADR-030 确立了一条红线：**跨 origin 重定向必须剥离凭据**，并由
`OriginScopedCredentialHandler` 在 Windows 端显式实现（自行跟随重定向，逐跳比对 origin）。
但那只覆盖**应用层发出的 HTTP**。

播放流由 libmpv 用它自己的网络栈抓取。因此必须先回答：**引擎是否遵守同一套策略？**

## 实测结论（先测量，再设计）

用 `windows/tools/MpvNetworkProbe` 对钉住的引擎构建（mpv v0.41.0-1154-gec38361c5）实测：

| 问题 | 实测结果 |
| --- | --- |
| `--http-header-fields` 在**同 origin** 重定向后是否保留 | **保留**（符合预期） |
| `--http-header-fields` 在**跨 origin** 重定向后是否保留 | **原样发给新 origin** —— `X-Emby-Token` 被投递到另一个主机 |
| 代理选项名 | `http-proxy` 被接受；`proxy` 与 `http_proxy` 被拒绝 |
| 是否遵守配置的代理 | **遵守，但受环境变量影响**：`NO_PROXY` 含 `127.0.0.1` 时引擎直连，绕过 `--http-proxy` |
| 引擎选项拼写错误 | **静默忽略**（`mpv_set_option_string` 返回错误码，旧实现未检查） |

> 结论：**默认情况下引擎与应用的网络策略并不一致。**
> 凭据会跨 origin 泄漏，路由取决于用户的 shell 环境变量而非配置的策略。

## 决策

**服务器媒体流一律经本地转发（`LoopbackMediaBridge`）取回；引擎只拿到一个不含任何凭据的回环 URL。**

```
应用层                                  引擎
  │                                      │
  ├─ API / 图片 / 字幕 ──► MediaSourceHttpClientFactory（策略 + 逐跳凭据作用域）
  │                                      │
  └─ 播放流 ──► LoopbackMediaBridge ──►  http://127.0.0.1:<port>/<token>/stream
                     │                     │
                     └──► 同一 MediaSourceHttpClientFactory ──► 媒体服务器
```

**这样做的关键收益不是"两套配置保持一致"，而是"只有一套网络代码"。**
代理策略与重定向凭据作用域不可能漂移，因为它们根本是同一段实现。

### 为什么不直接用 `--http-proxy`

因为代理只是问题的一半。即使代理完全对齐，引擎仍会把 `X-Emby-Token` 投递给跨 origin 的
重定向目标——那是这轮实测确认的凭据泄漏。`--http-proxy` 无法表达"在 origin 变化时剥离请求头"。

此外 `--http-proxy` 的取值只能来自环境或显式配置；实测表明环境变量（`NO_PROXY`）会覆盖它。
应用现在仍会把策略推导出的值显式传给引擎（`NetworkPolicy.ToEngineProxyValue`），
使得"引擎的配置是策略的函数"可被读回验证（`GetOptionValue("http-proxy")`），
但**安全保证来自转发层，不来自这个选项**。

## 转发层的约束（安全与控制）

一个能访问带凭据媒体的本地 HTTP 端口对其他本地进程和网页是有吸引力的目标，因此按多条轴收紧：

| 轴 | 措施 |
| --- | --- |
| 监听范围 | 仅 `127.0.0.1`（绑定失败则回退 `localhost`），端口随机 |
| 会话授权 | URL 含 256 位随机会话令牌，每次播放重新生成，不持久化、不写日志 |
| 网页滥用 | 带 `Origin` 或 `Referer` 的请求一律 403 —— 浏览器跨源请求必然携带 `Origin`，而回环地址对任何网页都是跨源 |
| DNS rebinding | `Host` 必须等于绑定的端点 |
| 生命周期 | 播放结束即关闭监听并取消在途请求 |

资源纪律（任务明确要求）：**不缓存任何内容**，每条连接只有一个 64 KiB 拷贝缓冲；
Range 原样转发（seek 仍是一次往返）；并发上限 4；退出时取消而非放任排空。

## 引擎侧凭据的分层

媒体流的鉴权头（Emby 的 `X-Emby-Token`、Jellyfin 的 authorization 头）由 provider 通过
`IProviderPlayback.StreamRequestHeaders` 提供，并附带 `StreamHeaderOrigin`。
**只有当解析出的流 URL 与服务器同 origin 时才附加**：
服务器可以合法地把流指向对象存储或 CDN，把会话令牌发到那里就是在重演本 ADR 要消除的泄漏。

## 对"引擎必须能播放"的验证

`Engine_through_the_bridge_never_delivers_the_token_to_a_redirect_target`：
起两个回环服务器，A 对带凭据的请求回 302 指向 B；转发层持有凭据，引擎只拿到回环 URL。
断言：A 收到请求、B 被访问到、**B 从未看到 `X-Emby-Token`**、且引擎仍拿到可播放媒体
（`duration > 0`）。

## 后果与代价

- 正向：凭据泄漏与代理漂移在结构上不可能发生；媒体路由可被逐请求审计。
- 代价：多一次回环拷贝（本机内存带宽量级）；引擎的`path`属性会是回环 URL，
  因此 UI 显示与日志一律使用 `PlaybackRequest.DisplayUrl`（已脱敏的服务器 URL）。
- 未验证：真实 VPN/TUN 环境下的出口一致性；SOCKS 代理；引擎自身的 `--http-proxy`
  在转发层存在时已非关键路径，但仍在设置中保留以便诊断。
