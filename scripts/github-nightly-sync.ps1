#!/usr/bin/env pwsh
#requires -Version 7.0
<#
.SYNOPSIS
    MediaHub 夜间 GitHub 同步（每天 00:00 检查当天更新并同步到 GitHub + README）。

.DESCRIPTION
    流程：
      1. git fetch 后检测「当天是否有更新」= 今日提交数 > 0 或 存在本地未推送提交。
      2. 无更新 → 直接退出，不产生任何提交、不推送。
      3. 有更新 →
         - 刷新 README.md 中 <!-- MEDIAHUB:RECENT:START --> ... <!-- MEDIAHUB:RECENT:END --> 区块
           （近 N 天提交摘要，内容无变化则不提交）；
         - 若当前分支是 main/master，先切出 sync/nightly-<日期> 分支（仓库纪律：禁止直推 main）；
         - 只 `git add README.md`，绝不提交未跟踪文件或其它改动；
         - 推送分支，并确保存在对应 PR（不存在则 `gh pr create --draft --fill`）。
    日志写入 <repo>/.git/github-sync.log（不进版本库）。

.PARAMETER RepoRoot
    仓库根目录，默认为当前目录所属仓库。

.PARAMETER DryRun
    只报告将要执行的动作，不写 README、不提交、不推送。

.PARAMETER RecentDays
    README 摘要回看天数，默认 7。

.PARAMETER MaxRecentEntries
    README 摘要最多条数，默认 10。

.EXAMPLE
    pwsh -File scripts/github-nightly-sync.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$RepoRoot,
    [switch]$DryRun,
    [int]$RecentDays = 7,
    [int]$MaxRecentEntries = 10
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Invoke-Git {
    param(
        [Parameter(Mandatory)][string[]]$GitArgs,
        [switch]$AllowFail
    )
    $raw = & git -C $Repo @GitArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @($raw | ForEach-Object { "$_" })
    if ($code -ne 0 -and -not $AllowFail) {
        throw ("git {0} 失败 (exit {1}): {2}" -f ($GitArgs -join ' '), $code, ($lines -join ' / '))
    }
    return [pscustomobject]@{ Code = $code; Lines = $lines; Text = ($lines -join "`n").Trim() }
}

function Invoke-Gh {
    param(
        [Parameter(Mandatory)][string[]]$GhArgs,
        [switch]$AllowFail
    )
    $raw = & gh @GhArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @($raw | ForEach-Object { "$_" })
    if ($code -ne 0 -and -not $AllowFail) {
        throw ("gh {0} 失败 (exit {1}): {2}" -f ($GhArgs -join ' '), $code, ($lines -join ' / '))
    }
    return [pscustomobject]@{ Code = $code; Lines = $lines; Text = ($lines -join "`n").Trim() }
}

# ---------- 仓库定位 ----------
if ($RepoRoot) {
    $Repo = (Resolve-Path -LiteralPath $RepoRoot).Path
} else {
    $Repo = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
    if (-not $Repo) { throw '当前目录不在 git 仓库内，且未指定 -RepoRoot。' }
    $Repo = $Repo.Trim()
}

$LogPath = Join-Path (Join-Path $Repo '.git') 'github-sync.log'
function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "[{0}] [{1}] {2}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    try { Add-Content -LiteralPath $LogPath -Value $line -Encoding utf8 } catch { }
}

$StartStamp = Get-Date
Write-Log ("===== 夜间同步开始 (repo={0}, dryRun={1}) =====" -f $Repo, [bool]$DryRun)

# ---------- 0. 同步远端引用 ----------
[void](Invoke-Git -GitArgs @('fetch', '--prune', '--quiet', 'origin') -AllowFail)

$branch = (Invoke-Git -GitArgs @('rev-parse', '--abbrev-ref', 'HEAD')).Text
if ($branch -eq 'HEAD') { throw '当前处于 detached HEAD，无法自动同步，请先切到分支。' }
Write-Log ("当前分支: {0}" -f $branch)

# ---------- 1. 检测「当天更新」 ----------
$today = (Get-Date).Date
$sinceToday = $today.ToString('yyyy-MM-ddTHH:mm:ss')

# 本脚本自身产生的提交不算「项目更新」，也不进 README 摘要（否则每晚自增一条 → 无限提交）。
$SyncCommitGrep = '^docs\(readme\): 自动同步最近更新'

$todayCommits = @((Invoke-Git -GitArgs @('log', "--since=$sinceToday", '--invert-grep', '-E', "--grep=$SyncCommitGrep", '--pretty=format:%h|%ad|%s', '--date=short')).Lines | Where-Object { $_ })
# 只统计「当前 HEAD 上、任何远端都没有」的提交 —— 推送当前分支即可发布的内容。
$unpushedCommits = @((Invoke-Git -GitArgs @('log', 'HEAD', '--not', '--remotes', '--pretty=format:%h|%s')).Lines | Where-Object { $_ })
$dirtyTracked = @((Invoke-Git -GitArgs @('status', '--porcelain', '--untracked-files=no')).Lines | Where-Object { $_ })

Write-Log ("今日提交: {0} 条；当前分支未推送提交: {1} 条；已跟踪文件未提交改动: {2} 条" -f $todayCommits.Count, $unpushedCommits.Count, $dirtyTracked.Count)

# 其它本地分支上的未推送提交：仅提示，绝不自动推送（可能是实验分支）。
$otherUnpushed = @()
foreach ($ref in (Invoke-Git -GitArgs @('for-each-ref', '--format=%(refname:short)', 'refs/heads')).Lines) {
    if (-not $ref -or $ref -eq $branch) { continue }
    $n = @((Invoke-Git -GitArgs @('log', $ref, '--not', '--remotes', '--pretty=format:%h') -AllowFail).Lines | Where-Object { $_ }).Count
    if ($n -gt 0) { $otherUnpushed += "$ref($n)" }
}
if ($otherUnpushed.Count -gt 0) {
    Write-Log ('其它本地分支存在未推送提交（本次不动）: ' + ($otherUnpushed -join ', ')) 'WARN'
}

$hasUpdate = ($todayCommits.Count -gt 0) -or ($unpushedCommits.Count -gt 0)
if (-not $hasUpdate) {
    Write-Log '当天无更新，无需同步。' 'OK'
    if ($dirtyTracked.Count -gt 0) { Write-Log ('注意：存在未提交的已跟踪文件改动（不自动提交）: ' + (($dirtyTracked | Select-Object -First 5) -join ' | ')) 'WARN' }
    Write-Log '===== 夜间同步结束（无操作） ====='
    exit 0
}

# ---------- 2. 刷新 README 最近更新区块 ----------
$StartMarker = '<!-- MEDIAHUB:RECENT:START -->'
$EndMarker = '<!-- MEDIAHUB:RECENT:END -->'
$ReadmePath = Join-Path $Repo 'README.md'
$readmeChanged = $false

$recent = @((Invoke-Git -GitArgs @('log', "--since=$RecentDays days ago", '--invert-grep', '-E', "--grep=$SyncCommitGrep", '--pretty=format:%ad %s', '--date=short')).Lines | Where-Object { $_ } | Select-Object -First $MaxRecentEntries)
$recentLines = if ($recent.Count -gt 0) { ($recent | ForEach-Object { '- ' + $_ }) -join "`n" } else { '- （近 ' + $RecentDays + ' 天无提交）' }
$block = @(
    $StartMarker
    '## 最近更新'
    ''
    ('<sub>本区块由 scripts/github-nightly-sync.ps1 自动维护：每次同步时刷新近 {0} 天提交（最多 {1} 条）。</sub>' -f $RecentDays, $MaxRecentEntries)
    ''
    $recentLines
    $EndMarker
) -join "`n"

if (Test-Path -LiteralPath $ReadmePath) {
    $text = Get-Content -LiteralPath $ReadmePath -Raw -Encoding utf8
    $si = $text.IndexOf($StartMarker)
    $ei = $text.IndexOf($EndMarker)
    if ($si -ge 0 -and $ei -gt $si) {
        $newText = $text.Substring(0, $si) + $block + $text.Substring($ei + $EndMarker.Length)
    } else {
        $m = [regex]::Match($text, '(?m)^## ')
        if ($m.Success) {
            $newText = $text.Substring(0, $m.Index) + $block + "`n`n" + $text.Substring($m.Index)
        } else {
            $newText = $text.TrimEnd() + "`n`n" + $block + "`n"
        }
    }
    if ($newText -ne $text) {
        $readmeChanged = $true
        if ($DryRun) {
            Write-Log 'DRY-RUN：README.md 的「最近更新」区块需要刷新（未写入）。'
        } else {
            [System.IO.File]::WriteAllText($ReadmePath, $newText, (New-Object System.Text.UTF8Encoding($false)))
            Write-Log 'README.md「最近更新」区块已刷新。'
        }
    } else {
        Write-Log 'README.md「最近更新」区块无变化。'
    }
} else {
    Write-Log 'README.md 不存在，跳过区块刷新。' 'WARN'
}

# ---------- 3. 分支保护 + 提交 + 推送 + PR ----------
$stamp = $StartStamp.ToString('yyyyMMdd')
$isDefaultBranch = $branch -in @('main', 'master')
$targetBranch = $branch
$prUrl = $null

if ($DryRun) {
    if ($isDefaultBranch) { Write-Log ('DRY-RUN：当前为默认分支 {0}，实际运行会先切出 sync/nightly-{1} 再提交推送。' -f $branch, $stamp) }
    if ($readmeChanged) { Write-Log 'DRY-RUN：会执行 git add README.md && git commit（仅 README）。' }
    Write-Log ('DRY-RUN：会执行 git push origin {0}，并确保存在 PR。' -f $targetBranch)
    Write-Log '===== 夜间同步结束（dry-run，未做任何写操作） ====='
    Write-Log ('今日提交: ' + (($todayCommits | Select-Object -First 5) -join ' | '))
    exit 0
}

if ($isDefaultBranch) {
    $targetBranch = "sync/nightly-$stamp"
    if ((Invoke-Git -GitArgs @('rev-parse', '--verify', '--quiet', "refs/heads/$targetBranch") -AllowFail).Code -eq 0) {
        [void](Invoke-Git -GitArgs @('checkout', $targetBranch))
    } else {
        [void](Invoke-Git -GitArgs @('checkout', '-b', $targetBranch))
    }
    Write-Log ("已切出同步分支: {0}（禁止直推 {1}）" -f $targetBranch, $branch)
}

if ($readmeChanged) {
    [void](Invoke-Git -GitArgs @('add', '--', 'README.md'))
    $msgFile = [System.IO.Path]::GetTempFileName()
    try {
        $msg = "docs(readme): 自动同步最近更新（$($StartStamp.ToString('yyyy-MM-dd'))）"
        [System.IO.File]::WriteAllText($msgFile, $msg, (New-Object System.Text.UTF8Encoding($false)))
        [void](Invoke-Git -GitArgs @('commit', '-F', $msgFile))
        Write-Log '已提交 README 更新。'
    } finally {
        Remove-Item -LiteralPath $msgFile -Force -ErrorAction SilentlyContinue
    }
}

$hasUpstream = (Invoke-Git -GitArgs @('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}') -AllowFail).Code -eq 0
if ($hasUpstream) {
    [void](Invoke-Git -GitArgs @('push', 'origin', "HEAD:$targetBranch"))
} else {
    [void](Invoke-Git -GitArgs @('push', '-u', 'origin', $targetBranch))
}
Write-Log ("已推送到 origin/{0}" -f $targetBranch) 'OK'

# gh 默认按「当前工作目录」解析仓库，午夜运行 cwd 不可控 —— 显式绑定 origin 的 GitHub 仓库。
$originUrl = (Invoke-Git -GitArgs @('remote', 'get-url', 'origin') -AllowFail).Text
$slug = $null
if ($originUrl -match 'github\.com[:/](?<owner>[^/\s]+)/(?<repo>[^/\s]+?)(\.git)?/?$') {
    $slug = '{0}/{1}' -f $Matches['owner'], $Matches['repo']
} else {
    Write-Log ('origin 不是 GitHub 远端（{0}），跳过 PR 检查/创建。' -f $originUrl) 'WARN'
}

$prUrl = $null
if ($slug) {
    $prView = Invoke-Gh -GhArgs @('pr', 'view', $targetBranch, '--repo', $slug, '--json', 'number,url,state,isDraft') -AllowFail
    if ($prView.Code -eq 0) {
        $prUrl = ($prView.Text | ConvertFrom-Json).url
        Write-Log ("已有 PR: {0}" -f $prUrl) 'OK'
    } else {
        $defaultBranchName = (Invoke-Gh -GhArgs @('repo', 'view', $slug, '--json', 'defaultBranchRef', '--jq', '.defaultBranchRef.name') -AllowFail).Text
        if (-not $defaultBranchName) { $defaultBranchName = 'main' }
        $created = Invoke-Gh -GhArgs @('pr', 'create', '--repo', $slug, '--draft', '--fill', '--base', $defaultBranchName, '--head', $targetBranch) -AllowFail
        if ($created.Code -eq 0) {
            $prUrl = $created.Text
            Write-Log ("已创建 PR: {0}" -f $prUrl) 'OK'
        } else {
            Write-Log ('PR 创建失败（分支已推送，可手动开 PR）: ' + $created.Text) 'WARN'
        }
    }
}

Write-Log ('===== 夜间同步结束（已同步 origin/{0}） =====' -f $targetBranch) 'OK'
Write-Host ''
Write-Host ("RESULT: branch={0} todayCommits={1} pushed=True readmeChanged={2} pr={3}" -f $targetBranch, $todayCommits.Count, $readmeChanged, $prUrl)
exit 0
