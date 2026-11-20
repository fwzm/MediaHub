# 2026-11-20 · TokenStore 记录解码严格化（凭据完整性）

## 目标

core/security 的 TokenStore 记录编解码在损坏数据上 fail-open：decode 里 String(bytes, Charsets.UTF_8) 对非法 UTF-8 字节静默替换为 U+FFFD，
access 为空串仍返回可用 StoredToken，expires 字段非数字被静默当作无过期。后果是残缺/被篡改的加密记录会被当成有效会话：
用户带着一个已损坏的 Token 去请求，服务端 401 后触发会话清理，而真正需要的是重新登录；空 access 更会把「没有凭据」伪装成「已登录」。

## 任务状态

type:state 已实现并验证：

- decode 改为严格 UTF-8（CharsetDecoder + CodingErrorAction.REPORT，CharacterCodingException → null），access 与 refresh 都走该路径。
- access 去空白后为空即判定损坏，返回 null。
- expires 字段必须是可解析整数（非法 → null），只有非正值才按「无过期」处理。
- 新增 core/security/src/test/kotlin/com/mediahub/core/security/TokenStoreCodecTest.kt（6 tests）覆盖上述三条 + 字段数/非数字过期 + 正常往返。

## 已试方案与失败原因

type:dead-end 负向对照 D 首版把 expires 改为 `parts[2].toLongOrNull()`（去掉 elvis）→ Kotlin 编译失败（BUILD FAILED, compileDebugKotlin），不是行为对照，作废；改为 `?: 0L` 后才成为有效对照（恰好 1 红）。
type:dead-end 负向对照 C 用 ops_edit_file 以空字符串 newText 删除一行后，还原时把空串当作 oldText → INVALID_ARGUMENT（oldText must not be empty），文件停在变异态；改为以「行 + 下一行」为锚点成对编辑才可逆。

## 进度与下一步

type:progress 证据（EXECUTED）：RED 6 tests / 4 failed（exit 1，40s）；聚焦 GREEN core:security 23 tests / 0 failed / 0 skipped（exit 0，41s）；
负向对照 4/4 有效，各恰好 1 红并逐字节还原（sha256 fded67e0… MATCH）：A 放宽 access 解码 → 「access token bytes that are not utf8」红；B 放宽 refresh 解码 → 「refresh token bytes that are not utf8」红；
C 去掉空值守卫 → 「blank access token is not a usable session」红；D2 过期字段宽松为 0 → 「non numeric expiry is rejected as corrupt」红。
全量 testDebugUnitTest + assembleDebug 结果见 evidence 目录 full-20261120.log。

下一步：用 40 位完整 SHA 复查本分支 Draft PR 的 exact-head CI；真机/真实服务器登录回归 NOT_RUN（Room 6 到 5 迁移阻塞，禁清库）。
