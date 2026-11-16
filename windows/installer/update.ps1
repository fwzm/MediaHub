#Requires -Version 5.1
<#
.SYNOPSIS
    Update agent for an installed MediaHub: wait for the app to exit, release file locks, swap the
    program tree, repair shortcuts, and roll back if anything after the swap fails.

.DESCRIPTION
    This script is the only part of the update that runs while MediaHub is not. The running app
    downloads and verifies the package, starts this script hidden, and then closes itself. The script
    waits for that process (or stops it after -WaitSeconds), stops anything else still running from
    the install directory, and only then renames the old tree aside.

    Layout and conventions are the ones install.ps1 established: per-user, unpackaged, program files
    in %LOCALAPPDATA%\Programs\MediaHub, user data in %LOCALAPPDATA%\MediaHub. No elevation, no
    service, no third-party tool: Expand-Archive, Get-FileHash, WScript.Shell and Start-Process all
    ship with Windows.

    The package is the same zip the install kit publishes. Its SHA-256 is checked before anything is
    unpacked, the extracted tree is checked before the swap, and the installed required files are
    hashed again after the swap. A failure after the swap restores the renamed backup, so a failed
    update leaves the previous build installed, never half of each.

    The package and this script are unsigned. A zip obtained through a browser can also carry the
    Mark-of-the-Web, which is what makes SmartScreen warn on first launch; nothing here suppresses
    that warning or pretends the binaries are signed.

.PARAMETER InstallDir
    Installed program tree to update. Required.

.PARAMETER PackageZip
    Update package produced by make-install-kit.ps1. Required.

.PARAMETER ExpectedSha256
    SHA-256 of the zip, from update.json. Required: an unverified package is never unpacked.

.PARAMETER NewVersion
    Version the package must contain. When supplied, the extracted MediaHub.exe must report it.

.PARAMETER ParentPid
    The MediaHub process that started this script. It is waited for, then stopped if still alive.

.PARAMETER WaitSeconds
    Grace period for the parent process. Default 30.

.PARAMETER Restart
    Start MediaHub again after a successful update (also after a rollback, so the user is not left
    with no window).

.PARAMETER StateDir
    Directory for logs, state, staging and backups. Default %LOCALAPPDATA%\MediaHub\update.

.PARAMETER StartMenuDir
.PARAMETER DesktopDir
.PARAMETER TaskbarPinsDir
    Shortcut locations. Defaults are the current user's real folders. Overridable so an automated
    drill can use throw-away directories instead of the signed-in user's desktop.

.PARAMETER NoShortcuts
    Skip shortcut repair.

.PARAMETER FailAfterSwap
    Fault-injection switch for the rollback drill. It makes the agent fail immediately after the old
    tree has been renamed aside and the new one is in place. The application never passes it.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File update.ps1 `
        -InstallDir "$env:LOCALAPPDATA\Programs\MediaHub" `
        -PackageZip "$env:LOCALAPPDATA\MediaHub\update\downloads\1.1.0\MediaHub-Setup-win-x64-1.1.0.zip" `
        -ExpectedSha256 <hex> -NewVersion 1.1.0 -Restart

.NOTES
    Exit codes: 0 = updated, 2 = bad arguments/missing files, 3 = package hash mismatch,
    4 = preparation failed (installation untouched), 5 = update failed and the previous version was
    restored, 6 = rollback failed (backup path is in the log and state.json), 7 = another update is
    already running.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $InstallDir,
    [Parameter(Mandatory = $true)] [string] $PackageZip,
    [Parameter(Mandatory = $true)] [string] $ExpectedSha256,
    [string] $NewVersion = '',
    [int] $ParentPid = 0,
    [int] $WaitSeconds = 30,
    [switch] $Restart,
    [string] $StateDir,
    [string] $StartMenuDir,
    [string] $DesktopDir,
    [string] $TaskbarPinsDir,
    [switch] $NoShortcuts,
    [switch] $FailAfterSwap,
    [string] $LogPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

# Windows PowerShell 5.1 started from PowerShell 7 inherits PS7's PSModulePath, which hides 5.1's own
# modules and makes Get-FileHash look missing. Same repair as install.ps1.
$psHomeModules = Join-Path $PSHOME 'Modules'
if (($env:PSModulePath -split ';') -notcontains $psHomeModules) {
    $env:PSModulePath = $psHomeModules + ';' + $env:PSModulePath
}

function Get-NormalizedVersion([string] $Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $value = ($Text -split '\+')[0].Trim()
    $parts = @($value -split '\.')
    while ($parts.Count -lt 4) { $parts += '0' }
    $numbers = @()
    foreach ($part in $parts[0..3]) {
        $number = 0
        if (-not [int]::TryParse($part, [ref] $number)) { return $value }
        $numbers += $number
    }
    return (New-Object 'System.Version' $numbers[0], $numbers[1], $numbers[2], $numbers[3]).ToString(4)
}

function Get-ExeVersion([string] $ExePath) {
    if (-not (Test-Path -LiteralPath $ExePath)) { return '' }
    try {
        $info = (Get-Item -LiteralPath $ExePath).VersionInfo
        if ($info.ProductVersion) { return Get-NormalizedVersion $info.ProductVersion }
        if ($info.FileVersion) { return Get-NormalizedVersion $info.FileVersion }
    }
    catch { }
    return ''
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

function Move-DirectoryWithRetry([string] $From, [string] $To, [int] $Attempts = 30) {
    $parent = Split-Path -Parent $To
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Move-Item -LiteralPath $From -Destination $To -ErrorAction Stop
            return $true
        }
        catch {
            if ($attempt -eq $Attempts) {
                Write-Log ('move failed after ' + $Attempts + ' attempts: ' + $From + ' -> ' + $To + ': ' + $_.Exception.Message)
                return $false
            }
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Remove-DirectoryWithRetry([string] $Path, [int] $Attempts = 10) {
    if (-not (Test-Path -LiteralPath $Path)) { return $true }
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return $true
        }
        catch {
            if ($attempt -eq $Attempts) { return $false }
            Start-Sleep -Milliseconds 400
        }
    }
    return $false
}

# ------------------------------------------------------------------------------------------------
# log / state / lock
# ------------------------------------------------------------------------------------------------

$installRoot = [System.IO.Path]::GetFullPath($InstallDir)
$packagePath = [System.IO.Path]::GetFullPath($PackageZip)
if (-not $StateDir) { $StateDir = Join-Path $env:LOCALAPPDATA 'MediaHub\update' }
$stateRoot = [System.IO.Path]::GetFullPath($StateDir)
New-Item -ItemType Directory -Force -Path (Join-Path $stateRoot 'logs') | Out-Null

if (-not $LogPath) {
    $LogPath = Join-Path (Join-Path $stateRoot 'logs') ('update-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
}
$logDirectory = Split-Path -Parent $LogPath
if ($logDirectory) { New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null }
$script:LogPath = [System.IO.Path]::GetFullPath($LogPath)
$script:OldVersion = ''
$script:NewVersion = Get-NormalizedVersion $NewVersion
$script:BackupPath = ''
$script:WorkRoot = ''
$script:FailureCode = 4
$script:Swapped = $false

function Write-Log([string] $Message) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') + ' ' + $Message
    Write-Host ('update: ' + $Message)
    try { Add-Content -LiteralPath $script:LogPath -Value $line -Encoding UTF8 } catch { }
}

function Write-State([string] $Status, [string] $ErrorText, [bool] $Restarted) {
    $state = [ordered]@{
        status        = $Status
        oldVersion    = $script:OldVersion
        newVersion    = $script:NewVersion
        packageSha256 = $ExpectedSha256.ToLowerInvariant()
        installDir    = $installRoot
        backup        = $script:BackupPath
        log           = $script:LogPath
        restarted     = $Restarted
        error         = $ErrorText
        updatedAt     = (Get-Date).ToString('o')
    }
    try {
        $json = $state | ConvertTo-Json -Depth 4
        [System.IO.File]::WriteAllText((Join-Path $stateRoot 'state.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
    }
    catch { Write-Log ('could not write state.json: ' + $_.Exception.Message) }
}

$lockPath = Join-Path $stateRoot 'update.lock'
$lockStream = $null
try {
    $lockStream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
}
catch {
    Write-Host ('update: FAILED - another update is already running (' + $lockPath + ')') -ForegroundColor Red
    exit 7
}

# ------------------------------------------------------------------------------------------------
# helpers for processes and shortcuts
# ------------------------------------------------------------------------------------------------

function Get-ProcessesUnderInstall([string] $Root) {
    $result = @()
    foreach ($process in @(Get-Process -ErrorAction SilentlyContinue)) {
        if (-not $process) { continue }
        if ($process.Id -eq $PID) { continue }
        try {
            $path = $process.Path
            if ($path -and $path.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
                $result += $process
            }
        }
        catch { }
    }
    return $result
}

function Stop-ProcessesUnderInstall([string] $Root) {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        $processes = @(Get-ProcessesUnderInstall $Root)
        if ($processes.Count -eq 0) { return $true }
        foreach ($process in $processes) {
            try {
                Write-Log ('stopping process ' + $process.ProcessName + ' (' + $process.Id + ') holding files in the install directory')
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
            }
            catch { Write-Log ('could not stop process ' + $process.Id + ': ' + $_.Exception.Message) }
        }
        Start-Sleep -Milliseconds 500
    }
    return (@(Get-ProcessesUnderInstall $Root).Count -eq 0)
}

function Repair-OneShortcut([object] $Shell, [string] $LinkPath, [bool] $CreateWhenMissing) {
    if (-not (Test-Path -LiteralPath $LinkPath) -and -not $CreateWhenMissing) { return }
    $shortcut = $Shell.CreateShortcut($LinkPath)
    $shortcut.TargetPath = Join-Path $installRoot 'MediaHub.exe'
    $shortcut.WorkingDirectory = $installRoot
    $shortcut.IconLocation = (Join-Path $installRoot 'MediaHub.exe') + ',0'
    $shortcut.Description = 'MediaHub'
    $shortcut.Save()
    Write-Log ('shortcut repaired: ' + $LinkPath)
}

function Test-ShortcutBelongsToUs([object] $Shell, [string] $LinkPath, [string] $OldRoot) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($LinkPath)
    if ($name -like 'MediaHub*') { return $true }
    try {
        $shortcut = $Shell.CreateShortcut($LinkPath)
        $target = $shortcut.TargetPath
        if (-not $target) { return $false }
        if ($target.StartsWith($installRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
        if ($OldRoot -and $target.StartsWith($OldRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
        $icon = $shortcut.IconLocation
        if ($icon -and $icon.StartsWith($installRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
        return [System.IO.Path]::GetFileName($target) -ieq 'MediaHub.exe'
    }
    catch { return $false }
}

function Repair-Shortcuts([string] $OldRoot) {
    if ($NoShortcuts) { Write-Log 'shortcut repair skipped (-NoShortcuts)'; return }
    try {
        $shell = New-Object -ComObject WScript.Shell

        if (-not $StartMenuDir) { $StartMenuDir = [Environment]::GetFolderPath('Programs') }
        if (-not $DesktopDir) { $DesktopDir = [Environment]::GetFolderPath('Desktop') }
        if (-not $TaskbarPinsDir) {
            $TaskbarPinsDir = Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar'
        }

        if ($StartMenuDir) {
            # Repair what exists; install.ps1 has a -NoShortcut switch, so a missing link can be a
            # deliberate choice and must not be recreated here.
            Repair-OneShortcut $shell (Join-Path $StartMenuDir 'MediaHub.lnk') $false
        }

        if ($DesktopDir) {
            # A desktop icon is the user's choice (install.ps1 only creates it with -DesktopShortcut);
            # repair it when present, never add one unasked.
            Repair-OneShortcut $shell (Join-Path $DesktopDir 'MediaHub.lnk') $false
        }

        if ($TaskbarPinsDir -and (Test-Path -LiteralPath $TaskbarPinsDir)) {
            foreach ($link in @(Get-ChildItem -LiteralPath $TaskbarPinsDir -Filter '*.lnk' -File -ErrorAction SilentlyContinue)) {
                if (Test-ShortcutBelongsToUs $shell $link.FullName $OldRoot) {
                    Repair-OneShortcut $shell $link.FullName $false
                }
            }
        }
    }
    catch {
        # Shell integration is repair work, not part of the binary swap. A locked or unreadable
        # shortcut must not turn a successful update into a rollback.
        Write-Log ('shortcut repair warning: ' + $_.Exception.Message)
    }

    try {
        Add-Type -Namespace MediaHubUpdate -Name ShellNotify -ErrorAction SilentlyContinue -MemberDefinition @'
[DllImport("shell32.dll")] public static extern void SHChangeNotify(int eventId, uint flags, System.IntPtr item1, System.IntPtr item2);
'@
        [MediaHubUpdate.ShellNotify]::SHChangeNotify(0x08000000, 0x1000, [System.IntPtr]::Zero, [System.IntPtr]::Zero)
    }
    catch { }
}

function Start-MediaHub() {
    $exe = Join-Path $installRoot 'MediaHub.exe'
    if (-not (Test-Path -LiteralPath $exe)) { Write-Log 'restart skipped: MediaHub.exe is absent'; return $false }
    try {
        Start-Process -FilePath $exe -WorkingDirectory $installRoot | Out-Null
        Write-Log 'MediaHub restarted'
        return $true
    }
    catch {
        Write-Log ('restart failed: ' + $_.Exception.Message)
        return $false
    }
}

function Restore-Backup() {
    if (-not $script:BackupPath -or -not (Test-Path -LiteralPath $script:BackupPath)) { return $false }
    Write-Log ('rolling back from ' + $script:BackupPath)
    Stop-ProcessesUnderInstall $installRoot | Out-Null
    if (Test-Path -LiteralPath $installRoot) {
        if (-not (Remove-DirectoryWithRetry $installRoot 20)) {
            Write-Log 'could not remove the failed installation directory'
            return $false
        }
    }
    if (-not (Move-DirectoryWithRetry $script:BackupPath $installRoot 20)) { return $false }
    $script:BackupPath = ''
    return $true
}

# ------------------------------------------------------------------------------------------------
# 1. arguments
# ------------------------------------------------------------------------------------------------

Write-Log ('agent start; install=' + $installRoot + '; package=' + $packagePath)
Write-Log ('expected sha256=' + $ExpectedSha256.ToLowerInvariant())

if (-not (Test-Path -LiteralPath $installRoot)) {
    Write-Log ('install directory not found: ' + $installRoot)
    $script:FailureCode = 2
    Write-State 'failed' 'install directory not found' $false
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $installRoot 'MediaHub.exe'))) {
    Write-Log 'install directory has no MediaHub.exe'
    $script:FailureCode = 2
    Write-State 'failed' 'install directory has no MediaHub.exe' $false
    exit 2
}
if (-not (Test-Path -LiteralPath $packagePath)) {
    Write-Log ('package not found: ' + $packagePath)
    $script:FailureCode = 2
    Write-State 'failed' 'package not found' $false
    exit 2
}
if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
    Write-Log 'expected sha256 is not 64 hexadecimal characters'
    $script:FailureCode = 2
    Write-State 'failed' 'invalid expected sha256' $false
    exit 2
}

$script:OldVersion = Get-ExeVersion (Join-Path $installRoot 'MediaHub.exe')
Write-Log ('installed version=' + $script:OldVersion + '; package version=' + $script:NewVersion)

$exitCode = 0
$restarted = $false
$errorText = ''

try {
    # --- 2. let the app leave, then release every handle under the install directory -----------
    if ($ParentPid -gt 0) {
        $parent = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
        if ($parent) {
            Write-Log ('waiting up to ' + $WaitSeconds + 's for process ' + $ParentPid + ' to exit')
            if (-not $parent.WaitForExit($WaitSeconds * 1000)) {
                Write-Log ('process ' + $ParentPid + ' did not exit in time; stopping it')
                try { Stop-Process -Id $ParentPid -Force -ErrorAction Stop } catch { }
            }
            try { $parent.WaitForExit(5000) | Out-Null } catch { }
        }
    }

    if (-not (Stop-ProcessesUnderInstall $installRoot)) {
        Write-Log 'processes inside the install directory are still running; nothing was changed'
        $script:FailureCode = 4
        throw 'could not release processes holding the install directory'
    }

    # --- 3. verify the package before unpacking ------------------------------------------------
    $script:FailureCode = 3
    $actualHash = Get-Sha256 $packagePath
    if ($actualHash -ne $ExpectedSha256.ToLowerInvariant()) {
        Write-Log ('package hash mismatch: expected ' + $ExpectedSha256.ToLowerInvariant() + ', got ' + $actualHash)
        throw 'package SHA-256 mismatch'
    }
    Write-Log 'package hash verified'

    # --- 4. unpack and validate the payload before touching the installation --------------------
    $script:FailureCode = 4
    $script:WorkRoot = Join-Path (Join-Path $stateRoot 'work') ([System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $script:WorkRoot | Out-Null
    Expand-Archive -LiteralPath $packagePath -DestinationPath $script:WorkRoot -Force -ErrorAction Stop

    $payload = Join-Path $script:WorkRoot 'app'
    if (-not (Test-Path -LiteralPath (Join-Path $payload 'MediaHub.exe'))) {
        $found = @(Get-ChildItem -LiteralPath $script:WorkRoot -Recurse -Filter 'MediaHub.exe' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Directory.Name -eq 'app' } | Select-Object -First 1)
        if ($found.Count -gt 0) { $payload = $found[0].Directory.FullName }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $payload 'MediaHub.exe'))) {
        throw 'package has no app\MediaHub.exe'
    }

    $required = @('MediaHub.exe', 'MediaHub.dll', 'libmpv-2.dll')
    $requiredHashes = @{}
    foreach ($name in $required) {
        $file = Join-Path $payload $name
        if (-not (Test-Path -LiteralPath $file)) { throw ('package payload is missing ' + $name) }
        $requiredHashes[$name] = Get-Sha256 $file
    }

    $payloadVersion = Get-ExeVersion (Join-Path $payload 'MediaHub.exe')
    if ($script:NewVersion -and $payloadVersion -ne $script:NewVersion) {
        throw ('package version mismatch: expected ' + $script:NewVersion + ', payload reports ' + $payloadVersion)
    }
    if (-not $script:NewVersion) { $script:NewVersion = $payloadVersion }
    Write-Log ('payload validated: version=' + $payloadVersion + ', files=' + $required.Count + ' required')

    # --- 5. move the old tree aside and put the new one in place --------------------------------
    $backupParent = Join-Path $stateRoot 'backups'
    New-Item -ItemType Directory -Force -Path $backupParent | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $script:BackupPath = Join-Path $backupParent (('app-' + ($script:OldVersion -replace '[^0-9.]', '') + '-' + $stamp).Trim('-'))

    Write-Log ('moving current installation to ' + $script:BackupPath)
    if (-not (Move-DirectoryWithRetry $installRoot $script:BackupPath)) {
        throw 'could not move the current installation aside; nothing was replaced'
    }
    $script:Swapped = $true

    if (-not (Move-DirectoryWithRetry $payload $installRoot)) {
        throw 'could not move the new payload into place'
    }
    Write-Log 'new payload is in place'

    # The kit root carries the uninstaller; the app tree does not. Preserve the old one if a
    # package was produced without it, and always leave this agent in the install directory.
    $kitUninstall = Join-Path $script:WorkRoot 'uninstall.ps1'
    if (Test-Path -LiteralPath $kitUninstall) {
        Copy-Item -LiteralPath $kitUninstall -Destination (Join-Path $installRoot 'uninstall.ps1') -Force
    }
    elseif (Test-Path -LiteralPath (Join-Path $script:BackupPath 'uninstall.ps1')) {
        Copy-Item -LiteralPath (Join-Path $script:BackupPath 'uninstall.ps1') -Destination (Join-Path $installRoot 'uninstall.ps1') -Force
    }
    if ($PSCommandPath -and (Test-Path -LiteralPath $PSCommandPath)) {
        Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $installRoot 'update.ps1') -Force
    }

    if ($FailAfterSwap) {
        throw 'simulated failure after swap (rollback drill)'
    }

    # --- 6. post-swap verification --------------------------------------------------------------
    $installedVersion = Get-ExeVersion (Join-Path $installRoot 'MediaHub.exe')
    if ($script:NewVersion -and $installedVersion -ne $script:NewVersion) {
        throw ('installed version ' + $installedVersion + ' does not match ' + $script:NewVersion)
    }
    foreach ($name in $required) {
        $installed = Join-Path $installRoot $name
        if (-not (Test-Path -LiteralPath $installed)) { throw ('installed tree is missing ' + $name) }
        if ((Get-Sha256 $installed) -ne $requiredHashes[$name]) { throw ('installed copy of ' + $name + ' is corrupt') }
    }
    Write-Log 'post-swap verification passed'

    # --- 7. shortcuts + state ------------------------------------------------------------------
    Repair-Shortcuts $script:BackupPath

    Write-State 'succeeded' '' $false
    if ($Restart) { $restarted = Start-MediaHub }
    Write-State 'succeeded' '' $restarted

    # Keep the previous build for the next rollback and drop older ones.
    try {
        $oldBackups = @(Get-ChildItem -LiteralPath $backupParent -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -Skip 2)
        foreach ($old in $oldBackups) { Remove-DirectoryWithRetry $old.FullName 3 | Out-Null }
    }
    catch { }

    Write-Log ('update succeeded: ' + $script:OldVersion + ' -> ' + $script:NewVersion)
    $exitCode = 0
}
catch {
    $errorText = $_.Exception.Message
    Write-Log ('FAILED: ' + $errorText)

    # Only a completed first move means the backup is the previous installation. A move that failed
    # halfway (possible when the state directory is on another volume) leaves the real installation
    # in place and a partial copy at the backup path; restoring that copy would destroy a good tree.
    if (-not $script:Swapped -and $script:BackupPath -and (Test-Path -LiteralPath $script:BackupPath)) {
        Write-Log 'the previous installation was never moved; removing the partial backup copy'
        Remove-DirectoryWithRetry $script:BackupPath 3 | Out-Null
        $script:BackupPath = ''
    }

    if ($script:Swapped -and $script:BackupPath -and (Test-Path -LiteralPath $script:BackupPath)) {
        $restored = Restore-Backup
        if ($restored) {
            Repair-Shortcuts ''
            Write-State 'rolled-back' $errorText $false
            if ($Restart) { $restarted = Start-MediaHub }
            Write-State 'rolled-back' $errorText $restarted
            Write-Log ('rollback complete; previous version ' + $script:OldVersion + ' restored')
            $exitCode = 5
        }
        else {
            Write-State 'rollback-failed' $errorText $false
            Write-Log ('ROLLBACK FAILED; previous tree is still at ' + $script:BackupPath)
            $exitCode = 6
        }
    }
    else {
        # Nothing was moved: the installation is exactly what it was before this run.
        Write-State 'failed' $errorText $false
        Write-Log 'no files were changed'
        $exitCode = $script:FailureCode
    }
}
finally {
    if ($script:WorkRoot -and (Test-Path -LiteralPath $script:WorkRoot)) {
        Remove-DirectoryWithRetry $script:WorkRoot 3 | Out-Null
    }
    if ($lockStream) { try { $lockStream.Dispose() } catch { } }
}

exit $exitCode
