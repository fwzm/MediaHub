# 交接账本 · 2026-10-23 · M0-A WebDAV UA 真机闭环

## 状态

- 发现并修复一个真实 M0-A 缺陷：WebDAV 没有图片鉴权 contributor，原 `ProviderImageAuthStore` 不建立 URL scope，导致 WebDAV 请求只能使用默认 User-Agent。
- 修复提交尚未提交；当前工作树包含生产代码、回归测试、README 和 CHANGELOG 修改，待门禁确认后提交。
- 设备：Xiaomi 14 Ultra / serial `123e243f` / API 36；受控 MockWebServer 使用 `adb reverse`，未访问真实服务器或真实账号。

## 先红后绿

- RED：新增 WebDAV scope 回归在修复前失败 1/6，断言 `serverIdForUrl(http://127.0.0.1:33780/dav/)` 无法解析。
- GREEN：`:app:testDebugUnitTest --tests com.mediahub.app.image.ProviderImageAuthStoreTest` 通过 6/0。
- 回归：`:app:testDebugUnitTest :app:lintDebug` 通过，测试报告和 lint 均为成功。

## 真机证据

- 本地 APK 使用同签名 `adb install -r` 覆盖安装成功；修复后 APK SHA-256：`9C369A23D497D5A06A3877FF6E9C223FA347D698E6E26D25775BBD3A084BCD6C`。
- 编辑页显示自定义 UA `MediaHubTest99`；保存后 force-stop / 重启，编辑页仍恢复该值。
- 受控 WebDAV `OPTIONS /dav/` 返回连接成功，MockWebServer 实际收到 `User-Agent: MediaHubTest99`。
- 修复前同一真机请求收到默认 `MediaHub/0.1.0-alpha.1 (Android 16)`，证据保存在 `mh-device-evidence-20261023/m0a_requests.jsonl`。
- 冷启动最早的一条后台 OPTIONS 仍可能在 server scope 快照建立前使用默认 UA；该时序边界已观察并保留，不能宣称所有冷启动请求均已覆盖。

## 下一步

- 提交并推送本轮修复，更新 Draft PR #36；PR #45 仍不包含本分支提交。
- 如需收紧冷启动时序，单独设计 scope readiness 的有界等待，不能在网络拦截器中直接做无限 DataStore/Room I/O。
- G7 继续 `DO_NOT_MERGE`，等待用户显式授权。
