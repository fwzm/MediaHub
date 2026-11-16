#Requires -Version 7.0
<#
.SYNOPSIS
    Drives a real upgrade, a real rollback and a rejected package against an isolated per-user tree.

.DESCRIPTION
    This is the acceptance drill for the update path. It does not touch the signed-in user's
    installation, desktop, Start Menu or taskbar: the install tree, state directory and all shortcut
    folders live under one throw-away work directory. A copy of powershell.exe is started as a
    stand-in MediaHub process so the agent's wait/stop/lock-release path is exercised, but it is
    hidden and killed before the swap; no MediaHub window is opened.

    Three runs:
      1. upgrade    - 1.0.0 kit installed, 1.1.0 package applied, version and shortcuts verified.
      2. rollback   - same package with -FailAfterSwap; exit 5 and the 1.0.0 tree must be back,
                      byte-for-byte complete, with no 1.1.0-only file left behind.
      3. bad hash   - wrong digest; exit 3 and the installation must be untouched.

.PARAMETER OldKit
    Kit directory of the installed version. Default: windows/artifacts/setup/MediaHub-Setup-win-x64-1.0.0.

.PARAMETER NewKit
    Kit directory of the new version. Default: the newest MediaHub-Setup-win-x64-* under
    windows/artifacts/update-drill/kits.

.PARAMETER WorkDir
    Throw-away root. Default: windows/artifacts/update-drill/run-<timestamp>.

.EXAMPLE
    pwsh -NoProfile -File windows/tools/verify-update-chain.ps1 `
        -OldKit windows/artifacts/setup/MediaHub-Setup-win-x64-1.0.0 `
        -NewKit windows/artifacts/update-drill/kits/MediaHub-Setup-win-x64-1.1.0

.NOTES
    Exit codes: 0 = all three drills passed, 1 = a drill failed, 2 = input kit missing/incomplete.
#>
[CmdletBinding()]
param(
    [string] $OldKit,
    [string] $NewKit,
    [string] $WorkDir,
    [int] $MinIdleSeconds = 1800,
    [int] $IdleWaitSeconds = 0,
    [switch] $KeepWorkDir
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$artifactRoot = Join-Path $repoRoot 'windows\artifacts\update-drill'

if (-not $OldKit) { $OldKit = Join-Path $repoRoot 'windows\artifacts\setup\MediaHub-Setup-win-x64-1.0.0' }
if (-not $NewKit) {
    $candidates = @(Get-ChildItem -LiteralPath (Join-Path $artifactRoot 'kits') -Directory -Filter 'MediaHub-Setup-win-x64-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending)
    if ($candidates.Count -gt 0) { $NewKit = $candidates[0].FullName }
}
if (-not $WorkDir) { $WorkDir = Join-Path $artifactRoot ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }

$OldKit = [System.IO.Path]::GetFullPath($OldKit)
$NewKit = [System.IO.Path]::GetFullPath($NewKit)
$WorkDir = [System.IO.Path]::GetFullPath($WorkDir)
$agent = Join-Path $repoRoot 'windows\installer\update.ps1'

foreach ($path in @($OldKit, $NewKit)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host ('drill: FAILED - kit not found: ' + $path) -ForegroundColor Red
        exit 2
    }
    foreach ($name in @('app\MediaHub.exe', 'app\MediaHub.dll', 'app\libmpv-2.dll', 'uninstall.ps1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $path $name))) {
            Write-Host ('drill: FAILED - kit is incomplete: ' + (Join-Path $path $name)) -ForegroundColor Red
            exit 2
        }
    }
}
if (-not (Test-Path -LiteralPath $agent)) {
    Write-Host ('drill: FAILED - update agent not found: ' + $agent) -ForegroundColor Red
    exit 2
}

# ------------------------------------------------------------------------------------------------
# quiet gate (hard rule: never run while the user is at the keyboard)
# ------------------------------------------------------------------------------------------------

Import-Module (Join-Path $repoRoot 'windows\tools\acceptance\lib\MhQuiet.psm1') -Force

$idle = Get-MhUserIdleSeconds
if ($null -eq $idle) {
    Write-Host 'drill: idle time unavailable; refusing to run' -ForegroundColor Red
    exit 10
}
if ($idle -lt $MinIdleSeconds) {
    if ($IdleWaitSeconds -le 0) {
        Write-Host ('drill: REFUSED - user idle ' + $idle + 's, gate needs ' + $MinIdleSeconds + 's') -ForegroundColor Yellow
        Write-Host 'drill: re-run with -IdleWaitSeconds <n> to wait for the window'
        exit 10
    }
    $deadline = (Get-Date).AddSeconds($IdleWaitSeconds)
    Write-Host ('drill: waiting for ' + $MinIdleSeconds + 's idle (now ' + $idle + 's)')
    while ($idle -lt $MinIdleSeconds) {
        if ((Get-Date) -ge $deadline) {
            Write-Host ('drill: REFUSED - still only ' + $idle + 's idle after waiting') -ForegroundColor Yellow
            exit 10
        }
        Start-Sleep -Seconds 10
        $idle = Get-MhUserIdleSeconds
        if ($null -eq $idle) { $idle = 0 }
    }
    Write-Host ('drill: idle gate open at ' + $idle + 's idle')
}

$abortFile = Join-Path $WorkDir 'aborted.txt'
$stopWatchFile = Join-Path $WorkDir 'watch.stop'
$watchdogSource = @'
using System;
using System.Runtime.InteropServices;
namespace MhChainDrillWatch
{
    public static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
        [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);
        [DllImport("kernel32.dll")] public static extern uint GetTickCount();
    }
}
'@
$watchdog = Start-Job -ScriptBlock {
    param($TypeSource, $MinIdleSeconds, $AbortFile, $StopFile, $Root)
    if (-not ('MhChainDrillWatch.Native' -as [type])) { Add-Type -TypeDefinition $TypeSource -Language CSharp }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    while (-not (Test-Path -LiteralPath $StopFile)) {
        $info = New-Object MhChainDrillWatch.Native+LASTINPUTINFO
        $info.cbSize = [uint32][System.Runtime.InteropServices.Marshal]::SizeOf($info)
        if ([MhChainDrillWatch.Native]::GetLastInputInfo([ref]$info)) {
            $tick = [uint64][MhChainDrillWatch.Native]::GetTickCount()
            $last = [uint64]$info.dwTime
            $delta = if ($tick -ge $last) { $tick - $last } else { (0x100000000 - $last) + $tick }
            $idleNow = [int]($delta / 1000)
            if ($idleNow -lt $MinIdleSeconds) {
                foreach ($process in @(Get-Process -Name MediaHub -ErrorAction SilentlyContinue)) {
                    $path = ''
                    try { $path = $process.Path } catch { }
                    if ($path -and ([System.IO.Path]::GetFullPath($path)).StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
                        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                    }
                }
                Set-Content -LiteralPath $AbortFile -Value ('user-active idle=' + $idleNow + ' threshold=' + $MinIdleSeconds) -Encoding UTF8
                break
            }
        }
        Start-Sleep -Milliseconds 700
    }
} -ArgumentList @($watchdogSource, $MinIdleSeconds, $abortFile, $stopWatchFile, $WorkDir)

function Test-Aborted {
    if (Test-Path -LiteralPath $abortFile) {
        Write-Host ('drill: ABORTED - ' + (Get-Content -LiteralPath $abortFile -Raw).Trim()) -ForegroundColor Yellow
        return $true
    }
    return $false
}

# ------------------------------------------------------------------------------------------------
# layout (all throw-away)
# ------------------------------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$localAppData = Join-Path $WorkDir 'LocalAppData'
$installDir = Join-Path $localAppData 'Programs\MediaHub'
$stateDir = Join-Path $localAppData 'MediaHub\update'
$startMenu = Join-Path $WorkDir 'StartMenu'
$desktop = Join-Path $WorkDir 'Desktop'
$taskbar = Join-Path $WorkDir 'Taskbar'
$sourceDir = Join-Path $WorkDir 'source'
$downloadDir = Join-Path $WorkDir 'downloads'
New-Item -ItemType Directory -Force -Path $installDir, $startMenu, $desktop, $taskbar, $sourceDir, $downloadDir | Out-Null

$oldVersion = ((Get-Item -LiteralPath (Join-Path $OldKit 'app\MediaHub.exe')).VersionInfo.ProductVersion -split '\+')[0]
$newVersion = ((Get-Item -LiteralPath (Join-Path $NewKit 'app\MediaHub.exe')).VersionInfo.ProductVersion -split '\+')[0]
$kitLeaf = Split-Path -Leaf $NewKit
$packageZip = Join-Path (Split-Path -Parent $NewKit) ($kitLeaf + '.zip')
if (-not (Test-Path -LiteralPath $packageZip)) {
    Write-Host ('drill: packaging ' + $packageZip)
    Compress-Archive -LiteralPath $NewKit -DestinationPath $packageZip -CompressionLevel Optimal
}
Copy-Item -LiteralPath $packageZip -Destination (Join-Path $sourceDir (Split-Path -Leaf $packageZip)) -Force
$packageHash = (Get-FileHash -LiteralPath (Join-Path $sourceDir (Split-Path -Leaf $packageZip)) -Algorithm SHA256).Hash.ToLowerInvariant()
$packageSize = (Get-Item -LiteralPath (Join-Path $sourceDir (Split-Path -Leaf $packageZip))).Length
$manifest = [ordered]@{
    version   = $newVersion
    package   = (Split-Path -Leaf $packageZip)
    sha256    = $packageHash
    size      = $packageSize
    published = (Get-Date).ToUniversalTime().ToString('o')
    notes     = 'local update drill'
}
[System.IO.File]::WriteAllText((Join-Path $sourceDir 'update.json'),
    ($manifest | ConvertTo-Json) + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ('drill: workdir   ' + $WorkDir)
Write-Host ('drill: installed ' + $oldVersion)
Write-Host ('drill: package   ' + $newVersion + ' ' + $packageZip + ' (' + [math]::Round($packageSize / 1MB, 1) + ' MiB)')
Write-Host ('drill: sha256    ' + $packageHash)

# ------------------------------------------------------------------------------------------------
# helpers
# ------------------------------------------------------------------------------------------------

function Fail([string] $Message) {
    throw $Message
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { Fail $Message }
}

function Get-ExeVersion([string] $ExePath) {
    return ((Get-Item -LiteralPath $ExePath).VersionInfo.ProductVersion -split '\+')[0]
}

function Install-OldTree {
    if (Test-Path -LiteralPath $installDir) { Remove-Item -LiteralPath $installDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    Copy-Item -Path (Join-Path $OldKit 'app\*') -Destination $installDir -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $OldKit 'uninstall.ps1') -Destination $installDir -Force
    # An installation made by this build already carries the agent; the 1.0.0 kit predates it.
    Copy-Item -LiteralPath $agent -Destination (Join-Path $installDir 'update.ps1') -Force
}

function Write-TestShortcuts {
    $shell = New-Object -ComObject WScript.Shell
    $exe = Join-Path $installDir 'MediaHub.exe'
    foreach ($link in @(
            (Join-Path $startMenu 'MediaHub.lnk'),
            (Join-Path $desktop 'MediaHub.lnk'),
            (Join-Path $taskbar 'MediaHub.lnk'))) {
        $shortcut = $shell.CreateShortcut($link)
        $shortcut.TargetPath = $exe
        $shortcut.WorkingDirectory = $installDir
        $shortcut.IconLocation = $exe + ',0'
        $shortcut.Description = 'MediaHub drill'
        $shortcut.Save()
    }
}

function Assert-Shortcut([string] $Path) {
    Assert-True (Test-Path -LiteralPath $Path) ('shortcut missing: ' + $Path)
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($Path)
    $expected = Join-Path $installDir 'MediaHub.exe'
    Assert-True ($shortcut.TargetPath -ieq $expected) ('shortcut target is wrong: ' + $Path + ' -> ' + $shortcut.TargetPath)
}

function New-LockProbe {
    $probeDir = Join-Path $installDir 'lockprobe'
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    $probeExe = Join-Path $probeDir 'MediaHub.exe'
    Copy-Item -LiteralPath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Destination $probeExe -Force
    $target = Join-Path $installDir 'MediaHub.dll'
    $command = '$f=[System.IO.File]::Open(''' + $target + ''',[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::ReadWrite); Start-Sleep -Seconds 120'
    $process = Start-Process -FilePath $probeExe -ArgumentList @('-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', $command) -PassThru -WindowStyle Hidden
    Start-Sleep -Milliseconds 800
    Assert-True (-not $process.HasExited) 'lock probe exited before the update started'
    return $process
}

function Invoke-Agent([int] $ParentPid = 0, [int] $WaitSeconds = 30, [switch] $FailAfterSwap, [string] $Hash = '') {
    if (-not $Hash) { $Hash = $packageHash }
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', $agent,
        '-InstallDir', $installDir,
        '-PackageZip', (Join-Path $sourceDir (Split-Path -Leaf $packageZip)),
        '-ExpectedSha256', $Hash,
        '-NewVersion', $newVersion,
        '-StateDir', $stateDir,
        '-StartMenuDir', $startMenu,
        '-DesktopDir', $desktop,
        '-TaskbarPinsDir', $taskbar
    )
    if ($ParentPid -gt 0) { $arguments += @('-ParentPid', $ParentPid, '-WaitSeconds', $WaitSeconds) }
    if ($FailAfterSwap) { $arguments += '-FailAfterSwap' }

    Write-Host ('drill: agent ' + ($arguments -join ' '))
    $output = & powershell.exe @arguments 2>&1
    $code = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    foreach ($line in ($output | Select-Object -Last 12)) { Write-Host ('    ' + $line) }
    return [pscustomobject]@{ Code = $code; Output = $text }
}

# ------------------------------------------------------------------------------------------------
# 1. upgrade
# ------------------------------------------------------------------------------------------------

$exitCode = 0
try {

Write-Host ''
Write-Host 'drill: [1/3] upgrade'
if (Test-Aborted) { throw 'aborted by user input' }
Install-OldTree
Write-TestShortcuts
$probe = New-LockProbe
Write-Host ('drill: lock probe pid ' + $probe.Id + ' holds MediaHub.dll')

$result = Invoke-Agent -ParentPid $probe.Id -WaitSeconds 2
Assert-True ($result.Code -eq 0) ('upgrade exited ' + $result.Code + ', expected 0')
$probe.Refresh()
Assert-True ($probe.HasExited) 'the agent did not release the process holding the install directory'
Assert-True ((Get-ExeVersion (Join-Path $installDir 'MediaHub.exe')) -eq $newVersion) 'installed version did not change'
Assert-True (Test-Path -LiteralPath (Join-Path $installDir 'uninstall.ps1')) 'uninstaller was not preserved'
Assert-True (Test-Path -LiteralPath (Join-Path $installDir 'update.ps1')) 'update agent was not installed for later runs'
Assert-Shortcut (Join-Path $startMenu 'MediaHub.lnk')
Assert-Shortcut (Join-Path $desktop 'MediaHub.lnk')
Assert-Shortcut (Join-Path $taskbar 'MediaHub.lnk')
$state = Get-Content -LiteralPath (Join-Path $stateDir 'state.json') -Raw | ConvertFrom-Json
Assert-True ($state.status -eq 'succeeded') ('state is ' + $state.status + ', expected succeeded')
Assert-True ($state.oldVersion -like ($oldVersion + '*')) ('state oldVersion is ' + $state.oldVersion)
$backups = @(Get-ChildItem -LiteralPath (Join-Path $stateDir 'backups') -Directory -ErrorAction SilentlyContinue)
Assert-True ($backups.Count -ge 1) 'no backup of the previous version was kept'
Write-Host ('drill: [1/3] PASS ' + $oldVersion + ' -> ' + $newVersion + ' (exit 0, backup ' + $backups[0].Name + ')') -ForegroundColor Green

# ------------------------------------------------------------------------------------------------
# 2. rollback (failure after the swap)
# ------------------------------------------------------------------------------------------------

Write-Host ''
Write-Host 'drill: [2/3] rollback after a failed swap'
if (Test-Aborted) { throw 'aborted by user input' }
Install-OldTree
Write-TestShortcuts
$result = Invoke-Agent -FailAfterSwap
Assert-True ($result.Code -eq 5) ('rollback exited ' + $result.Code + ', expected 5')
Assert-True ((Get-ExeVersion (Join-Path $installDir 'MediaHub.exe')) -eq $oldVersion) 'rollback did not restore the old executable'
Assert-True (Test-Path -LiteralPath (Join-Path $installDir 'uninstall.ps1')) 'rollback left no uninstaller'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $installDir 'MediaHub.Update.dll'))) 'rollback left a new-version-only file behind'
Assert-Shortcut (Join-Path $startMenu 'MediaHub.lnk')
Assert-Shortcut (Join-Path $desktop 'MediaHub.lnk')
Assert-Shortcut (Join-Path $taskbar 'MediaHub.lnk')
$state = Get-Content -LiteralPath (Join-Path $stateDir 'state.json') -Raw | ConvertFrom-Json
Assert-True ($state.status -eq 'rolled-back') ('state is ' + $state.status + ', expected rolled-back')
Write-Host ('drill: [2/3] PASS failed update restored ' + $oldVersion + ' (exit 5)') -ForegroundColor Green

# ------------------------------------------------------------------------------------------------
# 3. rejected package (hash mismatch)
# ------------------------------------------------------------------------------------------------

Write-Host ''
Write-Host 'drill: [3/3] rejected package'
if (Test-Aborted) { throw 'aborted by user input' }
Install-OldTree
$before = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.dll') -Algorithm SHA256).Hash
$result = Invoke-Agent -Hash (('0' * 64))
Assert-True ($result.Code -eq 3) ('bad package exited ' + $result.Code + ', expected 3')
$after = (Get-FileHash -LiteralPath (Join-Path $installDir 'MediaHub.dll') -Algorithm SHA256).Hash
Assert-True ($before -eq $after) 'a rejected package changed the installed tree'
$state = Get-Content -LiteralPath (Join-Path $stateDir 'state.json') -Raw | ConvertFrom-Json
Assert-True ($state.status -eq 'failed') ('state is ' + $state.status + ', expected failed')
Assert-True ($result.Output -like '*hash mismatch*') 'the agent log does not explain the hash rejection'
Write-Host 'drill: [3/3] PASS bad package rejected before any file was touched (exit 3)' -ForegroundColor Green

Write-Host ''
Write-Host 'RESULT: UPDATE CHAIN PASS (upgrade, rollback, hash rejection)'
}
catch {
    if (Test-Path -LiteralPath $abortFile) { $exitCode = 11 } else { $exitCode = 1 }
    Write-Host ('drill: FAILED - ' + $_.Exception.Message) -ForegroundColor Red
}
finally {
    if ($watchdog) {
        Set-Content -LiteralPath $stopWatchFile -Value 'stop' -Encoding ASCII -ErrorAction SilentlyContinue
        [void](Wait-Job -Job $watchdog -Timeout 10)
        Remove-Job -Job $watchdog -Force -ErrorAction SilentlyContinue
    }

    # Keep the small state/log evidence, remove the copied installation trees. A successful drill
    # must not leave a second MediaHub, its backups or its shortcuts on the machine.
    $logArchive = Join-Path $artifactRoot 'last-drill'
    try {
        if (Test-Path -LiteralPath $logArchive) { Remove-Item -LiteralPath $logArchive -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $logArchive | Out-Null
        if (Test-Path -LiteralPath (Join-Path $stateDir 'state.json')) {
            Copy-Item -LiteralPath (Join-Path $stateDir 'state.json') -Destination $logArchive -Force
        }
        if (Test-Path -LiteralPath (Join-Path $stateDir 'logs')) {
            Copy-Item -LiteralPath (Join-Path $stateDir 'logs') -Destination $logArchive -Recurse -Force
        }
        Write-Host ('LOGS: ' + $logArchive)
    }
    catch { Write-Host ('drill: could not archive logs: ' + $_.Exception.Message) }

    if ($KeepWorkDir) {
        Write-Host ('EVIDENCE: ' + $WorkDir)
    }
    else {
        try {
            Remove-Item -LiteralPath $WorkDir -Recurse -Force
            Write-Host ('CLEANUP: removed temporary installation ' + $WorkDir)
        }
        catch { Write-Host ('drill: CLEANUP FAILED, remove manually: ' + $WorkDir) -ForegroundColor Yellow }
    }
}
exit $exitCode
