# W5.1-R5 Gate B1 进展记录 —— 新 Picker 已被创建，完整选中仍未验证

- 日期：2026-10-15
- 被测程序：构建于 19:10:49，sha256 前缀 `d67cfdff3a709a8a`

## 1. 上一轮结论的一处更正

R4 报告把「`Microsoft.Windows.Storage.Pickers` 是否随 WinAppSDK 1.8 提供」标为 **UNKNOWN**，
原因是按 `microsoft.windowsappsdk\1.8.260803003` 查包未命中。

**该结论已更正：API 确实随 1.8 提供，我当时的包路径是错的。**

实际位置（`microsoft.windowsappsdk.foundation` 包，注意该包是独立拆分的，不在 `microsoft.windowsappsdk` 之下）：

- `...\microsoft.windowsappsdk.foundation\1.8.260803002\lib\net6.0-windows10.0.17763.0\Microsoft.Windows.Storage.Pickers.Projection.dll`
- `...\microsoft.windowsappsdk.foundation\1.8.260803002\metadata\Microsoft.Windows.Storage.Pickers.winmd`

## 2. 实现已迁移到桌面 Picker，并编译通过

`OnOpenVideoClick` 从旧 API 改为：

```csharp
var picker = new Microsoft.Windows.Storage.Pickers.FileOpenPicker(AppWindow.Id);
picker.SuggestedStartLocation = ...PickerLocationId.VideosLibrary;
foreach (string extension in DroppedMedia.SupportedExtensions) picker.FileTypeFilter.Add(extension);
PickFileResult? picked = await picker.PickSingleFileAsync();
if (picked is null || string.IsNullOrEmpty(picked.Path)) { /* 取消 */ }
OpenPaths(new[] { picked.Path });
```

**两套对象模型没有混写**：新 API 取 `WindowId`、返回 `PickFileResult.Path`，不再涉及
`StorageFile` 或 `InitializeWithWindow`。

方案编译 **0 错误 0 警告** —— 证明该 API 在本项目的实际引用与 TFM 下确实可见可用，
不是仅凭文档推断。

## 3. 行为已改变（这是本轮的关键实测差异）

| 轮次 | trace | 含义 |
| --- | --- | --- |
| R4（旧 API） | 只有 `menu:openvideo-invoked` | picker **从未被创建** |
| R5（新 API） | `menu:openvideo-invoked` + **`menu:openvideo-picker-created`** | picker **确实被创建** |

`menu:openvideo-picker-created` 是新加的事件标记，它出现在 trace 里说明执行流**越过了创建点** ——
旧 API 从未做到这一点。（同一轮 harness 还探测到 `class=SysShadow` 窗口，那是对话框的投影阴影类，
与「对话框被创建」一致。）

## 4. 仍未验证的部分（不得据此宣告 PASS）

**未能完成「在真实对话框中选中文件 → 播放器接管 → 时间轴推进」这一段。**

harness 侧的具体障碍已定位：文件菜单项 `menu.openvideo` **只在「文件」菜单展开后才存在于 UIA 树中**。
脚本有时点开菜单（能取到该项），有时没有（取到 `Null`，那一轮的 trace 因此完全为空）。
两次运行结果不同，正是这个时序造成的，而不是产品行为不稳定。

因此 **Gate B1 = GUI_UNVERIFIED**（不是 FAILED，也不是 PASS）：
- 症状已从「命令执行但 picker 未创建」变为「picker 已创建，但未验证到选中与播放」
- 取消路径、错误路径、中文/空格路径均**未验证**

## 5. 下轮第一条命令

把「展开文件菜单 → 断言 `menu.openvideo` 可获取 → 再点击」做成显式两步并各自断言，
消除时序依赖；随后用 UIA 层次（而非 `#32770` 类名）定位 WinAppSDK 桌面 Picker 对话框 ——
该对话框由 `Microsoft.WindowsAppRuntime` 承载，类名与传统 `IFileDialog` 不同，
应枚举 `RootElement` 子窗口并按 **进程归属 + ControlType + 内部 Edit 控件** 三重条件确认。

脚本：`windows/tools/acceptance/gateB1-picker.ps1`（本轮已入库）。
