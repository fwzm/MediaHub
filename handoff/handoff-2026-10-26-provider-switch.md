# Provider 表单切换与异步所有权交接

## 状态

沿用D:/deepseek_test/mh-g4-obs、codex/integrate-archive-ui-20261026、Draft72，父2d45c2d8。
生产3文件：AddServerViewModel、AddServerScreen、共享ServerAddressField；新增11个真实Room/主执行器测试，固定CI必需类。
61881 RED、12594首轮GREEN、19728最终GREEN、56701 app/lint、20043 Python均已终止，不要重启这些旧句柄。
实际commit/push/CI以Git与外部active-closure.json为准，不能只读本文历史文字判断。

## 目标

移植旧47分支的不同provider清空地址/凭据与派生状态，并补齐单个表单的操作所有权。
同类型/未注册输入不改草稿；已有源加载前后锁定类型；认证/保存同步占用并冻结输入；旧探测响应/finally不覆盖新任务。
整体所有分支main准备目标未完成，不以该表单增量替代SAF、混合browse或其他本地历史内容审计。

## 失败与通过

RED：10项9个真实断言失败、0错误/跳过，88forcedtasks/7m38/exit1；3生产文件在RED前未改，hash前后一致。
首轮GREEN：265项253通过12环境skip、0失败/错误，144forcedtasks/10m47；新增10项通过。该版本仅作为首轮证据保留。
复核后撤回额外NonCancellable登出上下文，保留原回滚上下文并透传取消，新增取消身份/不落库用例。
最终GREEN：266项254通过12环境skip、0失败/错误，144forcedtasks/10m40/exit0，350输入hash不变。
模块：server44/home16全通过，database194pass+12 CLI环境skip；11新增方法全部通过。
本机MH_W638_SYNC_DLL未配置，不能称266全部通过。2d45 Windows40313712554另有相同DB/common/model/Windows源的实际12反向peer用例，仍单独记为旧提交保留组件证据。
APK+server lint：772tasks30执行742未变，约5m/exit0，0错误3基线警告；APK5fde0fb73de418b89a37c5ee359ec9ebd9da725bb48310481088ab0fd2643fc7未安装。
Python63项通过153.907s；没有本轮新真机结论。所有原始XML/日志/第一轮记录只在本机，不覆盖历史失败。

## 下一步

父2d45 Android40313712588两API已137pass1预期skip/冻结0问题，完整build仍在lint收尾；Windows40313712554已success且原始1592+104/TLS27/实际peer23已核验。
等父完整原始产物保存后再推本批，避免取消有效运行。使用audit-frozen-unit-candidate.py从字面Git对象提取父源文件，不能拿当前WIP替代。
新head完整CI后再更新main-facing Draft59；现59仍c789，已独立main-base完整raw验证。
Provider切换任务完成后，实际剩余功能：混合Browse/Library初始与续页路由、完整SAF及备份/双内核/字幕兼容。
旧47的119路径已分类但不等于全功能完成；本地9组已有2组处理，另7组仍待内容核验，local-only-content-blobs.json为只读差异清单。
仅本地feature/emby-progress-reporting已读旧provider/DTO/test与部分调用方：当前已有更完整生命周期；仍需完成逐路径/方法证据归档，尤其旧silent500与现coordinator错误边界区别，不能直接记9旧用例等价。
保护用户资产、其他worktree与真实账号；无手机操作。G7DO_NOT_MERGE，不写main、不merge/close/mark-ready。
