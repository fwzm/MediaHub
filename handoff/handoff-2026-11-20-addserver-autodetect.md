# Handoff 2026-11-20 添加服务器自动识别媒体端口（#75）

## 任务状态（type:state）
- Round 20/256；审计行 #75「添加服务器时自动识别端口/协议」按“媒体端口候选探测”切片实现，状态 PARTIAL（只覆盖 Emby/Jellyfin 裸主机，不含全协议自动识别）。
- worktree: D:/deepseek_test/mh-addserver-autodetect-20261120；branch: feature/addserver-autodetect-20261120；base: origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。
- 代码提交: 93041e4666a24d30d3bd3d9e462fe1d59a0da95d（4 files +333/−13）。
- 生产改动: 新增 core:common ServerAddressAutoDetect：显式端口/反代 path 或非 Emby/Jellyfin 只返回规范化地址；裸主机按协议偏好依次尝试默认端口、8920、8096。AddServerViewModel.testConnection 逐候选探测，首个成功即把 baseUrl/resolvedUrl/preferHttps 写回草稿并递增 addressVersion；全失败保留原地址并发布最后一次失败；LOCAL 仍走单次本地目录测试。
- 测试: core:common ServerAddressAutoDetectTest 7（新增）、feature:server AddServerViewModelAutoDetectTest 2（新增）。
- 证据目录: D:/deepseek_test/mh-addserver-autodetect-evidence-20261120。

## 目标（type:goal）
- 用户只输入主机名时，不再因为不知道媒体服务器常用端口（8096/8920）而连接失败；自动识别成功后地址框显示可用 URL。
- 显式端口、反代子路径、WebDAV/网盘等非媒体类型绝不被擅自改写或探测媒体端口。
- 纯 JVM 可红绿；不访问真实服务器，不改变现有保存/登录语义。

## 已试方案与失败原因（type:dead-end）
- 不做全端口/全协议扫描：耗时不可控且会对用户网络产生无谓探测；只保留默认端口与两个已知媒体端口。
- NC 若直接删 8096，会使 Emby/Jellyfin 多个候选测试同时变红；拆成“显式端口守卫失效”和“ViewModel 只测首候选”两个单点场景，保证每场恰好 1 红。
- 首版 NC 只盯 VM 时，resolver 覆写会绕过真实策略；改用 core:common 纯策略测试与 VM 行为测试分层覆盖。

## 进度与下一步（type:progress）
- RED: red-20261120.cmd exit1,1；core:common 7 tests/6 failed，feature:server 2 tests/1 failed（AddServerViewModelAutoDetectTest.kt:119）。
- 聚焦 GREEN: core:common 1m26s/7 tests/0 fail；feature:server 3m18s/2 tests/0 fail；exit0,0。
- NC: 3/3 PASS_ONE_RED（policy-ignores-explicit-port、vm-first-candidate-only、vm-does-not-adopt）；每场 exit1 恰好1红，恢复 sha256 与快照一致；summary allPassOneRed=true。
- 全量 GREEN: BUILD SUCCESSFUL 13m9s / 579 actionable tasks / exit0；151 suites / 1642 tests / 11 skipped / 0 failures / 0 errors（app 8/30、core:common 53/689（11 skip）、core:model 8/71、core:ui 9/57、feature:detail 4/41、feature:home 2/16、feature:library 6/71、feature:player 14/129、feature:search 4/30、feature:server 10/68、feature:settings 33/440）。
- APK: app-debug.apk size=130579351 bytes sha256=d595fbf07631dbd12f8ec42bd3d15485ee502c904fefcc2cc13903eee851021d（未安装）。
- 真机: NOT_RUN（未触碰设备 123e243f；Room 6↔5 阻塞，禁清库）。
- 下一步: 创建 Draft PR；用 40 位 head 查 exact-head Android CI；#171 attempt 2 已 success，需回填 queue；#173 仍 in_progress；G7 恒 DO_NOT_MERGE。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
