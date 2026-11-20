# Handoff 2026-11-20 播放页常亮（#104 切片）

## 目标
type:goal
修复审计项 #104「修复-长时间播放会熄屏」：播放器激活期间持有 FLAG_KEEP_SCREEN_ON，
退出播放器时按进入前状态恢复，避免抢占宿主已有的常亮策略。切片只改
feature/player/PlayerSystemUiController.kt 与新增 Robolectric 契约测试，不触碰 PlayerScreen。

## 任务状态
type:state
实现与验证已完成：RED 1 红（enteringPlaybackKeepsScreenOn 在无实现时失败），聚焦 GREEN 5/5，
NC 3/3 每场恰好 1 红并逐字节还原；全量门禁与 APK 构建结果见下方进度段。
真机长寿播放验证 NOT_RUN（设备 123e243f 未触碰，Room 6↔5 阻塞、禁止清库）。

## 已试方案与失败原因
type:dead-end
未采用「只在 PlayerScreen 加 Modifier.keepScreenOn」：PlayerScreen.kt 被开放 PR #125/#138 占用，
且 Compose 修饰符随页面重组，无法覆盖 Activity 级窗口标志与退出恢复。
未采用「无条件 clearFlags 退出」：会把宿主进入播放器前已有的常亮状态清除，NC-B 已复现该回归。

## 进度与下一步
type:progress
- 分支 feature/keep-screen-on-20261120，base 77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- RED：PlayerSystemUiControllerTest 5 tests / 1 failed，exit1（无实现时仅 enter 断言失败）。
- 聚焦 GREEN：5 tests / 0 failed，BUILD SUCCESSFUL 2m12s。
- NC：A 去掉 enter addFlags、B 退出无条件 clear、C 仅沉浸式才 addFlags，各 1 红；恢复后 sha 一致。
- 下一步：全量 testDebugUnitTest + assembleDebug、提交、Draft PR、queue 回填、exact-head CI。
- 证据目录 D:/deepseek_test/mh-keep-screen-on-evidence-20261120。
