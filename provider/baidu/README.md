# provider:baidu（规划中，未创建 Gradle 模块）

百度网盘数据源规划（V0.3+）。
- 优先百度开放平台 OAuth / 官方 API。
- 播放 URL 临时签名，播放时动态解析（ADR-003）。
- 速率限制：注意官方频控，做好 429 处理与退避。
- 独立 Connector + Experimental 标记；凭据加密存储。

参见：docs/providers/README.md
