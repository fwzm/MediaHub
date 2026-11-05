# W12 安全回归不变量测试

本文记录 W12 的安全回归测试：把「凭据不得外泄」这类要求变成会红的不变量，而不是只写在文档里的约定。
全部用例只跑 `windows/tests/MediaHub.Tests`，不需要 GUI、不需要真实服务器、不改产品代码。

## 1. 范围与边界

- 新增用例全部在 `windows/tests/MediaHub.Tests/SecurityRegressionTests.cs`（6 个测试类、33 个测试方法、65 个用例）。
- 同时修正了 `windows/tests/MediaHub.Tests/NetworkSecurityTests.cs` 里一处测试设施缺陷（见 §4）。
- `windows/src/**` 一行未改：本轮 `git status` 中由本工作产生的路径只有上面两个测试文件。
- 不连真服、不启动 GUI、不写 `%LOCALAPPDATA%\MediaHub`：保险库用例只在 `%TEMP%\mediahub-security-tests\<guid>` 下建目录，`Dispose`/`finally` 递归删除；其它用例纯内存或回环 socket。
- 测试之间不共享可变状态：每个用例自建 `LogService` / `MockMediaServer` / `DpapiCredentialStore`。

## 2. 六条不变量与钉住它们的测试

| # | 不变量 | 测试类（用例数） | 关键断言 |
|---|---|---|---|
| 1 | 凭据对象渲染时永不出现密码/令牌明文，也不泄露长度 | `CredentialDisclosureTests`（5） | `ToString()` 与字符串插值均不含秘密；200/300 字符秘密与 null 渲染结果在归一化 `(present)`→`(none)` 后逐字符相等；`VaultStatus` 全枚举下 `VaultLoadResult` 只在 `Ok` 携带 Credential |
| 2 | 保险库落盘无明文；换熵/换目录/被篡改一律失败关闭且不返回半截数据 | `VaultFailClosedTests`（7） | vault 与 entropy.bin 原始字节里 UTF-8 与 UTF-16 都搜不到标记；复制 vault 到别处→`ForeignUser` 且 `Credential` 为 null；替换 salt 后不重新生成（字节保持被替换值）；损坏文档→`Corrupt` 且隔离文件恰好一个；`version=2`→`Unavailable` 且文件逐字节不变；DPAPI 解开但不是凭据载荷→`Undecryptable` |
| 3 | 地址输入拒绝 userinfo / query / fragment / 非 http(s)，且拒绝文本不回显输入 | `ServerUrlNormalizerSecurityTests`（13） | 4 种 userinfo 写法均报「不能内嵌用户名或密码」且不含该 userinfo；query/fragment 在解析前被拒且不回显参数值；非 http scheme 只回显 scheme；被接受的地址 `BaseUri.UserInfo/Query/Fragment` 全空 |
| 4 | 凭证只发给完全相同的 origin；跨源重定向剥离全部身份头；HTTPS→HTTP 降级在发出下一跳前被拒 | `OriginScopedCredentialHandlerTests`（28） | 同源 302 后 7 个身份头仍在；6 种跨源形态（异端口/异主机/子域/畸形目标/scheme 升级/userinfo）第二跳身份头全无而 `Range`/`User-Agent` 保留；跨源后再回原源也不恢复凭证；降级抛「拒绝 HTTPS 到 HTTP 的重定向降级」且 inner 只收到 1 次请求；`MaxRedirects=2` 时第 3 跳前抛「重定向次数超过上限（2）」 |
| 5 | 诊断日志与启动轨迹不写入令牌 | `LoggingDisclosureTests`（6） | 六种夹带形态（`api_key` query、Authorization 头行、`X-Emby-Token` 头行、userinfo URL、裸 JWT、异常消息）经 `LogService` 后 `Entries` 与 `Export()` 均不含秘密且保留可诊断形状；异常路径只留类型名；真实 provider 会话后日志条目为空（结构上无写入面）；`StartupTrace` 源码只有 `Write(string stage)` 与 `{stage}` 插值 |
| 6 | 访问令牌只走请求头，不进 URL（ADR-026） | `MediaBrowserTokenPlacementTests`（6） | Emby/Jellyfin 全流程请求的 `PathAndQuery` 不含令牌、query 不含 `api_key=`/`token=`；同一次运行里确有请求把令牌放在 `X-Emby-Token`/`X-MediaBrowser-Token`（Emby）或 `Authorization: MediaBrowser Token="…"`（Jellyfin）；`ImageUrl`/`SubtitleUrl`/`DirectStreamUrl` 生成的 URL 不含令牌与 userId |

## 3. 未覆盖项与残余风险

1. **`StartupTrace` 只做源码结构断言**：它在 `MediaHub.App` 程序集里，而测试工程有意不引用 App（会拖入 WinUI）。因此无法在运行时证明写入内容；替代手段是读取 `windows/src/MediaHub.App/Services/StartupTrace.cs` 原文，断言唯一参数是 `stage` 且文件内不出现 `Authorization`/`Token`/`Cookie`/`Header`/`HttpClient`/`RequestUri`。若将来该文件新增请求相关参数，用例会红。
2. **HTTPS→HTTP 降级在 handler 层验证**：真实降级需要真 TLS 服务端，本文件改用脚本化 inner handler 直接驱动 `OriginScopedCredentialHandler`，验证的是「拒绝发生在发出下一跳之前」（inner 仅 1 条记录）。TLS 端到端另属 W6 传输层测试范围。
3. **`LogService` 与 provider 层之间没有引用关系**（provider 从不注入日志服务），因此「provider 请求不会写日志」是结构事实，用一个真实 provider 会话后 `Entries` 为空来钉住；若将来有人在 provider 里接日志，该用例会红，但不会告诉你哪一类字段被写——需人工补断言。
4. **保险库用例依赖 DPAPI `CurrentUser`**：需在交互式用户配置文件下运行（CI 的 windows runner 满足；服务账户/无配置文件会话下会稳定得到 `ForeignUser`）。
5. **`ServerUrlNormalizer` 的实际行为是「全部拒绝」而非「净化」**：本文件把拒绝文本与拒绝边界钉住；若产品改为接受并脱敏，用例会红，等于强制更新本文。
6. **端口选择仍依赖 http.sys**：回环监听使用 20000–59999 随机端口 + 最多 20 次重试，端口被占不会让整跑失败，但若 http.sys 本身不可用（受限容器），这些用例会失败而非跳过。

## 4. 顺带修掉的测试设施缺陷

**现象（CI）**：`windows-ci` pull_request，run `40011245950`，head `c57ecf9` 真红一次：

```
Failed MediaHub.Tests.RedirectCredentialTests.Redirect_chain_is_bounded
System.Net.HttpListenerException : The process cannot access the file because it is being used by another process.
   at System.Net.HttpListener.AddPrefixCore(String registeredPrefix) … NetworkSecurityTests.cs:line 182
Failed: 1, Passed: 372, Total: 373
```

**根因**：`NetworkSecurityTests.cs` 的 `Redirect_chain_is_bounded` 自己 `new HttpListener()` 并用 `Random.Shared.Next(20000, 60000)` 取端口，`Prefixes.Add` 之后直接 `listener.Start()`，**没有**像同文件 `StartServer` 与 `StartSameOriginChain` 那样 `catch (HttpListenerException) { continue; }`。随机端口撞上已占用端口时，`Start()` 抛 `HttpListenerException` 直接判失败。

**修复**（只改测试设施，产品代码零改动）：

- 抽出 `private HttpListener StartLoopbackListener(out int port)`：随机选端口 → `Prefixes.Add` → `try { Start() } catch (HttpListenerException) { listener.Close(); continue; }`，最多 20 次，成功时登记到 `_listeners` 供 `Dispose` 回收；仍失败才 `throw new InvalidOperationException("could not bind a loopback listener")`。
- `StartServer`、`StartSameOriginChain`、`Redirect_chain_is_bounded` 三处统一改用该 helper（前两处原本有等价重试，抽出来避免再出现第三份漏写重试的副本）。
- `Redirect_chain_is_bounded` 的断言语义不变：服务器对 `/loop` 无限 302 自指，客户端在 `MaxRedirects = 3` 下必须抛 `HttpRequestException`。

**回归证据**（连跑 5 次，同一构建）：

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo --filter FullyQualifiedName~RedirectCredentialTests
已通过! - 失败:     0，通过:     8，已跳过:     0，总计:     8，持续时间: 106 ms - MediaHub.Tests.dll (net10.0)
已通过! - 失败:     0，通过:     8，已跳过:     0，总计:     8，持续时间: 116 ms - MediaHub.Tests.dll (net10.0)
已通过! - 失败:     0，通过:     8，已跳过:     0，总计:     8，持续时间: 138 ms - MediaHub.Tests.dll (net10.0)
已通过! - 失败:     0，通过:     8，已跳过:     0，总计:     8，持续时间: 124 ms - MediaHub.Tests.dll (net10.0)
已通过! - 失败:     0，通过:     8，已跳过:     0，总计:     8，持续时间: 120 ms - MediaHub.Tests.dll (net10.0)
```

（8 = 该类 5 个 Fact + 3 个 Theory 数据行的展开结果。）

## 5. 复现与结果

```
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```

本机（dotnet 10.0.113，Windows 11 26220）实测：

```
已通过! - 失败:     0，通过:   438，已跳过:     0，总计:   438，持续时间: 4 s - MediaHub.Tests.dll (net10.0)
```

数字口径：`--list-tests` 共 438 行，其中本次新增 6 个测试类 65 个用例，其余 373 个为既有用例——与 CI run `40011245950` 的 `Total: 373` 一致（CI 那个 head 还没有本文件）。

> 注：任务书口中的「基线 788」是整个解决方案（`MediaHub.Tests` + `MediaHub.WebDav.Tests`）的规模；单跑 `MediaHub.Tests` 分项目的基线是 373，本轮为 438。
