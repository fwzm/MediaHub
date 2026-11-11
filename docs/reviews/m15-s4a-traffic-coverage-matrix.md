# M15-S4-A 出网路由清单与冷却覆盖矩阵（可审计）

来源：V8 第四节 S4-A。基线：feature/m15-rate-limit-cooldown。本文为静态审查（源码证据），不是执行结果。

## 0. 生产环境 OkHttpClient 构造点（全仓库扫查）

对主源码集执行 OkHttpClient.Builder( 扫查，恰好 3 处：

| # | 构造点 | 说明 |
|---|---|---|
| 1 | core/network/HttpClientFactory.kt | API / media / 桥上游 / 字幕 / 探测 / WebDAV 共用的基座 |
| 2 | app/MediaHubApp.kt | Coil 图片加载（独立 client） |
| 3 | player/engine/PlayerFactory.kt | Media3 ExoPlayer 取流（独立 client） |

未发现 HttpURLConnection / java.net.URL / Retrofit / Ktor / DownloadManager / WebView 的生产用法；
唯一的裸 socket 是 MpvHttpBridge 的两个 loopback ServerSocket。

## 1. 覆盖矩阵

图例：A = addInterceptor（应用层），N = addNetworkInterceptor（network 层）。

| 路径 | 构造点 | 拦截器链（按顺序） | 冷却 | 凭据跨源 | UA | 重定向 |
|---|---|---|---|---|---|---|
| API / Provider | HttpClientFactory.apiClient() | A 冷却(不记账) -> A RequestId -> A Redacting -> A RetryOnce -> Connect -> N UA -> N 冷却(逐跳记账) | 是 | 否 | 是 | 开 |
| media/stream | HttpClientFactory.mediaClient() | A 冷却(不记账) -> A RequestId -> A Redacting -> Connect -> N UA -> N 冷却(逐跳记账) | 是 | 否 | 是 | 开 |
| **Coil 图片** | MediaHubApp.kt | A ProviderImageAuth -> Connect -> N OriginScoped -> N UA | **否** | 是 | 是 | 开 |
| 字幕下载（mpv） | MpvPlaybackEngine 由 mediaClient().newBuilder() 派生 | 继承 media 链 + N OriginScoped | 是 | 是 | 是 | 开 |
| Emby / Jellyfin | EmbyProviderFactory / JellyfinProviderFactory（newBuilder() 派生） | 继承 API 或 media 链，index 0 前置 identityGuard | 是 | 否 | 是 | 开 |
| **Media3 ExoPlayer 取流** | player/engine/PlayerFactory.kt | N OriginScoped -> N UA（无冷却） | **否** | 是 | 是 | 开 |
| mpv 原生直连 | 无（mpv 只拿到 loopback URL） | N/A | N/A | N/A | N/A | 由 ytdl=no 关闭自取网 |
| 桥到上游 | MpvHttpBridge 由 mediaClient().newBuilder() 派生 | 继承 media 链 + N OriginScoped | 是 | 是 | 是 | 开 |
| WebDAV | WebDavProviderFactory -> .newBuilder() | 继承 API 或 media 链 | 是 | 否 | 是 | **关闭**（followRedirects(false)） |
| 服务器探测 | EndpointTestService | 继承 API / media 链 | 是 | 否 | 是 | 开 |

## 2. 结论：**两条生产出网路径完全没有 429 冷却**

1. **Coil 图片加载** —— 链中无 RateLimitCooldownInterceptor。
2. **Media3 ExoPlayer 取流** —— 即**实际播放视频**的那条路径，链中无 RateLimitCooldownInterceptor。

V8 明确指出：不能因 API/mediaClient 绿灯就推断全部流量受控。本矩阵确认该推断**不成立** ——
两条路径均**不受** 429 冷却保护。这属于 NOT_COVERED，不是自动共享。

### 2.1 一个额外的结构性事实

PlayerFactory 没有依赖 HttpClientFactory，而是自建 OkHttpClient.Builder()。
因此 MediaHttpClient.okHttpClient() 所提供的复用同一 client 能力**未被播放器使用**：
Media3 有自己的连接池与拦截器列表。所谓共享连接池在播放路径上并未实现。

### 2.2 冷却表是否共享

生产仅有一处 HttpClientFactory 构造（AppModule 的 @Provides @Singleton），
且未传入 rateLimitCooldown 参数，故使用默认实例 —— **整个进程只有一张冷却表**。
因此 API 路径上的 429 **会**限制 media/桥上游/字幕路径（同一 serverId/origin 时）；
但**不会**限制 Coil 与 Media3（二者根本不在该链上）。

## 3. 归属键与歧义分支

- 冷却键 = serverIdFor(url) 优先，未知时回退**精确 origin**（scheme://host:port）。
- 端口被区分（52f7399 已修复同 host 异端口被误冷却）。
- 路径按**最长段前缀**匹配；同 scheme+host+port 且同长度前缀的多个服务器 fail-closed 返回 null，
  此时回退到共享 origin 键 —— 该歧义分支下 A 服的 429 仍可能限制 B 服。
  该残余风险有界（不超过 15 分钟），且只在歧义配置下出现。

## 4. 待办（不在本切片内）

- 是否给 Media3 与 Coil 接入冷却：需评估「播放流被 429 冷却是否比不冷却更好」
  （长视频流被误冷却的代价可能高于收益），以及跨模块改造的爆炸半径。
  V8 要求有针对性地最小改造，不要求无条件全接入。
- 歧义分支的隔离键改进（例如引入配置级稳定标识而非依赖 URL 形状）。