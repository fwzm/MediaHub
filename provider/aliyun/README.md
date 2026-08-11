# provider:aliyun（规划中，未创建 Gradle 模块）

阿里云盘数据源规划（V0.3 阶段，MVP 之后）。

关键原则（ADR-003）：
- 优先官方 Open API / OAuth。
- 播放 URL 是临时签名 URL：**只存 resource id / file id，播放时 resolvePlayback() 再解析**，过期自动重解析。
- Token 生命周期：access/refresh token 走 `core:security` 加密存储。
- 独立 Connector，明确 Experimental 标记，对 API 变化做好失败处理。
- 禁止把密钥/Cookie/Token 写死源码。

参见：docs/providers/README.md
