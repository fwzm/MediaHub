<#
    Helpers for the injected-runner seam suite.

    Pester 5 executes a test file's top-level code during discovery only, so helpers
    defined there are invisible inside `It` at run time. Dot-source this file from
    inside each `It` instead: that works under both Pester 3.4 (what this repo pins
    locally) and Pester 5 (what CI images ship).
#>
function Assert-True($Condition, [string]$Message) {
    # Pester 3.4 and 5.x disagree on `Should` syntax, so assertions are plain throws:
    # an uncaught throw fails the `It` under either version.
    if (-not $Condition) { throw $Message }
}

function New-SyncFixture {
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('mh-sync-seam-' + [guid]::NewGuid().ToString('n'))
    $repo = Join-Path $temp 'repo'
    New-Item -ItemType Directory -Path $repo -Force | Out-Null
    & git -C $repo init -q -b codex/fixture | Out-Null
    & git -C $repo config user.email fixture@example.invalid
    & git -C $repo config user.name 'Controlled fixture'
    Set-Content -LiteralPath (Join-Path $repo 'README.md') -Value '# Fixture' -Encoding utf8
    & git -C $repo add README.md
    & git -C $repo commit -qm 'fixture initial' | Out-Null
    $bare = Join-Path $temp 'remote.git'
    & git init --bare -q $bare | Out-Null
    & git -C $repo remote add origin $bare
    [pscustomobject]@{ Temp = $temp; Repo = $repo }
}

function Invoke-SyncWithSeam([string]$SyncScript, $Fixture) {
    $callsFile = Join-Path $Fixture.Temp 'git-calls.txt'
    $ghCallsFile = Join-Path $Fixture.Temp 'gh-calls.txt'
    $runnerFile = Join-Path $Fixture.Temp 'git-runner.ps1'
    $ghFile = Join-Path $Fixture.Temp 'gh-runner.ps1'
    $runner = @'
param([string[]]$GitArgs)
Add-Content -LiteralPath '__CALLS__' -Value ($GitArgs -join ' ')
if ($GitArgs[0] -eq 'remote') { return [pscustomobject]@{ Code = 0; Lines = @('https://github.com/example/fixture.git'); Text = 'https://github.com/example/fixture.git' } }
$out = @(& git -C '__REPO__' @GitArgs 2>&1 | ForEach-Object { "$_" })
[pscustomobject]@{ Code = $LASTEXITCODE; Lines = $out; Text = ($out -join "`n").Trim() }
'@
    $gh = @'
param([string[]]$GhArgs)
Add-Content -LiteralPath '__GH_CALLS__' -Value ($GhArgs -join ' ')
if ($GhArgs[1] -eq 'list') { return [pscustomobject]@{ Code = 0; Lines = @(); Text = '' } }
[pscustomobject]@{ Code = 0; Lines = @('https://example.invalid/pull/1'); Text = 'https://example.invalid/pull/1' }
'@
    Set-Content -LiteralPath $runnerFile -Value ($runner -replace '__CALLS__', $callsFile -replace '__REPO__', $Fixture.Repo) -Encoding utf8
    Set-Content -LiteralPath $ghFile -Value ($gh -replace '__GH_CALLS__', $ghCallsFile) -Encoding utf8
    $command = "`$git = [scriptblock]::Create((Get-Content -Raw -LiteralPath '$runnerFile')); " +
               "`$gh = [scriptblock]::Create((Get-Content -Raw -LiteralPath '$ghFile')); " +
               "& '$SyncScript' -RepoRoot '$($Fixture.Repo)' -Apply -GitRunner `$git -GhRunner `$gh"
    $output = & pwsh -NoProfile -Command $command 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{ Code = $LASTEXITCODE; Output = ($output -join "`n"); Calls = $callsFile; GhCalls = $ghCallsFile }
}
