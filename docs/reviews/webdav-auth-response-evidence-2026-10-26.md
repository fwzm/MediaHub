# WebDAV 认证响应与旧分支契约（2026-10-26）

父提交为 `229c450845e2f6ab56f186d680764f9cbdb2c890`，沿用现有 feature 和 Draft72。
旧分支 `47eca350` 的 WebDavConnectionTest 包含 OPTIONS、受保护 PROPFIND 和编码重试三个契约。
OPTIONS 已在父增量移植；本轮验证并修正认证响应证据，不恢复旧集中存储或 Basic 默认编码。

## 实际问题

原认证把成功 HTTP 状态和解析出的任意资源列表视为验证通过。
受控测试证实，合法 HTML、错误 XML 命名空间、空 multistatus、不相关资源或结构注入可导致保存密码或发布已认证。
首次强制11项测试8项断言失败；扩展结构用例后18项13项断言失败，均0错误/跳过。
两次 RED 结束前生产文件未改，原始日志与 XML 保留。

## 修复

SAX 解析继续使用原有禁止 DTD/外部实体、安装并回读 resolver/lexical handler 的流程。
新增 DAV multistatus 文档根与已选属性的直接祖先路径检查，拒绝标量里的嵌套标记；
外部扩展中的 response/href/status 不得接管另一资源。祖先记录固定最多六层，不为忽略的深层扩展保留无界栈。

解析结果额外记录是否实际观察到成功 response/propstat 状态。普通空集合解析仍可返回空列表，
但认证/恢复的 Depth:0 结果须包含配置根的成功资源。匹配复用同 origin/user-info 排除规则，
保留路径大小写、保留字符转义与 query 区别，兼容非保留字符编码及既有尾斜线形式。
[RFC4918 §8.3](https://www.rfc-editor.org/rfc/rfc4918.html#section-8.3) 的响应 URI 规则是核对依据。

只有这些检查及既有世代条件提交均通过才保存新密码。无效恢复响应报告 INVALID_RESPONSE，保留已有凭据；
原 HTTP401、HTTP403、取消、超时与身份/世代语义继续由现有测试约束。没有变更凭据格式或数据库。

## 编码与旧实现差异

当前实现首个请求使用 UTF-8；中文凭据的字节、请求次数和失败不落盘由真实本机 PROPFIND 测试核对。
已使用 UTF-8 的401响应不会仅因同编码 challenge 再重发相同或有损凭据。
[RFC7617 §2](https://www.rfc-editor.org/rfc/rfc7617.html#section-2) 保留 ASCII 兼容默认编码的实现选择，
没有要求默认 ISO-8859-1。旧用例刻意要求中文先得到不同编码结果再重试，不作为当前实现的回归要求。
这不等同于宣称支持只接受旧编码的服务器，也不等同于完整 Basic 国际化合规认证；兼容策略差异明确保留。

## 实际验证

- 第一次 RED：11项8失败，68项任务全部执行，3分46秒，exit1。
- 结构 RED：18项13失败，4项执行/64未变，1分58秒，exit1。
- 完整 WebDAV 强制回归：144项通过，0失败/错误/跳过，68任务全部执行，4分34秒，exit0。
  其中新认证18项包含合法绝对/相对/编码根、成功状态、中文首请求及无无效重试。
- 门禁自测63项通过，新增两个解析器 Android 方法均列为 API32/36必需项；缺方法的现有负向夹具检查继续执行。
- 应用、app/WebDAV测试APK及 WebDAV lint 已完成：exit0，299秒，645 actionable tasks: 28 executed, 617 up-to-date；WebDAV lint 0错误、0警告。

新增 Android 方法尚未实际运行，测试APK编译不算仪器执行。最终提交完整CI仍须绑定字面SHA核对。
手机按用户最新答复无法连接，真机BLOCKED_ENV，没有安装或操作设备；没有真实账号扫描或额外认证尝试。
所有测试请求仅发到受控 MockWebServer，扩展URI与foreign href只作为数据解析。
main不变，PR保持Draft，G7 DO_NOT_MERGE；本轮不宣称旧架构分支全部可合入。

APK SHA-256：`5059939acf7dc677868de32588e364939200310a3c2ea4e74c23ba66fd2c565b`，未安装。SAX强制安全配置前缀与父提交源码一致；根/祖先检查位于原安全安装与回读流程之后。
