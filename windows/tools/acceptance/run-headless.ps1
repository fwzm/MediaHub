#Requires -Version 7
<#
.SYNOPSIS
Runs the GUI acceptance suites in quiet (background) mode: no idle gate, no foreground, no input
injection.

.DESCRIPTION
Differences from run-quiet.ps1, by design:

  * no idle gate and no watchdog - a run never waits for, or aborts on, user input. It cannot
    disturb the user because the window is parked outside the virtual desktop (MEDIAHUB_QUIET_RECT)
    and is shown without activation (MEDIAHUB_QUIET_START=1);
  * every suite is driven through UI Automation patterns and screenshots come from PrintWindow, so
    no step needs the window to be on top;
  * steps that genuinely need a real foreground (system file pickers, coordinate clicks) are
    recorded as NEEDS-FOREGROUND by the suites and skipped instead of being forced on screen;
  * checkpoint granularity is the individual assertion: each Check appends one JSON line to
    steps-<server>-<suite>.jsonl, and checkpoint.json stores the passed/failed/needs-foreground
    step ids per combination. A crash halfway keeps everything already decided.

Exit codes: 0 = every combination PASS (NEEDS-FOREGROUND steps are listed but do not fail the run),
1 = at least one FAIL or UNVERIFIED combination, 2 = could not run (no server).

.EXAMPLE
pwsh -File windows/tools/acceptance/run-headless.ps1 -Suites all
pwsh -File windows/tools/acceptance/run-headless.ps1 -Suites u1,u2 -Servers emby -PreflightOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('u1', 'u2', 'u3', 'u4', 'all')][string[]]$Suites = @('all'),
    [ValidateSet('emby', 'jellyfin')][string[]]$Servers = @('emby', 'jellyfin'),
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$OutRoot = 'D:\mh-verify\headless',
    # Only run the P0 preflight (reset + source seeding) and report what settings.json keeps.
    [switch]$PreflightOnly,
    [int]$ComboTimeoutMinutes = 25,
    [switch]$NoServers,
    [switch]$KeepServers
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
$suiteScripts = @{
    u1 = Join-Path $here 'u1\continue-watching-gui.ps1'
    u2 = Join-Path $here 'u2\autoplay-gui.ps1'
    u3 = Join-Path $here 'u3\favorite-gui.ps1'
    u4 = Join-Path $here 'u4\playlists-gui.ps1'
}
if ($Suites -contains 'all') { $Suites = @('u1', 'u2', 'u3', 'u4') }

$serverPorts = @{ emby = 8097; jellyfin = 8096 }
$serverExe = @{
    emby     = 'D:\mh-servers\emby-install\app\system\EmbyServer.exe'
    jellyfin = 'D:\mh-servers\jellyfin\jellyfin\jellyfin.exe'
}
$serverArgs = @{
    emby     = @('-programdata', 'D:\mh-servers\data\emby', '-nointerface')
    jellyfin = @('--datadir', 'D:\mh-servers\data\jellyfin',
                 '--configdir', 'D:\mh-servers\data\jellyfin\config',
                 '--cachedir', 'D:\mh-servers\data\jellyfin\cache',
                 '--logdir', 'D:\mh-servers\data\jellyfin\log',
                 '--webdir', 'D:\mh-servers\jellyfin\jellyfin\jellyfin-web')
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$stepDir = Join-Path $OutRoot 'steps'
New-Item -ItemType Directory -Force -Path $stepDir | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$summaryPath = Join-Path $OutRoot ('headless-' + $stamp + '.txt')
$checkpointPath = Join-Path $OutRoot 'checkpoint.json'

# The suites consult these: quiet launch + step-level records.
$env:MH_QUIET_UI = '1'
$env:MH_STEP_DIR = $stepDir
if (-not $env:MEDIAHUB_QUIET_RECT) { $env:MEDIAHUB_QUIET_RECT = '3200,200,1280,800' }

$lines = New-Object System.Collections.Generic.List[string]
$startedServers = @{}
$mediaHubBaseline = @(Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })

# Player chrome auto-hides after a few seconds while playing. Nothing moves the pointer in a quiet
# run, so the top/bottom bars would collapse before the first assertion reads the title or the seek
# slider. The older acceptance driver (tools/acceptance/emby/run.ps1) turns auto-hide off for the
# same reason and restores it afterwards; this is a test precondition, not a product bypass.
$settingsPath = Join-Path $env:LOCALAPPDATA 'MediaHub\settings.json'
$autoHideBefore = $null
function Set-HeadlessAutoHide([bool]$Value) {
    if (-not (Test-Path $settingsPath)) { return $false }
    try {
        $cfg = Get-Content $settingsPath -Raw | ConvertFrom-Json
        $cfg.AutoHideControls = $Value
        $cfg | ConvertTo-Json -Depth 12 | Set-Content $settingsPath -Encoding UTF8
        return $true
    }
    catch { return $false }
}

function Add-Line([string]$Text) {
    $lines.Add($Text)
    Write-Host $Text
    Add-Content -Path $summaryPath -Value $Text -Encoding UTF8
}

function Test-PortOpen([int]$Port) {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        if (-not $task.Wait(1500)) { $client.Dispose(); return $false }
        $open = $client.Connected
        $client.Dispose()
        return $open
    }
    catch { return $false }
}

function Test-ServerReady([string]$Name) {
    # An open port is not readiness: Jellyfin listens before authentication is usable, and a suite
    # started in that window fails P0.1 and then cascades into seven unrelated FAILs.
    try {
        $info = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $serverPorts[$Name] + '/System/Info/Public') -TimeoutSec 5 -ErrorAction Stop
        return [bool]$info.Version
    }
    catch { return $false }
}

function Start-HeadlessServer([string]$Name) {
    if ($NoServers) { return [pscustomobject]@{ state = 'skipped'; pid = 0 } }
    if (Test-PortOpen $serverPorts[$Name]) {
        $readyDeadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $readyDeadline) {
            if (Test-ServerReady $Name) { return [pscustomobject]@{ state = 'already-running'; pid = 0 } }
            Start-Sleep -Seconds 2
        }
        return [pscustomobject]@{ state = 'start-timeout'; pid = 0 }
    }
    if (-not (Test-Path $serverExe[$Name])) { return [pscustomobject]@{ state = 'missing-exe'; pid = 0 } }
    $log = Join-Path $OutRoot ('server-' + $Name + '.log')
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $startArgs = @{
            FilePath               = $serverExe[$Name]
            ArgumentList           = $serverArgs[$Name]
            WindowStyle            = 'Hidden'
            PassThru               = $true
            RedirectStandardOutput = $log
            RedirectStandardError  = ($log + '.err')
        }
        $proc = Start-Process @startArgs
        Add-Line ('server ' + $Name + ': launch attempt ' + $attempt + ' pid=' + $proc.Id)
        $deadline = (Get-Date).AddSeconds(180)
        $exitNote = $null
        while ((Get-Date) -lt $deadline) {
            if (Test-PortOpen $serverPorts[$Name]) {
                if (Test-ServerReady $Name) { return [pscustomobject]@{ state = 'started'; pid = $proc.Id } }
            }
            if ($proc.HasExited) { $exitNote = 'pid ' + $proc.Id + ' exited early code=' + $proc.ExitCode; break }
            Start-Sleep -Seconds 2
        }
        if ($null -eq $exitNote) { return [pscustomobject]@{ state = 'start-timeout'; pid = $proc.Id } }
        Add-Line ('server ' + $Name + ': ' + $exitNote)
        Start-Sleep -Seconds 3
    }
    return [pscustomobject]@{ state = 'start-failed'; pid = 0 }
}

function Stop-HeadlessServer([string]$Name) {
    $procName = if ($Name -eq 'emby') { 'EmbyServer' } else { 'jellyfin' }
    Get-Process -Name $procName -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

function Stop-ProcessTree([int]$RootId) {
    if ($RootId -le 0) { return 0 }
    try {
        $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
        $kill = New-Object 'System.Collections.Generic.List[int]'
        $queue = New-Object 'System.Collections.Generic.Queue[int]'
        $queue.Enqueue($RootId)
        $seen = 0
        while ($queue.Count -gt 0 -and $seen -lt 256) {
            $seen++
            $parentId = $queue.Dequeue()
            foreach ($p in $all) {
                if ([int]$p.ParentProcessId -eq $parentId) {
                    $queue.Enqueue([int]$p.ProcessId)
                    $kill.Add([int]$p.ProcessId)
                }
            }
        }
        foreach ($k in @($kill | Sort-Object -Descending)) { Stop-Process -Id $k -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $RootId -Force -ErrorAction SilentlyContinue
        return $kill.Count + 1
    }
    catch { return 0 }
}

function Stop-RunMediaHub {
    $killed = 0
    foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
        if ($mediaHubBaseline -notcontains $p.Id) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            $killed++
        }
    }
    return $killed
}

function Read-StepRecords([string]$Path) {
    if (-not (Test-Path $Path)) { return @() }
    $rows = @()
    foreach ($line in (Get-Content -Path $Path -ErrorAction SilentlyContinue)) {
        if (-not $line.Trim()) { continue }
        try { $rows += ($line | ConvertFrom-Json) } catch { }
    }
    return $rows
}

$combos = @()
foreach ($server in $Servers) {
    foreach ($suite in $Suites) {
        $combos += [pscustomobject]@{ server = $server; suite = $suite; key = ($server + '/' + $suite) }
    }
}

$checkpoint = [ordered]@{ runId = $stamp; mode = 'headless-quiet'; updatedAt = (Get-Date).ToString('o'); combos = [ordered]@{} }
$overallFail = 0

Add-Line ('headless-run ' + $stamp + ' suites=' + ($Suites -join ',') + ' servers=' + ($Servers -join ',') + ' preflight=' + [bool]$PreflightOnly + ' quietRect=' + $env:MEDIAHUB_QUIET_RECT + ' gate=none (input does not abort) out=' + $OutRoot)
Add-Line ('checkpoint=' + $checkpointPath + ' steps=' + $stepDir + ' MediaHub baseline=' + $(if ($mediaHubBaseline.Count) { $mediaHubBaseline -join ',' } else { '<none>' }))

if (Test-Path $settingsPath) {
    try { $autoHideBefore = (Get-Content $settingsPath -Raw | ConvertFrom-Json).AutoHideControls } catch { $autoHideBefore = $null }
    $autoHideNow = Set-HeadlessAutoHide $false
    Add-Line ('precondition AutoHideControls: before=' + $autoHideBefore + ' applied=' + $autoHideNow)
}

try {
    foreach ($combo in $combos) {
        $key = $combo.key
        $server = Start-HeadlessServer $combo.server
        Add-Line ('server ' + $combo.server + ': ' + $server.state)
        if ($server.pid -gt 0) { $startedServers[$combo.server] = $server.pid }
        if ($server.state -in @('missing-exe', 'start-timeout', 'start-failed')) {
            Add-Line ('UNVERIFIED ' + $key + ' reason=server-' + $server.state)
            $checkpoint.combos[$key] = [pscustomobject]@{ status = 'UNVERIFIED'; reason = 'server-' + $server.state; result = ''; exitCode = $null; passed = @(); failed = @(); needsForeground = @() }
            $overallFail++
            continue
        }

        $null = Stop-RunMediaHub
        Start-Sleep -Seconds 2

        $stepFile = Join-Path $stepDir ('steps-' + $combo.server + '-' + $combo.suite + '.jsonl')
        Remove-Item -Path $stepFile -ErrorAction SilentlyContinue
        $log = Join-Path $OutRoot ('run-' + $combo.suite + '-' + $combo.server + '-' + $stamp + '.log')
        $errLog = $log + '.err'
        $argList = @('-NoProfile', '-File', $suiteScripts[$combo.suite], '-Server', $combo.server, '-Exe', $Exe)
        if ($PreflightOnly) { $argList += '-PreflightOnly' }

        $sw = [Diagnostics.Stopwatch]::StartNew()
        $proc = Start-Process pwsh -ArgumentList $argList -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
        $deadline = (Get-Date).AddMinutes($ComboTimeoutMinutes)
        $timedOut = $false
        while (-not $proc.HasExited) {
            if ((Get-Date) -ge $deadline) { $timedOut = $true; $null = Stop-ProcessTree $proc.Id; break }
            Start-Sleep -Milliseconds 500
        }
        $null = $proc.WaitForExit(5000)
        $sw.Stop()
        $elapsed = [math]::Round($sw.Elapsed.TotalSeconds, 1)

        $result = 'NO-RESULT'
        if (Test-Path $log) {
            $hit = Select-String -Path $log -Pattern '^RESULT:' | Select-Object -Last 1
            if ($hit) { $result = $hit.Line }
        }
        $fails = @()
        if (Test-Path $log) { $fails = @(Select-String -Path $log -Pattern '\[FAIL\]' | ForEach-Object { $_.Line }) }
        $steps = @(Read-StepRecords $stepFile)
        $passedSteps = @($steps | Where-Object { $_.status -eq 'PASS' } | ForEach-Object { $_.step })
        $failedSteps = @($steps | Where-Object { $_.status -eq 'FAIL' } | ForEach-Object { $_.step })
        $nfSteps = @($steps | Where-Object { $_.status -eq 'NEEDS-FOREGROUND' } | ForEach-Object { $_.step })

        $exitCode = if ($timedOut) { $null } else { $proc.ExitCode }
        $status = 'UNVERIFIED'
        if ($timedOut) { $status = 'UNVERIFIED' }
        elseif ($exitCode -eq 0 -and $result -match '0 failed') { $status = 'PASS' }
        elseif ($exitCode -ne $null) { $status = 'FAIL' }

        if ($status -ne 'PASS') { $overallFail++ }
        Add-Line ('combo ' + $key + ' status=' + $status + ' exit=' + $(if ($null -eq $exitCode) { 'timeout' } else { $exitCode }) + ' ' + $result + ' elapsed=' + $elapsed + 's steps=' + $steps.Count + ' pass=' + $passedSteps.Count + ' fail=' + $failedSteps.Count + ' needsForeground=' + $nfSteps.Count)
        foreach ($f in $fails) { Add-Line ('    ' + $f) }
        if ($nfSteps.Count -gt 0) { Add-Line ('    NEEDS-FOREGROUND steps: ' + ($nfSteps -join ', ')) }

        $checkpoint.combos[$key] = [pscustomobject]@{
            status          = $status
            timedOut        = $timedOut
            exitCode        = $exitCode
            result          = $result
            elapsedSec      = $elapsed
            log             = $log
            stepsFile       = $stepFile
            passed          = $passedSteps
            failed          = $failedSteps
            needsForeground = $nfSteps
        }
        $checkpoint.updatedAt = (Get-Date).ToString('o')
        $checkpoint | ConvertTo-Json -Depth 8 | Set-Content -Path $checkpointPath -Encoding UTF8
    }
}
finally {
    $null = Stop-RunMediaHub
    if ($null -ne $autoHideBefore) {
        $restored = Set-HeadlessAutoHide ([bool]$autoHideBefore)
        Add-Line ('restore AutoHideControls=' + $autoHideBefore + ' applied=' + $restored)
    }
    if (-not $KeepServers -and -not $NoServers) {
        foreach ($s in @('emby', 'jellyfin')) { Stop-HeadlessServer $s }
    }
    $checkpoint.updatedAt = (Get-Date).ToString('o')
    $checkpoint | ConvertTo-Json -Depth 8 | Set-Content -Path $checkpointPath -Encoding UTF8
    Add-Line ('cleanup done: MediaHub processes=' + @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count + ' serversKept=' + [bool]$KeepServers)
}

$final = @()
foreach ($combo in $combos) {
    $c = $checkpoint.combos[$combo.key]
    $final += ($combo.key + '=' + $c.status + ' (P=' + @($c.passed).Count + ' F=' + @($c.failed).Count + ' NF=' + @($c.needsForeground).Count + ')')
}
Add-Line ('FINAL ' + ($final -join ' | '))
Add-Line ('RESULT: ' + $(if ($overallFail -eq 0) { 'OK all combinations PASS' } else { ('INCOMPLETE/FAILED combinations=' + $overallFail) }))
exit $(if ($overallFail -eq 0) { 0 } else { 1 })
