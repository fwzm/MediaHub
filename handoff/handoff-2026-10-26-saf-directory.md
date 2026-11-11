# 授权目录接入交接

## 状态

独立worktree D:/deepseek_test/mh-g4-obs；分支 codex/integrate-archive-ui-20261026，父35b40e61。
本轮生产/测试完成后本机8模块强制回归1070pass+12既有环境skip，0失败；823输入hash稳定。
APK/testAPK/全项目lint通过；应用APK5a7f61dd37e2，真机同签名就地升级成功。
当前真实commit/push/CI以Git、PR72及外部 active-closure.json 为准，不能沿用父提交CI。

## 目标

SAF选择、持久读授权、单来源树绑定、嵌套浏览/详情/字幕、Media3/mpv播放、原记录重授权均接入现有主线。
保持历史私有目录、设备绑定不可迁移备份、本机加密回滚边界。总体所有分支main准备仍active，G7DO_NOT_MERGE。

## 失败与通过

ProviderRED11/10；内容桥RED5/5；mpv路由RED2/2；表单/LibraryRED28/5真实断言失败，原始日志/XML保留。
mpv首次116/1夹具authority缺失失败保留，修正ProviderInfo；初步Provider26/0清单漏新帮助文件，
最终8模块1082项1070pass12skip/301forcedtasks/327.673s/exit0覆盖全部新文件，不能用初步清单代替。
APK+testAPK+lint1028tasks235executed/450.242s/exit0，0lint错误；Python63/35.037s通过。
真机123e243f/API36：真实系统目录授权与保存、冷进程重进、嵌套详情、两引擎中段推进/暂停、中文外挂可见、
原来源重授权返回目录。一轮/引擎；原用户来源保留，仅新增受控来源/夹具；原mpv偏好恢复，音量0未调整。
原始UI与账号截图只留本机。完整APKhash及逐轮范围见 docs/reviews/saf-directory-content-2026-10-26.md。

## 下一步

查新提交literalCI：新增4个必需JVM类与系统目录UI方法。UI方法已编译但未执行，重点核对撤权→错误页→原记录重授权、
DocumentsUI差异和ModalBottomSheet折叠/退出；不得因编译通过标PASS，也不要在真机执行isolated备份测试。
父35b原始Android/Windows/API报告已保存，CI均通过，无需重启旧进程。
本轮所有Gradle/phone脚本已终止，后续以外部checkpoint现态为准。
本地历史进度分支523bed9的11路径已按当前32wire+6VM+7coordinator+1factory+4token测试核对，剩6本地组；
origin/feature/cross-device-source-sync已见b3a7b646、windows-client已见4a7f9787，未整合，刷新后按内容核对。
保护其他worktrees与原用户资产；不写main、不merge/close/mark-ready、不清数据、不操作真实服务器扫描。
