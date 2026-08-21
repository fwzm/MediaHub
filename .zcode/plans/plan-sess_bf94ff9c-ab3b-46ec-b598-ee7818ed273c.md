# Phase 1B-2.3：Artwork Pipeline（海报与背景图）

目标：Emby 图片全链路（URL 契约 → 鉴权加载 → UI 展示），Token 永不进 URL、跨 origin 不泄漏（延续 ADR-026/030 红线）。真机验证收尾。

## 1. Emby 图片 URL 契约（provider/emby）

- `EmbyApiClient` 新增 `imageUrl(itemId, type, tag?, maxWidth, quality=85)`：走现有 `endpointResolver.endpoint("/Items")` + `buildUrlWithSegments` 模式，产出 `{base}/emby/Items/{id}/Images/{Primary|Thumb|Backdrop}?tag=..&maxWidth=..&quality=..`。**不含任何凭据**（tag 是内容哈希，仅用于缓存键）。
- DTO 补字段：`EmbyUserItemDto`（detail）加 `ImageTags: Map<String,String>?`、`BackdropImageTags: List<String>?`、`PrimaryImageAspectRatio: Double?`；库 DTO 如缺 `ImageTags` 一并补。
- Provider 层 enrich（map 后补字段，mapper 保持纯函数）：
  - MOVIE/SERIES/SEASON → `posterUrl`=Primary(maxWidth=400)、`backdropUrl`=Backdrop[0](maxWidth=1280)
  - EPISODE → `posterUrl`=Thumb ?? Primary(maxWidth=400)（16:9 缩略图语义）、`backdropUrl`=null
  - FOLDER/AUDIO → null（保持图标）；无 ImageTags → null
  - `MediaLibrary.imageUrl` 同步解析（EmbyLibraryMapper 的"留 Phase 1B 后续"占位）

## 2. 鉴权图片加载基建

- **`OriginScopedCredentialInterceptor` 从 player/engine 迁到 core/network**（player/engine 已依赖 core/network，改 import；4 个回归测试留在 player/engine 继续覆盖全链路）。
- app 模块新增 `EmbyImageAuthStore` + `EmbyImageAuthInterceptor`（构造注入纯函数 `servers()/token()/userId()`，可单测）：
  - application interceptor：请求 origin(scheme+host+port) 命中已知 Emby 服务器 → 注入 `X-Emby-Token` + `X-Emby-Authorization`（复用 provider:emby 的 `EmbyAuthorizationHeaderBuilder` + `ClientIdentity`）；未命中原样放行
  - network 层挂迁移后的 `OriginScopedCredentialInterceptor`（图片重定向同样不泄漏凭据）
- `MediaHubApp` 实现 `ImageLoaderFactory`（Hilt @EntryPoint 注入）：`callFactory(imageOkHttp)` + `crossfade(true)` + `respectCacheHeaders(false)` + 磁盘缓存 `cacheDir/image_cache` 256MB LRU（对齐 MediaCacheProvider 先例）；不挂请求日志避免刷屏。

## 3. UI（新建 core:ui 模块 + 三处接入）

- **新建 `core:ui`**（settings.gradle 注册；feature/home、feature/library、feature/detail 依赖 + coil-compose）：`PosterImage`(2:3)、`ThumbImage`(16:9)、`BackdropImage`，SubcomposeAsyncImage + Compose 绘制 placeholder/error（灰底 + 类型图标），统一 8dp 圆角、ContentScale.Crop。
- **feature/library**：媒体条目改 3 列海报墙 `LazyVerticalGrid`（EPISODE 16:9，其余 2:3，标题叠加底部）；FOLDER 保留行；"⬆ 返回上级"置顶保留。
- **feature/home**：继续观看卡片改 16:9 缩略图 + 底部 `LinearProgressIndicator` + 标题；旧记录 posterUrl 为 null 显示占位（重播后自愈）。
- **进度图片落盘**：`PlaybackSession` + `posterUrl`（PlayerViewModel 从 item.posterUrl 传入）→ `PlaybackEngine` 两处 `PlaybackProgress` 构造带出 → Room 列已存在，零迁移。
- **feature/detail 接线极简详情页**：补 hilt/provider:api/lifecycle 依赖 + `DetailViewModel`（getServer→registry.create→handle.detail.getItemDetail）；UI = backdrop 16:9 顶部 + 海报/标题/年份/评分/简介（折叠）+ 播放按钮 → 现有 player 路由；nav 新增 `detail/{serverId}/{itemId}?title=`，home/library 的 onOpenItem 改进详情页。

## 4. 测试与门禁

- provider/emby：`imageUrl` 契约测试（无 Token、path/query 正确、tag 编码）+ enrich 策略测试（Episode Thumb fallback、无图 null、Movie poster+backdrop）。
- app：`EmbyImageAuthInterceptor` 纯 JVM 测试（命中 origin 注入头、未命中放行、函数式依赖可 fake）。
- 迁移后全量 `testDebugUnitTest` + `lintDebug` + `assembleDebug` 全绿 → commit + push main → exact-head CI。

## 5. 真机验证（CI 绿后）

装机走查：库海报墙出图、继续观看缩略图+进度条、详情页 backdrop/海报、播放起播不受影响；logcat 确认图片请求 200 且 URL 无 Token；无图条目/断网显示占位不崩溃；退出/恢复无回归。

## 边界

- 不做 Jellyfin 图片、不做人员头像/演员表、不做 Kodi/NFO 特殊路径；详情页不做季/集列表（仍走库浏览）。
- TASKS.md 复选框与 1B-2.2 条目随本次实质提交一起同步（不单独 docs commit）。