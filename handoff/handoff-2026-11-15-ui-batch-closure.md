# 交接：界面批次收尾（2026-11-15）

分支 `feature/ui-batch-20261114`，Draft PR #124，基线 `origin/main` @ `77ef1f2f`。
本轮把上一轮 README 里登记的四个缺口全部落地，不再保留为「已知缺口」。

## 1. 状态

- 代码：四项收尾均已实现，工作树内未提交前已完成真实门禁。
- 门禁（`--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1 --continue`）：

```
:core:model:test                 tests=92   failures=0
:core:ui:testDebugUnitTest       tests=72   failures=0
:core:database:testDebugUnitTest tests=282  failures=0
:feature:library:testDebugUnitTest  tests=89  failures=0
:feature:detail:testDebugUnitTest   tests=54  failures=0
:feature:settings:testDebugUnitTest tests=440 failures=0
:feature:home:testDebugUnitTest     tests=20  failures=0
:app:compileDebugKotlin          （编译通过）
合计 tests=1049 failures=0，exit code 0，BUILD SUCCESSFUL
```

新增/覆盖测试（均在上述统计内）：

```
com.mediahub.model.ManualMatchTest                 5
com.mediahub.model.BadgePackTest                   9（含新增 6）
com.mediahub.core.ui.nav.GlassBlurPolicyTest       3
com.mediahub.feature.library.SafPathTest           4
com.mediahub.core.database.prefs.UserPreferencesLayoutStoreTest 6（含新增 1）
```

证据级别：`EXECUTED_PASS`（JVM 单测 + 编译）。
**未做真机验证**：API 31+ 真模糊、SAF 目录选择器、手动匹配跨重启回显都需要真机/模拟器，
本轮为 `NOT_RUN`，不要升格。

## 2. 本轮四项怎么落的

1. **手动匹配持久化**
   - `core/model/ManualMatch.kt`：`ManualMatchRecord` + `manualMatchKey(serverId,itemId)` +
     `encode/decodeManualMatches`（`\t` 分隔，坏行丢弃）。
   - `UserPreferences.manualMatches` → `UserPreferencesStore` 的 `manual_matches` 偏好键（复用既有 DataStore，**没有新建表**）。
   - `ManualMatchViewModel.selectCandidate` 写成功后才回调跳转；
     `ManualMatchedBanner` + `ManualMatchBadgeViewModel` 在详情页顶部回显，可「打开」或「取消匹配」。
2. **文件浏览器独立 SAF 目录栈**
   - `feature/library/SafPath.kt`：纯栈逻辑（push / pop 保根 / breadcrumb / 编解码），JVM 可测。
   - `feature/library/SafBrowser.kt`：`SafDocumentSource` 抽象 + `AndroidSafDocumentSource`
     （`DocumentsContract.buildChildDocumentsUriUsingTree`，未新增 documentfile 依赖）+
     `SafBrowserViewModel`（栈写 `SavedStateHandle["safPathStack"]`）。
   - `FileBrowserScreen`：顶栏文件夹按钮切到 `SafDirectoryPanel`（面包屑、上级、换目录、
     `OpenDocumentTree` + `takePersistableUriPermission`、文件信息弹窗）。
3. **徽章包自定义新增 / 删除**
   - `BadgePack.custom`、`CUSTOM_BADGE_PACK_PREFIX`、`nextCustomBadgePackId`（`custom-N` 防碰撞）、
     `encode/decodeCustomBadgePacks`、`deleteBadgePack`（内置包删除保护）。
   - 设置页徽章包区遍历 `allBadgePacks(...)`，自定义包可删，底部「新增徽章包」对话框；
     自定义包存 `custom_badge_packs` 偏好键。
4. **29 液态玻璃 API 31+ 真模糊**
   - `core/ui/nav/GlassBlurPolicy.kt`：模糊阈值/半径/scrim 纯函数（3 个单测）。
   - `core/ui/nav/GlassBackdrop.kt`：`GlassBackdropRecorder` 用 `android.graphics.RenderNode`
     （API 29+ 公开 API；Compose 1.7.6 没有公开的 `rememberGraphicsLayer`）录制内容层绘制，
     导航栏按坐标差取「身后那一块」交给 `Modifier.blur` 做真背板模糊。
   - `LiquidGlassNavBar` 新增 `backdrop` / `backdropOffset` 参数；API < 31 时
     `available=false`，仍走原「半透明 + 顶部高光 + 细描边」仿制方案。
   - `HomeScreen`：列表改为绘制到导航栏下方（忽略 bottom 内边距、改用 contentPadding 让位），
     并录 RenderNode + `onGloballyPositioned` 记录两者 root 坐标。

## 3. 失败方案 / 走过的弯路（勿重试）

- `androidx.compose.ui.graphics.layer.GraphicsLayer` 在 Compose 1.7.6 里存在，但
  `rememberGraphicsLayer`、`DrawScope.drawLayer` 均不可用（前者根本不在 1.7.6 的公开 API 里，
  后者实际符号在 `androidx.compose.ui.graphics.layer.drawLayer` 但缺少创建入口）。
  → 不要再用 GraphicsLayer 方案，必须走 RenderNode（或升级 Compose，属架构改动）。
- `androidx.compose.foundation.layout.calculateTopPadding` 不是顶层导入，
  它是 `PaddingValues` 的成员函数，直接 `padding.calculateTopPadding()`，不要 import。
- `DrawScope.drawIntoCanvas` 需要 `import androidx.compose.ui.graphics.drawscope.drawIntoCanvas`。
- `DetailBody` 里没有 `viewModel`；它只有 `detail.item`，serverId 用 `item.serverId`。
- `nextCustomBadgePackId` 形参必须是 `List<String>`（core/model 自带 `Collection` 数据类会遮蔽）。

## 4. 下一步

- 真机（Xiaomi 14 Ultra / serial `123e243f` / API 36）走一遍：
  SAF 进入子目录→旋转→仍在原目录；手动匹配选中→杀进程→重启→详情页顶部仍有「已手动匹配」；
  首页滚动时导航栏下是否真模糊（API 36 应走真模糊分支）；低版本用模拟器（API 30）确认仍为仿制方案。
- 真机结果回来前保持 `CI_PENDING` / `NOT_RUN`，不要写成 PASS。
- G7 恒 `DO_NOT_MERGE`，不 merge、不 mark-ready。
