# W14 Windows 诊断日志滚动落盘

Windows 客户端现在在内存环形日志之外，保留一份有界的滚动文件副本。应用组合根使用
`%LOCALAPPDATA%\MediaHub\logs`，默认每个文件 1 MiB，最多 5 个文件；轮转发生在日志条目之间，
所以一条记录不会被拆开。单条记录即使超过上限也会完整写入，下一条记录才开始新文件。

`LogService` 先脱敏，再把条目交给 `LogFileSink`。磁盘目录不可写时，sink 记录 `LastError` 并继续
使用内存日志，不能因为诊断落盘失败而影响播放。`Export()` 仍返回当前内存快照，并在配置 sink 时
附带滚动目录位置；滚动文件是补充历史，不会与快照重复拼接。

本轮验证：`LogFileSinkTests` 9/9；Windows `MediaHub.Tests` 1601/1601；`MediaHub.App` 构建 0 警告、
0 错误。未宣称真实长时播放重启或设置页导出按钮的 GUI 验收。
