# LGPL 引擎切换说明（W13b）

> 状态：**默认仍未切换**。本仓库默认构建、默认分发的是 `windows/native/libmpv` 下的
> **GPL-3.0-or-later** 基线（默认值见 `windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj:23`）；
> `-p:LibMpvFlavour=lgpl` 只是给维护者验证 LGPL 候选的旁路开关。
> `windows/THIRD-PARTY-NOTICES.md:61-63` 记录的 RELEASE BLOCKER **未解除**，`README.md:173-174` 的口径不变。
>
> 本文只回答四件事：怎么切、切完怎么验、依赖清单长什么样、什么时候必须重跑收集。

## 1. 边界（这份文档不改变什么）

| 事项 | 现状 | 位置 |
| --- | --- | --- |
| 默认 flavour | `gpl`，未改 | `MediaHub.Player.Mpv.csproj:23` |
| 随包分发的引擎 | `windows/native/libmpv/libmpv-2.dll`，GPL-3.0-or-later | `windows/tools/fetch-libmpv.ps1:15-17` |
| 许可结论 | RELEASE BLOCKER 仍在（GPL DLL 与仓库自称 MIT 冲突） | `windows/THIRD-PARTY-NOTICES.md:61-63` |
| 仓库自身许可 | 根目录没有 `LICENSE`，声明与实际不一致 | `windows/docs/licensing/LICENCE-DECISION.md:180-190`、`windows/docs/LGPL-COMPLIANCE.md` §6 |
| LGPL 候选状态 | 已构建、已自包含、已验证可加载，**未切产品** | `README.md:128`、`windows/docs/licensing/W5-LGPL-BUILD.md` |

切不切、什么时候切，是维护者决定（见第 7 节）。本文与脚本只是把切换路径做成可复现、可回滚。

## 2. 两套引擎

| flavour | 目录 | libmpv-2.dll | 许可 | 同行依赖 | Debug x64 输出文件数 |
| --- | --- | --- | --- | --- | --- |
| `gpl`（默认） | `windows/native/libmpv` | 120,989,696 B / `6a48faec…63d9` | GPL-3.0-or-later | 无（静态自带 + 系统 DLL） | 352 |
| `lgpl` | `windows/engine/lgpl/dist` | 3,327,274 B / `20035902…3c0a` | LGPL-2.1-or-later（判定见 W5-LGPL-BUILD.md，残余待核实项见第 7 节） | **33 个**，必须与 core 同目录 | 385（352 + 33） |

两个目录都**不在**任何发行包里「随仓库分发」：GPL 那份 115 MB，靠 `fetch-libmpv.ps1` 拉取（`.gitignore:35-36`）；
LGPL 那份的 33 个依赖靠 `collect-lgpl-runtime.ps1` 从本机工具链收集（第 5 节）。

差异是本质性的：GPL 构建把编解码器静态链进 DLL（单文件 120 MB）；LGPL 构建动态链接 FFmpeg
（core 只有 3.3 MB），因此**少了任何一个依赖 DLL，进程会在加载期弹「找不到 xxx.dll」而不是报一个可读错误**。
`VerifyLibMpv` 与 `collect-lgpl-runtime.ps1` 就是为了把这类失败提前到构建期。

## 3. 怎么切

### 3.1 先确认 dist 的依赖是齐的

```pwsh
# 只看计划，不复制任何文件（-WhatIf 由 CmdletBinding(SupportsShouldProcess) 提供）
pwsh -NoProfile -File windows/tools/collect-lgpl-runtime.ps1 -WhatIf

# 真正收集：把非系统依赖从工具链目录复制进 dist，并写出 runtime-dependencies-collected.txt
pwsh -NoProfile -File windows/tools/collect-lgpl-runtime.ps1
```

退出码（脚本在末尾打印 `RESULT:` 行）：

| 退出码 | 含义 | 处置 |
| --- | --- | --- |
| 0 | `RESULT: SELF-CONTAINED` —— 全部导入都已解析 | 可以构建 lgpl |
| 1 | dist 目录不存在 | 先构建 LGPL 引擎并放入 dist |
| 2 | dist 里没有 libmpv-2.dll | 跑 `windows/engine/lgpl/build-mpv-lgpl.sh` |
| 3 | `RESULT: INCOMPLETE`，有导入既不在 dist、也不在依赖根、也不是 Windows DLL | 加依赖根（`-DependencyRoots`）或重建引擎；确认无碍才用 `-AllowUnresolved` |
| 4 | dist 里的 DLL 不是预期那份 LGPL 候选（sha256 不符） | 确认要换引擎则同步脚本里的 `$ExpectedCoreSha256` / `$ExpectedCoreBytes` |

其它开关：`-RepoRoot`、`-DistDirectory`、`-CoreDllName`、`-DependencyRoots`、`-OutputFileName`、`-Force`（dist 里同名但哈希不同的文件默认保留并告警）、`-AllowUnresolved`。

### 3.2 用 lgpl 构建

```pwsh
dotnet build src/MediaHub.App/MediaHub.App.csproj -c Debug -p:Platform=x64 `
  -p:MediaHubBinRoot=D:/mh-out-lgpl/ -p:LibMpvFlavour=lgpl
```

（`MediaHubBinRoot` 只被 App 项目消费，所以两种 flavour 的产物比对必须以 App 为构建对象；
默认 gpl 的等价命令去掉 `-p:LibMpvFlavour` 即可。）

### 3.3 构建期守卫

`MediaHub.Player.Mpv.csproj:40-49` 的 `VerifyLibMpv` 目标在 `BeforeBuild` 跑，四条错误都附上唯一能修好它的命令：

| 条件 | 报错（节选） |
| --- | --- |
| `LibMpvFlavour` 既不是 gpl 也不是 lgpl（csproj:41） | `Unknown LibMpvFlavour '…'. Use -p:LibMpvFlavour=gpl … or -p:LibMpvFlavour=lgpl …` |
| gpl 但目录里没有 DLL（csproj:43） | `libmpv-2.dll not found in '…' (LibMpvFlavour=gpl). Run: pwsh -File windows/tools/fetch-libmpv.ps1` |
| lgpl 但目录里没有 DLL（csproj:45） | `… (LibMpvFlavour=lgpl). Build the candidate first (…build-ffmpeg-lgpl.sh, build-mpv-lgpl.sh), then run: … collect-lgpl-runtime.ps1` |
| lgpl 但缺依赖清单（csproj:47） | `… has not had its dependency set collected (runtime-dependencies-collected.txt is missing) …` |

复制行为：csproj:51-53 只复制 core；csproj:60-65 在 lgpl 下把 `$(LibMpvDir)\*.dll`（排除 core）**平铺**复制到输出目录 ——
Windows 加载器按加载模块所在目录解析导入，平铺是唯一能让 P/Invoke 成功、又让用户能整目录替换的布局
（替换义务见 `windows/docs/LGPL-COMPLIANCE.md` §3）。

### 3.4 守卫的负路径（已实测，防止「绿了但没测到」）

| 场景 | 实测结果 |
| --- | --- |
| `-p:LibMpvFlavour=bogus` | exit 1，csproj(41,5) 报未知 flavour |
| lgpl + `-p:LibMpvDir` 指向只放了 core 的临时目录 | exit 1，csproj(47,5) 报缺 `runtime-dependencies-collected.txt` |
| gpl + `-p:LibMpvDir` 指向空临时目录 | exit 1，csproj(43,5) 报 libmpv-2.dll not found |

（用 `dotnet msbuild <csproj> -t:VerifyLibMpv -p:…` 单独触发，临时目录用完即删。）

## 4. 切完必须验证三件事

### 4.1 普通探针：DLL 能加载、能解码

`windows/tools/acceptance/engine/engine-probe.ps1` 会同时跑 GPL 与 LGPL 两份，断言 24 条
（身份哈希、加载模式、features 来源、maxTimePos、许可 token 等），末行打印 `RESULT: N passed, M failed`。

**注意**：该脚本默认 `-LgplDependencies` 包含 `C:\msys64\ucrt64\bin`，所以它通过只证明
「这台装了 MSYS2 的机器上能跑」，**不证明 dist 自包含**。自包含要单独验（4.2）。

### 4.2 自包含探针：把 PATH 里的工具链拿掉再跑

```pwsh
$repo  = "D:\deepseek_test\MediaHub_Win"
$dist  = "$repo\windows\engine\lgpl\dist"
$probe = "$repo\windows\src\MediaHub.EngineProbe\bin\Debug\net10.0\MediaHub.EngineProbe.exe"
dotnet build windows/src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj -c Debug -nologo

$saved = $env:PATH
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -and $_ -notmatch 'msys64|mh-servers|lgpl-build' }) -join ';'
& $probe --dll "$dist\libmpv-2.dll" --deps $dist `
  --media "$repo\windows\tests\assets\sample-av.mp4" --seconds 3 `
  --label lgpl-selfcontained --json "$env:TEMP\lgpl-selfcontained.json"
$env:PATH = $saved
```

必须同时满足（缺一条就不算自包含）：

1. `verdict = PASS`；
2. **`loadMode = native-library`** —— 探针先 `NativeLibrary.Load(绝对路径)`，失败才 `AddDllDirectory(--deps)` 再 `LoadLibraryExW`；
   只有主路径成功才说明是 **Windows 加载器自己**从 DLL 所在目录解析到了全部 33 个依赖，
   而这正是产品形态（`MpvNative.cs:16` 的 `[DllImport("libmpv-2.dll")]` 按名加载同目录文件）；
3. `featuresSource = log`、`featuresContainGpl = false`（LGPL 候选不带 gpl token）；
4. `maxTimePos ≥ 2`（3 秒片源解到 3.000）；
5. `errorLogTotal = 0`。

本次实测（2026-10-23）结果与完整 JSON 存 `windows/engine/lgpl/dist/selfcontained-probe.txt`
（含 10 条断言、probe stdout、依赖清单、受限项说明）。同一命令对 **LGPL 构建输出目录**
（`D:\mh-out-lgpl\…\win-x64`）也跑过一遍：`verdict=PASS » loadMode=native-library » maxTimePos=3 » gplToken=False` ——
即打包出来的目录同样自包含。

### 4.3 单测回归

```pwsh
dotnet test windows/tests/MediaHub.Tests/MediaHub.Tests.csproj -c Debug --nologo
```

切换 flavour 不改变托管代码路径，但会改 P/Invoke 的实际目标，至少确认基线不变：438 passed / 0 failed。

## 5. 依赖清单与 sha256

### 5.1 收集规则（`collect-lgpl-runtime.ps1`）

从 dist 的 `libmpv-2.dll` 出发，**递归**遍历 PE 导入表（纯 PowerShell 解析 PE：DOS 头 → COFF → 可选头 →
导入表/延迟导入表 → 节表 RVA 换算，不依赖 dumpbin/objdump/VS）。每个导入按顺序判定：

1. dist 里已有 → 复用（`origin=dist`）；
2. 依赖根里有（默认 `D:\mh-servers\lgpl-build\prefix\bin` 与 `C:\msys64\ucrt64\bin`）→ 复制进 dist；
3. 名字匹配 `^(api-ms-win|ext-ms-win)-` → API set 契约，不是真实文件，跳过；
4. `%SystemRoot%\System32\<name>` 存在 → 视为操作系统 DLL，**不复制**（随包分发系统 DLL 既无必要也违反 Windows 许可）；
5. 其余 → 计入 unresolved，默认让脚本以退出码 3 结束（`INCOMPLETE`）。

每一步的 name / bytes / sha256 / source 都写进 `windows/engine/lgpl/dist/runtime-dependencies-collected.txt`。
「系统 DLL」的判定只用第 3、4 条这两个可解释规则，没有维护任何手工白名单。

### 5.2 33 个随包依赖（合计 56.1 MiB；7 个来自 FFmpeg 前缀，26 个来自 MSYS2 ucrt64）

| 文件 | 字节 | sha256 | 来源 |
| --- | --- | --- | --- |
| `avcodec-62.dll` | 18,603,520 | `1faacb7a3bfd9d16c5b8258ede66baf03c31d2578d828af68ade215ada6d9f4d` | FFmpeg 前缀 |
| `avdevice-62.dll` | 153,088 | `af2075f1e5d17a04e0a109d76af8704ca15b6c1438054962ebdfa596ce793ef2` | FFmpeg 前缀 |
| `avfilter-11.dll` | 5,288,448 | `c077cd1c46d337851e4035716fcfb218a48a771b073b27474cf86c1e4a6f9c05` | FFmpeg 前缀 |
| `avformat-62.dll` | 2,805,760 | `e6bf5d047479763b818ce34b73ae256c1d7d2163c1d6964cef6eff7ea5636257` | FFmpeg 前缀 |
| `avutil-60.dll` | 1,067,520 | `c1ce3e99f2252ce1d977bc5e0d3d6e2e9d0200723a391c0ef1b781337e7bd353` | FFmpeg 前缀 |
| `libass-9.dll` | 345,174 | `cb2b46133b3bd9630384148bd7229e987af9703208d4329fc2215f4170b8dc6b` | MSYS2 ucrt64 |
| `libbrotlicommon.dll` | 144,565 | `c62c08223e450dfc2fff33c752cc228505ffd9d755201958ba800d5d30481a2c` | MSYS2 ucrt64 |
| `libbrotlidec.dll` | 62,619 | `e270b6e576594ee8ee6e33ccc54e34a54304616dd6225652e11e49d6f17ef896` | MSYS2 ucrt64 |
| `libbz2-1.dll` | 78,774 | `669a4bf9266d5f020e843aa5fd75b93ce8ebde27014d5ac466a8b3cf39d8aba7` | MSYS2 ucrt64 |
| `libdovi.dll` | 1,370,861 | `75988509f0d2166e2060ed548913bd7b34556bc16b3f9a02469de1dd2347a7fb` | MSYS2 ucrt64 |
| `libexpat-1.dll` | 191,185 | `00b9ad2a1f75bbf4f83428a9adcd609da1c8590a84c944226422f081cb1459e1` | MSYS2 ucrt64 |
| `libfontconfig-1.dll` | 505,833 | `be30a200fa4000d0a7a1d3911391969627440148f01d0028a319bcb9fa112202` | MSYS2 ucrt64 |
| `libfreetype-6.dll` | 808,305 | `19604dde573cbb8a13053fc231eee99b91cd972e0e9a3e1465f44ad6985e83ee` | MSYS2 ucrt64 |
| `libfribidi-0.dll` | 152,541 | `14bd0ed4c24d0068434bc23cd4f39e8dc0edcf56f2c9996b6fe39b39ffc54673` | MSYS2 ucrt64 |
| `libgcc_s_seh-1.dll` | 152,895 | `6a68f99a316764b680bbb6f0553299fbc7130a3421a1d64bb044627cbe0c6db2` | MSYS2 ucrt64 |
| `libglib-2.0-0.dll` | 1,568,604 | `3a1abec0184b238db54399cb9243393d9436d96b8fdb9bd9f2875da207c5b458` | MSYS2 ucrt64 |
| `libgraphite2.dll` | 167,857 | `93953e4744bcfce9a7bc439b956544bc213095cb1349c3602a58370f32b25c18` | MSYS2 ucrt64 |
| `libharfbuzz-0.dll` | 1,612,472 | `842d73ce2dd9ee506ef68f4d3e9ca29b62c87e34999cd2720505bf3c9d3f575c` | MSYS2 ucrt64 |
| `libiconv-2.dll` | 1,135,869 | `b37d1b4acab5310c4e4f6e2a961d146454ff740d943724a68fa3cebc85deb7cd` | MSYS2 ucrt64 |
| `libintl-8.dll` | 212,436 | `77184a77e8e751999816c311ae144fae3fe1957992dff525c13267bd845982d4` | MSYS2 ucrt64 |
| `liblcms2-2.dll` | 448,202 | `03314471fadd04cdd63c108472ff85d058903d236a0356c14eda363f0e1ccb65` | MSYS2 ucrt64 |
| `libpcre2-8-0.dll` | 706,022 | `1f609cde2ce432efb7d2b0c7b84a14a6dbfc9622d1ac1a3cbadf51c5b0b4dada` | MSYS2 ucrt64 |
| `libplacebo-360.dll` | 1,235,613 | `8eb2c3fe24a124d9dbf0383d60ac7dde6c0dd5607b2451cad0d924271e46d5b8` | MSYS2 ucrt64 |
| `libpng16-16.dll` | 254,777 | `7f425fd2b41d83869bb31bf761b4fb315f11040d55540661f96e582e1ec79a36` | MSYS2 ucrt64 |
| `libshaderc_shared.dll` | 10,657,197 | `331df8a45983735b823c893a9b872c3414af6eadca9fa189d0b6dfe97960b3cf` | MSYS2 ucrt64 |
| `libspirv-cross-c-shared.dll` | 3,433,687 | `bc4bdcf63b9bbf60777bc10340050ae6edfbb3be3faf3fa636af53ecf7d2bbc8` | MSYS2 ucrt64 |
| `libstdc++-6.dll` | 2,403,882 | `9510ec8bbec49de557da9935f793ad19f1ffdb4e5e3ef7715bd40ffe0b5f3892` | MSYS2 ucrt64 |
| `libunibreak-7.dll` | 141,442 | `f1f4bb5b5bb3dbf8d142df3012f13e4b5b0246565496d43718a6f33321e96283` | MSYS2 ucrt64 |
| `libwinpthread-1.dll` | 62,176 | `0e013ffc123dda330d27c76f26ba74fcd690f1236bb0225d3b4f406d71774e9e` | MSYS2 ucrt64 |
| `swresample-6.dll` | 140,800 | `7fc1e4754c1ddf7f2eebec7801a32d3ffb0efe7d9753e84837d8a57d9e243ee0` | FFmpeg 前缀 |
| `swscale-9.dll` | 2,089,984 | `e0d920aea621b242703c988807b353989a84ce3069bc80979135bc60f3255bae` | FFmpeg 前缀 |
| `vulkan-1.dll` | 724,189 | `31862137c4179dcdbba782760f3e401f9fb526477947079d7f63987864f9b0eb` | MSYS2 ucrt64 |
| `zlib1.dll` | 100,279 | `1dc85208162ee57fe97e892bb5160fe9588903992995f90adc3a3a358dac6fc6` | MSYS2 ucrt64 |

核心件：`libmpv-2.dll` 3,327,274 B `20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a`。

### 5.3 44 个留给操作系统的 DLL

脚本把它们逐个列出（不是笼统「系统库」）：ADVAPI32、AVICAP32、AVRT、bcrypt、bcryptprimitives、CFGMGR32、
CRYPT32、dwmapi、DWrite、GDI32、IMM32、KERNEL32、ncrypt、ntdll、ole32、OLEAUT32、OPENGL32、RPCRT4、
Secur32、SHCORE、SHELL32、SHLWAPI、USER32、USERENV、USP10、UxTheme、VERSION、WS2_32，外加 16 个
`api-ms-win-*` API set。这些在 Windows 10/11 上必然存在，随包复制反而不可取。
目标机若缺某个，报错里就是上面这些明文名字，可直接定位；本仓库支持范围见 `README.md`。

### 5.4 为什么 33 个 DLL 不入库

它们在 `windows/engine/lgpl/dist/.gitignore` 里被排除（保留 core `libmpv-2.dll` 与所有 `*.txt` 证据）。
理由：约 56 MiB 的第三方构建产物，身份由 `runtime-dependencies-collected.txt` 的 sha256 记录，字节可再生；
把它们提交进 git 会让每次引擎重建都产生数十 MB 的二进制提交。**别人 clone 之后必须自己跑一次
`collect-lgpl-runtime.ps1`**，否则 `-p:LibMpvFlavour=lgpl` 会被 csproj:47 的守卫拦下（这是刻意的）。

## 6. 什么时候必须重跑 collect

- 重建了 LGPL 引擎（`build-ffmpeg-lgpl.sh` / `build-mpv-lgpl.sh`）→ core 变了，导入表可能变；
- 升级了 MSYS2 的 `ucrt64` 包或 FFmpeg 前缀（依赖版本与哈希会变）；
- 改了 mpv 的 `--enable-*` 特性（新增特性可能引入新 DLL，例如换掉着色链路）；
- 换了一台构建机（依赖根路径不同 → 传 `-DependencyRoots`）；
- 打包前例行核对：`-WhatIf` 的输出里 unresolved 必须为 0。

散落提醒：脚本对 dist 里「同名但哈希不同」的文件默认**保留并告警**（避免悄悄替换用户手工放入的构建），
要用工具链版本覆盖时加 `-Force`。

## 7. 发布仍需维护者拍板

即使第 4 节全部通过，以下决定不属于工程侧：

1. **许可判定**：LGPL-2.1-or-later 的结论依据、以及 `W5-LGPL-BUILD.md:222-252` 列出的残余待核实项
   （libiconv / lcms2 等组件、`-Dgpl=false` 非授权、MSYS2 非固定 feed、版本串 `v0.41.0-UNKNOWN`）；
2. **仓库自身许可**：根目录没有 `LICENSE`，切换引擎前必须先补齐，否则无法对外声称任何许可
   （`LICENCE-DECISION.md:180-190`、`LGPL-COMPLIANCE.md` §6）；
3. **发布口径**：一旦切换，`README.md:173-174`、`windows/THIRD-PARTY-NOTICES.md` §3、
   `windows/docs/sbom-windows.cdx.json` 里的 libmpv 组件许可必须同步改写；
4. **用户可替换性**：安装目录布局、升级/卸载时是否会覆盖用户替换的 DLL（`LGPL-COMPLIANCE.md` §3 的四条硬约束）。

**在维护者拍板前，本仓库默认仍分发 GPL 构建，RELEASE_BLOCKED 不变。**

## 8. 复现证据（原始行）

| 环节 | 原始输出 | 证据文件 |
| --- | --- | --- |
| 收集依赖（-WhatIf 复跑） | `PE files parsed : 34` / `dependencies : 33 non-system, 44 system, 0 unresolved` / `RESULT: SELF-CONTAINED - 33 dependency DLLs resolved, 44 Windows DLLs left to the OS.` / exit 0 | `dist/runtime-dependencies-collected.txt` |
| gpl 构建 | `已成功生成。 0 个警告 0 个错误`（00:00:22.51），352 个文件 | `dist/flavour-builds.txt` §1 |
| lgpl 构建 | `已成功生成。 0 个警告 0 个错误`（00:00:08.40），385 个文件、引擎 DLL 34 个 | `dist/flavour-builds.txt` §2 |
| 产物哈希交叉核对 | 34 个 DLL 逐个与 dist 源比对，0 不一致 | `dist/flavour-builds.txt` §2 表 |
| 自包含探针 | `verdict=PASS`、`loadMode=native-library`、`featuresContainGpl=False`、`maxTimePos=3.000`、exit 0 | `dist/selfcontained-probe.txt` |
| 受限 PATH | 原 65 条 → 保留 64 条（仅剔除 `C:\msys64\ucrt64\bin`） | `dist/selfcontained-probe.txt` 头部 |
| 单测回归 | `已通过! - 失败: 0，通过: 438，已跳过: 0，总计: 438` | 本文 §4.3 |

两次构建都用 `-p:MediaHubBinRoot` 指到 `D:/mh-out-gpl/` 与 `D:/mh-out-lgpl/`，未触碰任何既有输出目录，未运行 GUI。

## 9. 已知边界与未覆盖

- 构建层验到 **x64 的 Debug build 与 Release publish**（后者见第 10 节）；ARM64、MSIX 打包、签名与安装包形态均未验；
- 探针设 `vo=null / ao=null / hwdec=no`，**GPU 解码、HDR、音频输出设备**均未验；
- 依赖集合是本机 MSYS2/FFmpeg 的一次快照，换机器或换工具链后哈希会变（参见 `windows/docs/licensing/REPRODUCIBLE-BUILD.md`）；
- `v0.41.0-UNKNOWN` 版本串（LGPL 构建拿不到 git describe）仍是既知瑕疵；
- 44 个系统 DLL 的可获得性依赖受支持的 Windows 版本，未在更老系统上验证；
- `collect-lgpl-runtime.ps1` 解析的是导入表（含延迟导入），不覆盖运行时 `LoadLibrary` 动态拉取的模块
  （例如用户自带的字体或显卡驱动侧的 DLL），这类缺失只会在真机播放时暴露；
- 本文不评价「该不该换引擎」，那是第 7 节的事。

## 10. Release/publish 两 flavour 实测

第 3、4 节验的是 Debug build；真正会发给用户的形态是 Release publish。本节把两种 flavour 各发布一次，
并证明**发布目录自身**自包含（与构建机的 MSYS2 / FFmpeg 前缀无关）。结论：两条命令都成功，
两个目录各自独立可加载，LGPL 目录比 GPL 目录**恰好多 33 个依赖 DLL**，文本产物里没有任何构建机路径。

### 10.1 原始命令行（working-directory = `windows`）

命令形状与 CI 的 Portable publish 步骤一致（`.github/workflows/windows-ci.yml:172-177`），
只把输出目录换成独立目录（不覆盖 CI 的 `./artifacts/portable`，也不碰既有的 `D:/mh-out*`）：

```pwsh
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -o D:/mh-out-pub-gpl
dotnet publish src/MediaHub.App/MediaHub.App.csproj -c Release -p:Platform=x64 -p:LibMpvFlavour=lgpl -o D:/mh-out-pub-lgpl
```

### 10.2 原始输出

| flavour | 退出码 | 耗时 | 尾部原始行 |
| --- | --- | --- | --- |
| gpl | 0 | 47 s | `MediaHub.App -> D:\mh-out-pub-gpl\` |
| lgpl | 0 | 17 s | `MediaHub.App -> D:\mh-out-pub-lgpl\` |

两次完整日志（本机证据 `D:\mh-verify\w13c\publish-gpl.log` / `publish-lgpl.log`）里
按 `warning `、`error `、`个警告`、`个错误` 检索均为 **0 条命中**（dotnet 只在有问题时才打印警告/错误段）。
LGPL 那次还打印了 `MediaHub.Player.Mpv -> …\MediaHub.Player.Mpv.dll`，与 GPL 那次一致。

### 10.3 产物清单

| 目录 | 文件数 | 总字节 | 折合 | libmpv-2.dll 字节 | libmpv-2.dll sha256 |
| --- | --- | --- | --- | --- | --- |
| `D:\mh-out-pub-gpl` | 526 | 348,275,148 | 332.14 MiB | 120,989,696 | `6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9` |
| `D:\mh-out-pub-lgpl` | 559 | 289,439,302 | 276.03 MiB | 3,327,274 | `20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a` |

LGPL 目录**总字节更小**，因为 core 从 120.99 MB 降到 3.33 MB（−117.6 MB），而新增的 33 个依赖只有 58.8 MB。
两棵树根目录 `*.dll` 计数：GPL 280 → LGPL 313（+33）。
CI 的 smoke-check 清单（`MediaHub.exe`、`libmpv-2.dll`、`MediaHub.dll`、`MediaHub.Net.dll`、`MediaHub.Providers.dll`）
在两个目录里都齐全（missing=0）。

### 10.4 两 flavour 的差异文件

按相对路径递归比对（`Get-ChildItem -Recurse`，逐文件长度）：

- **只在 LGPL 出现：33 个**（恰为第 5.2 节列的依赖集合，全在根目录）；
- **只在 GPL 出现：0 个** —— 也就是说 GPL 目录里**没有**任何辅助 DLL，引擎 DLL 只有 1 个 `libmpv-2.dll`；
- **同名但大小不同：1 个** —— `libmpv-2.dll`（120,989,696 → 3,327,274）。

33 个新增文件逐个与 `windows/engine/lgpl/dist/` 源比对 sha256：**0 处不一致**，合计 58,826,576 B。
FFmpeg 侧（7 个）：`avcodec-62`(18,603,520) `avdevice-62`(153,088) `avfilter-11`(5,288,448)
`avformat-62`(2,805,760) `avutil-60`(1,067,520) `swresample-6`(140,800) `swscale-9`(2,089,984)；
MSYS2 侧（26 个）：`libass-9` `libbrotlicommon` `libbrotlidec` `libbz2-1` `libdovi` `libexpat-1`
`libfontconfig-1` `libfreetype-6` `libfribidi-0` `libgcc_s_seh-1` `libglib-2.0-0` `libgraphite2`
`libharfbuzz-0` `libiconv-2` `libintl-8` `liblcms2-2` `libpcre2-8-0` `libplacebo-360` `libpng16-16`
`libshaderc_shared`(10,657,197) `libspirv-cross-c-shared`(3,433,687) `libstdc++-6` `libunibreak-7`
`libwinpthread-1` `vulkan-1` `zlib1`（逐文件 sha256 见第 5.2 节，本次实测与之一致）。
LGPL 目录里的 core 与 `dist\libmpv-2.dll` 哈希相同：`20035902…3c0a`。

### 10.5 自包含探针（把构建机路径从 PATH 里剔掉）

用已构建的探针（不重新编译），两个发布目录各跑一次：

```pwsh
# 先剔除 PATH 中匹配 msys64|mh-servers|lgpl-build 的条目（实测：65 条 → 64 条，只掉 C:\msys64\ucrt64\bin）
dotnet windows/src/MediaHub.EngineProbe/bin/Debug/net10.0/MediaHub.EngineProbe.dll `
  --dll D:/mh-out-pub-gpl/libmpv-2.dll --deps D:/mh-out-pub-gpl `
  --media windows/tests/assets/sample-av.mp4 --seconds 3 --label release-publish-gpl --json <out.json>
```

（LGPL 那次只把 `--dll/--deps` 换成 `D:/mh-out-pub-lgpl`、label 换成 `release-publish-lgpl`。）

| 断言 | GPL 原始行 | LGPL 原始行 |
| --- | --- | --- |
| verdict=PASS | `"verdict": "PASS"` | `"verdict": "PASS"` |
| loadMode 主路径 | `"loadMode": "native-library"` | `"loadMode": "native-library"` |
| featuresSource=log | `"featuresSource": "log"` | `"featuresSource": "log"` |
| errorLogTotal=0 | `"errorLogTotal": 0` | `"errorLogTotal": 0` |
| maxTimePos≥2 | `"maxTimePos": 3` | `"maxTimePos": 3` |
| GPL token | `"featuresContainGpl": true` | `"featuresContainGpl": false` |

两侧退出码均为 0；探针 stdout 的判定行同为
`verdict        : PASS` / `reason         : file-loaded 且 time-pos 推进到 3.000 秒（下限 2.000 秒），error 级日志 0 条`；
`"dllBytes"` 分别为 120989696 与 3327274，`"clientApiVersion": 131077`（0x00020005 = 2.5）一致。

**为什么这能证明自包含**：探针先 `NativeLibrary.Load(绝对路径)`（loadMode=`native-library`），
只有它失败时才 `AddDllDirectory(--deps)` 重试（`native-library+user-dirs`）或退到 `LoadLibraryExW`。
本次两侧都停在第一级，说明 Windows 加载器**自己**从 `libmpv-2.dll` 所在目录解析了全部 33 个依赖 ——
这与产品形态一致（`MpvNative.cs:16` 用 `[DllImport("libmpv-2.dll")]`，App 输出目录就是 DLL 目录）。
另外 `--deps` 虽然传了，但没有被用到（否则 loadMode 会是 +`user-dirs`）。

### 10.6 泄漏检查（发布目录里不得残留构建机路径）

对两棵树里的全部文本产物（`*.json` `*.config` `*.xml` `*.manifest` `*.txt` `*.props` `*.targets`
`*.js` `*.html` `*.css`，每棵树 9 个文件）用正则 `msys64|lgpl-build|mh-servers` 检索：
**GPL 0 条命中、LGPL 0 条命中**；按**文件名**检索同样 0 条。
`MediaHub.deps.json` 里也不列这些引擎 DLL —— 它们是以 `None Include` 的 content 复制进输出目录的，
不进 NuGet 依赖图，因此没有携带构建路径的载体。

### 10.7 未覆盖（别把本节当发布放行）

- 只验 **x64**；ARM64、MSIX 打包、代码签名、安装/升级流程均未验；
- 探针固定 `vo=null / ao=null / hwdec=no` ⇒ **GPU 解码、HDR、音频输出设备**仍未验（与 Debug 轮同口径）；
- 全程零窗口：未启动 GUI、未连真实服务器，只做 publish + 进程内解码；
- 33 个依赖仍是本机 MSYS2/FFmpeg 快照，换机需重跑 `collect-lgpl-runtime.ps1`（第 6 节）；
- 未验证「用户替换 DLL」在 publish 布局下的实际生效，那属于 `LGPL-COMPLIANCE.md` §3 的分发约束而非本轮实测；
- 本节不改变第 7 节的结论：**默认仍分发 GPL 构建，RELEASE_BLOCKED 不变**。

参考：`windows/docs/W13-LGPL-ENGINE-PROBE.md`（探针本身）、`windows/docs/LGPL-COMPLIANCE.md`（分发义务）、
`windows/docs/licensing/LICENCE-DECISION.md`（许可判定）、`windows/docs/licensing/W5-LGPL-BUILD.md`（候选构建）。