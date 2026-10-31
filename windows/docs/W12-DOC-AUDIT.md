# W12 文档一致性审计（Windows）

审计对象：`windows/docs/**` 与 `windows/THIRD-PARTY-NOTICES.md` 里对**能力状态 / 测试数 / 哈希 / 路径 / 行号**的断言。
审计方式：只读命令（`git`、`gh`、`Get-FileHash`、`Get-Content`、`grep`）+ 读代码。**未构建、未运行 `dotnet test`、未改代码/测试/脚本/LICENSE。**

- 审计时间：2026-10-24 凌晨（基线采集 2026-10-23 22:25 +08）
- 工作区：`D:\deepseek_test\MediaHub_Win`，分支 `feature/windows-client`
- 基线：本地 HEAD = `282dcc2058815317a3ce285f04855d078404a7ab` = `origin/feature/windows-client` = PR #33 `headRefOid`（ahead/behind 0/0）；`origin/main` = `1246313c7a66d492258b5b4b20b51cbdcf2da9cc`；PR #33 OPEN + Draft + MERGEABLE + CLEAN；PR #39 `headRefOid` = `01233cabf1488f3c05fcd44e472fee153fe0b977`（base `feature/windows-client`，Draft，mergeable UNKNOWN，相对当前分支 1 ahead / 21 behind）。
- CI（exact SHA `282dcc2`）：run 39955246292 `windows-ci`/push/success、39955255418 `Android CI`/pull_request/success、39955255573 `windows-ci`/pull_request/success；无 in_progress/queued。

## 0. 机器可读状态（CI 核对）

<!-- doc-audit-status:begin -->
snapshot-status: historical-audit-not-current
audit-date: 2026-10-24
baseline-head: 282dcc2058815317a3ce285f04855d078404a7ab
baseline-branch: feature/windows-client
counts-are-as-of: baseline-head
recheck-rule: every number here must be re-verified against a fresh run before it is used as current
current-truth-source: windows-ci run log (dotnet test output + check-licence-surface)
doc-role: dated audit snapshot - never the acceptance authority
<!-- doc-audit-status:end -->

本文所有数字（测试条数、行号、哈希、PR 状态）都是 **as-of `baseline-head` 的历史快照**，接近「当前」的用法必须重新核对；CI 运行日志才是当下的权威。第 4 节列出的「不跑测试 / 不构建即无法判定」是这份快照的边界，不随时间推移自动失效。

## 1. 核实为【相符】（未改）

| 文件:行 | 断言 | 证据 |
|---|---|---|
| CAPABILITY-MAP.md:112、W9-EVIDENCE.md:18-19 | 477 tests / 0 failed（MediaHub.Tests 373 + MediaHub.WebDav.Tests 104） | 静态计数：`tests/MediaHub.Tests` 235 `[Fact]` + 27 `[Theory]` + 138 `[InlineData]` = 373；`tests/MediaHub.WebDav.Tests` 92 + 3 + 12 = 104 |
| CAPABILITY-MAP.md:114 | 引擎网络边界 9 条 | `tests/MediaHub.Tests/MediaStreamBoundaryTests.cs`：9 `[Fact]` / 0 `InlineData` |
| CAPABILITY-MAP.md:115 | 凭据库与 URL 规范化 79 条 | `CredentialVaultTests.cs` 33 + `ServerUrlNormalizerTests.cs` 46（3 `[Fact]` + 43 `[InlineData]`）= 79 |
| CAPABILITY-MAP.md:121 | 便携版 331.6 MB | `windows/artifacts/portable` 实测 331.6 MB / 521 个文件 |
| CAPABILITY-MAP.md:123 | libmpv 为 GPL，RELEASE BLOCKER | `windows/native/libmpv/libmpv-2.dll` 120,989,696 B / `6A48FAECB5FFE14A7BE2F1FCF8B193BF3D51837CFB36E8D3527D82089DE563D9`；`sbom-windows.cdx.json:421-433` 同哈希，`:36` 注「GPL-3.0-or-later build … LGPL candidate not shipped」 |
| ROADMAP-WINDOWS.md:29-30 | 剧集页季/集验证已完成 | `u2/autoplay-gui.ps1:217` `Open-EpisodePlay`、`:227-230` `Wait-Card $script:season1Card` → `Invoke-Deep` → `EpCard`、`:429` P1.1 断言；`evidence/u2-autoplay/jellyfin/checks.json` 35/35（2026-10-23 23:52） |
| W9-EVIDENCE.md:18-19 | 477 / 0 failed | 同上静态计数 |
| W9-EVIDENCE.md:127 | PR #39 停在 `01233ca`、base `feature/windows-client`、21 behind、mergeable UNKNOWN | `gh pr view 39`：headRefOid `01233cab…`、baseRefName `feature/windows-client`、isDraft true、mergeable UNKNOWN；`git cat-file -t 01233cab…` → commit；`git rev-list --left-right --count 01233cab…HEAD` → `1 21` |
| W9-EVIDENCE.md:132 | PR #33 Draft 需人解除 | `gh pr view 33`：isDraft true、state OPEN、mergeable MERGEABLE、mergeStateStatus CLEAN，CI 全绿 |
| W9-EVIDENCE.md:93（结论本身） | 已入库代码里 Android 无收藏写入 | 见 §3 冲突 4：仅未提交改动触碰该结论，故按已入库代码维持原判定，只加脚注 |
| LGPL-COMPLIANCE.md:15-16 | GPL dll 尺寸/哈希 | 实测 120,989,696 B / `6A48FAEC…63D9`（同上） |
| W5-LGPL-BUILD.md:18 | LGPL 构建产物 3,327,274 B / `20035902…3C0A` | `windows/engine/lgpl/dist/libmpv-2.dll` 实测一致；文件实测 252 行，被引用的 `:247-249`（v0.41.0-UNKNOWN）与 `:226-235`（libiconv / lcms2 待核实）均对得上 |
| PACKAGING.md:15-17 | 引用 `windows-ci.yml` 的 publish/smoke/artifact 行号 | `.github/workflows/windows-ci.yml` 140 行：`:114-119` publish、`:121-129` smoke（5 个必需文件）、`:131-140` upload-artifact retention 14 |
| THIRD-PARTY-NOTICES.md:18、:61-63、:71-72 | GPL-3.0-or-later 结论 + RELEASE BLOCKER + 双摘要 | 与 `sbom-windows.cdx.json`、`fetch-libmpv.ps1:33-37` 的 pin 一致（`$DllSha256='6a48faec…63d9'`） |
| LICENCE-DECISION.md:180-190 | 仓库无 LICENSE 文件 | `git ls-files` 里只有 `windows/tools/license-report.ps1` 命中 LICENSE 子串 |
| ACCEPTANCE-QUIET-RUNBOOK.md:117-118 | W9 四门证据来自直接运行、二进制 `A15F6E4F…` | `W10-ACCEPTANCE-STATUS.md:13`、`u3/u4/u2-jellyfin` 目录时间戳（10-23 23:38~23:52）与之一致；该二进制已不在磁盘，属历史记录 |
| W10-ACCEPTANCE-STATUS.md:13、:20 | U1 41/41；二进制 539648 B / 00:13:17 / `A8EDD448…` | `D:\mh-out\x64\Debug\…\MediaHub.dll` 实测 539648 B / 2026-10-24 00:13:17 / `A8EDD448…`；U1 两服 checks.json 各 41 条全 pass |

## 2. 已改（过期 / 错误断言）

| 文件:行 | 原断言 | 核实结果 | 证据 | 处理 |
|---|---|---|---|---|
| CAPABILITY-MAP.md:93 | 季/集导航 `IMPLEMENTED_UNVERIFIED（模拟服务器剧集库为空，未在有数据下验证）` | 过期：W9 的 U2 已在真实 Emby 4.10.2 / Jellyfin 12.2 剧集库上按季/集播出 | `evidence/u2-autoplay/jellyfin/checks.json` 35/35；`autoplay-gui.ps1:217/227-230/429`；ROADMAP:29-30 | 改为 `EXECUTED_PASS` 并给出断言数与产物位置 |
| CAPABILITY-MAP.md:95 | 继续观看「Emby 39/39 + Jellyfin 39/39；卡片海报仍未懒加载」 | 过期：U1 已扩为两服各 41/41；海报懒加载已实现 | `evidence/u1-continue/{emby,jellyfin}/checks.json` 41/41（2026-10-24 00:11/00:12，dll `A8EDD448`）；`ContinueWatchingPage.xaml.cs:99`、`MediaCardViewModel.cs:73` | 改为 41/41 + 海报已实现 |
| CAPABILITY-MAP.md:118 | 「两服四门共 276 条断言全绿」 | 部分过期：U1 已扩到 41/41×2；U2 Emby 的 35/35 产物已被覆盖 | 同 §3 冲突 1；U1 计入后应为 280 条 | 保留 276 为 W9 口径，补 U1=41/41 与证据覆盖说明 |
| W9-EVIDENCE.md:53 | U1 脚本 844 行 / `2E670C38…C70` | 漂移（W10 扩写 +46 行） | 实测 890 行 / `991D5BC76A78C09EA3CB6A423A8DD6A10024F3D41098B14C2EF70A537C55ABC9` | 原文保留（历史跑记录），追加当前值 |
| W9-EVIDENCE.md:71 | U2 脚本 605 行 / `99C4F2D6…56F9D` | 漂移 | 实测 606 行 / `6A686143DBF991FFE8C98797A62DF929E2A791161096837FDCFF997ECA9CB4ED` | 追加当前值 |
| W9-EVIDENCE.md:84 | U3 脚本 396 行 / `E3256CA6…17BF` | 漂移 | 实测 397 行 / `2A1771F0109C1B735561D5C5E6EA241EFD2C034207A9693F8E0879E5F25D784F` | 追加当前值 |
| W9-EVIDENCE.md:97 | U4 脚本 874 行 / `5FC1050F…F333` | 漂移 | 实测 875 行 / `A07A0B1180AF21257B4E71D5AF4EBD5E57F1B7DCDE859D1BA90DBAEC0DBF15B9` | 追加当前值 |
| W9-EVIDENCE.md:125 | 卡片海报 `IMPLEMENTED_UNVERIFIED（页面未调用 Card.RequestPoster）` | 错：页面已调用 | `Views/ContinueWatchingPage.xaml.cs:99`、`FavoritesPage.xaml.cs:86`、`LibraryPage.xaml.cs:121`；定义 `MediaCardViewModel.cs:73`；U1 P2.6 在两服通过 | 改为 `EXECUTED_PASS（W10 已修）` |
| W9-EVIDENCE.md:129 | LGPL 构建与许可证清单**未完成** | 半过期：LGPL 构建已产出并验证，许可证清单（LGPL-COMPLIANCE / SBOM / NOTICES）已就位；**未切换发布物**才是阻塞点 | `windows/engine/lgpl/dist/libmpv-2.dll` 3,327,274 B；`W5-LGPL-BUILD.md:3` BUILT AND VERIFIED；`sbom-windows.cdx.json:36`「LGPL candidate not shipped」 | 改为「构建已产出、随包仍是 GPL 构建、切换与签名决策未做」 |
| W9-EVIDENCE.md 第 0 节、第 8 节表 | （无） | 需补证据覆盖事实 | `evidence/u2-autoplay/emby/checks.json` 2026-10-24 01:53：23 pass / 12 fail | 第 0 节加复核脚注；第 8 节新增「U2 Emby 证据产物」行 |
| ROADMAP-WINDOWS.md:60-61 | 「仍在队列：U1、U3、U2、R7 重放」 | 全部过期：W9 已交付 U1/U2/U3/U4，R7 重放 W8 已完成 | W9-EVIDENCE 第 3~6 节；W8-EVIDENCE.md:178-179、:185-186、:198（`r7-b2-queue` 63/63、`r7-a3-webdav` 24/24）；HEAD `282dcc2` 提交题即「W8 防回归：重放 R7 队列与 WebDAV 脚本」 | 重写为「W9 已交付 …；仍在队列：U2/U3/U4 在 `A8EDD448` 复跑、W6 跨端同步、签名/MSIX、硬件矩阵、GPL 义务」 |
| ACCEPTANCE-QUIET-RUNBOOK.md:120-121 | U1 41/41「结果写入 `D:\mh-verify\quiet-w10b\summary-*.txt`」 | 错：quiet-w10b 只有一次 `ABORTED-BY-USER` 尝试，summary 里无 U1/41/41；U1 是直接跑套件脚本取得的 | `quiet-w10b/summary-20261024-004100.txt`（247 B，仅 quiet-run 头 + watchdog + server started）；全 `D:\mh-verify` 无 `run-u1*` 日志；U1 证据在 `evidence/u1-continue/{emby,jellyfin}/`（`results.md` 头记 dll `A8EDD448…`、脚本 `991D5BC7…`） | 改为指向 `evidence/u1-continue/`，并注明 quiet-w10b 不是 U1 证据 |

## 3. 事实冲突（逐条）

1. **U2 Emby 的 35/35 已被覆盖**：`windows/docs/W9-EVIDENCE.md` 第 0 节仍按 35/35 记，但 `evidence/u2-autoplay/emby/checks.json` 现为 2026-10-24 01:53 的 **23 pass / 12 fail**。失败项全部是 `P1.0 客户端启动且窗口在前台 :: pid=`、`P1.1 NAV-FAILED` 一类「窗口没起来」，与 `W10-ACCEPTANCE-STATUS.md:35` 记的「守卫连拒 12 次、非产品失败」一致。Jellyfin 侧 35/35（23:52）仍在。
2. **W10-ACCEPTANCE-STATUS.md:64 与磁盘不符**：该行称「U2/U3/U4 证据仍来自 `A15F6E4F`」，但 `u2-autoplay/emby/` 实际已是 `A8EDD448` 上的中断跑（`results.md` 表头记录该哈希）。U3/U4 与 U2-Jellyfin 仍确为 `A15F6E4F`。
3. **U1 证据位置**：runbook 说在 quiet runner 输出里，实际在 `evidence/u1-continue/`；quiet-w10b 的 summary 只有三行头信息。
4. **Android 侧收藏能力**：`W9-EVIDENCE.md:93`、`:128` 说「Android 收藏只读、无写入」，但工作树里有**未提交**改动声明 `FAVORITE` 能力（`provider/api/src/main/kotlin/com/mediahub/provider/api/ProviderCapability.kt:49`、`ProviderHandle.kt:46`、`EmbyProvider.kt:41`、`EmbyProviderFactory.kt:143` 及 Jellyfin 同名文件）。未提交、未验证 → 不改文档结论，只在 `:93` 加脚注。
5. **脚本行数/哈希漂移 4 项**（u1 +46、u2 +1、u3 +1、u4 +1 行）：W9 记录的是当时快照，现值为 §2 表所列；已在原文后追加当前值，不覆盖历史。
6. **同一二进制两处路径**：U1 的 `results.md` 写被测 exe 在 `D:\mh-out3\…`（dll 539648 B / 00:01:52 / `A8EDD448`），`W10-ACCEPTANCE-STATUS.md:20` 写 `D:\mh-out\…`（539648 B / 00:13:17 / 同哈希）——同哈希不同副本，不是冲突。
7. 本地 vs 远端、PR #33 head vs 本地 HEAD：**无冲突**（三者同为 `282dcc2`）。

## 4. 不跑测试 / 不构建即无法判定（单列，不许猜）

| 项 | 现状 | 为什么判不了 |
|---|---|---|
| CI 310 vs 静态 373（MediaHub.Tests） | `.github/workflows/windows-ci.yml:63-69` 只跑 `tests/MediaHub.Tests/MediaHub.Tests.csproj`；exact SHA 日志实测 `Passed: 310, Total: 310` | 差 63 例；静态计数口径（`[Theory]` 展开、条件跳过、平台过滤）无法只靠读代码断定，需要在本机跑一次 `dotnet test` |
| 477 tests 当前是否仍全绿 | 文档 `EXECUTED_PASS`，本轮只做静态计数 | 未运行（另一条 GUI 验收链要用空闲窗口，本次不抢 CPU） |
| `A15F6E4F` 二进制 | W9 四门证据的哈希 | 该 dll 已不在磁盘（`D:\mh-out*` 只剩 `A8EDD448` 与旧 `2C302A62`），无法复算 |
| U2/U3/U4 在 `A8EDD448` 上是否通过 | W10 三次尝试全被使用者打断 | 需要在连续 300 s 空闲 + QUNS 放行窗口里跑 `run-quiet.ps1` |
| 硬件兼容矩阵 / HDR / 音频独占 / 签名 / MSIX | `NOT_RUN` / `DEVICE_UNVERIFIED` | 单机单卡、无签名证书、无干净机 |
| U1 41 条断言的逐条语义 | 只核到条数与表头（41 条全 pass、dll/脚本哈希对得上） | 未逐条读 `detail` 与 PNG |

## 5. 复现命令

```powershell
git rev-parse HEAD
git status --porcelain
git fetch origin --prune
git rev-parse origin/feature/windows-client origin/main
gh pr view 33 --json number,state,isDraft,headRefOid,mergeable,mergeStateStatus,headRefName,baseRefName,url
gh pr view 39 --json number,state,isDraft,headRefOid,baseRefName,mergeable,url
gh run list --branch feature/windows-client --limit 10 --json databaseId,name,status,conclusion,headSha,event

# 证据与哈希
Get-FileHash windows/native/libmpv/libmpv-2.dll -Algorithm SHA256
Get-FileHash windows/engine/lgpl/dist/libmpv-2.dll -Algorithm SHA256
foreach ($s in @('u1/continue-watching-gui','u2/autoplay-gui','u3/favorite-gui','u4/playlists-gui')) {
  $p = "windows/tools/acceptance/$s.ps1"
  "$s lines=$((Get-Content $p).Count) sha=$((Get-FileHash $p -Algorithm SHA256).Hash)"
}
foreach ($s in @('emby','jellyfin')) {
  $a = Get-Content "windows/tools/acceptance/evidence/u1-continue/$s/checks.json" -Raw | ConvertFrom-Json
  "$s $($a.Count) checks / $(($a | Where-Object pass).Count) pass"
}
```
