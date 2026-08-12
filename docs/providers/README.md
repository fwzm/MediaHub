# Provider 设计

## 契约与装配

所有数据源实现最小 `MediaProvider`，再按真实能力实现独立接口。Factory 暴露 `ProviderDescriptor` 并返回
`ProviderHandle`，通过 Hilt `@IntoSet` 注册。Registry 按稳定 providerId 查找，添加页直接读取 Descriptor。

| Provider | Descriptor 计划能力 | Phase 0.5 运行时能力 |
|---|---|---|
| Emby | Auth + Library + Playback + Search + Subtitle + Progress | Auth（Phase 1A） |
| Jellyfin | Auth + Library + Playback + Search + Subtitle + Progress | 仅协议探测 |
| WebDAV | Auth + Browse + Playback | Auth（Basic 验证/加密会话） |
| Local | Browse + Playback | Browse + Playback |

Remote DTO 与协议细节只能存在于具体 Provider；输出统一 Domain Model。禁止 UI 按来源写 if/else。

## 连接探测

- Emby：读取公开 System Info，要求 Id/Version，检测到 Jellyfin ProductName 时拒绝。
- Jellyfin：读取公开 System Info，要求 Jellyfin ProductName、Id、Version。
- WebDAV：OPTIONS 要求 2xx 与非空 DAV Header；401/403 映射为 AUTH_REQUIRED，不误报成非 WebDAV。
- Local：要求 `content://` 文档树、持久读权限和可读目录。

## 凭据

- Emby：UsernamePassword → Access Token + User + remoteServerId 原子 AuthSession；密码不保存。
- Jellyfin：登录尚未实现，UsernamePassword 不允许 pending 持久化。
- WebDAV：用受保护 PROPFIND 验证 Basic；默认标准字符集，服务器 challenge 声明 UTF-8 时重试；成功后加密保存。
- 云盘：优先官方 OAuth/Device Code/Refresh Token；Cookie Session 仅在协议允许时使用。

## Local（已实现）

添加页用系统 `ACTION_OPEN_DOCUMENT_TREE` 获取目录，调用 `takePersistableUriPermission`。Provider 用
DocumentsContract/ContentResolver 列举子目录并返回 `content://` DIRECT_PLAY；旧空 LOCAL 记录原地重新授权。

## V0.1 顺序

1. 等待 Phase 1A reconciliation CI/Review；
2. Phase 1B：Libraries/Items/详情；
3. PlaybackInfo 与兼容性决策；
4. Progress reporter；
5. 再将同一契约独立实现到 Jellyfin 与 WebDAV。

所有 endpoint、Header、DTO 必须依据官方文档，并配 MockWebServer 契约测试。临时播放 URL 只在播放时解析，永不落库。
