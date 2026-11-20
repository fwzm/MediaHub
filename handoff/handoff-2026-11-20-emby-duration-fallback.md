# 交接账本 · 2026-11-20 · Round 26（审计 #11 Emby/飞牛 时长兜底）

## 目标（type:goal）

闭环审计行 #11「飞牛媒体总时长识别错误」中可在纯 JVM 验证的部分：让 Emby 侧时长在条目/来源缺失、为 0 或为负时不再显示成 0，而是回退到可用的次级来源或标记为未知。范围限定 provider:emby，不触碰其它模块，不与任何开放 PR 的文件冲突。

## 任务状态（type:state）

已完成并推送：分支 feature/emby-duration-fallback-20261120，基于 origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。代码提交 2077f4eb1a45ec1c9bcd90ca1e491e987823e6e9（6 files +67/−3）。三处生产改动：EmbyMediaItemMapper 非正 runTimeTicks → null；EmbyDetailMapper 条目时长缺失时回退首个正时长 media source；EmbyPlaybackProvider 来源时长无效时回退 item.runtimeMs。新增 3 个回归测试。证据级别 EXECUTED_PASS（本机），真实飞牛/Emby 服务器与真机 NOT_RUN，整体 PARTIAL。

## 已试方案与失败原因（type:dead-end）

首次 RED 运行时测试文件尚未补齐对 EmbyBaseItemDto 的正负 runTimeTicks 夹具，只有 mapper 一处失败；补齐 EmbyRuntimeSanityTest 后 RED 稳定为 3 红。聚焦 GREEN 首轮曾因测试过滤器写成短类名而匹配到 0 个测试（Gradle 报无匹配即视为成功），改为完整类名后复跑得到 29 tests/0 failed。负向对照首轮未加 --rerun-tasks --no-build-cache，Gradle 判 UP-TO-DATE 直接 BUILD SUCCESSFUL 造成假绿，补上两个开关后才复现「恰好 1 红」。

## 进度与下一步（type:progress）

下一步第一件事：用 40 位 head SHA 查本分支的 Android CI run 结果（gh run list --commit 2077f4eb1a45ec1c9bcd90ca1e491e987823e6e9 --json status,conclusion,databaseId），回填 queue.json 的 ci_state；若仍 in_progress 保持 CI_PENDING，不得当 PASS。剩余缺口：真实飞牛/Emby 服务器上的时长显示样本（需用户提供受控服务器）、真机 UI 显示验证（设备 123e243f 当前 Room 6↔5 阻塞，禁清库）。下一轮候选：审计 #136（HLS 无扩展名）或 provider:webdav 侧同类缺口。
