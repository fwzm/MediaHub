# provider:smb（规划中，未创建 Gradle 模块）

SMB 数据源在 MVP 闭环（Emby/Jellyfin/本地/WebDAV）稳定后接入。

计划：
- 独立 Gradle 模块 `provider:smb`，实现 `MediaProvider`（BROWSE 能力）。
- 协议库候选：jcifs-ng / smbj（需评估维护状态与 aarch64 兼容性）。
- 认证：用户名/密码（走 `core:security` 加密存储，绝不落库）。
- 目录 → `MediaItem(FOLDER)`，文件 → `MediaItem(VIDEO/AUDIO)`，播放地址为 `smb://` 会话 URL（连接期内有效）。
- 不允许把 SMB 会话信息写死或进入普通日志。

参见：docs/providers/README.md
