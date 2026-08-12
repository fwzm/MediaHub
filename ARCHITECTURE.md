# 架构说明（ARCHITECTURE）

> 本文档描述**当前真实架构**（以实际代码为准）。如与旧文档冲突，以代码为准并更新本文档。

## 1. 分层与依赖方向

```
UI (feature/*)
  ↓ 只依赖领域模型与 Provider 接口
UseCase / ViewModel
  ↓
Repository (core:database)
  ↓
Provider (provider/*) —— MediaProvider 统一接口
  ↓
Remote / Local（core:network ApiClient / MediaHttpClient；本地文件系统）
```

- UI 永远不接触 Emby/Jellyfin/云盘原始 JSON。
- Provider 层负责 Remote Model → Mapper → Domain Model（`core:model`）。
- 禁止在 UI/UseCase 出现 `if (type == EMBY)` 之类的分支（能力差异用 `ProviderCapability` 表达）。

## 2. 模块依赖图（关键路径）

```
core:model（纯 Kotlin，零依赖） ← 所有模块
core:logging ← core:network / security / database / player / provider / feature
core:common ← 各处（调度器、id、时间、Nav 编解码）
core:network（OkHttp + kotlinx.serialization）← provider:base / feature:server / player:engine
core:security（Keystore）← provider:base（TokenStore）
core:database（Room + DataStore + Hilt）← feature:* / app
provider:api（纯 Kotlin 接口）← provider:base / feature:*
provider:base（BaseMediaServerProvider + 注册表 + Hilt @IntoSet）← provider:emby/jellyfin/webdav
player:compatibility（评估器，纯逻辑）← feature:player（后续接入）
player:engine（Media3 封装）← feature:player
metadata（纯 Kotlin 接口）← 后续 feature
app（组合一切：DI、导航、Provider 工厂装配）
```

## 3. 数据源统一抽象（MediaProvider）

`provider:api` 采用**能力组合**（ADR-014）：`MediaProvider` 只含公共最小契约
（serverId/type/displayName/descriptor/testConnection）；认证、媒体库、详情、浏览、
播放、搜索、字幕、进度为**可选能力接口**，由 `ProviderHandle` 组合暴露
（可空字段 = 类型安全，UI 用 `handle.library != null` 判断，禁止 `type == EMBY` 分支）。

每种数据源实现一个 `MediaProviderFactory`（自报 `ProviderDescriptor`，ADR-015），
通过 Hilt `@IntoSet` 自注册到 `DefaultProviderRegistry`；注册表按 `ServerType` 建索引，
"添加媒体库"页面从 `registry.descriptors()` 动态渲染——新增 Provider 无需改 UI。

- 媒体服务器类共享 `BaseMediaServerProvider`（异常映射、Token 会话、通用 HTTP 探测）。
- 协议差异（endpoint、鉴权头、连接测试）由各子类独立实现，禁止塞进 if/else。
- 连接测试为**协议级嗅探**（ADR-019/024）：Emby/Jellyfin 查 /System/Info/Public（Id/Version 必填），
  WebDAV 用 OPTIONS，Local 查目录；不再用 HTTP <500 判定。
- 凭据生命周期（ADR-016/026）：`CredentialVault` 存长期凭据（密码/API Key/Refresh/Cookie），
  `TokenStore` 存会话令牌（按 localServerId），`EmbySessionStore` 存会话元数据
  （remoteServerId/userId/userName），均 Keystore/私有存储，禁止明文密码。
- 认证会话（Phase 1A，ADR-026）：Emby 登录/恢复/验证/登出闭环；客户端身份
  `ClientIdentity`（core:common）跨协议复用；`X-Emby-Token` 集中注入；
  401=token 失效清会话，网络问题保留会话。

## 4. 领域模型（core:model）

- 统一类型：`MediaType`（MOVIE/SERIES/SEASON/EPISODE/VIDEO/AUDIO/FOLDER/LIVE_TV/OTHER）。
- 统一条目：`MediaItem`（含 serverId/id/path 资源标识）。
- 播放源：`PlaybackSource`（url 临时、headers/cookies 仅内存、mode=DIRECT_PLAY/STREAM/TRANSCODE）。
- 进度：`PlaybackProgress`（本地快照 + 服务端上报两用）。
- 分页：`PageRequest` / `PagedResult`。

关键约束（ADR-003）：**只持久化资源标识（itemId/path），播放 URL 播放时 resolve，绝不落库**。

## 5. 播放管线

```
MediaItem + PlaybackOptions
  → Provider.resolvePlayback()          // 临时 URL（云盘签名链接等）
  → PlaybackCompatibilityEvaluator      // 设备能力 + 偏好 → DIRECT_PLAY/STREAM/TRANSCODE
  → PlaybackEngine(Media3)              // MediaItem + 请求头注入 + SimpleCache
  → PlayerView + 自定义控制层（Compose）
  → 进度回调 → 本地快照 + Provider 上报（尽力而为）
```

- 网络分层：普通 API 用 `ApiClient`（短超时/重试）；媒体流用 `MediaHttpClient`
  （Range/206/302/Cookie、探测结构化 `PlaybackError`、连接池复用）。
- 播放缓存：Media3 `SimpleCache`（LRU 512MB），与元数据缓存（Room）、图片缓存（Coil，后续）分离。
- 请求头上下文：`PlaybackHeadersHolder` 按引擎（会话）创建（ADR-018），多播放器/预加载互不污染。
- 进度同步：`ProgressSyncCoordinator` 三档节流（ADR-017）——本地快照 5s 采样、
  远端上报按 Provider 间隔（默认 10s）、Pause/Seek/Ended 立即 flush、退出 final flush。

## 6. 安全与日志

- `core:security`：Android Keystore AES/GCM 加密存储（SecretStorage），Token 按 serverId 管理。
- `core:logging`：统一 Logger（NETWORK/PLAYER/PROVIDER/DATABASE/AUTH/UI/SECURITY），
  所有实现强制 `Redactor` 脱敏；`MemoryLogger` 环形缓冲供诊断导出。
- 结构化错误：API 层 `ApiException`、Provider 层 `ProviderException`、播放层 `PlaybackError`
  （AUTH_EXPIRED / URL_EXPIRED / HTTP_403 / ... / DECODER_ERROR 等），UI 展示用户文案 + 日志留诊断。

## 7. 性能约束（当前基线）

- 冷启动：Application 无重逻辑；DI 图均为轻量单例。
- 列表：Compose LazyColumn + 分页（PageRequest，默认 50/200 条）。
- 禁止 Main Thread IO；Repository/Provider 全部 suspend。
- 图片管线（Coil）与媒体详情抓取在 Provider 接入阶段引入，保持滚动稳定。

## 8. 未来扩展点（已预留、未实现）

- 聚合媒体库 / 全局搜索（UnifiedLibrary + GlobalSearch，跨 Provider 并发合并）。
- 线路质量评估（RouteQualityEvaluator：DNS/TCP/TLS/TTFB/吞吐/缓冲健康）。
- 元数据刮削（metadata 模块：TMDB/Bangumi/豆瓣）。
- 播放决策接入播放前管线（PlaybackCompatibilityEvaluator 已就绪，尚未接入 resolve 流程）。
- 云盘 / SMB Provider（目录已占位，见 provider/*/README.md）。
