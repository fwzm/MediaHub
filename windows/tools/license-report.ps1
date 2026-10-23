#Requires -Version 7.0
<#
.SYNOPSIS
    Produces a licence inventory (Markdown + JSON), the third-party notices document and a
    CycloneDX SBOM for the Windows solution, from the NuGet restore graph that the build
    actually resolved.

.DESCRIPTION
    Data source, in this order:
      1. windows/src/*/*.csproj and windows/tests/*/*.csproj - the projects in scope.
      2. <project>/obj/project.assets.json - the packages restore actually resolved (including
         transitive ones), not the PackageReference list typed by hand.
      3. <NuGet cache>/<id>/<version>/<id>.nuspec - the license element the package author
         published (expression or file), or the deprecated licenseUrl.

    Nothing is inferred from a package name alone.

    Policy (default):
      * a package with neither a license element nor a licenseUrl is a violation;
      * a licence expression containing GPL-*, AGPL-*, SSPL-*, BUSL-* or Commons-Clause is a
        violation;
      * a package whose only licence metadata is the deprecated licenseUrl is a warning, and
        becomes a violation with -RequireExact (an SPDX expression is required);
      * LGPL-* is not GPL-*: matching happens on whole SPDX tokens, so LGPL-2.1 is not denied by
        the GPL rule.

    This is the statically verifiable part only. It does not build, does not publish, and says
    nothing about the GPL-only native engine binary (see windows/docs/LGPL-COMPLIANCE.md and
    windows/THIRD-PARTY-NOTICES.md).

.PARAMETER RepoRoot
    Repository root. Default: two levels above this script (windows/tools -> windows -> repo).

.PARAMETER OutDir
    Where the evidence artefacts (license-report.md, license-report.json) go.
    Default: <temp>/mh-license-report.

.PARAMETER Json
    Explicit path for the machine readable report. Default: <OutDir>/license-report.json.

.PARAMETER FailOnDeny
    Exit 3 when the policy is violated. Default true; disable with -FailOnDeny:$false.

.PARAMETER RequireExact
    Also require an SPDX expression: license files and licence-url-only packages become
    violations.

.PARAMETER Sbom
    Also (re)generate the CycloneDX 1.5 SBOM at -SbomPath.

.PARAMETER SbomPath
    Default: <repo>/windows/docs/sbom-windows.cdx.json

.PARAMETER NoticesPath
    Default: <repo>/windows/docs/THIRD-PARTY-NOTICES.md

.PARAMETER SkipDocs
    Do not write the two committed documents; write only the evidence artefacts.

.PARAMETER NuGetRoot
    NuGet global packages folder. Default: NUGET_PACKAGES, else %USERPROFILE%/.nuget/packages.

.PARAMETER TimestampUtc
    Freeze the "generated at" stamp (ISO-8601, e.g. 2026-10-23T00:00:00Z) so a re-run is
    byte-for-byte identical. Without it the current UTC time is used, which is the only field
    that changes between two runs.

.EXAMPLE
    pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir D:/mh-verify/license -Sbom

.NOTES
    Exit codes: 0 clean, 1 nothing to inspect (usage), 2 project.assets.json missing
    (run dotnet restore), 3 policy violation.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $OutDir,
    [string] $Json,
    [bool]   $FailOnDeny = $true,
    [switch] $RequireExact,
    [switch] $Sbom,
    [string] $SbomPath,
    [string] $NoticesPath,
    [string] $NuGetRoot,
    [switch] $SkipDocs,
    [string] $TimestampUtc
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$pathSeparator = [string][System.IO.Path]::DirectorySeparatorChar

# ---------------------------------------------------------------------------------------------
# Fixed facts about the native engine. These are not derived from NuGet: libmpv is a raw DLL that
# ships next to the executable. The numbers are restated from windows/tools/fetch-libmpv.ps1
# (lines 33-37 pin them) so the generated notices can never silently disagree with the script that
# installs the binary, and are cross-checked against the DLL on disk on every run.
# ---------------------------------------------------------------------------------------------
$NativeEngine = [ordered]@{
    Component     = 'libmpv (mpv)'
    Version       = 'v0.41.0-1154-gec38361c5'
    License       = 'GPL-3.0-or-later'
    Path          = 'windows/native/libmpv/libmpv-2.dll'
    Bytes         = 120989696
    Sha256        = '6a48faecb5ffe14a7be2f1fcf8b193bf3d51837cfb36e8d3527d82089de563d9'
    PinScript     = 'windows/tools/fetch-libmpv.ps1:33-37'
    Verdict       = 'windows/THIRD-PARTY-NOTICES.md:18 - GPL build; distributing it triggers GPL-3.0-or-later obligations'
    LgplCandidate = 'windows/engine/lgpl/dist/libmpv-2.dll - 3,327,274 B, sha256 20035902597d...ee4a3c0a - built and verified, NOT shipped (windows/docs/licensing/W5-LGPL-BUILD.md:18-22)'
    Compliance    = 'windows/docs/LGPL-COMPLIANCE.md'
}

function Write-Utf8 {
    param([string] $Path, [string] $Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Format-Argument {
    param([string] $Name, $Value)
    if ($Value -is [switch]) {
        if ($Value.IsPresent) { return " -$Name" }
        return " -$($Name):" + '$false'
    }
    if ($Value -is [bool]) {
        if ($Value) { return " -$Name" }
        return " -$($Name):" + '$false'
    }
    return " -$Name " + '"' + [string]$Value + '"'
}

# --- resolve roots ---------------------------------------------------------------------------
if (-not $RepoRoot) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
if (-not (Test-Path -LiteralPath $RepoRoot)) {
    Write-Error "repository root not found: $RepoRoot"
    exit 1
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

if (-not $OutDir) {
    $OutDir = Join-Path ([System.IO.Path]::GetTempPath()) 'mh-license-report'
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}
if (-not $Json) { $Json = Join-Path $OutDir 'license-report.json' }
if (-not $SbomPath) { $SbomPath = Join-Path $RepoRoot 'windows/docs/sbom-windows.cdx.json' }
if (-not $NoticesPath) { $NoticesPath = Join-Path $RepoRoot 'windows/docs/THIRD-PARTY-NOTICES.md' }
if (-not $NuGetRoot) {
    if ($env:NUGET_PACKAGES) { $NuGetRoot = $env:NUGET_PACKAGES }
    else { $NuGetRoot = [System.IO.Path]::Combine($env:USERPROFILE, '.nuget', 'packages') }
}

if ($TimestampUtc) {
    $stamp = [DateTimeOffset]::MinValue
    $parsedOk = [DateTimeOffset]::TryParse($TimestampUtc, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::AssumeUniversal, [ref] $stamp)
    if (-not $parsedOk) { throw "-TimestampUtc '$TimestampUtc' is not an ISO-8601 timestamp (e.g. 2026-10-23T00:00:00Z)" }
    $generatedAt = $stamp.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
} else {
    $generatedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
}
$dotnetVersion = 'unknown'
try { $dotnetVersion = (& dotnet --version).Trim() } catch { }
$pwshVersion = $PSVersionTable.PSVersion.ToString()
$gitHead = '(unknown)'
$gitBranch = '(unknown)'
$gitDescribe = '(unknown)'
try { $gitHead = (& git -C $RepoRoot rev-parse --short HEAD 2>$null).Trim() } catch { }
try { $gitBranch = (& git -C $RepoRoot rev-parse --abbrev-ref HEAD 2>$null).Trim() } catch { }
try { $gitDescribe = (& git -C $RepoRoot describe --tags --always --dirty 2>$null).Trim() } catch { }
$gitDescribeClean = $gitDescribe -replace '-dirty$', ''

$commandLine = 'pwsh -NoProfile -File windows/tools/license-report.ps1'
foreach ($key in ($PSBoundParameters.Keys | Sort-Object)) {
    $commandLine += (Format-Argument -Name $key -Value $PSBoundParameters[$key])
}

$warnings = [System.Collections.Generic.List[string]]::new()
$violations = [System.Collections.Generic.List[object]]::new()

# --- discover projects -----------------------------------------------------------------------
$projectFiles = @()
foreach ($branch in @('src', 'tests')) {
    $dir = Join-Path $RepoRoot (Join-Path 'windows' $branch)
    if (Test-Path -LiteralPath $dir) {
        $projectFiles += @(Get-ChildItem -LiteralPath $dir -Filter *.csproj -File -Recurse -Depth 1)
    }
}
$projectFiles = @($projectFiles | Sort-Object FullName)
if ($projectFiles.Count -eq 0) {
    Write-Error "no .csproj under windows/src or windows/tests (repo root: $RepoRoot)"
    exit 1
}

# --- read the resolved package graph ---------------------------------------------------------
$projects = [System.Collections.Generic.List[object]]::new()
$rows = [System.Collections.Generic.List[object]]::new()
$assetsMissing = [System.Collections.Generic.List[string]]::new()

foreach ($projectFile in $projectFiles) {
    $projectName = $projectFile.BaseName
    $relativeProject = ([System.IO.Path]::GetRelativePath($RepoRoot, $projectFile.FullName)) -replace '\\', '/'
    $assetsPath = Join-Path $projectFile.DirectoryName 'obj/project.assets.json'
    if (-not (Test-Path -LiteralPath $assetsPath)) {
        $assetsMissing.Add($projectName)
        $warnings.Add("${projectName}: obj/project.assets.json is missing - run: dotnet restore windows/MediaHub.Windows.sln")
        $projects.Add([pscustomobject]@{ name = $projectName; path = $relativeProject; assets = 'missing'; state = 'missing-assets'; packages = 0 })
        continue
    }
    $assetsInfo = Get-Item -LiteralPath $assetsPath
    if ($assetsInfo.LastWriteTimeUtc -lt $projectFile.LastWriteTimeUtc) {
        $warnings.Add("${projectName}: project.assets.json is older than the .csproj - the resolved graph may be stale (dotnet restore windows/MediaHub.Windows.sln)")
    }
    $document = Get-Content -LiteralPath $assetsPath -Raw | ConvertFrom-Json -AsHashtable
    if (-not $document.Contains('libraries')) {
        $warnings.Add("${projectName}: project.assets.json has no libraries section - run: dotnet restore windows/MediaHub.Windows.sln")
        $projects.Add([pscustomobject]@{ name = $projectName; path = $relativeProject; assets = 'empty'; state = 'empty-graph'; packages = 0 })
        continue
    }
    $count = 0
    $libraries = $document['libraries']
    foreach ($key in ($libraries.Keys | Sort-Object)) {
        $library = $libraries[$key]
        if (-not $library.Contains('type')) { continue }
        if ($library['type'] -ne 'package') { continue }
        $separator = $key.IndexOf('/')
        if ($separator -lt 1) { continue }
        $id = $key.Substring(0, $separator)
        $version = $key.Substring($separator + 1)
        $rows.Add([pscustomobject]@{ project = $projectName; id = $id; version = $version })
        $count++
    }
    $projects.Add([pscustomobject]@{ name = $projectName; path = $relativeProject; assets = 'ok'; state = 'ok'; packages = $count })
}

# --- licence metadata helpers ----------------------------------------------------------------
function Get-LicenseFileGuess {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $text = ''
    try { $text = Get-Content -LiteralPath $Path -Raw } catch { return '' }
    if (-not $text) { return '' }
    if ($text.Length -gt 6000) { $text = $text.Substring(0, 6000) }
    if ($text -match 'MICROSOFT SOFTWARE LICENSE TERMS') { return 'Microsoft (proprietary eula, see file)' }
    if ($text -match 'Apache License') { return 'Apache-2.0' }
    if ($text -match 'MIT License') { return 'MIT' }
    if ($text -match 'Permission is hereby granted, free of charge') { return 'MIT' }
    if ($text -match 'BSD 3-Clause') { return 'BSD-3-Clause' }
    if ($text -match 'Redistribution and use in source and binary forms') { return 'BSD-3-Clause' }
    if ($text -match 'Microsoft') { return 'Microsoft (proprietary eula, see file)' }
    return ''
}

function Get-LicenseUrlGuess {
    param([string] $Url)
    if (-not $Url) { return '' }
    $lower = $Url.ToLowerInvariant()
    if ($lower -match 'licenses.nuget.org/mit') { return 'MIT' }
    if ($lower -match 'opensource.org/licenses/mit') { return 'MIT' }
    if ($lower -match 'apache.org/licenses/license-2.0') { return 'Apache-2.0' }
    if ($lower -match 'microsoft.com') { return 'Microsoft (proprietary eula)' }
    return ''
}

function Get-LicenseTokens {
    param([string] $Expression)
    if (-not $Expression) { return @() }
    $clean = $Expression -replace '[()]', ' '
    $tokens = @()
    foreach ($part in ($clean -split '\\s+')) {
        $token = $part.Trim().ToUpperInvariant()
        if (-not $token) { continue }
        if ($token -in @('AND', 'OR', 'WITH')) { continue }
        $tokens += $token
    }
    return $tokens
}

function Get-DeniedTokens {
    param([string[]] $Tokens)
    $hits = @()
    foreach ($token in @($Tokens)) {
        if ($token.StartsWith('LICENSEREF-', [System.StringComparison]::Ordinal)) { $hits += $token; continue }
        foreach ($pattern in @('GPL-', 'AGPL-', 'SSPL-', 'BUSL-', 'COMMONS-CLAUSE')) {
            if ($token.StartsWith($pattern, [System.StringComparison]::Ordinal)) { $hits += $token; break }
        }
    }
    return $hits
}

$licenseCache = @{}

function Resolve-PackageLicense {
    param([string] $Id, [string] $Version)

    $cacheKey = "$Id/$Version"
    if ($licenseCache.ContainsKey($cacheKey)) { return $licenseCache[$cacheKey] }

    $result = [ordered]@{
        id            = $Id
        version       = $Version
        purl          = "pkg:nuget/$Id@$Version"
        license       = ''
        licenseSource = ''
        licenseFile   = ''
        licenseUrl    = ''
        exact         = $false
        status        = 'missing'
        detail        = ''
        nuspecRel     = ''
        licenseRel    = ''
        tokens        = @()
        denied        = @()
    }

    $packageDir = Join-Path (Join-Path $NuGetRoot $Id.ToLowerInvariant()) $Version
    if (-not (Test-Path -LiteralPath $packageDir)) {
        $result.status = 'missing-package'
        $result.detail = "package folder not found in the NuGet cache: $packageDir"
        $licenseCache[$cacheKey] = [pscustomobject]$result
        return $licenseCache[$cacheKey]
    }

    $nuspec = Join-Path $packageDir ($Id.ToLowerInvariant() + '.nuspec')
    if (-not (Test-Path -LiteralPath $nuspec)) {
        $candidate = @(Get-ChildItem -LiteralPath $packageDir -Filter *.nuspec -File | Select-Object -First 1)
        if ($candidate.Count -gt 0) { $nuspec = $candidate[0].FullName }
    }
    if (-not (Test-Path -LiteralPath $nuspec)) {
        $result.status = 'missing-nuspec'
        $result.detail = "no .nuspec in $packageDir"
        $licenseCache[$cacheKey] = [pscustomobject]$result
        return $licenseCache[$cacheKey]
    }
    $result.nuspecRel = ($Id.ToLowerInvariant() + '/' + $Version + '/' + (Split-Path -Leaf $nuspec))

    [xml] $spec = Get-Content -LiteralPath $nuspec -Raw
    $metadata = $spec.SelectSingleNode("/*[local-name()='package']/*[local-name()='metadata']")
    if (-not $metadata) {
        $result.status = 'missing-nuspec'
        $result.detail = 'nuspec has no /package/metadata element'
        $licenseCache[$cacheKey] = [pscustomobject]$result
        return $licenseCache[$cacheKey]
    }

    $licenseUrlNode = $metadata.SelectSingleNode("*[local-name()='licenseUrl']")
    if ($licenseUrlNode) { $result.licenseUrl = $licenseUrlNode.InnerText.Trim() }

    $licenseElement = $metadata.SelectSingleNode("*[local-name()='license']")
    if ($licenseElement) {
        $type = $licenseElement.GetAttribute('type')
        $value = $licenseElement.InnerText.Trim()
        if ($type -eq 'expression' -and $value) {
            $result.license = $value
            $result.licenseSource = 'expression'
            $result.status = 'expression'
            $result.exact = $true
        }
        elseif ($type -eq 'file' -and $value) {
            $relative = $value -replace '\\', '/'
            $licensePath = Join-Path $packageDir $relative
            $guess = Get-LicenseFileGuess -Path $licensePath
            $result.licenseFile = $relative
            $result.licenseRel = ($Id.ToLowerInvariant() + '/' + $Version + '/' + $relative)
            $result.licenseSource = 'file'
            $result.status = 'license-file'
            if ($guess) { $result.license = "~$guess (from $relative)" }
            else { $result.license = "see $relative" }
        }
        else {
            $result.status = 'missing'
            $result.detail = "unusable license element (type=" + $type + ", value=" + $value + ")"
        }
    }

    if ($result.status -eq 'missing' -and $result.licenseUrl) {
        $guess = Get-LicenseUrlGuess -Url $result.licenseUrl
        $result.license = $result.licenseUrl
        $result.licenseSource = 'licenseUrl'
        $result.status = 'license-url'
        if ($guess) { $result.detail = "deprecated licenseUrl only; hint: $guess" }
        else { $result.detail = 'deprecated licenseUrl only' }
    }

    if ($result.status -eq 'expression') {
        $result.tokens = @(Get-LicenseTokens -Expression $result.license)
        $result.denied = @(Get-DeniedTokens -Tokens $result.tokens)
    }

    $licenseCache[$cacheKey] = [pscustomobject]$result
    return $licenseCache[$cacheKey]
}

# --- assemble the package list ---------------------------------------------------------------
$packages = [System.Collections.Generic.List[object]]::new()
$byId = @{}
foreach ($row in $rows) {
    $key = "$($row.id)|$($row.version)"
    if (-not $byId.ContainsKey($key)) {
        $info = Resolve-PackageLicense -Id $row.id -Version $row.version
        $byId[$key] = $info
        $packages.Add($info)
    }
}
foreach ($package in $packages) {
    Add-Member -InputObject $package -NotePropertyName usedBy -NotePropertyValue ([System.Collections.Generic.List[string]]::new()) -Force
}
foreach ($row in $rows) {
    $info = $byId["$($row.id)|$($row.version)"]
    $usedBy = [System.Collections.Generic.List[string]]$info.usedBy
    if (-not $usedBy.Contains($row.project)) { $usedBy.Add($row.project) }
}

# --- policy ----------------------------------------------------------------------------------
$buildOnlyIds = @(
    'Microsoft.NET.Test.Sdk', 'Microsoft.CodeCoverage', 'Microsoft.TestPlatform.ObjectModel',
    'Microsoft.TestPlatform.TestHost', 'Microsoft.Windows.SDK.BuildTools',
    'Microsoft.Windows.SDK.BuildTools.MSIX', 'xunit', 'xunit.abstractions', 'xunit.analyzers',
    'xunit.assert', 'xunit.core', 'xunit.extensibility.core', 'xunit.extensibility.execution',
    'xunit.runner.visualstudio'
)

foreach ($package in ($packages | Sort-Object id)) {
    $reason = ''
    if (@($package.denied).Count -gt 0) {
        $reason = 'denied-license (' + (@($package.denied) -join ', ') + ')'
    }
    elseif ($package.status -in @('missing', 'missing-nuspec', 'missing-package')) {
        $reason = 'missing-license-metadata'
    }
    elseif ($package.status -eq 'expression' -and @($package.tokens).Count -eq 0) {
        $reason = 'unrecognised-license'
    }
    elseif ($RequireExact -and ($package.status -in @('license-file', 'license-url'))) {
        $reason = 'spdx-expression-required (only ' + $package.status + ')'
    }

    if ($reason) {
        $violations.Add([pscustomobject]@{ package = $package.id; version = $package.version; reason = $reason; license = $package.license; source = $package.nuspecRel })
    }
    elseif ($package.status -eq 'license-url') {
        $warnings.Add("$($package.id)/$($package.version): only the deprecated licenseUrl is published - no SPDX expression (a violation under -RequireExact)")
    }
}

# --- native engine facts ---------------------------------------------------------------------
$nativePath = Join-Path $RepoRoot ($NativeEngine.Path -replace '/', $pathSeparator)
$nativeObserved = [ordered]@{ present = $false; bytes = 0; sha256 = ''; matchesPin = $false }
if (Test-Path -LiteralPath $nativePath) {
    $nativeObserved.present = $true
    $nativeObserved.bytes = (Get-Item -LiteralPath $nativePath).Length
    try { $nativeObserved.sha256 = (Get-FileHash -LiteralPath $nativePath -Algorithm SHA256).Hash.ToLowerInvariant() } catch { }
    $nativeObserved.matchesPin = ($nativeObserved.sha256 -eq $NativeEngine.Sha256)
    if (-not $nativeObserved.matchesPin) {
        $warnings.Add("$($NativeEngine.Path): sha256 does not match the pin in $($NativeEngine.PinScript) - the installed engine is NOT the reviewed binary")
    }
}
else {
    $warnings.Add("$($NativeEngine.Path): not present on this machine (fetch it with windows/tools/fetch-libmpv.ps1)")
}

# --- summary ---------------------------------------------------------------------------------
$expressionCount = 0
$fileCount = 0
$urlCount = 0
$missingCount = 0
foreach ($package in $packages) {
    if ($package.status -eq 'expression') { $expressionCount++ }
    elseif ($package.status -eq 'license-file') { $fileCount++ }
    elseif ($package.status -eq 'license-url') { $urlCount++ }
    else { $missingCount++ }
}

$summary = [ordered]@{
    projects        = $projects.Count
    projectsMissing = $assetsMissing.Count
    rows            = $rows.Count
    packages        = $packages.Count
    expression      = $expressionCount
    licenseFile     = $fileCount
    licenseUrl      = $urlCount
    missing         = $missingCount
    violations      = $violations.Count
    warnings        = $warnings.Count
    requireExact    = [bool]$RequireExact
    failOnDeny      = [bool]$FailOnDeny
}

# --- JSON report -----------------------------------------------------------------------------
$jsonReport = [ordered]@{
    schemaVersion  = 1
    generatedAtUtc = $generatedAt
    generatedBy    = 'windows/tools/license-report.ps1'
    commandLine    = $commandLine
    tooling        = [ordered]@{ dotnet = $dotnetVersion; pwsh = $pwshVersion }
    repository     = [ordered]@{ root = $RepoRoot; branch = $gitBranch; head = $gitHead; describe = $gitDescribe }
    policy         = [ordered]@{ failOnDeny = [bool]$FailOnDeny; requireExact = [bool]$RequireExact }
    summary        = $summary
    projects       = @($projects | ForEach-Object { [ordered]@{ name = $_.name; path = $_.path; state = $_.state; packages = $_.packages } })
    packages       = @($packages | Sort-Object id | ForEach-Object {
        [ordered]@{
            id            = $_.id
            version       = $_.version
            purl          = $_.purl
            license       = $_.license
            licenseSource = $_.licenseSource
            licenseFile   = $_.licenseFile
            licenseUrl    = $_.licenseUrl
            status        = $_.status
            exact         = $_.exact
            detail        = $_.detail
            nuspec        = $_.nuspecRel
            tokens        = @($_.tokens)
            denied        = @($_.denied)
            usedBy        = @($_.usedBy | Sort-Object)
        }
    })
    violations     = @($violations | ForEach-Object { [ordered]@{ package = $_.package; version = $_.version; reason = $_.reason; license = $_.license; source = $_.source } })
    warnings       = @($warnings)
    nativeEngine   = [ordered]@{
        component    = $NativeEngine.Component
        version      = $NativeEngine.Version
        license      = $NativeEngine.License
        path         = $NativeEngine.Path
        pinnedSha256 = $NativeEngine.Sha256
        observed     = $nativeObserved
        note         = 'GPL-only engine: NOT covered by this report policy; see windows/docs/LGPL-COMPLIANCE.md'
    }
}

Write-Utf8 -Path $Json -Text (($jsonReport | ConvertTo-Json -Depth 12) + [Environment]::NewLine)

# --- console ---------------------------------------------------------------------------------
Write-Output "license-report  repo=$RepoRoot  branch=$gitBranch  head=$gitHead  describe=$gitDescribe"
Write-Output "license-report  dotnet=$dotnetVersion  pwsh=$pwshVersion  generated=$generatedAt"
Write-Output "license-report  projects=$($summary.projects)  rows=$($summary.rows)  packages=$($summary.packages)"
Write-Output "license-report  licence sources: expression=$($summary.expression) file=$($summary.licenseFile) licenseUrl=$($summary.licenseUrl) missing=$($summary.missing)"
Write-Output "license-report  native-engine: $($NativeEngine.License) ($($NativeEngine.Path), observed=$($nativeObserved.bytes) B, matchesPin=$($nativeObserved.matchesPin)) - NOT covered by this check"
foreach ($warning in $warnings) { Write-Output "license-report  WARN  $warning" }
foreach ($violation in $violations) { Write-Output "license-report  DENY  $($violation.package)/$($violation.version): $($violation.reason) [$($violation.license)]" }
Write-Output "license-report  violations=$($violations.Count)  warnings=$($warnings.Count)"

# --- Markdown evidence report ----------------------------------------------------------------
$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add('# MediaHub Windows - NuGet licence report (generated)')
$markdown.Add('')
$markdown.Add('> Generated by `windows/tools/license-report.ps1`. Do not edit by hand.')
$markdown.Add('')
$markdown.Add('| field | value |')
$markdown.Add('| --- | --- |')
$markdown.Add("| generated (UTC) | $generatedAt |")
$markdown.Add("| command | $commandLine |")
$markdown.Add("| dotnet SDK | $dotnetVersion |")
$markdown.Add("| PowerShell | $pwshVersion |")
$markdown.Add("| repository | $RepoRoot |")
$markdown.Add("| branch / HEAD | $gitBranch / $gitHead |")
$markdown.Add("| git describe | $gitDescribe |")
$markdown.Add("| policy | failOnDeny=$($summary.failOnDeny), requireExact=$($summary.requireExact) |")
$markdown.Add('')
$markdown.Add('## 1. Summary')
$markdown.Add('')
$markdown.Add('| metric | value |')
$markdown.Add('| --- | --- |')
$markdown.Add("| projects inspected | $($summary.projects) (missing assets: $($summary.projectsMissing)) |")
$markdown.Add("| project-package rows | $($summary.rows) |")
$markdown.Add("| unique packages | $($summary.packages) |")
$markdown.Add("| licence from SPDX expression | $($summary.expression) |")
$markdown.Add("| licence from package file | $($summary.licenseFile) |")
$markdown.Add("| deprecated licenseUrl only | $($summary.licenseUrl) |")
$markdown.Add("| no licence metadata | $($summary.missing) |")
$markdown.Add("| policy violations | $($summary.violations) |")
$markdown.Add("| warnings | $($summary.warnings) |")
$markdown.Add('')
$markdown.Add('## 2. Resolved packages, per project')
$markdown.Add('')
$markdown.Add('| project | package | version | licence | licence source | nuspec / licence file (under the NuGet cache) |')
$markdown.Add('| --- | --- | --- | --- | --- | --- |')
foreach ($row in ($rows | Sort-Object project, id)) {
    $info = $byId["$($row.id)|$($row.version)"]
    $sourceFile = $info.nuspecRel
    if ($info.licenseRel) { $sourceFile = $info.licenseRel }
    $markdown.Add("| $($row.project) | $($row.id) | $($row.version) | $($info.license) | $($info.licenseSource) | $sourceFile |")
}
$markdown.Add('')
$markdown.Add('## 3. Unique packages')
$markdown.Add('')
$markdown.Add('| package | version | licence | source | status | exact | used by |')
$markdown.Add('| --- | --- | --- | --- | --- | --- | --- |')
foreach ($package in ($packages | Sort-Object id)) {
    $markdown.Add("| $($package.id) | $($package.version) | $($package.license) | $($package.licenseSource) | $($package.status) | $($package.exact) | $(@($package.usedBy | Sort-Object) -join ', ') |")
}
$markdown.Add('')
$markdown.Add('## 4. Policy verdict')
$markdown.Add('')
if ($violations.Count -eq 0) {
    $markdown.Add('No violations under the selected policy.')
}
else {
    $markdown.Add('| package | version | reason | licence | source |')
    $markdown.Add('| --- | --- | --- | --- | --- |')
    foreach ($violation in $violations) {
        $markdown.Add("| $($violation.package) | $($violation.version) | $($violation.reason) | $($violation.license) | $($violation.source) |")
    }
}
if ($warnings.Count -gt 0) {
    $markdown.Add('')
    $markdown.Add('### Warnings')
    $markdown.Add('')
    foreach ($warning in $warnings) { $markdown.Add("- $warning") }
}
$markdown.Add('')
$markdown.Add('## 5. Native engine (not a NuGet package, not covered above)')
$markdown.Add('')
$markdown.Add("$($NativeEngine.Component) $($NativeEngine.Version) ships as $($NativeEngine.Path) and is licensed **$($NativeEngine.License)**.")
$markdown.Add("Pinned sha256 $($NativeEngine.Sha256) ($($NativeEngine.PinScript)); observed on this machine: $($nativeObserved.sha256) (match: $($nativeObserved.matchesPin)).")
$markdown.Add("This is a release blocker, documented at $($NativeEngine.Verdict). LGPL candidate build (not shipped): $($NativeEngine.LgplCandidate).")
$markdown.Add("Compliance checklist for the LGPL path: $($NativeEngine.Compliance).")
$reportPath = Join-Path $OutDir 'license-report.md'
Write-Utf8 -Path $reportPath -Text (($markdown -join [Environment]::NewLine) + [Environment]::NewLine)

# --- third-party notices document ------------------------------------------------------------
if (-not $SkipDocs) {
    $notices = [System.Collections.Generic.List[string]]::new()
    $notices.Add('# MediaHub Windows - 第三方组件与许可证清单')
    $notices.Add('')
    $notices.Add('> **本文件由脚本生成，请勿手工编辑。** 生成器：`windows/tools/license-report.ps1`。')
    $notices.Add('> 重新生成：`pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Sbom`')
    $notices.Add('')
    $notices.Add('| 生成信息 | 值 |')
    $notices.Add('| --- | --- |')
    $notices.Add("| 生成时间（UTC） | $generatedAt |")
    $notices.Add("| 生成命令 | $commandLine |")
    $notices.Add("| dotnet SDK / PowerShell | $dotnetVersion / $pwshVersion |")
    $notices.Add("| 仓库 | $RepoRoot |")
    $notices.Add("| 分支 / HEAD | $gitBranch / $gitHead |")
    $notices.Add("| git describe | $gitDescribe |")
    $notices.Add("| 数据来源 | $($summary.projects) 个 csproj 的 obj/project.assets.json + 本机 NuGet 缓存中的 .nuspec |")
    $notices.Add('')
    $notices.Add('## 0. 覆盖范围与边界')
    $notices.Add('')
    $notices.Add('- 覆盖：Windows 解决方案（`windows/MediaHub.Windows.sln`）**实际解析到的 NuGet 依赖图**，含传递依赖；每个包的许可取自包自身发布的 `.nuspec`（`<license type="expression">` / `<license type="file">` / 已弃用的 `<licenseUrl>`）。')
    $notices.Add('- 不覆盖：①发布产物中的完整文件清单（见 `windows/docs/licensing/SBOM.md`）；②随包分发的**非 NuGet 原生二进制**（见 §3）；③MSIX/安装器自带的运行库；④任何法律意见。')
    $notices.Add('- 因此本文件只满足「可静态验证」的部分，**不构成发布就绪结论**，也不解除 §3 的 GPL 阻塞。')
    $notices.Add('')
    $notices.Add('## 1. 运行时 / 随包分发的 NuGet 依赖')
    $notices.Add('')
    $notices.Add('| 包 | 版本 | 许可 | 许可来源 | 使用的项目 |')
    $notices.Add('| --- | --- | --- | --- | --- |')
    foreach ($package in ($packages | Where-Object { $buildOnlyIds -notcontains $_.id } | Sort-Object id)) {
        $notices.Add("| $($package.id) | $($package.version) | $($package.license) | $($package.licenseSource) | $(@($package.usedBy | Sort-Object) -join ', ') |")
    }
    $notices.Add('')
    $notices.Add('## 2. 仅构建期 / 测试期使用的 NuGet 依赖')
    $notices.Add('')
    $notices.Add('这些包不进入应用安装包，但仍在仓库的构建与测试链里使用，因此一并记录。')
    $notices.Add('')
    $notices.Add('| 包 | 版本 | 许可 | 许可来源 | 使用的项目 |')
    $notices.Add('| --- | --- | --- | --- | --- |')
    foreach ($package in ($packages | Where-Object { $buildOnlyIds -contains $_.id } | Sort-Object id)) {
        $notices.Add("| $($package.id) | $($package.version) | $($package.license) | $($package.licenseSource) | $(@($package.usedBy | Sort-Object) -join ', ') |")
    }
    $notices.Add('')
    $notices.Add('## 3. 非 NuGet 二进制（随包分发）')
    $notices.Add('')
    $notices.Add('这些组件不由 NuGet 解析，上面的表格**不覆盖**它们。')
    $notices.Add('')
    $notices.Add('| 组件 | 版本 | 许可 | 随包方式 | 事实来源 |')
    $notices.Add('| --- | --- | --- | --- | --- |')
    $notices.Add("| $($NativeEngine.Component) | $($NativeEngine.Version) | **$($NativeEngine.License)** | $($NativeEngine.Path)（$('{0:N0}' -f $NativeEngine.Bytes) 字节，安装时强制校验 sha256） | $($NativeEngine.PinScript) |")
    $notices.Add('| FFmpeg (libav*) | N-127521-g2ec8998c3 | **GPL-3.0-or-later**（随该 libmpv 构建） | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:11` |')
    $notices.Add('| rubberband / libdvdnav / libdvdread | 随 libmpv 构建 | **GPL-2.0** | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:12` |')
    $notices.Add('| libplacebo / libass / libcurl 等 | 随 libmpv 构建 | LGPL-2.1-or-later / ISC 等 | 静态链接进 libmpv-2.dll | `windows/THIRD-PARTY-NOTICES.md:13`；逐组件清单见 `windows/engine/lgpl/component-licences.txt` |')
    $notices.Add('| .NET 10 运行时（自包含发布时） | 10.0 | MIT | 随发布产物 | `windows/THIRD-PARTY-NOTICES.md:7` |')
    $notices.Add('')
    $notices.Add('### 3.1 libmpv 的版本来源')
    $notices.Add('')
    $notices.Add('- 具体版本：**v0.41.0-1154-gec38361c5**（内嵌 FFmpeg N-127521-g2ec8998c3），来自 shinchiro/mpv-winbuild-cmake 的 release `20261014`，资产 `mpv-dev-x86_64-20261014-git-ec38361c56.7z`。')
    $notices.Add('- 获取方式：`windows/tools/fetch-libmpv.ps1` 下载并按两个固定 SHA-256（归档 + 解包后的 DLL）校验后才安装；DLL 不入库（`.gitignore:35-36`）。')
    $notices.Add("- 本次运行实测：文件存在=$($nativeObserved.present)，字节=$($nativeObserved.bytes)，sha256=$($nativeObserved.sha256)，与固定值一致=$($nativeObserved.matchesPin)。")
    $notices.Add('')
    $notices.Add('### 3.2 许可结论（与既有记录一致，未改变）')
    $notices.Add('')
    $notices.Add('- 当前 `windows/native/libmpv/libmpv-2.dll` 是 **GPL 构建**（`gpl` 出现在引擎自报的 enabled-features 里），按 GPL-3.0-or-later 分发；以当前产物对外发布即触发 GPL 义务。详见 `windows/THIRD-PARTY-NOTICES.md:16-63`。')
    $notices.Add("- LGPL 候选构建已存在但**尚未切换**：$($NativeEngine.LgplCandidate)。")
    $notices.Add('- 切换到 LGPL 构建后必须履行的义务、可替换 DLL 的要求与分发前 checklist：`windows/docs/LGPL-COMPLIANCE.md`。')
    $notices.Add('')
    $notices.Add('## 4. 仓库自身的许可')
    $notices.Add('')
    $notices.Add('- 仓库根**没有 LICENSE 文件**：`windows/docs/licensing/LICENCE-DECISION.md:180-190` 记录了这一点（`git ls-files` 无匹配，`git log -- LICENSE` 无输出）。')
    $notices.Add('- `windows/THIRD-PARTY-NOTICES.md:63` 与 LICENCE-DECISION.md:186 引用的 ADR-W001:40 声明「本仓库为 MIT」，但没有任何文件承载该声明。')
    $notices.Add('- 本脚本不能修复这一点；正式发布前必须由人工补上 LICENSE 并与之对齐。')
    $notices.Add('')
    $notices.Add('## 5. 本次运行的策略判定')
    $notices.Add('')
    $notices.Add('策略：无许可元数据、或出现 GPL-/AGPL-/SSPL-/BUSL-/Commons-Clause 许可 → 违规；仅 `<licenseUrl>` 视为警告，`-RequireExact` 时升级为违规（要求 SPDX 表达式）。')
    $notices.Add('')
    if ($violations.Count -eq 0) {
        $notices.Add('- 违规：**0**。')
    }
    else {
        $notices.Add('- 违规：**' + $violations.Count + '**')
        foreach ($violation in $violations) { $notices.Add("  - $($violation.package)/$($violation.version)：$($violation.reason)（$($violation.license)）") }
    }
    if ($warnings.Count -eq 0) {
        $notices.Add('- 警告：**0**。')
    }
    else {
        $notices.Add('- 警告：**' + $warnings.Count + '**')
        foreach ($warning in $warnings) { $notices.Add("  - $warning") }
    }
    $notices.Add('')
    $notices.Add('## 6. 复现方式')
    $notices.Add('')
    $notices.Add('```powershell')
    $notices.Add('dotnet restore windows/MediaHub.Windows.sln')
    $notices.Add('pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <任意目录> -Sbom')
    $notices.Add('```')
    $notices.Add('')
    $notices.Add('脚本退出码：0 干净 / 1 未找到可检查的工程 / 2 缺 `project.assets.json`（先 restore）/ 3 策略违规。')
    $notices.Add('')
    $notices.Add('相关文件：`windows/docs/sbom-windows.cdx.json`（CycloneDX 1.5）、`windows/docs/LGPL-COMPLIANCE.md`、`windows/THIRD-PARTY-NOTICES.md`（历史手工清单：引擎 GPL 判定与固定摘要）、`windows/docs/licensing/`（W3/W5 记录）。')
    Write-Utf8 -Path $NoticesPath -Text (($notices -join [Environment]::NewLine) + [Environment]::NewLine)
}

# --- SBOM ------------------------------------------------------------------------------------
if ($Sbom) {
    $components = [System.Collections.Generic.List[object]]::new()
    foreach ($package in ($packages | Sort-Object id)) {
        $component = [ordered]@{ type = 'library'; 'bom-ref' = $package.purl; name = $package.id; version = $package.version; purl = $package.purl }
        if ($package.status -eq 'expression' -and $package.license) {
            $component.licenses = @([ordered]@{ expression = $package.license })
        }
        elseif ($package.license) {
            $component.licenses = @([ordered]@{ license = [ordered]@{ name = $package.license } })
        }
        else {
            $component.licenses = @([ordered]@{ license = [ordered]@{ name = 'NOASSERTION' } })
        }
        $components.Add($component)
    }
    $enginePurl = 'pkg:generic/libmpv@0.41.0-1154-gec38361c5'
    $engineComponent = [ordered]@{
        type      = 'library'
        'bom-ref' = $enginePurl
        name      = 'libmpv'
        version   = '0.41.0-1154-gec38361c5'
        purl      = $enginePurl
        licenses  = @([ordered]@{ expression = 'GPL-3.0-or-later' })
    }
    if ($nativeObserved.sha256) {
        $engineComponent.hashes = @([ordered]@{ alg = 'SHA-256'; content = $nativeObserved.sha256 })
    }
    $components.Add($engineComponent)

    $purls = @($components | ForEach-Object { $_.'bom-ref' } | Sort-Object)
    $hash = [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes(($purls -join '|')))
    $hex = -join ($hash[0..15] | ForEach-Object { $_.ToString('x2') })
    $serial = 'urn:uuid:' + $hex.Substring(0, 8) + '-' + $hex.Substring(8, 4) + '-' + $hex.Substring(12, 4) + '-' + $hex.Substring(16, 4) + '-' + $hex.Substring(20, 12)

    $bom = [ordered]@{
        bomFormat    = 'CycloneDX'
        specVersion  = '1.5'
        serialNumber = $serial
        version      = 1
        metadata     = [ordered]@{
            timestamp = $generatedAt
            tools     = @([ordered]@{ vendor = 'MediaHub'; name = 'windows/tools/license-report.ps1'; version = '1' })
            component = [ordered]@{
                type      = 'application'
                'bom-ref' = 'pkg:generic/MediaHub.Windows@' + $gitDescribeClean
                name      = 'MediaHub.Windows'
                version   = $gitDescribeClean
            }
            properties = @(
                [ordered]@{ name = 'mediahub:source-revision'; value = "$gitBranch@$gitHead" },
                [ordered]@{ name = 'mediahub:git-describe'; value = $gitDescribe },
                [ordered]@{ name = 'mediahub:own-license'; value = 'undocumented - no LICENSE file in the repository (windows/docs/licensing/LICENCE-DECISION.md:180-190)' },
                [ordered]@{ name = 'mediahub:engine-license-blocker'; value = 'libmpv-2.dll is a GPL-3.0-or-later build (windows/THIRD-PARTY-NOTICES.md:18); the LGPL candidate exists but is not shipped' }
            )
        }
        components   = @($components)
    }
    Write-Utf8 -Path $SbomPath -Text (($bom | ConvertTo-Json -Depth 12) + [Environment]::NewLine)
}

Write-Output "license-report  wrote: report=$reportPath  json=$Json"
if (-not $SkipDocs) { Write-Output "license-report  wrote: notices=$NoticesPath" }
if ($Sbom) { Write-Output "license-report  wrote: sbom=$SbomPath" }

# --- exit ------------------------------------------------------------------------------------
if ($assetsMissing.Count -gt 0) {
    Write-Output 'license-report  RESULT: INCOMPLETE (project.assets.json missing - run dotnet restore)'
    exit 2
}
if ($violations.Count -gt 0) {
    if ($FailOnDeny) {
        Write-Output 'license-report  RESULT: FAIL (policy violations listed above)'
        exit 3
    }
    Write-Output 'license-report  RESULT: FAIL (violations listed above; -FailOnDeny:$false keeps exit 0)'
    exit 0
}
Write-Output 'license-report  RESULT: PASS (no policy violations)'
exit 0
