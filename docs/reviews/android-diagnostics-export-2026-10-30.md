# Android 诊断日志导出增量（2026-10-30）

本轮从远端 W6-110 内容中拆出 Android 诊断报告的独立契约，保留当前候选已有的同步、备份和播放器实现。

## 实现

- `core:logging` 新增 `DiagnosticsReport`：固定标题、生成时间、版本、设备和条目数；日志行和头部字段在导出时再次脱敏；写盘失败返回稳定原因码，不抛出到设置页。
- `feature:settings` 新增 `DiagnosticsExportPort`、文件实现和统一文案；`SettingsViewModel` 维护 Idle/Exporting/Exported/Failed 状态，导出任务单飞，完成后允许再次导出。
- 设置页增加“导出诊断日志”入口与结果行；AppModule 将内存 `LogBuffer` 接到应用私有 `filesDir/diagnostics` 目录，版本和设备信息来自当前应用组合根。

## 验证

先写测试后运行：

- RED：只有测试时 `:core:logging:compileDebugUnitTestKotlin` 失败，原因为 `DiagnosticsReport` 未定义，exit 1。
- GREEN：`:core:logging:testDebugUnitTest --tests '*DiagnosticsReportTest*'`，8/8，0 failures/errors/skips，exit 0。
- GREEN：`:feature:settings:testDebugUnitTest --tests '*DiagnosticsExport*'`，导出端口 4/4、ViewModel 2/2，0 failures/errors/skips，exit 0。
- App 组合根：`:app:compileDebugKotlin --rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`，exit 0，321 tasks executed；只有既有 Kotlin/Android deprecation warnings，没有编译错误。

## 边界

- 当前验证覆盖 JVM 渲染、脱敏、文件失败降级、重复导出和 Hilt 编译；没有把“真实用户点击设置页后分享文件”写成已验收。
- 文件写入使用应用私有目录，不访问真实媒体服务器或用户账号。
- Android exact-head visual run `40976715120` 属于前一个 head `99ffec77`，其 API32/API36 visual job 已失败；待 workflow build job 结束后读取原始日志，不能用它为本轮新提交背书。
