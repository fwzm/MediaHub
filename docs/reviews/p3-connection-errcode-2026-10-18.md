# P3 · `ConnectionStatus.errorCode` 从「死字段」变成可操作指引（V12 24h 作业）

**run_id**: 20261017T220451-mediahub-v12 | **分支**: feature/p3-connection-errcode
**基线 head**: 3e6e5f6749350964a1338da7a790feae324ab385

## 1. 发现的问题（「只定义、不使用」的死字段）

`ConnectionStatus` 定义了 `errorCode: ProviderException.ErrorCode?`，但核验结果：

| 侧 | 现状 |
|---|---|
| WebDAV | ✅ 会设置（`AUTH_REQUIRED` / `HTTP`） |
| Emby | ❌ **从不设置** |
| Jellyfin | ❌ **从不设置** |
| UI（AddServerScreen / ServerEditorScreen） | ❌ **只读 `ok` + `message`，从不读 `errorCode`** |

这正是 M16 `recordUnobservable` 的同类问题：**API 形状存在，但没有生产消费者**。
后果是 UI 只能把自由文本 `message` 丢给用户，无法给出**按类别**的下一步指引。

## 2. 修法（不是删字段，而是让它真正生效）

按 G4 的教训（「有参数 ≠ 已接线」），正确做法是**接通**而不是删除：

### 2.1 `ConnectionStatus.toActionableHint()`

新增方法，按 `errorCode` 返回**可操作**的下一步：

| errorCode | 指引 |
|---|---|
| `AUTH_REQUIRED` | 需要登录：请先完成账号登录 |
| `AUTH_FAILED` | 账号或密码不正确：请核对后重试 |
| `AUTH_EXPIRED` | 登录状态已过期：请重新登录 |
| `NETWORK` | 网络不可达：请检查网络连接与服务器地址 |
| `CONNECTION` | 连接失败：请确认服务器地址与端口，以及是否允许远程访问 |
| `HTTP` | 服务器拒绝访问：这不是密码错误，请检查该账号权限 / 远程访问 / IP 白名单 |
| `PARSE` | 服务器响应无法解析：该地址可能不是对应的媒体服务器 |
| `NOT_FOUND` | 路径不存在：请检查服务器地址是否包含正确的前缀 |
| `RATE_LIMITED` | 请求过于频繁：请稍后再试 |
| `NOT_IMPLEMENTED` | 该操作尚未支持 |
| `UNKNOWN` / null | 请检查服务器地址与网络连接 |
| （`ok == true`） | **null**（成功无需指引） |

关键设计：**未设置 errorCode 时返回通用指引而非 null** —— UI 不应在失败时空着没有任何下一步。

### 2.2 两个 provider 填 errorCode

- Emby / Jellyfin 的 `testConnection`：401→`AUTH_REQUIRED`、403/5xx→`HTTP`、404→`NOT_FOUND`；
- 无效 SystemInfo → `PARSE`；
- `ProviderException` → 透传 `e.code`；其余异常 → `UNKNOWN`。

### 2.3 UI 真正消费

`AddServerScreen` 与 `ServerEditorScreen` 失败时展示 `message` + `toActionableHint()`。

## 3. 先红后绿

**RED**（方法尚不存在）：

```
e: ConnectionStatusContractTest.kt: Unresolved reference 'assertTrue' / toActionableHint
BUILD FAILED in 33s（:provider:api:compileTestKotlin 编译期失败）
```

> 说明：本切片的 RED 是**编译期未解析引用**（方法不存在），属合法 RED 形态
> （与 M16-S0/S1 的先例一致），不是「跳过 RED」。

**GREEN**：

```
com.mediahub.provider.api.ConnectionStatusContractTest: tests=3 failures=0
  - auth required is actionable and distinct from generic http
  - a successful status has no error hint
  - unknown error code still yields a safe generic hint
EmbyProviderConnectionTest:     6/0
JellyfinProviderConnectionTest: 7/0
```

## 4. 契约测试断言了什么

1. `AUTH_REQUIRED` 与 `HTTP` 的指引**必须不同** —— 否则 errorCode 没有意义，
   这正是「403 被当成需要登录」那类缺陷的结构性防线；
2. 成功状态返回 null；
3. 未设置 errorCode 时返回保守通用指引（不抛异常、不返回 null）。

## 5. 结论

- `errorCode`：从「死字段」变为**生产填充 + UI 消费**，先红后绿；
- 跨 provider 一致：Emby / Jellyfin / WebDAV 三条路径现在都填 errorCode；
- 与 P2 衔接：P2 修的是 403 的**文案**，P3 修的是**结构化通道**，两者互补。