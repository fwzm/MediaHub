# Handoff — 2026-11-20 — Library/Home 自适应布局（#26 切片）

## 任务状态
type:state
- Round 15/256；task_id=adaptive-layout-20261120；branch=feature/adaptive-layout-20261120；基于 origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 代码提交：e437bf4d639d2ab8b396fa1af6a3b414191a706c（4 files +76/−7：AdaptiveLayoutPolicy.kt、AdaptiveLayoutPolicyTest.kt、LibraryScreen.kt、HomeScreen.kt）。
- 实现：core:ui AdaptiveLayoutPolicy 提供媒体库列数（2/3/4/5/6）与继续观看卡片宽度（232/276/320/360dp）；LibraryScreen 用 LocalConfiguration.screenWidthDp 选择 GridCells.Fixed，并把所有整行 span 从固定 3 改为 maxLineSpan；HomeScreen 用同一断点策略替换固定 276dp。
- 验证：RED core:ui 3 tests / 3 failed；聚焦 GREEN 6m42s / 92 tasks / exit0（core:ui 10 suites/60 tests）；NC 4/4 PASS_ONE_RED；全量 GREEN 17m33s / 549 tasks / exit0；80 suites / 795 tests / 0 skipped / 0 failures / 0 errors；APK size=130579351 sha256=20da6b34216f43537ce5a7274d3d205079d83a4013b1375a8ce8c1d060c52088（未安装）。
- 真机：NOT_RUN（未触碰设备 123e243f；Room 6↔5 阻塞，禁清库）。

## 目标
type:goal
- 审计行 #26「部分界面自适应布局」的 Library + Home 切片：用窗口宽度断点替换固定 3 列网格与 276dp 继续观看卡片，并保证整行项跨满所有列。

## 已试方案与失败原因
type:dead-end
- 未引入 material3 WindowSizeClass 依赖，改用 LocalConfiguration.screenWidthDp + 纯 Kotlin 断点策略，便于 JVM 单测；效果等价于 compact/medium/expanded 分档。
- DetailScreen 第 223 行的详情海报宽度刻意不改：PR #163 已在同一位置引入 PosterLayoutPolicy，两分支同时改会产生冲突；#26 的 Detail 子项待 #163 合并后复核。
- NC 四个场景均从实现态 snapshot 逐字节还原，sha256 无 mismatch；summary-all.json allPassOneRed=true。

## 进度与下一步
type:progress
- 当前为 #26 的 PARTIAL 闭环：Library 网格与 Home 卡片已完成；Detail 页面由 #163 覆盖，需在 #163 合并后复核 #26 全页闭合。
- 下一步：用 40 位完整 SHA 查 PR #163 与本 PR 的 exact-head Android CI；结果未出前保持 CI_PENDING，不得当 PASS。
- 下一审计候选：审计行 #5 媒体库横竖海报自适应，或 #43 编辑媒体库不立即生效；先查 queue 第一条 READY。
