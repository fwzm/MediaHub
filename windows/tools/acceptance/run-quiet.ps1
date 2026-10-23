#Requires -Version 7
<#
.SYNOPSIS
Runs the MediaHub GUI acceptance suites without disturbing whoever is using this machine.

.DESCRIPTION
Every suite here drives a real WinUI window through UIA, which means it needs an unlocked interactive
session and the window has to be visible: there is no supported way to automate a window on a desktop
the user cannot see. So "do not disturb" is implemented as a contract instead of a trick:

  * pre-flight gate - refuses to start unless the machine has been idle for -MinIdleSeconds
    (keyboard, mouse and touch count; window focus does not),
  * optional waiting - -WaitForIdleMinutes N waits up to N minutes for that idle window, then gives up
    silently (exit code 0, no windows ever shown),
  * watchdog - while a suite runs, a background job polls input every 700 ms; the moment the user
    touches the machine it kills MediaHub and records "aborted by user", so the run stops instead of
    fighting for the screen,
  * no leftovers - the app is killed between suites and the test media servers are stopped at the end
    unless -KeepServers is passed.

Exit codes: 0 = ran (or skipped because the user was active), 1 = at least one suite failed.

.EXAMPLE
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites u1,u2 -Servers emby

.EXAMPLE
# Start it now and let it wait for a three-minute gap; it stops the moment you come back.
pwsh -File windows/tools/acceptance/run-quiet.ps1 -Suites all -WaitForIdleMinutes 30
#>
[CmdletBinding()]
param(
    [ValidateSet('u1', 'u2', 'u3', 'u4', 'all')][string[]]$Suites = @('all'),
    [ValidateSet('emby', 'jellyfin')][string[]]$Servers = @('emby', 'jellyfin'),
    [int]$MinIdleSeconds = 180,
    [int]$WaitForIdleMinutes = 0,
    [string]$Exe = 'D:\mh-out\x64\Debug\net10.0-windows10.0.19041.0\win-x64\MediaHub.exe',
    [string]$OutRoot = 'D:\mh-verify\quiet',
    [switch]$KeepServers
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$here = Split-Path -Parent $PSCommandPath
Import-Module (Join-Path $here 'lib\MhQuiet.psm1') -Force

$suitePaths = @{
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
    emby     = @('-datadir', 'D:\mh-servers\data\emby', '-nointerface')
    jellyfin = @('--datadir', 'D:\mh-servers\data\jellyfin',
                 '--configdir', 'D:\mh-servers\data\jellyfin\config',
                 '--cachedir', 'D:\mh-servers\data\jellyfin\cache',
                 '--logdir', 'D:\mh-servers\data\jellyfin\log',
                 '--webdir', 'D:\mh-servers\jellyfin\jellyfin\jellyfin-web')
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
    if (Test-PortOpen $serverPorts[$Name]) { return 'already-running' }
    if (-not (Test-Path $serverExe[$Name])) { return 'missing-exe' }

    $log = Join-Path $OutRoot ('server-' + $Name + '.log')
    $startArgs = @{
        FilePath = $serverExe[$Name]
        ArgumentList = $serverArgs[$Name]
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = $log
        RedirectStandardError = ($log + '.err')
    }
    Start-Process @startArgs | Out-Null

    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen $serverPorts[$Name]) { return 'started' }
        Start-Sleep -Seconds 3
    }
    return 'start-timeout'
}

function Stop-QuietServer([string]$Name) {
    $procName = if ($Name -eq 'emby') { 'EmbyServer' } else { 'jellyfin' }
    Get-Process -Name $procName -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$summaryPath = Join-Path $OutRoot ('summary-' + $stamp + '.txt')
$lines = New-Object System.Collections.Generic.List[string]

function Add-Line([string]$Text) {
    $lines.Add($Text)
    Write-Host $Text
    Set-Content -Path $summaryPath -Value $lines -Encoding UTF8
}

$idle = Get-MhUserIdleSeconds
Add-Line ('quiet-run ' + $stamp + ' suites=' + ($Suites -join ',') + ' servers=' + ($Servers -join ',') + ' idleNow=' + $idle + 's threshold=' + $MinIdleSeconds + 's waitMinutes=' + $WaitForIdleMinutes)

# ---- gate ------------------------------------------------------------------
$idleOk = Wait-MhUserIdle -MinIdleSeconds $MinIdleSeconds -TimeoutSeconds ($WaitForIdleMinutes * 60)
if (-not $idleOk) {
    Add-Line ('SKIPPED-USER-ACTIVE idle=' + (Get-MhUserIdleSeconds) + 's needed=' + $MinIdleSeconds + 's: nothing was started, no window was opened.')
    exit 0
}

# ---- run -------------------------------------------------------------------
$watch = $null
$failed = @()
$aborted = $false
try {
    $watch = Start-MhQuietWatch -MinIdleSeconds $MinIdleSeconds -FlagPath (Join-Path $OutRoot ('watch-' + $stamp))
    Add-Line ('watchdog started (kills MediaHub within ~1s of any input, threshold=' + $MinIdleSeconds + 's)')

    foreach ($server in $Servers) {
        $serverState = Start-QuietServer $server
        Add-Line ('server ' + $server + ': ' + $serverState)
        if ($serverState -in @('missing-exe', 'start-timeout')) {
            $failed += ($server + ' (server unavailable: ' + $serverState + ')')
            continue
        }

        foreach ($suite in $Suites) {
            if (Test-MhQuietAborted $watch) { $aborted = $true; break }

            Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2

            $log = Join-Path $OutRoot ('run-' + $suite + '-' + $server + '-' + $stamp + '.log')
            $errLog = $log + '.err'
            $started = Get-Date
            $proc = Start-Process pwsh -ArgumentList @('-NoProfile', '-File', $suitePaths[$suite], '-Server', $server, '-Exe', $Exe) -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -Wait -WindowStyle Hidden
            $elapsed = [int]((Get-Date) - $started).TotalSeconds

            $result = 'NO-RESULT'
            if (Test-Path $log) {
                $hit = Select-String -Path $log -Pattern '^RESULT:' | Select-Object -Last 1
                if ($hit) { $result = $hit.Line }
            }
            $fails = @()
            if (Test-Path $log) { $fails = @(Select-String -Path $log -Pattern '\[FAIL\]' | ForEach-Object { $_.Line }) }

            if (Test-MhQuietAborted $watch) {
                $aborted = $true
                Add-Line ($server + ' ' + $suite + ' ABORTED-BY-USER after ' + $elapsed + 's (' + (Get-MhQuietAbortReason $watch) + ')')
                break
            }

            Add-Line ($server + ' ' + $suite + ' exit=' + $proc.ExitCode + ' ' + $result + ' elapsed=' + $elapsed + 's fails=' + $fails.Count)
            foreach ($f in $fails) { Add-Line ('    ' + $f) }
            if ($proc.ExitCode -ne 0 -or $result -notmatch '0 failed') { $failed += ($server + '/' + $suite) }
        }

        if (-not $KeepServers) { Stop-QuietServer $server }
        if ($aborted) { break }
    }
}
finally {
    Stop-MhQuietWatch $watch
    Get-Process MediaHub -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    if (-not $KeepServers) {
        foreach ($s in @('emby', 'jellyfin')) { Stop-QuietServer $s }
    }
    Add-Line ('cleanup done: MediaHub processes=' + @(Get-Process MediaHub -ErrorAction SilentlyContinue).Count + ' serversKept=' + [bool]$KeepServers)
}

if ($aborted) {
    Add-Line 'RESULT: ABORTED-BY-USER (user came back; app killed, servers stopped)'
    exit 1
}
if ($failed.Count -gt 0) {
    Add-Line ('RESULT: FAILED ' + ($failed -join ', '))
    exit 1
}
Add-Line 'RESULT: OK'
exit 0
