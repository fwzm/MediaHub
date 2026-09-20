# WorkBuddy 执行线 — 恢复现场（resume）

> 更新于 2026-09-20。下一轮接手先读本文件，再核对实际 Git 与远端，避免重复计划。
> 配套：`docs/workbuddy/current-state.md`（状态快照）、`docs/workbuddy/task-state.json`（机器可读）。

## 1. 一句话现状

W0 状态核对完成；W8 只读 WebDAV 功能包已实现并进入本地验证；W1 的 PR #18 代码级复验可做，
但**任何设备/模拟器验收在本会话不可执行**（adb 与 Android SDK 环境变量缺失）。

## 2. 我创建的工作区与分支

| 路径 | 模式 | 基线 SHA | 用途 |
| --- | --- | --- | --- |
| `D:/deepseek_test/mh-wb-state` | detached HEAD | `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1` | W0 状态产物 + W9 文档 |
| `D:/deepseek_test/mh-wb-webdav` | detached HEAD | `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1` | W8 只读 WebDAV 代码 |

远端分支命名（推送时显式指定）：
- `workbuddy/w0-state`
- `workbuddy/feat-webdav-readonly`

**工具链缺陷（必读）**：本机 git 为 `2.55.0.windows.5`，`git worktree add -b` 与 `git switch -c`
会创建出**不可解析的分支 ref**（`rev-parse HEAD` 报 ambiguous，`git status` 把全部文件显示为 `A`）。
规避：一律 `git worktree add --detach <path> <sha>`，提交后 `git push origin HEAD:refs/heads/<name>`。
已确认 `main` 与既有 13 个 worktree 未受影响。

## 3. W8 已完成的改动（`mh-wb-webdav` 工作树）

生产（`provider/webdav/src/main/kotlin/.../webdav/`）：

| 文件 | 职责 |
| --- | --- |
| `WebDavModel.kt`（新） | `WebDavResource` 模型 + `WebDavUrls`：base 归一化、同 origin href 解析、仅解码 `%XX`（不把 `+` 当空格）、类型/容器推断 |
| `WebDavMultistatusParser.kt`（新） | SAX 解析 207 multistatus：命名空间感知（前缀无关）、`propstat` 2xx 过滤、属性顺序无关、禁用 DTD/外部实体 |
| `WebDavApi.kt`（新） | `PROPFIND` 客户端：Basic 头、`Depth`、**不跟随重定向**、响应体 8 MiB 有界读取、取消穿透 |
| `WebDavCredentials.kt`（新） | `WebDavAuth`（Basic 头构造）、`WebDavCredentialStore`（密码进 `CredentialVault`）、`WebDavSession`（凭据缺失 fail-closed） |
| `WebDavAuthProvider.kt`（新） | 认证 / 会话恢复 / 登出；401 才清凭据；403/404/5xx/网络分别映射为 `AuthSessionErrorKind` |
| `WebDavBrowseProvider.kt`（新） | `PROPFIND Depth: 1` 列目录；剔除目录自身条目；跨 origin 条目丢弃；**本地切片**并如实标注无服务端分页 |
| `WebDavPlaybackProvider.kt`（新） | 直链播放：header-only Basic；拒绝 URL user-info 与跨 origin 目标；无伪造 `sessionId` |
| `WebDavProvider.kt`（重写） | `MediaProvider` + `OPTIONS` 协议探测；取消不折叠为 `ConnectionStatus(false)` |
| `WebDavProviderFactory.kt`（重写） | 装配 AUTH+BROWSE+PLAYBACK；身份世代守卫抛 `IOException`（F-C1-1 同款） |
| `build.gradle.kts` | 增加 `junit` / `mockwebserver` / `kotlinx-coroutines-test` 测试依赖 |

关键行为变更（相对骨架）：
1. `declaredCapabilities` 移除 **SEARCH**（无实现，ADR-022 禁止声明空能力）。
2. `ProviderHandle` 从"全 null"变为 `auth + browse + playback`。
3. `testConnection` 不再吞掉 `CancellationException`。

测试（`provider/webdav/src/test/kotlin/.../webdav/`）：`WebDavTestSupport.kt`（夹具/假件）、
`WebDavMultistatusParserTest.kt`（解析 + URL 契约，含 XXE 与畸形 XML）、`WebDavBrowseProviderTest.kt`
（PROPFIND 线级契约 / 自条目剔除 / 编码 / 本地切片 / 401·404·302 / 无凭据零请求 / 播放守卫）、
`WebDavAuthProviderTest.kt`（认证与会话恢复状态机）、`WebDavProviderFactoryTest.kt`
（能力审计 + 失效身份零出网 + 取消穿透）。

## 4. 下一条具体动作

1. 读取 `D:/deepseek_test/build_webdav.log`，确认 `:provider:webdav:testDebugUnitTest` 结果。
2. 失败则按编译/断言错误修复；通过则记录 XML 计数（`provider/webdav/build/test-results/`）。
3. 在 `mh-wb-webdav` 提交并推送 `workbuddy/feat-webdav-readonly`，开 Draft PR。
4. 在 `mh-wb-state` 提交并推送 `workbuddy/w0-state`。
5. W1：在独立 worktree 检出 `21231ee`，跑 `:provider:emby:testDebugUnitTest`、
   `:provider:jellyfin:testDebugUnitTest`、`:core:common:testDebugUnitTest`、
   `:feature:settings:testDebugUnitTest` 复验 F-C1-1 分层回归；设备验收登记为阻塞。

## 5. 仍需外部条件（不得在本线宣布通过）

- **设备/模拟器验收（W1 的 SAF / 八终止点 / 损坏 journal；W5 的 API 32/36 视觉门禁）**：
  需要 `adb` 可用 + 专用模拟器。当前 `adb` 不在 PATH，`ANDROID_HOME`/`ANDROID_SDK_ROOT` 未设置。
  最小条件：安装 platform-tools，或显式提供 SDK 路径并创建专用 AVD。
- **W7 Jellyfin 真实验收**：需要明确授权的测试实例（`BLOCKED_BY_SERVER`）。
- **W2 Media3 选轨**：`codex/fix-media3-track-selection` 分支仍在 `8e516e4`（= main，零提交），
  归 Agent B；未经转交不得修改其生产实现。

## 6. 边界与红线（本轮遵守）

- 未改动 `main`；未触碰 `feature/backup-restore`、`feature/playback-visual-effects`、
  `docs/1h-device-evidence` 及其他 Agent 的 worktree。
- 未做 merge / auto-merge / Release / 发行签名 / 个人设备安装。
- 未放宽 TLS、未吞异常、未删除安全检查。
- `local.properties` 为本机 SDK 路径，**不入库**。
