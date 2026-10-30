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

33 项检查分十三组：

| 组 | 检查 | 失败含义 |
| --- | --- | --- |
| 项目面 | `projects-discovered` | `windows/src` 或 `windows/tests` 下找不到 csproj（多半 `-RepoRoot` 传错） |
| 生成的 notices | `notices-exist`、`notices-libmpv-engine`、`notices-engine-licence`、`notices-lgpl-dependency-section`、`notices-lgpl-evidence`、`notices-reproduce-command`、`notices-points-here` | notices 被手改坏、或生成器改版后丢了关键段落 |
| LGPL 说明 | `lgpl-compliance-exists` 及三条标记（`P/Invoke`、`libmpv-2.dll`、`written offer`） | 动态链接事实、可替换性、源码提供方式被删掉 |
| SBOM | `sbom-shape`（CycloneDX 1.5）、`sbom-engine-component`（libmpv + GPL-3.0-or-later）、`sbom-lgpl-flavour-property` | SBOM 不是合法文档或丢了引擎记录 ⇒ 随包引擎变成「无记录」 |
| 报告一致性 | `report-exists`、`report-no-violations`、`report-project-count`、`report-engine-pin`、`report-lgpl-dependency-count`、`lgpl-licence-gaps` | 报告过期（csproj 数变了）、出现策略违规、引擎哈希与固定值不符、LGPL 依赖数变了 |
| SBOM 库存（W32 起） | `sbom-inventory`、`sbom-inventory-licences`、`sbom-source-revision` | 入库的 SBOM 与「当场重新解析依赖图得到的报告」不再一一对应（包升级/新增/移除、许可表达式变了），或 SBOM 记录的本机来源提交不落在当前分支历史上 |
| 工具链（W33 + W34 起） | `sdk-pin-matches-report` | 报告不是由仓库根 `global.json` 钉住的 SDK 生成的——SBOM 与全部实测证据必须出自同一条工具链；另见 §3.2（关修剪使还原图在任何机器上一致） |
| 通告文档（W35 起） | `notices-inventory`、`notices-declarations` | 入库的 `THIRD-PARTY-NOTICES.md` 与「当场解析出的依赖图」或「csproj 里直接声明的包」不再对得上（漏包、版本/许可漂移、新增依赖没写进法律文档） |
| 引擎候选身份（W36 起） | `lgpl-candidate-hashes`、`lgpl-candidate-claims` | 入库的 LGPL 候选 DLL 与 `windows/engine/lgpl/dist/dist-hashes.txt` 的记录不符；或十一个引用候选身份的文档/常量写着别的构建（字节数或 sha256 对不上） |
| 引擎能力（W37 起） | `vulkan-claims` | 候选件忽然带上 Vulkan、`MpvPlayer` 不再把 `gpu-api` 钉成 `d3d11`、`windows/src` 下出现 vulkan 字样，或 `VULKAN-NECESSITY.md` 丢了 verdict 行——许可证决策依赖的「Vulkan 非必需」结论不能悄悄过期 |
| 引擎许可声明（W38 起） | `lgpl-licence-table` | LGPL flavour 的 33 个 DLL 与 `component-licences.txt` 的逐行声明对不上（表被改坏、缺行、证据行号指到别处），或装出来的文件清单与报告行数不符 |
| 分发与签名（W39 起） | `packaging-claims` | 发布形态、签名、安装方式与 `PACKAGING.md` §0 七行不符：CI 开始签名、仓库出现 `*.appxmanifest` 或 WiX/Inno/NSIS 工程、App 不再是 unpackaged self-contained portable、`windows-ci.yml` 丢了 portable publish/上传步骤（构建输出 `obj/`、`bin/`、`artifacts/` 不计） |
| 包内许可文件（W40 起） | `package-licence-staging` | 发布树不再真的带 `licences/` 的六个文档 + digest manifest：暂存脚本被删、清单缩水、manifest 摘要与字节不符、或 CI 不再调用它（该检查把脚本重跑到临时树，不读文档） |

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
