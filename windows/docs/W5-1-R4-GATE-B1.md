# W5.1-R4 Gate B1 实测记录 —— 文件选择器未弹出（确证的产品缺陷）

- 日期：2026-10-15
- 被测程序：`D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe`
  构建于 18:14:44，sha256 前缀 `e1665506cf33d284`
- 素材：`D:\mh-media\LocalOpen\Chinese 中文 空格.mp4`（存在，含中文与空格）

## 结论

**Gate B1 = FAILED（产品缺陷），不是 GUI_UNVERIFIED。**

菜单命令执行成功，但**系统文件选择器没有出现在屏幕上**，播放器也没有接管。
这是产品侧的行为，不是 harness 定位失败 —— 两次独立尝试都无法找到对话框，最后用 UIA 枚举
桌面全部顶层窗口证实：**该窗口根本不存在**。

## 证据链

**1. 菜单命令确实执行了**（持久 startup trace）

```
18:18:59.513 menu:openvideo-invoked
```

**2. 但 trace 中没有后续任何分支**

代码在 `PickMultipleFilesAsync()` 之后有三条出口，每条都会写 trace：

| 分支 | 应有 trace | 实际 |
| --- | --- | --- |
| 用户取消 | `menu:openvideo-cancelled` | **无** |
| 选中文件 | `menu:openvideo-selected count=N` | **无** |
| 抛异常 | `menu:openvideo-failed <类型>` | **无** |

三者皆无 → `PickMultipleFilesAsync()` **既没有返回也没有抛出**。

**3. 桌面上不存在对话框窗口**（UIA 枚举 `RootElement` 的直接子元素）

```
可见顶层窗口数 = 12
  pid=98476  class=WinUIDesktopWin32WindowClass  name="MediaHub"      ← 应用自身，唯一的窗口
  pid=20460  class=wetype.statusbar.window       name="StatusBarWnd"
  pid=33344  class=Q360NetmonClass               name=""
  pid=98084  class=CASCADIA_HOSTING_WINDOW_CLASS name="C:\\Python\\Python313\\python.exe"
  pid=8556   class=Shell_TrayWnd                 name=""
  pid=27144/23692/33188/83756/36004  class=Chrome_WidgetWin_1   （浏览器窗口）
  pid=57592  class=CEF-OSC-WIDGET                name="NVIDIA GeForce Overlay"
  pid=8556   class=Progman                       name="Program Manager"
```

**没有任何 `#32770` 或文件对话框类窗口。**

## 最小复现

```powershell
pwsh -NoProfile -File windows/tools/acceptance/gateB1-open-video.ps1
# -> menu.file -> INVOKE
# -> menu.openvideo -> INVOKE
# -> 未找到选择器窗口（HARNESS_BLOCKED）
#   trace: 只有 menu:openvideo-invoked
```

`gateB1-diagnose.ps1` 额外记录了窗口列表；`.tools/winlist.ps1` 是纯粹的 UIA 枚举，不驱动任何东西。

## 疑因（尚未确证，故不作为结论）

代码用的是 `Windows.Storage.Pickers.FileOpenPicker` + `WinRT.Interop.InitializeWithWindow.Initialize(picker, WindowHandle)`。
R4 任务书第 05 节已预判此风险并给出方向：

> 项目已使用 Windows App SDK 1.8，优先核对并采用 `Microsoft.Windows.Storage.Pickers.FileOpenPicker(WindowId)`
> 及 `PickFileResult.Path`。仅当实际依赖/运行环境迫使使用旧 `Windows.Storage.Pickers` 时，才使用
> HWND + InitializeWithWindow；不要混写两个 API 的对象模型。

本机尝试核对新 API 是否随 WinAppSDK 1.8 提供时**未完成**：首次按错误路径
（`microsoft.windowsappsdk\1.8.260803003`）查找失败，实际包目录是 `microsoft.windowsappsdk.winui\1.8.260803003`。
**因此「新 API 是否可用」目前是 UNKNOWN，不是结论。**

## 本轮已完成的 B1 相关工作（代码侧）

1. 文件菜单加稳定 AutomationId：`menu.file` / `menu.openvideo` / `menu.openfolder` —— 此前只能靠打开菜单再按标签匹配。
2. `OnOpenVideoClick` 增加持久 trace 三条出口（invoked / cancelled / selected / failed）——
   **正是这三条出口的缺席，把「命令没跑」和「命令跑了但 picker 没出来」区分开了。**
3. 取消路径显式保持当前播放会话不被影响。

## 下轮第一条命令

先确证新 API 是否随当前 WinAppSDK 提供：

```powershell
Get-ChildItem "$env:USERPROFILE\.nuget\packages\microsoft.windowsappsdk.winui\1.8.260803003" -Recurse -Include *.winmd |
  ForEach-Object { $b=[IO.File]::ReadAllBytes($_.FullName); $t=[Text.Encoding]::Unicode.GetString($b);
                   if ($t -match 'Microsoft\.Windows\.Storage\.Pickers') { $_.FullName } }
```

若存在 → 按任务书改用 `FileOpenPicker(WindowId)` + `PickFileResult.Path`，**不要混写两套对象模型**；
若不存在 → 保留旧 API，但需解释 `PickMultipleFilesAsync` 为何不返回：优先怀疑
`SuggestedStartLocation = PickerLocationId.VideosLibrary` 在 unpackaged 应用中的行为，以及
`InitializeWithWindow` 收到的 HWND 是否为当前活动窗口。
