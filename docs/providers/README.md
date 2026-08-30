# 数据源（Provider）设计文档

## 统一接口

所有数据源实现 `com.mediahub.provider.api.MediaProvider`（组合认证/媒体库/浏览/播放/搜索/字幕/进度
能力），通过 `MediaProviderFactory` 创建并经 Hilt `@IntoSet` 注册进 `MediaProviderRegistry`。
UI 通过 `registry.create(server)` 获取实例，按 `ProviderCapability` 决定展示。

数据流：`Remote Model → Mapper → Domain Model(core:model)`。
禁止：UI 处理各源原始 JSON；Provider 特有 if/else 泄漏到 UI。

## Emby（Phase 1 实现）

待确认 endpoint（**必须查官方文档，禁止凭记忆虚构**）：

- 认证：`POST /Users/AuthenticateByName`（Body: Username/Pw；Header: X-Emby-Token 需客户端标识）
- 媒体库：`GET /Users/{userId}/Views`
- 浏览/详情：`GET /Users/{userId}/Items?...`、`GET /Users/{userId}/Items/{itemId}`
- 搜索（Phase 1C 已实现）：`GET /Users/{userId}/Items?SearchTerm=...&Recursive=true`
  （+IncludeItemTypes 锁类型；非 /Search/Hints）
- 播放源：`POST /Items/{itemId}/PlaybackInfo`（MediaSources → DirectPlay/DirectStream/Transcode 三态）
- 进度（Phase 1H 已实现，ADR-040）：`POST /Sessions/Playing`（首报）/
  `POST /Sessions/Playing/Progress`（后续/关键事件）/ `POST /Sessions/Playing/Stopped`
  （退出权威进度写入者）；PositionTicks 恒发（缺省会令服务端误判"播放完成"）
- 图片：`/Items/{itemId}/Images/{type}`（需带鉴权头，Coil 集成时注意）

要点：默认直连（Direct Play），客户端能解码绝不请求转码；会话 Token 加密存储（core:security）。

## Jellyfin（Phase 1 实现）

API 与 Emby 同源但**独立 Connector**：共享 `BaseMediaServerProvider`，协议差异各自实现
（ADR-039：不 import provider:emby，JSON shape 相似也不建立依赖）。
认证已实现（Phase 1G-A）：标准 `Authorization: MediaBrowser Client/Device/DeviceId/Version[,Token]` 单头
（不使用 X-Emby-*/X-MediaBrowser-* legacy 头）；登录 `POST /Users/AuthenticateByName`；
探活 `GET /System/Info/Public`（无 Token，防串服身份校验）；登出 `POST /Sessions/Logout`。

## WebDAV（Phase 1 实现）

- 列目录：`PROPFIND`（Depth: 1），解析 multistatus XML。
- 认证：Basic（用户名/密码走 Keystore 加密存储）。
- 播放：直链 = baseUrl + 路径（支持 Range），无需临时 URL 概念。

## 本地存储（已实现 ✅）

- 目录 → MediaItem(FOLDER)；视频/音频按扩展名归类。
- 播放：`file://` URI，DIRECT_PLAY。
- 根目录：`LocalRootProvider`（当前为应用外部目录）；完整"本机存储"需 SAF 文档树（后续）。

## SMB / 云盘（规划）

见 `provider/{smb,aliyun,baidu,quark,china-mobile,tianyi}/README.md`。
核心原则：官方 API/OAuth 优先；临时签名 URL 播放时动态解析（ADR-003）；
独立 Connector + Experimental 标记；凭据加密存储；不做绕过风控/会员/DRM 的实现。
