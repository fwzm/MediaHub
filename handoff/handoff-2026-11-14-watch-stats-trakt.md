# handoff-2026-11-14-watch-stats-trakt

## 1. 当前状态

- 分支：段1 `feature/watch-stats-trakt-20261114` → 段2 `feature/watch-stats-ui-20261114` → 段3 `feature/trakt-tracking-20261114`
- worktree：`D:\deepseek_test\mh-stats-trakt-20261114`
- 基线：`origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`
- 段 1 提交：`b0be2828`（feat(stats): 本地观影统计数据层与记录器）
- 段 2 提交：`301773f8`（feat(stats): 观影统计页面、日历、趋势、历史与入口显隐）
- 段 3 提交：`0b30406f`（feat(trakt): Trakt 追更、播出日期与请求限流）
- Draft PR：#119（base=main）/ #120（base=段1）/ #123（base=段2），全部 Draft，**未合并**
- G7：`DO_NOT_MERGE`

已完成（段 1/3，条目 141/122/139 数据面）：

- `core:model` 新增 `WatchStats.kt`（会话 / 日历 / 趋势 / 回顾 / 总览）。
- `core:database` 新增 `watch_sessions`、`hidden_content` 两表 + DAO + `MIGRATION_5_6`（Room v6）。
- `WatchStatsRepository`：聚合总时长、电影/剧集数（按 itemId 去重）、观看天数、日历、30 天趋势、历史（倒序）、回顾（连续天数/最常看标题）。
- 统计查询对 `hidden_content` **无条件过滤**，不依赖隐藏空间锁定状态。
- `DefaultWatchStatsRecorder`：按正向内容增量累计观看时长，回拖/回看继续计入，大跨度前跳按墙钟封顶，暂停/缓冲不计；会话 checkpoint Upsert；<1s 会话丢弃。
- `feature:player` 接入：Ready 后采样 + 30s checkpoint；自动连播换集前结算；`stopAndFlush` 用最终进度收尾。
- 条目 122：`UserPreferences.watchStatsEnabled`（默认 true），关闭后停止记录、已有记录保留。

## 2. 本段证据

- `:core:database:testDebugUnitTest --rerun-tasks --no-build-cache` → exit 0，
  30 suites / 290 tests / 0 failures（新增 13 条：Recorder 8 + Repository 4 + Migration 1；偏好 +1）。
- `:feature:player:testDebugUnitTest --rerun-tasks --no-build-cache` → exit 0，
  14 suites / 129 tests / 0 failures。
- `:feature:player:compileDebugKotlin :app:compileDebugKotlin` → exit 0（Hilt 聚合通过）。
- RED 说明：仓库基线不存在这些类/表，新增测试在基线上无法编译（先红），实现后转绿。

## 3. 段 2/3（统计 UI）已完成

- 新模块 `feature:stats`：`StatsScreen` 总览四卡 / 观影回顾 / 月历（周一为周起点，可翻月，按当日时长着色）/ 近 30 天趋势柱图 / 观看历史列表。
- `StatsViewModel`：合并 `WatchStatsStore.observeOverview(30)` 与偏好流；关闭开关时顶部提示"不再记录，已有记录保留"。
- `feature:settings`：新增"观影统计"开关；**开关关闭时统计入口按钮不渲染**（条目 122）。
- `app`：`Routes.STATS` 导航；设置页 `onOpenStats`。
- 提交：`301773f8`（分支 `feature/watch-stats-ui-20261114`，基于段 1 head）。

### 段 2 证据

- `:feature:stats:testDebugUnitTest --rerun-tasks --no-build-cache` → exit 0，2 suites / 6 tests / 0 failures。
- `:feature:settings:testDebugUnitTest --rerun-tasks --no-build-cache` → exit 0，33 suites / 440 tests / 0 failures。
- `:app:compileDebugKotlin --rerun-tasks --no-build-cache` → exit 0。
- 未做 Compose 真机/截图验证（`NOT_RUN`），不声称 UI 已人工验收。

## 4. 段 3/3（Trakt 追更与限流）已完成

- 分支：`feature/trakt-tracking-20261114`（基于段 2 head `d412cb39`）
- 提交：`0b30406f`（feat(trakt): Trakt 追更、播出日期与请求限流）
- Draft PR：#123（base=`feature/watch-stats-ui-20261114`，**未合并**）
- `core:trakt` 新模块：
  - `TraktConfig`：Gradle 属性 `mediahub.trakt.clientId/clientSecret/accessToken` → `BuildConfig` → Hilt 注入点；
    空白值转 null，`NotConfigured` 降级，零请求、不崩溃。
  - `TraktRateLimiter`：主动最小间隔（默认 350ms）+ 429 `Retry-After` 冷却。
  - `TraktRepository`：`searchShow` / `addToWatchlist` / `removeFromWatchlist` / `getCalendar` / 本地追更列表；
    429 / 401 / 403 / 404 / 网络异常均有明确文案。
- `core:database`：`trakt_tracked_shows` 表 + `MIGRATION_6_7`（Room v7）。
- `feature:detail`：剧集播出日期（`yyyy-MM-dd`）、加入/取消追更按钮与失败提示。
- `feature:stats`：追更日历页（按天分组、季/集排序、未配置/失败/空态文案）。
- `feature:settings`：新增「Trakt 追更 → 追更日历」入口。

### 段 3 证据（EXECUTED_PASS）

- `:core:trakt:testDebugUnitTest` → exit 0，12 tests / 0 failures（限流 3 + 仓库 9）。
- `:core:database:testDebugUnitTest` → exit 0，291 tests / 0 failures（含 `Migration6to7Test`）。
- `:feature:stats:testDebugUnitTest` → exit 0，7 tests / 0 failures。
- `:feature:detail:testDebugUnitTest` → exit 0，41 tests / 0 failures。
- `:feature:settings:testDebugUnitTest` → exit 0，440 tests / 0 failures。
- `:app:compileDebugKotlin` → BUILD SUCCESSFUL（Hilt 聚合通过；修复了 `@Module` 混用 `@Binds`/`@Provides`）。
- 全部命令：`--rerun-tasks --no-build-cache --no-daemon --console=plain --no-parallel --max-workers=1`。

## 5. 与原始目标的差距（勿夸大）

- 隐藏空间：仓库无该模块/UI，本次只提供隐藏键存储 + 统计侧过滤契约；标记写入方缺失 → 139 的端到端为 `PARTIAL`。
- 真机 `NOT_RUN`；无设备证据。CI 结果 `CI_PENDING`（不观察为 PASS，不做 merge / mark-ready）。
- Trakt 凭据仅通过 Gradle 属性注入；仓库内无真实凭据，验证均为 MockWebServer。

## 6. 下一步

1. 观察 PR #119 / #120 / #123 的 CI；若失败按失败日志修（不 merge、不 mark-ready）。
2. 可选：真机验证统计页与 Trakt 追更（需设备在线，按 AGENTS.md §8 的 tap 延迟规则）。
3. 更新 `D:\deepseek_test\mh-dsh-longrun-20261014\queue.json` 与 `RUNLOG.md`（本段已更新）。
