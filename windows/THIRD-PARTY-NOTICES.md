# 第三方组件与许可证

本客户端分发时包含以下第三方组件。发布产物必须随附本文件。

| 组件 | 版本 | 许可证 | 用途 | 获取方式 |
| --- | --- | --- | --- | --- |
| .NET Runtime | 10.0 | MIT | 运行时（自包含） | 随发布产物 |
| Windows App SDK (WinUI 3) | 1.8.260921001 | Microsoft Software License / MIT | UI 框架 | NuGet |
| System.Security.Cryptography.ProtectedData | 10.0.13 | MIT | DPAPI 凭据加密 | NuGet |
| **libmpv (mpv)** | **v0.41.0-1154-gec38361c5** | **GPL-3.0-or-later（见下）** | 播放引擎 | `tools/fetch-libmpv.ps1` |
| FFmpeg (libav*) | N-127521-g2ec8998c3 | **GPL-3.0-or-later**（随本构建） | 解复用与解码 | 随 libmpv |
| rubberband / libdvdnav / libdvdread | 随 mpv 构建 | **GPL-2.0** | 变速、DVD 导航 | 随 libmpv（静态链接） |
| libplacebo / libass / libcurl 等 | 随 mpv 构建 | LGPL-2.1-or-later / ISC 等 | 渲染、字幕、网络 | 随 libmpv |
| xunit | 2.9.3 | Apache-2.0 | 测试 | NuGet（仅测试） |

## libmpv 的许可证判定（依据实际二进制，非推测）

**结论：本构建为 GPL 构建，具体为 GPL-3.0-or-later。以当前产物对外分发将触发 GPL 义务。**

版本是 **3.0-or-later 而不是 2.0-or-later**：构建方（shinchiro/mpv-winbuild-cmake 的
`packages/ffmpeg.cmake`）为 FFmpeg 传入 `--enable-gpl` **且** `--enable-version3`，
而 FFmpeg 自己的 configure 帮助文本写明 `--enable-version3  upgrade (L)GPL to version 3`。
详细的判定依据见 [docs/licensing/LICENCE-DECISION.md](docs/licensing/LICENCE-DECISION.md)。

判定依据（对 `windows/native/libmpv/libmpv-2.dll` 的实测）：

| 检查 | 结果 |
| --- | --- |
| 引擎自报版本 | `mpv v0.41.0-1154-gec38361c5`（ffmpeg `N-127521-g2ec8998c3`） |
| 引擎自报特性清单 | `List of enabled features: ... glob-win32 **gpl** iconv javascript ...` |
| `x264` / `x265` 相关符号 | 存在（`libx265`、`x265`、`x264` 字符串） |
| LGPL 条款文本 | 存在（mpv 自身的 LGPL 声明） |
| `--enable-gpl` 字面量 | 未在二进制中以可检索形式出现（不作为反证） |

**`gpl` 出现在 mpv 自己的 enabled-features 清单中，这是权威信号：该构建启用了 GPL 组件。**
此外该 DLL 内嵌了自己的 meson 命令行且**不含** `-Dgpl`，而 mpv 的 meson 默认值为 `true`，
两者互相印证。注意 mpv 官方 Copyright 明确说明 `-Dgpl=false` **本身并不构成 LGPL 授权**，
且"链接的库仍会影响最终许可证（例如 FFmpeg 以 GPL 构建时）"。

社区构建（shinchiro/mpv-winbuild-cmake）默认开启 GPL-only 组件（x264/x265 等）。
mpv 自身的代码是 LGPL-2.1+，但**与 GPL 组件链接后的整体按 GPL 分发**。

### 对分发方式的具体影响

若以当前 `libmpv-2.dll` 分发 MediaHub Windows 便携包，分发方必须：

1. 以 GPL-2.0-or-later 分发包含该二进制的**整体作品**（不能仅声明 MIT）；
2. 提供**对应的完整源码**（含 mpv、FFmpeg 及所用第三方库，以及生成该二进制的构建脚本），
   或提供有效期不少于三年的书面要约；
3. 不得附加与 GPL 冲突的额外限制。

### 可选的合规路径

- **路径 A（推荐用于正式发行）**：自行构建 **LGPL 配置**的 libmpv（禁用 GPL-only 组件，
  即不使用 x264/x265 等），从而可以宽松许可分发本客户端。
- **路径 B**：接受 GPL，随产物提供 Corresponding Source 与构建脚本，并调整本项目的许可声明。
- **路径 C**：不分发二进制，仅提供构建脚本由用户自行构建（法律风险最低，用户体验最差）。

### 当前状态

**RELEASE BLOCKER —— 未解决。** 在上述任一路径落实之前，
**不得将 Windows 便携包作为正式发行版对外分发**。
本仓库自身为 MIT，该许可**不覆盖** `libmpv-2.dll`。

## LGPL flavour 额外带入的运行时依赖（默认 flavour 不分发）

默认构建（`-p:LibMpvFlavour=gpl`）只带一个引擎 DLL：`windows/native/libmpv/libmpv-2.dll`
（120,989,696 字节，GPL-3.0-or-later，判定见上一节）。以 `-p:LibMpvFlavour=lgpl` 构建时，引擎换成

`windows/engine/lgpl/dist/libmpv-2.dll`（**3,327,274 字节**，SHA-256
`20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a`），并**额外带入 33 个运行时 DLL**，
合计 58,826,576 字节。实测树的差别只有这些：`dotnet publish` 文件数 526 → 559，字节数
348,275,148 → 289,439,302（引擎从 115.4 MB 降到 3.2 MB，抵消了这 58.8 MB 依赖）。证据：
`windows/engine/lgpl/dist/flavour-builds.txt`、`windows/engine/lgpl/dist/runtime-dependencies-collected.txt`。

| 许可族 | 文件数 | 字节 | 代表文件 |
| --- | --- | --- | --- |
| LGPL-2.1-or-later / LGPL2.1（含 LGPL 配置的 FFmpeg） | 11 | 33,273,735 | `avcodec-62`、`avfilter-11`、`libplacebo-360`、`libglib-2.0-0` |
| GPL 家族（GPL-3.0-or-later / GPL-2.0-or-later / MIT AND GPL-3.0-or-later） | 5 | 4,949,153 | `libiconv-2`、`libstdc++-6`、`liblcms2-2`、`libfreetype-6`、`libgcc_s_seh-1` |
| Apache-2.0 | 3 | 14,815,073 | `libshaderc_shared`、`libspirv-cross-c-shared`、`vulkan-1` |
| MIT / ISC / Zlib | 7 | 3,777,412 | `libharfbuzz-0`、`libdovi`、`libbrotli*`、`libass-9`、`zlib1` |
| 自定义上游文本 | 2 | 760,610 | `libpng16-16`、`libfontconfig-1` |
| **未记录**（`component-licences.txt` 无声明行） | 5 | 1,250,593 | `libwinpthread-1`、`libpcre2-8-0`、`libintl-8`、`libexpat-1`、`libbz2-1` |
| **合计** | **33** | **58,826,576** | |

两点必须写清楚：

1. **切到 LGPL flavour 不等于零 GPL 义务。** 上表 GPL 家族的 5 个文件（以及 FFmpeg 之外仍然 GPL 的
   `libiconv`、GPL 双许可的 `lcms2`/`libfreetype`）本身就带 GPL 条款；残余义务见
   `windows/docs/licensing/W5-LGPL-BUILD.md`。
2. **5 个未记录项的许可尚未核实**，本文件不猜。逐行证据（文件名/字节/SHA-256/来源路径/许可/证据行号）
   见 `windows/docs/THIRD-PARTY-NOTICES.md` §3.3；核对脚本 `windows/tools/check-licence-surface.ps1 -FailOnLicenceGaps` 会把该缺口判为失败，
   作为「发布 LGPL flavour 之前」的硬门。

上述任何一项都不改变本文件开头的结论：在维护者拍板之前，`libmpv-2.dll` 的 GPL 问题仍是 **RELEASE BLOCKER**，
默认 flavour 仍随包分发 GPL 构建。

## 供应链固定

`windows/tools/fetch-libmpv.ps1` 固定两个摘要并在安装前强制校验：

| 对象 | SHA-256 |
| --- | --- |
| `mpv-dev-x86_64-20261014-git-ec38361c56.7z` | `325555b4e0b1d87fb7c92c96010776581599c64e4988052cb9d05e130b52f939` |
| 解包后的 `libmpv-2.dll`（120,989,696 字节） | `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` |

**两个摘要都固定**的理由：GitHub release 资产可以在原地被替换，URL 本身不构成身份；
固定归档摘要可发现被替换的归档，固定解包后 DLL 摘要还能发现解包环节产出的非受审二进制。
校验失败时脚本**拒绝安装**，而不是继续。

验证方式（无需重新下载）：

```powershell
pwsh -File windows/tools/fetch-libmpv.ps1
# -> libmpv already present and verified: ...\libmpv-2.dll (120989696 bytes)
```

## 未包含

- 不含任何 Emby / Jellyfin / Plex 的商标或私有代码。
- 不含用户的服务器地址、账号或令牌。
- `libmpv-2.dll` **不入库**（`.gitignore` 排除），由上述脚本按固定摘要获取。
