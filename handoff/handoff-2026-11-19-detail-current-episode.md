# Handoff — 2026-11-19 — 详情页当前集定位（#13）

## 任务状态 type:state

- Round 12/256；task_id=detail-current-episode-20261120；branch=feature/detail-current-episode-20261120。
- 代码提交：23f431640f82d28e56c97bb9947155e76c6c788b（5 files，+282/−8）。
- Draft PR：待创建（base=main；创建后由 handoff 提交更新 head）。
- 实现：新增 DetailResumePolicy（isInProgress/currentEpisodeId/findInProgressSeason）；SeriesBrowseState 新增 currentEpisodeId；DetailViewModel 默认季立即展示后按季扫描其他季，找到正在观看剧集时切季并高亮；DetailScreen 用 scrollState + onGloballyPositioned 定位并高亮“继续”行；详情页不依赖 ProgressRepository。
- 验证：RED 25 tests / 5 failed（DetailResumePolicyTest 3 红、DetailViewModelTest 2 红）；聚焦 GREEN 2m53s/68 tasks/exit 0（25 tests，0 failures）；NC 3/3 PASS_ONE_RED；全量 GREEN 13m25s/568 tasks/exit 0；121 suites / 1184 tests / 12 skipped / 0 failures / 0 errors；APK size=130595735 sha256=e6c0e164a045ecf844e7a93cbef3a08ed0083ae5c9bc9cd80bdd816e388153bc（未安装）。
- 真机：NOT_RUN（设备 123e243f 未触碰；Room 6↔5 阻塞，禁清库）。

## 目标 type:goal

- 闭环审计行 #13「修复-进详情页未定位当前集」：用户从继续观看/外部入口进入剧集详情时，按正在观看的剧集反查所在季，自动选中该季并滚动到当前集，不再总是停留在第一季顶部。

## 已试方案与失败原因 type:dead-end

- 首轮编译在 DetailResumePolicy.kt:18 报 Operator call is prohibited on a nullable receiver of type Long?：UserData.playbackPositionMs 是 Long?；改为 (userData.playbackPositionMs ?: 0L) > 0L 后通过。
- 本轮没有需要回退的生产方案；RED 是刻意先写测试的预期失败。
- 负向对照 3 场景均恢复到实现态；每场景恰好 1 红，快照 sha256 无 mismatch。

## 进度与下一步 type:progress

- 下一步：用 40 位完整 SHA 查询 PR exact-head Android CI（handoff 提交后的最终 head）；结果未出前保持 CI_PENDING，不得当 PASS。
- 下一轮候选：审计行 #24 海报高度分档、#28 菜单副标题黑底对比度，或按 queue 第一条 READY 继续。
- 证据目录：D:/deepseek_test/mh-detail-resume-evidence-20261120（snapshot、nc/summary-all.json、focused-green-20261120.log、green-final-20261120.log、pr-body.md）。
