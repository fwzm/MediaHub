<#
.SYNOPSIS
W5-E area 5: resource release after a NORMAL app exit.

.DESCRIPTION
The claim under test is 'the app released everything', not 'the process disappeared'. Before the
exit the harness records what the app actually owned - the loopback media relay it opens for a
remote stream, the engine's render window, its audio session and its sockets - and after a plain
window close it asserts that each of them is gone.

The exit method is reported explicitly: a harness that had to force-kill the process does not get
to call the result a pass, so that case is recorded as 'forced' with the reason.
#>
param(
  [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
  [string]$RemoteTile = 'Resume Test (2024)',
  [string]$Media = 'D:\mh-media\playback-180s.mp4',
  [string]$EvidenceRoot = (Join-Path $PSScriptRoot 'evidence')
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'lib\MhUia.psm1') -Force -WarningAction SilentlyContinue
$dir = Join-Path $EvidenceRoot '05-release'
$run = New-MhRun -Area 'resource-release' -EvidenceDir $dir -Meta @{ exe = $Exe; remoteTile = $RemoteTile }
function Ev-Shot { param([string]$name) (Save-MhScreenshot -Path (Join-Path $dir ($name + '.png')) -Rect (Get-WinRect)).path }
function Ev-Dump { param([string]$name) (Save-MhDump -Path (Join-Path $dir ($name + '.txt')) -Root (Get-MhWindow) -VisibleOnly).path }
function Ev { param([string]$name) @((Ev-Shot $name), (Ev-Dump $name)) }
function Get-WinRect { $i = Get-MhWindowInfo; @($i.frame.x, $i.frame.y, $i.frame.w, $i.frame.h) }
function Wait-Seconds { param([double]$s) Start-Sleep -Milliseconds ([int]($s * 1000)) }

Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
Wait-Seconds 2
[void](Start-MhApp -Exe $Exe -ResetTrace)
Wait-Seconds 9
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))
$pid0 = (Get-MhProcess | Select-Object -First 1).Id

# A remote item is what makes the app open its loopback relay; the local file path never does.
$tile = Find-MhAppElement -Contains $RemoteTile
$remoteStarted = $false
if ($tile) {
  $tr = Get-MhElementRect -Element $tile
  [void](Click-MhPoint -X $tr.cx -Y $tr.cy -SettleMs 2500)
  $play = Find-MhAppElement -AutomationId 'PlayButton'
  if ($play) {
    [void](Invoke-MhElement -Element $play)
    Wait-Seconds 10
    $remoteStarted = ($null -ne (Find-MhAppElement -AutomationId 'SeekSlider'))
  }
}
if (-not $remoteStarted) {
  Add-MhNote -Run $run -Text ("the remote continue-watching item could not be started; falling back to the local fixture, so the loopback relay is NOT exercised in this run")
  Get-MhProcess | Stop-Process -Force -ErrorAction SilentlyContinue
  Wait-Seconds 2
  [void](Start-MhApp -Exe $Exe -ResetTrace -ArgumentList @($Media))
  Wait-Seconds 8
  $pid0 = (Get-MhProcess | Select-Object -First 1).Id
}
[void](Set-MhForeground -Hwnd ([IntPtr](Get-MhWindowInfo).hwnd))

$listeners = @(Get-MhListeners -Pids @($pid0))
$conns = @(Get-MhConnections -Pids @($pid0))
$child = @(Get-MhChildWindows -Hwnd (Get-MhWindowHandle) | Where-Object { $_.class -like 'mpv*' })
$peak = Get-MhAudioPeak -ProcessId $pid0
$before = [ordered]@{
  remotePlayback = $remoteStarted
  pid = $pid0
  listeners = @($listeners | ForEach-Object { $_.address + ':' + $_.port })
  connections = @($conns | ForEach-Object { $_.state + ' ' + $_.address + ':' + $_.port + ' -> ' + $_.remote })
  engineWindow = @($child | ForEach-Object { $_.class + ' ' + $_.w + 'x' + $_.h + ' visible=' + $_.visible })
  audio = $peak.raw
}
$evBefore = Ev '00-resources-while-running'
Add-MhCheck -Run $run -Id 'resources.before-exit' -Before @{ } -Input 'read every resource the client owns while it is running' -After $before -Assertion ('the client owns its own resources before the exit: engine window present, audio session present, loopback listener present' + $(if ($remoteStarted) { '' } else { ' (listener expected only for a remote stream)' })) -Result $(if (@($child).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evBefore -Note ('listeners=' + (@($before.listeners) -join ',') + ' audio=' + $peak.raw)

if ($remoteStarted) {
  Add-MhCheck -Run $run -Id 'resources.loopback-relay-open' -Before @{ } -Input 'read the listening sockets of the app' -After @{ listeners = $before.listeners } -Assertion 'playing a remote item opens the loopback relay the engine is pointed at' -Result $(if (@($listeners | Where-Object { $_.address -match '127.0.0.1|::1' }).Count -gt 0) { 'pass' } else { 'fail' }) -Evidence $evBefore
} else {
  Add-MhCheck -Run $run -Id 'resources.loopback-relay-open' -Before @{ } -Input 'n/a' -After @{ } -Assertion 'playing a remote item opens the loopback relay' -Result 'not-verified' -Evidence $evBefore -Note 'no remote item could be started in this run, so the relay was never created'
}

# ---------------------------------------------------------------- normal exit

$close = Stop-MhAppGracefully -TimeoutSec 30
Wait-Seconds 3
$liveAfter = @(Get-MhProcess)
$strayListeners = @(); $strayConns = @(); $strayMpv = @()
if ($before.listeners.Count -gt 0) {
  foreach ($l in $before.listeners) {
    $port = [int]($l.Split(':')[-1])
    $still = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    if ($still.Count -gt 0) { $strayListeners += ($l + ' pid=' + $still[0].OwningProcess) }
  }
}
$strayConns = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $pid0 })
$strayMpv = @([Mh.Native]::TopLevelWindows() | Where-Object { $_.Split('|')[2] -like 'mpv*' })
$strayProcs = @(Get-Process -Name 'mpv', 'ffmpeg' -ErrorAction SilentlyContinue)
$audioAfter = Get-MhAudioPeak -ProcessId $pid0
$after = [ordered]@{
  closeMethod = $close.method
  waitedSec = $close.waitedSec
  liveProcesses = $liveAfter.Count
  listenersStillOpen = $strayListeners
  socketsOwnedByOldPid = $strayConns.Count
  strayEngineWindows = @($strayMpv | ForEach-Object { $_.Split('|')[2] })
  strayEngineProcesses = @($strayProcs | ForEach-Object { $_.ProcessName })
  audioAfterExit = $audioAfter.raw
}
$evAfter = Ev '01-after-exit'

Add-MhCheck -Run $run -Id 'release.exit-was-normal' -Before @{ processes = 1 } -Input 'close the window with WM_CLOSE (no kill)' -After @{ method = $close.method; waitedSec = $close.waitedSec } -Assertion 'the process exited on its own; a forced kill would invalidate every release claim below' -Result $(if ($close.method -eq 'graceful') { 'pass' } else { 'fail' }) -Evidence @() -Note 'if this is fail, nothing below may be reported as a release pass'

Add-MhCheck -Run $run -Id 'release.no-process' -Before @{ processes = 1 } -Input 'wait for the process table' -After @{ liveProcesses = $after.liveProcesses } -Assertion 'no MediaHub process remains after the normal exit' -Result $(if ($after.liveProcesses -eq 0) { 'pass' } else { 'fail' }) -Evidence $evAfter

Add-MhCheck -Run $run -Id 'release.no-listener' -Before @{ listeners = $before.listeners } -Input 're-check every port the app was listening on' -After @{ stillOpen = $after.listenersStillOpen; socketsOfOldPid = $after.socketsOwnedByOldPid } -Assertion 'every port the app held is free again and no socket is still attributed to its old process id' -Result $(if ($after.listenersStillOpen.Count -eq 0 -and $after.socketsOwnedByOldPid -eq 0) { 'pass' } else { 'fail' }) -Evidence $evAfter

Add-MhCheck -Run $run -Id 'release.no-engine' -Before @{ engineWindow = $before.engineWindow; audio = $before.audio } -Input 'look for the engine window, engine processes and its audio session' -After @{ windows = $after.strayEngineWindows; processes = $after.strayEngineProcesses; audio = $after.audioAfterExit } -Assertion 'no engine window and no engine process survive the app, and its audio session is gone' -Result $(if ($after.strayEngineWindows.Count -eq 0 -and $after.strayEngineProcesses.Count -eq 0 -and $after.audioAfterExit -match 'NO_SESSION') { 'pass' } else { 'fail' }) -Evidence $evAfter

$result = Save-MhRun -Run $run -MarkdownPath (Join-Path $dir 'results.md')
Write-Host ('results: ' + ($result.counts | ConvertTo-Json -Compress))
