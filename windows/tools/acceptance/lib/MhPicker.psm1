# 文件对话框驱动的共用实现。全部走 Win32，不遍历对话框的 UIA 子树：
# 实测对 shell 视图调 UIA FromHandle/FindAll 会 Operation timed out (0x80131505)，
# 而且 Descendants 里的第一个 Edit 是壳层的属性元素（UIProperty / System.ItemNameDisplay），不是文件名输入框。
Set-StrictMode -Version Latest

if (-not ('MhPickerNative' -as [type])) {
Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class MhPickerNative {
  delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr h, EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowTextLengthW(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr SendMessageW(IntPtr h, uint msg, IntPtr wp, string lp);
  [DllImport("user32.dll")] static extern IntPtr SendMessageW(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);

  public static string ClassOf(IntPtr h) { var sb = new StringBuilder(256); GetClassNameW(h, sb, 256); return sb.ToString(); }
  public static string TextOf(IntPtr h) { var sb = new StringBuilder(1024); GetWindowTextW(h, sb, 1024); return sb.ToString(); }

  public static List<IntPtr> TopLevel(uint pid) {
    var list = new List<IntPtr>();
    EnumWindows((h, p) => { uint wp; GetWindowThreadProcessId(h, out wp); if (wp == pid && IsWindowVisible(h)) list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }

  public static List<IntPtr> Children(IntPtr parent) {
    var list = new List<IntPtr>();
    EnumChildWindows(parent, (h, p) => { list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }

  // 文件名输入框：可见、可用的 Edit，且不属于地址栏那条隐藏的编辑框（地址栏 Edit 不可见）。
  public static IntPtr FileNameEdit(IntPtr dlg) {
    foreach (var h in Children(dlg)) {
      if (ClassOf(h) == "Edit" && IsWindowVisible(h) && IsWindowEnabled(h)) return h;
    }
    return IntPtr.Zero;
  }

  // 确认按钮文案随对话框用途变化："打开(&O)" / "选择文件夹(&S)" / 英文 "Open"、"Select Folder"。
  public static IntPtr ConfirmButton(IntPtr dlg) {
    foreach (var h in Children(dlg)) {
      if (ClassOf(h) != "Button" || !IsWindowVisible(h)) continue;
      var t = TextOf(h);
      if (t.Contains("打开") || t.Contains("选择") || t.Contains("Open") || t.Contains("Select")) return h;
    }
    return IntPtr.Zero;
  }

  public static IntPtr CancelButton(IntPtr dlg) {
    foreach (var h in Children(dlg)) {
      if (ClassOf(h) != "Button" || !IsWindowVisible(h)) continue;
      var t = TextOf(h);
      if (t.Contains("取消") || t.Contains("Cancel")) return h;
    }
    return IntPtr.Zero;
  }

  public static bool Visible(IntPtr h) { return IsWindowVisible(h); }
  public static bool SetText(IntPtr h, string text) { return SendMessageW(h, 0x000C, IntPtr.Zero, text) != IntPtr.Zero; }
  public static void Click(IntPtr h) { SendMessageW(h, 0x00F5, IntPtr.Zero, IntPtr.Zero); }
  public static void Focus(IntPtr h) { SetForegroundWindow(h); }
  public static string Describe(IntPtr h) { return "class=" + ClassOf(h) + " text=\"" + TextOf(h) + "\""; }
}
"@
}

function Wait-MhPickerDialog {
  <# 只认本进程的 #32770 顶层窗口。别的应用（浏览器等）也有 Edit 控件，按"有 Edit 就算对话框"识别会点错窗口。 #>
  param([Parameter(Mandatory)][int]$AppPid, [int]$TimeoutSec = 30)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    foreach ($h in [MhPickerNative]::TopLevel([uint32]$AppPid)) {
      if ([MhPickerNative]::ClassOf($h) -eq '#32770') { return $h }
    }
    Start-Sleep -Milliseconds 400
  }
  return [IntPtr]::Zero
}

function Get-MhPickerTitle { param([IntPtr]$Dialog) [MhPickerNative]::TextOf($Dialog) }

function Set-MhPickerPath {
  <# 把路径写进文件名框。返回写之前和写之后的值，调用方可以据此断言写入真的发生了。 #>
  param([Parameter(Mandatory)][IntPtr]$Dialog, [Parameter(Mandatory)][string]$Path)
  $edit = [MhPickerNative]::FileNameEdit($Dialog)
  if ($edit -eq [IntPtr]::Zero) { return [pscustomobject]@{ ok = $false; before = ''; after = ''; reason = 'no-file-name-edit' } }
  $before = [MhPickerNative]::TextOf($edit)
  [MhPickerNative]::SetText($edit, $Path) | Out-Null
  Start-Sleep -Milliseconds 400
  $after = [MhPickerNative]::TextOf($edit)
  return [pscustomobject]@{ ok = ($after -eq $Path); before = $before; after = $after; reason = '' }
}

function Confirm-MhPicker {
  param([Parameter(Mandatory)][IntPtr]$Dialog)
  $btn = [MhPickerNative]::ConfirmButton($Dialog)
  if ($btn -eq [IntPtr]::Zero) { return $false }
  [MhPickerNative]::Click($btn)
  return $true
}

function Close-MhPicker {
  param([Parameter(Mandatory)][IntPtr]$Dialog)
  $btn = [MhPickerNative]::CancelButton($Dialog)
  if ($btn -eq [IntPtr]::Zero) { return $false }
  [MhPickerNative]::Click($btn)
  return $true
}

function Wait-MhPickerClosed {
  param([Parameter(Mandatory)][IntPtr]$Dialog, [int]$TimeoutSec = 20)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (-not [MhPickerNative]::IsWindowVisible($Dialog)) { return $true }
    Start-Sleep -Milliseconds 300
  }
  return $false
}
