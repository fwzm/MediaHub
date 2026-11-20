# Round 23 — 编辑媒体源时切换生效线路（#84 部分）

## 目标
type:goal
补齐审计行 #84 的缺口：编辑媒体源页面只能改当前地址，没有多线路列表与切换入口；保存时还会无条件重写 endpoints 索引 0，导致真正的主线路标识与 URL 错配。本轮提供线路弹窗、只允许启用线路切换，并让保存只更新真正 primary 线路。

## 任务状态
type:state
- worktree `D:/deepseek_test/mh-endpoint-switch-20261120`；分支 `feature/endpoint-switch-20261120`；base `77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- 代码提交 `319acf55`：4 files +394/−2（新增线路弹窗、VM 切换逻辑、测试）。
- 红色：`:feature:server:testDebugUnitTest --tests ServerEditorEndpointSwitchTest` 3 tests/2 failed，exit1（首次编译失败为测试构造缺参数，补齐后复现）。
- 聚焦 GREEN：3 tests/0 failed，BUILD SUCCESSFUL 4m6s。
- 负向对照 3/3 恰好 1 红：停用线路可被选中、保存重写索引 0、未知 id 回退默认线路；逐字节还原后三个快照 sha256 全部一致。
- 全量 GREEN：`testDebugUnitTest assembleDebug --rerun-tasks --no-build-cache`，BUILD SUCCESSFUL 16m58s / 882 tasks；320 suites / 2994 tests / 23 skipped / 0 failures / 0 errors。
- APK 130595735 bytes，sha256 `35872a9a419f7d80fb3714fc6773ed91cad01e2d77ce70b6fdefcfe523b9c6c2`（未安装）。
- 状态 **PARTIAL**：VM 行为已被 JVM 测试覆盖，弹窗 UI 只通过编译，未做 Compose/真机交互验证。

## 已试方案与失败原因
type:dead-end
- 初次 RED 编译失败：测试用 worktree 的 `ServerEditorViewModel` 构造需要 `serverUserAgentRepository` 与 `userAgentPolicy`，按主仓库旧签名创建导致 `TokenStore` 类型报错；补齐两参数后正常进入 3/2 红。
- unknown 用例首轮假绿：stub 下切换前后 baseUrl 都是 A，补 `assertEquals("https://media-b.example", after.baseUrl)` 后才真实覆盖。
- NC-C 第一次回退命中停用线路，导致 2 红；第二次改法让 Kotlin 推断出 `ServerEndpoint?` 编译失败；第三次仅在 id 完全不存在时回退默认线路，才达到恰好 1 红。
- 未采用改导航或列表页的方案；`ServerEditorScreen.kt` 已被 #124/#128 占用，本轮接受同文件重叠但只新增独立 dialog 调用。

## 进度与下一步
type:progress
- 已提交代码，账本随后提交；分支推送后创建 Draft PR，queue 增补 `endpoint-switch-20261120` 并回填历史 PR 的 CI 结论。
- exact-head Android CI 用 40 位 head SHA 触发/查询；结果未出时保持 `CI_PENDING`，不得升格为 PASS。
- 真机 **NOT_RUN**：未触碰设备 123e243f；Room 6↔5 阻塞，禁清库。
- 下一轮优先零冲突切片：#117「资源设为首页」需避开 #126 的 HomeViewModel；#103 已有保留清理，可考虑设置页手动清理入口（避开被占用文件后选）。
