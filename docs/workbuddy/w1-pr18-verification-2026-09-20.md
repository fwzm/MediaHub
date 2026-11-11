# W1 复验记录 — PR #18 新 head（`21231ee`）

> 执行者：WorkBuddy 执行线（独立复验，**不代替** Agent C 的 delta 复审，也不改 PR #18 的状态）。
> 工作区：`D:/deepseek_test/mh-wb-pr18`（detached HEAD @ `21231eefcb847f84b8d1092d9c2146438f472ddc`）。

## 1. 基线核对

| 项 | 实测值 |
| --- | --- |
| PR #18 head | `21231eefcb847f84b8d1092d9c2146438f472ddc`（与任务快照一致） |
| PR #18 base | `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`（= `origin/main`） |
| PR 状态 | OPEN / **Draft** / MERGEABLE |
| CI run `34769586851` | `head_sha = 21231ee`、event=pull_request、conclusion=**success** |
| 结论 | CI **确实跑在当前 head 上**，不是旧提交的绿灯 |

## 2. F-C1-1：源码级核对（不需要运行即可判定）

| 检查点 | 实测 | 位置 |
| --- | --- | --- |
| 守卫异常类型为 `IOException` | ✅ | `provider/emby/.../EmbyProviderFactory.kt:57`、`provider/jellyfin/.../JellyfinProviderFactory.kt:56` |
| 不再是 `check()`/`IllegalStateException` | ✅ | 两处均为 `throw IOException("媒体源身份已变化，请重新打开媒体源")` |
| 守卫插入位置在**拦截器最前**（日志/重试/IO 之前） | ✅ | `interceptors().add(0, identityGuard)` |

分层回归测试（`EmbyIdentityGuardFailureLayerTest` / `JellyfinIdentityGuardFailureLayerTest`）逐层钉死：

- **L1 原始 HTTP 边界**：`assertTrue(thrown is IOException, "原始 HTTP 边界必须以 IOException（或其兼容子类）失败，实际为 …")`
  —— 断言的是**异常类型**，不是 `runCatching{}.isFailure`（C 的原始发现即"弱断言掩盖类型回归"，此处已收紧）。
- **L2 `MediaHttpClient.probe`**：断言**不抛未映射运行时异常** + 返回 `MediaProbeResult.Failure`。
- **L3 `ApiClient.probe`**：断言**不抛未映射运行时异常** + 返回 `ServerProbeResult.Failure`。
- 每层都附 `assertEquals("失效后不得有请求到达旧地址", 0, oldEndpoint.requestCount)`。
- **正向对照**：`positive control - fresh handle and unrelated source keep working after invalidation`。

结论：**F-C1-1 已修复**，且"只断言发生任意异常"的弱断言确认被替换为类型级断言 + 零请求断言。

## 3. 本轮执行的分层回归（EXECUTED）

两个批次，均为**真实执行**（非历史 XML、非缓存）：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME="C:\Users\55160\AppData\Local\Android\Sdk"
# 批次 1
.\gradlew.bat :core:common:testDebugUnitTest :core:security:testDebugUnitTest `
  :feature:settings:testDebugUnitTest :feature:home:testDebugUnitTest `
  :provider:emby:testDebugUnitTest :provider:jellyfin:testDebugUnitTest `
  --no-daemon --console=plain --continue
# 批次 2（批次 1 中 3 个 test task 为 FROM-CACHE，强制重跑换 EXECUTED）
.\gradlew.bat :provider:emby:testDebugUnitTest :provider:jellyfin:testDebugUnitTest `
  :core:common:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon --console=plain --continue
```

| 批次 | 结果 | 任务状态 |
| --- | --- | --- |
| 1 | BUILD SUCCESSFUL，2m54s | 187 tasks：77 executed / 110 from cache；其中 `core:security` / `feature:home` / `feature:settings` 的 test task = **EXECUTED**；`core:common` / `provider:emby` / `provider:jellyfin` 的 test task = FROM-CACHE |
| 2 | BUILD SUCCESSFUL，4m41s | **104 tasks：104 executed（0 from cache）**；三个 test task 全部 **EXECUTED** |

### XML 汇总（各模块 `build/test-results/testDebugUnitTest`）

| 模块 | XML | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| `core:common` | 4 | 134 | 0 | 0 | 0 |
| `core:security` | 3 | 34 | 0 | 0 | 0 |
| `feature:home` | 2 | 30 | 0 | 0 | 0 |
| `feature:settings` | 8 | 208 | 0 | 0 | 0 |
| `provider:emby` | 18 | 336 | 0 | 0 | 0 |
| `provider:jellyfin` | 13 | 184 | 0 | 0 | 0 |
| **合计** | **48** | **926** | **0** | **0** | **0** |

口径说明：本表是**本轮 6 模块**的实测结果，与 Agent A 记录的"六共享模块 463"不是同一任务集合
（A 用的是更窄的 task 选择），因此数字不可直接比较，也不作为验收目标。
表中每个模块的 XML 均在本轮生成；`provider:emby` / `provider:jellyfin` / `core:common` 的 XML
来自批次 2 的 EXECUTED 运行。

## 4. 未执行（不得据此宣布通过）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 专用模拟器正式 SAF 路径（加密导出 / MERGE / REPLACE_SELECTED / 取消 / 损坏文件 / 预览后基线变化） | **未执行** | `adb` 不在 PATH |
| 八个真实进程终止点 + 损坏 journal 分支复验 | **未执行** | 同上 |
| Room / DataStore / 凭据 / 文件边界分别验证 | **未执行** | 同上 |
| 全量 `assembleDebug` / `lintDebug` / `assembleDebugAndroidTest` | **未执行** | 本轮只跑 6 模块单测 |
| `:player:mpv` / `:provider:webdav` / `:metadata` | 未纳入本轮 | 不在本次 6 模块范围 |

因此：**PR #18 保持 `C REVIEW PENDING` / `DEVICE UNVERIFIED`**，本记录不改变其状态，
也不构成本线对其合并的批准。Agent C 的 delta 复审状态独立保留。

## 5. 补充观察（供后续核对，非缺陷结论）

- `provider:emby` 单模块 XML 数（18）与 `provider:jellyfin`（13）明显大于 Agent A 记录中
  对应模块的用例数（164 / 88）——说明两模块的测试类在 PR #18 之后又有扩充；
  具体新增项需按用例 ID 集合比对才能定性，本轮未做逐用例 diff。
- 批次 1 出现 3 个 `FROM-CACHE` test task，说明本机 build cache 已存在前次运行的产物；
  凡以"当前执行"口径汇报的测试，必须用 `--rerun-tasks --no-build-cache` 取得 EXECUTED 证据。
