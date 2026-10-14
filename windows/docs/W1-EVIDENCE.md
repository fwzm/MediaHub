# W1 执行证据（2026-10-14）

本文件记录**实际执行**得到的结论。凡未执行的一律写 NOT_RUN / DEVICE_UNVERIFIED，
不得用"代码完成""构建通过"替代。

## 环境

| 项 | 值 |
| --- | --- |
| OS | Windows 10.0.26300（win-x64） |
| .NET SDK | 10.0.113（另有 9.0.313） |
| Windows SDK | 10.0.26100.0 |
| Visual Studio | 2022 存在（**未使用任何 workload**） |
| 显示 | 3048×2032 物理，**150% 缩放** |
| GPU 型号 | 未单独采集 |
| 硬件解码 | `hwdec=d3d11va` 生效（引擎上报） |
| 音频 | WASAPI 共享模式（引擎日志 `ao/wasapi` 初始化与释放正常） |

## 1. 工具链可行性（EXECUTED_PASS）

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 最小 WinUI 3 探针构建 | `dotnet build -c Debug -p:Platform=x64` | 成功，0 error 0 warning |
| 探针运行（默认部署） | 直接运行 exe | **失败**：缺少 Windows App Runtime 1.8 |
| 探针运行（self-contained） | 同上，改 `WindowsAppSDKSelfContained=true` | **成功**，窗口渲染并截图确认 |

## 2. 引擎集成（EXECUTED_PASS）

`MpvProbe`（`windows/tools/MpvProbe`）是自动化集成测试，
对真实媒体文件跑 加载 → 播放 → 暂停 → 拖动，并断言引擎可观测状态。

**样本**：由 `ffmpeg` 生成，1280×720 H.264 @25fps + AAC 2ch 48kHz，时长 12.000 s，另附外挂 SRT。

```
$ MpvProbe.exe <sample_av.mp4> 5
engine: libmpv client API 0.131077
  t= 0.8s state=Loading  pos= 0.000 dur= 0.000 size=1280x720 codec=H.264 ... hwdec=d3d11va vo=gpu-next ctx=d3d11 evloop=True
  ...
RESULT duration=12.000s reached=4.360s seeked=6.000s audioTracks=1 subTracks=1 snapshots=81
MPV_PROBE PASS
```

断言覆盖：事件循环存活、时长 > 0、播放位置推进、视频宽高、视频编码、`hwdec-current` 有值、
暂停后位置不再推进且状态为 Paused、seek 后位置移动、音轨被枚举。

### 2.1 期间定位并修复的两个引擎缺陷（均属"能播但 UI 不动"的静默失败）

**缺陷 A —— `mpv_observe_property` 的格式语义被误用**

`MPV_FORMAT_NONE` 在 `mpv_observe_property` 中表示"只通知、不给值"（`data` 为空），
与 `mpv_set_property` 中"用属性原生格式"的含义**不同**。原实现照搬后者语义，
导致所有属性事件被静默丢弃。

- 现象：画面正常播放，但 `pos`/`dur` 恒为 0、状态恒为 Loading。
- 定位：`MpvProbe` 逐秒打印引擎可观测状态，同时打开 mpv 自身日志——
  日志显示 `Set property: pause="yes"` 与 `seek done` 均已生效，说明命令到达但事件未回传。
- 修复：每个被观察属性显式声明其原生格式。

**缺陷 B —— 事件线程回调 mpv API**

libmpv 契约禁止在处理事件时回调其 API。原实现在属性变更回调里直接读属性、并再次发起命令。

- 修复：事件线程只解析事件自带载荷、落标志位；所有属性读取与命令下发移交给独立的发布计时器（10 Hz）。

### 2.2 另一个被启动期追踪捕获的缺陷

日志脱敏器的多条正则**丢失了转义序列**（生成了非法的字符类），
导致首条日志即抛 `RegexParseException`、应用在窗口出现前中止。

- 定位：`StartupTrace` 逐阶段写盘——无控制台的 WinUI 进程若在窗口前失败，完全无可见症状。
- 修复：重写脱敏器，并由本轮 `RedactionTests` 固定。

## 3. 端到端播放（EXECUTED_PASS）

真实运行 `MediaHub.exe <video>`（该入口同时也是文件关联"打开方式"的入口）：

- 窗口出现、播放页加载、标题与路径正确。
- **视频画面在窗口内正确合成并随时间推进**（截图为播放中的一帧，非静态首帧）。
- 底部控制栏显示 **`0:11 / 0:12`**（引擎上报的位置/时长）与
  **`1280×720 · H.264 / AVC · hwdec=d3d11va · bt.1886 · aac · 25fps`**。
- 控制栏自动隐藏后视频矩形扩展为整个窗口（沉浸），布局追踪确认
  `video=(0,0) 2032x1284 topVis=Collapsed bottomVis=Collapsed`。

### 3.1 排查过程中的一个环境陷阱（对后续自动化验证有普遍意义）

初次截图"看不到底部控制栏"，一度被怀疑是布局或渲染缺陷。实际原因是
**本机为 150% 缩放，而截图脚本进程是 DPI-unaware**，`CopyFromScreen` 只取到真实帧缓冲的
左上 2032×1355，而窗口物理尺寸是 3048×2032。

- 交叉验证：UI Automation 枚举元素包围盒（`Button` 位于物理 rect y≈1840–1959，`off=False`），
  与 XAML 布局追踪（`bottomBar=88`、`bottomVis=Visible`）一致，从而排除布局缺陷。
- 修复：截图脚本在抓屏前调用 `SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)`。

> 结论：**在 150% DPI 机器上，任何 DPI-unaware 的截屏或坐标比对都会给出假阴性。**
> 该修复已写入自动化脚本，避免后续重复误判。

## 4. 构建与测试（EXECUTED_PASS）

| 命令 | 结果 |
| --- | --- |
| `dotnet build src/MediaHub.App -c Debug -p:Platform=x64` | 成功，0 error |
| `dotnet test tests/MediaHub.Tests` | **99 passed / 0 failed / 0 skipped**（295 ms） |
| `MpvProbe <file> 5` | MPV_PROBE PASS |

测试首轮为 **94 passed / 3 failed**，三条失败均已定位：

1. 字幕语言后缀把 `zh-Hans` 截成 `zh`（**实现缺陷**，已修复：`-` 属于 BCP-47 标签）；
2. 同一条用例传参未遵守方法契约（**测试缺陷**，已修正）；
3. "同 origin 保留凭据"用例实际用了两个端口（**测试缺陷**，已改为单监听器自跳转）。

## 5. Windows CI（EXECUTED_PASS）

工作流 `.github/workflows/windows-ci.yml`，以 `paths: windows/**` 限定，不影响 Android 流水线。

最终一次运行（`7203150`，run `38507025950`）全部步骤 success：

| 步骤 | 结果 |
| --- | --- |
| restore / build（Debug，全解决方案） | success |
| 单元与契约测试 | `tests total=99 failed=0` |
| TRX 健全性检查 | success（本步骤专门用于拦截"零测试也算通过"） |
| **播放引擎集成测试（真实播放）** | `fixture: 26985 bytes` → `RESULT duration=4.000s reached=3.100s seeked=1.800s audioTracks=1` → `MPV_PROBE PASS` |
| 便携版发布 | `portable output size: 331.6 MB` |
| 发布产物完整性检查 | success（exe + libmpv + 三个应用程序集齐全） |

### 5.1 CI 演进过程中的两次自我纠正（值得记录）

**第一次**：首版探针步骤失败。原因是探针把 usage 写到 stderr，而 runner 的
`$ErrorActionPreference = 'Stop'` 会把原生 stderr 重定向变成终止性错误。

**第二次（更重要）**：修复后所有步骤变绿，但**检查日志发现探针实际走了降级分支**——
hosted runner 没有 `ffmpeg`，因此**真实播放从未被执行**，步骤却报告成功。
这种"绿但没测到该测的东西"正是本仓库证据纪律要防的失败模式，因此**没有保留绿灯**，
而是改为：提交 27 KB 媒体夹具（`windows/tests/assets/sample-av.mp4`），
使该步骤完全自足（不依赖 ffmpeg 与网络）。

**CI 中未验证的部分**（步骤自身会打印）：
GPU 渲染、HDR、音频输出设备。CI 以 `vo=null` / `hwdec=no` / `ao=null` 运行，
只验证解码路径、时钟与传输控制，**不验证呈现与音频**。

## 6. 未执行 / 未验证（NOT_RUN / DEVICE_UNVERIFIED）

| 项 | 状态 |
| --- | --- |
| Emby / Jellyfin 真服端到端（登录、浏览、播放、进度回写） | **DEVICE_UNVERIFIED** |
| WebDAV / Plex / SMB | **NOT_IMPLEMENTED** |
| 全屏切换的 UI 实测（代码路径已实现） | **NOT_RUN**（未截图确认） |
| 控制栏按钮逐一点击实测 | **PARTIAL**（键盘与引擎侧已通过引擎测试） |
| 实际听到声音 | **NOT_VERIFIED_BY_LISTENING**（引擎已枚举 AAC 2ch 并初始化 WASAPI） |
| HDR10 / HLG / 杜比视界 | **NOT_RUN** |
| 多显卡选择、设备丢失恢复 | **NOT_RUN** |
| WASAPI 独占、HDMI 直通、多声道 | **NOT_IMPLEMENTED** |
| MSIX 打包、代码签名、干净机安装 | **NOT_RUN** |
| Windows CI | **EXECUTED_PASS**（见第 5 节；CI 内的真实播放已于 §5.1 修正后生效） |
| Intel / AMD / NVIDIA 兼容矩阵 | **NOT_RUN**（仅本机单卡） |