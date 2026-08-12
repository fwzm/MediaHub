# 架构说明（ARCHITECTURE）

> 本文描述 Phase 0.5 + Phase 1A reconciliation 的当前真实实现；如文档与代码冲突，以代码为准。

## 1. 依赖方向

```text
feature/* → provider:api + core:model + repositories
                    ↓
        ProviderHandle（可选能力组合）
                    ↓
provider:* → provider:base → core:network / core:security / core:logging
                    ↓
       Remote API 或 Android SAF

feature:player → player:engine → Media3
```

- UI 不解析 Provider 原始响应，不按 Emby/Jellyfin/Local 做类型分支。
- `core:model` 是统一领域语言；`provider:api` 是跨层能力契约。
- `core:security` 只提供通用 Keystore `SecretStorage`；Provider 层实现 `CredentialVault`，不存在 core 反向依赖。

## 2. Provider 能力组合

`MediaProvider` 只包含所有数据源都具备的最小能力：`serverId`、`descriptor` 与 `testConnection()`。
其余接口互相独立：

| 能力 | 接口 | 典型实现 |
|---|---|---|
| 认证 | `MediaAuthProvider` | Emby、WebDAV；Jellyfin 尚未开放 |
| 结构化媒体库 | `MediaLibraryProvider` | Emby、Jellyfin |
| 文件树 | `MediaBrowseProvider` | Local、WebDAV、云盘 |
| 播放解析 | `MediaPlaybackProvider` | 可播放的数据源 |
| 搜索/字幕/进度 | 对应独立接口 | 按协议选择 |

Factory 返回 `ProviderHandle`。Descriptor 表示计划能力，Handle 字段推导 `runtimeCapabilities`，并校验
“运行时能力 ⊆ 计划能力”。业务层只以 Handle 为准，未完成 API 不会因为列在路线图中就被伪装成可用。
具体 Provider 类也不得实现未完成能力；当前 Emby/Jellyfin 都是最小 Provider，WebDAV 认证独立在
`WebDavAuthProvider`，避免通过具体类型绕过 Handle。

## 3. Descriptor 与注册表

- Factory 通过 Hilt `@IntoSet` 自注册并公开 `ProviderDescriptor`。
- Registry 按稳定、开放的 `providerId:String` 建索引，重复 ID 启动即失败。
- `MediaServer.providerId` 已替代封闭 `ServerType`。Room 暂时沿用历史列名 `type`，Mapper 兼容旧枚举字符串。
- 添加页直接消费 `registry.descriptors`；新增一个真实 Provider 只需模块、Factory 和 Hilt 绑定，无需修改页面。
- 尚无模块的路线项由 `PlannedProviderCatalog` 作为不可选占位；注册同 ID 的真实 Factory 会自动覆盖占位。

## 4. 凭据生命周期

```text
UsernamePassword（仅内存）
        ↓
MediaAuthProvider.authenticate
        ↓
AuthSession(Token + User + remoteServerId)
        ↓ 原子加密记录
AuthenticationCoordinator → CredentialVault → Keystore SecretStorage
```

- Room 只存非敏感服务器与账号元数据。
- `CredentialVault` 是唯一会话 source of truth；不存在 `TokenStore + EmbySessionStore` 第二套状态。
- Emby 已实现“密码换 Access Token”；密码只存在于登录请求内存，不进入 Vault。尚未实现的用户名密码登录也不暂存原始密码。
- Token、远端服务器 ID 与用户身份作为一个 `AuthSession` 加密记录，避免双 Store 部分写入。
- WebDAV 的长期访问依赖 Basic 密码，验证后以 `SessionCredential.BasicAuth` 加密保存。
- Credential 模型已为 OAuth2、Refresh Token、Cookie Session、Device Code、API Key、二维码登录留出扩展点。
- 所有 Credential/Session 类型覆盖 `toString()`，日志链路继续由 `Redactor` 兜底。

恢复由通用 `AuthenticationCoordinator.restore(handle)` 驱动并已接入首页启动路径。Emby 恢复顺序固定为：

1. 不带 Token 请求公开 System Info；
2. `remoteServerId` 匹配后才发送 `X-Emby-Token`；
3. 仅 401 或明确服务器/用户身份变化使会话失效；403、解析错误、超时、DNS、5xx 保留本地会话；
4. 登出先 best-effort 撤销服务端 Token，最终始终由 Coordinator 清理 Vault。

## 5. 播放请求上下文

`PlaybackSource` 仍是 Phase 0.5 的单主资源模型，但每次 `PlaybackEngine.play()` 都会构造新的
`PlaybackRequestContext` 与 `MediaSource`。Header、Cookie、Referer、Authorization 被不可变复制到该 MediaSource，
`HeaderAwareDataSource` 不读取任何全局 mutable holder。

因此两个播放器、预加载或跨来源切换不会相互覆盖请求头。未来扩展到主视频/外挂字幕/DRM/备用线路时，每个资源应有
自己的 request context 与过期时间。

## 6. 进度管线

| 路径 | 频率/触发 | 行为 |
|---|---|---|
| UI State | 500ms | 仅内存更新位置与时长 |
| 本地续播快照 | 默认 5s | conflate + `ProgressRepository.save` |
| 远端周期上报 | Provider 策略，默认 10s | 独立节流；单次上报最多等待 2s |
| 关键事件 | Play/Pause/Seek/Stop/End | 无丢弃队列；立即本地保存与限时远端上报 |
| 页面销毁兜底 | release | final flush |

高频位置 tick 不创建子 coroutine，也不直接触发数据库或网络。退出按钮先完成 STOP flush；生命周期异常销毁再以 IO scope
做最后兜底。

## 7. 本地媒体（SAF）

添加本地媒体时启动 `ACTION_OPEN_DOCUMENT_TREE`，保存 `takePersistableUriPermission()` 的文档树 URI。
`LocalProvider` 使用 `DocumentsContract`/`ContentResolver` 列举任意层级子目录，`DocumentFile` 校验根授权，Media3 播放 `content://`。这覆盖普通本地目录、
U 盘与系统可见的 DocumentProvider，不围绕 `java.io.File` 或 `file://` 扩展。
旧版空 `LOCAL.baseUrl` 行在首页显示“需要重新授权”，并更新原服务器记录，不创建重复媒体源。

## 8. 网络、错误与缓存

- `ApiClient` 负责普通 API；`MediaHttpClient` 负责 Range/302/Cookie/长连接媒体流。
- 协议连接测试由具体 Provider 完成：Emby/Jellyfin 校验公开 System Info，WebDAV 校验 `OPTIONS` 与 `DAV` Header，
  Local 校验持久 URI 权限。401/403/404 不再因 `<500` 被视为成功。
- Emby 的 API root、DTO、`Authorization: Emby ...` 与 `X-Emby-Token` 统一封装在 `provider:emby/api`；
  Phase 1A 只开放 AUTH，Library/Playback/Search/Subtitle/Progress 均未开放。
- 错误按 `ApiException` → `ProviderException` → `PlaybackError` 分层。
- 缓存继续分离：Room（元数据/进度）、Coil（图片，待接入）、Media3 SimpleCache（播放）、DownloadManager（离线，后续）。

## 9. 质量门禁与扩展边界

GitHub Actions 对 PR 与主分支运行 `assembleDebug`、`testDebugUnitTest`、`lintDebug`。单测覆盖 Registry/能力组合、
凭据生命周期、请求头隔离、进度节流、旧数据库映射与 Provider 协议探测。

本阶段保留现有 Gradle 模块，不为减少模块数做高风险重构。后续只有存在独立依赖边界、编译收益或所有权时才新增模块。
