# 内部 API 契约

## Provider

- `MediaProvider`：公共身份、Descriptor、协议连接测试。
- `ProviderHandle`：可选的 auth/library/browse/playback/search/subtitle/progress 能力。
- `MediaProviderFactory.descriptor`：Factory 自描述；`create(server)` 返回 Handle。
- `MediaProviderRegistry`：按开放 `providerId:String` 查找 Factory/Descriptor，拒绝重复 ID。

调用方先读取 Handle，不判断 Provider 的具体 class 或 providerId。Descriptor 是计划能力；Handle 的
`runtimeCapabilities` 是当前权威，且必须是计划能力的子集。

## 分页

- 请求：`PageRequest(offset, limit)`，默认 50。
- 响应：`PagedResult<T>(items, totalCount?, hasMore, nextOffset?)`。
- 列表页不得一次拉取全量。

## 凭据

- `Credentials`：一次认证输入；所有 `toString()` 均脱敏。
- `SessionCredential`：登录后可长期使用的 Token/Basic/OAuth/Cookie/API Key。
- `AuthSession`：Credential + User + remoteServerId 的单条原子加密记录。
- `AuthenticationCoordinator`：认证、恢复、登出、保存/清理 Session、失败回滚；唯一 source of truth 入口。
- `CredentialVault`：敏感信息唯一持久化接口；实现必须落入 Keystore 加密 `SecretStorage`。

`MediaAuthProvider.restoreSession` 返回 Restored/Invalidated/Unavailable；只有 Invalidated 清 Vault。
Emby/Jellyfin 的 UsernamePassword 不持久化；WebDAV Basic 因协议需要可加密保留。
禁止把 Secret 写入 Room、DataStore 或日志。

## 连接测试

UI 只调用 `handle.provider.testConnection(request)`。具体 Provider 校验协议身份与状态；HTTP `<500` 不是成功条件。

## 进度

- UI：播放器内存 StateFlow。
- 本地：`ProgressRepository` 快照，默认 5s。
- 远端：`MediaProgressProvider.reportingPolicy` 独立控制周期。
- 关键事件：`PlaybackProgressReason` 的 PLAY/PAUSE/SEEK/STOP/END，进入无丢弃队列并立即 flush。

## 错误

| 层 | 类型 | 用途 |
|---|---|---|
| 网络 | `ApiException` | HTTP/requestId/url（日志脱敏） |
| Provider | `ProviderException` | 认证、连接、HTTP、限流、未实现 |
| 播放 | `PlaybackError` | 重试或降级依据 |

UI 展示用户文案，诊断详情只进入脱敏日志。
