# 内部 API 契约文档

> 模块间接口约定（非公开网络 API）。

## 分页

- 请求：`PageRequest(offset, limit)`（默认 limit=50）。
- 响应：`PagedResult<T>(items, totalCount?, hasMore, nextOffset?)`。
- 规则：列表页不得一次拉全量；滚动触发下一页。

## 错误契约

| 层 | 类型 | 用途 |
|----|------|------|
| 网络 | `ApiException` | HTTP 状态 + requestId + url（脱敏） |
| Provider | `ProviderException`（含 ErrorCode） | 认证/网络/HTTP/解析/限流/未实现 |
| 播放 | `PlaybackError.Code` | 结构化播放错误（重试/降级依据） |

UI 规则：只展示 `message`（用户可读）；诊断信息进日志（已脱敏），不展示堆栈。

## 认证与凭据

- `Credentials`（用户名密码/WebDAV/Token/APIKey）仅内存，绝不落库。
- 会话 Token：`TokenStore`（Keystore 加密），按 serverId 存取，支持过期时间。
- 日志：所有 Logger 实现强制 Redactor 脱敏（Authorization/Cookie/Token/密码）。

## 进度同步

- 本地快照：`ProgressRepository`（Room playback_progress，含标题/海报快照供"继续观看"）。
- 服务端上报：Provider.reportProgress（尽力而为，失败仅记日志）。
- 续播：`PlaybackOptions.startPositionMs` / `PlaybackSession.resumePositionMs`。

## Provider 注册

- `MediaProviderFactory.serverType` + Hilt `@IntoSet` 自注册；
- `DefaultProviderRegistry` 按 ServerType 建索引；新增源 = 新 Factory + 绑定，无需改注册表。
