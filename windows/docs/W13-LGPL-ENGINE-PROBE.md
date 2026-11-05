# W13 LGPL 引擎无头探针（libmpv 解码等价性）

- 状态：完成（探针 + 断言脚本 + 证据全部落盘，24/24 断言通过）
- 日期：2026-10-24
- 分支：feature/windows-client
- 前置：W5-H 已产出 LGPL 候选 libmpv（windows/engine/lgpl/dist），W6 起 App 仍使用 GPL 基线 dll
- 范围：只新增 windows/src/MediaHub.EngineProbe（独立 Exe）、windows/tools/acceptance/engine/engine-probe.ps1、本文件与证据文件。未改动 windows/native/libmpv、未改动 App/Player 任何一行、未启动 GUI

## 0. 机器可读状态（CI 核对）

下面这段由 `windows/tools/check-licence-surface.ps1` 的第 48 项 `engine-probe-claims` 逐行核对：两个 dll 的身份必须与探针脚本里钉死的常量、`windows/tools/fetch-libmpv.ps1` 的基线摘要、`windows/engine/lgpl/dist/dist-hashes.txt` 的记录一致；本篇第 2 节的交付物表必须与 `windows/src/MediaHub.EngineProbe` 目录一一对应且行数相符；断言条数由脚本结构当场算出（不在循环里的 + 每个 flavor 的 × flavor 数）。**没有跑过 GUI 就是没有跑过 GUI**：探针只解码、不显示、不出声，GUI 播放仍未验证。

<!-- BEGIN engine-probe-status (checked by check-licence-surface.ps1) -->
```text
probe-project: windows/src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj
probe-script: windows/tools/acceptance/engine/engine-probe.ps1
flavour-pair: gpl-baseline, lgpl-candidate
gpl-baseline-bytes: 120989696
gpl-baseline-sha256: 6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9
lgpl-candidate-bytes: 3327274
lgpl-candidate-sha256: 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a
assertion-count: 24
assertion-formula: unlooped-plus-looped-per-flavour
decode-surface: vo-null-and-ao-null
hardware-decode: not-exercised
verdict-scope: decoder-availability-not-gui-playback
evidence-directory: windows/tools/acceptance/evidence/engine/
```
<!-- END engine-probe-status -->

## 1. 为什么要一个无头探针，以及它为什么不能代替 GUI 证据

替换引擎 dll 属于「换掉整个解码内核」，风险不在编译，而在运行时真实解码能力。GUI 验收（W5-GUI-ACCEPTANCE.md 那套 UIA 脚本）能证明「窗口里放出来了」，但它的结论受播放器实现、渲染后端、显示器、音频设备影响，失败时无法区分是 dll 不行还是 App 接线不行；而且 GUI 验收无法在 CI 里稳定复跑。

因此 W13 只回答一个更窄、更可判定的问题：**给定同一个绝对路径的 libmpv-2.dll，用同一段 C API 调用序列，能不能把同一份素材解码推进到指定秒数、并且不产生 error 级日志？** 探针用 NativeLibrary.Load + GetExport + Marshal.GetDelegateForFunctionPointer 按绝对路径动态绑定，所以两个 dll 可以在一台机器、一个进程模型下背靠背对比，不需要替换 windows/native/libmpv 下的任何文件。

必须明说的边界：探针用 vo=null / ao=null，即**只解码、不显示、不出声**。它不能证明 GUI 播放、HDR/色调映射、硬件解码、音频设备输出、字幕渲染——这些仍然只能由 GUI 验收证明。本文件第 5 节的结论必须连同这条限制一起读。

## 2. 交付物

| 文件 | 行数 | 作用 |
| --- | ---: | --- |
| windows/src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj | 20 | net10.0 Exe，零 NuGet、零项目引用，不引用 App/Player |
| windows/src/MediaHub.EngineProbe/Program.cs | 140 | 入口：参数解析、UTF-8 控制台、120 s 自超时看门狗、退出码、报告输出 |
| windows/src/MediaHub.EngineProbe/ProbeOptions.cs | 204 | CLI 契约：--dll/--media/--seconds/--deps/--json/--label/--help 与退出码说明 |
| windows/src/MediaHub.EngineProbe/MpvLibrary.cs | 174 | 按绝对路径加载 dll，三种加载方式依次回退，导出函数解析 |
| windows/src/MediaHub.EngineProbe/MpvApi.cs | 324 | libmpv C API 的 cdecl 委托绑定与结构体读取（UTF-8 手工编组） |
| windows/src/MediaHub.EngineProbe/ProbeRunner.cs | 360 | 一次解码：设选项→初始化→loadfile→事件循环→采样→terminate_destroy |
| windows/src/MediaHub.EngineProbe/ProbeReport.cs | 256 | 报告模型、判定规则、JSON 序列化、人类可读文本 |
| windows/tools/acceptance/engine/engine-probe.ps1 | 273 | 跑两个 flavor、24 条断言、PASS/FAIL 行、RESULT 汇总、证据落盘 |
| windows/docs/W13-LGPL-ENGINE-PROBE.md | — | 动机、身份、实测数字、结论、复跑方式、限制（本文件） |

证据目录：`windows/tools/acceptance/evidence/engine/`（已被根 .gitignore 第 48 行 `windows/tools/acceptance/evidence/` 忽略），内含 engine-probe-gpl-baseline.json/.log、engine-probe-lgpl-candidate.json/.log、engine-probe-summary.txt、engine-probe-assertions.txt。

## 3. 两个 dll 的身份（脚本内钉死，不匹配即 FAIL）

| flavor | 路径 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| gpl-baseline（当前随包） | windows/native/libmpv/libmpv-2.dll | 120,989,696 | 6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9 |
| lgpl-candidate（W5-H 产物） | windows/engine/lgpl/dist/libmpv-2.dll | 3,327,274 | 20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a |

素材：`D:\mh-media\playback-180s.mp4`（H.264 1280x720 + AAC，180 s），目标 10 s，进度下限 9 s。

## 4. 实测数字（2026-10-24 03:35，一次完整运行）

| 观测项 | gpl-baseline | lgpl-candidate |
| --- | --- | --- |
| 加载方式 | native-library（无需额外目录） | native-library+user-dirs（需 FFmpeg 8.1.1 + MSYS2 UCRT64 依赖目录） |
| 依赖目录 | 无 | D:\mh-servers\lgpl-build\prefix\bin; C:\msys64\ucrt64\bin |
| client api | 2.5 (0x00020005) | 2.5 (0x00020005) |
| mpv-version | mpv v0.41.0-1154-gec38361c5 | mpv v0.41.0-UNKNOWN |
| ffmpeg-version | N-127521-g2ec8998c3 | 8.1.1 |
| file-loaded | True | True |
| max time-pos | 10.000 s | 10.080 s |
| 视频 | H.264 1280x720 | H.264 1280x720 |
| 音频 | AAC | AAC |
| duration | 180.000 s | 180.000 s |
| hwdec-current | no（探针固定 hwdec=no） | no |
| error 级日志 | 0 | 0 |
| warn 级日志 | 2（均为 demuxer 的 UDTA parsing failed retrying raw） | 2（同一行） |
| 耗时 | 10,030 ms | 10,123 ms |
| 退出码 / verdict | 0 / PASS | 0 / PASS |
| 选项告警 | 无 | osc=no、ytdl=no「option not found」（该构建不编译这两个选项，非致命，已记录） |

enabled-features（来源均为运行期 libmpv 日志的 `[cplayer] List of enabled features:` 行，脚本断言里写明 source=log；这条日志在 mpv_initialize 期间发出，探针把 mpv_request_log_messages 提前到 mpv_create 之后、initialize 之前才抓得到）：

- gpl-baseline：60 个 feature，含 `gpl`（另含 amf、cuda-hwaccel、cuda-interop、vaapi、vulkan、javascript、luajit、libbluray、dvdnav、dvda、libarchive、libcurl、zimg、vapoursynth、openal、sixel、sdl2-gamepad、jpeg、uchardet、rubberband、egl-angle、ffnvcodec、direct3d、debug、subrandr、win32-smtc、zimg-st428、vk-khr-display、vaapi-win32、egl-angle-win32、rubberband-3、d3d11 等）
- lgpl-candidate：28 个 feature，**不含** `gpl`；相对基线少的 32 个 token 是：amf、cuda-hwaccel、cuda-interop、debug、direct3d、dvda、dvdnav、egl-angle、egl-angle-win32、ffnvcodec、gpl、javascript、jpeg、libarchive、libbluray、libcurl、luajit、openal、rubberband、rubberband-3、sdl2-gamepad、sixel、subrandr、uchardet、vaapi、vaapi-win32、vapoursynth、vk-khr-display、vulkan、win32-smtc、zimg、zimg-st428
- 两者共有的关键 feature：ffmpeg、d3d11、d3d-hwaccel、d3d9-hwaccel、gl、gl-win32、gl-dxinterop、libass、libavdevice、libplacebo、shaderc、spirv-cross、wasapi、win32-*、iconv、lcms2、glob、vector、zlib

## 5. 结论

1. **解码层面：LGPL 候选可以顶替基线。** 同一素材、同一 API 序列、同一台机器，LGPL 候选 file-loaded 成功，time-pos 推进到 10.080 s（基线 10.000 s），0 条 error 级日志，client API 主版本一致（2.5），耗时差约 1%（10.12 s vs 10.03 s）。就「能不能解码 H.264 + AAC 的 MP4 并推进播放」而言，两者等价。
2. **许可层面：候选确实不含 gpl token**（基线含），且该结论来自运行期日志而非静态字符串猜测。
3. **能力层面：候选是窄口径构建。** 它比基线少 32 个 feature：没有 cuda-hwaccel / cuda-interop / amf / vaapi / vulkan（即除 D3D11/D3D9 之外的硬件解码路径都没有）、没有 javascript/luajit（不能跑 ytdl 类脚本）、没有 libbluray/dvdnav（无蓝光/DVD 菜单）、没有 libcurl/libarchive/zimg/vapoursynth/openal/sixel 等。d3d11-hwaccel 仍在，但本次探针 hwdec=no，**没有验证硬件解码**。
4. **部署层面：候选不是自包含的。** 基线 dll 单文件即可加载；候选必须能找到 FFmpeg 8.1.1 与 MSYS2 UCRT64 的一整套 dll（本次靠 AddDllDirectory 提供）。若要随包，必须先完成依赖闭包与清单核对（W5-H 的 dist/runtime-bundle-manifest.txt 与 runtime-dependencies*.txt 是这条工作的输入），并且 App 的加载方式要相应调整。
5. **本条结论不能外推到 GUI 播放。** 探针证明的是「解码器可用」，不是「App 里能播」：GUI 播放、HDR/色调映射、硬件解码、音频设备输出、字幕渲染、seek 与音画同步都没有在本轮被验证。

## 6. 复跑方式

```powershell
# 只构建探针（不触碰 App）
dotnet build windows/src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj -c Debug -nologo

# 跑两个 flavor 的全部断言（约 25 秒），证据写入 windows/tools/acceptance/evidence/engine/
pwsh -NoProfile -File windows/tools/acceptance/engine/engine-probe.ps1

# 单 flavor 手工探测
windows/src/MediaHub.EngineProbe/bin/Debug/net10.0/MediaHub.EngineProbe.exe \
  --dll D:\deepseek_test\MediaHub_Win\windows\engine\lgpl\dist\libmpv-2.dll \
  --media D:\mh-media\playback-180s.mp4 --seconds 10 \
  --deps "D:\mh-servers\lgpl-build\prefix\bin;C:\msys64\ucrt64\bin" --json
```

探针退出码：0=loaded 且进度达标，1=用法错误，2=dll 加载失败，3=加载/解码失败或进度不达标，4=自超时（120 s）。

## 7. 断言清单（24 条，本次全过）

| 组 | 断言 |
| --- | --- |
| environment | probe-exe / media / gpl-dll / lgpl-dll 存在 |
| identity | 两个 dll 的 sha256 与字节数与钉死值一致 |
| load | 两个 flavor 都能加载（记录实际加载方式） |
| api | 两者 client api 主版本相同 |
| decode/<flavor> | file-loaded、progress（maxTimePos ≥ 9 s）、no-error-logs、log-capture、decoded-video（宽高与 codec 非空）、exit-code |
| features | source-stated（两个 flavor 的 feature 来源都被写明，本次均为 log） |
| licence | 基线含 gpl token、候选不含 gpl token |

## 8. 已知限制

1. vo=null / ao=null：无渲染、无音频输出，因此不覆盖色彩管理、HDR、音频设备、音量/直通。
2. hwdec=no：硬件解码路径（D3D11VA / CUDA / VAAPI）未被验证；候选本来也没有 cuda/vaapi。
3. 无字幕渲染：libass 存在但探针不做字幕合成，字幕样式/位置/字体回退未验证。
4. 单一素材：只有 H.264 8-bit 4:2:0 + AAC 的 MP4；HEVC、AV1、10-bit、HDR、多音轨/多字幕、Dolby/DTS 直通都没测。
5. 单机单次：每个 flavor 只跑一次 10 秒，不做长时间稳定性、seek、变速、chapter 跳转。
6. 依赖目录是环境事实（D:\mh-servers\lgpl-build\prefix\bin、C:\msys64\ucrt64\bin），脚本把它们作为参数默认值写死；换机器需显式传入或安装同版本依赖。
7. 探针不评估许可合规本身（源码提供、NOTICE、构建脚本可复现性见 LGPL-COMPLIANCE.md 与 windows/engine/lgpl/*）。
8. 候选的 mpv-version 是 `mpv v0.41.0-UNKNOWN`（构建时没有 git 描述），因此无法用版本串判断它就是同一 commit 的构建——身份判定只用 sha256 + feature 集。
