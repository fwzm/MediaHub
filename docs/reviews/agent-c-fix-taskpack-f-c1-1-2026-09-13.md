# F-C1-1 修复任务包（Agent C → Agent A）

受审版本：`82a38abce4e761c002dde6d8c9420176e977f57c`。C 未修改该分支的生产代码。

## 结论

两个 ProviderFactory 的身份守卫在**失败出口**抛出 `IllegalStateException`，与下游只处理 `IOException` 的网络边界不兼容。
C 已在原始 head 上取得可复现红灯（Emby 与 Jellyfin 各 3 处失败，见 `agent-c-round2-2026-09-13.md` §R1）。

## 受影响位置

```text
provider/emby/src/main/kotlin/com/mediahub/provider/emby/EmbyProviderFactory.kt:52-55
provider/jellyfin/src/main/kotlin/com/mediahub/provider/jellyfin/JellyfinProviderFactory.kt:51-54
```

```kotlin
val identityGuard = Interceptor { chain ->
    check(tokenStore.isAuthenticationLeaseCurrent(identityLease)) { "媒体源身份已变化，请重新打开媒体源" }
    chain.proceed(chain.request())
}
```

## 实测失败（原始 head）

| 层 | 期望 | 实际 |
| --- | --- | --- |
| L1 原始 OkHttp 边界 `newCall(...).execute()` | `IOException` 或兼容子类 | `IllegalStateException` |
| L2 `MediaHttpClient.probe()` | 返回 `MediaProbeResult.Failure` | 抛出 `IllegalStateException` |
| L3 `ApiClient.probe()` | 返回 `ServerProbeResult.Failure` | 抛出 `IllegalStateException` |

## 建议修法（最小）

把守卫的失败出口改为**兼容 `IOException` 的失败**，并保持 guard 位于日志、重试与任何网络 IO **之前**：

```kotlin
val identityGuard = Interceptor { chain ->
    if (!tokenStore.isAuthenticationLeaseCurrent(identityLease)) {
        throw IOException("媒体源身份已变化，请重新打开媒体源")
    }
    chain.proceed(chain.request())
}
```

理由：`MediaHttpClient.probe` 与 `ApiClient.probe` 已经只 catch `IOException` 并转换为结构化失败；
Media3 数据源路径同样只处理 `IOException`。改类型即可让既有边界自然收敛，**不改变任何安全语义**——请求仍然在出网前被拒。

## 明确禁止

- 不要 `catch (Throwable)` 吞掉编程错误；
- 不要伪造成功响应；
- 不要放宽 / 删除失效检查；
- 不要把守卫移到日志、重试或网络之后；
- 不要在公开 probe 上断言「必须抛 IOException」——probe 自身承担异常→结果转换。

## 回归要求（已在 C 分支就绪）

`EmbyIdentityGuardFailureLayerTest` / `JellyfinIdentityGuardFailureLayerTest`：
L1 断言失败类型为 `IOException`；L2/L3 断言返回结构化 Failure 且**不抛出**；三层均断言旧地址请求数为 0；
正向对照（新 handle 可用、其他来源不受影响、被撤销 handle 旧地址 0 请求）必须保持通过。

A 交回补丁后，C 将在**真实新 head** 上复跑这两组回归及受影响的共享测试，并按约定再跑一轮受影响回归与新 CI；
**旧 772 项证据不为新生产 SHA 背书。**
