<#
.SYNOPSIS
  MediaHub 每日同步：检查当日更新 -> 推送 GitHub -> 维护 README 的 windows 更新摘要块。

.DESCRIPTION
  幂等性设计（连续运行第二次起不再产生新提交）：
    * 「有更新」= 刚结束的自然日（00:00-24:00）内有提交，或当前分支存在未推送提交。
    * README 只改 <!-- WINDOWS-SYNC:START --> 与 <!-- WINDOWS-SYNC:END --> 之间的内容，
      提交时只 git add README.md 一个文件。
    * 自动同步提交不计入「更新」（--grep 排除，且摘要只统计 windows/ 路径）。
    * 摘要块内不写入运行日期，内容只随 windows/ 的真实提交变化。
  无人值守：GIT_TERMINAL_PROMPT=0、GCM_INTERACTIVE=never，绝不交互；失败返回非零码。
  安全边界：
    * 永不直接提交或推送到 main/master/HEAD，从不 force push。
    * 工作区有未提交改动、或 README 本身被手工修改时，跳过 README 自动维护（不覆盖人工编辑）。
    * 只 add README.md 一个路径，绝不 add 未跟踪文件。

.PARAMETER DryRun
  只打印将要做的事；不写文件、不提交、不推送、不建 PR。

.EXAMPLE
  pwsh -NoProfile -File scripts/sync-github-daily.ps1 -RepoRoot D:\deepseek_test\MediaHub_Win
#>
[CmdletBinding()]
param(
    [string] $RepoRoot    = '',
    [string] $Repo        = 'fwzm/MediaHub',
    [string] $Remote      = 'origin',
    [string] $BaseBranch  = 'main',
    [int]    $SummaryDays = 7,
    [string] $LogPath     = '',
    [switch] $DryRun,
    [switch] $SkipPush,
    [switch] $NoPr
)

$ErrorActionPreference = 'Stop'
$script:SyncTag = '自动同步'
$startMarker = '<!-- WINDOWS-SYNC:START -->'
$endMarker   = '<!-- WINDOWS-SYNC:END -->'
$protected   = @('main', 'master', 'HEAD')

# 无人值守上下文（任务计划程序）没有控制台，原生命令 stdout 会按 ANSI 代码页解码：
# 中文提交主题会变成乱码，且被吞掉换行 —— 2026-10-15 00:00 的计划任务运行因此把乱码块
# 写进了 README（提交 44b4a46）。这里必须在任何 git 调用之前固定 UTF-8。
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }
try { [Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false) } catch { }
try { $OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path (Join-Path $env:LOCALAPPDATA 'MediaHub') 'daily-sync.log'
}

$script:lockStream = $null
$lockPath = Join-Path (Join-Path $env:LOCALAPPDATA 'MediaHub') 'daily-sync.lock'

function Acquire-Lock {
    $dir = Split-Path -Parent $lockPath
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    try {
        $script:lockStream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        return $true
    } catch {
        $age = [TimeSpan]::Zero
        if (Test-Path -LiteralPath $lockPath) { $age = (Get-Date) - (Get-Item -LiteralPath $lockPath).LastWriteTime }
        if ($age.TotalMinutes -gt 30) {
            Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
            try {
                $script:lockStream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                return $true
            } catch { return $false }
        }
        return $false
    }
}

function Release-Lock {
    if ($script:lockStream) { try { $script:lockStream.Dispose() } catch { }; $script:lockStream = $null }
    Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
}

function Write-Log {
    param([string] $Message, [string] $Level = 'INFO')
    $line = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' [' + $Level + '] ' + $Message
    try {
        $dir = Split-Path -Parent $LogPath
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -LiteralPath $LogPath -Value $line -Encoding utf8
    } catch { }
    Write-Host $line
}

function Invoke-Git {
    param([string[]] $GitArgs, [switch] $AllowFail)
    $prev = $env:GIT_TERMINAL_PROMPT
    $env:GIT_TERMINAL_PROMPT = '0'
    try {
        $out  = & git -C $RepoRoot @GitArgs 2>&1
        $code = $LASTEXITCODE
    } finally { $env:GIT_TERMINAL_PROMPT = $prev }
    $lines = @($out | ForEach-Object { [string]$_ })
    if ($code -ne 0 -and -not $AllowFail) {
        throw ('git ' + ($GitArgs -join ' ') + ' 失败 (exit ' + $code + '): ' + ($lines -join ' | '))
    }
    return [pscustomobject]@{ Code = $code; Lines = $lines }
}

function Get-Text {
    param([object] $Result)
    return (@($Result.Lines | Where-Object { $_ -and $_.Trim() }) -join [Environment]::NewLine)
}

function Has-Output {
    param([object] $Result)
    if (-not $Result) { return $false }
    return (@($Result.Lines | Where-Object { $_ -and $_.Trim() }).Count -gt 0)
}

function Build-SummaryBlock {
    # 用 git --output=<文件> 落盘后再以 UTF-8 读取：绕开控制台代码页，主题里的中文/换行不会损坏。
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ('mh-sync-' + [guid]::NewGuid().ToString('N') + '.txt')
    $rows = @()
    try {
        $gitArgs = @('log', ('--since=' + $SummaryDays + ' days ago'), '--pretty=format:%h %ad %s',
                     '--date=short', '--invert-grep', ('--grep=' + $script:SyncTag),
                     ('--output=' + $tmp), '--', 'windows/')
        $res = Invoke-Git -GitArgs $gitArgs -AllowFail
        if ($res.Code -eq 0 -and (Test-Path -LiteralPath $tmp)) {
            foreach ($l in [IO.File]::ReadAllLines($tmp, [System.Text.Encoding]::UTF8)) {
                $t = ([string]$l).Trim()
                if ([string]::IsNullOrWhiteSpace($t)) { continue }
                $rows += ('- ' + $t)
                if ($rows.Count -ge 8) { break }
            }
        } else {
            Write-Log ('读取 windows/ 提交历史失败：' + ($res.Lines -join ' ')) 'WARN'
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
    if ($rows.Count -eq 0) { $rows = @('- （最近 ' + $SummaryDays + ' 天 windows/ 无提交）') }
    $header = '<!-- 由 scripts/sync-github-daily.ps1 自动生成：windows/ 最近 ' + $SummaryDays + ' 天提交。请勿手工编辑块内内容。 -->'
    return (@($header) + $rows) -join [Environment]::NewLine
}

function Get-ReadmePlan {
    $readmePath = Join-Path $RepoRoot 'README.md'
    if (-not (Test-Path -LiteralPath $readmePath)) { Write-Log 'README.md 不存在，跳过 README 维护' 'WARN'; return $null }
    $text = Get-Content -LiteralPath $readmePath -Raw
    $si = $text.IndexOf($startMarker)
    $ei = $text.IndexOf($endMarker)
    if ($si -lt 0 -or $ei -lt 0 -or $ei -lt $si) {
        Write-Log ('README.md 没有 ' + $startMarker + ' 标记块（当前分支未启用自动摘要），跳过 README 维护')
        return $null
    }
    $block   = Build-SummaryBlock
    $newText = $text.Substring(0, $si + $startMarker.Length) + [Environment]::NewLine + $block +
               [Environment]::NewLine + $text.Substring($ei)
    return [pscustomobject]@{ Path = $readmePath; Old = $text; New = $newText; Block = $block; Changed = ($newText -ne $text) }
}

function Ensure-PullRequest {
    param([string] $BranchName)
    if ($NoPr) { Write-Log 'NoPr 已指定，跳过 PR 处理'; return $null }
    if ($BranchName -in $protected) { Write-Log ('分支 ' + $BranchName + ' 受保护，不建 PR') 'WARN'; return $null }
    $view = @(& gh pr view $BranchName --repo $Repo --json number,url,state 2>&1)
    if ($LASTEXITCODE -eq 0) {
        Write-Log ('PR 已存在：' + ($view -join ' '))
        return ($view -join ' ')
    }
    $title = 'docs(readme): 自动同步 Windows 客户端最近更新（' + (Get-Date).ToString('yyyy-MM-dd') + '）'
    $body  = '自动同步：更新 README 中 windows/ 的最近提交摘要块。' + [Environment]::NewLine + [Environment]::NewLine +
             '本 PR 由 scripts/sync-github-daily.ps1 生成，只修改 README.md 一个文件。' + [Environment]::NewLine +
             '合并前请核对摘要与 windows/ 的实际变更一致；未合并的 Draft 分支内容不得写成已发布现状。'
    $create = @(& gh pr create --repo $Repo --base $BaseBranch --head $BranchName --title $title --body $body 2>&1)
    if ($LASTEXITCODE -ne 0) { Write-Log ('gh pr create 失败：' + ($create -join ' ')) 'WARN'; return $null }
    Write-Log ('已创建 PR：' + ($create -join ' '))
    return ($create -join ' ')
}

$result = [ordered]@{
    date = (Get-Date).ToString('yyyy-MM-dd'); branch = ''; dayCommits = 0; unpushed = 0
    readmeChanged = $false; committed = $false; pushed = $false; pr = $null; action = 'none'; notes = ''
}

try {
    Write-Log ('开始：RepoRoot=' + $RepoRoot + ' Remote=' + $Remote + ' DryRun=' + [bool]$DryRun)
    if (-not (Acquire-Lock)) {
        Write-Log '另一个同步进程正在运行（锁被占用），本次直接退出' 'WARN'
        exit 0
    }
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:GCM_INTERACTIVE = 'never'

    $cur = (Invoke-Git -GitArgs @('rev-parse', '--abbrev-ref', 'HEAD')).Lines[0].Trim()
    $result.branch = $cur
    Invoke-Git -GitArgs @('fetch', $Remote, '--prune', '--quiet') -AllowFail | Out-Null

    $dayStart = (Get-Date).Date.AddDays(-1).ToString('yyyy-MM-dd HH:mm:ss')
    $dayEnd   = (Get-Date).Date.ToString('yyyy-MM-dd HH:mm:ss')
    $dayRes   = Invoke-Git -GitArgs @('log', ('--since=' + $dayStart), ('--until=' + $dayEnd),
                                      '--pretty=format:%h %ad %s', '--date=iso',
                                      '--invert-grep', ('--grep=' + $script:SyncTag)) -AllowFail
    $result.dayCommits = @($dayRes.Lines | Where-Object { $_ -and $_.Trim() }).Count

    $remoteRef = $Remote + '/' + $cur
    $refOk = (Invoke-Git -GitArgs @('rev-parse', '--verify', '--quiet', $remoteRef) -AllowFail).Code -eq 0
    if ($refOk) {
        $result.unpushed = [int](Get-Text (Invoke-Git -GitArgs @('rev-list', '--count', ($remoteRef + '..HEAD'))))
    } else {
        $result.unpushed = [int](Get-Text (Invoke-Git -GitArgs @('rev-list', '--count', 'HEAD')))
        Write-Log ('远端分支 ' + $remoteRef + ' 不存在，按全部本地提交计未推送')
    }
    Write-Log ('分支=' + $cur + ' 当日提交=' + $result.dayCommits + ' 未推送=' + $result.unpushed)

    $dirty       = Has-Output (Invoke-Git -GitArgs @('status', '--porcelain', '--untracked-files=no'))
    $readmeDirty = Has-Output (Invoke-Git -GitArgs @('status', '--porcelain', '--', 'README.md'))
    $plan = Get-ReadmePlan
    $readmeChanged = [bool]($plan -and $plan.Changed)
    $result.readmeChanged = $readmeChanged

    $hasUpdate = ($result.dayCommits -gt 0) -or ($result.unpushed -gt 0)
    if (-not $hasUpdate -and -not $readmeChanged) {
        $result.action = 'no-update'
        Write-Log '今日无更新：未推送、未改 README（零写入）'
        [Environment]::NewLine + ($result | ConvertTo-Json -Compress) | Write-Output
        exit 0
    }

    $didCommit = $false
    if ($readmeChanged) {
        if ($DryRun) {
            Write-Log 'DryRun：将更新 README 摘要块，内容如下'
            Write-Host (Build-SummaryBlock)
        } elseif ($cur -in $protected) {
            $result.notes += '在受保护分支，跳过 README 提交;'
            Write-Log ('当前在受保护分支 ' + $cur + '，不提交 README（需人工在 feature 分支处理）') 'WARN'
        } elseif ($dirty) {
            $result.notes += '工作区有未提交改动，跳过 README 提交;'
            Write-Log '工作区有未提交改动，跳过 README 自动维护（不覆盖人工编辑）' 'WARN'
        } else {
            Set-Content -LiteralPath $plan.Path -Value $plan.New -NoNewline -Encoding utf8
            # 写回后自检：块内容必须与预期逐字符一致，否则还原原文件并放弃本次提交。
            $written = Get-Content -LiteralPath $plan.Path -Raw
            $wsi = $written.IndexOf($startMarker)
            $wei = $written.IndexOf($endMarker)
            $wblock = ''
            if ($wsi -ge 0 -and $wei -gt $wsi) {
                $wblock = $written.Substring($wsi + $startMarker.Length, $wei - $wsi - $startMarker.Length).Trim([char]13, [char]10)
            }
            if ($wblock -ne $plan.Block) {
                Set-Content -LiteralPath $plan.Path -Value $plan.Old -NoNewline -Encoding utf8
                throw 'README 标记块写回后与预期不一致（疑似编码或换行损坏）；已还原原文件并放弃提交'
            }
            Invoke-Git -GitArgs @('add', '--', 'README.md') | Out-Null
            $staged = @((Invoke-Git -GitArgs @('diff', '--cached', '--name-only')).Lines |
                        Where-Object { $_ -and $_.Trim() } | ForEach-Object { $_.Trim() })
            if ($staged.Count -ne 1 -or $staged[0] -ne 'README.md') {
                throw ('暂存区只应包含 README.md，实际：' + ($staged -join ', '))
            }
            $msg = 'docs(readme): ' + $script:SyncTag + ' Windows 客户端最近更新（' + (Get-Date).ToString('yyyy-MM-dd') + '）'
            Invoke-Git -GitArgs @('commit', '--no-verify', '-m', $msg) | Out-Null
            # 只回读哈希（ASCII），主题用脚本自己构造的字符串，避免再从 git stdout 取中文
            Write-Log ('已提交：' + (Get-Text (Invoke-Git -GitArgs @('log', '-1', '--pretty=format:%h'))) + ' ' + $msg)
            $didCommit = $true
            $result.committed = $true
        }
    } else {
        Write-Log 'README 摘要块无变化，不提交'
    }

    $needPush = ($result.unpushed -gt 0) -or $didCommit
    if (-not $needPush) {
        Write-Log '无需要推送的内容'
    } elseif ($SkipPush) {
        Write-Log 'SkipPush 已指定，未推送' 'WARN'
    } elseif ($cur -in $protected) {
        Write-Log ('拒绝推送到受保护分支 ' + $cur) 'WARN'
    } elseif ($DryRun) {
        Write-Log ('DryRun：将执行 git push ' + $Remote + ' ' + $cur)
    } else {
        $push = Invoke-Git -GitArgs @('push', $Remote, $cur) -AllowFail
        if ($push.Code -ne 0) {
            Write-Log ('push 被拒，尝试 pull --rebase 后重试：' + ($push.Lines -join ' ')) 'WARN'
            $rb = Invoke-Git -GitArgs @('pull', '--rebase', $Remote, $cur) -AllowFail
            if ($rb.Code -ne 0) { throw ('pull --rebase 失败，放弃推送：' + ($rb.Lines -join ' ')) }
            $push = Invoke-Git -GitArgs @('push', $Remote, $cur) -AllowFail
            if ($push.Code -ne 0) { throw ('push 重试仍失败：' + ($push.Lines -join ' ')) }
        }
        $result.pushed = $true
        Write-Log ('已推送：' + $cur + ' -> ' + $Remote)
    }

    if (-not $DryRun -and ($didCommit -or $result.pushed)) { $result.pr = Ensure-PullRequest -BranchName $cur }
    $result.action = if ($result.pushed) { 'synced' } else { 'no-push' }
    Write-Log ('完成：action=' + $result.action + ' 分支=' + $cur + ' PR=' + $result.pr)
    [Environment]::NewLine + ($result | ConvertTo-Json -Compress) | Write-Output
    exit 0
} catch {
    Write-Log ('失败：' + $_.Exception.Message) 'ERROR'
    Release-Lock
    Write-Log ((Get-PSCallStack | Select-Object -First 4 | ForEach-Object { $_.Command + ':' + $_.ScriptLineNumber }) -join ' <- ') 'ERROR'
    exit 1
} finally {
    Release-Lock
}
