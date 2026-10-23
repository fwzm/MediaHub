param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$AutoId = "",
  [string]$Name = "",
  [string]$Text = ""
)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

$proc = Get-Process MediaHub -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { Write-Output "NO_WINDOW"; exit 1 }
$root = [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
if (-not $root) { Write-Output "NO_UIA_ROOT"; exit 1 }

function Get-All { $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition) }

function Find-ByAutoId([string]$id) {
  $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
  return $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}
function Find-ByName([string]$n) {
  $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $n)
  return $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}
function Enable-Input {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
public class MhInput {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr c);
}
"@ -ErrorAction SilentlyContinue
  [MhInput]::SetProcessDpiAwarenessContext([IntPtr](-4)) | Out-Null
}
function Click-Element($e) {
  Enable-Input
  $p = Get-Process MediaHub -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
  if ($p) { [MhInput]::SetForegroundWindow($p.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 350 }
  $r = $e.Current.BoundingRectangle
  $x = [int]($r.X + $r.Width/2); $y = [int]($r.Y + $r.Height/2)
  [MhInput]::SetCursorPos($x, $y) | Out-Null
  Start-Sleep -Milliseconds 220
  [MhInput]::mouse_event(0x0002,0,0,0,[IntPtr]::Zero)
  [MhInput]::mouse_event(0x0004,0,0,0,[IntPtr]::Zero)
  return "$x,$y"
}

switch ($Action) {
  "text" {
    foreach ($e in Get-All) {
      $c = $e.Current
      if ($c.ControlType -eq [System.Windows.Automation.ControlType]::Text -and -not $c.IsOffscreen -and $c.Name) { $c.Name }
    }
  }
  "ids" {
    foreach ($e in Get-All) {
      $c = $e.Current
      if ($c.AutomationId -and -not $c.IsOffscreen) { $c.AutomationId + " | " + $c.ControlType.ProgrammaticName.Replace('ControlType.','') + " | " + $c.Name }
    }
  }
  "find" {
    if ($AutoId) { $e = Find-ByAutoId $AutoId } else { $e = Find-ByName $Name }
    if ($AutoId -and -not $e) {
      # Report what ids DO exist: an absent id is otherwise indistinguishable from a page that never
      # finished loading, and those need very different follow-up.
      "NOT_FOUND $AutoId"
      foreach ($x in Get-All) { $c = $x.Current; if ($c.AutomationId -and -not $c.IsOffscreen) { "  present: " + $c.AutomationId } }
      exit 2
    }
    if (-not $e) { "NOT_FOUND"; exit 2 }
    $r = $e.Current.BoundingRectangle
    "FOUND " + [int]$r.X + "," + [int]$r.Y + " " + [int]$r.Width + "x" + [int]$r.Height
  }
  "click-id" {
    $e = Find-ByAutoId $AutoId
    if (-not $e) { "NOT_FOUND $AutoId"; exit 2 }
    "CLICKED " + (Click-Element $e) + " " + $e.Current.Name
  }
  "invoke-id" {
    $e = Find-ByAutoId $AutoId
    if (-not $e) { "NOT_FOUND $AutoId"; exit 2 }

    # An id is often attached to a non-interactive container (a Border inside an item template),
    # which exposes no Invoke or SelectionItem pattern. Synthesising a mouse click there is unreliable
    # and - as this harness learned the hard way - silently does nothing while still reporting a
    # click. So the pattern is looked for in a fixed order: the element itself, then its ancestors
    # (the GridViewItem that actually owns the click), then the legacy default action, and only then
    # a real mouse click as the last resort.
    $node = $e
    for ($depth = 0; $depth -lt 6 -and $node -ne $null; $depth++) {
      $pat = $null
      if ($node.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pat)) {
        $pat.Invoke(); "INVOKED(depth=$depth) " + $node.Current.Name; break
      }
      if ($node.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pat)) {
        $pat.Select(); "SELECTED(depth=$depth) " + $node.Current.Name; break
      }
      if ($depth -eq 3) {
        $legacy = $null
        if ($node.TryGetCurrentPattern([System.Windows.Automation.LegacyIAccessiblePattern]::Pattern, [ref]$legacy)) {
          $legacy.DoDefaultAction(); "DEFAULT_ACTION " + $node.Current.Name; break
        }
      }
      $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
      $node = $walker.GetParent($node)
    }
    if ($node -eq $null) { "CLICKED(fallback) " + (Click-Element $e) + " " + $e.Current.Name }
  }
  "invoke-name" {
    # Substring match, because a container's automation name is often the ToString of the object it
    # is bound to rather than the label the user sees. Elements that can actually be invoked are
    # preferred so the first match is not an inner TextBlock with no pattern.
    $e = $null
    foreach ($candidate in Get-All) {
      $c = $candidate.Current
      if ($c.IsOffscreen -or -not $c.Name) { continue }
      if ($c.Name -notlike ('*' + $Name + '*')) { continue }
      $pat = $null
      if ($candidate.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pat) -or
          $candidate.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pat)) { $e = $candidate; break }
    }
    if (-not $e) { "NOT_FOUND $Name"; exit 2 }
    $pat = $null
    if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pat)) { $pat.Invoke(); "INVOKED " + $e.Current.Name; break }
    if ($e.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$pat)) { $pat.Select(); "SELECTED " + $e.Current.Name; break }
    "NO_PATTERN " + $e.Current.Name
  }
  "click-xy" {
    Enable-Input
    $p = Get-Process MediaHub -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if ($p) { [MhInput]::SetForegroundWindow($p.MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 350 }
    $parts = $Text.Split(',')
    [MhInput]::SetCursorPos([int]$parts[0], [int]$parts[1]) | Out-Null
    Start-Sleep -Milliseconds 220
    [MhInput]::mouse_event(0x0002,0,0,0,[IntPtr]::Zero)
    [MhInput]::mouse_event(0x0004,0,0,0,[IntPtr]::Zero)
    "CLICKED_XY " + $Text
  }
  "set-range" {
    # Drives a Slider through RangeValuePattern, which is the pattern a slider actually exposes.
    # Setting .Value through a synthetic click would depend on where the thumb happens to be, and a
    # seek test whose target depends on pointer arithmetic is not measuring the seek.
    $e = Find-ByAutoId $AutoId
    if (-not $e) { "NOT_FOUND $AutoId"; exit 2 }
    $pat = $null
    if (-not $e.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pat)) {
      "NO_RANGE_PATTERN " + $e.Current.Name; exit 3
    }
    $target = [double]$Text
    $pat.SetValue($target)
    $now = $pat.Current.Value
    "SET_RANGE " + $e.Current.Name + " requested=" + $target + " actual=" + $now
  }
  "get-range" {
    $e = Find-ByAutoId $AutoId
    if (-not $e) { "NOT_FOUND $AutoId"; exit 2 }
    $pat = $null
    if (-not $e.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pat)) {
      "NO_RANGE_PATTERN " + $e.Current.Name; exit 3
    }
    "RANGE " + $pat.Current.Value + " min=" + $pat.Current.Minimum + " max=" + $pat.Current.Maximum
  }
  "window" {
    $r = $root.Current.BoundingRectangle
    "WINDOW " + [int]$r.X + "," + [int]$r.Y + " " + [int]$r.Width + "x" + [int]$r.Height
  }
  default { "UNKNOWN_ACTION $Action"; exit 4 }
}
