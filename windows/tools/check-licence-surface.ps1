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
      5. the LGPL runtime dependency set changes and nobody notices the licence gap count moved;
      6. the committed SBOM drifts from the freshly resolved graph - a package is bumped or added,
         the report moves with it, and docs/sbom-windows.cdx.json keeps describing the old set.
      7. the tracked LGPL candidate binary is replaced, or the digest quoted in ten documents and in
         the report generator goes stale - nothing compared those quotes with the bytes on disk.

    This script checks those seven surfaces without touching the network, the build, or the
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
    [string] $EngineDistPath,
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

# --- 6. SBOM inventory vs the regenerated report -----------------------------------------------
function Get-SbomLicenceExpression {
    param($Component)
    $entry = @($Component.licenses) | Select-Object -First 1
    if ($null -eq $entry) { return "" }
    if ($entry.expression) { return [string]$entry.expression }
    if ($entry.license -and $entry.license.name) { return [string]$entry.license.name }
    return ""
}

function Format-PurlList {
    param([object[]] $Purls)
    $list = @($Purls)
    if ($list.Count -le 3) { return ($list -join ", ") }
    return ((@($list) | Select-Object -First 3) -join ", ") + " (+" + ($list.Count - 3).ToString() + " more)"
}

$reportPackages = @()
$sbomNuget = @()
if ($report) { $reportPackages = @(@($report.packages) | Where-Object { $_.purl -like "pkg:nuget/*" }) }
if ($bom) { $sbomNuget = @(@($bom.components) | Where-Object { $_.purl -like "pkg:nuget/*" }) }

if (-not $report) {
    $reason = "no licence report to compare against - pass -JsonPath, or run: pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom"
    Add-Check "sbom-inventory" "SKIP" $reason
    Add-Check "sbom-inventory-licences" "SKIP" $reason
    Add-Check "sbom-source-revision" "SKIP" $reason
}
elseif (-not $bom) {
    $reason = "the SBOM did not parse, so its inventory cannot be compared - see the sbom-shape check above"
    Add-Check "sbom-inventory" "SKIP" $reason
    Add-Check "sbom-inventory-licences" "SKIP" $reason
    Add-Check "sbom-source-revision" "SKIP" $reason
}
else {
    $reportByPurl = @{}
    foreach ($row in $reportPackages) { $reportByPurl[([string]$row.purl)] = $row }
    $sbomByPurl = @{}
    foreach ($component in $sbomNuget) { $sbomByPurl[([string]$component.purl)] = $component }
    $missing = @($reportByPurl.Keys | Where-Object { -not $sbomByPurl.ContainsKey($_) } | Sort-Object)
    $extra = @($sbomByPurl.Keys | Where-Object { -not $reportByPurl.ContainsKey($_) } | Sort-Object)
    if (($missing.Count -eq 0) -and ($extra.Count -eq 0)) {
        Add-Check "sbom-inventory" "PASS" ($reportByPurl.Count.ToString() + " resolved package(s) recorded one for one in the SBOM (" + $sbomByPurl.Count.ToString() + " pkg:nuget components)")
    }
    else {
        $detail = "the SBOM is stale: " + $missing.Count.ToString() + " resolved package(s) missing [" + (Format-PurlList $missing) + "], " + $extra.Count.ToString() + " recorded but no longer resolved [" + (Format-PurlList $extra) + "] - regenerate: pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom"
        Add-Check "sbom-inventory" "FAIL" $detail
    }

    $licenceDrift = @()
    foreach ($purl in @($reportByPurl.Keys | Sort-Object)) {
        if ($sbomByPurl.ContainsKey($purl)) {
            $recorded = Get-SbomLicenceExpression -Component $sbomByPurl[$purl]
            $resolved = [string]$reportByPurl[$purl].license
            if ($recorded -ne $resolved) { $licenceDrift += ($purl + ": sbom=" + $recorded + " report=" + $resolved) }
        }
    }
    if ($licenceDrift.Count -eq 0) {
        Add-Check "sbom-inventory-licences" "PASS" ("every recorded licence expression still matches the resolved graph (" + @($reportByPurl.Keys).Count.ToString() + " rows)")
    }
    else {
        Add-Check "sbom-inventory-licences" "FAIL" ($licenceDrift.Count.ToString() + " licence expression(s) drifted: " + ((@($licenceDrift) | Select-Object -First 3) -join " ; ") + " - regenerate: pwsh -NoProfile -File windows/tools/license-report.ps1 -Sbom")
    }

    $revisionProp = @($bom.metadata.properties) | Where-Object { $_.name -eq "mediahub:source-revision" } | Select-Object -First 1
    if (-not $revisionProp) {
        Add-Check "sbom-source-revision" "WARN" "the SBOM records no mediahub:source-revision property, so its lineage cannot be checked"
    }
    else {
        $recordedRevision = [string]$revisionProp.value
        $sha = if ($recordedRevision.Contains("@")) { $recordedRevision.Substring($recordedRevision.LastIndexOf("@") + 1) } else { "" }
        if (-not $sha) {
            Add-Check "sbom-source-revision" "WARN" ("cannot read a commit out of mediahub:source-revision=" + $recordedRevision)
        }
        else {
            $git = Get-Command git -ErrorAction SilentlyContinue
            if (-not $git) {
                Add-Check "sbom-source-revision" "WARN" ("git is not on PATH, so " + $sha + " cannot be checked against this branch")
            }
            else {
                $head = ""
                $present = $false
                $shallow = $false
                try {
                    $head = [string](@(& git -C $RepoRoot rev-parse HEAD 2>$null) | Select-Object -First 1)
                    & git -C $RepoRoot cat-file -e ($sha + "^{commit}") 2>$null | Out-Null
                    $present = ($LASTEXITCODE -eq 0)
                    if (-not $present) {
                        $shallowText = [string](@(& git -C $RepoRoot rev-parse --is-shallow-repository 2>$null) | Select-Object -First 1)
                        $shallow = ($shallowText.Trim() -eq "true")
                    }
                }
                catch {
                    $present = $false
                }
                if (-not $present) {
                    if ($shallow) {
                        Add-Check "sbom-source-revision" "WARN" ("this clone is shallow, so it does not contain " + $sha + " (" + $recordedRevision + ") and its lineage cannot be checked here - check out with fetch-depth: 0 to enforce it")
                    }
                    else {
                        Add-Check "sbom-source-revision" "FAIL" ("the SBOM records " + $recordedRevision + ", which is not in this clone at all - it came from another lineage; regenerate it on this branch")
                    }
                }
                else {
                    $reachable = $false
                    try {
                        & git -C $RepoRoot merge-base --is-ancestor $sha HEAD 2>$null | Out-Null
                        $reachable = ($LASTEXITCODE -eq 0)
                    }
                    catch {
                        $reachable = $false
                    }
                    if (-not $reachable) {
                        Add-Check "sbom-source-revision" "FAIL" ("the SBOM says it was generated from " + $recordedRevision + ", which is not an ancestor of HEAD (" + $head + ") - it came from another lineage; regenerate it on this branch")
                    }
                    elseif ($head -and ([string]$head).StartsWith($sha)) {
                        Add-Check "sbom-source-revision" "PASS" ("generated from " + $recordedRevision + ", which is HEAD")
                    }
                    else {
                        Add-Check "sbom-source-revision" "PASS" ("generated from " + $recordedRevision + ", an ancestor of HEAD (" + ([string]$head).Substring(0, 12) + ") - the inventory above is what matters; regenerate to refresh the recorded revision")
                    }
                }
            }
        }
    }
}

# --- 7. SDK pin vs the toolchain that produced the report --------------------------------------
$globalJsonFile = Join-Path $RepoRoot "global.json"
if (-not $report) {
    Add-Check "sdk-pin-matches-report" "SKIP" "the licence report did not parse, so the SDK that produced it is unknown - see the report-exists check above"
}
elseif (-not (Test-Path -LiteralPath $globalJsonFile)) {
    Add-Check "sdk-pin-matches-report" "WARN" ("no " + $globalJsonFile + " - the SDK is not pinned, and SDK feature bands resolve (prune) framework-provided packages differently, so the resolved graph and the SBOM can drift between machines")
}
else {
    try {
        $pinDoc = Read-JsonFile -Path $globalJsonFile
        $pinned = [string]$pinDoc.sdk.version
        $observed = [string]$report.tooling.dotnet
        if (-not $pinned) {
            Add-Check "sdk-pin-matches-report" "WARN" ("global.json has no sdk.version, so there is no pin to compare against")
        }
        elseif (($observed -eq "") -or ($observed -eq "unknown")) {
            Add-Check "sdk-pin-matches-report" "WARN" ("the report does not record which SDK produced it (tooling.dotnet=" + $observed + "), so the pin cannot be checked - regenerate the report where dotnet is on PATH")
        }
        elseif ($observed -eq $pinned) {
            Add-Check "sdk-pin-matches-report" "PASS" ("the report was produced by the pinned SDK " + $pinned)
        }
        else {
            Add-Check "sdk-pin-matches-report" "FAIL" ("the report was produced with SDK " + $observed + " but global.json pins " + $pinned + " - one toolchain must produce the SBOM and every local measurement; build with the pinned SDK or move the pin and the regenerated SBOM together")
        }
    }
    catch {
        Add-Check "sdk-pin-matches-report" "FAIL" ("cannot read " + $globalJsonFile + ": " + $_.Exception.Message)
    }
}

# --- 8. notices inventory vs the resolved graph and the declared packages ----------------------
$noticesRaw = ''
if (Test-Path -LiteralPath $noticesFile) { $noticesRaw = Get-Content -LiteralPath $noticesFile -Raw }
$noticesTable = @()
if (Test-Path -LiteralPath $noticesFile) {
    $noticesTable = @(Get-Content -LiteralPath $noticesFile | Where-Object { $_.TrimStart().StartsWith('|') })
}

function Format-NameList {
    param([object[]] $Names)
    $list = @($Names)
    if ($list.Count -le 3) { return ($list -join ', ') }
    return ((@($list) | Select-Object -First 3) -join ', ') + ' (+' + ($list.Count - 3).ToString() + ' more)'
}

if (-not $report) {
    Add-Check 'notices-inventory' 'SKIP' 'the licence report did not parse, so the resolved graph has nothing to compare against'
}
elseif ($noticesTable.Count -eq 0) {
    Add-Check 'notices-inventory' 'SKIP' ('no table rows in ' + $noticesFile + ' - see the notices-exist check above')
}
else {
    $unrecorded = @()
    $versionDrift = @()
    $licenceGaps = @()
    foreach ($pkgRow in @($report.packages)) {
        $pkgId = [string]$pkgRow.id
        $pkgVersion = [string]$pkgRow.version
        $pkgLicence = [string]$pkgRow.license
        $pkgLabel = $pkgId + '@' + $pkgVersion
        $rowMatches = @($noticesTable | Where-Object { $_.Contains('| ' + $pkgId + ' |') })
        if ($rowMatches.Count -eq 0) { $unrecorded += $pkgLabel; continue }
        $withVersion = $rowMatches
        if ($pkgVersion -ne '') {
            $withVersion = @($rowMatches | Where-Object { $_.Contains($pkgVersion) })
            if ($withVersion.Count -eq 0) { $versionDrift += $pkgLabel; continue }
        }
        if (($pkgLicence -ne '') -and (-not (@($withVersion | Where-Object { $_.Contains($pkgLicence) }).Count -gt 0))) {
            $licenceGaps += ($pkgLabel + ' has no ' + $pkgLicence)
        }
    }
    if (($unrecorded.Count -eq 0) -and ($versionDrift.Count -eq 0) -and ($licenceGaps.Count -eq 0)) {
        Add-Check 'notices-inventory' 'PASS' (@($report.packages).Count.ToString() + ' resolved package(s) each have a notices row with the resolved version and licence (' + $noticesTable.Count.ToString() + ' table rows)')
    }
    else {
        $detail = 'the notices doc is stale: ' + $unrecorded.Count.ToString() + ' package(s) missing [' + (Format-NameList $unrecorded) + '], ' + $versionDrift.Count.ToString() + ' with a drifted version [' + (Format-NameList $versionDrift) + '], ' + $licenceGaps.Count.ToString() + ' with a drifted licence [' + (Format-NameList $licenceGaps) + '] - regenerate: pwsh -NoProfile -File windows/tools/license-report.ps1 -Docs'
        Add-Check 'notices-inventory' 'FAIL' $detail
    }
}

$declaredNames = @()
foreach ($projectFile in @($projectFiles)) {
    $projectText = Get-Content -LiteralPath $projectFile.FullName -Raw
    foreach ($m in [regex]::Matches($projectText, '<PackageReference\s+Include="([^"]+)"')) {
        $declaredNames += [string]$m.Groups[1].Value
    }
}
$declaredNames = @($declaredNames | Sort-Object -Unique)
if ($declaredNames.Count -eq 0) {
    Add-Check 'notices-declarations' 'WARN' 'no PackageReference Include=... found under windows/src and windows/tests'
}
else {
    $notDeclared = @($declaredNames | Where-Object { -not $noticesRaw.Contains($_) })
    if ($notDeclared.Count -eq 0) {
        Add-Check 'notices-declarations' 'PASS' ($declaredNames.Count.ToString() + ' directly declared package(s) are each named in the notices doc')
    }
    else {
        Add-Check 'notices-declarations' 'FAIL' ($notDeclared.Count.ToString() + ' directly declared package(s) are absent from the notices doc: ' + (Format-NameList $notDeclared) + ' - the legal document must name every dependency the build resolves')
    }
}

# --- 9. the tracked LGPL candidate engine identity ---------------------------------------------
# windows/engine/lgpl/dist holds the tracked candidate libmpv build. dist-hashes.txt records the
# digests taken when it was built, and ten documents (plus the report generator itself) quote the
# same digest. Nothing compared either record with the bytes that are actually committed, so a
# swapped binary or a stale document stays invisible.
$engineDist = if ($EngineDistPath) {
    if ([System.IO.Path]::IsPathRooted($EngineDistPath)) { $EngineDistPath }
    else { Resolve-RepoPath -Path $EngineDistPath }
}
else {
    Join-Path $RepoRoot (Join-Path 'windows' (Join-Path 'engine' (Join-Path 'lgpl' 'dist')))
}
$engineHashFile = Join-Path $engineDist 'dist-hashes.txt'
$engineRows = @()
if (Test-Path -LiteralPath $engineHashFile) {
    foreach ($line in @(Get-Content -LiteralPath $engineHashFile)) {
        $trimmed = ([string]$line).Trim()
        if (($trimmed -eq '') -or $trimmed.StartsWith('#')) { continue }
        $parts = @($trimmed -split "\s+")
        if ($parts.Count -lt 2) { continue }
        $name = [System.IO.Path]::GetFileName($parts[$parts.Count - 1].TrimStart('*'))
        if ($name -eq '') { continue }
        $candidatePath = Join-Path $engineDist $name
        $exists = Test-Path -LiteralPath $candidatePath
        $bytes = [int64]0
        $observed = ''
        if ($exists) {
            $bytes = [int64](Get-Item -LiteralPath $candidatePath).Length
            $observed = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $engineRows += [pscustomobject]@{
            name = $name
            recorded = $parts[0].ToLowerInvariant()
            path = $candidatePath
            exists = $exists
            bytes = $bytes
            sha256 = $observed
        }
    }
}
$engineMissing = @()
$engineDrift = @()
foreach ($row in $engineRows) {
    if (-not $row.exists) { $engineMissing += $row.name; continue }
    if ($row.sha256 -ne $row.recorded) {
        $engineDrift += ($row.name + ' recorded=' + $row.recorded.Substring(0, 8) + '...' + $row.recorded.Substring($row.recorded.Length - 4) + ' observed=' + $row.sha256.Substring(0, 8) + '...' + $row.sha256.Substring($row.sha256.Length - 4))
    }
}
if ($engineRows.Count -eq 0) {
    Add-Check 'lgpl-candidate-hashes' 'WARN' ('no recorded artefact hash was found under ' + $engineDist + ' - dist-hashes.txt is absent or empty, so the tracked engine binaries are unverified here')
}
elseif (($engineMissing.Count -eq 0) -and ($engineDrift.Count -eq 0)) {
    $coreRows = @($engineRows | Where-Object { $_.name -eq 'libmpv-2.dll' })
    $coreNote = ''
    if ($coreRows.Count -eq 1) { $coreNote = ' (libmpv-2.dll ' + $coreRows[0].bytes.ToString('N0') + ' B)' }
    Add-Check 'lgpl-candidate-hashes' 'PASS' ($engineRows.Count.ToString() + ' recorded engine artefact(s) still hash to their recorded digest' + $coreNote)
}
else {
    Add-Check 'lgpl-candidate-hashes' 'FAIL' ($engineMissing.Count.ToString() + ' recorded artefact(s) absent [' + (Format-NameList $engineMissing) + '], ' + $engineDrift.Count.ToString() + ' with a drifted digest [' + (Format-NameList $engineDrift) + '] - the record lives in windows/engine/lgpl/dist/dist-hashes.txt')
}

# The same identity is quoted elsewhere; every quote must still agree with the bytes.
$claimSites = @(
    'windows/tools/license-report.ps1',
    'windows/docs/LGPL-ENGINE-SWITCH.md',
    'windows/docs/LGPL-COMPLIANCE.md',
    'windows/docs/licensing/W5-LGPL-BUILD.md',
    'windows/docs/licensing/SBOM.md',
    'windows/docs/THIRD-PARTY-NOTICES.md',
    'windows/THIRD-PARTY-NOTICES.md',
    'README.md',
    'windows/engine/README.md',
    'windows/docs/W13-LGPL-ENGINE-PROBE.md',
    'windows/docs/W9-EVIDENCE.md'
)
$coreCandidate = @($engineRows | Where-Object { ($_.name -eq 'libmpv-2.dll') -and $_.exists })
if ($coreCandidate.Count -ne 1) {
    Add-Check 'lgpl-candidate-claims' 'SKIP' 'the tracked candidate libmpv-2.dll is not present here, so the documents quoting its identity cannot be compared with it'
}
else {
    $claimBytes = ([int64]$coreCandidate[0].bytes).ToString('N0')
    $claimDigest = [string]$coreCandidate[0].sha256
    $claimPrefix = $claimDigest.Substring(0, 8)
    $claimSuffix = $claimDigest.Substring($claimDigest.Length - 4)
    $claimStale = @()
    foreach ($site in $claimSites) {
        $sitePath = Resolve-RepoPath -Path $site
        if (-not (Test-Path -LiteralPath $sitePath)) { $claimStale += ($site + ' (file absent)'); continue }
        $siteText = [string](Get-Content -LiteralPath $sitePath -Raw)
        if ($null -eq $siteText) { $siteText = '' }
        $lower = $siteText.ToLowerInvariant()
        if (-not $lower.Contains($claimBytes)) { $claimStale += ($site + ' (never quotes ' + $claimBytes + ')'); continue }
        $hasDigest = $lower.Contains($claimDigest) -or ($lower.Contains($claimPrefix) -and $lower.Contains($claimSuffix))
        if (-not $hasDigest) { $claimStale += ($site + ' (quotes the size but not the digest)') }
    }
    if ($claimStale.Count -eq 0) {
        Add-Check 'lgpl-candidate-claims' 'PASS' ($claimSites.Count.ToString() + ' claim site(s) quote the observed candidate identity (' + $claimBytes + ' B, ' + $claimPrefix + '...' + $claimSuffix + ')')
    }
    else {
        Add-Check 'lgpl-candidate-claims' 'FAIL' ($claimStale.Count.ToString() + ' claim site(s) disagree with the tracked libmpv-2.dll (' + $claimBytes + ' B, ' + $claimPrefix + '...' + $claimSuffix + '): ' + (Format-NameList $claimStale) + ' - update the quote or the binary, they must describe the same build')
    }
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
