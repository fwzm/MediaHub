# Windows TLS 阶段测试交接（2026-10-26）

## 状态

沿用 D:/deepseek_test/mh-g4-obs、codex/integrate-archive-ui-20261026、Draft72。
HEAD5c0c2b4a，当前尚未提交 TLS 测试增量；没有 merge 进行中。
变体证明57787已结束，候选源码已按字节恢复；完整Windows92208也已结束。
强制Kotlin/Windows实际互操作runner44085已通过并结束；没有本地运行中的构建，不要重启旧句柄。
外部active-closure.json与tls-timing-peers-result.json是恢复入口。

## 目标

用可控的接入耗时断言真实端点交给TLS的剩余预算及调用者令牌，并在已接入TCP上分别验证真实TLS的
截止时间和显式取消。保留全部原错误码与详情断言，移除固定睡眠对阶段的错误假设。
完成后正常commit/push现有feature并更新Draft72，不新建无关分支，不写main，不merge/close/mark-ready。

## 失败与证据

原CI40271196414确实断言失败；原测试/端点blob在cd7与5c0一致，绑定见tls-timing-original-ci-binding.json。
新TLS套件27/0通过。临时linked-token与full-budget两种生产调用参数错误，分别被新端点测试真实断言抓住，
各1失败/exit1；变体未提交，原字节恢复，见tls-timing-mutation-proof.json与候选备份。
完整Windows1592+104全通过，无跳过，解决方案构建0警告/错误，exit0。
强制23项Kotlin/Windows实际CLI对端已全部通过，11+12项0失败/错误/跳过，exit0、75tasks全部执行、7m9。
使用刚编译的x64CLI，三个Windows输入文件和三份依赖DLL哈希前后不变，见tls-timing-peers-result.json。
生产改动仅提供内部、按实例的可控测试依赖，公开Start仍为原Stopwatch/真实TLS路径；协议与生产分类没有改写。

## 下一步

先读外部active-closure.json核对提交/推送状态，避免重复提交或重复建PR。
本轮只暂存3个代码/测试文件和2个新文档，正常commit/push现有feature并更新Draft72。
本地验证已完成，原失败/变体失败均保留。最终字面SHA和CI结论以外部清单为准，不用父提交成功替代。
原5c0的WindowsCI40276493234已service成功，Android40276493215的API32/36仍运行且尚无报告；
尽量先保存其14个归档界面用例的原始证据，再推新提交，避免把待完成的界面执行误当成通过。
新的完整CI必须绑定最终字面SHA；main-facing59仍停cd7，原失败仍记录，不能直接推进main。
旧架构83路径待逐项处理；本轮只读核对了稳定设备身份的等价内联重构，以及被九项已实现能力替代的旧AUTH-only工厂测试，
没有宣称旧SAF或其他认证架构已实现。手机空闲问题仍未答复，不执行UI/安装/强停。目标仍active，G7DO_NOT_MERGE。
