# 路线图（ROADMAP）

## Phase 0 —— 项目骨架（本次完成 ✅）

- [x] Kotlin + Compose 多模块工程（23 个 Gradle 模块 + 版本目录）
- [x] 统一领域模型（core:model，纯 Kotlin）
- [x] MediaProvider 统一抽象（provider:api）与注册表（provider:base，Hilt @IntoSet）
- [x] 网络层：ApiClient / MediaHttpClient / 结构化错误 / 脱敏日志
- [x] Room + DataStore + Keystore 加密存储
- [x] Media3 播放引擎封装（PlaybackEngine + 缓存 + 请求头注入）
- [x] 播放兼容性评估器（纯逻辑 + 单测）
- [x] Server 管理基础 UI（添加/测试连接/保存/列表）+ 首页 + 本地文件浏览 + 播放器页 + 设置
- [x] 文档体系（README/ARCHITECTURE/ROADMAP/TASKS/DECISIONS/CHANGELOG/HANDOFF）
- [x] 可编译（assembleDebug）+ 单测 + lint 通过

## Phase 0.5 —— 架构加固（当前）

- [x] Provider 接口隔离与类型安全能力组合
- [x] 开放 providerId、Factory 自描述与动态添加页
- [x] 加密凭据/会话生命周期
- [x] 播放请求上下文会话隔离
- [x] 分层、节流的播放进度管线
- [x] Android SAF 本地目录
- [x] Provider 协议级连接测试
- [x] CI 与核心边界单测
- [x] GitHub Actions 三项质量门禁实跑通过（run 31554659794），本阶段关闭

## V0.1 —— MVP 闭环（Emby + Jellyfin + 本地 + WebDAV + 播放器）

目标：14 项 MVP 清单（见任务要求第十八节）全部可用。

1. Emby Provider：登录（/Users/AuthenticateByName）、媒体库（/Users/{userId}/Views）、
   条目浏览/搜索/详情、播放源解析（/Items/{itemId}/PlaybackInfo，
   DirectPlay/DirectStream/Transcode 三态）、进度上报（/Sessions/Playing）、续播、收藏。
2. Jellyfin Provider：同一套能力（独立 Connector，与 Emby 共享 base）。
3. WebDAV Provider：PROPFIND 文件树、Basic 认证、直链播放（Range）。
4. 播放器：接入 PlaybackCompatibilityEvaluator 到 resolve 流程；音轨/字幕选择完善；
   外挂字幕；字幕/音频延迟；倍速/缩放/HDR 状态显示；结构化错误重试（URL 过期自动重解析）。
5. 全局搜索入口（跨已接入 Provider 并发聚合）。
6. 详情页完整化（海报/简介/演职员/版本选择）。
7. 网络安全检查：cleartext 改为 networkSecurityConfig 按域名放行。
8. 诊断页（媒体信息/解码器/码率/缓冲/网速）+ 脱敏导出。

## V0.2 —— NAS 与体验

- SMB Provider（jcifs-ng/smbj 评估）、SFTP/NFS 评估。
- 元数据刮削：TMDB（首）、Bangumi、豆瓣；NAS 文件 → 海报墙。
- 播放体验：自动连播、片头/片尾跳过、画中画、后台播放。
- 线路质量评估（RouteQualityEvaluator）与多版本选择。
- 收藏/历史/跨服务器进度合并。

## V0.3 —— 云盘

- 阿里云盘（官方 Open API/OAuth，临时签名 URL 动态解析）→ 百度 → 夸克 → 移动云盘 → 天翼。
- 云盘文件树浏览、搜索、限速处理、链接过期自动重解析。
- 下载管理 / 离线播放（独立 DownloadManager，与播放缓存分离）。

## V1.0 —— 聚合媒体库

- UnifiedLibrary：多源合并浏览、GlobalSearch 聚合、去重、最佳线路自动推荐。
- 多版本选择 UI（Emby A 4K DV / NAS 4K HDR10 / 云盘 1080P）。
- 账号同步 / 云端配置备份；Android TV 评估；投屏（DLNA/Chromecast）。

> 原则：每阶段先"能跑"再"架构正确"再"功能完整"；禁止一次开发十种云盘。
