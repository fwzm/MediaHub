# 设计文档交接（2026-10-26）

## 状态

沿用D:/deepseek_test/mh-g4-obs、codex/integrate-archive-ui-20261026、Draft72。
本增量父提交2f4909a79befbab9381e38d1768ae2693bcf8d73，仅3份设计文档和本轮review/handoff。
31项相关契约强制回归已通过，63677已结束，没有本地构建运行。最终提交/推送与CI状态以外部active-closure.json为准；
恢复时核对实际HEAD，不能重复提交或把本文件的父SHA当成尚未发布的证据。

## 目标

保留当前ADR015/016/017/018/020/022/023的选择，修正旧接口、凭据分工、实际工厂能力、进度与Header描述。
播放器现代视觉实现与生命周期正文保留；验证命令补齐运行身份参数。
只在现有feature提交推送并更新Draft72，不新增无关分支，不写main，不merge/close/mark-ready。

## 验证与边界

37本地链接/4工厂字段集合/API符号检查通过，视觉正文按字节未变；输入与引用源码哈希一致。
provider:api25+provider:base6=31通过，0失败/错误/跳过，57tasks全部执行/4m28/exit0，使用rerun-tasks/no-build-cache。
本轮不改生产代码，没有生产RED要求，不能把31项写成全项目PASS。
2f4909WindowsCI40279925801的rawrevision/TRX已核验1592+104全通过、TLS27实际用例；同run11+12实际CLI XML通过。
这些是父提交结果，新提交CI必须另外核对。2f4909Android40279925807仍待完成，不能当成通过。
用户先表示手机空闲，但ADB列表为空，后又明确暂时无法连接、继续本机；真机BLOCKED_ENV，零设备操作。

## 下一步

先读外部检查点/实际HEAD，完成或核对这5个文档文件的正常commit/push/Draft更新。
新READY生产任务：WebDAV OPTIONS目前任意2xx成功、DAV头只用于诊断，旧分支要求协议身份声明。
RFC4918§10.1/18.1要求class1；用MockWebServer先RED再修正，覆盖缺失/空白/伪造/重复头/扩展URL含逗号，
保留401/403认证语义、取消传播、无自动重定向；不要扫描真实服务。
对应WebDAV运行时行仍待处理，不能因文档完成就记为产品能力完成。其余旧架构契约仍逐项核对。
保护所有其他worktree/用户资产；手机未连接期间不反复询问或尝试安装。目标仍active/G7DO_NOT_MERGE。
