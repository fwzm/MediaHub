# 2026-11-11 CI 契约修复交接

## 状态

本轮基线为 `65cdfc60fe5d782c8fd8c76608610f338e53bd1e`，分支为 `codex/integrate-archive-ui-20261026`，Draft PR 为 #72。远端 main 为 `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`。fetch 后 Windows 分支前移至 `09ea1972`，同步分支前移至 `b937e39c`，这些新增内容需要重新审计。

基线 Android run `40983110070` 已 completed failure。单元报告包含 2821 tests、2 failures、0 errors、23 environment skips。两个失败都在 `MpvAudioTrackSelectionTest`。API32 和 API36 各有 139 个唯一用例，137 passed、1 failed、1 skipped，失败都在 SAF 流程第 160 行。Windows run `40983110075` completed success。

## 目标与改动

先修复当前候选的确定性 CI 失败，取得新提交的单元与仪器结果后继续分支整合。

SAF 测试把信息和字幕入口的 matcher 改为实际 `TextButton` 使用的文字加 click action。生产组件给这两个入口设置的是 Text。原 matcher 用 content description，无法命中。上一交接把信息入口超时归因于 IME 覆盖，证据不足，本轮按实际组件语义更正。

mpv 测试夹具初始化 track-list 时同步对应的 `aid`。原夹具同时报告首轨 selected=yes 与 aid=no，导致两个预期选中首轨的断言失败。新增引擎反例仍单独覆盖 aid=no 与旧 selected=yes 并存时不得宣称选中。

## 已执行验证与边界

本地原始 `MpvAudioTrackSelectionTest` 12 tests、2 failures，exit 1。修复后的 mpv 全模块以 `--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1` 执行，129 tests、0 failures、0 errors、0 skips，57 tasks executed，exit 0。新反例实际执行通过。

SAF instrumentation 编译命令为 `:app:compileDebugAndroidTestKotlin --rerun --no-build-cache --no-daemon --no-parallel --max-workers=1`。实际 exit 0，347 tasks 中 2 executed、345 up-to-date，耗时 4m55s。该结果覆盖编译，两个 API 的运行验收仍待 CI。

初次本地命令因为 PowerShell 拆分未引号包裹的 Kotlin Gradle 参数而在任务解析阶段失败。保留 `mpv-selection-red.log`，修正参数后得到上述真实 RED 与 GREEN。

原始日志、JUnit XML、两个 API 的截图与 UI dump 保存在本机 `D:\deepseek_test\mh-main-readiness-evidence-20261111`。SAF 的运行结论以新提交的 API32/API36 CI 为准。设备列表为空，真机验收为 BLOCKED_ENV。

## 下一步

将两项测试修复与本交接一起提交到 feature 分支，更新 Draft #72 和外部 queue/RUNLOG/TEST_MATRIX。查询新提交对应的 Android 与 Windows run，下载实际 XML 复核 SAF、备份、mpv 和整体单元门禁。后续仍须覆盖远端 Windows/同步新增内容及 main-facing 候选整合。G7 保持 DO_NOT_MERGE。
