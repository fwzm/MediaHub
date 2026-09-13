# Agent C 第二批（R0–R9）执行账本 — 2026-09-13

受审版本 `82a38abce4e761c002dde6d8c9420176e977f57c`（PR #18 head，base `8e516e4…`）。
执行前重新 fetch：main / PR #18 head / PR #16 head / PR #10 head 与任务快照**逐项一致，无漂移**；
C 两条分支本地与远端 ref 一致（`agent-c/batch-2026-09-13` @ `c75ecd0`、`agent-c/pr16-verify-2026-09-13` @ `45afb87`）。

## 包级状态总表

| 包 | 状态 | 本轮实际产出 |
| --- | --- | --- |
| R0 记录更正 | **EXECUTED_PASS** | 见 §R0 |
| R1 F-C1-1 分层回归 | **EXECUTED_PASS（红灯证据已取得）** | 2 provider × 4 测试，3 红 1 绿 |
| R2 真机夹具安全边界 | **PARTIAL** | 夹具按安全契约重写；真机执行仍被 HyperOS 选择器阻塞 |
| R3 C4 真实并发交错 | **EXECUTED_PASS** | 固定种子有界交错压力测试通过 |
| R4 C6 慢 final 复现 | **NOT_STARTED** | 未执行（见 §R4 恢复点） |
| R5a F-C1-2 脱敏复现 | **EXECUTED_PASS（红灯）** | 用户可见错误回显主机名已复现 |
| R5b C7 取消测试 | **NOT_STARTED** | 未执行 |
| R6 2A 选轨夹具 | **WAITING_FOR_OWNER + PARTIAL** | B 未交付（0 提交/未推送/无 PR）；ffmpeg 8.1.1 可用，夹具未生成 |
| R7 PR #10 非真机复审 | **EXECUTED_PASS（CI 产物审计）** | API 32/36 产物逐条审计；**未亲自运行设备** |
| R8 Jellyfin 协议 | **NOT_STARTED（真服子项 BLOCKED_ENV）** | 未执行 |
| R9 接回与交付 | **EXECUTED_PASS** | 本档 + JSON + receipt + A 修复任务包 |

## R0 — 交付状态与机器记录更正（EXECUTED_PASS）

已修正旧 JSON 中核实出的 4 处不一致：

1. `cBranches.batch.head` 由短 SHA `6f222db`（第一轮三个提交中的第一个）更正为交付 tip `c75ecd0…`，并加 `headCorrection` 说明。
2. `c2.status` 由「local re-run IN PROGRESS」更正为 `COMPLETED`（`localGate` 早已记录完成，两者矛盾）。
3. `generatedUtc` 增加 `generatedUtcSemantics`：它是**文件创建时间**，后加入的事件（本机门禁 10:58:20Z 结束）晚于它；
   各事件的权威时间以各自字段为准，另加 `contentFinalisedBy` 指向 `c75ecd0`。
4. 「零阻断 / 代码通过」与未闭环的 F-C1-1 分离：`c1.blockingFindingsSemantics` 说明该字段只统计**四组不变量**是否受阻；
   另立 `c1.pr18IntegrationVerdict = PATCH REQUIRED`、`c1.deviceVerification = DEVICE_UNVERIFIED`。

同时：包级状态词表统一为 SOURCE_REVIEWED / EXECUTED_PASS / PARTIAL / WAITING_FOR_OWNER / BLOCKED_ENV /
NOT_RUN_POLICY / NOT_STARTED；源码结论一律标 `SOURCE_READ` 证据级别；
把「本次未识别到死锁路径」从「不存在死锁路径」改正，并补明线路身份推导的三项前提（候选筛选 / 等价关系 / DAO 排序）。
**提交文件内不写入其自身尚未生成的 commit SHA**；最终交付 SHA 与各文件哈希记在提交后生成的外部 receipt。

## R1 — F-C1-1 分层回归（EXECUTED_PASS，取得红灯证据）

### 三层边界与措辞更正

| 层 | 断言 | 措辞 |
| --- | --- | --- |
| L1 原始 OkHttp 边界（`MediaHttpClient.okHttpClient().newCall().execute()`） | 必须**以 IOException 或其兼容子类**失败 | 原始同步 `Call.execute` 等价边界 |
| L2 `MediaHttpClient.probe` | 必须**返回** `MediaProbeResult.Failure` | probe 自身承担异常→结果转换，**不断言「必须抛 IOException」** |
| L3 `ApiClient.probe` | 必须**返回** `ServerProbeResult.Failure` | 同上 |
| 每层 | 失效后**旧地址请求数为 0** | 不出网 |

### 实测结果（原始生产 head，仅新增测试文件）

| 模块 | 测试 | 结果 |
| --- | --- | --- |
| `provider/emby` | `EmbyIdentityGuardFailureLayerTest` | tests=4 **failures=3**：L1/L2/L3 红；positive control **PASS** |
| `provider/jellyfin` | `JellyfinIdentityGuardFailureLayerTest` | tests=4 **failures=3**：L1/L2/L3 红；positive control **PASS** |
| `core/network` | `EndpointTestServiceRedactionTest`（R5a） | tests=1 **failures=1** 红 |

三处红灯的实际异常完全一致：

```text
java.lang.IllegalStateException: 媒体源身份已变化，请重新打开媒体源
```

- L1：`原始 HTTP 边界必须以 IOException（或其兼容子类）失败，实际为 java.lang.IllegalStateException`
- L2：`MediaHttpClient.probe ... 不得向调用者抛出未映射的运行时异常，实际抛出 java.lang.IllegalStateException`
- L3：`ApiClient.probe ... 不得向调用者抛出未映射的运行时异常，实际抛出 java.lang.IllegalStateException`

**为什么旧测试没发现**：仓库自带 `*FactoryRestoreIsolationTest` 只用 `runCatching { … }.isFailure` 断言，
接受任意 Throwable，**不约束失败类型**；本次严格版测试在同一场景下立刻变红。

**正向对照（已通过）**：撤销后新建 handle 可正常工作（library 请求成功）、其他来源不受影响（media 探测成功）、
被撤销 handle 的旧地址请求数保持 0。说明红灯特异于「被撤销 handle 的失败出口」，不是夹具噪声。

证据 XML：`agent-c-evidence/r1-r3-r5/r1-emby-layer.xml`、`r1-jellyfin-layer.xml`（各 4 用例 / 3 失败）。

## R2 — 真机夹具安全边界（PARTIAL）

### 已完成的夹具整改

原 `BackupUserFlowHarmlessTest` 的名称与行为不符，已**删除**，替换为
`app/src/androidTest/.../BackupUserFlowNonOverwritingDeviceTest.kt`：

1. **写入登记**：文件头 DATA CONTRACT 逐条登记本测试的全部写入（1 条自有 id 的 server 行、1 条其 progress 行、最多 1 个
   任务名文件）；明确它**不覆盖任何既有行、不写偏好、不写凭据**。标签 `PRODUCTION_FLOW_WITH_TEST_DATA` — 真实生产链 +
   测试端插入数据，**不冒充原用户数据实例验收**，也不用假导出器冒充生产链。
2. **只读前置检查**：进入任何页面操作前检查「无待续作 / 无损坏 / 无孤儿快照」——直接读 journal SharedPreferences 与
   `shared_prefs/mediahub_restore_journal.xml(.bak)` 文本、`noBackupFilesDir/restore-snapshots` 条目数；
   **不调用 `recoverInterruptedRestore`**（那是执行恢复，不是检查）。前置不成立即停并报 BLOCKED，**不自动清理日志或快照**。
3. **密码**：改为每次运行 `SecureRandom` 生成的 32 字符密码，仅存在于内存 char[]，finally 擦除，不写日志/持久化；
   **不再使用仓库中固定且公开的测试密码**。
4. **零写入校验**：比较**全量业务状态 + 凭据状态**（全部 servers、全部 progress、完整 UserPreferences、三个凭据 shared_prefs
   的键名集合、token 是否存在），只排除本测试自有的行；**凭据原值不进入报告**。
5. **并发写入**：发现用户/后台导致的状态差异时判 **INCONCLUSIVE**，**不用旧快照覆盖回去制造「状态相同」**。
6. **清理**：只删除本轮明确创建、可验证归属的行与**精确文件名**，无通配删除。

### 仍未完成

真机执行仍停在 HyperOS 文件选择器。本轮已从失败现场取到真实 picker 层级（`agent-c-evidence/c3/picker-hierarchy-run3.xml`）：
实际包为 `com.google.android.documentsui`，**默认已在「下载内容」目录**，界面用的是 `dir_list` / `item_root` /
`header_title` / `breadcrumb_text`，**没有 AOSP 的 `roots_list`**。适配器已按此改写为「先看 header/breadcrumb，已在 Downloads
就跳过根抽屉；否则开根抽屉并在 `roots_list` 或 `dir_list` 内按文本定位；不盲猜坐标」。该改写**未在真机上重新执行验证**。

另：本轮曾一度怀疑 Downloads 残留 `MediaHub-backup-20260913-190621.mhb`；经复核该字符串是**保存对话框里预填的文件名输入框内容**，
`/sdcard/Download` 下当前**无任何 MediaHub* 文件**，无残留、无清理缺口。

破坏性部分（确认替换、故障注入、8 个进程终止点、损坏日志）状态 **NOT_RUN_POLICY**，未运行，也不改换设备执行。

## R3 — C4 真实并发交错（EXECUTED_PASS）

新增 `EmbyAuthenticationInterleavingStressTest`：固定种子 `20260913`、**16 轮**有界交错（非无界 advanceUntilIdle），
每轮用真实 `EmbyAuthProvider` + `MockWebServer` 闸门 Dispatcher：请求到达服务端（`CountDownLatch`）后，
按种子决定是否执行 `withRestoreIdentityChange` 撤销，再放行响应。

**三类事实分别断言**：
- NETWORK：正常路径 `requestCount == 1`（证明请求真的出网）；
- BACKGROUND：后台登录协程 `isCompleted`；
- STATE COMMIT：token/session 是否落库（撤销轮必须失败且不落库）。

**本轮结果：1 用例 PASS，0 失败**（16 轮全部通过）。所有失败会累积上报而非首错即停；资源在 finally 释放。

既有覆盖（本机取证确认在 CI 与本轮 XML 中通过）：`TokenStoreAuthenticationTest`、Emby/Jellyfin `AuthProviderTest` 的
在途登录 + 迟到响应、撤销后旧/临时 provider 零请求、普通登出后再登录、以及 `*FactoryRestoreIsolationTest` 的零请求断言。
**未识别到需要新生产接缝的缺口**；本次未修改任何生产代码。

## R4 — C6 慢 final 复现（NOT_STARTED）

未执行。恢复点（可直接开工）：在 `player/engine` 或 `core/network` 的 JVM 测试中，用 `MockWebServer` Dispatcher 构造受控慢
`Playing`/`Progress`/`Stopped`，复用生产 `ApiClient`/Provider/Coordinator 边界，并在单调时钟上记录
`engine.stop` / `coordinator.stop` / `flushFinal` / 各端点请求响应 / 取消来源 / release；分别断言本地 final 保存、周期上报终止、
旧 session 不重开、资源释放与既有退出预算。注意 `flushFinal` 的 2000ms 预算与同步 `Call.execute()` 不可中断是两个独立事实。
不扩大超时、不补重试、不重写 finality。

## R5 — F-C1-2 脱敏复现（EXECUTED_PASS，红灯）与 C7 取消（NOT_STARTED）

新增 `core/network/EndpointTestServiceRedactionTest`：用合成 user-info + 敏感 query 的地址令 OkHttp 在解析期失败，
使异常文本含完整 URL，再断言**用户可见**的 `EndpointTestResult.error` 不含主机名、user-info、query 凭据。

实测红灯：

```text
用户可见错误不得回显主机名；实际=API test failed: secret-host.example
```

即 `EndpointTestService` 把原始异常文本透出到用户可见字段，与项目其余边界（`ApiClient`/`MediaHttpClient` 用 `Redactor`、
`BackupFileStore` 用固定文案）不一致。修复方向：固定文案 + 类型化结果，**CancellationException 保持取消语义**不转普通失败；
属当前生产负责人范围，C 不顺带重构网络层。

**C7 取消测试未执行**：连接等待、读体等待、取消后新请求、迟到返回、页面退出、重复请求等场景未跑；
因此**不声称**底层 `Call` 已被取消或网络资源已释放。

## R6 — 2A 选轨（WAITING_FOR_OWNER + PARTIAL）

查询结果（本次实测）：
- 分支 `codex/fix-media3-track-selection` **仅存在于本地**，`git rev-list --count main..branch` = **0**，即**无任何超越 main 的提交**；
- **未推送到 origin**（`git ls-remote --heads origin` 无匹配）；
- `gh pr list --state all` **无任何**引用音轨/字幕选轨的 PR。

故「B 已交付修复」不成立；只阻塞「修复后复验」，不阻塞夹具准备。
工具就绪：**ffmpeg 8.1.1 可用**（`…\WinGet\Links\ffmpeg.exe`），可生成多音轨/多字幕/无字幕合成片。
**本轮未生成夹具、未写基线失败测试**。恢复点：用 ffmpeg 生成可区分多轨合成片并记录生成命令/属性/哈希，
再在本机/Robolectric 覆盖 trackType/rendererIndex/groupIndex/trackIndex/UI 标识与实际选择映射。

## R7 — PR #10 非真机复审（EXECUTED_PASS，CI 产物审计）

**声明：本节审计的是 CI 产物，C 本轮没有亲自运行 API 32/36 设备。**

PR #10 CI run `33971603657`（head `3febdf8b77…`，base `0e08fadb…`，conclusion success）。
下载并审计 `visual-api-36-…` 与 `visual-api-32-…` 两个产物：

| 版本 | revision.txt | 用例 | 通过 | 跳过 |
| --- | --- | ---: | ---: | --- |
| API 36 | head=3febdf8b77… base=0e08fadb… event_sha=fb7998a8… | 6 | 5 | `FlowGlowRuntimeShaderTest#pre33FallbackProducesPixelsWithoutRuntimeShader` |
| API 32 | 同上 | 6 | 5 | `FlowGlowRuntimeShaderTest#runtimeShaderCompilesAcceptsEveryUniformAndProducesPixels` |

两个版本的跳过是**互补且正确**的：API 36 跳过 pre-33 回退路径（该路径由 API 32 执行），
API 32 跳过 RuntimeShader 编译（需 API 33+，由 API 36 执行）。引述数量时**不能把 skipped 计入通过**。

CI 覆盖到 R7 要求的非真机路径：Off/隐藏/停止/销毁时无帧循环（`FlowGlowCompositionClockTest#disabledHiddenStoppedAnd
DisposedCompositionsOwnNoFrameLoop`）、强制回退不采样音频、pre-33 回退、RuntimeShader 全 uniform 编译并出像素、
字幕安全带外的环境光边界、palette 过渡不泄漏到播放器外。

**仍未验证**：偏好持久化与恢复默认的端到端、正式设置页入口接线、PR #10 视觉偏好与 PR #18 备份/保护快照/回滚模型的
交叉集成测试（未提出，因为需要先按既有契约判断字段是否应备份）。本轮**未重做视觉功能、未做临时集成、未声明 FPS/功耗/HDR/DRM 或真实 FFT**。

## R8 — Jellyfin 协议（NOT_STARTED；真服子项 BLOCKED_ENV）

未执行。真服子项 `BLOCKED_ENV`（无已授权实例，未接触生产账号、未新购资源、未开放端口）；
**协议核验与 MockWebServer 契约测试子项单独排队**（非阻塞）。恢复点：按当前版本核对
`PlaybackInfo.PlaySessionId` → 播放源 → 两引擎进度 → 三个上报 DTO 的完整传播，fixture 可追溯到协议版本与检索日期；
不从 Emby 失败推导 Jellyfin 必失败。

## R9 — 接回与交付（EXECUTED_PASS）

- PR #16 更正提交 `45afb870415103e4070d3d90188c07885b60fa4d` 已在 C 自有分支，本轮**未改动其内容**，
  作为最小交接包供 A 复核接回（更正证据索引、披露 GET 502、限定 UserData 结论；**SLOW-FINAL 保持 OPEN**）。
- F-C1-3（markFailed 无生产调用点）保留为**设计观察**，本轮**未**为了消除观察项增加会失败的持久写入；
  F-C1-4（orphan GC）保留**已接受限制**，本轮未自动删快照、未为日志恢复增加绕过开关。
- 统计与结论来自本轮 XML，用例身份含模块/类/用例名。
- **原 head 的 772 项门禁已执行（第一轮）**，本轮为纯测试/fixture 增量，未重复消耗整轮全量构建。
- PR #18 暂保持 **PATCH REQUIRED / C DYNAMIC VERIFICATION PARTIAL / DEVICE_UNVERIFIED**；
  F-C1-1 修复后需对真实新 head 复跑本回归及受影响共享测试，再更新代码结论。USB 子集的部分成功**不能**替代
  确认恢复、进程终止或完整真机验收。

## 与本轮相关的明确未完成项

| 项 | 状态 | 原因 |
| --- | --- | --- |
| R4 C6 慢 final 复现 | NOT_STARTED | 本轮预算用于 R1/R3/R5 的可执行红灯与绿灯 |
| R5b C7 取消测试 | NOT_STARTED | 同上 |
| R6 合成媒体与基线失败测试 | NOT_STARTED | R1–R3 优先；B 亦未交付修复 |
| R7 偏好持久化端到端 / 正式入口接线 | NOT_STARTED | 需设备或 JVM 入口测试，未执行 |
| R8 协议与 MockWebServer 契约 | NOT_STARTED | 未执行 |
| R2 真机六项无害场景通过证据 | PARTIAL | HyperOS 选择器适配已改但未复跑 |
| 破坏性真机套件 | NOT_RUN_POLICY | 用户授权范围外 |
