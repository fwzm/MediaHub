type:state
# Round 22 · 播放记录保留清理（#103 部分）

## 目标
审计行 #103「清理播放记录异常」的垂直切片：本地 playback_progress 表此前只有清理 API、没有任何调用方，记录会无限增长。本轮在应用启动时按 90 天保留窗口裁剪旧记录，让过期的“继续观看”条目不再占用数据库。

## 任务状态
- worktree D:/deepseek_test/mh-progress-retention-20261120；branch feature/progress-retention-20261120；base origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 新增 core/database ProgressRetentionPolicy（DEFAULT_RETENTION_DAYS=90，cutoffEpochMs 严格小于截止点，非正窗口 require 拒绝）。
- ProgressRepository.applyRetention(nowEpochMs, retentionDays) 调用 DAO deleteOlderThan 并返回删除数；DAO 返回值由 Unit 改 Int。
- MediaHubApp.onCreate 注入 ProgressRepository，在 SupervisorJob+IO 的进程级 scope 内 runCatching 执行启动清理，失败不影响启动。
- 状态 PARTIAL：只有启动时自动裁剪，没有设置页手动清理入口，也没有播放中的滚动裁剪。

## 已试方案与失败原因
- 首版 NC A 未加 --rerun-tasks --no-build-cache，Gradle 把测试任务判为 UP-TO-DATE，BUILD SUCCESSFUL 属于假绿；加 --rerun-tasks --no-build-cache 后复现恰好 1 红。教训：负向对照必须强制重跑。
- 首版 MediaHubApp 编辑因两条 edit 的 oldText 定位重叠被整体拒绝（status=error），拆成三条独立 edit 后成功。
- 真机未运行：设备 123e243f 未触碰，Room 6↔5 阻塞且禁止清库，启动清理没有真机证据。

## 进度与下一步
- RED EXECUTED_FAIL：:core:database 5 tests/4 failed（策略 3 中 2，仓库 2 中 2），red-20261120.log。
- 聚焦 GREEN EXECUTED_PASS：5 tests/0 failed；:app:compileDebugKotlin exit0。
- NC 3/3 PASS_ONE_RED：默认天数 90→30（策略 1 红）、SQL <→<=（仓库 1 红）、删除 require（策略 1 红）；逐字节还原后 4 个文件 sha256 与快照一致。
- 全量 GREEN EXECUTED_PASS：24m19s/882 tasks/exit0；321 suites/2996 tests/23 skipped/0 failures/0 errors；APK 130579351B sha256=694d9ff5bb2563beffefda8570df2eaef83432dd00579078f212258e0e3d48a9（未安装）。
- 下一步候选：审计行 #84 多线路切换 UI（ServerEditorViewModel 无开放 PR 占用），或给 #103 补设置页手动清理入口（SettingsScreen 被 #161/#152 占用，需等其合入）。
