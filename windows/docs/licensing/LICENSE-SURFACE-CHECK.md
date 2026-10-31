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

46 项检查分二十五组：

| 组 | 检查 | 失败含义 |
| --- | --- | --- |
| 项目面 | `projects-discovered` | `windows/src` 或 `windows/tests` 下找不到 csproj（多半 `-RepoRoot` 传错） |
| 生成的 notices | `notices-exist`、`notices-libmpv-engine`、`notices-engine-licence`、`notices-lgpl-dependency-section`、`notices-lgpl-evidence`、`notices-reproduce-command`、`notices-points-here` | notices 被手改坏、或生成器改版后丢了关键段落 |
| LGPL 说明 | `lgpl-compliance-exists`、`lgpl-compliance-dynamic-linking`、`lgpl-compliance-replaceable-dll`、`lgpl-compliance-source-offer`（三条标记：`P/Invoke`、`libmpv-2.dll`、`written offer`） | 动态链接事实、可替换性、源码提供方式被删掉 |
| SBOM | `sbom-shape`（CycloneDX 1.5）、`sbom-engine-component`（libmpv + GPL-3.0-or-later）、`sbom-lgpl-flavour-property` | SBOM 不是合法文档或丢了引擎记录 ⇒ 随包引擎变成「无记录」 |
| 报告一致性 | `report-exists`、`report-no-violations`、`report-project-count`、`report-engine-pin`、`report-lgpl-dependency-count`、`lgpl-licence-gaps` | 报告过期（csproj 数变了）、出现策略违规、引擎哈希与固定值不符、LGPL 依赖数变了 |
| SBOM 库存（W32 起） | `sbom-inventory`、`sbom-inventory-licences`、`sbom-source-revision` | 入库的 SBOM 与「当场重新解析依赖图得到的报告」不再一一对应（包升级/新增/移除、许可表达式变了），或 SBOM 记录的本机来源提交不落在当前分支历史上 |
| 工具链（W33 + W34 起） | `sdk-pin-matches-report` | 报告不是由仓库根 `global.json` 钉住的 SDK 生成的——SBOM 与全部实测证据必须出自同一条工具链；另见 §3.2（关修剪使还原图在任何机器上一致） |
| 通告文档（W35 起） | `notices-inventory`、`notices-declarations` | 入库的 `THIRD-PARTY-NOTICES.md` 与「当场解析出的依赖图」或「csproj 里直接声明的包」不再对得上（漏包、版本/许可漂移、新增依赖没写进法律文档） |
| 引擎候选身份（W36 起） | `lgpl-candidate-hashes`、`lgpl-candidate-claims` | 入库的 LGPL 候选 DLL 与 `windows/engine/lgpl/dist/dist-hashes.txt` 的记录不符；或十一个引用候选身份的文档/常量写着别的构建（字节数或 sha256 对不上） |
| 引擎能力（W37 起） | `vulkan-claims` | 候选件忽然带上 Vulkan、`MpvPlayer` 不再把 `gpu-api` 钉成 `d3d11`、`windows/src` 下出现 vulkan 字样，或 `VULKAN-NECESSITY.md` 丢了 verdict 行——许可证决策依赖的「Vulkan 非必需」结论不能悄悄过期 |
| 引擎许可声明（W38 起） | `lgpl-licence-table` | LGPL flavour 的 33 个 DLL 与 `component-licences.txt` 的逐行声明对不上（表被改坏、缺行、证据行号指到别处），或装出来的文件清单与报告行数不符 |
| LGPL 合规声明（W41 起） | `lgpl-compliance-block`、`lgpl-compliance-baseline` | 文档 §5.1 的机器可读组件表与解析图不再一一对应（漏包/多包/许可漂移），或 §1 的 GPL 基线摘要与报告解析值分叉（pin 换引擎后文档没跟上） |
| 分发与签名（W39 起） | `packaging-claims` | 发布形态、签名、安装方式与 `PACKAGING.md` §0 七行不符：CI 开始签名、仓库出现 `*.appxmanifest` 或 WiX/Inno/NSIS 工程、App 不再是 unpackaged self-contained portable、`windows-ci.yml` 丢了 portable publish/上传步骤（构建输出 `obj/`、`bin/`、`artifacts/` 不计） |
| 门禁自证（W42 起） | `check-inventory` | 脚本里声明的检查清单与「本次运行发出的 id」「`LICENSE-SURFACE-CHECK.md` 点名的 id」「该文档与 README 声明的数量」任一分叉——新增检查忘了登记，或数量没跟着改 |
| 引擎默认 flavour（W43 起） | `engine-flavour-default` | 默认 flavour 不再是 GPL 基线（csproj 缺省改了、别的工程或工作流开始构建候选），或 `LGPL-ENGINE-SWITCH.md` 的 `engine-flavour-status` 块不再声明 |
| 许可决策记录（W44 起） | `licence-decision-claims` | 发布阻塞状态或引擎许可标签从 `LICENCE-DECISION.md` 消失、该记录又开始声称 notices 标错，或两份 notices 里 libmpv 一行不再写 GPL-3.0-or-later |
| 发行决策记录（W45 起） | `adr-packaging-claims` | `windows/docs/adr/ADR-W004-packaging-and-distribution.md` 不再描述仓库：状态块缺行或改值、ADR 又写回「包内零许可文件」、四个包属性与 App csproj 当场取到的值不符，或 `.gitignore` 不再排除 `windows/native/libmpv/`、`fetch-libmpv.ps1` 被删 |
| 引擎固定摘要（W46 起） | `engine-pins-claims` | `windows/tools/fetch-libmpv.ps1` 钉的归档摘要、解包后 libmpv-2.dll 摘要与归档文件名，在三份引用它们的文档（`windows/THIRD-PARTY-NOTICES.md`、`ADR-W004`、`REPRODUCIBLE-BUILD.md`）里不再逐字出现——换 pin 不同步改文档，prose 就继续描述旧字节 |
| 包内许可文件（W40 起） | `package-licence-staging` | 发布树不再真的带 `licences/` 的六个文档 + digest manifest：暂存脚本被删、清单缩水、manifest 摘要与字节不符、或 CI 不再调用它（该检查把脚本重跑到临时树，不读文档） |
| 能力对照表（W47 起） | `capability-map-claims` | `windows/docs/CAPABILITY-MAP.md` §0 的九行状态块缺行/改值、测试行写回写死的用例计数、或它声称的发布状态与 `PACKAGING.md` §0 不再一致 |
| 验收不打扰契约（W48 起） | `quiet-run-claims` | `windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md` §0 的九行门禁事实（空闲阈值 180 s、QUNS 只允许 5/6、看门狗 700 ms、abort 标记与 `MH_ALLOW_ACTIVE`/`MH_QUIET_ABORT_FILE`、守卫优先级）缺行或改值，或 `run-quiet.ps1` 的默认值、`MhQuiet.psm1` 的 QUNS 白名单与轮询、`MhUia.psm1` 的 `Start-MhApp` abort 守卫与它不再一致 |
| 续播下限契约（W49 起） | `resume-floor-claims` | `windows/docs/W8-RESUME-FLOOR-CONTRACT.md` §0 的八行阈值与用例数、`MockMediaServer.cs` 里 Emby 2 % / Jellyfin 5 % / 300 s / 90 % / 10 s 的常量、以及契约测试实际展开出的用例数，三者任一分叉——放宽下限、删判定分支、加减用例却没同步文档即红 |
| 诊断日志落盘（W50 起） | `log-file-claims` | `windows/docs/W14-DIAGNOSTIC-LOG-FILES.md` §0 的十五行上限/轮转/失败策略/用例数、`LogFileSink.cs` 的默认常量与轮转触发式、`LogService.cs` 的环形上限与写入顺序、`AppServices.cs` 的组合根目录、sink 测试展开的 9 例，五方任一分叉即红 |
| 日期快照审计（W51 起） | `doc-audit-snapshot-claims` | `windows/docs/W12-DOC-AUDIT.md` §0 状态块缺失或与正文分叉（正文 HEAD ≠ 块内 baseline-head、基线提交不再是本分支祖先、快照不再自称 as-of、或第 4 节不可判定清单被删） |
| 文档残留与围栏（W52 起） | `doc-residue-claims` | 文本文件里留着生成期占位符（填写处出现未替换的记号，确要讨论该记号的行须带 `residue-allow` 标记）、markdown 围栏行数为奇数、CAPABILITY-MAP §0 状态块不再被真围栏包住，或一行里粘了两条表格行（`||` 出现在行首为 `|` 的行上） |


`lgpl-licence-gaps` **W38 起应当 PASS**：`component-licences.txt` 已补齐 33 行（此前少 5 个包，是收集脚本从没问过 bzip2/expat/gettext-runtime/pcre2、并在 `pacman -Si` 查不到 `libwinpthread-git` 时静默跳过所致）。它现在只在真有行缺声明时给 WARN，`-FailOnLicenceGaps` 只用来复现 W38 之前的旧行为；「表是否被改坏」由 `lgpl-licence-table`（§3.6）按行核对。

报告不存在时，相关检查输出 `SKIP` 而不是失败——没跑过生成器的机器也能用核对器查文档面。

### 3.1 SBOM 库存三检（W32，2026-10-30）

前三组只证明 SBOM「形状是对的」：它仍是 CycloneDX 1.5、仍记录 libmpv、仍带 LGPL flavour 属性。包版本一旦上调或新增，生成器会产出新报告，而入库的 `sbom-windows.cdx.json` 会继续描述旧集合，CI 仍全绿——这正是本仓库脚本注释里自述的「静默腐烂」。三检把这层钉住：

- `sbom-inventory`：把报告 `packages[].purl` 与 SBOM `components[].purl`（只取 `pkg:nuget/`）做集合比较，两侧一一对应才算过；漂移时列出「解析得到但未记录」与「记录了但已不再解析」两类 purl（每类最多列 3 条 + 计数），FAIL。版本变化天然表现为「一增一减」，所以同一条检查同时覆盖升降级。
- `sbom-inventory-licences`：对两边都存在同一 purl 的行，比较报告 `license` 与 SBOM `licenses[0].expression`（缺 expression 时回退到 `license.name`）；29 行当前逐字相同（含 `~BSD-3-Clause (from LICENSE.txt)` 这类带 `~` 前缀的线索值），任一不一致 FAIL。
- `sbom-source-revision`：读 `metadata.properties` 的 `mediahub:source-revision`（形如 `feature/windows-client@1c30471`），用 `git merge-base --is-ancestor` 判它是否在当前 HEAD 的历史上。可达 = PASS（等于 HEAD 说明它就是当前提交生成的，否则提示「重新生成可刷新记录的提交」）；不可达 = FAIL（这份 SBOM 来自另一条血统）；属性缺失、值里读不出提交、机器上没有 git = WARN。

两个使用细节：`-SbomPath` 会被拼到仓库根下（`Resolve-RepoPath`），**绝对路径拼起来找不到文件**，要传仓库相对路径；`-JsonPath` 则原样使用，绝对路径可以。CI 无需改 YAML：`windows-ci.yml` 的 Licence report 步骤本来就带 `-JsonPath` 调这个脚本，三项新检查自动生效。

负向对照（用临时 SBOM 副本 + `-SbomPath` 指过去，不改仓库文件；每处**恰好 1 红**）：删一个组件 → `sbom-inventory` FAIL；改一个版本 → FAIL（一增一减）；加一个幻影组件 → FAIL；改一条许可表达式（`Microsoft.CodeCoverage@17.13.0`: `MIT` → `Apache-2.0`）→ `sbom-inventory-licences` FAIL；把来源提交改成 40 位悬空提交（用 `git commit-tree` 造，对象存在但不在 HEAD 谱系）→ `sbom-source-revision` FAIL；浅克隆里该对象根本没取回来 → 同一检查 WARN（提示 `fetch-depth: 0`）；删掉报告 → 三检全部 `SKIP`、退出码仍为 0。

### 3.2 工具链钉住：同一条工具链产出 SBOM 与实测（W33 + W34，2026-10-30）

W32 的三检首次随 CI 跑就抓到两件事，都不是「SBOM 写错了」：

1. `sbom-inventory` FAIL：CI 报告 `packages=28`、入库 SBOM 与本机都是 29，差的是 `pkg:nuget/System.Reflection.Metadata@1.6.0`。它不是任何 csproj 的直接引用，而是 `Microsoft.TestPlatform.ObjectModel@17.13.0` 的传递依赖。
2. `sbom-source-revision` FAIL：`actions/checkout@v4` 默认 `fetch-depth: 1`，克隆里根本没有 SBOM 记录的那次提交，`git merge-base --is-ancestor` 无从判定。

据此先做了三件事：

- 仓库根新增 `global.json`：`{"sdk":{"version":"10.0.113","rollForward":"disable"}}`——钉住生成入库 SBOM 与全部实测证据的那一版 SDK（不是「最新的 10.0.x」）。要让 SBOM 描述新 SDK 的图，应当连同 `global.json` 与重新生成的 SBOM 一起改。
- `windows-ci.yml`：`actions/checkout@v4` 加 `fetch-depth: 0`（本流水线不读 tags，代价只是一次完整 fetch；仓库 pack 约 27 MiB / 500 提交），`actions/setup-dotnet@v4` 的 `dotnet-version` 由 `"10.0.x"` 改为 `"10.0.113"`。
- 核对器新增第 25 项 `sdk-pin-matches-report`：读仓库根 `global.json` 的 `sdk.version`，与报告的 `tooling.dotnet` 比对。相等 PASS；不等 FAIL（同时点名两个版本）；没有 `global.json`、`sdk.version` 为空、报告里 `tooling.dotnet` 为空或 `unknown` 一律 WARN；报告不存在时 SKIP。

这条 SDK pin 并没能单独修好第一件事：钉住后的首次 CI（`16f40a46`，run 41079426815）里 `sdk-pin-matches-report` 与 `sbom-source-revision` 都 PASS，`sbom-inventory` 仍 FAIL（CI 28 / SBOM 29，差 `System.Reflection.Metadata@1.6.0`）。真正决定这个包在不在图里的是**机器上有没有修剪数据**：CI 的 .NET 安装带 `PrunePackageData`（随 targeting pack 提供）而会剪掉框架自带的包；本机安装没有 `packs/` 目录、框架引用改经 NuGet 解析、没有这份数据因而不剪；两边 SDK 版本相同也照样分叉。

因此 W34 在 `windows/Directory.Build.props` 直接关掉修剪：`<RestoreEnablePackagePruning>false</RestoreEnablePackagePruning>`。修剪只是发布体积优化，关掉之后还原图（以及由它派生的 SBOM 与许可报告）在任何机器上都相同；原先的 `AllowMissingPrunePackageData` 保留。

`sbom-source-revision` 同时改成四分支：属性缺失 / 值里读不出提交 / 机器上没有 git → WARN；`git cat-file -e <sha>^{commit}` 失败时再看 `git rev-parse --is-shallow-repository`，为 true（浅克隆，对象根本没取回来）→ WARN 并提示 `fetch-depth: 0`，为 false（对象确实不在克隆里）→ FAIL；对象存在但 `merge-base --is-ancestor` 为假 → FAIL（记录的是另一条血统）；可达 → PASS。

负向对照：把 `global.json` 的 pin 改成 10.0.403 → 恰好 1 红（`sdk-pin-matches-report`）；报告副本的 `tooling.dotnet` 改成 10.0.403 → 恰好 1 红；改成 `unknown` → 0 红 2 警；SBOM 副本的来源提交改成用 `git commit-tree` 造出来的悬空提交（对象存在但不在 HEAD 谱系）→ 恰好 1 红（`sbom-source-revision`）；浅克隆（`git clone --depth 1` 后跑核对器）→ 同一检查 WARN、退出码 0，`git fetch --unshallow` 后回到 PASS。

### 3.3 通告文档库存两检（W35，2026-10-31）

生成的 `windows/docs/THIRD-PARTY-NOTICES.md` 是随包附带、给外部看的那份法律文档；它此前只有「形状检查」（关键段落还在不在），没有任何一项比对**依赖清单**，于是新增一个包、或包升级/许可变了而不重生成 notices，都会静默漂移。新增两项：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `notices-inventory` | 报告 `packages[]` 每一项都必须在 notices 表格行里找到 `| 包名 |`，且同一行含解析版本与许可串 | notices 与「当场重新解析的依赖图」不再一致：漏包、版本漂移、许可漂移 |
| `notices-declarations` | `windows/src`、`windows/tests` 里每个 `<PackageReference Include="…">` 的包名都必须出现在 notices 文本里 | 新增直接依赖却没写进法律文档（不依赖报告，报告缺失时仍在跑） |

报告缺失时按既有语义处理：`notices-inventory` SKIP（退出码仍 0），`notices-declarations` 继续跑。负向对照用 `-NoticesPath` 指向副本，不动仓库文件：

- 删掉 `Microsoft.CodeCoverage` 那一行 → 恰好 1 红（`notices-inventory`，1 package(s) missing）。
- 把该行版本 17.13.0 改成 17.99.0 → 恰好 1 红（1 with a drifted version）。
- 把该行许可 MIT 改成 Apache-2.0 → 恰好 1 红（1 with a drifted licence）。
- 临时往 `MediaHub.Tests.csproj` 加一个不存在的 `Phantom.Package` → 恰好 1 红（`notices-declarations`）；随后把 csproj 逐字节还原（sha256 相等）。
- `-JsonPath` 指向不存在的报告 → 24 项、6 项 SKIP、0 红 0 警，`notices-declarations` 仍 PASS（不因缺报告而假警）；W36 的两项不依赖报告，照常 PASS。

### 3.4 LGPL 候选件身份两检（W36，2026-10-31）

`windows/engine/lgpl/dist` 下的候选引擎是**入库二进制**（`libmpv-2.dll`、`mpv.exe`），而它的身份被写进十一处：`windows/tools/license-report.ps1` 的 `LgplCandidate` 常量、`windows/docs/LGPL-ENGINE-SWITCH.md`、`windows/docs/LGPL-COMPLIANCE.md`、`windows/docs/licensing/W5-LGPL-BUILD.md`、`windows/docs/licensing/SBOM.md`、`windows/docs/THIRD-PARTY-NOTICES.md`、`windows/THIRD-PARTY-NOTICES.md`、`README.md`、`windows/engine/README.md`、`windows/docs/W13-LGPL-ENGINE-PROBE.md`、`windows/docs/W9-EVIDENCE.md`。此前没有任何一项把「引用」与「磁盘字节」对照——换错二进制、或文档陈旧，都不会红。新增两项：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `lgpl-candidate-hashes` | `windows/engine/lgpl/dist/dist-hashes.txt` 每条 `<sha256> *<path>`：文件必须存在，且实测 sha256 与记录相等 | 入库候选件被替换、被删，或记录本身被改（记录缺失或空 → WARN，不 FAIL） |
| `lgpl-candidate-claims` | 用上一条实测出的字节数与 sha256，逐个核对十一处引用：必须含千分位字节串（如 `3,327,274`），且含完整 sha256 或「前 8 位 + 后 4 位」缩写（大小写不敏感） | 二进制换了而文档没跟上，或某处引用了别的构建（候选件不在时 SKIP） |

`-EngineDistPath` 可把 `dist-hashes.txt` 的读取根指到别处（绝对路径原样使用，相对路径按仓库根解析）；负向对照用它指向临时目录，不动仓库文件：

- 把记录里 `libmpv-2.dll` 的一个 hex 位改掉 → 恰好 1 红（`lgpl-candidate-hashes`，drifted digest）。
- 记录里多写一个不存在的 `ghost.dll` → 恰好 1 红（absent）；同一检查对 `libmpv-2.dll` 自身仍 PASS。
- 改动一份 DLL 副本、并把记录同步改成新 sha（哈希检查过）→ 恰好 1 红（`lgpl-candidate-claims`，十一处引用全部对不上）。

### 3.5 引擎能力与 Vulkan 结论（W37，2026-10-31）

许可证决策依赖一条能力判断：**产品不请求 Vulkan，候选件也不提供 Vulkan**。这条判断的两半都可能悄悄变（默认设置改了、候选件重建时把 Vulkan 编进去、结论文件被删），因此把它钉住：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `vulkan-claims` | ① `windows/engine/lgpl/mpv-lgpl-features.txt` 记录的特性表必须仍含 `d3d11`、`d3d-hwaccel`、`libplacebo`，且**不含** `vulkan`；② `windows/src/MediaHub.Player.Mpv/MpvPlayer.cs` 必须仍把 `gpu-api` 钉成 `d3d11`；③ `windows/src` 下任何 `.cs` 不得出现 vulkan 字样；④ `windows/docs/licensing/VULKAN-NECESSITY.md` 必须存在且保留 `verdict:` / `capability-delta:` 两行 | 候选件忽然带上 Vulkan、产品改为请求 Vulkan、或结论文件丢了——许可证面上「Vulkan 非必需」的说法不再成立，需要重新评估 |

`-EngineFeaturePath` / `-PlayerSourcePath` / `-VulkanDocPath` / `-SourceRootPath` 各自把上述输入指到别处（相对路径按仓库根解析，跨出仓库要写 `../../`；绝对路径会被拼到仓库根下，是上一轮踩过的坑），负向对照用它指向临时副本：

- 特性表副本里加一个 `vulkan` token → 恰好 1 红（候选件现在自称有 Vulkan）。
- 特性表副本里删掉 `d3d11`（或 `libplacebo`）token → 恰好 1 红（候选件不再提供产品要用的能力）。
- `MpvPlayer.cs` 副本把 `gpu-api` 改成 `vulkan` → 恰好 1 红（产品改为请求 Vulkan）。
- 独立源码目录里放一个含 vulkan 字样的 `.cs` → 恰好 1 红（源码面出现 Vulkan 依赖）。
- 结论文件副本删掉 `verdict:` 行 → 恰好 1 红（结论行丢失）。
- 特性表路径不存在 → 该项 SKIP、退出码 0（「记录缺失」不等于「结论错」）。

两半的来源、GPL 基线对照与维护者决策清单（A 接受并记录 / B 修 meson 探测重建 / C 保持 GPL 基线默认）见 [VULKAN-NECESSITY.md](VULKAN-NECESSITY.md)；当场复测用 `windows/tools/probe-engine-capabilities.ps1`。

### 3.6 LGPL flavour 的许可声明表（W38，2026-10-31）

`windows/engine/lgpl/component-licences.txt` 是 LGPL flavour 那 33 个 DLL 的逐行许可声明（哪一行属于哪个包、声明了什么许可）。此前它只有 28 行，而 `license-report.ps1` 的 `$LgplComponentMap` 对缺的 5 个包写死「未记录」，核对器只把缺口当 WARN ⇒ 表被改坏、行号指针失效都不会红。W38 补齐 33 行（收集脚本现在缺一行就报错退出）并新增一项：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `lgpl-licence-table` | ① 报告每行的 `license` 不得以 `未记录` 开头；② 每行的 `evidence` 必须含 `component-licences.txt`；③ `component-licences.txt:<N>` 形式的指针，第 N 行必须存在且真的含该许可串；④ `component-licences.txt:<A>-<B>` 范围指针（FFmpeg 用的注释行）必须落在表内、合并文本里出现该组件名、且带括号注记；⑤ 其它没有行号的写法直接 FAIL；⑥ 用 `collectedList`（`runtime-dependencies-collected.txt`）的文件名集合与报告行集合比对，缺名字或行数不符 FAIL | 声明表被改写/删行、证据行号指到别处、装出来的 DLL 与表不再一一对应——「33 个 DLL 的许可都已记录」这句话不再成立 |

报告缺 `lgplFlavour` 或 `dependencies` 为空时该项 SKIP（退出码仍 0）；`-FailOnLicenceGaps` 只保留 W38 之前的旧行为，硬门交给本项。

负向对照（§4.6 的实测，全部恰好 1 红，且都不改仓库文件）：

- 报告副本把某行许可改回 `未记录…` → 1 红（仍有文件没有声明）。
- 报告副本把 `libass-9.dll` 的许可从 `ISC` 改成 `MIT`（指针仍指第 8 行）→ 1 红（detail 回显该行原文）。
- 报告副本把 `zlib1.dll` 的证据改成 `component-licences.txt:99` → 1 红（表只有 40 行）。
- 报告副本删掉 `zlib1.dll` 那一行 → 1 红（装出来的清单与报告不一致）。
- 报告副本把 `avcodec-62.dll` 的证据改成 `component-licences.txt:1-2 (…)` → 1 红（注释行 1-2 从未出现 FFmpeg）。
- 报告副本把 `libexpat-1.dll` 的证据改成 `see component-licences.txt` → 1 红（没有任何行号）。
- `-JsonPath` 指向不存在的报告 → 该项 SKIP、退出码 0（与 §3.3 同口径）。

### 3.7 分发与签名状态（W39，2026-10-31）

`windows/docs/licensing/PACKAGING.md` 是唯一写清「今天到底发布什么」的文档：portable、self-contained、未签名（SIGNING_BLOCKED）、无 MSIX、无安装器、未在干净机装过、因 GPL 义务被阻止发布。它逐条记了实测值，但没有任何检查对照仓库 ⇒ 只要 CI 加了签名步骤、仓库里出现 `.appxmanifest` 或安装器工程、或 App 不再是 unpackaged self-contained portable，文档会继续宣称旧状态而 CI 全绿。

W39 给该文档加了 §0 机器可读状态块（W40 起为七行），并新增一项：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `packaging-claims` | ① `PACKAGING.md` 必须含 §0 的六行且值逐字相符：`signing-status: blocked-no-certificate`、`packaging-form: portable-zip-self-contained`、`msix: not-implemented`、`installer: not-implemented`、`clean-machine-install: not-run`、`release-status: blocked-gpl-obligation`；② App csproj 必须仍有 `<WindowsPackageType>None</WindowsPackageType>`、`<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>`、`<SelfContained>true</SelfContained>`、`<RuntimeIdentifier>win-x64</RuntimeIdentifier>`；③ `.github/workflows` 下不得出现签名令牌（`signtool` / `AzureSignTool` / `CertificateThumbprint` / `signingCertificate` / `-p:SignPackage` / `codesign`）；④ `windows/` 下（排除 `obj/`、`bin/`、`artifacts/` 构建输出）不得有 `*.appxmanifest` 或 `*.wixproj`/`*.wxs`/`*.iss`/`*.nsi`/`*.msix`；⑤ `windows-ci.yml` 必须仍含 `-o ./artifacts/portable` 与 `mediahub-windows-` | 发布形态/签名/安装状态与文档不符——「证书还没签、MSIX 还没做」这类如实状态被悄悄改写，或反过来签名做完了文档没更新 |

`## 0` 七行由人工与 §1 一起维护；文档缺失时该项 SKIP（退出码仍 0）。负向对照见 §4.7。

### 3.8 包内许可文件（W40，2026-10-31）

`PACKAGING.md` §1 与 `SBOM.md` §4.1 曾同时记录「发布包里 licence/notice 文件 = 0」——这是一条
独立于 GPL 的合规缺口。W40 让发布过程真的把它们放进去：`windows/tools/stage-licence-files.ps1`
把六个已在仓库里的文档/清单复制进 `<发布树>/licences/`，并生成 `MANIFEST.txt`（每行 sha256、字节数、
仓库内来源与包内名字）；`windows-ci.yml` 在 `Portable publish (Release)` 之后立刻调用它，smoke check 把
七个路径都列为必需，因此没有它们的包上不了 artifact。

| 包内文件 | 仓库内来源 | 作用 |
| --- | --- | --- |
| `THIRD-PARTY-NOTICES.md` | `windows/THIRD-PARTY-NOTICES.md` | 人读摘要：GPL 引擎结论 + LGPL 依赖族 |
| `THIRD-PARTY-NOTICES-FULL.md` | `windows/docs/THIRD-PARTY-NOTICES.md` | 生成的逐包表（84 行） |
| `LGPL-COMPLIANCE.md` | `windows/docs/LGPL-COMPLIANCE.md` | 动态链接事实、可替换 `libmpv-2.dll`、书面提供源码 |
| `LGPL-ENGINE-SWITCH.md` | `windows/docs/LGPL-ENGINE-SWITCH.md` | 用户如何自行更换 LGPL 引擎 |
| `component-licences.txt` | `windows/engine/lgpl/component-licences.txt` | 随包 33 个 DLL 的声明行（40 行） |
| `sbom.cdx.json` | `windows/docs/sbom-windows.cdx.json` | CycloneDX 1.5 依赖清单 |
| `MANIFEST.txt` | 暂存脚本生成 | 上述每个文件的摘要与大小 |

`package-licence-staging` 不读文档，而是把暂存脚本真跑一遍（写进临时树）：① 七个路径都出现；② 目录里
没有清单之外的文件；③ `MANIFEST.txt` 每个摘要行都能在前 64 位十六进制处与文件实际 sha256 对上，且摘要行数
恰为 6；④ `windows-ci.yml` 仍含 `stage-licence-files.ps1` 与 `licences/MANIFEST.txt`。任一不成立即 FAIL，
提示「改暂存脚本、publish 作业与 `PACKAGING.md` §0/§1、`SBOM.md` §4.1 要一起动」。脚本缺失时该项 FAIL
（它本身就是被钉的对象），不比 SKIP。负向对照见 §4.8。

仍未做的：licence 正文（LGPL-2.1-or-later、GPL-3.0-or-later、MIT、Apache-2.0、微软软件许可条款）没有
随包，仓库自身也没有 `LICENSE` 文件；两者属于许可证决策（`LICENCE-DECISION.md`），不由暂存步骤决定，
`MANIFEST.txt` 的表头已把这一点写进包内。

### 3.9 LGPL 合规说明的组件与基线（W41，2026-10-31）

`windows/docs/LGPL-COMPLIANCE.md` 是分发前必读的那份文档：它同时描述 GPL 基线与 LGPL 候选、引引擎摘要、列第三方许可。W38 修好了底表、W36 钉住了候选摘要，但没人比对该文档自己的组件清单与解析图，也没人复查 GPL 基线声明会在 pin 更新后失效。两项新检查补上：

| 检查 | 判据 | 失败含义 |
| --- | --- | --- |
| `lgpl-compliance-block` | 该文档 §5.1 的机器可读块（`<!-- BEGIN component-licence-table` … `<!-- END component-licence-table`，26 行「组件 → 许可」）必须与 `license-report.json` 的依赖行一一对应：少一个组件、多一个组件、或任一许可串不同即 FAIL | 随包组件清单与真实解析图分叉 ⇒ 分发前读的许可清单是错的 |
| `lgpl-compliance-baseline` | §1 必须仍引用报告解析出的 GPL 基线：`windows/native/libmpv/libmpv-2.dll`、千分位字节数、`pinnedSha256`、引擎版本串；报告带 `nativeEngine` 时逐项比对，报告缺该记录 ⇒ SKIP | pin 换引擎后文档继续宣称旧摘要 ⇒ 用户看到的二进制身份是过期的 |

说明：
- 比对前把许可串归一化（合并空白、大小写不敏感），因为表格里的作用域写法（如 `documentation:`）与大小写各不相同。
- 候选件本身不走这两项：它的字节与十一个引用点由 §3.4 的 `lgpl-candidate-hashes` / `lgpl-candidate-claims` 负责。
- 负向对照六条（各恰好 1 红，均用临时文档/报告副本，不改仓库文件）：① 块里把 `libiconv` 许可改回 `GPL-3.0-or-later`；② 删 `pcre2` 行；③ 加 `phantom-package` 幻影行；④ §1 的 sha256 换成 40 位悬空值；⑤ §1 的字节数改成 `120,989,700`；⑥ 报告副本删掉 `nativeEngine` ⇒ 该项 SKIP，但既有的 `report-engine-pin` 仍会红，故整次退出码仍为 1。

### 3.10 门禁自证：数量与 id 清单（W42，2026-10-31）

核对器「N 项检查」这个数字，从 W32 到 W41 一共手工改过八次；`LICENSE-SURFACE-CHECK.md` 与 `README.md` 里的那句话是全套工具中唯一没有任何机器校验的声明。第 36 项补上：

| 判据 | 内容 | 失败含义 |
| --- | --- | --- |
| A 数量漂移 | 本文件与 `README.md` 声明的数量都必须等于脚本第 13 段 `$declaredCheckIds` 的长度（当前 46） | 加了检查，但文档或 README 的数量没跟着改 |
| B 文档缺口 | `$declaredCheckIds` 里的每个 id 都必须出现在本文件正文中 | 加了检查，本文件的表里没有它的行 |
| C 未申报发射 | 本次运行 `$checks` 里出现的每个 id 都必须在清单里 | 加了检查但忘了登记清单（脚本与自证必须是同一个真源） |

三条任一出问题即 FAIL，detail 分别点名；清单里重复 id 也算 FAIL。清单写在脚本第 13 段（37 行），是「检查 id → 文档」的双向核对点：漏登记在 C 红，漏写文档在 B 红，漏改数字在 A 红。

两个新参数（都经 `Resolve-RepoPath`，相对路径按仓库根解析，跨出仓库要写 `../../`）：`-LicenceDocPath`（默认 `windows/docs/licensing/LICENSE-SURFACE-CHECK.md`）、`-ReadmePath`（默认 `README.md`）。任一文档读不到 → 该项 SKIP（退出码不变），因为此时无从比较。

负向对照见 §4.10。


### 3.11 引擎默认 flavour（W43，2026-10-31）

`windows/docs/LGPL-ENGINE-SWITCH.md` 声称「默认仍是 GPL 基线，`-p:LibMpvFlavour=lgpl` 只是旁路开关」。这句话此前没有任何机器校验：把 `MediaHub.Player.Mpv.csproj` 的缺省改成 `lgpl`、让别的工程或工作流用候选构建、或把文档里的口径删掉，CI 都会继续绿。

第 37 项 `engine-flavour-default` 一次核对四件事：

| 判据 | 看什么 | 什么情况红 |
| --- | --- | --- |
| 缺省值 | `windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj` 仍有 `<LibMpvFlavour Condition="'$(LibMpvFlavour)' == ''">gpl</LibMpvFlavour>` | 缺省被改成 `lgpl` 或该行被删 |
| 两 flavour 目录 | 同一 csproj 仍分别映射到 `native\libmpv` 与 `engine\lgpl\dist`，且仍有 `Unknown LibMpvFlavour` 的 Error | 目录映射或非法值守卫被移除 |
| 别无他人切换 | 其它 `*.csproj` 与 `.github/workflows/*.yml` 里不得出现把 `LibMpvFlavour` 赋成 `lgpl` 的写法 | 某工程或工作流开始构建候选 |
| 文档声明 | `LGPL-ENGINE-SWITCH.md` 仍含 `<!-- BEGIN engine-flavour-status -->` 块的三行（`default-flavour: gpl-baseline`、`candidate-flavour: lgpl-candidate-not-default`、`switch-flag: -p:LibMpvFlavour=lgpl`），且正文仍点名两套引擎目录 | 块被删或值被改，文档也不再提两套目录 |

两个新参数：`-PlayerProjectPath`（默认 `windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj`）与 `-LgplSwitchDocPath`（默认 `windows/docs/LGPL-ENGINE-SWITCH.md`）。切换文档读不到时本项 SKIP、退出码仍 0，与既有「报告缺失即 SKIP」语义一致。

负向对照（临时副本，不动仓库文件，各恰好 1 个 FAIL 检查）：csproj 副本把缺省改成 `lgpl` → 1 红；csproj 副本删掉 lgpl 目录映射 → 1 红；临时 workflows 里放一个带 `-p:LibMpvFlavour=lgpl` 的 yml → 1 红；文档副本把块里 `default-flavour` 的值改掉 → 1 红；文档副本删掉整块 → 1 红；`-LgplSwitchDocPath` 指向不存在文件 → 本项 SKIP、退出码 0。

### 3.12 许可决策记录（W44，2026-10-31）

第 38 项 `licence-decision-claims` 钉住 `windows/docs/licensing/LICENCE-DECISION.md` 这份「发布阻塞」的权威记录：

| 判据 | 通过条件 | 失败示例 |
| --- | --- | --- |
| 状态块 | 文档含 `<!-- BEGIN licence-decision-status -->` 块，块内三行逐字为 `status: open-release-blocker`、`shipped-engine-licence: GPL-3.0-or-later`、`repo-label-mismatch: resolved-2026-10-31` | 块被删；或把已落地的标签改回 `GPL-2.0-or-later` |
| 陈旧声明 | 正文不再命中 `the repository says GPL-2\.0-or-later` 与 `lines 10–11 state`（后一个正则同时吃 en dash；两个正则都带 `(?i)` 前缀，大小写不敏感） | 把 2026-10-14 版「仓库标错」的段落原样写回 |
| notices 标签 | 生成件 `windows/docs/THIRD-PARTY-NOTICES.md` 与手写件 `windows/THIRD-PARTY-NOTICES.md` 里含 `libmpv (mpv)` 的行都必须含 `GPL-3.0-or-later` | 任一文件把该行改回 `GPL-2.0-or-later`，或删掉该行 |

两个参数：`-LicenceDecisionDocPath`（默认 `windows/docs/licensing/LICENCE-DECISION.md`）、`-HandwrittenNoticesPath`（默认 `windows/THIRD-PARTY-NOTICES.md`），都按仓库根解析（跨出仓库要写 `../../`）。文档读不到时该检查 SKIP，退出码仍 0。

> 为什么钉它：这份记录是「不得对外分发」的唯一权威来源，却是 2026-10-14 手写的；W36/W38 改了两份 notices 的引擎标签之后，它 §1.5 与 §6 还在要求「把 notices 的 GPL-2.0-or-later 改成 GPL-3.0-or-later」——声明与仓库现状分叉，而 CI 全绿。W44 修掉这处分叉，并把标签、状态与 notices 三者锁在一起。


### 3.13 发行决策记录（W45，2026-10-31）

第 39 项 `adr-packaging-claims` 钉住 `windows/docs/adr/ADR-W004-packaging-and-distribution.md`（决定发行形式的 ADR，2026-10-14），它在 W40 之前没有任何机器校验，也第一个陈旧：“发布产物中不含任何许可证/声明文件”在包内已经随包 7 件之后已为假：

| 判据 | 通过条件 | 失败示例 |
| --- | --- | --- |
| 状态块 | ADR 含 `<!-- BEGIN adr-packaging-status` … `<!-- END adr-packaging-status` 块，块内逐字五行：`packaging-form: unpackaged-self-contained-portable`、`msix: optional-not-implemented`、`signing: blocked-no-certificate`、`licence-files-in-package: staged-by-ci-since-w40`、`native-libmpv: gitignored-and-fetched-by-tool` | 块被删；或把签名状态改成 `signed-by-ci` |
| 陈旧声明 | 正文不再命中 `(?i)不含任何许可证/声明文件` 与 `(?i)522 个文件里 0 个` | 把 2026-10-14 版“包内零许可文件”的句子原样写回 |
| 包属性引用 | ADR 里必须逐字出现 `<WindowsPackageType>None</WindowsPackageType>`、`<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>`、`<SelfContained>true</SelfContained>`、`<RuntimeIdentifier>win-x64</RuntimeIdentifier>`（值取自 App csproj 当场解析，而非写死） | App csproj 把某个属性改了而 ADR 还引旧值；或 csproj 不再设该属性 |
| native 不入库 | `.gitignore` 仍含 `windows/native/libmpv/`，且 `<RepoRoot>/windows/tools/fetch-libmpv.ps1` 仍存在 | `.gitignore` 规则被删；或 ADR 名点的拉取脚本被删（ADR 的“不入库”只对 `windows/native/libmpv/` 成立，入库的 LGPL 候选在 `windows/engine/lgpl/dist/`） |

两个参数：`-AdrPackagingPath`（默认 `windows/docs/adr/ADR-W004-packaging-and-distribution.md`）、`-GitIgnorePath`（默认 `.gitignore`），都按仓库根解析（跨出仓库要写 `../../`）。ADR 读不到时该检查 SKIP，退出码仍 0。

> 为什么钉它：ADR-W004 是“为什么选 unpackaged + 自包含”的唯一决策记录，也是 W39 的 `PACKAGING.md` 与实际产物形态之间的链接；它自己在 2026-10-14 写下的“包内零许可文件”已经被 W40 推翻，而两边都没有东西发现这个分叉。
### 3.14 引擎固定摘要（W46，2026-10-31）

第 40 项 `engine-pins-claims` 钉住 `windows/tools/fetch-libmpv.ps1` 里的两个固定摘要与归档文件名。该脚本是 GPL 基线 libmpv 的唯一获取口（拒绝装入任何摘要不符的归档/解包 DLL），而三份文档逐字引用它的 pin；此前没有任何检查比对这两侧：

| 判据 | 通过条件 | 失败示例 |
| --- | --- | --- |
| pin 可解析 | 脚本里仍以单引号字面量声明 `$AssetName`、`$AssetSha256`（64 位 hex）、`$DllSha256`（64 位 hex） | 三个常量被删/改名/改成拼接表达式 |
| 三份引用文档 | `windows/THIRD-PARTY-NOTICES.md`、`windows/docs/adr/ADR-W004-packaging-and-distribution.md`、`windows/docs/licensing/REPRODUCIBLE-BUILD.md` 都必须仍含**当前**归档摘要、**当前** DLL 摘要与归档文件名（大小写不敏感，按整文件文本比对） | 把 pin 上调到新版本而三份文档还写旧摘要；或把某份文档里的摘要改一位 |

四个参数：`-FetchScriptPath`（默认 `windows/tools/fetch-libmpv.ps1`）、`-PinsNoticesPath`、`-PinsAdrPath`、`-PinsReproPath`（三份引用文档），都按仓库根解析（跨出仓库要写 `../../`）。脚本或任一份引用文档缺失都算 FAIL（脚本是权威、文档是必须存在的证据；没有 SKIP 分支）。

> 为什么钉它：W36 只覆盖了入库的 LGPL 候选件，GPL 基线的两个摘要在 `fetch-libmpv.ps1` 里是常量、在文档里是 prose，pin 一换 prose 就过期；这与 W35/W36/W38/W41/W44/W45 是同一类「声明腐烂」，只是权威方是脚本常量而不是生成件。

### 3.15 能力对照表（W47，2026-10-31）

`windows/docs/CAPABILITY-MAP.md` 是「Windows 客户端能做什么」的汇总表，W47 之前没有任何机器校验：测试行写着固定计数（`477 tests / 0 failed`，而 CI 实测已是 `MediaHub.Tests 478` + `MediaHub.WebDav.Tests 104`），发布状态行停在 `NOT_RUN`，而 `PACKAGING.md` §0 与 App csproj 早已把签名记为 `SIGNING_BLOCKED`、包内许可文件记为 7 件。第 41 项 `capability-map-claims` 钉住三件：

| 判据 | 内容 |
| --- | --- |
| 状态块 | 表含 `<!-- BEGIN capability-map-status` … `<!-- END capability-map-status` 块，块内逐字九行：`capability-map-status: checked-by-ci`、`test-count-claim: see-ci`、`msix: not-implemented`、`signing: blocked-no-certificate`、`clean-machine-install: not-run`、`engine-flavour-default: gpl`、`engine-lgpl-candidate: built-not-switched`、`licence-files-in-package: staged-by-ci`、`release-blocker: gpl-obligation` |
| 不再写死计数 | 全文不得命中 `(\bi\d+\s+tests?\s*/\s*\d+\s+failed` 型计数行：用例数权威是 CI 打印（`test-count-claim: see-ci`） |
| 交叉核对 | `PACKAGING.md` §0 必须仍写 `msix: not-implemented`、`signing-status: blocked-no-certificate`、`clean-machine-install: not-run`、`licence-files-in-package: staged-by-ci`；播放器工程仍默认 `native\libmpv`（GPL 基线）；`windows/engine/lgpl/dist/libmpv-2.dll` 仍在（否则 `built-not-switched` 无依据） |

`-CapabilityMapPath` 可换一幅对照表（默认 `windows/docs/CAPABILITY-MAP.md`）；表不存在 ⇒ `SKIP`、退出码仍 0。它是**摘要层**门禁：权威仍是 `PACKAGING.md` §0、`LGPL-ENGINE-SWITCH.md` §0 与 `LICENCE-DECISION.md` §6 各自的状态块，本项只保证摘要不再与它们分叉。
### 3.16 验收「不打扰」契约（W48，2026-10-31）

`windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md` 是 GUI 验收的唯一入口契约（自 2026-10-24 起所有 GUI 验收只能经 `run-quiet.ps1` 启动）。它承诺三件事：不在人不在场时启动、人一回来就停、跑完不留残骸 —— 但 W48 之前，这份承诺只活在文档与脚本里，没有任何检查把两侧对上：把 `run-quiet.ps1` 的默认阈值调大、把 `MhQuiet.psm1` 的 QUNS 白名单放宽、或删掉 `Start-MhApp` 的 abort 标记检查，CI 都会全绿，而那正是「测试会打扰使用者」的入口。

| 判据 | 内容 |
| --- | --- |
| 状态块 | 文档含 `<!-- BEGIN quiet-run-status` … `<!-- END quiet-run-status` 块，块内逐字九行：`default-min-idle-seconds: 180`、`wait-for-idle-minutes-default: 0`、`idle-source: GetLastInputInfo`、`quns-allowed-states: 5,6`、`watchdog-poll-milliseconds: 700`、`abort-marker: watch-*.abort`、`suite-reentry-env: MH_ALLOW_ACTIVE`、`abort-flag-env: MH_QUIET_ABORT_FILE`、`gate-precedence: abort-marker-then-allow-active-then-idle-and-quns` |
| 与 runner 对账 | `run-quiet.ps1` 仍写 `[int]$MinIdleSeconds = 180`、`[int]$WaitForIdleMinutes = 0`、调用 `Start-MhQuietWatch`、导出 `MH_ALLOW_ACTIVE = '1'` 与 `MH_QUIET_ABORT_FILE`；文档参数表仍写 `-MinIdleSeconds` 默认 180、`-WaitForIdleMinutes` 默认 0 |
| 与门禁模块对账 | `windows/tools/acceptance/lib/MhQuiet.psm1` 仍查 `SHQueryUserNotificationState`、白名单仍是 `$state -eq 5 -or $state -eq 6`、看门狗仍 `[int]$PollMilliseconds = 700`、仍导出 `Test-MhQuietAborted` |
| 与守卫优先级对账 | `windows/tools/acceptance/lib/MhUia.psm1` 的 `Start-MhApp` 仍先查 `$env:MH_QUIET_ABORT_FILE`（抛 `refusing to start MediaHub: the quiet run was aborted by user input`），再看 `$env:MH_ALLOW_ACTIVE -ne '1'` |

四个路径参数（都按仓库根解析）：`-QuietRunbookPath`（默认 `windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md`）、`-QuietRunnerPath`（`windows/tools/acceptance/run-quiet.ps1`）、`-QuietModulePath`（`…/lib/MhQuiet.psm1`）、`-UiaModulePath`（`…/lib/MhUia.psm1`）。文档不存在 ⇒ `SKIP`、退出码仍 0；runner 或模块缺失 ⇒ `FAIL`。

它刻意**不**核对阈值的人读散文措辞，也不判断门禁真的挡住了谁 —— 那是 `run-quiet.ps1` 与看门狗的运行时职责（见文档「自检」节）。

### 3.17 续播下限契约（W49，2026-10-31）

`resume-floor-claims` 把「mock 与真服的保真度契约」从散文变成可核对的三方一致：

| 判据 | 失败含义 |
| --- | --- |
| 文档 §0 的 `resume-floor-status` 块含八行逐字 | 阈值、比较方式（百分比而非秒）、来源、用例数被改写或整块删除 |
| `MockMediaServer.cs` 含六个常量/表达式 | 下限被放宽（如 `Emby = new(2, 300)` 改 1）、上限或 10 s 尾窗被删、判定从百分比改成秒 |
| 契约测试展开的用例数 = 文档声明的用例数 | 加了用例没同步文档、或删了用例让保护面缩小 |

用例数由脚本当场数出（`[Fact]` 计 1、每条 `[InlineData(` 计 1），不是读文档；文档写成 31 而实现是别的数就红。三个路径参数（`-ResumeFloorDocPath`、`-MockServerPath`、`-ResumeFloorTestPath`）让负向对照可以指到临时副本；文档不存在 ⇒ SKIP（退出码仍 0）。

`windows/docs/W8-RESUME-FLOOR-CONTRACT.md` §2 的 469 是该轮单工程实跑的历史数字，本检查**不**要求它等于当前 CI 计数（那会随其它轮次改动而漂移）；它钉的是阈值语义与用例数。

### 3.18 诊断日志落盘契约（W50，2026-10-31）

`log-file-claims` 把 W14 的滚动日志承诺从散文变成可核对的五方一致：

| 判据 | 失败含义 |
| --- | --- |
| 文档 §0 的 `log-file-status` 块含十五行逐字 | 上限、轮转边界、永不抛出、可恢复失败清单、脱敏归属或用例数被改写或整块删除 |
| `LogFileSink.cs` 含七处常量/表达式 | 1 MiB / 5 文件默认值变了、文件名模板不再拼 `.log`、轮转触发式被删、整条写入的注释没了、`Describe` 不再给「类型名: 消息」 |
| 可恢复失败清单与两处 `catch` 计数 | `Append` 或构造不再吞异常（把诊断日志变成崩溃点），或五类磁盘错误里少了任何一类 |
| `LogService.cs` 含六处 token 与写入顺序 | 环形上限、`FileSink` 注入点、`FileSink?.Append(entry)` 调用、导出目录行或 `Redact` 被删；写入顺序不再是「内存环 → 文件 → EntryAdded」 |
| `AppServices.cs` 的组合根正则 + sink 用例数 = 文档声明数 | 应用不再把 sink 建在 `%LOCALAPPDATA%\MediaHub\logs`；或加减用例却没同步文档 |

用例数由脚本当场数出（`[Fact]` 计 1、每条 `[InlineData(` 计 1），不是读文档；文档写 9 而实现是别的数就红。五个路径参数（`-LogFileDocPath`、`-LogFileSinkPath`、`-LogServicePath`、`-AppServicesPath`、`-LogFileTestPath`）让负向对照可以指到临时副本；文档不存在 ⇒ SKIP（退出码仍 0）。

刻意**不**钉「本机实跑 478 passed / 0 failed」这类数字：它会随其它轮次的新增用例漂移，本检查钉的是契约语义与用例数。

### 3.19 日期快照审计（W51，2026-10-31）

`windows/docs/W12-DOC-AUDIT.md` 是 2026-10-24 凌晨的一次只读审计（当时 477 tests、PR #33 停在 `282dcc2`、libmpv 吃 120,989,696 B 等）。它天生会过期，而它的数字与当前计数本来就不同——最容易出的两件事是有人把它的 477 当成「现在」引用，或者有人改掉它的基线提交，让整份快照失去锚点。第 45 项 `doc-audit-snapshot-claims` 钉住：

| 判据 | 失败含义 |
| --- | --- |
| §0 的 `doc-audit-status` 块含八行逐字 | 快照状态、审计日期、基线提交/分支、数字口径、复核规则、当前权威来源或文档角色被改写或整块删除 |
| 正文的 `本地 HEAD = <sha>` 与块内 `baseline-head` 相等 | 块与正文分叉（改了一处忘了另一处），或正文不再点名快照取自哪个提交 |
| `origin/<baseline-branch>` 仍出现在正文 | 快照不再绑定到它当时的分支 |
| 文档仍说数字是 **as-of 快照**，且仍要求「接近当前」的用法重新核对 | 这份过期审计被悄悄改写成当前权威 |
| `## 4. 不跑测试 / 不构建即无法判定` 一节仍在 | 快照自己声明的边界被删掉，后来的读者会以为它当时就能判定 |
| `git cat-file -t <sha>` 为 commit 且该 sha 是 HEAD 的祖先 | 基线提交被改成不存在的对象，或不再属于本分支历史（浅克隆 ⇒ WARN 并提示 `fetch-depth: 0`） |

一个路径参数 `-DocAuditPath` 让负向对照可以指到临时副本；文档不存在 ⇒ SKIP（退出码仍 0）。

刻意**不**要求它的 477 / 373 等历史数字等于当前计数：那正是「历史快照」的含义，本轮反而要求它保持是历史。

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

### 4.2 实测（2026-10-30，W33 + W34，本机）

```text
license-report  dotnet=10.0.113  pwsh=7.6.9
license-report  projects=13  rows=49  packages=29  violations=0  warnings=3

check-licence-surface  checks=25 failed=0 warnings=1 skipped=0
check-licence-surface  PASS  sbom-inventory  29 resolved package(s) recorded one for one in the SBOM (29 pkg:nuget components)
check-licence-surface  PASS  sbom-inventory-licences  every recorded licence expression still matches the resolved graph (29 rows)
check-licence-surface  PASS  sbom-source-revision  generated from feature/windows-client@1c30471, an ancestor of HEAD (16f40a46df56) - the inventory above is what matters; regenerate to refresh the recorded revision
check-licence-surface  PASS  sdk-pin-matches-report  the report was produced by the pinned SDK 10.0.113
check-licence-surface  RESULT: PASS
```

`warnings=3` 比 §4 多，是因为这一版报告把 `Microsoft.Windows.SDK.BuildTools@10.0.26100.4654` 与 `xunit.abstractions@2.0.3` 只拿到 `licenseUrl`（另有一条同类记录），属许可来源缺口而非库存漂移。`packages=29` 正是关闭修剪后的图；入库 SBOM 与之逐条相同，因此无需重新生成——W32 的库存检查、W33 的 pin 检查与 W34 的关修剪合起来，让「它必须继续新鲜」这件事在 CI 里可判定。

CI 侧的第一手证据（`16f40a46`，run 41079426815，step「Licence surface guard」）：`FAIL sbom-inventory the SBOM is stale: 0 resolved package(s) missing [], 1 recorded but no longer resolved [pkg:nuget/System.Reflection.Metadata@1.6.0]`，同时 `PASS sbom-source-revision`（`fetch-depth: 0` 生效）与 `PASS sdk-pin-matches-report the report was produced by the pinned SDK 10.0.113`——这组行正是上面诊断的现场。

有一条强度差异必须写明：CI 的 checkout 是完整克隆（`fetch-depth: 0`）时，来源提交判定是硬门（FAIL）；浅克隆里该对象不存在，同一检查只给 WARN。这条检查的强度取决于克隆深度，仓库 CI 已把它设成硬门形态。

### 4.3 实测（2026-10-31，W35，本机，HEAD a517e663）

```text
check-licence-surface  checks=27 failed=0 warnings=1 skipped=0
check-licence-surface  PASS  notices-inventory  29 resolved package(s) each have a notices row with the resolved version and licence (84 table rows)
check-licence-surface  PASS  notices-declarations  5 directly declared package(s) are each named in the notices doc
RESULT: PASS
```

（`warnings=1` 仍是既有的 `lgpl-licence-gaps` 五行：默认 flavour 不分发那五个 DLL。29 个解析包与 5 个直接声明包此刻都对得上 ⇒ 入库 notices 是新鲜的；W35 的价值是让将来的漂移在 CI 里红掉。）

### 4.4 实测（2026-10-31，W36，本机）

```text
check-licence-surface  checks=29 failed=0 warnings=1 skipped=0
check-licence-surface  PASS  lgpl-candidate-hashes  2 recorded engine artefact(s) still hash to their recorded digest (libmpv-2.dll 3,327,274 B)
check-licence-surface  PASS  lgpl-candidate-claims  11 claim site(s) quote the observed candidate identity (3,327,274 B, 20035902...3c0a)
RESULT: PASS
```

（入库候选件 `libmpv-2.dll` 与 `mpv.exe` 都与 `dist-hashes.txt` 一致，十一处引用也都写着同一个构建 ⇒ 此刻是新鲜的；W36 的价值是让「换包/改文档」在未来红掉。报告用 W34 生成的那份：`projects=13 rows=49 packages=29 violations=0 warnings=3`。缺报告时这两项不 SKIP，照常 PASS，见 §3.3 的 `-JsonPath` 对照。）

### 4.5 实测（2026-10-31，W37，本机）

`BT`text
check-licence-surface  checks=30 failed=0 warnings=2 skipped=0
check-licence-surface  PASS  vulkan-claims  the candidate still advertises d3d11/d3d-hwaccel/libplacebo without vulkan (28 features), the player still pins gpu-api=d3d11, and no .cs under windows/src mentions vulkan
RESULT: PASS
`BT`

（`warnings=2` 是本机这一跑里 `git` 不在进程 PATH 上，`sbom-source-revision` 因此降为 WARN；CI 的完整克隆里它是硬门。候选件的能力面另由 `windows/tools/probe-engine-capabilities.ps1` 当场探测：`gpu-api` 只列 `auto, d3d11, opengl`，`gpu-context` 只列 `auto, d3d11, win, dxinterop`，`vo` 含 `gpu-next`，特性表 28 项不含 `vulkan` ⇒ 产品要的每一项都在，Vulkan 确实缺，结论与实现一致。）

### 4.6 实测（2026-10-31，W38，本机）

```text
license-report  dotnet=10.0.113  pwsh=7.6.9  generated=2026-10-30T19:55:05Z
license-report  projects=13  rows=49  packages=29
license-report  lgpl-flavour dependencies: present=True count=33 unrecorded=0 (windows/engine/lgpl/dist/runtime-dependencies-collected.txt)
license-report  violations=0  warnings=4
license-report  RESULT: PASS (no policy violations)

check-licence-surface  checks=31 failed=0 warnings=0 skipped=0
check-licence-surface  PASS  lgpl-licence-gaps  every row has a declaration line
check-licence-surface  PASS  lgpl-licence-table  33 shipped LGPL-flavour DLL(s) each quote a licence declaration that component-licences.txt really holds (40 table line(s), shipped list 33 file(s))
RESULT: PASS
```

`warnings=0` 是本轮起 33 个 DLL 全部有声明行；`warnings=4` 里多出的那条是 `MediaHub.Tests: project.assets.json is older than the .csproj` 的本机陈旧警告，与库存无关。`component-licences.txt` 共 40 行、数据行 8..40（33 行）；`libiconv` 现声明为 `LGPL-2.1-or-later`（文档部分 `documentation:GPL-3.0-or-later`），因此许可族归类把它从 GPL 族移入 LGPL 族，见 [SBOM.md](SBOM.md) §7.2 与 [windows/THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md)。缺报告时该检查 SKIP、退出码 0（§3.6）。

### 4.7 实测（2026-10-31，W39，本机，HEAD 06aadcaf）

```text
check-licence-surface  checks=32 failed=0 warnings=0 skipped=0
check-licence-surface  PASS  lgpl-licence-table  33 shipped LGPL-flavour DLL(s) each quote a licence declaration that component-licences.txt really holds (40 table line(s), shipped list 33 file(s))
check-licence-surface  PASS  packaging-claims  the shipping state in PACKAGING.md still matches the repository: portable self-contained publish, no signing step, no MSIX manifest, no installer project outside build output (6 machine-readable lines)
RESULT: PASS
```

W39 首跑该项 FAIL，库里的报错点出了 `windows/src/MediaHub.App/obj/**/MsixContent/MSIX/*.msix` 六个文件——那是 Windows App SDK 的 MSIX 工具在 Debug 构建时生成的**构建输出**（`EnableMsixTooling=true` 只开工具，仓库里没有 tracked 的 MSIX 工程）。据此把「仓库现状」的扫描口径明确为排除 `obj/`、`bin/`、`artifacts/`，并在检查注释与本文件里写明，避免把构建产物当成发布形态。

七条负向对照各恰好 1 红（全部用临时副本，不改仓库文件）：

- 文档副本把 `signing-status` 的值改成 `signed-by-ci` → 1 红（该行不匹配）。
- 文档副本删掉 `msix: not-implemented` 行 → 1 红（缺行）。
- 临时 workflows 目录里放一份含 `signtool` 的 `sign.yml` → 1 红（文档说无人签名，CI 开始签名）。
- 临时 windows 根下放 `Package.appxmanifest` → 1 红（文档说 MSIX 未实现）。
- App csproj 副本把 `<SelfContained>true</SelfContained>` 改成 `false` → 1 红（发布形态漂移）。
- `-PackagingDocPath` 指向不存在的文件 → 该项 SKIP、退出码 0。
- 临时 workflows 里放缺 `-o ./artifacts/portable` 的 `windows-ci.yml` → 1 红（发布/上传步骤漂移）。

### 4.8 实测（2026-10-31，W40，本机，HEAD 1cb89525）

```text
stage-licence-files.ps1: 6 files + MANIFEST.txt written into a temp tree (exit 0)
check-licence-surface  checks=33 failed=0 warnings=0 skipped=0
check-licence-surface  PASS  packaging-claims  the shipping state in PACKAGING.md still matches the repository: ... (7 machine-readable lines)
check-licence-surface  PASS  package-licence-staging  the staging script puts 7 licence/notice file(s) into the published tree with a matching digest manifest, and windows-ci.yml runs it before the smoke check
RESULT: PASS
```

六条负向对照各恰好 1 红（全部临时副本/临时目录，不改仓库文件）：

- 暂存脚本副本删掉 `component-licences.txt` 一项 → 1 红（该文件不会出现在包里）。
- 暂存脚本副本把 `MANIFEST.txt` 某行摘要写成假的 → 1 红（摘要与字节不符）。
- 暂存脚本副本额外暂存一份 `extra.txt` → 1 红（清单之外的文件）。
- `-StageScriptPath` 指向不存在的文件 → 1 红（暂存脚本不在）。
- `-WorkflowsPath` 指向缺 `stage-licence-files.ps1` 的 `windows-ci.yml` → 1 红（CI 不再暂存）。
- `PACKAGING.md` 副本少了 `licence-files-in-package: staged-by-ci` 行 → 1 红（`packaging-claims`）。

### 4.9 实测（2026-10-31，W41，本机，HEAD 893f510d）

```
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS  lgpl-compliance-block  the machine-readable component table in LGPL-COMPLIANCE.md matches the resolved dependency graph (26 component row(s), 26 resolved component(s))
check-licence-surface  PASS  lgpl-compliance-baseline  section 1 still quotes the GPL baseline the report resolved (windows/native/libmpv/libmpv-2.dll, 120,989,696 B, 6a48faec..., v0.41.0-1154-gec38361c5)
check-licence-surface  checks=35 failed=0 warnings=0 skipped=0
RESULT: PASS
```

- 文档侧同步补齐的真实漂移：§5 的逐组件清单还停在 W38 之前——缺 5 个包（libwinpthread-git、pcre2、expat、bzip2、gettext-runtime），且把 `libiconv` 写成 GPL-3.0-or-later；§5「两处需要人工确认」第 1 条已改写为 W38 已核实。新的 §5.1 把 26 个随包组件的「组件 → 许可」做成机器可读块，交由 `lgpl-compliance-block` 钉住。
- 六条负向对照见 §3.9。

### 4.10 实测（2026-10-31，W42，本机，HEAD 752c9b99）

```text
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS  check-inventory  36 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=36 failed=0 warnings=0 skipped=0
RESULT: PASS
```

- 本轮新增的真实发现（由负向对照逼出来）：判据 B 最初用 `IndexOf` 做子串匹配，于是把某个 id 改名成 `lgpl-compliance-baseline-X` 时它仍算「文档写到了」——对照 NC3 因此不红。改成边界正则（id 两侧不得是 `[A-Za-z0-9-]`）后才真正红；§3.10 的判据表与 PASS 文案据此改写。证据日志 `D:/mh-verify/w42-nc3.log`。
- 六条负向对照（各恰好 1 个 FAIL 检查，均用临时副本，不改仓库文件）：① `LICENSE-SURFACE-CHECK.md` 副本把数量改成 35 → 1 红；② `README.md` 副本数量改 35 → 1 红；③ 文档副本把 `lgpl-compliance-baseline` 改名 → 1 红（边界匹配生效）；④ 脚本副本改掉 `vulkan-claims` 的发射名 → 1 红（未申报发射）；⑤ 脚本副本从清单删掉 `lgpl-licence-table` 一行 → 1 红（该 detail 列出数量漂移与未申报发射共 3 条）；⑥ `-LicenceDocPath` 指向不存在的文件 → SKIP、退出码 0。日志 `D:/mh-verify/w42-nc1.log` … `w42-nc6.log`。
- 复现命令（两个参数都按仓库根解析，跨出仓库要写 `../../`）：`pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -LicenceDocPath ../../mh-verify/w42-neg/nc1-doc.md`。

### 4.11 实测（2026-10-31，W43，本机，HEAD 63d51e9c）

```text
check-licence-surface  PASS  engine-flavour-default  the default flavour is still gpl-baseline in the player project, nothing else builds the LGPL candidate, and LGPL-ENGINE-SWITCH.md still declares it (3 machine-readable lines)
check-licence-surface  PASS  check-inventory  37 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=37 failed=0 warnings=0 skipped=0
RESULT: PASS
```

（报告用 W34 生成的那份：`projects=13 rows=49 packages=29 violations=0 warnings=3`；本机该进程 PATH 含 git 时 `sbom-source-revision` 为 PASS，否则按设计降为 WARN。）

**首版真发现**：扫描「其它工程或工作流是否构建候选」时，`MediaHub.Player.Mpv.csproj` 自己的帮助注释里就写着 `-p:LibMpvFlavour=lgpl`（第 19 行），所以「路径必须等于 `-PlayerProjectPath`」的排除不够——参数换成外部副本时，仓库里那份真 csproj 会被误报成「别的工程在构建候选」。改成「文件名等于 `MediaHub.Player.Mpv.csproj` 一律排除，其余文件命中即红」。

负向对照（临时副本，不动仓库文件，各恰好 1 个 FAIL 检查）：

- csproj 副本把缺省值改成 `lgpl` → 1 红；
- csproj 副本删掉 `engine\lgpl\dist` 的目录映射 → 1 红；
- 临时 workflows 里放一个带 `-p:LibMpvFlavour=lgpl` 的 `switch.yml` → 1 红；
- 文档副本把块里 `default-flavour` 的值改掉 → 1 红；
- 文档副本删掉整块 → 1 红；
- `-PlayerProjectPath` 指向不存在的文件 → 1 红（工程缺失是仓库损坏，不是 SKIP）；
- `-LgplSwitchDocPath` 指向不存在文件 → 本项 SKIP、`checks=37 failed=0 skipped=1`、退出码 0。

### 4.12 实测（2026-10-31，W44，本机，HEAD d1b8b7b2）

```text
check-licence-surface  PASS  licence-decision-claims  LICENCE-DECISION.md still declares the open release blocker and the GPL-3.0-or-later engine label, and both notice files still label libmpv GPL-3.0-or-later (3 machine-readable lines)
check-licence-surface  PASS  check-inventory  38 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=38 failed=0 warnings=0 skipped=0
check-licence-surface  RESULT: PASS
```

补丁落地、文档还没改时的第一次跑（`D:/mh-verify/w44-positive-first.log`）如实报红两条：`licence-decision-claims` 列出 3 个问题（无 `licence-decision-status` 块、两处陈旧声明正则命中），`check-inventory` 列出 3 个问题（文档与 README 还写 37 项、文档未点名 `licence-decision-claims`）——这正是 W42 自证项「加了检查却忘了同步文档就红」的效果。

**真实发现**：第一版 NC3 把陈旧句写成大写开头的 `The repository says GPL-2.0-or-later`，检查没有变红 —— .NET 正则默认区分大小写，将来把这句话首字母大写就会漏过。已给两个陈旧正则加 `(?i)` 前缀，之后同一个大写变体的对照恰好 1 红。

六条负向对照（临时副本，不改仓库文件；日志 `D:/mh-verify/w44b-ncN.log`）：

| 对照 | 改动 | 结果 |
| --- | --- | --- |
| NC1 | 状态块 `shipped-engine-licence` 改成 `MIT` | 恰好 1 红：`the status block does not say shipped-engine-licence: GPL-3.0-or-later` |
| NC2 | 删掉整个状态块 | 恰好 1 红：`LICENCE-DECISION.md carries no licence-decision-status block` |
| NC3 | 文档末尾写回大写的 `The repository says GPL-2.0-or-later` | 恰好 1 红（判据 B） |
| NC4 | 手写 notices 的 libmpv 行标签改 `GPL-2.0-or-later` | 恰好 1 红：`the libmpv row in nc4-handwritten-label.md no longer says GPL-3.0-or-later` |
| NC5 | `-LicenceDecisionDocPath` 指向不存在的文件 | SKIP、退出码 0（`checks=38 failed=0 skipped=1`） |
| NC6 | 手写 notices 里删掉 libmpv 那一行 | 恰好 1 红：`no libmpv (mpv) row in nc6-handwritten-norow.md` |

生成件那份 notices 走的是同一段代码（正向运行读的就是生成件）；NC4/NC6 打在可单独替换的手写件上，这样每条对照都保持「恰好 1 红」而不牵连其它 notices 检查。


### 4.13 实测（2026-10-31，W45，本机，HEAD bd75aba2）

本机（报告 `D:/mh-verify/w38-license/license-report.json`）：

```text
check-licence-surface  PASS  adr-packaging-claims  ADR-W004 still describes the repository: the status block matches, the four packaging properties are quoted exactly as the app project sets them, the licence-file claim is current, and the native libmpv tree stays ignored and fetched (5 machine-readable lines)
check-licence-surface  PASS  check-inventory  39 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=39 failed=0 warnings=0 skipped=0
RESULT: PASS
```

此前证据：守卫补丁刚落地、两份文档还没同步时的第一次跑（`D:/mh-verify/w45-positive-first.log`）如实报红一条：`check-inventory` 列出 3 个问题（文档与 README 还写 38 项、文档未点名 `adr-packaging-claims`）——新检查新 id 与文档数字必须一起动，这正是 W42 自证项的作用。

七条负向对照（临时副本 `D:/mh-verify/w45-neg/`，不改仓库文件）：

| 编号 | 动作 | 结果 |
| --- | --- | --- |
| NC1 | ADR 副本把 `signing: blocked-no-certificate` 改成 `signed-by-ci` | 恰好 1 红：`the status block does not say signing: blocked-no-certificate` |
| NC2 | ADR 副本删掉整个 `adr-packaging-status` 块 | 恰好 1 红：`ADR-W004 carries no adr-packaging-status block` |
| NC3 | ADR 副本末尾写回 2026-10-14 版「包内零许可文件」句（含 522/0） | 恰好 1 红，该行列出两个陈旧正则各命中一次 |
| NC4 | ADR 副本把 XML 块里的 `<SelfContained>true</SelfContained>` 改成 `false` | 恰好 1 红：“不再引用 App csproj 当场取到的值” |
| NC5 | ADR 副本把 `licence-files-in-package` 值改成 `absent` | 恰好 1 红：“状态块不再声明 staged-by-ci-since-w40” |
| NC6 | `-AdrPackagingPath` 指向不存在的文件 | SKIP、退出码 0（`checks=39 failed=0 skipped=1`） |
| NC7 | `-GitIgnorePath` 指向已把 `windows/native/libmpv/` 规则改名的副本 | 恰好 1 红：`the ignore file no longer excludes windows/native/libmpv/` |

日志 `D:/mh-verify/w45-positive.log`（正向）与 `D:/mh-verify/w45-nc1.log` … `w45-nc7.log`；载荷 `D:/mh-verify/w45-guard-payload.json`（守卫补丁）。
### 4.14 实测（2026-10-31，W46，本机，HEAD ac96fe45）

本机（报告 `D:/mh-verify/w38-license/license-report.json`）：

```text
check-licence-surface  PASS  engine-pins-claims  the archive digest, the extracted dll digest and the archive name pinned by fetch-libmpv.ps1 are still quoted by all 3 citing document(s)
check-licence-surface  PASS  check-inventory  40 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=40 failed=0 warnings=0 skipped=0
RESULT: PASS
```

首次实现时的两个真发现（都留在守卫代码注释/口径里）：① 用 PowerShell 双引号里的 ``$` 转义只能阻止变量插值，写进 .NET 正则后 `$` 仍是行尾锚点 ⇒ 三个 pin 正则改为 `[regex]::Escape('$AssetName')` 拼接；② 该检查先于文档同步落地时，`check-inventory` 如实报 3 个问题（文档与 README 还写 39 项、文档未点名 `engine-pins-claims`）。

七条负向对照（临时副本 `D:/mh-verify/w46-neg/`，不改仓库文件）：

| 编号 | 动作 | 结果 |
| --- | --- | --- |
| NC1 | 脚本副本把 `$AssetSha256` 换成另一个合法 hex | 恰好 1 红：三份文档都不再含新归档摘要 |
| NC2 | notices 副本把归档摘要改一位 | 恰好 1 红：该文档不再引用归档摘要 |
| NC3 | ADR 副本删掉 DLL 摘要 | 恰好 1 红：该文档不再引用解包 DLL 摘要 |
| NC4 | `REPRODUCIBLE-BUILD.md` 副本把归档文件名改掉 | 恰好 1 红：该文档不再引用归档文件名 |
| NC5 | `-FetchScriptPath` 指向不存在的文件 | 恰好 1 红：脚本缺失 |
| NC6 | `-PinsNoticesPath` 指向不存在的文件 | 恰好 1 红：引用文档缺失 |
| NC7 | 四份临时副本把 pin 与三处引用**同步**改成另一组合法值 | PASS（证明检查跟随脚本，而非写死当前值） |

### 4.15 实测（2026-10-31，W47，本机，HEAD cd253c0d）

``~text
check-licence-surface  PASS  capability-map-claims  the capability map still describes the repository: 9 machine-readable lines, shipping claims agreeing with PACKAGING.md, the GPL baseline default in the player project and the built LGPL candidate
check-licence-surface  PASS  check-inventory  41 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=41 failed=0 warnings=0 skipped=0
check-licence-surface  RESULT: PASS
``~

七条负向对照（临时副本，均不改仓库文件；日志 `D:/mh-verify/w47-nc*.log`）：

| # | 改动 | 结果 |
| --- | --- | --- |
| NC1 | 表副本把 `signing` 值改成 `signed-by-ci` | 恰好 1 红：状态块不再声明 `signing: blocked-no-certificate` |
| NC2 | 表副本删掉整个状态块 | 恰好 1 红：无 `capability-map-status` 块 |
| NC3 | 表副本测试行写回 `478 tests / 0 failed` | 恰好 1 红：测试行又写死计数 |
| NC4 | 表副本删掉 `release-blocker: gpl-obligation` 行 | 恰好 1 红：状态块缺该行 |
| NC5 | `PACKAGING.md` 副本把 `signing-status` 改成 `signed-by-ci` | **2 红**（`packaging-claims` 与 `capability-map-claims` 同时响——交叉核对本就与权威检查重叠，这是预期的冗余，不是缺陷） |
| NC5b | 表副本删掉 `licence-files-in-package: staged-by-ci` 行 | 恰好 1 红：状态块缺该行（只打新检查） |
| NC6 | 播放器 csproj 副本把缺省 flavour 映射改成 LGPL 目录 | **2 红**（`engine-flavour-default` 与 `capability-map-claims` 同时响，同样是预期的冗余） |
| NC6b | 表副本把 `engine-flavour-default: gpl` 改成 `lgpl` | 恰好 1 红：状态块不再声明 `gpl`（只打新检查） |
| NC7 | `-CapabilityMapPath` 指向不存在文件 | `SKIP`、退出码 0 |
### 4.16 实测（2026-10-31，W48，本机，HEAD da0948cf）

```text
check-licence-surface  PASS  quiet-run-claims  the quiet-run safety contract still holds: the runbook block names 9 gate facts, the runner still defaults to 180 s idle and 700 ms polling, the QUNS allow-list is still 5/6, and Start-MhApp still refuses on an abort marker before MH_ALLOW_ACTIVE
check-licence-surface  PASS  check-inventory  42 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=42 failed=0 warnings=0 skipped=0
check-licence-surface  RESULT: PASS
```

守卫补丁先落地、文档未同步时的首跑如实 2 红（保留为证据）：`quiet-run-claims` 报「the runbook carries no quiet-run-status block」，`check-inventory` 报 3 个问题（两份文档还写 41 项、文档未点名 `quiet-run-claims`）。

七条负向对照（临时副本，均不改仓库文件；日志 `D:/mh-verify/w48-nc*.log`）：

| # | 改动 | 结果 |
| --- | --- | --- |
| NC1 | 文档副本把状态块的 `default-min-idle-seconds` 改成 900 | 恰好 1 红：状态块不再声明 `180` |
| NC2 | 文档副本删掉整个状态块 | 恰好 1 红：无 `quiet-run-status` 块 |
| NC3 | `run-quiet.ps1` 副本把默认 `[int]$MinIdleSeconds = 180` 改成 900 | 恰好 1 红：runner 不再含该默认行 |
| NC4 | `MhUia.psm1` 副本把 `$env:MH_QUIET_ABORT_FILE` 全改成 `$env:MH_QUIET_ABORT_MARKER` | 恰好 1 红：`Start-MhApp` 不再含 abort 标记检查 |
| NC5 | 文档副本把参数表的 `-WaitForIdleMinutes` 默认值 0 改成 5 | 恰好 1 红：参数表不再声明 0 |
| NC6 | `-QuietRunbookPath` 指向不存在文件 | `SKIP`、退出码 0 |
| NC7 | `MhQuiet.psm1` 副本把 QUNS 白名单 `$state -eq 5 -or $state -eq 6` 改成 `$state -ge 1` | 恰好 1 红：白名单不再是 5/6 |

七条里六条打在 `quiet-run-claims` 上、一条走 SKIP 通道，没有一条依赖别的检查 —— 这条契约此后不能再被悄悄放宽。

### 4.17 实测（2026-10-31，W49，本机，HEAD 47913224）

代码：

```text
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS    resume-floor-claims  the resume-floor contract still holds: the document block names the measured floors (Emby 2 %, Jellyfin 5 %, 300 s minimum, 90 % ceiling, 10 s trailing window) and the mock plus its tests still expand to 31 case(s)
check-licence-surface  PASS    check-inventory  43 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=43 failed=0 warnings=0 skipped=0
RESULT: PASS
```

七条负向对照（临时副本，未改仓库文件）：

| # | 改法 | 结果 |
| --- | --- | --- |
| NC1 | 文档块 `emby-min-percent` 改 3 | 恰好 1 红 |
| NC2 | 删掉整块 | 恰好 1 红 |
| NC3 | mock 副本 `Emby = new(2, 300)` 改 `new(1, 300)` | 恰好 1 红 |
| NC4 | 契约测试副本删一条 `[InlineData(` | 恰好 1 红（用例数分叉） |
| NC5 | mock 副本删 `TrailingWindowTicks` 常量 | 恰好 1 红 |
| NC6 | `-ResumeFloorDocPath` 指不存在文件 | SKIP、退出码 0 |
| NC7 | 文档块 `contract-case-count` 改 30（测试不动） | 恰好 1 红 |

### 4.18 实测（2026-10-31，W50，本机，HEAD 05d53cb6）

代码：

```text
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS    log-file-claims  the rolling-file contract still holds: 9 test case(s) pin a 1 MiB / 5-file trail that rotates between entries, never throws, and records LastError for the five recoverable failure types, while LogService keeps the 2000-entry ring and the export directory line
check-licence-surface  PASS    check-inventory  44 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=44 failed=0 warnings=0 skipped=0
RESULT: PASS
```

守卫补丁先落地、文档未同步时的首跑如实 1 红（保留为证据）：新项已 PASS，`check-inventory` 报 3 个问题（两份文档还写 43 项、文档未点名 `log-file-claims`）。

八条负向对照（临时副本，未改仓库文件）：

| # | 改法 | 结果 |
| --- | --- | --- |
| NC1 | 文档副本块 `max-files: 5` 改 7 | 恰好 1 红 |
| NC2 | 删掉整个 `log-file-status` 块 | 恰好 1 红 |
| NC3 | sink 副本 `DefaultMaxFiles = 5` 改 7 | 恰好 1 红 |
| NC4 | sink 副本可恢复清单里 `NotSupportedException` 换成别的类型 | 恰好 1 红 |
| NC5 | service 副本删掉 `FileSink?.Append(entry);` | 恰好 1 红（同时触发写入顺序判据） |
| NC6 | 组合根副本 `LocalApplicationData` 改 `ApplicationData` | 恰好 1 红 |
| NC7 | 文档块 `contract-case-count` 改 10（测试不动） | 恰好 1 红 |
| NC8 | `-LogFileDocPath` 指不存在文件 | SKIP、退出码 0 |

七条打在 `log-file-claims` 上、一条走 SKIP 通道，没有一条依赖别的检查 —— W14 的「日志永远有界、永远不因磁盘出错而崩」此后不能再被悄悄放宽。

### 4.19 实测（2026-10-31，W51，本机，HEAD 2bdaa499）

代码：

```text
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS    doc-audit-snapshot-claims  the W12 audit is still a dated snapshot: baseline 282dcc2058815317a3ce285f04855d078404a7ab on origin/feature/windows-client is a real ancestor of HEAD, its numbers are labelled as-of that point, and the undetermined list is still present
check-licence-surface  PASS    check-inventory  45 declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count
check-licence-surface  checks=45 failed=0 warnings=0 skipped=0
RESULT: PASS
```

守卫补丁先落地、文档未同步时的首跑如实 1 红（保留为证据）：新项已 PASS，`check-inventory` 报 3 个问题（两份文档还写 44 项、文档未点名 `doc-audit-snapshot-claims`）。更强的一条证据：新项自己被负向对照逼出过**两个**缺陷——① 正文正则多写了一个 `.`，报「the body no longer names the baseline HEAD the snapshot was taken at」（NC3 复现同一句）；② NC5 不是「1 红」而是整份脚本抛 `不能对值为 Null 的表达式调用方法`（守卫提前终止、连汇总行都没有）——真因是 `[string](@(& git …) | Select-Object -First 1)` 在原生命令没有输出时得到 `$null`，随后 `.Trim()` 炸掉；改成 `$out = @(& …)` + 计数判空后再取值才修好（此后 `.Trim()` 全部去掉）。负向对照不只是证明判据会红，也逼出了「空输出即崩」这一类守卫自身的脆弱点。

七条负向对照（临时副本，未改仓库文件）：

| # | 改法 | 结果 |
| --- | --- | --- |
| NC1 | 文档副本块 `audit-date` 改 2026-10-25 | 恰好 1 红 |
| NC2 | 删掉整个 `doc-audit-status` 块 | 恰好 1 红 |
| NC3 | 正文 `本地 HEAD = <sha>` 换成另一个 40 位 sha | 恰好 1 红 |
| NC4 | 删掉「接近『当前』的用法必须重新核对」一句 | 恰好 1 红 |
| NC5 | 文档块 `baseline-head` 与正文一起改成 40 个 `0` | 恰好 1 红（最先命中「块内不再声明 `baseline-head`」，早于 git 判据） |
| NC6 | 删掉 `## 4. 不跑测试 / 不构建即无法判定` 标题 | 恰好 1 红 |
| NC7 | 正文 `origin/feature/windows-client` 换成别的分支 | 恰好 1 红 |
| NC8 | `-DocAuditPath` 指不存在文件 | SKIP、退出码 0 |


说明一处**无法在本机构造**的对照：git 的两条判据（对象不是 commit / 不是 HEAD 的祖先）针对的是「换一台机器克隆后基线提交根本不存在」，而块内 `baseline-head` 必须逐字等于 `282dcc…`（防「把快照偷偷改挂到别的祖先提交」），git 读的又是同一行 ⇒ 本机必须先把提交造出来才有对照，因此只覆盖到 NC5 这一层。浅克隆会先走 WARN（提示 `fetch-depth: 0`），与 `sbom-source-revision` 同口径；CI 是完整克隆（`fetch-depth: 0`），两条判据在那里才真正生效。

七条打在 `doc-audit-snapshot-claims` 上、一条走 SKIP 通道 —— 过期审计此后不能被当成当前计数，也不能靠改基线提交甩掉历史。

### 3.20 文档残留与围栏完整性（W52，2026-10-31）

W47 的 `capability-map-claims` 逐行比对了 CAPABILITY-MAP §0 的九行块，却没有看一眼包住它的围栏：把围栏写成「波浪 + 单反引号 + 波浪」之后，状态块在渲染上成了普通正文，而检查一路绿——它钉的是块内容，不是块的存在形态。生成期占位符的残留同理：那是「工具把文档写坏了」，既不是内容错误，也不是任何既有检查的判据。第 46 项 `doc-residue-claims` 因此不看语义，只看形态：

| 判据 | 失败含义 |
| --- | --- |
| 扫描根内的文本文件不得出现四种未标记形态：`{BT}`、`~BT~`、`⟪BT⟫`、波浪-单反引号-波浪（residue-allow） | 某份文档或脚本里还留着生成期占位符——正文渲染出来就是乱码；确要讨论某个记号的行必须带 `residue-allow` 标记（本行就是示例） |
| 每份 markdown 的围栏行数（三反引号或 `~~~`）为偶数 | 围栏没闭合：后面的段落被吞进代码块，或本该是代码块的状态块被渲染成正文 |
| CAPABILITY-MAP §0 的 BEGIN 注释下一行是 三反引号+text、END 注释上一行是 三反引号 | 状态块自己不再被真围栏包住（W52 之前就是这个坏法） |
| 行首为 `|` 的行，剥掉行内代码跨距后不得再含 `||` | 往文档里插行时插在锚点文本之后而不是整行之后，两条表格行粘成一行（W51 在 `LICENSE-SURFACE-CHECK.md` 第 72 行这样写过一次，W51 的 README 那一段还因此整段重复了一遍） |
| 扫描到的文本文件数 ≥ 20 | `-DocsScanRoot` 指错目录时的哨兵：扫到的文件太少就不算证明 |

跳过围栏内部的行、并先剥离行内代码跨距再判 `||`，是因为 `docs/android-source-sync-transfer.md` 的表格里正当地写着 `|| true`（在反引号跨距内），`windows/docs/W8-EVIDENCE.md` 的围栏内也有一个 `||`。一个路径参数 `-DocsScanRoot`（默认仓库根）让负向对照可以指到临时副本；目录不存在 ⇒ SKIP（退出码仍 0）。

刻意**不**做的两件事：不检查文档语义（那是各条 claims 检查的活），也不把 `residue-allow` 当白名单滥用——标记只允许出现在「本行就是要讨论这个记号」的行上，检查不做进一步判断，因为那需要读语义。

本次还顺手修掉两个真实损坏（都是这条检查当场找出来的，不是假设）：`windows/docs/CAPABILITY-MAP.md` 的 8 处围栏/行内代码被写成占位符形态（整份文档一行真围栏都不剩），以及 `LICENSE-SURFACE-CHECK.md` 第 72 行由 W51 造成的表格行粘连（README 第 364 行同时留了一份整段重复的旧副本，已按新副本收敛为一段）。
### 4.20 实测（2026-10-31，W52，本机，HEAD 2bdaa499）

代码：

```text
pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath D:/mh-verify/w38-license/license-report.json
check-licence-surface  PASS    doc-residue-claims  1194 text file(s) scanned: no unmarked placeholder residue, all 89 markdown file(s) have balanced fences, and the capability map status block sits inside a real fence
check-licence-surface  checks=46 failed=0 warnings=0 skipped=0
RESULT: PASS
```

守卫补丁先落地、两份文档还没同步时的首跑如实 1 红（保留为证据）：`check-inventory` 列出 3 个问题（文档与 README 还写 45 项、文档未点名 `doc-residue-claims`），新项自己已 PASS。

第 46 项自己被首跑与负向对照逼出过**三个**缺陷，都已修好并复验：① 围栏判据最初的写法把「三反引号」写成了「波浪 + 单波浪号」，于是整份文档一行真围栏都没数——数出来的是 `~*` 行，0 条也照样「偶数」（NC3 的「围栏奇数」与 NC1 的「状态块不是真围栏」把两种后果都钉住了）；② 记号清单最初把「三反引号」经占位符替换成单个反引号，结果**每一行含反引号的正文都被判成残留**（首跑 8507 条假问题），改成直接写四种记号形态后才正确；③ NC7 不是「SKIP、退出码 0」，而是整份脚本抛 `Resolve-Path` 找不到路径后终止（`-DocsScanRoot` 指不存在目录时，第 290 行对不存在的路径直接调 `Resolve-Path`，在 `$ErrorActionPreference = 'Stop'` 下连 checks= 汇总行都没打印）——改成「先 `Resolve-RepoPath` 得到路径、`Test-Path` 通过才规范化」后才真正走到 SKIP。

八条负向对照（临时副本树 `D:/mh-verify/w52-nc/`，未改仓库文件）：

| # | 改法 | 结果 |
| --- | --- | --- |
| NC1 | CAPABILITY-MAP 副本把 §0 围栏改回波浪形态 | 恰好 1 红（同一项内 4 条问题：2 行占位符 + 状态块的开/闭都不是真围栏） |
| NC2 | 给 W12-DOC-AUDIT.md 副本注入一个未标记的占位符 | 恰好 1 红 |
| NC3 | 删掉 CAPABILITY-MAP 副本 §0 的收尾围栏 | 恰好 1 红（围栏奇数 + 状态块未闭合） |
| NC4 | 给 android 文档副本加一个孤立围栏 | 恰好 1 红（73 行围栏，原文 72） |
| NC5 | `-DocsScanRoot` 指到只放 4 个文件的目录 | 恰好 1 红（哨兵） |
| NC6 | 删掉 android 文档第 4306 行的 `residue-allow` 标记 | 恰好 1 红（点名该行） |
| NC7 | `-DocsScanRoot` 指不存在目录 | SKIP、退出码 0 |
| NC8 | 把许可文档副本里两条表格行粘成一行 | 恰好 1 红（点名第 12 行的接缝） |

日志 `D:/mh-verify/w52-positive.log`（正向）与 `D:/mh-verify/w52-nc/nc1.log` … `nc8.log`；首跑与中间态保留在 `D:/mh-verify/w52-first.log`、`D:/mh-verify/w52-second.log`、`D:/mh-verify/w52-third.log`。

八条打在 `doc-residue-claims` 上、一条走 SKIP 通道 —— 「文档被工具写坏」此后不再只能靠人眼发现；NC8 正是 W51 那次表格行粘连、以及 README 第 364 行整段重复的形态。

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
