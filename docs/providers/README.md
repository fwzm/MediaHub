# 数据源（Provider）设计

## 装配与当前能力

最小 `MediaProvider`、可选能力接口与 `ProviderHandle` 的契约见 [内部 API](../api/README.md)。
工厂经 Hilt `@IntoSet` 注册，描述用于展示规划能力，非空句柄字段用于判断当前可用能力。
注册表仍按 `ServerType` 查找；新增开放 Provider ID 的数据库迁移尚未采用。

以下是当前工厂实际装配的字段，不是所有服务版本或设备的兼容性承诺：

| 工厂 | 当前非空能力字段 |
|---|---|
| [Emby](../../provider/emby/src/main/kotlin/com/mediahub/provider/emby/EmbyProviderFactory.kt) | auth、library、detail、playback、query、search、identityLookup、progress、favorite |
| [Jellyfin](../../provider/jellyfin/src/main/kotlin/com/mediahub/provider/jellyfin/JellyfinProviderFactory.kt) | auth、library、detail、playback、search、progress、favorite |
| [WebDAV](../../provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavProviderFactory.kt) | auth、browse、detail、playback、subtitleDiscovery |
| [Local](../../provider/local/src/main/kotlin/com/mediahub/provider/local/LocalProviderFactory.kt) | browse、detail、playback、subtitleDiscovery |

没有出现在表中的字段保留为空。尤其 `subtitle` 列表接口为空不等于播放器没有内嵌字幕：
后端轨道、播放源附带的字幕和同目录外挂发现分别走各自路径，不能用单个规划枚举推断全部字幕能力。
其他计划中的 Provider 以描述的状态及实际句柄为准。

Remote DTO 与协议解析放在具体 Provider，输出统一 Domain Model。UI 不处理各源原始 JSON，
也不通过判断具体 Provider class 来代替运行时能力检查。

## 连接、认证与存储

UI 调用 `handle.provider.testConnection()`，具体实现负责探测协议：

- [Emby](../../provider/emby/src/main/kotlin/com/mediahub/provider/emby/EmbyProvider.kt) 与
  [Jellyfin](../../provider/jellyfin/src/main/kotlin/com/mediahub/provider/jellyfin/JellyfinProvider.kt)
  分别使用自己的公开信息接口和身份判定；端点解析、认证与响应映射不能混用。
- [WebDAV](../../provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavProvider.kt)
  发 OPTIONS，成功状态还须有 DAV class 1 声明；重复响应头按列表解析，扩展 URI 内的数字不作为 class 标记。
  该检查确认协议声明，实际认证另经受保护的 PROPFIND 验证；状态文案不回显服务端的原始能力载荷。
- Local 检查配置的本地根目录；完整的系统文档树授权路径仍见下节的边界。

Emby/Jellyfin 使用独立的 AuthProvider、TokenStore 与各自的非敏感 SessionStore；登录后不保存密码。
恢复时核对远端身份与会话，确定失效与暂时不可用分别处理。具体行为由各 Provider 的连接、认证、恢复隔离测试约束。

WebDAV 认证及恢复的 Depth:0 响应须为有效 DAV multistatus，并包含同 origin、匹配配置根地址的成功资源状态。
HTML、空结果或其他资源的元数据不触发保存密码或发布已认证状态；无效恢复响应保留原凭据并报告响应异常。

WebDAV 的 [`WebDavCredentialStore`](../../provider/webdav/src/main/kotlin/com/mediahub/provider/webdav/WebDavCredentials.kt)
把身份和密码写进一条加密记录，按规范化地址、用户名及进程内世代限制旧 handle/旧认证结果。
当前 Basic 头按 UTF-8 编码；旧分支的另一种字符集重试策略并未采用，不能声称已支持该兼容路径。
禁止把 `user:password` 拼进 URL，禁止日志输出 Basic、Token 或密码原文。

## Local 与 SAF 边界

[`AppLocalRootProvider`](../../app/src/main/kotlin/com/mediahub/app/di/AppLocalRootProvider.kt)
当前提供应用私有外部目录的 file 根，LocalProvider 通过现有根进行目录浏览、详情、播放及同目录字幕发现。
[`LocalRootProvider.contentRoots()`](../../provider/local/src/main/kotlin/com/mediahub/provider/local/LocalRootProvider.kt)
默认返回空列表。ADR-020 预留的系统目录选择、持久 URI 授权与完整 content 文档树接线仍属后续工作。
仓库存在相关辅助类型不等于这条产品流程已完成；也不能因为旧分支文档写“已实现”就把它算作当前能力。

## 播放、分页与验证

临时播放地址在实际播放时解析，来源认证、Header/Cookie 归属与重定向策略由 Provider/播放管线约束。
不要把短期签名地址当成永久媒体地址写入数据库。
列表、查询与搜索使用分页；服务端排序/筛选能力在分页前使用，不能仅对已返回的一页做本地排序冒充全库结果。

变更协议行为前核对实现、相关 ADR、官方协议资料及 MockWebServer 契约测试。
真实服务账号和媒体不作为自动扫描夹具；原始日志、凭据和 UI dump 不进入仓库。
当前提交的单元、模拟器、实际 CLI 与真机验证结果分别登记，不能互相替代。
