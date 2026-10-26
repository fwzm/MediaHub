# 设计文档契约核对（2026-10-26）

本增量基于 `2f4909a79befbab9381e38d1768ae2693bcf8d73`，只调整现有的 API、Provider、播放器设计文档
与本轮交接记录。生产代码、测试代码、Gradle、CI 和 APK 没有改动。

## 修正内容

- API 文档改用实际 `descriptor`、`ProviderHandle`、`MediaProgressProvider.reportFinalProgress` 等接口；
  保留当前 ServerType 持久化与运行时能力子集校验，不导入旧集中认证或开放 ID 模型。
- 凭据说明区分 TokenStore、非敏感会话元数据、CredentialVault 以及 WebDAV 的身份绑定加密记录。
  这些机制不等同于已实现所有云盘认证或跨进程凭据一致性。
- 四个实际工厂的非空字段逐一与源码核对：Emby 9 项、Jellyfin 7 项、WebDAV 5 项、Local 4 项。
  `subtitle` 与内嵌轨道、`subtitleDiscovery` 分别说明，避免用单个规划枚举推断字幕功能。
- 播放器文档补齐每个 MediaSource 的 Header 快照、关键事件自己的不可变进度、独立周期采样与显式 final flush。
  既有视觉实现和生命周期正文按字节保留，仅补齐验证命令的运行身份参数和入口条件。
- 明确现有 file 根与仍未接线的完整 SAF 流程，以及旧分支 WebDAV 字符集策略与当前 UTF-8 策略的差异。

## 实际验证

机械核对覆盖 37 个本地链接、实际工厂字段集合、关键 API 符号及视觉正文保留。
文档输入和引用源码的 SHA-256 在检查前后保持一致。

现有 `:provider:api:test :provider:base:testDebugUnitTest` 实际执行 31 项，全部通过，
0 失败、0 错误、0 跳过，退出码 0；57 项 Gradle 任务全部执行，耗时 4 分 28 秒。
使用 JDK21、`--rerun-tasks --no-build-cache --no-daemon --no-parallel --max-workers=1`。
这是文档涉及的接口和注册约束范围，不是全项目回归，也没有为文档改动编造生产 RED。

父提交 `2f4909a7` 的 Windows CI `40279925801` 原始修订和报告已另行核验：
1592 项主测试、104 项 WebDAV 测试全部通过；TLS 套件 27 项实际执行，包含新的预算、截止时间和显式取消用例。
同一运行另有 11+12 项实际 Kotlin/Windows CLI 对端 XML，0 失败、0 跳过。
这些结果属于 `2f4909a7`，不能改写为本轮最终文档提交的 CI 结论。

## 仍需处理

当前 WebDAV OPTIONS 成功分支接受 2xx，但没有校验 DAV 声明。
[RFC 4918 第10.1与18.1节](https://www.rfc-editor.org/rfc/rfc4918.html#section-10.1)
要求 DAV 资源在 OPTIONS 响应中声明 class 1。旧分支的协议识别契约因此继续作为独立生产任务处理，
本轮只如实记录当前行为，不把普通 HTTP 2xx 当成已证明的 DAV 身份。

下一步应先用 MockWebServer 复现缺失、空白、无效或混淆声明的误判，再最小修复；
同时保留 401/403 的认证语义、取消传播和禁止自动重定向的边界。真实账号不作扫描夹具。

三份文档重整不代表旧 SAF、开放 Provider ID、集中认证或字符集回退已经实现；对应生产契约继续独立记账。
用户曾确认手机空闲，但本次 `adb devices -l` 返回空列表，随后明确表示暂时无法连接、继续本机工作。
因此真机步骤为 `BLOCKED_ENV`，没有安装、读取账号、操作手机或改动用户数据。
最终新提交 CI 仍须绑定其字面 SHA 核对，PR 保持 Draft，G7 为 `DO_NOT_MERGE`。
