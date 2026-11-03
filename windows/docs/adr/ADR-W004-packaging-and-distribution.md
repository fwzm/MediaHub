# ADR-W004 — 构建与发行形式

- 状态：**已接受**（2026-10-14）
- 相关：ADR-W001
## 0. 机器可读状态（CI 核对）

本段是 `check-licence-surface.ps1` 的 `adr-packaging-claims`（W45，第 39 项）逐字核对的真源：下面四个包属性必须与 `windows/src/MediaHub.App/MediaHub.App.csproj` 当场取到的值逐字相同，且本文不得再出现「包内零许可文件」之类的陈旧说法；任何一次「ADR 记的形态 ≠ 仓库现状」都会让 CI 变红。

<!-- BEGIN adr-packaging-status (checked by check-licence-surface.ps1) -->
```text
packaging-form: unpackaged-self-contained-portable
msix: optional-not-implemented
signing: blocked-no-certificate
licence-files-in-package: staged-by-ci-since-w40
native-libmpv: gitignored-and-fetched-by-tool
```
<!-- END adr-packaging-status -->


## 问题

Windows 上有两种主流发行形式（MSIX 与传统 unpackaged），且客户机上是否已装
Windows App Runtime 完全不可控。必须在不要求用户预装运行时的前提下分发。

## 实测依据

| 观察 | 结果 |
| --- | --- |
| 默认（framework-dependent + framework-dependent Windows App SDK）运行 | **失败**：`Required components of the Windows App Runtime are missing. Version 1.8 (MSIX package version >= 8000.994.2142.0)` |
| 设置 `WindowsAppSDKSelfContained=true` + `SelfContained=true` 后 | **成功**：exe 从目录直接启动并渲染窗口 |

本机 Windows App Runtime 未安装，说明"要求用户先装运行时"的方案在真实环境里极易失败。

## 决策

**默认发行形式：unpackaged + 自包含（portable）。** 同时保留 MSIX 作为后续可选项。

```xml
<WindowsPackageType>None</WindowsPackageType>        <!-- unpackaged -->
<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>
<SelfContained>true</SelfContained>                   <!-- 含 .NET 运行时 -->
<RuntimeIdentifier>win-x64</RuntimeIdentifier>
```

### 为什么先做 portable

- **零前置依赖**：不要求 Windows App Runtime，也不要求特定 .NET 版本。
- **可验证**：解压即运行，测试与用户看到的是同一个产物。
- **便于 CI 归档**：`dotnet publish` 输出即交付物，无需签名证书。

### MSIX 何时更合适（后续）

- 需要自动更新、干净卸载、文件类型关联的系统级注册、企业分发时。
- 代价：需要代码签名证书；用户侧需要信任证书；自包含 MSIX 体积同样约 200 MB。
- **文件类型关联**在 unpackaged 模式下并非不可行（写 `HKCU\Software\Classes`），
  但需要显式安装步骤，因此排在 MSIX 之前单独实现（见 ROADMAP-WINDOWS W3）。

## 产物体积

| 组成 | 约 |
| --- | --- |
| .NET 10 自包含运行时 | ~70 MB |
| Windows App SDK 自包含 | ~40 MB |
| `libmpv-2.dll` | ~115 MB |
| 应用自身 | < 1 MB |

GPL 基线的 `libmpv-2.dll` **不入库**（`.gitignore` 的 `windows/native/libmpv/` 规则已排除），由 `windows/tools/fetch-libmpv.ps1` 拉取并按上表两个摘要强制校验；LGPL 候选引擎（`windows/engine/lgpl/dist/libmpv-2.dll` 与 `mpv.exe`）**是入库件**，见 [../licensing/LGPL-ENGINE-SWITCH.md](../licensing/LGPL-ENGINE-SWITCH.md)。

脚本现在固定**两个**摘要，任一不符即拒绝安装：

| 对象 | SHA-256 |
| --- | --- |
| `mpv-dev-x86_64-20261014-git-ec38361c56.7z`（31,562,418 字节） | `325555b4e0b1d87fb7c92c96010776581599c64e4988052cb9d05e130b52f939` |
| 解包后的 `libmpv-2.dll`（120,989,696 字节） | `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` |

**两个都固定**的理由：GitHub release 资产可以在原地被替换，URL 本身不构成身份。
固定归档摘要可发现被替换的归档；再固定解包后 DLL 的摘要，还能发现解包环节产出的非受审二进制。

## 未完成项（如实登记）

- 供应链摘要已固定并强制校验（见上），且已验证：`fetch-libmpv.ps1` 对已安装副本返回
  "already present and verified"。
- 许可证：**本构建为 GPL-3.0-or-later**，发行阻断未解除；见
  [../licensing/LICENCE-DECISION.md](../licensing/LICENCE-DECISION.md)。
- 发布产物中的许可证/声明文件：**W40 起随包**——`windows/tools/stage-licence-files.ps1` 由 CI 在 publish 后把六份文档（`THIRD-PARTY-NOTICES.md`、`THIRD-PARTY-NOTICES-FULL.md`、`LGPL-COMPLIANCE.md`、`LGPL-ENGINE-SWITCH.md`、`component-licences.txt`、`sbom.cdx.json`）暂存进 `licences/` 并生成 `MANIFEST.txt`，烟测断言这 7 个路径；仍未随包的是 LGPL/GPL 许可正文本身，属许可证决策。
- `MediaHub.exe` 与 `libmpv-2.dll` 的 `Get-AuthenticodeSignature` 均为 `NotSigned`：
  **SIGNING_BLOCKED**。
- 未生成 MSIX 包；未做代码签名；未做安装/卸载实测。
- 未在干净机器上验证 portable 包（本机已具备构建期依赖）。
