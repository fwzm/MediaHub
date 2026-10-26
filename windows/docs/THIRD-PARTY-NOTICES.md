# MediaHub Windows - 第三方组件与许可证清单

> **本文件由脚本生成，请勿手工编辑。** 生成器：`windows/tools/license-report.ps1`。
> 重新生成：`pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Sbom`

| 生成信息 | 值 |
| --- | --- |
| 生成时间（UTC） | 2026-10-24T00:00:00Z |
| 生成命令 | pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir "windows/artifacts/license-report" -Sbom -TimestampUtc "2026-10-24T00:00:00Z" |
| dotnet SDK / PowerShell | 10.0.113 / 7.6.7 |
| 仓库 | D:\deepseek_test\MediaHub_Win |
| 分支 / HEAD | feature/windows-client / 1c30471 |
| git describe | v0.1.0-alpha.1-317-g1c30471-dirty |
| 数据来源 | 13 个 csproj 的 obj/project.assets.json + 本机 NuGet 缓存中的 .nuspec |

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
| System.Security.Cryptography.ProtectedData | 10.0.13 | MIT | expression | MediaHub.App, MediaHub.Net, MediaHub.Providers, MediaHub.Providers.WebDav, MediaHub.ResumeProbe, MediaHub.Tests, MediaHub.WebDav.Tests |

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

### 3.3 LGPL flavour 额外带入的运行时依赖（默认 flavour 不分发它们）

默认 flavour（-p:LibMpvFlavour=gpl）只带 `windows/native/libmpv/libmpv-2.dll`。下表是 **LGPL flavour 额外带入的**依赖，清单来自 `windows/engine/lgpl/dist/runtime-dependencies-collected.txt`（由 windows/tools/collect-lgpl-runtime.ps1 生成），许可转录自 `windows/engine/lgpl/component-licences.txt`。它们不随当前默认产物分发，但切到 LGPL flavour 就随包。

| 文件 | 组件 | 许可 | 证据 |
| --- | --- | --- | --- |
| `avcodec-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (built by windows/engine/lgpl/build-ffmpeg-lgpl.sh; its configure reports "LGPL version 2.1 or later") |
| `swscale-9.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `swresample-6.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `libwinpthread-1.dll` | mingw-w64 runtime | 未记录（component-licences.txt 无此包） | component-licences.txt 无行 - 待核实 |
| `libunibreak-7.dll` | libunibreak | Zlib | component-licences.txt:18 |
| `libstdc++-6.dll` | libstdc++ | GPL-3.0-or-later WITH GCC-exception-3.1 AND GFDL-1.3-or-later | component-licences.txt:26 |
| `libspirv-cross-c-shared.dll` | spirv-cross | Apache-2.0 | component-licences.txt:9 |
| `libshaderc_shared.dll` | shaderc | Apache-2.0 | component-licences.txt:8 |
| `libpng16-16.dll` | libpng | custom (see upstream LICENSE) | component-licences.txt:20 |
| `libplacebo-360.dll` | libplacebo | LGPL2.1 | component-licences.txt:7 |
| `libpcre2-8-0.dll` | pcre2 | 未记录（component-licences.txt 无此包） | component-licences.txt 无行 - 待核实 |
| `liblcms2-2.dll` | lcms2 | MIT AND GPL-3.0-or-later | component-licences.txt:16 |
| `libintl-8.dll` | gettext | 未记录（component-licences.txt 无此包） | component-licences.txt 无行 - 待核实 |
| `libiconv-2.dll` | libiconv | GPL-3.0-or-later | component-licences.txt:23 |
| `libharfbuzz-0.dll` | harfbuzz | MIT | component-licences.txt:13 |
| `vulkan-1.dll` | vulkan-loader | Apache-2.0 | component-licences.txt:10 |
| `libgraphite2.dll` | graphite2 | LGPL-2.1-or-later | component-licences.txt:24 |
| `libgcc_s_seh-1.dll` | libgcc | GPL-3.0-or-later WITH GCC-exception-3.1 AND GFDL-1.3-or-later | component-licences.txt:25 |
| `libfribidi-0.dll` | fribidi | LGPL-2.1-or-later | component-licences.txt:14 |
| `libfreetype-6.dll` | freetype | GPL-2.0-or-later OR FTL | component-licences.txt:12 |
| `libfontconfig-1.dll` | fontconfig | custom (see upstream COPYING) | component-licences.txt:15 |
| `libexpat-1.dll` | expat | 未记录（component-licences.txt 无此包） | component-licences.txt 无行 - 待核实 |
| `libdovi.dll` | libdovi | MIT | component-licences.txt:17 |
| `libbz2-1.dll` | bzip2 | 未记录（component-licences.txt 无此包） | component-licences.txt 无行 - 待核实 |
| `libbrotlidec.dll` | brotli | MIT | component-licences.txt:21 |
| `libbrotlicommon.dll` | brotli | MIT | component-licences.txt:21 |
| `libass-9.dll` | libass | ISC | component-licences.txt:6 |
| `avutil-60.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `avformat-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `avfilter-11.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `avdevice-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:3-4 (same FFmpeg build) |
| `libglib-2.0-0.dll` | glib2 | LGPL-2.1-or-later | component-licences.txt:19 |
| `zlib1.dll` | zlib | Zlib | component-licences.txt:22 |

- 合计 **33** 个 DLL，总字节 58,826,576。其中 libiconv-2.dll（GPL-3.0-or-later）、liblcms2-2.dll（MIT AND GPL-3.0-or-later）、libfreetype-6.dll（GPL-2.0-or-later OR FTL）、libgcc_s_seh-1.dll 与 libstdc++-6.dll（GPL-3.0-or-later WITH GCC-exception-3.1）携带 GPL 家族条款 ⇒ **切到 LGPL flavour 并不等于零 GPL 义务**，与 `windows/docs/licensing/W5-LGPL-BUILD.md:228-235` 记录的残留义务一致。
- 缺口：libwinpthread-1.dll、libpcre2-8-0.dll、libintl-8.dll、libexpat-1.dll、libbz2-1.dll 在 `windows/engine/lgpl/component-licences.txt` 里**没有声明行**，本表只能标「未记录」。发布 LGPL flavour 前必须补齐这些包的许可证据（pacman -Si 的 License 字段）。

## 4. 仓库自身的许可

- 仓库根**没有 LICENSE 文件**：`windows/docs/licensing/LICENCE-DECISION.md:180-190` 记录了这一点（`git ls-files` 无匹配，`git log -- LICENSE` 无输出）。
- `windows/THIRD-PARTY-NOTICES.md:63` 与 LICENCE-DECISION.md:186 引用的 ADR-W001:40 声明「本仓库为 MIT」，但没有任何文件承载该声明。
- 本脚本不能修复这一点；正式发布前必须由人工补上 LICENSE 并与之对齐。

## 5. 本次运行的策略判定

策略：无许可元数据、或出现 GPL-/AGPL-/SSPL-/BUSL-/Commons-Clause 许可 → 违规；仅 `<licenseUrl>` 视为警告，`-RequireExact` 时升级为违规（要求 SPDX 表达式）。

- 违规：**0**。
- 警告：**4**
  - MediaHub.Player.Mpv: project.assets.json is older than the .csproj - the resolved graph may be stale (dotnet restore windows/MediaHub.Windows.sln)
  - Microsoft.Windows.SDK.BuildTools/10.0.26100.4654: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)
  - System.Reflection.Metadata/1.6.0: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)
  - xunit.abstractions/2.0.3: only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)

## 6. 复现方式

```powershell
dotnet restore windows/MediaHub.Windows.sln
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <任意目录> -Sbom
```

脚本退出码：0 干净 / 1 未找到可检查的工程 / 2 缺 `project.assets.json`（先 restore）/ 3 策略违规。

相关文件：`windows/docs/sbom-windows.cdx.json`（CycloneDX 1.5）、`windows/docs/LGPL-COMPLIANCE.md`、`windows/THIRD-PARTY-NOTICES.md`（历史手工清单：引擎 GPL 判定与固定摘要）、`windows/tools/check-licence-surface.ps1`（核对本文件与 SBOM 是否跟得上仓库现状，退出码 0/1）、`windows/docs/licensing/`（W3/W5/W11b 记录）。
