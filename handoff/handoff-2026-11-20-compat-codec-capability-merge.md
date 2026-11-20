# 交接账本 2026-11-20 · player:compatibility 编解码能力包络合并

## 目标
- type:goal 修复 DeviceCapabilities.videoCapability 只取首个匹配解码器导致的能力丢失：MediaCodecList 对同一编码常返回软解+硬解+不同档位，`firstOrNull` 使结果取决于声明顺序（软解在前 → 硬解与 10bit 能力被隐藏，播放决策误判为 TRANSCODE）。

## 任务状态
- type:state 已交付：Draft PR #205 / branch feature/compat-codec-capability-merge-20261120 / worktree D:/deepseek_test/mh-compat-display-20261120。
- type:state 实现：player/compatibility/src/main/kotlin/com/mediahub/player/compatibility/DeviceCapabilities.kt 的 videoCapability 改为对同 codec 的全部候选取包络（maxWidth/maxHeight 取最大，supports10Bit 与 hardwareAccelerated 取任一）。基线 sha256=694f10b4…（1236B），实现后 sha256=09a5b008…（2018B）。
- type:state 测试：新增 player/compatibility/src/test/kotlin/com/mediahub/player/compatibility/DeviceCapabilitiesCodecMergeTest.kt（6 tests）。

## 已试方案与失败原因
- type:dead-end 曾考虑改 PlaybackCompatibilityEvaluator 的评估顺序来绕过，但缺陷根因在能力快照层，改评估器会把顺序依赖扩散到调用方，放弃。
- type:dead-end NC 首版曾想用 minOf/maxOf 之外的整体退化（去掉合并分支）作为对照，会同时红多条断言，不满足「恰好 1 红」，改为三条独立防线各退化一处。

## 进度与下一步
- type:progress RED：6 tests / 3 failed，exit 1（DeviceCapabilitiesCodecMergeTest.kt:32 等）。
- type:progress 聚焦 GREEN：player:compatibility 3 suites / 27 tests / 0 failed。
- type:progress NC 3/3 各恰好 1 红：nc-a 包络取 minOf（:32）、nc-b supports10Bit 改 all（:48）、nc-c hardwareAccelerated 改 all（:61），每场后从快照逐字节还原 MATCH。
- type:progress 下一步：全量门禁 → commit/push → Draft PR → queue.json 追加条目 → 记忆与白板。
