# A2 轮联合候选 — B/C 手动审查交接包

生成：2026-09-21（Agent A / zcode 第二轮手动批次）。全部 SHA 已在本地解析核实；短 SHA 仅用于行文定位。

## 1. 分支与提交（审查对象）

| 分支 | head | 内容 | 来源 |
| --- | --- | --- | --- |
| `a/webdav-detail-loop` | `4f892ff` | A2-1 凭据世代协调器（71f6789）+ A2-2 取消绑定/parser fail-closed（4f892ff），基座 PR #19 @01c825e | A 本人 |
| `a/core-security-hardening` | `fe9981d` | A2-4 五项：ProviderException 消毒 / ApiException 脱敏 / Redactor+日志脱敏 / 搜索库文案收口 / 空页分页守卫，基座 main @8e516e4 | A 委托代理 |
| `a/visuals-integration` | `6c1c7bc` | A2-3：merge(722b800, #22@e0980b6)，PlaybackEngine 唯一冲突文件语义合成 | A 委托代理 |
| `a/slowfinal-regression` | `959a18e` | SLOW-FINAL 缺陷修复 + 5 条确定性回归，基座 36a3fc2 | A 委托代理 |
| `a/joint-candidate` | `f6168ff`（最终门禁中，含全部上述） | 联合候选：main → backup(04d921c) → t0001(cd653db) → webdav(5df5d31→4f892ff) → visuals(6c1c7bc) → security(fe9981d) → slowfinal(959a18e)，外加 lease 对齐(6472c91/433f166)、跨分支契约消解(8822545)、Home 装载态(c1082ff)、链路测试(46e7a88) | A 本人 |

merge-base 层级：`a/joint-candidate` 的每个 merge 都保留源分支祖先链，未 squash。

## 2. 跨分支语义冲突与裁决（C 重点复核项）

1. **A2-4 × F-C1-1**（8822545）：SanitizedProviderCause 最初把 IOException 也包装，导致
   `Emby/JellyfinFactoryRestoreIsolationTest`（C 的 F-C1-1 分层回归）在联合分支失败。
   裁决：IOException/CancellationException **原实例透传**（传输/取消契约），message 固定文案
   + 日志层脱敏仍覆盖文本敏感面。锁定：`ProviderExceptionIoPassthroughTest` 4 条。
2. **WebDAV 凭据世代 × PR #18 restore lease**（6472c91/433f166）：新增
   `CredentialGenerationInvalidator`（provider:api）+ WebDAV 适配绑定 +
   `TokenSessionLoginInvalidator` 注入集合调用——restore 身份变更同时推进 TokenStore
   lease、CredentialVault 清理与 WebDAV 世代。锁定：`WebDavCredentialGenerationInvalidatorTest`。
3. **settings/build 与 CI workflow 冲突**：取双方并集（备份的 validation 上传 +
   视觉的 API32/36 门禁 + --continue），未削弱任何门禁。

## 3. 已识别但保留的语义（登记，未擅改）

- **失败探测写 null 覆写历史质量数据**：按既有语义保留（上轮已登记），未改产品规则。
- **ApiClient.probe（连接测试）仍为阻塞 execute**：SLOW-FINAL 修复仅覆盖 execute/executeNoContent
  （退出链证据范围）；probe 不在退出链，未取得证据未动。
- **匿名 WebDAV 无法添加**：authMethod=BASIC 强制用户名密码（PR #19 既有产品语义）。
- **t0003（4684685）planner/TrackRowMap 架构**：独立增强候选，未混入；与 #22 的差异已登记
  （文件级：+TrackSelectionPlanner/TrackRowMap/PlayerTrackSelection）。
- **DetailViewModel.kt:152 `else -> "加载失败：${e.message}"`**：与 A2-4 第 4 项同型，
  属 detail 模块（本轮授权范围外），**待办**。

## 4. 各分支证据与验证层级（详见各分支提交说明）

- A2-1：红（跨 Factory 迟到 401 误删 B 密码，line 109）→ 绿；webdav 模块 82+ 测试。
- A2-2：parser 红×4（207 状态行被丢 / 失败 propstat collection / response 级失败未丢弃 /
  feature 失败静默继续）→ 绿；取消 4 条（EventListener 真实 canceled/responseFailed/
  connectionReleased 证据）；JDK parser 全验，**Android parser 设备范围 NOT_RUN**。
- A2-3：真实引擎入口 6/6（非 mapper/planner 替代）；engine 81 / mpv 20 / core:ui 51 / feature:player 51。
- A2-4：五项红→绿（ProviderExceptionSanitization / ApiExceptionRedaction / Redactor+Logcat /
  搜索库收口 / 空页守卫 `pageWindow`）；provider:api 12 / core:network 16+ / core:logging 24 /
  emby 152 / jellyfin 86 / search 29 / library 44（分支上）。
- SLOW-FINAL：**真实缺陷复现**（退出预算被阻塞 execute 击穿，红：3524ms 未返回）→ 修复
  （ApiClient enqueue 桥接）→ 5/5 绿；本地先落库、恰一次 POST、取消即终止 Call。
- A2-6：`WebDavJointChainTest`（46e7a88）——真实 AddServerViewModel/Registry
  （DefaultProviderRegistry）/Room/WebDavProviderFactory/MockWebServer，表单→保存→浏览→
  详情→播放源→PlayerViewModel **Ready**（唯一缝：PlaybackEngineCreator fun interface，
  Media3/mpv 无法 JVM 装配）；Home 装载态回归。

## 5. 最终门禁（本包生成时正在运行，结果见主报告）

`:app:assembleDebug` / `:app:lintDebug` / 20 个模块单测，联合 head f6168ff。

## 6. 未验证项（如实登记）

- **模拟器/真机**：本轮全部 NOT_RUN（无设备授权）；视觉 API32/36 门禁依赖云端 CI。
- **真实服务器**：Emby smoke server / 真实 NAS / Jellyfin 实例均未触碰；
  Jellyfin 保持 BLOCKED_BY_SERVER；PR #16 / SLOW-FINAL 的**真实服务器侧验收**保持 OPEN
  （本轮的受控回归只证明生产代码链路语义，不替代真实网络验收）。
- **PR #18**：C REVIEW PENDING / DEVICE UNVERIFIED 保持；C 基线仍定位 82a38ab，
  本轮新增变更**全部不在**其下，需另行复审（本文档即delta入口）。
- **CI**：联合候选 Draft PR 创建后以新 CI run 为准，不借用 #21/#22/#18 旧绿色结论。
- Mimosa 全量安全审计：推送时钩子提示未取得完整扫描结论——**不作安全结论声明**。
