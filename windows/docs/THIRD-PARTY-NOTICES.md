# MediaHub Windows - 第三方组件与许可证清单

> **本文件由脚本生成，请勿手工编辑。** 生成器：`windows/tools/license-report.ps1`。
> 重新生成：`pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Sbom`

| 生成信息 | 值 |
| --- | --- |
| 生成时间（UTC） | 2026-10-23T18:57:12Z |
| 生成命令 | pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir "D:\mh-verify\license" |
| dotnet SDK / PowerShell | 10.0.113 / 7.6.7 |
| 仓库 | D:\deepseek_test\MediaHub_Win |
| 分支 / HEAD | feature/windows-client / afb0ef2 |
| git describe | v0.1.0-alpha.1-278-gafb0ef2 |
| 数据来源 | 11 个 csproj 的 obj/project.assets.json + 本机 NuGet 缓存中的 .nuspec |

## 0. 覆盖范围与边界

- 覆盖：Windows 解决方案（`windows/MediaHub.Windows.sln`）**实际解析到的 NuGet 依赖图**，含传递依赖；每个包的许可取自包自身发布的 `.nuspec`（`<license type="expression">` / `<license type="file">` / 已弃用的 `<licenseUrl>`）。
- 不覆盖：①发布产物中的完整文件清单（见 `windows/docs/licensing/SBOM.md`）；②随包分发的**非 NuGet 原生二进制**（见 §3）；③MSIX/安装器自带的运行库；④任何法律意见。
- 因此本文件只满足「可静态验证」的部分，**不构成发布就绪结论**，也不解除 §3 的 GPL 阻塞。

## 1. 运行时 / 随包分发的 NuGet 依赖

| 包 | 版本 | 许可 | 许可来源 | 使用的项目 |
| --- | --- | --- | --- | --- |
| Microsoft.Web.WebView2 | 1.0.3179.45 | ~BSD-3-Clause (from LICENSE.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK | 1.8.260921001 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.AI | 1.8.79 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.Base | 1.8.251216001 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.DWrite | 1.8.25122902 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.Foundation | 1.8.260803002 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.InteractiveExperiences | 1.8.260708001 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.ML | 1.8.2223 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.Runtime | 1.8.260921001 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.Widgets | 1.8.251231004 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Microsoft.WindowsAppSDK.WinUI | 1.8.260803003 | ~Microsoft (proprietary eula, see file) (from license.txt) | file | MediaHub.App |
| Newtonsoft.Json | 13.0.1 | MIT | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| System.Numerics.Tensors | 9.0.0 | MIT | expression | MediaHub.App |
| System.Reflection.Metadata | 1.6.0 | https://github.com/dotnet/corefx/blob/master/LICENSE.TXT | licenseUrl | MediaHub.Tests, MediaHub.WebDav.Tests |
| System.Security.Cryptography.ProtectedData | 10.0.13 | MIT | expression | MediaHub.App, MediaHub.Net, MediaHub.Providers, MediaHub.Providers.WebDav, MediaHub.Tests, MediaHub.WebDav.Tests |

## 2. 仅构建期 / 测试期使用的 NuGet 依赖

这些包不进入应用安装包，但仍在仓库的构建与测试链里使用，因此一并记录。

| 包 | 版本 | 许可 | 许可来源 | 使用的项目 |
| --- | --- | --- | --- | --- |
| Microsoft.CodeCoverage | 17.13.0 | MIT | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| Microsoft.NET.Test.Sdk | 17.13.0 | MIT | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| Microsoft.TestPlatform.ObjectModel | 17.13.0 | MIT | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| Microsoft.TestPlatform.TestHost | 17.13.0 | MIT | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| Microsoft.Windows.SDK.BuildTools | 10.0.26100.4654 | https://aka.ms/WinSDKLicenseURL | licenseUrl | MediaHub.App |
| Microsoft.Windows.SDK.BuildTools.MSIX | 1.7.20250829.1 | ~Microsoft (proprietary eula, see file) (from sdk_license.txt) | file | MediaHub.App |
| xunit | 2.9.3 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.abstractions | 2.0.3 | https://raw.githubusercontent.com/xunit/xunit/master/license.txt | licenseUrl | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.analyzers | 1.18.0 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.assert | 2.9.3 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.core | 2.9.3 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.extensibility.core | 2.9.3 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.extensibility.execution | 2.9.3 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |
| xunit.runner.visualstudio | 3.0.2 | Apache-2.0 | expression | MediaHub.Tests, MediaHub.WebDav.Tests |

## 3. 非 NuGet 二进制（随包分发）

这些组件不由 NuGet 解析，上面的表格**不覆盖**它们。

| 组件 | 版本 | 许可 | 随包方式 | 事实来源 |
| --- | --- | --- | --- | --- |
| libmpv (mpv) | v0.41.0-1154-gec38361c5 | **GPL-3.0-or-later** | windows/native/libmpv/libmpv-2.dll（120,989,696 字节，安装时强制校验 sha256） | windows/tools/fetch-libmpv.ps1:33-37 |
| FFmpeg (libav*) | N-127521-g2ec8998c3 | **GPL-3.0-or-later**（随该 libmpv 构建） | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:11` |
| rubberband / libdvdnav / libdvdread | 随 libmpv 构建 | **GPL-2.0** | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:12` |
| libplacebo / libass / libcurl 等 | 随 libmpv 构建 | LGPL-2.1-or-later / ISC 等 | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:13`；逐组件清单见 `windows/engine/lgpl/component-licences.txt` |
| .NET 10 运行时（自包含发布时） | 10.0 | MIT | 随发布产物 | `windows/THIRD-PARTY-NOTICES.md:7` |

### 3.1 libmpv 的版本来源

- 具体版本：**v0.41.0-1154-gec38361c5**（内嵌 FFmpeg N-127521-g2ec8998c3），来自 shinchiro/mpv-winbuild-cmake 的 release `20261014`，资产 `mpv-dev-x86_64-20261014-git-ec38361c56.7z`。
- 获取方式：`windows/tools/fetch-libmpv.ps1` 下载并按两个固定 SHA-256（归档 + 解包后的 DLL）校验后才安装；DLL 不入库（`.gitignore:35-36`）。
- 本次运行实测：文件存在=True，字节=120989696，sha256=6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9，与固定值一致=True。

### 3.2 许可结论（与既有记录一致，未改变）

- 当前 `windows/native/libmpv/libmpv-2.dll` 是 **GPL 构建**（`gpl` 出现在引擎自报的 enabled-features 里），按 GPL-3.0-or-later 分发；以当前产物对外发布即触发 GPL 义务。详见 `windows/THIRD-PARTY-NOTICES.md:16-63`。
- LGPL 候选构建已存在但**尚未切换**：windows/engine/lgpl/dist/libmpv-2.dll - 3,327,274 B, sha256 20035902597d...ee4a3c0a - built and verified, NOT shipped (windows/docs/licensing/W5-LGPL-BUILD.md:18-22)。
- 切换到 LGPL 构建后必须履行的义务、可替换 DLL 的要求与分发前 checklist：`windows/docs/LGPL-COMPLIANCE.md`。

## 4. 仓库自身的许可

- 仓库根**没有 LICENSE 文件**：`windows/docs/licensing/LICENCE-DECISION.md:180-190` 记录了这一点（`git ls-files` 无匹配，`git log -- LICENSE` 无输出）。
- `windows/THIRD-PARTY-NOTICES.md:63` 与 LICENCE-DECISION.md:186 引用的 ADR-W001:40 声明「本仓库为 MIT」，但没有任何文件承载该声明。
- 本脚本不能修复这一点；正式发布前必须由人工补上 LICENSE 并与之对齐。

## 5. 本次运行的策略判定

策略：无许可元数据、或出现 GPL-/AGPL-/SSPL-/BUSL-/Commons-Clause 许可 → 违规；仅 `<licenseUrl>` 视为警告，`-RequireExact` 时升级为违规（要求 SPDX 表达式）。

- 违规：**0**。
- 警告：**3**
  - Microsoft.Windows.SDK.BuildTools/10.0.26100.4654: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)
  - System.Reflection.Metadata/1.6.0: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)
  - xunit.abstractions/2.0.3: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)

## 6. 复现方式

```powershell
dotnet restore windows/MediaHub.Windows.sln
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <任意目录> -Sbom
```

脚本退出码：0 干净 / 1 未找到可检查的工程 / 2 缺 `project.assets.json`（先 restore）/ 3 策略违规。

相关文件：`windows/docs/sbom-windows.cdx.json`（CycloneDX 1.5）、`windows/docs/LGPL-COMPLIANCE.md`、`windows/THIRD-PARTY-NOTICES.md`（历史手工清单：引擎 GPL 判定与固定摘要）、`windows/docs/licensing/`（W3/W5 记录）。
