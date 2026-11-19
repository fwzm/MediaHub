# Handoff — 2026-11-20 — 响应式竖版海报尺寸（#24）

## 任务状态
type:state
- Round 14/256；task_id=poster-height-20261120；branch=feature/poster-height-20261120。
- 代码提交：46aaa2288fad82bc124c9d6fcb546c9126bb90ec（5 files +70/−2）。
- Draft PR #163：https://github.com/fwzm/MediaHub/pull/163（base=main）。
- 实现：新增 PosterLayoutPolicy（详情海报 96/104/120/136dp，宽高比手机 2:3、大屏 0.70–0.72）；PosterImage 新增可选 aspectRatio；LibraryScreen 海报卡片与 DetailScreen 详情海报接入窗口宽度策略。
- 验证：RED core:ui 3 tests / 3 failed（临时 stub）；聚焦 GREEN core:ui 17 tasks / exit 0（3 tests，0 failures）+ library/detail 编译 55 tasks / exit 0；NC 3/3 PASS_ONE_RED；全量 GREEN 10m55s / 549 tasks / exit 0；80 suites / 795 tests / 0 skipped / 0 failures / 0 errors；APK size=130579351 sha256=129b2fa16e6aea3655b14545b50021e6a21ff060332f0898ed54d61f61f08779（未安装）。
- 真机：NOT_RUN（设备 123e243f 未触碰；Room 6↔5 阻塞，禁清库）。

## 目标
type:goal
- 闭环审计行 #24「优化-主页/详情竖版海报高度」：提供按窗口宽度分档的海报宽度与宽高比，替换固定 2:3 / 104dp 的单档布局。

## 已试方案与失败原因
type:dead-end
- 无回退的生产方案；RED 使用临时 0dp / 0f stub 证明 3 个策略断言能抓住单档/错误比例缺陷。
- 负向对照 3 场景均从实现态 snapshot 逐字节还原，sha256 无 mismatch；`nc/summary-all.json` allPassOneRed=true。

## 进度与下一步
type:progress
- 下一步：用 40 位完整 SHA 查询 PR #163 exact-head Android CI；结果未出前保持 CI_PENDING，不得当 PASS。
- 下一轮候选：审计行 26「部分界面自适应布局」（需 WindowSizeClass 全页梳理），或按 queue 第一条 READY。
- 证据目录：D:/deepseek_test/mh-poster-height-evidence-20261120（snapshot、nc/summary-all.json、focused-green-20261120.log、green-final-20261120.log、pr-body.md）。