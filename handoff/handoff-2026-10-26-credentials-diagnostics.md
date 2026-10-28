# 凭据诊断脱敏交接

## 状态

工作区D:/deepseek_test/mh-g4-obs，沿用codex/integrate-archive-ui-20261026与Draft72，父c789f00d。
本批生产只改四种既有Credentials的toString，加4项直接生产行为测试、固定CI必需类并记录脱敏review。
95861模块回归已结束，无本地Gradle运行；不要重启旧句柄。最终commit/push/CI以Git和外部active-closure.json为准。

## 目标

移植旧47eca350的有用凭据诊断脱敏子契约，保留当前认证/存储/ServerType模型。
继续所有分支的main合并准备，整体目标未完成；不把已分类、架构替代或延期写成完整功能通过。

## 失败与通过

初次执行测试JVM在启动时内存提交失败errno1455，未执行测试，不算RED；原始日志及hs_err已移存本机私有证据目录。
确认终态后，验证进程改为Gradle768MiB/Test256MiB/SerialGC/2处理器，保留单worker/单fork。
实际RED4项4个AssertionError、0错误/跳过，6forcedtasks/2m11/exit1，原生产文件hash未变。
最小修复后，API/security/base/Emby/Jellyfin/WebDAV全模块强制重跑507项通过、0失败/错误/跳过，155实际任务、10m36/exit0；213输入前后hash不变。
本轮未构建或安装新APK，没有新真机结论；旧4c物理测试与APK5059939acf7d仍只绑定旧受测版本。
Python门禁结果与发布终态见外部台账，不把运行中任务当PASS。

## 下一步

本批30路径有明确处理，剩36未分类；private legacy-api-auth-disposition.json保存逐项理由和范围。
下一实际代码增量：对当前生产接口做RED，移植Emby根地址规范化、身份头转义、恢复用户id校验，
及Emby/Jellyfin产品身份识别（先核对ProductName缺失的兼容夹具/协议依据）。
随后移植provider切换时凭据清理，并完成SAF完整实现或取得明确范围决策；不能把ADR020预留当落地。
继续余下网络/导航/Local/WebDAV/Emby session/根文档等差异，最后刷新所有远端分支内容覆盖清单。
main-facing Draft59保持c789已验证生产4c+文档，当前CI40302384458/40302384376另查终态；
仅先推Draft72本批修复，待其字面head完整CI及原始报告通过再快进59，避免反复取消main-facing运行。
保护真实账号、数据库、其他worktree与用户资产；设备操作已结束并恢复音量。G7DO_NOT_MERGE。
