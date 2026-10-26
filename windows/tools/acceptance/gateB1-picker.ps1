$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class B1K {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr c);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@ -ErrorAction SilentlyContinue
[B1K]::SetProcessDpiAwarenessContext([IntPtr](-4)) | Out-Null
Add-Type -AssemblyName System.Windows.Forms

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Write-Host ('exe time=' + (Get-Item $exe).LastWriteTime + ' sha=' + (Get-FileHash -Algorithm SHA256 $exe).Hash.ToLowerInvariant().Substring(0,16))
$target = 'D:\mh-media\LocalOpen\Chinese 中文 空格.mp4'
Write-Host ('target exists=' + (Test-Path $target))

Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { if ($_.MainWindowHandle -ne 0) { $_.CloseMainWindow() | Out-Null } } catch {} }
Start-Sleep -Seconds 4
Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch {} }
Start-Sleep -Seconds 3
Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 24
$app = Get-Process -Id $proc.Id
$root = [System.Windows.Automation.AutomationElement]::FromHandle($app.MainWindowHandle)
function Find-Id($r, [string]$id) {
  return $r.FindFirst([System.Windows.Automation.TreeScope]::Descendants,
    (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $id)))
}
$fm = Find-Id $root 'menu.file'; $p=$null; $fm.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$p)|Out-Null; $p.Invoke()
Start-Sleep -Seconds 3
$ov = Find-Id $root 'menu.openvideo'
if (-not $ov) { $ov = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty,'打开视频…'))) }
$p2=$null; $ov.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$p2)|Out-Null; $p2.Invoke()
Write-Host '已触发 打开视频…'

# 不只靠 #32770：枚举桌面直接子窗口，用 UIA 层次 + 标题交叉确认
$desktop = [System.Windows.Automation.AutomationElement]::RootElement
$dlg = $null
for ($i = 0; $i -lt 25; $i++) {
  foreach ($w in $desktop.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)) {
    $c = $w.Current
    if ($c.ProcessId -eq $proc.Id -and $c.ClassName -notmatch 'WinUIDesktopWin32WindowClass') { $dlg = $w; break }
    if ($c.ClassName -match 'CabinetWClass|#32770') { $dlg = $w; break }
  }
  if ($dlg) { break }
  Start-Sleep -Milliseconds 600
}
if (-not $dlg) {
  Write-Host '未探测到选择器窗口 → FAILED'
  Get-Content "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue | Select-String 'menu:' | ForEach-Object { Write-Host ('  ' + $_.Line) }
  Write-Host 'DONE'; exit 1
}
Write-Host ('对话框: pid=' + $dlg.Current.ProcessId + ' class=' + $dlg.Current.ClassName + ' name="' + $dlg.Current.Name + '"')

$edit = $null
foreach ($e in $dlg.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
  if ($e.Current.ControlType -eq [System.Windows.Automation.ControlType]::Edit) { $edit = $e; break }
}
if ($edit) {
  $vp=$null
  if ($edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern,[ref]$vp)) {
    $vp.SetValue($target); Write-Host '  已写入路径'
    $edit.SetFocus(); Start-Sleep -Milliseconds 400
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}'); Write-Host '  已回车'
  }
}
Start-Sleep -Seconds 22

Write-Host '--- trace ---'
Get-Content "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue | Select-String 'menu:|playback' | ForEach-Object { Write-Host ('  ' + $_.Line) }
Write-Host '--- 播放器页 ---'
(& pwsh -NoProfile -File ($R + 'windows\tools\acceptance\lib\uia-id.ps1') -Action text 2>&1) | Select-Object -First 6 | ForEach-Object { Write-Host ('  ' + $_) }
Write-Host 'DONE'
