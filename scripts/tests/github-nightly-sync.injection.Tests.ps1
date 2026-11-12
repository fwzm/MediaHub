<#
    Pester suite for the injectable runner seam (`-GitRunner` / `-GhRunner`).

    The seam exists so callers and tests can observe or replace the exact Git/GitHub
    invocations without weakening real behaviour. Each case runs the real script in a
    child `pwsh` against a disposable local repository; the injected Git runner records
    every argument list and then delegates to real Git, so only the seam is faked.

    Helpers are dot-sourced inside each `It`: Pester 5 only runs a test file's top-level
    code during discovery, so definitions placed there are unavailable at run time.
    Assertions throw instead of using `Should`, which changed syntax between Pester 3.4
    (pinned locally) and 5.x (shipped by CI images).
#>
Describe 'github-nightly-sync runner seam' {
    It 'routes git writes through -GitRunner and keeps the README-only commit' {
        . (Join-Path $PSScriptRoot 'github-nightly-sync.seam.ps1')
        $syncScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'github-nightly-sync.ps1'
        $fixture = New-SyncFixture
        try {
            $result = Invoke-SyncWithSeam -SyncScript $syncScript -Fixture $fixture
            Assert-True ($result.Code -eq 0) "sync exited $($result.Code): $($result.Output)"
            $calls = Get-Content -LiteralPath $result.Calls -Raw
            Assert-True ($calls -match 'commit --only -m') 'commit must keep --only'
            Assert-True ($calls -match 'push -u origin HEAD:refs/heads/codex/fixture') 'push must target the current branch'
            Assert-True ($calls -match 'log HEAD --not --remotes') 'unpushed detection must stay read-only'
            $readme = Get-Content -LiteralPath (Join-Path $fixture.Repo 'README.md') -Raw
            Assert-True ($readme -match 'MEDIAHUB:RECENT:START') 'README must gain the sync block'
        } finally {
            Remove-Item -LiteralPath $fixture.Temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    It 'sends fixed Draft PR arguments through -GhRunner and never uses --fill' {
        . (Join-Path $PSScriptRoot 'github-nightly-sync.seam.ps1')
        $syncScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'github-nightly-sync.ps1'
        $fixture = New-SyncFixture
        try {
            $result = Invoke-SyncWithSeam -SyncScript $syncScript -Fixture $fixture
            Assert-True ($result.Code -eq 0) "sync exited $($result.Code): $($result.Output)"
            $ghCalls = Get-Content -LiteralPath $result.GhCalls -Raw
            Assert-True ($ghCalls -match 'pr create') 'a Draft PR must be created'
            Assert-True ($ghCalls -match '--draft') 'the PR must stay Draft'
            Assert-True ($ghCalls -match '--base main') 'the PR must target main'
            Assert-True ($ghCalls -match 'docs: refresh recent commit IDs') 'the PR title must stay fixed'
            Assert-True (-not ($ghCalls -match '--fill')) 'the PR must never use --fill'
            Assert-True ($result.Output -match 'Created Draft PR: https://example.invalid/pull/1') 'the created PR URL must be reported'
        } finally {
            Remove-Item -LiteralPath $fixture.Temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
