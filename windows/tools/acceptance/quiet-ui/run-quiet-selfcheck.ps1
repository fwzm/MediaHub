#Requires -Version 7
<#
.SYNOPSIS
End-to-end self-check of run-quiet.ps1's gate / pause / resume loop, without a server or the app.

.DESCRIPTION
Uses two throwaway suite scripts through run-quiet's -SuitePaths test hook:

  u1  finishes immediately and must land in the checkpoint,
  u3  announces itself, sleeps, then finishes - and is the suite the simulated user interrupts.

The driver starts run-quiet, waits until u1 has passed and u3 is running, injects one harmless
synthetic input, and then asserts that run-quiet:
  * logged "用户回来了，已暂停" within a couple of seconds,
  * killed the interrupted suite process,
  * went back to the gate and only resumed after a fresh quiet window,
  * did not run the already-completed u1 a second time,
  * finished with RESULT: OK and both keys in checkpoint.json.

.EXAMPLE
pwsh -File windows/tools/acceptance/quiet-ui/run-quiet-selfcheck.ps1
#>
[CmdletBinding()]
param(
    [int]$MinIdleSeconds = 3,
    [string]$OutRoot = 'D:\mh-verify\run-quiet-selfcheck'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here '..\lib\MhQuiet.psm1') -Force
Import-Module (Join-Path $here '..\lib\MhQuietUi.psm1') -Force

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$root = Join-Path $OutRoot $stamp
$fakeDir = Join-Path $root 'fake'
New-Item -ItemType Directory -Force -Path $fakeDir | Out-Null
$logPath = Join-Path $root 'driver.log'
$failures = New-Object System.Collections.Generic.List[string]

function Note([string]$Text) {
    Add-Content -Path $logPath -Value ((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $Text) -Encoding UTF8
    Write-Host $Text
}
function Check([string]$Name, [bool]$Pass, [string]$Detail) {
    if (-not $Pass) { $failures.Add($Name) }
    Note (('[{0}] {1} :: {2}' -f $(if ($Pass) { 'PASS' } else { 'FAIL' }), $Name, $Detail))
}
function Wait-Until([scriptblock]$Probe, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Probe) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}
function Get-SummaryPath {
    $p = Get-ChildItem -Path $root -Filter 'summary-*.txt' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    if ($p) { return $p.FullName }
    return $null
}
function Get-SummaryText {
    $p = Get-SummaryPath
    if ($p) { return (Get-Content $p -Raw) }
    return ''
}
function Test-Summary([string]$Pattern) {
    $text = Get-SummaryText
    return ($text -match $Pattern)
}

$u1 = Join-Path $fakeDir 'u1.ps1'
$u3 = Join-Path $fakeDir 'u3.ps1'
Set-Content -Path $u1 -Encoding UTF8 -Value @'
param([string]$Server, [string]$Exe)
'fake u1: completes immediately' | Write-Output
'RESULT: 0 failed' | Write-Output
'@
Set-Content -Path $u3 -Encoding UTF8 -Value @'
param([string]$Server, [string]$Exe)
$pid | Set-Content -Path (Join-Path $PSScriptRoot 'u3-pid.txt') -Encoding UTF8
'started' | Set-Content -Path (Join-Path $PSScriptRoot 'u3-started.txt') -Encoding UTF8
Start-Sleep -Seconds 20
'done' | Set-Content -Path (Join-Path $PSScriptRoot 'u3-done.txt') -Encoding UTF8
'RESULT: 0 failed' | Write-Output
'@

$runner = (Resolve-Path (Join-Path $here '..\run-quiet.ps1')).Path
$cmd = "& '$runner' -Suites u1,u3 -Servers emby -MinIdleSeconds $MinIdleSeconds -OutRoot '$root' -SuitePaths @{u1='$u1';u3='$u3'} -NoServers -Exe 'unused' -Checkpoint '$(Join-Path $root 'checkpoint.json')'"
Note ('driver start out=' + $root)
Note ('run-quiet: ' + $cmd)

$runnerProc = Start-Process pwsh -ArgumentList @('-NoProfile', '-Command', $cmd) -PassThru -WindowStyle Hidden
Note ('run-quiet pid=' + $runnerProc.Id)

try {
    Check 'gate.before-run' (Wait-Until { Test-Summary 'gate: attempt=1' } 30) 'first gate attempt logged before any suite'
    Check 'u1.completed' (Wait-Until { Test-Summary 'emby u1 exit=0 RESULT: 0 failed' } 60) 'u1 finished and passed'

    $u3Started = Join-Path $fakeDir 'u3-started.txt'
    Check 'u3.started' (Wait-Until { Test-Path $u3Started } 30) 'second suite is running'
    $u3Pid = 0
    try { $u3Pid = [int]((Get-Content (Join-Path $fakeDir 'u3-pid.txt') -Raw).Trim()) } catch { }
    Note ('u3 pid=' + $u3Pid)

    Start-Sleep -Milliseconds 700
    $inj = Send-MhQuietSyntheticInput
    $injectedAt = Get-Date
    Note ('injected input mode=' + $inj.mode + ' idle=' + $inj.idleAfter + 's cursor ' + $inj.cursorBefore + ' -> ' + $inj.cursorAfter)

    Check 'pause.logged' (Wait-Until { Test-Summary 'PAUSED 用户回来了，已暂停' } 15) 'run-quiet logged the pause'
    Check 'pause.latency' ([int]((Get-Date) - $injectedAt).TotalSeconds -le 8) ('pause seen ' + [int]((Get-Date) - $injectedAt).TotalSeconds + 's after the input')
    Check 'pause.suite-killed' (Wait-Until { -not (Get-Process -Id $u3Pid -ErrorAction SilentlyContinue) } 5) ('interrupted suite pid ' + $u3Pid + ' is gone')

    $u3Done = Join-Path $fakeDir 'u3-done.txt'
    Check 'resume.finished' (Wait-Until { Test-Path $u3Done } 90) 'interrupted suite finished after the resume'
    Check 'runner.exit' (Wait-Until { $runnerProc.HasExited } 30) 'run-quiet process ended'

    $text = Get-SummaryText
    $u1Runs = ([regex]::Matches($text, 'emby u1 exit=')).Count
    $readyCount = ([regex]::Matches($text, 'gate: READY')).Count
    Check 'resume.u1-not-redone' ($u1Runs -eq 1) ('u1 ran ' + $u1Runs + ' time(s)')
    Check 'resume.second-gate' ($readyCount -ge 2) ('gate opened ' + $readyCount + ' time(s)')
    Check 'result.ok' ($text -match 'RESULT: OK') 'final result is OK'

    $cpPath = Join-Path $root 'checkpoint.json'
    $cpText = if (Test-Path $cpPath) { Get-Content $cpPath -Raw } else { '' }
    Check 'checkpoint.both' (($cpText -match 'emby/u1') -and ($cpText -match 'emby/u3')) 'checkpoint contains both completed keys'
}
finally {
    if (-not $runnerProc.HasExited) { Stop-Process -Id $runnerProc.Id -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
    Note ('RESULT: FAILED ' + ($failures -join ', ') + ' log=' + $logPath)
    exit 1
}
Note ('RESULT: OK log=' + $logPath + ' summary=' + (Get-SummaryPath))
exit 0
