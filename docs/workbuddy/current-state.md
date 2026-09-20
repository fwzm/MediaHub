# MediaHub 当前状态快照（WorkBuddy 执行线 W0）

> 核对时间：2026-09-20。本文件仅记录**代码与远端可直接验证**的事实，不复制旧文档结论。
> 仓库：`fwzm/MediaHub`。工作副本：`D:\deepseek_test\MediaHub`（worktree，当前检出 `feature/backup-restore` @ `21231ee`）。

## 1. 基线核对结果

| 项 | 实际值 | 与任务快照 |
| --- | --- | --- |
| `origin/main` | `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`（Merge PR #15） | 一致 |
| PR #18 `feature/backup-restore` | head `21231eefcb847f84b8d1092d9c2146438f472ddc`，base `8e516e4`，OPEN / **Draft**，mergeable=MERGEABLE | 一致 |
| PR #18 最新 CI | run `34769586851`（2026-09-13），`head_sha = 21231ee`，conclusion=success | 一致；**已确认 CI 真跑在当前 head** |
| PR #10 `feature/playback-visual-effects` | head `3febdf8b77a6a1d63d54b9d0c409de708ed978bf`，OPEN（非 Draft），MERGEABLE | 一致 |
| PR #16 `docs/1h-device-evidence` | head `f40363ce5199e05a4cd197b456a851c2b984492b`，OPEN，MERGEABLE | 一致 |
| PR #6 `codex/post-1d-review-hardening` | head `7809facab2316f1c43f1e30deb2efeacd8c2ca78`，OPEN，**CONFLICTING** | **任务快照未列出**，本轮新发现 |
| `AGENTS.md` | 仓库中**不存在** | 与 Agent C 结论一致 |

`docs/workbuddy/` 目录此前不存在，本文件为首个产物。

## 2. Provider 能力矩阵（源码级，2026-09-20）

以各 `*ProviderFactory.create()` 实际装配的 `ProviderHandle` 字段为准（ADR-022：字段非空 ⇔ runtimeCapabilities）。

| Provider | 代码位置 | runtimeCapabilities | 未被本线验证的缺口 |
| --- | --- | --- | --- |
| Emby | `provider/emby/.../EmbyProviderFactory.kt:98-119` | AUTH, LIBRARY, DETAIL, PLAYBACK, QUERY, SEARCH, IDENTITY_LOOKUP, PROGRESS | 真实服务器 device smoke 未做；`fix/1h-emby-play-session-id` 未合并 |
| Jellyfin | `provider/jellyfin/.../JellyfinProviderFactory.kt:110-118` | AUTH, LIBRARY, DETAIL, SEARCH, PLAYBACK, PROGRESS | **QUERY 缺**、**IDENTITY_LOOKUP 缺**（已知 DEFER）；device smoke 需真实 server |
| WebDAV | `provider/webdav/.../WebDavProvider.kt`、`WebDavProviderFactory.kt:39` | **∅ 空集**（`ProviderHandle(provider = provider)`，其余字段全 null） | 骨架；`declaredCapabilities` 声明 AUTH/BROWSE/PLAYBACK/**SEARCH**，其中 SEARCH 无实现 |
| Local | `provider/local/.../LocalProviderFactory.kt:25-30` | BROWSE, DETAIL, PLAYBACK | SAF 树导航已存在（`SafTreeNavigator.kt`），未在本轮复核 |

WebDAV 现状（逐方法）：`testConnection()` = 真实 OPTIONS 探测；`logout()` = 清会话；`authenticate/refreshSession/restoreSession/authHeaders/listFolder/search` 全部 `notYet(...)` 抛 `ProviderException.NotYetImplemented`。

**文档漂移**：`TASKS.md` / `README.md` 尾部的“WebDAV 待实现”“Jellyfin Provider 完整实现”等条目早于实际代码，不得据此重新实现已存在能力（1C–1F、Jellyfin 1G A/B/C、Emby PROGRESS、本地备份均已落地或待合并）。

## 3. 正式入口与代码位置（UI 链路）

| 能力 | 正式入口 | 代码位置 | 备注 |
| --- | --- | --- | --- |
| 添加媒体源 | 首页 → 添加 | `feature/server/.../AddServerScreen.kt`、`AddServerViewModel.kt` | 认证表单条件 = `descriptor.authMethod != NONE`；**登录调用固定传 `Credentials.UsernamePassword`**（`AddServerViewModel.kt:261-263`）——`Credentials.WebDav` 在仓库中**零引用** |
| 媒体库浏览 | 首页卡片 → 媒体库 | `feature/library/.../LibraryScreen.kt`、`LibraryViewModel.kt` | 走 LIBRARY/BROWSE 能力 |
| 搜索 | 首页 → 搜索 | `feature/search/.../SearchScreen.kt`、`engine/*` | 聚合 + canonical 身份 |
| 详情 / 换源 | 搜索结果 / 库条目 → 详情 | `feature/detail/.../DetailScreen.kt`、`source/CanonicalSourceResolver.kt` | 换源 = route replacement（ADR-038） |
| 播放 | 详情 → 播放 | `feature/player/.../PlayerScreen.kt`、`PlayerViewModel.kt` | 双内核（Media3 + mpv） |
| 设置 → 播放偏好 | 设置 | `feature/settings/.../SettingsScreen.kt` | — |
| 设置 → 同步与备份 | 设置 | PR #18 分支上的 `BackupScreen` | **仅在未合并 PR #18，不得记为 main 已完成** |
| 播放器视觉（FlowGlow） | PR #10 分支 | `feature/playback-visual-effects` | 仅未合并 PR |

## 4. 任务归属与阻塞（本线视角）

| 包 | 归属 | 本线可执行性 | 阻塞条件 |
| --- | --- | --- | --- |
| W1 PR #18 复验 | A 持有实现；本线做独立复验 | 可在本机跑 JVM 层复验；**模拟器/真机验收不可执行** | 本会话 `adb` 不在 PATH、无 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 环境变量 → DEVICE 验收 BLOCKED |
| W2 Media3 选轨 | **B 持有**；`codex/fix-media3-track-selection` 分支当前 @ `8e516e4`（= main，**零提交**） | 仅可准备合成媒体 / 失败回归 / 验收脚本 | 生产实现仍归 B；未见交付 |
| W3 SLOW-FINAL | 未分配 | 可分析退出时间轴 | 需真实 server 证据才能闭环 |
| W4 EndpointTestService | 未分配 | 可做脱敏与取消的代码级复核 + 回归 | 无 |
| W5 PR #10 接回 | A 持有原分支 | 可在自有候选集成分支做 main 更新与依赖补丁 | 视觉设备门禁需模拟器 |
| W6 全应用 UI | 并行线 | 可做，但需避免与 A/B 改同一批核心文件 | 截图验收需模拟器 |
| W7 Jellyfin 真实验收 | 未分配 | 仅能做契约级测试与验收脚本 | **BLOCKED_BY_SERVER**（无授权实例） |
| W8 只读 WebDAV | 未分配（仍为骨架） | **可完整执行** | 无 |

## 5. 本机环境实测（决定“能验证什么”）

- `JAVA_HOME` = `C:\Program Files\Java\jdk1.8.0_381`（**失效**）；可用 JDK 为 `C:\Program Files\Java\jdk-21`（项目记忆）或 Android Studio `jbr`。
- `java -version`（PATH 中）= 21.0.7 LTS。
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量**均未设置**，但 SDK **实际存在**于
  `C:\Users\55160\AppData\Local\Android\Sdk`（含 android-32/33/34/35/36、build-tools）。
  原始 `local.properties` 为空 → 必须先写入 `sdk.dir` 或显式设置 `ANDROID_HOME` 才能构建。
- `adb` 不在 PATH（platform-tools 未加入 PATH）→ 本会话**不能**跑模拟器/仪器测试。
- 已实测可执行：`JAVA_HOME=C:\Program Files\Java\jdk-21` + `local.properties: sdk.dir` 下
  `:provider:webdav:testDebugUnitTest` 成功运行（55 tests / 0 failures，见第 8 节）。
- Gradle wrapper 存在；`gradle/libs.versions.toml`：AGP 8.9.3 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2 / Hilt 2.57.1 / Media3 1.11.0 / Room 2.8.4 / compileSdk 36 / minSdk 26。
  - **注意**：项目记忆记录的 Kotlin 2.0.21 / Gradle 8.14 与当前目录不符，以 `libs.versions.toml` 为准。
- 全量门禁实测约 49 分钟（1197 tasks），本线优先做**目标模块**测试。

## 6. 工具链异常（影响工作流，需登记）

本机 git 为 `2.55.0.windows.5`（PortableGit）。**无法创建新的分支 ref**：

```
git worktree add -b workbuddy/x <path> origin/main
  → "Preparing worktree (new branch ...)" 成功后 "fatal: invalid reference: workbuddy/x"
git switch -c <name>   → 报成功，但新分支 HEAD 不可解析（rev-parse HEAD 报 ambiguous）
```

已确认受影响：新建 worktree 内 `git status` 把全部文件显示为 `A`（HEAD 视为空树）。

**规避方式（本线采用）**：`git worktree add --detach <path> <sha>`（可用），在 detached HEAD 上提交，推送时显式写成远端分支：
`git push origin HEAD:refs/heads/workbuddy/<任务名>`。

已有 13 个 worktree 与 `main` 均未受影响；本线未改写任何现有分支或 ref。

## 7. 未验证事项（不得当作已通过）

- PR #18 的模拟器 SAF / 八个进程终止点 / 损坏 journal：本轮**未执行**（无 adb）。
- PR #10 视觉效果的 API 32/36 门禁与真实解码进度：本轮**未执行**。
- Emby / Jellyfin 真实服务器验收：本轮**未执行**。
- 全量 `assembleDebug` / `lintDebug`：本轮**未执行**（只跑了单模块 unit test）。
- 模拟器 / 仪器门禁：本轮**未执行**（`adb` 不在 PATH）。

## 8. 跨分支依赖陷阱（本轮实测发现，必须记录）

`TokenStore` 的**身份世代 lease API 只存在于 PR #18 分支，不在 `main` 上**。

| 位置 | `main`（`8e516e4`） | PR #18（`21231ee`） |
| --- | --- | --- |
| `TokenStore` 成员 | `saveTokens` / `readTokens` / `clear` | 另有 `authenticationLease` / `beginAuthentication` / `isAuthenticationCurrent` / `isAuthenticationLeaseCurrent` / `clearAuthenticationIfCurrent` / `withRestoreIdentityChange` |
| Emby/Jellyfin Factory | **无**身份守卫 | 有 `identityGuard`（lease 失效抛 `IOException`，F-C1-1） |

推论：
1. 任何**基于 `main`** 的新代码不得依赖 lease API（会编译失败，且形成对未合并分支的隐式依赖）。
   本轮 W8 初版就因此编译失败（8 处 `Unresolved reference`），已改为不装配身份守卫。
2. 评审 F-C1-1 时必须区分分支：在 `main` 上"守卫缺失"是预期状态，不是缺陷；
   缺陷判定只对 PR #18 的 head 生效。
3. PR #18 合并后，任何需要身份守卫的 Provider 才可复用该 API；届时 W8 可跟进对齐。

## 9. W8 交付（本线完成）

- 分支/PR：`workbuddy/feat-webdav-readonly` @ `01c825e`，Draft PR **#19**（head 与 base 见 PR 正文）。
- 范围：只读认证（Basic）+ `PROPFIND` 目录浏览 + 直链播放；不含备份、视觉、证据归档。
- 骨架 → 实现的能力变化：`ProviderHandle` 由全 null 变为 `auth + browse + playback`；
  `declaredCapabilities` **移除 SEARCH**（无实现，ADR-022）。
- 验证（EXECUTED）：`:provider:webdav:testDebugUnitTest` **BUILD SUCCESSFUL**，
  5 份 XML / **55 tests / 0 failures / 0 errors / 0 skipped**
  （Auth 15、Browse 14、Urls 11、Parser 8、Factory 7）。
- 未执行：全量 assemble/lint、真实 WebDAV 服务验收、真机验收。

## 10. 文档交付（本线完成）

- 分支/PR：`workbuddy/w0-state` @ `f565107`，Draft PR **#20**。
- 内容：本文件 + `task-state.json` + `resume.md` + `w4-endpointtestservice-findings.md`。
