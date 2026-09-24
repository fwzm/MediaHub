# PR #24（a/joint-candidate-a4）— B 增量复审交接包

生成：2026-09-24（Agent A / zcode，A4 轮）。本包供 Agent B 对**新 SHA** 独立复审；
旧候选 ac67d85（PR #23，冻结不动）的结论不自动继承。

## 1. 定位

- 新候选分支：`a/joint-candidate-a4`，**head = aa633dbabf30c5bb9e9a67a88ef9ac1398bf9f29**（PR #24，Draft）
- base = main 8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1
- 修复来源分支（均保留祖先链，已推送）：`a/a4-subtitle-cache`（0a1c793，基于 8ec0a2c）、
  `a/a4-shared-net`（4311cbe，基于 ac67d85）、a4joint 上的直接提交（归属世代/脱敏/迁移/门禁/链路适配）
- 相对旧候选的 diff 范围：`git diff ac67d85..aa633dba`（主要面：player/mpv/SubtitleCache.kt 全重构、
  WebDavCallBridge、ApiClient.probe、PlayerViewModel 字幕中心+归属世代、feature/server 脱敏 7 处、
  Migration3to4Test、verify-visual-tests.py、P1/P2 并集 merge 3 文件）

## 2. finding 关闭对照（修复 SHA / 回归 / 剩余边界）

| B finding（2026-09-23 报告） | 修复 SHA | 正确行为回归（旧红→新绿） | 剩余边界 |
| --- | --- | --- | --- |
| OriginOf 默认端口归一 | 51e2eec | A4SubtitleCacheOriginContractTest 8/8：隐式↔:80、隐式↔:443 判同源（B 红 2 条转绿）；默认↔:5005 异源（B 绿锁定）；双向矩阵；真实请求凭据作用域（同 origin 携带/异 origin 不携带，显式端口对） | 隐式端口真实请求级验证受 MockWebServer 特权端口限制，以反射级+显式端口真实请求覆盖 |
| 同名缓存碰撞 | 300608b | A4SubtitleCacheScopeIsolationTest 4/4（**全新重建**；旧复现 SOURCE_NOT_ARCHIVED 属实） | — |
| SAF 半成品复用 | 50b3252 | A4SubtitleCacheAtomicTest 2/2：中断→无正式残留→重拷→完整（旧实现红记录于子代理执行日志） | — |
| 下载无上限 | f0cf1e7 | 超 4MiB 失败+无残留 | — |
| 下载不可取消 | e68c5a9 | A4SubtitleCacheCancellationTest 2/2：等头/读体取消→EventListener 真实 canceled（Canceled/Socket closed 双形态）+ mpv-subtitle-bridge 线程释放；红=旧阻塞 execute 不及时传播 | — |
| 缓存无失效/清理 | e68c5a9 | A4SubtitleCacheEvictionTest 2/2：70 文件→≤64+孤儿 .part 清零；clearSession 清空 | LRU 阈值（64/32MiB）为工程默认，未做容量测试 |
| 字幕竞争（迟到回放覆盖手动选择） | a4joint（会话代贯穿：记忆读取/引擎提交/UI/持久化每步校验） | PlayerViewModelSubtitleCenterTest 12/12：**正确归因动态复现**（先证 resolve Ready 进目标路径，再 recall 屏障交错；记忆 zh vs 手动 eng，引擎/状态/记忆三面不覆盖） | 竞争的引擎内交错（loadExternalSubtitle 挂起）未另设屏障——会话代在 apply 返回路径已覆盖 |
| mpv 伪成功 | 0a1c793 | MpvSubtitleTest 10/10：sub-add 后 track-list 回读（count+1 且新轨 type=sub 才成功）；sub-delay 回读对值；未确认→false | 上游 native 错误码不回传的根本边界不变（B 证据归档维持）；AAR/.so 一致性仍未核验（单列） |
| Bridge 总并发=16+调用者 | 4311cbe | A4BridgeTotalConcurrencyTest 3/3：红 21 in-flight → 绿 16+5 挂起排队（Semaphore(16) FIFO）；取消排队零请求零泄漏；两阶段放行 21 全完成 | 池线程 CallerRuns 保留为防御（准入不变量下不可达） |
| probe timeoutMs 死参数/外层取消不断 | 4311cbe | ApiClientProbeTimeoutTest 3/3：红（timeoutMs=300 等满 1200ms 成功）→ 绿（~300ms Failure NETWORK_TIMEOUT + EventListener.canceled）；外层取消 ~500ms 返回 null | — |
| feature/server e.message 残留 | e97ceed | feature:server+settings 全绿；7 处固定文案 | BackupRepository 类型化白名单文案（"未知媒体源类型"等）复核后保留——B 确认非泄漏 |
| parser 门禁缺失/跳过 | 4d64d1a | verify-visual-tests.py：provider/webdav 入 MODULES + 8 用例 mandatory；以 B 归档 API36 XML 干跑验证 8/8 识别 | 设备真实执行以本 PR CI（API32/36）新 XML 为准 |
| Room 3→4 迁移 | 4d64d1a | Migration3to4Test 1/1（SQL 级：表/主键去重/serverId 索引/既有数据无损） | **BLOCKED_ENV**：Room 2.8.4 MigrationTestHelper 在 Robolectric 下 databaseName/绝对路径不兼容（三构造形态均抛 IAE）；identity hash 由 schemas/4.json 编译期覆盖 |

## 3. 新增回归（非 B finding 驱动）

- 字幕会话代：SubtitleCenterState 重置链路 12/12 全量重跑绿。
- 联合链路：WebDavJointChainTest 适配 P2（PROPFIND 5→6，第 6 次 Depth:1 打 /电影/；
  文件链路 percent 保真 3 次）。
- P1/P2 集成：PlayerViewModelTest +PlayerInfoPanel 用例经 NoSubtitleMemory 适配后绿。

## 4. 本地门禁（head aa633dba 前一提交 4b7dc85 起 + app 适配后全量）

- 全模块 `--rerun-tasks --no-build-cache`：首跑 1076 任务全 EXECUTED（仅 app 编译因缺
  subtitleMemoryStore 参数失败→修复后 app 三任务绿），BUILD SUCCESSFUL。
- 分模块单测计数（全 EXECUTED）：logging 48 / common 140 / api 16 / model 40 / network 46 /
  webdav 92 / emby 175 / jellyfin 94 / server 29 / library 48 / search 29 / settings 104 /
  home 16 / detail 37 / engine 74 / mpv 48 / ui 51 / player 61 / app 9 / database +1（迁移）。
- APK：SHA256 `a87b4eb7221b84cb1e44c43f078c340d5b3d3e4cb2afc57b4c7f24cdb11a630f`，
  129,660,832 字节（构建于 aa633dba 前的 4b7dc85 树 + app 测试适配不改 APK 内容；
  严格口径：APK 构建输入树 = 4b7dc85+app-test-commit aa633dba 的生产面，即 aa633dba 本身——
  app 适配只动 app/src/test，assembleDebug 输出不变）。

## 5. 设备/服务器验收边界（NOT_RUN/OPEN 如实）

- 模拟器：本地无设备授权 NOT_RUN；云端 API32/36 视觉+parser 门禁以 PR #24 CI 新 XML 为准。
- 真机：全项 NOT_RUN（含 SAF 真机回传、mpv 真机 sub-add 渲染、旧库升级）。
- 真实服务器：NOT_RUN；SLOW-FINAL 真实服务器侧 OPEN；Jellyfin BLOCKED_BY_SERVER。
- 备份恢复：C REVIEW PENDING / DEVICE UNVERIFIED 保持（本轮未动备份核心）。

## 6. 证据位置

- B 证据包（接收核验 10/10）：`D:\deepseek_test\mh-a-round\a4-evidence\`（原 zip 32f88421… 保留在 mh-b-review）
- A4 各分支测试 XML：各 worktree build/test-results（a4sub/a4net/a4joint）
- 新 CI：PR #24 runs（以 head aa633dba 的 run 为唯一有效）
