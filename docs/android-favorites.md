# Android 收藏（Favorite）能力：现状、契约与测试清单

> task-22 / A1 交付物。范围：**数据层 + 提供者层 + 单测**。Compose UI、Room 表、备份/同步**不在本轮范围**（见 §6）。
> 事实基准：Emby 4.10.2 与 Jellyfin 12.2 真机验证（Windows U3）＋ Android 侧既有代码勘察。

## 1. 现状（改动前）

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 收藏写入 | **完全没有**。`provider/api` 无 Favorite 能力接口；Emby/Jellyfin ApiClient 无任何 FavoriteItems 写端点 | `provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt`（改前 131 行，10 个能力接口） |
| 声明能力 | `ProviderCapability.FAVORITE` 枚举项存在，但 Emby/Jellyfin 的 `declaredCapabilities` 均未声明；运行时也无实例 | `provider/api/.../ProviderCapability.kt:48-49`、`provider/emby/.../EmbyProvider.kt:28-40`、`provider/jellyfin/.../JellyfinProvider.kt:29-39` |
| 只读筛选 | Emby 支持 `IsFavorite` 查询参数（仅筛选，不写）；Jellyfin 连筛选都没有 | `provider/emby/.../api/EmbyApiClient.kt:92`（`put("IsFavorite", …)`，在 `getUserItems` 内） |
| 状态来源 | 仅服务端 `UserData.IsFavorite`（DTO 默认 `false`，缺失字段不可区分） | `EmbyLibraryDtos.kt:97-104`、`JellyfinLibraryDtos.kt:20-27` |
| 本地表 | 无。Room v4 只有 servers / server_endpoints / accounts / playback_progress / subtitle_memory | `core/database/schemas/com.mediahub.core.database.AppDatabase/4.json` |
| 死模型 | `core/model/.../People.kt:21-26` 的 `data class Favorite(serverId, itemId, addedAtEpochMs)` 全仓 0 引用（不接线） | grep 结果 |

结论：Android 侧"收藏"此前只有**领域模型的一个布尔字段 + Emby 的只读筛选**，没有任何写通路，也没有本地缓存。本轮把写通路补齐到与 Windows 同一 wire 契约。

## 2. 端点契约（Emby 与 Jellyfin 同名同义）

| 动作 | 方法 + 路径 | 成功响应 | 说明 |
| --- | --- | --- | --- |
| 收藏 | `POST /Users/{userId}/FavoriteItems/{itemId}` | 200，空体 | Emby 实际 URL 带 `/emby` 前缀（见 §3） |
| 取消收藏 | `DELETE /Users/{userId}/FavoriteItems/{itemId}` | 200，空体 | DELETE 不得带请求体（OkHttp 限制） |
| 回读 | `GET /Users/{userId}/Items/{itemId}?Fields=UserData` | 200，JSON 含 `UserData.IsFavorite` | **写后必回读**，返回值取回读结果 |

**禁止**：`POST|DELETE /Users/{userId}/Favorites/{itemId}` —— Emby 4.10.2 与 Jellyfin 12.2 **都是 404**（Windows U3 真机）。测试 mock 也不为该路径准备响应，mock 不得比真服宽松。

鉴权：
- Emby：`X-Emby-Token: <token>`（沿用 `EmbyApiClient.authenticatedHeaders`）。
- Jellyfin：仅 `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token="…"`，**不得**出现 `X-Emby-Token`（ADR-039）。

## 3. 实现落点

| 文件 | 内容 |
| --- | --- |
| `provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt`（文件尾） | `interface MediaFavoriteProvider`：`suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean`、`suspend fun isFavorite(itemId: String): Boolean` |
| `provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderHandle.kt` | 新字段 `favorite: MediaFavoriteProvider? = null`（尾插，全部构造点用命名参数，无破坏）；`runtimeCapabilities` 增加 `if (favorite != null) add(ProviderCapability.FAVORITE)`；`hasAnyCapability` 补 `\|\| favorite != null` |
| `core/network/src/main/kotlin/com/mediahub/core/network/ApiClient.kt` | 新增 `deleteNoContent(url, headers)` → `executeNoContent("DELETE", …, body = null)`（`buildBody` 对非 POST 的 null body 返回 null，满足 OkHttp DELETE 无 body） |
| `provider/emby/.../api/EmbyFavoriteDtos.kt`（新建） | `EmbyFavoriteStateDto` / `EmbyFavoriteUserDataDto`（`IsFavorite: Boolean? = null`，null 表示"读不到"） |
| `provider/emby/.../api/EmbyApiClient.kt` | `setFavorite(token, userId, itemId, favorite)`（:188）、`getFavoriteState(...): Boolean?`（:210）、`FAVORITE_FIELDS = "UserData"`（:442） |
| `provider/emby/.../favorite/EmbyFavoriteProvider.kt`（新建） | 实现 `MediaFavoriteProvider`；`requireSession` → 写 → 回读；缺字段抛 `ProviderException.Parse` |
| `provider/jellyfin/.../api/JellyfinFavoriteDtos.kt`（新建） | 同构 DTO |
| `provider/jellyfin/.../api/JellyfinApiClient.kt` | `setFavorite`（:237）、`getFavoriteState`（:256）、`FAVORITE_FIELDS`（:316） |
| `provider/jellyfin/.../favorite/JellyfinFavoriteProvider.kt`（新建） | 同构实现；KDoc 记录：收藏回读走 `/Users/{userId}/Items/{id}` 是**既有选择、不是 ADR-039 现代路由**（现代读法为 `/Items/{id}?UserId=`）；写/读同族以保证同一用户作用域，例外不外溢到 library/detail/search |
| `EmbyProvider.kt` / `JellyfinProvider.kt` | `declaredCapabilities` 增加 `ProviderCapability.FAVORITE` |
| `EmbyProviderFactory.kt` / `JellyfinProviderFactory.kt` | 装配 `favorite = …FavoriteProvider(...)` |

语义纪律：
1. **写成功 ≠ 收藏成功**：方法返回的是**回读**的 `UserData.IsFavorite`。服务器忽略写入时返回 `false`（Emby/Jellyfin 单测各钉一例）。
2. **回读缺字段不是 false**：DTO 用 `Boolean?`，null → `ProviderException.Parse`，绝不降级成"未收藏"。
3. **写失败不继续回读**：401/404 直接抛出，避免把"读到的旧状态"误报为新结果。
4. **取消红线**：`CancellationException` 原样上抛，不折叠为 `ProviderException`。
5. **ADR-022**：`runtimeCapabilities ⊆ declaredCapabilities`，有 `favorite` 实例才进运行时集合；工厂装了实例、descriptor 也声明了 FAVORITE，故包含关系成立（两处工厂测试断言）。

## 4. 错误映射（沿用既有 taxonomy，未新增类型）

| 服务器响应 | 抛出 | 断言位置 |
| --- | --- | --- |
| 401 | `ProviderException.AuthExpired`（code `AUTH_EXPIRED`） | Emby/Jellyfin 单测 `write 401 …` |
| 404 | `ProviderException.NotFound(serverId, "媒体库或条目")` | `write 404 maps to NotFound` |
| 其余非 2xx（如 500） | `ProviderException.Http(statusCode, url, method, requestId)` | `readback 500 maps to Http …` |
| 回读 JSON 缺 `UserData` / `IsFavorite` | `ProviderException.Parse`（code `PARSE`） | `readback without … is a parse error` |
| 无 Token / 无会话 | `ProviderException.AuthRequired`（0 次 HTTP） | `setFavorite without session …` |
| IOException | `ProviderException.Network`（`mapError` 既有分支，未单测） | — |

## 5. 测试清单（共 23 例，全部 MockWebServer / 纯 JVM）

`provider/emby/src/test/kotlin/com/mediahub/provider/emby/favorite/EmbyFavoriteProviderTest.kt`（10）
1. POST 正确路径/头/空 body + 回读 `true`，且 path 不含 `/Favorites/`
2. DELETE 正确（bodySize 0）+ 回读 `false`
3. 服务器忽略写入 → 回读 `false`（不谎报成功）
4. 回读缺 `IsFavorite` → Parse
5. 回读缺 `UserData` → Parse（仅 1 次请求，无 fallback）
6. 写 401 → AuthExpired 且 **不回读**
7. 写 404 → NotFound
8. 回读 500 → Http(500, method=GET)
9. `isFavorite` 单请求读回
10. 无会话 → AuthRequired，0 次 HTTP

`provider/jellyfin/src/test/kotlin/com/mediahub/provider/jellyfin/favorite/JellyfinFavoriteProviderTest.kt`（10）
1. POST **无 `/emby` 前缀** + `Authorization: MediaBrowser …` 含 `Token="tok-1"` + **无 `X-Emby-Token`** + 空 body + 回读 `true`
2. DELETE 正确 + 回读 `false`
3. 服务器忽略写入 → `false`
4. 回读缺 `IsFavorite` → Parse
5. 回读缺 `UserData` → Parse
6. 写 401 → AuthExpired 且不回读
7. 写 404 → NotFound
8. 回读 500 → Http(500, GET)
9. `isFavorite` 单请求
10. 无会话 → AuthRequired，0 次 HTTP

`core/network/src/test/kotlin/com/mediahub/core/network/ApiClientNoContentTest.kt`（+2）
- `deleteNoContent` 发 DELETE、无 body、保留 header
- DELETE 404 → `ApiException(statusCode=404, method="DELETE")`

`provider/api/src/test/kotlin/com/mediahub/provider/api/ProviderHandleTest.kt`（+1）
- `favorite` 非空 → runtime 含 `FAVORITE`、`hasAnyCapability` 为真；为空时两侧都不含

既有断言同步更新：
- `provider/emby/src/test/.../EmbyProviderFactoryTest.kt`：精确集合加 FAVORITE，断言 `handle.favorite` 非空、descriptor 声明 FAVORITE
- `provider/jellyfin/src/test/.../JellyfinProviderCapabilityAuditTest.kt`：冻结集合加 FAVORITE，断言 `favorite` 非空 + runtime ⊆ declared

## 6. 未覆盖项（明确不做 / 待后续任务）

1. **Compose UI**：无收藏按钮、无长按菜单、无状态驱动图标；UI 层入口仍为 0。
2. **Room / 本地缓存**：收藏状态不落库，每次以服务端回读为准；离线时不可写、不可读（会抛 Network/AuthRequired）。
3. **备份/同步（backupId）**：既有备份结构不含收藏与播放列表；未扩展。
4. **列表页收藏筛选接线**：Emby `filter.favorite` 参数保留但 Android UI 未暴露；Jellyfin 侧无对应参数（端点支持 `Filters=IsFavorite`，未实现）。
5. **批量/幂等**：无批量写；重复 POST/DELETE 由服务端幂等处理，客户端不做去抖。
6. **收藏时间**：`UserData` 无 `FavoriteAddedAt` 回读（模型 `Favorite.addedAtEpochMs` 仍为死字段）。
7. **验收（已完成，2026-10-23）**：收到 lead GO 后已跑真实 gradle（JDK 21，`--console=plain`），全部绿：
   - `gradlew.bat :provider:api:test :core:network:testDebugUnitTest :provider:emby:testDebugUnitTest --tests *Favorite* :provider:jellyfin:testDebugUnitTest --tests *Favorite*` → `BUILD SUCCESSFUL in 16s`（116 tasks）；
   - `gradlew.bat :provider:emby:testDebugUnitTest :provider:jellyfin:testDebugUnitTest`（整模块全量，含被过滤掉的 EmbyProviderFactoryTest / JellyfinProviderCapabilityAuditTest）→ `BUILD SUCCESSFUL in 18s`；
   - 用例数（JUnit XML）：emby 20 suites / 185 tests / 0 failures；jellyfin 14 suites / 104 tests / 0 failures；core:network 11 suites / 56 tests；provider:api 4 suites / 17 tests；其中 EmbyFavoriteProviderTest=10、JellyfinFavoriteProviderTest=10、ApiClientNoContentTest=3、ProviderHandleTest=7、EmbyProviderFactoryTest=1、JellyfinProviderCapabilityAuditTest=3。
   - 首跑曾 1 例红：`assertEquals(0L, server.requestCount)` 中 requestCount 为 Int，Long 与 Integer 用 Object 重载比较 → `expected: java.lang.Long<0> but was: java.lang.Integer<0>`；改为 `assertEquals(0, …)` 后转绿（**未放宽任何断言**）。
   - 命令提示：Android library 模块的 `test` 是生命周期任务，不接受 `--tests`；须用 `testDebugUnitTest`。provider:api 为纯 JVM 模块，用 `test`。
