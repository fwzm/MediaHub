<#
.SYNOPSIS
    Runs the W6-35 end-to-end evidence for the sync page's transfer presenter.

.DESCRIPTION
    The W6-33 presenter is covered by 29 in-process tests in the main suite, where the peer is always a
    helper object built in the same process. That proves the presenter's own logic, but not that it speaks
    the shipped command line's wire protocol.

    This script closes that gap without a GUI: it builds MediaHub.SyncCli, points the transport presenter
    end-to-end project at the resulting DLL through MH_PRESENTER_E2E_CLI, and runs that project. Every peer
    in it is a real 'dotnet MediaHub.SyncCli.dll' child process started with the arguments an operator would
    type, and every assertion is checked against something outside the presenter: the child's exit code, the
    child's JSON on stdout, a store file on disk, a port that can be rebound, or a witness directory that
    must stay empty.

    The project lives under windows/tools/acceptance/w6/presenter-e2e and is deliberately NOT part of
    windows/MediaHub.Windows.sln nor of windows/tests/MediaHub.Tests: the main suite's "1453 passed, 0
    skipped" figure is a pinned promise in the README, and a test that shells out to a child process does not
    belong in it.

    Nothing here starts the MediaHub GUI, opens a window, or writes under D:/mh-out. All temporary roots are
    removed afterwards unless -KeepArtifacts is given.

.PARAMETER OutDir
    Where the build log, the test log and the summary JSON are written. Default: D:/mh-verify/w6-35.

.PARAMETER SkipBuild
    Reuse the DLLs already built (the script still fails if they are missing).

.PARAMETER KeepArtifacts
    Keep the per-case temporary roots for inspection (they are deleted by default).

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/presenter-e2e.ps1 -SkipBuild -KeepArtifacts -OutDir D:/mh-verify/w6-35-keep
#>
[CmdletBinding()]
param(
    [string]$OutDir = 'D:/mh-verify/w6-35',
    [switch]$SkipBuild,
    [switch]$KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:scriptName = 'w6-35-presenter-e2e'
$script:started = Get-Date
$script:EnvName = 'MH_PRESENTER_E2E_CLI'
$script:SecretLiteral = 'correct horse battery staple'

$script:CaseNames = @(
    'R1_plaintext_receive_accepts_a_real_cli_send_and_writes_nothing',
    'R2_tls_receive_hands_its_fingerprint_to_a_real_pinned_send',
    'R3_authenticated_receive_takes_the_secret_from_the_child_environment_only',
    'R4_a_wrong_pin_is_refused_and_the_peer_writes_nothing',
    'R5_a_send_to_a_real_serve_applies_both_sources',
    'R6a_confirm_delivery_against_a_real_serve_reports_the_close',
    'R6b_confirm_delivery_against_a_silent_peer_is_delivery_unconfirmed',
    'R7a_stopping_the_receiver_releases_the_port_for_a_rebind',
    'R7b_cancelling_a_waiting_receive_reports_cancelled_and_writes_nothing',
    'R8_a_receipt_written_by_the_presenter_is_read_back_by_a_real_cli_send',
    'R9_a_presenter_send_that_awaits_a_receipt_gets_one_from_a_real_serve',
    'R10_a_real_serve_that_does_not_acknowledge_leaves_the_presenter_with_receipt_missing'
)

function Write-Step {
    param([string]$Text)

    Write-Host ''
    Write-Host ('== ' + $Text) -ForegroundColor Cyan
}

function Read-Text {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return ''
    }

    return Get-Content -LiteralPath $Path -Raw -Encoding utf8
}

function Get-Counts {
    param([string]$Text)

    # vstest prints two shapes depending on whether the run is clean:
    #   "失败: 0，通过: 9，已跳过: 0，总计: 9"    (any failure)
    #   "测试总数: 9 / 通过数: 9"                 (clean run)
    # Both are parsed so the script does not report 0/0 on a green run.
    $legacy = [regex]::Match($Text, '失败:\s*(\d+)，\s*通过:\s*(\d+)，\s*已跳过:\s*(\d+)，\s*总计:\s*(\d+)')
    if ($legacy.Success) {
        return [pscustomobject]@{
            Passed  = [int]$legacy.Groups[2].Value
            Failed  = [int]$legacy.Groups[1].Value
            Skipped = [int]$legacy.Groups[3].Value
            Total   = [int]$legacy.Groups[4].Value
        }
    }

    $passed = 0
    $failed = 0
    $skipped = 0
    $total = 0
    $totalMatch = [regex]::Match($Text, '测试总数:\s*(\d+)')
    $passedMatch = [regex]::Match($Text, '通过数:\s*(\d+)')
    $skippedMatch = [regex]::Match($Text, '跳过数:\s*(\d+)')
    $failedMatch = [regex]::Match($Text, '失败数:\s*(\d+)')
    if ($totalMatch.Success) { $total = [int]$totalMatch.Groups[1].Value }
    if ($passedMatch.Success) { $passed = [int]$passedMatch.Groups[1].Value }
    if ($skippedMatch.Success) { $skipped = [int]$skippedMatch.Groups[1].Value }
    if ($failedMatch.Success) {
        $failed = [int]$failedMatch.Groups[1].Value
    }
    elseif ($total -gt 0 -and $passed -lt $total) {
        $failed = $total - $passed
    }

    return [pscustomobject]@{ Passed = $passed; Failed = $failed; Skipped = $skipped; Total = $total }
}

function Get-Verdict {
    param([string]$Text, [string]$CaseName)

    $pattern = '(已通过|失败|已跳过)\s+\S*' + [regex]::Escape($CaseName) + '\s*\[([0-9]+ (ms|s)|< 1 ms)\]'
    $matched = [regex]::Match($Text, $pattern)
    if (-not $matched.Success) {
        return [pscustomobject]@{ Verdict = 'missing'; Duration = '' }
    }

    return [pscustomobject]@{ Verdict = $matched.Groups[1].Value; Duration = $matched.Groups[2].Value }
}

function Get-ResidualProcesses {
    $mine = @(Get-CimInstance Win32_Process -Filter "Name = 'dotnet.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*MediaHub.SyncCli.dll*' })

    return $mine
}

$repoRoot = Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
$cliProject = Join-Path $repoRoot 'windows/src/MediaHub.SyncCli/MediaHub.SyncCli.csproj'
$cliDll = Join-Path $repoRoot 'windows/src/MediaHub.SyncCli/bin/Debug/net10.0/MediaHub.SyncCli.dll'
$testProject = Join-Path $PSScriptRoot 'presenter-e2e/PresenterE2E.csproj'
$buildLog = Join-Path $OutDir 'synccli-build.log'
$testLog = Join-Path $OutDir 'presenter-e2e.log'
$summaryPath = Join-Path $OutDir 'presenter-e2e-summary.json'

Write-Host ('repo root: ' + $repoRoot)
Write-Host ('out dir:   ' + $OutDir)

if (-not (Test-Path -LiteralPath $repoRoot)) {
    throw 'the repository root could not be resolved from the script path'
}

if (-not (Test-Path -LiteralPath $testProject)) {
    throw ('the end-to-end project is missing: ' + $testProject)
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}

foreach ($stale in @($buildLog, $testLog, $summaryPath)) {
    if (Test-Path -LiteralPath $stale) {
        Remove-Item -LiteralPath $stale -Force
    }
}

Write-Step 'clearing leftovers from an earlier run'
foreach ($process in @(Get-ResidualProcesses)) {
    Write-Host ('  stopping leftover CLI child ' + $process.ProcessId)
    try {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }
    catch {
        Write-Host ('  could not stop ' + $process.ProcessId + ': ' + $_.Exception.Message)
    }
}

$buildSucceeded = $false
if ($SkipBuild) {
    Write-Step 'build skipped (-SkipBuild)'
    $buildSucceeded = Test-Path -LiteralPath $cliDll
    if (-not $buildSucceeded) {
        Write-Host ('  MISSING: ' + $cliDll) -ForegroundColor Red
    }
}
else {
    Write-Step 'building MediaHub.SyncCli'
    Push-Location $repoRoot
    try {
        & dotnet build $cliProject -c Debug -v:m 2>&1 | Tee-Object -FilePath $buildLog | Out-Host
        $buildSucceeded = ($LASTEXITCODE -eq 0) -and (Test-Path -LiteralPath $cliDll)
    }
    finally {
        Pop-Location
    }
}

$buildText = Read-Text -Path $buildLog
$buildWarnings = 0
$buildErrors = 0
if ($buildText -ne '') {
    $warn = [regex]::Match($buildText, '(\d+) 个警告')
    $err = [regex]::Match($buildText, '(\d+) 个错误')
    if ($warn.Success) { $buildWarnings = [int]$warn.Groups[1].Value }
    if ($err.Success) { $buildErrors = [int]$err.Groups[1].Value }
}

if (-not $buildSucceeded) {
    Write-Host 'the CLI could not be built, so nothing can be exercised' -ForegroundColor Red
    exit 1
}

Write-Host ('CLI dll: ' + $cliDll)

$previous = [Environment]::GetEnvironmentVariable($script:EnvName, 'Process')
[Environment]::SetEnvironmentVariable($script:EnvName, $cliDll, 'Process')

$testExit = 1
try {
    Write-Step 'running the end-to-end project (every peer is a real child process)'
    Push-Location $repoRoot
    try {
        & dotnet test $testProject -c Debug --logger 'console;verbosity=normal' 2>&1 | Tee-Object -FilePath $testLog | Out-Host
        $testExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    [Environment]::SetEnvironmentVariable($script:EnvName, $previous, 'Process')
}

$testText = Read-Text -Path $testLog
$counts = Get-Counts -Text $testText

Write-Step 'case results'
$rows = @()
foreach ($caseName in $script:CaseNames) {
    $verdict = Get-Verdict -Text $testText -CaseName $caseName
    $rows += [pscustomobject]@{
        Case     = $caseName
        Verdict  = $verdict.Verdict
        Duration = $verdict.Duration
    }
}

$rows | Format-Table -AutoSize -Wrap | Out-Host

Write-Step 'secret and artifact checks'
$secretLeak = $testText.Contains($script:SecretLiteral, [StringComparison]::Ordinal)
if ($secretLeak) {
    Write-Host '  FAIL the shared secret appeared verbatim in the test output' -ForegroundColor Red
}
else {
    Write-Host '  PASS the shared secret never appeared in the test output' -ForegroundColor Green
}

$roots = @()
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'mh-w6-35-'
$roots = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Directory -Filter 'mh-w6-35-*' -ErrorAction SilentlyContinue)
$keptRoots = $roots.Count
if ($KeepArtifacts) {
    Write-Host ('  left ' + $keptRoots + ' temporary root(s) in place (-KeepArtifacts)')
}
else {
    foreach ($root in $roots) {
        try {
            Remove-Item -LiteralPath $root.FullName -Recurse -Force -ErrorAction Stop
        }
        catch {
            Write-Host ('  could not remove ' + $root.FullName + ': ' + $_.Exception.Message)
        }
    }

    $left = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Directory -Filter 'mh-w6-35-*' -ErrorAction SilentlyContinue)
    Write-Host ('  removed temporary roots under ' + $tempRoot + ' (left: ' + $left.Count + ')')
}

$residual = @(Get-ResidualProcesses)
Write-Host ('residual MediaHub.SyncCli processes: ' + $residual.Count)

$runSucceeded = $testText.Contains('测试运行成功', [StringComparison]::Ordinal)
$allPassed = $runSucceeded -and ($counts.Failed -eq 0) -and ($counts.Passed -ge $script:CaseNames.Count) -and ($counts.Total -eq $counts.Passed + $counts.Failed)
$allPresent = @($rows | Where-Object { $_.Verdict -ne '已通过' }).Count -eq 0
$noLeftovers = $residual.Count -eq 0
$ok = $allPassed -and $allPresent -and $noLeftovers -and (-not $secretLeak) -and ($testExit -eq 0)

$elapsed = (Get-Date) - $script:started
$summary = [pscustomobject]@{
    script                = $script:scriptName
    startedAt             = $script:started.ToString('o')
    elapsedMs             = [int]$elapsed.TotalMilliseconds
    repoRoot              = $repoRoot
    cliDll                = $cliDll
    testProject           = $testProject
    build                 = [pscustomobject]@{
        ran        = (-not $SkipBuild)
        succeeded  = $buildSucceeded
        warnings   = $buildWarnings
        errors     = $buildErrors
        log        = $buildLog
    }
    tests                 = [pscustomobject]@{
        exitCode = $testExit
        passed   = $counts.Passed
        failed   = $counts.Failed
        skipped  = $counts.Skipped
        total    = $counts.Total
        log      = $testLog
    }
    cases                 = $rows
    secretLeaked          = $secretLeak
    residualProcesses     = $residual.Count
    tempRootsKept         = [bool]$KeepArtifacts
    tempRootsLeft         = $keptRoots
    passed                = $ok
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Step 'summary'
Write-Host ('tests:       ' + $counts.Passed + ' passed, ' + $counts.Failed + ' failed, ' + $counts.Skipped + ' skipped, ' + $counts.Total + ' total')
Write-Host ('cases:       ' + @($rows | Where-Object { $_.Verdict -eq '已通过' }).Count + '/' + $script:CaseNames.Count + ' passed')
Write-Host ('secret leak: ' + $secretLeak)
Write-Host ('residual:    ' + $residual.Count)
Write-Host ('elapsed:     ' + [int]$elapsed.TotalMilliseconds + ' ms')
Write-Host ('summary:     ' + $summaryPath)

if ($ok) {
    Write-Host ''
    Write-Host ('RESULT: ' + $counts.Passed + ' passed, 0 failed (' + $script:CaseNames.Count + ' cases)') -ForegroundColor Green
    exit 0
}

Write-Host ''
Write-Host ('RESULT: ' + $counts.Passed + ' passed, ' + $counts.Failed + ' failed (see ' + $testLog + ')') -ForegroundColor Red
exit 1
