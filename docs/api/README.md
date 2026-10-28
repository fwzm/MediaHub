# 内部 API 契约

本文描述当前仓库的模块接口。架构选择见 [DECISIONS.md](../../DECISIONS.md)，实现与测试链接是核对入口；
历史分支中的类型名或阶段状态不能直接代替当前接口。

## Provider 注册与能力

[`MediaProvider`](../../provider/api/src/main/kotlin/com/mediahub/provider/api/MediaProvider.kt) 保留服务器身份、
`descriptor` 和无参数的 `testConnection()`。认证、媒体库、详情、浏览、播放、查询、搜索、字幕、进度等
由独立接口表达，并通过 [`ProviderHandle`](../../provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderHandle.kt)
的可空字段组合。

[`MediaProviderFactory`](../../provider/api/src/main/kotlin/com/mediahub/provider/api/MediaProviderFactory.kt)
自报 `descriptor`，`create(server)` 返回句柄。Hilt `@IntoSet` 注册工厂，
[`DefaultProviderRegistry`](../../provider/base/src/main/kotlin/com/mediahub/provider/base/DefaultProviderRegistry.kt)
按 `ServerType` 查找；未知类型返回空，重复类型或重复描述 ID 会在注册时失败。
现阶段数据库保留 `ServerType`，开放 `providerId` 持久化属于 ADR-015 的后续迁移。

`ProviderDescriptor.declaredCapabilities` 是规划声明；`ProviderHandle.runtimeCapabilities` 由非空字段推导。
构造句柄时强制要求运行时能力属于声明能力。调用方先读取 `handle.playback`、`handle.query` 等字段，
再使用对应接口，不能因为描述里计划支持某能力就调用空字段。

具体能力接口和参数见 [`CapabilityProviders.kt`](../../provider/api/src/main/kotlin/com/mediahub/provider/api/CapabilityProviders.kt)。
例如动态解析播放源由 `MediaPlaybackProvider.resolvePlayback(item, options)` 提供，
分页使用 `PageRequest` / `PagedResult`；排序与筛选应由支持查询能力的 Provider 在分页前完成。

## 认证与凭据

`MediaAuthProvider` 提供 `authenticate`、`refreshSession`、`restoreSession`、`logout` 和 `currentUser`。
恢复结果为 `AuthSessionState`，由各 Provider 根据真实响应、身份及当前会话世代判定；网络或协议暂时不可用
不能无条件当作已登出。不要导入旧分支的集中 `AuthenticationCoordinator` / `SessionRestoreResult` 接口。

- [`TokenStore`](../../core/security/src/main/kotlin/com/mediahub/core/security/TokenStore.kt) 管理会话令牌；
  [`CredentialVault`](../../core/security/src/main/kotlin/com/mediahub/core/security/CredentialVault.kt) 管理按种类存取的长期凭据。
  生产敏感存储经 Keystore 加密，禁止把密码、Token、Cookie 明文写入 Room、DataStore 或日志。
- Emby/Jellyfin 登录密码只作为认证输入；Token 进入 TokenStore，用户与远端身份等非敏感元数据
  分别由各自的 SessionStore 保存，不能把两者描述成旧架构的一条统一 AuthSession 记录。
- WebDAV 的长期密码按协议需要保存，但只写入带版本、地址及用户名身份绑定的加密 `WEBDAV_CREDENTIAL` 记录。
  [`WebDavCredentialCoordinator`](../../provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCredentialCoordinator.kt)
  在进程内按服务器串行化世代与凭据操作；网络等待在锁外。该机制不承诺跨进程或跨设备一致性。
- 长期凭据的种类预留不等于对应云盘/OAuth 流程已经接线；当前可用能力仍以工厂句柄为准。

## 进度与事件

[`PlaybackEventMessage`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/PlaybackEvent.kt)
的 `kind` 保留原枚举语义；生产关键控制事件可携带 `PlaybackEventSnapshot`，把事件发生时的不可变进度
一起传递，避免异步消费者重新读取已经变化的位置。

[`ProgressSyncCoordinator`](../../player/engine/src/main/kotlin/com/mediahub/player/engine/ProgressSyncCoordinator.kt)
将本地快照默认每 5 秒采样、远端上报默认每 10 秒采样，两条周期独立。
远端间隔可由 `MediaProgressProvider.remoteReportIntervalMs` 指定。
Pause/Seek/Ended 使用事件自身进度立即刷新；Stopped 只通知状态。
退出由 [`PlayerViewModel.stopAndFlush()`](../../feature/player/src/main/kotlin/com/mediahub/feature/player/PlayerViewModel.kt)
显式停止引擎与周期工作、执行一次 `flushFinal`，随后释放引擎。
远端最终操作走 `MediaProgressProvider.reportFinalProgress`，与普通 `reportProgress` 区分，带有限等待。

## 错误与证据

网络异常、`ProviderException` 和 [`PlaybackError`](../../core/network/src/main/kotlin/com/mediahub/core/network/PlaybackError.kt)
分别保留各层语义。UI 展示用户可读文案，诊断信息经过 Logger/Redactor 脱敏，不展示原始堆栈或凭据。

门禁按提交、模块、套件与方法检查实际报告，见 [Android CI](../../.github/workflows/android-ci.yml)。
单元测试、模拟器、CLI 互操作和真机结果分别记录；编译或旧提交的结果不能直接提升为当前提交的完整验收。
