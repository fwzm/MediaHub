# 2026-11-19 Round 8：审计行 #144 继续观看/来源选择器线路名

## 任务状态
<!-- type:state -->
- Round 8/256 完成一个垂直切片：把当前生效 ServerEndpoint.name 带进聚合来源与首页继续观看卡片。
- worktree D:/deepseek_test/mh-continue-line-20261119；branch feature/continue-watching-line-20261119；base origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 代码提交 6809fbc1173da1a7f04e3bd504292651cc946ce9（7 files，+178/−10）；Draft PR #150。
- 聚焦 GREEN 4m28s/122 tasks；全量 GREEN 14m9s/538 tasks；debug 合计 775 tests / 11 skipped / 0 failures / 0 errors；APK size=130579351 sha256=ccbd108e68a0c1238714f3a3e9a2a9842dd420f0aeee7047f0faf04acca31eaa（未安装）。
- 负向对照 3/3 PASS_ONE_RED，均从 snapshot 逐字节还原；真机 NOT_RUN；exact-head CI_PENDING。

## 目标
<!-- type:goal -->
- 审计行 #144 的验收语义：来源选择器与继续观看都能区分同服务器的不同线路，而非只显示服务器名。
- 约束：不改数据库 schema、不改导航参数、不改网络协议；endpointName 缺省 null 时完全回退旧标签；本切片只基于 main，不依赖未合并的 #143。

## 已试方案与失败原因
<!-- type:dead-end -->
- 两个 Gradle 任务并行触发 core/logging classes.jar 文件锁（另一个程序正在使用此文件），改为严格串行。
- home fixture 首次同时向 MediaServer 构造器传 baseUrl 与 endpoints，编译失败；属夹具错误，不计产品 RED，改为 .copy(endpoints=...) 后得到真实 2 红。
- 首次 git push 因配置的 credential-manager 不存在而失败（could not read Username）；改用 git -c credential.helper= -c credential.helper="!gh auth git-credential" 推送，未改全局配置。

## 进度与下一步
<!-- type:progress -->
- 已完成：CanonicalSourceOccurrence.endpointName 贯通、SourceSelectorModel 标签、ContinueWatchingLine 纯函数与 HomeScreen 接线；测试与负向对照全部落盘。
- 下一步：用 40 位 head SHA 查询 PR #150 的 Android CI；真机验收受设备 123e243f 的 Room 6↔5 阻塞，禁清库。
- 下一审计候选：审计行 #148 聚合空状态文案（需先定义统一语义，不能直接替换季空态）；或继续在 #143 PR #148 内推进播放页线路选择。G7 恒 DO_NOT_MERGE。