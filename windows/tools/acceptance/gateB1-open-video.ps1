$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class B1Input {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr c);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@ -ErrorAction SilentlyContinue
[B1Input]::SetProcessDpiAwarenessContext([IntPtr](-4)) | Out-Null

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Write-Host ('exe time=' + (Get-Item $exe).LastWriteTime + ' sha=' + (Get-FileHash -Algorithm SHA256 $exe).Hash.ToLowerInvariant().Substring(0,16))

# 造一个"中文 + 空格"的本地素材，用来同时验证路径边界
$src = Get-ChildItem 'D:\deepseek_test\MediaHub_Win\.tools\media' -Filter *.mp4 -ErrorAction SilentlyContinue | Select-Object -First 1
$dir = 'D:\mh-media\LocalOpen'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$target = Join-Path $dir 'Chinese 中文 空格.mp4'
if ($src -and -not (Test-Path $target)) { Copy-Item $src.FullName $target -Force }
Write-Host ('target = ' + $target + '  exists=' + (Test-Path $target))

Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { if ($_.MainWindowHandle -ne 0) { $_.CloseMainWindow() | Out-Null } } catch {} }
Start-Sleep -Seconds 5
Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch {} }
Start-Sleep -Seconds 3

Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 24
Write-Host ('app pid=' + $proc.Id)

# 找 MediaHub 主窗口
$app = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if (-not $app -or $app.MainWindowHandle -eq 0) { Write-Host 'ABORT: no main window'; exit 1 }
$root = [System.Windows.Automation.AutomationElement]::FromHandle($app.MainWindowHandle)

function Find-Id($r, [string]$id) {
  $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
  return $r.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}
function Activate($e) {
  $p = $null
  if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p)) { $p.Invoke(); return 'INVOKE' }
  if ($e.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$p)) { $p.Expand(); return 'EXPAND' }
  return 'NONE'
}

Write-Host '--- 1) 打开文件菜单 ---'
$fileMenu = Find-Id $root 'menu.file'
if (-not $fileMenu) { Write-Host '  menu.file NOT_FOUND'; exit 2 }
Write-Host ('  menu.file -> ' + (Activate $fileMenu))
Start-Sleep -Seconds 2

Write-Host '--- 2) 点击「打开视频…」 ---'
$openVideo = Find-Id $root 'menu.openvideo'
if (-not $openVideo) {
  # 弹出菜单可能不在主窗口的 UIA 子树里，退化为按名字在桌面根找
  $desk = [System.Windows.Automation.AutomationElement]::RootElement
  $nameCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, '打开视频…')
  $openVideo = $desk.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $nameCond)
}
if (-not $openVideo) { Write-Host '  menu.openvideo NOT_FOUND'; exit 2 }
Write-Host ('  menu.openvideo -> ' + (Activate $openVideo))
Start-Sleep -Seconds 5

Write-Host '--- 3) 查找原生文件选择器窗口 ---'
$desktop = [System.Windows.Automation.AutomationElement]::RootElement
$dlg = $null
for ($i = 0; $i -lt 20; $i++) {
  $wins = $desktop.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
  foreach ($w in $wins) {
    $n = $w.Current.Name
    $cn = $w.Current.ClassName
    if ($cn -match 'CabinetWClass|#32770' -or $n -match '打开|Open') { $dlg = $w; break }
  }
  if ($dlg) { break }
  Start-Sleep -Milliseconds 700
}
if (-not $dlg) { Write-Host '  未找到选择器窗口（HARNESS_BLOCKED）'; Write-Host 'DONE'; exit 3 }
Write-Host ('  对话框: name="' + $dlg.Current.Name + '" class=' + $dlg.Current.ClassName)

Write-Host '--- 4) 在文件名框输入路径并回车 ---'
$edit = $null
$all = $dlg.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($e in $all) {
  if ($e.Current.ControlType -eq [System.Windows.Automation.ControlType]::Edit) { $edit = $e; break }
}
if (-not $edit) { Write-Host '  未找到文件名输入框'; Write-Host 'DONE'; exit 3 }
$vp = $null
if ($edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$vp)) {
  $vp.SetValue($target)
  Write-Host '  已写入路径'
  $edit.SetFocus()
  Start-Sleep -Milliseconds 500
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
  Write-Host '  已回车'
} else { Write-Host '  输入框无 ValuePattern'; Write-Host 'DONE'; exit 3 }

Start-Sleep -Seconds 20

Write-Host '--- 5) 应用 trace ---'
Get-Content "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue | Select-String -Pattern 'menu:|playback' | ForEach-Object { Write-Host ('  ' + $_.Line) }

Write-Host '--- 6) 播放器页 ---'
@'
'@ | Out-Null
pwsh -NoProfile -File ($R + 'windows\tools\acceptance\lib\uia-id.ps1') -Action text 2>&1 | Select-Object -First 6 | ForEach-Object { Write-Host ('  ' + $_) }
Write-Host 'DONE'
