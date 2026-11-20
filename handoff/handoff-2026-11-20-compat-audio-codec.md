# 交接账本 · 2026-11-20 · 音频编码归一化精度（审计 #63 切片）

## 目标
type:goal
修复 player:compatibility 模块 AudioCodec.fromCodecName 的子串匹配缺陷：旧实现按枚举声明顺序取第一个 norm.contains(alias) 命中，导致 eac3 命中 AC3 的 ac3、dts-hd 命中 DTS 的 dts，设备只支持 AC3/DTS 时被误判为可直通、实际播放无声。目标是把音频编码归一化改为词边界 + 长别名优先的精确匹配，使播放兼容性决策（DIRECT_PLAY / DIRECT_STREAM）与设备真实解码能力一致。

## 任务状态
type:state
已完成并推送：分支 feature/compat-audio-codec-20261120（worktree D:/deepseek_test/mh-compat-audio-20261120，base origin/main@77ef1f2f），改动 player/compatibility/src/main/kotlin/com/mediahub/player/compatibility/VideoCodec.kt（+48/−5）与新增 player/compatibility/src/test/kotlin/com/mediahub/player/compatibility/AudioCodecAliasTest.kt（7 个测试）。RED 6 红、聚焦 GREEN 28/28 绿、负向对照有效 3 场（各恰好 1 红且逐字节还原复验 sha=936f7f15…）。真机杜比/DTS 素材 NOT_RUN，真实 Emby/Jellyfin 服务器 NOT_RUN，故整体证据级别 PARTIAL（仅 JVM 单测）。

## 已试方案与失败原因
type:dead-end
- 方案「仅补别名、保留 contains 子串匹配」→ 失败：eac3 仍会被 ac3 先命中，测试 "eac3 tokens resolve to eac3 not ac3" 红；必须同时改匹配算法。
- 方案「长别名优先排序 + 词边界判断」首次 GREEN → 失败：ddp5.1 因别名后紧跟数字不满足边界，1 红（dolby digital plus spellings resolve to eac3）；补 endsAtBoundary 跳过版本号数字后转绿。
- 负向对照 nc-c（把边界判断改成恒 true，退化回子串语义）→ 失败为无效对照：BUILD SUCCESSFUL、0 红。原因是 MATCH_ORDER 已按别名长度降序，最长别名先匹配，单纯退化边界检查不再改变结果；该场作废，改用 nc-c2（去掉 dd+ 别名）取得恰好 1 红。教训：长度降序是主防线、词边界是第二道防线，负向对照必须打在仍被主防线覆盖的路径上。

## 进度与下一步
type:progress
- 全量门禁：testDebugUnitTest assembleDebug --rerun-tasks --no-build-cache --no-daemon --console=plain --no-parallel --max-workers=1 --continue，日志 D:/deepseek_test/mh-compat-audio-evidence-20261120/full-20261120.log。
- 下一步（可直接执行）：cd /d/deepseek_test/mh-compat-audio-20261120 && git add -A && git commit -m "fix(player): precise audio codec alias matching"，随后 push 分支、开 Draft PR，并把 queue.json（D:/deepseek_test/mh-dsh-longrun-20261014/queue.json）置顶新条目 ci_state=CI_PENDING。
- 遗留缺口（不在本轮范围）：VideoCodec 的视频侧 fromCodecName 仍是子串匹配（h265/hevc 无冲突，但 mpeg4 与 mpeg2 存在潜在误判）；DevicePlaybackAssessment/Assessor 在审计中引用的路径已不存在，需重新定位真实实现后再处理杜比播放决策。
