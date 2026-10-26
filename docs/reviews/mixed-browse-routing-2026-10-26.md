# 混合浏览能力的首屏、续页与导航边界

基于96f051f7，在既有feature分支与Draft72移植旧47架构分支的有用文件夹路由契约。
生产改动仅在LibraryViewModel，不按ServerType决定路由，不改变provider接口或数据库格式。

## 路由规则

| 上下文/运行时能力 | 首屏 | 续页 |
|---|---|---|
| Library根入口，尚未显式进入文件夹 | getLibraries | 库入口不直接分页 |
| 已进入文件夹且有Browse，即使同时有Library/Query | listFolder，携带当前文件夹 | 同一Browse接口、文件夹与offset |
| 有Library、无Browse | Query优先，否则Library.getItems | 保持同一路径及查询快照 |
| 有Browse、没有Library，即使还有Query | Browse根列表 | 继续Browse，不在第二页切到Query |

显式进入文件夹后，libraryId仍为root也不能继续发布顶层库列表；没有Browse时走Library条目接口。
顶层getLibraries结果发布前补齐导航代号/父级快照检查，晚返回的旧根请求不能覆盖新文件夹。
Browse结果不暴露Library查询菜单；返回父级后恢复原筛选与查询路由。
续页仍按id去重，保留现有sort/filter快照、导航代号、父级与取消检查。Browse失败不改用另一个接口掩盖错误。

## 真实执行

先在未改生产文件上运行9项，6个真实断言失败；加入根入口两个边界后，扩展到11项，8个真实断言失败。
两轮均0错误/跳过，68个forced任务；首轮1m54，扩展1m45，exit1，生产hash前后未变，原始XML/日志分开保留本机。

最小修复后完整强制rerun/no-cache：library69/detail41/search30，共140项全部通过，0失败/错误/跳过。
110任务全部执行、2m38、exit0；11新增方法均实际通过，361个输入前后哈希一致。
11项覆盖根库/显式文件夹、Browse+Library+Query、纯Library/Query、纯Browse、Browse+Query无Library、续页去重、旧页/旧根结果与错误边界。
CI新增必需类LibraryMixedCapabilitiesTest，后续须按最终字面head核对完整方法集。

APK与库模块lint已完成：750任务、16执行/734未变，约1m10、exit0，0错误2依赖警告，与96原始基线一致。
APK SHA256为71d0a764f47a34a4f7cdb0ca0b6052ec4692bc43db35efeb8a1cb1bc9e93e829，未安装。
Python证据门禁63项通过，37.380秒。最终字面head完整CI与发布终态另查外部台账，不将局部门禁提升为整仓/真机结论。
本轮没有新的物理验收，旧APK的真机结果不为新提交背书。

## 合并准备范围

此增量完成旧LibraryViewModel的混合能力路由子契约；完整SAF与本地历史组内容审计仍未完成。
main-facing Draft59已在前一2d45的独立main-base原始报告核验后，普通快进到96f051f7。
96的feature-base原始报告已核验通过；新main-base运行仍须独立查终态，不混用base/head。
main保持1246313，所有PR保持Draft，G7 DO_NOT_MERGE。
