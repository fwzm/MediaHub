# Handoff — Round 21 #80 轨道选择反馈

type:state
- 状态：实现、RED、聚焦 GREEN、3 处负向对照、全量门禁、APK 均已完成；代码提交 351448e7cea71ef777c00e1c20a23abd78c459b9（14 files +217/−50）。Draft PR 待创建。
- 分支 feature/track-selection-feedback-20261120，base origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7，worktree D:/deepseek_test/mh-track-selection-20261120。

type:goal
- 审计行 #80「修复-播放部分视频轨道读取失败」：引擎选择失败不再静默 return，而是返回 TrackSelectionResult 并由播放页显示 2.6 秒提示。
- PARTIAL：只覆盖手动音频/内嵌字幕选择失败反馈；外挂字幕加载失败与自动轨道回退不在本轮。

type:dead-end
- 首版全量构建 :player:mpv:compileDebugKotlin 失败（Unresolved reference TrackSelectionResult，10 处），原因是 MpvPlaybackEngine 缺 import；补 import 后 :player:mpv:compileDebugKotlin 1m17s 通过，再跑全量成功。
- 字幕拒绝测试最初先手动选外挂字幕，manualSelection 已为 true 导致 assertFalse 假红；改为不预选外挂后通过。

type:progress
- RED：:player:engine 5 tests/3 failed；:feature:player 34 tests/2 failed（日志 red-engine.log、red-vm.log）。
- 聚焦 GREEN：engine 5/5，BUILD SUCCESSFUL 1m49s；VM 34/34，BUILD SUCCESSFUL 4m。
- 负向对照 3/3 PASS_ONE_RED：mapped-null-applied（engine 5/1）、audio-no-notice（VM 34/1）、subtitle-takes-over（VM 34/1）；每场 exit1 恰好 1 红，逐字节恢复 sha 一致，summary nc/summary-all.json。
- 全量：testDebugUnitTest assembleDebug BUILD SUCCESSFUL 21m25s / 882 tasks / exit0；201 suites / 1986 tests / 11 skipped / 0 failures / 0 errors（含 :player:engine 26/193、:player:mpv 19/129；另跑 :core:model:test 8/71、:provider:api:test 7/29，BUILD SUCCESSFUL 49s）。
- APK app-debug.apk 130579351 B sha256=eab25b3bf18ba6de2be6f135a1053905b6952483718649f374ac163e5a8d3e57（未安装）。
- 真机 NOT_RUN（未触碰设备 123e243f；Room 6↔5 阻塞，禁清库）。
- 下一步：查 exact-head Android CI（40 位 SHA），success 才回填；下一轮从审计表取未冲突项（#84 需避 #167，#87/#45 避 #171，#52/#99/#35 避 #173）。