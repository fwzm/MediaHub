<#
.SYNOPSIS
W5-E area 1: lifecycle - minimise, restore, resize, leave the player, close the app, restart.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$Media = 'D:\mh-media\playback-180s.mp4',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence')
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$dir = Join-Path $EvidenceRoot '01-lifecycle'
$run = New-MhRun -Area 'lifecycle' -EvidenceDir $dir -Meta @{ exe = $Exe; media = $Media }
function Ev-Shot { param([string]$name) (Save-MhScreenshot -Path (Join-Path $dir ($name + '.png')) -Rect (Get-WinRect)).path }
function Ev-Dump { param([string]$name) (Save-MhDump -Path (Join-Path $dir ($name + '.txt')) -Root (Get-MhWindow) -VisibleOnly).path }
function Ev { param([string]$name) @((Ev-Shot $name), (Ev-Dump $name)) }
function Get-WinRect { $i = Get-MhWindowInfo; @($i.frame.x, $i.frame.y, $i.frame.w, $i.frame.h) }
function Wait-Seconds { param([double]$s) Start-Sleep -Milliseconds ([int]($s * 1000)) }
function Get-Lifecycle {
  $i = Get-MhWindowInfo
  [ordered]@{
    processes = @(Get-MhProcess).Count
    visible = $(if ($i) { $i.visible } else { $false })
    iconic = $(if ($i) { $i.isIconic } else { $null })
    rect = $(if ($i) { $i.frame.w.ToString() + 'x' + $i.frame.h + ' at ' + $i.frame.x + ',' + $i.frame.y } else { 'none' })
    playerOpen = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
    shellVisible = (@(Get-MhAppElementAll | Where-Object { (Get-MhControlType $_) -eq 'ListItem' -and $_.Current.Name -eq ([char]0x5A92 + [char]0x4F53 + [char]0x6E90) }).Count -gt 0)
    video = $(if (Get-MhVideoRect) { (Get-MhVideoRect).w.ToString() + 'x' + (Get-MhVideoRect).h } else { 'none' })
  }
}

Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
Wait-Seconds 2
[void](Start-MhApp -Exe $Exe -ResetTrace -ArgumentList @($Media))
Wait-Seconds 8
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
Wait-Seconds 1
$start = Get-Lifecycle
$evStart = Ev '00-started'
Add-MhCheck -Run $run -Id 'start.running' -Before @{ } -Input ('launch with ' + $Media) -After $start -Assertion 'exactly one live client process with a visible window, the player open, and a rendering video rectangle' -Result $(if ($start.processes -eq 1 -and $start.visible -and $start.playerOpen -and $start.video -ne 'none') { 'pass' } else { 'fail' }) -Evidence $evStart

# ---------------------------------------------------------------- minimise / restore

$minResult = Set-MhWindowState -State 'minimized'
Wait-Seconds 1.5
$min = Get-Lifecycle
Add-MhCheck -Run $run -Id 'window.minimize' -Before $start -Input ('UIA WindowPattern.SetWindowVisualState(Minimized) -> ' + $minResult) -After $min -Assertion 'the window reports itself as minimised and the process stays alive' -Result $(if ($min.iconic -and $min.processes -eq 1) { 'pass' } else { 'fail' }) -Evidence @()

$resResult = Set-MhWindowState -State 'normal'
Wait-Seconds 1.5
$res = Get-Lifecycle
Add-MhCheck -Run $run -Id 'window.restore' -Before $min -Input ('SetWindowVisualState(Normal) -> ' + $resResult) -After $res -Assertion 'the window is no longer minimised and the video surface came back' -Result $(if ((-not $res.iconic) -and $res.video -ne 'none') { 'pass' } else { 'fail' }) -Evidence (Ev '01-restored')

$newRect = Set-MhWindowSize -X 60 -Y 60 -Width 900 -Height 620
$sized = Get-Lifecycle
$okSize = ([Math]::Abs($newRect.w - 900) -le 20 -and [Math]::Abs($newRect.h - 620) -le 20)
Add-MhCheck -Run $run -Id 'window.resize' -Before $res -Input 'SetWindowPos 900x620 at 60,60' -After @{ rect = ($newRect.w.ToString() + 'x' + $newRect.h + ' at ' + $newRect.x + ',' + $newRect.y); state = $sized } -Assertion 'the window takes the requested size (within 20 px of the DWM frame) and the video rectangle follows' -Result $(if ($okSize -and $sized.video -ne 'none') { 'pass' } else { 'fail' }) -Evidence (Ev '02-resized')

$sizedInfo = Get-MhWindowInfo
$videoAfterResize = Get-MhVideoRect
Add-MhCheck -Run $run -Id 'window.video-follows-resize' -Before @{ video = $res.video } -Input 'read the engine child window after the resize' -After @{ video = ($videoAfterResize.w.ToString() + 'x' + $videoAfterResize.h); windowClientWidth = $sizedInfo.rect.w } -Assertion 'the engine surface was re-placed to the new window size (its width tracks the window, not the old one)' -Result $(if ($videoAfterResize.w -le ($sizedInfo.rect.w + 8) -and $videoAfterResize.w -gt 200) { 'pass' } else { 'fail' }) -Evidence (Ev '03-video-follows')

# ---------------------------------------------------------------- leave the player

$inv = Invoke-MhElement -Element (Find-MhAppElement -AutomationId 'player.back')
Wait-Seconds 2.5
$left = Get-Lifecycle
Add-MhCheck -Run $run -Id 'player.close-returns-to-shell' -Before $sized -Input ('invoke player.back (' + $inv + ')') -After $left -Assertion 'the player page is torn down (no seek bar) and the navigation chrome is back' -Result $(if ((-not $left.playerOpen) -and $left.shellVisible) { 'pass' } else { 'fail' }) -Evidence (Ev '04-back-to-shell') -Note ('engine child window after leaving the player: ' + $left.video + ' - the window object outlives the page; whether it is still visible is measured in the release area (05)')

# ---------------------------------------------------------------- close and restart

$close = Stop-MhAppGracefully -TimeoutSec 25
Wait-Seconds 2
$closed = Get-Lifecycle
Add-MhCheck -Run $run -Id 'app.close-normal' -Before $left -Input 'close the window (WM_CLOSE)' -After @{ close = $close; state = $closed } -Assertion 'the app exits on a normal window close without the harness force-killing it' -Result $(if ($close.method -eq 'graceful' -and $closed.processes -eq 0) { 'pass' } else { 'fail' }) -Evidence @()

$app2 = Start-MhApp -Exe $Exe -ResetTrace
Wait-Seconds 9
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$restart = Get-Lifecycle
$evRestart = Ev '05-restarted'
Add-MhCheck -Run $run -Id 'app.restart' -Before $closed -Input 'relaunch' -After $restart -Assertion 'the app comes back with a visible window, the shell shown, and the player NOT auto-opened' -Result $(if ($restart.processes -eq 1 -and $restart.visible -and $restart.shellVisible -and -not $restart.playerOpen) { 'pass' } else { 'fail' }) -Evidence $evRestart

$result = Save-MhRun -Run $run -MarkdownPath (Join-Path $dir 'results.md')
Write-Host ('results: ' + ($result.counts | ConvertTo-Json -Compress))
$close2 = Stop-MhAppGracefully -TimeoutSec 20
Write-Host ('close: ' + ($close2 | ConvertTo-Json -Compress))
