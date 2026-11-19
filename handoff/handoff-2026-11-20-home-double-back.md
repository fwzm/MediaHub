# Handoff 2026-11-20 首页连续两次返回退出（#4）

## 任务状态（type:state）
- Round 18/256；审计行 #4「新增-首页连续返回两次才退出」已实现并通过本地门禁。
- worktree: D:/deepseek_test/mh-home-double-back-20261120；branch: feature/home-double-back-20261120；base: origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 代码提交: 28f39b1b02f00d4bb62b3b4348a60133929ddd5a（3 files +74/−0）。
- 生产改动: core/ui HomeBackExitPolicy（EXIT_WINDOW_MS=2000，首次返回 ShowHint，窗口内第二次 Exit，越界/负值 ShowHint）；app MediaHubNavHost 在 Routes.HOME 装 BackHandler，提示“再按一次退出”，第二次 finish()。
- 测试: HomeBackExitPolicyTest 5 tests（首次/窗口内/边界/超窗/时钟回退）。
- 证据目录: D:/deepseek_test/mh-home-double-back-evidence-20261120。

## 目标（type:goal）
- 首页按返回键不再立即退出：第一次仅提示，2 秒内第二次才结束 Activity。
- 策略纯 JVM 可测，导航层只做接线，避免与未合并的 HomeScreen 改动冲突。

## 已试方案与失败原因（type:dead-end）
- MSYS 直接执行 ./gradlew：报 “line 215: -Dorg.gradle.appname=gradlew: command not found” 等，wrapper 的 set -- 块被拆散；改用 cmd.exe /c 调用 .cmd + gradlew.bat。
- cmd.exe //c 调用 .cmd：被当成交互式 cmd，只打印横幅与提示符并 exit0；改用 cmd.exe /c（单斜杠）。
- app:compileDebugKotlin 04:50 一次 exit0 但 stderr “The process cannot access the file...”（输出/日志被占，假绿，不采用）；04:51 一次失败于 :provider:local:generateDebugRFile “Couldn't delete ...R.jar”（并发锁，不采用）；采用 04:52 原始任务写出的 green-appcompile.log。
- NC 首轮无失败尝试。

## 进度与下一步（type:progress）
- RED: core:ui 5 tests/2 failed（窗口内、边界两断言），exit1。
- 聚焦 GREEN: core:ui 1m2s/17 tasks，5/5；app:compileDebugKotlin 9m1s/321 tasks，exit0。
- NC: 3/3 PASS_ONE_RED（backwards-clock-window、boundary-exclusive、first-back-exits），每场 exit1 恰好1红，恢复 sha256=234e595b6a65abf59f9907123faf67a0cc71cf0f073120e2372db427c364ef6b。
- 全量 GREEN: BUILD SUCCESSFUL 11m43s / 566 actionable tasks / exit0；94 suites / 926 tests / 0 skipped / 0 failures / 0 errors（app 8/30、core:model 8/71、core:ui 10/62、feature:detail 4/41、feature:home 2/16、feature:library 6/71、feature:player 14/129、feature:server 9/66、feature:settings 33/440）。
- APK: app-debug.apk size=130579351 bytes sha256=741824d69382dc5b562489e3ef6a62cde98b06042692a23c96cf049931a1613e（未安装）。
- 下一步: 推送分支、创建/刷新 Draft PR、写 queue；用 40 位 head 查 exact-head Android CI；真机 NOT_RUN（设备 123e243f 未触碰，Room 6↔5 阻塞，禁清库）；G7 恒 DO_NOT_MERGE。
