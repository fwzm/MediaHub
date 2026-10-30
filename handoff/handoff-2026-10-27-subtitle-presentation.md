# 字幕外观移植与CI适配交接

## 状态

沿用D:/deepseek_test/mh-g4-obs，codex/integrate-archive-ui-20261026，父9a0edee3/Draft72。
Native外观从DataStore→真实VM→门面→mpv接通；颜色/字号真机一轮通过并恢复白色/18sp/mpv/音量0。
真实commit/push/新CI以Git和外部active-closure.json为准，完整目标仍active，G7DO_NOT_MERGE。

## 目标

补本地f7af7699已确认的字幕外观缺口，不替换当前字体/Surface/轨道/本地字节桥安全策略。
其余hardening/unified差异、两条近期远端前移分支及主候选整合仍未完成。

## 失败与通过

13项真实断言RED/184forced/147.439s/exit1/0错误跳过，原三生产文件未改。
首次GREEN423/0无跳过/178.291s；CI适配后最终GREEN2 423/0无跳过/184forced/172.726s/826输入稳定/exit0。
首次APK+testAPK+相关lint831tasks71executed/128.824s/exit0；最终app/testAPK/lint另见外部账本。
Python63/40.186s通过。所有旧日志保留，不重启已结束的74531/96324/42087等句柄。
真机APK8dadd1c53f0b9d0b845d1e3be33ecda38fdd869fe7da84898a37206752d5bbe3同签名就地升级、安装hash核对；
170.533秒暂停白→黄，18→24sp退出重进后变大，随后恢复白/18sp，mpv和音量0不改，手机已返回首页。
原始首帧前过早暂停黑画面保留；正常播放后验证，未删掉测量记录；未知其他ASS/PGS/硬件范围不升格。

## 下一步

父9a0Android40347272292整体FAIL原始已下载：JVM2777=2754pass23skip/103必需类/329Git输入校验0问题，
API32 137pass1skip1fail、API36 136pass1skip2fail；目录UI在Local类型组合语义处失败；36另备份预览等待失败。
测试已改合并语义和正向观察解密状态，断言与原数据保护保留，新增诊断收集；新headCI仍须实际复核。
父Windows40347272278原始1592+104/peer23通过。不要复用父CI为新代码背书。
四个本地组69路径已内容核对（31/67/76/56旧方法正文相同且rawpass），剩hardening与unified两组。
继续核对unified音轨/Jellyfin/字体差异，远端sync74f64301与windows4a7f9787尚未整合；刷新后看内容。
不在有用户数据的真机运行isolated备份脚本；不写main/merge/close/mark-ready、不清数据或覆盖别的worktree。
