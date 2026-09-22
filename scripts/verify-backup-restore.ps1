param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [switch]$IsolatedEmulator,
    [switch]$SkipUi
)
$ErrorActionPreference = 'Stop'
if (-not $IsolatedEmulator -or $Serial -notmatch '^emulator-\d+$') {
    throw 'Requires an explicitly disposable emulator. Never use an existing user device or user emulator.'
}
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbPath = if ($adbCommand) { $adbCommand.Source } elseif ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
} else { throw 'Set ANDROID_HOME or add adb to PATH.' }
$hardware = (& $adbPath -s $Serial shell getprop ro.hardware).Trim()
if ($hardware -notin @('ranchu', 'goldfish')) { throw 'Physical devices are prohibited for this destructive fixture probe.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$runner = 'com.mediahub.app.test/androidx.test.runner.AndroidJUnitRunner'
$probe = 'com.mediahub.app.backup.RestoreProcessDeathTest'

function Invoke-Probe([string]$ClassName, [string]$Checkpoint, [string]$LogName, [switch]$ExpectedDeath) {
    $arguments = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'backupAcceptance', 'isolated')
    if ($Checkpoint) { $arguments += @('-e', 'checkpoint', $Checkpoint) }
    $arguments += @('-e', 'class', $ClassName, $runner)
    $logPath = Join-Path $OutputDirectory $LogName
    & $adbPath @arguments *> $logPath
    $logText = Get-Content -LiteralPath $logPath -Raw
    if ($ExpectedDeath) {
        if ($logText -notmatch 'shortMsg=Process crashed\.') { throw "Expected process termination missing: $logPath" }
    } elseif ($logText -notmatch 'OK \(1 test\)' -or $logText -match 'FAILURES!!!') {
        throw "Verification failed; evidence and state retained: $logPath"
    }
}

if (-not $SkipUi) {
    Invoke-Probe 'com.mediahub.app.backup.BackupUserFlowTest' '' 'ui-saf.log'
    Write-Output 'PASS Settings / SAF export-cancel-save-import-confirmed-replace'
}
foreach ($checkpointName in @('SNAPSHOT', 'PREPARING', 'INVALIDATED', 'DB_BEFORE_MARK', 'DB_WRITTEN', 'PREFERENCES_APPLIED', 'ROLLING_BACK', 'COMPLETED')) {
    Invoke-Probe "$probe#seedAndTerminate" $checkpointName "$checkpointName-seed.log" -ExpectedDeath
    Invoke-Probe "$probe#recoverInNewProcess" $checkpointName "$checkpointName-recover.log"
    Write-Output "PASS new-process recovery: $checkpointName"
}

# This file belongs only to the disposable probe app. The verification method deletes its own test journal.
Invoke-Probe "$probe#seedAndTerminate" 'PREPARING' 'corrupt-journal-seed.log' -ExpectedDeath
$fixturePath = Join-Path $OutputDirectory 'corrupt-journal.fixture'
Set-Content -LiteralPath $fixturePath -Value '<map><string name="active_restore">interrupted' -NoNewline -Encoding utf8
& $adbPath -s $Serial push $fixturePath /data/local/tmp/mediahub-pr18-corrupt.xml | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not transfer the isolated corruption fixture.' }
& $adbPath -s $Serial shell run-as com.mediahub.app cp /data/local/tmp/mediahub-pr18-corrupt.xml shared_prefs/mediahub_restore_journal.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not apply the isolated corruption fixture.' }
Invoke-Probe "$probe#corruptedDiskJournalBlocksRecoveryAndNewRestore" 'PREPARING' 'corrupt-journal-verify.log'
Write-Output 'PASS corrupt disk journal: new process blocks recovery and new restore with zero business writes'
