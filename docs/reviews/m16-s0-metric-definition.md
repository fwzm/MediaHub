# M16-S0 指标定义与隐私口径（可审计）

来源：V8 第三节。本文件是 M16 的口径基准，后续切片不得在未修订本表的情况下改变语义。

## 1. 计数单位

| 指标 | 定义 | 产生位置 | 为什么必须单列 |
|---|---|---|---|
| logicalCalls | 一次 OkHttp Call | callStart（或执行前取消的结算路径） | 逻辑操作数；与业务动作一一对应 |
| exchanges | 真正写到线上的一个 HTTP 请求 | requestHeadersStart | 重定向/重试会让一次 Call 产生多次 exchange；这是服务器真实承受的请求数 |
| redirectHops | 3xx + Location 的响应次数 | responseHeadersEnd（按状态码累计） | 重定向是额外 exchange，必须能单独归因 |
| connectAttempts | 新建物理连接次数 | connectStart | 连接池复用不触发；用于识别连接 churn |
| canceledCalls | 被取消的逻辑请求 | canceled | 取消不是没发生：要求零交换但必须可观测 |
| failedCalls | 带异常类别结束的请求 | callFailed | 只保留异常类名 |
| loopbackInbound | mpv 到本桥 127.0.0.1 的下游接入 | 桥 accept 路径 | 不是本机对外出网，不得计入 exchanges |
| localFileReads | 本地文件读取 | 桥 local-file 分支 | 根本不是网络请求 |
| statusCounts | 每个 exchange 的响应状态分布 | responseHeadersEnd | 逐跳记录；3xx 不被最终状态覆盖 |
| partialObservability | 存在观测不到的通道 | recordUnobservable | 未知不得算 0 |

## 2. 通道分类（RequestChannel）

API / MEDIA / IMAGE / SUBTITLE / BRIDGE_UPSTREAM。
注意 BRIDGE_UPSTREAM 是真实出网（桥到上游），与 loopbackInbound（下游接入）严格分开。

## 3. 隐私红线（强制）

观测绝不携带：完整 URL、path、query、token、cookie、Authorization、User-Agent、自定义服务器名、字幕正文。

保留字段与理由：

| 字段 | 是否可识别 | 说明 |
|---|---|---|
| requestId | 否 | 随机 12 字符，仅用于串联同一次 Call |
| scopeId | 否 | SHA-256(salt + host + port) 取前 12 hex；salt 每次进程随机，不可逆、不可跨进程关联 |
| method | 否 | GET/POST 等 |
| statusCode | 否 | 整数 |
| errorKind | 否 | 异常类名；异常消息可能含 URL，故不保留 |
| durationMs | 否 | 阶段耗时 |
| isRange | 否 | 布尔；只判断 header 是否存在 |

实现层面的保证：NetworkExchangeRecorder 只读取 url.host / url.port / method / 状态码 / 异常类名，
从不读取 path、query 或任何 header 的值（Range 仅判断存在性）。因此忘记脱敏在结构上不可能导致凭据泄漏。

## 4. 有界性

- 按 scope 明细：上限 DEFAULT_MAX_TRACKED_SCOPES = 64；达到上限后新 scope 不再新增明细，累计计数继续。
- 观测环形缓冲：上限 DEFAULT_MAX_OBSERVATIONS = 128。
- 累计计数使用 AtomicLong，永不因裁剪而丢失。

## 5. 已确立的 OkHttp 事件次序（实测，非推断）

由临时探针在 OkHttp 4.12 上实测：

| 场景 | 事件序 |
|---|---|
| execute 前 cancel | canceled → callStart → callFailed（没有 callEnd） |
| 正常完成 | callStart → connectStart → requestHeadersStart → responseHeadersEnd → callEnd |
| 重定向链 | 每跳一组 requestHeadersStart + responseHeadersEnd；response.priorResponse 恒为 null |
| 在途取消 | callStart → ... → canceled → callFailed |

两个反直觉结论（已写进实现注释）：

1. canceled 会在 callStart 之前触发；
2. responseHeadersEnd 触发时 priorResponse 尚未挂接，因此重定向跳数只能按状态码累计。