# 交接文档（HANDOFF）—— 每个 AI 必读

> 最后更新：2026-08-12，FINAL PATCH 4 完成（PR 分支 Home 状态闭环）。

## Patch 4（最新）：re-login 后 Home auth-state 立即刷新（PR 架构）

- `HomeViewModel.forceRestore(serverId)`：调 authenticationCoordinator.restore，覆盖现有状态，
  不受 init containsKey 去重影响。
- Navigation result（auth_changed_server_id）：AddServer 保存成功后通知 Home → forceRestore。
- 效果：SessionExpired/SignedOut → 重新登录 → 返回 Home → Authenticated（不杀 App）。
- 全项目 79 用例；assembleDebug/test/lint 通过。
- **待办（需你在 GitHub）**：更新 PR description（删旧 41@Test/31556164299 写 latest）；确认 latest-head
  CI success；重新 latest-head review；然后 merge PR #1 封板 Phase 1A。

## Patch 3（最新）

- 已把 latest main（47db322/2916e84）真正 merge 进 PR 分支，main 成为祖先（ahead 0/behind 0）。
- 冲突以 PR 架构为准（CredentialVault + AuthenticationCoordinator + providerId），未恢复 TokenStore/EmbySessionStore。
- Existing-server UI 显式化：ExistingServerMode（AUTH_RELOGIN/LOCAL_REAUTHORIZE/NONE）+ 标题/按钮区分 +
  existing 模式隐藏 ProviderGrid（锁定原 Provider）。
- 策略测试：feature/server 的 ServerClickDecision(7)+ServerEditModePolicy(2)+ServerSavePlanner(5)；全项目 79。
- 待办（需你在 GitHub 操作）：确认 latest head（b71f0a0）CI success + mergeable；更新 PR description；
  重新请求 latest-head review；然后 merge PR #1。

## 0. Patch 2（最新）：Existing Server Re-login + feature:server 测试（评审 Patch 2 规范）

- `AddServerViewModel`：`reauthorizeId` 非空时不再强制 filter LOCAL_STORAGE；按原
  `providerId` 定位 descriptor，支持 **AUTH_RELOGIN**（认证型）与 **LOCAL_REAUTHORIZE**（本地目录）。
- `HomeViewModel.clickTarget` 三路分发：LocalReauthorize / AuthRelogin / Open——认证型
  Provider 在 SignedOut/SessionExpired 时进入 existing-server re-login（**不再直接进 library**）。
- `ServerSavePlanner`（纯函数，可 JVM 单测）：复用 same id + 保留 isDefault/sortOrder/
  createdAtEpochMs/lastXxx，`updateSource=true`（updateServer，绝不 addServer 重复）；新建走 add。
- 测试：`feature/server` ServerSavePlannerTest 4 例。
- **待办（需 GitHub 操作，沙箱无法执行）**：① 更新 PR description（删旧 41@Test/Actions run，
  写 latest 真实状态）；② re-request latest-head Codex/Copilot review；③ 确认 latest head CI
  success + mergeable 后 merge PR #1。

## 1. 当前状态

- Phase 0.5 架构与 Emby Phase 1A 认证已收口在同一 PR 分支；最新组合树必须重新通过
  `assembleDebug`、`testDebugUnitTest`、`lintDebug`，旧 `8c8e0bf` CI 结果不能替代本轮验证。
- 本阶段没有实现 Emby Library/Items/PlaybackInfo，也没有进入 Phase 1B。
- LocalProvider 已从 app 私有目录迁移到 SAF 文档树；旧 `file://` 路径不再是长期方案。

## 2. 关键契约

- `MediaProvider` 只有 `serverId`、`descriptor`、`testConnection`。
- Factory 返回 `ProviderHandle`；可选能力为 auth/library/browse/playback/search/subtitle/progress。
- Factory 声明计划能力 `ProviderDescriptor`；Handle 只暴露已实现的 `runtimeCapabilities`。
  Registry 使用开放 `providerId:String`，添加页动态渲染。
- `CredentialVault` 是敏感凭据唯一持久化入口，具体实现位于 `provider:base`，底层使用
  `core:security.SecretStorage`。一个 `AuthSession` 原子包含 Credential、User、remoteServerId；
  不存在 TokenStore/EmbySessionStore。Room/DataStore 不存 Secret。
- `MediaAuthProvider` 是无状态协议适配器；`AuthenticationCoordinator` 独占 authenticate/restore/logout 的持久化和清理。
- 播放请求头属于 `PlaybackRequestContext`/MediaSource，不得恢复全局 mutable holder。
- 高频 progress 只更新内存；本地/远端经过 `ProgressSyncGate`，关键事件即时 flush。

## 3. 兼容迁移

`MediaServer.providerId` 已替代 `ServerType`。Room 表结构仍为 v1，历史列 `servers.type` 继续存在：

- 读取：Mapper 把 `EMBY`、`ALIYUN_DRIVE` 等旧枚举名转成稳定小写 ID。
- 写入：直接把 providerId 写进该列。
- 后续首次确有必要的 Room migration 再把列名改为 `provider_id`，不能静默破坏用户数据。

## 4. Provider 当前完成度

| Provider | 已完成 | V0.1 仍需实现 |
|---|---|---|
| Local | SAF 授权、浏览、content:// 播放 | 媒体扫描/元数据体验 |
| Emby | System Info、登录、恢复、验证、登出；运行时仅 AUTH | Phase 1B：Library/Items/PlaybackInfo/搜索/字幕/进度 |
| Jellyfin | Descriptor、System Info 身份探测；运行时不暴露未完成能力 | 同上，保持独立 Connector |
| WebDAV | OPTIONS/DAV 探测、受保护 PROPFIND 验证 Basic、运行时仅 Auth | 完整目录 PROPFIND、路径/转义、Range 播放 |

Emby/Jellyfin 的 UsernamePassword 不允许 pending 持久化。Emby 密码仅存在于 AuthenticateByName 请求内存，成功后
只保存 `AuthSession.AccessToken`；Jellyfin 登录未实现时必须明确失败，不得保存原始密码。

## 5. 播放与进度注意事项

- `PlayerFactory` 可以是 Singleton，因为它只创建对象；任何具体播放 Header/Cookie 都不能保存为工厂字段。
- UI 500ms 更新、本地默认 5s、Provider 默认 10s；远端调用最多等待 2s。Provider 可设
  `periodicIntervalMs=null` 只接收关键事件；关键事件使用无丢弃 Channel，不得 conflate/drop。
- Slider 只在拖动结束时触发一次 Seek；返回按钮先 STOP flush。异常销毁由 `onCleared` 兜底 final flush。
- `PlaybackSource` 仍是单主 URL 模型；未来资源集合演进必须保持每资源独立 Header/过期时间。

## 6. 测试与门禁

本阶段新增测试覆盖：

- Provider Registry 开放 ID/重复检测、Descriptor 计划能力与 Handle 运行时子集
- 原子 AuthSession、用户名密码不 defer、恢复失效/暂不可用分流、Credential codec 不出现明文
- 两个播放会话的 Authorization/Cookie 隔离与输入防御性复制
- 本地/远端独立节流与关键事件重置
- 旧 ServerType 数据映射与自定义 providerId round-trip
- Emby 登录/恢复/remoteServerId/401/403/malformed/Header/logout，Jellyfin/WebDAV 协议探测与 WebDAV 受保护认证

CI 工作流：`.github/workflows/android-ci.yml`，必须全部通过：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

本轮最终结果必须引用 reconciliation head 的新 Actions run；发布前不得沿用旧 head 的成功记录。

## 7. 下一阶段唯一入口

当前唯一动作是等待 reconciliation CI 与 Codex/Copilot review。全部通过后仍不自动合并 PR。
获得最终评审/合并决定后，Phase 1B 才可从 Libraries/Items 开始，再做 PlaybackInfo 与 progress reporter。

禁止在下一阶段顺手实现 Jellyfin、云盘、Plex、FFmpeg、MPV 或大规模 UI 重构。
