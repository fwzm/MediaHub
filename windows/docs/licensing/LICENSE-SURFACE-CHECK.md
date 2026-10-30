# 许可证清单的生成与核对（W11b）

- 状态：**工具与文档已就绪，判定仍未收口**。本页只说「怎么生成、怎么核对」，不作法律结论。
- 相关：
  - 生成器：`windows/tools/license-report.ps1`
  - 核对器：`windows/tools/check-licence-surface.ps1`
  - 生成的产物：[`windows/docs/THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)、[`windows/docs/sbom-windows.cdx.json`](../sbom-windows.cdx.json)
  - 判定与未收口项：[LICENCE-DECISION.md](LICENCE-DECISION.md)、[SBOM.md](SBOM.md)、[W5-LGPL-BUILD.md](W5-LGPL-BUILD.md)

## 1. 两个脚本分工

| 脚本 | 作用 | 碰网络/构建 | 退出码 |
| --- | --- | --- | --- |
| `license-report.ps1` | 扫 `windows/src/*/*.csproj` 与 `windows/tests/*/*.csproj` 的 `obj/project.assets.json`，读每个 NuGet 包 nuspec 的许可，写 Markdown/JSON/notices/SBOM | 否（只读磁盘；要求先 `dotnet restore` 过） | 0 通过 / 1 用法或找不到 csproj / 2 缺 assets / 3 策略违规 |
| `check-licence-surface.ps1` | 静态核对「仓库现状」与「已生成的四份文档 + 报告」是否仍然一致 | 否（纯读） | 0 全部通过 / 1 有 FAIL / 2 RepoRoot 不存在 |

生成器回答「每个包是什么许可」；核对器回答「文档有没有跟上仓库」。两者都不做法律判断，也不代替人工拍板。

## 2. 生成（每次改过依赖或 csproj 后重跑）

```powershell
# 1) 先让 assets.json 反映当前 csproj（只 restore，不构建 App）
dotnet restore windows/MediaHub.Windows.sln

# 2) 生成证据 + 两份入库文档 + SBOM
#    OutDir 用仓库内相对路径（windows/**/artifacts/ 已 gitignore），这样 notices 里记录的
#    「生成命令」行对任何人可复现；换成本机绝对路径会让每次生成的文档都不一样。
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir windows/artifacts/license-report -Sbom
```

要点：

- `-OutDir` 落 `license-report.md` 与 `license-report.json`（证据，不入库）；`-Sbom` 额外写 `windows/docs/sbom-windows.cdx.json`。
- 默认同时覆写 `windows/docs/THIRD-PARTY-NOTICES.md`；只想拿证据、不动文档时加 `-SkipDocs`。
- `-FailOnDeny:$false` 只报告不失败；`-RequireExact` 把「只有 licenseUrl、没有 SPDX 表达式」也判违规（默认只警告，因为 3 个 Microsoft/xunit 老包确实如此）。
- `-TimestampUtc 2026-10-23T00:00:00Z` 可冻结生成时间以做**可复现对比**。实测（两次分别写 `repro-a` / `repro-b`）：
  SBOM **逐字节一致**；`license-report.json` 仅 1 行不同、`license-report.md` 亦然 —— 差异只有记录下来的命令行
  里回显的 `-OutDir`/`-Json` 路径本身，其余 987 行完全一致。

## 3. 核对（提交前或 CI 里跑）

```powershell
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath windows/artifacts/license-report/license-report.json
```

25 项检查分七组：

| 组 | 检查 | 失败含义 |
| --- | --- | --- |
| 项目面 | `projects-discovered` | `windows/src` 或 `windows/tests` 下找不到 csproj（多半 `-RepoRoot` 传错） |
| 生成的 notices | `notices-exist`、`notices-libmpv-engine`、`notices-engine-licence`、`notices-lgpl-dependency-section`、`notices-lgpl-evidence`、`notices-reproduce-command`、`notices-points-here` | notices 被手改坏、或生成器改版后丢了关键段落 |
| LGPL 说明 | `lgpl-compliance-exists` 及三条标记（`P/Invoke`、`libmpv-2.dll`、`written offer`） | 动态链接事实、可替换性、源码提供方式被删掉 |
| SBOM | `sbom-shape`（CycloneDX 1.5）、`sbom-engine-component`（libmpv + GPL-3.0-or-later）、`sbom-lgpl-flavour-property` | SBOM 不是合法文档或丢了引擎记录 ⇒ 随包引擎变成「无记录」 |
| 报告一致性 | `report-exists`、`report-no-violations`、`report-project-count`、`report-engine-pin`、`report-lgpl-dependency-count`、`lgpl-licence-gaps` | 报告过期（csproj 数变了）、出现策略违规、引擎哈希与固定值不符、LGPL 依赖数变了 |
| SBOM 库存（W32 起） | `sbom-inventory`、`sbom-inventory-licences`、`sbom-source-revision` | 入库的 SBOM 与「当场重新解析依赖图得到的报告」不再一一对应（包升级/新增/移除、许可表达式变了），或 SBOM 记录的本机来源提交不落在当前分支历史上 |
| 工具链（W33 起） | `sdk-pin-matches-report` | 报告不是由仓库根 `global.json` 钉住的 SDK 生成的——SDK 特性带会修剪框架自带的包，SBOM 会描述另一张图 |

`lgpl-licence-gaps` 默认是 **WARN**：`component-licences.txt` 里没有声明的 5 个 MSYS2 包（`libwinpthread-1`、`libpcre2-8-0`、`libintl-8`、`libexpat-1`、`libbz2-1`）是**已知缺口**，默认 flavour 不随包分发它们。要在「切 LGPL 发布前」把缺口当硬门，加 `-FailOnLicenceGaps`。

报告不存在时，相关检查输出 `SKIP` 而不是失败——没跑过生成器的机器也能用核对器查文档面。

### 3.1 SBOM 库存三检（W32，2026-10-30）

前三组只证明 SBOM「形状是对的」：它仍是 CycloneDX 1.5、仍记录 libmpv、仍带 LGPL flavour 属性。包版本一旦上调或新增，生成器会产出新报告，而入库的 `sbom-windows.cdx.json` 会继续描述旧集合，CI 仍全绿——这正是本仓库脚本注释里自述的「静默腐烂」。三检把这层钉住：

- `sbom-inventory`：把报告 `packages[].purl` 与 SBOM `components[].purl`（只取 `pkg:nuget/`）做集合比较，两侧一一对应才算过；漂移时列出「解析得到但未记录」与「记录了但已不再解析」两类 purl（每类最多列 3 条 + 计数），FAIL。版本变化天然表现为「一增一减」，所以同一条检查同时覆盖升降级。
- `sbom-inventory-licences`：对两边都存在同一 purl 的行，比较报告 `license` 与 SBOM `licenses[0].expression`（缺 expression 时回退到 `license.name`）；29 行当前逐字相同（含 `~BSD-3-Clause (from LICENSE.txt)` 这类带 `~` 前缀的线索值），任一不一致 FAIL。
- `sbom-source-revision`：读 `metadata.properties` 的 `mediahub:source-revision`（形如 `feature/windows-client@1c30471`），用 `git merge-base --is-ancestor` 判它是否在当前 HEAD 的历史上。可达 = PASS（等于 HEAD 说明它就是当前提交生成的，否则提示「重新生成可刷新记录的提交」）；不可达 = FAIL（这份 SBOM 来自另一条血统）；属性缺失、值里读不出提交、机器上没有 git = WARN。

两个使用细节：`-SbomPath` 会被拼到仓库根下（`Resolve-RepoPath`），**绝对路径拼起来找不到文件**，要传仓库相对路径；`-JsonPath` 则原样使用，绝对路径可以。CI 无需改 YAML：`windows-ci.yml` 的 Licence report 步骤本来就带 `-JsonPath` 调这个脚本，三项新检查自动生效。

负向对照（用临时 SBOM 副本 + `-SbomPath` 指过去，不改仓库文件；每处**恰好 1 红**）：删一个组件 → `sbom-inventory` FAIL；改一个版本 → FAIL（一增一减）；加一个幻影组件 → FAIL；改一条许可表达式（`Microsoft.CodeCoverage@17.13.0`: `MIT` → `Apache-2.0`）→ `sbom-inventory-licences` FAIL；把来源提交改成 40 位悬空提交（用 `git commit-tree` 造，对象存在但不在 HEAD 谱系）→ `sbom-source-revision` FAIL；浅克隆里该对象根本没取回来 → 同一检查 WARN（提示 `fetch-depth: 0`）；L；删掉报告 → 三检全部 `SKIP`、退出码仍为 0。

### 3.2 SDK 钉住：CI 必须用与生成 SBOM 相同的 SDK（W33，2026-10-30）

W32 的三检首次随 CI 跑就抓到两件事，都不是「SBOM 写错了」：

1. ~BT~sbom-inventory~BT~ FAIL：CI 报告 ~BT~packages=28~BT~、入库 SBOM 与本机都是 29，差的是 ~BT~pkg:nuget/System.Reflection.Metadata@1.6.0~BT~。它不是任何 csproj 的直接引用，而是 ~BT~Microsoft.TestPlatform.ObjectModel@17.13.0~BT~ 的传递依赖；差别来自 SDK 特性带（feature band）——10.0.403 带修剪数据（~BT~PrunePackageData~BT~），会把框架自带的这个包剪掉，10.0.113 没有这份数据，于是保留。也就是说：**同一个仓库在不同 SDK 下还原出的依赖图不一样**。
2. ~BT~sbom-source-revision~BT~ FAIL：~BT~actions/checkout@v4~BT~ 默认 ~BT~fetch-depth: 1~BT~，克隆里根本没有 SBOM 记录的那次提交，~BT~git merge-base --is-ancestor~BT~ 无从判定。

据此仓库做了三件事：

- 仓库根新增 ~BT~global.json~BT~：~BT~{"sdk":{"version":"10.0.113","rollForward":"disable"}}~BT~——钉住生成入库 SBOM 与全部实测证据的那一版 SDK（不是「最新的 10.0.x」）。要让 SBOM 描述新 SDK 的图，应当连同 ~BT~global.json~BT~ 与重新生成的 SBOM 一起改。
- ~BT~windows-ci.yml~BT~：~BT~actions/checkout@v4~BT~ 加 ~BT~fetch-depth: 0~BT~（本流水线不读 tags，代价只是一次完整 fetch；仓库 pack 约 27 MiB / 500 提交），~BT~actions/setup-dotnet@v4~BT~ 的 ~BT~dotnet-version~BT~ 由 ~BT~"10.0.x"~BT~ 改为 ~BT~"10.0.113"~BT~。
- 核对器新增第 25 项 ~BT~sdk-pin-matches-report~BT~：读仓库根 ~BT~global.json~BT~ 的 ~BT~sdk.version~BT~，与报告的 ~BT~tooling.dotnet~BT~ 比对。相等 PASS；不等 FAIL（同时点名两个版本，并说明「SDK 特性带对框架自带包的修剪不同，SBOM 会描述另一张图」）；没有 ~BT~global.json~BT~、~BT~sdk.version~BT~ 为空、报告里 ~BT~tooling.dotnet~BT~ 为空或 ~BT~unknown~BT~（多半是生成报告时机器 ~BT~PATH~BT~ 上没有 dotnet）一律 WARN；报告不存在时 SKIP。

~BT~sbom-source-revision~BT~ 同时改成四分支：属性缺失 / 值里读不出提交 / 机器上没有 git → WARN；~BT~git cat-file -e <sha>^{commit}~BT~ 失败时再看 ~BT~git rev-parse --is-shallow-repository~BT~，为 true（浅克隆，对象根本没取回来）→ WARN 并提示 ~BT~fetch-depth: 0~BT~，为 false（对象确实不在克隆里）→ FAIL；对象存在但 ~BT~merge-base --is-ancestor~BT~ 为假 → FAIL（记录的是另一条血统）；可达 → PASS。

负向对照：把 ~BT~global.json~BT~ 的 pin 改成 10.0.403 → 恰好 1 红（~BT~sdk-pin-matches-report~BT~）；报告副本的 ~BT~tooling.dotnet~BT~ 改成 10.0.403 → 恰好 1 红；改成 ~BT~unknown~BT~ → 0 红 2 警；SBOM 副本的来源提交改成一个用 ~BT~git commit-tree~BT~ 造出来的悬空提交（对象存在但不在 HEAD 谱系）→ 恰好 1 红（~BT~sbom-source-revision~BT~）。

## 4. 当前实测（2026-10-23，HEAD baac30c）

```text
license-report  projects=13  rows=49  packages=29
license-report  licence sources: expression=14 file=12 licenseUrl=3 missing=0
license-report  native-engine: GPL-3.0-or-later (windows/native/libmpv/libmpv-2.dll, observed=120989696 B, matchesPin=True)
license-report  lgpl-flavour dependencies: present=True count=33 unrecorded=5
license-report  violations=0  warnings=4
license-report  RESULT: PASS (no policy violations)

check-licence-surface  checks=21 failed=0 warnings=1 skipped=0
check-licence-surface  WARN  lgpl-licence-gaps  5 row(s) without a licence declaration: ...
check-licence-surface  RESULT: PASS
```

### 4.1 实测（2026-10-30，W32，HEAD b586b001）

```text
check-licence-surface  checks=24 failed=0 warnings=1 skipped=0
check-licence-surface  PASS  sbom-inventory  29 resolved package(s) recorded one for one in the SBOM (29 pkg:nuget components)
check-licence-surface  PASS  sbom-inventory-licences  every recorded licence expression still matches the resolved graph (29 rows)
check-licence-surface  PASS  sbom-source-revision  generated from feature/windows-client@1c30471, an ancestor of HEAD (b586b001bb8f) - ...
check-licence-surface  RESULT: PASS
```

报告由 `license-report.ps1 -OutDir D:/mh-verify/w32m-license -SkipDocs` 当场跑出（`projects=13 rows=49 packages=29 violations=0 warnings=4`）；本机 sln 级 `dotnet restore` 不可用（`NuGet.targets(780,5) Value cannot be null`），按 CI 的做法单工程 restore `MediaHub.EngineProbe` / `MediaHub.ResumeProbe` 补齐 `project.assets.json` 后报告才是完整的 13 工程（否则 `projects` 偏少并自判 INCOMPLETE）。

### 4.2 实测（2026-10-30，W33，本机改动 + HEAD 13fad6b8）

~BT~~BT~~BT~text
license-report  dotnet=10.0.113  pwsh=7.6.9
license-report  projects=13  rows=49  packages=29  violations=0  warnings=3

check-licence-surface  checks=25 failed=0 warnings=1 skipped=0
check-licence-surface  PASS  sbom-inventory  29 resolved package(s) recorded one for one in the SBOM (29 pkg:nuget components)
check-licence-surface  PASS  sbom-inventory-licences  every recorded licence expression still matches the resolved graph (29 rows)
check-licence-surface  PASS  sbom-source-revision  generated from feature/windows-client@1c30471, an ancestor of HEAD (13fad6b84352) - ...
check-licence-surface  PASS  sdk-pin-matches-report  the report was produced by the pinned SDK 10.0.113
check-licence-surface  RESULT: PASS
~BT~~BT~~BT~

~BT~warnings=3~BT~ 比 §4 多，是因为这一版报告把 ~BT~Microsoft.Windows.SDK.BuildTools@10.0.26100.4654~BT~ 与 ~BT~xunit.abstractions@2.0.3~BT~ 只拿到 ~BT~licenseUrl~BT~（另有一条同类记录），属许可来源缺口而非库存漂移。报告由本机 10.0.113 生成，与 ~BT~global.json~BT~ 的 pin 一致；~BT~sbom-inventory~BT~ 的 29 对 29 说明入库 SBOM 无需重新生成——W33 让「它必须继续新鲜」这件事在 CI 里可判定。

有一条强度差异必须写明：CI 的 checkout 是完整克隆（~BT~fetch-depth: 0~BT~）时，来源提交判定是硬门（FAIL）；浅克隆里该对象不存在，同一检查只给 WARN。这条检查的强度取决于克隆深度，仓库 CI 已把它设成硬门形态。

## 5. 这个工具**不**覆盖什么

- 不判断 GPL/LGPL 的法律结论：`libmpv-2.dll` 是 GPL-3.0-or-later 这一点来自二进制内嵌特性表与构建命令行，见 [LICENCE-DECISION.md](LICENCE-DECISION.md)。
- 不下载任何包的许可原文：`file` 型许可按包内文件名与首 6 KB 文本猜测（如 `LICENSE.txt` → BSD-3-Clause / Microsoft EULA），结果带 `~` 前缀，属于「线索」不是「判定」。
- 不覆盖**仓库根没有 LICENSE**这一事实：`windows/docs/licensing/LICENCE-DECISION.md:180` 与生成 notices 的 §4 都把它写成未收口项，核对器不会因此报 FAIL（不是它能决定的事）。
- 不校验随包 DLL 的签名或来源（那是 `fetch-libmpv.ps1` 的固定摘要与 `collect-lgpl-runtime.ps1` 的 sha256 清单负责的）。
- 不替代发布前的人工 checklist，见 [LGPL-COMPLIANCE.md](../LGPL-COMPLIANCE.md) §7。

## 6. 建议接入方式

- 本地：改过 `*.csproj` 或升级过包，先 `dotnet restore`，再跑生成器 + 核对器，两者都绿才提交文档变更。
- CI（可选）：在已有 Windows job 里追加两步（不引入新依赖、不需要网络）：

```powershell
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir $env:RUNNER_TEMP/lic -Sbom -SkipDocs
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath $env:RUNNER_TEMP/lic/license-report.json
```

  注意 `-SkipDocs`：CI 只产生证据与 SBOM 之外的报告，避免在流水线里改写入库文档。

CI 侧还要两条前置：checkout 必须完整克隆（`fetch-depth: 0`），否则 `sbom-source-revision` 只能给 WARN；runner 装的 SDK 必须等于 `global.json` 的 pin（`windows-ci.yml` 已经写成 `"10.0.113"`），否则 `sdk-pin-matches-report` FAIL。
