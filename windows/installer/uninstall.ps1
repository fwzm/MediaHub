#Requires -Version 5.1
<#
.SYNOPSIS
    Removes a per-user MediaHub installation created by install.ps1.

.DESCRIPTION
    Deletes the program files, the Start Menu / desktop shortcuts that point at this installation,
    the uninstall entry under HKCU, and the per-user file-association keys the application writes
    (HKCU\Software\Classes\Applications\MediaHub.exe and HKCU\Software\Classes\MediaHub.Video).

    User data in %LOCALAPPDATA%\MediaHub - settings, the DPAPI credential vault, playback history,
    artwork cache and the startup trace - is deliberately kept: silently deleting credentials on
    uninstall is hostile, and the vault is bound to the Windows account anyway. Pass
    -RemoveUserData to delete it as well.

.PARAMETER InstallDir
    Installation to remove. Default: %LOCALAPPDATA%\Programs\MediaHub.

.PARAMETER RemoveUserData
    Also delete %LOCALAPPDATA%\MediaHub (settings, credential vault, history, artwork).

.PARAMETER Quiet
    Accepted for the Settings > Apps "quiet uninstall" command line; the script never prompts.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File uninstall.ps1

.NOTES
    Exit codes: 0 = removed, 3 = MediaHub is running, 4 = removal failed.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $InstallDir,
    [switch] $RemoveUserData,
    [switch] $Quiet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

# See install.ps1: repair PSModulePath before any cmdlet that lives in a dot-sourced module.
$psHomeModules = Join-Path $PSHOME 'Modules'
if (($env:PSModulePath -split ';') -notcontains $psHomeModules) {
    $env:PSModulePath = $psHomeModules + ';' + $env:PSModulePath
}

if (-not $InstallDir) { $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\MediaHub' }
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$dataDir = Join-Path $env:LOCALAPPDATA 'MediaHub'

function Write-Step { param([string] $Text) Write-Host ('uninstall: ' + $Text) }
function Fail {
    param([int] $Code, [string] $Text)
    Write-Host ('uninstall: FAILED - ' + $Text) -ForegroundColor Red
    exit $Code
}

$running = @(Get-Process -Name 'MediaHub' -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Fail 3 ("MediaHub is running (PID " + ($running | ForEach-Object { $_.Id }) + "); close it and run the uninstaller again")
}

if (-not $PSCmdlet.ShouldProcess($InstallDir, 'uninstall MediaHub')) { exit 0 }

# --- shortcuts -------------------------------------------------------------------------------
foreach ($linkPath in @(
        (Join-Path ([Environment]::GetFolderPath('Programs')) 'MediaHub.lnk'),
        (Join-Path ([Environment]::GetFolderPath('Desktop')) 'MediaHub.lnk'))) {
    if (-not (Test-Path -LiteralPath $linkPath)) { continue }
    try {
        $shell = New-Object -ComObject WScript.Shell
        $target = $shell.CreateShortcut($linkPath).TargetPath
        if ($target -and ($target.StartsWith($InstallDir, [System.StringComparison]::OrdinalIgnoreCase))) {
            Remove-Item -LiteralPath $linkPath -Force
            Write-Step ('removed shortcut: ' + $linkPath)
        }
        else {
            Write-Step ('kept shortcut (points elsewhere): ' + $linkPath)
        }
    }
    catch {
        Write-Warning ('could not inspect shortcut ' + $linkPath + ': ' + $_.Exception.Message)
    }
}

# --- file associations -----------------------------------------------------------------------
# Same keys FileAssociationService.Unregister() removes; the uninstaller has to do it even when the
# application itself is no longer runnable.
foreach ($key in @(
        'HKCU:\Software\Classes\Applications\MediaHub.exe',
        'HKCU:\Software\Classes\MediaHub.Video')) {
    if (Test-Path -LiteralPath $key) {
        Remove-Item -LiteralPath $key -Recurse -Force -ErrorAction SilentlyContinue
        Write-Step ('removed registry key: ' + $key)
    }
}
$classesKey = 'HKCU:\Software\Classes'
if (Test-Path -LiteralPath $classesKey) {
    foreach ($extensionKey in @(Get-ChildItem -LiteralPath $classesKey -ErrorAction SilentlyContinue)) {
        $openWith = Join-Path $extensionKey.PSPath 'OpenWithProgids'
        if (-not (Test-Path -LiteralPath $openWith)) { continue }
        $value = Get-ItemProperty -LiteralPath $openWith -Name 'MediaHub.Video' -ErrorAction SilentlyContinue
        if ($null -eq $value) { continue }
        Remove-ItemProperty -LiteralPath $openWith -Name 'MediaHub.Video' -Force -ErrorAction SilentlyContinue
        Write-Step ('removed OpenWithProgids entry under ' + $extensionKey.PSChildName)
        $remaining = @(Get-ItemProperty -LiteralPath $openWith -ErrorAction SilentlyContinue)
        $propertyCount = 0
        if ($null -ne $remaining -and $remaining.Count -gt 0) {
            $propertyCount = @($remaining[0].PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' }).Count
        }
        if ($propertyCount -eq 0) {
            Remove-Item -LiteralPath $openWith -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# --- uninstall entry -------------------------------------------------------------------------
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\MediaHub'
if (Test-Path -LiteralPath $uninstallKey) {
    Remove-Item -LiteralPath $uninstallKey -Recurse -Force
    Write-Step 'removed the uninstall registry entry'
}

# --- program files ---------------------------------------------------------------------------
if (Test-Path -LiteralPath $InstallDir) {
    $removed = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force
            $removed = $true
            break
        }
        catch {
            if ($attempt -eq 3) { Fail 4 ("could not delete " + $InstallDir + ": " + $_.Exception.Message) }
            Start-Sleep -Milliseconds 400
        }
    }
    if (-not $removed) { Fail 4 ("could not delete " + $InstallDir) }
    Write-Step ('removed program files: ' + $InstallDir)
}
else {
    Write-Step ('program files already absent: ' + $InstallDir)
}

# --- user data -------------------------------------------------------------------------------
if ($RemoveUserData) {
    if (Test-Path -LiteralPath $dataDir) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force
        Write-Step ('removed user data: ' + $dataDir)
    }
}
else {
    Write-Step ('kept user data (settings, credential vault, history): ' + $dataDir)
    Write-Step 'pass -RemoveUserData to delete it as well'
}

Write-Step 'done'
exit 0
