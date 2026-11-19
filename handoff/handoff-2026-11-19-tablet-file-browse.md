# #97 平板文件浏览布局 — 交接账本

## 任务状态

<!-- type:state -->
- 审计行 97「优化-平板文件浏览布局」已实现并交付 Draft PR **#141**（https://github.com/fwzm/MediaHub/pull/141），base=main，head=feature/tablet-file-browse-20261119，代码提交 991af1f2ad02e1b7b1607952b6bc5a9e0196d233。
- G7=**DO_NOT_MERGE**；PR 保持 Draft，未合并、未 mark-ready、未写 main。
- JVM 单测与构建 **EXECUTED_PASS**；真机/模拟器安装与视觉渲染 **NOT_RUN**（未触碰设备 123e243f）。

## 目标

<!-- type:goal -->
- LibraryScreen 网格不再固定 3 列：按可用宽度自适应，手机 3 列、平板 5 列、大平板 6 列。
- 解除 MainActivity 竖屏锁，平板可随系统旋转；播放器退出后平板恢复系统方向、手机仍保持竖屏。
- 仅做一个垂直切片，并补齐网格策略、网格列数、Manifest 方向与播放器方向恢复的 JVM/Robolectric 测试。

## 已试方案与失败原因

<!-- type:dead-end -->
- LibraryGridColumnsTest 首轮未包 `requiredWidth(600.dp)`，布局仍按 3 列排版，断言 `expected:76.0 but was:221.0`；加 `Box(Modifier.requiredWidth(600.dp))` 后测到目标列数，不计产品 RED。
- MainActivityOrientationManifestTest 首轮 Robolectric 报 `No manifest file found at .\AndroidManifest.xml`，activities 为空（`assertNotNull` 失败）；在 app 与 feature:library 启用 `testOptions.unitTests.isIncludeAndroidResources = true` 并改用 `getPackageInfo(...GET_ACTIVITIES)` 后获得真实 RED。
- 首轮负向对照脚本 XML 正则把自闭合 `<testcase .../>` 与下一个 `</testcase>` 错配，导致 policy/controller 名称错位；改用 `/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g` 重跑 policy-tablet/policy-large 并统一重解析，最终 5/5 均为 PASS_ONE_RED。

## 进度与下一步

<!-- type:progress -->
- 已完成：生产改动（LibraryGridPolicy 新增、LibraryScreen 自适应网格、Manifest 解除竖屏锁、PlayerSystemUiController 平板恢复 UNSPECIFIED）+ 4 个测试 + 5/5 负向对照 + 全量 GREEN（31 suites / 237 tests / 0 failures，BUILD SUCCESSFUL 14m10s，538 tasks executed）+ Draft PR #141。
- 下一步第一步（直接执行）：用 40 位完整 SHA `991af1f2ad02e1b7b1607952b6bc5a9e0196d233` 查询 PR #141 exact-head CI；pending/红不得当 PASS，红则保留原文定位后重跑。
- 独立后续：真机/模拟器验收 #141（平板旋转 + 多列布局 + 播放器退出方向），需先核对同签名与 Room 版本；设备 123e243f 当前真机验收受 Room 6↔5 阻塞，不得清库。
