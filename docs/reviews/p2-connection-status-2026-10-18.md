# P2 · testConnection 的 401/403 语义修正（V12 24h 作业）

**run_id**: 20261017T220451-mediahub-v12 | **分支**: feature/p2-connection-status-semantics
**基线 head**: d46ff272b3d3daaa2fa5f8586ae0d47b4b70054b

## 1. 缺陷（真实，且是同类问题的第二处）

`EmbyProvider.testConnection()` 与 `JellyfinProvider.testConnection()` 都把状态码合并成同一句文案：

```kotlin
401, 403 -> "服务器需要登录（HTTP ${e.statusCode}）"
```

但 401 与 403 语义完全不同：

| 码 | 含义 | 正确引导 |
|---|---|---|
| **401** | 缺少/无效凭据 | 去登录、输密码 |
| **403** | 凭据有效但访问被拒（账号禁用 / 不允许远程访问 / IP 白名单 / 权限不足） | 查账号权限与网络策略，**不是**重输密码 |

合并的后果：**用户在 403 时被引导反复输入正确密码**，方向完全错误。

这与上一轮 G3 修过的 `EmbyAuthProvider.mapLoginFailure` 是同一类缺陷，只是位置不同
（G3 修的是**登录**路径，这里是**连接测试**路径）。

## 2. 更严重的一点：旧测试把这个缺陷**固化成契约**

`EmbyProviderConnectionTest` 原有一条：

```kotlin
fun `http 403 fails with auth hint`() {
    ...
    assertTrue(result.message!!.contains("登录"))   // ← 要求 403 文案含「登录」
}
```

也就是说，如果只改生产代码而不改测试，修复会**被测试判为失败**。
这类「测试守护了一个错误行为」的情况比单纯缺陷更危险 —— 它会让回归看起来是绿的。

## 3. 修复

两个 provider 的 `testConnection` 拆分 401 / 403：

```kotlin
401 -> "服务器需要登录（HTTP 401）"
403 -> "服务器拒绝访问（HTTP 403）——这不是未登录，请检查该账号权限 / 远程访问 / IP 白名单"
404 -> "该地址不是 Emby/Jellyfin 服务（404）"
else -> "HTTP ${e.statusCode}"
```

同时把旧测试从「固化缺陷」改为「守护正确语义」，并补 Jellyfin 的 401 用例（此前缺失）。

## 4. 先红后绿证据

**RED**（回滚两处生产代码修复）：

```
EmbyProviderConnectionTest > http 403 is reported as access denied, not as missing login FAILED
6 tests completed, 1 failed
JellyfinProviderConnectionTest > http 403 is reported as access denied, not as missing login FAILED
7 tests completed, 1 failed
```

**GREEN**（恢复修复）：

```
EmbyProviderConnectionTest:     tests=6 failures=0
JellyfinProviderConnectionTest: tests=7 failures=0
```

## 5. 覆盖矩阵

| 用例 | Emby | Jellyfin |
|---|---|---|
| `http 401 ... auth hint` / `asks for login` | ✅ | ✅（本轮新增） |
| `http 403 is reported as access denied, not as missing login` | ✅（本轮新增） | ✅（本轮新增） |
| 404 / 无效 JSON / 子路径保留 / 取消红线 | 既有 | 既有 |

## 6. 结论

- 401/403 语义：**两条 provider 的 testConnection 均已修正**，先红后绿
- 旧测试固化缺陷的问题：**已一并纠正**
- 跨 provider 一致性：Emby 与 Jellyfin 现在文案一致，且与 WebDAV（`403 -> Http(403)`）语义对齐
- 未做：真机验证（纯文案/映射逻辑，JVM 契约足够）