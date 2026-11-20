# handoff 2026-11-20 · core:logging 结构化凭据头脱敏（Emby/Jellyfin 家族）

## 目标
type:goal 修复审计外新发现的生产日志泄露：core/network 的 RedactingLoggingInterceptor 把整张请求头表交给 Redactor.redactHeaders() 打印，而 Redactor 的敏感头集合漏登记 X-Emby-Authorization / X-MediaBrowser-Authorization，且只做 exact 键名匹配、无形状兜底，导致 Emby 家族的 UserId / DeviceId / Token 结构化值原样进 logcat 与诊断导出。

## 任务状态
type:state Round 29 垂直切片完成：Redactor 增加 4 个显式键（x-emby-authorization、x-mediabrowser-authorization、x-mediabrowser-token、x-session-key）+ SENSITIVE_HEADER_KEY_PATTERN 形状兜底（fail-closed）+ 两条结构化头 KEY_VALUE_PATTERNS（整行值 / 单值），redactHeaders 与 isSensitiveHeader 统一走同一判定；同时删除被新 pattern 覆盖的 3 条旧 header 正则（authorization / cookie / x-*-token 整行），避免同一语义两套实现漂移。新增 RedactorStructuredHeaderTest 7 tests。RED 7/3、聚焦 GREEN 46 tests/0 failed、NC 有效 3/3（各恰好 1 红且逐字节还原 sha 一致）。真机与真实 Emby/Jellyfin 服务器 NOT_RUN。

## 已试方案与失败原因
type:dead-end 形状规则放宽为过宽（把 request-id 也纳入）→ 良性头 X-Request-Id 被误脱敏，benign headers are preserved verbatim 用例 1 红；说明形状兜底必须收在凭据语义后缀上，不能按 x- 前缀一刀切。
type:dead-end 删除结构化单值 pattern 单独测试（NC-D：只去掉 session-key 备选）→ 仍全绿，因整行 pattern 已覆盖同一输入；说明负向对照必须打在真正唯一的防线上，重复防线上的退化不构成有效对照。
type:dead-end 首次用 cmd //c 跑 NC 脚本 → 输出为空且 EXIT=0 假绿；原因是 MSYS 下 cmd 参数解析吞掉了重定向。改用 ops_bash 直接执行 gradlew.bat 并显式 JAVA_HOME='C:\Program Files\Java\jdk-21' 才拿到真实 exit code。

## 进度与下一步
type:progress 已提交 Draft PR（见 PR 链接），queue.json 已登记；#192（AudioCodec 别名）CI run 44684253894 仍 in_progress，需用 40 位 SHA 复查。
下一步：用 40 位完整 SHA 复查本 PR 的 exact-head CI run；复查 #192 run 44684253894；Round 30 候选切片 —— VideoCodec 视频侧同类子串误判（mpeg4/mpeg2、vp8/vp9）、#114 Coil 内存缓存、#111 统一弹窗、#107 占位 Logo。
