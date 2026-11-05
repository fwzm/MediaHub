# Android 播完自动连播（autoPlayNextEpisode）

- 状态：**已实现，单测已验证**（2026-10-23 gradle：JVM 20 例 + Robolectric 9 例，0 failures；真机/CI 未覆盖）
- 日期：2026-10-23
- 相关：`core/model/.../UserPreferences.kt`（开关）、`feature/player/.../PlayerViewModel.kt`（消费方）
- 代码：`core/model/src/main/kotlin/com/mediahub/model/UpNextResolver.kt`、`feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt`
- 测试：`core/model/src/test/kotlin/com/mediahub/model/UpNextResolverTest.kt`、`feature/player/src/test/kotlin/com/mediahub/feature/player/PlayerAutoplayTest.kt`

## 问题

`UserPreferences.autoPlayNextEpisode` 在设置页能点、能持久化（默认 true），但没有任何消费方：一集播完停在黑屏/最后一帧，用户必须回列表手动找下一集。Android 侧因此缺一条「连续追剧」的基本路径。

## 决策

### D1：选集逻辑抽成纯函数 `UpNextResolver`（可 JVM 单测）

剧集排序和「下一集是谁」如果写在 ViewModel 里，只能靠 Robolectric 测，且极易被 UI 状态污染。规则因此下沉到 `core:model`（纯 Kotlin JVM 模块、零协程依赖、无 Android 依赖），ViewModel 只负责取数据与切播。

排序与取值规则（每条都有单测钉住）：

- **季**按 `seasonNumber` 升序；季号缺失或 ≤0（特别篇/未知）**一律排最后**，不得因为 0 顶到第一季前面。
- **集**按 `episodeNumber` 升序，同号再看 `indexNumber`，最后看 `id`；集号缺失或 ≤0 的集同样排最后。
- `nextInSeason`：能在列表里定位当前集（按 id）→ 取排序后的下一条；定位不到但集号已知 → 取集号更大的第一条；两者都不可用 → null（交给跨季）。
- `followingSeasons`：当前季能定位 → 取它之后的全部季（含季号未知、排末尾的季）；当前季不在列表但季号已知 → 只取季号更大的季（未知季号保守跳过）；其余 → 空列表（宁可不连播也不猜）。
- `resolveNext`：同季优先；本季没有下一集则逐季取该季第一集，**空季跳过**；全都没有 → null。

### D2：跨季必须能连（S01E03 → S02E01）

只做同季续播的产品行为是残的：一季 10 集的剧每 10 集断一次。测试里显式钉住 `S01 最后一集 → S02 第一集`，并覆盖「下一季是空季则继续往后找」。

### D3：切播复用既有 resolve 路径，不新写一套播放流程

连播只做三件事：算出 `nextEpisodeId`、改 `itemId`、**清空启动快照**（`launchType/launchRuntime/launchPoster/launchContainer`），然后调既有的 `resolveInternal(autoplay = true)`。后者本来就走 `detail → getResume → resolvePlayback → engine.play → 字幕中心 → Ready`，所以续播位置、字幕发现、错误提示、启动跟踪全都自动继承。

### D4：事件只能订阅一次（Channel 是单消费者）

`SwitchablePlaybackEngine.events` 是 `Channel.receiveAsFlow()`：`ProgressSyncCoordinator` 已经在消费它（Pause/Seek/Ended 的立即 flush）。ViewModel 若再 `collect` 一次会**抢走事件**，导致退出/暂停时的进度同步丢事件。因此加一层 tap：ViewModel 只订阅一次，把事件转发到内部 `MutableSharedFlow`（`playbackEventBus`），协调器与连播逻辑都从这条总线读。`Ended` 在总线上只转发一次给协调器（旧条目的「已看完」不会重复上报）。

### D5：开关关掉就什么都不做；失败一次就整轮停住

- `autoPlayNextEpisode = false`：`Ended` 直接返回，不弹提示、不排队、不改 itemId。
- 同一集重复 `Ended`：`autoplayHandledItemId` 记住「已为哪一集安排过」，只安排一次（不会连跳两集）。
- 切换失败（下一集 resolve 抛错）：`autoplayBlocked = true`，本轮播放停在失败态，**不重试、不连环跳集**；用户手动重播（`resolve()`）会清掉这个标记。
- 最后一季最后一集：无下一集 → 原地不动，不报错、不进 Failed。

### D6：详情页直传快照要补齐剧集元数据

从详情页点播放时 `SavedStateHandle` 只带 `type/title/runtime/poster/container`，没有 `seriesId/seasonId/集号`。只看快照的话连播会**静默失效**（真实用户最常走这条路径）。因此 `advanceToNextEpisode` 先 `hydrateEpisodeMetadata`：快照缺季/集字段时用 `detail.getItemDetail(item.id)` 补一次（已有 `seriesId + seasonId` 时零额外请求）。

## 数据流

```
engine.events (Channel, 单消费者)
   └─ startEventsTap()  ← 只订阅一次
        ├─ playbackEventBus.emit(event) → ProgressSyncCoordinator（Pause/Seek/Ended flush）
        └─ event == Ended → onPlaybackEnded()
                              ├─ 开关关 / 已处理 / 非 EPISODE → return
                              └─ advanceToNextEpisode(currentItem)
                                   ├─ hydrateEpisodeMetadata（快照缺字段时补 detail）
                                   ├─ getSeasons(seriesId) + getEpisodes(seasonId)
                                   ├─ UpNextResolver.nextInSeason(...) → 命中则结束
                                   ├─ UpNextResolver.followingSeasons(...) → 逐季 firstEpisode
                                   └─ itemId = next; 清空启动快照; resolveInternal(autoplay=true)
```

## 测试

### `UpNextResolverTest`（core:model，20 例）

季/集排序（含季号 0 与 -1、集号 0 排最后，特别篇不插到 S02 之前）、`nextInSeason` 命中/id 未知按号回退/两者不可用给 null、`followingSeasons` 正常与末季/未知季号/当前季不在列表、`resolveNext` 同季/跨季/跳过空季/最后一集给 null、`firstEpisode` 空季、`isKnownNumber` 边界。

### `PlayerAutoplayTest`（feature:player，9 例）

| 场景 | 钉住的规则 |
| --- | --- |
| 开关关 | Ended 后仍停在原条目，不再调用 `engine.play` |
| 同季下一集 | Ended → 播放 `s1e2`，`itemType` 仍是 EPISODE，状态 Ready |
| 跨季 | `s1e1` 是本季唯一集 → 播 `s2e1` |
| 最后一集 | 不切换、不 Failed |
| 切换失败 | 停在 Failed，再收 Ended 也不重试 |
| 切集后进度身份 | 上报的 `itemId` 是新条目 |
| 重复 Ended | 只前进一步（`playCalls == 2`） |
| 详情页快照启动 | 缺季/集元数据时靠 detail 补齐，仍能连播 |
| 电影播完 | 不触发任何切换 |

## 未覆盖 / 待办

- **UI 提示**：没有「即将播放下一集」倒计时或取消入口（当前是直接切）。产品若要 Netflix 式 5 秒倒计时，需要新增 UI 状态，不改 resolver。
- **多服务器**：连播只在当前 `handle`（当前数据源）内进行；跨服务器同名剧集不连。
- **剧集列表分页**：`getEpisodes` 返回整季后端已分页；超长季（>500 集）若后端截断，`nextInSeason` 可能找不到下一集（退化为跨季）。
- **播放列表/下一集预取**：无预缓冲，切集有短暂 loading（依赖既有 resolve）。
- **Android 真机/CI**：JVM 与 Robolectric 用例已通过（`:core:model:test`、`:feature:player:testDebugUnitTest`），尚未在真机与 CI 上跑；instrumentation 未覆盖。

## 复现

```bash
# 纯逻辑（最快，无需模拟器）— JVM 模块，test 本身就是 Test 任务
./gradlew :core:model:test --tests 'com.mediahub.model.UpNextResolverTest'

# 播放器行为（Robolectric，无需模拟器）— Android library 模块必须用 variant Test 任务
./gradlew :feature:player:testDebugUnitTest --tests 'com.mediahub.feature.player.PlayerAutoplayTest'
```

> `:feature:player:test` 是生命周期任务，带 `--tests` 会报 `Unknown command-line option '--tests'`；`--tests` 只对 variant Test 任务（如 `testDebugUnitTest`）有效。

预期：两条命令均 `BUILD SUCCESSFUL`，两个测试类 0 failures（20 + 9 = 29 例）。

实测（2026-10-23）：

| 命令 | 结果 |
| --- | --- |
| `:core:model:test --tests ...UpNextResolverTest --rerun-tasks` | `BUILD SUCCESSFUL in 12s`，tests=20 failures=0 errors=0 |
| `:feature:player:testDebugUnitTest --tests ...PlayerAutoplayTest --rerun-tasks` | `BUILD SUCCESSFUL in 1m 5s`，tests=9 failures=0 errors=0 |
| `test --rerun-tasks`（全量） | `BUILD SUCCESSFUL in 3m 19s`，全部 JUnit XML failures=0 errors=0 |
