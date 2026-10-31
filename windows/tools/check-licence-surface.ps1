#Requires -Version 7.0
<#
.SYNOPSIS
    Static sanity check that the licence surface still matches the repository.

.DESCRIPTION
    Twenty things rot silently in a licence deliverable:
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
         graph, or section 1 stops quoting the GPL baseline the report resolved.
     13. the guard's own inventory drifts: a check is added and never listed, the document or the
         README keeps claiming the old number, or a declared check id is dropped from the document.
      14. the default engine flavour silently leaves the GPL baseline: the player project starts
          defaulting to the LGPL candidate, another project or workflow builds it, or
          windows/docs/LGPL-ENGINE-SWITCH.md stops declaring which flavour is default.
      15. the licence decision record rots: the release-blocker status or the engine licence label
          is removed, the record claims again that the notice files mislabel the engine, or the
          notice files stop saying GPL-3.0-or-later for libmpv.
      16. the packaging ADR rots: the app project stops quoting the packaging properties the ADR
          decided on, the record claims zero licence files in the package again, or the native libmpv
          tree stops being ignored and fetched by the tool the ADR names.

      17. the pinned engine digests go stale in prose: the archive digest or the extracted dll digest
          declared by fetch-libmpv.ps1 moves, and the documents that quote both keep describing the
          old bytes.

      18. the capability map rots: its shipping and flavour status block is removed or
          reworded, the test row goes back to quoting a fixed count, or the map claims a
          shipping state PACKAGING.md no longer records.

      19. the quiet-run safety contract rots: the acceptance runbook stops promising the idle
          threshold and the QUNS allow-list it documented, the runner's defaults drift, the
          watchdog stops polling for input, or Start-MhApp loses the abort marker that keeps a
          window from being put back on screen after the user came back.

      20. the resume-floor contract rots: the mock's measured thresholds drift from the values
          the contract document publishes, the tests that pin the mock stop expanding to the
          number the document declares, or the percentage-vs-seconds decision is replaced by a

    This script checks those twenty surfaces without touching the network, the build, or the
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

.PARAMETER LicenceDecisionDocPath
    The licence decision record that states the release blocker. Default:
    windows/docs/licensing/LICENCE-DECISION.md. The licence-decision-claims check compares its
    machine-readable status block with the notice files and rejects a stale label claim.

.PARAMETER HandwrittenNoticesPath
    The hand-written notices document next to the generated one. Default:
    windows/THIRD-PARTY-NOTICES.md. Both files are read when the engine label is cross-checked.

.PARAMETER AdrPackagingPath
    The packaging ADR that decided the shipping form. Default:
    windows/docs/adr/ADR-W004-packaging-and-distribution.md. The adr-packaging-claims check compares
    its machine-readable status block and its quoted packaging properties with the app project.

.PARAMETER GitIgnorePath
    The repository ignore file that must keep excluding the native libmpv tree. Default: .gitignore.


.PARAMETER FetchScriptPath
    The script that owns the pinned engine digests (archive sha256, extracted libmpv-2.dll sha256).
    Default: windows/tools/fetch-libmpv.ps1. The engine-pins-claims check reads the pins from it.

.PARAMETER PinsNoticesPath
    Default: windows/THIRD-PARTY-NOTICES.md - one of the documents that must quote both pins.

.PARAMETER PinsAdrPath
    Default: windows/docs/adr/ADR-W004-packaging-and-distribution.md - the packaging ADR, which also
    quotes the pinned digests.

.PARAMETER PinsReproPath
    Default: windows/docs/licensing/REPRODUCIBLE-BUILD.md - the reproducibility note, which also
    quotes the pinned digests.

.PARAMETER CapabilityMapPath
    The capability map that summarises what the Windows client can do. Default:
    windows/docs/CAPABILITY-MAP.md. The capability-map-claims check compares its status block
    with PACKAGING.md and with the player project it summarises.

.PARAMETER QuietRunbookPath
    The quiet-run acceptance runbook. Default: windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md. The
    quiet-run-claims check compares its status block with the runner and the two PowerShell
    modules that actually implement the gate.

.PARAMETER QuietRunnerPath
    The runner that owns the idle gate. Default: windows/tools/acceptance/run-quiet.ps1.

.PARAMETER QuietModulePath
    The module with the idle/QUNS/watchdog primitives. Default: windows/tools/acceptance/lib/MhQuiet.psm1.

.PARAMETER UiaModulePath
    The module whose Start-MhApp refuses to open a window once the run was aborted. Default:
    windows/tools/acceptance/lib/MhUia.psm1.

.PARAMETER ResumeFloorDocPath
    The contract document that publishes the mock's measured resume floors. Default:
    windows/docs/W8-RESUME-FLOOR-CONTRACT.md. The resume-floor-claims check compares its
    status block and its declared case count with the mock and its tests.

.PARAMETER MockServerPath
    The emulated MediaBrowser server that implements the floor. Default:
    windows/tests/MediaHub.TestSupport/MockMediaServer.cs.

.PARAMETER ResumeFloorTestPath
    The contract test whose [Fact] and [InlineData] attributes are counted. Default:
    windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs.

.PARAMETER LogFileDocPath
    The document that publishes the rolling diagnostic-log limits. Default:
    windows/docs/W14-DIAGNOSTIC-LOG-FILES.md. The log-file-claims check compares its status
    block with the sink, the log service, the composition root, and the sink's own tests.

.PARAMETER LogFileSinkPath
    The rolling-file sink itself. Default: windows/src/MediaHub.Net/LogFileSink.cs.

.PARAMETER LogServicePath
    The in-memory ring that feeds the sink. Default: windows/src/MediaHub.Net/LogService.cs.

.PARAMETER AppServicesPath
    The composition root that points the sink at %LOCALAPPDATA%\MediaHub\logs. Default:
    windows/src/MediaHub.App/AppServices.cs.

.PARAMETER DocAuditPath
    The dated document-consistency audit. Default: windows/docs/W12-DOC-AUDIT.md. The
    doc-audit-snapshot-claims check keeps it labelled as a snapshot taken at one commit.

.PARAMETER LogFileTestPath
    The tests that pin the sink's behaviour. Default:
    windows/tests/MediaHub.Tests/LogFileSinkTests.cs.

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
    [string] $LicenceDecisionDocPath,
    [string] $HandwrittenNoticesPath,
    [string] $AdrPackagingPath,
    [string] $GitIgnorePath,
    [string] $FetchScriptPath,
    [string] $PinsNoticesPath,
    [string] $PinsAdrPath,
    [string] $PinsReproPath,
    [string] $QuietRunbookPath,
    [string] $QuietRunnerPath,
    [string] $QuietModulePath,
    [string] $UiaModulePath,
    [string] $CapabilityMapPath,
    [string] $ResumeFloorDocPath,
    [string] $MockServerPath,
    [string] $ResumeFloorTestPath,
    [string] $LogFileDocPath,
    [string] $LogFileSinkPath,
    [string] $LogServicePath,
    [string] $AppServicesPath,
    [string] $LogFileTestPath,
    [string] $DocAuditPath,
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
$licenceDecisionFile = Resolve-RepoPath -Path $LicenceDecisionDocPath -Default 'windows/docs/licensing/LICENCE-DECISION.md'
$handwrittenNoticesFile = Resolve-RepoPath -Path $HandwrittenNoticesPath -Default 'windows/THIRD-PARTY-NOTICES.md'
$adrPackagingFile = Resolve-RepoPath -Path $AdrPackagingPath -Default 'windows/docs/adr/ADR-W004-packaging-and-distribution.md'
$gitIgnoreFile = Resolve-RepoPath -Path $GitIgnorePath -Default '.gitignore'
$quietRunbookFile = Resolve-RepoPath -Path $QuietRunbookPath -Default 'windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md'
$quietRunnerFile = Resolve-RepoPath -Path $QuietRunnerPath -Default 'windows/tools/acceptance/run-quiet.ps1'
$quietModuleFile = Resolve-RepoPath -Path $QuietModulePath -Default 'windows/tools/acceptance/lib/MhQuiet.psm1'
$uiaModuleFile = Resolve-RepoPath -Path $UiaModulePath -Default 'windows/tools/acceptance/lib/MhUia.psm1'
$resumeFloorDocFile = Resolve-RepoPath -Path $ResumeFloorDocPath -Default 'windows/docs/W8-RESUME-FLOOR-CONTRACT.md'
$mockServerFile = Resolve-RepoPath -Path $MockServerPath -Default 'windows/tests/MediaHub.TestSupport/MockMediaServer.cs'
$resumeFloorTestFile = Resolve-RepoPath -Path $ResumeFloorTestPath -Default 'windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs'
$logFileDocFile = Resolve-RepoPath -Path $LogFileDocPath -Default 'windows/docs/W14-DIAGNOSTIC-LOG-FILES.md'
$logFileSinkFile = Resolve-RepoPath -Path $LogFileSinkPath -Default 'windows/src/MediaHub.Net/LogFileSink.cs'
$logServiceFile = Resolve-RepoPath -Path $LogServicePath -Default 'windows/src/MediaHub.Net/LogService.cs'
$appServicesFile = Resolve-RepoPath -Path $AppServicesPath -Default 'windows/src/MediaHub.App/AppServices.cs'
$logFileTestFile = Resolve-RepoPath -Path $LogFileTestPath -Default 'windows/tests/MediaHub.Tests/LogFileSinkTests.cs'
$docAuditFile = Resolve-RepoPath -Path $DocAuditPath -Default 'windows/docs/W12-DOC-AUDIT.md'

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
    'licence-decision-claims',
    'adr-packaging-claims',
    'engine-pins-claims',
    'capability-map-claims',
    'quiet-run-claims',
    'resume-floor-claims',
    'log-file-claims',
    'doc-audit-snapshot-claims',
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

# --- 15. the licence decision record must stay honest ---
$decisionProblems = @()
if (-not (Test-Path -LiteralPath $licenceDecisionFile)) {
    Add-Check 'licence-decision-claims' 'SKIP' ('cannot read ' + $licenceDecisionFile + ', so the release-blocker record has nothing to compare against')
}
else {
    $decisionText = Get-Content -LiteralPath $licenceDecisionFile -Raw
    $decisionStart = $decisionText.IndexOf('<!-- BEGIN licence-decision-status', [System.StringComparison]::Ordinal)
    $decisionEnd = $decisionText.IndexOf('<!-- END licence-decision-status', [System.StringComparison]::Ordinal)
    if ($decisionStart -lt 0 -or $decisionEnd -le $decisionStart) {
        $decisionProblems += 'LICENCE-DECISION.md carries no licence-decision-status block'
    }
    else {
        $decisionBlock = $decisionText.Substring($decisionStart, $decisionEnd - $decisionStart)
        foreach ($expected in @('status: open-release-blocker', 'shipped-engine-licence: GPL-3.0-or-later', 'repo-label-mismatch: resolved-2026-10-31')) {
            if ($decisionBlock.IndexOf($expected, [System.StringComparison]::Ordinal) -lt 0) {
                $decisionProblems += ('the status block does not say ' + $expected)
            }
        }
    }
    foreach ($stalePattern in @('(?i)the repository says GPL-2\.0-or-later', '(?i)lines\s+10\s*[-\u2013]\s*11\s+state')) {
        if ([regex]::IsMatch($decisionText, $stalePattern)) {
            $decisionProblems += ('LICENCE-DECISION.md still asserts the stale claim matching ' + $stalePattern)
        }
    }
    foreach ($noticesCandidate in @($noticesFile, $handwrittenNoticesFile)) {
        if (-not (Test-Path -LiteralPath $noticesCandidate)) {
            $decisionProblems += ('the notices file is absent: ' + $noticesCandidate)
            continue
        }
        $noticeText = Get-Content -LiteralPath $noticesCandidate -Raw
        $engineRows = @($noticeText -split "`r?`n" | Where-Object { $_.IndexOf('libmpv (mpv)', [System.StringComparison]::Ordinal) -ge 0 })
        if ($engineRows.Count -eq 0) {
            $decisionProblems += ('no libmpv (mpv) row in ' + [System.IO.Path]::GetFileName($noticesCandidate))
        }
        elseif (($engineRows -join ' ').IndexOf('GPL-3.0-or-later', [System.StringComparison]::Ordinal) -lt 0) {
            $decisionProblems += ('the libmpv row in ' + [System.IO.Path]::GetFileName($noticesCandidate) + ' no longer says GPL-3.0-or-later')
        }
    }
    if ($decisionProblems.Count -eq 0) {
        Add-Check 'licence-decision-claims' 'PASS' 'LICENCE-DECISION.md still declares the open release blocker and the GPL-3.0-or-later engine label, and both notice files still label libmpv GPL-3.0-or-later (3 machine-readable lines)'
    }
    else {
        Add-Check 'licence-decision-claims' 'FAIL' (($decisionProblems -join '; ') + ' - update windows/docs/licensing/LICENCE-DECISION.md, windows/THIRD-PARTY-NOTICES.md and windows/docs/THIRD-PARTY-NOTICES.md together')
    }
}


# --- 16. the packaging ADR must still describe the repository --------------------------------
# windows/docs/adr/ADR-W004-packaging-and-distribution.md decided the shipping form in 2026-10-14
# and quotes the four packaging properties, the 'native libmpv is ignored and fetched' mechanism
# and 'zero licence files in the package'. W40 made the last one false and nothing compared the
# record with the project it describes.
$adrFile = Resolve-ClaimPath -Path $AdrPackagingPath -Default 'windows/docs/adr/ADR-W004-packaging-and-distribution.md'
$gitIgnoreFile = Resolve-ClaimPath -Path $GitIgnorePath -Default '.gitignore'
$fetchScriptFile = Join-Path $RepoRoot 'windows/tools/fetch-libmpv.ps1'

if (-not (Test-Path -LiteralPath $adrFile)) {
    Add-Check 'adr-packaging-claims' 'SKIP' ('cannot read ' + $adrFile + ', so the packaging ADR has nothing to compare against')
}
else {
    $adrProblems = @()
    $adrText = Get-Content -LiteralPath $adrFile -Raw
    $adrStart = $adrText.IndexOf('<!-- BEGIN adr-packaging-status', [System.StringComparison]::Ordinal)
    $adrEnd = $adrText.IndexOf('<!-- END adr-packaging-status', [System.StringComparison]::Ordinal)
    if ($adrStart -lt 0 -or $adrEnd -le $adrStart) {
        $adrProblems += 'ADR-W004 carries no adr-packaging-status block'
    }
    else {
        $adrBlock = $adrText.Substring($adrStart, $adrEnd - $adrStart)
        foreach ($expected in @('packaging-form: unpackaged-self-contained-portable', 'msix: optional-not-implemented', 'signing: blocked-no-certificate', 'licence-files-in-package: staged-by-ci-since-w40', 'native-libmpv: gitignored-and-fetched-by-tool')) {
            if ($adrBlock.IndexOf($expected, [System.StringComparison]::Ordinal) -lt 0) {
                $adrProblems += ('the status block does not say ' + $expected)
            }
        }
    }
    foreach ($stalePattern in @('(?i)不含任何许可证/声明文件', '(?i)522 个文件里 0 个')) {
        if ([regex]::IsMatch($adrText, $stalePattern)) {
            $adrProblems += ('ADR-W004 still asserts the stale claim matching ' + $stalePattern)
        }
    }
    if (-not (Test-Path -LiteralPath $packagingAppProject)) {
        $adrProblems += ('the app project is absent at ' + $packagingAppProject)
    }
    else {
        $adrCsproj = Get-Content -LiteralPath $packagingAppProject -Raw
        foreach ($propertyName in @('WindowsPackageType', 'WindowsAppSDKSelfContained', 'SelfContained', 'RuntimeIdentifier')) {
            $csprojMatch = [regex]::Match($adrCsproj, '<' + $propertyName + '>([^<]*)</' + $propertyName + '>')
            if (-not $csprojMatch.Success) {
                $adrProblems += ('the app project no longer sets ' + $propertyName)
            }
            else {
                $quoted = '<' + $propertyName + '>' + $csprojMatch.Groups[1].Value + '</' + $propertyName + '>'
                if ($adrText.IndexOf($quoted, [System.StringComparison]::Ordinal) -lt 0) {
                    $adrProblems += ('ADR-W004 does not quote ' + $quoted + ' while the app project sets it')
                }
            }
        }
    }
    if (-not (Test-Path -LiteralPath $gitIgnoreFile)) {
        $adrProblems += ('the ignore file is absent at ' + $gitIgnoreFile)
    }
    elseif ((Get-Content -LiteralPath $gitIgnoreFile -Raw).IndexOf('windows/native/libmpv/', [System.StringComparison]::Ordinal) -lt 0) {
        $adrProblems += 'the ignore file no longer excludes windows/native/libmpv/'
    }
    if (-not (Test-Path -LiteralPath $fetchScriptFile)) {
        $adrProblems += ('the fetch script named by the ADR is absent at ' + $fetchScriptFile)
    }
    if ($adrProblems.Count -eq 0) {
        Add-Check 'adr-packaging-claims' 'PASS' 'ADR-W004 still describes the repository: the status block matches, the four packaging properties are quoted exactly as the app project sets them, the licence-file claim is current, and the native libmpv tree stays ignored and fetched (5 machine-readable lines)'
    }
    else {
        Add-Check 'adr-packaging-claims' 'FAIL' (($adrProblems -join '; ') + ' - update windows/docs/adr/ADR-W004-packaging-and-distribution.md and the app project together')
    }
}



# --- 17. the pinned engine digests must still be quoted where the documents cite them ------------
# windows/tools/fetch-libmpv.ps1 pins the archive digest and the extracted libmpv-2.dll digest and
# refuses to install anything else. Three documents quote both digests plus the archive name.
# Bumping a pin without moving the prose left those quotes stale and nothing compared them.
$fetchPinFile = Resolve-ClaimPath -Path $FetchScriptPath -Default 'windows/tools/fetch-libmpv.ps1'
$pinSites = @(
    @{ Name = 'windows/THIRD-PARTY-NOTICES.md'; Path = $PinsNoticesPath; Default = 'windows/THIRD-PARTY-NOTICES.md' },
    @{ Name = 'windows/docs/adr/ADR-W004-packaging-and-distribution.md'; Path = $PinsAdrPath; Default = 'windows/docs/adr/ADR-W004-packaging-and-distribution.md' },
    @{ Name = 'windows/docs/licensing/REPRODUCIBLE-BUILD.md'; Path = $PinsReproPath; Default = 'windows/docs/licensing/REPRODUCIBLE-BUILD.md' }
)

$pinProblems = @()
if (-not (Test-Path -LiteralPath $fetchPinFile)) {
    $pinProblems += ('the fetch script that owns the engine digests is absent at ' + $fetchPinFile)
    Add-Check 'engine-pins-claims' 'FAIL' (($pinProblems -join '; ') + ' - restore windows/tools/fetch-libmpv.ps1 or point -FetchScriptPath at it')
}
else {
    $pinText = Get-Content -LiteralPath $fetchPinFile -Raw
    $assetNamePattern = '(?m)^\s*' + [regex]::Escape('$AssetName') + "\s*=\s*'([^']+)'"
    $assetShaPattern = '(?m)^\s*' + [regex]::Escape('$AssetSha256') + "\s*=\s*'([0-9a-fA-F]{64})'"
    $dllShaPattern = '(?m)^\s*' + [regex]::Escape('$DllSha256') + "\s*=\s*'([0-9a-fA-F]{64})'"
    $assetNameMatch = [regex]::Match($pinText, $assetNamePattern)
    $assetShaMatch = [regex]::Match($pinText, $assetShaPattern)
    $dllShaMatch = [regex]::Match($pinText, $dllShaPattern)
    if (-not $assetNameMatch.Success -or -not $assetShaMatch.Success -or -not $dllShaMatch.Success) {
        $pinProblems += 'the fetch script no longer declares $AssetName, $AssetSha256 and $DllSha256 as single-quoted literals'
    }
    else {
        $assetName = $assetNameMatch.Groups[1].Value
        $assetSha = $assetShaMatch.Groups[1].Value.ToLowerInvariant()
        $dllSha = $dllShaMatch.Groups[1].Value.ToLowerInvariant()
        foreach ($site in $pinSites) {
            $siteFile = Resolve-ClaimPath -Path $site.Path -Default $site.Default
            if (-not (Test-Path -LiteralPath $siteFile)) {
                $pinProblems += ('a document that cites the engine digests is absent: ' + $site.Name + ' (' + $siteFile + ')')
                continue
            }
            $siteText = (Get-Content -LiteralPath $siteFile -Raw).ToLowerInvariant()
            $probes = @(
                @{ What = ('the archive digest ' + $assetSha.Substring(0, 12) + '...'); Needle = $assetSha },
                @{ What = ('the extracted dll digest ' + $dllSha.Substring(0, 12) + '...'); Needle = $dllSha },
                @{ What = ('the archive name ' + $assetName); Needle = $assetName.ToLowerInvariant() }
            )
            foreach ($probe in $probes) {
                if ($siteText.IndexOf($probe.Needle, [System.StringComparison]::Ordinal) -lt 0) {
                    $pinProblems += ($site.Name + ' no longer quotes ' + $probe.What)
                }
            }
        }
    }
    if ($pinProblems.Count -eq 0) {
        Add-Check 'engine-pins-claims' 'PASS' ('the archive digest, the extracted dll digest and the archive name pinned by fetch-libmpv.ps1 are still quoted by all 3 citing document(s)')
    }
    else {
        Add-Check 'engine-pins-claims' 'FAIL' (($pinProblems -join '; ') + ' - move windows/tools/fetch-libmpv.ps1 and the documents that quote its pins together')
    }
}
# --- 18. the capability map must still describe the repository --------------------------------
$capabilityMapFile = Resolve-ClaimPath -Path $CapabilityMapPath -Default 'windows/docs/CAPABILITY-MAP.md'
if (-not (Test-Path -LiteralPath $capabilityMapFile)) {
    Add-Check 'capability-map-claims' 'SKIP' ('cannot read ' + $capabilityMapFile + ', so the capability map has nothing to verify')
}
else {
    $capabilityText = [string](Get-Content -LiteralPath $capabilityMapFile -Raw)
    $capabilityProblems = @()
    if ($capabilityText.IndexOf('<!-- BEGIN capability-map-status', [System.StringComparison]::Ordinal) -lt 0 -or $capabilityText.IndexOf('<!-- END capability-map-status', [System.StringComparison]::Ordinal) -lt 0) {
        $capabilityProblems += 'windows/docs/CAPABILITY-MAP.md carries no capability-map-status block'
    }
    foreach ($line in @(
        'capability-map-status: checked-by-ci',
        'test-count-claim: see-ci',
        'msix: not-implemented',
        'signing: blocked-no-certificate',
        'clean-machine-install: not-run',
        'engine-flavour-default: gpl',
        'engine-lgpl-candidate: built-not-switched',
        'licence-files-in-package: staged-by-ci',
        'release-blocker: gpl-obligation'
    )) {
        if ($capabilityText.IndexOf($line, [System.StringComparison]::Ordinal) -lt 0) {
            $capabilityProblems += ('the status block does not say ' + $line)
        }
    }
    if ([regex]::IsMatch($capabilityText, '(?i)\b\d+\s+tests?\s*/\s*\d+\s+failed')) {
        $capabilityProblems += 'the test row quotes a fixed test count, which goes stale the next time a test is added'
    }
    if (Test-Path -LiteralPath $packagingDocFile) {
        $capabilityPackagingText = [string](Get-Content -LiteralPath $packagingDocFile -Raw)
        foreach ($needle in @('msix: not-implemented', 'signing-status: blocked-no-certificate', 'clean-machine-install: not-run', 'licence-files-in-package: staged-by-ci')) {
            if ($capabilityPackagingText.IndexOf($needle, [System.StringComparison]::Ordinal) -lt 0) {
                $capabilityProblems += ('PACKAGING.md no longer says ' + $needle + ' for the same claim')
            }
        }
    }
    else {
        $capabilityProblems += ('cannot read ' + $packagingDocFile + ' to cross-check the shipping claims')
    }
    if (Test-Path -LiteralPath $flavourProjectFile) {
        $capabilityFlavourText = [string](Get-Content -LiteralPath $flavourProjectFile -Raw)
        if (-not [regex]::IsMatch($capabilityFlavourText, 'native[\\/]libmpv')) {
            $capabilityProblems += 'the player project no longer defaults to the GPL baseline tree, so engine-flavour-default: gpl is wrong'
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'windows/engine/lgpl/dist/libmpv-2.dll'))) {
        $capabilityProblems += 'the LGPL candidate binary is gone, so engine-lgpl-candidate: built-not-switched is unsupported'
    }
    if ($capabilityProblems.Count -eq 0) {
        Add-Check 'capability-map-claims' 'PASS' 'the capability map still describes the repository: 9 machine-readable lines, shipping claims agreeing with PACKAGING.md, the GPL baseline default in the player project and the built LGPL candidate'
    }
    else {
        Add-Check 'capability-map-claims' 'FAIL' (($capabilityProblems -join '; ') + ' - update windows/docs/CAPABILITY-MAP.md section 0 and the row it summarises')
    }
}
# --- 19. the quiet-run safety contract ---
$quietProblems = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path -LiteralPath $quietRunbookFile)) {
    Add-Check 'quiet-run-claims' 'SKIP' 'no quiet-run runbook to compare against (windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md)'
}
else {
    $quietRunbookText = Get-Content -LiteralPath $quietRunbookFile -Raw
    $beginAt = $quietRunbookText.IndexOf('<!-- BEGIN quiet-run-status', [System.StringComparison]::Ordinal)
    $endAt = $quietRunbookText.IndexOf('<!-- END quiet-run-status', [System.StringComparison]::Ordinal)
    if ($beginAt -lt 0 -or $endAt -lt 0 -or $endAt -le $beginAt) {
        $quietProblems.Add('the runbook carries no quiet-run-status block (the machine-readable gate contract)')
    }
    else {
        $quietBlock = $quietRunbookText.Substring($beginAt, $endAt - $beginAt)
        foreach ($quietLine in @(
            'default-min-idle-seconds: 180',
            'wait-for-idle-minutes-default: 0',
            'idle-source: GetLastInputInfo',
            'quns-allowed-states: 5,6',
            'watchdog-poll-milliseconds: 700',
            'abort-marker: watch-*.abort',
            'suite-reentry-env: MH_ALLOW_ACTIVE',
            'abort-flag-env: MH_QUIET_ABORT_FILE',
            'gate-precedence: abort-marker-then-allow-active-then-idle-and-quns'
        )) {
            if (-not $quietBlock.Contains($quietLine)) { $quietProblems.Add('the status block does not say ' + $quietLine) }
        }
    }
    if (-not $quietRunbookText.Contains('| `-MinIdleSeconds` | 180 |')) { $quietProblems.Add('the parameter table no longer documents -MinIdleSeconds as 180') }
    if (-not $quietRunbookText.Contains('| `-WaitForIdleMinutes` | 0 |')) { $quietProblems.Add('the parameter table no longer documents -WaitForIdleMinutes as 0') }

    if (-not (Test-Path -LiteralPath $quietRunnerFile)) {
        $quietProblems.Add('the quiet runner is absent (windows/tools/acceptance/run-quiet.ps1)')
    }
    else {
        $quietRunnerText = Get-Content -LiteralPath $quietRunnerFile -Raw
        foreach ($quietNeedle in @(
            '[int]$MinIdleSeconds = 180',
            '[int]$WaitForIdleMinutes = 0',
            'Start-MhQuietWatch',
            '$env:MH_ALLOW_ACTIVE = ''1''',
            '$env:MH_QUIET_ABORT_FILE'
        )) {
            if (-not $quietRunnerText.Contains($quietNeedle)) { $quietProblems.Add('the runner no longer contains ' + $quietNeedle) }
        }
    }

    if (-not (Test-Path -LiteralPath $quietModuleFile)) {
        $quietProblems.Add('the quiet-gate module is absent (windows/tools/acceptance/lib/MhQuiet.psm1)')
    }
    else {
        $quietModuleText = Get-Content -LiteralPath $quietModuleFile -Raw
        foreach ($quietNeedle in @(
            'SHQueryUserNotificationState',
            '$state -eq 5 -or $state -eq 6',
            '[int]$PollMilliseconds = 700',
            'Test-MhQuietAborted'
        )) {
            if (-not $quietModuleText.Contains($quietNeedle)) { $quietProblems.Add('the quiet-gate module no longer contains ' + $quietNeedle) }
        }
    }

    if (-not (Test-Path -LiteralPath $uiaModuleFile)) {
        $quietProblems.Add('the acceptance UI module is absent (windows/tools/acceptance/lib/MhUia.psm1)')
    }
    else {
        $uiaModuleText = Get-Content -LiteralPath $uiaModuleFile -Raw
        foreach ($quietNeedle in @(
            'function Start-MhApp',
            '$env:MH_QUIET_ABORT_FILE',
            'refusing to start MediaHub: the quiet run was aborted by user input',
            '$env:MH_ALLOW_ACTIVE -ne ''1'''
        )) {
            if (-not $uiaModuleText.Contains($quietNeedle)) { $quietProblems.Add('Start-MhApp no longer contains ' + $quietNeedle) }
        }
    }

    if ($quietProblems.Count -eq 0) {
        Add-Check 'quiet-run-claims' 'PASS' 'the quiet-run safety contract still holds: the runbook block names 9 gate facts, the runner still defaults to 180 s idle and 700 ms polling, the QUNS allow-list is still 5/6, and Start-MhApp still refuses on an abort marker before MH_ALLOW_ACTIVE'
    }
    else {
        Add-Check 'quiet-run-claims' 'FAIL' (($quietProblems -join '; ') + ' - update windows/docs/ACCEPTANCE-QUIET-RUNBOOK.md section 0 and the file it describes')
    }
}
# --- 20. the resume-floor contract (mock fidelity) ---------------------------------------------
$resumeFloorBlock = ''
$resumeFloorCaseCount = 0
$resumeFloorProblems = [System.Collections.Generic.List[string]]::new()
if (-not (Test-Path -LiteralPath $resumeFloorDocFile)) {
    Add-Check 'resume-floor-claims' 'SKIP' 'windows/docs/W8-RESUME-FLOOR-CONTRACT.md is absent, so the resume-floor contract has nothing to compare against'
}
else {
    $resumeFloorText = Get-Content -LiteralPath $resumeFloorDocFile -Raw
    $resumeFloorStart = $resumeFloorText.IndexOf('<!-- BEGIN resume-floor-status', [System.StringComparison]::Ordinal)
    $resumeFloorEnd = $resumeFloorText.IndexOf('<!-- END resume-floor-status', [System.StringComparison]::Ordinal)
    if ($resumeFloorStart -lt 0 -or $resumeFloorEnd -lt $resumeFloorStart) {
        $resumeFloorProblems.Add('the contract document carries no resume-floor-status block (the machine-readable thresholds and the case count)')
    }
    else {
        $resumeFloorBlock = $resumeFloorText.Substring($resumeFloorStart, $resumeFloorEnd - $resumeFloorStart)
        foreach ($resumeFloorLine in @(
            'emby-min-percent: 2',
            'jellyfin-min-percent: 5',
            'minimum-runtime-seconds: 300',
            'maximum-percent-default: 90',
            'trailing-window-ticks: 10000000',
            'floor-comparison: percent-of-runtime',
            'threshold-source: measured-constants-not-server-config',
            'contract-case-count: 31'
        )) {
            if (-not $resumeFloorBlock.Contains($resumeFloorLine)) { $resumeFloorProblems.Add('the status block does not say ' + $resumeFloorLine) }
        }
    }

    if (-not (Test-Path -LiteralPath $mockServerFile)) {
        $resumeFloorProblems.Add('the server mock is absent (windows/tests/MediaHub.TestSupport/MockMediaServer.cs)')
    }
    else {
        $mockServerText = Get-Content -LiteralPath $mockServerFile -Raw
        foreach ($mockNeedle in @(
            'MockResumeFloor Emby = new(2, 300)',
            'MockResumeFloor Jellyfin = new(5, 300)',
            'MaximumPercent = 90',
            'private const long TrailingWindowTicks = 10_000_000L',
            '(decimal)position * 100m / item.RunTimeTicks',
            'MinimumRuntimeTicks => MinimumRuntimeSeconds * TimeSpan.TicksPerSecond'
        )) {
            if (-not $mockServerText.Contains($mockNeedle)) { $resumeFloorProblems.Add('the mock no longer contains ' + $mockNeedle) }
        }
    }

    if (-not (Test-Path -LiteralPath $resumeFloorTestFile)) {
        $resumeFloorProblems.Add('the contract test file is absent (windows/tests/MediaHub.Tests/ResumeFloorContractTests.cs)')
    }
    else {
        $resumeFloorTestText = Get-Content -LiteralPath $resumeFloorTestFile -Raw
        $resumeFloorCaseCount = ([regex]::Matches($resumeFloorTestText, [regex]::Escape('[Fact]'))).Count + ([regex]::Matches($resumeFloorTestText, [regex]::Escape('[InlineData('))).Count
        $resumeFloorDeclared = [regex]::Match($resumeFloorBlock, 'contract-case-count: (\d+)')
        if ($resumeFloorDeclared.Success) {
            $declaredCount = [int]$resumeFloorDeclared.Groups[1].Value
            if ($declaredCount -ne $resumeFloorCaseCount) {
                $resumeFloorProblems.Add('the document declares ' + $declaredCount + ' contract case(s) but the test file expands to ' + $resumeFloorCaseCount)
            }
        }
    }

    if ($resumeFloorProblems.Count -eq 0) {
        Add-Check 'resume-floor-claims' 'PASS' ('the resume-floor contract still holds: the document block names the measured floors (Emby 2 %, Jellyfin 5 %, 300 s minimum, 90 % ceiling, 10 s trailing window) and the mock plus its tests still expand to ' + $resumeFloorCaseCount + ' case(s)')
    }
    else {
        Add-Check 'resume-floor-claims' 'FAIL' (($resumeFloorProblems -join '; ') + ' - update windows/docs/W8-RESUME-FLOOR-CONTRACT.md section 0 and the code it describes')
    }
}
# --- 21. the rolling diagnostic-log file contract ----------------------------------------------
$logFileBlock = ''
$logFileCaseCount = 0
$logFileProblems = [System.Collections.Generic.List[string]]::new()
if (-not (Test-Path -LiteralPath $logFileDocFile)) {
    Add-Check 'log-file-claims' 'SKIP' 'windows/docs/W14-DIAGNOSTIC-LOG-FILES.md is absent, so the rolling-file contract has nothing to compare against'
}
else {
    $logFileText = Get-Content -LiteralPath $logFileDocFile -Raw
    $logFileStart = $logFileText.IndexOf('<!-- BEGIN log-file-status', [System.StringComparison]::Ordinal)
    $logFileEnd = $logFileText.IndexOf('<!-- END log-file-status', [System.StringComparison]::Ordinal)
    if ($logFileStart -lt 0 -or $logFileEnd -lt $logFileStart) {
        $logFileProblems.Add('the document carries no log-file-status block (the machine-readable limits and the case count)')
    }
    else {
        $logFileBlock = $logFileText.Substring($logFileStart, $logFileEnd - $logFileStart)
        foreach ($logFileLine in @(
            'max-bytes-per-file: 1048576',
            'max-files: 5',
            'file-name-pattern: mediahub-0.log .. mediahub-(MaxFiles-1).log',
            'rotation-boundary: between-entries-never-inside-one',
            'rotation-trigger: current-length-plus-entry-bytes-over-limit',
            'append-never-throws: true',
            'last-error: type-name-and-message',
            'recoverable-failures: IOException, UnauthorizedAccessException, SecurityException, ArgumentException, NotSupportedException',
            'memory-ring-entries: 2000',
            'export-directory-line: # 滚动文件目录：',
            'app-log-directory: %LOCALAPPDATA%\MediaHub\logs',
            'file-sink-injection: LogService(LogFileSink? fileSink = null)',
            'write-order: memory-ring-then-file-sink-then-entry-added',
            'redaction-owner: LogService.Redact',
            'contract-case-count: 9'
        )) {
            if (-not $logFileBlock.Contains($logFileLine)) { $logFileProblems.Add('the status block does not say ' + $logFileLine) }
        }
    }

    if (-not (Test-Path -LiteralPath $logFileSinkFile)) {
        $logFileProblems.Add('the rolling-file sink is absent (windows/src/MediaHub.Net/LogFileSink.cs)')
    }
    else {
        $logFileSinkText = Get-Content -LiteralPath $logFileSinkFile -Raw
        foreach ($sinkNeedle in @(
            'public const long DefaultMaxBytesPerFile = 1024 * 1024;',
            'public const int DefaultMaxFiles = 5;',
            'public string? LastError { get; private set; }',
            'public string PathFor(int index) => Path.Combine(_directory, _fileNamePrefix + index + ".log");',
            'if (currentLength > 0 && currentLength + bytes.Length > _maxBytesPerFile) Rotate();',
            '// An entry is appended whole even when it alone exceeds the limit; the next entry',
            'private static string Describe(Exception ex) => ex.GetType().Name + ": " + ex.Message;'
        )) {
            if (-not $logFileSinkText.Contains($sinkNeedle)) { $logFileProblems.Add('the sink no longer contains ' + $sinkNeedle) }
        }
        $recoverableCatches = ([regex]::Matches($logFileSinkText, [regex]::Escape('catch (Exception ex) when (IsRecoverableDiskFailure(ex))'))).Count
        if ($recoverableCatches -ne 2) { $logFileProblems.Add('the sink swallows recoverable disk failures in ' + $recoverableCatches + ' place(s) - the constructor and Append are the two that must never throw') }
        $recoverableAt = $logFileSinkText.IndexOf('private static bool IsRecoverableDiskFailure', [System.StringComparison]::Ordinal)
        if ($recoverableAt -lt 0) { $logFileProblems.Add('the sink has no IsRecoverableDiskFailure predicate') }
        else {
            $recoverableTail = $logFileSinkText.Substring($recoverableAt, [Math]::Min(400, $logFileSinkText.Length - $recoverableAt))
            foreach ($failureType in @('IOException', 'UnauthorizedAccessException', 'System.Security.SecurityException', 'ArgumentException', 'NotSupportedException')) {
                if (-not $recoverableTail.Contains($failureType)) { $logFileProblems.Add('the sink no longer treats ' + $failureType + ' as a recoverable disk failure') }
            }
        }
    }

    if (-not (Test-Path -LiteralPath $logServiceFile)) {
        $logFileProblems.Add('the log service is absent (windows/src/MediaHub.Net/LogService.cs)')
    }
    else {
        $logServiceText = Get-Content -LiteralPath $logServiceFile -Raw
        foreach ($serviceNeedle in @(
            'private const int MaxEntries = 2000;',
            'public LogFileSink? FileSink { get; }',
            'public LogService(LogFileSink? fileSink = null)',
            'FileSink?.Append(entry);',
            'sb.AppendLine("# 滚动文件目录：" + FileSink.DirectoryPath);',
            'public static string Redact(string? input)'
        )) {
            if (-not $logServiceText.Contains($serviceNeedle)) { $logFileProblems.Add('the log service no longer contains ' + $serviceNeedle) }
        }
        $ringAt = $logServiceText.IndexOf('_entries.Add(entry);', [System.StringComparison]::Ordinal)
        $sinkAt = $logServiceText.IndexOf('FileSink?.Append(entry);', [System.StringComparison]::Ordinal)
        $sinkEntryAt = $logServiceText.IndexOf('EntryAdded?.Invoke(', [System.StringComparison]::Ordinal)
        if ($ringAt -lt 0 -or $sinkAt -lt 0 -or $sinkEntryAt -lt 0 -or -not ($ringAt -lt $sinkAt -and $sinkAt -lt $sinkEntryAt)) {
            $logFileProblems.Add('the write order changed: an entry must reach the memory ring, then the file sink, and only then the EntryAdded subscribers')
        }
    }

    if (-not (Test-Path -LiteralPath $appServicesFile)) {
        $logFileProblems.Add('the composition root is absent (windows/src/MediaHub.App/AppServices.cs)')
    }
    else {
        $appServicesText = Get-Content -LiteralPath $appServicesFile -Raw
        if (-not [regex]::IsMatch($appServicesText, 'new LogService\(new LogFileSink\(Path\.Combine\(\s*Environment\.GetFolderPath\(Environment\.SpecialFolder\.LocalApplicationData\),\s*"MediaHub",\s*"logs"\)\)\);')) {
            $logFileProblems.Add('the composition root no longer builds the log service on %LOCALAPPDATA%\MediaHub\logs through a LogFileSink')
        }
    }

    if (-not (Test-Path -LiteralPath $logFileTestFile)) {
        $logFileProblems.Add('the sink test file is absent (windows/tests/MediaHub.Tests/LogFileSinkTests.cs)')
    }
    else {
        $logFileTestText = Get-Content -LiteralPath $logFileTestFile -Raw
        $logFileCaseCount = ([regex]::Matches($logFileTestText, [regex]::Escape('[Fact]'))).Count + ([regex]::Matches($logFileTestText, [regex]::Escape('[InlineData('))).Count
        $logFileDeclared = [regex]::Match($logFileBlock, 'contract-case-count: (\d+)')
        if ($logFileDeclared.Success) {
            $declaredLogCases = [int]$logFileDeclared.Groups[1].Value
            if ($declaredLogCases -ne $logFileCaseCount) {
                $logFileProblems.Add('the document declares ' + $declaredLogCases + ' contract case(s) but the sink tests expand to ' + $logFileCaseCount)
            }
        }
    }

    if ($logFileProblems.Count -eq 0) {
        Add-Check 'log-file-claims' 'PASS' ('the rolling-file contract still holds: ' + $logFileCaseCount + ' test case(s) pin a 1 MiB / 5-file trail that rotates between entries, never throws, and records LastError for the five recoverable failure types, while LogService keeps the 2000-entry ring and the export directory line')
    }
    else {
        Add-Check 'log-file-claims' 'FAIL' (($logFileProblems -join '; ') + ' - update windows/docs/W14-DIAGNOSTIC-LOG-FILES.md section 0 and the code it describes')
    }
}


# --- 22. dated audit snapshots ---------------------------------------------------------------
if (-not (Test-Path -LiteralPath $docAuditFile)) {
    Add-Check 'doc-audit-snapshot-claims' 'SKIP' 'the W12 audit snapshot is absent (windows/docs/W12-DOC-AUDIT.md)'
}
else {
    $docAuditText = Get-Content -LiteralPath $docAuditFile -Raw
    $docAuditProblems = [System.Collections.Generic.List[string]]::new()
    $docAuditWarn = ''
    $blockBegin = '<!-- doc-audit-status:begin -->'
    $blockEnd = '<!-- doc-audit-status:end -->'
    $beginAt = $docAuditText.IndexOf($blockBegin, [System.StringComparison]::Ordinal)
    $endAt = $docAuditText.IndexOf($blockEnd, [System.StringComparison]::Ordinal)
    $headMatch = $null
    if ($beginAt -lt 0 -or $endAt -lt $beginAt) {
        $docAuditProblems.Add('the document no longer carries the doc-audit-status block')
    }
    else {
        $docAuditBlock = $docAuditText.Substring($beginAt, $endAt - $beginAt + $blockEnd.Length)
        foreach ($line in @(
            'snapshot-status: historical-audit-not-current',
            'audit-date: 2026-10-24',
            'baseline-head: 282dcc2058815317a3ce285f04855d078404a7ab',
            'baseline-branch: feature/windows-client',
            'counts-are-as-of: baseline-head',
            'recheck-rule: every number here must be re-verified against a fresh run before it is used as current',
            'current-truth-source: windows-ci run log (dotnet test output + check-licence-surface)',
            'doc-role: dated audit snapshot - never the acceptance authority'
        )) {
            if (-not $docAuditBlock.Contains($line)) { $docAuditProblems.Add('the status block no longer declares ' + $line) }
        }
        $headMatch = [regex]::Match($docAuditBlock, 'baseline-head: ([0-9a-f]{40})')
        $branchMatch = [regex]::Match($docAuditBlock, 'baseline-branch: ([A-Za-z0-9._/-]+)')
        if (-not $headMatch.Success) { $docAuditProblems.Add('the status block no longer declares a 40-hex baseline-head') }
        if (-not $branchMatch.Success) { $docAuditProblems.Add('the status block no longer declares a baseline-branch') }
        if (-not $docAuditText.Contains('都是 **as-of')) { $docAuditProblems.Add('the document no longer says its numbers are as-of the baseline HEAD') }
        if (-not $docAuditText.Contains('接近「当前」的用法必须重新核对')) { $docAuditProblems.Add('the document no longer tells readers to re-verify before using it as current') }
        if (-not $docAuditText.Contains('## 4. 不跑测试 / 不构建即无法判定')) { $docAuditProblems.Add('the document lost the section that lists what this snapshot could not determine') }
        if ($headMatch.Success) {
            $bodyHead = [regex]::Match($docAuditText, '本地 HEAD = .([0-9a-f]{40})')
            if (-not $bodyHead.Success) { $docAuditProblems.Add('the body no longer names the baseline HEAD the snapshot was taken at') }
            elseif ($bodyHead.Groups[1].Value -ne $headMatch.Groups[1].Value) {
                $docAuditProblems.Add('the body was taken at ' + $bodyHead.Groups[1].Value + ' but the status block claims ' + $headMatch.Groups[1].Value)
            }
            if ($branchMatch.Success -and -not [regex]::IsMatch($docAuditText, '= .origin/' + [regex]::Escape($branchMatch.Groups[1].Value) + '.')) {
                $docAuditProblems.Add('the body no longer ties the snapshot to origin/' + $branchMatch.Groups[1].Value)
            }
            $git = Get-Command git -ErrorAction SilentlyContinue
            if (-not $git) {
                $docAuditWarn = 'git is unavailable here, so the declared baseline commit could not be resolved'
            }
            else {
                $shallowOutput = @(& git -C $RepoRoot rev-parse --is-shallow-repository 2>$null)
                $shallowText = if (@($shallowOutput).Count -gt 0) { [string]$shallowOutput[0] } else { '' }
                if ($shallowText -eq 'true') {
                    $docAuditWarn = 'this clone is shallow, so it cannot contain the declared baseline commit - fetch-depth: 0 resolves it'
                }
                else {
                    $typeOutput = @(& git -C $RepoRoot cat-file -t $headMatch.Groups[1].Value 2>$null)
                    $typeText = if (@($typeOutput).Count -gt 0) { [string]$typeOutput[0] } else { '' }
                    if ($typeText -ne 'commit') {
                        $docAuditProblems.Add('the declared baseline HEAD ' + $headMatch.Groups[1].Value + ' is not a commit in this repository')
                    }
                    else {
                        & git -C $RepoRoot merge-base --is-ancestor $headMatch.Groups[1].Value HEAD 2>$null | Out-Null
                        if ($LASTEXITCODE -ne 0) {
                            $docAuditProblems.Add('the declared baseline HEAD ' + $headMatch.Groups[1].Value + ' is not an ancestor of HEAD')
                        }
                    }
                }
            }
        }
    }

    if ($docAuditProblems.Count -eq 0) {
        if ($docAuditWarn) {
            Add-Check 'doc-audit-snapshot-claims' 'WARN' $docAuditWarn
        }
        else {
            Add-Check 'doc-audit-snapshot-claims' 'PASS' ('the W12 audit is still a dated snapshot: baseline ' + $headMatch.Groups[1].Value + ' on origin/' + $branchMatch.Groups[1].Value + ' is a real ancestor of HEAD, its numbers are labelled as-of that point, and the undetermined list is still present')
        }
    }
    else {
        Add-Check 'doc-audit-snapshot-claims' 'FAIL' (($docAuditProblems -join '; ') + ' - update windows/docs/W12-DOC-AUDIT.md section 0 and the body it describes')
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
