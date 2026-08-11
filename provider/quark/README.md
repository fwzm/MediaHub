# provider:quark（规划中，未创建 Gradle 模块）

夸克网盘数据源规划（V0.3+）。
- 若仅能依赖 Cookie / 非公开接口：独立 Connector、Experimental 标记、
  对接口变更做失败兜底、凭据走加密存储；不做绕过风控/会员限制的实现。
- 播放链接临时性：只存 file id，播放时 resolve（ADR-003）。

参见：docs/providers/README.md
