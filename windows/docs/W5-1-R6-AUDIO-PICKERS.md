# W5.1 R6 — 音频播放、文件选择器迁移与播放器面板缺陷

日期：2026-10-22 · 关系：接在 [W5-1-R5-GATE-B1.md](W5-1-R5-GATE-B1.md) 之后，属于 W5.1「逐项 GUI 验收」的一轮。

## 1. 本轮处理的用户报告

| 报告 | 实际根因 | 处理 |
| --- | --- | --- |
| 音轨面板「关不掉」 | `OnAudioPanelClick` / `OnSubtitlePanelClick` 只调 `ShowPanel(...)`，没有 toggle；全页唯一能收面板的路径是信息按钮的 else 分支 | 改为 toggle，引入 `PanelKind` 单一状态（`None`/`Info`/`Audio`/`Subtitle`） |
| 面板「一直闪」 | `ApplySnapshot`（10 Hz）比较 `snapshot.SubtitleTracks.Count != SubtitleList.Items.Count`，而字幕列表含「关闭字幕」哨兵行，两者恒差 1 → 每帧拆掉重建 | 改为比较 `_renderedAudioCount` / `_renderedSubtitleCount` |
| `.aac` 等音频打不开 | `SupportedVideoExtensions` 是唯一白名单，文件选择器与命令行都只认视频扩展名 | 新增 `SupportedAudioExtensions` / `SupportedMediaExtensions` / `IsSupportedMediaFile`；**音频不进 `VideoExtensions`**（那套还驱动 WebDAV 媒体分类），只走显式用户意图 |
| 文件→打开视频 挂死（R5 遗留 Gate B1） | 旧 `Windows.Storage.Pickers` + `InitializeWithWindow` 在 unpackaged WinUI 3 窗口上既不返回也不抛，`PickMultipleFilesAsync()` 永不完成，桌面无任何对话框 | 迁到 WinAppSDK 1.8 的 `Microsoft.Windows.Storage.Pickers`（`FileOpenPicker(AppWindow.Id)`），选择器调用全部收敛到 `Services/FilePickerService.cs` |
| （本轮自动化发现）字幕「已加载 / 加载失败」提示看不见 | `ApplySnapshot` 每 100 ms 无条件重写 `SubtitleHint.Text`，一次性结果只活一帧，失败原因更是瞬间消失 | 增设 8 秒 `SubtitleNotice` 优先级层：`ComposeSubtitleHint()` 在有效期内保留一次性结果 |

## 2. 状态表

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 音频文件真实播放（命令行 `.aac`） | **EXECUTED_PASS** | `windows/tools/acceptance/r6-audio.ps1` 12/12 |
| 音轨面板可开可关 | **EXECUTED_PASS** | 同上：开=存在且不在屏外；再点=不在屏内 |
| 字幕列表不再每帧重建 | **EXECUTED_PASS** | 1.2 s 内 6 次采样 RuntimeId 恒为 `n=1` |
| 文件 → 打开视频…（`.aac`） | **EXECUTED_PASS** | `r6-pickers.ps1` P1：`title=Sine Tone`、`duration=1:05`、`technical=aac` |
| 文件 → 打开文件夹… | **EXECUTED_PASS** | P2：`open-folder:selected` + settings 落盘 + 本地库页面出现目录行 `D:\mh-media\Audio` |
| 播放器 → 加载外挂字幕… | **EXECUTED_PASS** | P3：轨列表出现 `未知语言 · subrip · 外挂 · playback-180s.srt`，轨数 1 → 2，提示保留文件名 |
| 多选打开（多文件队列） | **NOT_IMPLEMENTED** | 产品尚未实现；本轮未做任何声明 |
| 取消路径（用户按取消） | **IMPLEMENTED_UNVERIFIED** | trace 出口已埋 `*-cancelled`，GUI 未断言 |

合计：`r6-audio.ps1` **12/12 PASS**、`r6-pickers.ps1` **18/18 PASS**；单元测试 **296 passed / 0 failed**；App 构建 0 警告 0 错误。

## 3. 音频播放实测细节

夹具 `D:\mh-media\Audio\Sine Tone.aac`（978,907 字节，65.47 s，`format_name=aac`），以命令行 `MediaHub.exe "...\Sine Tone.aac"` 打开：

- `cli:opening` 出现在启动 trace 里 —— 命令行入口确实被走到，不是回退到空窗口；
- `TitleText=Sine Tone`、时长 `1:05`、技术信息 `aac`；
- 时钟 `0:03 → 0:08` 连续推进；
- **真实出声**：`Get-MhAudioPeak` 在播放中采样得到 `PEAK=0.088583`，与 W3 基线 `0.0886` 一致（同一正弦夹具），`SESSIONS=8 STATE=1`；
- 音轨面板：点击后 `panel.audio` 进 UIA 树且 `IsOffscreen=False`；再次点击后 `found=True offscreen=True`——**先证存在、再证消失**。

## 4. 选择器实测细节

`r6-pickers.ps1` 用真实 `MediaHub.exe` + 真实 shell 对话框跑三条路径，每条都断言到**应用侧结果**而不只是「对话框关掉了」：

1. **P1 打开视频**：`menu:openvideo-selected` → 播放器 `Sine Tone` / `1:05` / `aac`。
2. **P2 打开文件夹**：`open-folder:selected` → `%LOCALAPPDATA%\MediaHub\settings.json` 出现 `D:\\mh-media\\Audio` → 本地库页面列出该目录。
3. **P3 加载外挂字幕**：轨数 `1 → 2`，新轨名字含 `playback-180s.srt`，面板提示同时写着 `已加载：playback-180s.srt 共 1 条字幕轨道。`

## 5. 自动化判据缺陷（与产品缺陷同等记录）

这四条是本轮真正花时间的部分，写下来避免下轮重犯：

1. **shell 文件对话框不能用 UIA 驱动**：对 `#32770` 里的 shell 视图调 `AutomationElement.FromHandle` / `ValuePattern.SetValue` 一律 `Operation timed out. (0x80131505)`；`Descendants` 里第一个 `Edit` 是壳层属性元素（`ClassName=UIProperty`、`AutomationId=System.ItemNameDisplay`），不是文件名框。对话框驱动改走 Win32：`windows/tools/acceptance/lib/MhPicker.psm1`（`EnumChildWindows` + `WM_SETTEXT` + `BM_CLICK`）。
2. **不能按「有 Edit 就算对话框」识别窗口**：第一次这么写，把路径直接敲进了另一个应用的浏览器窗口。识别必须按「本进程 pid + 类名 `#32770`」。
3. **「关闭 / 消失」类断言必须先证明它存在过**：旧版「第二次点击关闭面板」曾因元素从未进 UIA 树而假通过。
4. **不要用对话框控件回显做判据**：`ComboBox` 内的 `Edit` 用 `GetWindowTextW` 读回空串（文本在 `ComboBoxEx32` 父窗口），`Set-MhPickerPath` 的 `ok=False` 与「路径真的生效」并存——判据只能是应用侧结果（播放了什么、settings 写了什么、轨道名是什么）。

## 6. 复现

```powershell
# 构建（App 必须重定向输出目录，bin 下有僵尸进程持有锁）
.tools\stop-app.ps1
dotnet build windows\src\MediaHub.App\MediaHub.App.csproj -c Debug -p:Platform=x64 -p:MediaHubBinRoot=D:\mh-out\

# 音频 12 项
pwsh -NoProfile -File windows\tools\acceptance\r6-audio.ps1

# 三条选择器路径 18 项
pwsh -NoProfile -File windows\tools\acceptance\r6-pickers.ps1
```

证据目录：`windows/tools/acceptance/evidence/r6-audio/`、`windows/tools/acceptance/evidence/r6-pickers/`（截图 + `results.json`，按仓库既有约定不入库）。

## 7. 未做 / 下一轮

- 多选打开与多文件队列：**NOT_IMPLEMENTED**，产品侧尚无入口。
- 选择器取消路径、`PickFilesAsync`（多选）调用点：**IMPLEMENTED_UNVERIFIED**。
- 高 DPI / 深色主题下对话框与面板的视觉验收未做。
- 归入路线图 W4 的代理一致性与缓存项未动。
