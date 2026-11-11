# 真机音频与首页契约交接

## 状态

沿用 `D:/deepseek_test/mh-g4-obs`、`codex/integrate-archive-ui-20261026`、Draft72。
真实受测生产源码4c649768；APK5059939acf7dc677868de32588e364939200310a3c2ea4e74c23ba66fd2c565b。
本批只追加脱敏核验文档，实际提交/推送状态以Git和外部active-closure.json为准。
49项首页/服务器强制重跑已完成，79878旧句柄不要重启；无生产改动。

## 目标

继续推进所有分支达到main候选条件，目标尚未完成。此批补齐MPV受控AC3/AAC实际可听性和音轨选择确认，
并对旧首页/服务器保存、恢复与导航逐子契约记账，避免把架构替代或SAF预留写成已实现。

## 失败与通过

用户授权临时低音量，AC3/AAC现场确认两次都能听到；各次最终MUSIC静音/扬声器0已核验还原。
AAC重新开菜单确认选中。受控中英文/Off、暂停/返回非黑画面1轮和冷启动UA3轮的先前证据同属4c。
返回位置差334ms仍保留。Media3/真实Emby/外挂字幕本轮NOT_RUN。
首页16项/服务器33项，109forcedtasks、7m2s、exit0、0失败/错误/跳过；源码/输入哈希未变。
旧SAF入口和授权仍DEFERRED；其他部分仅相应子契约覆盖，详见review文档。

## 下一步

4c的Android40294040788现已全success，原始报告已保存：2700唯一项2677通过23预期跨端跳过，
91必需类完整方法/manifest/hash的冻结验证器exit0，lint无Error/Fatal；两个API各137通过1预期能力跳过，新两个解析器方法实际通过。
Windows40294040795原始revision/TRX/peer也已核验：1592+104通过，TLS27，实际跨端11+12均通过，原生无窗口探针PASS。
文档提交另查自身CI，不把4c结果写成未来head结果。更新既有main-facing feature候选须先核对PR59/远端SHA与祖先关系，只能普通快进推送，不能写main。
本批13条路径有明确处理，剩66条未分类；SAF延期与部分覆盖不能算merge-ready。
继续AddServer两大文件及其余API/base/auth/storage差异；不得凭本批49项或整仓CI宣布所有旧功能已经覆盖。
原始设备证据仅本机physical-4c，PR仅脱敏摘要；主工作区资产、其他worktree、真实账号和数据库均保留。
手机本轮操作已结束，音量恢复；G7DO_NOT_MERGE，不合并/关闭/mark-ready，不写main。
