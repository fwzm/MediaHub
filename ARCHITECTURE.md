# 架构说明（ARCHITECTURE）

> 本文描述 Phase 0.5 的当前真实实现；如文档与代码冲突，以代码为准并立即修正文档。

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
| 认证 | `MediaAuthProvider` | Emby、Jellyfin、WebDAV |
| 结构化媒体库 | `MediaLibraryProvider` | Emby、Jellyfin |
| 文件树 | `MediaBrowseProvider` | Local、WebDAV、云盘 |
| 播放解析 | `MediaPlaybackProvider` | 可播放的数据源 |
| 搜索/字幕/进度 | 对应独立接口 | 按协议选择 |

Factory 返回 `ProviderHandle`。Descriptor 表示计划能力，Handle 字段推导 `runtimeCapabilities`，并校验
“运行时能力 ⊆ 计划能力”。业务层只以 Handle 为准，未完成 API 不会因为列在路线图中就被伪装成可用。

## 3. Descriptor 与注册表

- Factory 通过 Hilt `@IntoSet` 自注册并公开 `ProviderDescriptor`。
- Registry 按稳定、开放的 `providerId:String` 建索引，重复 ID 启动即失败。
- `MediaServer.providerId` 已替代封闭 `ServerType`。Room 暂时沿用历史列名 `type`，Mapper 兼容旧枚举字符串。
- 添加页直接消费 `registry.descriptors`；新增一个真实 Provider 只需模块、Factory 和 Hilt 绑定，无需修改页面。
- 尚无模块的路线项由 `PlannedProviderCatalog` 作为不可选占位；注册同 ID 的真实 Factory 会自动覆盖占位。

## 4. 凭据生命周期

```text
用户输入短期 Credentials
        ↓
AuthenticationCoordinator → MediaAuthProvider.authenticate
        ↓ 成功                         ↓ Phase 1 尚未实现
SessionCredential                 加密暂存 pending credential
        ↓                                  ↓
CredentialVault → Keystore SecretStorage ← 后续登录成功后清除
```

- Room 只存非敏感服务器与账号元数据。
- Emby/Jellyfin 的目标生命周期是“密码换 Access Token 后销毁密码”；Phase 0.5 未实现真实登录，因此加密暂存，
  避免添加页面销毁后凭据直接丢失。V0.1 登录成功必须原子地写 Session 并删除 pending。
- WebDAV 的长期访问依赖 Basic 密码，验证后以 `SessionCredential.BasicAuth` 加密保存。
- Credential 模型已为 OAuth2、Refresh Token、Cookie Session、Device Code、API Key、二维码登录留出扩展点。
- 所有 Credential/Session 类型覆盖 `toString()`，日志链路继续由 `Redactor` 兜底。

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
| 关键事件 | Play/Pause/Seek/Stop/End | 不 conflate；立即本地保存与限时远端上报 |
| 页面销毁兜底 | release | final flush |

高频位置 tick 不创建子 coroutine，也不直接触发数据库或网络。退出按钮先完成 STOP flush；生命周期异常销毁再以 IO scope
做最后兜底。

## 7. 本地媒体（SAF）

添加本地媒体时启动 `ACTION_OPEN_DOCUMENT_TREE`，保存 `takePersistableUriPermission()` 的文档树 URI。
`LocalProvider` 使用 `DocumentFile`/`ContentResolver` 浏览与校验授权，Media3 播放 `content://`。这覆盖普通本地目录、
U 盘与系统可见的 DocumentProvider，不围绕 `java.io.File` 或 `file://` 扩展。

## 8. 网络、错误与缓存

- `ApiClient` 负责普通 API；`MediaHttpClient` 负责 Range/302/Cookie/长连接媒体流。
- 协议连接测试由具体 Provider 完成：Emby/Jellyfin 校验公开 System Info，WebDAV 校验 `OPTIONS` 与 `DAV` Header，
  Local 校验持久 URI 权限。401/403/404 不再因 `<500` 被视为成功。
- 错误按 `ApiException` → `ProviderException` → `PlaybackError` 分层。
- 缓存继续分离：Room（元数据/进度）、Coil（图片，待接入）、Media3 SimpleCache（播放）、DownloadManager（离线，后续）。

## 9. 质量门禁与扩展边界

GitHub Actions 对 PR 与主分支运行 `assembleDebug`、`testDebugUnitTest`、`lintDebug`。单测覆盖 Registry/能力组合、
凭据生命周期、请求头隔离、进度节流、旧数据库映射与 Provider 协议探测。

本阶段保留现有 Gradle 模块，不为减少模块数做高风险重构。后续只有存在独立依赖边界、编译收益或所有权时才新增模块。
