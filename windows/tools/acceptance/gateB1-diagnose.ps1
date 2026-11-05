$ErrorActionPreference='Continue'
$R = 'D:\deepseek_test\MediaHub_Win\'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class WinEnum {
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  public static List<string> List() {
    var res = new List<string>();
    EnumWindows((h, p) => {
      if (!IsWindowVisible(h)) return true;
      var t = new StringBuilder(256); GetWindowTextW(h, t, 256);
      var c = new StringBuilder(256); GetClassNameW(h, c, 256);
      uint pid; GetWindowThreadProcessId(h, out pid);
      res.Add(pid + "|" + c.ToString() + "|" + t.ToString());
      return true;
    }, IntPtr.Zero);
    return res;
  }
}
"@ -ErrorAction SilentlyContinue

$exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { if ($_.MainWindowHandle -ne 0) { $_.CloseMainWindow() | Out-Null } } catch {} }
Start-Sleep -Seconds 5
Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch {} }
Start-Sleep -Seconds 3
Remove-Item "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $exe -PassThru
Start-Sleep -Seconds 24
Write-Host ('app pid=' + $proc.Id)

$winsBefore = [WinEnum]::List()
$app = Get-Process -Id $proc.Id
$root = [System.Windows.Automation.AutomationElement]::FromHandle($app.MainWindowHandle)
function Find-Id($r, [string]$id) {
  $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
  return $r.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}
$fm = Find-Id $root 'menu.file'
$p = $null; $fm.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p) | Out-Null; $p.Invoke()
Start-Sleep -Seconds 3
$ov = Find-Id $root 'menu.openvideo'
if (-not $ov) { $ov = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, '打开视频…'))) }
$p2 = $null; $ov.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p2) | Out-Null; $p2.Invoke()
Write-Host '已触发 打开视频…'
Start-Sleep -Seconds 7

Write-Host '--- 新增顶层窗口（诊断，最小复现证据）---'
$winsAfter = [WinEnum]::List()
$new = $winsAfter | Where-Object { $winsBefore -notcontains $_ }
if ($new) { $new | ForEach-Object { Write-Host ('  ' + $_) } } else { Write-Host '  （无新增顶层窗口）' }

Write-Host '--- 全部可见顶层窗口（前 25）---'
$winsAfter | Select-Object -First 25 | ForEach-Object { Write-Host ('  ' + $_) }

Write-Host '--- app trace ---'
Get-Content "$env:LOCALAPPDATA\MediaHub\startup.log" -ErrorAction SilentlyContinue | Select-String -Pattern 'menu:|open-video|playback' | ForEach-Object { Write-Host ('  ' + $_.Line) }
Write-Host 'DONE'
