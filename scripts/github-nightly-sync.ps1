#requires -Version 7.0
<#
.SYNOPSIS
    手动预览最近更新；显式 -Apply 才写 README、提交和推送并建立 Draft PR。
.DESCRIPTION
    默认与 -DryRun 均只读，不 fetch、不写检查点或锁。未安装任何定时任务。
    Apply 拒绝已有暂存/已跟踪改动，保护 main/master；仅提交 README。
    首次回看 24 小时，后续从成功检查点起算，排除本脚本产生的提交。
#>
[CmdletBinding()]
param(
    [string]$RepoRoot,
    [switch]$Apply,
    [switch]$DryRun,
    [ValidateRange(1,365)][int]$RecentDays = 7,
    [ValidateRange(1,100)][int]$MaxRecentEntries = 10
)
$ErrorActionPreference = 'Stop'
if ($Apply -and $DryRun) { throw '-Apply 与 -DryRun 不能同时使用。' }
$preview = -not $Apply
function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$GitArgs, [switch]$AllowFail)
    $previousLocks = $env:GIT_OPTIONAL_LOCKS
    try {
        if ($preview) { $env:GIT_OPTIONAL_LOCKS = '0' }
        $output = @(& git -C $Repo @GitArgs 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally { $env:GIT_OPTIONAL_LOCKS = $previousLocks }
    if ($code -ne 0 -and -not $AllowFail) {
        throw "git $($GitArgs[0]) failed (exit $code)."
    }
    [pscustomobject]@{ Code=$code; Lines=$output; Text=($output -join "`n").Trim() }
}
if ($RepoRoot) { $Repo = (Resolve-Path -LiteralPath $RepoRoot).Path }
else {
    $Repo = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0) { throw '请指定 -RepoRoot。' }
}
$gitDir = (Invoke-Git -GitArgs @('rev-parse','--absolute-git-dir')).Text
$markerPath = Join-Path $gitDir 'github-sync-last-check'
$lockPath = Join-Path $gitDir 'github-sync.lock'
$logPath = Join-Path $gitDir 'github-sync.log'
$started = Get-Date
$lockHandle = $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Write-SyncLog([string]$Message) {
    Write-Host $Message
    if (-not $preview) { Add-Content -LiteralPath $logPath -Value $Message -Encoding utf8 }
}
try {
    if (-not $preview) {
        # Keep the exclusive handle alive: an old timestamp never evicts a live owner.
        try {
            $lockHandle = [System.IO.File]::Open($lockPath, 'OpenOrCreate', 'Write', 'None')
        } catch [System.IO.IOException] { throw '另一个同步实例持有锁，本次停止。' }
    }
    $branch = (Invoke-Git -GitArgs @('symbolic-ref','--quiet','--short','HEAD')).Text
    if (-not $branch) { throw 'detached HEAD 不能自动同步。' }
    $dirty = (Invoke-Git -GitArgs @('status','--porcelain','--untracked-files=no')).Text
    if (-not $preview -and $dirty) { throw '已有暂存或已跟踪改动，请先自行处理；同步未写 README。' }
    if (-not $preview) { [void](Invoke-Git -GitArgs @('fetch','--quiet','origin')) }
    $since = $started.AddHours(-24)
    if (Test-Path -LiteralPath $markerPath) {
        $parsed = [datetime]::MinValue
        $raw = Get-Content -LiteralPath $markerPath -Raw
        # A corrupt or future checkpoint must not hide newly committed work.
        if ([datetime]::TryParse($raw.Trim(), [ref]$parsed) -and $parsed -le $started) { $since = $parsed }
    }
    $syncGrep = '^docs\(readme\): 自动同步最近更新'
    $window = @((Invoke-Git -GitArgs @('log',"--since=$($since.ToString('o'))",'--invert-grep','-E',"--grep=$syncGrep",'--format=%h')).Lines | Where-Object { $_ })
    $unpushed = @((Invoke-Git -GitArgs @('log','HEAD','--not','--remotes','--format=%h')).Lines | Where-Object { $_ })
    Write-SyncLog "Window commits=$($window.Count); unpushed=$($unpushed.Count); preview=$preview"
    if ($window.Count -eq 0 -and $unpushed.Count -eq 0) {
        if (-not $preview) { [System.IO.File]::WriteAllText($markerPath,$started.ToString('o'),$utf8) }
        Write-SyncLog '窗口内无更新。'
        exit 0
    }
    $readmePath = Join-Path $Repo 'README.md'
    if (-not (Test-Path -LiteralPath $readmePath)) { throw 'README.md 缺失，同步停止。' }
    if ((Invoke-Git -GitArgs @('ls-files','--error-unmatch','--','README.md') -AllowFail).Code -ne 0) {
        throw 'README.md 尚未跟踪，同步停止，保留原文件。'
    }
    # Only dates and immutable IDs enter README; commit subjects can contain secrets.
    $recent = @((Invoke-Git -GitArgs @('log',"--since=$RecentDays days ago",'--invert-grep','-E',"--grep=$syncGrep",'--format=%ad %h','--date=short',"-n$MaxRecentEntries")).Lines | Where-Object { $_ })
    $startMarker = '<!-- MEDIAHUB:RECENT:START -->'
    $endMarker = '<!-- MEDIAHUB:RECENT:END -->'
    $entries = if ($recent.Count) { ($recent | ForEach-Object { '- '+$_ }) -join "`n" } else { '- 窗口内无提交' }
    $block = "$startMarker`n## 最近更新`n`n$entries`n$endMarker"
    $original = [System.IO.File]::ReadAllText($readmePath,$utf8)
    $startIndex = $original.IndexOf($startMarker)
    $endIndex = $original.IndexOf($endMarker)
    if ($startIndex -ge 0 -and $endIndex -gt $startIndex) {
        $updated = $original.Substring(0,$startIndex)+$block+$original.Substring($endIndex+$endMarker.Length)
    } elseif ($startIndex -ge 0 -or $endIndex -ge 0) { throw 'README 同步区块不完整，请先修复。' }
    else { $updated = $original.TrimEnd()+"`n`n"+$block+"`n" }
    if ($preview) {
        Write-SyncLog "README would change=$($original -ne $updated); no files, refs or remote changes."
        exit 0
    }
    if ($branch -in @('main','master')) {
        $branch = "codex/nightly-$($started.ToString('yyyyMMdd-HHmmss'))-$PID"
        [void](Invoke-Git -GitArgs @('switch','-c',$branch))
    }
    if ($original -ne $updated) {
        [System.IO.File]::WriteAllText($readmePath,$updated,$utf8)
        # --only prevents accidental inclusion of another staged path.
        [void](Invoke-Git -GitArgs @('commit','--only','-m',"docs(readme): 自动同步最近更新（$($started.ToString('yyyy-MM-dd'))）",'--','README.md'))
    }
    [void](Invoke-Git -GitArgs @('push','-u','origin',"HEAD:refs/heads/$branch"))
    $originUrl = (Invoke-Git -GitArgs @('remote','get-url','origin')).Text
    if ($originUrl -match 'github\.com[:/](?<owner>[^/\s:]+)/(?<repo>[^/\s]+?)(\.git)?/?$') {
        $slug = "$($Matches['owner'])/$($Matches['repo'])"
        $existing = & gh pr list --repo $slug --state open --head $branch --json url --jq '.[0].url'
        if ($LASTEXITCODE -ne 0) { throw '无法查询 Draft PR；分支已推送，检查点未推进。' }
        if ($existing) { Write-SyncLog "Existing PR: $existing" }
        else {
            # Fixed body avoids --fill copying private commit messages into a PR.
            $created = & gh pr create --repo $slug --draft --base main --head $branch --title 'docs: refresh recent commit IDs' --body 'Refresh README recent commit dates and IDs. No merge or main write was performed. Review the branch diff before use.'
            if ($LASTEXITCODE -ne 0) { throw 'Draft PR 创建失败；分支已推送，检查点未推进。' }
            Write-SyncLog "Created Draft PR: $created"
        }
    } else { Write-SyncLog '非 GitHub 远端，跳过 PR 操作。' }
    [System.IO.File]::WriteAllText($markerPath,$started.ToString('o'),$utf8)
    Write-SyncLog "Published branch=$branch; main unchanged."
} finally { if ($null -ne $lockHandle) { $lockHandle.Dispose() } }
