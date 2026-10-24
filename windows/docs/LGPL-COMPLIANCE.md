# LGPL 合规说明（libmpv / MediaHub Windows）

- 状态：**工程与文档草稿，非法律意见**。本文件只描述仓库里可核对的事实与可执行的义务，不构成任何发布就绪结论。
- 适用范围：`windows/` 解决方案（`windows/MediaHub.Windows.sln`）在将 **libmpv 从 GPL 构建切换到 LGPL 构建** 之后需要履行的义务。
- 当前事实（2026-10-23 的工作树）：**已发布的引擎仍是 GPL 构建**，因此 GPL-3.0-or-later 的义务此刻就成立，本文件**不解除** `windows/THIRD-PARTY-NOTICES.md:61-63` 记录的 RELEASE BLOCKER。
- 相关材料：`windows/THIRD-PARTY-NOTICES.md`（手工清单：引擎 GPL 判定与固定摘要）、`windows/docs/THIRD-PARTY-NOTICES.md`（脚本生成：NuGet 依赖清单）、`windows/docs/licensing/LICENCE-DECISION.md`（W3 决策记录）、`windows/docs/licensing/W5-LGPL-BUILD.md`（LGPL 构建证据）、`windows/docs/sbom-windows.cdx.json`（CycloneDX 1.5）。

---

## 1. 引擎的两套事实

| 事实 | GPL 基线（当前随包） | LGPL 候选（已构建，未随包） |
| --- | --- | --- |
| 文件 | `windows/native/libmpv/libmpv-2.dll` | `windows/engine/lgpl/dist/libmpv-2.dll` |
| 大小 | 120,989,696 B | 3,327,274 B |
| sha256 | `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` | `20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a` |
| 版本 | v0.41.0-1154-gec38361c5（内嵌 FFmpeg N-127521-g2ec8998c3） | 同源，`-Dgpl=false` 重建；引擎自报版本串为 `v0.41.0-UNKNOWN`（见 W5-LGPL-BUILD.md:247-249） |
| 许可 | **GPL-3.0-or-later**（`gpl` 在 enabled-features 中） | LGPL-2.1-or-later 组合（`gpl` 不在 features 中） |
| 证据 | `windows/THIRD-PARTY-NOTICES.md:16-41`、`windows/tools/fetch-libmpv.ps1:15-17` | `windows/docs/licensing/W5-LGPL-BUILD.md:16-22`、`windows/engine/lgpl/component-licences.txt` |

两条事实同时存在：**切换动作尚未发生**（`windows/src/MediaHub.Player.Mpv` 仍加载 `windows/native/libmpv/libmpv-2.dll`，见 §2）。在切换完成并重新跑完整验收之前，本文件只用于把义务写成可执行清单，不能当作「现在可以发布」的依据。

---

## 2. 动态链接事实（可核对）

| 事实 | 证据 |
| --- | --- |
| 通过 P/Invoke 按**文件名**加载 DLL，而非静态链接 | `windows/src/MediaHub.Player.Mpv/MpvNative.cs:16`：`internal const string Lib = "libmpv-2.dll";` |
| 共 18 个 `[DllImport(Lib, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]` 入口（`mpv_create` … `mpv_client_api_version`） | `windows/src/MediaHub.Player.Mpv/MpvNative.cs:88-139` |
| DLL 以「输出目录旁的独立文件」形式随包，不合并进 `MediaHub.exe` | `windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj:21-23`（`<None Include="$(LibMpvDir)\libmpv-2.dll" Link="libmpv-2.dll" CopyToOutputDirectory="PreserveNewest" />`） |
| 构建时若缺少 DLL 直接失败（不会悄悄降级） | `windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj:16-19`（`VerifyLibMpv` → `<Error …>`） |
| 文件来源与摘要固定（下载即校验，不符即拒绝安装） | `windows/tools/fetch-libmpv.ps1:33-37`（`$ReleaseTag`/`$AssetSha256`/`$DllSha256`/`$Url`）、`:69-71`、`:88-90`、`:105` |
| DLL **不入库**（`.gitignore` 排除整个目录） | `.gitignore:35-36`（`windows/native/libmpv/`） |
| 进程内由 Windows 加载器按同目录文件名解析 | 由上面两条共同决定：`libmpv-2.dll` 放在 `MediaHub.exe` 同目录即被加载，替换该文件即换引擎 |

结论：**这是标准的「共享库 + 运行时加载」形态**，满足 LGPL-2.1 §6 对「使用合适的共享库机制」的前提，也是用户自行替换库文件的可行基础。

---

## 3. 用户可替换 DLL（必须保持的性质）

LGPL 允许用户用自己修改/重编译的库替换随包库。要维持这一点，以下四条是硬约束：

1. **不得静态链接 libmpv，也不得把 DLL 嵌入可执行文件或单文件打包**。依据：`MpvNative.cs:16` 按文件名加载；`MediaHub.Player.Mpv.csproj:21-23` 以独立文件复制。任何「单文件发布 / 资源内嵌 / 内存加载」改造都会破坏可替换性，必须回到本文件重新评估。
2. **不得改名或遮蔽**：DLL 必须以原名 `libmpv-2.dll` 出现在 `MediaHub.exe` 同目录；不得用前置搜索路径、私有 `SetDllDirectory`、或自带同名壳 DLL 把用户替换的文件挡住。
3. **不得对随包库做阻止替换的完整性强制**。当前校验发生在**构建/更新时**（`fetch-libmpv.ps1:69-105`；`windows/docs/licensing/PACKAGING.md:156-157` 规定更新流程先校验再安装新引擎），这是允许的；但**运行期**若加入「哈希不符即拒绝启动」的逻辑，就会让用户无法使用自己替换的库，必须改为「警告 + 继续」或完全不校验，并在实现时同步更新本文件。
4. **不得对第三方 DLL 做会妨碍替换的改动/签名处理**。`windows/docs/licensing/PACKAGING.md:189` 已记录「用我们的证书给第三方二进制签名」不可取；替换后的库不要求带我们的签名。

> 校验方式（人工/CI 均可）：`Get-ChildItem <安装目录>\libmpv-2.dll` 存在即满足形态要求；把该文件替换为用户自编译版本后 `MediaHub.exe` 仍能启动并播放，即视为可替换性通过。

---

## 4. 源码与构建信息的提供方式

切换到 LGPL 引擎后，随包必须能指向「库自身的源码 + 得到同一二进制的方法」。仓库里已有的材料：

| 需要提供 | 仓库内已有的对应物 |
| --- | --- |
| mpv 上游源码版本 | `windows/engine/lgpl/`（构建脚本与 `component-licences.txt`）、`windows/docs/licensing/W5-LGPL-BUILD.md:26-40`（工具链与命令） |
| 可复现构建步骤 | `windows/engine/lgpl/collect-licences.sh`、`windows/docs/licensing/REPRODUCIBLE-BUILD.md` |
| 依赖组件许可清单 | `windows/engine/lgpl/component-licences.txt` |
| 二进制的确切摘要 | `windows/docs/sbom-windows.cdx.json` 的 libmpv 组件（`hashes` SHA-256） |
| 已知缺口 | `windows/docs/licensing/W5-LGPL-BUILD.md:250-252`：MSYS2 依赖是滚动仓库，未归档成可复现 feed |

**written offer 模板**（放进发布说明/安装包，按实际发布渠道补齐方括号内容）：

```text
MediaHub for Windows 包含 libmpv（LGPL-2.1-or-later）的动态链接副本
（libmpv-2.dll，版本 [版本串]，sha256 [摘要]）。

本产品以动态库方式使用 libmpv，用户可以用自行构建的、与本产品 ABI 兼容的
libmpv-2.dll 替换安装目录中的同名文件，替换后功能由用户自担。

libmpv 及其依赖组件的完整源码、以及生成随包二进制的构建脚本，可从
[源码提供地址 / 仓库地址] 获取；若无法从该地址获取，请以书面方式联系
[发布方联系邮箱]，我们将在 [承诺期限，例如 3 年 / 与支持期一致] 内提供
对应版本的源码副本，仅收取介质与邮寄成本。

本产品自身代码的许可见 [LICENSE 路径]。
```

中文模板之外，发布到海外渠道时应同时提供英文等价文本；两种文本的版本串与摘要必须与 SBOM 一致。

---

## 5. 第三方组件许可与残留风险

- 逐组件清单：`windows/engine/lgpl/component-licences.txt`（libass ISC、libplacebo LGPL-2.1、shaderc/spirv-cross/vulkan-loader Apache-2.0、glslang BSD-3-Clause、freetype GPL-2.0-or-later OR FTL、harfbuzz MIT、fribidi/glib2/graphite2 LGPL-2.1-or-later、lcms2 MIT AND GPL-3.0-or-later、libdovi MIT、libunibreak Zlib、libpng custom、brotli MIT、zlib Zlib、libiconv GPL-3.0-or-later、libgcc/libstdc++ GPL-3.0-or-later WITH GCC-exception-3.1、libjpeg-turbo BSD-like、libtiff MIT、libwebp BSD-3-Clause、giflib MIT、lerc Apache-2.0、libdeflate MIT、jbigkit GPL-2.0）。
- **两处需要人工确认**（W5-LGPL-BUILD.md:226-235）：
  - `libiconv-2.dll`：MSYS2 对**包**声明 GPL-3.0-or-later，而 GNU libiconv 的**库**是 LGPL-2.1-or-later（GPL 只覆盖 `iconv` 程序）。随包的是 DLL，主张成立但**必须核实**并留证。
  - `liblcms2-2.dll`：MSYS2 声明 `MIT AND GPL-3.0-or-later`，lcms2 上游是 MIT。需确认随包 DLL 对应的上游许可，不能默认「MIT 就够」。
- `jbigkit`（GPL-2.0）在 pacman 依赖闭包里但**不在运行时闭包**，不随包；一旦打包方式变化必须重新核对。
- `freetype` 走 FTL 选项（宽松分支），不是 GPL 分支。

---

## 6. 我们自身的许可（当前为缺陷）

- 仓库根**没有 `LICENSE` 文件**：`windows/docs/licensing/LICENCE-DECISION.md:180-190` 记录 `git ls-files` 只有一个匹配文件（`windows/THIRD-PARTY-NOTICES.md`），`git log -- LICENSE` 无输出。
- `windows/THIRD-PARTY-NOTICES.md:63` 与 `LICENCE-DECISION.md:186` 引用的 `ADR-W001:40` 声称「本仓库为 MIT」，但**没有任何文件承载该声明**，`README.md` 的许可徽章指向死链接。
- 因此：本文件**不得**被解读为「MediaHub 自身是 MIT 或任何特定许可」。发布前必须由人写定 `LICENSE` 并与此处对齐；在此之前「LGPL-2.1 §6 + 自身许可声明」这一环无法闭环。本条是**独立于引擎切换的 release blocker**。

---

## 7. 分发前人工 checklist

| # | 检查项 | 通过标准 | 证据/命令 |
| --- | --- | --- | --- |
| 1 | 随包引擎确为 LGPL 构建 | 安装目录 `libmpv-2.dll` 的 sha256 = LGPL 候选摘要，且**不是** GPL 基线摘要 | `Get-FileHash .\libmpv-2.dll -Algorithm SHA256` 对照 §1 表 |
| 2 | 引擎自报 features 无 `gpl` | 引擎 feature 列表 28 项且不含 `gpl` | `W5-LGPL-BUILD.md:20` 的三源核对记录 |
| 3 | 动态链接形态未被破坏 | 独立 `libmpv-2.dll` + 无内嵌/无改名 | `MpvNative.cs:16,88-139`；`MediaHub.Player.Mpv.csproj:21-23` |
| 4 | 可替换性实测 | 用自编译 DLL 替换后仍能启动播放 | §3 校验方式 |
| 5 | 运行期无不符即拒的完整性强制 | 代码中无「哈希不符即退出」路径 | 检索 `windows/src/MediaHub.Player.Mpv` 与启动路径 |
| 6 | LGPL 文本与声明随包 | 安装包内包含 LGPL-2.1 全文 + 使用的库清单 + 声明 | 安装包内容检查；清单见 `windows/docs/THIRD-PARTY-NOTICES.md` §3 |
| 7 | 源码可得 + written offer 已发布 | §4 模板填好并出现在发布说明 | `windows/docs/licensing/REPRODUCIBLE-BUILD.md`、`windows/engine/lgpl/` |
| 8 | libiconv / lcms2 许可主张已核实并留证 | 两条均有上游证据 | `W5-LGPL-BUILD.md:226-235` |
| 9 | 自身 LICENSE 已落盘并与声明一致 | 根目录存在 `LICENSE`，声明与之一致 | `git ls-files | Select-String 'LICENSE'` |
| 10 | NuGet 依赖许可复核 | 脚本退出码 0 | `pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Sbom` |
| 11 | SBOM 与实包一致 | `windows/docs/sbom-windows.cdx.json` 的 libmpv 摘要与实际文件一致 | `license-report.ps1` 生成时自带摘要校验 |
| 12 | 签名策略不阻碍替换 | 不对第三方 DLL 强签、不校验替换件签名 | `PACKAGING.md:189` |

全部勾选之前不得对外分发；任何一项为「否」都要回到发布说明里如实标注，而不是用模糊措辞掩盖。

---

## 8. 复现与再生成

```powershell
# NuGet 依赖清单 + SBOM（会顺带核对随包 libmpv 的固定摘要）
dotnet restore windows/MediaHub.Windows.sln
pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <任意目录> -Sbom

# 只核对引擎摘要
Get-FileHash windows/native/libmpv/libmpv-2.dll -Algorithm SHA256
```

- `license-report.ps1` 输出 `windows/docs/THIRD-PARTY-NOTICES.md` 与 `windows/docs/sbom-windows.cdx.json`，并对 `windows/native/libmpv/libmpv-2.dll` 做固定摘要比对（不符会给出 WARN 并在证据 md 里标注）。
- 退出码：0 干净 / 1 未找到可检查工程 / 2 缺 `project.assets.json`（先 restore）/ 3 策略违规。

## 9. 边界

本文件是工程侧的合规**清单与事实记录**，不是法律意见；`W5-LGPL-BUILD.md:5-7` 与 `LICENCE-DECISION.md` 的同等声明继续有效。最终许可能否成立由人工/法务判断，且必须先解决 §6 的自身许可缺失与 §5 的两处许可主张核实。
