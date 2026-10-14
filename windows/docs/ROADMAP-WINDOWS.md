# MediaHub Windows 路线图

状态标记：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / NOT_RUN / NOT_IMPLEMENTED。

## W0 — 仓库审计与能力梳理 ✅ 本轮完成

- 远端 main / PR / CI 实际状态核对（`docs/W0-AUDIT.md`）。
- Windows 能力映射、风险清单、技术选型 ADR（W001–W003）。
- 工具链可行性实测（WinUI 3 构建 + 运行）。

## W1 — 最小完整播放器 ✅ 本轮完成（EXECUTED_PASS）

- libmpv 嵌入 WinUI 3（`--wid`），D3D11 硬解，真实文件播放。
- 播放/暂停/进度拖动/音量/字幕/音轨/倍速/全屏/控制栏自动隐藏。
- 本地媒体浏览、继续观看、设置、命令行打开文件。
- 引擎集成测试 `MpvProbe` 与 99 条单元测试。

**遗留**：全屏 UI 截图确认、控制栏按钮逐一点击、实际听声、HDR。

## W2 — 媒体源接入 🟡 本轮推进

**已完成**
- 共享领域契约；每源网络策略；重定向凭据作用域；脱敏。
- Local / Emby / Jellyfin provider（协议层实现 + 契约测试）。

**未完成（下一批可直接执行）**
1. **媒体源添加/编辑 UI**（`SourcesPage` 目前只列出现有源）。
   验收：可用真实 Emby 完成"探测 → 登录 → 保存 → 出现在源列表"。
2. **凭据加密存储**：接入 DPAPI（`ProtectedData`）或 Windows Credential Manager；
   当前设置文件**刻意不保存密码与 Token**，重启需重新登录。
3. **海报墙与详情页**：接入图片管线（凭据按源注入，跨 origin 剥离）。
4. **跨源聚合搜索**：`IProviderSearch` 已就绪，缺编排与去重。
5. **真服端到端验证**：Emby 与 Jellyfin 各一台，覆盖登录/浏览/播放/进度回写。
   —— 在此之前 Emby/Jellyfin 只能标记 IMPLEMENTED_UNVERIFIED。
6. WebDAV provider（Android 已有只读实现，可作为协议参照）。

## W3 — Windows 播放体验

- 播放列表、自动连播下一集、断线恢复、播放进度恢复的完整闭环。
- 文件类型关联（HKCU 注册）+ 拖放打开。
- 多窗口、多显示器、窗口状态恢复、系统媒体按键。
- 快捷键可配置；简洁/详细信息模式的 UI 打磨。

## W4 — 可靠性与平台优化

- 代理一致性实测：系统代理 / PAC / TUN / VPN 组合下的出口核对（API 与 mpv 分别验证）。
- 远程文件缓存：自适应预读、块大小按实测调整（**不得默认 16 MB 为最优**）、
  请求去重、Range 合并、并发上限、失败退避、退出清理。
- 双显卡选择与设备丢失恢复；软件回退故障注入测试。
- HDR：四种桌面/片源组合实测；PQ/HLG、tone mapping、Advanced Color、多显示器 HDR 变化。
- 音频：WASAPI 独占（失败即回退共享并提示原因）、HDMI 直通能力探测、音画同步（基于引擎时钟 +
  音频时间戳，**禁止把固定 30/50 ms 写成通用补偿常量**）。
- 性能基准与功耗：按 `docs` 场景矩阵采集 CPU/GPU/内存/掉帧/缓存命中；MMCSS 与线程优先级
  调整必须有实测依据。

## W5 — 分发验证

- `fetch-libmpv.ps1` 补齐 SHA-256 固定值。
- `dotnet publish` 便携包 + MSIX 双通道构建；代码签名与干净机安装实测。
- 第三方许可证清单随产物分发；回归测试与发布前审查。
