#Requires -Version 7
<#
.SYNOPSIS
Runs the MediaHub GUI acceptance suites without disturbing whoever is using this machine.

.DESCRIPTION
Every suite here drives a real WinUI window through UIA, which means it needs an unlocked interactive
session and the window has to be visible: there is no supported way to automate a window on a desktop
the user cannot see. So "do not disturb" is implemented as a contract instead of a trick:

  * idle gate - nothing starts until the machine has had no keyboard/mouse/touch input for
    -MinIdleSeconds (default 1800 s = 30 min). The gate logs how long it has been waiting and why;
    it also refuses a locked screen, a disconnected session, a switched-away user and a clock jump
    consistent with sleep. Waiting is unlimited by default; -WaitForIdleMinutes N optionally caps it.
  * pause on return - while a suite runs, a background job re-checks input every second. The moment
    the user touches the machine (or the session locks) it kills only this run's process tree, the
    driver closes the app and stops the servers it started, logs "用户回来了，已暂停", and goes back
    to the gate. The next run resumes after another continuous quiet window.
  * checkpoint - completed (server, suite) pairs are written to checkpoint.json, so a pause costs at
    most the suite that was running, never the suites that already passed.
  * no leftovers - the app is killed between suites and the test media servers are stopped at the end
    unless -KeepServers is passed.

What the gate cannot see: GetLastInputInfo only observes input activity. Watching a video, reading a
document or being connected remotely produces no input, so "no input" is not "not using the PC". The
gate is therefore one of four layers; the other three are in lib\MhQuietUi.psm1 (non-activating
launch, UIA pattern calls, PrintWindow capture) and are exercised by quiet-ui\verify.ps1.

-DevOverrideIdleGate exists for developing the harness itself: it skips the idle requirement and the
input abort so a run can proceed on a machine that is in use. A run made with it is not acceptance
evidence and is logged as such. The locked/session/sleep checks still apply.

Exit codes: 0 = ran and every combination passed, 1 = at least one suite failed, 2 = stopped with
combinations still pending (only if a pause could not resume), 3 = the idle gate never opened
(SKIPPED-GATE: nothing was measured, so it is not a pass and must not be counted as one).

.EXAMPLE
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u1,u2 -Servers emby

.EXAMPLE
# Wait indefinitely for a 30-minute quiet window; the run pauses the moment the user comes back
# and resumes from checkpoint.json in the next window.
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites all
#>
[CmdletBinding()]
param(
    [ValidateSet('u1', 'u2', 'u3', 'u4', 'all')][string[]]$Suites = @('all'),
    [ValidateSet('emby', 'jellyfin')][string[]]$Servers = @('emby', 'jellyfin'),
    [int]$MinIdleSeconds = 1800,
    # 0 = wait indefinitely for the quiet window (the required behaviour). A positive value caps the
    # wait and turns "still busy" into a clean skip instead of an endless gate.
    [int]$WaitForIdleMinutes = 0,
    [switch]$DevOverrideIdleGate,
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$OutRoot = 'D:\mh-verify\quiet',
    [string]$Checkpoint = '',
    # Test hook: replaces individual suite scripts (used by quiet-ui\run-quiet-selfcheck.ps1 to
    # exercise the gate / pause / resume loop without a server or the app).
    [hashtable]$SuitePaths = @{},
    [switch]$NoServers,
    [switch]$KeepServers
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here 'lib\MhQuiet.psm1') -Force
Import-Module (Join-Path $here 'lib\MhQuietUi.psm1') -Force

# Not $suitePaths: PowerShell variables are case-insensitive, so that would alias -SuitePaths.
$suiteScripts = @{
    u1 = Join-Path $here 'u1\continue-watching-gui.ps1'
    u2 = Join-Path $here 'u2\autoplay-gui.ps1'
    u3 = Join-Path $here 'u3\favorite-gui.ps1'
    u4 = Join-Path $here 'u4\playlists-gui.ps1'
}
foreach ($key in $SuitePaths.Keys) { $suiteScripts[[string]$key] = [string]$SuitePaths[$key] }

if ($Suites -contains 'all') { $Suites = @('u1', 'u2', 'u3', 'u4') }

$serverPorts = @{ emby = 8097; jellyfin = 8096 }
$serverExe = @{
    emby     = 'D:\mh-servers\emby-install\app\system\EmbyServer.exe'
    jellyfin = 'D:\mh-servers\jellyfin\jellyfin\jellyfin.exe'
}
$serverArgs = @{
    # Emby ignores -datadir (it silently falls back to app\programdata, binds 8096 and opens a browser);
    # -programdata is the switch that actually selects the lab instance on 8097.
    emby     = @('-programdata', 'D:\mh-servers\data\emby', '-nointerface')
    jellyfin = @('--datadir', 'D:\mh-servers\data\jellyfin',
                 '--configdir', 'D:\mh-servers\data\jellyfin\config',
                 '--cachedir', 'D:\mh-servers\data\jellyfin\cache',
                 '--logdir', 'D:\mh-servers\data\jellyfin\log',
                 '--webdir', 'D:\mh-servers\jellyfin\jellyfin\jellyfin-web')
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$summaryPath = Join-Path $OutRoot ('summary-' + $stamp + '.txt')
if (-not $Checkpoint) { $Checkpoint = Join-Path $OutRoot 'checkpoint.json' }
$lines = New-Object System.Collections.Generic.List[string]
$watch = $null
$startedServers = @{}
# Only processes that appear after this point belong to this run; a concurrent acceptance session
# (someone else's MediaHub) must survive our pause cleanup.
$mediaHubBaseline = @(Get-Process MediaHub -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })

function Add-Line([string]$Text) {
    $lines.Add($Text)
    Write-Host $Text
    # Append one line at a time: rewriting the whole file loses the tail if a run is killed mid-write.
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

function Start-QuietServer([string]$Name) {
    # Emby may launch its self-updater and exit on the first start after it staged an update, so a
    # single attempt is not enough: retry once when the process dies before the port opens.
    if (Test-PortOpen $serverPorts[$Name]) { return [pscustomobject]@{ state = 'already-running'; pid = 0 } }
    if (-not (Test-Path $serverExe[$Name])) { return [pscustomobject]@{ state = 'missing-exe'; pid = 0 } }

    $log = Join-Path $OutRoot ('server-' + $Name + '.log')
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $startArgs = @{
            FilePath = $serverExe[$Name]
            ArgumentList = $serverArgs[$Name]
            WindowStyle = 'Hidden'
            PassThru = $true
            RedirectStandardOutput = $log
            RedirectStandardError = ($log + '.err')
        }
        $proc = Start-Process @startArgs
        Add-Line ('server ' + $Name + ': launch attempt ' + $attempt + ' pid=' + $proc.Id)

        $deadline = (Get-Date).AddSeconds(150)
        $exitNote = $null
        while ((Get-Date) -lt $deadline) {
            if (Test-MhQuietUiAborted $watch) { return [pscustomobject]@{ state = 'aborted'; pid = $proc.Id } }
            if (Test-PortOpen $serverPorts[$Name]) { return [pscustomobject]@{ state = 'started'; pid = $proc.Id } }
            if ($proc.HasExited) { $exitNote = 'pid ' + $proc.Id + ' exited early code=' + $proc.ExitCode; break }
            Start-Sleep -Seconds 2
        }
        if ($null -eq $exitNote) { return [pscustomobject]@{ state = 'start-timeout'; pid = $proc.Id } }
        Add-Line ('server ' + $Name + ': ' + $exitNote)
        Start-Sleep -Seconds 3
    }
    return [pscustomobject]@{ state = 'start-failed'; pid = 0 }
}

function Stop-QuietServer([string]$Name) {
    $procName = if ($Name -eq 'emby') { 'EmbyServer' } else { 'jellyfin' }
    Get-Process -Name $procName -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

function Stop-ProcessTree([int]$RootId) {
    # The suite is a child pwsh that starts MediaHub; killing only the suite can leave the app behind.
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
        foreach ($k in @($kill | Sort-Object -Descending)) {
            Stop-Process -Id $k -Force -ErrorAction SilentlyContinue
        }
        Stop-Process -Id $RootId -Force -ErrorAction SilentlyContinue
        return $kill.Count + 1
    }
    catch { return 0 }
}

function Stop-RunLeftovers([string]$RootPidFile) {
    # Everything the user's return must take down: the suite tree, any MediaHub this run started,
    # and the lab servers this run launched. Idempotent.
    $killed = 0
    if ($RootPidFile -and (Test-Path $RootPidFile)) {
        $rootId = 0
        try { $rootId = [int]((Get-Content -Path $RootPidFile -Raw).Trim()) } catch { }
        # The watchdog usually got here first. Enumerating the tree again is expensive, so only do
        # it when the suite is still alive.
        if ($rootId -gt 0 -and (Get-Process -Id $rootId -ErrorAction SilentlyContinue)) {
            $killed += Stop-ProcessTree $rootId
        }
    }
    foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
        if ($mediaHubBaseline -notcontains $p.Id) {
            Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
            $killed++
        }
    }
    foreach ($name in @($startedServers.Keys)) {
        $serverPid = [int]$startedServers[$name]
        if ($serverPid -gt 0 -and (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
            $killed++
        }
        $startedServers.Remove($name)
    }
    return $killed
}

# ---------------------------------------------------------------- checkpoint
$cp = Read-MhQuietCheckpoint -Path $Checkpoint
$completed = New-Object System.Collections.Generic.List[string]
foreach ($k in @($cp.completed)) { if ($k) { $completed.Add([string]$k) } }
$failedKeys = New-Object System.Collections.Generic.List[string]
$cpFailed = if ($cp.PSObject.Properties.Name -contains 'failed') { @($cp.failed) } else { @() }
foreach ($k in $cpFailed) { if ($k) { $failedKeys.Add([string]$k) } }

function Save-Checkpoint {
    [pscustomobject]@{
        runId     = $stamp
        updatedAt = (Get-Date).ToString('o')
        completed = @($completed | Sort-Object -Unique)
        failed    = @($failedKeys | Sort-Object -Unique)
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $Checkpoint -Encoding UTF8
}

$combos = @()
foreach ($server in $Servers) {
    foreach ($suite in $Suites) {
        $combos += [pscustomobject]@{ server = $server; suite = $suite; key = ($server + '/' + $suite) }
    }
}

$idleNow = Get-MhUserIdleSeconds
$waitCapText = if ($WaitForIdleMinutes -gt 0) { ([string]$WaitForIdleMinutes) + 'min' } else { 'none' }
Add-Line ('quiet-run ' + $stamp + ' suites=' + ($Suites -join ',') + ' servers=' + ($Servers -join ',') + ' idleNow=' + $idleNow + 's threshold=' + $MinIdleSeconds + 's waitCap=' + $waitCapText + ' checkpoint=' + $Checkpoint)
$already = @($combos | Where-Object { $completed -contains $_.key } | ForEach-Object { $_.key })
Add-Line ('checkpoint: already done=' + $(if ($already.Count) { $already -join ',' } else { '<none>' }) + ' pending=' + (@($combos | Where-Object { $completed -notcontains $_.key }).Count))

$transcriptPath = Join-Path $OutRoot ('transcript-' + $stamp + '.log')
$pausedCount = 0
$gateAttempt = 0
$gateTimedOut = $false

try {
    Start-Transcript -Path $transcriptPath -Force | Out-Null
}
catch { }

try {
    while ($true) {
        $pending = @($combos | Where-Object { $completed -notcontains $_.key })
        if ($pending.Count -eq 0) { break }

        # ---- gate ----------------------------------------------------------
        $gateAttempt++
        if ($DevOverrideIdleGate) {
            Add-Line ('gate: DEV-OVERRIDE attempt=' + $gateAttempt + ' idle=' + (Get-MhUserIdleSeconds) + 's need=' + $MinIdleSeconds + 's - development run only, not acceptance evidence')
        }
        else {
            Add-Line ('gate: attempt=' + $gateAttempt + ' idle=' + (Get-MhUserIdleSeconds) + 's need=' + $MinIdleSeconds + 's pending=' + ($pending.Count) + ' - waiting for a continuous quiet window')
            $gate = Wait-MhQuietGate -MinIdleSeconds $MinIdleSeconds -TimeoutSeconds ($WaitForIdleMinutes * 60) -LogPath $summaryPath
            if (-not $gate.Ready) {
                $blocker = if ($gate.Blocker) { $gate.Blocker.kind } else { 'user-active' }
                Add-Line ('SKIPPED-USER-ACTIVE idle=' + (Get-MhUserIdleSeconds) + 's needed=' + $MinIdleSeconds + 's waited=' + $gate.WaitedSeconds + 's blocker=' + $blocker + ': nothing was started, no window was opened.')
                $gateTimedOut = $true
                break
            }
            Add-Line ('gate: READY idle=' + $gate.IdleSeconds + 's waited=' + $gate.WaitedSeconds + 's')
        }

        $flagPath = Join-Path $OutRoot ('watch-' + $stamp + '-' + $gateAttempt)
        $rootPidFile = $flagPath + '.root'
        Remove-Item -Path ($flagPath + '.abort') -ErrorAction SilentlyContinue
        # In a development override the machine is allowed to be active, so only the session-state
        # checks (locked/disconnected/sleep) may abort. Acceptance runs use the real threshold.
        $watchThreshold = if ($DevOverrideIdleGate) { 0 } else { $MinIdleSeconds }
        $watch = Start-MhQuietUiWatch -MinIdleSeconds $watchThreshold -PollMilliseconds 1000 -FlagPath $flagPath -RootPidFile $rootPidFile
        Add-Line ('watchdog started (poll=1s, abortOnInput=' + (-not $DevOverrideIdleGate) + ', kills only this run process tree=' + $rootPidFile + ')')

        $paused = $false
        foreach ($combo in $pending) {
            if (Test-MhQuietUiAborted $watch) { $paused = $true; break }

            if ($NoServers) {
                Add-Line ('server ' + $combo.server + ': skipped (NoServers test hook)')
            }
            else {
                $server = Start-QuietServer $combo.server
                Add-Line ('server ' + $combo.server + ': ' + $server.state)
                if ($server.state -eq 'aborted') { $paused = $true; break }
                if ($server.pid -gt 0) { $startedServers[$combo.server] = $server.pid }
                if ($server.state -in @('missing-exe', 'start-timeout', 'start-failed')) {
                    if ($failedKeys -notcontains $combo.key) { $failedKeys.Add($combo.key) }
                    Save-Checkpoint
                    continue
                }
            }

            # Kill only a MediaHub this run owns; another acceptance session's app must survive.
            foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
                if ($mediaHubBaseline -notcontains $p.Id) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
            }
            Start-Sleep -Seconds 2

            $log = Join-Path $OutRoot ('run-' + $combo.suite + '-' + $combo.server + '-' + $stamp + '.log')
            $errLog = $log + '.err'
            $started = Get-Date
            # The gate and the watchdog own the keep-out contract for this window, so the in-suite
            # restarts (stop + start for a clean baseline) must not be refused for idle time - that
            # guard is for a bare Start-MhApp. The abort marker is still exported, so a restart after
            # the user comes back fails loudly instead of flashing a window on screen.
            $savedAllow = $env:MH_ALLOW_ACTIVE
            $savedAbort = $env:MH_QUIET_ABORT_FILE
            $env:MH_ALLOW_ACTIVE = '1'
            $env:MH_QUIET_ABORT_FILE = [string]$watch.AbortFile
            $proc = $null
            try {
                $proc = Start-Process pwsh -ArgumentList @('-NoProfile', '-File', $suiteScripts[$combo.suite], '-Server', $combo.server, '-Exe', $Exe) -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
                # The watchdog reads this to kill exactly this suite (and the app it starts).
                Set-Content -Path $rootPidFile -Value ([string]$proc.Id) -Encoding UTF8
                # Do not block on WaitForExit: poll the abort marker too, so a pause is acted on
                # here as well as in the watchdog instead of waiting for the suite's own timeout.
                while (-not $proc.HasExited) {
                    if (Test-MhQuietUiAborted $watch) {
                        # The watchdog owns child cleanup; stop the suite itself and log the pause.
                        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                        $null = $proc.WaitForExit(1000)
                        break
                    }
                    Start-Sleep -Milliseconds 250
                }
            }
            finally {
                $env:MH_ALLOW_ACTIVE = $savedAllow
                $env:MH_QUIET_ABORT_FILE = $savedAbort
            }
            $elapsed = [int]((Get-Date) - $started).TotalSeconds

            if (-not $proc) {
                Add-Line ('START-FAILED ' + $combo.key + ' (pwsh could not launch the suite)')
                if ($failedKeys -notcontains $combo.key) { $failedKeys.Add($combo.key) }
                Save-Checkpoint
                continue
            }

            if (Test-MhQuietUiAborted $watch) {
                $paused = $true
                Add-Line ('PAUSED-COMBO ' + $combo.key + ' after ' + $elapsed + 's (' + (Get-MhQuietUiAbortReason $watch) + ')')
                break
            }

            $result = 'NO-RESULT'
            if (Test-Path $log) {
                $hit = Select-String -Path $log -Pattern '^RESULT:' | Select-Object -Last 1
                if ($hit) { $result = $hit.Line }
            }
            $fails = @()
            if (Test-Path $log) { $fails = @(Select-String -Path $log -Pattern '\[FAIL\]' | ForEach-Object { $_.Line }) }

            Add-Line ($combo.server + ' ' + $combo.suite + ' exit=' + $proc.ExitCode + ' ' + $result + ' elapsed=' + $elapsed + 's fails=' + $fails.Count)
            foreach ($f in $fails) { Add-Line ('    ' + $f) }

            if ($proc.ExitCode -eq 0 -and $result -match '0 failed') {
                if (-not $completed.Contains($combo.key)) { $completed.Add($combo.key) }
                $null = $failedKeys.Remove($combo.key)
            }
            else {
                if (-not $failedKeys.Contains($combo.key)) { $failedKeys.Add($combo.key) }
            }
            Save-Checkpoint
        }

        $abortReason = if (Test-MhQuietUiAborted $watch) { Get-MhQuietUiAbortReason $watch } else { '' }

        if (-not $paused) {
            Stop-MhQuietUiWatch $watch
            $watch = $null
            break
        }

        # ---- pause ---------------------------------------------------------
        # Log the pause before stopping the watchdog: Stop-MhQuietUiWatch waits for the job's
        # process-tree sweep, and the "user is back" record must not be delayed by it.
        $pausedCount++
        $killed = Stop-RunLeftovers $rootPidFile
        Add-Line ('PAUSED 用户回来了，已暂停 pausedCount=' + $pausedCount + ' killed=' + $killed + ' completed=' + $completed.Count + '/' + $combos.Count + ' reason=' + $abortReason + ' -> waiting for the next continuous ' + [int]($MinIdleSeconds / 60) + '-minute quiet window')
        Save-Checkpoint
        Stop-MhQuietUiWatch $watch
        $watch = $null
    }
}
finally {
    if ($watch) { Stop-MhQuietUiWatch $watch }
    foreach ($p in @(Get-Process MediaHub -ErrorAction SilentlyContinue)) {
        if ($mediaHubBaseline -notcontains $p.Id) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
    if (-not $KeepServers -and -not $NoServers) {
        foreach ($s in @('emby', 'jellyfin')) { Stop-QuietServer $s }
    }
    Add-Line ('cleanup done: MediaHub processes=' + @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count + ' serversKept=' + [bool]$KeepServers)
    try { Stop-Transcript | Out-Null } catch { }
}

$remaining = @($combos | Where-Object { $completed -notcontains $_.key })
if ($remaining.Count -gt 0 -and -not $gateTimedOut) {
    Add-Line ('INCOMPLETE remaining=' + (($remaining | ForEach-Object { $_.key }) -join ',') + ' paused=' + $pausedCount)
    exit 2
}
if ($gateTimedOut) {
    # Timeout on the idle gate is the absence of a measurement, not a pass. Exit 3 (the harness
    # blocked code the other gates already use) keeps it out of the "all green" bucket.
    Add-Line ('RESULT: SKIPPED-GATE no continuous ' + [int]($MinIdleSeconds / 60) + '-minute quiet window; nothing was measured completed=' + $completed.Count + '/' + $combos.Count)
    exit 3
}
if ($failedKeys.Count -gt 0) {
    Add-Line ('RESULT: FAILED ' + (($failedKeys | Sort-Object -Unique) -join ', '))
    exit 1
}
Add-Line ('RESULT: OK completed=' + $completed.Count + ' paused=' + $pausedCount)
exit 0
