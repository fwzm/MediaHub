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

## 7. 勘误（2026-09-25 补记，B 复审必读）

1. **工作区遗留提交事故**：本文档初版所列 head aa633dba **不含**字幕归属世代修复
   （PlayerViewModel 会话代 +44 行）与其回归测试、P1 测试适配——它们停留在工作区
   未提交（历次 commit 只 add 了其他文件），导致本地门禁绿（Gradle 编译工作区）
   而 run 35997370601 三 job 编译失败。更正提交：**26df88e**（归属世代+回归+P1 适配）
   + **77215db**（androidTest 参数适配）。本包 finding 对照表中归属世代一行的
   修复 SHA 以 26df88e 为准。
2. **审查对象更正**：冻结 head = **77215dbd1ed39a98ff26b3f1c59e82ff807e309a**
   （生产代码面 = 26df88e；77215db 仅 androidTest 参数 + 本文档）。
   PR #24 初版的 aa633dba 表述作废。
3. **CI**：run **36065571593** @ 77215db **三 job SUCCESS**（API32/36 视觉含
   parser 8 条 mandatory 门禁 + build 全步）。35997370601 失败为上述事故，留档不删除。
4. **APK 可重现性**：`a87b4eb7…630f`（129,660,832 字节）在 77215db 树上
   --rerun-tasks --no-build-cache 强制重跑 SHA 一致（4b7dc85 时构建亦含工作区
   归属世代代码，故一致）。

## 8. 终审交接增补（2026-09-26，B 终审对象）

**终审 head = 67af94540806be060e61846b2228492d8dd91e0e**（475a9bb → 8dc7c33 → 67af945）。
B 上轮 4 项收尾 finding 的修复与回归（详见 PR #24 正文对照表）：

| B finding | 修复提交 | 永久回归 |
| --- | --- | --- |
| 链路测试响应数量/异步顺序 | 8dc7c33 | WebDavJointChainTest（语义分派；本地 no-daemon 5/5 + CI 云端通过；稳定性全记录：daemon 1 绿+4 文件锁不挑绿） |
| 引擎副作用归属窗口 | 67af945（论证+回归） | MpvSubtitleTest stop 交错：下载挂起+stop → 无 sub-add、false、缓存目录清空；串行边界论证（withNative isCurrent 与 play/stop 共享 stateLock；Main 串行）写入 loadExternalSubtitle KDoc——**交 B 裁定** |
| mpv 确认强度 | 67af945 | MpvSubtitleTest 4 条：目标 filename+selected 命中（含并发无关轨/非末轨）；错误 filename、未选中 → false；未确认不写记忆 |
| clearSession 无生产调用点 | 67af945 | 引擎 play→beginSession / stop→endSession 接线 + stop 交错回归断言缓存目录清空；落地前二次资格检查（迟到下载不重新落地） |

**对账**（唯一用例数，跨 API/重复运行分列）：
- A4 新增永久回归唯一用例：链路 1、SubtitleCenter 12（含归属交错 1）、
  MpvSubtitle 新增 4（目标身份 2 + stop 交错 1 + 原确认 1 改造）、
  Origin 8 / Scope 4 / Atomic 2 / Cancellation 2 / Eviction 2 / BridgeTotal 3 /
  ProbeTimeout 3 / Migration 1。
- 跨 API 执行：parser 设备用例 8 × 2（API32、API36 各一次，CI run 36195276304）。
- 重复运行：链路测试本地 5 次（no-daemon）+ CI 1 次，全部记录非挑绿。
- **不把 NEEDS_REPRO 计为 finding 数**：引擎副作用窗口原 NEEDS_REPRO 状态已由
  串行边界论证 + stop 交错回归取代（交 B 裁定后更新台账）。

**CI**：run 36195276304 @ 67af945 三 job SUCCESS（API32/36 视觉+parser 门禁+build 全步）。
**APK**：`8f2ad2929b04d0d1eb0a5842c7827bcfd3bc7bafdabfcd2f5558b177f64a2e0b`
（129,660,832 字节，干净检出 67af945 构建；旧 a87b4eb2 作废）。
**边界**：physical_device / real_server / old_database_upgrade（真实旧库升级）
NOT_RUN；backup_C 保持 C REVIEW PENDING / DEVICE UNVERIFIED。
