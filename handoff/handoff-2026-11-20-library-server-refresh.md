# Handoff — 2026-11-20 — 媒体源连接变更即时重载（#43）

## 任务状态
type:state
- Round 16/256；task_id=library-server-refresh-20261120；branch=feature/library-server-refresh-20261120。
- 实现：新增 core:model ServerRefreshPolicy；LibraryViewModel / DetailViewModel 订阅 ServerStore.observeServers()，仅在 type、username 或启用线路的 url/isPrimary/sortOrder 变化时重载；name/note/icon/线路质量测量不触发网络重拉。
- 验证：RED core:model 6 tests/4 failed、feature:library 26/1 failed、feature:detail 22/1 failed；聚焦 GREEN exit 0/0/0；NC 3/3 PASS_ONE_RED；全量 GREEN 11m23s/549 tasks/exit 0；80 suites/803 tests/0 skipped/0 failures/0 errors；APK size=130579351 sha256=1e241c447c9dd4c418d1bc367ca59124d10b8c5abdd03fbea0bd7f1cc6590a82（未安装）。
- 真机：NOT_RUN（设备 123e243f 未触碰；Room 6↔5 阻塞，禁清库）。

## 目标
type:goal
- 闭环审计行 #43「编辑媒体库不立即生效」：已打开的媒体库与详情页在媒体源连接配置保存后立即按新配置重建 Provider handle 并重取数据，不需要退出重进。

## 已试方案与失败原因
type:dead-end
- 不做无条件 observe→load：端点测速会持续写 last*Quality 字段，无条件重载会造成浏览页反复刷新；策略只比较连接签名，忽略展示与统计字段。
- RED 用恒 false stub 证明 4 个连接变更断言可捕获缺陷；NC 分别去掉 url、关闭 Library 订阅、关闭 Detail 订阅，均恰好 1 红，snapshot 逐字节还原 sha256 无 mismatch。

## 进度与下一步
type:progress
- 下一步：push feature 分支、创建 Draft PR、更新 queue.json、用 40 位完整 SHA 查询 exact-head CI；结果未出前保持 CI_PENDING。
- 下一轮候选：queue 第一条 READY，或审计行 #5 媒体库横竖海报自适应。
- 证据目录：D:/deepseek_test/mh-library-refresh-evidence-20261120（snapshot、nc/summary-all.json、red-*.log、green-*.log、green-full-20261120.log）。
