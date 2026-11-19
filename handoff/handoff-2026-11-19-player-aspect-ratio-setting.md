# Handoff — 2026-11-19 — 播放画面比例记忆（#9）

## 任务状态 type:state

- Round 10/256；task_id=player-aspect-ratio-setting-20261119；branch=feature/player-aspect-ratio-memory-20261119。
- 代码提交：6338ed5bb53afe2c7bdf246fb97be913a43e26b8（14 files，+317/−24）。
- Draft PR：https://github.com/fwzm/MediaHub/pull/155（base=main；创建后由 handoff 提交更新 head）。
- 实现：新增 PlayerAspectRatio 四档（FIT/FILL/RATIO_16_9/RATIO_4_3）；UserPreferences 末尾加 playerAspectRatio（默认 FIT）；UserPreferencesStore 新增 player_aspect_ratio key 与读写；PlayerAspectRatioPolicy 纯函数计算显示比例与居中尺寸；PlayerAspectRatioSheet 选择弹层；PlayerScreen 用 BoxWithConstraints 约束 SurfaceView 并新增「画面比例」入口；PlayerViewModel.updatePlayerAspectRatio 持久化；RestoreSnapshotStore codec v3 兼容 v1/v2。
- 验证：聚焦 GREEN 6m40s/242 tasks/exit 0；UserPreferencesStoreTest 8、RestoreSnapshotStoreTest 11、PlayerAspectRatioPolicyTest 7、PlayerAspectRatioSheetTest 1；全量 GREEN 13m41s/546 tasks/exit 0；92 suites / 955 tests / 12 skipped / 0 failures / 0 errors；NC 3/3 PASS_ONE_RED；APK size=130597883 sha256=cd9c4500e57c91aad2c3d0e2610b6312f1cf683a46e067545ba4a7135dbb2825。
- 真机：NOT_RUN（设备 123e243f 未触碰；Room 6↔5 阻塞，禁清库）。

## 目标 type:goal

- 闭环审计行 #9「修复-播放比例记忆失效」：提供适应/拉伸/16:9/4:3 四档画面比例，跨影片记忆，播放页即时切换并只改变 SurfaceView 显示约束。

## 已试方案与失败原因 type:dead-end

- 本轮没有需要回退的生产方案；RED 是刻意先写测试的预期失败（core:database 277/1、settings 440/1、player 8/6），不是产品缺陷。
- 采用字段追加在 UserPreferences data class 末尾、备份 codec 升 v3 的方案，避免 Round 9 曾出现的 RestoreSnapshotStore 位置参数类型错位；本轮全量门禁一次通过。
- 负向对照 runner 第一次启动时因 nc 输出目录尚不存在导致重定向失败；创建目录后重跑，三场景均恰好 1 红，不是产品/测试失败。

## 进度与下一步 type:progress

- 下一步：用 40 位完整 SHA 查询 PR #155 exact-head Android CI（handoff 提交后的最终 head）；结果未出前保持 CI_PENDING，不得当 PASS。
- 下一轮候选：审计行 #148 聚合空状态文案（Search/Library/Detail 语义不一致，需先定产品边界）或按 queue 第一条 READY 继续。
- 证据目录：D:/deepseek_test/mh-aspect-ratio-evidence-20261119（snapshot、nc、focused-green-20261119.log、green-final-20261119.log、pr-body.md）。
