# Windows TLS 超时测试的阶段与预算（2026-10-26）

## 原始失败

面向 main 的候选 `cd7ceeeca28ea26f0fe53f7405bebfc2770ca817` 在 Windows CI
`40271196414` 的 `SourceSyncTlsTransportTests.A_timeout_is_reported_as_a_timeout_even_when_the_accept_was_slow`
真实失败：预期 `tls-handshake-failed`，实际 `transfer-timeout`。
该测试及端点实现的 Git blob 与本次增量父提交 `5c0c2b4aeedbb24349d04b41d40555f34ecc892f` 一致。
原始运行仍记为失败，日志保留在本机证据目录。

旧测试在 900ms 的整个接收预算内先等待 400ms，再连接一个不进行 TLS 握手的客户端。
它没有确认是否已经进入 TLS 阶段。调度延迟可能先耗尽接入预算，端点因而在握手前返回
`transfer-timeout`；这种结果无法证明 TLS 自身把截止时间错误地当作调用者取消。

## 现在分别验证的契约

1. 真实 `ReceiveOnceAsync` 端点接受一个已连接的本机 TCP 客户端。
   通过内部、按实例注入的耗时读取器，把接入耗时固定为 25 秒，总预算为 30 秒。
   在 TLS 调用边界断言收到的预算严格为 5 秒，取消令牌严格来自调用者。
   该边界用受控回调观察参数，不把它算作真实 TLS 握手证明。
2. 先完成 TCP 接入，再调用真实 `SslStream` 握手实现，让独立的握手截止时间触发。
   仍要求 `tls-handshake-failed`、`did not finish within`，并排除调用者取消的详情。
3. 同样在 TCP 接入后启动真实握手，再显式取消调用者令牌。
   仍要求调用者取消详情，并排除截止时间详情；取消时刻不再依赖固定睡眠。
4. 用三个边界值确认剩余预算包含接入耗时；恰好用尽和超出预算都保持既有的 1ms 定时器下限。

公开 `Start` 的默认路径仍使用 `Stopwatch.Elapsed` 和真实 `AuthenticateServerAsync`。
内部构造器与友元测试程序集提供按实例的测试依赖，不引入全局可变开关或 CLI 配置。
这次修正针对测试的阶段假设；生产的公开启动、协议、错误码及握手参数规则保持原有语义。

## 实际负向与通过结果

新测试先在本机 TLS 套件中执行，27 项通过，0 失败、0 跳过。
随后分别在真实端点调用位置临时引入两种错误：

| 临时错误 | 实际执行结果 |
|---|---|
| 给 TLS 传入整个操作的预算取消令牌 | 新端点测试 1 项真实断言失败，退出码 1 |
| 给 TLS 传入完整 30 秒预算 | 新端点测试 1 项真实断言失败，退出码 1 |

两个变体都产生测试报告中的断言失败，而非编译错误。完成后按字节恢复候选源码并核对哈希。
再执行完整 Windows 回归：`MediaHub.Tests` **1592 项通过**，`MediaHub.WebDav.Tests` **104 项通过**，
均为 0 失败、0 跳过；解决方案 Debug/x64 构建退出码 0，0 警告、0 错误。

Kotlin/Windows 两个方向的实际 CLI 互操作已分别通过 11 项和 12 项，0 失败、0 错误、0 跳过。
命令使用 JDK21、`--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`，
75 项任务全部执行，耗时 7 分 9 秒，退出码 0。
三个 Windows 输入文件与新构建 CLI 的三份依赖 DLL，在完整回归前后哈希均未变化。
具体命令、TRX、变体断言和三份依赖 DLL 的哈希在本机独立证据清单中。
本轮没有改 Android 生产代码，也没有重新安装或重新构建 Android APK。
`5c0c2b4a` 的 Android 界面 CI 与本次最终提交 CI 均须分别按字面 SHA 核对。

## 交付边界

此增量在现有 feature 分支与 Draft PR #72 上继续提交，保留此前归档界面修复，避免新增无关分支。
手机仍按用户要求暂缓；没有进行真实账号扫描或媒体服务探测。
main 不变，G7 为 `DO_NOT_MERGE`；原失败不会被其他提交的成功覆盖。
