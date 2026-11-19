# Handoff 2026-11-19 — 播放页聚合来源与线路切换（#143）

## 任务状态（type:state）
- 分支 `feature/player-source-aggregation-20261119`；worktree `D:\deepseek_test\mh-player-sources-20261119`；base `origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`
- 代码提交：`d55ac52b9759e97b0cf0d22ada2b308c3fc56840`（21 files，+690/−13）
- 实现：core:model `PlaybackSourceOption`、core:common `PlayerSourceHandoff`、feature:detail `PlayerSourceOptions`、feature:player 线路面板/选择策略/起播位置策略、app `PlayerRouteBuilder`
- 验证：RED 四组真实失败；聚焦 GREEN；8/8 负向对照恰好 1 红并从快照逐字节还原；全量门禁 BUILD SUCCESSFUL 13m23s / 547 tasks / exit 0；APK 已产出未安装
- 真机验收：NOT_RUN（设备 123e243f 受 Room 6↔5 阻塞，禁清库）
- PR：#148（Draft，G7 = DO_NOT_MERGE）

## 目标（type:goal）
- 把详情页聚合出的多来源保留到播放阶段：DetailViewModel 在聚合完成后写入进程内 handoff
- 播放页仅当存在至少 2 个服务器时展示「线路」入口；选择线路后用新 player route 替换当前播放并保持当前 positionMs
- 起播位置优先级：显式 start 参数 > 续播 progress > 0

## 已试方案与失败原因（type:dead-end）
- 把 sources JSON 放进 navigation args：违反 ADR-038「occurrences 不进 navigation args」→ 改为 `PlayerSourceHandoff` 进程内传递
- 直接渲染 ModalBottomSheet 做 Robolectric 点击测试：assert 能过但点击不回传 → 拆出 `PlayerSourceSheetContent` 单独测试
- `PlayerRouteBuilderTest` 按 itemId 明文断言：实际 itemId 经 NavArgCodec 编码（item-1→aXRlbS0x）→ 断言改用编码值
- 负向对照 runner 用 cmd 重定向日志：报「文件名、目录名或卷标语法不正确」且 100ms 退出 → 改为 spawnSync + cwd/env，由 Node 写日志

## 进度与下一步（type:progress）
- 全量测试：core:common testDebugUnitTest 686 / testReleaseUnitTest 686（11 skipped each），feature:detail 44，feature:player 138，app 32；全部 0 failures / 0 errors
- APK：`app/build/outputs/apk/debug/app-debug.apk` size=130596167，sha256=`3513751b9141f2d234e53ea9767ba5836d474be037e827aebd2beadee44ef5b3`
- 下一步：用 40 位完整 SHA 查 #148 exact-head CI（pending 不得当 PASS）
- 后续：真机验收仍受 Room 6↔5 阻塞；禁止 adb uninstall / pm clear / 清用户数据
