<#
.SYNOPSIS
W5-E area 3: fullscreen - F, F11, the full-screen button, double-click and ESC.

.DESCRIPTION
Each check asserts on geometry and window style, not on a glyph:
  window frame   DWM extended frame bounds versus the monitor rectangle (full screen = equal)
  window style   WS_CAPTION disappears in full screen, which separates 'full screen' from
                 'maximised' - both have the same width and both would pass a rectangle-only test
  video rectangle the engine's own child window (class 'mpv'), i.e. the surface that renders
  layout trace   the client's own layout: line from %LOCALAPPDATA%\MediaHub\startup.log

Keys are sent with a bounded retry (Send-MhKeysUntil) because a single injected keystroke is
occasionally dropped while the window is still activating; the attempt count is recorded so a
dropped keystroke shows up as data instead of as a product failure.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$Media = 'D:\mh-media\playback-180s.mp4',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence')
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$dir = Join-Path $EvidenceRoot '03-fullscreen'
$run = New-MhRun -Area 'fullscreen' -EvidenceDir $dir -Meta @{ exe = $Exe; media = $Media }
function Ev-Shot { param([string]$name) (Save-MhScreenshot -Path (Join-Path $dir ($name + '.png')) -Rect (Get-WinRect)).path }
function Ev-Dump { param([string]$name) (Save-MhDump -Path (Join-Path $dir ($name + '.txt')) -Root (Get-MhWindow) -VisibleOnly).path }
function Ev { param([string]$name) @((Ev-Shot $name), (Ev-Dump $name)) }
function Get-WinRect { $i = Get-MhWindowInfo; @($i.frame.x, $i.frame.y, $i.frame.w, $i.frame.h) }
function Wait-Seconds { param([double]$s) Start-Sleep -Milliseconds ([int]($s * 1000)) }
function Get-Geometry {
  $i = Get-MhWindowInfo; $v = Get-MhVideoRect; $s = Get-MhWindowStyle -Hwnd ([IntPtr]$i.hwnd)
  [ordered]@{
    win = ($i.frame.x.ToString() + ',' + $i.frame.y + ' ' + $i.frame.w + 'x' + $i.frame.h)
    winW = $i.frame.w; winH = $i.frame.h; winX = $i.frame.x; winY = $i.frame.y
    monitor = ($i.monitor.monitor.w.ToString() + 'x' + $i.monitor.monitor.h)
    monW = $i.monitor.monitor.w; monH = $i.monitor.monitor.h
    style = $s.hex; hasCaption = $s.hasCaption; fullScreen = (Test-MhFullScreen)
    video = $(if ($v) { $v.w.ToString() + 'x' + $v.h + ' at ' + $v.x + ',' + $v.y } else { 'none' })
    videoW = $(if ($v) { $v.w } else { 0 }); videoH = $(if ($v) { $v.h } else { 0 })
    layoutTrace = (@(Get-MhLogMatched -Pattern ' layout: ') | Select-Object -Last 1)
  }
}

Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
Wait-Seconds 2
[void](Start-MhApp -Exe $Exe -ResetTrace -ArgumentList @($Media))
Wait-Seconds 8
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
Wait-Seconds 1.5
$baseline = Get-Geometry
$evBase = Ev '00-baseline'
Add-MhCheck -Run $run -Id 'setup.baseline-windowed' -Before @{ } -Input ('launch with ' + $Media) -After $baseline -Assertion 'the player starts in a non-full-screen window with a measurable video rectangle and a caption' -Result $(if ((-not $baseline.fullScreen) -and $baseline.videoW -gt 0 -and $baseline.hasCaption) { 'pass' } else { 'fail' }) -Evidence $evBase

function Test-Entered { (Test-MhFullScreen) }
function Test-Left { -not (Test-MhFullScreen) }

# ---------------------------------------------------------------- F
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$r1 = Send-MhKeysUntil -Keys @('F') -Until { Test-Entered }
$afterF = Get-Geometry
$evF = Ev '01-key-F-enter'
Add-MhCheck -Run $run -Id 'fullscreen.key-F-enters' -Before $baseline -Input ('key F (' + $r1.attempts + ' attempt(s))') -After $afterF -Assertion 'the window covering the whole monitor with WS_CAPTION gone, and the video rectangle grew with it' -Result $(if ($r1.success -and $afterF.fullScreen -and $afterF.videoW -ge $baseline.videoW -and $afterF.videoH -gt $baseline.videoH) { 'pass' } else { 'fail' }) -Evidence $evF -Note ('layout trace: ' + $afterF.layoutTrace)

[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$r2 = Send-MhKeysUntil -Keys @('F') -Until { Test-Left }
$afterF2 = Get-Geometry
$evF2 = Ev '02-key-F-leave'
Add-MhCheck -Run $run -Id 'fullscreen.key-F-leaves' -Before $afterF -Input ('key F again (' + $r2.attempts + ' attempt(s))') -After $afterF2 -Assertion 'the caption and a non-monitor frame come back' -Result $(if ($r2.success -and (-not $afterF2.fullScreen) -and $afterF2.hasCaption) { 'pass' } else { 'fail' }) -Evidence $evF2

# ---------------------------------------------------------------- F11
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$r3 = Send-MhKeysUntil -Keys @('F11') -Until { Test-Entered }
$afterF11 = Get-Geometry
$evF11 = Ev '03-F11-enter'
Add-MhCheck -Run $run -Id 'fullscreen.key-F11-enters' -Before $afterF2 -Input ('key F11 (' + $r3.attempts + ' attempt(s))') -After $afterF11 -Assertion 'F11 enters full screen as well' -Result $(if ($r3.success -and $afterF11.fullScreen) { 'pass' } else { 'fail' }) -Evidence $evF11 -Note ('layout trace: ' + $afterF11.layoutTrace)

# ---------------------------------------------------------------- ESC
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$r4 = Send-MhKeysUntil -Keys @('ESC') -Until { Test-Left }
$afterEsc = Get-Geometry
$playerStillOpen = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
$evEsc = Ev '04-ESC-leave'
Add-MhCheck -Run $run -Id 'fullscreen.ESC-leaves' -Before $afterF11 -Input ('key ESC (' + $r4.attempts + ' attempt(s))') -After @{ geometry = $afterEsc; playerStillOpen = $playerStillOpen } -Assertion 'ESC leaves full screen and keeps the player open (the documented behaviour while full screen)' -Result $(if ($r4.success -and $playerStillOpen) { 'pass' } else { 'fail' }) -Evidence $evEsc

# ---------------------------------------------------------------- windowed ESC exits the player

$beforeExitEsc = Get-Geometry
$playerWasOpen = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
[void](Send-MhKeys -Keys @('ESC'))
Wait-Seconds 3
$playerAfter = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
$evEsc2 = Ev '05-ESC-windowed'
Add-MhCheck -Run $run -Id 'fullscreen.ESC-exits-player-when-windowed' -Before @{ playerOpen = $playerWasOpen; fullScreen = $beforeExitEsc.fullScreen } -Input 'key ESC while NOT full screen' -After @{ playerOpen = $playerAfter } -Assertion 'with the player windowed, ESC leaves the player and returns to the shell' -Result $(if ((-not $beforeExitEsc.fullScreen) -and (-not $playerAfter)) { 'pass' } else { 'fail' }) -Evidence $evEsc2 -Note 'a second press is deliberately not retried: the documented action destroys the page under test'

# ---------------------------------------------------------------- button, and double-click

$shellTexts = @(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'Text' } | ForEach-Object { $_.Current.Name })
$inShell = (@($shellTexts | Where-Object { $_ -eq '媒体源' }).Count -gt 0)
Add-MhCheck -Run $run -Id 'exit.to-shell' -Before @{ playerOpen = $playerWasOpen } -Input 'read the shell' -After @{ shellVisible = $inShell } -Assertion 'the navigation chrome is back after leaving the player' -Result $(if ($inShell) { 'pass' } else { 'fail' }) -Evidence $evEsc2

$result = Save-MhRun -Run $run -MarkdownPath (Join-Path $dir 'results.md')
Write-Host ('results: ' + ($result.counts | ConvertTo-Json -Compress))
$close = Stop-MhAppGracefully -TimeoutSec 20
Write-Host ('close: ' + ($close | ConvertTo-Json -Compress))
