# ADR-W004 — 构建与发行形式

- 状态：**已接受**（2026-10-14）
- 相关：ADR-W001
## 0. 机器可读状态（CI 核对）

本段是 `check-licence-surface.ps1` 的 `adr-packaging-claims`（W45，第 39 项）逐字核对的真源：下面四个包属性必须与 `windows/src/MediaHub.App/MediaHub.App.csproj` 当场取到的值逐字相同，且本文不得再出现「包内零许可文件」之类的陈旧说法；任何一次「ADR 记的形态 ≠ 仓库现状」都会让 CI 变红。

<!-- BEGIN adr-packaging-status (checked by check-licence-surface.ps1) -->
```text
packaging-form: unpackaged-self-contained-portable
msix: optional-not-implemented
installer: per-user-powershell-implemented
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

**默认发行形式：unpackaged + 自包含（portable）**，并在同一棵树上提供每用户 PowerShell 安装器
（2026-11-12）。MSIX 仍保留为后续可选项。

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

### 安装器（2026-11-12 落地，仍未解决 GPL 阻断）

在上述 portable 形态之上增加一个**每用户 PowerShell 安装器**，而不是 MSIX：

- `windows/tools/make-install-kit.ps1` 用 `dotnet publish` + `stage-licence-files.ps1` 产出
  `MediaHub-Setup-win-x64-<ver>/`（`app/` + `install.ps1` + `uninstall.ps1` + `install.cmd` +
  `update.ps1`）并压成 zip，同时写出可作为更新源的 `update.json`；
- `install.ps1` 把 `app/` 覆盖复制到 `%LOCALAPPDATA%\Programs\MediaHub`，建开始菜单快捷方式，
  写 `HKCU\...\Uninstall\MediaHub`（设置→应用可见），复制 `uninstall.ps1`；无需管理员、无需预装运行时；
- `uninstall.ps1` 删程序文件、快捷方式、卸载项与文件关联键，默认**保留** `%LOCALAPPDATA%\MediaHub`
  （`-RemoveUserData` 才删）。

不选 MSIX 的理由与代价：MSIX 必须签名才能安装，而本仓库没有证书；MSIX 的数据虚拟化会改变
settings/DPAPI vault/history 的位置并在卸载时一并删除，需要单独设计与测试；而应用已经自己写 HKCU
关联，MSIX 的“免费关联注册”优势暂时用不上。代价是：没有包身份、未签名下载会触发
SmartScreen 警告（成本与证书选项见 [../licensing/PACKAGING.md](../licensing/PACKAGING.md) §2.2）。

### 更新链路（2026-11-14 落地）

在未打包形态上补了自更新，而不是改 MSIX：

- 设置页可配置更新源（本地目录或 HTTP 静态目录），清单为 `update.json`；
- `MediaHub.Update` 负责清单解析、版本比较、下载与 SHA-256 校验，`update.ps1` 负责在应用退出后
  换文件、备份、回滚和修复快捷方式；
- 旧目录改名为备份后才换入新目录；换入后任何失败都恢复备份，旧目录未被移动前的失败不改动安装；
- 快捷方式修复覆盖开始菜单、桌面（仅原来存在时）和任务栏已固定项；
- 更新代理内嵌在应用中，因此没有更新功能的旧版本也能被新版本替换。

仍然**未签名**：应用内校验只能保证包完整，不能消除 SmartScreen；不伪造签名结论。实际演练范围
见 [../../README.md](../../README.md) 的“安装与更新”一节，HTTP 完整界面演练尚未执行。

已实测（2026-11-12）：安装 → 快速启动连真实服务器并 DirectPlay 播放 → 通过 `UninstallString` 卸载 →
再次安装；快捷方式 target/workdir/icon 正确，`MediaHub.exe` 带四尺寸图标。**未实测**：干净机器
（无 .NET / 无 Windows App Runtime）。平台仍为 **x64 only**（`RuntimeIdentifier=win-x64`，
引擎只有 `x86_64-w64-mingw32` 构建），`TargetPlatformMinVersion=10.0.17763.0`。

### MSIX 何时更合适（后续）

- 需要自动更新、干净卸载、文件类型关联的系统级注册、企业分发时。
- 代价：需要代码签名证书；用户侧需要信任证书；自包含 MSIX 体积同样约 200 MB。
- **文件类型关联**在 unpackaged 模式下已由应用自己写 `HKCU\Software\Classes`（`FileAssociationService`），
  但仍以“候选项”形式出现，默认处理程序由用户在系统设置里选择。

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
- 未生成 MSIX 包；未做代码签名。安装 → 首启连服务器播放 → 卸载 → 重装已实测（2026-11-12），
  详见 [../licensing/PACKAGING.md](../licensing/PACKAGING.md) §2.3。
- 未在干净机器上验证安装包（本机已具备构建期依赖）；仅 x64，arm64 无引擎、不做承诺。
