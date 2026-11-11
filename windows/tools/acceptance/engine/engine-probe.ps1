#Requires -Version 7.0
<#
.SYNOPSIS
  W13: run the headless engine probe against the GPL baseline and the LGPL candidate libmpv builds
  and compare what actually happened during decoding.

.DESCRIPTION
  For each build the probe is started through MediaHub.EngineProbe.exe, which binds libmpv by absolute
  path (NativeLibrary.Load + GetExport + GetDelegateForFunctionPointer), sets vo=null / ao=null, opens
  the media file and watches time-pos until the requested number of seconds has really been decoded.
  Nothing is copied next to anything else: the DLL under test stays exactly where it is, so this script
  cannot damage windows/native/libmpv.

  Every assertion prints 'PASS <name> :: <detail>' or 'FAIL <name> :: <detail>'; the last line is
  'RESULT: N passed, M failed'. Evidence (JSON per flavour, stdout log per flavour, summary) is written
  under windows/tools/acceptance/evidence/engine, which is gitignored.

.EXAMPLE
  pwsh -NoProfile -File windows/tools/acceptance/engine/engine-probe.ps1

.NOTES
  Exit code 0 only when every assertion passed.
#>
[CmdletBinding()]
param(
    [string]$MediaPath = 'D:\mh-media\playback-180s.mp4',
    [double]$Seconds = 10,
    [string[]]$LgplDependencies = @('D:\mh-servers\lgpl-build\prefix\bin', 'C:\msys64\ucrt64\bin'),
    [string]$EvidenceDirectory = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$ProbeExe = Join-Path $RepoRoot 'windows\src\MediaHub.EngineProbe\bin\Debug\net10.0\MediaHub.EngineProbe.exe'
$GplDll = Join-Path $RepoRoot 'windows\native\libmpv\libmpv-2.dll'
$LgplDll = Join-Path $RepoRoot 'windows\engine\lgpl\dist\libmpv-2.dll'

if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $RepoRoot 'windows\tools\acceptance\evidence\engine'
}

# Identity is pinned: a probe of "whatever DLL happens to be there" would prove nothing.
$ExpectedGplSha256 = '6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9'
$ExpectedGplBytes = 120989696
$ExpectedLgplSha256 = '20035902597d6d04d044bada05f5ff9a0aec294f4847f4f39632cd08ee4a3c0a'
$ExpectedLgplBytes = 3327274

New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

$script:Passed = 0
$script:Failed = 0
$script:Results = [System.Collections.Generic.List[string]]::new()

function Assert-That {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Condition,
        [string]$Detail = ''
    )

    if ($Condition) {
        $script:Passed++
        $line = "PASS $Name :: $Detail"
    }
    else {
        $script:Failed++
        $line = "FAIL $Name :: $Detail"
    }

    Write-Host $line
    $script:Results.Add($line)
}

function Invoke-EngineProbe {
    param(
        [Parameter(Mandatory)][string]$Flavour,
        [Parameter(Mandatory)][string]$DllPath,
        [string[]]$DependencyDirectories = @()
    )

    $jsonPath = Join-Path $EvidenceDirectory "engine-probe-$Flavour.json"
    $logPath = Join-Path $EvidenceDirectory "engine-probe-$Flavour.log"
    Remove-Item -LiteralPath $jsonPath -Force -ErrorAction SilentlyContinue

    $probeArguments = @(
        '--dll', $DllPath,
        '--media', $MediaPath,
        '--seconds', $Seconds.ToString([System.Globalization.CultureInfo]::InvariantCulture),
        '--json', $jsonPath,
        '--label', $Flavour
    )

    $usableDirectories = @($DependencyDirectories | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) })
    if ($usableDirectories.Count -gt 0) {
        $probeArguments += @('--deps', ($usableDirectories -join ';'))
    }

    Write-Host ''
    Write-Host "--- probe: $Flavour ---"
    Write-Host ('command: ' + $ProbeExe + ' ' + ($probeArguments -join ' '))

    $output = & $ProbeExe @probeArguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    $output | Set-Content -LiteralPath $logPath -Encoding utf8

    $report = $null
    if (Test-Path -LiteralPath $jsonPath) {
        $report = Get-Content -LiteralPath $jsonPath -Raw -Encoding utf8 | ConvertFrom-Json
    }

    return [pscustomobject]@{
        Flavour  = $Flavour
        ExitCode = $exitCode
        JsonPath = $jsonPath
        LogPath  = $logPath
        Report   = $report
    }
}

function Get-ReportValue {
    param($Report, [string]$Name, $Default = $null)
    if ($null -eq $Report) { return $Default }
    $property = $Report.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Format-Number {
    param($Value, [string]$Format = '0.000')
    if ($null -eq $Value) { return 'n/a' }
    return ([double]$Value).ToString($Format, [System.Globalization.CultureInfo]::InvariantCulture)
}

# ---------------------------------------------------------------- environment
Assert-That -Name 'environment/probe-exe' -Condition (Test-Path -LiteralPath $ProbeExe -PathType Leaf) -Detail $ProbeExe
Assert-That -Name 'environment/media' -Condition (Test-Path -LiteralPath $MediaPath -PathType Leaf) -Detail $MediaPath
Assert-That -Name 'environment/gpl-dll' -Condition (Test-Path -LiteralPath $GplDll -PathType Leaf) -Detail $GplDll
Assert-That -Name 'environment/lgpl-dll' -Condition (Test-Path -LiteralPath $LgplDll -PathType Leaf) -Detail $LgplDll

if (-not (Test-Path -LiteralPath $ProbeExe -PathType Leaf)) {
    Write-Host ('RESULT: ' + $script:Passed + ' passed, ' + $script:Failed + ' failed')
    Write-Host 'hint: build the probe first: dotnet build windows/src/MediaHub.EngineProbe/MediaHub.EngineProbe.csproj -c Debug -nologo'
    exit 1
}

# ---------------------------------------------------------------- run both builds
$gpl = Invoke-EngineProbe -Flavour 'gpl-baseline' -DllPath $GplDll
if ($gpl.ExitCode -eq 2 -and $LgplDependencies.Count -gt 0) {
    # A self-contained build should not need help; if it does, say so instead of hiding it.
    Write-Host 'gpl-baseline could not be loaded on its own; retrying with dependency directories'
    $gpl = Invoke-EngineProbe -Flavour 'gpl-baseline' -DllPath $GplDll -DependencyDirectories $LgplDependencies
}

$lgpl = Invoke-EngineProbe -Flavour 'lgpl-candidate' -DllPath $LgplDll -DependencyDirectories $LgplDependencies

# ---------------------------------------------------------------- identity
$gplFile = Get-Item -LiteralPath $GplDll
$lgplFile = Get-Item -LiteralPath $LgplDll
$gplSha = (Get-FileHash -LiteralPath $GplDll -Algorithm SHA256).Hash.ToLowerInvariant()
$lgplSha = (Get-FileHash -LiteralPath $LgplDll -Algorithm SHA256).Hash.ToLowerInvariant()

$gplIdentityDetail = 'sha256=' + $gplSha + ' bytes=' + $gplFile.Length + ' expected=' + $ExpectedGplSha256 + '/' + $ExpectedGplBytes
$lgplIdentityDetail = 'sha256=' + $lgplSha + ' bytes=' + $lgplFile.Length + ' expected=' + $ExpectedLgplSha256 + '/' + $ExpectedLgplBytes
Assert-That -Name 'identity/gpl-baseline' -Condition ($gplSha -eq $ExpectedGplSha256 -and $gplFile.Length -eq $ExpectedGplBytes) -Detail $gplIdentityDetail
Assert-That -Name 'identity/lgpl-candidate' -Condition ($lgplSha -eq $ExpectedLgplSha256 -and $lgplFile.Length -eq $ExpectedLgplBytes) -Detail $lgplIdentityDetail

$gplReport = $gpl.Report
$lgplReport = $lgpl.Report

# ---------------------------------------------------------------- load + client api
$gplLoadMode = Get-ReportValue $gplReport 'loadMode' 'n/a'
$lgplLoadMode = Get-ReportValue $lgplReport 'loadMode' 'n/a'
Assert-That -Name 'load/gpl-baseline' -Condition ($gpl.ExitCode -ne 2 -and $gplLoadMode -ne 'failed' -and $null -ne $gplReport) -Detail ('exit=' + $gpl.ExitCode + ' loadMode=' + $gplLoadMode)
Assert-That -Name 'load/lgpl-candidate' -Condition ($lgpl.ExitCode -ne 2 -and $lgplLoadMode -ne 'failed' -and $null -ne $lgplReport) -Detail ('exit=' + $lgpl.ExitCode + ' loadMode=' + $lgplLoadMode)

$gplApi = Get-ReportValue $gplReport 'clientApiText' 'n/a'
$lgplApi = Get-ReportValue $lgplReport 'clientApiText' 'n/a'
$gplApiMajor = Get-ReportValue $gplReport 'clientApiMajor' -1
$lgplApiMajor = Get-ReportValue $lgplReport 'clientApiMajor' -2
Assert-That -Name 'api/same-major' -Condition ($gplApiMajor -ge 0 -and $gplApiMajor -eq $lgplApiMajor) -Detail ('gpl=' + $gplApi + ' lgpl=' + $lgplApi)

# ---------------------------------------------------------------- per flavour evidence
$floorSeconds = $Seconds - 1
foreach ($entry in @($gpl, $lgpl)) {
    $flavour = $entry.Flavour
    $report = $entry.Report

    $fileLoaded = Get-ReportValue $report 'fileLoaded' $false
    Assert-That -Name ('decode/' + $flavour + '/file-loaded') -Condition ($fileLoaded -eq $true) -Detail ('fileLoaded=' + $fileLoaded)

    $maxPos = Get-ReportValue $report 'maxTimePos' $null
    $progressOk = ($null -ne $maxPos) -and ([double]$maxPos -ge [double]$floorSeconds)
    $progressDetail = 'maxTimePos=' + (Format-Number $maxPos) + 's floor=' + (Format-Number $floorSeconds) + 's target=' + (Format-Number $Seconds '0.#') + 's'
    Assert-That -Name ('decode/' + $flavour + '/progress') -Condition $progressOk -Detail $progressDetail

    $errorTotal = Get-ReportValue $report 'errorLogTotal' -1
    Assert-That -Name ('decode/' + $flavour + '/no-error-logs') -Condition ($errorTotal -eq 0) -Detail ('errorLogTotal=' + $errorTotal)

    $logCapture = Get-ReportValue $report 'logCaptureAvailable' $false
    Assert-That -Name ('decode/' + $flavour + '/log-capture') -Condition ($logCapture -eq $true) -Detail ('logCaptureAvailable=' + $logCapture)

    $width = [int](Get-ReportValue $report 'videoWidth' 0)
    $videoCodec = [string](Get-ReportValue $report 'videoCodec' '')
    $decodeDetail = 'videoCodec=' + $videoCodec + ' ' + $width + 'x' + (Get-ReportValue $report 'videoHeight' 0) + ' audioCodec=' + (Get-ReportValue $report 'audioCodec' 'n/a')
    Assert-That -Name ('decode/' + $flavour + '/decoded-video') -Condition ($width -gt 0 -and -not [string]::IsNullOrWhiteSpace($videoCodec)) -Detail $decodeDetail

    $verdict = Get-ReportValue $report 'verdict' 'n/a'
    $reason = Get-ReportValue $report 'reason' 'n/a'
    Assert-That -Name ('decode/' + $flavour + '/exit-code') -Condition ($entry.ExitCode -eq 0) -Detail ('exitCode=' + $entry.ExitCode + ' verdict=' + $verdict + ' reason=' + $reason)
}

# ---------------------------------------------------------------- licence features
$gplFeaturesSource = Get-ReportValue $gplReport 'featuresSource' 'none'
$lgplFeaturesSource = Get-ReportValue $lgplReport 'featuresSource' 'none'
$gplHasGpl = Get-ReportValue $gplReport 'featuresContainGpl' $null
$lgplHasGpl = Get-ReportValue $lgplReport 'featuresContainGpl' $null

Assert-That -Name 'features/source-stated' -Condition ($gplFeaturesSource -eq 'log' -and $lgplFeaturesSource -eq 'log') -Detail ('gpl source=' + $gplFeaturesSource + ' lgpl source=' + $lgplFeaturesSource)
Assert-That -Name 'licence/gpl-baseline-has-gpl' -Condition ($gplHasGpl -eq $true) -Detail ('featuresContainGpl=' + $gplHasGpl + ' from ' + $gplFeaturesSource)
Assert-That -Name 'licence/lgpl-candidate-lacks-gpl' -Condition ($lgplHasGpl -eq $false) -Detail ('featuresContainGpl=' + $lgplHasGpl + ' from ' + $lgplFeaturesSource)

# ---------------------------------------------------------------- summary
$summaryLines = @(
    'W13 headless engine probe summary',
    ('timestamp        : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')),
    ('media            : ' + $MediaPath),
    ('target seconds   : ' + (Format-Number $Seconds '0.#')),
    '',
    ('gpl-baseline     : sha256=' + $gplSha + ' bytes=' + $gplFile.Length),
    ('  loadMode       : ' + $gplLoadMode),
    ('  client api     : ' + $gplApi),
    ('  mpv version    : ' + (Get-ReportValue $gplReport 'mpvVersion' 'n/a')),
    ('  file loaded    : ' + (Get-ReportValue $gplReport 'fileLoaded' 'n/a')),
    ('  max time-pos   : ' + (Format-Number (Get-ReportValue $gplReport 'maxTimePos' $null)) + ' s'),
    ('  error logs     : ' + (Get-ReportValue $gplReport 'errorLogTotal' 'n/a') + ' / warn logs: ' + (Get-ReportValue $gplReport 'warnLogTotal' 'n/a')),
    ('  video          : ' + (Get-ReportValue $gplReport 'videoCodec' 'n/a') + ' ' + (Get-ReportValue $gplReport 'videoWidth' 0) + 'x' + (Get-ReportValue $gplReport 'videoHeight' 0)),
    ('  audio          : ' + (Get-ReportValue $gplReport 'audioCodec' 'n/a')),
    ('  features       : ' + ((Get-ReportValue $gplReport 'enabledFeatures' @()) -join ' ')),
    ('  gpl token      : ' + $gplHasGpl + ' (source: ' + $gplFeaturesSource + ')'),
    ('  verdict        : ' + (Get-ReportValue $gplReport 'verdict' 'n/a') + ' -- ' + (Get-ReportValue $gplReport 'reason' 'n/a')),
    '',
    ('lgpl-candidate   : sha256=' + $lgplSha + ' bytes=' + $lgplFile.Length),
    ('  loadMode       : ' + $lgplLoadMode),
    ('  client api     : ' + $lgplApi),
    ('  mpv version    : ' + (Get-ReportValue $lgplReport 'mpvVersion' 'n/a')),
    ('  file loaded    : ' + (Get-ReportValue $lgplReport 'fileLoaded' 'n/a')),
    ('  max time-pos   : ' + (Format-Number (Get-ReportValue $lgplReport 'maxTimePos' $null)) + ' s'),
    ('  error logs     : ' + (Get-ReportValue $lgplReport 'errorLogTotal' 'n/a') + ' / warn logs: ' + (Get-ReportValue $lgplReport 'warnLogTotal' 'n/a')),
    ('  video          : ' + (Get-ReportValue $lgplReport 'videoCodec' 'n/a') + ' ' + (Get-ReportValue $lgplReport 'videoWidth' 0) + 'x' + (Get-ReportValue $lgplReport 'videoHeight' 0)),
    ('  audio          : ' + (Get-ReportValue $lgplReport 'audioCodec' 'n/a')),
    ('  features       : ' + ((Get-ReportValue $lgplReport 'enabledFeatures' @()) -join ' ')),
    ('  gpl token      : ' + $lgplHasGpl + ' (source: ' + $lgplFeaturesSource + ')'),
    ('  verdict        : ' + (Get-ReportValue $lgplReport 'verdict' 'n/a') + ' -- ' + (Get-ReportValue $lgplReport 'reason' 'n/a')),
    ('  dependency dirs: ' + ($LgplDependencies -join '; ')),
    ''
)

Write-Host ''
Write-Host '================ measured numbers ================'
$summaryLines | ForEach-Object { Write-Host $_ }

$summaryPath = Join-Path $EvidenceDirectory 'engine-probe-summary.txt'
$summaryLines | Set-Content -LiteralPath $summaryPath -Encoding utf8
$script:Results | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'engine-probe-assertions.txt') -Encoding utf8

Write-Host ('evidence         : ' + $EvidenceDirectory)
Write-Host ('RESULT: ' + $script:Passed + ' passed, ' + $script:Failed + ' failed')

if ($script:Failed -gt 0) { exit 1 }
exit 0
