# MediaHub Windows 路线图

状态标记：EXECUTED_PASS / IMPLEMENTED_UNVERIFIED / SERVER_UNVERIFIED / NOT_RUN / NOT_IMPLEMENTED。

## W0 — 仓库审计与能力梳理 ✅ 完成

远端 main / PR / CI 实际状态核对，能力映射、风险清单、技术选型 ADR（W001–W003）。

## W1 — 最小完整播放器 ✅ 完成（EXECUTED_PASS）

libmpv 嵌入、D3D11 硬解、本地真实文件播放、控制层、设置、命令行打开文件。

## W2 — 媒体源、凭据与远端播放 ✅ 本轮完成

**已完成并实测**
- 凭据库（DPAPI CurrentUser，版本化/原子写/损坏隔离/异账户识别）与 URL 规范化。
- 媒体源管理 UI：添加（探测→登录→保存）、测试、重新登录、删除（同时清除凭据）。
- 库列表 / 海报墙 / 详情页 / 播放 / 搜索 / 继续观看页面，左侧导航全部可达。
- **引擎网络边界**：实测确认引擎会把凭据投递给跨 origin 重定向目标、且代理受环境变量影响，
  因此改为经本地转发取流，引擎不再持有服务器凭据（ADR-W006）。
- **经真实 UI 的远端播放**：EXECUTED_PASS（模拟服务器）。

**未完成（下一批可直接执行）**
1. ~~真实 Emby / Jellyfin 服务器端到端~~ —— **已完成**：Jellyfin 12.2 与 Emby 4.10.1.0 均实测登录、浏览、
   播放、进度回写（见 W4/W5 轮次证据），过程中暴露并修掉三个真实协议缺陷。
2. 凭据库的真实第二 Windows 账户 / 跨机器行为验证。
3. 收藏（Emby/Jellyfin 的 `IsFavorite`）与"继续观看"的写入闭环。
4. 剧集页在有数据的服务器上的季/集验证（模拟服务器剧集库为空）。
5. 全屏切换与控制栏按钮的逐一点击实测（W1 遗留）。
6. ~~WebDAV provider~~ —— **已完成**：只读实现 + 真实 WsgiDAV 4.3.3 端到端（Gate A 目录与媒体路径实测，
   见 [W5-1-R3-GATE-A.md](W5-1-R3-GATE-A.md)）。

## W5.1 — 逐项 GUI 验收（进行中）

用真实进程 + UI Automation 逐条核对已有能力，不以“页面能点开”代替成品验收。已完成轮次：

- R2/R3 Gate A 打通到「目录可见 + 媒体 GET」：[W5-1-R3-GATE-A.md](W5-1-R3-GATE-A.md)
- R4 Gate A 后半程（Seek、回环释放）：[W5-1-R4-GATE-A-SEEK-RELEASE.md](W5-1-R4-GATE-A-SEEK-RELEASE.md)
- R4/R5 Gate B1（文件菜单与选择器）：[W5-1-R4-GATE-B1.md](W5-1-R4-GATE-B1.md)、[W5-1-R5-GATE-B1.md](W5-1-R5-GATE-B1.md)
- R6 音频播放 + 三条选择器路径 + 面板缺陷：[W5-1-R6-AUDIO-PICKERS.md](W5-1-R6-AUDIO-PICKERS.md)
- R7 多文件临时队列（B2）、WebDAV 目录与错误边界（A3）、R6 回归（C）：[W7-EVIDENCE.md](W7-EVIDENCE.md)

验收过程中修掉的**产品**缺陷：音轨/字幕面板无法关闭、面板每 100 ms 重建、`.aac` 等音频不可达、
旧文件选择器永不返回、字幕一次性提示被快照覆盖。

R7 再修掉七个：切项后新视频冻结在 0:00（mpv 的 `pause` 跨 `loadfile` 保留）、本地文件永不触发自动跳项
（本地分支提前 return 掉 EOF 判断）、`DecodeEndFile` 不闩锁 EOF、面包屑显示未解码 URL 且根目录「返回上级」可用、
列举失败时页头不更新（用户被困在报错目录）、启动时不可达的来源在媒体库页消失（离线→重试不可达）、
慢服务器上第二次导航被静默丢弃。

仍在队列：收藏与继续观看写入闭环、剧集自动连播的 GUI 验收、Emby 完整播放与进度回写、
Jellyfin 中断恢复（服务端 playstate 判定，未解决）。

## W3 — Windows 播放体验

- 播放列表、自动连播下一集、断线恢复、播放进度恢复闭环。
- 文件类型关联（HKCU）+ 拖放打开；多窗口、多显示器、窗口状态恢复、系统媒体按键。
- 快捷键可配置；详情页简洁/详细模式打磨。

## W4 — 可靠性与平台优化

- 代理一致性实测：系统代理 / PAC / TUN / VPN 组合下 API 与引擎的出口核对
  （转发层使得引擎出口＝应用出口，但**仍需实测确认**）。
- 远程文件缓存：自适应预读、块大小按实测调整（**不得默认 16 MB 为最优**）、
  请求去重、Range 合并、并发上限、失败退避、退出清理。
- 双显卡选择与设备丢失恢复；软件回退故障注入。
- HDR 四种组合实测；PQ/HLG、tone mapping、Advanced Color、多显示器 HDR 变化。
- 音频：WASAPI 独占（失败回退共享并提示原因）、HDMI 直通能力探测、
  音画同步（基于引擎时钟 + 音频时间戳，**禁止固定 30/50 ms 常量**）。
- 性能与功耗基准；MMCSS / 线程优先级调整必须有实测依据。

## W5 — 分发验证

- **解决 libmpv 的 GPL 义务**（自建 LGPL 配置，或按 GPL 提供 Corresponding Source）；
  在此之前**不得正式分发**。见 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)。
- 便携包 + MSIX 双通道构建；代码签名；干净机安装实测。
- 第三方许可证清单随产物分发；回归测试与发布前审查。
