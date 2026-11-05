# W0 — 仓库审计与能力梳理（2026-10-14）

审计对象：`https://github.com/fwzm/MediaHub`，本轮以 `git fetch --all --prune --tags` 之后的远端实际状态为准，
不沿用任何历史结论。

## 1. 远端真实状态

| 项 | 值 | 证据 |
| --- | --- | --- |
| 默认分支 | `main` | `git rev-parse origin/main` |
| 审计时 main | `1246313c7a66d492258b5b4b20b51cbdcf2da9cc` | 同上 |
| main 最新提交 | Merge pull request #27（A4 收敛闭环文档） | `git log origin/main --oneline` |
| 本地分支 | 40+，含多条 `codex/*`、`a/*`、`feature/*` | `git branch -a` |
| CI | 仅 `.github/workflows/android-ci.yml`（Android 单流水线） | 目录列举 |

### PR 台账（`gh pr list --state all`，共 31 条）

**OPEN（4 条）** — 全部为 Draft，本轮未触碰：

| PR | 分支 | 标题 | 可合并性 |
| --- | --- | --- | --- |
| #31 | `feature/m4-legacy-verification` | docs(m4): 遗留问题核验报告（8 项） | MERGEABLE |
| #30 | `feature/m2-playback-tuning` | feat(player): 播放参数产品化（ADR-044） | MERGEABLE |
| #29 | `feature/m3-cloud-strm-contract` | docs(arch): 云盘与 STRM 接入契约 ADR-043 | MERGEABLE |
| #28 | `feature/m1-adr004-device-routing` | feat(player): 设备能力证据驱动引擎选路（ADR-042） | MERGEABLE |

**OPEN 且长期滞留（2 条，需人工决策，本轮不处置）**：`#16`（docs/1h-device-evidence）、
`#20`（docs/workbuddy W0 状态核对）、`#6`（codex/post-1d-review-hardening，`CONFLICTING`）。

> PR #25 的实际状态：**已合并**（2026-10-14T00:37Z，base 为 `a/joint-candidate-a4`，非 main）。
> 它作为 A4 联合候选链的一环并入 PR #24 后进入 main。本报告不使用 PR #25 作为"进行中"的依据。

**MERGED（23 条）**：#2–#5、#7–#15、#17–#19、#21、#23–#27。
**CLOSED（2 条）**：#1、#22（#22 以"被主线实现取代"关闭）。

```
本轮新增分支：feature/windows-client（基于 1246313），不直接提交 main。
本轮未合并、未 rebase、未删除任何既有 Android 分支或 PR。
```

## 2. Android 侧已具备的能力（用于 Windows 能力映射）

### 2.1 模块与依赖方向（`settings.gradle.kts`，23 个模块）

```
core:        common / model / network / database / security / logging / ui
player:      engine / mpv / compatibility
provider:    api / base / emby / jellyfin / webdav / local（+ smb 等占位 README）
metadata
feature:     home / server / library / detail / search / settings / player
```

关键不变量（`ARCHITECTURE.md`）：UI 不接触原始 JSON；Provider 做 Remote→Domain 映射；
禁止 `if (type == EMBY)` 分支，能力差异一律走 `ProviderCapability`。

### 2.2 领域契约（Windows 端需保持同一语义）

| Android 契约 | 位置 | Windows 对应 |
| --- | --- | --- |
| `MediaProvider` + 能力组合（ADR-014） | `provider:api` | `ProviderHandle` + 可选接口 |
| `ProviderDescriptor` 声明能力 vs `ProviderHandle` 运行时能力（ADR-022） | `provider:api` | 同名语义，`RuntimeCapabilities` 由接口存在性推导 |
| `ProviderException` 结构化错误 + cause 消毒（A3-4） | `provider:api` | `ProviderException` / `ProviderErrorCode` |
| `MediaItem`/`PlaybackSource`/`PlaybackProgress` | `core:model` | `MediaHub.Domain` |
| ADR-003：播放 URL 播放时解析，绝不落库 | `DECISIONS.md` | 同一约束，见 `PlayerHostService` |
| ADR-017：进度三档节流（本地 5s / 远端 10s / seek·pause·ended 立即） | `player:engine` | `ProgressSyncPolicy` |
| ADR-030：跨 origin 重定向剥离凭据 | `core:network` | `OriginScopedCredentialHandler`（改为显式跟随重定向） |
| ADR-026：Token 只走 Header，禁止进 URL | `provider:emby` | 同一约束，见 `MediaBrowserApi` |
| ADR-040：进度上报恒发 PositionTicks + PlaySessionId 必需 | `provider:emby` | 同一约束，见 `MediaBrowserProvider.ReportAsync` |

### 2.3 媒体源现状（以代码为准）

| 来源 | Android 状态 | Windows 本轮状态 |
| --- | --- | --- |
| 本地存储 | 已实现（SAF 文件树） | **已实现**（文件系统 + 映射盘 + UNC） |
| Emby | 已实现（AUTH/LIBRARY/DETAIL/PLAYBACK/PROGRESS/SEARCH/SUBTITLE） | **已实现**（同协议契约，未经真机对服验证） |
| Jellyfin | 已实现（1G 封板，与 Emby 共享协议） | **已实现**（共享 MediaBrowser 实现） |
| WebDAV | 已实现（只读） | 未实现（**不得标记为支持**） |
| Plex / SMB / 云盘 | 占位或规划 | 未实现 |
| 播放引擎 | Media3 + mpv 双内核（ADR-034） | libmpv（D3D11 嵌入） |

### 2.4 测试夹具与约定

- 1275 个 JVM 单测（`HANDOFF.md` 2026-10-14 记录）覆盖 domain/provider/network/player。
- 约定：**红绿证据**（先复现失败再修）、**exact-head CI**、**DEVICE UNVERIFIED 不简写为通过**。
- Windows 端继承同一条纪律：见 `docs/W1-EVIDENCE.md`。

## 3. 风险清单

| # | 风险 | 影响 | 本轮处置 |
| --- | --- | --- | --- |
| R1 | libmpv 无 D3D11 render API，无法直接喂 `SwapChainPanel` | 影响视频表面方案选择 | 已实测核对头文件，见 ADR-W002；采用 `--wid` |
| R2 | WinUI 3 XAML 无法叠加在外部子 HWND 之上（airspace） | 影响控制层设计 | 控制层外挂到视频矩形之外，见 ADR-W002 |
| R3 | Windows 代理多层共存（WinINet/WinHTTP/PAC/TUN/VPN） | 出口不一致、凭据泄漏 | 每源 `NetworkPolicy` + 显式重定向跟随 |
| R4 | Emby/Jellyfin 需要账号才能端到端验证 | 无法声明"真服验证" | 标记 **DEVICE/VENDOR UNVERIFIED** |
| R5 | HDR / 多显卡 / 音频独占待实测 | 高级播放能力未证实 | 排入 W4，本轮不声称支持 |
| R6 | 本机为 150% DPI 缩放 | 子窗口几何易错 | 已在代码中按 `GetDpiForWindow` 换算并验证 |
| R7 | 引擎事件线程调用 mpv API 会死锁 | 表现为"能播但 UI 不动" | 已定位并修复，见 W1 证据 |
| R8 | `mpv_observe_property(MPV_FORMAT_NONE)` 语义与 `mpv_set_property` 不同 | 属性事件静默丢失 | 已定位并修复，见 W1 证据 |

## 4. 结论

Android 侧处于 **A4 收敛后**的稳定态：main 可构建、CI 绿、4 条 Draft PR 待处置。
本轮据此建立独立的 Windows 客户端分支，**不修改任何 Android 代码**，
以共享领域契约（而非共享源码）保持一致。
