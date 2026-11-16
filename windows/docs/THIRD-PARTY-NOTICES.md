# MediaHub Windows - 第三方组件与许可证清单

> **本文件由脚本生成，请勿手工编辑。** 生成器：`windows/tools/license-report.ps1`。
> 重新生成：`pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Sbom`

| 生成信息 | 值 |
| --- | --- |
| 生成时间（UTC） | 2026-11-14T06:13:08Z |
| 生成命令 | pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir "windows\artifacts\license-report" -Sbom |
| dotnet SDK / PowerShell | 10.0.113 / 7.6.9 |
| 仓库 | D:\deepseek_test\MediaHub_Win |
| 分支 / HEAD | feature/windows-client / 09ea1972 |
| git describe | v0.1.0-alpha.1-481-g09ea1972-dirty |
| 数据来源 | 14 个 csproj 的 obj/project.assets.json + 本机 NuGet 缓存中的 .nuspec |

## 0. 覆盖范围与边界

- 覆盖：Windows 解决方案（`windows/MediaHub.Windows.sln`）**实际解析到的 NuGet 依赖图**，含传递依赖；每个包的许可取自包自身发布的 `.nuspec`（`<license type="expression">` / `<license type="file">` / 已弃用的 `<licenseUrl>`）。
- 不覆盖：①发布产物中的完整文件清单（见 `windows/docs/licensing/SBOM.md`）；②随包分发的**非 NuGet 原生二进制**（见 §3）；③MSIX/安装器自带的运行库；④任何法律意见。
- 因此本文件只满足「可静态验证」的部分，**不构成发布就绪结论**；发布状态（签名、干净机安装等未完成项）见 `windows/docs/licensing/PACKAGING.md`。

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
| libmpv (mpv) | v0.41.0（LGPL 构建） | **LGPL-2.1-or-later** | windows/engine/lgpl/dist/libmpv-2.dll（3,327,274 字节）+ 33 个依赖 DLL，随包分发，安装时强制校验 sha256 | windows/engine/lgpl/dist/dist-hashes.txt |
| FFmpeg (libav*) | 8.1.1 (LGPL build, --disable-gpl --enable-shared) | **LGPL-2.1-or-later**（动态链接） | `avcodec-62` / `avformat-62` / `avfilter-11` 等 7 个 DLL 与 core 同目录 | `windows/engine/lgpl/build-ffmpeg-lgpl.sh`（`--disable-gpl --enable-shared`） |
| libmpv (mpv) — GPL 基线（对比夹具） | v0.41.0-1154-gec38361c5 | **GPL-3.0-or-later** | windows/native/libmpv/libmpv-2.dll（120,989,696 字节），仅 `-p:LibMpvFlavour=gpl` 时使用，**不随包分发** | windows/tools/fetch-libmpv.ps1:33-37 |
| FFmpeg — GPL 基线（对比夹具） | N-127521-g2ec8998c3 | **GPL-3.0-or-later** | 静态链接进上面那份 GPL libmpv | `windows/THIRD-PARTY-NOTICES.md:11` |
| libplacebo / libass / 26 个 UCRT64 依赖 | 随 LGPL 引擎 | LGPL-2.1-or-later / ISC / Apache-2.0 等 | 与 core 同目录，逐组件清单见 §3.3 | `windows/engine/lgpl/component-licences.txt` |
| .NET 10 运行时（自包含发布时） | 10.0 | MIT | 随发布产物 | `windows/THIRD-PARTY-NOTICES.md:7` |

### 3.1 libmpv 的版本来源

- 随包分发的 core：**v0.41.0**（LGPL 构建，FFmpeg 8.1.1 (LGPL build, --disable-gpl --enable-shared) 动态链接），构建脚本 `windows/engine/lgpl/build-mpv-lgpl.sh`（meson `-Dgpl=false -Dlua=disabled`），构建记录 `windows/docs/licensing/W5-LGPL-BUILD.md`。
- 构建工具链为 MSYS2 UCRT64，包版本锁在 `windows/engine/lgpl/msys2-package-hashes.txt`；33 个依赖 DLL 由 `windows/tools/collect-lgpl-runtime.ps1` 枚举。
- 本次运行实测：文件存在=True，字节=3327274，sha256=a3f901d6b5bae54a4b5b87cd9d9d973fd3af079aa70a61851560c30e74722a68，与固定值一致=True。
- GPL 基线（对比夹具，不随包分发）：v0.41.0-1154-gec38361c5（内嵌 FFmpeg N-127521-g2ec8998c3），来自 shinchiro/mpv-winbuild-cmake 的 release `20261014`，由 `windows/tools/fetch-libmpv.ps1` 下载并校验；详见 `windows/THIRD-PARTY-NOTICES.md:16-63`。

### 3.2 许可结论

- **随包分发的是 LGPL 构建**：`windows/engine/lgpl/dist/libmpv-2.dll`（3,327,274 B，sha256 a3f901d6b5bae54a4b5b87cd9d9d973fd3af079aa70a61851560c30e74722a68，`v0.41.0`，enabled-features 不含 `gpl`），FFmpeg 动态链接，33 个依赖 DLL 与 core 同目录。判定与证据见 windows/docs/licensing/LICENCE-DECISION.md §9 - LGPL-2.1-or-later build, shipped since 2026-11-12；构建记录见 `windows/docs/licensing/W5-LGPL-BUILD.md:18-22`。
- 旧的 GPL 构建现仅作对比夹具（`-p:LibMpvFlavour=gpl`），不随包分发：windows/native/libmpv/libmpv-2.dll - 120,989,696 B, sha256 6a48faec...89de563d9, GPL-3.0-or-later - comparison fixture only (-p:LibMpvFlavour=gpl), NOT shipped。
- LGPL 引擎需履行的义务、可替换 DLL 的要求与分发前 checklist：windows/docs/LGPL-COMPLIANCE.md。

### 3.3 LGPL 引擎运行时依赖（自 2026-11-12 起随默认产物分发）

默认产物带 `windows/engine/lgpl/dist/libmpv-2.dll` + `mpv.exe` + 33 个依赖 DLL。下表是这批依赖，清单来自 `windows/engine/lgpl/dist/runtime-dependencies-collected.txt`（由 windows/tools/collect-lgpl-runtime.ps1 生成），许可转录自 `windows/engine/lgpl/component-licences.txt`。

| 文件 | 组件 | 许可 | 证据 |
| --- | --- | --- | --- |
| `avcodec-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (built by windows/engine/lgpl/build-ffmpeg-lgpl.sh; its configure reports "LGPL version 2.1 or later") |
| `swscale-9.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `swresample-6.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `libwinpthread-1.dll` | libwinpthread-git | MIT AND BSD-3-Clause-Clear | component-licences.txt:29 |
| `libunibreak-7.dll` | libunibreak | Zlib | component-licences.txt:20 |
| `libstdc++-6.dll` | libstdc++ | GPL-3.0-or-later WITH GCC-exception-3.1 AND GFDL-1.3-or-later | component-licences.txt:28 |
| `libspirv-cross-c-shared.dll` | spirv-cross | Apache-2.0 | component-licences.txt:11 |
| `libshaderc_shared.dll` | shaderc | Apache-2.0 | component-licences.txt:10 |
| `libpng16-16.dll` | libpng | custom | component-licences.txt:22 (packager declares "custom"; the upstream LICENSE text governs) |
| `libplacebo-360.dll` | libplacebo | LGPL2.1 | component-licences.txt:9 |
| `libpcre2-8-0.dll` | pcre2 | BSD-3-Clause | component-licences.txt:40 |
| `liblcms2-2.dll` | lcms2 | MIT AND GPL-3.0-or-later | component-licences.txt:18 |
| `libintl-8.dll` | gettext-runtime | GPL-3.0-or-later AND LGPL-2.1-or-later | component-licences.txt:39 |
| `libiconv-2.dll` | libiconv | LGPL-2.1-or-later  documentation:GPL-3.0-or-later | component-licences.txt:25 |
| `libharfbuzz-0.dll` | harfbuzz | MIT | component-licences.txt:15 |
| `vulkan-1.dll` | vulkan-loader | Apache-2.0 | component-licences.txt:12 |
| `libgraphite2.dll` | graphite2 | LGPL-2.1-or-later | component-licences.txt:26 |
| `libgcc_s_seh-1.dll` | libgcc | GPL-3.0-or-later WITH GCC-exception-3.1 AND GFDL-1.3-or-later | component-licences.txt:27 |
| `libfribidi-0.dll` | fribidi | LGPL-2.1-or-later | component-licences.txt:16 |
| `libfreetype-6.dll` | freetype | GPL-2.0-or-later OR FTL | component-licences.txt:14 |
| `libfontconfig-1.dll` | fontconfig | custom | component-licences.txt:17 (packager declares "custom"; the upstream COPYING text governs) |
| `libexpat-1.dll` | expat | MIT | component-licences.txt:38 |
| `libdovi.dll` | libdovi | MIT | component-licences.txt:19 |
| `libbz2-1.dll` | bzip2 | custom | component-licences.txt:37 (packager declares "custom"; the upstream LICENSE text governs) |
| `libbrotlidec.dll` | brotli | MIT | component-licences.txt:23 |
| `libbrotlicommon.dll` | brotli | MIT | component-licences.txt:23 |
| `libass-9.dll` | libass | ISC | component-licences.txt:8 |
| `avutil-60.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `avformat-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `avfilter-11.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `avdevice-62.dll` | FFmpeg (libav*) | LGPL-2.1-or-later | component-licences.txt:5-6 (same FFmpeg build) |
| `libglib-2.0-0.dll` | glib2 | LGPL-2.1-or-later | component-licences.txt:21 |
| `zlib1.dll` | zlib | Zlib | component-licences.txt:24 |

- 合计 **33** 个 DLL，总字节 58,826,576。其中 libiconv-2.dll（GPL-3.0-or-later）、liblcms2-2.dll（MIT AND GPL-3.0-or-later）、libfreetype-6.dll（GPL-2.0-or-later OR FTL）、libgcc_s_seh-1.dll 与 libstdc++-6.dll（GPL-3.0-or-later WITH GCC-exception-3.1）携带 GPL 家族条款 ⇒ **LGPL 引擎并不等于零 GPL 义务**，与 `windows/docs/licensing/W5-LGPL-BUILD.md:228-235` 记录的残留义务一致。
- 本表 33 行都有 `windows/engine/lgpl/component-licences.txt` 的声明行可引；没有任何一行靠猜测填写。

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

相关文件：`windows/docs/sbom-windows.cdx.json`（CycloneDX 1.5）、`windows/docs/LGPL-COMPLIANCE.md`、`windows/docs/licensing/LICENCE-DECISION.md` §9（LGPL 引擎判定与证据）、`windows/tools/check-licence-surface.ps1`（核对本文件与 SBOM 是否跟得上仓库现状，退出码 0/1）、`windows/docs/licensing/`（W3/W5/W11b 记录）。
