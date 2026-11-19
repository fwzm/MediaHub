# #129 跨服搜索分页续拉 — 交接账本

## 任务状态

<!-- type:state -->
- 审计行 129「优化-跨服资源聚合与搜索加载」的分页缺口已实现并提交 Draft PR **#144**（https://github.com/fwzm/MediaHub/pull/144），base=main，head=feature/search-pagination-20261119，代码提交 6aba0ca50515677b4c2109209f90b7308289c7c3。
- G7=**DO_NOT_MERGE**；PR 保持 Draft，未合并、未 mark-ready、未写 main。
- JVM 单测与构建 **EXECUTED_PASS**；真机/真实服务器分页 **NOT_RUN**（未安装 APK、未触碰设备 123e243f）。

## 目标

<!-- type:goal -->
- 跨服务器聚合搜索不再只显示第一页：首轮记录每个服务器的 nextOffset，提供「加载更多」按钮续拉。
- 续拉只请求仍有下一页的服务器；按 targets 顺序追加并按 serverId + item.id 去重；单服失败/超时保留旧命中与旧 offset。
- ViewModel 同一时间只允许一个续拉任务；切换查询时取消旧任务，避免旧结果回填。

## 已试方案与失败原因

<!-- type:dead-end -->
- 临时 VM stub 删除了 SavedStateHandle 与 query StateFlow，导致 SearchScreen.kt:72 编译错误（Property delegate must have a getValue / Unresolved reference 'query'）；按 base 恢复 VM API 后重新获得真实 RED，该编译失败不计产品 RED。
- 首轮负向对照把 Gradle 参数逐个加双引号，cmd.exe 报 Cannot locate tasks that match '":feature:search:testDebugUnitTest"' as project '"'；改为 ['gradlew.bat', ...args].join(' ') 不加引号。
- engine-next-offset 场景最初按全类跑，去掉首轮 offset 会连带 loadMore 无 offset 可续拉，得到 3 红而非恰好 1 红；改为方法级 * 通配过滤（first*page*records*only*servers*that*have*another*page 等），最终 5/5 PASS_ONE_RED。
- SearchBodyLoadMoreTest 首次编译因冗余 import assertExists 与错误包名 com.mediahub.core.model 失败；改用 com.mediahub.model 并删 import 后通过。

## 进度与下一步

<!-- type:progress -->
- 已完成：GlobalSearchEngine 首轮 nextOffsets + loadMore 续拉/去重/失败保留；GlobalSearchModels nextOffsets/canLoadMore；GlobalSearchViewModel MutableStateFlow + loadMore；SearchScreen「加载更多」按钮；新增 GlobalSearchPaginationTest（5）、SearchBodyLoadMoreTest（1）、GlobalSearchViewModelTest 新增 loadMore 用例（1）。
- 证据：全量 :feature:search:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug -> BUILD SUCCESSFUL 12m38s，519 tasks executed，exit 0；feature:search 6 suites/37 tests、app 8 suites/30 tests，合计 14 suites/67 tests 全绿。负向对照 5/5 PASS_ONE_RED 均从快照逐字节还原并 sha256 校验。APK size=130595735 sha256=30ffe6f754eeca8d143ddf956160bf4b641b70d6004a28c89f9eabf9656a046c（未安装）。
- 下一步第一步（直接执行）：用 40 位完整 SHA 查询 PR #144 exact-head CI；pending/红不得当 PASS，红则保留原始失败定位后处理。
- 独立后续：真机/真实服务器验证多页续拉（需先核对同签名与 Room 版本；设备 123e243f 受 Room 6↔5 阻塞，禁清库）。G7 恒 DO_NOT_MERGE。
