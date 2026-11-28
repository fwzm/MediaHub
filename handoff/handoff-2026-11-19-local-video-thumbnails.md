# 交接账本 · 2026-11-19 · #92 本地视频封面

## type:goal 目标

- 审计行 92「本地视频缩略图/封面」在 origin/main 完全未实现：本地文件树与 SAF 树的 `MediaItem.posterUrl` 均为 null。
- 本轮只做一个垂直切片：让本地视频拥有封面来源，由 Coil 在展示时抽首帧；目录/音频不得伪造封面。

## type:state 任务状态

- 分支 `feature/local-video-thumbnails-20261119`，worktree `D:/deepseek_test/mh-local-thumbnails-20261119`，base `origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- 代码提交 `9d1bed2ebea2c57d75cd04571393315de7625e96`（9 files，+194/−1）。
- 生产改动：新增 `LocalPoster.forItem(type, uri)`（仅 `MediaType.VIDEO` 且非空 URI 返回 uri）；`LocalProvider.File.toMediaItem` 与 `SafLocalAccess.mapped` 接线 `posterUrl`；版本目录与 app 增加 `coil-video` 依赖；`MediaHubApp.newImageLoader` 注册 `VideoFrameDecoder.Factory()`。
- 测试：新增 3 个测试文件 5 例；`:provider:local:testDebugUnitTest` 7 suites/31 tests、`:app:testDebugUnitTest` 8 suites/30 tests，合计 61 tests / 0 failures / 0 errors / 0 skipped（`:app:assembleDebug` 同时成功）。
- 负向对照：3/3 PASS_ONE_RED（file-provider-poster、saf-provider-poster、helper-non-video-filter），每处恰好 1 红；脚本结束哈希校验 mismatches=[]。
- 证据：`D:/deepseek_test/mh-local-thumbnails-evidence-20261119/`（green-final-20261119.log、nc/summary-final.json、snapshot/SHA256SUMS.txt）。
- 真机：NOT_RUN。未安装 APK、未触碰 123e243f。
- 封面真实解码：NOT_RUN / SOURCE_REVIEWED。JVM 只能证明 URI 接线与 Coil API 编译；`VideoFrameDecoder` 的实际抽帧必须在可用设备或模拟器上看图确认。

## type:dead-end 已试方案与失败原因

- 首次 SAF 测试用只实现 `LocalRootProvider` 的假 root，`LocalProvider.documents` 在 `SafLocalAccess.kt:10` 抛 `ProviderException$DirectoryAccessRequired`；原因是 `SafLocalAccess.documents` 要求 `roots as? SafTreeNavigator.SafSource`。改为同时实现两个接口后 5 例全绿，非生产缺陷。
- 负向脚本首轮把自闭合 `<testcase .../>` 与下一个 `</testcase>` 错配，导致 helper 场景被判 REVIEW；改为 `/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g` 后重跑得到 PASS_ONE_RED，不是实现问题。
- 未采用「把视频文件复制/截图落盘」方案：会产生额外磁盘写入和清理责任；Coil `VideoFrameDecoder` 已能直接读原 URI。

## type:progress 进度与下一步

- `#133` 轨道反馈 PR 的 exact-head CI 已出结论，非 PASS：run `44469719346` / head `bd82ecbb9a8c23c27642d6e344f217b0b679532c`；API 32 job `135376041120` failure、API 36 job `135376041426` success、build job `135384300833` failure（gate: VISUAL_RESULT=failure）。失败测试为 `com.mediahub.app.backup.BackupUserFlowNonOverwritingDeviceTest.exportCancelSaveWrongPasswordAndUnconfirmedReplaceAreNonOverwriting`，与本轮 provider 改动无关，但队列必须记 CI_FAILED，不能当 PASS。
- 设备安装仍受 `mediahub.db user_version=6` 与 main `AppDatabase=5` 的 Room 降级风险阻塞；不得清库或降级。真机/模拟器验收需先解决版本兼容或使用全新数据目录。
- 下一步：推送本分支并开 Draft PR；队列插入 `local-video-thumbnails-20261119`（status=EXECUTED_PASS，device=NOT_RUN，decode=NOT_RUN/SOURCE_REVIEWED）；下一候选优先「干净模拟器上验证封面实际渲染」或审计行 107 服务器占位 logo。G7 恒 `DO_NOT_MERGE`。
