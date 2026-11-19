# #107 媒体源占位 Logo — 交接账本

## 任务状态

<!-- type:state -->
- 审计行 107「部分服不显示占位 Logo」已实现并交付 Draft PR **#137**（https://github.com/fwzm/MediaHub/pull/137），base=main，head=feature/server-icon-fallback-20261119，代码提交 6ebdaa2a9d4041205345469aa36f52619bd799d4。
- G7=**DO_NOT_MERGE**；PR 保持 Draft，未合并、未 mark-ready、未写 main。
- 真机安装与视觉渲染 **NOT_RUN**（未触碰设备 123e243f）。

## 目标

<!-- type:goal -->
- 首页媒体源卡片与播放器服务器身份行不再把 `icon` URI 当文本显示；无有效图标时统一显示首字母占位徽标（`core/ui/ServerIcon`）。
- 仅做一个垂直切片：ServerCard + PlayerServerIdentity + 对应 Robolectric 测试。

## 已试方案与失败原因

<!-- type:dead-end -->
- 初始 `ServerIcon` 目标路径误记为 `core/ui/components`，实际为 `core/ui/src/main/kotlin/com/mediahub/core/ui/ServerIcon.kt`；已按真实路径接入。
- 测试首轮编译失败：`PlayerServerIdentityTest` 误 import `assertExists`（成员函数无需 import）；`ServerCardServerIconTest` 使用 legacy 构造器时无 `icon` 参数；分别删除 import、改用 `.copy(icon=...)` 后获得真实 RED。
- 负向对照脚本最初按整类运行会把无关断言一起打红；改为 `--tests=<无空格类名>` 精确过滤，server/player 各恰好 1 红。

## 进度与下一步

<!-- type:progress -->
- 实现：ServerCard 使用 44dp ServerIcon；PlayerServerIdentity 使用 20dp ServerIcon + 名称；PlayerScreen 接线。
- 验证 **EXECUTED_PASS（仅 JVM/构建）**：`:feature:server:testDebugUnitTest :feature:player:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-build-cache --no-daemon --console=plain --no-parallel --max-workers=1` → BUILD SUCCESSFUL in 6m 39s，526 tasks，exit 0；server 10 suites/67 tests、player 15/130、app 8/30，合计 33 suites/227 tests，0 failures/0 errors/0 skipped。
- 负向对照 2/2 PASS_ONE_RED，快照还原后哈希校验通过；APK sha256 9e9b1293a09cd1dd61ffb5a38fc7914706131edf6952b6c14cf5e1480d5b2060。
- 下一步：①查询 PR #137 exact-head CI（40 位 SHA）；②真机/截图验收仍受 Room 6↔5 安装阻塞，需先处理迁移；③后续候选审计行 #97 平板文件浏览 / #129 跨服搜索分页。
