# 混合Browse/Library路由交接

## 状态

沿用D:/deepseek_test/mh-g4-obs、codex/integrate-archive-ui-20261026、Draft72，父96f051f7。
生产仅LibraryViewModel；11项新测试、CI固定完整类、review。原始9/6及扩展11/8 RED分别保存。
47295/91469 RED、65829 GREEN、16199 app/lint、61604 Python均已结束，不要重启旧句柄。
实际commit/push/新headCI以Git与外部active-closure.json为准。

## 目标

将旧47的混合能力文件夹路由移植到真实VM，首屏和续页一致，保护既有Query排序/筛选、分页去重、导航代号与父级快照。
明确进入文件夹后不继续显示root库列表；迟到根结果不能覆盖子目录。整体所有分支main准备仍未完成。

## 失败与通过

第一轮9项6真实断言失败、0错误/跳过，68forcedtasks/1m54/exit1；扩展11项8断言失败，68forcedtasks/1m45/exit1。
生产文件在两次RED前都未改、hash一致，失败XML/日志保留。最小修复后library69/detail41/search30，140项全部通过，
0失败/错误/跳过；110forcedtasks/2m38/exit0，361输入hash前后一致，11新增方法均通过。
APK+library lint：750tasks16执行734未变，约1m10/exit0，0错误2依赖警告，与96基线一致。
APK71d0a764f47a34a4f7cdb0ca0b6052ec4692bc43db35efeb8a1cb1bc9e93e829未安装，没有新真机结论。
Python63项通过37.380秒；新headCI仍须单独核对原始报告。

## 下一步

主候选Draft59已在2d45独立main-base raw校验后普通快进至96；新main-baseCI另查，不把feature-base绿灯当主候选新运行结果。
2d45主候选旧Android40320926169、Windows40320926300原始已保存核验：2723=2700pass23环境skip、97必需类冻结0问题，
两API各137pass1预期skip；Windows1592+104/TLS27/实际JVMpeer23通过，base=main1246313。
96 feature-base完整raw：Android40320926393 2734=2711pass23skip、98必需类/324Git源0问题，新表单11方法实际通过，
两API各137pass1skip；Windows40320926302原始1592+104/TLS27/实际peer23通过。
本轮路由通过后，旧47主要功能缺口剩完整SAF：选择/持久授权/树浏览/播放/重授权，必须保留现有detail/字幕/双内核/备份边界。
另7个仅本地历史提交组仍需内容核对，local-only-content-blobs.json仅为差异清单，不是通过证明。
旧119路径只是分类完成，不能据此宣告所有分支已可合并；最后需刷新本地+origin refs及候选head/base/CI/产物一致性。
保护用户资产、其他worktree与真实账号。G7DO_NOT_MERGE，不写main、不merge/close/mark-ready，无设备动作。
