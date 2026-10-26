# Emby 身份与协议契约移植交接

## 状态

沿用D:/deepseek_test/mh-g4-obs、codex/integrate-archive-ui-20261026、Draft72，父b094c25d。
本批7个生产文件，5个测试文件，CI固定5个关键类，追加ADR说明与脱敏review。
本地61017/89494及Python84752均已终止，不要重启旧句柄。实际commit/push/新headCI以Git与外部active-closure.json为准。

## 目标

继续所有分支达到main候选条件。移植旧47分支的根地址规范化、身份头转义、恢复用户id检查、产品识别。
保留当前凭据/ServerType模型及ProductName缺失兼容策略，不以架构替代/部分覆盖宣称全部旧功能已实现。

## 失败与通过

RED：19项13个真实断言失败、0错误/跳过，119forcedtasks/6m44/exit1，7生产文件在RED前未改，哈希前后保持一致。
GREEN：六个完整模块526项通过、0失败/错误/跳过，155forcedtasks/7m20/exit0；19新增方法均通过。
APK+两provider lint：780tasks/85执行/695未变，10m38/exit0，两模块各0错误4警告，与c789基线类型/源行一致。
APK962a029440637e94c4ecd383bae998965acc766910ac2ddf220d60e0e87dd9e3未安装，无本轮新真机结论。
Python63项通过，158.926s。旧失败、XML、输入hash与APK均留本机，不覆盖历史结果。

## 下一步

原main-facing c789的Android40302384458和Windows40302384376已全success并下载原始产物：
2700=2677pass+23环境skip，320冻结Git源/91必需类/manifest/hash exit0，两API各137pass1预期skip；
Windows1592+104/TLS27/实际peer23pass，head/base/checkout全部核验。不可当成本批新生产head结果。
父b094的Android40306271807两API各137pass1skip已保存校验，整体build仍运行；Windows40306271826 success。
尽量保存b094完整结果后再推本批，避免再次取消有效整仓运行。不得混用当前工作区源码审计父CI；
使用audit-frozen-unit-candidate.py从字面Git对象提取源文件。Draft59在本批新head全绿后再普通快进。
旧47分支本批18新路径处理、7已识别端口更新，剩18未分类；含明确SAF/混合browse/表单凭据切换缺口，整体未完成。
全范围重新清点106本地+87origin，165祖先引用、20非祖先提交组，其中9个仅本地组，worktree实际63。
本地MPV标题组3精确方法体/生产与测试blob与4c实际单测一致，已内容覆盖；仅历史文档组也已处理，另7组待核对。
本机current-ref-coverage.json、local-only-branch-inventory.json、legacy-emby-webdav-disposition.json为对应清单。
下一READY生产工作：provider切换凭据清理、混合浏览能力路径、完整SAF（保留已实现detail/字幕/双内核与备份边界），
并完成剩余根文档/导航等差异及本地7组审计。未完成范围保持显式，不因单测绿就宣告全部可合并。
保护用户资产、其他worktree与真实账号；无设备动作。G7DO_NOT_MERGE，不写main、不merge/close/mark-ready。
