# Round 27 交接账本 — #66 字幕编码探测与转码（feature/hls-detect-20261120）

## 目标
type:goal 修复审计行 #66「部分字幕乱码」：外挂字幕以 GB18030/Big5/UTF-16 落盘时 mpv 按 UTF-8 解析产生乱码，需在落盘前探测真实编码并转成 UTF-8。

## 任务状态
type:state worktree D:/deepseek_test/mh-hls-detect-20261120，分支 feature/hls-detect-20261120，base origin/main=77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7。新增 core/model SubtitleCharsetDetector（BOM + 严格 UTF-8 + GB18030/Big5 CJK 打分），player/mpv SubtitleCache 在 beforeCommit 前 normalizeToUtf8。RED 7/5 与 3/2 双红，聚焦 GREEN 7/0 与 3/0，NC 3/3 恰好 1 红，全量 20m40s/882 tasks/321 suites/3001 tests（core:model 单独复跑 7/0）。真机与真实服务器 NOT_RUN → 本轮 PARTIAL。

## 已试方案与失败原因
type:dead-end ① 首版 detect 只用 BOM 与 UTF-8 合法性强判，未做严格淘汰：undecodable bytes 用例返回 UTF_8 而非 UNKNOWN → 改为 CodingErrorAction.REPORT 严格解码，非法即 null。② 首版 tiebreak 未定：GB 与 Big5 同分时随机偏一侧，NC-a 翻转后 gb18030 用例即红，确认必须显式「同分偏 GB18030」。③ 统计口径踩坑：testDebugUnitTest 不含 core:model（kotlin.jvm 模块），全量 XML 里 core\model 的 1/7 fail 是 NC 遗留过期文件，须单独 :core:model:test 复跑才能给全量结论。

## 进度与下一步
type:progress 代码 4 文件已改（1 改 3 新增），APK app-debug.apk 130579351B sha256=dd3fa4c259d3a605254eaa1431b3e500339ea54fe7910680fc1a988d40a7af92（未安装）。下一步：查本 PR exact-head CI run；真实乱码字幕样本（GBK/Big5 各一）真机 sub-add 验证仍未做，需等 Room 6↔5 阻塞解除且禁清库约束下安排。G7 恒为 DO_NOT_MERGE。
