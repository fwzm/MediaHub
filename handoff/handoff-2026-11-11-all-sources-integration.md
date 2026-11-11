# 2026-11-11 完整分支整合交接

## 状态

当前工作区为 `D:\deepseek_test\mh-g4-obs`，分支为 `codex/integrate-all-sources-20261111`。本轮从 `f5e1f69505dde679eb7b52c7726bdb1e317ac31e` 建立整合分支，接入同步分支 `b937e39ceb44171bb13c79cf090dbc6b22828a1c` 的完整传入内容，21 个文本冲突均已解决。本地合并提交之后再接入 Windows 剩余变更和影院界面。

修复基线 f5e1 已在 Draft #72 取得完整 CI。Android run `42858324379` 的两个 API 与 build 均 success，原始 JUnit 为 2822 tests、0 failures/errors、23 environment skips。API32/API36 各 139 唯一用例，138 passed、1 个既有 skip，SAF 撤权后重授权及备份用例均通过。Windows run `42858324596` success。

## 目标与整合选择

继续形成覆盖全部有效分支内容的 main-facing 联合候选。三方预演证实，Windows 和同步的传入差异均无删除路径，实际冲突各 21 个，影院界面 4 个。此前根据整棵分支树差异推断合并会删除已有文件，依据不足，本轮改用共同祖先差异和三方合并。

整合包含配对设备、同步基线、逐条裁决与删除诊断、Windows 备份 CLI 和 Provider 写回等完整内容。删除链保留当前 UA 清理，并归入 servers 步骤。导入会话保留当前 generation 和取消所有权保护，同时接入新的整份信封与 allow/skip 名单接口。诊断端口保持应用私有 files 目录并接入真实 Logger。

同步日志的 ID 改为 JSON 字符串边界，避免合法 ID 的 secret/token/password 后缀被脱敏器当成字段名。现有全局脱敏规则保持生效，实际敏感值继续被遮盖。数据库模块显式声明 JSON 库的生产依赖。

## 验证及失败记录

- UA 删除边界先 RED，2 tests、1 failure、88 forced tasks、exit 1。恢复 purge 后 server 模块 66/66。
- 首次五模块强制门执行 189 tasks，exit 1。common 682/0、logging 39/0、server 66/0。database 275 tests 中 3 个日志断言失败，settings 测试保留了旧接口类型和参数数目而编译失败。原始报告均保留。
- 日志修复首轮因 JSON 库仅在 testImplementation 而编译失败，日志保留。改为生产依赖后最终 database/settings 强制门 exit 0，162 tasks executed，耗时 19m46s。database 276/0，settings 440/0，均无 errors/skips。配置了本地 Windows CLI DLL，互操作测试实际执行。
- 五个模块的分阶段结果合计 1503 tests、0 failures/errors/skips。它们分别来自上述执行，不能写成单次五模块命令整体 PASS。
- Windows 主套件 1959/0、WebDAV 104/0。App 构建 0 warnings/errors。两个实际 TRX 的逐方法、逐数据行基线校验 exit 0。
- Windows 基线退役一个旧的慢 accept 超时测试名称，并登记三个已有确定性替代方法，包含剩余预算的 3 个数据行。加上三个归档边界方法，总数由 1952 增至 1959，原有方法案例数无缩减。
- 首次基线生成目录混有同一报告的副本，生成了 4 个 suite，已恢复原基线后用独立的两文件目录重新生成，最终为 2 个 suite。中文 TRX 的列表名被上游门禁拒绝，已保留报告并在英文进程环境真实重跑两个套件。最终门禁仍提示虚拟列表与部分计时精度的既有警告。
- CI 脚本 63/0。两个 workflow YAML 均能解析。

证据集中在本机 `D:\deepseek_test\mh-main-readiness-evidence-20261111`，包括 `integration-work-state.json`、所有 RED/GREEN 日志及 XML/TRX。当前整合候选的 Android APK、Hilt 整体编译、全仓单元与完整 CI 仍待联合门禁。真机设备列表为空，记录 BLOCKED_ENV。

## 下一步

1. 保存同步整合提交，保持 feature 分支和 Draft 门禁。
2. Windows 剩余传入变更相对同步分支共同祖先仅 4 个路径，ref 为 `origin/feature/windows-client`。完整 SHA 在三方审计 JSON 中。
3. 接入 `origin/feature/cinematic-ui` 的 32 个传入路径。重点保留 LibraryRoute 的重新授权回调、ServerEditor 本地源操作和当前 SAF 仪器用例。CI 脚本采用影院分支的去重归档与具体任务重跑，同时保留 SAF 与备份的执行。
4. 在最终代码上执行全仓单元、APK、lint、instrumentation 编译与两个 API CI，核验 SHA/base/产物和报告，再发布 main-facing Draft。G7 保持 DO_NOT_MERGE。
