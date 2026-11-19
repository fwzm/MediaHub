# Round 17 · #66 本地/SAF 字幕编码归一化

## 任务状态（type:state）

- Round 17/256，审计行 #66，状态 **PARTIAL**：本地/SAF 导入字幕已覆盖，网络字幕未覆盖。
- worktree `D:/deepseek_test/mh-subtitle-encoding-20261120`；branch `feature/subtitle-encoding-20261120`；base `77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- 代码提交 `754c87378f64436adbebbf972e96d4ff65857911`；Draft PR #169（base=main，未合并）。
- 全量 GREEN exit 0，96 suites / 930 tests / 0 failures；APK 130579351 bytes，sha256 `66f69ddca27e3b30a6baa08b9bd070a63544c1f0f9d150474cbadd5eed5d47c6`。
- 真机 **NOT_RUN**：未安装、未触碰设备 `123e243f`（Room 6↔5 阻塞，禁清库）。

## 目标（type:goal）

- 外挂字幕交给 Media3/mpv 前识别 UTF-8、UTF-16、GB18030(GBK)、Big5，必要时写 UTF-8 缓存副本，消除常见中文乱码。
- 只改导入/本地路径；读取或转码失败必须原样回退，不阻断播放。

## 已试方案与失败原因（type:dead-end）

- 首次 player GREEN 失败：`PlayerViewModelSubtitleCenterTest.kt:936` 期望 `content://fixture/document/7` 实得 null。原因：`prepare` 的 `withContext(Dispatchers.IO)` 在 `runCurrent` 下不完成，selectedExternalId 未写。修复：`ImportedSubtitleResolver` 暴露 `protected open val ioDispatcher`，测试覆写为同一 TestDispatcher；生产默认仍为 Dispatchers.IO。
- NC3 首次用 ViewModel wiring 测试验证 base `prepare`，结果 0 failed：该测试覆写了 `prepare`，绕过真实实现。改为 `ImportedSubtitleResolverTest` 的真实文件 URI 路径后，恰好 1 红。
- 未采用“把 http(s) 字幕也下载转码”：会与 mpv `SubtitleCache`、Media3 的取流路径重复且扩大鉴权/生命周期风险，本轮明确留作缺口。

## 进度与下一步（type:progress）

- 下一步：复核 PR #169 的 exact-head Android CI（使用 40 位 head SHA）；随后从 queue 选下一条 READY 审计项，优先不与未合并 PR 同文件冲突的纯 JVM 切片。
- 已知未合并：PR #167/#165/#163/#161 均 CI_PENDING；本 PR 不得合并，G7=DO_NOT_MERGE。
