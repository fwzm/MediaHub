# W4 预备核查 — EndpointTestService 脱敏与取消边界（只读分析）

> 结论先行：**F-C1-2 的两条主线在当前源码中仍然存在**（原始异常 message 直入用户结果；取消被折叠）。
> 本文只做证据记录与最小修复方案，未改动任何生产代码。

核对对象：`core/network/src/main/kotlin/com/mediahub/core/network/EndpointTestService.kt`
（基线 `8e516e4`，即 `origin/main`）。

## 1. 脱敏：原始异常 message 进入用户可见结果

```kotlin
// Layer 1 (API test)
} catch (e: Exception) {
    errorMsg = "API test failed: ${e.message}"
}
...
return EndpointTestResult(..., error = errorMsg)
```

- `e.message` 是 OkHttp/JDK 的原始文本，未经 `Redactor`。
- 调用方 `feature/server/.../ServerEditorViewModel.kt:271-281` 把该结果映射为
  `EndpointQualityResult` 并写入线路质量状态（是否落库需按 `ServerRepositoryUpdateEndpointQualityTest`
  与 `EndpointQualityResult` 字段继续核对——本文件未展开）。
- 风险路径：地址由用户输入（`ServerAddressField`）。若用户把令牌放在 query 里
  （例如 `?api_key=...`）或地址含 user-info，异常文本可能带出凭据片段；
  日志侧有 `Redactor`，**结果侧没有**。
- 结论：属于 F-C1-2 未闭环项。修复方向 = 用户侧只给固定/结构化文案
  （如"无法连接服务器（NETWORK）"），诊断细节统一过 `Redactor` 后再落日志，
  两者分离；不要在用户结果里回显 `e.message`。

## 2. 取消：`CancellationException` 被折叠为普通失败结果

```kotlin
} catch (e: Exception) {                       // Layer 1
    errorMsg = "API test failed: ${e.message}"  // 取消 → 伪装成"测试失败但成功返回"
}
...
} catch (e: Exception) {                        // Layer 2（best-effort，风险较低）
    // media test failure doesn't invalidate API result
}
```

- `kotlin.coroutines.cancellation.CancellationException` 是 `Exception` 的子类，
  因此协程取消不会穿透 `test()`，而是返回一个"看起来正常"的 `EndpointTestResult`。
- 对比：调用方 `ServerEditorViewModel:272-274` **已经**写了
  `catch (e: CancellationException) { throw e }`，说明取消穿透是预期契约
  （ADR-039 同款红线），但服务层把取消吃掉了，该分支实际不可达。
- 后果：A 测试被取消后，UI 可能把"用户已取消"呈现为"线路很慢/失败"，
  且外层基于 requestId 的陈旧结果隔离失去一层保证。
- 结论：属于 F-C1-2 未闭环项。修复方向 = Layer 1/2 的 `catch` 先显式
  `catch (e: CancellationException) { throw e }`，其余才映射为结构化错误。

## 3. 线程与主线程阻塞（已登记，仍开放）

文件自身 KDoc 已登记：`test()` 内使用同步 `Call.execute()`，且**未切换 IO dispatcher**；
当前调用方在 `viewModelScope` 主调度器上。`suspend` 声明不会自动把阻塞调用移出主线程。

- 与本仓既有做法不一致：`ApiClient.execute` / `MediaHttpClient` 都在
  `withContext(Dispatchers.IO)` 内执行阻塞调用。
- 实测证据缺失：现有测试用 fake service + `clock = { 0L }`，`EndpointTestServiceTest`
  不覆盖真实阻塞路径；因此"线路测试不阻塞主线程"目前**未经验证**。
- 修复方向：测试主体移入 `withContext(Dispatchers.IO)`；取消边界用
  `Call.cancel()` 挂钩（`suspendCancellableCoroutine` + `invokeOnCancellation`），
  使取消真正中断底层 I/O，而不是等 call timeout。

## 4. 建议的交付切分（遵守"独立任务分别交付"）

| 补丁 | 内容 | 依赖 |
| --- | --- | --- |
| W4-a | 取消穿透（Layer 1/2 显式重抛 `CancellationException`）+ 回归 | 无 |
| W4-b | 用户结果脱敏（固定文案 + `Redactor` 处理诊断文本）+ 回归 | 无 |
| W4-c | IO dispatcher + `Call.cancel()` 可取消边界 + 回归 | 无 |

三者都不需要改动 `provider:api` / 共享契约；**不得**借这批改动全仓重写网络层。

## 5. 本轮未做的事

- 未修改 `EndpointTestService` 或任何生产代码（本包归属未分配，且按 W4 要求
  "脱敏与取消分别提交"）。
- 未验证 `EndpointQualityResult` 的落库路径与 UI 展示文案。
- 未构造合成 user-info / host / 路径 / query / 凭据标记的回归用例
  （列为 W4-a/b 的验收条件）。
