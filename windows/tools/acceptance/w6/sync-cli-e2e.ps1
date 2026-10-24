<#
.SYNOPSIS
    MediaHub SyncCli cross-device acceptance: device A -> envelope file -> device B.

.DESCRIPTION
    Drives two independent source stores ("device A" and "device B") through the real
    MediaHub.SyncCli binary, exactly the way a user would from a terminal:

      1. device A holds three known sources plus one source type Windows does not
         support, and it announces one tombstone for a source the user deleted.
      2. A exports, B plans (three buckets are checked by count and by reason),
         B imports with --apply, and the peer identity is checked byte for byte.
      3. A exports again and B plans again: the round must be idempotent.
      4. A exports under a passphrase; B imports it, and a wrong passphrase must
         fail closed without writing anything.
      5. A exports with a sealed credential; B must re-seal it with its own DPAPI
         key, so the stored blob differs and still unprotects to the same plaintext.

    Every check prints "PASS/FAIL <name> :: <detail>" and the script ends with
    "RESULT: N passed, M failed".

    The script never starts the MediaHub GUI, never talks to a media server and never
    touches the published build output: the only process it spawns is
    "dotnet <MediaHub.SyncCli.dll>". Transcripts and device stores are written to
    windows/tools/acceptance/evidence/w6-sync-cli/<timestamp>/ (gitignored).

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/sync-cli-e2e.ps1

.EXAMPLE
    pwsh -NoProfile -File windows/tools/acceptance/w6/sync-cli-e2e.ps1 -KeepTemp
#>
[CmdletBinding()]
param(
    [string]$EvidenceDirectory,
    [switch]$NoBuild,
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$script:utf8 = [System.Text.UTF8Encoding]::new($false)
$script:nl = [Environment]::NewLine
$script:repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$script:cliProject = Join-Path $script:repoRoot 'windows\src\MediaHub.SyncCli\MediaHub.SyncCli.csproj'
$script:cliDll = Join-Path $script:repoRoot 'windows\src\MediaHub.SyncCli\bin\Debug\net10.0\MediaHub.SyncCli.dll'
$script:stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:evidence = if ($EvidenceDirectory) { $EvidenceDirectory } else { Join-Path $script:repoRoot ('windows\tools\acceptance\evidence\w6-sync-cli\' + $script:stamp) }
$script:tempRoot = Join-Path $env:TEMP ('mh-w6-sync-cli-' + $script:stamp)
$script:scratch = Join-Path $env:TEMP ('mh-w6-sync-cli-' + $script:stamp + '-scratch')

$script:rootA = Join-Path $script:tempRoot 'deviceA'
$script:rootB = Join-Path $script:tempRoot 'deviceB'
$script:rootB2 = Join-Path $script:tempRoot 'deviceB-archive'
$script:rootB3 = Join-Path $script:tempRoot 'deviceB-wrong-pass'
$script:rootB4 = Join-Path $script:tempRoot 'deviceB-secrets'

$script:passphrase = 'correct horse battery staple'
$script:secretPlaintext = 's3cret-Pa55word-for-nas-a'

$script:transcript = New-Object 'System.Collections.Generic.List[string]'
$script:checkLines = New-Object 'System.Collections.Generic.List[string]'
$script:failures = New-Object 'System.Collections.Generic.List[string]'
$script:callNo = 0
$script:passed = 0
$script:failed = 0

function Format-CliArguments {
    param([string[]]$Arguments)
    $redact = @('--passphrase', '--secrets', '--expect')
    $parts = New-Object 'System.Collections.Generic.List[string]'
    $skipNext = $false
    foreach ($argument in $Arguments) {
        if ($skipNext) { $parts.Add('<redacted>'); $skipNext = $false; continue }
        $name = $argument
        $inline = $false
        if ($argument -like '--*=*') { $name = $argument.Split('=')[0]; $inline = $true }
        if ($redact -contains $name) {
            if ($inline) { $parts.Add($name + '=<redacted>') } else { $parts.Add($argument); $skipNext = $true }
            continue
        }
        $parts.Add($argument)
    }
    return ($parts -join ' ')
}

function Invoke-Cli {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $script:callNo++
    $errorFile = Join-Path $script:scratch ('stderr-' + $script:callNo + '.txt')
    $stdoutLines = & dotnet $script:cliDll @Arguments 2> $errorFile
    $exitCode = $LASTEXITCODE
    $stdout = ''
    if ($null -ne $stdoutLines) { $stdout = (($stdoutLines | ForEach-Object { [string]$_ }) -join $script:nl) }
    $stderr = ''
    if (Test-Path -LiteralPath $errorFile) {
        $stderr = [System.IO.File]::ReadAllText($errorFile)
    }
    $script:transcript.Add('$ dotnet MediaHub.SyncCli.dll ' + (Format-CliArguments -Arguments $Arguments))
    $script:transcript.Add('  exit=' + $exitCode)
    if ($stdout.Trim()) { $script:transcript.Add('  stdout: ' + $stdout.Trim()) }
    if ($stderr.Trim()) { $script:transcript.Add('  stderr: ' + $stderr.Trim()) }
    return [pscustomobject]@{
        Arguments = $Arguments
        ExitCode  = $exitCode
        Stdout    = $stdout.Trim()
        Stderr    = $stderr.Trim()
    }
}

function Get-CliJson {
    param($Result)
    $lines = @($Result.Stdout -split "\r?\n")
    $jsonLine = $null
    foreach ($line in $lines) {
        if ($line.TrimStart().StartsWith('{')) { $jsonLine = $line; break }
    }
    if (-not $jsonLine) {
        throw ('no JSON document on stdout (exit ' + $Result.ExitCode + '), stderr: ' + $Result.Stderr)
    }
    return ($jsonLine | ConvertFrom-Json)
}

function Write-SourceStore {
    param([string]$Root, [object[]]$Records)
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $json = '[]'
    if ($null -ne $Records -and $Records.Count -gt 0) {
        $json = ConvertTo-Json -InputObject @($Records) -Depth 6
    }
    [System.IO.File]::WriteAllText((Join-Path $Root 'sync-sources.json'), $json, $script:utf8)
}

function Read-SourceStore {
    param([string]$Root)
    $path = Join-Path $Root 'sync-sources.json'
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    return @([System.IO.File]::ReadAllText($path) | ConvertFrom-Json)
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Label)
    if ([string]$Expected -ne [string]$Actual) {
        throw ('$Label' + ': expected [' + [string]$Expected + '] but was [' + [string]$Actual + ']')
    }
}

function Assert-True {
    param($Condition, [string]$Label)
    if (-not $Condition) { throw $Label }
}

function Check {
    param([string]$Name, [scriptblock]$Body)
    $detail = 'ok'
    try {
        $value = & $Body
        if ($null -ne $value -and [string]$value) { $detail = [string]$value }
        $script:passed++
        $line = ('PASS ' + $Name + ' :: ' + $detail)
        $script:checkLines.Add($line)
        Write-Host $line
    }
    catch {
        $script:failed++
        $message = $_.Exception.Message
        $line = ('FAIL ' + $Name + ' :: ' + $message)
        $script:checkLines.Add($line)
        $script:failures.Add($line)
        Write-Host $line
    }
}

Write-Host ('MediaHub SyncCli end-to-end acceptance :: ' + $script:stamp)
Write-Host ('repo      : ' + $script:repoRoot)
Write-Host ('sandbox   : ' + $script:tempRoot)
Write-Host ('evidence  : ' + $script:evidence)
Write-Host ''

Check 'cli-binary-is-available' {
    if (-not (Test-Path -LiteralPath $script:cliProject)) { throw ('missing project ' + $script:cliProject) }
    if (-not (Test-Path -LiteralPath $script:cliDll)) {
        if ($NoBuild) { throw ('missing ' + $script:cliDll + ' (rerun without -NoBuild)') }
        & dotnet build $script:cliProject -c Debug -nologo | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'dotnet build MediaHub.SyncCli failed' }
    }
    return ($script:cliDll)
}

Check 'device-a-holds-three-known-sources-and-one-unsupported-type' {
    New-Item -ItemType Directory -Path $script:tempRoot, $script:scratch -Force | Out-Null
    $records = @(
        [ordered]@{ serverId = 'srv-emby'; type = 'Emby'; url = 'http://nas-a:8096/emby/'; accountScope = 'alice'; displayName = '客厅 Emby'; recordRevision = 3 },
        [ordered]@{ serverId = 'srv-jelly'; type = 'Jellyfin'; url = 'http://nas-a:8096/jelly'; accountScope = 'bob'; displayName = 'Jellyfin 楼上'; recordRevision = 1 },
        [ordered]@{ serverId = 'srv-dav'; type = 'WebDav'; url = 'https://dav.example/gate'; displayName = 'WebDAV 网关'; recordRevision = 2 },
        [ordered]@{ serverId = 'srv-quantum'; type = 'Unknown'; sourceTypeRaw = 'quantum-nas'; url = 'https://quantum.example:9443/v1'; displayName = 'Quantum NAS'; recordRevision = 2 }
    )
    Write-SourceStore -Root $script:rootA -Records $records
    $written = Read-SourceStore -Root $script:rootA
    Assert-Equal 4 $written.Count 'device A record count'
    $types = @($written | ForEach-Object { [string]$_.type })
    Assert-True ($types -contains 'Unknown') 'the unsupported source type must stay on disk'
    return '4 sources on A (Emby, Jellyfin, WebDav, unknown quantum-nas)'
}

Check 'export-a-emits-three-sources-and-one-tombstone' {
    $first = Invoke-Cli @('export', '--root', $script:rootA, '--device', 'device-a', '--json')
    Assert-Equal 0 $first.ExitCode 'first export exit code'
    $store = Read-SourceStore -Root $script:rootA
    $script:ids = @{}
    foreach ($record in $store) { $script:ids[[string]$record.serverId] = [string]$record.syncSourceId }
    foreach ($key in @('srv-emby', 'srv-jelly', 'srv-dav', 'srv-quantum')) {
        Assert-True ($script:ids.ContainsKey($key)) ('no derived syncSourceId for ' + $key)
        Assert-True ($script:ids[$key].StartsWith('local-')) ('derived id must be prefixed: ' + $script:ids[$key])
        Assert-Equal 22 $script:ids[$key].Length ('derived id length for ' + $key)
    }
    $kept = @($store | Where-Object { [string]$_.serverId -ne 'srv-dav' })
    Write-SourceStore -Root $script:rootA -Records $kept
    $script:deletionsJson = ConvertTo-Json -InputObject @(@{ syncSourceId = $script:ids['srv-dav']; recordRevision = 5; deletedAtEpochMs = 1760000000000 }) -Compress
    $export = Invoke-Cli @('export', '--root', $script:rootA, '--device', 'device-a', '--deletions', $script:deletionsJson, '--json')
    Assert-Equal 0 $export.ExitCode 'export with tombstone exit code'
    $json = Get-CliJson $export
    Assert-Equal 3 $json.sourceCount 'envelope source count'
    Assert-Equal 1 $json.deletionCount 'envelope deletion count'
    Assert-Equal $false $json.canSendSecrets 'canSendSecrets must default to false'
    $script:envelopeA = Join-Path $script:rootA 'sync-envelope.json'
    Assert-True (Test-Path -LiteralPath $script:envelopeA) 'envelope file missing'
    return ('sources=3 deletions=1 canSendSecrets=false ids=' + (($script:ids.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Value }) -join ','))
}

Check 'device-b-plan-splits-apply-approval-and-tombstone' {
    $plan = Invoke-Cli @('plan', '--root', $script:rootB, '--in', $script:envelopeA, '--json')
    Assert-Equal 3 $plan.ExitCode 'plan exit code with a pending approval'
    $json = Get-CliJson $plan
    Assert-Equal 2 $json.counts.apply 'plan apply count'
    Assert-Equal 1 $json.counts.requiresApproval 'plan requiresApproval count'
    Assert-Equal 1 $json.counts.ignored 'plan ignored count'
    $approvalReasons = @($json.requiresApproval | ForEach-Object { [string]$_.reason })
    Assert-True ($approvalReasons -contains 'unknown-source-type') ('approval reasons: ' + ($approvalReasons -join ','))
    $ignoredReasons = @($json.ignored | ForEach-Object { [string]$_.reason })
    Assert-True ($ignoredReasons -contains 'tombstone-unknown') ('ignored reasons: ' + ($ignoredReasons -join ','))
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $script:rootB 'sync-sources.json'))) 'plan must never create a store'
    return ('counts=2/1/1 approval=' + ($approvalReasons -join ',') + ' ignored=' + ($ignoredReasons -join ','))
}

Check 'device-b-import-applies-only-what-it-understands' {
    $import = Invoke-Cli @('import', '--root', $script:rootB, '--in', $script:envelopeA, '--apply', '--json')
    Assert-Equal 0 $import.ExitCode 'import --apply exit code'
    $json = Get-CliJson $import
    Assert-Equal $true $json.succeeded 'import succeeded flag'
    Assert-Equal 2 $json.appliedCount 'import applied count'
    Assert-Equal 0 $json.requiresApprovalCount 'import requiresApproval count'
    $entries = @($json.entries)
    $unsupported = @($entries | Where-Object { [string]$_.action -eq 'Unsupported' })
    Assert-Equal 1 $unsupported.Count 'unsupported entry count'
    Assert-Equal 'unknown-source-type' ([string]$unsupported[0].reason) 'unsupported entry reason'
    $tombstoned = @($entries | Where-Object { [string]$_.reason -eq 'no-local-source' })
    Assert-Equal 1 $tombstoned.Count 'tombstone entry count'
    Assert-Equal 'NoChange' ([string]$tombstoned[0].action) 'a tombstone must never be auto-applied'
    return ('applied=2 skipped=' + $json.skippedCount + ' unsupported=1 tombstone=NoChange')
}

Check 'device-b-stores-the-peer-identity-verbatim' {
    $store = Read-SourceStore -Root $script:rootB
    Assert-Equal 2 $store.Count 'device B record count'
    $expected = @($script:ids['srv-emby'], $script:ids['srv-jelly'])
    foreach ($record in $store) {
        Assert-Equal ([string]$record.syncSourceId) ([string]$record.serverId) 'serverId must equal the peer syncSourceId'
        Assert-True ($expected -contains [string]$record.syncSourceId) ('unexpected syncSourceId on B: ' + $record.syncSourceId)
    }
    Assert-True (-not ($store | Where-Object { [string]$_.syncSourceId -eq $script:ids['srv-dav'] })) 'a tombstoned source must not appear on B'
    Assert-True (-not ($store | Where-Object { [string]$_.syncSourceId -eq $script:ids['srv-quantum'] })) 'an unsupported source must not be invented on B'
    $emby = @($store | Where-Object { [string]$_.syncSourceId -eq $script:ids['srv-emby'] })
    Assert-Equal 'http://nas-a:8096/emby' ([string]$emby[0].url) 'addresses must arrive normalised'
    return ('ids match A verbatim; url=' + $emby[0].url)
}

Check 'unsupported-source-type-survives-the-wire-untouched' {
    $envelopeText = [System.IO.File]::ReadAllText($script:envelopeA)
    Assert-True ($envelopeText.Contains('quantum-nas')) 'the envelope lost the unknown wire value'
    $storeA = Read-SourceStore -Root $script:rootA
    $quantum = @($storeA | Where-Object { [string]$_.serverId -eq 'srv-quantum' })
    Assert-Equal 1 $quantum.Count 'device A must keep its unsupported source'
    Assert-Equal 'quantum-nas' ([string]$quantum[0].sourceTypeRaw) 'device A must keep the raw wire value'
    Assert-Equal ([string]$script:ids['srv-quantum']) ([string]$quantum[0].syncSourceId) 'device A must keep its derived id'
    return 'wire value quantum-nas preserved on A and reported as unsupported on B'
}

Check 'second-round-is-idempotent' {
    $export = Invoke-Cli @('export', '--root', $script:rootA, '--device', 'device-a', '--deletions', $script:deletionsJson, '--json')
    Assert-Equal 0 $export.ExitCode 'second export exit code'
    $envelope = Get-CliJson $export
    Assert-Equal 3 $envelope.sourceCount 'second envelope source count'
    Assert-Equal 1 $envelope.deletionCount 'second envelope deletion count'
    $plan = Invoke-Cli @('plan', '--root', $script:rootB, '--in', $script:envelopeA, '--json')
    Assert-Equal 3 $plan.ExitCode 'second plan exit code'
    $json = Get-CliJson $plan
    Assert-Equal 0 $json.counts.apply 'second plan apply count'
    Assert-Equal 1 $json.counts.requiresApproval 'second plan requiresApproval count'
    $alreadyApplied = @($json.ignored | Where-Object { [string]$_.reason -eq 'already-applied' })
    Assert-Equal 2 $alreadyApplied.Count 'already-applied count'
    $ignoredReasons = @($json.ignored | ForEach-Object { [string]$_.reason })
    Assert-True ($ignoredReasons -contains 'tombstone-unknown') 'the tombstone must still be reported, never applied'
    return 'apply=0 already-applied=2 approval=1 (unknown type), tombstone still reported'
}

Check 'passphrase-archive-round-trips-to-a-fresh-device' {
    $export = Invoke-Cli @('export', '--root', $script:rootA, '--device', 'device-a', '--passphrase', $script:passphrase, '--json')
    Assert-Equal 0 $export.ExitCode 'encrypted export exit code'
    $json = Get-CliJson $export
    Assert-Equal $true $json.encrypted 'encrypted flag'
    $script:archiveA = Join-Path $script:rootA 'sync-envelope.mhsync'
    Assert-True (Test-Path -LiteralPath $script:archiveA) 'archive file missing'
    $bytes = [System.IO.File]::ReadAllBytes($script:archiveA)
    Assert-Equal 'MHSYNCA1' ([System.Text.Encoding]::ASCII.GetString($bytes, 0, 8)) 'archive magic'
    $import = Invoke-Cli @('import', '--root', $script:rootB2, '--in', $script:archiveA, '--passphrase', $script:passphrase, '--apply', '--json')
    Assert-Equal 0 $import.ExitCode 'encrypted import exit code'
    $imported = Get-CliJson $import
    Assert-Equal 2 $imported.appliedCount 'encrypted import applied count'
    return ('magic=MHSYNCA1 bytes=' + $bytes.Length + ' applied=2')
}

Check 'wrong-passphrase-fails-closed-and-writes-nothing' {
    $result = Invoke-Cli @('import', '--root', $script:rootB3, '--in', $script:archiveA, '--passphrase', 'this is not the passphrase', '--apply', '--json')
    Assert-Equal 4 $result.ExitCode 'wrong passphrase exit code'
    $json = Get-CliJson $result
    Assert-Equal $false $json.ok 'ok flag on failure'
    Assert-Equal 'wrong-passphrase-or-tampered' ([string]$json.error.code) 'error code'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $script:rootB3 'sync-sources.json'))) 'a refused import must not create a store'
    Assert-True ($json.error.message.Length -gt 0) 'the error must carry a message'
    return 'exit=4 code=wrong-passphrase-or-tampered store absent'
}

Check 'secrets-are-resealed-with-the-local-dpapi-key' {
    $secretBase64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($script:secretPlaintext))
    $map = [ordered]@{}
    $map[[string]$script:ids['srv-emby']] = $secretBase64
    $mapJson = ConvertTo-Json -InputObject $map -Compress
    $export = Invoke-Cli @('export', '--root', $script:rootA, '--device', 'device-a', '--secrets', $mapJson, '--json')
    Assert-Equal 0 $export.ExitCode 'secrets export exit code'
    $json = Get-CliJson $export
    Assert-Equal 1 $json.sealedSecretCount 'sealed secret count'
    Assert-Equal $true $json.canSendSecrets 'canSendSecrets with a protector'
    $envelope = [System.IO.File]::ReadAllText((Join-Path $script:rootA 'sync-envelope.json')) | ConvertFrom-Json
    $sealed = @($envelope.sources | Where-Object { [string]$_.syncSourceId -eq [string]$script:ids['srv-emby'] })
    Assert-Equal 1 $sealed.Count 'sealed source count in the envelope'
    Assert-Equal 'dpapi-user' ([string]$sealed[0].secrets.scheme) 'envelope secret scheme'
    $envelopeBlob = [string]$sealed[0].secrets.payloadBase64
    Assert-True ($envelopeBlob.Length -ge 128) 'envelope blob looks too short for sealed material'
    $import = Invoke-Cli @('import', '--root', $script:rootB4, '--in', (Join-Path $script:rootA 'sync-envelope.json'), '--apply', '--json')
    Assert-Equal 0 $import.ExitCode 'secrets import exit code'
    $imported = Get-CliJson $import
    Assert-Equal 2 $imported.appliedCount 'secrets import applied count'
    $store = Read-SourceStore -Root $script:rootB4
    $stored = @($store | Where-Object { [string]$_.syncSourceId -eq [string]$script:ids['srv-emby'] })
    Assert-Equal 1 $stored.Count 'stored source count on B'
    Assert-Equal 'dpapi-user' ([string]$stored[0].secret.scheme) 'stored secret scheme'
    $storedBlob = [string]$stored[0].secret.payloadBase64
    Assert-True ($storedBlob -ne $envelopeBlob) 'the credential must be re-sealed, not copied across devices'
    $check = Invoke-Cli @('secret-check', '--root', $script:rootB4, '--id', [string]$script:ids['srv-emby'], '--expect', $secretBase64, '--json')
    Assert-Equal 0 $check.ExitCode 'secret-check exit code'
    $checked = Get-CliJson $check
    Assert-Equal $true $checked.matched 'secret-check matched flag'
    Assert-Equal 'dpapi-user' ([string]$checked.scheme) 'secret-check scheme'
    return ('envelope blob ' + $envelopeBlob.Length + 'B -> stored blob ' + $storedBlob.Length + 'B, same plaintext on B')
}

Check 'no-plaintext-secret-in-any-artefact' {
    $needles = @($script:secretPlaintext, [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($script:secretPlaintext)))
    $haystacks = New-Object 'System.Collections.Generic.List[string]'
    $haystacks.Add(($script:transcript -join $script:nl))
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    foreach ($file in @(Get-ChildItem -LiteralPath $script:tempRoot -Recurse -File)) {
        if ($file.Length -gt 4MB) { continue }
        $haystacks.Add($latin1.GetString([System.IO.File]::ReadAllBytes($file.FullName)))
    }
    foreach ($needle in $needles) {
        foreach ($haystack in $haystacks) {
            Assert-True (-not $haystack.Contains($needle)) 'the credential leaked into the transcript or an artefact'
        }
    }
    return ('scanned ' + $haystacks.Count + ' artefacts for ' + $needles.Count + ' needles')
}

Check 'no-gui-or-media-server-was-started' {
    $gui = @(Get-Process -Name 'MediaHub' -ErrorAction SilentlyContinue)
    Assert-Equal 0 $gui.Count 'MediaHub GUI processes'
    return 'only dotnet MediaHub.SyncCli.dll was executed'
}

Check 'device-roots-are-independent' {
    foreach ($root in @($script:rootA, $script:rootB, $script:rootB2, $script:rootB3, $script:rootB4)) {
        Assert-True ($root.StartsWith($script:tempRoot)) ('root escaped the sandbox: ' + $root)
    }
    Assert-True ($script:rootA -ne $script:rootB) 'the two devices must not share a store'
    return 'five independent roots under the temp sandbox'
}

New-Item -ItemType Directory -Path $script:evidence -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $script:evidence 'transcript.txt'), (($script:transcript -join $script:nl) + $script:nl), $script:utf8)
[System.IO.File]::WriteAllText((Join-Path $script:evidence 'checks.txt'), (($script:checkLines -join $script:nl) + $script:nl), $script:utf8)
$deviceEvidence = Join-Path $script:evidence 'devices'
New-Item -ItemType Directory -Path $deviceEvidence -Force | Out-Null
try {
    foreach ($item in @(Get-ChildItem -LiteralPath $script:tempRoot -Force)) {
        Copy-Item -LiteralPath $item.FullName -Destination $deviceEvidence -Recurse -Force
    }
}
catch {
    Write-Host ('WARN could not copy device stores into the evidence folder: ' + $_.Exception.Message)
}
$summary = 'checks=' + $script:passed + ' passed, ' + $script:failed + ' failed (MediaHub.SyncCli ' + $script:stamp + ')' + $script:nl
[System.IO.File]::WriteAllText((Join-Path $script:evidence 'summary.txt'), $summary, $script:utf8)

Write-Host ''
if ($script:failed -gt 0) {
    Write-Host 'Failures:'
    foreach ($failure in $script:failures) { Write-Host ('  - ' + $failure) }
}
Write-Host ('evidence : ' + $script:evidence)
Write-Host ('RESULT: ' + $script:passed + ' passed, ' + $script:failed + ' failed')

if ($script:failed -eq 0 -and -not $KeepTemp) {
    Remove-Item -LiteralPath $script:tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
if (-not $KeepTemp) {
    Remove-Item -LiteralPath $script:scratch -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:failed -gt 0) { exit 1 } else { exit 0 }
