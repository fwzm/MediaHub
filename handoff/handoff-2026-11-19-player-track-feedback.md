# 2026-11-19 #80 轨道选择失败反馈

## 状态 <!-- type:state -->
- worktree `D:/deepseek_test/mh-track-feedback-20261119`，分支 `feature/player-track-feedback-20261119`，base `origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- 代码提交 `d5bdbc1129965d3600ca5d227ce9eeb4e7f2a2b1`（16 files, +438/−124）。
- Draft PR **#133** https://github.com/fwzm/MediaHub/pull/133，OPEN/Draft，base=main，G7 `DO_NOT_MERGE`。
- 最终单测：4 模块 **67 suites / 486 tests / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESSFUL 4m1s，439 tasks executed，exit 0；两个 androidTest 编译任务通过。
- 真机：**NOT_RUN**（未安装 APK、未触碰 123e243f）。

## 目标 <!-- type:goal -->
修复审计项 #80「轨道选择失败静默」：选择音轨/字幕轨时，快照过期、UI 行无法映射地址或引擎拒绝，原本静默关闭弹窗；现在必须给出可见反馈，且不得误清外挂字幕或写字幕记忆。

## 已试方案与失败原因 <!-- type:dead-end -->
- 首次负向对照脚本用 `spawnSync('cmd.exe', ['/c', cmd])` 传含引号的 `--tests "class"`，Gradle 报 `No tests found for given includes`，首场景 REVIEW；改为 `--tests=<无空格通配>` 后 9/9 通过。
- `MpvPlaybackEngine.withCurrent` 改为返回 Boolean 后，四个 observer 的 expression body 报返回类型不匹配导致首次编译失败；改 block body 后通过（保留为 EXECUTED_FAIL 记录）。
- 负向场景 `mpv-success-returns-false` 首轮 expectName 误填为 foreign snapshot，仅 REVIEW；修正测试名后单场景复跑 PASS_ONE_RED。
- UI Snackbar 在 JVM 无 Compose 屏幕测试，只能编译 + SOURCE_REVIEWED，不能宣称 UI 已实测。

## 进度与下一步 <!-- type:progress -->
- 已完成：Boolean 引擎契约（Port/Media3/MPV/Switchable）；VM `TrackSelectionIssue` + 两个统一入口；Snackbar 接线；中英文字符串；8 个生产文件 + 8 个测试文件；9 个负向对照全部恰好 1 红并哈希还原。
- 下一步：① 真机验收 #133（选择失败提示路径需构造 stale/rejected 场景）；② 真机验收 #130 播放完成自动退出；③ 审计下一条 READY（例如 #43 编辑媒体库对已开页面不生效）。
- 约束：G7 恒 `DO_NOT_MERGE`；只 push feature 分支；未写 main、未 merge/close/mark-ready。
