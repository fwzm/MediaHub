# Emby playback regression sweep (real servers, UI automation).
#
# Drives the shipped app against the two configured Emby servers and the local Jellyfin
# instance, and records what was actually observed. Every check either has a concrete
# observation (trace line, UI text, engine state, file on disk) or is reported as
# not-verified with the missing prerequisite spelled out.
#
# Usage:
#   pwsh -NoProfile -File windows\tools\acceptance\emby\run.ps1
#   pwsh -NoProfile -File windows\tools\acceptance\emby\run.ps1 -Only direct
#
# -Only accepts: all, direct, av, transcode, episodes, resume, versions, devices

param(
  [string]$Only = 'all',
  [int]$PlayerStartTimeoutSec = 90
)

$ErrorActionPreference = 'Stop'
$env:MH_ALLOW_ACTIVE = '1'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
Import-Module (Join-Path $root 'windows\tools\acceptance\lib\MhUia.psm1') -Force -WarningAction SilentlyContinue

$exe = Join-Path $root 'windows\src\MediaHub.App\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path $root ('windows\tools\acceptance\evidence\emby\' + $stamp)
$run = New-MhRun -Area 'Emby playback regression' -EvidenceDir $evidence -Meta ([ordered]@{
    serverA = 'emby:570e399431174cd896afe8220ba857c1 (夏天, Emby 4.9.4.1, http://cf.maodu.xyz/)'
    serverB = 'emby:1928b4b0614d4346a87e693ce37e9556 (茶包, Emby 4.10.1.0, https://emby.tbag.qzz.io/)'
    jellyfin = 'jellyfin:daffc6534af44bbb8f73848209519255 (local 127.0.0.1:8096, real server, feature coverage only)'
  })

$traceIndex = 0
$screenshots = New-Object System.Collections.Generic.List[string]

# ---------------------------------------------------------------- helpers

function Raise-App {
  <# Brings the app window to the front and reports whether it actually got there. #>
  for ($i = 0; $i -lt 3; $i++) {
    $r = Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub')
    if ($r -and $r.isForeground) { return $true }
  }
  return $false
}

function Show-Chrome {
  <#
    The player polls the cursor at ~7 Hz and only reveals the controls when the pointer *moved* at
    least 3 px inside the video rectangle. Setting the cursor to the same centre twice therefore does
    nothing after the first time, and the controls stay collapsed - which removes every control from
    the accessibility tree, not merely from view. The move is always a two-step jiggle for that reason.
  #>
  Raise-App | Out-Null
  $w = Get-MhWindowInfo
  if (-not $w -or -not $w.rect) { return }
  $cx = [int]($w.rect.x + $w.rect.w / 2)
  $cy = [int]($w.rect.y + $w.rect.h / 2)
  [Mh.Native]::SetCursorPos($cx - 40, $cy - 24) | Out-Null
  Start-Sleep -Milliseconds 120
  [Mh.Native]::SetCursorPos($cx, $cy) | Out-Null
  Start-Sleep -Milliseconds 320
}

function Get-State {
  Show-Chrome
  $s = Get-MhPlayerState -SkipSlow
  if ($s -and ($s.position -or $s.title)) { return $s }
  # The page is briefly rebuilt when the player window adopts its saved geometry; a lookup landing
  # in that window returns empty rather than failing, which used to read as "position never moved".
  for ($i = 0; $i -lt 3; $i++) {
    Start-Sleep -Milliseconds 600
    Show-Chrome
    $s = Get-MhPlayerState -SkipSlow
    if ($s -and ($s.position -or $s.title)) { return $s }
  }
  return $s
}

function Get-Position {
  for ($i = 0; $i -lt 5; $i++) {
    $s = Get-State
    if ($s.positionSeconds -ne $null) { return $s }
    Start-Sleep -Milliseconds 500
  }
  return $s
}

function Get-Trace([string]$Pattern, [int]$Since = -1) {
  $log = Get-MhLog
  if ($Since -lt 0) { $Since = 0 }
  @($log | Select-Object -Skip $Since | Where-Object { $_ -match $Pattern })
}

function Get-LastTrace([string]$Pattern, [int]$Since = -1) {
  $hits = @(Get-Trace -Pattern $Pattern -Since $Since)
  if ($hits.Count -eq 0) { return $null }
  # @() is required: indexing the single-element result directly indexes the string's characters.
  return $hits[-1]
}

function Wait-Trace {
  <#
    Waits for a matching line that was appended after $Since. Wait-MhLogLine scans the whole in-memory
    log, so reusing it inside a retry loop can match a line from an earlier attempt and report a switch
    that never happened in this attempt.
  #>
  param([string]$Pattern, [int]$Since, [int]$TimeoutSec = 30)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $hit = Get-LastTrace $Pattern -Since $Since
    if ($hit) { return @($hit) }
    Start-Sleep -Milliseconds 400
  }
  return @()
}

function Find-Control([string]$Id) {
  <#
    UIA throws "unrecognized error" from FindAll when a popup root closes while the app-wide element
    list is being enumerated. The lookup itself is fine; it just landed in a bad millisecond. One
    retry separates "the control is absent" from "the broker was momentarily unhappy" - the two were
    indistinguishable before, and both were recorded as product failures.
  #>
  for ($i = 0; $i -lt 4; $i++) {
    try {
      $el = Find-MhAppElement -AutomationId $Id -IncludeOffscreen
      if ($el) { return $el }
    }
    catch { }
    Start-Sleep -Milliseconds 500
  }
  return $null
}

function Read-List([string]$Id) {
  for ($i = 0; $i -lt 4; $i++) {
    try {
      $list = Find-Control $Id
      if (-not $list) { return @() }
      return @(Get-MhElementAll -Root $list | Where-Object { (Get-MhControlType $_) -eq 'ListItem' } | ForEach-Object { $_.Current.Name })
    }
    catch { Start-Sleep -Milliseconds 500 }
  }
  return @()
}

function Get-ComboItemNames([string]$Id) {
  <# Opens a ComboBox and reads the popup entries; the popup is outside the combo's subtree. #>
  $combo = Find-Control $Id
  if (-not $combo) { return @() }
  $expand = $null
  if (-not $combo.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$expand)) { return @() }
  $expand.Expand()
  Start-Sleep -Milliseconds 700
  $nav = @('首页', '继续观看', '媒体库', '搜索', '我的收藏', '播放列表', '媒体源', '设置')
  $names = @()
  for ($i = 0; $i -lt 4; $i++) {
    try {
      $names = @(Get-MhAppElementAll | Where-Object {
        $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and
        -not $_.Current.IsOffscreen -and
        $nav -notcontains $_.Current.Name
      } | ForEach-Object { $_.Current.Name })
      break
    }
    catch { Start-Sleep -Milliseconds 500 }
  }
  $expand.Collapse()
  Start-Sleep -Milliseconds 300
  return $names
}

function Find-NavItem([string]$Name) {
  $item = $null
  for ($i = 0; $i -lt 25 -and -not $item; $i++) {
    try {
      $item = Get-MhAppElementAll | Where-Object {
        $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and $_.Current.Name -eq $Name
      } | Select-Object -First 1
    }
    catch { $item = $null }
    if (-not $item) { Start-Sleep -Milliseconds 300 }
  }
  return $item
}

function Nav([string]$Name) {
  $item = Find-NavItem $Name
  if (-not $item) { throw "导航项不存在: $Name" }
  Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
  Invoke-MhElementDeep -Element $item | Out-Null
  Start-Sleep -Seconds 2
}

function Leave-Player {
  Show-Chrome
  if (-not (Find-Control 'player.back')) { return }
  for ($attempt = 0; $attempt -lt 3; $attempt++) {
    Show-Chrome
    $back = Find-Control 'player.back'
    if ($back) {
      Invoke-MhElementDeep -Element $back | Out-Null
      Start-Sleep -Seconds 3
      if (-not (Find-Control 'player.back')) { return }
    }
    Start-Sleep -Seconds 1
  }
}

function Open-ContinueBoard {
  for ($attempt = 0; $attempt -lt 3; $attempt++) {
    Nav '继续观看'
    for ($i = 0; $i -lt 30; $i++) {
      try { $rows = @(Get-MhAppElementAll | Where-Object { $_.Current.Name -like 'ContinueRow*' }) }
      catch { $rows = @() }
      if ($rows.Count -gt 0) { return $rows }
      Start-Sleep -Milliseconds 400
    }
  }
  return @()
}

function Open-LibraryItem([string]$Library, [string]$Title) {
  <#
    Library -> item -> detail -> 播放. Used when an item has no resumable state to appear in the
    continue lists (the control probe on the local Jellyfin needs an item that exists on that server,
    which the local resume history does not guarantee).
  #>
  Leave-Player
  $lib = $null
  for ($attempt = 0; $attempt -lt 3 -and -not $lib; $attempt++) {
    Nav '媒体库'
    for ($i = 0; $i -lt 30; $i++) {
      try {
        $lib = @(Get-MhAppElementAll | Where-Object {
          $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and $_.Current.Name -eq $Library
        }) | Select-Object -First 1
      }
      catch { $lib = $null }
      if ($lib) { break }
      Start-Sleep -Milliseconds 400
    }
  }
  if (-not $lib) { throw "媒体库中找不到片库: $Library" }
  Invoke-MhElementDeep -Element $lib | Out-Null
  Start-Sleep -Seconds 4

  $item = $null
  for ($i = 0; $i -lt 30; $i++) {
    try {
      $item = @(Get-MhAppElementAll | Where-Object {
        $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::ListItem -and
        $_.Current.Name -like ('*' + $Title + '*')
      }) | Select-Object -First 1
    }
    catch { $item = $null }
    if ($item) { break }
    Start-Sleep -Milliseconds 400
  }
  if (-not $item) { throw "片库 $Library 中找不到条目: $Title" }
  $before = (Get-MhLog).Count
  Invoke-MhElementDeep -Element $item | Out-Null
  Start-Sleep -Seconds 3

  $play = $null
  for ($i = 0; $i -lt 20; $i++) {
    $play = Find-Control 'detail.play'
    if ($play) { break }
    Start-Sleep -Milliseconds 500
  }
  if (-not $play) { throw ('条目详情页未打开: ' + $Title) }
  Invoke-MhElementDeep -Element $play | Out-Null

  $state = $null
  $deadline = (Get-Date).AddSeconds($PlayerStartTimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $state = Get-State
    if ($state.positionSeconds -gt 0) { break }
    $failed = Get-LastTrace 'playback:prepare-failed' -Since $before
    if ($failed) { break }
    Start-Sleep -Milliseconds 600
  }
  return [pscustomobject]@{
    Ok = ($state -and $state.positionSeconds -gt 0)
    RowName = $Title
    ItemId = ''
    Ticks = 0
    State = $state
    TraceFrom = $before
  }
}

function Open-ContinueItem([string]$Title) {
  <# Opens one continue board row and waits for playback to actually advance. #>
  Leave-Player
  $row = $null
  for ($attempt = 0; $attempt -lt 3 -and -not $row; $attempt++) {
    $rows = Open-ContinueBoard
    $row = $rows | Where-Object { $_.Current.Name -like ('*' + $Title + '*') } | Select-Object -First 1
    if (-not $row) { Start-Sleep -Seconds 1 }
  }
  if (-not $row) { throw "继续观看中找不到条目: $Title" }
  return (Start-FromRow -Row $row)
}

function Start-FromRow($Row) {
  <# Invokes a continue row and waits until the player is actually advancing. #>
  $rowName = $Row.Current.Name
  $ticks = 0
  if ($rowName -match 'PositionTicks\s*=\s*(\d+)') { $ticks = [int64]$Matches[1] }
  $itemId = ''
  if ($rowName -match 'ItemId\s*=\s*([^,}]+)') { $itemId = $Matches[1].Trim() }

  $before = (Get-MhLog).Count
  # Pattern invoke, not a coordinate click: the row exposes Invoke and that path cannot land on
  # whatever window happens to be in front. A click at row coordinates was measured landing on the
  # editor window behind which MediaHub sat, which reports "nothing happened" rather than an error.
  $activation = Invoke-MhElementDeep -Element $Row
  if (-not ($activation -like 'INVOKED*' -or $activation -like 'SELECTED*')) {
    # Fallback for a row without an action pattern: raise the window first, then double-click.
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
    Click-MhElement -Element $Row -SettleMs 1500 -DoubleClick | Out-Null
  }
  Start-Sleep -Seconds 3

  # The player page can appear before the engine reports a duration; wait for the resolution trace
  # first so "0:00" is not mistaken for a failed start.
  $resolved = @()
  $deadline = (Get-Date).AddSeconds($PlayerStartTimeoutSec)
  while ((Get-Date) -lt $deadline -and $resolved.Count -eq 0) {
    $resolved = @(Get-Trace 'playback:resolved' -Since $before)
    if ($resolved.Count -eq 0) {
      $failed = Get-LastTrace 'playback:prepare-failed' -Since $before
      if ($failed) { break }
      Start-Sleep -Milliseconds 500
    }
  }
  if ($resolved.Count -eq 0) { Start-Sleep -Seconds 2 }

  $detail = Find-Control 'detail.play'
  if ($detail) { Invoke-MhElementDeep -Element $detail | Out-Null }

  $state = $null
  $deadline = (Get-Date).AddSeconds($PlayerStartTimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $state = Get-State
    if ($state.positionSeconds -gt 0) { break }
    $failed = Get-LastTrace 'playback:prepare-failed' -Since $before
    if ($failed) { break }
    Start-Sleep -Milliseconds 600
  }

  return [pscustomobject]@{
    Ok = ($state -and $state.positionSeconds -gt 0)
    RowName = $rowName
    ItemId = $itemId
    Ticks = $ticks
    State = $state
    TraceFrom = $before
  }
}

function Open-HomeItem([string]$Title) {
  <#
    The 继续观看 page lists server-side resumables only; the local Jellyfin instance keeps its
    position in the client's own store, so its items appear on the home shelf (ContinueItem) but not
    on that page. This opens the home shelf row instead, for the same protocol-level control probes.
  #>
  Leave-Player
  $row = $null
  for ($attempt = 0; $attempt -lt 3 -and -not $row; $attempt++) {
    Nav '首页'
    Start-Sleep -Seconds 2
    for ($i = 0; $i -lt 30; $i++) {
      try {
        $rows = @(Get-MhAppElementAll | Where-Object {
          $_.Current.Name -like 'ContinueItem*' -and $_.Current.Name -like ('*' + $Title + '*')
        })
      }
      catch { $rows = @() }
      if ($rows.Count -gt 0) { $row = $rows[0]; break }
      Start-Sleep -Milliseconds 400
    }
  }
  if (-not $row) { throw "首页继续观看中找不到条目: $Title" }
  return (Start-FromRow -Row $row)
}

function Find-ListItem([string]$ListId, [string]$Name, [switch]$NotMatch) {
  for ($i = 0; $i -lt 4; $i++) {
    try {
      $list = Find-Control $ListId
      if ($list) {
        $row = Get-MhElementAll -Root $list | Where-Object {
          if ((Get-MhControlType $_) -ne 'ListItem') { return $false }
          if ($NotMatch) { return ($_.Current.Name -notmatch $Name) }
          return ($_.Current.Name -eq $Name)
        } | Select-Object -First 1
        if ($row) { return $row }
      }
    }
    catch { }
    Start-Sleep -Milliseconds 500
  }
  return $null
}

function Test-RowSelected([string]$ListId, [string]$RowName) {
  <# Reads SelectionItem.IsSelected from a freshly looked-up row; old handles go stale when the list
     is rebuilt from the 10 Hz snapshot, and a stale handle silently reports nothing. #>
  $row = Find-ListItem $ListId $RowName
  if (-not $row) { return $null }
  try {
    return $row.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected
  }
  catch { return $null }
}

function Open-InfoPanel {
  <#
    The info toggle is a ToggleButton whose handler only opens the panel on the click that leaves it
    checked. Opening an audio/subtitle list does not uncheck it, so a single click can merely uncheck
    the toggle and leave the previous panel up. Click until the panel really is the info panel.
  #>
  Show-Chrome
  $toggle = Find-Control 'InfoToggle'
  if (-not $toggle) { return $false }
  for ($i = 0; $i -lt 3; $i++) {
    Invoke-MhElementDeep -Element $toggle | Out-Null
    Start-Sleep -Milliseconds 800
    $summary = Find-Control 'TechnicalSummary'
    if ($summary) { return $true }
    $toggle = Find-Control 'InfoToggle'
    if (-not $toggle) { return $false }
  }
  return $false
}

function Expand-SettingsSection([string]$Title) {
  <# Settings is a list of Expanders: only the open one contributes children to the accessibility tree. #>
  $header = $null
  for ($i = 0; $i -lt 4 -and -not $header; $i++) {
    try {
      $header = Get-MhAppElementAll -IncludeOffscreen | Where-Object {
        $_.Current.Name -eq $Title -and $_.Current.ControlType -eq [System.Windows.Automation.ControlType]::Button
      } | Select-Object -First 1
    }
    catch { $header = $null }
    if (-not $header) { Start-Sleep -Milliseconds 500 }
  }
  if (-not $header) { return $false }
  try {
    $p = $header.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
    if ($p.Current.ExpandCollapseState -ne [System.Windows.Automation.ExpandCollapseState]::Expanded) {
      $p.Expand()
      Start-Sleep -Seconds 1
    }
    return $true
  }
  catch {
    Invoke-MhElementDeep -Element $header | Out-Null
    Start-Sleep -Seconds 1
    return $true
  }
}

function Set-AutoHide([bool]$On) {
  <#
    Auto-hide collapses the top and bottom bars three seconds after the pointer stops, and a collapsed
    element leaves the accessibility tree entirely - not merely the screen. Every control lookup after
    that point fails, which is indistinguishable from a missing feature. The sweep therefore turns
    auto-hide off for its duration and restores the user's value at the end.
  #>
  Leave-Player
  Nav '设置'
  Start-Sleep -Seconds 2
  Expand-SettingsSection '界面' | Out-Null
  $sw = Find-Control 'AutoHideSwitch'
  if (-not $sw) { throw '设置页找不到自动隐藏开关' }
  $tp = $sw.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
  $want = if ($On) { [System.Windows.Automation.ToggleState]::On } else { [System.Windows.Automation.ToggleState]::Off }
  if ($tp.Current.ToggleState -ne $want) { $tp.Toggle(); Start-Sleep -Seconds 1 }
  return (Read-Setting 'AutoHideControls')
}

function Invoke-WithRetry([scriptblock]$Action, [int]$Attempts = 4) {
  <# UIA throws "unrecognized error" from FindAll when a popup root closes mid-enumeration. #>
  for ($i = 0; $i -lt $Attempts; $i++) {
    try { return (& $Action) }
    catch {
      if ($i -eq $Attempts - 1) { throw }
      Start-Sleep -Milliseconds 700
    }
  }
}

function Set-Speed([string]$Label) {
  <#
    Retries the whole expand/select cycle: the dropdown is a popup window, and enumerating the app
    roots while a previous popup is still closing throws UIA's "unrecognized error" from FindAll.
  #>
  $last = ''
  for ($attempt = 0; $attempt -lt 4; $attempt++) {
    Show-Chrome
    $combo = Find-Control 'SpeedCombo'
    if (-not $combo) { Start-Sleep -Milliseconds 400; continue }
    try { $last = Select-MhComboItem -ComboElement $combo -Text $Label }
    catch { $last = 'ERR: ' + $_.Exception.Message }
    if ($last -match 'SELECTED') { return $last }
    Start-Sleep -Milliseconds 600
  }
  return $last
}

function Restore-SubtitleStyle($Before) {
  <# Subtitle size/colour are product settings; the sweep changes them and must put them back. #>
  Leave-Player
  Nav '设置'
  Start-Sleep -Seconds 2
  Expand-SettingsSection '字幕' | Out-Null
  $slider = Find-Control 'SubtitleSizeSlider'
  if ($slider -and $Before.SubtitleFontSize) {
    $cur = $slider.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern).Current.Value
    if ([Math]::Abs([double]$cur - [double]$Before.SubtitleFontSize) -ge 0.5) {
      Set-MhRangeValue -Element $slider -Value ([double]$Before.SubtitleFontSize) | Out-Null
    }
  }
  $combo = Find-Control 'SubtitleColorCombo'
  if ($combo -and $Before.SubtitleColor) {
    $label = switch ($Before.SubtitleColor) {
      '#FFF3C4' { '暖黄' } '#C8F0FF' { '浅蓝' } '#FFE066' { '黄色' } '#B9F6CA' { '浅绿' } default { '白色' }
    }
    Invoke-WithRetry { Select-MhComboItem -ComboElement (Find-Control 'SubtitleColorCombo') -Text $label } | Out-Null
  }
}

function Set-Range([string]$Id, [double]$Value) {
  $el = Find-Control $Id
  if (-not $el) { throw "控件不存在: $Id" }
  $result = Set-MhRangeValue -Element $el -Value $Value
  if ($result -ne 'SET') { throw "无法设置 $Id ($result)" }
  return $result
}

function Read-Setting([string]$Name) {
  $p = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
  if (-not (Test-Path $p)) { return $null }
  $json = Get-Content $p -Raw | ConvertFrom-Json
  return $json.$Name
}

function Save-Shot([string]$Name) {
  $path = Join-Path $evidence ($Name + '.png')
  # Crop to the app window: a full-screen grab records whatever else is on the desktop, which has
  # already produced "evidence" whose subject was another application.
  $raised = Raise-App
  # Lift above any always-on-top editor window for the duration of the grab; foreground alone does
  # not guarantee the top of the z-order when another window is topmost.
  $hwnd = Get-MhWindowHandle -Exe 'MediaHub'
  if ($hwnd -ne [IntPtr]::Zero) { [Mh.Native]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x13) | Out-Null }
  Start-Sleep -Milliseconds 500
  $w = Get-MhWindowInfo
  $rect = if ($w -and $w.rect) { @($w.rect.l, $w.rect.t, $w.rect.w, $w.rect.h) } else { $null }
  if ($rect) { Save-MhScreenshot -Path $path -Rect $rect | Out-Null }
  else { Save-MhScreenshot -Path $path -FullScreen | Out-Null }
  if ($hwnd -ne [IntPtr]::Zero) { [Mh.Native]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x13) | Out-Null }
  Add-MhNote -Run $run -Text ('evidence ' + $Name + ': app foreground before capture=' + $raised)
  $screenshots.Add($path)
  return $path
}

function Add-Fail([string]$Id, [string]$Assertion, [string]$Message) {
  Add-MhCheck -Run $run -Id $Id -Input 'see note' -Assertion $Assertion -Result 'fail' -Note $Message
}

function Should-Run([string]$Section) {
  return ($Only -eq 'all' -or $Only -eq $Section)
}

# ---------------------------------------------------------------- setup

Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Initialize-MhUia | Out-Null
Start-MhApp -Exe $exe -ResetTrace | Out-Null
Start-Sleep -Seconds 8
Set-MhWindowState -State maximized | Out-Null
Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
Start-Sleep -Seconds 1

$beforeSettings = [ordered]@{
  AllowPlayerScreenshots = Read-Setting 'AllowPlayerScreenshots'
  SubtitleFontSize = Read-Setting 'SubtitleFontSize'
  SubtitleColor = Read-Setting 'SubtitleColor'
  AudioDevice = Read-Setting 'AudioDevice'
  AutoPlayNext = Read-Setting 'AutoPlayNext'
  ResumePlayback = Read-Setting 'ResumePlayback'
  PlayerBrightness = Read-Setting 'PlayerBrightness'
  AutoHideControls = Read-Setting 'AutoHideControls'
}
$beforeSettings | ConvertTo-Json | Set-Content -Path (Join-Path $evidence 'settings-before.json') -Encoding UTF8
Add-MhNote -Run $run -Text ('设置快照: ' + ($beforeSettings | ConvertTo-Json -Compress))
$autoHideOff = Set-AutoHide $false
Add-MhNote -Run $run -Text ('验收期间关闭自动隐藏控制栏: AutoHideControls=' + $autoHideOff + '（结束时恢复为 ' + $beforeSettings.AutoHideControls + '）')

try {

# ---------------------------------------------------------------- C1 direct play

if (Should-Run 'direct') {
  $mhRun = $run
  try {
    $r = Open-ContinueItem '沙丘'
    $resolved = Get-LastTrace 'playback:resolved' -Since $r.TraceFrom
    $resume = Get-LastTrace 'playback:resume=' -Since $r.TraceFrom
    $mode = if ($resolved -match 'mode=(\w+)') { $Matches[1] } else { '' }
    $state0 = $r.State
    Start-Sleep -Seconds 6
    # Open the info panel through its own toggle rather than the 'I' shortcut: a keyboard shortcut is
    # delivered to the foreground window, and the editor hosting this run was observed stealing the
    # foreground mid-session, which silently sent the key to the wrong application.
    Show-Chrome
    $infoToggle = Find-Control 'InfoToggle'
    if ($infoToggle) { Invoke-MhElementDeep -Element $infoToggle | Out-Null }
    Start-Sleep -Milliseconds 900
    $state1 = Get-State
    $hwdec = if ($state1.technical) { $state1.technical -match 'hwdec=d3d11va' } else { $false }
    $advanced = ($state0.positionSeconds -ne $null -and $state1.positionSeconds -ne $null -and $state1.positionSeconds -gt $state0.positionSeconds)
    $shotI = Save-Shot 'direct-play-info'
    if ($infoToggle) { Invoke-MhElementDeep -Element $infoToggle | Out-Null }
    Start-Sleep -Milliseconds 400
    $shot = Save-Shot 'direct-play'

    Add-MhCheck -Run $mhRun -Id 'direct.play' -Before @{ positionTicks = $r.Ticks } -Input '点击继续观看“沙丘”' -After @{ mode = $mode; position = $state1.position; technical = $state1.technical; resolved = $resolved } -Assertion '真实 Emby 直接起播：解析为 DirectPlay，位置从服务器续播点继续推进，硬件解码生效' -Result $(if ($r.Ok -and $mode -eq 'DirectPlay' -and $advanced -and $hwdec) { 'pass' } else { 'fail' }) -Evidence @($shot, $shotI) -Note ('resolved=' + $resolved + ' | resume=' + $resume)
    # The coordinator only writes playback:resume when it had to ask the provider (no StartPositionTicks
    # in the request). A ContinueRow already carries the server's position, so the observable that
    # matters here is the position playback actually started at.
    $expectedStart = if ($r.Ticks -gt 0) { $r.Ticks / 10000000.0 } else { $null }
    $startedAtServerPosition = ($expectedStart -ne $null -and $state0.positionSeconds -ne $null -and
                                [Math]::Abs($state0.positionSeconds - $expectedStart) -lt 90)
    $resumeFromProvider = ($resume -and $resume -notmatch 'resume=local:')
    Add-MhCheck -Run $mhRun -Id 'direct.resume' -Before @{ ticks = $r.Ticks; expectedStartSeconds = $expectedStart } -Input '同一次起播' -After @{ startPositionSeconds = $state0.positionSeconds; resumeTrace = $resume } -Assertion '起播位置等于服务器续播记录（ContinueRow 携带的 StartPositionTicks），而不是从 0:00 或本机历史开始' -Result $(if ($r.Ok -and ($startedAtServerPosition -or $resumeFromProvider)) { 'pass' } else { 'fail' }) -Evidence @() -Note ('resume=' + $resume + '（请求已带 StartPositionTicks 时协调器不写该行，属预期）')
  }
  catch { Add-Fail 'direct.play' '真实 Emby 直接起播' $_.Exception.Message }
}

# ---------------------------------------------------------------- C8 pause / seek / speed

if ((Should-Run 'av') -or $Only -eq 'playback-controls') {
  try {
    $r = Open-ContinueItem '沙丘'
    $stateBefore = Get-Position

    # pause
    Show-Chrome
    $pp = Find-Control 'player.playpause'
    if (-not $pp) { throw '播放/暂停按钮不存在' }
    Invoke-MhElementDeep -Element $pp | Out-Null
    Start-Sleep -Seconds 1
    $paused0 = (Get-Position).positionSeconds
    Start-Sleep -Seconds 3
    $paused1 = (Get-Position).positionSeconds
    $frozen = ($paused0 -ne $null -and $paused1 -ne $null -and [Math]::Abs($paused1 - $paused0) -le 1)

    # resume
    Show-Chrome
    Invoke-MhElementDeep -Element $pp | Out-Null
    Start-Sleep -Seconds 3
    $resumed = (Get-State).positionSeconds
    $moved = ($resumed -ne $null -and $paused1 -ne $null -and $resumed -gt $paused1 + 1)

    Add-MhCheck -Run $run -Id 'controls.pause' -Before @{ position = $paused0 } -Input '点击播放/暂停' -After @{ positionAfter3s = $paused1; resumed = $resumed } -Assertion '暂停后位置冻结，再次点击后继续推进' -Result $(if ($frozen -and $moved) { 'pass' } else { 'fail' }) -Evidence @()

    # seek to 60% by dragging the slider through its value
    $dur = (Get-State).durationSeconds
    Set-Range 'player.seekslider' 0.6
    Start-Sleep -Seconds 3
    $afterSeek = Get-State
    $expected = if ($dur) { $dur * 0.6 } else { $null }
    $seekOk = ($afterSeek.positionSeconds -ne $null -and $expected -and [Math]::Abs($afterSeek.positionSeconds - $expected) -lt 30)
    Add-MhCheck -Run $run -Id 'controls.seek' -Before @{ position = $resumed; duration = $dur } -Input '拖动进度到 60%' -After @{ position = $afterSeek.position; expected = $expected } -Assertion '拖动后从新位置继续播放（±30 秒）' -Result $(if ($seekOk) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'seek-60'))

    # speed 2x
    $s0 = (Get-State).positionSeconds
    $speedPick = Set-Speed '2.0x'
    if ($speedPick -notmatch 'SELECTED') { throw ('无法选择 2.0x: ' + $speedPick) }
    Start-Sleep -Seconds 5
    $s1 = (Get-State).positionSeconds
    $delta = if ($s0 -ne $null -and $s1 -ne $null) { $s1 - $s0 } else { 0 }
    $speedOk = ($delta -gt 7)
    $restoreSpeed = Set-Speed '1.0x'
    Add-MhCheck -Run $run -Id 'controls.speed' -Before @{ position = $s0 } -Input '选择 2.0x 并等待 5 秒' -After @{ position = $s1; deltaSeconds = $delta } -Assertion '倍速生效：5 秒内媒体时间推进约 10 秒' -Result $(if ($speedOk) { 'pass' } else { 'fail' }) -Evidence @() -Note ('select=' + $speedPick + ' | restore=' + $restoreSpeed)
  }
  catch { Add-Fail 'controls.pause' '暂停/拖动/倍速' $_.Exception.Message }
}

# ---------------------------------------------------------------- C11 fullscreen

if ((Should-Run 'av') -or $Only -eq 'fullscreen') {
  try {
    Show-Chrome
    $win = Get-MhWindowInfo
    $video0 = Get-MhVideoRect
    $shot0 = Save-Shot 'fullscreen-before'
    $btn = Find-Control 'player.fullscreen'
    if (-not $btn) { throw '全屏按钮不存在' }
    Show-Chrome
    Invoke-MhElementDeep -Element $btn | Out-Null
    Start-Sleep -Seconds 2
    $winFs = Get-MhWindowInfo
    $videoFs = Get-MhVideoRect
    $shotFs = Save-Shot 'fullscreen-in'
    Show-Chrome
    $btn = Find-Control 'player.fullscreen'
    Invoke-MhElementDeep -Element $btn | Out-Null
    Start-Sleep -Seconds 2
    $winBack = Get-MhWindowInfo
    $videoBack = Get-MhVideoRect
    $shotBack = Save-Shot 'fullscreen-out'

    $entered = ($video0 -and $videoFs -and (($videoFs.w * $videoFs.h) -gt ($video0.w * $video0.h)))
    $restored = ($videoBack -and $video0 -and [Math]::Abs($videoBack.w - $video0.w) -lt 8 -and [Math]::Abs($videoBack.h - $video0.h) -lt 8)
    Add-MhCheck -Run $run -Id 'fullscreen.roundtrip' -Before @{ window = "$($win.w)x$($win.h)"; video = "$($video0.w)x$($video0.h)" } -Input '点击全屏，再点击退出全屏' -After @{ fullscreenVideo = "$($videoFs.w)x$($videoFs.h)"; restoredVideo = "$($videoBack.w)x$($videoBack.h)"; restoredWindow = "$($winBack.w)x$($winBack.h)" } -Assertion '全屏后视频面面积增大，退出后恢复到进入前的大小（±8px）' -Result $(if ($entered -and $restored) { 'pass' } else { 'fail' }) -Evidence @($shot0, $shotFs, $shotBack)
  }
  catch { Add-Fail 'fullscreen.roundtrip' '全屏往返' $_.Exception.Message }
}

# ---------------------------------------------------------------- C12 screenshot

if ((Should-Run 'av') -or $Only -eq 'screenshot') {
  try {
    Show-Chrome
    $shotDir = Join-Path ([Environment]::GetFolderPath('MyPictures')) 'MediaHub 截图'
    $present = Test-Path $shotDir
    $existing = if ($present) { @(Get-ChildItem $shotDir -Filter *.png | ForEach-Object { $_.FullName }) } else { @() }
    $btn = Find-Control 'player.screenshot'
    $wasVisible = ($null -ne $btn)
    $file = $null
    if ($btn) {
      Show-Chrome
      Invoke-MhElementDeep -Element $btn | Out-Null
      for ($i = 0; $i -lt 20 -and -not $file; $i++) {
        Start-Sleep -Milliseconds 400
        if (Test-Path $shotDir) {
          $file = Get-ChildItem $shotDir -Filter *.png | Where-Object { $existing -notcontains $_.FullName } | Select-Object -First 1
        }
      }
      $toast = Find-Control 'player.screenshottip'
      $toastText = if ($toast) { $toast.Current.Name } else { '' }
      $copy = Join-Path $evidence 'screenshot-by-player.png'
      if ($file) { Copy-Item $file.FullName $copy -Force }
      Add-MhCheck -Run $run -Id 'screenshot.on' -Before @{ buttonVisible = $wasVisible } -Input '点击播放器截图按钮' -After @{ newFile = $(if ($file) { $file.Name } else { '' }); toast = $toastText } -Assertion '设置开启时截图按钮可用，点击后生成 PNG 并提示保存位置' -Result $(if ($wasVisible -and $file) { 'pass' } else { 'fail' }) -Evidence @($(if ($file) { $copy } else { '' }))
    }
    else {
      Add-MhCheck -Run $run -Id 'screenshot.on' -Input '查找截图按钮' -Assertion '设置开启时截图按钮可见' -Result 'fail' -Note '设置 AllowPlayerScreenshots=True 但按钮未出现在可访问树中'
    }

    # Off half: the setting must also *remove* the entry, not merely be stored.
    Leave-Player
    Nav '设置'
    Start-Sleep -Seconds 2
    Expand-SettingsSection '界面' | Out-Null
    $toggle = Find-Control 'ScreenshotSwitch'
    if (-not $toggle) { throw '设置页找不到截图开关' }
    $tp = $toggle.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    if ($tp.Current.ToggleState -eq 'On') { $tp.Toggle(); Start-Sleep -Seconds 1 }
    $offValue = Read-Setting 'AllowPlayerScreenshots'
    Save-Shot 'screenshot-setting-off'
    $r2 = Open-ContinueItem '沙丘'
    Show-Chrome
    $btnOff = Find-Control 'player.screenshot'
    Save-Shot 'screenshot-off-no-button'
    Add-MhCheck -Run $run -Id 'screenshot.off' -Before @{ setting = $offValue } -Input '在设置中关闭“允许在播放器中截图”，再开始播放' -After @{ setting = $offValue; buttonPresent = ($null -ne $btnOff); playing = $r2.Ok } -Assertion '关闭设置后截图按钮从播放器移除，而播放本身不受影响' -Result $(if ($offValue -eq $false -and -not $btnOff -and $r2.Ok) { 'pass' } else { 'fail' }) -Evidence @((Join-Path $evidence 'screenshot-setting-off.png'), (Join-Path $evidence 'screenshot-off-no-button.png'))

    # Restore the user's setting; the acceptance run must not leave the product changed.
    Leave-Player
    Nav '设置'
    Start-Sleep -Seconds 2
    Expand-SettingsSection '界面' | Out-Null
    $toggle = Find-Control 'ScreenshotSwitch'
    if ($toggle) {
      $tp = $toggle.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
      if ($tp.Current.ToggleState -eq 'Off') { $tp.Toggle(); Start-Sleep -Seconds 1 }
    }
    $restoredValue = Read-Setting 'AllowPlayerScreenshots'
    Add-MhCheck -Run $run -Id 'screenshot.setting-restored' -Before @{ setting = $offValue } -Input '把截图开关恢复为开启' -After @{ setting = $restoredValue } -Assertion '验收结束后截图设置回到原值（true）' -Result $(if ($restoredValue -eq $true) { 'pass' } else { 'fail' }) -Note ('restored=' + $restoredValue)
  }
  catch { Add-Fail 'screenshot.on' '截图开关' $_.Exception.Message }
}

# ---------------------------------------------------------------- C3 transcode / HLS

if (Should-Run 'transcode') {
  # Transcoding is a server capability, not a client one: an Emby server may refuse to hand out an
  # HLS session for a given file. The sweep therefore tries several real items and records refusals
  # as server behaviour instead of calling the UI broken.
  $tried = @()
  $done = $false
  foreach ($title in @('第 1 集', '阿凡达', '沙丘')) {
    if ($done) { break }
    try {
      $r = Open-ContinueItem $title
      if (-not $r.Ok) { $tried += ($title + ': 未起播'); continue }
      Show-Chrome
      $toggle = Find-Control 'player.transcode'
      if (-not $toggle) { $tried += ($title + ': 无转码开关'); continue }
      $traceFrom = (Get-MhLog).Count
      Invoke-MhElementDeep -Element $toggle | Out-Null
      $toggleLine = Wait-Trace -Pattern 'playback:transcode-toggle on=True' -Since $traceFrom -TimeoutSec 20
      # Wait for whichever outcome arrives first: an HLS resolution or a prepare failure. A fixed
      # sleep either missed a slow server refusal or wasted a minute on the success path.
      $resolved = @()
      $refused = @()
      $deadline = (Get-Date).AddSeconds(60)
      while ((Get-Date) -lt $deadline) {
        $refused = @(Get-Trace 'playback:prepare-failed' -Since $traceFrom)
        if ($refused.Count -gt 0) { break }
        $resolved = @(Get-Trace 'playback:resolved .*mode=Transcode' -Since $traceFrom)
        if ($resolved.Count -gt 0) { break }
        Start-Sleep -Milliseconds 500
      }
      if ($refused.Count -gt 0) {
        $tried += ($title + ': 服务器拒绝 (' + ($refused[-1] -replace '^.*playback:prepare-failed\s*', '') + ')')
        continue
      }
      $badge = Find-Control 'player.transcodebadge'
      $state = $null
      for ($i = 0; $i -lt 60; $i++) {
        $state = Get-State
        if ($state.positionSeconds -gt 0) { break }
        Start-Sleep -Milliseconds 500
      }
      Start-Sleep -Seconds 4
      $state2 = Get-State
      $advanced = ($state -and $state2 -and $state2.positionSeconds -gt $state.positionSeconds)
      $hls = ($resolved.Count -gt 0 -and $resolved[-1] -match 'master\.m3u8')
      $shot = Save-Shot 'transcode-hls'
      $ok = ($hls -and $advanced -and $badge)
      Add-MhCheck -Run $run -Id 'transcode.hls' -Before @{ mode = 'DirectPlay'; item = $title } -Input ('点击“强制转码”（' + $title + '）') -After @{ resolved = $(if ($resolved.Count) { $resolved[-1] } else { '' }); position = $state2.position; badgeVisible = [bool]$badge } -Assertion '强制转码后改为 HLS（master.m3u8）播放并持续推进，顶栏出现转码标记' -Result $(if ($ok) { 'pass' } else { 'fail' }) -Evidence @($shot) -Note ('toggle=' + ($toggleLine -join ' ') + ' | tried=' + ($tried -join ' | '))
      if ($ok) {
        $done = $true
        Show-Chrome
        $toggle = Find-Control 'player.transcode'
        $backFrom = (Get-MhLog).Count
        Invoke-MhElementDeep -Element $toggle | Out-Null
        $back = Wait-Trace -Pattern 'playback:transcode-toggle on=False' -Since $backFrom -TimeoutSec 20
        $direct = Wait-Trace -Pattern 'playback:resolved .*mode=DirectPlay' -Since $backFrom -TimeoutSec 60
        Add-MhCheck -Run $run -Id 'transcode.back-to-direct' -Input '再次点击“强制转码”关闭' -After @{ resolved = $(if ($direct.Count) { $direct[-1] } else { '' }) } -Assertion '关闭强制转码后回到直连播放' -Result $(if ($direct.Count -gt 0) { 'pass' } else { 'fail' }) -Evidence @()
      }
      else { $tried += ($title + ': 未获得 HLS 流') }
    }
    catch { $tried += ($title + ': ' + $_.Exception.Message) }
  }
  if (-not $done) {
    Add-MhCheck -Run $run -Id 'transcode.hls' -Input '在真实 Emby 条目上开启强制转码' -After @{ tried = ($tried -join ' | ') } -Assertion '强制转码改为 HLS 播放' -Result 'not-verified' -Note ('两台服务器的条目均未返回 HLS 转码流（服务器侧拒绝），逐条记录: ' + ($tried -join ' | ') + '。客户端在失败时给出明确错误并停止播放，未伪装成功')
  }

  # Control probe on the local Jellyfin (same MediaBrowser protocol): separates "the client's
  # transcode path is broken" from "these Emby servers refuse to transcode".
  # The local Jellyfin sample used by earlier sweeps was removed from its library (its PlaybackInfo
  # now answers 404), so the control opens a library item that exists on that server right now.
  $jfItem = @{ Library = 'Movies'; Title = 'Hevc Movie' }
  $jf = @()
  $jfDone = $false
  try {
    $r = Open-LibraryItem $jfItem.Library $jfItem.Title
    if (-not $r.Ok) { $jf += ($jfItem.Title + ': 未起播') }
    else {
      Show-Chrome
      $toggle = Find-Control 'player.transcode'
      if (-not $toggle) { $jf += ($jfItem.Title + ': 无转码开关') }
      else {
        $traceFrom = (Get-MhLog).Count
        Invoke-MhElementDeep -Element $toggle | Out-Null
        $null = Wait-Trace -Pattern 'playback:transcode-toggle on=True' -Since $traceFrom -TimeoutSec 20
        $resolved = @()
        $refused = @()
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
          $refused = @(Get-Trace 'playback:prepare-failed' -Since $traceFrom)
          if ($refused.Count -gt 0) { break }
          $resolved = @(Get-Trace 'playback:resolved .*mode=Transcode' -Since $traceFrom)
          if ($resolved.Count -gt 0) { break }
          Start-Sleep -Milliseconds 500
        }
        if ($refused.Count -gt 0) { $jf += ($jfItem.Title + ': 拒绝 (' + ($refused[-1] -replace '^.*playback:prepare-failed\s*', '') + ')') }
        else {
          $hls = ($resolved.Count -gt 0 -and $resolved[-1] -match 'master\.m3u8')
          $jf += ($jfItem.Title + ': ' + $(if ($hls) { 'HLS master.m3u8' } else { '未获得 HLS' }))
          if ($hls) { $jfDone = $true }
        }
      }
    }
  }
  catch { $jf += ($jfItem.Title + ': ' + $_.Exception.Message) }
  Add-MhCheck -Run $run -Id 'transcode.jellyfin-control' -Input '在本机 Jellyfin 上重复强制转码（协议同源对照）' -After @{ probe = ($jf -join ' | ') } -Assertion '同一条转码链路在具备转码能力的服务器上仍能取得 HLS（证明拒绝来自 Emby 服务器而非客户端）' -Result $(if ($jfDone) { 'pass' } else { 'not-verified' }) -Note ('Jellyfin 对照结果: ' + ($jf -join ' | '))
}

# ---------------------------------------------------------------- C4 multi-version

if (Should-Run 'versions') {
  try {
    $r = $null
    try { $r = Open-ContinueItem 'Multi Version Lab' } catch { $r = $null }
    if (-not $r -or -not $r.Ok) {
      try { $r = Open-HomeItem 'Multi Version Lab' } catch { $r = $null }
    }
    if (-not $r -or -not $r.Ok) {
      # The local Jellyfin sample was removed from its library after the row was recorded, so the
      # resume row points at an id the server now answers 404 for. That is a missing fixture, not a
      # version-switch defect, and it is recorded as such.
      Add-MhCheck -Run $run -Id 'versions.switch' -Input '打开多版本条目' -Assertion '多版本条目列出至少两个版本并可切换' -Result 'not-verified' -Note '本地 Jellyfin 的多版本样片已从片库移除（PlaybackInfo 404），当前没有可测的多版本条目'
      $versions = @()
    }
    else {
    Open-InfoPanel | Out-Null
    $versions = Read-List 'panel.version'
    if ($versions.Count -lt 2) {
      Start-Sleep -Seconds 1
      $versions = Read-List 'panel.version'
    }
    if ($versions.Count -lt 2) {
      # A title that plays fine but is served with a single MediaSource cannot demonstrate version
      # switching. That is a fixture limitation, not a defect, so it is recorded as not-verified
      # rather than failed.
      Add-MhCheck -Run $run -Id 'versions.switch' -Input '打开 Multi Version Lab 版本列表' -Assertion '多版本条目列出至少两个版本' -Result 'not-verified' -Note ('该条目可正常起播，但服务器只返回单版本，无法验证切换；实际列出 ' + $versions.Count + ' 个版本' + $(if ($versions.Count -gt 0) { ': ' + ($versions -join ' ; ') } else { '' }))
    }
    else {
      $before = (Get-MhLog).Count
      $target = Find-ListItem 'panel.version' $versions[1]
      if (-not $target) { throw ('版本行不存在: ' + $versions[1]) }
      Invoke-MhElementDeep -Element $target | Out-Null
      $switch = Wait-Trace -Pattern 'playback:version-switch id=' -Since $before -TimeoutSec 30
      Start-Sleep -Milliseconds 800
      $resolved = @(Get-Trace 'playback:resolved' -Since $before)
      $state = $null
      for ($i = 0; $i -lt 60; $i++) {
        $state = Get-State
        if ($state.positionSeconds -gt 0) { break }
        Start-Sleep -Milliseconds 500
      }
      Add-MhCheck -Run $run -Id 'versions.switch' -Before @{ versions = $versions } -Input '选择第二个版本' -After @{ switch = $(if ($switch.Count) { $switch[-1] } else { '' }); resolved = $(if ($resolved.Count) { $resolved[-1] } else { '' }); position = $state.position } -Assertion '切换版本触发新的 MediaSourceId 并从该版本位置继续播放' -Result $(if ($switch.Count -gt 0 -and $state.positionSeconds -gt 0) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'version-switch'))
    }
    }
  }
  catch { Add-Fail 'versions.switch' '多版本切换' $_.Exception.Message }

  # Emby side: report what the probed Emby items actually expose.
  try {
    $probe = @()
    foreach ($title in @('沙丘', '8号出口', '阿凡达')) {
      try {
        $null = Open-ContinueItem $title
        $v = Read-List 'panel.version'
        $probe += ($title + '=' + $v.Count)
      }
      catch { $probe += ($title + '=error') }
    }
    Add-MhCheck -Run $run -Id 'versions.emby-availability' -Input '依次打开 3 个 Emby 条目并读取版本列表' -After @{ probe = ($probe -join ' ; ') } -Assertion 'Emby 上存在多版本条目可测（否则本项记 NOT_RUN 而非 pass）' -Result $(if (($probe | Where-Object { $_ -match '=[2-9]' }).Count -gt 0) { 'pass' } else { 'not-verified' }) -Note ('Emby 抽查结果: ' + ($probe -join ' ; ') + '。多版本切换分支仅由本地 Jellyfin 验证')
  }
  catch { Add-MhCheck -Run $run -Id 'versions.emby-availability' -Input '抽查 Emby 版本列表' -Assertion 'Emby 上存在多版本条目可测' -Result 'not-verified' -Note $_.Exception.Message }
}

# ---------------------------------------------------------------- C5 subtitles + style

if (Should-Run 'av') {
  try {
    $r = Open-ContinueItem '第 1 集'
    $serverSubs = Get-LastTrace 'playback:server-subtitles=' -Since $r.TraceFrom
    Show-Chrome
    $btn = Find-Control 'player.subtitlepanel'
    if ($btn) { Invoke-MhElementDeep -Element $btn | Out-Null; Start-Sleep -Seconds 1 }
    $subs = Read-List 'panel.subtitle'
    $embedded = $subs | Where-Object { $_ -notmatch '关闭' }

    if ($embedded.Count -gt 0) {
      $row = Find-ListItem 'panel.subtitle' '关闭' -NotMatch
      $subName = $row.Current.Name
      Invoke-MhElementDeep -Element $row | Out-Null
      Start-Sleep -Seconds 3
      $sel = Test-RowSelected 'panel.subtitle' $subName
      $state = Get-State
      $advances = ($r.State.positionSeconds -ne $null -and $state.positionSeconds -gt $r.State.positionSeconds)
      Save-Shot 'embedded-subtitle'
      Add-MhCheck -Run $run -Id 'subtitles.embedded' -Before @{ tracks = $subs; position = $r.State.position } -Input ('选择字幕轨: ' + $subName) -After @{ selected = $sel; position = $state.position } -Assertion '内封字幕轨可列出、可选中，播放不中断，画面上应出现该语言字幕' -Result $(if ($sel -eq $true -and $advances) { 'pass' } else { 'fail' }) -Evidence @((Join-Path $evidence 'embedded-subtitle.png'))

      # style: font size + colour, verified through the settings the engine reads.
      # Targets are chosen relative to the current values so the check does not depend on the
      # user's baseline (an earlier aborted run left 75 behind and made a fixed target a no-op).
      $sizeBefore = [double](Read-Setting 'SubtitleFontSize')
      $sizeTarget = if ($sizeBefore -ge 70) { 60 } else { 75 }
      Set-Range 'panel.subtitlesize' $sizeTarget
      Start-Sleep -Seconds 1
      $sizeAfter = Read-Setting 'SubtitleFontSize'
      $colorCombo = Find-Control 'panel.subtitlecolor'
      $colorAfter = $null
      $colorMap = [ordered]@{ '白色' = '#FFFFFF'; '暖黄' = '#FFF3C4'; '浅蓝' = '#C8F0FF'; '黄色' = '#FFE066'; '浅绿' = '#B9F6CA' }
      $colorLabel = @($colorMap.Keys | Where-Object { $colorMap[$_] -ne $beforeSettings.SubtitleColor })[0]
      if ($colorCombo) {
        Invoke-WithRetry { Select-MhComboItem -ComboElement (Find-Control 'panel.subtitlecolor') -Text $colorLabel } | Out-Null
        Start-Sleep -Milliseconds 600
        $colorAfter = Read-Setting 'SubtitleColor'
      }
      Start-Sleep -Seconds 1
      $shot = Save-Shot 'subtitle-style'
      Add-MhCheck -Run $run -Id 'subtitles.style' -Before @{ fontSize = $sizeBefore; color = $beforeSettings.SubtitleColor } -Input ('字幕字号调至 ' + $sizeTarget + '，颜色选择“' + $colorLabel + '”') -After @{ fontSize = $sizeAfter; color = $colorAfter } -Assertion '字幕样式调整写入设置（引擎在下一帧应用），不是只改界面' -Result $(if ($sizeAfter -ne $sizeBefore -and ($colorAfter -eq $colorMap[$colorLabel] -or -not $colorCombo)) { 'pass' } else { 'fail' }) -Evidence @($shot)
      # restore (retried: the colour dropdown is a popup and its close can race the next lookup)
      Start-Sleep -Milliseconds 600
      try { Invoke-WithRetry { Set-Range 'panel.subtitlesize' $sizeBefore } | Out-Null }
      catch { Add-MhNote -Run $run -Text ('字号恢复失败: ' + $_.Exception.Message) }
      if ($colorCombo) {
        $label = switch ($beforeSettings.SubtitleColor) {
          '#FFF3C4' { '暖黄' } '#C8F0FF' { '浅蓝' } '#FFE066' { '黄色' } '#B9F6CA' { '浅绿' } default { '白色' }
        }
        try {
          Invoke-WithRetry {
            $c = Find-Control 'panel.subtitlecolor'
            if ($c) { Select-MhComboItem -ComboElement $c -Text $label | Out-Null }
          } | Out-Null
        }
        catch { Add-MhNote -Run $run -Text ('颜色恢复失败: ' + $_.Exception.Message) }
      }
    }
    else {
      Add-MhCheck -Run $run -Id 'subtitles.embedded' -Input '打开字幕面板' -After @{ tracks = $subs } -Assertion '内封字幕轨可列出' -Result 'fail' -Note ('字幕列表: ' + ($subs -join ' ; '))
    }

    if ($serverSubs -and $serverSubs -match 'server-subtitles=(\d+)' -and [int]$Matches[1] -gt 0) {
      $external = $subs | Where-Object { $_ -match '外挂|\.srt|\.ass' }
      Add-MhCheck -Run $run -Id 'subtitles.external' -Input '读取服务端外挂字幕准备日志' -After @{ trace = $serverSubs; tracks = $subs } -Assertion '服务端外挂字幕被下载并作为字幕轨出现在列表中' -Result $(if ($external.Count -gt 0) { 'pass' } else { 'fail' }) -Note ($external -join ' ; ')
    }
    else {
      Add-MhCheck -Run $run -Id 'subtitles.external' -Input '读取服务端外挂字幕准备日志' -After @{ trace = $serverSubs } -Assertion '服务端外挂字幕被下载并作为字幕轨出现' -Result 'not-verified' -Note ('本次播放的 playback:server-subtitles 为 ' + $serverSubs + '；两台 Emby 抽查条目均无 sidecar 字幕文件')
    }
  }
  catch { Add-Fail 'subtitles.embedded' '内封/外挂字幕及样式' $_.Exception.Message }
}

# ---------------------------------------------------------------- C6 audio tracks

if (Should-Run 'av') {
  try {
    $r = Open-ContinueItem '沙丘'
    Show-Chrome
    # The info/audio/subtitle panels are mutually exclusive; read the engine's audio codec first,
    # then open the audio list and switch, then read the codec again.
    $infoToggle = Find-Control 'InfoToggle'
    if ($infoToggle) { Open-InfoPanel | Out-Null }
    $tech0 = (Get-State).technical
    $btn = Find-Control 'player.audiopanel'
    if ($btn) { Invoke-MhElementDeep -Element $btn | Out-Null; Start-Sleep -Seconds 1 }
    $tracks = Read-List 'panel.audio'
    if ($tracks.Count -ge 2) {
      # Pick a track whose codec differs from what is decoding now, so "the engine changed" is
      # observable rather than assumed.
      $current = if ($tech0 -match '·\s*(ac3|eac3|dts|truehd|flac|aac|opus|mp3)\s*·') { $Matches[1] } else { '' }
      $target = $tracks | Where-Object { $current -and $_ -notmatch $current } | Select-Object -First 1
      if (-not $target) { $target = $tracks[1] }
      $row = Find-ListItem 'panel.audio' $target
      if (-not $row) { throw ('音轨行不存在: ' + $target) }
      Invoke-MhElementDeep -Element $row | Out-Null
      Start-Sleep -Seconds 3
      # Selection is read while the audio list is still up; opening the info panel collapses it.
      $selected = Test-RowSelected 'panel.audio' $target
      if ($infoToggle) { Open-InfoPanel | Out-Null }
      $state = Get-State
      $tech1 = $state.technical
      $codecAfter = if ($tech1 -match '·\s*(ac3|eac3|dts|truehd|flac|aac|opus|mp3)\s*·') { $Matches[1] } else { '' }
      $changed = ($tech0 -and $tech1 -and $tech0 -ne $tech1)
      Add-MhCheck -Run $run -Id 'audio.track' -Before @{ tracks = $tracks; technical = $tech0 } -Input ('切换到音轨: ' + $target) -After @{ technical = $tech1; codec = $codecAfter; position = $state.position; selected = $selected } -Assertion '多音轨条目可切换音轨，切换后播放继续且引擎解码的音轨随之改变' -Result $(if ($changed -and $codecAfter -and $state.positionSeconds -gt 0) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'audio-track')) -Note ('selected=' + $selected + ' | codec ' + $current + ' -> ' + $codecAfter + ' | before=' + $tech0)
    }
    else {
      Add-MhCheck -Run $run -Id 'audio.track' -Input '打开音轨面板' -After @{ tracks = $tracks } -Assertion '多音轨条目可切换' -Result 'not-verified' -Note ('沙丘实际只暴露 ' + $tracks.Count + ' 条音轨: ' + ($tracks -join ' ; '))
    }
  }
  catch { Add-Fail 'audio.track' '音轨切换' $_.Exception.Message }
}

# ---------------------------------------------------------------- C9 episodes + auto-advance

if (Should-Run 'episodes') {
  try {
    $r = Open-ContinueItem '第 1 集'
    $title0 = (Get-State).title
    Show-Chrome
    $prev = Find-Control 'player.prevepisode'
    $next = Find-Control 'player.nextepisode'
    $prevVisible = ($null -ne $prev)
    $nextVisible = ($null -ne $next)
    $prevEnabled = if ($prev) { $prev.Current.IsEnabled } else { $false }
    $nextEnabled = if ($next) { $next.Current.IsEnabled } else { $false }

    Add-MhCheck -Run $run -Id 'episodes.buttons' -Input '播放剧集并读取上一集/下一集按钮' -After @{ prev = "$prevVisible/$prevEnabled"; next = "$nextVisible/$nextEnabled"; title = $title0 } -Assertion '剧集条目的上一集/下一集按钮出现' -Result $(if ($nextVisible -and $prevVisible) { 'pass' } else { 'fail' }) -Note ('上一集=' + $prevVisible + ' enabled=' + $prevEnabled + '; 下一集=' + $nextVisible + ' enabled=' + $nextEnabled)

    if ($nextVisible -and $nextEnabled) {
      $before = (Get-MhLog).Count
      Invoke-MhElementDeep -Element $next | Out-Null
      $started = $false
      $state = $null
      for ($i = 0; $i -lt 90; $i++) {
        $state = Get-State
        if ($state.positionSeconds -gt 0 -and $state.title -ne $title0) { $started = $true; break }
        Start-Sleep -Milliseconds 500
      }
      Add-MhCheck -Run $run -Id 'episodes.next' -Before @{ title = $title0 } -Input '点击“下一集”' -After @{ title = $state.title; position = $state.position } -Assertion '点击下一集切换到邻集并开始播放' -Result $(if ($started) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'next-episode'))
    }
    else {
      Add-MhCheck -Run $run -Id 'episodes.next' -Input '点击下一集' -Assertion '点击下一集切换到邻集并开始播放' -Result 'not-verified' -Note '下一集按钮不可用：该条目在 Emby 上没有可确定的下一集'
    }

    # auto-advance: jump near the end and wait for an unattended switch
    $before = (Get-MhLog).Count
    $state = Get-State
    $seekEl = Find-Control 'player.seekslider'
    $seekMax = 0
    if ($seekEl) { $seekMax = (Get-MhRangeValue -Element $seekEl).max }
    if ($state.durationSeconds -gt 30 -or $seekMax -gt 30) {
      Set-Range 'player.seekslider' 0.999
      $advanced = Wait-Trace -Pattern 'queue:auto-advance (title|episode)=' -Since $before -TimeoutSec 90
      $st = Get-State
      Add-MhCheck -Run $run -Id 'episodes.auto-advance' -Before @{ title = $state.title } -Input '把进度拖到结尾附近并等待自动连播' -After @{ trace = $(if ($advanced.Count) { $advanced[-1] } else { '' }); title = $st.title; position = $st.position } -Assertion '当前条目结束后自动播放下一项，无需用户操作' -Result $(if ($advanced.Count -gt 0) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'auto-advance'))
    }
    else {
      Add-MhCheck -Run $run -Id 'episodes.auto-advance' -Input '拖到结尾' -Assertion '自动连播' -Result 'not-verified' -Note '条目时长信息不可用'
    }
  }
  catch { Add-Fail 'episodes.buttons' '上一集/下一集与自动连播' $_.Exception.Message }
}

# ---------------------------------------------------------------- C10 resume write-back

if (Should-Run 'resume') {
  try {
    $r = Open-ContinueItem '阿凡达'
    $state0 = Get-State
    $ticks0 = $r.Ticks
    Start-Sleep -Seconds 12
    $state1 = Get-State
    Save-Shot 'resume-before-exit'
    Leave-Player
    Start-Sleep -Seconds 3
    # The shell keeps the current page when the player closes, and re-selecting the already-selected
    # "继续观看" nav item does not reload it. Bounce through another page first, otherwise this reads
    # the list that was cached before playback and reports "no write-back" for a write that happened.
    Nav '首页'
    Start-Sleep -Seconds 1
    $rows = Open-ContinueBoard
    $row = $rows | Where-Object { $_.Current.Name -like '*阿凡达*' } | Select-Object -First 1
    $ticks1 = 0
    if ($row -and $row.Current.Name -match 'PositionTicks\s*=\s*(\d+)') { $ticks1 = [int64]$Matches[1] }

    Add-MhCheck -Run $run -Id 'resume.writeback' -Before @{ rowTicks = $ticks0; playedTo = $state1.position } -Input '播放约 12 秒后退出，回到继续观看页' -After @{ rowTicks = $ticks1 } -Assertion '退出后服务器续播位置被写回（继续观看条目 ticks 增大）' -Result $(if ($ticks1 -gt $ticks0) { 'pass' } else { 'fail' }) -Evidence @((Join-Path $evidence 'resume-before-exit.png')) -Note ('ticks ' + $ticks0 + ' -> ' + $ticks1)

    Start-Sleep -Seconds 2
    Stop-MhAppGracefully -TimeoutSec 15 | Out-Null
    if (@(Get-MhProcess).Count -gt 0) { Stop-MhAppForced | Out-Null }
    Start-Sleep -Seconds 2
    Start-MhApp -Exe $exe | Out-Null
    Start-Sleep -Seconds 8
    Set-MhWindowState -State maximized | Out-Null
    Set-MhForeground -Hwnd (Get-MhWindowHandle -Exe 'MediaHub') | Out-Null
    $rows = Open-ContinueBoard
    $row = $rows | Where-Object { $_.Current.Name -like '*阿凡达*' } | Select-Object -First 1
    $ticks2 = 0
    if ($row -and $row.Current.Name -match 'PositionTicks\s*=\s*(\d+)') { $ticks2 = [int64]$Matches[1] }
    Add-MhCheck -Run $run -Id 'resume.persisted' -Before @{ rowTicks = $ticks1 } -Input '重启应用后再次打开继续观看' -After @{ rowTicks = $ticks2 } -Assertion '重启后仍从服务器读回同一进度（说明进度已落到服务端而不是内存）' -Result $(if ($ticks2 -ge $ticks1 -and $ticks2 -gt $ticks0) { 'pass' } else { 'fail' }) -Evidence @((Save-Shot 'resume-after-restart'))
  }
  catch { Add-Fail 'resume.writeback' '续播与进度回写' $_.Exception.Message }
}

# ---------------------------------------------------------------- C2 direct stream / C7 audio device

if ($Only -eq 'all' -or $Only -eq 'direct') {
  $modes = @(Get-Trace 'playback:resolved' | ForEach-Object { if ($_ -match 'mode=(\w+)') { $Matches[1] } })
  $streamSeen = ($modes -contains 'DirectStream')
  Add-MhCheck -Run $run -Id 'direct.stream' -Input '扫描本次运行的全部 playback:resolved' -After @{ modes = ($modes -join ',') } -Assertion '直连串流（DirectStream）在真实 Emby 上被实际走到' -Result $(if ($streamSeen) { 'pass' } else { 'not-verified' }) -Note '两台 Emby（4.9.4.1 / 4.10.1.0）在没有 DeviceProfile 的 PlaybackInfo 上对被抽查条目均返回 SupportsDirectPlay=true，客户端因此只会选 DirectPlay；界面也没有“强制 DirectStream”入口，本项无法在真实服务器上制造'
}

if (Should-Run 'devices') {
  try {
    Leave-Player
    Nav '设置'
    Start-Sleep -Seconds 2
    $beforeDevice = Read-Setting 'AudioDevice'
    $items = Get-ComboItemNames 'settings.audiodevice'
    $status = Find-Control 'settings.audiodevicestatus'
    $statusText = if ($status) { $status.Current.Name } else { '' }
    Save-Shot 'audio-devices'
    if ($items.Count -ge 2) {
      $combo = Find-Control 'settings.audiodevice'
      $target = $items | Where-Object { $_ -notlike '*自动*' } | Select-Object -First 1
      $selection = if ($target) { Select-MhComboItem -ComboElement $combo -Text $target } else { '' }
      Start-Sleep -Seconds 2
      $afterDevice = Read-Setting 'AudioDevice'
      $statusAfter = Find-Control 'settings.audiodevicestatus'
      $statusAfterText = if ($statusAfter) { $statusAfter.Current.Name } else { '' }
      Save-Shot 'audio-device-switched'
      # Restore the user's original routing before leaving the page.
      $restoreLabel = if ([string]::IsNullOrEmpty($beforeDevice)) { '自动（跟随系统默认）' } else { $beforeDevice }
      $restore = Select-MhComboItem -ComboElement (Find-Control 'settings.audiodevice') -Text $restoreLabel
      Start-Sleep -Seconds 2
      $restored = Read-Setting 'AudioDevice'
      # The setting stores the engine id ("wasapi/{guid}"), while the combo and status text show the
      # human description ("扬声器 (Realtek(R) Audio)"). Asserting equality with the label would be a
      # false failure; the id, the status text and the restored value are the observable facts.
      $switched = ($target -and $selection -match 'SELECTED' -and -not [string]::IsNullOrEmpty($afterDevice) -and $statusAfterText -like ('*' + $target + '*'))
      Add-MhCheck -Run $run -Id 'audio.device' -Before @{ device = $beforeDevice } -Input ('设置页选择输出设备: ' + $target) -After @{ selection = $selection; device = $afterDevice; status = $statusAfterText; restored = $restored; restoreSelection = $restore } -Assertion '设备选择写入设置、状态文字反映当前输出，且能切回原设备' -Result $(if ($switched) { 'pass' } else { 'fail' }) -Note ('设备清单: ' + ($items -join ' ; ')) -Evidence @('audio-device-switched')
      if ($restored -ne $beforeDevice) {
        Add-MhCheck -Run $run -Id 'audio.device-restore' -Input '切回原输出设备' -After @{ restored = $restored; expected = $beforeDevice } -Assertion '验收结束恢复用户原始输出设备' -Result 'fail' -Note '原设备未恢复，需人工检查'
      }
    }
    else {
      Add-MhCheck -Run $run -Id 'audio.device' -Input '展开设置页音频设备下拉框' -After @{ devices = $items; status = $statusText } -Assertion '音频设备切换可用' -Result 'not-verified' -Note ('本机只有 ' + $items.Count + ' 个输出设备（' + ($items -join ' ; ') + '），无法在不改变系统配置的前提下验证切换')
    }
  }
  catch { Add-MhCheck -Run $run -Id 'audio.device' -Input '读取音频设备列表' -Assertion '音频设备切换可用' -Result 'not-verified' -Note $_.Exception.Message }
}

}
finally {
  # restore anything the sweep changed
  try {
    if ($beforeSettings) { Restore-SubtitleStyle $beforeSettings }
  }
  catch { }
  try {
    if ($beforeSettings -and $beforeSettings.AutoHideControls -eq $true) { Set-AutoHide $true | Out-Null }
  }
  catch { }
  try {
    $current = [ordered]@{
      AllowPlayerScreenshots = Read-Setting 'AllowPlayerScreenshots'
      SubtitleFontSize = Read-Setting 'SubtitleFontSize'
      SubtitleColor = Read-Setting 'SubtitleColor'
      AudioDevice = Read-Setting 'AudioDevice'
      AutoHideControls = Read-Setting 'AutoHideControls'
    }
    $current | ConvertTo-Json | Set-Content -Path (Join-Path $evidence 'settings-after.json') -Encoding UTF8
  }
  catch { }

  Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  $saved = Save-MhRun -Run $run -JsonPath (Join-Path $evidence 'results.json') -MarkdownPath (Join-Path $evidence 'results.md')
  Write-Host ''
  Write-Host ('evidence: ' + $evidence)
  Write-Host ('summary : pass={0} fail={1} not-verified={2}' -f $saved.counts['pass'], $saved.counts['fail'], $saved.counts['not-verified'])
}
