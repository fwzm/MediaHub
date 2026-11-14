# handoff-2026-11-14-watch-stats-trakt

## 1. 当前状态

- 分支：`feature/watch-stats-trakt-20261114`
- worktree：`D:\deepseek_test\mh-stats-trakt-20261114`
- 基线：`origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`
- 段 1 提交：`b0be2828`（feat(stats): 本地观影统计数据层与记录器）
- Draft PR：#119（base=main，Draft，**未合并**）
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

## 3. 与原始目标的差距（勿夸大）

- 隐藏空间：仓库无该模块/UI，本次只提供隐藏键存储 + 统计侧过滤契约；标记写入方缺失 → 139 的端到端为 `PARTIAL`。
- 统计页面（总览/日历/趋势/历史/回顾 UI）尚未实现 → 段 2。
- Trakt（142/146、90 的请求限流与错误提示）尚未实现 → 段 3。
- 真机 `NOT_RUN`；无设备证据。

## 4. 下一步

1. 段 2：新建 `feature:stats`（Compose 统计页 + 观影日历 + 趋势 + 历史 + 回顾），导航与设置入口按 `watchStatsEnabled` 显隐。
2. 段 3：`core:trakt`（凭据注入、MockWebServer 测试、频率限制 429/Retry-After、追更增删、追更日历、剧集播出日期与错误提示）。
3. 每段单独提交 + Draft PR；更新 queue.json 的 `watch_stats_trakt_20261114` 与 RUNLOG。
