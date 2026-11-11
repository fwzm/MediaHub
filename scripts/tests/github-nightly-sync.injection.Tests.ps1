<#
    Pester suite for the injectable runner seam (`-GitRunner` / `-GhRunner`).

    The seam exists so callers and tests can observe or replace the exact Git/GitHub
    invocations without weakening real behaviour. Each case runs the real script in a
    child `pwsh` against a disposable local repository; the injected Git runner records
    every argument list and then delegates to real Git, so only the seam is faked.
#>
$script:syncRoot = Split-Path -Parent $PSScriptRoot
$script:syncScript = Join-Path $script:syncRoot 'github-nightly-sync.ps1'

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

function Invoke-SyncWithSeam($Fixture) {
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
               "& '$script:syncScript' -RepoRoot '$($Fixture.Repo)' -Apply -GitRunner `$git -GhRunner `$gh"
    $output = & pwsh -NoProfile -Command $command 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{ Code = $LASTEXITCODE; Output = ($output -join "`n"); Calls = $callsFile; GhCalls = $ghCallsFile }
}

Describe 'github-nightly-sync runner seam' {
    It 'routes git writes through -GitRunner and keeps the README-only commit' {
        $fixture = New-SyncFixture
        try {
            $result = Invoke-SyncWithSeam $fixture
            $result.Code | Should Be 0
            $calls = Get-Content -LiteralPath $result.Calls -Raw
            ($calls -match 'commit --only -m') | Should Be $true
            ($calls -match 'push -u origin HEAD:refs/heads/codex/fixture') | Should Be $true
            ($calls -match 'log HEAD --not --remotes') | Should Be $true
            $readme = Get-Content -LiteralPath (Join-Path $fixture.Repo 'README.md') -Raw
            ($readme -match 'MEDIAHUB:RECENT:START') | Should Be $true
        } finally {
            Remove-Item -LiteralPath $fixture.Temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    It 'sends fixed Draft PR arguments through -GhRunner and never uses --fill' {
        $fixture = New-SyncFixture
        try {
            $result = Invoke-SyncWithSeam $fixture
            $result.Code | Should Be 0
            $ghCalls = Get-Content -LiteralPath $result.GhCalls -Raw
            ($ghCalls -match 'pr create') | Should Be $true
            ($ghCalls -match '--draft') | Should Be $true
            ($ghCalls -match '--base main') | Should Be $true
            ($ghCalls -match 'docs: refresh recent commit IDs') | Should Be $true
            ($ghCalls -match '--fill') | Should Be $false
            ($result.Output -match 'Created Draft PR: https://example.invalid/pull/1') | Should Be $true
        } finally {
            Remove-Item -LiteralPath $fixture.Temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
