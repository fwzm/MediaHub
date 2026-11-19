# Handoff 2026-11-20 跨服搜索分页续拉（#129）

## 任务状态（type:state）
- Round 19/256；审计行 #129「优化-跨服资源聚合与搜索加载」按“搜索分页续拉”切片实现，状态 PARTIAL（同片多源聚合未做）。
- worktree: D:/deepseek_test/mh-search-pagination-20261120；branch: feature/search-pagination-20261120；base: origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 代码提交: 2cba63c8bb01e548b8fa0d99f285c03ab2a2077c（6 files +195/−26）。
- 生产改动: GlobalSearchEngine.search 新增 offset/offsetsByServer，把 PageRequest.offset 真正传给 provider，并在成功时记录 hasMoreByServer/nextOffsetByServer；GlobalSearchViewModel 改为 MutableStateFlow + 显式 searchJob，startSearch/loadMore 合并分页，按 server 去重；SearchScreen 在还有下一页且空闲时显示“加载更多”。
- 测试: GlobalSearchEngineTest 12（新增 2）、GlobalSearchViewModelTest 7（新增 1）、SearchAggregatorTest 11、JellyfinEmbyCanonicalAggregationTest 3。
- 证据目录: D:/deepseek_test/mh-search-pagination-evidence-20261120。

## 目标（type:goal）
- 跨服搜索结果不再只取每服第一页；用户可在列表底部继续加载，直到所有服 hasMore=false。
- 每服独立维护下一页绝对偏移，避免不同服页大小不一致时错位。
- 保持现有聚合/排序/错误收集语义，纯 JVM 可红绿。

## 已试方案与失败原因（type:dead-end）
- 首版把全局 offset 平均分给所有服，会在页大小不一致时错位；改为 offsetsByServer 显式映射。
- loadMore 先清空旧 hits 再展示新页会闪空；改为以当前 state 为 base 合并。
- NC 若只盯 VM，无法证明 engine 真的传 offset；补 engine 的 forwarded-offset 断言。

## 进度与下一步（type:progress）
- RED: :feature:search:testDebugUnitTest 19 tests/3 failed，exit1；失败点 GlobalSearchViewModelTest.kt:264、GlobalSearchEngineTest.kt:77、:64。
- 聚焦 GREEN: :feature:search 2m7s/4 suites/33 tests/0 failures；:app:compileDebugKotlin 6m54s/321 tasks/exit0。
- NC: 3/3 PASS_ONE_RED（offset-ignored、hasmore-not-reported、loadmore-noop），每场 exit1 恰好1红，恢复 sha256 全部一致；summary allPassOneRed=true。
- 全量 GREEN: BUILD SUCCESSFUL 14m9s / 574 actionable tasks / exit0；97 suites / 954 tests / 0 skipped / 0 failures / 0 errors（app 8/30、core:model 8/71、core:ui 9/57、feature:detail 4/41、feature:home 2/16、feature:library 6/71、feature:player 14/129、feature:search 4/33、feature:server 9/66、feature:settings 33/440）。
- APK: app-debug.apk size=130595735 bytes sha256=12bba090026f74009c83c0f81795fd07f04474002fbaa6ba075569ed95278eb6（未安装）。
- 下一步: 推送分支、创建 Draft PR、写 queue；用 40 位 head 查 exact-head Android CI；真机 NOT_RUN（设备 123e243f 未触碰，Room 6↔5 阻塞，禁清库）；G7 恒 DO_NOT_MERGE。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
