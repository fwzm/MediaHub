# Handoff — 2026-11-14 界面批次（21 条目，四段交付）

## 1. 状态

- 分支 `feature/ui-batch-20261114`（worktree `D:\deepseek_test\mh-ui-batch-20261114`），
  base `origin/main@77ef1f2fc33b819a59e07a8bfafb40cb89d5cef7`。
- head `32f7b854ce5520cf9b00903d4ab34ce1cdbf5d01`，Draft PR **#124**（G7 `DO_NOT_MERGE`）。
- 四段 commit：
  - `d69631ad` 段1：3、4、13、14、17、26、43、48
  - `eaaa0e60` 段2：108、110、111、77、119
  - `f6184dc7` 段3：24、56、76、118
  - `32f7b854` 段4：29（含 55/70）、103、129、133
- 已完成条目：21/21 全部有实现与单测；证据分级 `EXECUTED_PASS`（JVM 单测 + 编译），真机 `NOT_RUN`。

### 门禁实测（`--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`）

| 段 | 命令范围 | 结果 |
|---|---|---|
| 1 | 全量单测 | 503 tests / 0 failures |
| 2 | core:model / feature:library / feature:server / feature:search | 78 / 85 / 70 / 33，0 failures |
| 3 | core:model / core:database / feature:detail / feature:settings / feature:server / feature:player | 82 / 281 / 49 / 440 / 70 / 129，0 failures，EXIT=0 |
| 4 | core:ui / feature:detail 单测 + feature:home、app 编译 | 69 / 54 / (20 home 既有)，0 failures，EXIT=0，7m59s |

先红证据：段4 先写测试 → `:core:ui:compileDebugUnitTestKotlin` FAILED（EXIT=1，未实现符号），实现后转绿；
段3 路径解码期望值首跑不符后修正。**段1 为先实现后补测试，无先红日志（如实记录）**。

## 2. 目标

让这批 UI 条目真正落地在代码里，而不是「做不到」的说明：
仓库原先没有的模型/页面按最小可用版本新建（文件浏览器、资源标签排序、徽章包、手动匹配页），
缺口写进 README 而不是记阻塞。

## 3. 失败方案（不要重试）

- ❌ 改 `HomeViewModel` / `LibraryViewModel` 构造注入新偏好 → 既有测试大量 4 参构造会全红；
  改为新增独立 `HomeAppearanceViewModel` / `LibraryAppearanceViewModel`。
- ❌ 同一 DataStore 文件开第二个实例做「重开持久化」断言 → 抛 multiple DataStores active；
  改为同实例写读断言。
- ❌ 用 `RenderEffect` / blur 做玻璃 → 低版本与低端机掉帧且需新依赖；改半透明 + 渐变 + 描边。
- ❌ 在 `ManualMatchPolicy` 里依赖 `android.net.Uri` → 纯 JVM 测试跑不了；改自实现百分号解码。
- ❌ 改 `DetailViewModel` 接入手动匹配 → 与 `feature/detail-manual-match-20261114`（`e51c4e2b`）冲突；
  改独立页面 + 可选入口回调。
- ❌ PowerShell 用 heredoc 写 commit message → 解析失败；改多个 `-m` 参数。

## 4. 下一步

1. 观察 PR #124 exact-head `32f7b854` 的 CI；红则按真实日志修，不把 `CI_PENDING` 当 PASS。
2. 与另一路对齐手动匹配：`#124` 独立页面 vs `feature/detail-manual-match-20261114` 弹窗 + 持久化，二选一。
3. 剩余缺口（见 README「界面批次（2026-11-14）落地范围与缺口」）：手动匹配结果不持久化、
   文件浏览器无独立本地/SAF 目录栈、徽章包不支持自定义新增/删除、119 仅布局常量、29 无真模糊。
4. 真机验收需用户给独占窗口（当前 `NOT_RUN`，不得写成真机通过）。
5. 合入需用户显式授权；G7 恒 `DO_NOT_MERGE`，本会话未 merge / close / mark-ready / 写 main。
