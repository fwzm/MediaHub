#requires -Version 5.1
<#
Agent C - C3 verification wrapper: PR #18 SAF chain + 8 real process-termination points.

This script DOES NOT reimplement the destructive fixture probe. It reuses the existing
scripts/verify-backup-restore.ps1 (Agent A) and adds Agent C ownership/safety guards:
  * an explicit -Serial is REQUIRED (no default, no ambient device);
  * the target must be an emulator (ro.hardware in ranchu/goldfish);
  * the known physical device model is refused outright;
  * A/B owned AVD names are refused;
  * start/end time, source head and APK SHA256 are recorded next to the logs.

Usage:
  pwsh -File scripts/agent-c/verify-backup-restore-c.ps1 -Serial emulator-5580 -OutDir D:\evidence\c3

Exit codes: 90 bad worktree, 91 missing helper script, 92 bad ANDROID_HOME/adb,
            93 device not found, 94 not an emulator, 95 forbidden device/AVD, 2 helper failed.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$Serial,
  [Parameter(Mandatory=$true)][string]$OutDir,
  [string]$Worktree = "D:\deepseek_test\MediaHub-agent-c-pr18",
  [string[]]$ForbiddenAvd = @("Codex_PR18_Integration_36", "Codex_PR18_Review_36"),
  [string]$ForbiddenModel = "24031PN0DC",
  [string]$AndroidHome = $env:ANDROID_HOME,
  [switch]$SkipUi
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Worktree)) { Write-Error "worktree not found: $Worktree"; exit 90 }
$helper = Join-Path $Worktree "scripts/verify-backup-restore.ps1"
if (-not (Test-Path -LiteralPath $helper)) { Write-Error "helper script missing: $helper"; exit 91 }

# SDK resolution order: -AndroidHome, $env:ANDROID_HOME, per-user standard SDK, adb on PATH.
$candidates = @()
if ($AndroidHome) { $candidates += (Join-Path $AndroidHome "platform-tools/adb.exe") }
if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe") }
$adb = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $adb) {
  $c = Get-Command adb -ErrorAction SilentlyContinue
  if ($c) { $adb = $c.Source } else { Write-Error "adb not found; pass -AndroidHome or set ANDROID_HOME"; exit 92 }
}

$devices = & $adb devices | Select-String -Pattern "^\S+\s+device$" | ForEach-Object { ($_ -split "\s+")[0] }
if ($Serial -notin $devices) { Write-Error "serial not connected: $Serial (connected: $($devices -join ", "))"; exit 93 }

$hardware = (& $adb -s $Serial shell getprop ro.hardware).Trim()
if ($hardware -notin @("ranchu", "goldfish")) { Write-Error "refusing non-emulator target: $Serial (ro.hardware=$hardware)"; exit 94 }
$model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
$avdName = (& $adb -s $Serial emu avd name 2>$null | Select-Object -First 1)
if ($avdName) { $avdName = $avdName.Trim() }
if ($model -eq $ForbiddenModel) { Write-Error "refusing known physical device model: $model"; exit 95 }
if ($avdName -and ($ForbiddenAvd -contains $avdName)) { Write-Error "refusing A/B owned AVD: $avdName"; exit 95 }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$head = (git -C $Worktree rev-parse HEAD).Trim()
$startUtc = (Get-Date).ToUniversalTime().ToString("o")
$log = Join-Path $OutDir "c3-run.log"

"=== Agent C C3 verification ===" | Tee-Object -FilePath $log
"serial   : $Serial (model=$model avd=$avdName hardware=$hardware)" | Tee-Object -FilePath $log -Append
"head     : $head" | Tee-Object -FilePath $log -Append
"start    : $startUtc" | Tee-Object -FilePath $log -Append

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$code = 0
try {
  $helperArgs = @("-Serial", $Serial, "-OutputDirectory", $OutDir, "-IsolatedEmulator")
  if ($SkipUi) { $helperArgs += "-SkipUi" }
  & pwsh -NoProfile -File $helper @helperArgs 2>&1 | Tee-Object -FilePath $log -Append
  if ($LASTEXITCODE -ne 0) { $code = 2 }
} catch {
  $_ | Out-String | Tee-Object -FilePath $log -Append
  $code = 2
}
$sw.Stop()

"=== END exit=$code elapsed=$([math]::Round($sw.Elapsed.TotalSeconds,1))s ===" | Tee-Object -FilePath $log -Append
foreach ($apk in @("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")) {
  $p = Join-Path $Worktree $apk
  if (Test-Path -LiteralPath $p) {
    $h = (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash
    "$apk  $h" | Tee-Object -FilePath $log -Append
  }
}

Get-ChildItem -LiteralPath $OutDir -File | Sort-Object Name | ForEach-Object {
  "{0,-40} {1,10} {2}" -f $_.Name, $_.Length, (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.Substring(0,16)
} | Set-Content -LiteralPath (Join-Path $OutDir "c3-evidence-index.txt") -Encoding UTF8

exit $code
