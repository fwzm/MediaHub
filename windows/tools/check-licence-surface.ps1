#Requires -Version 7.0
<#
.SYNOPSIS
    Static sanity check that the licence surface still matches the repository.

.DESCRIPTION
    Fourteen things rot silently in a licence deliverable:
      1. a new .csproj appears and the NuGet report was never regenerated;
      2. the generated notices lose the libmpv GPL conclusion or the LGPL dependency table;
      3. the SBOM stops being a valid CycloneDX 1.5 document or loses the engine record;
      4. a regenerated report suddenly contains a policy violation;
      5. the LGPL runtime dependency set changes and nobody notices the licence gap count moved;
      6. the committed SBOM drifts from the freshly resolved graph - a package is bumped or added,
         the report moves with it, and docs/sbom-windows.cdx.json keeps describing the old set.
      7. the tracked LGPL candidate binary is replaced, or the digest quoted in ten documents and in
         the report generator goes stale - nothing compared those quotes with the bytes on disk.
      8. the engine capability assessment drifts: the candidate starts advertising Vulkan, the player stops
         pinning gpu-api=d3d11, or the verdict in windows/docs/licensing/VULKAN-NECESSITY.md is lost.
      9. the LGPL flavour ships a DLL whose licence declaration is missing, or whose row in
         component-licences.txt was rewritten and the report kept quoting the old transcription.
     10. the shipping state in windows/docs/licensing/PACKAGING.md drifts: CI starts signing, an
          app manifest or installer project appears, or the app stops being an unpackaged
          self-contained portable build - the document keeps claiming SIGNING_BLOCKED and green CI.
     11. the published package stops carrying licence text: the staging step is dropped from
          windows-ci.yml, the set shrinks, or a digest in the generated manifest stops matching the
          bytes - PACKAGING.md section 1 and SBOM.md section 4.1 both recorded zero licence files.
     12. the LGPL compliance note rots: its component-licence table stops matching the resolved
     13. the guard's own inventory drifts: a check is added and never listed, the document or the
         README keeps claiming the old number, or a declared check id is dropped from the document.
      14. the default engine flavour silently leaves the GPL baseline: the player project starts
          defaulting to the LGPL candidate, another project or workflow builds it, or
          windows/docs/LGPL-ENGINE-SWITCH.md stops declaring which flavour is default.

    This script checks those fourteen surfaces without touching the network, the build, or the
    release artefacts. It is deliberately read-only: everything it verifies is a file that
    windows/tools/license-report.ps1 or a maintainer wrote earlier.

.PARAMETER RepoRoot
    Repository root. Default: two levels above this script (windows/tools/..).

.PARAMETER JsonPath
    The license-report.json written by license-report.ps1 -Json. When omitted, the script looks
    for windows/artifacts/license-report/license-report.json (the path the docs use) and, if that is absent too,
    the report-dependent checks are reported as SKIP instead of failing.

.PARAMETER StageScriptPath
    The script that stages licence/notice files into a published tree. Default:
    windows/tools/stage-licence-files.ps1. The package-licence-staging check re-runs it into a
    temporary tree rather than trusting the document.

.PARAMETER LicenceDocPath
    The document that lists the checks and how many of them there are. Default:
    windows/docs/licensing/LICENSE-SURFACE-CHECK.md. The check-inventory check compares its count
    claim (and the README one) with the inventory declared in this script.

.PARAMETER ReadmePath
    The README whose licence-surface paragraph states how many checks run. Default: README.md.

.PARAMETER PlayerProjectPath
    The project that decides which libmpv build the app loads. Default:
    windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj.

.PARAMETER LgplSwitchDocPath
    The document that records the default flavour and the switch flag. Default:
    windows/docs/LGPL-ENGINE-SWITCH.md.

.PARAMETER FailOnLicenceGaps
    Treat the LGPL dependency rows whose licence cannot be quoted from component-licences.txt as
    errors as well. Off by default, but as of W38 no such row is expected: every shipped DLL has a
    declaration line, and the separate lgpl-licence-table check fails if one goes missing again.
    The switch stays so the older behaviour can still be reproduced.

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
    [string] $EngineFeaturePath,
    [string] $PlayerSourcePath,
    [string] $VulkanDocPath,
    [string] $SourceRootPath,
    [string] $PackagingDocPath,
    [string] $WorkflowsPath,
    [string] $WindowsRootPath,
    [string] $AppProjectPath,
    [string] $StageScriptPath,
    [string] $LicenceDocPath,
    [string] $ReadmePath,
    [string] $PlayerProjectPath,
    [string] $LgplSwitchDocPath,
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

$licenceDocFile = Resolve-RepoPath -Path $LicenceDocPath -Default 'windows/docs/licensing/LICENSE-SURFACE-CHECK.md'
$readmeFile = Resolve-RepoPath -Path $ReadmePath -Default 'README.md'

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
# The licence decision for the LGPL engine rests on one capability claim: the product never asks for a
# Vulkan surface and the candidate build does not advertise one. Both halves can rot - a settings
# default changes, the candidate is rebuilt with Vulkan, or the verdict file disappears - so the claim
# is pinned to the artefacts that carry it. See windows/docs/licensing/VULKAN-NECESSITY.md.
$vulkanDocFile = Resolve-RepoPath -Path $VulkanDocPath -Default 'windows/docs/licensing/VULKAN-NECESSITY.md'
$engineFeatureFile = Resolve-RepoPath -Path $EngineFeaturePath -Default 'windows/engine/lgpl/mpv-lgpl-features.txt'
$playerSourceFile = Resolve-RepoPath -Path $PlayerSourcePath -Default 'windows/src/MediaHub.Player.Mpv/MpvPlayer.cs'
$vulkanProblems = @()
if (-not (Test-Path -LiteralPath $engineFeatureFile)) {
    Add-Check 'vulkan-claims' 'SKIP' ('the recorded engine feature list is absent (' + $engineFeatureFile + '), so the candidate side of the Vulkan claim cannot be checked here')
}
else {
    $engineFeatureText = [string](Get-Content -LiteralPath $engineFeatureFile -Raw)
    $featureMatch = [regex]::Match($engineFeatureText, 'List of enabled features:\s*(?<list>[^\r\n]+)')
    $engineFeatures = @()
    if ($featureMatch.Success) {
        $engineFeatures = @($featureMatch.Groups['list'].Value.Trim() -split '\s+' | Where-Object { $_.Length -gt 0 })
    }
    if ($engineFeatures.Count -eq 0) { $vulkanProblems += 'the recorded feature list could not be parsed' }
    foreach ($needed in @('d3d11', 'd3d-hwaccel', 'libplacebo')) {
        if ($engineFeatures -notcontains $needed) { $vulkanProblems += ('the candidate no longer advertises ' + $needed) }
    }
    if ($engineFeatures -contains 'vulkan') { $vulkanProblems += 'the candidate now advertises vulkan, so the capability delta recorded in VULKAN-NECESSITY.md no longer holds' }
    $sourceRoot = Resolve-RepoPath -Path $SourceRootPath -Default 'windows/src'
    $sourceMentions = @()
    if (Test-Path -LiteralPath $sourceRoot) {
        $sourceMentions = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.cs' -File | Where-Object {
            ([string](Get-Content -LiteralPath $_.FullName -Raw)).ToLowerInvariant().Contains('vulkan')
        } | ForEach-Object { $_.FullName.Substring($RepoRoot.Length + 1) })
    }
    if ($sourceMentions.Count -gt 0) { $vulkanProblems += ('the product source now mentions vulkan [' + (Format-NameList $sourceMentions) + ']') }
    $playerText = ''
    if (Test-Path -LiteralPath $playerSourceFile) { $playerText = [string](Get-Content -LiteralPath $playerSourceFile -Raw) }
    if (-not ([regex]::IsMatch($playerText, 'Opt\(\s*"gpu-api"\s*,\s*"d3d11"\s*\)'))) { $vulkanProblems += 'MpvPlayer no longer pins gpu-api=d3d11' }
    $docText = ''
    if (Test-Path -LiteralPath $vulkanDocFile) { $docText = [string](Get-Content -LiteralPath $vulkanDocFile -Raw) }
    if ($null -eq $docText) { $docText = '' }
    if ($docText -eq '') { $vulkanProblems += 'windows/docs/licensing/VULKAN-NECESSITY.md is missing' }
    else {
        if (-not ([regex]::IsMatch($docText, '(?m)^\s*verdict:\s*not-required-by-the-product-path\s*$'))) { $vulkanProblems += 'VULKAN-NECESSITY.md lost its verdict line' }
        if (-not ([regex]::IsMatch($docText, '(?m)^\s*capability-delta:\s*lgpl-candidate-lacks-vulkan\s*$'))) { $vulkanProblems += 'VULKAN-NECESSITY.md lost its capability-delta line' }
    }
    if ($vulkanProblems.Count -eq 0) {
        Add-Check 'vulkan-claims' 'PASS' ('the candidate still advertises d3d11/d3d-hwaccel/libplacebo without vulkan (' + $engineFeatures.Count.ToString() + ' features), the player still pins gpu-api=d3d11, and no .cs under windows/src mentions vulkan')
    }
    else {
        Add-Check 'vulkan-claims' 'FAIL' ($vulkanProblems.Count.ToString() + ' claim(s) drifted: ' + (Format-NameList $vulkanProblems) + ' - update windows/docs/licensing/VULKAN-NECESSITY.md and the measurement together')
    }
}

# The LGPL flavour ships 33 extra DLLs and every one of them must carry a licence declaration the
# table really holds. The report transcribes the packager's line; this check goes back to the bytes,
# and it also compares the shipped file list with the report row by row so a name that silently lost
# its map entry cannot disappear from the notices.
$lgplFlavour = $null
if ($null -ne $report) { $lgplFlavour = $report['lgplFlavour'] }
$licenceRows = @()
$lgplPresent = $false
if ($null -ne $lgplFlavour) {
    $licenceRows = @($lgplFlavour['dependencies'])
    $lgplPresent = [bool]$lgplFlavour['present']
}
if ((-not $lgplPresent) -or ($licenceRows.Count -eq 0)) {
    Add-Check 'lgpl-licence-table' 'SKIP' 'the report carries no LGPL-flavour dependency list here, so its licence declarations cannot be compared with windows/engine/lgpl/component-licences.txt'
}
else {
    $licenceTableFile = Resolve-RepoPath -Path ([string]$lgplFlavour['licenceTable']) -Default 'windows/engine/lgpl/component-licences.txt'
    $licenceTableLines = @()
    if (Test-Path -LiteralPath $licenceTableFile) { $licenceTableLines = @(Get-Content -LiteralPath $licenceTableFile) }
    $collectedFile = Resolve-RepoPath -Path ([string]$lgplFlavour['collectedList']) -Default 'windows/engine/lgpl/dist/runtime-dependencies-collected.txt'
    $collectedNames = @()
    if (Test-Path -LiteralPath $collectedFile) {
        $collectedNames = @(Get-Content -LiteralPath $collectedFile | Where-Object { $_.Trim() -ne '' -and -not $_.Trim().StartsWith('#') } | ForEach-Object { $_.Trim() -split '\s+' | Select-Object -First 1 })
    }
    $licenceUnrecorded = @()
    $licenceNoSource = @()
    $licenceBroken = @()
    $licenceMissing = @()
    foreach ($row in $licenceRows) {
        $name = [string]$row['name']
        $licence = [string]$row['license']
        $evidence = [string]$row['evidence']
        if ($licence.StartsWith('未记录')) { $licenceUnrecorded += ($name + ' (' + $licence + ')'); continue }
        if (-not $evidence.Contains('component-licences.txt')) { $licenceNoSource += ($name + ' -> ' + $evidence); continue }
        $single = [regex]::Match($evidence, 'component-licences\.txt:(\d+)(\s+\(.*\))?\z')
        $rng = [regex]::Match($evidence, 'component-licences\.txt:(\d+)-(\d+)')
        if ($single.Success) {
            $lineNo = [int]$single.Groups[1].Value
            if (($lineNo -lt 1) -or ($lineNo -gt $licenceTableLines.Count)) {
                $licenceBroken += ($name + ' points at component-licences.txt:' + $lineNo + ', but the table has ' + $licenceTableLines.Count + ' line(s)')
            }
            elseif (-not $licenceTableLines[$lineNo - 1].Contains($licence)) {
                $licenceBroken += ($name + ' quotes ' + $licence + ' from component-licences.txt:' + $lineNo + ', which reads: ' + $licenceTableLines[$lineNo - 1].Trim())
            }
        }
        elseif ($rng.Success) {
            $from = [int]$rng.Groups[1].Value
            $to = [int]$rng.Groups[2].Value
            $component = [string]$row['component']
            $flat = $component
            $paren = $component.IndexOf(' (')
            if ($paren -gt 0) { $flat = $component.Substring(0, $paren) }
            if (($from -lt 1) -or ($to -gt $licenceTableLines.Count) -or ($from -gt $to)) {
                $licenceBroken += ($name + ' points at component-licences.txt:' + $from + '-' + $to + ', outside the ' + $licenceTableLines.Count + '-line table')
            }
            else {
                $joined = ($licenceTableLines[($from - 1)..($to - 1)] -join ' ')
                if (-not $joined.Contains($flat)) { $licenceBroken += ($name + ' cites comments ' + $from + '-' + $to + ' that never name ' + $flat) }
                elseif (-not ($evidence -match '\(.+\)')) { $licenceBroken += ($name + ' cites those comments without saying which build produced the licence') }
            }
        }
        else {
            $licenceBroken += ($name + ' cites component-licences.txt without a line number')
        }
    }
    if ($collectedNames.Count -gt 0) {
        $rowNames = @($licenceRows | ForEach-Object { [string]$_['name'] })
        $licenceMissing = @($collectedNames | Where-Object { $rowNames -notcontains $_ })
        if (($licenceMissing.Count -eq 0) -and ($rowNames.Count -ne $collectedNames.Count)) {
            $licenceMissing += ('the shipped list has ' + $collectedNames.Count + ' file(s) but the report carries ' + $rowNames.Count + ' row(s)')
        }
    }
    $licenceProblems = @()
    if ($licenceUnrecorded.Count -gt 0) { $licenceProblems += ($licenceUnrecorded.Count.ToString() + ' shipped file(s) still have no licence declaration [' + (Format-NameList $licenceUnrecorded) + ']') }
    if ($licenceNoSource.Count -gt 0) { $licenceProblems += ($licenceNoSource.Count.ToString() + ' row(s) cite no source in component-licences.txt [' + (Format-NameList $licenceNoSource) + ']') }
    if ($licenceBroken.Count -gt 0) { $licenceProblems += ($licenceBroken.Count.ToString() + ' row(s) cite a table line that does not hold their licence [' + (Format-NameList $licenceBroken) + ']') }
    if ($licenceMissing.Count -gt 0) { $licenceProblems += ('the shipped list and the report disagree: ' + (Format-NameList @($licenceMissing))) }
    if ($licenceProblems.Count -eq 0) {
        Add-Check 'lgpl-licence-table' 'PASS' ($licenceRows.Count.ToString() + ' shipped LGPL-flavour DLL(s) each quote a licence declaration that component-licences.txt really holds (' + $licenceTableLines.Count + ' table line(s), shipped list ' + $collectedNames.Count.ToString() + ' file(s))')
    }
    else {
        Add-Check 'lgpl-licence-table' 'FAIL' (($licenceProblems -join '; ') + ' - re-collect the table with windows/engine/lgpl/collect-licences.sh and re-map the new package in windows/tools/license-report.ps1')
    }
}

# --- 10. packaging and signing claims ---------------------------------------------------------
# windows/docs/licensing/PACKAGING.md states the shipping state (portable, self-contained,
# unsigned, no MSIX, no installer, never installed on a clean machine, release blocked by the GPL
# obligation). Nothing compared that statement with the repository, so the first signing step, the
# first Package.appxmanifest or a changed packaging property would leave the document describing
# the old state while every gate stayed green. Build output (obj/bin/artifacts) is not the
# repository: the MSIX tooling writes .msix files into obj on every Debug build.
function Resolve-ClaimPath {
    param([string] $Path, [string] $Default)
    $value = if ($Path) { $Path } else { $Default }
    if ([System.IO.Path]::IsPathRooted($value)) { return $value }
    return (Resolve-RepoPath -Path $value)
}
$packagingDocFile = Resolve-ClaimPath -Path $PackagingDocPath -Default 'windows/docs/licensing/PACKAGING.md'
$packagingWorkflows = Resolve-ClaimPath -Path $WorkflowsPath -Default '.github/workflows'
$packagingWindowsRoot = Resolve-ClaimPath -Path $WindowsRootPath -Default 'windows'
$packagingAppProject = Resolve-ClaimPath -Path $AppProjectPath -Default 'windows/src/MediaHub.App/MediaHub.App.csproj'
if (-not (Test-Path -LiteralPath $packagingDocFile)) {
    Add-Check 'packaging-claims' 'SKIP' ('the packaging document is absent at ' + $packagingDocFile + ', so the shipping state it states cannot be compared with the repository')
}
else {
    $packagingDoc = [string](Get-Content -LiteralPath $packagingDocFile -Raw)
    if ($null -eq $packagingDoc) { $packagingDoc = '' }
    $packagingProblems = @()
    $packagingFacts = @(
        @{ line = 'signing-status: blocked-no-certificate'; what = 'signing status' },
        @{ line = 'packaging-form: portable-zip-self-contained'; what = 'packaging form' },
        @{ line = 'msix: not-implemented'; what = 'MSIX state' },
        @{ line = 'installer: not-implemented'; what = 'installer state' },
        @{ line = 'clean-machine-install: not-run'; what = 'clean-machine install state' },
        @{ line = 'release-status: blocked-gpl-obligation'; what = 'release status' },
        @{ line = 'licence-files-in-package: staged-by-ci'; what = 'in-package licence file state' }
    )
    foreach ($fact in $packagingFacts) {
        if (-not $packagingDoc.Contains([string]$fact['line'])) { $packagingProblems += ('the machine-readable ' + $fact['what'] + ' line is missing (expected "' + $fact['line'] + '")') }
    }
    if (-not (Test-Path -LiteralPath $packagingAppProject)) {
        $packagingProblems += ('the app project is absent at ' + $packagingAppProject + ', so the documented packaging form cannot be verified')
    }
    else {
        $appProjectText = [string](Get-Content -LiteralPath $packagingAppProject -Raw)
        if ($null -eq $appProjectText) { $appProjectText = '' }
        foreach ($property in @('<WindowsPackageType>None</WindowsPackageType>', '<WindowsAppSDKSelfContained>true</WindowsAppSDKSelfContained>', '<SelfContained>true</SelfContained>', '<RuntimeIdentifier>win-x64</RuntimeIdentifier>')) {
            if (-not $appProjectText.Contains($property)) { $packagingProblems += ('MediaHub.App.csproj no longer carries ' + $property) }
        }
    }
    $signingSignals = @()
    if (Test-Path -LiteralPath $packagingWorkflows) {
        foreach ($workflowFile in @(Get-ChildItem -LiteralPath $packagingWorkflows -File -Recurse -ErrorAction SilentlyContinue)) {
            $workflowText = [string](Get-Content -LiteralPath $workflowFile.FullName -Raw)
            if ($null -eq $workflowText) { $workflowText = '' }
            foreach ($token in @('signtool', 'AzureSignTool', 'CertificateThumbprint', 'signingCertificate', '-p:SignPackage', 'codesign')) {
                if ($workflowText.IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $signingSignals += ($workflowFile.Name + ' mentions ' + $token) }
            }
        }
    }
    if (@($signingSignals).Count -gt 0) { $packagingProblems += ('the document says no CI job signs and no certificate secret exists, but ' + (Format-NameList @($signingSignals))) }
    $msixArtefacts = @()
    $installerArtefacts = @()
    if (Test-Path -LiteralPath $packagingWindowsRoot) {
        foreach ($item in @(Get-ChildItem -LiteralPath $packagingWindowsRoot -Recurse -File -ErrorAction SilentlyContinue)) {
            $skipBuildOutput = $false
            foreach ($segment in @('obj', 'bin', 'artifacts')) {
                if ($item.FullName.IndexOf(($pathSeparator + $segment + $pathSeparator), [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $skipBuildOutput = $true }
            }
            if ($skipBuildOutput) { continue }
            if ($item.Name.EndsWith('.appxmanifest', [System.StringComparison]::OrdinalIgnoreCase)) { $msixArtefacts += $item.FullName }
            elseif (@('.wixproj', '.wxs', '.iss', '.nsi', '.msix') -contains $item.Extension.ToLowerInvariant()) { $installerArtefacts += $item.FullName }
        }
    }
    if (@($msixArtefacts).Count -gt 0) { $packagingProblems += ('the document says MSIX is not implemented, but ' + (@($msixArtefacts).Count).ToString() + ' appxmanifest file(s) exist [' + (Format-NameList @($msixArtefacts)) + ']') }
    if (@($installerArtefacts).Count -gt 0) { $packagingProblems += ('the document says no installer exists, but ' + (@($installerArtefacts).Count).ToString() + ' installer project file(s) exist [' + (Format-NameList @($installerArtefacts)) + ']') }
    $packagingCi = Join-Path $packagingWorkflows 'windows-ci.yml'
    if (-not (Test-Path -LiteralPath $packagingCi)) {
        $packagingProblems += ('windows-ci.yml is absent under ' + $packagingWorkflows + ', so the documented publish step cannot be verified')
    }
    else {
        $packagingCiText = [string](Get-Content -LiteralPath $packagingCi -Raw)
        if ($null -eq $packagingCiText) { $packagingCiText = '' }
        foreach ($expected in @('-o ./artifacts/portable', 'mediahub-windows-')) {
            if ($packagingCiText.IndexOf($expected, [System.StringComparison]::Ordinal) -lt 0) { $packagingProblems += ('windows-ci.yml no longer carries "' + $expected + '", which the document describes as the portable publish and artifact step') }
        }
    }
    if (@($packagingProblems).Count -eq 0) {
        Add-Check 'packaging-claims' 'PASS' 'the shipping state in PACKAGING.md still matches the repository: portable self-contained publish, no signing step, no MSIX manifest, no installer project outside build output (7 machine-readable lines)'
    }
    else {
        Add-Check 'packaging-claims' 'FAIL' ((@($packagingProblems) -join '; ') + ' - update PACKAGING.md section 0 and section 1 with the change')
    }
}

# --- 11. the published package must carry licence and notice text -------------------------------
# PACKAGING.md section 1 and SBOM.md section 4.1 both recorded that the portable package shipped
# zero licence/notice files. windows/tools/stage-licence-files.ps1 now copies a fixed set into
# <tree>/licences/ with a digest manifest, windows-ci.yml runs it right after publish, and the
# smoke check asserts the result. This check re-runs the staging script into a temporary tree, so
# the gate fails if the set shrinks, a digest stops matching the bytes, the script disappears or
# CI stops calling it.
$stagingScript = Resolve-ClaimPath -Path $StageScriptPath -Default 'windows/tools/stage-licence-files.ps1'
$stagingNames = @(
    'THIRD-PARTY-NOTICES.md',
    'THIRD-PARTY-NOTICES-FULL.md',
    'LGPL-COMPLIANCE.md',
    'LGPL-ENGINE-SWITCH.md',
    'component-licences.txt',
    'sbom.cdx.json'
)
$stagingExpected = @($stagingNames + 'MANIFEST.txt')
if (-not (Test-Path -LiteralPath $stagingScript)) {
    Add-Check 'package-licence-staging' 'FAIL' ('the staging script is absent at ' + $stagingScript + ' - the publish output ships with no licence or notice text without it')
}
else {
    $stagingProblems = @()
    $stagingTmp = Join-Path ([System.IO.Path]::GetTempPath()) ('mh-licence-stage-' + [System.Guid]::NewGuid().ToString('N'))
    $stagingTree = Join-Path $stagingTmp 'portable'
    try {
        [System.IO.Directory]::CreateDirectory($stagingTree) | Out-Null
        try { $stagingOutput = & $stagingScript -RepoRoot $RepoRoot -TargetDir $stagingTree 2>&1 }
        catch { $stagingProblems += ('the staging script failed: ' + $_.Exception.Message) }
        $stagingDir = Join-Path $stagingTree 'licences'
        foreach ($name in $stagingExpected) {
            if (-not (Test-Path -LiteralPath (Join-Path $stagingDir $name))) { $stagingProblems += ('the package would not carry licences/' + $name) }
        }
        if (Test-Path -LiteralPath $stagingDir) {
            foreach ($item in @(Get-ChildItem -LiteralPath $stagingDir -File)) {
                if ($stagingExpected -notcontains $item.Name) { $stagingProblems += ('licences/' + $item.Name + ' is staged but not part of the recorded set') }
            }
        }
        $stagingManifest = Join-Path $stagingDir 'MANIFEST.txt'
        if (Test-Path -LiteralPath $stagingManifest) {
            $manifestLines = @(Get-Content -LiteralPath $stagingManifest)
            $digestLines = @($manifestLines | Where-Object { $_ -match '^[0-9a-f]{64} ' })
            if (@($digestLines).Count -ne @($stagingNames).Count) { $stagingProblems += ('MANIFEST.txt has ' + @($digestLines).Count + ' digest line(s), expected ' + @($stagingNames).Count) }
            foreach ($name in $stagingNames) {
                $recorded = @($manifestLines | Where-Object { $_.EndsWith('/' + $name) })
                if (@($recorded).Count -eq 0) { $stagingProblems += ('MANIFEST.txt does not record licences/' + $name); continue }
                $expectedHash = ([regex]::Match([string]@($recorded)[0], '^[0-9a-f]{64}')).Value
                $actualHash = (Get-FileHash -LiteralPath (Join-Path $stagingDir $name) -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($expectedHash -ne $actualHash) { $stagingProblems += ('MANIFEST.txt records ' + $expectedHash + ' for licences/' + $name + ', but the staged bytes hash to ' + $actualHash) }
            }
        }
    }
    finally {
        if (Test-Path -LiteralPath $stagingTmp) { Remove-Item -LiteralPath $stagingTmp -Recurse -Force -ErrorAction SilentlyContinue }
    }
    $stagingCi = Join-Path $packagingWorkflows 'windows-ci.yml'
    if (-not (Test-Path -LiteralPath $stagingCi)) { $stagingProblems += 'windows-ci.yml is absent, so the staging step cannot be verified' }
    else {
        $stagingCiText = [string](Get-Content -LiteralPath $stagingCi -Raw)
        foreach ($expectedToken in @('stage-licence-files.ps1', 'licences/MANIFEST.txt')) {
            if ($stagingCiText.IndexOf($expectedToken, [System.StringComparison]::Ordinal) -lt 0) { $stagingProblems += ('windows-ci.yml no longer carries "' + $expectedToken + '", so the publish output is not guaranteed to stage or assert the licence files') }
        }
    }
    if (@($stagingProblems).Count -eq 0) {
        Add-Check 'package-licence-staging' 'PASS' ('the staging script puts ' + $stagingExpected.Count + ' licence/notice file(s) into the published tree with a matching digest manifest, and windows-ci.yml runs it before the smoke check')
    }
    else {
        Add-Check 'package-licence-staging' 'FAIL' ((@($stagingProblems) -join '; ') + ' - fix windows/tools/stage-licence-files.ps1 or the publish job together with PACKAGING.md section 0 and SBOM.md section 4.1')
    }
}

# --- 12. the LGPL compliance note component table and engine baseline --------------------------
# windows/docs/LGPL-COMPLIANCE.md is the document a distributor reads before shipping: it names both
# engines, quotes the GPL baseline digest, and lists the third-party licences. W38 fixed the
# underlying table and W36 pinned the candidate digest, but nothing compared the compliance note own
# component list with the resolved dependency graph, and nothing re-checked the GPL baseline claim
# after a pin update. Two checks close that.
function Get-NormalizedText {
    param([string] $Value)
    return (($Value -replace '\s+', ' ').Trim().ToLowerInvariant())
}
if (-not (Test-Path -LiteralPath $complianceFile)) {
    Add-Check 'lgpl-compliance-block' 'SKIP' ('the compliance note is absent at ' + $complianceFile)
    Add-Check 'lgpl-compliance-baseline' 'SKIP' ('the compliance note is absent at ' + $complianceFile)
}
else {
    $complianceText = [string](Get-Content -LiteralPath $complianceFile -Raw)

    # 12a. the machine-readable component table must describe exactly the resolved shipped set
    if (-not $report -or -not $report.lgplFlavour) {
        Add-Check 'lgpl-compliance-block' 'SKIP' 'no parsed report, so the component table cannot be compared with the resolved graph'
    }
    else {
        $resolvedComponents = [ordered]@{}
        $resolvedConflicts = @()
        foreach ($dependency in @($report.lgplFlavour.dependencies)) {
            if (-not $dependency) { continue }
            $componentName = [string]$dependency.component
            $componentLicence = [string]$dependency.license
            if ($componentName -eq '') { continue }
            if (-not $resolvedComponents.Contains($componentName)) { $resolvedComponents[$componentName] = $componentLicence }
            elseif ((Get-NormalizedText $resolvedComponents[$componentName]) -ne (Get-NormalizedText $componentLicence)) {
                $resolvedConflicts += ($componentName + ' resolves to two different licences: ' + $resolvedComponents[$componentName] + ' / ' + $componentLicence)
            }
        }
        $complianceBlockProblems = @()
        $blockBeginMarker = '<!-- BEGIN component-licence-table'
        $blockEndMarker = '<!-- END component-licence-table'
        $blockBeginIndex = $complianceText.IndexOf($blockBeginMarker, [System.StringComparison]::Ordinal)
        $blockEndIndex = $complianceText.IndexOf($blockEndMarker, [System.StringComparison]::Ordinal)
        $blockRowCount = 0
        if ($blockBeginIndex -lt 0 -or $blockEndIndex -lt $blockBeginIndex) {
            $complianceBlockProblems += 'the component-licence-table markers were not found (add the machine-readable block to section 5 of the compliance note)'
        }
        else {
            $blockBody = $complianceText.Substring($blockBeginIndex + $blockBeginMarker.Length, $blockEndIndex - ($blockBeginIndex + $blockBeginMarker.Length))
            $blockRows = [ordered]@{}
            foreach ($blockLine in @($blockBody -split '\r?\n')) {
                $trimmedLine = ([string]$blockLine).Trim()
                if ($trimmedLine -eq '') { continue }
                if ($trimmedLine.Contains('-->')) { continue }
                if ($trimmedLine -eq 'component | licence') { continue }
                $cells = @($trimmedLine -split '\s*\|\s*')
                if ($cells.Count -lt 2) { $complianceBlockProblems += ('the block line [' + $trimmedLine + '] is not a component-or-licence row'); continue }
                $rowName = ([string]$cells[0]).Trim()
                $rowLicence = ([string](@($cells[1..($cells.Count - 1)]) -join ' | ')).Trim()
                if ($blockRows.Contains($rowName)) { $complianceBlockProblems += ('the block lists ' + $rowName + ' twice') }
                $blockRows[$rowName] = $rowLicence
            }
            $blockRowCount = $blockRows.Count
            foreach ($componentName in @($resolvedComponents.Keys)) {
                if (-not $blockRows.Contains($componentName)) { $complianceBlockProblems += ('the block does not list ' + $componentName); continue }
                if ((Get-NormalizedText $blockRows[$componentName]) -ne (Get-NormalizedText $resolvedComponents[$componentName])) {
                    $complianceBlockProblems += ($componentName + ' is listed as [' + $blockRows[$componentName] + '] but the resolved graph says [' + $resolvedComponents[$componentName] + ']')
                }
            }
            foreach ($rowName in @($blockRows.Keys)) {
                if (-not $resolvedComponents.Contains($rowName)) { $complianceBlockProblems += ('the block lists ' + $rowName + ', which is not a shipped LGPL-flavour component') }
            }
            foreach ($conflict in $resolvedConflicts) { $complianceBlockProblems += $conflict }
        }
        if ($complianceBlockProblems.Count -eq 0) {
            Add-Check 'lgpl-compliance-block' 'PASS' ('the machine-readable component table in LGPL-COMPLIANCE.md matches the resolved dependency graph (' + $blockRowCount.ToString() + ' component row(s), ' + $resolvedComponents.Count.ToString() + ' resolved component(s))')
        }
        else {
            Add-Check 'lgpl-compliance-block' 'FAIL' ($complianceBlockProblems.Count.ToString() + ' problem(s): ' + (Format-NameList $complianceBlockProblems) + ' - update the component-licence-table block in windows/docs/LGPL-COMPLIANCE.md section 5')
        }
    }

    # 12b. section 1 must still quote the GPL baseline the report resolved
    $baselineBeginIndex = $complianceText.IndexOf('## 1. ', [System.StringComparison]::Ordinal)
    $baselineEndIndex = $complianceText.IndexOf('## 2. ', [System.StringComparison]::Ordinal)
    if (-not $report -or -not $report.nativeEngine) {
        Add-Check 'lgpl-compliance-baseline' 'SKIP' 'the report carries no nativeEngine record, so the GPL baseline claim cannot be compared'
    }
    elseif ($baselineBeginIndex -lt 0 -or $baselineEndIndex -le $baselineBeginIndex) {
        Add-Check 'lgpl-compliance-baseline' 'FAIL' 'the compliance note has no section 1 to quote the GPL baseline in'
    }
    else {
        $baselineText = $complianceText.Substring($baselineBeginIndex, $baselineEndIndex - $baselineBeginIndex)
        $engine = $report.nativeEngine
        $complianceBaselineProblems = @()
        $enginePath = [string]$engine.path
        $engineSha = [string]$engine.pinnedSha256
        $engineVersion = [string]$engine.version
        $enginePresent = [bool]$engine.observed.present
        $engineFormatted = ''
        foreach ($expectedToken in @($enginePath, $engineSha)) {
            if ($expectedToken -eq '') { continue }
            if ($baselineText.IndexOf($expectedToken, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
                $complianceBaselineProblems += ('section 1 does not quote ' + $expectedToken)
            }
        }
        if ($enginePresent) {
            $engineBytes = [int64]$engine.observed.bytes
            $engineFormatted = $engineBytes.ToString('N0', [System.Globalization.CultureInfo]::InvariantCulture)
            if ($baselineText.IndexOf($engineFormatted, [System.StringComparison]::Ordinal) -lt 0) {
                $complianceBaselineProblems += ('section 1 does not quote the resolved size ' + $engineFormatted + ' B')
            }
        }
        if ($engineVersion -ne '' -and $baselineText.IndexOf($engineVersion, [System.StringComparison]::Ordinal) -lt 0) {
            $complianceBaselineProblems += ('section 1 does not quote the pinned engine version ' + $engineVersion)
        }
        if ($complianceBaselineProblems.Count -eq 0) {
            $baselineDetail = $enginePath + ', ' + $engineSha.Substring(0, 8) + '... , ' + $engineVersion
            if ($engineFormatted -ne '') { $baselineDetail = $enginePath + ', ' + $engineFormatted + ' B, ' + $engineSha.Substring(0, 8) + '..., ' + $engineVersion }
            Add-Check 'lgpl-compliance-baseline' 'PASS' ('section 1 still quotes the GPL baseline the report resolved (' + $baselineDetail + ')')
        }
        else {
            Add-Check 'lgpl-compliance-baseline' 'FAIL' ($complianceBaselineProblems.Count.ToString() + ' problem(s): ' + (Format-NameList $complianceBaselineProblems) + ' - update windows/docs/LGPL-COMPLIANCE.md section 1 with the change')
        }
    }
}
# --- 13. the guard's own inventory must match what it emitted and what the documents claim ---
# The check count in LICENSE-SURFACE-CHECK.md and in README.md is a number a human had to bump by
# hand eight times (W32 through W41). The explicit inventory below is compared against both of them,
# against this run's emitted ids, and against the document text, so forgetting one of the three is a FAIL.
$declaredCheckIds = @(
    'projects-discovered',
    'notices-exist',
    'notices-libmpv-engine',
    'notices-engine-licence',
    'notices-lgpl-dependency-section',
    'notices-lgpl-evidence',
    'notices-reproduce-command',
    'notices-points-here',
    'lgpl-compliance-exists',
    'lgpl-compliance-dynamic-linking',
    'lgpl-compliance-replaceable-dll',
    'lgpl-compliance-source-offer',
    'sbom-shape',
    'sbom-engine-component',
    'sbom-lgpl-flavour-property',
    'report-exists',
    'report-no-violations',
    'report-project-count',
    'report-engine-pin',
    'report-lgpl-dependency-count',
    'lgpl-licence-gaps',
    'sbom-inventory',
    'sbom-inventory-licences',
    'sbom-source-revision',
    'sdk-pin-matches-report',
    'notices-inventory',
    'notices-declarations',
    'lgpl-candidate-hashes',
    'lgpl-candidate-claims',
    'vulkan-claims',
    'lgpl-licence-table',
    'packaging-claims',
    'package-licence-staging',
    'lgpl-compliance-block',
    'lgpl-compliance-baseline',
    'engine-flavour-default',
    'check-inventory'
)
if (-not (Test-Path -LiteralPath $licenceDocFile) -or -not (Test-Path -LiteralPath $readmeFile)) {
    $inventoryMissing = @()
    if (-not (Test-Path -LiteralPath $licenceDocFile)) { $inventoryMissing += $licenceDocFile }
    if (-not (Test-Path -LiteralPath $readmeFile)) { $inventoryMissing += $readmeFile }
    Add-Check 'check-inventory' 'SKIP' ('cannot read ' + (Format-NameList $inventoryMissing) + ', so the declared inventory has nothing to compare against')
}
else {
    $inventoryDocText = [string](Get-Content -LiteralPath $licenceDocFile -Raw)
    $inventoryReadmeText = [string](Get-Content -LiteralPath $readmeFile -Raw)
    $declaredList = @($declaredCheckIds)
    $declaredCount = $declaredList.Count
    $inventoryProblems = @()
    $inventoryDuplicates = @($declaredList | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    if ($inventoryDuplicates.Count -gt 0) {
        $inventoryProblems += ('the declared inventory lists ' + (Format-NameList $inventoryDuplicates) + ' more than once')
    }
    $docCountMatch = [regex]::Match($inventoryDocText, '(\d+)\s*\u9879\u68c0\u67e5\u5206')
    if (-not $docCountMatch.Success) {
        $inventoryProblems += 'LICENSE-SURFACE-CHECK.md no longer states how many checks it documents'
    }
    elseif ([int]$docCountMatch.Groups[1].Value -ne $declaredCount) {
        $inventoryProblems += ('LICENSE-SURFACE-CHECK.md claims ' + $docCountMatch.Groups[1].Value + ' check(s) but the guard declares ' + $declaredCount)
    }
    $readmeCountMatch = [regex]::Match($inventoryReadmeText, '\u73b0\u4e3a\s*\*\*(\d+)\s*\u9879\*\*')
    if (-not $readmeCountMatch.Success) {
        $inventoryProblems += 'README.md no longer states how many checks the licence surface guard runs'
    }
    elseif ([int]$readmeCountMatch.Groups[1].Value -ne $declaredCount) {
        $inventoryProblems += ('README.md claims ' + $readmeCountMatch.Groups[1].Value + ' check(s) but the guard declares ' + $declaredCount)
    }
    $inventoryUndocumented = @($declaredList | Where-Object { -not [regex]::IsMatch($inventoryDocText, '(?<![A-Za-z0-9\-])' + [regex]::Escape($_) + '(?![A-Za-z0-9\-])') })
    if ($inventoryUndocumented.Count -gt 0) {
        $inventoryProblems += ('LICENSE-SURFACE-CHECK.md never names ' + (Format-NameList $inventoryUndocumented))
    }
    $inventoryUndeclared = @($checks | ForEach-Object { $_.id } | Where-Object { $declaredList -notcontains $_ } | Sort-Object -Unique)
    if ($inventoryUndeclared.Count -gt 0) {
        $inventoryProblems += ('this run emitted ' + (Format-NameList $inventoryUndeclared) + ', which the declared inventory does not list')
    }
    if ($inventoryProblems.Count -eq 0) {
        Add-Check 'check-inventory' 'PASS' ($declaredCount.ToString() + ' declared check(s): this run emitted nothing undeclared, LICENSE-SURFACE-CHECK.md names every id on its own, and both it and README.md claim the same count')
    }
    else {
        Add-Check 'check-inventory' 'FAIL' ($inventoryProblems.Count.ToString() + ' problem(s): ' + (Format-NameList $inventoryProblems) + ' - add the new check to $declaredCheckIds in windows/tools/check-licence-surface.ps1 and move the count in LICENSE-SURFACE-CHECK.md and README.md with it')
    }
}

# --- 14. the default engine flavour must stay the GPL baseline --------------------------------

$flavourDocFile = Resolve-ClaimPath -Path $LgplSwitchDocPath -Default 'windows/docs/LGPL-ENGINE-SWITCH.md'
$flavourProjectFile = Resolve-ClaimPath -Path $PlayerProjectPath -Default 'windows/src/MediaHub.Player.Mpv/MediaHub.Player.Mpv.csproj'

if (-not (Test-Path -LiteralPath $flavourDocFile)) {
    Add-Check 'engine-flavour-default' 'SKIP' ('cannot read ' + $flavourDocFile + ', so the switch document has no claim to compare with the build')
}
else {
    $flavourProblems = @()
    if (-not (Test-Path -LiteralPath $flavourProjectFile)) {
        $flavourProblems += ('the player project is absent at ' + $flavourProjectFile)
    }
    else {
        $flavourProjectText = Get-Content -LiteralPath $flavourProjectFile -Raw
        $quoteChar = [string][char]39
        $defaultPattern = '<LibMpvFlavour\s+Condition="' + $quoteChar + '\$\(LibMpvFlavour\)' + $quoteChar + '\s*==\s*' + $quoteChar + $quoteChar + '">\s*gpl\s*</LibMpvFlavour>'
        if (-not [regex]::IsMatch($flavourProjectText, $defaultPattern)) {
            $flavourProblems += 'the player project no longer defaults LibMpvFlavour to gpl'
        }
        foreach ($directoryClaim in @('native\libmpv', 'engine\lgpl\dist')) {
            if ($flavourProjectText.IndexOf($directoryClaim, [System.StringComparison]::Ordinal) -lt 0) {
                $flavourProblems += ('the player project no longer maps a flavour to ' + $directoryClaim)
            }
        }
        if ($flavourProjectText.IndexOf('Unknown LibMpvFlavour', [System.StringComparison]::Ordinal) -lt 0) {
            $flavourProblems += 'the player project no longer rejects an unknown LibMpvFlavour value'
        }
    }

    $switchPattern = 'LibMpvFlavour\s*[=:]\s*[^\r\n]{0,2}lgpl'
    $candidateSwitchers = @()
    foreach ($project in @($projectFiles)) {
        if ($project.Name -ne 'MediaHub.Player.Mpv.csproj' -and $project.FullName -ne $flavourProjectFile -and (Test-Path -LiteralPath $project.FullName)) {
            $projectText = Get-Content -LiteralPath $project.FullName -Raw
            if ([regex]::IsMatch($projectText, $switchPattern)) { $candidateSwitchers += $project.Name }
        }
    }
    if (Test-Path -LiteralPath $packagingWorkflows) {
        foreach ($workflow in @(Get-ChildItem -LiteralPath $packagingWorkflows -File -Include *.yml, *.yaml -Recurse -ErrorAction SilentlyContinue)) {
            $workflowText = Get-Content -LiteralPath $workflow.FullName -Raw
            if ([regex]::IsMatch($workflowText, $switchPattern)) { $candidateSwitchers += $workflow.Name }
        }
    }
    if ($candidateSwitchers.Count -gt 0) {
        $flavourProblems += ('something else already builds the LGPL candidate: ' + (Format-NameList (Sort-Object -InputObject $candidateSwitchers -Unique)))
    }

    $flavourDocText = Get-Content -LiteralPath $flavourDocFile -Raw
    $blockStart = $flavourDocText.IndexOf('<!-- BEGIN engine-flavour-status', [System.StringComparison]::Ordinal)
    $blockEnd = $flavourDocText.IndexOf('<!-- END engine-flavour-status', [System.StringComparison]::Ordinal)
    if ($blockStart -lt 0 -or $blockEnd -le $blockStart) {
        $flavourProblems += 'LGPL-ENGINE-SWITCH.md carries no engine-flavour-status block'
    }
    else {
        $blockText = $flavourDocText.Substring($blockStart, $blockEnd - $blockStart)
        foreach ($expected in @('default-flavour: gpl-baseline', 'candidate-flavour: lgpl-candidate-not-default', 'switch-flag: -p:LibMpvFlavour=lgpl')) {
            if ($blockText.IndexOf($expected, [System.StringComparison]::Ordinal) -lt 0) {
                $flavourProblems += ('the status block does not say ' + $expected)
            }
        }
    }
    foreach ($docClaim in @('windows/native/libmpv', 'windows/engine/lgpl/dist')) {
        if ($flavourDocText.IndexOf($docClaim, [System.StringComparison]::Ordinal) -lt 0) {
            $flavourProblems += ('LGPL-ENGINE-SWITCH.md no longer names ' + $docClaim)
        }
    }

    if ($flavourProblems.Count -eq 0) {
        Add-Check 'engine-flavour-default' 'PASS' 'the default flavour is still gpl-baseline in the player project, nothing else builds the LGPL candidate, and LGPL-ENGINE-SWITCH.md still declares it (3 machine-readable lines)'
    }
    else {
        Add-Check 'engine-flavour-default' 'FAIL' (($flavourProblems -join '; ') + ' - update windows/docs/LGPL-ENGINE-SWITCH.md and the player project together')
    }
}

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
