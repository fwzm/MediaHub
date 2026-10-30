# W14 Windows 诊断日志落盘（滚动文件）

本文记录 Windows 客户端诊断日志的第二个出口：内存环形缓冲之外，新增一个有界的滚动文件 sink。

## 1. 动机

`windows/src/MediaHub.Net/LogService.cs` 到本轮为止只有内存缓冲：

- 环形上限 `MaxEntries = 2000`，超出后从头部删除；
- `Export()` 只渲染当前缓冲里的条目；
- 设置页「导出诊断日志」写的也只是这份快照。

于是长会话（例如连续播放几小时）之后最早的那些行恰好先被挤掉 —— 而它们往往正是故障第一次出现的地方。
本轮把每条日志同时追加到磁盘上的滚动文件，使「最早的行」在进程重启后仍然可读。

## 2. 设计：`LogFileSink`

新类型 `windows/src/MediaHub.Net/LogFileSink.cs`：

- 目录：构造时传入；应用传 `%LOCALAPPDATA%\MediaHub\logs`（目录不存在时创建）。
- 文件名：`mediahub-0.log`（最新）… `mediahub-(MaxFiles-1).log`（最旧）。默认 **每文件 1 MiB、最多 5 个**，即正常上界约 5 MiB。
- 轮转：追加前若「当前文件长度 + 本条字节数 > 上限」则轮转 —— 删掉最旧、其余各后移一位、索引 0 重新开始。
- 条目完整性：轮转只发生在两次 `Append` 之间，**一条日志永不跨文件**；单条本身超过上限时该条仍整条写入，下一条再触发轮转。
- 失败策略：`Append` 永不抛出。可恢复的磁盘错误（`IOException` / `UnauthorizedAccessException` / `SecurityException` / `ArgumentException` / `NotSupportedException`）只记录到 `LastError`（类型名 + 消息），应用继续用内存日志工作。
- 脱敏职责：sink 只接收已经过 `LogService.Redact` 的文本，自己**不做**二次脱敏 —— 二次脱敏会制造「调用方可以传原文」的错觉。

## 3. 接线

- `LogService` 构造改为 `LogService(LogFileSink? fileSink = null)`，暴露 `FileSink`。
- `Write` 顺序：先入内存环 → 再 `FileSink?.Append(entry)` → 最后 `EntryAdded`。先写盘的理由是它是唯一能活过进程的副本，且 UI 订阅者不应能阻止它。
- `Export()` 在有 sink 时于头部多一行 `# 滚动文件目录：<目录>`，让读报告的人知道完整历史在哪；没有 sink 时输出与之前逐字相同。
- 这一行的**形状与位置**（`# ` 前缀、紧跟标题之下）由 `DiagnosticsReport.RollingDirectoryPrefix` 常量与 `DiagnosticsReportTests` 的 7 条用例共同钉住（W6-113）；它**不过** `LogService.Redact` —— 脱敏会吃掉临时目录里的 32 位十六进制段，让指针不可跟随。
- 组合根 `windows/src/MediaHub.App/AppServices.cs` 构造 `new LogService(new LogFileSink(<LocalAppData>\MediaHub\logs))`。

## 4. 测试

新文件 `windows/tests/MediaHub.Tests/LogFileSinkTests.cs`，9 例（xunit）：

1. `Every_appended_entry_reaches_the_newest_file` —— 每条都进当前文件，级别与分类原样；
2. `Rotation_drops_the_oldest_file_and_keeps_entries_whole` —— 上限 64 B / 3 文件，写 5 条后最旧两条已删、保留的都是完整行；
3. `An_entry_larger_than_the_limit_is_written_whole` —— 超过上限的单条整条写入，下一条轮转；
4. `The_number_of_files_never_exceeds_the_configured_cap` —— 写 40 条后仍然只有 3 个文件；
5. `An_unwritable_location_is_reported_without_breaking_the_log` —— 目录被一个同名文件占住时 `LastError` 非空、内存日志照常记；
6. `The_file_keeps_the_lines_that_the_memory_ring_pushes_out` —— 写 2005 条：内存只剩 2000，文件有 2005 行（首尾都在）；
7. `Credentials_are_already_gone_before_the_line_reaches_the_disk` —— `api_key` 与 `Authorization` 到盘上已是 `***`；
8. `The_export_points_at_the_rolling_directory_only_when_one_is_configured` —— 有 sink 才多那行目录指针，`已脱敏` 标题保持；
9. `Constructor_rejects_impossible_settings` —— 空目录、非正上限、非正文件数都拒绝。

四条负向对照（先取实现态快照，逐条改坏后跑，再从快照逐字节还原）：

| 对照 | 改动 | 结果 |
| --- | --- | --- |
| A | 删掉轮转调用 | 3 红（轮转 / 超长条目 / 文件数上界） |
| B | `Write` 存原文而不调用 `Redact` | 1 红（凭据落盘） |
| C | 磁盘失败改为重新抛出 | 1 红（不可写目录不再安全） |
| D | `Export` 不再写目录指针 | 1 红（目录指针用例） |

还原后 `LogFileSink.cs` sha256 `5eed22cf61ef4f45f7b6f1504fcf13dc4ab7ddbed1da7efbb92122d3bd4d86e5`、
`LogService.cs` sha256 `0293409417b5cfebdaa99f8306bb0c00da073b0d706c44c7a354e973f00992e2`，与快照逐字节相同。

## 5. 证据与状态

- 本机 `MediaHub.Tests`：**478 passed / 0 failed**（原 469，新增 9 例）。
- App 构建（`windows/src/MediaHub.App/MediaHub.App.csproj`）：**0 警告 0 错误**。
- 本文件所述行为是 EXECUTED_PASS（单测与构建真实执行）；「设置页点导出后打开日志目录」的界面动作本轮未点，属 GUI_UNVERIFIED。

## 6. 边界与残余风险

1. **`Export()` 仍只是内存快照**，不与磁盘历史合并 —— 合并会重复渲染内存里已有的行；要完整历史请读 `logs` 目录。
2. **文件不做主动清理**：增长由轮转上界保证（≈5 MiB + 一条超限条目），用户手动删除目录不会破坏运行（下次写入重建，失败只记 `LastError`）。
3. **多实例并发未验证**：轮转用 `File.Move(..., overwrite)`，两个进程同时写同一目录时语义未定义；当前假设应用单实例运行。
4. **日志内容边界不变**：磁盘上的文本与内存日志同源（标题等用户数据可能出现，凭据已在写入前脱敏）；sink 不新增任何采集面。
5. **长会话真实证据未取**：2005 条的环形挤出由单测钉住，但没有「真实播放数小时后重启」的端到端证据。
