# 云盘与 STRM 接入契约（ADR-043，设计冻结 / 未实现）

- 状态：**草稿（DRAFT）** —— 仅设计文档，未实现、未封板。本 ADR 不改变任何生产代码。
- 编号依据：ADR-042 已被 M1「设备能力证据驱动引擎选路」占用（PR #28），故本 ADR 取 **ADR-043**。
- 落库位置：`docs/providers/cloud-strm-contract.md`；DECISIONS.md 中的 ADR-043 为精简决策记录，本文件为细则。
- 日期：2026-10-14
- 范围：云盘类数据源（阿里云盘 / 百度网盘 / 夸克网盘 / 中国移动云盘 / 中国电信天翼云盘）与
  `.strm` 引用文件的接入设计；不含 SMB、不实现播放链路。
- 引用约定：本文所有 `路径:行号` 均为仓库 `D:\deepseek_test\MediaHub` 内的**全路径相对路径**。
  - 核对基线 commit：`1246313`（"Merge pull request #27 from fwzm/docs/a4-convergence-closeout-2026-10-14"）。
  - 核对时间：2026-10-14T09:55+08:00。**该工作树当时非 clean**（`git status` 另有 8 个已修改文件与若干未跟踪文件，
    其中 `feature/player/.../PlayerViewModel.kt` 等在本次核对期间被**并发编辑**过）。
    因此：**行号引用绑定的不是 commit `1246313`，而是上述核对时刻的工作树内容**；
    在该工作树被继续编辑后，行号可能漂移。复核方式：按 `路径:行号` 重读源码比对本文摘录的关键标识符。
  - 本文覆盖 163 处 `路径:行号` 引用（133 处去重），已逐条回读源码核对（范围存在性 + 关键标识符命中），
    结果零失败；但这验证的是**文档准确性**，**不构成任何产品行为证据**。
- 前置约束（不得违反，本 ADR 全部结论都在其下展开）：
  - **ADR-003**：云盘/媒体播放 URL 播放时动态解析，绝不落库（`DECISIONS.md:16-20`）。
  - **ADR-026**：Token 只走 Header，不进 URL（`DECISIONS.md:170-184`）。
  - **ADR-030**：跨 origin 重定向剥离凭据（`DECISIONS.md:221-242`）。
  - **ADR-031**：图片鉴权同源注入（`DECISIONS.md:244-256`）。
  - **ADR-009**：缓存分离，禁止混用（`DECISIONS.md:50-53`）。
  - **ADR-014 / ADR-022**：能力接口组合 + Handle 只暴露真实实现的能力（`DECISIONS.md:82-88`、`DECISIONS.md:138-145`）。
  - **ADR-041**：导出不携带凭据或临时 URL（`DECISIONS.md:610-616`）。

---

## 1. 背景与现状（全部基于真实代码）

### 1.1 目录结构与模块状态

- `settings.gradle.kts:25-54` 声明 23 个 Gradle 模块，其中 `provider` 下**仅有** `:provider:api`、
  `:provider:base`、`:provider:emby`、`:provider:jellyfin`、`:provider:webdav`、`:provider:local`。
- `provider/aliyun`、`provider/baidu`、`provider/quark`、`provider/china-mobile`、`provider/tianyi`、
  `provider/smb` **只有 README 占位**，未 include 进 Gradle，**没有一行 Kotlin 代码**。
  `provider/aliyun/README.md:1` 自述“规划中，未创建 Gradle 模块”。
- `ServerType` 已预留云盘枚举：`ALIYUN_DRIVE`/`BAIDU_DRIVE`/`QUARK_DRIVE`/`CHINA_MOBILE_CLOUD`/`TIANYI_CLOUD`
  （`core/model/src/main/kotlin/com/mediahub/model/ServerType.kt:15-19`），未注册任何 Factory。
- 路线图把云盘放在 V0.3（`ROADMAP.md:40-45`），并明确原则“禁止一次开发十种云盘”（`ROADMAP.md:52`）。

### 1.2 provider 抽象现状

- 公共最小接口 `MediaProvider`：`serverId`/`type`/`displayName`/`descriptor`/`testConnection()`
  （`provider/api/src/main/kotlin/com/mediahub/provider/api/MediaProvider.kt:12-27`）。
- 能力接口（`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt`）：
  - `MediaBrowseProvider.listFolder(folder, page)` —— 注释即写明“文件树浏览能力（云盘 / NAS / 本地）”
    （`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:49-52`）；
  - `MediaPlaybackProvider.resolvePlayback(item, options): PlaybackSource`
    （`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:54-57`）；
  - `MediaDetailProvider`、`MediaSearchProvider`、`MediaQueryLibraryProvider`、`MediaSubtitleProvider`、
    `MediaProgressProvider` 等（`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:36-131`）。
- `ProviderHandle` 以可空字段组合能力并派生 `runtimeCapabilities`
  （`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderHandle.kt:15-51`）；
  `ProviderCapability` 枚举含 `BROWSE`/`PLAYBACK`/`MULTI_VERSION` 等
  （`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderCapability.kt:21-25`、
  `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderCapability.kt:45-56`）。
- 云盘所需的两块能力**当前不存在**：目录优先的元数据来源、STRM 引用解析。

### 1.3 网络层现状

| 事实 | 证据（路径:行号） |
| --- | --- |
| `ApiClient`（短超时/重试一次）与 `mediaClient`（读超时=0、call 超时=0、不重试）分离 | `core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:24-35`；ADR-012 `DECISIONS.md:65-68` |
| 仅对 GET/HEAD/DELETE 在 `IOException` 时重试 **1 次**，固定 `RETRY_DELAY_MS=300` | `core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:59-79` |
| 统一脱敏日志 + `X-Request-Id` | `core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:42-56`、`core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:83-101` |
| `ApiClient` 以 `enqueue` 桥接取消，取消经 `Call.cancel` 真实打断，并把 OkHttp 包装的取消**解包**为 `CancellationException` | `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:146-184`（解包见 `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:176-182`） |
| `ApiClient.probe` 的 `timeoutMs` 是真实 deadline | `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:195-242` |
| `MediaHttpClient.probe` 发 `Range: bytes=0-0`，解析 `206`/`Accept-Ranges`，回传 `finalUrl` | `core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:24-70` |
| `OriginScopedCredentialInterceptor` 在 network interceptor 逐跳剥离 7 个凭据头（含 `Cookie`/`Authorization`/`Proxy-Authorization`） | `core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:28-58`；`DECISIONS.md:230-239` |
| 播放栈：`OkHttpDataSource` + 上述 interceptor + 连接池(5, 5min) + `User-Agent = "MediaHub"` | `player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:35-52`、`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:72-74` |
| `mpv` 走同名安全栈的 loopback 媒体代理 | `core/network/src/main/kotlin/com/mediahub/core/network/MpvHttpBridge.kt:18`、`core/network/src/test/kotlin/com/mediahub/core/network/MpvHttpBridgeTest.kt:17-68` |
| 播放请求头 session-scoped 注入（`HeaderAwareDataSource.open` 读最新值） | `player/engine/src/main/kotlin/com/mediahub/player/engine/HeaderAwareDataSource.kt:23-34`；`player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackHeadersHolder.kt:8-16` |
| 起播网络元数据观测：redirect 跳数 / `Accept-Ranges` / `Content-Range` / `Content-Length` | `core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt:25-45`；`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt:16-23` |
| `PlaybackError.Code` 14 项；`PlaybackErrorMapper` 只映射 401/403/404/429/5xx | `core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:21-36`、`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:46-56` |
| 容器 MIME 判定是**扩展名白名单**（无文件头嗅探） | `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:67-80`；`provider/local/src/main/kotlin/com/mediahub/provider/local/LocalProvider.kt:168-181`；`core/model/src/main/kotlin/com/mediahub/model/MediaTypeGuesser.kt:10-23` |
| WebDAV 播放：拒绝 URL user-info、拒绝起点不在服务器 origin 的地址、Basic 只走 header | `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:31-65` |
| Emby 播放：`/Videos/{id}/stream.{container}?MediaSourceId&PlaySessionId&static=true`，**URL 不含 Token** | `provider/emby/src/main/kotlin/com/mediahub/provider/emby/api/EmbyApiClient.kt:210-228` |
| Emby 播放：强制 `X-Emby-Token` 不被 `RequiredHttpHeaders` 覆盖 | `provider/emby/src/main/kotlin/com/mediahub/provider/emby/playback/EmbyPlaybackProvider.kt:84-94` |
| 限流语义已有类型：`ProviderException.RateLimited(retryAfterMs: Long?)` | `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt:82-84`；`provider/base/src/main/kotlin/com/mediahub/provider/base/BaseMediaServerProvider.kt:50`；`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavApi.kt:117` |
| 总在途硬上限先例：WebDAV 桥 `Semaphore(16)` | `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCallBridge.kt:27-62` |
| 聚合查询并发先例：`Semaphore` 上限 4 | `feature/search/src/main/kotlin/com/mediahub/feature/search/engine/GlobalSearchEngine.kt:61-88` |
| 播放缓存：`SimpleCache` + `LeastRecentlyUsedCacheEvictor`，目录 `cacheDir/playback_cache`，默认 512MB | `player/engine/src/main/kotlin/com/mediahub/player/engine/MediaCacheProvider.kt:19-28`；ADR-009 `DECISIONS.md:50-53` |
| 备份：`SENSITIVE_QUERY_KEYS` 覆盖 token/sign/x-amz-*/x-goog-* 等；user-info 与敏感 query 命中即拒 | `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:16-54` |
| 备份白名单排除临时播放 URL / posterUrl / Cookie / Authorization | `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:7-16`、`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:59` |
| 备份文件上限 10 MiB、最多 1000 源 / 每源 64 线路 / 20000 进度 | `docs/backup/README.md:8-11` |
| Token/Cookie 归 `CredentialVault`（Keystore 加密），禁止 Room/DataStore 明文 | `DECISIONS.md:99-105`（ADR-016）；`core/security/src/main/kotlin/com/mediahub/core/security/CredentialVault.kt:8-13` |
| 日志脱敏键名集合（含 cookie / authorization / x-emby-token / proxy-authorization） | `core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt:15-33` |

### 1.4 关键空白（本 ADR 必须显式回答）

1. **仓库中不存在任何 `.strm` 相关代码或文档**：对全仓库（含 `*.kt` 与 `*.md`）检索
   `strm` / `.strm` 均**零命中**；“Emby 如何处理 .strm”在本仓库**无实现可依**，属**待验证**（V-01）。
2. **`metadata` 模块只有接口**：`MetadataProvider` 只有 `search(query, year)` / `detail(id)` /
   `source`（`metadata/src/main/kotlin/com/mediahub/metadata/MetadataProvider.kt:9-23`），
   **没有任何实现**，也**没有任何读取媒体文件字节的入口**。这正是不读文件头策略的天然落点。
3. **签名 URL 失效链路只有一半**：`PlaybackSource.expiresAtEpochMs` / `isExpired(now)` 已存在
   （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:34-35`、
   `core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:42-44`），
   但**当前无任何生产调用方**消费它；“URL 过期自动重解析”在
   `docs/player/README.md:38` 里仍是 **V0.1 计划项**，不是已实现能力。
   （行号说明：原始写入文件显示为 `:34-44`，此处两处引用为同一字段与其方法。）
4. **410 未建模**：`PlaybackErrorMapper.fromHttpStatus` 无 410 分支
   （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:46-56`），
   410 会落 `UNKNOWN`。
5. **无“自动跟随重定向”的统一开关**：OkHttp 默认 `followRedirects=true`；WebDAV 主动关闭它
   （`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavApi.kt:44-45`、
   `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavProvider.kt:107-108`）；
   播放栈**没有显式设置**，依赖 OkHttp 默认值
   （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:41-48`）。
6. **无按账户/主机的并发限流**，亦无 `Retry-After` 解析
   （`ProviderException.RateLimited.retryAfterMs` 始终为 `null`，
   `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt:83`）。

---

## 2. 决策

### 决策 1：目录/文件名优先的元数据获取策略

**决策**

1. **默认不联网取名**：云盘条目的标题/年份/季集号的**第一来源是文件名与目录名**，不是远端元数据服务。
   文件名解析必须做成**纯函数**（无 IO、无 Context），按以下顺序提取：
   `目录名（父目录 = 作品名）` → `文件名（标题）` → `季集模式`（`S01E02`/`1x02`/`第 2 季 第 5 集`）→
   `年份`（`(2021)`/`.2021.`）→ `分辨率/编码/HDR 后缀剥离`。
2. **分页优先、逐级下钻**：云盘浏览走 `MediaBrowseProvider.listFolder(folder: MediaItem?, page: PageRequest)`
   （`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:49-52`）。
   `MediaItem.parentId` 已为文件树导航预留
   （`core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:13-14`），
   云盘 Provider 必须填 `parentId` 与 `path`（相对路径），分页 `nextOffset` 语义沿用
   `LocalProvider` 的累计位移修正
   （`provider/local/src/main/kotlin/com/mediahub/provider/local/LocalProvider.kt:87-97` —— 防止同页死循环）。
3. **类型判定只看扩展名**：沿用既有 `MediaTypeGuesser.forPath`
   （`core/model/src/main/kotlin/com/mediahub/model/MediaTypeGuesser.kt:10-18`）与 WebDAV/Local 的 MIME 白名单
   （`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:67-80`）。
   **扩展名即类型，不打开文件。**
4. **重命名兜底走文件名**：云盘 `file_id` 稳定但显示名可变，因此**持久化键用 `file_id`，
   展示与刮削输入用 `path`（相对路径）**，两者都在 `MediaItem` 上
   （`core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:43-44`、
   `core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:8-11`）。
5. **刮削输出是“补充”，不是“替代”**：远端元数据（TMDB/Bangumi/豆瓣，
   `metadata/src/main/kotlin/com/mediahub/metadata/MetadataProvider.kt:18-23`）
   只允许**覆盖**可由文件名推导的字段子集；`path`/`id`/`parentId`/`sizeBytes` 等资源事实字段
   一律以云盘返回值为准，禁止被刮削改写。

**理由**

- 本地/WebDAV 两条既有链路已经证明“文件名 + 扩展名”足以驱动浏览与播放，且**零额外网络**；
  云盘 API 有配额与风控（见威胁 T-05），目录列表之外的请求应当稀缺。
- ADR-011 已确立“文件在哪（StorageProvider）”与“这是什么（MetadataProvider）”分离
  （`DECISIONS.md:60-63`）；目录优先策略正是该分离在云盘上的具体化：**刮削只吃字符串输入**。

**后果**

- 需要新增一个纯函数解析器（建议放在 `core:model` 的 `FileNameMetadataParser`），
  可被 Emby/Jellyfin 之外的**所有文件型数据源**复用。
- 命名混乱的云盘目录会刮出错误结果 —— 这是可接受的，且**用户可见可纠正**（重命名/手动匹配为后续切片）。

---

### 决策 2：禁止在刮削阶段读取媒体文件头（含为何 + 如何用测试保证）

**决策**

1. **硬红线**：刮削阶段（库构建、目录浏览、海报墙生成、批量扫描）**禁止**对媒体资源发起任何
   **会取回媒体内容的请求**，包括但不限于：
   - 读取文件头（容器魔数 / `ftyp` / EBML / matroska）；
   - 发 `Range: bytes=0-N` 探测容器或编码；
   - 发 `HEAD`/`GET` 到播放地址以“先确认能不能播”；
   - 下载整文件或做抽样解码。
2. **类型与 MIME 只来自扩展名**（决策 1.3）；`container` 字段只来自扩展名；
   `videoCodec`/`audioCodec`/`width`/`height`/`bitrate`/`hdrType` 在云盘场景**允许为空**
   （`PlaybackSource` 这些字段本就是可空，
   `core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:16-25`）。
3. **能力探测只能发生在用户已发起播放之后**，且只允许一次廉价探测
   （`MediaHttpClient.probe` 的 `Range: bytes=0-0`，
   `core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:24-36`），
   不得在列表/详情路径上调用。
4. **刮削输入面收窄为不可执行的纯数据**：`MetadataProvider.search(query, year)` 的入参只允许
   字符串（标题/年份），**不传 URL、不传 URI、不传 Provider 句柄**（现有接口已经如此，
   `metadata/src/main/kotlin/com/mediahub/metadata/MetadataProvider.kt:11-13` ——
   本决策把它固化为不可回退的契约）。

**理由（为什么这条值得写成红线）**

- **成本**：云盘按流量/调用计费且有会员风控。一个 5,000 条目的库，若每条目读 64 KiB 头，
  就是 320 MiB 流量与 5,000 次带签名的取链请求 —— 这是用户看不见的账单。
- **可用性**：签名 URL 有效期短（决策 5），刮削阶段取的链接在用户真正点播放时**已大概率失效**，
  读取本身毫无价值，只制造一次必然失败的请求和一次必然的重解析。
- **风控**：批量、并发、规律性的“取链 + 读头”是云盘风控的典型判定特征（威胁 T-05），
  后果是账号被限速甚至临时封禁 —— 这**不是**技术缺陷，是用户资产损失。
- **安全**：读头请求会携带该站点的签名 URL。任何一次这样的请求都会把“带凭据的 URL”
  送进日志/诊断导出的可观测面（`Redactor` 只保证键名级脱敏，
   `core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt:44-72`）。
  **少发一次，就少一个泄漏面。**
- **架构**：ADR-011 的分离只有在“存储层与元数据层没有共享的字节通道”时才成立。

**如何用测试保证（三条独立防线，全部可达自动化）**

1. **计数式回归（主防线）**：云盘 Provider 的 JVM 单测使用 `MockWebServer` 作为假云盘，
   跑完整“列目录 → 构建海报墙 → 出详情”流程，断言：
   - 该 MockWebServer 收到的**全部请求**的 URL 路径**只命中目录/列表/详情类端点**；
   - **不存在**任何带 `Range` 请求头的请求，不存在对播放路径的 `GET`/`HEAD`；
   - `Range` 断言用 `MockWebServer.takeRequest()` 的 `getHeader("Range")` 全量校验（期望全部为 `null`）。
   证据级别：**EXECUTED-JVM**。
2. **接口面静态约束（防回归）**：断言刮削链路**不持有**任何能发媒体请求的依赖。
   具体做法：`MetadataProvider` 的实现模块**不得依赖** `core:network` 的 `MediaHttpClient`
   与 `player:engine`。这可用 Gradle 模块依赖断言或架构测试（自写反射/依赖图扫描）表达。
   证据级别：**EXECUTED-JVM**（构建期/测试期）。
   现状支撑：`metadata` 模块只有接口、无实现
   （`metadata/src/main/kotlin/com/mediahub/metadata/MetadataProvider.kt` 全文件 50 行），
   该约束现在**零成本**即可成立。
3. **调用点白名单（防误接）**：对 `MediaHttpClient.probe` 与播放栈入口做调用点白名单测试 ——
   断言其调用方集合等于“播放起播路径”的已知集合（当前唯一入口是
   `feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt:572-682` 的 `resolve()`
   之后的引擎播放）。新增调用点必须同时更新白名单，从而在评审中被强制看见。
   证据级别：**EXECUTED-JVM（调用点集合断言）**。
   —— 注意：**源码审查（grep）本身不构成自动化证据**，只能作为辅助；主证据必须是 1/2/3 的可执行断言。

---

### 决策 3：STRM 地址解析与资源身份（与 Emby `.strm` 的交互）

**现状声明（重要）**：仓库**当前完全不认识 `.strm`**。全仓库检索 `strm`（`*.kt` 与 `*.md`）**零命中**；
Emby 侧播放链路只走 `/Videos/{itemId}/stream.{container}`
（`provider/emby/src/main/kotlin/com/mediahub/provider/emby/api/EmbyApiClient.kt:210-228`），
没有任何解析本地 `.strm` 文本文件的分支。因此本节是**新设计**，与 Emby 的交互细节
（尤其“Emby 服务端解析 .strm 后返回什么 `Path`/`MediaSources`”）标记为**待验证（V-01）**。

**决策**

1. **STRM 是“引用”，不是“媒体”**：`.strm` 是一个**纯文本引用文件**，其内容是单个地址。
   我们按下列规则处理（全部为纯函数，可单测）：
   - 读取上限 **8 KiB**（超出即拒绝，防把大文件误当 strm）；
   - 取首个非空、非 `#` 开头的行；去首尾空白与 UTF-8 BOM；
   - 必须能被解析为 **http/https** URL（其它 scheme 一律拒绝，含 `file://`、`smb://`、`ftp://`）；
   - 拒绝 URL user-info（与 WebDAV 同款红线，
     `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:37-46`）；
   - 拒绝 loopback / 私网 / link-local 主机（威胁 T-01）。
2. **资源身份 = (serverId, fileId/path)，不是 URL**：
   - `MediaItem.id` = 云盘 `file_id`；`MediaItem.path` = 相对路径
     （`core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:8-11`、
     `core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:43-44`）；
   - STRM 文件自身的身份 = `(serverId, strmPath)`；
   - **STRM 内的 URL 永远只是“待解析地址”，不构成身份**。同一 `file_id` 今天指向签名 URL A、
     明天指向 B，**身份不变**（ADR-003）。
3. **STRM 是可选能力，不是默认行为**：由 Provider 的 `descriptor` 声明（见 §3.2）。
   **只有声明了该能力的 Provider 才允许把 `.strm` 当条目**；
   未声明的 Provider 遇到 `.strm` 按普通文件处理（`MediaType.OTHER`，不可播）。
4. **与 Emby `.strm` 的交互（设计意图，待验证 V-01）**：Emby 服务端也会把 `.strm` 当作条目暴露给客户端。
   我们的策略是**分工而非猜测**：
   - 若条目来自 **Emby/Jellyfin**：一律走服务端 `resolvePlayback`（`/Videos/.../stream.*`），
     **不由客户端解析 Emby 服务器文件系统上的 `.strm`**（客户端根本读不到那台机器的文件）；
   - 若条目来自**云盘/本地文件树**：走客户端 STRM 解析；
   - **两条路径不得同时生效**，避免“同一媒体两种 URL 来源”的身份分裂（威胁 T-09）。
5. **凭据红线（与 ADR-026/030/041 一致）**：
   - STRM 内容**不得**包含 Token/Cookie（明文长期凭据）。框架无法阻止用户手写，
     但必须**主动拒绝并可诊断**：命中 `BackupUrlGuard.SENSITIVE_QUERY_KEYS` 同款参数名集合
     （`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:19-26`，
     含 `token`/`sign`/`x-amz-signature` 等）时给出明确错误，
     **不做静默剥离**（与备份层同款保守语义，
     `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:9-15`）。
   - 注意语义边界：**签名 URL 的签名参数是资源授权，不是用户凭据**。因此它**不算**违规、
     **不阻止解析**，但**必须**在日志中按 URL 脱敏处理
     （`core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt:74-90`），且
     **绝不允许进入持久化**（决策 11）。
     —— 两者目标不同：前者防“把长期凭据写进共享文件”，后者防“把临时授权写进数据库”。

**理由**

- 云盘的实际使用方式就是“别人分享一堆 `.strm`，每个文件一行直链”。若不支持，云盘接入的价值减半。
- **不让客户端去解析远端 `.strm`** 是最重要的一条：它把“读服务器本地文件”这个能力彻底从设计里删掉，
  直接消灭一整类 SSRF/路径穿越风险（威胁 T-01）。

---

### 决策 4：HTTP 302/307 重定向策略

**现状**

- 播放栈用 `OkHttpDataSource`
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:49-51`），
  重定向由 **OkHttp 原生跟随**，跨协议不再受限（ADR-030 决策段，`DECISIONS.md:230-238`）。
- 逐跳凭据剥离由 `OriginScopedCredentialInterceptor` 承担，origin 基准取 `chain.call().request()`
  （`core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:30-45`）。
- **`followRedirects` 在播放栈未被显式设置**，依赖 OkHttp 默认 `true`
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:41-48`）——
  这是**隐式依赖**，本 ADR 要求改为显式。
- WebDAV **主动禁用**重定向跟随
  （`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavApi.kt:44-45`），
  并有“302 不跟随、按错误上抛”的回归测试
  （`provider/webdav/src/test/kotlin/com/mediahub/provider/webdav/WebDavBrowseProviderTest.kt:192-203`）。

**决策**

1. **API 面与媒体面采用不同策略，且都必须显式声明**：
   - **媒体面（播放）**：`followRedirects(true)` + `followSslRedirects(true)`，**显式**写在
     `PlayerFactory` 中，不再依赖默认值；
   - **API 面（目录/取链/进度）**：**保持保守**。除明确需要跟随的端点外，默认
     `followRedirects(false)`（对齐 WebDAV 的既有选择），把 302 当**结构化错误**上抛，
     由上层决定是否改用新地址重试 —— 因为 API 请求带长期凭据，
     跟随重定向是把凭据交给第三方的最短路径。
2. **302/307 差异必须被尊重**：
   - `307`/`308`：method 与 body 保持不变（OkHttp 原生行为）；
   - `302`/`303`：OkHttp 会把非 GET/HEAD 降级为 GET —— 对**播放**（GET + Range）无害；
     对**会改变状态的 API**（如进度上报 `POST /Sessions/Playing`）**必须拒绝跟随**
     （否则一次重定向会静默把带 body 的 POST 变成无 body 的 GET，产生“假成功”）。
     —— 该风险在 Emby 进度链路已真实存在过（ADR-040 的 400/null-key 勘误，
     `DECISIONS.md:512-610` 段尾勘误），因此列为硬约束。
3. **跳数上限**：媒体面最大重定向跳数 **5**。超限即 `PlaybackError`，不做第 6 跳。
   现有可观测性已具备：`StartupNetworkEventListener` 计算 `redirectCount`
   （`core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt:33-35`），
   并通过 `PlaybackNetworkTraceSink` 回报
   （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt:16-23`）——
   上限校验应复用这条通道而不是新开一条。
4. **落地地址校验**：重定向的**最终 origin** 必须通过私网/loopback 校验；
   `MediaHttpClient.probe` 已回传 `finalUrl`
   （`core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:44-51`），
   该校验应挂在该字段上（当前**未做任何校验**）。
5. **凭据剥离不可配置**：不允许任何用户配置/Provider 参数关闭
   `OriginScopedCredentialInterceptor`。它必须继续是 network interceptor（逐跳生效），
   **不得**降级为 application interceptor（只生效一次）。

**理由**

- 云盘直链几乎必然经过至少一次 302/307 到 CDN；不跟随 = 无法播放。
- 但“跟随”与“带凭据跟随”是两件事。ADR-030 的剥离是这个设计能成立的前提，
  所以它必须是**结构性的（network interceptor）**，不能是**可选的（配置开关）**。

---

### 决策 5：签名 URL 有效期与失效处理

**现状**

- `PlaybackSource.expiresAtEpochMs: Long?`
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:34-35`）与
  `isExpired(nowEpochMs)`
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:42-44`）已存在，
  但**生产代码零调用**。
- `docs/player/README.md:38` 把“URL 过期重解析”列为 **V0.1 计划项**。
- Emby 的 Direct Stream URL **不带签名、不返回过期时间**
  （`provider/emby/src/main/kotlin/com/mediahub/provider/emby/api/EmbyApiClient.kt:215-228`），
  因此 `expiresAtEpochMs` 在 Emby 上恒为 `null`。

**决策**

1. **过期语义显式三态化**（新增字段，见 §3.3），取代“nullable = 未知”的模糊约定：

   | 值 | 含义 | 行为 |
   | --- | --- | --- |
   | `KNOWN(epochMs)` | Provider 知道确切过期时刻（云盘 API 返回 `expires_in`/`expiration`） | **主动预检** + **被动兜底** |
   | `UNKNOWN` | 是临时链接但过期时刻未知 | 仅**被动兜底**（403/410 触发重解析） |
   | `NONE` | 非临时链接（Local `file://`、WebDAV 直链、Emby 直连） | 不重解析 |

2. **主动预检的时机**：**只在起播与 Seek 之前**判断，不在播放中途轮询。
   预检提前量 = `min(剩余有效期 × 10%, 60s)`（即“还剩不到 10% 或不到 60 秒就视为已过期”），
   避免“续播到 95% 时链接还有 2 秒寿命”造成一次无谓失败。
3. **被动兜底是必须的**：`expires_in` 不可信（时钟偏移、服务端提前失效、单链接限次）。
   因此 **`UNKNOWN` 与 `KNOWN` 都必须实现 403/410 重解析路径**（决策 6）。
4. **重解析后必须回到同一个资源身份**：重解析 = 用 `(serverId, itemId)` 重新调用
   `resolvePlayback`，**不是** 用旧 URL 换新 URL。新 `PlaybackSource` 的
   `sessionId`/`headers` 同步刷新（`sessionId` 契约见
   `core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:27-33`：
   仅内存、不作鉴权凭据、不参与 URL 构造）。
5. **过期绝不写入持久层**：`expiresAtEpochMs` 与 URL 同生共死（内存），
   禁止进入 Room/DataStore/备份（ADR-003、ADR-041）。

**后果**

- 需要在播放会话内维护“当前 `PlaybackSource` + 解析代数（generation）”；
  代数概念在 `PlayerViewModel` 已有先例
  （`feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt:551` 定义、
  `feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt:575` 自增、
  `feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt:593` 校验），
  重解析必须复用同一代数语义，防止迟到结果覆盖新会话。

---

### 决策 6：403/410 重新解析条件（含熔断）

**现状**

- `PlaybackErrorMapper.fromHttpStatus` 映射 401/403/404/429/5xx，**无 410**
  （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:46-56`）；
  410 落 `UNKNOWN`。
- `PlaybackError.Code` 中 `URL_EXPIRED` 的文案已经是“播放链接已过期，**正在重新解析**”
  （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:23`）——
  说明“重解析”是既定意图，但**没有触发条件定义**。

**决策**

1. **新增 410 显式建模**：`410 → PlaybackError.Code.URL_EXPIRED`（语义：该链接确定已作废）。
2. **重解析只在“链接级”错误上触发**，且必须区分“可重解析”与“不可重解析”：

   | 触发 | 是否重解析 | 理由 |
   | --- | --- | --- |
   | `410 Gone` | **是**（第 1 次立即） | 链接确定作废 |
   | `403` 且本次链接的过期语义为 `KNOWN`/`UNKNOWN` | **是**（第 1 次立即） | 签名过期最典型表现 |
   | `403` 且 `NONE`（如 WebDAV 长期地址） | **否** | 是权限/账号问题，重解析只会得到同样的 403 |
   | `401` | **否** | 是会话问题，走既有 `AuthExpired` 语义（`provider/emby/src/main/kotlin/com/mediahub/provider/emby/playback/EmbyPlaybackProvider.kt:117-127`） |
   | `404` | **否** | 资源不存在，重解析无意义 |
   | 其它 4xx（`451`/`423` 等） | **否** | 未建模，按 `UNKNOWN` 上抛 |
   | `429` | **否**（改为退避，见决策 10） | 是限流，重试会加重 |
   | `5xx` | **最多 1 次**，且与重解析共享同一预算 | 服务端抖动 |

3. **每次播放的硬预算（熔断核心）**：
   - `MAX_RE_RESOLVE_PER_SESSION = 2`（整段播放最多 2 次重解析，跨多次 Seek 累计）；
   - 单次重解析后仍失败 → **停止重解析**，进入冷却（`≥ 30s` 或到用户手动重试）；
   - 冷却期内同一 `(serverId, itemId)` 的重解析请求**直接拒绝**（本地短路，不出网）。
4. **重解析的判据必须来自资源身份，而非链接**：冷却键是 `(serverId, itemId)`，
   因为“同一个链接反复解析出同一个坏地址”是最常见的重试风暴形态（威胁 T-03）。
5. **禁止对 `410` 之后的新链接“立刻无条件重试同一地址”**：新链接必须来自
   一次真实的 `resolvePlayback`，不允许“沿用旧 URL 再试一次”。

---

### 决策 7：断点续播与 Range 请求边界

**现状**

- 续播位置来源：`PlaybackOptions.startPositionMs`
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:58-59`），
  由 `PlayerViewModel` 从 `progressStore.getResume(serverId, itemId)` 取得并传入
  （`feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt:631-639`）。
- `MediaHttpClient.probe` 已发 `Range: bytes=0-0` 并解析 `206` / `Accept-Ranges: bytes`
  （`core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:28-51`），
  结果字段 `supportsRange` 已返回。
- 网络层已观测 `Accept-Ranges` / `Content-Range`
  （`core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt:40-42`），
  但**没有任何地方校验它们与请求是否一致**。
- `PlaybackSource.supportsSeeking: Boolean = true`
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:36`）默认乐观；
  WebDAV 也明确“声明 true，实际能力由 206 探测决定，不虚构”
  （`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:22-23`）。

**决策**

1. **Range 支持必须在起播时被“证实”而不是被“假定”**：
   - `supportsSeeking = false` 时，**禁止发起 Seek**（UI 层禁用 SeekBar），而不是发一个一定失败的请求；
   - `supportsSeeking = true` 但探测未证实（`probe()` 未跑或未返回 206/Accept-Ranges: bytes）时，
     首次 Seek 必须**允许失败并降级**（把 `supportsSeeking` 置 false，并提示“该源不支持跳转”），
     **不允许**无限重试。
2. **必须显式处理“服务器忽略 Range”**：请求 `Range: bytes=N-` 却收到 **200 且 `Content-Length` 为全量**
   时，播放器会从偏移 0 开始喂数据 —— 若上层仍按“已从 N 开始”记进度，
   会产生**进度倒退**（跳到 90% 却写回 0%）。因此：
   - 收到 200（非 206）响应时，**必须**把“实际起始偏移 = 0”这一事实传播给进度层；
   - 禁止把“请求的偏移”当作“实际播放位置”上报远端（这会污染服务端 userdata，
     与 ADR-040 记录的“退出刚打开的条目被标已看 = 服务端数据损坏”同源，`DECISIONS.md:512-560`）。
3. **`Content-Range` 必须校验**：若返回 206，`Content-Range` 的起始偏移与请求偏移不一致，
   视为**链接层错误**，不静默继续（可能读到另一资源的数据）。
   该校验应挂在已有的 `contentRange` 观测字段上
   （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt:22`）。
4. **断点续播与签名过期的交互**：续播位置在**重解析之后**重新应用
   （顺序：读 resume → resolve → 播放时带 position → 若发生重解析，丢弃旧引擎并将最新进度
   作为新的 resume 重新起播）。禁止“重解析后从 0 开始”而把用户进度抹掉。
5. **Range 边界与缓存**：`Range` 请求的响应分片进入 `SimpleCache`（决策 8），
   因此 **重解析产生的新 URL 会换一条缓存键**，续播的 Range 请求将**无法命中旧分片**。
   该代价被明示接受（不为此引入“缓存键归一化”——那会把不同授权下的内容混在一起，见威胁 T-06）。

---

### 决策 8：缓存作用域、失效与清理

**现状**

- `MediaCacheProvider`：`SimpleCache(cacheDir/playback_cache, LeastRecentlyUsedCacheEvictor(512MB))`
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/MediaCacheProvider.kt:19-28`）。
- `PlayerFactory` 把 `CacheDataSource` 包在 `HeaderAwareDataSource` **内层**
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:54-60`），
  即 **缓存键是请求 URI**，与请求头/凭据无关。
- ADR-009 规定四类缓存（Room / Coil / SimpleCache / DownloadManager）**独立生命周期，禁止混用**
  （`DECISIONS.md:50-53`）；图片缓存独立 256MB（ADR-031，`DECISIONS.md:254`）。
- 备份显式排除“视频缓存”（`docs/backup/README.md:11`、
  `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:11-12`）。

**决策**

1. **作用域 = 原始请求 URL（现状即如此），并把它固化为契约**：
   - 缓存键**不含**凭据/请求头（因此换账号不会命中他人分片 —— 同一设备同一 UID 内，
     缓存内容本身是媒体字节，不是授权令牌）；
   - 缓存键**包含全部 query**（签名/过期/PlaySessionId）→ **同一文件在不同会话会形成多条缓存**。
     这是**已知且接受的代价**：`SimpleCache` 的 LRU 会自然回收，且我们**拒绝**为省空间而
     剥离 query 做键归一化（会把不同授权的分片混用，威胁 T-06）。
2. **失效策略分两层**：
   - **内容失效**：**不主动失效**。播放缓存不做 ETag/Last-Modified 校验
     （当前也无此实现）；媒体分片在签名有效期内不会变。
   - **链接失效**（重解析）：**不清理旧缓存**。旧 URL 的分片保留到 LRU 淘汰。
     理由：清理需要按 URL 反查 `SimpleCache` 索引，成本高于收益，且会引入
     “清理与大文件缓存写读并发”的一致性风险。
3. **清理策略**：
   - 唯一自动清理 = **LRU 淘汰（512MB 上限）**，沿用 ADR-009，不新增按站点/账号的清理器；
   - 手动清理走系统“清除缓存”路径（`cacheDir`），**不新建设置项**；
   - **禁止**把播放缓存目录与下载目录混用（`ROADMAP.md:44` 已把下载管理列为独立设施）。
4. **禁止把临时 URL 写进任何缓存元数据**：不得额外写入“URL 字符串”到 Room/SQLite/DataStore
   以支持缓存管理（威胁 T-10）。
5. **共享设备的如实声明**：缓存目录是应用私有 `cacheDir`，但**同 UID 的其它进程可读**。
   在多账号共用一台设备的场景下，**已缓存的分片可能被另一账号读到**。
   本 ADR **不做**按账号隔离缓存（会成倍放大磁盘占用），但把该风险**显式登记**为已知边界
   （威胁 T-12），并在需要时由后续 ADR 决定是否引入按 `serverId` 分目录。

---

### 决策 9：用户配置的 UA/Cookie 如何限定到可信请求范围

**现状**

- 播放 UA 是**硬编码常量** `"MediaHub"`
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:50`、
  `player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:72-74`）；
  **没有任何用户配置入口**（全仓库检索无 UA 偏好项）。
- `PlaybackSource.cookies` 存在
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:14-15`），
  由 `buildRequestHeaders` 拼成 `Cookie` 头
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEngine.kt:609-615`；
  mpv 同款 `player/mpv/src/main/kotlin/com/mediahub/player/mpv/MpvPlaybackEngine.kt:556-557`）。
- `OriginScopedCredentialInterceptor` 的 `SENSITIVE_HEADERS` **包含 `Cookie` 与 `Authorization`
  和 `Proxy-Authorization`**，但**不含 `User-Agent`**
  （`core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:47-58`）。
- 现有红线（ADR-030 决策段）：`Range / User-Agent 等安全媒体头继续透传`（`DECISIONS.md:237-238`）。

**决策：必须区分“UA 穿透”与“凭据剥离”，二者不可互换**

1. **UA 穿透（允许跨 origin）**：`User-Agent` 是**指纹头，不是凭据**。跨 origin 时**保留**。
   理由：直链 CDN 常按 UA 做拒绝策略（云盘客户端 UA 白名单），
   剥离 UA 会导致**必然播放失败**，而它本身不泄漏身份。
2. **凭据剥离（跨 origin 一律剥离，无例外）**：`Cookie`、`Authorization`、`Proxy-Authorization`、
   `X-Emby-Token` 等**必须**继续被剥离。**用户配置不能改变这一点。**
3. **用户配置的作用域限定（本条的核心）**：
   - **存储**：云盘会话 Cookie / UA 走入 **`CredentialVault`（Keystore 加密，按 `(serverId, kind)`）**
     （`DECISIONS.md:99-105`；
     `core/security/src/main/kotlin/com/mediahub/core/security/CredentialVault.kt:8-13`）。
     禁止 Room / DataStore 明文；禁止日志
     （`core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt:15-33`）；
     禁止进入备份（`docs/backup/README.md:11`）。
   - **注入**：只在**该 `serverId` 的 `PlaybackSource.headers`/`cookies`** 上注入，
     经 `HeaderAwareDataSource`
     （`player/engine/src/main/kotlin/com/mediahub/player/engine/HeaderAwareDataSource.kt:30-34`）
     在 `open()` 时读取。**注入点只有一个**，不新增全局拦截器式的 Cookie 注入。
   - **schema 约束**：用户配置只允许 `User-Agent`、`Referer`、`Cookie` 三个键。
     **禁止**提供 `Authorization`/`Proxy-Authorization` 输入项
     （否则就是给用户一个把长期凭据发给任意第三方主机的开关）。
   - **同源覆盖**：Provider 自身鉴权头**必须获胜**（沿用 Emby 的先例：
     先剔除同名源级头再写入权威鉴权值，
     `provider/emby/src/main/kotlin/com/mediahub/provider/emby/playback/EmbyPlaybackProvider.kt:84-94`）。
   - **`Cookie` 无“跨 origin 豁免”**：即便用户显式配置“此 Cookie 用于第三方”，
     ADR-030 的剥离**依然生效** —— 因为没有这样的开关（这是设计选择，不是遗漏）。
4. **明示的边界**：**UA 穿透是一个真实的隐私信号**。跨 origin 的 UA 会把
   “这个用户在用 MediaHub（Android）” 告知 CDN。这是**接受**的代价（换取可播性），
   但必须在设置项文案中如实说明，不得宣称“UA 不泄露任何信息”。
5. **可选加固（建议，非本 ADR 强制）**：允许用户把 UA 设为**每主机**粒度，
   从而在没有必要时不使用全局 UA。**待验证（V-08）**：云盘 CDN 是否接受非客户端 UA ——
   在无真实样本前，默认行为是**全局 UA 穿透**。

---

### 决策 10：取消传播、并发限制、超时与重试预算（含 403/429 语义熔断）

#### 10.1 取消传播

1. 取消必须**原样穿透**，禁止被折叠为业务异常或成为重试理由：
   - 既有契约：`ProviderException` 的 `CancellationException` 原实例透传
     （`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt:107-111`）；
   - `ApiClient` 已实现“把 OkHttp 包装的取消解包回 `CancellationException`”
     （`core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:176-182`）；
   - `ApiClient` 的 `probe` 在 deadline 命中时经 `Call.cancel` 真实打断
     （`core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:195-209`）。
2. **云盘链路必须复用上述三个既有实现，不得新写一套取消桥**。
3. **重试必须显式排除取消（当前实现存在真实风险，需修复）**：
   `RetryOnceInterceptor` 只捕获 `IOException`
   （`core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:67`），
   而 `Call.cancel()` 恰恰表现为 `IOException("canceled")`。
   因此“用户已取消 → 拦截器再 sleep 300ms 重发一次”是**可能的现有行为**（威胁 T-04，需实测 V-09）。
   本 ADR 要求：**在 `RetryOnceInterceptor` 中显式识别取消并立即放弃重试**
   （例如经 `chain.call().isCanceled()` 判断，或检查 `IOException` 的 caused-by/suppressed 链中
   是否存在 `CancellationException` —— 与 `ApiClient.awaitCancellable` 同款判据，
   `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:178-181`）。
   —— 这是一条**对既有代码的缺陷登记**，本 ADR 只登记、不修改。

#### 10.2 并发限制

1. **按账户 + 按主机的双维度并发上限**（云盘风控的核心缓解措施）：
   - 单账户同域 API 并发 ≤ **4**（与既有聚合先例一致：
     `feature/search/src/main/kotlin/com/mediahub/feature/search/engine/GlobalSearchEngine.kt:85-88` 用 4）；
   - 单账户取链（resolve）并发 ≤ **2**；
   - 全应用云盘 API 总在途 ≤ **16**（与 WebDAV 桥硬上限对齐，
     `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCallBridge.kt:27-62`）。
2. **实现方式**：`kotlinx.coroutines.sync.Semaphore` + FIFO 公平队列，
   沿用 `WebDavCallBridge` 的准入语义（**先取名额再提交任务；排队中被取消则不提交、不出网**，
   `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCallBridge.kt:37-43`、
   `provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCallBridge.kt:64-67`）。
3. **禁止 CallerRuns 式执行**：`WebDavCallBridge` 明确定义了“池满后由提交线程就地执行会突破上限”
   的历史缺陷（`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCallBridge.kt:30-35`）。
   云盘侧**必须**采用有界队列 + 拒绝策略，不得复用 `CallerRunsPolicy`。

#### 10.3 超时预算

| 类别 | 超时 | 依据 |
| --- | --- | --- |
| API（列目录/详情/取链） | connect 10s / read 30s / call 60s | `core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:24-29` |
| API 单次探测（连接测试） | 真实 deadline 10s，超时即 `Call.cancel` | `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:210-242` |
| 媒体流 | read 0 / call 0（不掐断长视频） | `core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:31-35`；ADR-012 |
| 进度上报（含 final） | **2s 短超时**，不阻塞退出 | `DECISIONS.md:512-610` 段（final flush 语义）+ `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:146-156` |
| 单次重解析总预算 | **≤ 8s**（超时即放弃本次重解析，上抛结构化错误） | 本 ADR 新增 |

#### 10.4 重试预算与 403/429 语义熔断

1. **重试分层，禁止叠加**：

   | 层 | 现状 | 云盘要求 |
   | --- | --- | --- |
   | `ApiClient`（`apiClient()`） | GET/HEAD/DELETE 在 `IOException` 时重试 1 次，固定 300ms | **保留**，但必须先修 10.1.3 的取消误判 |
   | 媒体面（`mediaClient()`） | **不自动重试** | 必须保持“不自动重试”不变 |
   | 播放重解析 | 无 | 见决策 6：**每次播放 ≤ 2 次**，带冷却 |
   | 429 退避 | 无 | 新增（见下） |

2. **429 语义熔断**：
   - 解析 `Retry-After`（秒数或 HTTP-date）→ 填入已有的
     `ProviderException.RateLimited(serverId, retryAfterMs)`
     （`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt:82-84`，
     该字段**当前恒为 `null`**，本 ADR 要求真正填充）。
   - 退避 = `max(Retry-After, 指数退避)`，指数底 2、初始 1s、封顶 60s、带 **±20% 抖动**
     （抖动用于避免多任务同刻重试）。
   - 熔断状态按 `(serverId, host)` 维护；熔断期内**拒绝新请求并本地短路**（不出网）。
   - 连续 `429` ≥ 3 次 → 进入**长冷却（≥ 10 分钟）**并向用户可见地提示
     （而不是静默后台重试）。
3. **403 语义熔断**：403 **不进入 429 那套退避**（403 不是“稍后就好”）。
   403 的处置是决策 6 的分类表（可重解析 vs 不可），并且**同样受“每次播放 ≤ 2 次”预算约束**。
4. **禁止重试风暴的硬性判据（可测试）**：任意一次播放会话内，
   对同一 `(serverId, itemId)` 的**网络请求总数**必须存在上界；
   该上界必须能在单测里被断言（见 §5 测试矩阵 T13）。

---

### 决策 11：禁止把未验证直链写入持久化数据

**现状**

- `MediaItem.path` 的注释已写明“资源标识，播放时再解析，见 ADR-003”
  （`core/model/src/main/kotlin/com/mediahub/model/MediaItem.kt:43-44`）；
  `PlaybackSource.url` 注释“临时签名 URL / 会话 URL，绝不落库”
  （`core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:6-11`）。
- 备份层已实现两道守卫：DTO 白名单排除临时播放 URL / posterUrl
  （`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:11-12`、
  `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:59`）
  与 URL 值级守卫（user-info + 敏感 query 命中即拒，
  `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:16-54`）。
- 房间数据库无 URL 列（`core/database/src/main/kotlin/com/mediahub/core/database/entity/`）；
  `PlaybackProgressDto` 只存 `itemId`/`positionMs`/`durationMs`
  （`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:49-60`）。

**决策**

1. **“未验证直链”** = 任何未经过一次**真实播放或一次真实 Range 探测成功**的媒体地址。
2. **禁止写入**：Room、DataStore、备份载荷、日志、诊断导出、崩溃报告。
3. **允许写入的内容（白名单）**：
   - 云盘 `file_id`（`MediaItem.id`）、相对路径（`MediaItem.path`）、`parentId`、`sizeBytes`；
   - 播放进度（`itemId` + `positionMs` + `durationMs`）。
4. **“直链模板”同样禁止落库**：不允许把 `https://cdn.example/{fileId}?sign=...` 这类
   带签名槽位的模板写入持久层（同属“未验证直链”的变体）。
5. **即使直链“看起来永久有效”也不允许落库**：例如 `file://` 与 WebDAV 直链虽非临时，
   但它们的**身份已经由 `path`/`id` 表达**，重复存储 URL 只会制造两套真相（威胁 T-09、T-10）。
6. **第三道守卫（本 ADR 新增）**：**持久化写入点的类型级约束**沿用并扩展 `BackupUrlGuard` 的思路：
   - `DAO`/`Entity`/`Preferences` 的 DTO 中**不得出现**名字含 `url`/`uri`/`link`/`stream` 的
     非白名单字段（白名单仅指那些**已在 `MediaItem` 上但其持久化被显式排除的字段**）；
   - 该约束以**反射式单测**表达：扫描 `core:database` 的 Entity 与备份 DTO 的字段名与序列化名，
     命中禁词即失败（可执行、可回归）。
7. **STRM 文件本身不是持久化数据**：STRM 内容属于用户提供的输入，我们**只读不写**，
   且不把其内容回写到任何应用数据库。

---

## 3. 接口建议（尽量复用 provider/api 现有抽象）

### 3.1 复用（不改动）

| 现有抽象 | 位置 | 云盘用途 |
| --- | --- | --- |
| `MediaProvider` | `provider/api/src/main/kotlin/com/mediahub/provider/api/MediaProvider.kt:12-27` | 云盘 Provider 基类 |
| `MediaBrowseProvider.listFolder` | `provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:49-52` | 云盘文件树（`BROWSE`） |
| `MediaPlaybackProvider.resolvePlayback` | `provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:54-57` | 取签名 URL（`PLAYBACK`） |
| `MediaDetailProvider` / `MediaSearchProvider` / `MediaSubtitleProvider` | `provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:44-47`、`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:59-62`、`provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt:96-99` | 详情/搜索/字幕 |
| `ProviderHandle` + `ProviderCapability` | `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderHandle.kt:15-51`、`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderCapability.kt:11-56` | 能力组合与运行时声明 |
| `ProviderException`（含 `RateLimited`） | `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderException.kt:23-100` | 错误分类 |
| `PlaybackSource` / `PlaybackOptions` | `core/model/src/main/kotlin/com/mediahub/model/PlaybackSource.kt:9-61` | 播放源/选项 |
| `CredentialVault` | `core/security/src/main/kotlin/com/mediahub/core/security/CredentialVault.kt:8-13` | 云盘 OAuth/Cookie |
| `BackupUrlGuard` | `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:16-54` | 直链泄漏守卫 |

### 3.2 新增能力接口（`provider/api`）

```kotlin
/**
 * STRM 引用解析能力（新增，可选能力）。
 * 只有声明本能力的 Provider 才允许把 .strm 当媒体条目（决策 3.3）。
 * 硬约束：本接口的实现禁止发起网络请求 —— 它只做“读文本 + 语法校验”。
 */
interface MediaStrmResolveProvider {
    /** 解析 .strm 内容；返回 null 表示“不是可用的 STRM 引用”（调用方按普通文件处理）。 */
    fun resolveStrm(content: String): StrmReference?
}

/** 解析结果（纯数据，永不持久化）。 */
data class StrmReference(
    val url: String,
    /** 语法校验通过的 scheme：仅 http/https 可能取值。 */
    val scheme: String,
    /** 是否检测到“疑似嵌入了长期凭据”的参数（命中 BackupUrlGuard 同款键名集合）。 */
    val embeddedCredentialSuspected: Boolean,
)
```

- 新增 `ProviderCapability.STRM`（加入
  `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderCapability.kt`，
  并在 `ProviderHandle.runtimeCapabilities` 派生逻辑中登记，
  `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderHandle.kt:33-45`）。
- **不新增** `MediaItemUrlProvider` 之类“直接吐 URL”的接口 —— URL 只能经
  `resolvePlayback` 在播放时产生（ADR-003）。

### 3.3 `PlaybackSource` 扩展建议

```kotlin
/** 链接过期语义（新增，取代“nullable = 未知”的模糊约定，决策 5.1）。 */
sealed interface LinkExpiry {
    data class Known(val expiresAtEpochMs: Long) : LinkExpiry
    data object Unknown : LinkExpiry
    data object None : LinkExpiry
}

// PlaybackSource 新增字段（保留既有 expiresAtEpochMs 以兼容，标注 @Deprecated 迁移路径）：
val linkExpiry: LinkExpiry = LinkExpiry.None
/** 媒体能否响应 Range：由 probe 证实（false 时禁止 Seek）。 */
val rangeSupportVerified: Boolean = false
```

- 迁移期不破坏既有调用方：`linkExpiry` 缺省 `None`，与
  Emby（`provider/emby/src/main/kotlin/com/mediahub/provider/emby/api/EmbyApiClient.kt:215-228`，无过期）、
  WebDAV（`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:55-64`，无过期）、
  Local（`provider/local/src/main/kotlin/com/mediahub/provider/local/LocalProvider.kt:110-120`，`file://`）
  三条既有链路一致。

### 3.4 `PlaybackError.Code` 扩展建议

| 新增/变更 | 语义 | 触发点 |
| --- | --- | --- |
| `410 → URL_EXPIRED` | 链接确定作废 | `PlaybackErrorMapper.fromHttpStatus`（`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:46-56`）新增分支 |
| `REDIRECT_LIMIT`（新增） | 重定向跳数超限（> 5） | 复用 `redirectCount` 观测（`core/network/src/main/kotlin/com/mediahub/core/network/StartupNetworkEventListener.kt:33-35`） |
| `RANGE_MISMATCH`（新增） | `Content-Range` 起始偏移与请求不符 | 复用 `contentRange` 观测（`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackNetworkTraceSink.kt:22`） |
| `BLOCKED_TARGET`（新增） | 最终地址落在 loopback/私网 | 挂在 `MediaHttpClient.probe` 的 `finalUrl` 上（`core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:44-51`） |
| `RE_RESOLVE_BUDGET_EXHAUSTED`（新增） | 本次会话重解析预算用尽 | 决策 6.3 |

### 3.5 新增内部组件（建议命名，均不新增 Gradle 模块）

| 组件 | 位置建议 | 职责 | 纯函数？ |
| --- | --- | --- | --- |
| `FileNameMetadataParser` | `core:model` | 文件名/目录名 → 标题/年份/季集 | **是** |
| `StrmContentParser` | `provider:api` 或 `core:common` | STRM 文本 → `StrmReference` | **是** |
| `CloudHttpBudget` | `core:network` | 并发名额 + 429/403 熔断状态 + 退避计算 | 部分（时间可注入） |
| `PlaybackReResolver` | `feature:player` | 重解析预算 + 冷却 + 代数管理 | 否（需 `resolvePlayback`） |
| `CredentialHeaderPolicy` | `core:network` | 判定哪些头可穿透（指纹头）vs 必须剥离（凭据头） | **是** |

- `CredentialHeaderPolicy` 必须**复用**
  `OriginScopedCredentialInterceptor.SENSITIVE_HEADERS` 的取值，不得复制一份列表
  （避免两处漂移，见威胁 T-08）。当前该列表是 `private companion`
  （`core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:47-58`），
  需提升为可测试的公开策略对象。

---

## 4. 威胁模型

> 记号：**T-xx**；「类型」= 外部攻击 / 误用 / 内部错误。

### T-01 重定向或 STRM 指向内网/环回地址（SSRF）
- **类型**：外部攻击 / 误用
- **场景**：云盘分享内容或用户手写 `.strm` 指向 `http://127.0.0.1:8096`、
  `http://192.168.1.1/`、`http://169.254.169.254/`（云元数据端点）；或公网 URL 302 到内网。
- **影响**：内网探测、路由器管理面被访问、云环境元数据泄漏。
- **现有缓解**：**无**。`MediaHttpClient.probe` 会回传 `finalUrl`
  （`core/network/src/main/kotlin/com/mediahub/core/network/MediaHttpClient.kt:44-51`）
  但**没有任何校验**；播放栈未限制重定向目标。
- **本 ADR 要求**：起播前与每个重定向落点做私网/loopback/link-local 拒绝（新增 `BLOCKED_TARGET`）。
- **残余风险**：DNS 重绑定（先解析成公网、再解析成内网）—— **待验证**是否需要绑定 IP 校验。

### T-02 用户配置的 Cookie 被注入到第三方 CDN
- **类型**：误用
- **场景**：用户为“让云盘能播”把浏览器 Cookie 粘进配置，直链 302 到 CDN 时 Cookie 被带走。
- **影响**：云盘会话 Cookie 泄漏给 CDN 运营方 → 账号被他人接管。
- **现有缓解**：`OriginScopedCredentialInterceptor` 逐跳剥离 `Cookie`
  （`core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:49-57`），
  且两条回归已存在：
  `core/network/src/test/kotlin/com/mediahub/core/network/MpvHttpBridgeTest.kt:42-68`、
  `player/engine/src/test/kotlin/com/mediahub/player/engine/RedirectCredentialIsolationTest.kt:89-111`。
- **本 ADR 要求**：**不提供**任何关闭/豁免剥离的开关（决策 9.3）；`Cookie` 输入项只能写入 Vault。
- **残余风险**：UA 仍跨 origin 穿透（决策 9.4），但 UA 不含凭据。

### T-03 403/410 触发重试风暴
- **类型**：内部错误
- **场景**：签名失效 → 403 → 重解析 → 新链接同样 403（服务端风控生效）→ 无限循环；
  用户在播放页反复 Seek 放大频率。
- **影响**：账号被限速/封禁；设备耗电与流量；服务端 5xx。
- **现有缓解**：**部分** —— 媒体面不自动重试
  （`core/network/src/main/kotlin/com/mediahub/core/network/HttpClientFactory.kt:31-35`）；
  但**没有重解析预算**（`PlaybackError.Code.URL_EXPIRED` 已存在却无触发条件，
  `core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:23`）。
- **本 ADR 要求**：每次播放 ≤ 2 次重解析 + 冷却键 `(serverId, itemId)`（决策 6.3-6.4）。

### T-04 `RetryOnceInterceptor` 把“用户取消”当成可重试失败
- **类型**：内部错误
- **场景**：用户退出播放 → `Call.cancel()` → 表现为 `IOException` →
  拦截器 sleep 300ms 后**重发**。
- **影响**：退出后仍在出网（隐私与计费）、取消语义被违反、调试困难。
- **现有缓解**：`ApiClient` 侧已把取消解包
  （`core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt:176-182`）——
  **但拦截器在更早的位置**，解包救不了已发出的重试。
- **本 ADR 要求**：拦截器显式识别取消并放弃重试（决策 10.1.3）。
  **这是对既有代码的缺陷登记**（真实性待实测，见 V-09）。

### T-05 刮削阶段批量读文件头 → 云盘风控/计费
- **类型**：内部错误（设计错误）
- **场景**：海报墙初次构建时对每一条目发 `Range` 读头。
- **影响**：账号被判定为异常客户端；流量计费；大量失效链接请求。
- **现有缓解**：`metadata` 模块无实现
  （`metadata/src/main/kotlin/com/mediahub/metadata/MetadataProvider.kt` 全文件 50 行），
  **当前不可能发生**；Emby/WebDAV 链路也只按扩展名判类型
  （`provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavPlaybackProvider.kt:67-80`）。
- **本 ADR 要求**：决策 2 的三道测试防线（计数式回归 / 依赖面断言 / 调用点白名单）。

### T-06 缓存键归一化导致跨授权分片混用
- **类型**：误用 / 内部错误
- **场景**：为省磁盘把签名 query 从缓存键剥离 → A 账号缓存的分片被 B 账号命中。
- **影响**：越权读取（在共享设备上具现实意义）；内容与授权不一致。
- **现有缓解**：缓存键 = 原始 URI
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:54-60` 的
  `CacheDataSource` 位置决定），**天然不归一化**。
- **本 ADR 要求**：决策 8.1 —— 明确**拒绝**为省空间做键归一化。

### T-07 日志/诊断导出泄漏签名 URL
- **类型**：内部错误
- **场景**：直链写入日志或诊断包，用户分享给他人后链接仍有效。
- **影响**：媒体内容被他人下载（授权期内的重放）。
- **现有缓解**：`Redactor` 覆盖 `token`/`sign` 等键名
  （`core/logging/src/main/kotlin/com/mediahub/core/logging/Redactor.kt:44-72`）；
  `ApiException` 构造即脱敏
  （`core/network/src/main/kotlin/com/mediahub/core/network/ApiException.kt:10-22`）；
  `PlaybackError.toLogString` 走 `Redactor`
  （`core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt:38-40`）。
- **残余风险**：**值为路径型签名**（签名在 path 段而非 query）时键名级脱敏**失效**。
  这是一个真实盲点，本 ADR 标记为 **待验证（V-02）**。

### T-08 凭据头清单两处漂移
- **类型**：内部错误
- **场景**：新代码复制一份“敏感头列表”并漏掉某一项（如漏 `Proxy-Authorization`），
  或新增头（如 `X-Quark-Token`）只加在一处。
- **影响**：静默的凭据泄漏。
- **现有缓解**：`SENSITIVE_HEADERS` 单一定义
  （`core/network/src/main/kotlin/com/mediahub/core/network/OriginScopedCredentialInterceptor.kt:47-58`），
  且是 `private companion`（外部无法复制，但也无法被测试直接引用）。
- **本 ADR 要求**：提升为公开策略对象并加“清单完整性”单测（§3.5 的 `CredentialHeaderPolicy`）。

### T-09 同一媒体出现两套 URL 来源 / 两套身份
- **类型**：内部错误
- **场景**：Emby 服务端解析自己的 `.strm`，客户端又解析同一 `.strm`，产生两个不同 URL 与两个进度记录；
  或“直链模板”落库与实时解析共存。
- **影响**：进度分裂、续播错乱、去重失败（ADR-037 Canonical Identity 语义被破坏，
  `DECISIONS.md:350-374`）。
- **现有缓解**：ADR-003 要求“只存标识”（`DECISIONS.md:16-20`）；
  但**没有**“两条解析路径互斥”的规则。
- **本 ADR 要求**：决策 3.4 的分工规则（Emby 条目走服务端，云盘/本地条目走客户端，互斥）。

### T-10 未验证直链被写入持久层
- **类型**：误用 / 内部错误
- **场景**：为“加快二次起播”把直链缓存进 Room；或把带签名的图片/海报 URL 写进备份
  （图片 URL 带签名在云盘很常见）。
- **影响**：ADR-003/ADR-041 被绕过；链接泄漏；恢复后使用失效链接。
- **现有缓解**：备份 DTO 白名单排除 `posterUrl` 与临时播放 URL
  （`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:11-12`、
  `core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupDtos.kt:59`）；
  值级守卫（`core/common/src/main/kotlin/com/mediahub/core/common/backup/BackupUrlGuard.kt:16-54`）；
  备份上限 10 MiB（`docs/backup/README.md:8`）。
- **本 ADR 要求**：决策 11.6 的字段名反射式单测（第三道守卫）。

### T-11 用户手写 `.strm` 内嵌长期凭据
- **类型**：误用
- **场景**：用户把带 `?token=...` 的地址写进 `.strm` 并分享给他人。
- **影响**：长期凭据随文件扩散；且该文件常被同步/分享。
- **现有缓解**：无（该功能尚不存在）。
- **本 ADR 要求**：决策 3.5 —— 命中敏感参数名即**明确拒绝并可诊断**，不静默剥离；
  错误信息不得回显完整 URL（对齐 `docs/backup/README.md:10` 的“错误不回显 URL”）。

### T-12 缓存目录在共享设备上被同 UID 其它进程读取
- **类型**：误用（设备环境）
- **场景**：多账号共用设备，已缓存分片被另一账号的应用实例读到。
- **影响**：内容越权（非凭据越权）。
- **现有缓解**：无（`player/engine/src/main/kotlin/com/mediahub/player/engine/MediaCacheProvider.kt:19-22`
  的 `cacheDir/playback_cache` 为应用私有，但同 UID 可达）。
- **本 ADR 处置**：**显式登记为已知边界**，不做账号隔离（决策 8.5），代价与理由已写明。

### T-13 并发取链触发风控（并发维度）
- **类型**：内部错误
- **场景**：聚合视图（`GlobalSearchEngine` 风格，
  `feature/search/src/main/kotlin/com/mediahub/feature/search/engine/GlobalSearchEngine.kt:85-88`）
  对云盘源并发发起大量取链。
- **影响**：429/账号限速；与 T-03 叠加成风暴。
- **现有缓解**：无按账户并发限制。
- **本 ADR 要求**：决策 10.2 的双维度名额 + 有界队列。

### T-14 第三方 CDN 拒绝非客户端 UA → 必然播放失败
- **类型**：外部依赖（非安全）
- **场景**：CDN 按 UA 白名单拒绝 `MediaHub`
  （`player/engine/src/main/kotlin/com/mediahub/player/engine/PlayerFactory.kt:73`）。
- **影响**：功能不可用（而非安全问题）。
- **现有缓解**：UA 常量可改（编译期）。
- **本 ADR 要求**：UA 可配置且跨 origin 穿透（决策 9.1），并在设置页如实说明其隐私代价。

---

## 5. 测试矩阵

> **证据级别取值**：`EXECUTED-JVM`（JVM/Robolectric 单测，CI 可跑）、
> `EXECUTED-DEVICE`（仪器/真机）、`NOT_RUN`（未执行）。
> **可自动化**：是 / 否 / 部分。

| # | 场景 | 前置 | 期望 | 证据级别 | 可自动化 |
| --- | --- | --- | --- | --- | --- |
| T1 | 目录/文件名优先解析（含 `S01E02`、`(2021)`、分辨率后缀） | 纯函数输入样本集 | 标题/年份/季集号正确；不触发任何 IO | EXECUTED-JVM | 是 |
| T2 | 刮削阶段零媒体请求 | MockWebServer 假云盘 + 完整列表/详情流程 | 全部请求只命中列表/详情端点；**无任何 `Range` 头**、无播放路径 GET/HEAD | EXECUTED-JVM | 是 |
| T3 | 刮削链路无媒体能力（依赖面） | 模块依赖图 / 架构断言 | `metadata` 实现不依赖 `MediaHttpClient`、`player:engine` | EXECUTED-JVM | 是 |
| T4 | `MediaHttpClient.probe` 调用点白名单 | 全仓库调用点集合断言 | 调用方集合 == 已知起播路径集合；新增即失败 | EXECUTED-JVM | 是 |
| T5 | STRM 解析：正常单行 http/https | `StrmContentParser` 纯函数 | 返回 `StrmReference`，URL 已 trim、去 BOM | EXECUTED-JVM | 是 |
| T6 | STRM 解析：拒绝项（`file://`/`smb://`、user-info、>8KiB、嵌入 `token=`） | 上述纯函数 | 返回 null 或 `embeddedCredentialSuspected=true`；错误不回显完整 URL | EXECUTED-JVM | 是 |
| T7 | STRM 指向私网/loopback | 解析 + 目标校验 | 被拒（`BLOCKED_TARGET`），零出网 | EXECUTED-JVM | 是 |
| T8 | 跨 origin 重定向剥离凭据（ADR-030 回归） | 双 MockWebServer：A 307 → B | B 收不到 Token/Authorization/Cookie；`Range`/`UA` 仍在 | EXECUTED-JVM | 是（基线见 `player/engine/src/test/kotlin/com/mediahub/player/engine/RedirectCredentialIsolationTest.kt:89-111`） |
| T9 | 多跳（307+302）逐跳剥离 | 三 MockWebServer | 每一跳都剥离；同 origin 跳保留 | EXECUTED-JVM | 是 |
| T10 | 重定向跳数超限 | MockWebServer 6 跳循环 | 第 5 跳后停止，报 `REDIRECT_LIMIT` | EXECUTED-JVM | 是 |
| T11 | 签名 URL 主动预检（`KNOWN` + 提前量） | 注入固定时钟 | 剩余 < `min(10%,60s)` 时**不发起**播放请求，先重解析 | EXECUTED-JVM | 是 |
| T12 | 410/403 重解析分类表 | 假 Provider 按状态码返回 | 仅 `410` 与（`403` 且非 `None`）触发重解析；`404`/`401` 不触发 | EXECUTED-JVM | 是 |
| T13 | 重解析预算与冷却（熔断） | 连续失败的假 Provider | 每次播放重解析 ≤ 2 次；冷却期内**零出网**；请求总数有上界并可断言 | EXECUTED-JVM | 是 |
| T14 | 429 退避与 `Retry-After` 填值 | MockWebServer 返回 `429 + Retry-After: 30` | `ProviderException.RateLimited.retryAfterMs == 30000`；退避带抖动；连续 3 次进长冷却 | EXECUTED-JVM | 是 |
| T15 | 取消不被重试（T-04 缺陷回归） | 拦截器睡眠中点取消 | 取消后**无第二次请求**；`CancellationException` 原样上抛 | EXECUTED-JVM | 是 |
| T16 | Range 语义：服务器忽略 Range 返回 200 | MockWebServer 200 + 全量 `Content-Length` | 实际起始偏移被传播为 0；**进度不上报为请求偏移** | EXECUTED-JVM | 是 |
| T17 | Range 语义：206 且 `Content-Range` 偏移不符 | MockWebServer 206 + 错误起始 | 报 `RANGE_MISMATCH`，不静默继续 | EXECUTED-JVM | 是 |
| T18 | 重解析后续播位置保留 | 假 Provider：第一次成功、第二次重解析 | 新会话从**最新进度**起播，不清零 | EXECUTED-JVM | 是 |
| T19 | UA 穿透 / Cookie 剥离（决策 9 的对照） | 跨 origin 重定向 | `User-Agent` 出现在 B；`Cookie` 不出现 | EXECUTED-JVM | 是（基线见 `player/engine/src/test/kotlin/com/mediahub/player/engine/RedirectCredentialIsolationTest.kt:109`） |
| T20 | 用户配置头 schema 白名单 | 配置校验函数 | 拒绝 `Authorization`/`Proxy-Authorization`；只允许 UA/Referer/Cookie | EXECUTED-JVM | 是 |
| T21 | 凭据头清单完整性（T-08） | `CredentialHeaderPolicy` 公开清单 | 清单 == 期望集合（含 `Proxy-Authorization`）；播放栈引用同一对象 | EXECUTED-JVM | 是 |
| T22 | 持久化字段名守卫（T-10） | 反射扫描 Room Entity + 备份 DTO | 无名字含 `url`/`uri`/`link`/`stream` 的非白名单字段 | EXECUTED-JVM | 是 |
| T23 | 缓存键作用域（不归一化） | 两个不同签名 URL 请求同一 `file_id` | 产生两条缓存条目；**不做** query 剥离归一化 | EXECUTED-JVM | 部分（需真实 `SimpleCache` 实例） |
| T24 | 缓存清理 = LRU 512MB | 写入超过上限的分片 | 旧分片按 LRU 淘汰；无自定义清理器介入 | EXECUTED-JVM | 部分 |
| T25 | 凭据剥离不可配置 | 配置面扫描 | 无任何配置项/参数能否关闭 `OriginScopedCredentialInterceptor` | EXECUTED-JVM | 是 |
| T26 | 并发名额（账户 4 / 取链 2 / 总 16） | 计数式假客户端 | 峰值并发不超限；排队中取消 → **不出网** | EXECUTED-JVM | 是 |
| T27 | 真实云盘取链与直链重定向形态 | 真实账号 + 固定测试账号 | 记录 302/307 实际跳数、签名参数位置（query/path）、`expires_in` 单位 | EXECUTED-DEVICE | 否（需真实账号与真机） |
| T28 | Emby 服务端 `.strm` 条目返回的 `Path`/`MediaSources` 形态 | 真实 Emby + 一个 `.strm` 条目 | 确认服务端是否已解析、`MediaSources[].Path` 是什么 | EXECUTED-DEVICE | 否 |
| T29 | 私网重定向被拒（真机网络栈） | 真机 + 可控 302 服务 | 报 `BLOCKED_TARGET`，零内网请求 | EXECUTED-DEVICE | 部分 |
| T30 | 云盘风控行为（429/403 真实触发条件） | 真实账号（谨慎） | 记录真实冷却窗口与恢复时间 | EXECUTED-DEVICE | 否（且有账号风险，**建议不做**） |

**矩阵说明（须与结论一同引用）**

- 本矩阵中的 `EXECUTED-JVM` 行为**待实现项**，本轮**均未执行**：
  当前真实状态是 **`NOT_RUN`**（本轮不改生产代码，故无测试可跑）。
  表中“证据级别”标注的是**该用例落地后可达到的证据强度**，不是本轮结果。
- `EXECUTED-DEVICE` 项（T27–T30）需要真实云盘账号/真机，无此类资源时必须写 `NOT_RUN`，
  不得以 JVM 单测替代（对齐 `docs/player/README.md:110-113` 的证据边界声明）。
- 本轮唯一**实际执行**的验证是：本文件的**引用行号与文件内容一致性核对**
  （163 处 `路径:行号` 引用，120+ 处关键标识符命中核对，逐条回读源码）——
  证据级别 `EXECUTED-JVM（本地脚本）`，但它验证的是**文档准确性**，不是产品行为。
- **并发编辑告诫**：核对期间 `D:\deepseek_test\MediaHub` 工作树被**其它进程**并发修改
  （`git status` 显示 `DECISIONS.md`、`PlayerViewModel.kt`、`PlaybackEngineSelector.kt` 等文件在
  09:50–09:53 被写入，非本任务所为）。本任务**只读**该仓库、**未执行任何 git 写操作**，
  故本 ADR 的结论可能滞后于并发改动；重新核验时应以最新工作树为准。

---

## 6. 明确的非目标（本轮不做）

1. **不实现云盘播放链路**（用户明确要求）。本 ADR 不含任何生产代码改动。
2. 不实现 SMB / SFTP / NFS。
3. 不实现下载管理（`ROADMAP.md:44` 已列为独立设施）。
4. 不实现 MPV/FFmpeg 扩展解码（`DECISIONS.md:5-9` 的非目标不变）。
5. 不引入第三方云盘 SDK；不做绕过风控/会员/DRM 的实现
   （`docs/providers/README.md:47-48`）。
6. 不做按账号的缓存隔离（决策 8.5 已登记为已知边界）。

---

## 7. 待验证清单（不确定项，一律不臆测）

| # | 待验证项 | 为什么需要 | 建议的验证方式 |
| --- | --- | --- | --- |
| V-01 | Emby/Jellyfin 服务端如何处理并暴露 `.strm` 条目（是否已解析、`MediaSources[].Path` 与 `Container` 取值） | 决策 3.4 的分工规则依赖“服务端已解析”这一前提；仓库内**零 `.strm` 代码**可依 | 真实 Emby 4.x 放一个 `.strm` → 读 `PlaybackInfo` 响应（T28） |
| V-02 | 各家云盘直链的**签名参数位置**（query vs path 段） | 决定 `Redactor` 键名级脱敏是否足够（威胁 T-07） | 真实取链后离线检查 URL 形态（T27） |
| V-03 | 各家云盘 `expires_in` 的单位与可信度（秒/毫秒；是否服务器时钟） | 决策 5.1 的 `KNOWN` 预检提前量 | T27 |
| V-04 | 302 响应下 OkHttp 对已带 `Range` 的 GET 的实际行为（是否保留 Range、是否重置偏移） | 决策 4.2 与决策 7.2 的交互 | JVM 单测可先做；真机再确认 |
| V-05 | `MediaHttpClient.okHttpClient()` 与播放栈 `PlayerFactory` 自建 `OkHttpClient` **是否共用连接池** | 影响并发名额是否需要在两个客户端上同时生效 | 代码审查 + 单测断言（**当前看是两套客户端**，待确认） |
| V-06 | OkHttp 在 `Cookie` 头跨**同 host 不同 port / 同 host 不同 scheme** 时的处理 | 决策 9 的 origin 定义须与 ADR-030 一致（scheme+host+port） | 单测（三 MockWebServer 变体） |
| V-07 | `SimpleCache` 缓存键是否包含全部 query 与 fragment | 决策 8.1 的“同文件多条缓存”结论 | 读 Media3 实现 + 单测（T23） |
| V-08 | 云盘 CDN 是否接受非客户端 UA（`MediaHub`） | 决策 9.5 的默认行为选择 | T27 |
| V-09 | `RetryOnceInterceptor` 在 `Call.cancel()` 下是否**确实**会重发（T-04 是否真实存在） | 该条已登记为“可能的现有缺陷”，**未实测** | 单测：睡眠期取消并断言请求计数（T15） |
| V-10 | 云盘 API 的真实并发限流阈值与 429 形态（是否带 `Retry-After`） | 决策 10.2/10.4 的具体数值 | T27/T30（**账号风险，需用户批准**） |
| V-11 | 各云盘 `file_id` 的稳定性（重命名/移动后是否变化） | 决策 1.4 的持久化键选择 | T27 |
| V-12 | Emby `.strm` 场景下 `MediaSourceSelector` 的 `isIsoPath` 判定是否受影响（`provider/emby/src/main/kotlin/com/mediahub/provider/emby/playback/MediaSourceSelector.kt:17-19`） | 服务端可能把 `.strm` 的 `Path` 标成意外扩展名 | T28 |
| V-13 | 既有 `RedirectCredentialIsolationTest` 是否覆盖 `Proxy-Authorization` 与 `X-MediaBrowser-*` | 清单完整性（T-08）的实际覆盖度 | 读测试文件（`player/engine/src/test/kotlin/com/mediahub/player/engine/RedirectCredentialIsolationTest.kt:89-111` 已确认覆盖 Token/Authorization/Cookie/UA/Range） |
| V-14 | 备份上限（1000 源 / 20000 进度 / 10 MiB）在引入云盘源后的容量影响 | 一个云盘账号可能对应大量条目 | `docs/backup/README.md:8-9` + 容量估算 |
| V-15 | `provider/{aliyun,baidu,quark,china-mobile,tianyi,smb}` 的 README 是否在本次调研之外被更新 | 本 ADR 基于 2026-10-14 工作树；README 为占位 | 重读各 README 首行 |

---

## 8. 自查表（对应任务书的 11 项要求）

| # | 要求 | 落点 | 状态 |
| --- | --- | --- | --- |
| 1 | 目录/文件名优先的元数据获取策略 | 决策 1 | 已覆盖 |
| 2 | 禁止在刮削阶段读取媒体文件头（含为何 + 测试保证） | 决策 2（理由 5 条 + 测试 3 道防线） | 已覆盖 |
| 3 | STRM 地址解析与资源身份（与 Emby `.strm` 交互） | 决策 3 | 已覆盖（V-01 待验证） |
| 4 | HTTP 302/307 重定向策略 | 决策 4 | 已覆盖 |
| 5 | 签名 URL 有效期与失效处理 | 决策 5（三态） | 已覆盖 |
| 6 | 403/410 重新解析条件 | 决策 6（分类表 + 熔断） | 已覆盖 |
| 7 | 断点续播与 Range 请求边界 | 决策 7 | 已覆盖 |
| 8 | 缓存作用域、失效与清理 | 决策 8 | 已覆盖 |
| 9 | 用户配置 UA/Cookie 的可信范围（不绕 ADR-030；UA 穿透 vs 凭据剥离） | 决策 9 | 已覆盖 |
| 10 | 取消传播、并发、超时与重试预算（403/429 熔断） | 决策 10 | 已覆盖 |
| 11 | 禁止把未验证直链写入持久化数据 | 决策 11 | 已覆盖 |
| — | 接口建议（复用/新增/数据结构） | §3 | 已覆盖 |
| — | 威胁模型 ≥ 8 条 | §4（14 条：T-01…T-14） | 已覆盖 |
| — | 测试矩阵（场景/前置/期望/证据级别/可自动化） | §5（30 行：T1…T30） | 已覆盖 |
| — | 所有论断基于真实代码、引用 `路径:行号` | 全文；§5 末段说明本轮已回读核对 | 已覆盖 |
| — | 不改动 `D:\deepseek_test\MediaHub` 任何文件 | 本轮仅新建本文件 | 已遵守 |
