# WebDAV OPTIONS 协议声明校验（2026-10-26）

本增量基于 `453b9ca503366723557b2ca6453e60a7a6908f1b`，提取旧架构分支中的有效协议探测契约，
沿用当前工厂、凭据协调器和 Provider 接口。

## 真实问题与修复

原 `WebDavProvider.testConnection()` 将任何 OPTIONS 2xx 视为成功，DAV 字段只拼入状态文案。
受控 MockWebServer 的 15 项新测试实际产生 9 项断言失败，0 错误、0 跳过；
68 项任务全部强制执行，5 分 9 秒，退出码 1。原生产文件哈希在 RED 结束前未变，原 XML/日志保留。

现在成功状态同时要求可解析的 DAV class 1 声明。
[RFC 4918 §10.1/18.1](https://www.rfc-editor.org/rfc/rfc4918.html#section-10.1)
规定 DAV 资源在 OPTIONS 响应中声明 class 1。`WebDavCompliance` 将重复字段作为一个列表解析，
区分独立标记、扩展 token 和尖括号内的绝对 URI；URI 内的逗号或数字不能制造 class 1。
缺失、空白、近似数字、引号、相对 URI、片段或不完整列表都不能仅靠某处出现“1”而通过。
扩展 URI 只在内存中解析，不进行 DNS、HTTP 或其他访问。

状态文案改为固定结果，不回显服务端的原始能力载荷。
401 继续为 AUTH_REQUIRED，403 继续为权限类 HTTP 错误；其他 HTTP、超时、取消和禁止重定向路径保留。
连接探测不触发登录、凭据读取、保存或清理。
协议声明只是探测条件，不是对服务器所有 DAV 操作的完整认证；登录仍由独立认证能力完成。

## 实际验证

整个 `:provider:webdav:testDebugUnitTest` **126 项全部通过**，0 失败、0 错误、0 跳过，退出码 0。
68 项任务全部执行，5 分 2 秒，使用 JDK21、`--rerun-tasks --no-build-cache`、单 worker。
其中新套件15项涵盖无声明、无效列表、URI混淆、多字段、有效声明、状态分类、重定向、真实在途取消、
SecretStorage 禁止访问及原始载荷不回显；每种响应只请求本机受控服务。

该套件已加入 Android CI 必需名单，并由完整方法检查约束。现有门禁自测63项通过。
应用、app/WebDAV 测试 APK 与相关 lint 已完成：退出码0，384秒，645 actionable tasks: 49 executed, 5 from cache, 591 up-to-date；lint 0错误、0警告。
所有本机原始结果和冻结输入清单保留在独立证据目录。

## 边界

没有改 WebDAV Basic 编码、凭据存储格式、身份世代、数据库或播放器。
工厂注释同步了实际 subtitleDiscovery 能力，并移除了早期 main/lease 状态的过时描述；装配逻辑未变。
Provider 设计文档同步当前严格探测行为，不再将该缺口写为尚未实现。
旧分支中其他认证、字符集和开放 ID 契约仍独立核对，不能因这个探测契约通过就宣称整个旧分支已完成。

新提交 CI 尚须按最终字面 SHA 核对。手机按用户最新答复暂时无法连接，真机 BLOCKED_ENV，未安装或操作设备。
测试不访问真实服务账号；main 不变，PR 保持 Draft，G7 为 DO_NOT_MERGE。

本地调试APK SHA-256：`ee89dc587f1bd68ce1f8db881b88726466d51ffc7d8868124a075f4b92db2f4c`，没有安装；最终暂存清单记录三份APK及全部输入哈希。
