#Requires -Version 5.1
<#
.SYNOPSIS
    Per-user installer for MediaHub (unpackaged, self-contained Windows build).

.DESCRIPTION
    Copies the published application tree into %LOCALAPPDATA%\Programs\MediaHub, creates a Start
    Menu shortcut (and optionally a desktop one), and registers an entry under
    HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall so that Settings > Apps can remove it.

    No elevation, no service, no driver, no third-party installer runtime: everything is PowerShell
    plus the COM shortcut API that ships with Windows. The payload is the self-contained publish
    output, so nothing has to be installed beforehand (.NET and the Windows App SDK are inside the
    tree) and the machine needs no network access to install.

    The application's own data (%LOCALAPPDATA%\MediaHub: settings, DPAPI credential vault,
    playback history, artwork cache) is never touched by install or uninstall; uninstall.ps1
    removes it only when -RemoveUserData is passed.

.PARAMETER SourceDir
    Published tree to install. Default: the 'app' directory next to this script (the layout the
    install kit ships).

.PARAMETER InstallDir
    Destination. Default: %LOCALAPPDATA%\Programs\MediaHub.

.PARAMETER DesktopShortcut
    Also create a shortcut on the desktop. Off by default: the Start Menu entry is enough, and a
    desktop icon is the kind of thing an installer should not decide for the user.

.PARAMETER Launch
    Start MediaHub after a successful install (what the double-click wrapper does).

.PARAMETER NoShortcut
    Skip shortcut creation entirely (used by the packaging smoke test of the copy step alone).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 -Launch

.NOTES
    Exit codes: 0 = installed, 2 = payload incomplete, 3 = MediaHub is running,
    4 = copy or shortcut creation failed.

    The engine that gets installed is whatever libmpv-2.dll the payload carries. The script prints
    its SHA-256 so the installed engine is identifiable; the default build ships the LGPL-2.1-or-later
    build (figures and evidence: windows/docs/licensing/LICENCE-DECISION.md section 9).
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $SourceDir,
    [string] $InstallDir,
    [switch] $DesktopShortcut,
    [switch] $Launch,
    [switch] $NoShortcut
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

# A Windows PowerShell 5.1 process started from PowerShell 7 inherits PS7's PSModulePath, which
# leaves 5.1 unable to auto-load its own modules (Get-FileHash and friends then look "missing").
# The kit has to install from any shell, so make sure this host's own module directory is present.
$psHomeModules = Join-Path $PSHOME 'Modules'
if (($env:PSModulePath -split ';') -notcontains $psHomeModules) {
    $env:PSModulePath = $psHomeModules + ';' + $env:PSModulePath
}

function Get-Sha256([string] $Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    }
    finally { $stream.Dispose() }
}

if (-not $SourceDir) { $SourceDir = Join-Path $PSScriptRoot 'app' }
if (-not $InstallDir) { $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\MediaHub' }

$SourceDir = [System.IO.Path]::GetFullPath($SourceDir)
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$exeName = 'MediaHub.exe'
$required = @($exeName, 'MediaHub.dll', 'libmpv-2.dll')

function Write-Step { param([string] $Text) Write-Host ('install: ' + $Text) }
function Fail {
    param([int] $Code, [string] $Text)
    Write-Host ('install: FAILED - ' + $Text) -ForegroundColor Red
    exit $Code
}

# --- 1. payload sanity -----------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $SourceDir)) { Fail 2 ("payload directory not found: " + $SourceDir) }
$missing = @()
foreach ($name in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $SourceDir $name))) { $missing += $name }
}
if ($missing.Count -gt 0) {
    Fail 2 ("payload is incomplete, missing: " + ($missing -join ', ') + " in " + $SourceDir)
}

$sourceExe = Join-Path $SourceDir $exeName
$version = '0.0.0'
try {
    $info = (Get-Item -LiteralPath $sourceExe).VersionInfo
    if ($info.ProductVersion) {
        $version = ($info.ProductVersion -split '\+')[0]
    }
    elseif ($info.FileVersion) {
        $version = $info.FileVersion
    }
} catch { }

# --- 2. running instance ---------------------------------------------------------------------
$running = @(Get-Process -Name 'MediaHub' -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Fail 3 ("MediaHub is running (PID " + ($running | ForEach-Object { $_.Id }) + "); close it and run the installer again")
}

$enginePath = Join-Path $SourceDir 'libmpv-2.dll'
$engineHash = Get-Sha256 $enginePath
$engineBytes = (Get-Item -LiteralPath $enginePath).Length

Write-Step ("source      : " + $SourceDir)
Write-Step ("target      : " + $InstallDir)
Write-Step ("version     : " + $version)
Write-Step ("engine      : libmpv-2.dll " + $engineBytes + " bytes sha256 " + $engineHash)

if (-not (Test-Path -LiteralPath (Join-Path $SourceDir 'licences\MANIFEST.txt'))) {
    Write-Warning 'payload has no licences\MANIFEST.txt - it was published without stage-licence-files.ps1'
}

# --- 3. copy ---------------------------------------------------------------------------------
if ($PSCmdlet.ShouldProcess($InstallDir, 'install MediaHub ' + $version)) {
    if (Test-Path -LiteralPath $InstallDir) {
        Write-Step 'updating the existing installation in place'
    }
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

    # The payload is written over the existing tree rather than deleting it first. Deleting a large
    # DLL that an antivirus scan has just touched leaves the name in a pending-delete state, and
    # creating that same name again fails with access denied until the scan lets go; overwriting
    # in place avoids the window entirely and makes reinstall/upgrade a copy, not a swap.
    $sourceDirs = @(Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -Directory)
    $sourceFiles = @(Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -File)
    foreach ($dir in $sourceDirs) {
        $relative = $dir.FullName.Substring($SourceDir.Length).TrimStart('\')
        New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir $relative) | Out-Null
    }
    foreach ($file in $sourceFiles) {
        $relative = $file.FullName.Substring($SourceDir.Length).TrimStart('\')
        $destination = Join-Path $InstallDir $relative
        $copied = $false
        for ($attempt = 1; $attempt -le 10; $attempt++) {
            try {
                [System.IO.File]::Copy($file.FullName, $destination, $true)
                $copied = $true
                break
            }
            catch {
                if ($attempt -eq 10) { Fail 4 ('copy failed for ' + $relative + ': ' + $_.Exception.Message) }
                Start-Sleep -Milliseconds 1000
            }
        }
        if (-not $copied) { Fail 4 ('copy failed for ' + $relative) }
    }

    # The uninstaller must survive in the install directory: the registry entry points at it. The
    # update agent is copied too so a manual repair does not depend on the running app being healthy.
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'uninstall.ps1') -Destination $InstallDir -Force
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'update.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'update.ps1') -Destination $InstallDir -Force
    }

    # Files left over from an older payload are removed, best effort only: an upgrade must not fail
    # because a stale file is momentarily locked, and a stale file is not used by the new build.
    $expected = @{}
    foreach ($file in $sourceFiles) {
        $expected[$file.FullName.Substring($SourceDir.Length).TrimStart('\').ToLowerInvariant()] = $true
    }
    $expected['uninstall.ps1'] = $true
    $expected['update.ps1'] = $true
    foreach ($existing in @(Get-ChildItem -LiteralPath $InstallDir -Recurse -Force -File)) {
        $relative = $existing.FullName.Substring($InstallDir.Length).TrimStart('\')
        if ($expected.ContainsKey($relative.ToLowerInvariant())) { continue }
        try {
            Remove-Item -LiteralPath $existing.FullName -Force -ErrorAction Stop
            Write-Step ('removed stale file: ' + $relative)
        }
        catch {
            Write-Warning ('could not remove stale file ' + $relative + ': ' + $_.Exception.Message)
        }
    }

    $installedExe = Join-Path $InstallDir $exeName
    if (-not (Test-Path -LiteralPath $installedExe)) { Fail 4 'copy reported success but MediaHub.exe is absent in the target' }
    Write-Step ("copied " + [int]((Get-ChildItem -LiteralPath $InstallDir -Recurse -File | Measure-Object -Sum Length).Count) + " file(s)")

    # --- 4. shortcuts ------------------------------------------------------------------------
    if (-not $NoShortcut) {
        try {
            $shell = New-Object -ComObject WScript.Shell
            $targets = @(Join-Path ([Environment]::GetFolderPath('Programs')) 'MediaHub.lnk')
            if ($DesktopShortcut) { $targets += (Join-Path ([Environment]::GetFolderPath('Desktop')) 'MediaHub.lnk') }

            foreach ($linkPath in $targets) {
                $shortcut = $shell.CreateShortcut($linkPath)
                $shortcut.TargetPath = $installedExe
                $shortcut.WorkingDirectory = $InstallDir
                $shortcut.IconLocation = $installedExe + ',0'
                $shortcut.Description = 'MediaHub'
                $shortcut.Save()
                Write-Step ("shortcut    : " + $linkPath)
            }
        }
        catch {
            Fail 4 ("shortcut creation failed: " + $_.Exception.Message)
        }
    }

    # --- 5. uninstall entry ------------------------------------------------------------------
    try {
        $uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\MediaHub'
        if (-not (Test-Path -LiteralPath $uninstallKey)) { New-Item -Path $uninstallKey -Force | Out-Null }
        $sizeKb = [int]((Get-ChildItem -LiteralPath $InstallDir -Recurse -File | Measure-Object -Sum Length).Sum / 1024)
        $uninstallCommand = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $InstallDir 'uninstall.ps1') + '"'
        New-ItemProperty -Path $uninstallKey -Name 'DisplayName'     -Value 'MediaHub'                -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'DisplayVersion'  -Value $version                 -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'Publisher'       -Value 'MediaHub'                -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'DisplayIcon'     -Value ($installedExe + ',0')    -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'InstallLocation' -Value $InstallDir               -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'UninstallString' -Value $uninstallCommand         -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'QuietUninstallString' -Value ($uninstallCommand + ' -Quiet') -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'EstimatedSize'   -Value $sizeKb                  -PropertyType DWord  -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'InstallDate'     -Value (Get-Date -Format 'yyyyMMdd') -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'NoModify'        -Value 1 -PropertyType DWord -Force | Out-Null
        New-ItemProperty -Path $uninstallKey -Name 'NoRepair'        -Value 1 -PropertyType DWord -Force | Out-Null
        Write-Step ("uninstall   : HKCU (Settings > Apps > Installed apps > MediaHub, " + $sizeKb + " KB)")
    }
    catch {
        Fail 4 ("writing the uninstall entry failed: " + $_.Exception.Message)
    }
}

# --- 6. launch -------------------------------------------------------------------------------
if ($Launch -and $PSCmdlet.ShouldProcess('MediaHub', 'start')) {
    Start-Process -FilePath (Join-Path $InstallDir $exeName) -WorkingDirectory $InstallDir | Out-Null
    Write-Step 'started MediaHub'
}

Write-Step ('done (version ' + $version + ')')
exit 0
