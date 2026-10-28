#Requires -Version 7.0
<#
.SYNOPSIS
    Static sanity check that the licence surface still matches the repository.

.DESCRIPTION
    Five things rot silently in a licence deliverable:
      1. a new .csproj appears and the NuGet report was never regenerated;
      2. the generated notices lose the libmpv GPL conclusion or the LGPL dependency table;
      3. the SBOM stops being a valid CycloneDX 1.5 document or loses the engine record;
      4. a regenerated report suddenly contains a policy violation;
      5. the LGPL runtime dependency set changes and nobody notices the licence gap count moved.

    This script checks those five surfaces without touching the network, the build, or the
    release artefacts. It is deliberately read-only: everything it verifies is a file that
    windows/tools/license-report.ps1 or a maintainer wrote earlier.

.PARAMETER RepoRoot
    Repository root. Default: two levels above this script (windows/tools/..).

.PARAMETER JsonPath
    The license-report.json written by license-report.ps1 -Json. When omitted, the script looks
    for windows/artifacts/license-report/license-report.json (the path the docs use) and, if that is absent too,
    the report-dependent checks are reported as SKIP instead of failing.

.PARAMETER FailOnLicenceGaps
    Treat the LGPL dependency rows whose licence cannot be quoted from component-licences.txt as
    errors. Off by default because those five rows are a known, documented gap: the default
    flavour does not ship those DLLs.

.EXAMPLE
    pwsh -NoProfile -File windows/tools/check-licence-surface.ps1

.EXAMPLE
    pwsh -NoProfile -File windows/tools/check-licence-surface.ps1 -JsonPath windows/artifacts/license-report/license-report.json -FailOnLicenceGaps
.NOTES
    Exit codes: 0 = every check passed (warnings allowed), 1 = at least one check failed,
    2 = usage problem (repository root not found).
#>
[CmdletBinding()]
param(
    [string] $RepoRoot,
    [string] $JsonPath,
    [string] $NoticesPath,
    [string] $SbomPath,
    [string] $LgplCompliancePath,
    [switch] $FailOnLicenceGaps
)

$ErrorActionPreference = 'Stop'
$pathSeparator = [string][System.IO.Path]::DirectorySeparatorChar

if (-not $RepoRoot) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
if (-not (Test-Path -LiteralPath $RepoRoot)) {
    Write-Error "repository root not found: $RepoRoot"
    exit 2
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

function Resolve-RepoPath {
    param([string] $Path, [string] $Default)
    $value = if ($Path) { $Path } else { $Default }
    return (Join-Path $RepoRoot ($value -replace '/', $pathSeparator))
}

$noticesFile = Resolve-RepoPath -Path $NoticesPath -Default 'windows/docs/THIRD-PARTY-NOTICES.md'
$sbomFile = Resolve-RepoPath -Path $SbomPath -Default 'windows/docs/sbom-windows.cdx.json'
$complianceFile = Resolve-RepoPath -Path $LgplCompliancePath -Default 'windows/docs/LGPL-COMPLIANCE.md'
$reportFile = if ($JsonPath) { $JsonPath } else { Join-Path $RepoRoot ('windows' + $pathSeparator + 'artifacts' + $pathSeparator + 'license-report' + $pathSeparator + 'license-report.json') }
$collectedListFile = Resolve-RepoPath -Path '' -Default 'windows/engine/lgpl/dist/runtime-dependencies-collected.txt'

$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check {
    param([string] $Id, [string] $Outcome, [string] $Detail)
    $checks.Add([ordered]@{ id = $Id; outcome = $Outcome; detail = $Detail }) | Out-Null
    Write-Output ('check-licence-surface  ' + $Outcome.PadRight(4) + '  ' + $Id + '  ' + $Detail)
}

function Read-JsonFile {
    param([string] $Path)
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable)
}

# --- 1. project surface -----------------------------------------------------------------------
$projectFiles = @()
foreach ($dir in @('windows/src', 'windows/tests')) {
    $full = Join-Path $RepoRoot ($dir -replace '/', $pathSeparator)
    if (Test-Path -LiteralPath $full) {
        $projectFiles += @(Get-ChildItem -LiteralPath $full -Filter *.csproj -File -Recurse -Depth 1)
    }
}
$projectCount = @($projectFiles).Count
if ($projectCount -gt 0) {
    Add-Check 'projects-discovered' 'PASS' ($projectCount.ToString() + ' .csproj under windows/src and windows/tests')
}
else {
    Add-Check 'projects-discovered' 'FAIL' 'no .csproj found - wrong -RepoRoot?'
}

# --- 2. generated notices ---------------------------------------------------------------------
if (Test-Path -LiteralPath $noticesFile) {
    $notices = Get-Content -LiteralPath $noticesFile -Raw
    Add-Check 'notices-exist' 'PASS' ($noticesFile + ' (' + ([System.Text.Encoding]::UTF8.GetByteCount($notices)).ToString() + ' B)')
    $noticeExpectations = @(
        @('notices-libmpv-engine', 'libmpv-2.dll'),
        @('notices-engine-licence', 'GPL-3.0-or-later'),
        @('notices-lgpl-dependency-section', '### 3.3 LGPL flavour'),
        @('notices-lgpl-evidence', 'component-licences.txt'),
        @('notices-reproduce-command', 'license-report.ps1')
    )
    foreach ($pair in $noticeExpectations) {
        if ($notices.Contains($pair[1])) {
            Add-Check $pair[0] 'PASS' ('contains ' + [char]39 + $pair[1] + [char]39)
        }
        else {
            Add-Check $pair[0] 'FAIL' ('missing marker: ' + $pair[1])
        }
    }
    if ($notices.Contains('check-licence-surface.ps1')) {
        Add-Check 'notices-points-here' 'PASS' 'the related-files line names this script'
    }
    else {
        Add-Check 'notices-points-here' 'WARN' 'the related-files line does not name check-licence-surface.ps1'
    }
}
else {
    Add-Check 'notices-exist' 'FAIL' ('missing ' + $noticesFile + ' - run: pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom')
}

# --- 3. LGPL compliance note ------------------------------------------------------------------
if (Test-Path -LiteralPath $complianceFile) {
    $compliance = Get-Content -LiteralPath $complianceFile -Raw
    Add-Check 'lgpl-compliance-exists' 'PASS' $complianceFile
    foreach ($pair in @(@('lgpl-compliance-dynamic-linking', 'P/Invoke'), @('lgpl-compliance-replaceable-dll', 'libmpv-2.dll'), @('lgpl-compliance-source-offer', 'written offer'))) {
        if ($compliance.Contains($pair[1])) { Add-Check $pair[0] 'PASS' ('contains ' + $pair[1]) }
        else { Add-Check $pair[0] 'FAIL' ('missing marker: ' + $pair[1]) }
    }
}
else {
    Add-Check 'lgpl-compliance-exists' 'FAIL' ('missing ' + $complianceFile)
}

# --- 4. SBOM ----------------------------------------------------------------------------------
if (Test-Path -LiteralPath $sbomFile) {
    try {
        $bom = Read-JsonFile -Path $sbomFile
        $ok = ($bom.bomFormat -eq 'CycloneDX') -and ($bom.specVersion -eq '1.5')
        if ($ok) { Add-Check 'sbom-shape' 'PASS' ('CycloneDX ' + $bom.specVersion + ', ' + @($bom.components).Count.ToString() + ' components') }
        else { Add-Check 'sbom-shape' 'FAIL' ('bomFormat/specVersion unexpected: ' + $bom.bomFormat + '/' + $bom.specVersion) }
        $components = @($bom.components)
        $libmpv = $components | Where-Object { $_.name -eq 'libmpv' } | Select-Object -First 1
        if ($libmpv) { Add-Check 'sbom-engine-component' 'PASS' ('libmpv ' + $libmpv.version + ' licensed ' + (@($libmpv.licenses)[0].expression)) }
        else { Add-Check 'sbom-engine-component' 'FAIL' 'the SBOM has no libmpv component - the shipped engine is unrecorded' }
        $props = @($bom.metadata.properties)
        if (@($props | Where-Object { $_.name -eq 'mediahub:lgpl-flavour-dependencies' }).Count -gt 0) {
            Add-Check 'sbom-lgpl-flavour-property' 'PASS' 'metadata records the LGPL flavour dependency set'
        }
        else { Add-Check 'sbom-lgpl-flavour-property' 'WARN' 'metadata has no mediahub:lgpl-flavour-dependencies property' }
    }
    catch {
        Add-Check 'sbom-shape' 'FAIL' ('cannot parse ' + $sbomFile + ': ' + $_.Exception.Message)
    }
}
else {
    Add-Check 'sbom-shape' 'FAIL' ('missing ' + $sbomFile + ' - run: pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom')
}

# --- 5. NuGet report --------------------------------------------------------------------------
$collectedRows = 0
if (Test-Path -LiteralPath $collectedListFile) {
    $collectedRows = @(Get-Content -LiteralPath $collectedListFile | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') }).Count
}
if (Test-Path -LiteralPath $reportFile) {
    try {
        $report = Read-JsonFile -Path $reportFile
        $summary = $report.summary
        Add-Check 'report-exists' 'PASS' ($reportFile + ' (generated ' + $report.generatedAtUtc + ')')
        if ($summary.violations -eq 0) { Add-Check 'report-no-violations' 'PASS' 'violations=0' }
        else { Add-Check 'report-no-violations' 'FAIL' ('violations=' + $summary.violations + ' - see ' + $reportFile) }
        if ($summary.projects -eq $projectCount) { Add-Check 'report-project-count' 'PASS' ('report projects=' + $summary.projects + ' matches the ' + $projectCount + ' .csproj on disk') }
        else { Add-Check 'report-project-count' 'FAIL' ('report projects=' + $summary.projects + ' but ' + $projectCount + ' .csproj exist - regenerate the report') }
        if ($report.nativeEngine.observed.matchesPin) { Add-Check 'report-engine-pin' 'PASS' ('libmpv sha256 matches the pinned value (' + $report.nativeEngine.observed.bytes + ' B)') }
        else { Add-Check 'report-engine-pin' 'FAIL' ('the shipped libmpv-2.dll does not match the pinned sha256: ' + $report.nativeEngine.observed.sha256) }
        $flavour = $report.lgplFlavour
        if ($flavour.present) {
            if ($flavour.count -eq $collectedRows) { Add-Check 'report-lgpl-dependency-count' 'PASS' ('report count=' + $flavour.count + ' matches the ' + $collectedRows + ' collected rows') }
            else { Add-Check 'report-lgpl-dependency-count' 'FAIL' ('report count=' + $flavour.count + ' but the collected list has ' + $collectedRows + ' rows - regenerate the report') }
            $gaps = @($flavour.unrecorded)
            $gapDetail = if ($gaps.Count -gt 0) { $gaps.Count.ToString() + ' row(s) without a licence declaration: ' + ($gaps -join ', ') } else { 'every row has a declaration line' }
            if ($gaps.Count -eq 0) { Add-Check 'lgpl-licence-gaps' 'PASS' $gapDetail }
            elseif ($FailOnLicenceGaps) { Add-Check 'lgpl-licence-gaps' 'FAIL' $gapDetail }
            else { Add-Check 'lgpl-licence-gaps' 'WARN' $gapDetail }
        }
        else {
            Add-Check 'report-lgpl-dependency-count' 'WARN' 'the report says the LGPL dependency set was never collected on this machine'
        }
    }
    catch {
        Add-Check 'report-exists' 'FAIL' ('cannot parse ' + $reportFile + ': ' + $_.Exception.Message)
    }
}
else {
    Add-Check 'report-exists' 'SKIP' ('no report at ' + $reportFile + ' - pass -JsonPath, or run: pwsh -NoProfile -File windows/tools/license-report.ps1 -OutDir <dir> -Json <dir>/license-report.json -Sbom')
}

# --- summary ----------------------------------------------------------------------------------
$failed = @($checks | Where-Object { $_.outcome -eq 'FAIL' })
$warned = @($checks | Where-Object { $_.outcome -eq 'WARN' })
$skipped = @($checks | Where-Object { $_.outcome -eq 'SKIP' })
Write-Output ''
Write-Output ('check-licence-surface  checks=' + $checks.Count + ' failed=' + $failed.Count + ' warnings=' + $warned.Count + ' skipped=' + $skipped.Count)
if ($failed.Count -gt 0) {
    Write-Output 'RESULT: FAIL'
    exit 1
}
Write-Output 'RESULT: PASS'
exit 0
