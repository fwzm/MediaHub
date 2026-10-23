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

1. **Compose UI**：**已覆盖（§7.2）**——详情页 TopAppBar 收藏开关 + 「我的收藏」列表页；仍无长按菜单、无批量操作。
2. **Room / 本地缓存**：收藏状态不落库，每次以服务端回读为准；离线时不可写、不可读（会抛 Network/AuthRequired）。
3. **备份/同步（backupId）**：结构已就位（§7.3：`favorites` 段 + 格式版本 2）；**导出接线未做**（本机无收藏真源），恢复侧也不写服务端。
4. **列表页收藏筛选接线**：**部分覆盖（§7.1）**——服务端 `Filters=IsFavorite` 已用于「我的收藏」列表；普通媒体库列表页仍未暴露收藏筛选。
5. **批量/幂等**：无批量写；重复 POST/DELETE 由服务端幂等处理，客户端不做去抖。
6. **收藏时间**：`UserData` 无 `FavoriteAddedAt` 回读（模型 `Favorite.addedAtEpochMs` 仍为死字段）。
7. **验收（已完成，2026-10-23）**：收到 lead GO 后已跑真实 gradle（JDK 21，`--console=plain`），全部绿：
   - `gradlew.bat :provider:api:test :core:network:testDebugUnitTest :provider:emby:testDebugUnitTest --tests *Favorite* :provider:jellyfin:testDebugUnitTest --tests *Favorite*` → `BUILD SUCCESSFUL in 16s`（116 tasks）；
   - `gradlew.bat :provider:emby:testDebugUnitTest :provider:jellyfin:testDebugUnitTest`（整模块全量，含被过滤掉的 EmbyProviderFactoryTest / JellyfinProviderCapabilityAuditTest）→ `BUILD SUCCESSFUL in 18s`；
   - 用例数（JUnit XML）：emby 20 suites / 185 tests / 0 failures；jellyfin 14 suites / 104 tests / 0 failures；core:network 11 suites / 56 tests；provider:api 4 suites / 17 tests；其中 EmbyFavoriteProviderTest=10、JellyfinFavoriteProviderTest=10、ApiClientNoContentTest=3、ProviderHandleTest=7、EmbyProviderFactoryTest=1、JellyfinProviderCapabilityAuditTest=3。
   - 首跑曾 1 例红：`assertEquals(0L, server.requestCount)` 中 requestCount 为 Int，Long 与 Integer 用 Object 重载比较 → `expected: java.lang.Long<0> but was: java.lang.Integer<0>`；改为 `assertEquals(0, …)` 后转绿（**未放宽任何断言**）。
   - 命令提示：Android library 模块的 `test` 是生命周期任务，不接受 `--tests`；须用 `testDebugUnitTest`。provider:api 为纯 JVM 模块，用 `test`。

## 7. task-25 增补：收藏列表、UI 入口、备份结构

### 7.1 收藏列表（provider 层）

| 项 | 内容 |
| --- | --- |
| 接口 | `MediaFavoriteProvider.listFavorites(limit: Int = DEFAULT_FAVORITES_LIMIT): List<MediaItem>`；`DEFAULT_FAVORITES_LIMIT = 100`、`MAX_FAVORITES_LIMIT = 200`（`provider/api/.../CapabilityProviders.kt` 文件尾） |
| Emby 实现 | `GET /emby/Users/{uid}/Items?Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series,Episode&Fields=…&EnableUserData=true&StartIndex=0&Limit=…`（`EmbyApiClient.getFavoriteItems`、`EmbyFavoriteProvider.listFavorites`） |
| Jellyfin 实现 | 同参数，走现代路由 `GET /Items?UserId=…`（`JellyfinApiClient.getFavoriteItems`、`JellyfinFavoriteProvider.listFavorites`） |
| 兜底 | 服务端 `Filters` 只是优化：映射后仍 `filter { it.isFavorite }` + `distinctBy { it.id }`；服务器未标收藏的条目不会出现在列表里 |
| 边界 | `limit <= 0` 直接返回空列表且**不发 HTTP**；`limit` 上限 200（超出被夹紧） |
| 错误 | 与写入同族：401→`AuthExpired`、404→`NotFound`、其余→`Http`、无会话→`AuthRequired`（0 次 HTTP） |

### 7.2 UI 入口（Compose）

| 位置 | 行为 |
| --- | --- |
| 详情页 TopAppBar `actions`（`feature/detail/.../DetailScreen.kt`） | 收藏图标（`Icons.Default.Favorite` / `FavoriteBorder`），`contentDescription` 来自 `feature/detail/src/main/res/values/strings.xml`（`detail_action_favorite` / `detail_action_unfavorite`）；点击 `viewModel::toggleFavorite` |
| `DetailViewModel.toggleFavorite()` | `setFavorite(itemId, !current)` → 用**回读值**更新（非本地取反）；请求在途禁用按钮；失败只在详情上方追加可读错误，**图标不翻转** |
| 状态迁移纯函数 | `feature/detail/.../FavoriteToggleState.kt`：`FavoriteToggle.fromServerState / pending / confirmed / failed`（4 例单测） |
| 「我的收藏」列表页 | `feature/library/.../favorites/FavoritesScreen.kt`（Loading / Empty / Content / Error 四态；刷新失败保留旧列表只挂提示）；`FavoritesViewModel` 走 `handle.favorite.listFavorites()` |
| 入口 | 媒体库页 TopAppBar 收藏图标（`LibraryRoute(onOpenFavorites = …)`）→ 路由 `favorites/{serverId}`（`app/.../MediaHubNavHost.kt`，`Routes.FAVORITES`） |
| 状态纯函数 | `feature/library/.../favorites/FavoritesState.kt`：`loading / loaded / failed / unsupported`（6 例单测，含 `subtitleOf`） |

### 7.3 备份结构（`core/common/.../backup`）

| 项 | 内容 |
| --- | --- |
| 新 DTO | `BackupDtos.FavoriteDto(serverBackupId, itemId, title?, addedAtEpochMs)`；`BackupPayload.favorites: List<FavoriteDto> = emptyList()` → **老备份无该段按空列表** |
| 格式版本 | `BackupCrypto.FORMAT_VERSION = 2`、`MINIMUM_READER_VERSION = 2`；v1 文件（manifest 1/1、envelope 1）仍可导入（AAD 用文件自身 `formatVersion`），单测钉住 |
| 校验 | section 白名单加 `favorites`；`MAX_FAVORITES = 5_000`；空 `serverBackupId`/`itemId`、重复 `(serverBackupId, itemId)`、负 `addedAtEpochMs`、标题超 512 字符 → `Corrupted` |
| 导出 | `BackupRepository.buildPayload` 仅当 `favorites` 非空才写入 `includedSections/recordCounts`；**当前恒为空**（收藏真源在服务端，本机无表），避免向外宣称"已备份收藏" |
| 恢复 | 不写服务端收藏（需要逐条网络写入 + 用户确认，未在任务范围） |

### 7.4 本轮新增测试（共 +31 例）

- `provider/emby/.../favorite/EmbyFavoriteProviderTest.kt` **+7**：Filters/Recursive/IncludeItemTypes/EnableUserData/Limit 参数断言、未标收藏被丢弃、空页、limit<=0 不发 HTTP、limit 夹到 200、401→AuthExpired、404→NotFound。
- `provider/jellyfin/.../favorite/JellyfinFavoriteProviderTest.kt` **+5**：现代 `/Items` 路由 + `UserId` + 无 `/emby` 前缀 + 无 `X-Emby-Token`、未标收藏被丢弃、空页、401、404。
- `core/common/.../BackupSerializerTest.kt` **+8**：favorites roundtrip、v1 旧文件仍可导入（无 favorites → 空）、缺 `favorites` 键的 JSON 解码为空、空 ID/重复/负时间戳拒绝、sections 与 recordCounts 不一致拒绝、超上限拒绝。
- `feature/detail/.../FavoriteToggleTest.kt` **+4**：服务器值初始化、pending 不预翻转且清旧错误、confirmed 用回读值（服务器忽略时停在旧值）、failed 保持原状态 + 可读错误。
- `feature/library/.../favorites/FavoritesStateTest.kt` **+6**：空页→Empty、顺序保持、无内容失败→Error、有内容失败→保留列表+refreshError、不支持→显式错误、`subtitleOf` 文案。
- `provider/api/.../ProviderHandleTest.kt` **+1**：`favorite` 字段非空 → `runtimeCapabilities` 含 `FAVORITE`、`hasAnyCapability` 为真；缺省时两者皆为假。

### 7.5 task-25 未覆盖项

1. 收藏列表**分页/加载更多**（当前单页 limit=100，无滚动加载）。
2. 备份**导出接线**与恢复写入服务端收藏（结构/校验/版本已就位）。
3. 收藏时间戳（`Favorite.addedAtEpochMs`）无服务端回读，备份段该字段只能靠客户端记录时间。
4. 普通媒体库列表页的收藏筛选开关（仅「我的收藏」页用到 Filters）。
5. 多服务器聚合收藏（当前页按 `serverId` 单源）。
6. 本轮测试结果见 7.6（已实跑，全绿）。

### 7.6 实测记录（2026-10-24，JDK 21）

命令（仓库根，`cmd /c` 重定向日志，`--rerun-tasks` 确保无 UP-TO-DATE 复用）：

```
gradlew.bat :core:common:testDebugUnitTest :provider:api:test :provider:emby:testDebugUnitTest \
  :provider:jellyfin:testDebugUnitTest :feature:detail:testDebugUnitTest \
  :feature:library:testDebugUnitTest :feature:settings:testDebugUnitTest --console=plain --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 1m 12s`（EXIT=0）。各模块 JUnit XML 汇总（failures/errors 均为 0）：

| 模块 | suites | tests |
| --- | --- | --- |
| core:common | 5 | 78 |
| provider:api | 4 | 17 |
| provider:emby | 20 | 192 |
| provider:jellyfin | 14 | 109 |
| feature:detail | 4 | 41 |
| feature:library | 5 | 54 |
| feature:settings | 9 | 115 |
| **合计** | **61** | **606** |

红例复盘（全部为夹具/版本升级引发，实现与断言均未放宽）：

1. `ProviderHandleTest` 匿名 fake 未实现新增的 `listFavorites` → 编译失败；补实现（接口约束不放宽）。
2. `core:common` 2 例：`uncheckedBackup` 夹具把信封版本与 AAD 版本写死 1，而 `BackupSerializer.kt:273` 校验「内外版本一致」→ v2 载荷被判 `Corrupted`。夹具改为取 `payload.manifest.formatVersion`。
3. `feature:settings` 编译错：`RestoreSnapshotStoreTest.kt:185` 用位置参数构造 `BackupPayload`，`favorites` 插在 `progress` 之后导致错位 → 改命名参数。
4. `feature:settings` 31 例：`BackupTestFixtures.authenticatedTestBytes` 同样写死信封版本 1；`BackupRestoreSafetyTest` / `RestoreSnapshotStoreTest` 的 manifest 仍是 1/1（`export` 要求 `manifest.formatVersion == FORMAT_VERSION`）；`BackupRepositoryTest:530` 断言基线版本硬编码 1 → 分别改为按载荷版本构造、改用 `BackupCrypto` 常量、断言跟随常量。
5. `app/src/androidTest/.../RestoreProcessDeathTest.kt:123` 的 instrumented 夹具 manifest 同步改常量（本机未跑 instrumented，仅源码一致性）。

