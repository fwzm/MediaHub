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

21 项检查分五组：

| 组 | 检查 | 失败含义 |
| --- | --- | --- |
| 项目面 | `projects-discovered` | `windows/src` 或 `windows/tests` 下找不到 csproj（多半 `-RepoRoot` 传错） |
| 生成的 notices | `notices-exist`、`notices-libmpv-engine`、`notices-engine-licence`、`notices-lgpl-dependency-section`、`notices-lgpl-evidence`、`notices-reproduce-command`、`notices-points-here` | notices 被手改坏、或生成器改版后丢了关键段落 |
| LGPL 说明 | `lgpl-compliance-exists` 及三条标记（`P/Invoke`、`libmpv-2.dll`、`written offer`） | 动态链接事实、可替换性、源码提供方式被删掉 |
| SBOM | `sbom-shape`（CycloneDX 1.5）、`sbom-engine-component`（libmpv + GPL-3.0-or-later）、`sbom-lgpl-flavour-property` | SBOM 不是合法文档或丢了引擎记录 ⇒ 随包引擎变成「无记录」 |
| 报告一致性 | `report-exists`、`report-no-violations`、`report-project-count`、`report-engine-pin`、`report-lgpl-dependency-count`、`lgpl-licence-gaps` | 报告过期（csproj 数变了）、出现策略违规、引擎哈希与固定值不符、LGPL 依赖数变了 |

`lgpl-licence-gaps` 默认是 **WARN**：`component-licences.txt` 里没有声明的 5 个 MSYS2 包（`libwinpthread-1`、`libpcre2-8-0`、`libintl-8`、`libexpat-1`、`libbz2-1`）是**已知缺口**，默认 flavour 不随包分发它们。要在「切 LGPL 发布前」把缺口当硬门，加 `-FailOnLicenceGaps`。

报告不存在时，相关检查输出 `SKIP` 而不是失败——没跑过生成器的机器也能用核对器查文档面。

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

