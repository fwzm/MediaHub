# 交接文档（HANDOFF）—— 每个 AI 必读

> 最后更新：2026-08-12，PR #1 review 修复完成（分支：agent/phase-0-5-architecture-hardening，未 merge）。
> 上一条：Phase 0.5 Architecture Hardening。

## 0. PR #1 review 修复（最新）

- Copilot（10 项）+ Codex（P1×1 + P2×7）review 已全部处理，逐条说明见 `docs/REVIEW-PR1.md`。
- 覆盖：SAF 嵌套目录（tree-backed）、LOCAL REAUTH_REQUIRED、关键事件 Channel 保序、
  PLAY 语义（playWhenReady）、MediaTypeGuesser、WebDAV Basic charset（RFC 7617）、
  WebDAV PROPFIND 受保护验证、系统 Back、testConnection 默认参数兼容、WebDAV 401/403 AUTH_REQUIRED。
- 本地验证：assembleDebug / testDebugUnitTest（47）/ lintDebug 通过。
- **待办：latest PR head 的 GitHub Actions 全绿后，人工 review diff 再决定 merge（本 AI 不 merge）。**

## 1. 当前状态

- Phase 0.5 已完成：GitHub Actions 的 `assembleDebug`、`testDebugUnitTest`（41 个 `@Test`）
  与 `lintDebug` 全部通过；最终状态见 PR #1 的 head check。
- 本阶段没有实现完整 Emby/Jellyfin/WebDAV 业务 API，也没有进入 V0.1。
- LocalProvider 已从 app 私有目录迁移到 SAF 文档树；旧 `file://` 路径不再是长期方案。

## 2. 关键契约

- `MediaProvider` 只有 `serverId`、`descriptor`、`testConnection`。
- Factory 返回 `ProviderHandle`；可选能力为 auth/library/browse/playback/search/subtitle/progress。
- Factory 声明计划能力 `ProviderDescriptor`；Handle 只暴露已实现的 `runtimeCapabilities`。
  Registry 使用开放 `providerId:String`，添加页动态渲染。
- `CredentialVault` 是敏感凭据唯一持久化入口，具体实现位于 `provider:base`，底层使用
  `core:security.SecretStorage`。Room/DataStore 不存 Secret。
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
| Emby | Descriptor、System Info 身份探测；运行时不暴露未完成能力 | 登录、Library、播放源、搜索、字幕、进度 |
| Jellyfin | Descriptor、System Info 身份探测；运行时不暴露未完成能力 | 同上，保持独立 Connector |
| WebDAV | Descriptor、OPTIONS/DAV 探测、Basic 加密会话；运行时仅 Auth | PROPFIND、路径/转义、Range 播放 |

Emby/Jellyfin 登录暂未实现时，`AuthenticationCoordinator` 会把用户输入加密暂存而不是丢弃。V0.1 登录成功后
必须用 Token Session 原子替换 pending password 并删除原始密码。

## 5. 播放与进度注意事项

- `PlayerFactory` 可以是 Singleton，因为它只创建对象；任何具体播放 Header/Cookie 都不能保存为工厂字段。
- UI 500ms 更新、本地默认 5s、Provider 默认 10s；远端调用最多等待 2s。Provider 可设
  `periodicIntervalMs=null` 只接收关键事件；关键事件流不得 conflate。
- Slider 只在拖动结束时触发一次 Seek；返回按钮先 STOP flush。异常销毁由 `onCleared` 兜底 final flush。
- `PlaybackSource` 仍是单主 URL 模型；未来资源集合演进必须保持每资源独立 Header/过期时间。

## 6. 测试与门禁

本阶段新增测试覆盖：

- Provider Registry 开放 ID/重复检测、Descriptor 计划能力与 Handle 运行时子集
- 成功认证 session 保存、未实现认证的 pending 保存、Credential codec 不出现明文
- 两个播放会话的 Authorization/Cookie 隔离与输入防御性复制
- 本地/远端独立节流与关键事件重置
- 旧 ServerType 数据映射与自定义 providerId round-trip
- Emby/Jellyfin/WebDAV 协议身份探测（MockWebServer）

CI 工作流：`.github/workflows/android-ci.yml`，必须全部通过：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

本次已验证结果：`BUILD SUCCESSFUL in 9m 31s`，1005 actionable tasks（838 executed / 167 from cache）。

## 7. 下一阶段唯一入口

Phase 0.5 门禁全绿后进入 Emby：先实现 AuthenticateByName → Token Vault → current user，再做 Libraries/Items，
然后 PlaybackInfo 与 progress reporter。端点、Header 和 DTO 必须逐项对照官方文档，并为网络契约加 MockWebServer 测试。

禁止在下一阶段顺手实现 Jellyfin、云盘、Plex、FFmpeg、MPV 或大规模 UI 重构。
