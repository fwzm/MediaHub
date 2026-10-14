<#
.SYNOPSIS
Shared UI Automation, window-geometry, input and evidence helpers for the MediaHub GUI acceptance
harness (W5-E).

.DESCRIPTION
Design rules that came out of this project's own history:

* DPI. The machine runs at 150%. A DPI-unaware process is served virtualised coordinates and a
  screen grab silently captures only part of the framebuffer, which produces false negatives that
  look like product defects. Every entry point here goes through Initialize-MhUia, which calls
  SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2) before any element is measured.
* Assertions, not existence. Nothing here reports success because a control exists. Area scripts
  supply before-state, input, after-state and an explicit assertion; Add-MhCheck refuses an empty
  assertion ("a missing assertion is not evidence of success").
* Evidence is a file. Every check records the screenshot or UIA dump it was decided from.

All coordinates in this module are physical pixels of the virtual screen.
#>

$script:MhKeyMap = @{
  'ESC' = 0x1B; 'ESCAPE' = 0x1B; 'SPACE' = 0x20; 'ENTER' = 0x0D; 'TAB' = 0x09; 'BACK' = 0x08
  'LEFT' = 0x25; 'UP' = 0x26; 'RIGHT' = 0x27; 'DOWN' = 0x28
  'F1' = 0x70; 'F11' = 0x7A; 'F12' = 0x7B
  'A' = 0x41; 'B' = 0x42; 'C' = 0x43; 'D' = 0x44; 'E' = 0x45; 'F' = 0x46; 'G' = 0x47; 'H' = 0x48
  'I' = 0x49; 'J' = 0x4A; 'K' = 0x4B; 'L' = 0x4C; 'M' = 0x4D; 'N' = 0x4E; 'O' = 0x4F; 'P' = 0x50
  'Q' = 0x51; 'R' = 0x52; 'S' = 0x53; 'T' = 0x54; 'U' = 0x55; 'V' = 0x56; 'W' = 0x57; 'X' = 0x58
  'Y' = 0x59; 'Z' = 0x5A
  '0' = 0x30; '1' = 0x31; '2' = 0x32; '3' = 0x33; '4' = 0x34; '5' = 0x35; '6' = 0x36; '7' = 0x37
  '8' = 0x38; '9' = 0x39
}
$script:MhExtendedKeys = @(0x25, 0x26, 0x27, 0x28)

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing, System.Windows.Forms

if (-not ('Mh.Native' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

namespace Mh
{
    public static class Native
    {
        [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
        [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, IntPtr extra);
        [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
        [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr l);
        [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
        [DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
        [DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
        [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);

        public delegate bool EnumProc(IntPtr h, IntPtr l);

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential)]
        public struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public uint dwFlags;
        }

        public const int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
        public const uint MONITOR_DEFAULTTONEAREST = 2;
        public const uint KEYEVENTF_KEYUP = 0x0002;
        public const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        public const uint MOUSEEVENTF_LEFTUP = 0x0004;

        public static string Rect(IntPtr h)
        {
            RECT r;
            if (!GetWindowRect(h, out r)) return "";
            return r.Left + "|" + r.Top + "|" + r.Right + "|" + r.Bottom;
        }

        public static string ExtendedFrame(IntPtr h)
        {
            RECT r;
            int hr = DwmGetWindowAttribute(h, DWMWA_EXTENDED_FRAME_BOUNDS, out r, Marshal.SizeOf(typeof(RECT)));
            if (hr != 0) return Rect(h);
            return r.Left + "|" + r.Top + "|" + r.Right + "|" + r.Bottom;
        }

        public static string MonitorBounds(IntPtr h)
        {
            IntPtr mon = MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST);
            if (mon == IntPtr.Zero) return "";
            MONITORINFO mi = new MONITORINFO();
            mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
            if (!GetMonitorInfo(mon, ref mi)) return "";
            return mi.rcMonitor.Left + "|" + mi.rcMonitor.Top + "|" + mi.rcMonitor.Right + "|" + mi.rcMonitor.Bottom
                 + "|" + mi.rcWork.Left + "|" + mi.rcWork.Top + "|" + mi.rcWork.Right + "|" + mi.rcWork.Bottom;
        }

        public static string ClassName(IntPtr h)
        {
            StringBuilder sb = new StringBuilder(512);
            GetClassName(h, sb, sb.Capacity);
            return sb.ToString();
        }

        [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr ctx);
        [DllImport("user32.dll")] public static extern IntPtr GetThreadDpiAwarenessContext();
        [DllImport("user32.dll")] public static extern int GetAwarenessFromDpiAwarenessContext(IntPtr ctx);

        /// <summary>
        /// The awareness the *process* actually has. SetProcessDpiAwarenessContext returns false when
        /// an awareness mode was already fixed at startup (pwsh.exe declares one in its manifest), so
        /// the return value alone cannot tell the harness whether its capture is trustworthy.
        /// 0=unaware, 1=system aware, 2=per-monitor aware.
        /// </summary>
        public static int Awareness()
        {
            return GetAwarenessFromDpiAwarenessContext(GetThreadDpiAwarenessContext());
        }

        public static string WindowText(IntPtr h)
        {
            StringBuilder sb = new StringBuilder(1024);
            GetWindowText(h, sb, sb.Capacity);
            return sb.ToString();
        }

        /// <summary>Every top-level window as an "hwnd|pid|class|text|L|T|R|B|visible" record.</summary>
        public static string[] TopLevelWindows()
        {
            List<string> rows = new List<string>();
            EnumWindows(delegate(IntPtr h, IntPtr l)
            {
                uint pid; GetWindowThreadProcessId(h, out pid);
                rows.Add(Describe(h, pid));
                return true;
            }, IntPtr.Zero);
            return rows.ToArray();
        }

        /// <summary>Every child window of one window: the same record plus the immediate parent.</summary>
        public static string[] ChildWindows(IntPtr parent)
        {
            List<string> rows = new List<string>();
            EnumChildWindows(parent, delegate(IntPtr h, IntPtr l)
            {
                uint pid; GetWindowThreadProcessId(h, out pid);
                rows.Add(Describe(h, pid) + "|" + GetParent(h).ToInt64());
                return true;
            }, IntPtr.Zero);
            return rows.ToArray();
        }

        private static string Describe(IntPtr h, uint pid)
        {
            RECT r; GetWindowRect(h, out r);
            return h.ToInt64() + "|" + pid + "|" + ClassName(h) + "|" + WindowText(h) + "|"
                 + r.Left + "|" + r.Top + "|" + r.Right + "|" + r.Bottom + "|" + (IsWindowVisible(h) ? "1" : "0");
        }
    }
}
'@
}

# ---------------------------------------------------------------- initialisation

function Initialize-MhUia {
  <#
    Must run before any measurement. PER_MONITOR_AWARE_V2 is -4; the fallback exists because the
    call fails (rather than degrades) when the process already has an awareness context.
  #>
  # PER_MONITOR_AWARE_V2 == -4. The process-level call fails outright when the host already fixed
  # an awareness mode at startup (pwsh.exe is system-aware), and the failure is silent: the process
  # keeps the *system* mode, which on a 150% display still returns physical pixels for the primary
  # monitor but is not what the project's rule asks for. The thread-level override is tried second
  # because it can still be applied in that situation; the resulting mode is measured afterwards
  # rather than assumed from either return value.
  $script:MhDpiAware = [Mh.Native]::SetProcessDpiAwarenessContext([IntPtr](-4))
  $script:MhDpiAwareProcess = $script:MhDpiAware
  if (-not $script:MhDpiAware) {
    $script:MhDpiAware = [Mh.Native]::SetThreadDpiAwarenessContext([IntPtr](-4)) -ne [IntPtr]::Zero
  }
  $script:MhDpiAwareThread = $script:MhDpiAware
  if (-not $script:MhDpiAware) { [Mh.Native]::SetProcessDPIAware() | Out-Null }
  $script:MhDpiScale = Get-MhDpiScale
  $script:MhAwareness = try { [Mh.Native]::Awareness() } catch { -1 }
  return [pscustomobject]@{
    dpiAwareCallSucceeded = $script:MhDpiAware
    processCallSucceeded  = $script:MhDpiAwareProcess
    threadCallSucceeded   = $script:MhDpiAwareThread
    awareness             = $script:MhAwareness
    awarenessName         = $(switch ($script:MhAwareness) { 0 { 'unaware' } 1 { 'system-aware' } 2 { 'per-monitor-aware' } default { 'unknown' } })
    dpiScale              = $script:MhDpiScale
  }
}

function Get-MhDpiScale {
  <# Reported in the evidence so a reader can confirm the capture really was DPI aware. #>
  try {
    $bmp = New-Object System.Drawing.Bitmap(1, 1)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dpi = $g.DpiX
    $g.Dispose(); $bmp.Dispose()
    return [Math]::Round($dpi / 96.0, 3)
  } catch { return 0 }
}

# ---------------------------------------------------------------- processes and windows

function Get-MhProcess {
  <#
    Live MediaHub processes only. A process that has already exited can linger in the process table
    while a handle is still open; it has no threads and no window, and counting it as "the app is
    still running" would be a false failure.
  #>
  param([string]$Name = 'MediaHub')
  Get-Process $Name -ErrorAction SilentlyContinue | Where-Object { $_.Threads.Count -gt 0 }
}

function Get-MhZombieProcess {
  param([string]$Name = 'MediaHub')
  @(Get-Process $Name -ErrorAction SilentlyContinue | Where-Object { $_.Threads.Count -eq 0 })
}

function Get-MhWindowHandle {
  <#
    The app's top-level window handle, found by enumerating the process's own windows.
    Process.MainWindowHandle is deliberately not used: it is 0 for a window that is not currently
    visible, so a hidden or cloaked window would be reported as "the app is not running" - which is
    a different (and much more interesting) fact.
  #>
  param([string]$Exe = 'MediaHub', [switch]$IncludeHidden)
  $pids = @(Get-MhProcess -Name $Exe | ForEach-Object { $_.Id })
  if ($pids.Count -eq 0) { return $null }
  $rows = [Mh.Native]::TopLevelWindows()
  $cand = @()
  foreach ($r in $rows) {
    $f = $r.Split('|')
    if ($f.Count -lt 9) { continue }
    if ($pids -notcontains [int]$f[1]) { continue }
    if ($f[2] -notlike 'WinUIDesktopWin32WindowClass*') { continue }
    $cand += [pscustomobject]@{ hwnd = [IntPtr][int64]$f[0]; visible = ($f[8] -eq '1'); text = $f[3] }
  }
  if ($cand.Count -eq 0) { return $null }
  $preferred = @($cand | Where-Object { $_.visible })
  if ($preferred.Count -gt 0) { return $preferred[0].hwnd }
  if ($IncludeHidden) { return $cand[0].hwnd }
  return $cand[0].hwnd
}

function Get-MhWindow {
  <# The app's main window as an AutomationElement, or $null. #>
  param([string]$Exe = 'MediaHub')
  $h = Get-MhWindowHandle -Exe $Exe
  if (-not $h) { return $null }
  return [System.Windows.Automation.AutomationElement]::FromHandle($h)
}

function Get-MhWindowInfo {
  param([string]$Exe = 'MediaHub')
  $p = Get-MhProcess -Name $Exe | Select-Object -First 1
  if (-not $p) { return $null }
  $h = Get-MhWindowHandle -Exe $Exe
  if (-not $h) { $h = [IntPtr]::Zero }
  [pscustomobject]@{
    pid          = $p.Id
    hwnd         = $h.ToInt64()
    visible      = $(if ($h -eq [IntPtr]::Zero) { $false } else { [Mh.Native]::IsWindowVisible($h) })
    title        = $p.MainWindowTitle
    exe          = $(try { $p.Path } catch { '' })
    startTime    = $(try { $p.StartTime.ToString('s') } catch { '' })
    threads      = $p.Threads.Count
    rect         = (Get-MhRect -Hwnd $h)
    frame        = (Get-MhRect -Hwnd $h -Extended)
    monitor      = (Get-MhMonitor -Hwnd $h)
    isIconic     = [Mh.Native]::IsIconic($h)
    isForeground = ([Mh.Native]::GetForegroundWindow() -eq $h)
    responding   = $(try { $p.Responding } catch { $null })
  }
}

function Get-MhRect {
  param([IntPtr]$Hwnd, [switch]$Extended)
  $s = if ($Extended) { [Mh.Native]::ExtendedFrame($Hwnd) } else { [Mh.Native]::Rect($Hwnd) }
  if (-not $s) { return $null }
  $v = $s.Split('|') | ForEach-Object { [int]$_ }
  [pscustomobject]@{ l = $v[0]; t = $v[1]; r = $v[2]; b = $v[3]; x = $v[0]; y = $v[1]; w = ($v[2] - $v[0]); h = ($v[3] - $v[1]) }
}

function Get-MhMonitor {
  param([IntPtr]$Hwnd)
  $s = [Mh.Native]::MonitorBounds($Hwnd)
  if (-not $s) { return $null }
  $v = $s.Split('|') | ForEach-Object { [int]$_ }
  [pscustomobject]@{
    monitor = @{ l = $v[0]; t = $v[1]; r = $v[2]; b = $v[3]; w = ($v[2] - $v[0]); h = ($v[3] - $v[1]) }
    work    = @{ l = $v[4]; t = $v[5]; r = $v[6]; b = $v[7]; w = ($v[6] - $v[4]); h = ($v[7] - $v[5]) }
  }
}

function Get-MhChildWindows {
  <# Child HWNDs of the app window. This is how the engine's own window is observed. #>
  param([IntPtr]$Hwnd)
  $rows = [Mh.Native]::ChildWindows($Hwnd)
  $out = @()
  foreach ($r in $rows) {
    $f = $r.Split('|')
    $out += [pscustomobject]@{
      hwnd = [int64]$f[0]; pid = [int]$f[1]; class = $f[2]; text = $f[3]
      x = [int]$f[4]; y = [int]$f[5]; w = ([int]$f[6] - [int]$f[4]); h = ([int]$f[7] - [int]$f[5])
      visible = ($f[8] -eq '1'); parent = [int64]$f[9]
    }
  }
  $out
}

function Set-MhForeground {
  <#
    Synthetic input lands on whatever window is on top, so the target must be raised first.
    SW_SHOW (5) is used instead of SW_RESTORE (9) on purpose: restoring would un-minimise a window
    whose minimised state is itself under test.
  #>
  param([IntPtr]$Hwnd, [int]$SettleMs = 600)
  # No ShowWindow here. Hide/show is the app's business (AppWindow), and calling SW_SHOW on a WinUI 3
  # window was observed to leave it non-visible while the process kept running - which then looked
  # like "the app died" to every later step. Raising is enough for synthetic input to land.
  $ok = [Mh.Native]::SetForegroundWindow($Hwnd)
  Start-Sleep -Milliseconds $SettleMs
  $fg = [Mh.Native]::GetForegroundWindow()
  [pscustomobject]@{ setForegroundReturned = $ok; isForeground = ($fg -eq $Hwnd); foregroundHwnd = $fg.ToInt64() }
}

# ---------------------------------------------------------------- element lookup

function Get-MhElementAll {
  param([System.Windows.Automation.AutomationElement]$Root)
  if (-not $Root) { return @() }
  $Root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                [System.Windows.Automation.Condition]::TrueCondition)
}

function Get-MhAppRoots {
  <#
    Every UI Automation root that belongs to the app: the main window plus any separate Window
    element it owns under the desktop root. A WinUI 3 ContentDialog does not appear anywhere in the
    main window's descendant tree - it is published as its own "Popup" Window element - so a lookup
    that only walks the main window reports "the add-source dialog never opened" while it is sitting
    on screen. That false negative is exactly what this function exists to prevent.
  #>
  param([string]$Exe = 'MediaHub')
  $roots = @()
  $main = Get-MhWindow -Exe $Exe
  if ($main) { $roots += $main }
  $pids = @(Get-MhProcess -Name $Exe | ForEach-Object { $_.Id })
  if ($pids.Count -eq 0) { return $roots }
  try {
    $desktop = [System.Windows.Automation.AutomationElement]::RootElement
    $top = $desktop.FindAll([System.Windows.Automation.TreeScope]::Children,
                            [System.Windows.Automation.Condition]::TrueCondition)
    foreach ($t in $top) {
      if ($pids -notcontains $t.Current.ProcessId) { continue }
      if ($main -and $t.Current.NativeWindowHandle -eq $main.Current.NativeWindowHandle) { continue }
      $roots += $t
    }
  } catch { }
  return $roots
}

function Get-MhAppElementAll {
  <# Union of every app root's descendants, deduplicated by runtime id. #>
  param([switch]$IncludeOffscreen)
  $seen = @{}
  $out = @()
  foreach ($r in (Get-MhAppRoots)) {
    foreach ($e in (Get-MhElementAll -Root $r)) {
      $c = $e.Current
      if (-not $IncludeOffscreen -and $c.IsOffscreen) { continue }
      $key = ($e.GetRuntimeId() -join '.')
      if ($seen.ContainsKey($key)) { continue }
      $seen[$key] = $true
      $out += $e
    }
  }
  return $out
}

function Find-MhAppElement {
  <#
    Find-MhElement across every app root. Use this for anything that may live in a dialog, a flyout
    or a menu.
  #>
  param(
    [string]$AutomationId, [string]$Name, [string]$Contains, [string]$ControlType,
    [int]$Index = 0, [switch]$IncludeOffscreen
  )
  $matches = @()
  foreach ($e in (Get-MhAppElementAll -IncludeOffscreen:$IncludeOffscreen)) {
    $c = $e.Current
    if ($ControlType -and (Get-MhControlType $e) -ne $ControlType) { continue }
    if ($AutomationId -and $c.AutomationId -ne $AutomationId) { continue }
    if ($Name -and $c.Name -ne $Name) { continue }
    if ($Contains -and -not ($c.Name -like ('*' + $Contains + '*'))) { continue }
    $matches += $e
  }
  if ($matches.Count -le $Index) { return $null }
  return $matches[$Index]
}

function Get-MhControlType {
  param($Element)
  $Element.Current.ControlType.ProgrammaticName.Replace('ControlType.', '')
}

function Get-MhDump {
  <#
    One line per element: type, AutomationId, Name, ClassName, offscreen flag, rectangle, enabled.
    AutomationId (what x:Name becomes) is included because a dump that omits it cannot explain why
    a lookup failed.
  #>
  param([System.Windows.Automation.AutomationElement]$Root, [switch]$VisibleOnly)
  $lines = New-Object System.Collections.Generic.List[string]
  foreach ($e in (Get-MhElementAll -Root $Root)) {
    $c = $e.Current
    if ($VisibleOnly -and $c.IsOffscreen) { continue }
    $r = $c.BoundingRectangle
    # A collapsed or detached element reports an infinite rectangle; casting that to int throws and
    # aborted whole dumps before this guard existed.
    $line = '{0} | id={1} | name={2} | cls={3} | off={4} | {5},{6} {7}x{8} | enabled={9}' -f (Get-MhControlType $e), $c.AutomationId, $c.Name, $c.ClassName, $c.IsOffscreen, (ConvertTo-MhInt $r.X), (ConvertTo-MhInt $r.Y), (ConvertTo-MhInt $r.Width), (ConvertTo-MhInt $r.Height), $c.IsEnabled
    $lines.Add($line)
  }
  $lines
}

function Find-MhElement {
  <#
    Finds an element by AutomationId, then exact Name, then Name substring, then ControlType.
    Returns $null rather than throwing, so a caller can record "not found" as a failed assertion
    instead of crashing the area script.
  #>
  param(
    [System.Windows.Automation.AutomationElement]$Root,
    [string]$AutomationId,
    [string]$Name,
    [string]$Contains,
    [string]$ControlType,
    [int]$Index = 0,
    [switch]$IncludeOffscreen
  )
  $matches = @()
  foreach ($e in (Get-MhElementAll -Root $Root)) {
    $c = $e.Current
    if (-not $IncludeOffscreen -and $c.IsOffscreen) { continue }
    if ($ControlType -and (Get-MhControlType $e) -ne $ControlType) { continue }
    if ($AutomationId -and $c.AutomationId -ne $AutomationId) { continue }
    if ($Name -and $c.Name -ne $Name) { continue }
    if ($Contains -and -not ($c.Name -like ('*' + $Contains + '*'))) { continue }
    $matches += $e
  }
  if ($matches.Count -le $Index) { return $null }
  return $matches[$Index]
}

function ConvertTo-MhInt {
  <# UIA rectangles can be infinite for elements that are collapsed or not yet realised. #>
  param($Value)
  try {
    $d = [double]$Value
    if ([double]::IsNaN($d) -or [double]::IsInfinity($d)) { return -1 }
    return [int]$d
  } catch { return -1 }
}

function Get-MhElementRect {
  param($Element)
  if (-not $Element) { return $null }
  $r = $Element.Current.BoundingRectangle
  $x = ConvertTo-MhInt $r.X; $y = ConvertTo-MhInt $r.Y; $w = ConvertTo-MhInt $r.Width; $h = ConvertTo-MhInt $r.Height
  [pscustomobject]@{ x = $x; y = $y; w = $w; h = $h; cx = ($x + [int]($w / 2)); cy = ($y + [int]($h / 2)) }
}

function Get-MhFocused {
  $f = [System.Windows.Automation.AutomationElement]::FocusedElement
  if (-not $f) { return $null }
  $c = $f.Current
  [pscustomobject]@{
    type = (Get-MhControlType $f); id = $c.AutomationId; name = $c.Name; cls = $c.ClassName
    pid = $c.ProcessId; rect = (Get-MhElementRect $f)
  }
}

# ---------------------------------------------------------------- element actions

function Invoke-MhElement {
  param($Element)
  if (-not $Element) { return 'NOT_FOUND' }
  $pattern = $null
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
    $pattern.Invoke(); return 'INVOKED'
  }
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pattern)) {
    $pattern.Select(); return 'SELECTED'
  }
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
    $pattern.Toggle(); return 'TOGGLED'
  }
  return 'NO_PATTERN'
}

function Set-MhValue {
  param($Element, [string]$Value)
  if (-not $Element) { return 'NOT_FOUND' }
  $pattern = $null
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
    $pattern.SetValue($Value); return 'SET'
  }
  return 'NO_VALUEPATTERN'
}

function Get-MhRangeValue {
  <# Slider value through UIA - the only honest way to read a slider after the user moved it. #>
  param($Element)
  if (-not $Element) { return $null }
  $pattern = $null
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) {
    return [pscustomobject]@{ value = $pattern.Current.Value; min = $pattern.Current.Minimum; max = $pattern.Current.Maximum }
  }
  return $null
}

function Set-MhRangeValue {
  param($Element, [double]$Value)
  if (-not $Element) { return 'NOT_FOUND' }
  $pattern = $null
  if ($Element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) {
    $pattern.SetValue($Value); return 'SET'
  }
  return 'NO_RANGEPATTERN'
}

function Set-MhFocus {
  param($Element)
  if (-not $Element) { return 'NOT_FOUND' }
  try { $Element.SetFocus(); return 'FOCUSED' } catch { return 'FOCUS_FAILED' }
}

function Select-MhComboItem {
  <# Selects a ComboBox entry by visible text through expand + selection patterns. #>
  param($ComboElement, [string]$Text)
  if (-not $ComboElement) { return 'COMBO_NOT_FOUND' }
  $expand = $null
  if (-not $ComboElement.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$expand)) {
    return 'NO_EXPAND'
  }
  $expand.Expand()
  Start-Sleep -Milliseconds 600
  # The opened dropdown lives in a popup outside the ComboBox element's own subtree, so the search
  # has to run across every app root. Searching only inside the combo finds the item's TextBlock
  # (no SelectionItemPattern) or nothing at all - and an earlier revision of this function reported
  # "SELECTED" while the combo still said Emby.
  $item = Find-MhAppElement -ControlType 'ListItem' -Name $Text -Index 0
  if (-not $item) { $item = Find-MhAppElement -ControlType 'ListItem' -Contains $Text -Index 0 }
  if (-not $item) { $expand.Collapse(); return 'ITEM_NOT_FOUND' }
  $r = Invoke-MhElement -Element $item
  Start-Sleep -Milliseconds 500
  # Verified by reading the combo's own selection back; the item's select call returning is not
  # evidence that the combo changed.
  $verified = 'UNVERIFIED'
  $selPattern = $null
  if ($ComboElement.TryGetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern, [ref]$selPattern)) {
    $names = @($selPattern.Current.GetSelection() | ForEach-Object { $_.Current.Name })
    $verified = if ($names -contains $Text) { 'VERIFIED' } else { 'NOT_SELECTED:' + ($names -join ',') }
  }
  $expand.Collapse()
  Start-Sleep -Milliseconds 200
  return "$r/$verified"
}

function Click-MhPoint {
  param([int]$X, [int]$Y, [int]$SettleMs = 400, [switch]$DoubleClick)
  [Mh.Native]::SetCursorPos($X, $Y) | Out-Null
  Start-Sleep -Milliseconds 150
  [Mh.Native]::mouse_event([Mh.Native]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
  [Mh.Native]::mouse_event([Mh.Native]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
  if ($DoubleClick) {
    Start-Sleep -Milliseconds 80
    [Mh.Native]::mouse_event([Mh.Native]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
    [Mh.Native]::mouse_event([Mh.Native]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
  }
  Start-Sleep -Milliseconds $SettleMs
  return "clicked $X,$Y double=$([bool]$DoubleClick)"
}

function Click-MhElement {
  param($Element, [switch]$DoubleClick, [int]$SettleMs = 400)
  $r = Get-MhElementRect -Element $Element
  if (-not $r) { return 'NOT_FOUND' }
  $what = if ($DoubleClick) { 'double-clicked' } else { 'clicked' }
  [void](Click-MhPoint -X $r.cx -Y $r.cy -SettleMs $SettleMs -DoubleClick:$DoubleClick)
  return "$what $($r.cx),$($r.cy)"
}

function Send-MhKeys {
  <# Sends whole key strokes (down + up) to the foreground window. #>
  param([string[]]$Keys, [int]$KeyDelayMs = 150)
  $sent = @()
  foreach ($k in $Keys) {
    $name = $k.ToUpperInvariant()
    if (-not $script:MhKeyMap.ContainsKey($name)) { $sent += "UNKNOWN:$k"; continue }
    $vk = [byte]$script:MhKeyMap[$name]
    $flags = if ($script:MhExtendedKeys -contains $vk) { [Mh.Native]::KEYEVENTF_EXTENDEDKEY } else { 0 }
    [Mh.Native]::keybd_event($vk, 0, $flags, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [Mh.Native]::keybd_event($vk, 0, ($flags -bor [Mh.Native]::KEYEVENTF_KEYUP), [IntPtr]::Zero)
    $sent += $name
    Start-Sleep -Milliseconds $KeyDelayMs
  }
  return ($sent -join '+')
}

# ---------------------------------------------------------------- recordable state

function Get-MhState {
  <#
    The observable state a check compares before and after: window geometry (two ways), the element
    tree (counts by type), every visible text, slider values, focus, engine child windows and the
    app's own trace tail.
  #>
  param([System.Windows.Automation.AutomationElement]$Root, [string[]]$LogTail)
  $info = Get-MhWindowInfo
  $state = [ordered]@{}
  if ($info) {
    $state.pid = $info.pid
    $state.hwnd = $info.hwnd
    $state.rect = "$($info.rect.x),$($info.rect.y) $($info.rect.w)x$($info.rect.h)"
    $state.frame = "$($info.frame.x),$($info.frame.y) $($info.frame.w)x$($info.frame.h)"
    $state.monitor = "$($info.monitor.monitor.w)x$($info.monitor.monitor.h)"
    $state.isIconic = $info.isIconic
    $state.isForeground = $info.isForeground
    $state.childWindows = @(Get-MhChildWindows -Hwnd $info.hwnd | ForEach-Object { "$($_.class)@$($_.x),$($_.y) $($_.w)x$($_.h) vis=$($_.visible)" })
  } else {
    $state.pid = $null
    $state.processCount = @(Get-MhProcess).Count
  }

  if ($Root) {
    $texts = New-Object System.Collections.Generic.List[string]
    $byType = @{}
    foreach ($e in (Get-MhElementAll -Root $Root)) {
      $c = $e.Current
      $t = Get-MhControlType $e
      if (-not $byType.ContainsKey($t)) { $byType[$t] = 0 }
      $byType[$t] = $byType[$t] + 1
      if ($c.ControlType -eq [System.Windows.Automation.ControlType]::Text -and -not $c.IsOffscreen -and $c.Name) {
        $texts.Add($c.Name)
      }
    }
    $state.texts = @($texts)
    $state.elementCounts = $byType
    $sliders = @()
    foreach ($e in (Get-MhElementAll -Root $Root)) {
      if ((Get-MhControlType $e) -eq 'Slider') {
        $rv = Get-MhRangeValue -Element $e
        $rect = Get-MhElementRect -Element $e
        $sliders += [pscustomobject]@{
          id = $e.Current.AutomationId; value = $rv.value; min = $rv.min; max = $rv.max
          w = $rect.w; x = $rect.x; y = $rect.y
        }
      }
    }
    $state.sliders = $sliders
    $f = Get-MhFocused
    $state.focused = $(if ($f) { "$($f.type) id=$($f.id) name=$($f.name) pid=$($f.pid)" } else { $null })
  }
  if ($LogTail) { $state.logTail = $LogTail }
  return [pscustomobject]$state
}

# ---------------------------------------------------------------- the app's own trace

function Get-MhLogPath {
  Join-Path $env:LOCALAPPDATA 'MediaHub\startup.log'
}

function Get-MhLog {
  <# Full trace. The app flushes per line, so reading it while the app runs is safe. #>
  $p = Get-MhLogPath
  if (-not (Test-Path $p)) { return @() }
  @(Get-Content $p -ErrorAction SilentlyContinue)
}

function Get-MhLogMatched {
  param([string]$Pattern, [string[]]$Log)
  if (-not $Log) { $Log = Get-MhLog }
  @($Log | Where-Object { $_ -match $Pattern })
}

function Wait-MhLogLine {
  <#
    Waits for lines matching Pattern. An empty result means the timeout elapsed, which callers must
    treat as a failure: "nothing was logged" is a real observation, not a neutral one.
  #>
  param([string]$Pattern, [int]$TimeoutSec = 20, [int]$MinimumCount = 1)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $hits = @(Get-MhLogMatched -Pattern $Pattern)
    if ($hits.Count -ge $MinimumCount) { return $hits }
    Start-Sleep -Milliseconds 400
  }
  return @()
}

# ---------------------------------------------------------------- evidence

function Save-MhScreenshot {
  <#
    DPI-aware grab. The whole virtual screen is captured and then cropped: CopyFromScreen with a
    source rectangle on a scaled display is exactly where the "only the top-left corner" false
    negative comes from. Rect is @(x, y, w, h) in physical pixels.
  #>
  param(
    [Parameter(Mandatory)][string]$Path,
    [int[]]$Rect,
    [switch]$FullScreen
  )
  if (-not $script:MhDpiAware) { [void](Initialize-MhUia) }
  $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $bmp = New-Object System.Drawing.Bitmap($vs.Width, $vs.Height)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($vs.Left, $vs.Top, 0, 0, $bmp.Size)
  $g.Dispose()

  $outBmp = $bmp
  if (-not $FullScreen -and $Rect -and $Rect.Count -ge 4) {
    $x = [Math]::Max(0, $Rect[0] - $vs.Left); $y = [Math]::Max(0, $Rect[1] - $vs.Top)
    $w = [Math]::Min($bmp.Width - $x, $Rect[2]); $h = [Math]::Min($bmp.Height - $y, $Rect[3])
    if ($w -gt 2 -and $h -gt 2) {
      $crop = New-Object System.Drawing.Bitmap($w, $h)
      $cg = [System.Drawing.Graphics]::FromImage($crop)
      $cg.DrawImage($bmp, (New-Object System.Drawing.Rectangle(0, 0, $w, $h)),
                    (New-Object System.Drawing.Rectangle($x, $y, $w, $h)), [System.Drawing.GraphicsUnit]::Pixel)
      $cg.Dispose()
      $outBmp = $crop
    }
  }
  $dir = Split-Path $Path -Parent
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $outBmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
  $size = "$($outBmp.Width)x$($outBmp.Height)"
  if ($outBmp -ne $bmp) { $outBmp.Dispose() }
  $bmp.Dispose()
  return [pscustomobject]@{ path = $Path; size = $size; virtualScreen = "$($vs.Width)x$($vs.Height)"; dpiAware = $script:MhDpiAware }
}

function Save-MhDump {
  param([Parameter(Mandatory)][string]$Path, [System.Windows.Automation.AutomationElement]$Root, [switch]$VisibleOnly, [switch]$App)
  if (-not $Root) { $Root = Get-MhWindow }
  $lines = if ($App) {
    @(Get-MhDump -Root $Root -VisibleOnly:$VisibleOnly) +
    @(Get-MhDump -Root ([System.Windows.Automation.AutomationElement]::RootElement) -VisibleOnly:$VisibleOnly |
        Where-Object { $_ -match 'off=False' })
  } else {
    @(Get-MhDump -Root $Root -VisibleOnly:$VisibleOnly)
  }
  $dir = Split-Path $Path -Parent
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $lines | Set-Content -Path $Path -Encoding UTF8
  return [pscustomobject]@{ path = $Path; lines = $lines.Count }
}

# ---------------------------------------------------------------- player-specific observables

function ConvertFrom-MhTime {
  <# "0:07" / "1:02:03" as the client formats it -> seconds. Returns $null when unparseable. #>
  param([string]$Text)
  if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
  $parts = $Text.Trim().Split(':')
  try {
    $nums = $parts | ForEach-Object { [double]$_ }
  } catch { return $null }
  switch ($nums.Count) {
    2 { return $nums[0] * 60 + $nums[1] }
    3 { return $nums[0] * 3600 + $nums[1] * 60 + $nums[2] }
    default { return $null }
  }
}

function Get-MhPlayerState {
  <#
    Everything the player page exposes to UI Automation, in one object:
    position/duration text and their numeric forms, both sliders, the title, the engine's own
    technical summary (which is where "hwdec=d3d11va" proves the main playback path, not a
    software fallback), and the embedded engine's child window rectangle - the video rectangle a
    screenshot alone cannot measure.
  #>
  param([switch]$SkipSlow)
  $state = [ordered]@{}
  $pos = Find-MhAppElement -AutomationId 'PositionText'
  $dur = Find-MhAppElement -AutomationId 'DurationText'
  $state.playerVisible = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
  $state.position = if ($pos) { $pos.Current.Name } else { $null }
  $state.positionSeconds = if ($pos) { ConvertFrom-MhTime $pos.Current.Name } else { $null }
  $state.duration = if ($dur) { $dur.Current.Name } else { $null }
  $state.durationSeconds = if ($dur) { ConvertFrom-MhTime $dur.Current.Name } else { $null }
  $title = Find-MhAppElement -AutomationId 'TitleText'
  $state.title = if ($title) { $title.Current.Name } else { $null }
  $tech = Find-MhAppElement -AutomationId 'TechnicalSummary'
  $state.technical = if ($tech) { $tech.Current.Name } else { $null }
  $seek = Find-MhAppElement -AutomationId 'SeekSlider'
  if ($seek) { $rv = Get-MhRangeValue -Element $seek; $state.seekValue = $rv.value; $state.seekMin = $rv.min; $state.seekMax = $rv.max }
  $vol = Find-MhAppElement -AutomationId 'VolumeSlider'
  if ($vol) { $rv = Get-MhRangeValue -Element $vol; $state.volume = $rv.value; $state.volumeMax = $rv.max }
  $speed = Find-MhAppElement -AutomationId 'SpeedCombo'
  if ($speed) {
    try { $state.speed = $speed.GetCurrentPattern([System.Windows.Automation.SelectionPattern]::Pattern).Current.GetSelection() | ForEach-Object { $_.Current.Name } } catch { }
  }
  if (-not $SkipSlow) {
    $state.videoRect = (Get-MhVideoRect)
    $state.audio = Get-MhAudioPeak
  }
  return [pscustomobject]$state
}

function Get-MhVideoRect {
  <#
    The embedded engine's own child window ("mpv" class, title "<file> - mpv"). This is the video
    rectangle in physical pixels: it is what the native renderer actually occupies, as opposed to
    the XAML host panel that merely reserves the space.
  #>
  param([string]$Exe = 'MediaHub')
  $p = Get-MhProcess -Name $Exe | Select-Object -First 1
  if (-not $p) { return $null }
  $rows = @([Mh.Native]::TopLevelWindows())
  $childRows = @()
  $hwnd = Get-MhWindowHandle -Exe $Exe
  if ($hwnd) { $childRows = @([Mh.Native]::ChildWindows($hwnd)) }
  foreach ($r in $childRows) {
    $f = $r.Split('|')
    if ($f[2] -notlike 'mpv*') { continue }
    return [pscustomobject]@{
      hwnd = [int64]$f[0]; class = $f[2]; title = $f[3]
      x = [int]$f[4]; y = [int]$f[5]; w = ([int]$f[6] - [int]$f[4]); h = ([int]$f[7] - [int]$f[5])
      visible = ($f[8] -eq '1')
    }
  }
  return $null
}

function Get-MhAudioPeak {
  <#
    Peak level of the client's audio session on the default render endpoint, sampled in a child
    process. This is the only observable that distinguishes "the mute button was clicked" from
    "the sound stopped"; the client exposes no mute state to UI Automation.
  #>
  param([int]$ProcessId = 0, [int]$Samples = 18, [int]$IntervalMs = 50)
  if ($ProcessId -le 0) {
    $p = Get-MhProcess | Select-Object -First 1
    if (-not $p) { return $null }
    $ProcessId = $p.Id
  }
  $script = Join-Path $PSScriptRoot 'audio-peak.ps1'
  $out = & pwsh -NoProfile -File $script -ProcessId $ProcessId -Samples $Samples -IntervalMs $IntervalMs 2>&1
  $line = ($out | Where-Object { $_ -match '^PEAK=' } | Select-Object -First 1)
  if (-not $line) { return [pscustomobject]@{ raw = ($out -join ' '); peak = $null } }
  $peak = $null; $sessions = $null; $sess = $null
  if ($line -match 'PEAK=([0-9.]+|NO_SESSION)') { $peak = $matches[1] }
  if ($line -match 'SESSIONS=(\d+)') { $sessions = [int]$matches[1] }
  if ($line -match 'STATE=(\d+)') { $sess = [int]$matches[1] }
  [pscustomobject]@{ peak = $peak; sessions = $sessions; state = $sess; raw = $line }
}

function Get-MhPanelButton {
  <#
    The audio-track and subtitle panel buttons carry no AutomationId and no UIA name - only a
    ToolTip, which UI Automation publishes as HelpText. They are located by HelpText first and by
    position in the bottom bar second (they are the two buttons to the right of SpeedCombo).
  #>
  param([ValidateSet('audio', 'subtitle')][string]$Kind)
  $want = if ($Kind -eq 'audio') { '音轨' } else { '字幕' }
  foreach ($e in (Get-MhAppElementAll)) {
    if ((Get-MhControlType $e) -ne 'Button') { continue }
    try {
      $help = $e.Current.HelpText
      if ($help -eq $want) { return $e }
    } catch { }
  }
  $speed = Find-MhAppElement -AutomationId 'SpeedCombo'
  if (-not $speed) { return $null }
  $sr = Get-MhElementRect -Element $speed
  $cands = @()
  foreach ($e in (Get-MhAppElementAll)) {
    if ((Get-MhControlType $e) -ne 'Button') { continue }
    $r = Get-MhElementRect -Element $e
    if ($r.x -gt $sr.x -and $r.y -gt ($sr.y - 20) -and $r.w -lt 120) { $cands += [pscustomobject]@{ e = $e; r = $r } }
  }
  $cands = @($cands | Sort-Object { $_.r.x })
  if ($Kind -eq 'audio' -and $cands.Count -ge 1) { return $cands[0].e }
  if ($Kind -eq 'subtitle' -and $cands.Count -ge 2) { return $cands[1].e }
  return $null
}

function Get-MhTrackList {
  <# The rows currently shown in the audio or subtitle track list. #>
  param([ValidateSet('audio', 'subtitle')][string]$Kind)
  $id = if ($Kind -eq 'audio') { 'AudioList' } else { 'SubtitleList' }
  $list = Find-MhAppElement -AutomationId $id
  if (-not $list) { return @() }
  $rows = @()
  foreach ($e in (Get-MhElementAll -Root $list)) {
    if ((Get-MhControlType $e) -ne 'ListItem') { continue }
    $sel = $false
    try { $sel = $e.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected } catch { }
    $rows += [pscustomobject]@{ name = $e.Current.Name; selected = $sel }
  }
  return $rows
}

function Get-MhWindowProcessCount {
  @(Get-MhProcess).Count
}

function Set-MhWindowSize {
  <# Moves/resizes the app window through the Win32 API and reports the resulting rectangle. #>
  param([int]$X, [int]$Y, [int]$Width, [int]$Height, [int]$SettleMs = 700)
  $hwnd = Get-MhWindowHandle
  if (-not $hwnd) { return $null }
  [Mh.Native]::SetWindowPos($hwnd, [IntPtr]::Zero, $X, $Y, $Width, $Height, 0x0004) | Out-Null
  Start-Sleep -Milliseconds $SettleMs
  return (Get-MhRect -Hwnd $hwnd)
}

function Set-MhWindowState {
  <# Minimise/maximise/restore through UI Automation's WindowPattern. #>
  param([ValidateSet('minimized', 'maximized', 'normal')][string]$State, [int]$SettleMs = 900)
  $el = Get-MhWindow
  if (-not $el) { return 'NO_WINDOW' }
  $pattern = $null
  if (-not $el.TryGetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern, [ref]$pattern)) { return 'NO_WINDOWPATTERN' }
  switch ($State) {
    'minimized' { $pattern.SetWindowVisualState([System.Windows.Automation.WindowVisualState]::Minimized) }
    'maximized' { $pattern.SetWindowVisualState([System.Windows.Automation.WindowVisualState]::Maximized) }
    'normal' { $pattern.SetWindowVisualState([System.Windows.Automation.WindowVisualState]::Normal) }
  }
  Start-Sleep -Milliseconds $SettleMs
  return 'OK'
}

# ---------------------------------------------------------------- result recording

function New-MhRun {
  param(
    [Parameter(Mandatory)][string]$Area,
    [Parameter(Mandatory)][string]$EvidenceDir,
    [hashtable]$Meta = @{}
  )
  if (-not $script:MhDpiAware) { [void](Initialize-MhUia) }
  New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
  [pscustomobject]@{
    area        = $Area
    evidenceDir = (Resolve-Path $EvidenceDir).Path
    startedUtc  = (Get-Date).ToUniversalTime().ToString('o')
    machine     = $env:COMPUTERNAME
    user        = $env:USERNAME
    dpiAware    = $script:MhDpiAware
    dpiScale    = $script:MhDpiScale
    meta        = $Meta
    checks      = New-Object System.Collections.Generic.List[object]
    notes       = New-Object System.Collections.Generic.List[string]
  }
}

function Add-MhCheck {
  <#
    Records one check. Before/After are state objects, Input is what was done, Assertion is the
    sentence being decided. An empty assertion throws: a check without a claim is not a check.
  #>
  param(
    [Parameter(Mandatory)]$Run,
    [Parameter(Mandatory)][string]$Id,
    $Before,
    [Parameter(Mandatory)][AllowEmptyString()][string]$Input,
    $After,
    [Parameter(Mandatory)][string]$Assertion,
    [Parameter(Mandatory)][ValidateSet('pass', 'fail', 'not-verified', 'info')][string]$Result,
    [string[]]$Evidence = @(),
    [string]$Note = ''
  )
  if ([string]::IsNullOrWhiteSpace($Assertion)) { throw "check '$Id' has no assertion" }
  $check = [pscustomobject]@{
    id        = $Id
    result    = $Result
    input     = $Input
    assertion = $Assertion
    before    = $Before
    after     = $After
    evidence  = $Evidence
    note      = $Note
    at        = (Get-Date).ToUniversalTime().ToString('o')
  }
  $Run.checks.Add($check)
  $colour = switch ($Result) { 'pass' { 'Green' } 'fail' { 'Red' } 'not-verified' { 'Yellow' } default { 'Gray' } }
  Write-Host ('  [{0}] {1} - {2}' -f $Result.ToUpperInvariant(), $Id, $Assertion) -ForegroundColor $colour
  if ($Note) { Write-Host ('        note: ' + $Note) }
  return $check
}

function Add-MhNote {
  param([Parameter(Mandatory)]$Run, [Parameter(Mandatory)][string]$Text)
  $Run.notes.Add($Text)
  Write-Host ('  note: ' + $Text) -ForegroundColor DarkGray
}

function Save-MhRun {
  param([Parameter(Mandatory)]$Run, [string]$JsonPath, [string]$MarkdownPath)
  $Run | Add-Member -NotePropertyName finishedUtc -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
  $counts = @{ pass = 0; fail = 0; 'not-verified' = 0; info = 0 }
  foreach ($c in $Run.checks) { $counts[$c.result] = $counts[$c.result] + 1 }
  $Run | Add-Member -NotePropertyName summary -NotePropertyValue $counts -Force
  if (-not $JsonPath) { $JsonPath = Join-Path $Run.evidenceDir 'results.json' }
  $Run | ConvertTo-Json -Depth 12 | Set-Content -Path $JsonPath -Encoding UTF8
  if ($MarkdownPath) {
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# $($Run.area) - GUI acceptance result")
    $md.Add("")
    $md.Add("- started: $($Run.startedUtc)")
    $md.Add("- finished: $($Run.finishedUtc)")
    $md.Add("- dpi aware process: $($Run.dpiAware) (scale $($Run.dpiScale))")
    $md.Add("- checks: pass=$($counts['pass']) fail=$($counts['fail']) not-verified=$($counts['not-verified']) info=$($counts['info'])")
    $md.Add("")
    $md.Add("| id | result | input | assertion | evidence |")
    $md.Add("|----|--------|-------|-----------|----------|")
    foreach ($c in $Run.checks) {
      $ev = if ($c.evidence.Count -gt 0) { ($c.evidence -join ' ') } else { '-' }
      $md.Add("| $($c.id) | $($c.result) | $($c.input -replace '\|', '/') | $($c.assertion -replace '\|', '/') | $ev |")
    }
    if ($Run.notes.Count -gt 0) {
      $md.Add("")
      $md.Add("## Notes")
      foreach ($n in $Run.notes) { $md.Add("- $n") }
    }
    $md | Set-Content -Path $MarkdownPath -Encoding UTF8
  }
  return [pscustomobject]@{ json = $JsonPath; counts = $counts }
}

# ---------------------------------------------------------------- app lifecycle and resources

function Start-MhApp {
  <#
    Launches the built client. The trace file is cleared first when -ResetTrace is given so a run
    cannot be explained by the previous run's lines.
  #>
  param(
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string[]]$ArgumentList = @(),
    [switch]$ResetTrace,
    [int]$TimeoutSec = 40
  )
  if (-not (Test-Path $Exe)) { throw "app not found: $Exe" }
  if ($ResetTrace) {
    $log = Get-MhLogPath
    $dir = Split-Path $log -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Set-Content -Path $log -Value '' -Encoding UTF8
  }
  # Win32_Process.Create, not Start-Process. A Start-Process child belongs to the harness process
  # tree and is torn down with it, which made "launch in one step, drive it in the next" impossible:
  # the app was gone before the second step ran. A created process inherits nothing and survives.
  $cmdLine = '"' + $Exe + '"'
  if ($ArgumentList.Count -gt 0) {
    $cmdLine += ' ' + (($ArgumentList | ForEach-Object { '"' + $_ + '"' }) -join ' ')
  }
  $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $cmdLine
    CurrentDirectory = (Split-Path $Exe -Parent)
  }
  if ($created.ReturnValue -ne 0) { throw "could not start the app, Win32_Process.Create returned $($created.ReturnValue)" }
  $startedPid = [int]$created.ProcessId

  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    $live = Get-MhProcess | Where-Object { $_.Id -eq $startedPid -and $_.MainWindowHandle -ne 0 }
    if ($live) { break }
  }
  [pscustomobject]@{ pid = $startedPid; cmdLine = $cmdLine; window = (Get-MhWindowInfo); started = (Get-Date).ToString('s') }
}

function Stop-MhAppGracefully {
  <#
    Closes the window the way a user does (WM_CLOSE) and waits. The caller is told which branch was
    taken, because "the harness had to kill it" must never be reported as "the app released
    everything".
  #>
  param([int]$TimeoutSec = 25)
  $procs = @(Get-MhProcess)
  if ($procs.Count -eq 0) { return [pscustomobject]@{ method = 'already-exited'; waitedSec = 0 } }
  foreach ($p in $procs) { [void]$p.CloseMainWindow() }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    if (@(Get-MhProcess).Count -eq 0) {
      return [pscustomobject]@{ method = 'graceful'; waitedSec = [Math]::Round($sw.Elapsed.TotalSeconds, 1) }
    }
    Start-Sleep -Milliseconds 300
  }
  return [pscustomobject]@{ method = 'still-running-after-timeout'; waitedSec = $TimeoutSec }
}

function Stop-MhAppForced {
  param([string]$Reason = 'harness cleanup')
  $procs = @(Get-MhProcess)
  foreach ($p in $procs) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  [pscustomobject]@{ method = 'forced'; reason = $Reason; remaining = @(Get-MhProcess).Count }
}

function Get-MhListeners {
  <# Listening TCP endpoints owned by the given processes (the playback relay is a loopback one). #>
  param([int[]]$Pids)
  $conns = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
  if ($null -ne $Pids -and @($Pids).Count -gt 0) { $conns = @($conns | Where-Object { $Pids -contains [int]$_.OwningProcess }) }
  @($conns | ForEach-Object {
    [pscustomobject]@{ address = $_.LocalAddress; port = $_.LocalPort; pid = $_.OwningProcess }
  })
}

function Get-MhConnections {
  param([int[]]$Pids)
  $conns = @(Get-NetTCPConnection -ErrorAction SilentlyContinue)
  if ($null -ne $Pids -and @($Pids).Count -gt 0) { $conns = @($conns | Where-Object { $Pids -contains [int]$_.OwningProcess }) }
  @($conns | ForEach-Object {
    [pscustomobject]@{ state = "$($_.State)"; address = $_.LocalAddress; port = $_.LocalPort; remote = "$($_.RemoteAddress):$($_.RemotePort)"; pid = $_.OwningProcess }
  })
}

Initialize-MhUia | Out-Null
Export-ModuleMember -Function *
